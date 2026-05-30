# 架構研究：瀏覽器安全驗證與投資組合/交易 API 模式

**專案：** Stock Web V2  
**研究日期：** 2026-05-30  
**研究範圍：** 後端 Java/Spring Boot 模組化單體與 sibling Vue app 的 auth、CSRF、API client、portfolio/trading adapters、跨 repo 驗證責任  
**整體信心：** HIGH。依據既有 `.planning/codebase/*`、實際後端/前端源碼，以及 Spring Security 7.0 官方文件中 `CookieCsrfTokenRepository` 對 SPA CSRF token cookie/header 的支援。

## 結論

這個 milestone 應該先把「瀏覽器 session 合約」做成後端與前端共用的地基，再接 portfolio/trading。原因是目前後端仍是 bearer JWT filter、`csrf(AbstractHttpConfigurer::disable)`，而前端 `apiClient.ts` 也還沒有 `credentials: "include"`、CSRF header、401/403 session 行為。若先把交易 API 接上 cookie，但沒有完整 CSRF 與 refresh/logout 合約，會產生最危險的 partial cookie auth 狀態：瀏覽器會自動帶 cookie，但 unsafe request 還沒被可靠防護。

建議架構是：後端 `stock-start` 擁有 HTTP security、cookie、CSRF、CORS、401/403 envelope；`stock-module-user` 擁有登入、註冊、refresh、logout、`/me` 的 session 語意與 refresh token lifecycle；`stock-module-trading` 維持交易與投資組合業務，不感知 cookie 或 CSRF；前端 `src/services/apiClient.ts` 擁有 credentialed fetch、CSRF header、request id、envelope/error 解析；前端 `authApi.ts`、`portfolioApi.ts`、`tradingApi.ts` 是 domain adapters；Vue pages/components 只處理 UI state 與呼叫 adapter，不直接 `fetch`。

## 推薦架構

```text
Browser Vue app
  App.vue / pages / OrderTicket.vue
        |
        v
  services/authApi.ts
  services/portfolioApi.ts
  services/tradingApi.ts
        |
        v
  services/apiClient.ts
  - credentials: include
  - X-XSRF-TOKEN from XSRF-TOKEN cookie
  - Accept / Content-Type / X-Trace-Id awareness
  - ApiResponse envelope parsing
  - 401/403 normalized errors
        |
        v
Spring Boot stock-start
  SecurityConfig
  - CORS allowCredentials
  - JWT from Authorization for API clients
  - JWT from httpOnly access cookie for browsers
  - CSRF only required for cookie-auth unsafe methods
  - ApiSecurityErrorWriter envelopes
        |
        +--> stock-module-user
        |    AuthController/AuthService/RefreshTokenService
        |    register, login, refresh, me, logout, cookie issuing/revocation semantics
        |
        +--> stock-module-trading
             TradingController/TradingService/PortfolioCache
             create trade, list trades, holdings, summary
```

## Component Boundaries

