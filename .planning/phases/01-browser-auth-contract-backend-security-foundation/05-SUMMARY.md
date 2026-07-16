# Plan 05 Summary — Bearer Compatibility, Documentation Closeout, and Final Verification

## Completed

- Added `/api/v1/auth/token` for non-browser clients to receive bearer access/refresh tokens without setting browser cookies.
- Updated existing backend and E2E auth helpers to register/login through browser-safe endpoints and obtain bearer tokens through `/auth/token` where tests still need Authorization headers.
- Added `ai-docs/browser-auth-contract.md` documenting browser cookies, CSRF cookie/header names, refresh/logout semantics, bearer compatibility, error codes, frontend requirements, and portfolio/trading DTO boundaries.
- Verified the documentation contains the required contract terms for `HttpOnly`, `SameSite`, `XSRF-TOKEN`, `X-XSRF-TOKEN`, `/api/v1/auth/refresh`, `/api/v1/auth/logout`, `/api/v1/auth/token`, auth error codes, `credentials: "include"`, and portfolio/trading boundaries.

## Verification

- Compile check: `./mvnw -pl stock-start -am test-compile -DskipTests --no-transfer-progress`
- Focused final suite: `./mvnw -pl stock-start -am test -Dtest=BrowserAuthFlowIT,AuthFlowIT,CorsIT,AuthPersistenceIT -Dsurefire.failIfNoSpecifiedTests=false --fail-at-end --no-transfer-progress`
- Result: 23 tests, 0 failures, `BUILD SUCCESS`.
- Documentation grep: `rg "HttpOnly|SameSite|XSRF-TOKEN|X-XSRF-TOKEN|/api/v1/auth/refresh|/api/v1/auth/logout|/api/v1/auth/token|AUTH_INVALID_CREDENTIALS|AUTH_FORBIDDEN|AUTH_CSRF_TOKEN_INVALID|portfolio|trading|credentials: \"include\"" ai-docs/browser-auth-contract.md`

## Notes

- `ai-docs/` is ignored by the repository `.gitignore`; the contract file exists locally and must be force-added if this repository wants it committed.
