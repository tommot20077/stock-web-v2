---
phase: 01
plan: 05
type: tdd
wave: 5
depends_on: [04]
files_modified:
  - stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java
  - stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/TokenResponse.java
  - stock-start/src/test/java/dowob/xyz/stockwebv2/start/AuthFlowIT.java
  - stock-start/src/test/java/dowob/xyz/stockwebv2/start/BrowserAuthFlowIT.java
  - stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/support/AuthE2EHelper.java
  - stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/support/AbstractWsE2ETest.java
  - ai-docs/browser-auth-contract.md
autonomous: true
requirements: [AUTH-01, AUTH-02, AUTH-05, AUTH-06, AUTH-07, SEC-01, SEC-02, SEC-03, SEC-04, SEC-05, VER-04]
---

# Plan 05 — Bearer Compatibility, Documentation Closeout, and Final Verification

<objective>
Make non-browser bearer-token issuance explicit, migrate tests/helpers to the new split contract, complete documentation, and run full Phase 1 verification.
</objective>

<must_haves>
<truth id="D-14">Browser cookie auth and non-browser bearer-token issuance use separate endpoint contracts.</truth>
<truth id="D-16">A separate explicit non-browser token endpoint returns bearer-token JSON and does not set browser cookies.</truth>
<truth id="D-17">OpenAPI/docs/tests clearly split browser cookie flow from non-browser token flow.</truth>
<truth id="D-18">401/403 auth/security responses use stable frontend-routable codes.</truth>
<truth id="D-20">All auth/security failures preserve ApiResponse and trace/request id behavior.</truth>
</must_haves>

<threat_model>
| Threat | Mitigation in this plan |
|--------|-------------------------|
| Non-browser clients scrape browser endpoints for tokens | Add explicit `/api/v1/auth/token` for bearer JSON and keep browser endpoints token-body-free. |
| Existing tests mask browser contract regressions | Migrate helpers to the intended endpoint per scenario and keep browser-specific tests separate. |
| Final docs omit frontend obligations | Verify docs name cookies, CSRF, credentials, refresh/logout, error codes, and bearer path. |
</threat_model>

<tasks>
<task id="01-05-01" type="tdd">
<title>RED: Pin explicit non-browser token endpoint</title>
<read_first>
- stock-start/src/test/java/dowob/xyz/stockwebv2/start/AuthFlowIT.java
- stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java
- stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/LoginRequest.java
- stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthResponse.java
</read_first>
<action>
Add failing tests for `POST /api/v1/auth/token`: valid credentials return `data.accessToken`, `data.refreshToken`, and `data.user`; response does not set configured browser auth cookies; invalid credentials return 401 `AUTH_INVALID_CREDENTIALS`.
</action>
<verify>
Run `./mvnw -pl stock-start -am test -Dtest=AuthFlowIT --fail-at-end --no-transfer-progress` and confirm token endpoint tests fail before implementation.
</verify>
<acceptance_criteria>
- `AuthFlowIT` contains tests for `/api/v1/auth/token`.
- Tests assert token endpoint response body includes both token fields.
- Tests assert token endpoint does not set browser auth cookies.
</acceptance_criteria>
</task>

<task id="01-05-02" type="tdd">
<title>GREEN: Implement explicit token endpoint</title>
<read_first>
- stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java
- stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthResponse.java
- stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/LoginRequest.java
- stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/service/AuthService.java
- stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/service/RefreshTokenService.java
- stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/security/JwtService.java
</read_first>
<action>
Add `POST /api/v1/auth/token` using `LoginRequest` or a token-specific request DTO. Return `TokenResponse` or preserved `AuthResponse` with `accessToken`, `refreshToken`, and user metadata. Do not set browser auth cookies from this endpoint. Keep browser register/login token-body-free.
</action>
<verify>
Run `./mvnw -pl stock-start -am test -Dtest=AuthFlowIT,BrowserAuthFlowIT --fail-at-end --no-transfer-progress`.
</verify>
<acceptance_criteria>
- `/api/v1/auth/token` returns bearer token JSON for valid credentials.
- `/api/v1/auth/token` sets no access/refresh auth cookies.
- Browser register/login still set cookies and omit token body.
- Invalid credentials still map to `AUTH_INVALID_CREDENTIALS`.
</acceptance_criteria>
</task>