| Component | 責任 | 不應負責 |
|-----------|------|----------|
| `stock-start/src/main/java/.../start/config/SecurityConfig.java` | HTTP security chain、CORS、CSRF policy、從 bearer header 或 access cookie 建立 `Authentication`、統一 401/403 envelope。 | 不處理 login/register 業務，不直接操作 refresh token domain 規則。 |
| `SecurityConfig.JwtAuthenticationFilter` 或後續拆出的 browser token filter | 解析 access token、查 Redis `user:auth:{userId}` tokenVersion/status、建立 authorities。應支援 `Authorization: Bearer` 與瀏覽器 access cookie，且 bearer path 不需要 CSRF。 | 不簽發 token，不更新 portfolio/trade。 |
| `stock-module-user` `AuthController` | `/api/v1/auth/register`、`/api/v1/auth/login`、`/api/v1/auth/refresh`、`/api/v1/auth/logout`、`/api/v1/me`。瀏覽器模式下透過 `Set-Cookie` 設 access/refresh cookie；response body 回 user/session metadata，不回 refresh token 給 JS。 | 不讓前端持有 refresh token；不把 CSRF token 當成登入憑證。 |
| `stock-module-user` `RefreshTokenService` | Redis refresh token issue/rotate/revoke、user-agent metadata、tokenVersion/session status 配合。 | 不知道 HTTP cookie 名稱；cookie 包裝放 controller/security helper。 |
| `stock-module-trading` `TradingController` | 現有 `/api/v1/trades`、`/api/v1/portfolio/holdings`、`/api/v1/portfolio/summary`，用 `Authentication` 的 user id 與 `@PreAuthorize` 控權。 | 不做 cookie、CSRF、frontend mapping；交易仍是「手動成交紀錄」，不是 broker order lifecycle。 |
| `stock-module-trading` `TradingService` | `AssetFacade` tradeable 檢查、holding lock、`HoldingCalculator`、transaction write、portfolio cache invalidation。 | 不做 session restore，不直接回 UI 專用格式。 |
| `../../vue/stock-v2/vue-app/src/services/apiClient.ts` | 唯一 generic fetch wrapper：`credentials: "include"`、unsafe methods 自動加 `X-XSRF-TOKEN`、解析 `ApiResponse<T>`、轉 `ApiClientError`、保留 `requestId`。 | 不包含 portfolio/trading domain mapping。 |
| `authApi.ts` | register/login/refresh/me/logout adapter；管理 session restore 呼叫順序；暴露 user/session 結果給 store/composable。 | 不讀取 httpOnly cookies；只可讀非 httpOnly CSRF cookie。 |
| `portfolioApi.ts` | summary、holdings refetch，將後端 `PortfolioSummaryDto`、`HoldingDto` 轉成 UI 需要的 position/summary shape。 | 不建立 trade，不直接改 mock store。 |
| `tradingApi.ts` | `POST /trades`、`GET /trades`，將 `OrderTicket` 的 BUY/SELL manual execution 轉成 `CreateTradeRequest`。 | 不實作 broker pending order、partial fill、cancel。 |
| Vue pages/components | 顯示 loading/error/empty/success state；submit 後呼叫 adapter/refetch。 | 不直接呼叫 `fetch`，不處理 cookie/CSRF/header 細節。 |

## Cookie 與 CSRF 合約

**建議 cookie：**
- `__Host-stock_access`：httpOnly、Secure、SameSite=Lax 或依部署需求調整、Path `/`、短 TTL，供瀏覽器 protected API call 使用。
- `__Host-stock_refresh`：httpOnly、Secure、SameSite=Lax、Path `/api/v1/auth` 或 `/`，長 TTL，僅 refresh/logout 使用。
- `XSRF-TOKEN`：非 httpOnly、Secure、SameSite=Lax、Path `/`，只放 CSRF token，讓 JS 讀取後送 `X-XSRF-TOKEN`。Spring Security 官方文件建議 SPA 可用 `CookieCsrfTokenRepository.withHttpOnlyFalse()`，預設 cookie/header 命名即符合 Angular-style `XSRF-TOKEN` / `X-XSRF-TOKEN`。

**CSRF policy：**
- 對 cookie-authenticated unsafe methods 強制 CSRF：`POST`、`PUT`、`PATCH`、`DELETE`。
- `GET /api/v1/me`、`GET /api/v1/portfolio/*` 不要求 CSRF，但仍要求 authenticated。
- `Authorization: Bearer` 的 non-browser/API client path 可保留；若 request 沒帶 browser auth cookie 且以 bearer 成功驗證，CSRF 可忽略。
- `POST /api/v1/auth/login`、`POST /api/v1/auth/register` 可以不要求既有 CSRF token，但成功 response 必須建立新的 CSRF cookie，讓後續 unsafe calls 有 token。
- `POST /api/v1/auth/refresh` 與 `POST /api/v1/auth/logout` 建議要求 CSRF，因為 refresh/logout 都是 cookie-auth unsafe browser request；若 UX 需要初次 restore 時 refresh，也要先確保 CSRF cookie 可透過安全 GET 或登入頁 response 取得。

## Data Flow

### 1. Login

