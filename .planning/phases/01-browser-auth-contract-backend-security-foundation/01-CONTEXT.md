# Phase 1: Browser Auth Contract & Backend Security Foundation - Context

**Gathered:** 2026-05-30
**Status:** Ready for planning

<domain>
## Phase Boundary

This phase delivers the backend security foundation for browser-safe authentication. It changes the backend auth contract so browser users authenticate with httpOnly cookies plus CSRF protection, while non-browser API clients use a separate bearer-token issuance path. The phase must include contract documentation and backend tests for cookie auth, CSRF, CORS, refresh/logout, and stable 401/403 envelopes.

This phase does not implement the Vue API client, portfolio UI wiring, manual trade creation, broker order lifecycle, or multi-device session management UI/API.

</domain>

<decisions>
## Implementation Decisions

### Cookie Session Model
- **D-01:** Browser session uses both access and refresh httpOnly cookies. Vue must not store access tokens or refresh tokens.
- **D-02:** Backend authentication must support reading the browser access token from cookie while preserving `Authorization: Bearer` for non-browser API clients.
- **D-03:** Cookie `SameSite`, `Secure`, and optional domain are configurable per environment. Default policy should be `SameSite=Lax`; `SameSite=None; Secure` is available only when deployment requires true cross-site cookies.
- **D-04:** Phase 1 redefines the base session policy without adding multi-device session management. Locked defaults for planning: access token TTL `PT15M`, refresh absolute TTL `P14D`, refresh rotation on every `/api/v1/auth/refresh`, and no separate idle timeout in Phase 1.

### Refresh and Logout Semantics
- **D-05:** `/api/v1/auth/refresh` rotates the refresh cookie/token on each successful refresh.
- **D-06:** Refresh replay, missing refresh state, or invalid refresh token revokes the current browser session, clears auth cookies, and returns a 401 `ApiResponse` envelope.
- **D-07:** Logout revokes only the current browser session and clears the current browser cookies. It must not revoke all user sessions in Phase 1.
- **D-08:** Full multi-device session management, session list, and revoke-specific-device are deferred to v2.

### CSRF Contract
- **D-09:** `/api/v1/auth/refresh` requires CSRF. Refresh is an unsafe cookie-authenticated POST and follows the same rule as other unsafe browser requests.
- **D-10:** Backend provides a public/safe CSRF bootstrap path, preferably `GET /api/v1/csrf`, or an equivalent safe GET that issues the readable CSRF token.
- **D-11:** CSRF naming follows Spring convention: readable cookie `XSRF-TOKEN`, request header `X-XSRF-TOKEN`.
- **D-12:** CSRF protection applies to all cookie-authenticated unsafe requests (`POST`, `PUT`, `PATCH`, `DELETE`). Non-browser bearer-token requests may bypass CSRF.
- **D-13:** CSRF failure returns HTTP 403 as the standard `ApiResponse` envelope with a CSRF-specific error code and trace/request id. It must be distinguishable from ordinary authorization failure.

### Browser and Non-Browser Auth Endpoints
- **D-14:** Browser cookie auth and non-browser bearer-token issuance use separate endpoint contracts rather than one endpoint whose behavior changes by magic header.
- **D-15:** Browser endpoints remain `/api/v1/auth/register`, `/api/v1/auth/login`, `/api/v1/auth/refresh`, and `/api/v1/auth/logout`. Register/login set httpOnly cookies and return user/session metadata, not access/refresh token bodies.
- **D-16:** A separate explicit non-browser token endpoint, such as `/api/v1/auth/token`, returns bearer-token JSON and does not set browser auth cookies.
- **D-17:** OpenAPI/docs/tests must clearly split browser cookie flow from non-browser token flow.

### Error Response Contract
- **D-18:** 401/403 auth/security responses use stable error codes that frontend Phase 2 can route on without exposing unnecessary internal state.
- **D-19:** Error-code planning should include at least unauthenticated, token expired, refresh token invalid, forbidden, and CSRF token invalid semantics, reusing existing `ErrorCode` entries where appropriate and adding only missing codes.
- **D-20:** All auth/security failures from this phase must preserve the project-standard `ApiResponse<T>` envelope and trace/request id behavior.

