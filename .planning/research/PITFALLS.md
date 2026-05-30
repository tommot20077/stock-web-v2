# 領域風險研究

**領域:** Stock Web V2 瀏覽器 cookie auth、CSRF、Vue API mode、portfolio/trading 整合  
**研究日期:** 2026-05-30  
**整體信心:** HIGH。主要依據為本 repo 與 sibling frontend 的實作盤點；未使用外部網頁資料，因為本次問題要求的是 brownfield 系統內的具體落地風險。

## 重大風險

### 風險 1: 在啟用 cookie auth 前沒有先恢復 CSRF 防護

**會出什麼問題:**  
目前 `SecurityConfig` 全域 `.csrf(AbstractHttpConfigurer::disable)`，但 CORS 已經 `setAllowCredentials(true)`。只要瀏覽器開始帶 httpOnly cookie 呼叫 `POST /api/v1/trades`、`POST /api/v1/auth/logout`、backtest 建立或管理類 unsafe API，跨站頁面就可能觸發 credentialed request。現況 bearer token 放在 `Authorization` header 時，瀏覽器不會自動附上憑證；改成 cookie 後，這個安全假設會失效。

**警訊:**
- `SecurityConfig.java` 仍然全域 disable CSRF，同時新增 `Set-Cookie` 或 frontend `credentials: "include"`。
- `POST /api/v1/trades` 沒有 CSRF header 仍回 200/業務錯誤，而不是 403。
- 登入/refresh/logout 設計只討論 access/refresh cookie，沒有定義 `X-CSRF-Token` 或等價 header。
- CORS 允許 credential，但未明確限制 allowed origins 與 exposed headers。

**預防策略:**
- Phase 1: 先定義 backend/frontend auth contract，再改程式。cookie 模式必須包含：httpOnly auth cookie、非 httpOnly CSRF cookie 或 CSRF token endpoint、unsafe method 必填 `X-CSRF-Token`、401/403 錯誤 envelope、logout 清 cookie。
- 後端在 `stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java` 針對 cookie-authenticated unsafe requests 啟用 CSRF；bearer-only API client 可保留，但不可讓瀏覽器 cookie 路徑繞過 CSRF。
- 前端在 `../../vue/stock-v2/vue-app/src/services/apiClient.ts` 統一處理 credentials 與 CSRF header，不允許各 domain service 自己手刻。

**應處理階段:**  
Phase 1「瀏覽器 Auth/CSRF Contract」。這是 portfolio/trading API mode 的前置條件。

**可抓到此問題的驗證:**
- Backend MockMvc/IT：有 auth cookie 但無 CSRF header 的 `POST /api/v1/trades`、`POST /api/v1/auth/logout` 應回 403 且維持 `ApiResponse.failure` envelope。
- Backend MockMvc/IT：有正確 CSRF token 的 unsafe request 才成功。
- Frontend Vitest：`apiClient` 在 unsafe methods 送出 `credentials: "include"` 與 `X-CSRF-Token`。
- E2E/API mode smoke：登入後刷新頁面、呼叫 `/me`、送出交易、logout 全流程驗證 cookie 與 CSRF 合約。

### 風險 2: cookie 與 bearer token 雙軌沒有明確分界，造成安全語意混亂

**會出什麼問題:**  
現有 `AuthController` 的 register/login 回傳 `accessToken` 與 `refreshToken` JSON，`JwtAuthenticationFilter` 只讀 `Authorization: Bearer ...`。新 milestone 要加入 cookie auth，但需求也要求保留 bearer support 給非瀏覽器/API client。若沒有明確分界，容易出現瀏覽器同時支援 JSON refresh token、cookie refresh token、Authorization bearer 三種狀態，導致 logout/revoke/refresh 行為不可預期，也增加 XSS 可竊取 refresh token 的面。

**警訊:**
- register/login 同時把 refresh token 放 JSON body 與 httpOnly cookie，且 frontend 仍儲存 JSON token。
- `/auth/logout` 仍要求 body 傳 `refreshToken`，但瀏覽器 cookie 模式已不應讓 JS 讀 refresh token。
- `/me` 有時靠 Authorization header，有時靠 cookie，測試沒有分別覆蓋。
- API client 有「token storage」與 `credentials: "include"` 並存，但沒有 browser/non-browser 模式旗標。

