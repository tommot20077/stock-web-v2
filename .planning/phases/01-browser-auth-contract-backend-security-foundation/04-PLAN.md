---
phase: 01
plan: 04
type: tdd
wave: 4
depends_on: [03]
files_modified:
  - stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java
  - stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/LogoutRequest.java
  - stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/service/RefreshTokenService.java
  - stock-start/src/test/java/dowob/xyz/stockwebv2/start/BrowserAuthFlowIT.java
  - stock-start/src/test/java/dowob/xyz/stockwebv2/start/AuthPersistenceIT.java
  - ai-docs/browser-auth-contract.md
autonomous: true
requirements: [AUTH-05, AUTH-06, SEC-02, SEC-05, VER-04]
---

# Plan 04 — Refresh Rotation and Logout Current-Session Revocation

<objective>
Implement browser refresh and logout semantics: refresh rotates cookies/tokens with CSRF, invalid/replayed refresh clears the browser session with 401 envelope, and logout revokes only the current browser session.
</objective>

<must_haves>
<truth id="D-04">Defaults are access TTL `PT15M`, refresh absolute TTL `P14D`, refresh rotation on every refresh, and no separate idle timeout.</truth>
<truth id="D-05">`/api/v1/auth/refresh` rotates the refresh cookie/token on each successful refresh.</truth>
<truth id="D-06">Refresh replay, missing state, or invalid refresh token revokes the current browser session, clears auth cookies, and returns 401 ApiResponse.</truth>
<truth id="D-07">Logout revokes only the current browser session and clears current browser cookies.</truth>
<truth id="D-08">Full multi-device session management remains deferred to v2.</truth>
</must_haves>

<threat_model>
| Threat | Mitigation in this plan |
|--------|-------------------------|
| Stolen refresh token is reused | Refresh rotation deletes the old token and invalid/replay clears browser cookies with 401. |
| Logout accidentally revokes every device | Only the presented refresh cookie/token is revoked in Phase 1. |
| Refresh bypasses CSRF | Refresh endpoint is an unsafe cookie-authenticated POST and requires `X-XSRF-TOKEN`. |
</threat_model>

<tasks>
<task id="01-04-01" type="tdd">
<title>RED: Refresh requires CSRF and rotates cookies</title>
<read_first>
- stock-start/src/test/java/dowob/xyz/stockwebv2/start/BrowserAuthFlowIT.java
- stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java
- stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/service/RefreshTokenService.java
</read_first>
<action>
Add failing tests for `POST /api/v1/auth/refresh`: without `X-XSRF-TOKEN` returns `AUTH_CSRF_TOKEN_INVALID`; with valid refresh cookie and matching CSRF returns success, sets a new access cookie, sets a new refresh cookie, and the new refresh cookie value differs from the old one.
</action>
<verify>
Run `./mvnw -pl stock-start -am test -Dtest=BrowserAuthFlowIT --fail-at-end --no-transfer-progress`.
</verify>
<acceptance_criteria>
- Tests assert refresh requires CSRF.
- Tests assert refresh cookie rotation changes the refresh cookie value.
- Tests assert response body does not expose refresh token.
</acceptance_criteria>
</task>

<task id="01-04-02" type="tdd">
<title>GREEN: Add refresh rotation service behavior</title>
<read_first>
- stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/service/RefreshTokenService.java
- stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/repository/UserRepository.java
- stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/security/JwtService.java
- stock-start/src/test/java/dowob/xyz/stockwebv2/start/AuthPersistenceIT.java
</read_first>
<action>
Add focused refresh rotation behavior to `RefreshTokenService`: read `user:refresh:{token}`, validate `userId` and `tokenVersion` against `user:auth:{userId}`, delete old refresh key and index entry on success, issue a new refresh token, and signal invalid/missing/replay as `AUTH_REFRESH_TOKEN_INVALID`. Keep revoke-all/multi-device APIs out of scope.
</action>
<verify>
Run `./mvnw -pl stock-start -am test -Dtest=AuthPersistenceIT,BrowserAuthFlowIT --fail-at-end --no-transfer-progress`.
</verify>
<acceptance_criteria>
- Old refresh token key is deleted after successful rotation.
- New refresh token key exists with expected TTL and user id.
- Missing or reused refresh token produces `AUTH_REFRESH_TOKEN_INVALID`.
- No revoke-all behavior is added for Phase 1.
</acceptance_criteria>
</task>

