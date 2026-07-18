# Phase 1: Browser Auth Contract & Backend Security Foundation - Research

**Researched:** 2026-05-30
**Status:** Ready for planning
**Confidence:** HIGH

## Research Question

What does the planner need to know to implement Phase 1 safely, without creating a half-finished cookie-auth path that lacks CSRF, refresh/logout semantics, stable security envelopes, bearer compatibility, or contract documentation?

## Scope Summary

Phase 1 is a backend-first security contract phase. It must change the browser authentication contract from token bodies toward `HttpOnly` cookies, add double-submit CSRF for cookie-authenticated unsafe requests, preserve explicit non-browser bearer-token access, keep REST responses in `ApiResponse<T>`, and document the contract for Phase 2 frontend work.

The phase should not implement the Vue API client, frontend session store, portfolio UI wiring, trading writes, broker order lifecycle, or multi-device session management. It should still document enough frontend responsibilities for Phase 2 to consume the backend contract.

## Source Findings

### Current Auth Flow

- `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java` currently returns `AuthResponse(accessToken, refreshToken, user)` from register/login.
- `POST /api/v1/auth/logout` currently requires a JSON `LogoutRequest(refreshToken)` and bearer authentication.
- `GET /api/v1/me` resolves the authenticated user through Spring `Authentication`.
- `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/service/AuthService.java` owns registration and password verification only.
- `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/service/RefreshTokenService.java` issues opaque UUID refresh tokens, stores them in Redis as `user:refresh:{token}`, maintains `user:refresh:index:{userId}`, and writes `user:auth:{userId}` token version/status state.

### Current Security Config

- `stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java` disables CSRF globally with `.csrf(AbstractHttpConfigurer::disable)`.
- The authentication filter only reads `Authorization: Bearer ...`; it does not inspect cookies.
- `ApiSecurityErrorWriter` already writes security failures as `ApiResponse.failure(...)` with trace metadata.
- CORS uses exact configured origins from `stock.cors.allowed-origins`, allows credentials, allows all request headers, and exposes only `X-Trace-Id`.
- Public routes currently include register/login, assets, health, OpenAPI, Swagger UI, and WebSocket handshake.

### Error Catalog

- Existing relevant codes: `AUTH_INVALID_CREDENTIALS`, `AUTH_TOKEN_EXPIRED`, `AUTH_REFRESH_TOKEN_INVALID`, `AUTH_FORBIDDEN`, `AUTH_REDIS_UNAVAILABLE`.
- Missing explicit CSRF code: planner should add `AUTH_CSRF_TOKEN_INVALID` or an equivalent stable 403 code so Phase 2 can distinguish CSRF failure from ordinary authorization failure.
- All new error paths should use existing `ApiResponse<T>` shape from `stock-common/src/main/java/dowob/xyz/stockwebv2/common/api/ApiResponse.java`.

### Existing Tests

- `stock-start/src/test/java/dowob/xyz/stockwebv2/start/AuthFlowIT.java` covers bearer register/login/me/logout and malformed bearer token handling.
- `stock-start/src/test/java/dowob/xyz/stockwebv2/start/AuthPersistenceIT.java` covers refresh Redis storage and revocation.
- E2E helpers under `stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/support/` still expect token bodies.
- Phase 1 changes will require updating existing bearer/auth tests and adding new cookie/CSRF tests before production changes, per mandatory TDD.

## Recommended Implementation Shape

### 1. Define Browser Cookie Contract

Create an explicit cookie configuration abstraction rather than scattering cookie constants through controllers:

- Access cookie: `stock_access` or another documented name.
- Refresh cookie: `stock_refresh` or another documented name.
- Both auth cookies must be `HttpOnly`.
- Default `SameSite=Lax`.
- `SameSite=None` must require `Secure=true`.
- Cookie `Secure`, `SameSite`, domain, path, access max-age, and refresh max-age should be configurable through `stock.auth.cookie.*` or similarly scoped properties.
- Access cookie TTL should align with Phase 1 decision `PT15M`, even though current default `stock.jwt.access-token-ttl` is `PT30M`; planner should explicitly include a config update to avoid hidden mismatch.
- Refresh cookie TTL should remain `P14D`.

