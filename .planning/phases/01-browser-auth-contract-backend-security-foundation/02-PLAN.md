---
phase: 01
plan: 02
type: tdd
wave: 2
depends_on: [01]
files_modified:
  - stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java
  - stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthResponse.java
  - stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/BrowserSessionResponse.java
  - stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/TokenResponse.java
  - stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java
  - stock-start/src/test/java/dowob/xyz/stockwebv2/start/BrowserAuthFlowIT.java
  - stock-start/src/test/java/dowob/xyz/stockwebv2/start/AuthFlowIT.java
autonomous: true
requirements: [AUTH-01, AUTH-02, AUTH-06, SEC-01, SEC-05]
---

# Plan 02 — Browser Cookie Login/Register and Cookie Authentication

<objective>
Implement browser-safe register/login and cookie-based protected GET authentication while keeping the token body out of browser responses.
</objective>

<must_haves>
<truth id="D-01">Browser sessions use access and refresh HttpOnly cookies; Vue must not store access or refresh tokens.</truth>
<truth id="D-02">Backend reads browser access token from cookie while preserving Authorization Bearer for non-browser API clients.</truth>
<truth id="D-14">Browser cookie auth and non-browser bearer-token issuance use separate endpoint contracts.</truth>
<truth id="D-15">Browser register/login set HttpOnly cookies and return user/session metadata, not access/refresh token bodies.</truth>
</must_haves>

<threat_model>
| Threat | Mitigation in this plan |
|--------|-------------------------|
| XSS steals refresh token from response body | Browser register/login response body excludes refresh token and auth cookies are HttpOnly. |
| Cookie and bearer paths become ambiguous | Cookie auth applies only through browser endpoints; bearer token bodies move to explicit token endpoint in Plan 05. |
| Existing protected controllers learn transport details | Cookie parsing stays in `SecurityConfig` filter/helper, not feature controllers. |
</threat_model>

<tasks>
<task id="01-02-01" type="tdd">
<title>RED: Browser register/login set cookies and omit token body</title>
<read_first>
- stock-start/src/test/java/dowob/xyz/stockwebv2/start/BrowserAuthFlowIT.java
- stock-start/src/test/java/dowob/xyz/stockwebv2/start/AuthFlowIT.java
- stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java
- stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthResponse.java
</read_first>
<action>
Refine the red tests from Plan 01 so browser `POST /api/v1/auth/register` and `POST /api/v1/auth/login` assert `Set-Cookie` headers for access and refresh cookies, both auth cookies include `HttpOnly`, and `$.data.refreshToken` plus `$.data.accessToken` are absent. Assert `$.data.user.email` remains present.
</action>
<verify>
Run `./mvnw -pl stock-start -am test -Dtest=BrowserAuthFlowIT --fail-at-end --no-transfer-progress` and confirm these tests fail before implementation.
</verify>
<acceptance_criteria>
- `BrowserAuthFlowIT` contains browser register/login cookie tests.
- Tests assert no `$.data.refreshToken` and no `$.data.accessToken` for browser endpoints.
- The red run fails before production changes.
</acceptance_criteria>
</task>

<task id="01-02-02" type="tdd">
<title>GREEN: Add browser session response and cookie writer</title>
<read_first>
- stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java
- stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthResponse.java
- stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/service/RefreshTokenService.java
- stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/security/JwtService.java
- stock-start/src/main/resources/application.yaml
</read_first>
<action>
Introduce `BrowserSessionResponse` with user/session metadata but no token fields. Add a focused cookie helper/properties class using the `stock.auth.cookie.*` config from Plan 01. Update browser register/login methods to create access token through `JwtService`, issue refresh token through `RefreshTokenService`, set access/refresh auth cookies on `HttpServletResponse`, and return `ApiResponse<BrowserSessionResponse>`.
</action>
<verify>
Run `./mvnw -pl stock-start -am test -Dtest=BrowserAuthFlowIT --fail-at-end --no-transfer-progress`.
</verify>
<acceptance_criteria>
- Browser register/login responses set two auth cookies.
- Auth cookies include `HttpOnly`.
- Browser register/login JSON has `data.user` and no `data.accessToken` or `data.refreshToken`.
- Cookie names and attributes come from one properties/helper abstraction, not duplicated string construction in each controller method.
</acceptance_criteria>
</task>

<task id="01-02-03" type="tdd">
<title>RED/GREEN: Authenticate protected GET with access cookie</title>
<read_first>
- stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java
- stock-start/src/test/java/dowob/xyz/stockwebv2/start/BrowserAuthFlowIT.java
- stock-start/src/test/java/dowob/xyz/stockwebv2/start/AuthFlowIT.java
- stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/security/JwtService.java
</read_first>
<action>
Add a failing test that calls `GET /api/v1/me` with only the access cookie from browser login and no `Authorization` header. Then update the authentication filter to read the configured access cookie only when no bearer header is present, parse it with `JwtService`, enforce Redis token version/status exactly like bearer tokens, and set the same authorities.
</action>
<verify>
Run `./mvnw -pl stock-start -am test -Dtest=BrowserAuthFlowIT,AuthFlowIT --fail-at-end --no-transfer-progress`.
</verify>
<acceptance_criteria>
- `GET /api/v1/me` succeeds with access cookie only.
- Existing malformed bearer token test still returns `AUTH_INVALID_CREDENTIALS`.
- Existing bearer-authenticated protected requests still work.
- Cookie token invalid/version mismatch returns the existing security envelope, not a servlet default error.
</acceptance_criteria>
</task>
</tasks>

<verification>
- `./mvnw -pl stock-start -am test -Dtest=BrowserAuthFlowIT,AuthFlowIT --fail-at-end --no-transfer-progress`
</verification>

<success_criteria>
- Browser register/login issue HttpOnly access/refresh cookies.
- Browser response body does not expose access or refresh token values.
- Protected GET requests work with access cookie only.
- Bearer regression tests remain green.
</success_criteria>