**預防策略:**
- Phase 1 contract 明確拆分：Browser cookie mode 與 non-browser bearer mode。
- Browser login/register 回應只回 user/session metadata，refresh token 只存在 httpOnly cookie；`/auth/refresh` 透過 cookie rotation 發新 cookie/access cookie 或 session cookie。
- Browser logout 不要求 request body 帶 refresh token，而是從 cookie/session context revoke 並清 cookie。
- Bearer mode 若保留，應在 API docs 中標記為 non-browser client；frontend 不應把 refresh token 放 localStorage/sessionStorage。

**應處理階段:**  
Phase 1「瀏覽器 Auth/CSRF Contract」，並在 Phase 2「Vue Auth Client」落實模式分界。

**可抓到此問題的驗證:**
- Backend `AuthFlowIT` 新增 cookie-mode register/login/refresh/logout 測試：確認 `Set-Cookie` 屬性、JSON 不含 refresh token、logout 清 cookie。
- Backend bearer-mode regression：現有 Authorization header 流程仍可用於 non-browser tests。
- Frontend Vitest：auth service 不讀取、不保存 refresh token；session restore 只呼叫 `/me` 或 `/auth/refresh`。

### 風險 3: Vue `fetch` 沒有統一 credentials、CSRF、request ID 與 envelope 行為

**會出什麼問題:**  
`../../vue/stock-v2/vue-app/src/services/apiClient.ts` 目前只是 `fetch(path, init)`，沒有 `credentials: "include"`、沒有 CSRF header、沒有 401/403 session handling。其他 service 也曾有 envelope parsing 重複問題。若 portfolio/trading adapter 直接新增自己的 fetch，API mode 會在不同頁面出現不同錯誤行為：有的忘記帶 cookie，有的吃不到 CSRF，有的錯誤 envelope 解析不同。

**警訊:**
- 新增 `tradingApi.ts`、`portfolioApi.ts` 時直接呼叫 `fetch`。
- `apiClient.test.ts` 沒有檢查 `credentials` 與 unsafe method CSRF header。
- 某些 domain service 自己判斷 `success/error/requestId`，而不是共用 `apiRequest`。
- 401 只在元件中各自處理，沒有統一清 session 或導向登入。

**預防策略:**
- Phase 2 先升級 `apiClient.ts` 為唯一 HTTP 邊界：base URL、credentials、CSRF token 讀取、safe/unsafe method 判斷、envelope parsing、request ID、401/403 handling 都在這裡。
- Domain adapter 只能建 path、DTO mapper 與 schema guard，不處理 transport。
- `ApiClientError` 保留 `status/code/requestId/details`，讓 UI 能顯示錯誤但不丟失 trace ID。

**應處理階段:**  
Phase 2「Vue Auth/API Client Foundation」。Phase 3 portfolio/trading 不應在這之前接入真 API。

**可抓到此問題的驗證:**
- Frontend `apiClient.test.ts`：GET 帶 credentials；POST/PATCH/DELETE 帶 credentials 與 CSRF header；API failure envelope 轉為 `ApiClientError`；invalid JSON/invalid envelope 可預期失敗。
- Frontend `api-adapter-wiring.test.ts`：API mode 的 backtest/ops/AI/trading/portfolio adapter 都經過 shared client。
- Manual API mode smoke：清 cookie、過期 cookie、CSRF 遺失、403 permission denied 都顯示一致狀態。

### 風險 4: API mode 靜默退回 mock，掩蓋真後端整合失敗

**會出什麼問題:**  
`runtimeDataMode.ts` 目前只有 `value === "api" ? "api" : "mock"`。這對 demo 很方便，但對整合里程碑危險：`VITE_DATA_MODE` 拼錯、CI 未設定、部署環境漏設定，都會靜默變成 mock。結果登入、portfolio、trade history、order ticket 看似可用，但其實沒有打後端。

