---
last_mapped_commit: adae04117fcfa99db40b8ca304fb5cb9c5e36ec9
last_mapped_at: 2026-11-07
---
# Codebase Concerns

**Analysis Date:** 2026-11-07

> 範圍:後端 repo(本 repo)與前端 repo 根(程式在 `vue-app/`;本機 / cloud 路徑對照見 `ai-docs/judgment.md` §8)。前端路徑皆相對前端 repo 根。
> 已有 todo 的項目只做交叉引用(`.planning/todos/pending/`),不重複展開;Yuan 已裁決的範圍以 `2026-11-07-phase-5-scope-decisions.md` 為準,勿重議。

## Tech Debt

**金額 / 數量 / 價格以 JSON double 傳輸:**
- Issue: 前端 `amount` 為 `number`,經 `Number(...)` → `JSON.stringify` 送出;超過約 15–17 位有效數字會被無聲四捨五入,沖銷路徑同理。
- Files: `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/CreateCashEntryRequest.java`、`vue-app/src/services/cashApi.ts`、`vue-app/src/components/CashEntryDialog.vue`、`vue-app/src/services/apiTypes.ts`
- Impact: 帳本金額與使用者輸入不一致且無錯誤提示(一般金額不受影響)。
- Fix approach: 已裁決於 Phase 5:前端引入 decimal 套件,請求 / 回應改字串,service 層轉 decimal。見 `2026-11-06-cash-amount-string-serialization.md`;需同步 `ai-docs/trading-portfolio-contract.md` 與 mock adapter / E2E。

**大型 service 類別(後端):**
- Issue: 單一類別承擔查詢組裝、FX 折算、配置計算等多重責任。
- Files: `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/PortfolioQueryService.java`(904 行)、`.../service/ShareAdjustmentService.java`(595)、`.../service/DividendService.java`(435)、`.../service/TradingService.java`(427)、`.../service/CashLedgerService.java`(400)、`stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/ws/MarketWebSocketHandler.java`(545)
- Impact: 新需求(帳戶層級報酬、匯兌損益)都會落在 `PortfolioQueryService`,修改衝突與回歸風險高。
- Fix approach: 動到時先抽出純計算元件(如 allocation / FX 折算 / 今日損益)並以單元測試鎖行為;不建議單獨開重構 phase。

**大型 Vue SFC(前端):**
- Issue: 元件混合表單狀態、驗證、格式化與樣式。
- Files: `vue-app/src/components/HoldingEventDialog.vue`(1826 行)、`vue-app/src/components/OrderTicket.vue`(1606)、`vue-app/src/pages/Positions.vue`(1350)、`vue-app/src/pages/Overview.vue`(1193)、`vue-app/src/pages/Watchlist.vue`(1169)、`vue-app/src/pages/Trades.vue`(1129)、`vue-app/src/i18n.ts`(899)
- Impact: Phase 5 金額改 decimal 時,OrderTicket / HoldingEventDialog / CashEntryDialog 的數值處理需逐一改寫,容易漏。
- Fix approach: 改 decimal 時把解析 / 驗證抽成 composable(與 `useNow` 同層),元件只綁定。

**「未來時間」判斷不隨時間更新:**
- Issue: CashEntryDialog / OrderTicket 的 future-time 判斷取一次性 now。
- Files: `vue-app/src/components/CashEntryDialog.vue`、`vue-app/src/components/OrderTicket.vue`
- Fix approach: 改用 `useNow`(已列入 Phase 5 第 6 點)。

**Ops / AI Access 只有前端:**
- Issue: 後端無 `/api/v1/ops/**` 與 AI Access 端點,前端仍為 Preview。
- Files: `vue-app/src/pages/Ops.vue`、`vue-app/src/components/settings/SettingsAiAccess.vue`、`vue-app/src/services/opsApi.ts`、`vue-app/src/services/aiAccessApi.ts`、前端 `docs/api-contracts/mock-to-real-contract.md`
- Impact: 違反鐵律 6 的風險——任何新增控制都必須保持 Preview 標示。
- Fix approach: backlog,見 `2026-11-07-ops-and-ai-access-backend.md`。

**資產主檔只能靠 migration seed:**
- Files: `stock-db-migration/`、`stock-module-asset/src/main/java/dowob/xyz/stockwebv2/asset/api/AssetController.java`
- Fix approach: 見 `2026-09-28-asset-master-management.md`(新 milestone)。

## Known Bugs

**`executedAt` 晚於現在的錯誤缺 `fields.executedAt`:**
- Symptoms: 前端無法把錯誤標在欄位上(1900 下限的錯誤則有)。
- Files: `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/TradingService.java`、`vue-app/src/components/OrderTicket.vue`
- Workaround: 顯示一般錯誤訊息。已列入 Phase 5 第 6 點。

