# Plan 02 Summary — Browser Cookie Login/Register and Cookie Authentication

## Completed

- Changed browser `/api/v1/auth/register` and `/api/v1/auth/login` responses to set `HttpOnly` access/refresh cookies and return session metadata instead of bearer tokens in the response body.
- Added configurable browser auth cookie properties for names, path, domain, secure flag, SameSite, and access/refresh TTLs.
- Updated authentication filtering to prefer bearer tokens when present and fall back to the access cookie for browser requests.
- Added browser cookie service helpers for setting and clearing auth cookies consistently.

## Verification

- `BrowserAuthFlowIT` covers register/login cookie issuance, absence of token body fields, cookie-authenticated `/api/v1/me`, and logout clearing cookies.
- Final focused suite: `./mvnw -pl stock-start -am test -Dtest=BrowserAuthFlowIT,AuthFlowIT,CorsIT,AuthPersistenceIT -Dsurefire.failIfNoSpecifiedTests=false --fail-at-end --no-transfer-progress`
- Result: 23 tests, 0 failures, `BUILD SUCCESS`.

## Notes

- Non-browser bearer compatibility was kept separate through `/api/v1/auth/token` rather than preserving token bodies on browser login/register.