**警訊:**
- API integration demo 沒有 network calls，但 UI 還能操作。
- CI 只跑 frontend mock tests，沒有 `VITE_DATA_MODE=api` 的 build/test。
- README 或 phase verification 寫「打開 app 看起來正常」但沒有檢查 backend logs 或 request traces。
- `OrderTicket.vue` 仍 import `useMockPortfolioStore` 且 API mode 沒有替代 service。

**預防策略:**
- Phase 2 加入 explicit mode guard：local dev 可預設 mock；integration/CI/production 若 mode invalid 必須 fail fast。
- UI 可有非敏感 diagnostics 顯示 active mode，但不可把它當安全控制。
- Phase 3 接 portfolio/trading 前，先建立 mock/http 雙實作 service boundary，元件只依賴 domain service。

**應處理階段:**  
Phase 2「Vue Auth/API Client Foundation」，Phase 3「Portfolio/Trading API Mode」補足 adapter wiring。

**可抓到此問題的驗證:**
- Frontend `runtimeDataMode.test.ts`：production/integration context 中 invalid mode 會 throw 或 build fail。
- Frontend API-mode adapter tests：`VITE_DATA_MODE=api` 時 portfolio/trading calls 必須呼叫 HTTP adapter，不可碰 mock store。
- CI/本地驗證：`cd ../../vue/stock-v2/vue-app && VITE_DATA_MODE=api npm test && npm run build`。

### 風險 5: 直接把現有 OrderTicket 接到 `POST /api/v1/trades`，誤把「成交紀錄」當「下單系統」

**會出什麼問題:**  
後端 `CreateTradeRequest` 只有 `symbol/type/quantity/price/fee/note/executedAt`，`TradingService.createTrade` 立即寫 transaction 並更新 holdings。前端 `OrderTicket.vue` 則有 market/limit、TIF、placing、routing、filled、slippage、orderId 等完整下單體驗。若直接把「Place Order」按鈕接到 `POST /api/v1/trades`，使用者會以為自己送出一筆可等待/取消/成交的委託，但後端實際上是手動記錄一筆已成交交易。

**警訊:**
- API request 帶 `ordType`、`tif`、`orderId`，但後端 DTO 沒有對應欄位。
- market order 用前端隨機 slippage 後的 `fillPx` 當後端 `price`，沒有明確 UX 文案說這是 manual executed trade。
- UI 顯示「routing」或「placing」但後端沒有 pending order lifecycle。
- 測試只檢查 toast/filled state，不檢查 backend transaction shape。

**預防策略:**
- Phase 3 將本 milestone 的交易語意改為「記錄已成交交易」或「manual trade entry」，不要承諾 broker order lifecycle。
- 若保留現有 modal 視覺流程，API mode 需移除或降級 order type/TIF/routing 語意；只傳後端支援的欄位。
- 未來若要真委託單，另開 phase 設計 orders table、status、partial fills、cancel、broker integration。

**應處理階段:**  
Phase 3「Portfolio/Trading API Mode」。

**可抓到此問題的驗證:**
- Frontend component/service tests：API mode submit payload 只包含 backend `CreateTradeRequest` 支援欄位。
- Backend `TradingApiIT`：明確驗證 `POST /api/v1/trades` 是立即成交紀錄與 holdings projection 更新。
- UAT：下單/交易表單文案與結果頁不得出現「pending/cancel/routing」等目前後端不支援的承諾。

### 風險 6: 交易 duplicate submission 造成重複買賣與持倉錯誤

**會出什麼問題:**  
`TradingService.createTrade` 每次呼叫都產生新的 UUID 並 insert transaction；schema 只有 `uk_transactions_uuid`，沒有 `Idempotency-Key` 或 `clientOrderId`。雙擊、瀏覽器 retry、timeout 後重送、使用者回上一頁再提交，都會建立多筆交易。`findHoldingForUpdate` 與 version check 保護同一持倉的並發更新，但不會判斷「這是否同一個使用者意圖」。

**警訊:**
- 前端只用 `placing` flag 禁按鈕，沒有 server-side idempotency。
- request timeout 後 UI 顯示可重送，但後端可能已寫入 transaction。
- DB schema 沒有 `(user_id, idempotency_key)` unique constraint。
- 測試沒有「同 idempotency key 重送回同一筆 trade」或「不同 key 才新增」。