### the agent's Discretion
- The exact Spring Security class/function decomposition is up to the planner, as long as `stock-start` owns HTTP-level security and `stock-module-user` owns auth/session lifecycle.
- The exact cookie property names are up to the planner, provided they support environment-specific `SameSite`, `Secure`, and domain settings and are documented.
- The exact DTO names for browser session metadata and non-browser token response are up to the planner, provided browser responses do not expose refresh tokens.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Project and Phase Scope
- `.planning/PROJECT.md` — Core value, active requirements, constraints, and out-of-scope boundaries.
- `.planning/REQUIREMENTS.md` — v1 requirement IDs for Phase 1 and v2 deferred items.
- `.planning/ROADMAP.md` — Phase 1 goal, requirement mapping, and success criteria.
- `.planning/STATE.md` — Current milestone state and deferred items.

### Research and Codebase Maps
- `.planning/research/SUMMARY.md` — Research summary for auth/CSRF/API-mode milestone, including roadmap implications and verification requirements.
- `.planning/codebase/INTEGRATIONS.md` — Existing auth, Redis, CORS, frontend API adapter, and environment integration points.
- `.planning/codebase/CONCERNS.md` — Known security gaps around bearer-only auth, disabled CSRF, cookie integration, and frontend credentials.
- `.planning/codebase/ARCHITECTURE.md` — Backend module boundaries and request/auth data flow.
- `.planning/codebase/TESTING.md` — Existing backend/frontend verification patterns and gaps.

### Existing Source Areas
- `stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java` — Current Spring Security, CORS, JWT filter, and CSRF-disabled behavior.
- `stock-start/src/main/java/dowob/xyz/stockwebv2/start/error/GlobalExceptionHandler.java` — Existing API error envelope handling.
- `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java` — Current register/login/me/logout endpoints.
- `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/service/AuthService.java` — Current auth service behavior.
- `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/service/RefreshTokenService.java` — Current Redis refresh token and auth state lifecycle.
- `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/security/JwtService.java` — Existing ES256 JWT generation/parsing.
- `stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/ErrorCode.java` — Existing error code catalog.
- `../vue/stock-v2/vue-app/src/services/apiClient.ts` — Frontend Phase 2 will consume this contract; Phase 1 docs should describe expectations.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `JwtService` already owns access-token creation and parsing; Phase 1 should add cookie transport without rewriting JWT signing.
- `RefreshTokenService` already stores refresh tokens and auth state in Redis; Phase 1 should extend it for rotation/current-session revocation semantics.
- `ApiResponse`, `ApiMeta`, `ErrorCode`, `TraceIdFilter`, and `GlobalExceptionHandler` already provide the standard envelope and trace behavior.
- `SecurityConfig` already centralizes CORS, method security, and JWT auth filtering; this is the integration point for cookie token lookup and CSRF.

### Established Patterns
- Feature modules keep domain/session lifecycle in `stock-module-user`; app-level HTTP security belongs in `stock-start`.
- Protected controllers rely on Spring Security `Authentication` and `@PreAuthorize`; Phase 1 should not leak cookie/CSRF concerns into trading/portfolio modules.
- Redis keys already exist for `user:auth:{userId}`, refresh tokens, and refresh indexes; new session behavior should preserve token-version revocation invariants.
- Backend tests commonly live in `stock-start/src/test/java` for full app integration behavior and module tests for service/domain behavior.

### Integration Points
- Browser cookie auth starts at `AuthController` register/login/refresh/logout and is enforced by `SecurityConfig`.
- CSRF errors must be translated into the project-standard JSON envelope before reaching the frontend.
- CORS must allow the configured Vue origin with credentials and expose/allow relevant headers such as `X-XSRF-TOKEN`, `X-Trace-Id`, `Idempotency-Key`, and `Authorization` where appropriate.
- Non-browser bearer-token flow should remain explicit and separate so existing API-client semantics are not confused with browser cookie semantics.

</code_context>

<specifics>
## Specific Ideas

- Prefer Spring Security's standard SPA CSRF convention (`XSRF-TOKEN` cookie + `X-XSRF-TOKEN` header) over custom names.
- Prefer a safe `GET /api/v1/csrf` bootstrap endpoint so frontend Phase 2 can implement `ensureCsrfToken()` before unsafe calls and refresh.
- Browser register/login response should return user/session metadata only. Token response bodies belong to the separate non-browser token endpoint.
- Phase 1 should document the frontend's expected behavior even though frontend implementation happens in Phase 2.

</specifics>

<deferred>
## Deferred Ideas

- Full multi-device session management: session list, revoke specific device, revoke all devices UI/API, and richer session metadata belong in v2.
- True broker/order lifecycle remains out of scope for this phase.
- Frontend `apiClient.ts`, auth store, route guards, portfolio adapters, and trading adapters are Phase 2+ work.

</deferred>

---

*Phase: 1-Browser Auth Contract & Backend Security Foundation*
*Context gathered: 2026-05-30*
