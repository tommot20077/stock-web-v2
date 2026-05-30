# Plan 04 Summary — Refresh Rotation and Logout Current-Session Revocation

## Completed

- Added refresh-token consume-and-delete behavior for rotation in `RefreshTokenService`.
- Added browser `/api/v1/auth/refresh` that reads the refresh cookie, requires CSRF, rotates refresh state, and sets fresh auth cookies without exposing token bodies.
- Updated browser logout to revoke the current refresh session and clear auth cookies.
- Added invalid/replayed refresh handling that clears browser auth cookies and returns `AUTH_REFRESH_TOKEN_INVALID`.

## Verification

- `BrowserAuthFlowIT` covers refresh CSRF rejection, successful refresh cookie rotation, replay rejection, invalid refresh rejection, and cookie clearing.
- `AuthPersistenceIT` covers refresh token consume-for-rotation deleting the old Redis refresh state.
- Final focused suite: `./mvnw -pl stock-start -am test -Dtest=BrowserAuthFlowIT,AuthFlowIT,CorsIT,AuthPersistenceIT -Dsurefire.failIfNoSpecifiedTests=false --fail-at-end --no-transfer-progress`
- Result: 23 tests, 0 failures, `BUILD SUCCESS`.

## Notes

- Multi-device session listing and revoke-specific-device remain deferred as planned.