**預防策略:**
- Phase 3 在交易 API contract 加 `Idempotency-Key` header 或 `clientOrderId` body，後端持久化於 transactions 或獨立 idempotency table，unique by `user_id + key`。
- duplicate request 應回傳既有交易，不可再次更新 holdings。
- 前端每次使用者確認送出時產生穩定 key；同一次提交 retry 沿用同 key，新意圖才換 key。

**應處理階段:**  
Phase 3「Portfolio/Trading API Mode」。不可只靠 frontend debounce。

**可抓到此問題的驗證:**
- Backend `TradingApiIT`：同一使用者同 key 連續兩次 `POST /api/v1/trades`，`/trades` totalElements 仍為 1，holdings 只更新一次。
- Backend concurrent test：平行重送同 key 不產生 duplicate transaction。
- Frontend Vitest：API timeout retry 沿用同一 idempotency key；double click 只送一個 logical submission。

### 風險 7: Portfolio read model 更新時機錯誤，交易成功後 UI 顯示 stale/mock 狀態

**會出什麼問題:**  
後端交易成功後會 invalidate portfolio cache，但前端目前 `OrderTicket.vue` 直接 `portfolio.executeOrder()` 改 mock Pinia store。API mode 若只呼叫 `POST /api/v1/trades` 而不 refetch `/portfolio/summary`、`/portfolio/holdings`、`/trades`，畫面會停在舊資料；若同時保留 mock mutation，又會出現 UI 與後端資料不一致。

**警訊:**
- API mode submit 後仍呼叫 `useMockPortfolioStore().executeOrder()`。
- 成交 toast 顯示成功，但 Positions/Trades 頁面沒有 network refetch。
- summary、holdings、trade history 各自獨立更新，錯誤時沒有一致 rollback/refresh 策略。
- 測試只看 component local state，不驗證後續 read APIs。

**預防策略:**
- Phase 3 建立 portfolio/trading domain service：`createTrade` 成功後集中觸發 summary、holdings、trade history refetch 或 cache invalidation。
- API mode 不做本地 projection mutation；最多做 optimistic UI，但必須以 backend read model 回填。
- Mock mode 可保留現有 store，但要透過同一 service interface，不讓元件知道資料來源。

**應處理階段:**  
Phase 3「Portfolio/Trading API Mode」。

**可抓到此問題的驗證:**
- Frontend service/component tests：API mode 成交成功後依序呼叫 `POST /trades`、`GET /portfolio/summary`、`GET /portfolio/holdings`、`GET /trades`。
- Frontend test：API mode 不呼叫 mock store mutation。
- Backend IT：交易後 summary/holdings/trades 三個 endpoint 回傳一致資料。

### 風險 8: 401/403/CSRF failure 沒有一致 UX 與 envelope，導致使用者重複送單

**會出什麼問題:**  
後端已有 `ApiSecurityErrorWriter` 以 `ApiResponse.failure` 回傳 auth/forbidden error；但 CSRF 啟用後若沒有接到相同 envelope，前端可能收到 HTML error、空 body 或 generic HTTP error。交易提交時若前端無法判斷是 session expired、CSRF expired、permission denied、validation failed，使用者會重複按送出，放大 duplicate submission 風險。

**警訊:**
- CSRF 失敗回應不是 `{ success: false, error: { code, message }, requestId }`。
- `apiClient` 對 401/403 一律丟 `HTTP_ERROR`，沒有保留後端 error code。
- 交易 modal 在錯誤後仍停在 placing 或 filled，不回到可修正狀態。
- 前端沒有區分 `AUTH_TOKEN_EXPIRED`、`AUTH_FORBIDDEN`、`VALIDATION_FAILED`、交易業務錯誤。

**預防策略:**
- Phase 1 後端讓 auth、authorization、CSRF failure 都走一致 JSON envelope 與 trace ID。
- Phase 2 `apiClient` 將 401 轉為 session restore/refresh 或登入狀態；403 顯示權限/CSRF 問題；validation/business error 顯示在表單。
- Phase 3 交易 submit 失敗時不可自動重送，除非 idempotency 已完成且錯誤類型允許 retry。

