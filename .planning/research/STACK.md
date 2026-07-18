# 技術堆疊研究：瀏覽器安全 Auth、CSRF、Vue API 整合

**Project:** Stock Web V2  
**Research dimension:** Stack  
**Researched:** 2026-05-30  
**Overall confidence:** HIGH

## 結論

這個 milestone 應該沿用既有 Java 21 / Spring Boot 4.0.4 / Spring Security / Redis / Vue 3 / Vite 架構，不引入外部 SPA auth framework、BFF、OAuth server、Next/Nuxt 或 GraphQL。要做的是把現有 bearer JWT 後端擴充成明確的「瀏覽器 cookie session 合約」，並保留 bearer JWT 給非瀏覽器 API client。

推薦路線是：登入/註冊/refresh 由後端設定 `HttpOnly` auth cookies，Vue 不保存 refresh token；unsafe request 使用 Spring Security CSRF，前端從可讀的 CSRF cookie 或 `/api/v1/csrf` 取得 token 後送 `X-XSRF-TOKEN`；所有 API mode fetch 預設 `credentials: "include"`，統一處理 envelope、trace id、401/403、refresh retry、CSRF retry、idempotency key。

這比「把 JWT 放 localStorage」更適合本案，因為目前 refresh token 已是可重放 bearer secret，若暴露給 JavaScript，XSS 後果很重。Cookie auth 不是單獨的安全答案，因為瀏覽器會自動附帶 cookie，所以 CSRF 必須同時啟用並測試。

## Recommended Stack

### Backend Core

| Technology | Version / scope | Purpose | Recommendation | Confidence |
|------------|-----------------|---------|----------------|------------|
| Java | 21 | 後端 runtime | 維持現狀。此 milestone 不碰語言/runtime。 | HIGH |
| Spring Boot | 4.0.4 | 組合 app、auto config、test slices | 維持 parent BOM，不額外指定 Spring Security 版本，避免 BOM drift。 | HIGH |
| Spring Security Servlet | Boot-managed, docs current at 7.0.5 | 認證、授權、CORS、CSRF、security test | 使用內建 CSRF/CORS/filter chain，不引入 Sa-Token、Shiro、pac4j、外部 session middleware。 | HIGH |
| `spring-security-oauth2-jose` | 已存在 | JWT encode/decode | 保留既有 ES256 access JWT 與 Redis tokenVersion revocation。新增 cookie transport，不重寫 JWT service。 | HIGH |
| Spring WebMVC | Boot-managed | REST API | 維持 `ApiResponse<T>` envelope。新增 auth/csrf endpoint 也要回 envelope，除非 Spring `CsrfToken` endpoint 特別需要裸 token DTO。 | HIGH |
| Redis / `StringRedisTemplate` | 已存在 | refresh token、auth state、revocation | 繼續當 refresh/session adjunct store。新增 refresh rotation 與 cookie logout 清除，不新增 JDBC session table。 | HIGH |
| `ResponseCookie` / `CookieCsrfTokenRepository` | Spring Framework / Security built-in | Set-Cookie 與 CSRF token persistence | 用框架內建 cookie builder/repository。Auth cookies 必須 `HttpOnly`；CSRF token cookie 必須可被 JS 讀取或用 `/csrf` DTO 回傳。 | HIGH |

### Frontend Core

| Technology | Version / scope | Purpose | Recommendation | Confidence |
|------------|-----------------|---------|----------------|------------|
| Vue | 3.5.34 | SPA UI | 維持現狀。Auth state 用 composable/Pinia store，不為 auth 引入新 framework。 | HIGH |
| Vue Router | 5.0.7 | protected route / navigation guard | 使用 `router.beforeEach` + route meta 做 API mode protected page guard。不要在每個 page 各自檢查 session。 | MEDIUM |
| Pinia | 3.0.4 | session/user state | 建立 `authStore` 管 `user`, `status`, `restoreSession`, `logout`。不要把 token 放 store。 | HIGH |
| Vite | 8.0.13 | env/mode/build | `VITE_DATA_MODE=api` 才打 backend；新增 `VITE_API_BASE_URL` 或使用 dev proxy。所有 `VITE_*` 視為公開值，不放 secret。 | HIGH |
| Fetch API | Browser built-in | API client | 擴充現有 `apiClient.ts`，預設 `credentials: "include"`、`Accept: application/json`、JSON body、CSRF header、trace id/error parsing。 | HIGH |
| Vitest + jsdom | 4.1.6 / 29.1.1 | frontend unit tests | 測 `fetch` init、CSRF header、refresh retry、401/403 event，不需要 Playwright 作為第一層驗證。 | HIGH |