1. Vue login form 呼叫 `authApi.login({ email, password })`。
2. `apiClient` `POST /api/v1/auth/login`，`credentials: "include"`，JSON body。
3. `AuthController.login` 驗證 credential，`RefreshTokenService.issue(...)` 建立 Redis refresh state，`JwtService` 簽 access token。
4. 後端用 `Set-Cookie` 寫入 httpOnly access/refresh cookies，並確保 response 也帶/更新 `XSRF-TOKEN` cookie。
5. Response body 回 `ApiResponse<{ user: MeResponse }>` 或保留相容欄位但瀏覽器端不使用 token 欄位。
6. 前端 auth store 設定 user 與 authenticated 狀態；不把 access/refresh token 存到 localStorage/sessionStorage。

### 2. Refresh / Session Restore

1. Vue app 啟動時在 API mode 先呼叫 `authApi.me()`。
2. 若 `/me` 200，直接恢復 user。
3. 若 `/me` 401 且存在 refresh cookie，呼叫 `authApi.refresh()`，`apiClient` 送 `POST /auth/refresh`、`credentials: include`、`X-XSRF-TOKEN`。
4. 後端驗證 refresh cookie、Redis refresh state、user status/tokenVersion；rotate refresh token，簽新 access token，重新 `Set-Cookie`。
5. 前端再呼叫 `/me` 或使用 refresh response 中的 user 更新 session。
6. 若 refresh 401/403，前端清除 user state，停留在未登入 UI；不要無限 retry。

### 3. Logout

1. Vue 呼叫 `authApi.logout()`。
2. `apiClient` `POST /api/v1/auth/logout`，帶 credentials 與 `X-XSRF-TOKEN`。
3. 後端根據 refresh cookie 或 session id revoke Redis refresh state；必要時 bump tokenVersion 或只撤銷該 refresh token，依既有 logout 語意決定。
4. 後端回 `Set-Cookie` 清除 access/refresh cookies，也可清除/更新 `XSRF-TOKEN`。
5. 前端不依賴 body token；收到 success 或 401 都清除本地 user/session UI state。

### 4. CSRF Token 使用

1. 後端在 login/register/refresh 或專用安全 GET response 設 `XSRF-TOKEN` cookie。
2. `apiClient` 對 unsafe methods 讀 `document.cookie` 的 `XSRF-TOKEN`。
3. `apiClient` 加 header `X-XSRF-TOKEN: <token>`。
4. Spring Security CSRF 驗證 cookie token 與 header token。
5. CSRF 失敗回 403，仍使用 `ApiResponse.failure(...)` envelope，前端顯示 session/security 錯誤並要求重新整理或重新登入。

### 5. Protected API Call

1. Vue page 呼叫 domain adapter，例如 `portfolioApi.getSummary()`。
2. Adapter 呼叫 `apiRequest<PortfolioSummaryDto>('/api/v1/portfolio/summary')`。
3. Browser 自動帶 access cookie；若 non-browser client 則可帶 `Authorization: Bearer`。
4. 後端 security filter 解析 token、查 Redis `user:auth:{userId}`、建立 authorities。
5. `TradingController.summary` 用 `@PreAuthorize("hasAuthority('PORTFOLIO_VIEW')")` 控權。
6. 成功回 `ApiResponse.success(data, meta)`；前端 adapter unwrap data 並 map 成 UI shape。
7. 401：前端可嘗試一次 refresh 後 replay 原 request。403：不 refresh，顯示權限不足。

### 6. Trade Creation

1. `OrderTicket.vue` 在 API mode 呼叫 `tradingApi.createTrade(...)`，不要直接 `portfolio.executeOrder(...)`。
2. Adapter 將 UI order 轉成後端 `CreateTradeRequest`：
   - `symbol` = selected symbol
   - `type` = `BUY` 或 `SELL`
   - `quantity`、`price`、`fee` = decimal number/string，避免浮點格式污染
   - `note`
   - `executedAt` 可省略，讓後端用現在時間，除非 UI 明確支援成交時間