**應處理階段:**  
Phase 1 與 Phase 2 共同處理；Phase 3 在交易 UX 驗證。

**可抓到此問題的驗證:**
- Backend tests：CSRF missing/invalid、permission denied、expired auth 都有 JSON envelope 與 request ID。
- Frontend `apiClient.test.ts`：401/403/error envelope 保留 `code/requestId`。
- Component tests：交易 submit 的 401/403/validation error 會解除 placing、顯示錯誤、不新增交易。

## 中度風險

### 風險 1: CORS 與 cookie 屬性在 dev/prod 環境混用

**會出什麼問題:**  
dev origin 預設是 `http://localhost:5173`，CORS 已允許 credentials。cookie auth 需要 `SameSite`、`Secure`、domain/path、dev/prod origin 一致設計；若 dev 為了方便設太寬，prod 可能出現 cookie 不送、preflight 失敗或跨站暴露。

**警訊:**
- `STOCK_CORS_ALLOWED_ORIGINS` 使用 `*` 或多個未審查 origin 搭配 credentials。
- cookie path/domain 未限制到 API 需要範圍。
- local HTTP 與 production HTTPS 使用同一組 cookie secure policy。

**預防策略:**  
Phase 1 在 contract 裡列出 dev/prod cookie 屬性矩陣；CORS allowed origins 只能是明確白名單；preflight headers 包含 `X-CSRF-Token` 與 idempotency header。

**應處理階段:**  
Phase 1。

**可抓到此問題的驗證:**
- Backend CORS tests：allowed origin + credentials 通過；unknown origin 拒絕；`X-CSRF-Token`、`Idempotency-Key` preflight 可通過。
- Manual browser smoke：localhost frontend 呼叫 dev backend 能送 cookie；非白名單 origin 不能讀回應。

### 風險 2: Refresh token rotation/revocation 與 Redis auth state 沒測 degraded behavior

**會出什麼問題:**  
現有 bearer auth 每次請求會查 Redis `user:auth:{userId}`。cookie refresh flow 會更依賴 Redis 中 refresh token、token version、session revocation。Redis outage 目前會造成 auth failure；若 refresh/logout 同時引入 cookie，使用者可能卡在無法刷新、無法登出、前端無限 retry。

**警訊:**
- `/auth/refresh` 失敗時前端無限重試。
- Redis outage 回應與 invalid credential 混在一起，無法觀測。
- logout 清前端狀態但後端沒有 revoke，或後端失敗但 cookie 未清。

**預防策略:**  
Phase 1 定義 Redis unavailable 的錯誤碼、cookie 清除策略與 retry 上限；Phase 2 frontend 對 refresh failure 做 single-flight 與停止重試。

**應處理階段:**  
Phase 1/2。

**可抓到此問題的驗證:**
- Backend IT 或 slice test：Redis unavailable 時 `/me`、`/auth/refresh` 回一致 error envelope。
- Frontend tests：多個並行 401 只觸發一次 refresh；refresh 失敗會清 session，不無限 loop。

### 風險 3: Pagination 與 DTO shape 沒有 adapter，Trades/Positions 頁面硬吃 mock shape

**會出什麼問題:**  
後端 `/trades` 回 `PageResponse<TradeDto>`，`/portfolio/holdings` 回 `HoldingDto[]`，`/portfolio/summary` 回 summary DTO；前端 mock store 的欄位是 `sym/qty/px/d/type` 等展示 shape。若頁面直接改成吃 backend DTO，會把 mapping 散落在 components，後續欄位改名會大量破裂。

**警訊:**
- `Positions.vue`、`Trades.vue` 中出現 backend DTO 欄位名稱與格式轉換。
- 多個 component 各自處理 BigDecimal 字串/number 格式。
- API response pagination 被元件直接讀，沒有 service-level view model。

**預防策略:**  
Phase 3 在 portfolio/trading adapter 層轉成 frontend view model；元件維持展示 shape；分頁與 filter 狀態由 composable/service 管理。

