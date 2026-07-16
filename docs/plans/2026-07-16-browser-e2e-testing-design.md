# Browser E2E Testing Design(Playwright 全鏈路測試)

日期:2026-07-16(同日修訂 v2:test case 依程式碼查證重寫,詳 §5)
狀態:設計已核可(Yuan;v2 修訂範圍與案例亦經 Yuan 裁決)
範圍:跨 repo——後端 `java/stock-web-v2` 與前端 `vue/stock-v2`

## 1. 背景與目標

前後端各自有 CI,但缺少「真瀏覽器 → 真 HTTP → 真後端 → 暫時 DB」的驗證環節:

- 後端現有 `-Pe2e`(`*E2E.java`)是 in-process MockMvc 測試,不經真實 HTTP、無瀏覽器。
- 前端只有 Vitest(jsdom)單元測試,無 Playwright。
- 唯有真瀏覽器 E2E 能抓到的問題:cookie 沒帶、CSRF 沒過、proxy/baseURL 錯、CORS、路由守衛接線。

**定位(已決策)**:冒煙級核心路徑(13 條)起步,每次 PR 都跑;架構第一天就預留 tag 機制(`@smoke` grep)。「改 A 壞 B」的廣域迴歸主力仍是 API 契約測試與既有分層;瀏覽器 E2E 專注「使用者關鍵旅程有沒有真的通」。之後每次迴歸被 E2E 漏掉,就把該路徑補進案例,讓迴歸網跟著真實踩坑長大。

## 2. 已決策事項(含捨棄方案)

| 決策 | 選擇 | 捨棄方案與理由 |
|------|------|----------------|
| Suite 位置 | 前端 repo `vue-app/e2e/` | 後端 repo(改 UI 壞 selector 要跨 repo 修)、第三 repo(多養一個 repo 不划算) |
| CI 觸發 | 前後端 CI 都加 `browser-e2e` job,共用一份 script | 只在單邊觸發(另一邊改壞契約時抓不到) |
| 後端啟動 | 雙 repo checkout + `mvnw package` + 直接跑 bootJar | 發佈 Docker image(第二階段優化)、全家桶 compose(build 慢) |
| 暫時 DB | docker compose 起全新 TimescaleDB/Redis/Kafka,版本對齊 `ContainerIT` | 重用 Testcontainers(Playwright 在 Node 端,無法共用 JVM 生命週期) |
| 測試資料隔離 | 每條測試以 API 註冊唯一帳號;資產靠 Flyway seed | 固定 seed 帳號(測試互踩)、每測 truncate(慢、不能平行) |
| 瀏覽器 | 第一批只跑 Chromium | 多瀏覽器矩陣(價值低於維護成本,之後再加) |
| Phase 1 範圍(v2) | 旅程 A(Auth)+ D(Backtest)——前端 API mode 已接線的兩個旅程 | 四旅程一次到位(Markets/Trades/Positions 前端仍是 mock,無法真 E2E) |
| 旅程 B/C 時機(v2) | 綁 mock-to-real 接線:每接一頁真 API,同 PR 配套該頁 E2E 案例 | 先做接線再做 E2E(範圍爆炸);E2E 反過來作為接線的驗收標準 |
| Watchlist(v2) | 移出本計畫,列功能 backlog | 後端無 Watchlist API(僅權限枚舉),無從測起 |
| 後端對測版本(v2) | `develop`;前置條件:先把 security batch-1~3 分支合回 develop(Yuan 走 PR 流程) | 對落後的 develop 現狀測(近期 security/market-data 工作不在覆蓋內,價值打折) |

註:新註冊帳號的 `USER` role 已具備 `WATCHLIST_MANAGE`/`TRADE_EXECUTE`/`PORTFOLIO_VIEW`(`stock-common` `Role.java`),四條旅程無需 seed 帳號。

## 3. 架構

### 3.1 目錄結構(前端 repo)