### Verification Tooling

| Tool | Purpose | Required checks | Confidence |
|------|---------|-----------------|------------|
| `./mvnw -pl stock-start -am test` | 後端單元與 MVC/security 測試 | CSRF missing/invalid/valid、cookie issuance、401/403 envelope、CORS preflight。 | HIGH |
| `./mvnw -pl stock-start -am verify` | 後端整合 gate | Redis refresh token、cookie logout、refresh rotation、trading unsafe request CSRF。 | HIGH |
| `npm run build` in `../../vue/stock-v2/vue-app` | TypeScript + production bundle | API client/env typing 不破。 | HIGH |
| `npm test` in `../../vue/stock-v2/vue-app` | frontend unit tests | API mode client 行為與 auth store。 | HIGH |
| gstack `/browse` or manual browser smoke | browser contract | 登入、refresh、logout、portfolio/trade request with cookies。 | MEDIUM |

## Browser Auth Contract

### Prescriptive Design

1. **Login/register response**
   - Backend sets:
     - `__Host-stock_access` 或 `stock_access`: short TTL, `HttpOnly`, `Secure` in HTTPS, `SameSite=Lax`, `Path=/`.
     - `__Host-stock_refresh` 或 `stock_refresh`: refresh TTL, `HttpOnly`, `Secure` in HTTPS, `SameSite=Lax`, `Path=/api/v1/auth`.
     - `XSRF-TOKEN`: readable by JS, `Secure` in HTTPS, `SameSite=Lax`, `Path=/`.
   - JSON response should return `user` only, not `accessToken` / `refreshToken`, for browser endpoints.
   - Keep existing token-in-body behavior only through an explicit non-browser API mode or separate compatibility path if needed.

2. **Access authentication**
   - Update `JwtAuthenticationFilter` to read bearer token first for API clients, then access cookie for browser requests.
   - Keep Redis `user:auth:{userId}` tokenVersion/status check exactly as today.
   - Do not create `HttpSession`; keep `SessionCreationPolicy.STATELESS`.

3. **Refresh**
   - Add `POST /api/v1/auth/refresh`.
   - Read refresh token only from `HttpOnly` refresh cookie for browser mode.
   - Rotate refresh token on every successful refresh, update Redis, set new access/refresh cookies, and return `user` or empty envelope.
   - Missing/expired refresh returns `401`, not `403`.

4. **Logout**
   - `POST /api/v1/auth/logout` must require CSRF.
   - Browser logout reads refresh cookie server-side, revokes it, clears access/refresh cookies, clears CSRF token if Spring logout handler clears it, and returns `ApiResponse.empty`.
   - Do not require frontend to send refresh token in JSON body for browser logout.

5. **Session restore**
   - Vue startup calls `GET /api/v1/me` with credentials.
   - If `401` from expired access token, client attempts one `POST /api/v1/auth/refresh` with CSRF header, then retries `/me`.
   - If refresh fails, clear frontend user state and stay unauthenticated.

### Cookie Policy

Use `SameSite=Lax` for the normal dev/prod target where frontend and backend are same-site, even if they are different origins such as `localhost:5173` and `localhost:11180`. Prefer same-site production deployment under one registrable domain or reverse proxy path.

Only use `SameSite=None; Secure` if frontend and backend are truly cross-site in production. That mode increases reliance on exact CORS allowlists and CSRF tests, so it should be a deployment exception, not the default.

Use `__Host-` cookie names in HTTPS production if possible: no `Domain`, `Path=/`, `Secure`. For localhost development, allow non-prefixed names or profile-specific `Secure=false` because local HTTP cannot set real secure cookies consistently outside localhost exceptions.

## CSRF Stack

### Recommended Implementation

Use Spring Security CSRF, not a custom filter.

Recommended backend shape:

```java
http
    .csrf(csrf -> csrf
        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
        // use Spring Security SPA CSRF handler pattern if BREACH/XOR token handling is enabled
    );
```