<task id="01-04-03" type="tdd">
<title>RED/GREEN: Logout clears browser cookies and revokes current token</title>
<read_first>
- stock-start/src/test/java/dowob/xyz/stockwebv2/start/BrowserAuthFlowIT.java
- stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java
- stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/LogoutRequest.java
- stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/service/RefreshTokenService.java
</read_first>
<action>
Change browser logout to read the current refresh cookie instead of requiring `LogoutRequest.refreshToken` in browser mode. Add/adjust tests so cookie + valid CSRF logout returns success, emits expired access/refresh cookies, deletes only the current `user:refresh:{token}`, and a later cookie `/me` request returns 401 envelope.
</action>
<verify>
Run `./mvnw -pl stock-start -am test -Dtest=BrowserAuthFlowIT,AuthFlowIT --fail-at-end --no-transfer-progress`.
</verify>
<acceptance_criteria>
- Logout succeeds with browser cookies and CSRF header.
- Logout response clears access and refresh cookies using expired/max-age zero cookies.
- Current refresh Redis key is deleted.
- Logout does not require Vue to send refresh token in JSON.
- Existing validation behavior remains covered for any non-browser logout/token path that still uses JSON.
</acceptance_criteria>
</task>

<task id="01-04-04" type="tdd">
<title>RED/GREEN: Invalid refresh clears cookies and returns 401 envelope</title>
<read_first>
- stock-start/src/test/java/dowob/xyz/stockwebv2/start/BrowserAuthFlowIT.java
- stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/service/RefreshTokenService.java
- stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java
</read_first>
<action>
Add tests for invalid, missing Redis state, and replayed refresh cookie. Implement response handling so each returns HTTP 401 with `AUTH_REFRESH_TOKEN_INVALID`, includes `ApiResponse` metadata, and sends clearing cookies for both auth cookies.
</action>
<verify>
Run `./mvnw -pl stock-start -am test -Dtest=BrowserAuthFlowIT --fail-at-end --no-transfer-progress`.
</verify>
<acceptance_criteria>
- Invalid refresh returns HTTP 401 and `AUTH_REFRESH_TOKEN_INVALID`.
- Replay/missing refresh state returns HTTP 401 and clearing cookies.
- Response includes `success=false` and `meta.traceId`.
</acceptance_criteria>
</task>

<task id="01-04-05" type="execute">
<title>Update contract documentation for refresh/logout semantics</title>
<read_first>
- ai-docs/browser-auth-contract.md
- .planning/phases/01-browser-auth-contract-backend-security-foundation/01-CONTEXT.md
</read_first>
<action>
Update `ai-docs/browser-auth-contract.md` with refresh rotation, invalid/replay semantics, logout current-session revocation, cookie clearing, `PT15M` access TTL, `P14D` refresh TTL, and explicit deferral of multi-device session management.
</action>
<verify>
Run `rg "PT15M|P14D|current browser session|multi-device|AUTH_REFRESH_TOKEN_INVALID|clears auth cookies" ai-docs/browser-auth-contract.md`.
</verify>
<acceptance_criteria>
- Contract doc states refresh rotates on every successful `/api/v1/auth/refresh`.
- Contract doc states logout revokes only current browser session in Phase 1.
- Contract doc states multi-device session list/revoke-specific-device is deferred.
</acceptance_criteria>
</task>
</tasks>

<verification>
- `./mvnw -pl stock-start -am test -Dtest=BrowserAuthFlowIT,AuthPersistenceIT,AuthFlowIT --fail-at-end --no-transfer-progress`
- `rg "AUTH_REFRESH_TOKEN_INVALID|PT15M|P14D" ai-docs/browser-auth-contract.md`
</verification>

<success_criteria>
- Refresh rotates cookies/tokens and invalidates old refresh token state.
- Invalid/replayed refresh returns 401 envelope and clears cookies.
- Logout clears cookies and revokes only the current browser session.
- Phase 1 does not add multi-device session management.
</success_criteria>