```
vue/stock-v2/vue-app/
├── e2e/
│   ├── docker-compose.yml      # timescale/timescaledb:2.17.2-pg16、redis:7.4-alpine、cp-kafka:7.6.0
│   ├── run-e2e.(sh|ps1)        # 一鍵:compose up → 後端 build+run → health 等待 → playwright test
│   ├── tests/
│   │   ├── auth.spec.ts        # 旅程 A(Phase 1)
│   │   ├── backtest.spec.ts    # 旅程 D(Phase 1)
│   │   ├── markets.spec.ts     # 旅程 B(Phase 2,綁接線 PR)
│   │   └── trading.spec.ts     # 旅程 C(Phase 2,綁接線 PR)
│   └── support/
│       ├── fixtures.ts         # 唯一帳號註冊(API request context)、登入 helper
│       └── selectors.md        # data-testid 命名規範
├── playwright.config.ts
└── package.json                # 新增 @playwright/test、test:e2e script
```

### 3.2 執行流程(本地與 CI 同一份 script)

1. `docker compose up -d` 起 infra;等待健康。
2. 後端:`mvnw -pl stock-start -am package -DskipTests` → `java -jar ... --spring.profiles.active=e2e-browser`(Flyway migrate + seed,port 8080)→ 等 `/actuator/health` UP(timeout 90s,失敗 dump compose logs 後 fail-fast)。
3. 前端:`VITE_DATA_MODE=api npm run build` → Playwright `webServer` 跑 `vite preview`,preview proxy 把 `/api`、`/ws` 轉至 `localhost:8080`(前端 apiClient 用相對路徑 `/api/v1`,同源代理避免 cookie/CORS 折騰)。
4. `npx playwright test`。

### 3.3 後端新 profile `e2e-browser`

新增 `stock-start/src/main/resources/application-e2e-browser.yaml`(正式 resources,因為是外部啟動非測試 classpath):

- 承襲 `e2e` profile 精神:batch job 停用、JWT TTL 放寬。
- 差異:`market-data.scheduling/ingestor` **開啟**(mock provider 持續產 tick,畫面才有活資料)。
- datasource/redis/kafka 連線指向 compose 的固定 port(由 script 以環境變數注入)。

### 3.4 CI 編排

| Repo | 觸發點 | 做法 |
|------|--------|------|
| 前端 `vue/stock-v2` | PR/push → develop | 新 job `browser-e2e`(needs: frontend):checkout 自己 + checkout 後端 `develop`(後端 remote 無 main) |
| 後端 `stock-web-v2` | 現有 pipeline 尾端(needs: e2e) | 新 job `browser-e2e`:checkout 自己 + checkout 前端 `develop` |

- 跨 repo checkout:`actions/checkout` 第二個 path;私有 repo 需 PAT 或 deploy key(GitHub secret)。
- 邏輯集中在前端 repo 的 `run-e2e` script,兩邊 YAML 保持薄。
- 預估 job 時間 8–10 分鐘(Maven/npm cache 熱了之後)。

## 4. 測試基礎

- **資料隔離**:DB 每 run 全新;測試間以「API 直接註冊唯一帳號」隔離(`e2e+<runId>-<n>@test.local`),可安全平行。只有旅程 A 走 UI 註冊。
- **Selector 規範**:一律 `data-testid`;禁止 CSS class 與文字 selector(i18n 會壞)。前端頁面需補 testid——列入實作工作。
- **等待策略**:只用 auto-wait 與 `expect.poll`/`toPass`;禁止 `waitForTimeout`。
- **穩定性**:CI `retries: 1`、`trace: on-first-retry`、workers 4;斷言結構/存在性,不斷言 mock 行情數值。
- **登入加速**:第一批每測試登入即可;量大後再引入 `storageState`。

## 5. Test Case 矩陣(v2,依程式碼查證重寫)

v1 的 13 條案例有數項錯誤假設,已依後端程式碼逐項查證修正:

