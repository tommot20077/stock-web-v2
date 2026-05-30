# Plan 03 Summary — CSRF Bootstrap and Cookie Unsafe Request Enforcement

## Completed

- Added `GET /api/v1/csrf` to issue a readable `XSRF-TOKEN` cookie and return cookie/header names for browser clients.
- Added cookie-mode CSRF enforcement for unsafe requests using double-submit cookie/header validation.
- Returned `AUTH_CSRF_TOKEN_INVALID` in an `ApiResponse` envelope for missing or mismatched CSRF tokens.
- Kept bearer-authenticated unsafe requests outside cookie CSRF enforcement.

## Verification

- `BrowserAuthFlowIT` covers CSRF bootstrap, unsafe request rejection without a matching header, successful unsafe cookie-authenticated logout with a matching token, and CSRF enforcement on refresh.
- Final focused suite: `./mvnw -pl stock-start -am test -Dtest=BrowserAuthFlowIT,AuthFlowIT,CorsIT,AuthPersistenceIT -Dsurefire.failIfNoSpecifiedTests=false --fail-at-end --no-transfer-progress`
- Result: 23 tests, 0 failures, `BUILD SUCCESS`.

## Deviation

- The implementation uses a custom double-submit `BrowserCsrfFilter` instead of Spring Security's built-in CSRF repository/failure handler. This keeps bearer and cookie transports explicitly separated while preserving the existing `ApiResponse` security error envelope.
