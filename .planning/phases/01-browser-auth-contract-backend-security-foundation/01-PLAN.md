---
phase: 01
plan: 01
type: tdd
wave: 1
depends_on: []
files_modified:
  - stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/ErrorCode.java
  - stock-start/src/main/resources/application.yaml
  - stock-start/src/test/java/dowob/xyz/stockwebv2/start/BrowserAuthFlowIT.java
  - stock-start/src/test/java/dowob/xyz/stockwebv2/start/CorsIT.java
  - ai-docs/browser-auth-contract.md
autonomous: true
requirements: [AUTH-06, SEC-04, SEC-05, VER-04]
---

# Plan 01 — Security Contract, Error Codes, and Test Harness

<objective>
Establish the browser auth contract foundation before behavior changes: stable error code, cookie/CSRF configuration targets, contract documentation skeleton, and red integration tests that pin the security envelope/CORS expectations.
</objective>

<must_haves>
<truth id="D-03">Cookie SameSite, Secure, and optional domain are configurable per environment; default SameSite is Lax.</truth>
<truth id="D-13">CSRF failure returns HTTP 403 as standard ApiResponse with a CSRF-specific error code and trace/request id.</truth>
<truth id="D-17">OpenAPI/docs/tests clearly split browser cookie flow from non-browser token flow.</truth>
<truth id="D-18">401/403 auth/security responses use stable frontend-routable error codes.</truth>
<truth id="D-19">Error-code planning includes unauthenticated, token expired, refresh invalid, forbidden, and CSRF invalid semantics.</truth>
<truth id="D-20">All auth/security failures preserve ApiResponse envelope and trace/request id behavior.</truth>
</must_haves>

<threat_model>
| Threat | Mitigation in this plan |
|--------|-------------------------|
| Frontend cannot distinguish CSRF from forbidden | Add and test a dedicated `AUTH_CSRF_TOKEN_INVALID` 403 code. |
| Credentialed CORS accepts untrusted origins | Extend CORS tests for allowed Vue origin and rejected unknown origin. |
| Contract doc drifts from executable behavior | Add source assertions and red tests before implementation plans fill behavior. |
</threat_model>

<tasks>
<task id="01-01-01" type="tdd">
<title>RED: Add browser auth security contract tests</title>
<read_first>
- AGENTS.md
- CLAUDE.md
- ai-docs/testing-standards.md
- stock-start/src/test/java/dowob/xyz/stockwebv2/start/AuthFlowIT.java
- stock-start/src/test/java/dowob/xyz/stockwebv2/start/CorsIT.java
- stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java
- stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/ErrorCode.java
</read_first>
<action>
Create `stock-start/src/test/java/dowob/xyz/stockwebv2/start/BrowserAuthFlowIT.java` extending `ContainerIT`. Add failing tests that assert: browser register/login `Set-Cookie` includes access and refresh cookies with `HttpOnly`; browser register/login body does not include `refreshToken`; cookie-authenticated `POST /api/v1/auth/logout` without `X-XSRF-TOKEN` returns HTTP 403 with `$.error.code == "AUTH_CSRF_TOKEN_INVALID"`; `GET /api/v1/csrf` returns success and sets readable `XSRF-TOKEN`.
</action>
<verify>
Run `./mvnw -pl stock-start -am test -Dtest=BrowserAuthFlowIT --fail-at-end --no-transfer-progress` and confirm the new tests fail for missing cookie/CSRF behavior, not for compilation errors unrelated to the new test.
</verify>
<acceptance_criteria>
- `BrowserAuthFlowIT.java` exists.
- The test file contains assertions for `AUTH_CSRF_TOKEN_INVALID`, `XSRF-TOKEN`, `X-XSRF-TOKEN`, and `HttpOnly`.
- The focused Maven command exits non-zero before production code changes.
</acceptance_criteria>
</task>