- 前端 API mode 僅接線 auth/aiAccess/backtest/ops(`pageApiClients.ts`)→ 旅程 B/C 延後、綁接線進度
- 後端無 Watchlist API → 整旅程移除
- 回測請求無起訖日欄位(`CreateBacktestRunRequest`:strategyId/symbol/period/initialCapital/currency/benchmark/dataMode)→ v1 D2 不成立
- 交易為記錄式 API(`CreateTradeRequest`:price 自填、quantity `@DecimalMin(0.00000001)` → 小數股合法)
- 超賣 → `TRADE_INSUFFICIENT_HOLDING`;全賣 → 持倉歸零且 avgCost 歸 0(`HoldingCalculator.applySell`)
- email 正規化 trim+lowercase;重複 → `DuplicateResourceException`;登入失敗鎖定 → `AUTH_ACCOUNT_LOCKED`(`LoginAttemptService`,門檻/時長可設定)

**分層原則**:E2E 只收「必須真瀏覽器才能驗的接線」與「關鍵使用者旅程」;純輸入驗證邊界值下放 API 層 IT;純前端顯示邏輯歸 Vitest。

### 旅程 A:註冊/登入/Session(Phase 1,前後端已接線)

| ID | 類別 | 案例 | 預期(程式碼證據) | 歸屬 |
|----|------|------|--------------------|------|
| A1 | 正常 | UI 註冊 → 自動登入,Header 顯示使用者 | cookie 設置、`/me` 解析 | E2E @smoke |
| A2 | 正常 | 登入 → reload → session 仍在 | HttpOnly cookie + `/me` 開機恢復 | E2E @smoke |
| A3 | 正常 | 登出 → 回未登入態 | 清 cookie、狀態重置 | E2E @smoke |
| A4 | 邊界 | 密碼恰 8 碼(含大小寫+數字)成功;7 碼欄位錯誤 | regex `^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{8,}$` | 組合 → API 層;E2E 留一條「欄位錯誤有顯示」@extended |
| A5 | 邊界 | email 混大小寫+空白註冊 → 以純小寫登入成功 | `normalizeEmail` trim+lowercase | E2E @extended |
| A6 | 邊界 | username 3/50 字邊界 | `@Size(min=3, max=50)` | API 層 |
| A7 | 異常 | 錯誤密碼 → 友善錯誤 + `error.code`/`traceId` 可見 | `AUTH_INVALID_CREDENTIALS` | E2E @smoke |
| A8 | 異常 | 重複 email 註冊 → 欄位級錯誤 | `DuplicateResourceException("email")` | E2E @extended(username 重複 → API 層) |
| A9 | 異常 | 未登入訪問受保護頁 → 導向登入/AuthPanel | 路由守衛 + 401 | E2E @smoke |
| A10 | 異常 | 連續錯密碼達門檻 → 鎖定,正確密碼也被拒 | `AUTH_ACCOUNT_LOCKED`(profile 設低門檻+短時長) | E2E @extended |
| A11 | 異常 | refresh token 撤銷後操作 → 一次重試失敗 → 回未登入 | `AUTH_REFRESH_TOKEN_INVALID` + 清 cookie | E2E @extended |

### 旅程 D:回測(Phase 1,前後端已接線)

| ID | 類別 | 案例 | 預期 | 歸屬 |
|----|------|------|------|------|
| D1 | 正常 | 建立回測 → `expect.poll` 至完成 → 結果區塊顯示 | 非同步 job 全鏈,驗結構不驗數值 | E2E @smoke |
| D2 | 正常 | 完成後 reload → 歷史紀錄仍在 | 落 DB 驗證(依 Backtest 頁 UI 現況) | E2E @smoke |
| D3 | 邊界 | `initialCapital` 極小值(0.01)成功 | `@DecimalMin(0.0, exclusive)` | API 層 |
| D4 | 異常 | `initialCapital` = 0/負 → 欄位驗證錯誤顯示 | 同上 | E2E @extended |
| D5 | 異常 | 不存在 symbol → 業務錯誤顯示 | symbol 經 AssetFacade 檢查 | E2E @extended |
| D6 | 異常 | 執行中重複點執行 → disable/防抖,不重複建 job | 前端行為 | E2E @extended 或 Vitest |

