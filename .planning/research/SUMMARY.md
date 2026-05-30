# Project Research Summary

**Project:** Stock Web V2  
**Domain:** 股票交易 Web App 的瀏覽器安全認證、Vue API mode、投資組合/交易整合  
**Researched:** 2026-05-30  
**Confidence:** HIGH

## Executive Summary

Stock Web V2 是既有 Java/Spring Boot 模組化單體後端加 sibling Vue 3 SPA 的股票交易 Web App。後端已具備 auth、資產、行情、回測、交易與 portfolio API；前端已有完整 Vue shell、mock portfolio 與 API/mock runtime mode。這個 brownfield milestone 不應重做架構或引入新框架，而是把「瀏覽器安全 session 合約」補齊，再用 portfolio/trading 做第一條可驗收的 API-mode vertical slice。

推薦方向是保留 Java 21 / Spring Boot 4 / Spring Security / Redis / Vue 3 / Vite / Fetch API，將既有 bearer JWT 擴充為 browser cookie transport：access/refresh token 由 httpOnly cookie 承載，Vue 不保存 refresh token；unsafe browser request 必須透過 double-submit CSRF token；shared `apiClient.ts` 統一 `credentials: "include"`、CSRF header、`ApiResponse<T>` envelope、trace/request id、401 refresh retry 與 403/error 處理。Bearer token 可繼續服務 non-browser/API clients，但瀏覽器路徑必須明確走 cookie + CSRF。

最大風險是做出「半套 cookie auth」：瀏覽器自動帶 cookie，但 CSRF、logout、refresh、401/403 envelope、frontend retry 與 trading idempotency 沒有一起收斂。roadmap 應先完成合約文件、後端 security foundation、前端 transport foundation，再接 portfolio reads，最後接 manual executed trade creation、idempotency 與 post-trade refetch。Broker order lifecycle、AI trading policy、broker credentials、完整 settings/watchlists/alerts/analytics API 化與大型 UI redesign 都應延後。

## Key Findings

### Recommended Stack

研究結論是維持既有 stack，避免在這個 milestone 引入 BFF、OAuth Authorization Server、GraphQL、Axios、Next/Nuxt 或新的 auth framework。現有後端與前端都已具備足夠基礎，缺的是瀏覽器安全傳輸合約與跨 repo 驗證。

**Core technologies:**
- Java 21 / Spring Boot 4.0.4：維持既有模組化單體與 Maven/BOM 管理，避免 runtime/framework churn。
- Spring Security Servlet：負責 CORS、CSRF、JWT/cookie auth filter、401/403 JSON envelope；不要自製 CSRF filter。
- `spring-security-oauth2-jose`：保留既有 ES256 JWT 與 Redis tokenVersion/revocation，新增 cookie transport 而不是重寫 token service。
- Redis / `StringRedisTemplate`：延續 refresh token、auth state、revocation store；新增 refresh rotation、logout revoke 與 cookie 清除語意。
- Vue 3 / Pinia / Vue Router / Vite：維持既有 SPA shell；新增 auth store、route guard、runtime mode hardening。
- Browser Fetch API：擴充既有 `apiClient.ts`，統一 credentials、CSRF、envelope、request id、401/403 handling，不引入 Axios。
- Vitest + Maven tests：frontend 驗證 API client/auth store/adapters；backend 驗證 cookie、CSRF、CORS、refresh/logout、trading idempotency。

### Expected Features