**觀察清單名稱不換行空白只擋在服務層:**
- Symptoms: 繞過 service 直寫 DB 可存入僅含 NBSP 的名稱(04.2 IN-04)。
- Files: `stock-db-migration/`(watchlist CHECK)、`stock-module-market-data` watchlist service
- Fix: 新 migration 擴充 CHECK(Phase 5 第 6 點)。

**紅漲綠跌模式下語意色錯亂:**
- Symptoms: Ops 失敗狀態燈變綠;`.pill.buy` 等寫死 `rgba` 底色不跟著對調。
- Files: `vue-app/src/pages/Ops.vue`、`vue-app/src/accentTokens.ts`、相關 SFC 的 scoped style
- Fix: 失敗 / 錯誤另立固定色 token(Phase 5 第 6 點)。

**今日損益不含匯差 / K 線未還原權息:**
- Symptoms: 損益只算價格部分(UI 已標示「不含匯差」);拆股後 K 線與回測出現價格斷層。
- Files: `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/PortfolioQueryService.java`、`stock-module-market-data/`、`stock-module-backtest/`
- Workaround: 屬已知設計限制,見 `2026-09-28-fx-pnl-and-rate-history.md`、`2026-09-28-kline-adjusted-prices.md`。

## Security Considerations

**權限覆寫 / 改密碼 / 帳號刪除未實作:**
- Risk: `ai-docs/security.md` 的對應章節僅剩指向 todo 的一行;實作者不可假設這些保護存在(無 `user_permissions`、無 change-password、無 soft delete / anonymization)。
- Files: `ai-docs/security.md`、`ai-docs/architecture.md`、`stock-module-user/`
- Current mitigation: 權限僅來自 `Role`;login / register / refresh 有限流與 Account Lockout。
- Recommendations: backlog,見 `2026-11-07-account-and-security-hardening.md`。

**瀏覽器端 auth 安全性缺端到端驗證:**
- Risk: httpOnly cookie、CSRF header、credentials、登出後 `/api/v1/me` 回 401 目前只有單元 / 元件層驗證,沒有對真後端的瀏覽器測試。
- Files: `vue-app/src/services/apiClient.ts`(唯一 `fetch` 邊界,含 CSRF 取得、refresh 與 replay)、`vue-app/e2e/`、後端 `stock-infrastructure/`(security config)、`ai-docs/browser-auth-contract.md`
- Current mitigation: `apiClient.ts` 集中處理;component 無直接 `fetch`(已核對)。
- Recommendations: Phase 5 SC4。

**CORS / WebSocket origin 共用設定:**
- Risk: `stock.cors.allowed-origins` 預設空字串;REST CORS 與 WS 共用(`stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/config/WsConfig.java`)。部署漏設會導致前端全掛或若有人改成 `*` 搭配 credentials 則有風險。
- Recommendations: 在 prod profile 啟動時對空值 fail fast(新發現,尚無 todo)。

**前端 localStorage 使用:**
- Files: `vue-app/src/useTweaks.ts`、`vue-app/src/accentTokens.ts`、`vue-app/src/i18n.ts`
- Risk: 目前僅存 UI 偏好;鐵律 2 禁止 token 進 localStorage。新增持久化時須審查不得放入 token 或個資。

## Performance Bottlenecks

**分頁 `count(*)` 與觀察清單上限 count:**
- Problem: 每次分頁查詢多一次 count。
- Files: `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/repository/JdbcTradingRepository.java`、watchlist repository
- Improvement path: Yuan 3C-8 裁決**維持現狀**,不排工作。

**Integration test 時間:**
- Problem: market-data 各 `*IT` 各自起 Postgres / Kafka / Redis 容器(5 次 Kafka 啟動)。
- Improvement path: 共用 Testcontainers,估省約 60 秒;已併入 Phase 5,見 `2026-09-27-it-shared-containers.md`。

**Portfolio 查詢組裝:**
- Problem: `PortfolioQueryService` 每次 summary 重算持倉、FX 折算、配置;資料量增長時成本線性上升。
- Improvement path: 目前單使用者規模無問題;引入快取前先量測。

## Fragile Areas

**`apiClient.ts` 的 401 → refresh → replay 流程:**
- Files: `vue-app/src/services/apiClient.ts`、`vue-app/src/services/authSession.ts`
- Why fragile: 同時處理 CSRF 取得、併發 refresh、request replay;body 重送與 CSRF token 更新順序錯一步就產生無限迴圈或靜默登出。
- Safe modification: 先跑 `vue-app/src/services/apiClient.test.ts` 與 `authSession.test.ts`,兩種 `VITE_DATA_MODE` 都跑。
- Test coverage: 有單元測試;缺真後端瀏覽器驗證(Phase 5 SC3/SC4)。

