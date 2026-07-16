---
phase: 01
plan: 03
type: tdd
wave: 3
depends_on: [02]
files_modified:
  - stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java
  - stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/CsrfController.java
  - stock-start/src/test/java/dowob/xyz/stockwebv2/start/BrowserAuthFlowIT.java
  - stock-start/src/test/java/dowob/xyz/stockwebv2/start/AuthFlowIT.java
  - ai-docs/browser-auth-contract.md
autonomous: true
requirements: [AUTH-06, SEC-02, SEC-03, SEC-05, VER-04]
---

# Plan 03 — CSRF Bootstrap and Cookie Unsafe Request Enforcement

<objective>
Enable double-submit CSRF for cookie-authenticated unsafe requests, expose a safe CSRF bootstrap path, and preserve bearer-token clients without CSRF.
</objective>

<must_haves>
<truth id="D-09">`/api/v1/auth/refresh` requires CSRF because it is an unsafe cookie-authenticated POST.</truth>
<truth id="D-10">Backend provides a safe CSRF bootstrap path, preferably `GET /api/v1/csrf`.</truth>
<truth id="D-11">CSRF names are readable cookie `XSRF-TOKEN` and request header `X-XSRF-TOKEN`.</truth>
<truth id="D-12">CSRF applies to all cookie-authenticated unsafe requests; non-browser bearer requests may bypass CSRF.</truth>
<truth id="D-13">CSRF failure returns HTTP 403 ApiResponse with CSRF-specific code and trace/request id.</truth>
</must_haves>

<threat_model>
| Threat | Mitigation in this plan |
|--------|-------------------------|
| Cross-site POST uses browser auth cookies | Require matching `XSRF-TOKEN` cookie and `X-XSRF-TOKEN` header for cookie unsafe methods. |
| CSRF failure returns HTML/default Spring error | Route CSRF failures through ApiResponse security error writer with `AUTH_CSRF_TOKEN_INVALID`. |
| Non-browser bearer clients are accidentally blocked | Tests prove bearer `Authorization` requests do not require CSRF. |
</threat_model>

<tasks>
<task id="01-03-01" type="tdd">
<title>RED: Pin CSRF bootstrap and unsafe failure behavior</title>
<read_first>
- stock-start/src/test/java/dowob/xyz/stockwebv2/start/BrowserAuthFlowIT.java
- stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java
- stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/ErrorCode.java
</read_first>
<action>
Add failing tests in `BrowserAuthFlowIT` for `GET /api/v1/csrf` setting readable `XSRF-TOKEN`; cookie-authenticated `POST /api/v1/auth/logout` without header returning 403 `AUTH_CSRF_TOKEN_INVALID`; and the same POST with matching CSRF cookie/header reaching auth/logout handling rather than CSRF rejection.
</action>
<verify>
Run `./mvnw -pl stock-start -am test -Dtest=BrowserAuthFlowIT --fail-at-end --no-transfer-progress` and confirm red failures are behavior failures.
</verify>
<acceptance_criteria>
- Tests mention `GET /api/v1/csrf`, `XSRF-TOKEN`, `X-XSRF-TOKEN`, and `AUTH_CSRF_TOKEN_INVALID`.
- Missing CSRF expects HTTP 403.
- Matching CSRF expects not to fail with `AUTH_CSRF_TOKEN_INVALID`.
</acceptance_criteria>
</task>

<task id="01-03-02" type="tdd">
<title>GREEN: Configure Spring CSRF for cookie browser flow</title>
<read_first>
- stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java
- stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/ErrorCode.java
- stock-start/src/main/java/dowob/xyz/stockwebv2/start/error/GlobalExceptionHandler.java
</read_first>
<action>
Replace global CSRF disable with Spring Security CSRF configured for SPA cookie/header names `XSRF-TOKEN` and `X-XSRF-TOKEN`. Add a CSRF failure handler that writes `AUTH_CSRF_TOKEN_INVALID` through the same `ApiSecurityErrorWriter`. Ignore or bypass CSRF for non-browser bearer-token requests and safe methods. Permit `GET /api/v1/csrf`.
</action>
<verify>
Run `./mvnw -pl stock-start -am test -Dtest=BrowserAuthFlowIT --fail-at-end --no-transfer-progress`.
</verify>
<acceptance_criteria>
- Missing CSRF on cookie unsafe request returns HTTP 403 and `AUTH_CSRF_TOKEN_INVALID`.
- CSRF failure response contains `success=false`, `error.code`, and `meta.traceId`.
- `GET /api/v1/csrf` returns success and sets readable `XSRF-TOKEN`.
- No feature controller contains CSRF parsing code.
</acceptance_criteria>
</task>

<task id="01-03-03" type="tdd">
<title>RED/GREEN: Preserve bearer unsafe requests without CSRF</title>
<read_first>
- stock-start/src/test/java/dowob/xyz/stockwebv2/start/AuthFlowIT.java
- stock-start/src/test/java/dowob/xyz/stockwebv2/start/TradingApiIT.java
- stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java
</read_first>
<action>
Add or update a regression test proving a bearer-authenticated unsafe request, such as `POST /api/v1/auth/logout` through the non-browser token path or another existing bearer-protected POST, does not require `X-XSRF-TOKEN`. If logout is moved to cookie-only in Plan 04, use an existing bearer-protected POST endpoint that remains valid.
</action>
<verify>
Run `./mvnw -pl stock-start -am test -Dtest=AuthFlowIT,TradingApiIT --fail-at-end --no-transfer-progress`.
</verify>
<acceptance_criteria>
- A bearer unsafe request succeeds or reaches normal business validation without CSRF.
- The same test would fail if CSRF were globally required for all unsafe methods.
- No browser cookie-authenticated unsafe path bypasses CSRF.
</acceptance_criteria>
</task>

<task id="01-03-04" type="execute">
<title>Update contract documentation for CSRF flow</title>
<read_first>
- ai-docs/browser-auth-contract.md
- .planning/phases/01-browser-auth-contract-backend-security-foundation/01-UI-SPEC.md
</read_first>
<action>
Update `ai-docs/browser-auth-contract.md` to replace any "planned behavior" wording for CSRF with concrete behavior: `GET /api/v1/csrf`, readable `XSRF-TOKEN`, `X-XSRF-TOKEN`, cookie unsafe methods requiring CSRF, bearer bypass, and `AUTH_CSRF_TOKEN_INVALID` 403 envelope.
</action>
<verify>
Run `rg "GET /api/v1/csrf|XSRF-TOKEN|X-XSRF-TOKEN|AUTH_CSRF_TOKEN_INVALID|credentials: \"include\"" ai-docs/browser-auth-contract.md`.
</verify>
<acceptance_criteria>
- Contract doc names the CSRF bootstrap endpoint.
- Contract doc states refresh/logout require CSRF in browser cookie mode.
- Contract doc states bearer-token clients do not use CSRF as their protection mechanism.
</acceptance_criteria>
</task>
</tasks>

<verification>
- `./mvnw -pl stock-start -am test -Dtest=BrowserAuthFlowIT,AuthFlowIT,TradingApiIT --fail-at-end --no-transfer-progress`
- `rg "GET /api/v1/csrf|AUTH_CSRF_TOKEN_INVALID" ai-docs/browser-auth-contract.md`
</verification>

<success_criteria>
- Cookie-authenticated unsafe requests are CSRF protected.
- CSRF failure is a stable 403 ApiResponse envelope.
- Safe CSRF bootstrap is documented and tested.
- Bearer clients remain clearly separate from browser CSRF requirements.
</success_criteria>
