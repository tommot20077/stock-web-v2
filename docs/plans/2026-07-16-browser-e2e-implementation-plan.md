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

### Task 6 — 前端 e2e/Tests: 旅程 A 註冊/登入/Session(A1–A5)
- **目標**: `e2e/tests/auth.spec.ts` 5 條案例;AuthPanel/Header/SessionBanner 補 `data-testid`
- **TDD**: 先寫 5 條 spec 跑紅(缺 testid)→ 補 testid 與必要接線轉 Green
- **相依**: Task 5
- **驗證**: `npm run test:e2e -- e2e/tests/auth.spec.ts` 通過,且 `npm test`(Vitest)不退步

### Task 7 — 前端 e2e/Tests: 旅程 B 行情瀏覽(B1–B3)
- **目標**: `e2e/tests/markets.spec.ts` 3 條;Markets/Chart/Watchlist 補 `data-testid`;B3 驗證寫入 DB 後 reload 仍在
- **TDD**: 同上 Red → Green
- **相依**: Task 5(與 Task 6 可平行)
- **驗證**: `npm run test:e2e -- e2e/tests/markets.spec.ts` 通過

### Task 8 — 前端 e2e/Tests: 旅程 C 交易與持倉(C1–C3)
- **目標**: `e2e/tests/trading.spec.ts` 3 條(C1→C2 同檔 serial);Trades/Positions 補 `data-testid`
- **TDD**: 同上 Red → Green
- **相依**: Task 5(與 Task 6、7 可平行)
- **驗證**: `npm run test:e2e -- e2e/tests/trading.spec.ts` 通過

### Task 9 — 前端 e2e/Tests: 旅程 D 回測(D1–D2)
- **目標**: `e2e/tests/backtest.spec.ts` 2 條(`expect.poll` 等非同步 job);Backtest 頁補 `data-testid`
- **TDD**: 同上 Red → Green
- **相依**: Task 5(與 Task 6–8 可平行)
- **驗證**: `npm run test:e2e -- e2e/tests/backtest.spec.ts` 通過

### Task 10 — 前端 e2e/Script: run-e2e 串上 Playwright 與 artifacts
- **目標**: script 尾端串 `playwright test`;失敗收集後端 stdout log 到 `e2e/artifacts/`;README 補本地執行說明(含 Windows)
- **TDD**: 腳本任務
- **相依**: Task 6–9
- **驗證**: `npm run test:e2e` 全套 13 條綠

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
Task 2 ─┴→ Task 3 → Task 4 → Task 5 → Task 6/7/8/9(可平行)→ Task 10 → Task 11 → Task 12 → Task 13
```