**股數調整 / 股利事件重算:**
- Files: `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/ShareAdjustmentService.java`、`.../DividendService.java`、`vue-app/src/components/HoldingEventDialog.vue`
- Why fragile: 拆合股、配股影響成本與前收調整(04.2 D-04~D-10),與 append-only 帳本及沖銷交錯。
- Safe modification: 修改前補 edge case 測試(沖銷後再調整、跨幣別)。

**Mock / API 雙模式:**
- Files: `vue-app/src/services/runtimeDataMode.ts`、`vue-app/src/services/pageApiClients.ts`
- Why fragile: 新增 service 若只實作一種 adapter,mock 模式會掩蓋 API 失敗(鐵律 3);Phase 5 SC5 要求錯誤 / 重試狀態不被 mock 掩蓋。
- Safe modification: 每個新 service 同時寫 mock 與 api adapter 測試,並跑 `VITE_DATA_MODE=api npm test`。

**MarketWebSocketHandler:**
- Files: `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/ws/MarketWebSocketHandler.java`、`vue-app/src/services/quoteStream.ts`
- Why fragile: session 管理、訂閱與推送集中在單一 545 行類別;重連語意由前端 `quoteStream.ts` 承擔。

## Scaling Limits

**觀察清單:**
- Current capacity: 每清單 100 檔上限(以 count 強制)。
- Scaling path: 維持現狀(3C-8)。

**匯率資料:**
- Current capacity: `fx_rates` 只有 V2 seed 靜態值,無每日歷史。
- Limit: 無法計算歷史匯兌損益。
- Scaling path: 見 `2026-09-28-fx-pnl-and-rate-history.md`。

## Dependencies at Risk

**前端 bleeding-edge 版本:**
- Risk: TypeScript 6、Vite 8、Vue Router 5、Vitest 4 皆為新大版本,第三方 plugin 相容性可能落後。
- Impact: 升級 / 新增套件(如 Phase 5 的 decimal 套件)時型別或 build 失敗。
- Migration plan: 新增依賴後必跑 `npm run lint && npm test && npm run build`。

**前端 CI 使用 Node 20:**
- Risk: `.github/workflows/`(前端)固定 Node 20,與本機版本可能不一致;Node 20 將進入 EOL。
- Migration plan: 評估升級 Node 22 LTS(新發現,尚無 todo)。

## Missing Critical Features

**跨 repo browser E2E(真後端):**
- Problem: 目前 `vue-app/e2e/` 以 `page.route` stub 為主;`run-e2e.sh` 預設只跑 `@smoke`。
- Blocks: v1.0 收尾(Phase 5 VER-01/02/03);`@extended` 每 PR 執行已裁決。

**帳戶層級報酬率:**
- Problem: `roi` 僅以持倉成本計。
- Blocks: 真實績效呈現;見 `2026-09-28-account-level-return.md`。

**Analytics 產業分布:**
- Files: `vue-app/src/pages/Analytics.vue`
- 見 `2026-07-19-backend-asset-classification.md`(Overview / Positions 已由後端提供)。

## Test Coverage Gaps

**Phase 5 SC1 / SC2 對照稽核:**
- What's not tested: 尚未逐條比對後端 auth cookie / CSRF / CORS / refresh / logout / 401-403 信封 / 交易冪等,以及前端 API client / auth store / runtime mode / adapter 的覆蓋。
- Files: 後端各模組 `src/test/`、`vue-app/src/services/*.test.ts`
- Risk: 契約漂移未被發現。
- Priority: High(Phase 5 第 1 點)

**單一串連 journey:**
- What's not tested: login → `/me` → portfolio → create trade → refetch → logout 的單一 spec。
- Files: `vue-app/e2e/tests/`
- Priority: High(Phase 5 SC3)

**極大金額 / 高精度小數:**
- What's not tested: 接近 `@Digits(13,8)` 邊界的 round-trip。
- Files: `vue-app/src/services/cashApi.test.ts`、trading IT
- Priority: Medium(隨金額字串化一起補)

**歷史性測試斷言:**
- What's not tested: `vue-app/src/pages/Positions.test.ts`、`vue-app/src/pages/Trades.test.ts` 內有「D-13 TODO 註解已清除」這類 phase 稽核型斷言,`vue-app/src/task6.test.ts` 以任務編號命名——維護價值低、易誤導。
- Priority: Low(新發現,可在 Phase 5 稽核時順手整理)

---

*Concerns audit: 2026-11-07*
