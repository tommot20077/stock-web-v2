# Plan 01 Summary — Security Contract, Error Codes, and Test Harness

## Completed

- Added browser auth integration coverage through `BrowserAuthFlowIT`, starting from a red test that proved current register/login responses exposed bearer tokens and lacked CSRF bootstrap support.
- Added `AUTH_CSRF_TOKEN_INVALID` to the common error catalog so CSRF failures return the same `ApiResponse` error shape as other auth/security failures.
- Extended CORS coverage for credentialed allowed origin and disallowed origin behavior.

## Verification

- Red baseline: `./mvnw -pl stock-start -am test -Dtest=BrowserAuthFlowIT -Dsurefire.failIfNoSpecifiedTests=false --fail-at-end --no-transfer-progress`
- Final focused suite: `./mvnw -pl stock-start -am test -Dtest=BrowserAuthFlowIT,AuthFlowIT,CorsIT,AuthPersistenceIT -Dsurefire.failIfNoSpecifiedTests=false --fail-at-end --no-transfer-progress`
- Result: 23 tests, 0 failures, `BUILD SUCCESS`.

## Notes

- Test startup still emits the existing market-data batch `HistoricalTickReader` null input failure during application startup, but the Maven test run completes successfully.