3. `apiClient` `POST /api/v1/trades`，帶 credentials 與 CSRF header。
4. `TradingService.createTrade` 執行 asset tradeable 檢查、holding lock、持倉計算、交易寫入與 portfolio cache invalidation。
5. 後端回 `TradeDto`；前端只把它當成功結果，不在本地手算最終持倉。

### 7. Portfolio Refetch After Trade

1. trade creation 成功後，前端並行 refetch：
   - `GET /api/v1/portfolio/summary`
   - `GET /api/v1/portfolio/holdings`
   - `GET /api/v1/trades?page=0&size=20`
2. `portfolioApi` 與 `tradingApi` 更新 API mode store/composable。
3. UI 顯示後端回來的 summary、positions、recent trades；`lastFill` 可由成功 `TradeDto` 派生為 transient UI highlight，但不得作為真實 portfolio state。
4. 若 refetch 任一失敗，保留 trade 成功 toast，但標示 portfolio refresh failed 並提供 retry；不要用舊 mock state 假裝已同步。

## Suggested Build Order

1. **合約文件與測試骨架**
   - 在 `.planning` 或 `docs/api/browser-auth.md` 寫明 cookie、CSRF、401/403、refresh/logout、portfolio/trading endpoints。
   - 先補後端 security/controller tests 與前端 `apiClient` tests 的預期案例，避免半套 cookie auth。

2. **後端 browser auth + CSRF 地基**
   - `SecurityConfig` 啟用 CSRF repository、保留 CORS credentials、調整 filter 支援 access cookie。
   - `AuthController` 增加/調整 refresh/logout cookie contract。
   - 驗證 register/login/refresh/logout/me 皆回 envelope 與正確 cookies。

3. **前端 generic API client 與 auth adapter**
   - `apiClient.ts` 加 `credentials: include`、CSRF header、401 one-shot refresh hook 或由 higher-level session manager 控制。
   - 新增 `authApi.ts` 與 session restore flow。
   - UI 先只接 login/logout/me，不接 trading，確保瀏覽器 auth 穩定。

4. **Portfolio read adapters**
   - 新增 `portfolioApi.ts`，接 summary/holdings。
   - Positions/Overview 在 API mode 讀後端；mock mode 維持既有 Pinia mock store。
   - 完成 loading/error/empty state 與 mapping tests。

5. **Trading write adapter + post-trade refetch**
   - 新增 `tradingApi.ts`，OrderTicket API mode 改送 `POST /trades`。
   - 成功後 refetch summary/holdings/trades。
   - 測試 401 refresh replay、403 權限不足、CSRF 403、trade success refetch。

6. **跨 repo end-to-end verification**
   - 後端跑 Maven unit/integration。
   - 前端跑 Vitest/type-check/build。
   - 手動或自動瀏覽器驗證 cookie、CSRF header、trade 後 refetch。

## Contracts, Docs, Tests 放置位置

| 類型 | 位置 | 內容 |
|------|------|------|
| Browser auth contract | `.planning` 或 `docs/api/browser-auth.md` | cookie names/options、CSRF header/cookie、refresh/logout、401/403 行為、bearer 相容性。 |
| Backend security tests | `stock-start/src/test/java/.../start/config` 或既有 integration test package | cookie auth、bearer auth、CSRF pass/fail、CORS credentials、401/403 envelope。 |
| Backend auth controller tests | `stock-module-user/src/test/java/.../user/api` 與必要的 `stock-start` integration tests | login/register/refresh/logout/me cookie 與 Redis refresh lifecycle。 |
| Backend trading tests | `stock-module-trading/src/test/java/.../trading` | create trade invalidates cache、holdings/summary/list trades ownership and permissions。 |
| Frontend generic client tests | `../../vue/stock-v2/vue-app/src/services/apiClient.test.ts` | credentials、CSRF header、error envelope、invalid JSON/envelope、request id。 |
| Frontend auth adapter tests | `../../vue/stock-v2/vue-app/src/services/authApi.test.ts` | login/me/refresh/logout path、no token storage、401/403 behavior。 |
| Frontend portfolio/trading adapter tests | `../../vue/stock-v2/vue-app/src/services/portfolioApi.test.ts`、`tradingApi.test.ts` | DTO mapping、query params、create trade body、refetch orchestration。 |
| Frontend page/component tests | 既有 `src/task*.test.ts` 或 colocated page tests | API mode loading/error/success、OrderTicket submit 後 refetch、mock mode 不回歸。 |
| Cross-repo verification note | `.planning` phase `PLAN.md` / `VERIFY.md` | 明列 backend repo 與 frontend repo commands、ports、env vars。 |