Recommended files:

- `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/security/JwtProperties.java`
- New `stock-module-user` or `stock-start` cookie properties/writer class
- `stock-start/src/main/resources/application.yaml`
- `.env.example` if it documents public non-secret auth cookie configuration

### 2. Split Browser and Non-Browser Auth Contracts

Phase context locks separate endpoint contracts:

- Browser endpoints stay at `/api/v1/auth/register`, `/api/v1/auth/login`, `/api/v1/auth/refresh`, `/api/v1/auth/logout`.
- Browser register/login should set auth cookies and return user/session metadata only. Response body must not include refresh token.
- Add explicit non-browser token endpoint, for example `POST /api/v1/auth/token`, that returns bearer-token JSON and does not set browser cookies.
- Existing tests and E2E helpers that read token bodies should be moved to the explicit token endpoint or updated where they represent browser flows.

This avoids magic behavior based on headers and gives Phase 2 a stable browser-only contract.

### 3. Add Refresh Rotation and Logout Semantics

`RefreshTokenService` currently issues and revokes refresh tokens but does not rotate tokens through an API refresh endpoint.

Planner should add service methods for:

- Validating a refresh token from the refresh cookie.
- Checking token version against `user:auth:{userId}`.
- Deleting the old refresh token on successful refresh.
- Issuing a new refresh token and access token.
- Detecting replay, missing token state, invalid token, wrong token version, or inactive user.
- Clearing auth cookies on invalid refresh/replay/logout paths.

Phase context says refresh replay, missing state, or invalid refresh revokes the current browser session, clears cookies, and returns 401. Because current Redis data does not store a browser session id, "current browser session" should mean the presented refresh cookie/token. Do not implement revoke-all or multi-device session list in Phase 1.

### 4. Add Cookie-Aware Authentication Without Breaking Bearer

Keep bearer auth explicit and compatible:

- If `Authorization: Bearer ...` is present, authenticate from the bearer token and do not require CSRF solely because bearer auth was used.
- If no bearer header exists, read the access token from the access cookie.
- Mark cookie-authenticated requests so CSRF enforcement can apply to unsafe methods only when authentication is cookie-based.
- Continue building `UsernamePasswordAuthenticationToken` with role and permission authorities from JWT claims.
- Preserve fail-closed Redis behavior and `AUTH_REDIS_UNAVAILABLE`.

Planner should avoid leaking cookie parsing into feature controllers. This belongs in `stock-start` HTTP security/filter code or a focused infrastructure helper.

### 5. Use Spring Security CSRF Conventions

Preferred contract from context:

- Readable CSRF cookie: `XSRF-TOKEN`
- Request header: `X-XSRF-TOKEN`
- Safe bootstrap endpoint: `GET /api/v1/csrf`
- CSRF applies to cookie-authenticated unsafe `POST`, `PUT`, `PATCH`, `DELETE`.
- Non-browser bearer requests may bypass CSRF.
- CSRF failures return HTTP 403 `ApiResponse` with a CSRF-specific code and trace metadata.

Implementation can use Spring Security CSRF support and a custom failure handler/error writer rather than a broad custom security mechanism. The planner must ensure `GET /api/v1/csrf` causes a token to be issued and is public/safe.

### 6. CORS Needs Credentialed Browser Contract Details

Current CORS is close but incomplete for the new contract:

- Keep exact allowlisted origins; do not use wildcard with credentials.
- Keep `allowCredentials(true)`.
- Ensure preflight allows `X-XSRF-TOKEN`, `Authorization`, `X-Trace-Id`, and `Idempotency-Key`. `setAllowedHeaders(List.of("*"))` may be acceptable, but the contract docs/tests should prove the required headers.
- Expose `X-Trace-Id` and any request id header intentionally consumed by frontend.
- Add tests for allowed Vue origin and rejected unknown origin.

