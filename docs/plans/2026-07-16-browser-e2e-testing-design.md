# Browser E2E Testing Design(Playwright 全鏈路測試)

日期:2026-07-16
狀態:設計已核可(Yuan)
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

註:新註冊帳號的 `USER` role 已具備 `WATCHLIST_MANAGE`/`TRADE_EXECUTE`/`PORTFOLIO_VIEW`(`stock-common` `Role.java`),四條旅程無需 seed 帳號。

## 3. 架構

### 3.1 目錄結構(前端 repo)

```
vue/stock-v2/vue-app/
├── e2e/
│   ├── docker-compose.yml      # timescale/timescaledb:2.17.2-pg16、redis:7.4-alpine、cp-kafka:7.6.0
│   ├── run-e2e.(sh|ps1)        # 一鍵:compose up → 後端 build+run → health 等待 → playwright test
│   ├── tests/
│   │   ├── auth.spec.ts        # 旅程 A
│   │   ├── markets.spec.ts     # 旅程 B
│   │   ├── trading.spec.ts     # 旅程 C
│   │   └── backtest.spec.ts    # 旅程 D
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
| 前端 `vue/stock-v2` | PR/push → develop | 新 job `browser-e2e`(needs: frontend):checkout 自己 + checkout 後端 `main` |
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

## 5. 第一批 Test Cases(13 條)

### 旅程 A:註冊/登入/Session(5 條)

| # | 案例 | 驗證重點 |
|---|------|----------|
| A1 | UI 註冊 → 自動登入,Header 顯示使用者 | register、cookie 設置、前端 session 解析 |
| A2 | 錯誤密碼登入 → 友善錯誤(可見 `error.code`/`traceId`) | `AUTH_INVALID_CREDENTIALS` 錯誤路由 |
| A3 | 登入 → reload → session 仍在 | HttpOnly cookie 持久性、`/me` 開機解析 |
| A4 | 登出 → UI 回未登入態 | logout 清 cookie、前端狀態重置 |
| A5 | 未登入訪問受保護頁 → 導向登入/AuthPanel | 路由守衛與 401 接線 |

### 旅程 B:行情瀏覽(3 條)

| # | 案例 | 驗證重點 |
|---|------|----------|
| B1 | Markets 顯示 seed 資產(≥19 筆或有分頁) | Flyway seed → API → 畫面讀取鏈 |
| B2 | 搜尋 NVDA → 點入 Chart 顯示標的 | 查詢參數、路由帶參 |
| B3 | Watchlist 加入 NVDA → reload 仍在 → 移除消失 | 寫入暫時 DB 再讀回;unsafe POST 成功隱含驗證 CSRF 流程 |

### 旅程 C:交易與持倉(3 條)

| # | 案例 | 驗證重點 |
|---|------|----------|
| C1 | 買 NVDA 10 股 → Trades 出現 → Positions 顯示 → reload 仍在 | 前端 → API → DB → 讀回的完整寫入鏈 |
| C2 | 續 C1 賣 4 股 → Positions 變 6、Trades 多一筆(同檔 serial) | 狀態變更與聚合 |
| C3 | 數量 0/負 → 驗證錯誤、不產生交易 | validation 接線(`error.fields`) |

### 旅程 D:回測(2 條)

| # | 案例 | 驗證重點 |
|---|------|----------|
| D1 | 建立回測 → 執行 → `expect.poll` 至完成 → 結果顯示(驗結構) | 非同步 job 全鏈 |
| D2 | 參數無效(起訖日顛倒)→ 錯誤、不建 job | 驗證錯誤路由 |

原則:每條案例自帶帳號、不依賴執行順序(C2 例外,同檔 serial)。

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
4. 13 條測試案例(TDD:先寫案例對現況跑紅,再補 testid/接線讓其轉綠)。
5. CI 新增 `browser-e2e` job(checkout 後端 main)。

**跨 repo 前置:** cross-repo checkout 用的 PAT/deploy key secret(需 Yuan 在 GitHub 設定)。