<task id="01-05-03" type="tdd">
<title>RED/GREEN: Migrate bearer-dependent tests and helpers</title>
<read_first>
- stock-start/src/test/java/dowob/xyz/stockwebv2/start/AuthFlowIT.java
- stock-start/src/test/java/dowob/xyz/stockwebv2/start/BacktestApiIT.java
- stock-start/src/test/java/dowob/xyz/stockwebv2/start/TradingApiIT.java
- stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/support/AuthE2EHelper.java
- stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/support/AbstractWsE2ETest.java
</read_first>
<action>
Update test helpers and bearer-dependent integration/E2E tests to call `/api/v1/auth/token` when they need JSON tokens. Keep browser tests in `BrowserAuthFlowIT` using register/login cookies. Do not make browser endpoints re-expose tokens to keep old helpers working.
</action>
<verify>
Run `./mvnw -pl stock-start -am test --fail-at-end --no-transfer-progress`.
</verify>
<acceptance_criteria>
- No backend test helper reads `data.accessToken` or `data.refreshToken` from browser register/login endpoints.
- Bearer-protected module tests still obtain tokens through `/api/v1/auth/token`.
- BrowserAuthFlowIT remains the only suite asserting browser cookie flow details.
</acceptance_criteria>
</task>

<task id="01-05-04" type="execute">
<title>Finalize browser auth contract docs</title>
<read_first>
- ai-docs/browser-auth-contract.md
- .planning/phases/01-browser-auth-contract-backend-security-foundation/01-CONTEXT.md
- .planning/phases/01-browser-auth-contract-backend-security-foundation/01-UI-SPEC.md
- .planning/REQUIREMENTS.md
</read_first>
<action>
Finalize `ai-docs/browser-auth-contract.md` so it describes auth cookies, CSRF token/header, refresh/logout, 401/403 envelope examples, `/api/v1/auth/token`, portfolio/trading DTO responsibility boundaries, and backend/frontend verification responsibility. Remove stale "planned behavior" wording for completed Phase 1 behaviors.
</action>
<verify>
Run `rg "HttpOnly|SameSite|XSRF-TOKEN|X-XSRF-TOKEN|/api/v1/auth/refresh|/api/v1/auth/logout|/api/v1/auth/token|AUTH_INVALID_CREDENTIALS|AUTH_FORBIDDEN|AUTH_CSRF_TOKEN_INVALID|portfolio|trading|credentials: \"include\"" ai-docs/browser-auth-contract.md`.
</verify>
<acceptance_criteria>
- Documentation includes auth cookies, CSRF header, refresh/logout, 401/403, portfolio/trading DTO responsibility, and verification responsibilities.
- Documentation states Vue must not store access or refresh tokens.
- Documentation states API mode must use `credentials: "include"`.
</acceptance_criteria>
</task>

<task id="01-05-05" type="execute">
<title>Run final backend verification</title>
<read_first>
- stock-start/pom.xml
- pom.xml
- .planning/phases/01-browser-auth-contract-backend-security-foundation/01-VALIDATION.md
</read_first>
<action>
Run focused and full backend verification. Capture exact command output in the phase summary during execution. If Testcontainers/runtime dependencies block the full suite locally, record the blocker and run the largest focused suite that does execute.
</action>
<verify>
Run `./mvnw -pl stock-start -am verify -Dspring-boot.repackage.skip=true --fail-at-end --no-transfer-progress` and `./mvnw test --fail-at-end --no-transfer-progress`.
</verify>
<acceptance_criteria>
- Focused `stock-start` verification command exits 0 or the summary records the exact environmental blocker.
- Root Maven test command exits 0 or the summary records the exact environmental blocker.
- `BrowserAuthFlowIT`, `AuthFlowIT`, `CorsIT`, and `AuthPersistenceIT` are included in verification evidence.
</acceptance_criteria>
</task>
</tasks>

<verification>
- `./mvnw -pl stock-start -am test -Dtest=BrowserAuthFlowIT,AuthFlowIT,CorsIT,AuthPersistenceIT --fail-at-end --no-transfer-progress`
- `./mvnw -pl stock-start -am verify -Dspring-boot.repackage.skip=true --fail-at-end --no-transfer-progress`
- `./mvnw test --fail-at-end --no-transfer-progress`
- `rg "HttpOnly|X-XSRF-TOKEN|/api/v1/auth/token|AUTH_CSRF_TOKEN_INVALID|credentials: \"include\"" ai-docs/browser-auth-contract.md`
</verification>

<success_criteria>
- Browser cookie flow and non-browser bearer flow are explicit and separate.
- All Phase 1 requirements are covered by tests and docs.
- Contract docs are complete enough for Phase 2 frontend session/API client planning.
- Final backend verification evidence is available for `$gsd-verify-work`.
</success_criteria>