**Must have (table stakes):**
- Browser-safe login/register：後端設定 httpOnly access/refresh cookies，response body 不讓 Vue 依賴 refresh token。
- Session restore：Vue app 啟動時呼叫 `/api/v1/me`，401 時可嘗試一次 refresh，失敗後進入 anonymous state。
- Refresh/logout contract：`POST /api/v1/auth/refresh` rotate refresh token；logout revoke server state 並清 cookies。
- Double-submit CSRF：unsafe cookie-authenticated request 必須帶 `X-XSRF-TOKEN` 或等價 header，缺失/錯誤回 403 envelope。
- Shared frontend API client：所有 API mode request 都走同一 transport boundary，預設 `credentials: "include"`。
- Mock/API runtime mode：mock mode 保留；API integration/prod 不得因 invalid env 靜默 fallback 到 mock。
- Portfolio summary / holdings / trade history API adapters：API mode 以後端 read model 為 truth。
- Manual executed trade creation：order ticket 在 API mode 僅建立 buy/sell 已成交交易紀錄。
- Trade idempotency：同一 user + key retry 不可重複建立交易或重複更新 holdings。
- Post-trade refetch：交易成功後重新讀 summary、holdings、trade history，不用 mock projection 假裝同步。
- Contract documentation：先寫 cookie、CSRF、refresh/logout、401/403、portfolio/trading DTO 與驗證責任。

**Should have (valuable but secondary):**
- API mode diagnostics indicator：顯示目前資料模式，避免 demo/驗收誤判。
- Session expiry UX：refresh 失敗或 session 過期時清楚提示。
- Frontend duplicate-submit guard：禁用重複點擊，但不能取代 server idempotency。
- Last refreshed timestamp：讓 portfolio/trading refetch 的資料新鮮度可見。
- Minimal request/trace metadata：保留 request id / trace id 以利跨 repo debugging。
- Graceful backend-unavailable state：API mode 後端不可用時顯示錯誤，不退回 mock。

**Defer (v2+ / explicit anti-scope):**
- 真實券商串接、broker credentials、法遵與實際成交回報。
- Pending orders、order status、cancel、partial fills、time-in-force、broker execution reports。
- Market/limit 真實委託語意；本 milestone 只傳已成交價格與手動交易欄位。
- AI-assisted trading policy enforcement 與 AI/broker settings API mode。
- Full settings/watchlists/alerts/notifications/analytics API integration。
- 大型 Vue redesign；只做必要 integration、state、copy 與錯誤處理。
- localStorage/sessionStorage refresh token。
- 只靠 SameSite 而不做 CSRF。
- API mode 失敗時靜默 fallback 到 mock。

### Architecture Approach

架構應維持清楚邊界：`stock-start` 擁有 HTTP-level security、CORS、CSRF、cookie token parsing、401/403 envelope；`stock-module-user` 擁有 auth/session/refresh/logout domain semantics；`stock-module-trading` 維持交易與 portfolio business logic，不知道 cookie/CSRF；Vue `apiClient.ts` 是唯一 fetch boundary；`authApi.ts`、`portfolioApi.ts`、`tradingApi.ts` 是 thin domain adapters；Vue pages/components 只處理 UI state，不直接呼叫 raw `fetch`。

**Major components:**
1. `stock-start` `SecurityConfig`：CORS credentials、CSRF repository、bearer/cookie access token authentication、security failure envelope。
2. `stock-module-user` `AuthController` / `RefreshTokenService`：login/register/refresh/logout/me、refresh rotation/revocation、cookie issue/clear semantics。
3. `stock-module-trading` `TradingController` / `TradingService`：manual trade creation、holdings lock、portfolio summary/holdings/trades read model。
4. Vue `src/services/apiClient.ts`：credentials、CSRF token header、`ApiResponse<T>` parsing、request id、401 refresh retry、403/error normalization。
5. Vue `authApi.ts` / auth store：login/register/logout/session restore，不保存 tokens。
6. Vue `portfolioApi.ts` / `tradingApi.ts`：DTO mapping、trade payload shape、idempotency key、post-trade refetch orchestration。

### Critical Pitfalls