### 旅程 B:行情瀏覽(Phase 2,綁 Markets/Chart 接線 PR)

B1 seed 列表(≥19 筆,正常)、B2 搜尋 NVDA → Chart(正常)、B3 查無結果空狀態(邊界)、B4 特殊字元/超長搜尋不崩潰(邊界)、B5 不存在 symbol 的 Chart URL 不白屏(異常)。

### 旅程 C:交易與持倉(Phase 2,綁 Trades/Positions 接線 PR)

C1 記錄買入 → Trades/Positions → reload 仍在(正常,未來 @smoke 核心)、C2 部分賣出 → 持倉遞減(正常)、C3 全數賣出 → 歸零且 avgCost 歸 0(邊界)、C4 小數股 0.5 → 成功(邊界)、C5 超賣 → `TRADE_INSUFFICIENT_HOLDING` 錯誤顯示(異常)、C6 數量 0/負 → 驗證錯誤(異常)、C7 note 500/501 字(邊界 → API 層)、C8 不存在 symbol(異常 → API 層)。

### 已知 findings(不塞進 E2E,另行處理)

1. `CreateTradeRequest` 的 `quantity`/`price` 無上限驗證 → 極大值可通過 validation,可能撞 DB numeric 精度;建議 API 層測試並修 validation。
2. `origin/develop` 落後 security batch-1~3 分支 → 已裁決先合併(本設計前置條件)。

原則:每條案例自帶帳號、不依賴執行順序(同旅程內狀態接續者同檔 serial);Phase 1 @smoke 共 7 條(A1–A3、A7、A9、D1、D2),@extended 共 7 條。

## 6. 失敗處理與可除錯性

- 失敗上傳 artifacts:Playwright HTML report、trace、screenshot、**後端 stdout log**(UI 錯誤畫面顯示 `traceId`,可對後端 log)。
- 連續 flaky 案例記入 `ai-docs/bug-reports/LESSONS.md`,修復或隔離,不放任 retry 掩蓋。
- 啟動失敗 fail-fast:health 等待 timeout 90s,超時 dump compose logs。
- 本地:`npm run test:e2e` 一鍵跑完整流程;Windows 提供 PowerShell 對應。

## 7. 實作工作分佈(供 writing-plans 拆解)

**後端 repo:**
1. `application-e2e-browser.yaml` profile。
2. CI 新增 `browser-e2e` job(checkout 前端 develop)。

**前端 repo:**
1. Playwright 依賴、`playwright.config.ts`(webServer + preview proxy)。
2. `e2e/docker-compose.yml` 與 `run-e2e` script。
3. 頁面補 `data-testid`。
4. Phase 1 測試案例:旅程 A + D 共 14 條 E2E(7 @smoke + 7 @extended;TDD:先寫案例對現況跑紅,再補 testid/接線讓其轉綠)。
5. CI 新增 `browser-e2e` job(checkout 後端 develop)。
6. Phase 2(另立 PR,綁 mock-to-real 進度):Markets/Chart 接線 + 旅程 B 案例;Trades/Positions 接線 + 旅程 C 案例。

**跨 repo 前置:**
- cross-repo checkout 用的 PAT/deploy key secret(需 Yuan 在 GitHub 設定)。
- security batch-1~3 分支合回 develop(Yuan 走 PR 流程;E2E 對 develop 測才有意義)。
- API 層補測:旅程矩陣中歸屬「API 層」的邊界案例(A4 組合、A6、D3、C7、C8)與 finding #1(quantity/price 上限)在後端 `*E2E.java`/IT 層補齊。