Add `GET /api/v1/csrf` returning the current CSRF token DTO or touching the token so Spring writes the cookie. Permit it before login. Vue should call it on app startup, after login success, after logout success, and before retrying unsafe requests that failed with CSRF-specific `403`.

Unsafe methods requiring CSRF:

- `POST /api/v1/auth/login`
- `POST /api/v1/auth/register`
- `POST /api/v1/auth/refresh`
- `POST /api/v1/auth/logout`
- `POST /api/v1/trades`
- `POST /api/v1/backtests/runs`
- `POST /api/v1/market/ws/ticket`
- future `PUT/PATCH/DELETE` settings or AI/broker endpoints

Safe methods (`GET`, `HEAD`, `OPTIONS`) should not require CSRF.

### Error Contract

Add a stable error code if absent:

- `AUTH_CSRF_INVALID`: HTTP 403, message suitable for frontend session recovery.

`AccessDeniedHandler` should map `InvalidCsrfTokenException` and `MissingCsrfTokenException` to that code. Other authorization failures stay `AUTH_FORBIDDEN`. This matters because Vue can refresh CSRF token and retry only for CSRF failures; it should not retry permission failures.

## CORS Guidance

Keep `CorsConfigurationSource` in `SecurityConfig`, but tighten behavior:

- Exact `allowedOrigins`; never use `*` with credentials.
- `allowCredentials(true)` is required for browser cookies.
- Allowed headers should explicitly include `Content-Type`, `X-XSRF-TOKEN`, `X-CSRF-TOKEN`, `X-Trace-Id`, `Idempotency-Key`, and `Authorization`.
- Exposed headers should include `X-Trace-Id`; optionally expose `Idempotency-Key` response echo if implemented.
- Keep `OPTIONS` preflight permitted through CORS before auth. Spring Security docs call out that CORS must run before Security because preflight has no cookies.

Do not treat CORS as CSRF protection. CORS controls browser read/send permission for cross-origin fetch; CSRF is still needed because credentialed browser requests can carry cookies automatically.

## Vue API Client Guidance

### `apiClient.ts`

Evolve the existing shared client instead of duplicating auth logic in domain clients.

Required behavior:

- Prefix paths with `VITE_API_BASE_URL` if set; otherwise use relative `/api/v1`.
- Default `credentials: "include"` in API mode.
- Add JSON headers consistently.
- For unsafe methods, read `XSRF-TOKEN` from `document.cookie` or auth store and set `X-XSRF-TOKEN`.
- Parse backend `ApiResponse<T>` envelope only once.
- Preserve `requestId` from envelope/meta and `X-Trace-Id` response header for diagnostics.
- On `401`, run at most one refresh attempt, then replay the original request if refresh succeeds.
- On CSRF-specific `403`, call `/csrf` once, then replay unsafe request once.
- Emit a typed auth event or throw `ApiClientError` for UI handling; do not redirect inside low-level fetch code.

Domain clients should stay thin:

- `authApi.ts`: `csrf`, `register`, `login`, `me`, `refresh`, `logout`.
- `tradingApi.ts`: `summary`, `holdings`, `trades`, `createTrade`.
- `portfolioApi.ts` only if backend has separate portfolio endpoints; otherwise keep trading-owned API naming.

### Runtime Mode

Current `normalizeRuntimeDataMode` silently maps every unknown value to `mock`. Keep that for local default, but make API integration builds fail on invalid values:

- local dev default: `mock`
- explicit API: `VITE_DATA_MODE=api`
- CI/integration: require `VITE_DATA_MODE` to be either `mock` or `api`; invalid value fails test/build

This prevents an API-mode demo from accidentally passing against mock data.

### Route/Auth State

Use Pinia for auth state:

- `unknown`: app has not called `/me`
- `authenticated`: user DTO loaded
- `anonymous`: `/me` and refresh failed

Use Vue Router global guard with route meta for protected API pages. Vue Router docs support async guards and returning a route redirect; use return values, not legacy `next`, to avoid double-resolution mistakes.

## Portfolio / Trading API Mode Guidance

Treat current backend trading endpoint as **manual executed trade recording**, not order placement.

Frontend should not send current mock-only order lifecycle assumptions directly to backend:

- no pending order
- no partial fill
- no broker order id
- no time-in-force
- no market/limit lifecycle

For this milestone:

- Map order ticket submit to `POST /api/v1/trades` only after UI copy/state makes clear it records an executed trade.
- Add `Idempotency-Key` header for trade creation before serious retry behavior. Backend should persist `(user_id, idempotency_key)` and return existing result on replay.
- After successful trade creation, refetch portfolio summary, holdings, and trade history from backend. Do not mutate mock portfolio projection in API mode.

## What Not To Introduce

| Do not introduce | Why |
|------------------|-----|
| localStorage/sessionStorage token storage | Refresh token is a replayable bearer secret; JS-readable storage makes XSS account takeover easier. |
| A new auth framework or OAuth Authorization Server | Current app already has local email/password, JWT, Redis revocation, and role/permission model. This milestone is transport hardening, not identity-provider work. |
| Server-side `HttpSession` for SPA auth | Would conflict with current stateless JWT + Redis tokenVersion design and increase migration scope. |
| Axios just for credentials/interceptors | Fetch is already used and sufficient. Adding Axios creates churn without solving CSRF/auth design. |
| GraphQL/tRPC/OpenAPI-generated client | Existing backend has stable REST envelope and hand-written clients. Generation can be revisited after auth/trading contracts stabilize. |
| Full BFF layer | Useful for some deployments, but too large for this brownfield milestone. Same-site proxy/deployment plus cookie/CSRF is enough. |
| Full broker/order lifecycle | Backend currently records executed trades. Expanding to broker execution states belongs to a separate security and domain phase. |

## Recommended Implementation Order

1. **Backend contract first**
   - Add cookie properties, CSRF config, `/api/v1/csrf`, cookie writer/clearer, browser refresh/logout behavior.
   - Add security tests for cookies and CSRF before wiring Vue.

2. **Shared Vue client**
   - Add credentials, CSRF header, envelope handling, refresh retry, auth store.
   - Unit test fetch calls with Vitest.

3. **Auth UI/API mode**
   - Register/login/logout/session restore against backend.
   - Add route guard for protected API pages.

4. **Portfolio/trading API mode**
   - Add thin trading adapter.
   - Refetch backend read models after trade creation.
   - Add idempotency before automatic retry of trade creation.

5. **Browser smoke**
   - Verify login -> `/me` -> portfolio -> trade -> refetch -> logout in API mode with actual cookies and CSRF headers.

## Source Notes

| Source | Finding used | Confidence |
|--------|--------------|------------|
| Spring Security 7.0.5 CSRF docs: https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html | CSRF is default for unsafe methods; `CookieCsrfTokenRepository`; SPA `/csrf` endpoint guidance; refresh token after auth/logout; MockMvc CSRF testing. | HIGH |
| Spring Security CORS docs: https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html | CORS must run before Security because preflight has no cookies; use `CorsConfigurationSource`; exact CORS config for credentialed browser apps. | HIGH |
| MDN Fetch `Request.credentials`: https://developer.mozilla.org/en-US/docs/Web/API/Request/credentials | Browser fetch needs `credentials: "include"` for cross-origin credentials and `Set-Cookie` handling. | HIGH |
| MDN `Set-Cookie`: https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Set-Cookie | `HttpOnly`, `Secure`, `SameSite`, cookie prefixes, and `Set-Cookie` visibility constraints. | HIGH |
| Vite env docs: https://vite.dev/guide/env-and-mode | `VITE_*` variables are client-exposed strings; modes/env file behavior; do not store secrets in frontend env. | HIGH |
| Vue Router navigation guards: https://router.vuejs.org/guide/advanced/navigation-guards.html | Async global guards can redirect/cancel; prefer return style over legacy `next`. | MEDIUM |
| Vitest mocking docs: https://vitest.dev/guide/mocking.html | Use `vi` to mock fetch/env behavior; reset mocks/env between tests. | HIGH |

## Open Questions

- 是否要讓 browser login/register 繼續回傳 token body 作為相容模式？建議不要，除非已有外部 client 依賴同一路徑。
- Production frontend/backend 是否會同 site 部署？若不是，cookie 需要 `SameSite=None; Secure`，CORS/CSRF 測試要升級為強制 gate。
- Refresh token 是否已支援多裝置 session？若未支援，這個 milestone 至少要定義「單一 refresh cookie logout」與「revoke all」是否延後。
- Trading idempotency 是否納入同一 milestone？若前端會自動 retry `POST /trades`，就應納入；否則至少要禁止 trade create 自動 replay。