1. **Cookie auth 先上線但 CSRF 後補** — 同一階段必須完成 CSRF contract、backend negative tests、frontend unsafe-method header tests。
2. **Browser cookie 與 non-browser bearer token 雙軌混亂** — 合約明確拆分 browser cookie mode 與 API client bearer mode；Vue 不保存 refresh token。
3. **Frontend services 各自 raw fetch** — `apiClient.ts` 必須成為唯一 HTTP transport boundary，domain adapter 不處理 credentials/CSRF/envelope。
4. **API mode 靜默退回 mock** — local dev 可預設 mock，但 integration/prod invalid mode 要 fail fast；API mode 失敗必須顯示錯誤。
5. **OrderTicket 誤導成 broker order** — UI copy、adapter payload 與 UAT 都要明確是「記錄已成交交易」，不承諾 pending/cancel/routing。
6. **交易重送造成 duplicate transaction** — server-side idempotency 是 v1 核心，不可只靠 frontend debounce。
7. **交易成功後 UI 顯示 stale/mock state** — API mode 成功後要 refetch summary、holdings、trades；不可呼叫 mock store mutation。
8. **401/403/CSRF/validation error 混成 generic error** — backend 全部回 JSON envelope，frontend 保留 `code/requestId` 並分類處理。

## Implications for Roadmap

Based on research, suggested phase structure:

### Phase 1: Browser Auth Contract & Backend Security Foundation

**Rationale:** Cookie auth、CSRF、refresh/logout、401/403 envelope 是所有 API mode 的地基；不先完成會讓後續 portfolio/trading 直接建立在不安全 transport 上。  
**Delivers:** Contract doc、cookie properties、CSRF token endpoint/repository、browser login/register cookie issuance、refresh rotation、logout revoke/clear cookies、bearer compatibility、CORS allowlist/header config、backend security tests。  
**Addresses:** Browser-safe login/register、session restore primitives、refresh/logout、double-submit CSRF、consistent 401/403 behavior、contract documentation。  
**Avoids:** 半套 cookie auth、cookie/bearer 混亂、CORS/CSRF 誤判、CSRF failure 非 JSON envelope。

### Phase 2: Frontend Session & API Client Foundation

**Rationale:** Portfolio/trading adapters 之前，Vue 需要唯一的 transport boundary 與 session state，否則每個 domain client 都會重複且不一致地處理 credentials、CSRF、refresh retry 與 envelope。  
**Delivers:** `apiClient.ts` credentials/CSRF/envelope/error/request id、one-shot refresh behavior、auth API adapter、Pinia auth store、session restore、logout flow、route/protected operation guard、runtime mode hardening、frontend unit tests。  
**Uses:** Vue 3、Pinia、Vue Router、Vite env、Fetch API、Vitest。  
**Implements:** Frontend API client boundary、auth adapter、auth state machine (`unknown` / `authenticated` / `anonymous`)。  
**Avoids:** raw fetch proliferation、token storage in frontend、silent API-to-mock fallback、parallel infinite refresh loops。

### Phase 3: Portfolio Read API Mode

**Rationale:** Portfolio read path is lower risk than writes and validates the auth/session/client foundation against protected APIs before trading mutation is introduced.  
**Delivers:** API-mode summary adapter、holdings/positions adapter、trade history read adapter、DTO-to-view-model mapping、loading/error/empty states、mock mode regression tests。  
**Addresses:** Portfolio summary、holdings/positions、trade history table stakes。  
**Avoids:** components hard-coding backend DTO/PageResponse shape、mock fields mistaken as backend truth、API mode silently using mock store。

### Phase 4: Manual Trade Creation, Idempotency & Post-Trade Refetch

**Rationale:** This is the core product vertical slice but has the highest behavioral risk, so it should come after auth/CSRF/client/read models are stable.  
**Delivers:** `tradingApi.createTrade`、OrderTicket API-mode mapping to `CreateTradeRequest`、`Idempotency-Key` contract and backend persistence/unique behavior、duplicate-submit protection、post-trade refetch of summary/holdings/trades、validation/business error UX。  
**Addresses:** Manual executed trade creation、backend validation surfaced in UI、trade idempotency、post-trade refetch。  
**Avoids:** duplicate transactions、frontend-only sell validation drift、broker-order scope creep、stale portfolio state after trade。