<task id="01-01-02" type="tdd">
<title>GREEN: Add stable CSRF error code and documented config targets</title>
<read_first>
- stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/ErrorCode.java
- stock-start/src/main/resources/application.yaml
- .planning/phases/01-browser-auth-contract-backend-security-foundation/01-CONTEXT.md
- .planning/phases/01-browser-auth-contract-backend-security-foundation/01-RESEARCH.md
</read_first>
<action>
Add `AUTH_CSRF_TOKEN_INVALID(403, "CSRF token invalid")` to `ErrorCode`. Add non-secret default config keys under `stock.auth.cookie` in `application.yaml`: `access-name`, `refresh-name`, `path`, `same-site`, `secure`, `domain`, `access-token-ttl`, and `refresh-token-ttl`, with defaults matching `stock_access`, `stock_refresh`, `/`, `Lax`, `false`, blank domain, `PT15M`, and `P14D`.
</action>
<verify>
Run `./mvnw -pl stock-common,stock-start -am test -Dtest=BrowserAuthFlowIT --fail-at-end --no-transfer-progress`.
</verify>
<acceptance_criteria>
- `ErrorCode.java` contains `AUTH_CSRF_TOKEN_INVALID(403, "CSRF token invalid")`.
- `application.yaml` contains `stock.auth.cookie.access-name`, `refresh-name`, `same-site`, `secure`, `access-token-ttl`, and `refresh-token-ttl`.
- The focused command still fails only on behavior not yet implemented in later plans, not on missing enum/config references.
</acceptance_criteria>
</task>

<task id="01-01-03" type="tdd">
<title>RED/GREEN: Pin credentialed CORS contract</title>
<read_first>
- stock-start/src/test/java/dowob/xyz/stockwebv2/start/CorsIT.java
- stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java
- stock-start/src/main/resources/application.yaml
</read_first>
<action>
Extend `CorsIT` with tests for allowed origin preflight to an unsafe endpoint using `Access-Control-Request-Headers: X-XSRF-TOKEN, Authorization, X-Trace-Id, Idempotency-Key`, and for unknown origin `https://evil.example` not receiving `Access-Control-Allow-Origin`. Keep exact origin behavior and `Access-Control-Allow-Credentials: true` for `http://localhost:5173`.
</action>
<verify>
Run `./mvnw -pl stock-start -am test -Dtest=CorsIT --fail-at-end --no-transfer-progress`.
</verify>
<acceptance_criteria>
- `CorsIT` contains an allowed-origin credentials/header test and an unknown-origin rejection test.
- Allowed-origin response includes `Access-Control-Allow-Origin: http://localhost:5173`.
- Allowed-origin response includes `Access-Control-Allow-Credentials: true`.
- Unknown-origin response does not include `Access-Control-Allow-Origin: https://evil.example`.
</acceptance_criteria>
</task>

<task id="01-01-04" type="execute">
<title>Write the browser auth contract documentation skeleton</title>
<read_first>
- .planning/phases/01-browser-auth-contract-backend-security-foundation/01-CONTEXT.md
- .planning/phases/01-browser-auth-contract-backend-security-foundation/01-RESEARCH.md
- .planning/phases/01-browser-auth-contract-backend-security-foundation/01-UI-SPEC.md
- ai-docs/security.md
</read_first>
<action>
Create `ai-docs/browser-auth-contract.md` with sections for browser endpoints, cookie names/attributes, CSRF bootstrap `GET /api/v1/csrf`, `XSRF-TOKEN`, `X-XSRF-TOKEN`, refresh rotation, logout, 401/403 codes, explicit `/api/v1/auth/token` bearer path, and frontend responsibilities. Mark behavior not implemented yet as "Phase 1 planned behavior" until later tasks complete.
</action>
<verify>
Run `test -f ai-docs/browser-auth-contract.md && rg "X-XSRF-TOKEN|HttpOnly|/api/v1/auth/refresh|/api/v1/auth/token|AUTH_CSRF_TOKEN_INVALID" ai-docs/browser-auth-contract.md`.
</verify>
<acceptance_criteria>
- `ai-docs/browser-auth-contract.md` exists.
- The doc contains `HttpOnly`, `XSRF-TOKEN`, `X-XSRF-TOKEN`, `/api/v1/auth/refresh`, `/api/v1/auth/logout`, `/api/v1/auth/token`, `AUTH_CSRF_TOKEN_INVALID`, and `credentials: "include"`.
- The doc says Vue must not store access tokens or refresh tokens.
</acceptance_criteria>
</task>
</tasks>

<verification>
- `./mvnw -pl stock-start -am test -Dtest=BrowserAuthFlowIT,CorsIT --fail-at-end --no-transfer-progress`
- `test -f ai-docs/browser-auth-contract.md && rg "X-XSRF-TOKEN|HttpOnly|AUTH_CSRF_TOKEN_INVALID" ai-docs/browser-auth-contract.md`
</verification>

<success_criteria>
- Phase has red tests for cookie auth and CSRF before production implementation.
- `AUTH_CSRF_TOKEN_INVALID` is available as a stable 403 code.
- Credentialed CORS behavior is pinned by tests.
- Browser auth contract documentation exists and names the frontend security responsibilities.
</success_criteria>