**應處理階段:**  
Phase 3。

**可抓到此問題的驗證:**
- Frontend adapter tests：backend DTO fixture 轉成 Positions/Trades 所需 view model。
- Component tests：元件不需要知道 `PageResponse` envelope。

### 風險 4: Frontend-only sell validation 與 backend oversell validation 不一致

**會出什麼問題:**  
`OrderTicket.vue` 目前用 mock positions 做 sell validation；後端真正 oversell 判斷在 `HoldingCalculator.applySell` 與交易服務。API mode 若仍使用 stale frontend holdings 判斷，可能擋掉合法交易或放過會被後端拒絕的交易。

**警訊:**
- 賣出按鈕只看 local `portfolio.positions`，沒有處理 backend 409 `TRADE_INSUFFICIENT_HOLDING`。
- 成交前長時間未刷新 holdings。
- 同帳號多分頁操作後，一個分頁仍用舊持倉。

**預防策略:**  
前端 sell validation 只作 UX 預檢；後端結果才是權威。Phase 3 對 409/validation error 做清楚錯誤顯示並 refetch holdings。

**應處理階段:**  
Phase 3。

**可抓到此問題的驗證:**
- Backend `TradingApiIT` 保留 oversell rejection。
- Frontend component test：後端回 `TRADE_INSUFFICIENT_HOLDING` 時顯示錯誤、刷新 holdings、不新增 local trade。

## 輕度風險

### 風險 1: 文件與 OpenAPI 沒同步 cookie/CSRF contract

**會出什麼問題:**  
Swagger/OpenAPI 目前公開，但 auth 仍是 bearer 思維。若 contract 只寫在 issue 或程式碼，frontend 很容易實作錯 header/cookie/refresh/logout 流程。

**預防策略:**  
Phase 1 在 `.planning` 或 docs 補一份 backend/frontend contract，OpenAPI security scheme 後續同步更新。

**應處理階段:**  
Phase 1。

**可抓到此問題的驗證:**  
Roadmap/phase review 要求 contract doc 存在，且測試名稱與 doc 中 endpoint/header/cookie 對得上。

### 風險 2: Frontend sibling repo 沒有 CI，整合回歸靠手動記憶

**會出什麼問題:**  
backend GitHub Actions 不跑 sibling frontend tests/build。auth/API client/trading integration 很可能 backend 綠、frontend 壞。

**預防策略:**  
Phase 2 起每個影響 frontend 的 phase 都列明本地驗證命令；後續補 combined CI 或至少 PR checklist。

**應處理階段:**  
Phase 2 先做本地 gate；後續 DevEx/CI phase 再正式化。

**可抓到此問題的驗證:**  
每個 phase UAT 明列：`cd ../../vue/stock-v2/vue-app && npm test && npm run build`，API mode phase 加 `VITE_DATA_MODE=api`。

## 各階段警訊

| 階段主題 | 可能風險 | 緩解方式 |
|-------------|----------------|------------|
| Phase 1: Auth/CSRF Contract | 啟用 cookie 但 CSRF 仍關閉 | 先寫 contract 與 CSRF negative tests，再改 `SecurityConfig` |
| Phase 1: Cookie/Bearer Compatibility | browser 與 non-browser auth transport 混在一起 | 明確分 cookie-mode endpoints/behavior 與 bearer-mode support |
| Phase 2: Vue Auth/API Client | 各 service 自己 fetch，漏 credentials/CSRF/envelope | `apiClient.ts` 成為唯一 transport boundary |
| Phase 2: Runtime Mode | `VITE_DATA_MODE` 拼錯後靜默 mock | integration/prod invalid mode fail fast |
| Phase 3: Portfolio/Trading API Mode | 將 manual trade record 誤當 broker order | UI 與 payload 都改成已成交交易語意 |
| Phase 3: Trade Submission | double click/retry 造成 duplicate transaction | server-side idempotency key，frontend retry 沿用同 key |
| Phase 3: Post-Trade Refresh | 成功後只改 local state，不讀 backend | 成交後 refetch summary/holdings/trades |
| Phase 3: Error Handling | 401/403/CSRF/validation 混成 generic error | 統一 error envelope，frontend 分類處理 |
| Rollout | backend tests 綠但 frontend API mode 沒跑 | 每 phase 明列 backend Maven + frontend Vitest/build + API smoke |