### Phase 5: Cross-Repo Browser Flow Verification & Contract Hardening

**Rationale:** Backend and frontend live in sibling repos; green tests in one repo do not prove browser cookies, CSRF, CORS, refresh, logout, and trading refetch work together.  
**Delivers:** Backend Maven test/verify gate、frontend Vitest/type-check/build gate、API-mode browser smoke script/checklist using gstack `/browse` or manual DevTools verification、contract doc updates、trace/request-id debugging notes。  
**Addresses:** Verification implications for backend, frontend, and cross-repo browser flow。  
**Avoids:** backend-only green builds with broken frontend API mode、mock masking integration failure、cookie attributes failing only in browser。

### Phase Ordering Rationale

- Auth/CSRF must precede every protected API integration because cookie transport changes the browser threat model.
- Frontend API client/session foundation must precede domain adapters so credentials, CSRF, envelope, request id and refresh behavior do not fragment.
- Portfolio reads should precede trade writes because they exercise auth and mapping with lower business risk and become the read models needed after a trade.
- Trade creation belongs after read adapters because success must be verified by backend summary/holdings/trades, not local mutation.
- Cross-repo browser verification should close the milestone because cookie, CORS and CSRF correctness cannot be fully proven by unit tests alone.

### Research Flags

Phases likely needing deeper research during planning:
- **Phase 1:** Cookie attribute matrix for dev/prod deployment, Spring Security CSRF handler details, bearer-vs-cookie CSRF bypass rules, Redis refresh degraded behavior.
- **Phase 4:** Idempotency persistence design, concurrent duplicate submission behavior, UI wording downgrade from order placement to manual execution.
- **Phase 5:** Browser smoke automation approach across sibling repos, ports, env vars, cookies, CORS and CSRF inspection.

Phases with standard patterns (skip research-phase unless new uncertainty appears):
- **Phase 2:** Vue shared API client, Pinia auth store, Vue Router guard and Vitest fetch mocking are well-documented and locally constrained.
- **Phase 3:** DTO adapters, loading/error/empty states and mock/API service boundaries are standard frontend integration work once the backend contract is fixed.

## Verification Implications

### Backend

- Required baseline: `./mvnw -B test --fail-at-end --no-transfer-progress` and focused `stock-start` verify/integration tests.
- Add tests for unauthenticated protected endpoint -> 401 envelope; bearer protected request -> 200 without CSRF; cookie protected GET -> 200; cookie unsafe POST without CSRF -> 403 envelope; valid CSRF unsafe request -> success/validation response.
- Add auth flow tests confirming login/register issue httpOnly cookies, JSON does not expose refresh token in browser contract, refresh rotates token, logout clears cookies and revokes server state.
- Add CORS tests for allowed origin + credentials, unknown origin rejected, preflight accepts `X-XSRF-TOKEN`, `X-Trace-Id`, `Idempotency-Key`, `Authorization`.
- Add trading idempotency tests: same user + same key returns existing trade and updates holdings once, including concurrent retry.

### Frontend

- Required baseline in `../../vue/stock-v2/vue-app`: project test command, type-check/build command, and API-mode test/build with `VITE_DATA_MODE=api` where feasible.
- `apiClient` tests must assert `credentials: "include"` on API mode requests, CSRF header on unsafe methods, envelope parsing, 401 single refresh/replay, CSRF-specific 403 handling, request id retention.
- Auth store/API tests must assert no token storage, session restore via `/me`, logout clears local state, refresh failure stops retries.
- Runtime mode tests must assert invalid integration/prod mode fails rather than falling back to mock.
- Portfolio/trading adapter tests must assert DTO mapping, `CreateTradeRequest` payload shape, idempotency key generation/reuse, post-trade refetch, and API mode not calling mock portfolio mutation.

### Cross-Repo Browser Flow

