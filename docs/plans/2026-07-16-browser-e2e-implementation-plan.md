# Implementation Plan: Browser E2E Testing(Playwright 全鏈路測試)

## Design Reference
- Design doc: `docs/plans/2026-07-16-browser-e2e-testing-design.md`
- Approved by: Yuan
- 修正:後端 remote 無 `main`,前端 CI checkout 後端 **`develop`**(設計文件 §3.4 寫 main,以本計畫為準)

## 範圍說明
- 跨兩個 repo:後端 `stock-web-v2`(本 repo,branch `feature/browser-e2e`)與前端 `../../vue/stock-v2/vue-app`(需開對應 feature branch)
- 前端 Playwright spec 本身就是測試層:TDD 的 Red = spec 對現況跑紅(多半因缺 `data-testid` 或接線),Green = 補 testid/設定使其轉綠
- 純設定/腳本任務(compose、CI yml)以「可執行驗證指令」取代單元測試

## 前置(Yuan 手動)
- [ ] GitHub 建 cross-repo checkout 用的 PAT(或 deploy key),兩 repo 各設 secret `CROSS_REPO_TOKEN`(Task 11、12 才需要,本地開發不用)
- [ ] security batch-1~3 分支依序合回 `develop`(走 PR 流程)——E2E 對 develop 測的前置條件

## v2 修訂(2026-07-16,Yuan 裁決)
- Phase 1 範圍 = 旅程 A + D(前端 API mode 已接線者);旅程 B/C 綁 mock-to-real 接線 PR(Phase 2,不在本計畫);Watchlist 移出(後端無 API)
- Task 6–9 依設計文件 §5 v2 矩陣重寫;原「13 條案例」改為 Phase 1 十四條(7 @smoke + 7 @extended)

## Tasks

### Task 1 — 後端 stock-start/Config: 新增 `e2e-browser` profile
- **目標**: `stock-start/src/main/resources/application-e2e-browser.yaml`——batch 停用、JWT TTL 放寬、`market-data.scheduling/ingestor/mock` 全開、datasource/redis/kafka 連線讀環境變數(預設 compose 固定 port)
- **TDD**: 設定檔任務;驗證由 Task 4 的 run script 啟動(health UP + `/api/v1/assets` 有 seed 資料)承接
- **相依**: 無
- **驗證**: `./mvnw -pl stock-start -am package -DskipTests --no-transfer-progress` 可打包(profile 語法正確由 Task 4 實跑驗證)

### Task 2 — 前端 e2e/Infra: `docker-compose.yml`
- **目標**: `vue-app/e2e/docker-compose.yml` 定義 `timescale/timescaledb:2.17.2-pg16`、`redis:7.4-alpine`、`confluentinc/cp-kafka:7.6.0`,固定 port、含 healthcheck(版本對齊後端 `ContainerIT`)
- **TDD**: 設定任務
- **相依**: 無
- **驗證**: `docker compose -f e2e/docker-compose.yml up -d --wait` 三容器 healthy

### Task 3 — 前端 e2e/Script: `run-e2e` 一鍵腳本
- **目標**: `vue-app/e2e/run-e2e.sh` 與 `.ps1`:compose up → 後端 `mvnw package -DskipTests` + `java -jar --spring.profiles.active=e2e-browser` → 等 `/actuator/health` UP(timeout 90s,失敗 dump compose logs)→ 之後串 playwright(Task 4 完成後補上);後端 repo 路徑可用環境變數覆寫(預設 `../../java/stock-web-v2`… 依實際相對路徑)
- **TDD**: 腳本任務
- **相依**: Task 1, 2
- **驗證**: 執行 script 至後端 health UP,`curl http://localhost:8080/api/v1/assets?query=NVDA` 回 seed 資料

### Task 4 — 前端 vue-app/Config: Playwright 安裝與設定
- **目標**: `@playwright/test` 依賴、`playwright.config.ts`(chromium only、`retries:1`、`trace:on-first-retry`、workers 4、webServer=`vite preview`)、`vite.config.ts` 加 `preview.proxy`(`/api`、`/ws` → `localhost:8080`)、`package.json` 加 `test:e2e`
- **TDD**: 先寫 `e2e/tests/smoke.spec.ts`(開首頁、頁面可載入)確認 Red(無設定時跑不起來)→ 設定完成轉 Green
- **相依**: Task 3(需環境起著)
- **驗證**: `npm run test:e2e -- e2e/tests/smoke.spec.ts` 通過

### Task 5 — 前端 e2e/Support: fixtures(唯一帳號、登入 helper)
- **目標**: `e2e/support/fixtures.ts`:`uniqueAccount` fixture(API request context 打 `/api/v1/auth/register`,email 格式 `e2e+<runId>-<n>@test.local`)、`loginViaUi`/`loginViaApi` helper、`e2e/support/selectors.md` 規範文件
- **TDD**: 先寫使用 fixture 的最小 spec(API 註冊 → API 登入 → `/api/v1/me` 回同一使用者)確認 Red → 實作 fixture 轉 Green
- **相依**: Task 4
- **驗證**: `npm run test:e2e -- e2e/tests/fixtures.spec.ts` 通過