## Verification Responsibilities

**後端必驗：**
- `./mvnw -B test --fail-at-end --no-transfer-progress`
- `./mvnw -B -pl stock-start -am verify -Dspring-boot.repackage.skip=true --fail-at-end --no-transfer-progress`
- Security integration cases：
  - 無 cookie/無 bearer 呼叫 protected endpoint -> 401 envelope
  - bearer token 呼叫 protected endpoint -> 200，不要求 CSRF
  - cookie token GET protected endpoint -> 200
  - cookie token POST `/trades` 無 CSRF -> 403 envelope
  - cookie token POST `/trades` 有 CSRF -> 200/validation result
  - logout 後舊 access/refresh 失效

**前端必驗：**
- 在 `../../vue/stock-v2/vue-app` 跑 `npm test` 或專案既有 Vitest command。
- 跑 type-check/build，依 `package.json` 實際 script 為準。
- `apiClient` 測試 fetch options 中 `credentials: "include"` 與 unsafe method CSRF header。
- Auth flow 測試不寫入 localStorage/sessionStorage token。
- API mode 的 OrderTicket 成功後會呼叫 create trade，接著 refetch summary/holdings/trades。
- Mock mode 下既有 `useMockPortfolioStore().executeOrder` 行為維持。

**跨 repo 瀏覽器驗證：**
- 後端 dev server 與 Vue dev server 同時啟動，`STOCK_CORS_ALLOWED_ORIGINS` 包含 Vue origin。
- DevTools Network 驗證：
  - login response 有 httpOnly access/refresh cookie 與非 httpOnly `XSRF-TOKEN`
  - protected GET 自動帶 cookie
  - protected POST 帶 cookie 與 `X-XSRF-TOKEN`
  - 401 refresh 只 retry 一次
  - trade 成功後三個 refetch request 發出並更新 UI

## Patterns to Follow

### Pattern 1: Security 在 start module，domain 保持乾淨

**What：** `stock-start` 負責 Spring Security filter chain 與 HTTP-level failure envelope；feature modules 只透過 `Authentication` 取得 user id 並靠 `@PreAuthorize` 控權。  
**Why：** 既有架構已把 `SecurityConfig` 放在 `stock-start`，`TradingController` 已用 `@PreAuthorize`。延續這個邊界可避免 cookie/CSRF 細節污染 trading domain。

### Pattern 2: API client 是唯一 fetch 出口

**What：** 所有前端 HTTP adapters 都用 `apiRequest`，由它統一 credentials、CSRF、envelope、error。  
**Why：** 目前 `backtestApi.ts` 已遵守 generic `apiClient.ts` pattern；auth/portfolio/trading 應沿用，不要在 pages 裡散落 raw fetch。

### Pattern 3: Trade success 後以後端為真相來源

**What：** API mode 不手算 positions；`POST /trades` 成功後 refetch summary/holdings/trades。  
**Why：** 後端 `TradingService` 有 transaction、locking、cache invalidation 與 `AssetFacade` tradeable 檢查，前端 mock 計算只適合 demo mode。

## Anti-Patterns to Avoid

### Anti-Pattern 1: Cookie auth 先上線，CSRF 後補

**問題：** 瀏覽器會自動送 cookie，unsafe endpoints 立即暴露 CSRF 風險。  
**改法：** 同一 phase 內先完成 CSRF pass/fail 測試，再讓任何 protected write endpoint 接受 browser cookie auth。

### Anti-Pattern 2: 前端保存 refresh token