## 測試與 rollout 風險

- **測試順序風險:** 若先寫 portfolio/trading UI，再補 auth/CSRF，會出現大量測試要重寫。應先完成 Phase 1/2 的 transport contract，再接 Phase 3。
- **Mock 掩蓋風險:** 所有 API mode 功能都要有「mock mode 仍可用」與「api mode 必須打 HTTP」兩組測試。
- **Container suite 成本:** cookie/CSRF/trading idempotency 需要 `stock-start` IT，但全套容器測試成本高；phase 驗證應區分 focused IT 與 full verify。
- **回滾風險:** cookie auth rollout 應保留 bearer non-browser path；若 frontend cookie mode 出問題，可回到 mock/API bearer 測試，但不可在 production 瀏覽器偷偷退回 JS refresh token。

## 建議驗證矩陣

| 區域 | 測試位置 | 必須抓到的問題 |
|------|---------------|------------|
| Cookie 發放與清除 | `stock-start/src/test/java/dowob/xyz/stockwebv2/start/AuthFlowIT.java` | login/register 發 cookie；logout 清 cookie；JSON 不暴露 refresh token |
| CSRF 防護 | `stock-start/src/test/java/dowob/xyz/stockwebv2/start/` | unsafe method 缺少或帶錯 token = 403 envelope；valid token succeeds |
| CORS 憑證設定 | `stock-start` security tests | allowed origin/preflight/header；unknown origin rejected |
| API client 傳輸層 | `../../vue/stock-v2/vue-app/src/services/apiClient.test.ts` | credentials、CSRF header、error envelope、401/403 handling |
| Runtime 模式 | `../../vue/stock-v2/vue-app/src/services/runtimeDataMode.test.ts` | API mode explicit；invalid integration/prod mode fails |
| 交易冪等性 | `stock-start/src/test/java/dowob/xyz/stockwebv2/start/TradingApiIT.java` | duplicate key 不重複 insert、不重複更新 holdings |
| 交易 adapter | `../../vue/stock-v2/vue-app/src/services/*trading*.test.ts` | payload shape、idempotency key、交易後 refetch |
| Order ticket API mode | `../../vue/stock-v2/vue-app/src/components/OrderTicket*.test.ts` | 不碰 mock mutation；錯誤可恢復；成功後刷新 read model |

## 來源

- HIGH: `.planning/PROJECT.md` - milestone context、active requirements、constraints、key decisions。
- HIGH: `.planning/codebase/CONCERNS.md` - cookie/CSRF、frontend credentials、trading idempotency、API mode gaps。
- HIGH: `.planning/codebase/TESTING.md` - backend/frontend test framework、commands、coverage gaps。
- HIGH: `.planning/codebase/INTEGRATIONS.md` - auth provider、CORS/env vars、frontend adapter contracts、Redis/session integration。
- HIGH: `stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java` - CSRF disabled、credentialed CORS、bearer JWT filter、security error envelope。
- HIGH: `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java` - register/login JSON tokens、logout body refresh token、`/me` contract。
- HIGH: `../../vue/stock-v2/vue-app/src/services/apiClient.ts` - current shared fetch/envelope behavior, missing credentials/CSRF/session handling。
- HIGH: `../../vue/stock-v2/vue-app/src/services/runtimeDataMode.ts` - API/mock mode normalization。
- HIGH: `../../vue/stock-v2/vue-app/src/components/OrderTicket.vue` - current mock portfolio mutation and richer order-ticket semantics。
- HIGH: `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/TradingController.java` and `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/TradingService.java` - current trade creation/read model behavior。
- HIGH: `stock-start/src/test/java/dowob/xyz/stockwebv2/start/AuthFlowIT.java` and `stock-start/src/test/java/dowob/xyz/stockwebv2/start/TradingApiIT.java` - existing integration coverage and missing cookie/CSRF/idempotency tests。