### Task 6 — 前端 e2e/Tests: 旅程 A 註冊/登入/Session(v2 矩陣)
- **目標**: `e2e/tests/auth.spec.ts`——@smoke:A1 註冊自動登入、A2 reload session 在、A3 登出、A7 錯誤密碼(`AUTH_INVALID_CREDENTIALS` + traceId 可見)、A9 未登入導向;@extended:A4 密碼欄位錯誤顯示、A5 email 正規化、A8 重複 email、A10 帳號鎖定(`AUTH_ACCOUNT_LOCKED`,e2e-browser profile 設低門檻/短時長)、A11 refresh 失效回登入。AuthPanel/Header/SessionBanner 補 `data-testid`
- **TDD**: 先寫 spec 跑紅(缺 testid)→ 補 testid 與必要接線轉 Green
- **相依**: Task 5(A10 另相依 Task 1 的 lockout 參數)
- **驗證**: `npm run test:e2e -- e2e/tests/auth.spec.ts` 通過,且 `npm test`(Vitest)不退步

### Task 7 — 前端 e2e/Tests: 旅程 D 回測(v2 矩陣)
- **目標**: `e2e/tests/backtest.spec.ts`——@smoke:D1 建立→`expect.poll` 至完成→結果顯示(驗結構)、D2 完成後 reload 歷史仍在;@extended:D4 initialCapital 0/負欄位錯誤、D5 不存在 symbol 業務錯誤、D6 執行中防重複(若屬純前端行為改歸 Vitest 並註記)。Backtest 頁補 `data-testid`
- **TDD**: 同上 Red → Green
- **相依**: Task 5(與 Task 6 可平行)
- **驗證**: `npm run test:e2e -- e2e/tests/backtest.spec.ts` 通過

### Task 8 — 後端 API 層補測: 矩陣中歸屬 API 層的邊界案例
- **目標**: 在後端 `*E2E.java`(MockMvc)補:A4 密碼規則組合、A6 username 3/50 邊界、D3 initialCapital 極小值、C7 note 500/501、C8 不存在 symbol 下單;另寫 finding #1 的失敗測試(quantity/price 極大值)——若證實溢位/精度問題,**先回報 Yuan 再修**,不順手改 validation
- **TDD**: 標準 Red → Green(finding #1 停在 Red 並回報)
- **相依**: 無(可與 Task 6、7 平行;在後端 repo 進行)
- **驗證**: `./mvnw -pl stock-start -am test -Pe2e --no-transfer-progress`

### Task 9 —(佔位)Phase 2: 旅程 B/C 綁 mock-to-real 接線
- **目標**: 不在本計畫執行。每接一頁真 API(Markets/Chart、Trades/Positions),同 PR 依設計 §5 的 B1–B5、C1–C8 落地該頁 E2E
- **相依**: mock-to-real 接線進度
- **驗證**: 各接線 PR 內驗證

### Task 10 — 前端 e2e/Script: run-e2e 串上 Playwright 與 artifacts
- **目標**: script 尾端串 `playwright test`(預設只跑 @smoke,`--grep @extended` 跑擴充);失敗收集後端 stdout log 到 `e2e/artifacts/`;README 補本地執行說明(含 Windows)
- **TDD**: 腳本任務
- **相依**: Task 6、7
- **驗證**: `npm run test:e2e` @smoke 7 條綠;`npm run test:e2e -- --grep @extended` 綠(或含已註記之 skip)

### Task 11 — 前端 CI: `browser-e2e` job
- **目標**: 前端 repo `.github/workflows/ci.yml` 新增 job(needs: frontend):checkout 自己 + checkout 後端 `develop`(用 `CROSS_REPO_TOKEN`)、setup java/node + cache、跑 `run-e2e.sh`、失敗上傳 report/trace/後端 log artifacts
- **TDD**: CI 任務,驗證即實跑
- **相依**: Task 10 + 前置 secret
- **驗證**: 前端 repo 開 PR 觸發,job 綠

### Task 12 — 後端 CI: `browser-e2e` job
- **目標**: 本 repo `.github/workflows/ci.yml` 新增 job(needs: e2e):checkout 自己 + checkout 前端 `develop`,跑同一份 `run-e2e.sh`(後端路徑指向本 checkout)
- **TDD**: CI 任務,驗證即實跑
- **相依**: Task 11(script 與 artifacts 邏輯已定型)
- **驗證**: 本 repo push 觸發,job 綠

### Task 13 — 兩 repo/Docs: 驗證指令與規範收尾
- **目標**: 本 repo `CLAUDE.md` Verification Commands 加 E2E 指令;前端 repo 對應文件同步;`ai-docs` 若有測試規範需補 E2E 段落則以 `git add -f` 提交
- **TDD**: 文件任務
- **相依**: Task 12
- **驗證**: 文件審閱

## 執行順序總覽

```
Task 1 ─┐
Task 2 ─┴→ Task 3 → Task 4 → Task 5 → Task 6/7(可平行;Task 8 後端側隨時可平行)→ Task 10 → Task 11 → Task 12 → Task 13
(Task 9 = Phase 2 佔位,綁 mock-to-real 接線,不在本次執行)
```