- Start backend and sibling Vue dev server with explicit CORS origin and `VITE_DATA_MODE=api`.
- Browser smoke path: login -> `/me` session restore -> portfolio summary/holdings/trades load -> create manual trade -> refetch summary/holdings/trades -> logout -> `/me` returns 401.
- DevTools/automation must confirm login response sets httpOnly auth cookies plus readable `XSRF-TOKEN`, protected GET carries cookies, protected POST carries cookie and `X-XSRF-TOKEN`, 401 refresh retries once, logout clears cookies.
- API mode failure must show error/retry state, not mock data.

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | Based on existing repo stack, official Spring Security/MDN/Vite/Vue Router/Vitest docs, and clear brownfield constraints. |
| Features | HIGH | Derived from PROJECT.md active/out-of-scope requirements plus codebase concerns; table stakes are tightly coupled and consistent across research. |
| Architecture | HIGH | Verified against actual backend/frontend file layout and existing module boundaries; recommended boundaries align with current design. |
| Pitfalls | HIGH | Based on concrete current gaps: CSRF disabled, bearer-only filter, JSON tokens, missing frontend credentials/CSRF, mock/API mode fallback, trading idempotency gap. |

**Overall confidence:** HIGH

### Gaps to Address

- **Production deployment shape:** Confirm whether frontend/backend are same-site. SameSite=Lax is preferred; true cross-site deployment requires `SameSite=None; Secure` and stricter CORS/CSRF browser tests.
- **Browser vs non-browser auth compatibility:** Decide whether existing login/register endpoints keep token-body compatibility or split browser/non-browser behavior explicitly.
- **Refresh/session model:** Clarify single-device vs multi-device refresh semantics and what logout revokes.
- **CSRF endpoint/header naming:** Standardize on Spring default `XSRF-TOKEN` / `X-XSRF-TOKEN` unless codebase chooses a different documented name.
- **Trading idempotency storage:** Decide transaction column vs dedicated idempotency table and persistence TTL/retention.
- **Frontend package scripts:** Planning must read sibling `package.json` before finalizing exact Vitest/type-check/build commands.

## Sources

### Primary (HIGH confidence)
- `.planning/PROJECT.md` — milestone scope, active requirements, out-of-scope, constraints, repository context.
- `.planning/research/STACK.md` — recommended technologies, cookie/CSRF stack, frontend API client guidance, verification tooling.
- `.planning/research/FEATURES.md` — table-stakes v1 scope, differentiators, anti-features, dependencies, acceptance-oriented list.
- `.planning/research/ARCHITECTURE.md` — component boundaries, data flow, build order, test locations, roadmap implications.
- `.planning/research/PITFALLS.md` — critical/moderate/minor risks, warning signs, prevention strategies, verification matrix.
- `.planning/codebase/*` references cited by research files — existing architecture, structure, integrations, conventions, concerns, testing.
- Backend source references cited by research files: `SecurityConfig.java`, `AuthController.java`, `TradingController.java`, `TradingService.java`, existing `AuthFlowIT` and `TradingApiIT`.
- Frontend source references cited by research files: `apiClient.ts`, `runtimeDataMode.ts`, `OrderTicket.vue`.

### Official / External Sources (HIGH confidence)
- Spring Security CSRF documentation — `CookieCsrfTokenRepository`, SPA CSRF token cookie/header pattern, CSRF testing guidance.
- Spring Security CORS documentation — CORS must run before security; credentialed CORS requires exact origins.
- MDN Fetch `Request.credentials` — browser credentials and `Set-Cookie` behavior.
- MDN `Set-Cookie` — `HttpOnly`, `Secure`, `SameSite`, cookie prefixes.
- Vite env/mode documentation — `VITE_*` client exposure and mode behavior.
- Vue Router navigation guards documentation — async global guards and return-style redirects.
- Vitest mocking documentation — fetch/env mocking and reset patterns.

---
*Research completed: 2026-05-30*  
*Ready for roadmap: yes*