**問題：** 目前 `AuthResponse` 回 `accessToken`/`refreshToken`，若 Vue 直接存 token 會回到 bearer-in-browser 風險。  
**改法：** 瀏覽器合約中 refresh/access token 只在 httpOnly cookies；response body 只給 user/session metadata。Bearer token 相容性保留給 non-browser client。

### Anti-Pattern 3: 把 OrderTicket 當 broker order

**問題：** 後端 `/trades` 是已成交的 manual transaction，沒有 pending/cancel/partial fill/status。  
**改法：** UI copy 與 adapter 命名應稱作「record trade/manual execution」語意；完整 broker order lifecycle 延後。

### Anti-Pattern 4: API mode 失敗時偷偷回退 mock data

**問題：** 使用者會以為資料已同步後端，實際上看到 mock portfolio。  
**改法：** API mode 失敗要顯示 error/retry；mock fallback 只在明確 mock mode。

## Scalability Considerations

| Concern | 100 users | 10K users | 1M users |
|---------|-----------|-----------|----------|
| Auth/session | Redis refresh/tokenVersion 足夠；cookie auth + CSRF tests 是主要風險。 | Refresh rotation、Redis key TTL、rate limit login/refresh。 | Session/device management、Redis clustering、token revocation audit。 |
| Portfolio reads | 交易後直接 refetch summary/holdings/trades。 | 既有 `PortfolioCache` TTL 與 invalidation 要監控命中率。 | 需要事件化 valuation、分頁/增量同步、行情價格快取策略。 |
| Trading writes | 單體 transaction + holding lock 足夠。 | 需確保 DB lock 範圍與 index，避免同 user/symbol 熱點。 | 可能拆 command/event pipeline，但不應在本 milestone 提前做。 |
| Frontend state | API mode store/composable 足夠。 | 需要 request dedupe、stale-while-revalidate。 | 需要 cache invalidation protocol/WebSocket portfolio updates。 |

## Roadmap Implications

建議 roadmap phases 依賴順序：

1. **Browser Auth Contract & Backend Security Foundation**  
   先完成 cookie、CSRF、refresh/logout、401/403 envelope。這是所有 API mode 的前置條件。

2. **Frontend Session & API Client Foundation**  
   接 `apiClient`、`authApi`、session restore。完成後才能安全呼叫 protected APIs。

3. **Portfolio Read API Mode**  
   接 summary/holdings，讓 Overview/Positions 先讀後端，風險低於 write path，也能驗證 session 穩定。

4. **Trade Creation & Portfolio Refetch**  
   接 OrderTicket -> `POST /trades` -> refetch。這是核心 vertical slice，但必須排在 auth/CSRF/client 之後。

5. **Cross-Repo Verification & Contract Hardening**  
   收斂 docs/tests，跑 backend + frontend + browser verification，補 401/403/CSRF/trade-refresh regressions。

## Sources

- `.planning/PROJECT.md`：milestone active requirements、browser-safe auth、CSRF、API mode、portfolio/trading refetch。
- `.planning/codebase/ARCHITECTURE.md`：既有 modular monolith、module responsibilities、REST/auth/trading/frontend adapter paths。
- `.planning/codebase/STRUCTURE.md`：實際 module/file layout 與新增 code 位置。
- `.planning/codebase/INTEGRATIONS.md`：現有 bearer auth、CORS credentials、Redis refresh state、frontend adapter constraints。
- `.planning/codebase/CONVENTIONS.md`：TDD、error envelope、frontend adapter、contract test conventions。
- `stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java`：目前 CSRF disabled、bearer JWT filter、CORS credentials。
- `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java`：目前 login/register 回 JSON tokens，`/me`、logout 既有路徑。
- `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/TradingController.java`：既有 trade/portfolio endpoints 與 authorities。
- `../../vue/stock-v2/vue-app/src/services/apiClient.ts`：目前 generic envelope parser，尚未有 credentials/CSRF。
- Spring Security 7.0 reference via Context7 CLI：`CookieCsrfTokenRepository.withHttpOnlyFalse()` 適合 JavaScript-driven applications，使用 `XSRF-TOKEN` cookie 與 `X-XSRF-TOKEN` header pattern。