### 7. Contract Documentation

Phase 1 must create or update a contract doc that Phase 2 can implement against. Recommended path:

- `ai-docs/browser-auth-contract.md` or another project docs path chosen by the planner.

Minimum content:

- Browser endpoints and HTTP methods.
- Cookie names, attributes, TTLs, and environment properties.
- `GET /api/v1/csrf`, `XSRF-TOKEN`, `X-XSRF-TOKEN`.
- Refresh rotation behavior and invalid/replay behavior.
- Logout behavior and cookie clearing.
- 401/403 error codes and examples in `ApiResponse` envelope.
- Explicit non-browser token endpoint and bearer compatibility.
- Frontend responsibilities for Phase 2: `credentials: "include"`, bootstrap CSRF before unsafe calls/refresh/logout, no token storage, one refresh retry.
- Portfolio/trading DTO references can be summarized from existing backend APIs, but actual frontend adapters remain Phase 3/4.

## Existing Patterns to Preserve

- Keep HTTP-level security in `stock-start`.
- Keep auth/session lifecycle in `stock-module-user`.
- Keep feature modules unaware of cookie/CSRF mechanics.
- Keep `ApiResponse<T>`, `ApiMeta`, `ApiError`, and `TraceIdFilter` behavior.
- Keep constructor injection with final fields.
- Use `BusinessException` and `ErrorCode` for user-visible failures where Spring Security is not already handling the path.
- Write backend JavaDoc/comments in Traditional Chinese when adding public classes/methods.
- Use Maven wrapper commands from backend root for verification.

## Required TDD Plan Inputs

The planner must force RED/GREEN/REFACTOR for all production changes. Suggested first red tests:

1. `AuthFlowIT` or new `BrowserAuthFlowIT`: browser register/login sets `Set-Cookie` for httpOnly access/refresh cookies and response body has no `refreshToken`.
2. `BrowserAuthFlowIT`: `GET /api/v1/me` succeeds with access cookie and no Authorization header.
3. `BrowserAuthFlowIT`: protected unsafe endpoint with cookie but no CSRF returns 403 envelope with CSRF code.
4. `BrowserAuthFlowIT`: same unsafe endpoint with valid `XSRF-TOKEN` cookie/header reaches business validation or success.
5. `BrowserAuthFlowIT`: `POST /api/v1/auth/refresh` requires CSRF, rotates refresh cookie, and invalidates old refresh token.
6. `BrowserAuthFlowIT`: refresh replay/invalid refresh clears cookies and returns 401 envelope.
7. `BrowserAuthFlowIT`: logout with cookie + CSRF clears cookies and revokes current refresh token.
8. `AuthFlowIT`: `POST /api/v1/auth/token` returns bearer JSON and does not set auth cookies.
9. CORS integration test: allowed Vue origin credentials preflight passes with `X-XSRF-TOKEN`; unknown origin is not allowed.
10. Existing bearer tests: bearer protected request still succeeds without CSRF.

## Validation Architecture

Phase 1 should use layered validation:

| Layer | Purpose | Suggested files | Command |
|-------|---------|-----------------|---------|
| Unit/service | Refresh rotation, invalid/replay behavior, cookie property validation | `stock-module-user/src/test/java/...`, `stock-infrastructure/src/test/java/...` | `./mvnw -pl stock-module-user,stock-infrastructure -am test --fail-at-end --no-transfer-progress` |
| App integration | Cookie issuance, cookie auth, CSRF success/failure, CORS, 401/403 envelopes | `stock-start/src/test/java/dowob/xyz/stockwebv2/start/BrowserAuthFlowIT.java` | `./mvnw -pl stock-start -am verify -Dspring-boot.repackage.skip=true --fail-at-end --no-transfer-progress` |
| Regression | Existing bearer and auth flows remain valid | `stock-start/src/test/java/dowob/xyz/stockwebv2/start/AuthFlowIT.java`, E2E helpers | `./mvnw test --fail-at-end --no-transfer-progress` |
| Documentation | Phase 2 contract is implementable | `ai-docs/browser-auth-contract.md` | Source assertions in plan acceptance criteria |

Nyquist sampling guidance:

- Every task that changes security/auth behavior should include an automated focused test command.
- No production code task may lack a preceding test task unless it is documentation-only.
- A final verification task should run `./mvnw -pl stock-start -am verify -Dspring-boot.repackage.skip=true --fail-at-end --no-transfer-progress`.
- The final phase verification should also run `./mvnw test --fail-at-end --no-transfer-progress` if local runtime permits.

## Threat Model

| Threat | Risk | Required mitigation |
|--------|------|---------------------|
| CSRF against cookie-authenticated unsafe endpoints | Browser auto-sends cookies cross-site | Double-submit CSRF for cookie unsafe methods; 403 CSRF-specific envelope |
| XSS exfiltration of refresh token | JavaScript-readable refresh token enables replay | Refresh token only in `HttpOnly` cookie; browser responses omit refresh token |
| Bearer/cookie ambiguity | Clients may accidentally get weaker security path | Explicit `/auth/token` non-browser endpoint; browser endpoints set cookies |
| Refresh replay | Stolen or reused refresh token extends session | Rotate refresh on every refresh; revoke/clear presented browser session on invalid/replay |
| Overbroad CORS | Credentialed requests from untrusted origins | Exact configured origins, credentialed CORS tests, no wildcard with credentials |
| Inconsistent security errors | Frontend cannot route auth/CSRF failures | Stable 401/403 `ApiResponse` codes and trace metadata |
| Redis outage | Auth state unavailable | Preserve fail-closed `AUTH_REDIS_UNAVAILABLE` behavior |

## Planning Recommendations

Split Phase 1 into plans that keep blast radius controlled:

1. Security contract and config foundation: properties, error code, docs skeleton, tests for cookie attributes and CORS/CSRF envelopes.
2. Browser cookie auth flow: cookie writer, browser register/login responses, cookie authentication filter path, `/me` with cookie.
3. CSRF and unsafe request enforcement: `GET /api/v1/csrf`, Spring CSRF repository/handler/failure envelope, unsafe method tests.
4. Refresh/logout rotation semantics: refresh endpoint, rotation/replay behavior, logout current-session revoke and cookie clearing.
5. Non-browser bearer compatibility and documentation closeout: `/auth/token`, existing test helper migration, contract doc completion, final Maven verification.

The planner may merge or split these, but every plan must preserve TDD sequencing and explicitly cover all Phase 1 requirement IDs:

`AUTH-01`, `AUTH-02`, `AUTH-05`, `AUTH-06`, `AUTH-07`, `SEC-01`, `SEC-02`, `SEC-03`, `SEC-04`, `SEC-05`, `VER-04`.

## Open Questions for Execution

- Exact cookie names are discretionary but must be documented before implementation tasks rely on them.
- Decide whether `POST /api/v1/auth/token` reuses `LoginRequest` or receives a token-specific request DTO.
- Decide whether refresh token replay revokes only the presented token or all tokens for the same user. Phase context says current browser session only, so revoke-all should remain out of scope unless Yuan explicitly changes the decision.
- Decide whether existing register/login endpoints remain public unsafe CSRF-exempt because they do not depend on existing auth cookies. If they also set CSRF cookies, tests must pin behavior.

## Research Complete

Phase 1 has enough local source and planning context to produce executable plans. No external web browsing was required; the project research summary already captured the relevant Spring Security/browser references, and this phase research verified them against local code.

## RESEARCH COMPLETE
