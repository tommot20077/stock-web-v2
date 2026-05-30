# Phase 01 — Pattern Map

**Generated:** 2026-05-30
**Status:** Ready for planner

## Source Roles

| Planned area | Existing analog | Pattern to preserve |
|--------------|-----------------|---------------------|
| HTTP security configuration | `stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java` | Keep URL-layer auth, CORS, authentication filter, and security error writer in `stock-start`. |
| Auth endpoints | `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java` | Controllers return `ApiResponse<T>` and use request records with validation. |
| Refresh persistence | `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/service/RefreshTokenService.java` | Redis keys use `user:refresh:{token}`, `user:refresh:index:{userId}`, and `user:auth:{userId}`. |
| Error envelope | `stock-start/src/main/java/dowob/xyz/stockwebv2/start/error/GlobalExceptionHandler.java` and `SecurityConfig.ApiSecurityErrorWriter` | Security failures must return `ApiResponse.failure(ApiError.of(...), meta())` with trace id. |
| Integration tests | `stock-start/src/test/java/dowob/xyz/stockwebv2/start/AuthFlowIT.java`, `CorsIT.java`, `TradingApiIT.java` | Use `MockMvc`, `ObjectMapper`, `jsonPath`, and `ContainerIT` for full app behavior. |
| Persistence tests | `stock-start/src/test/java/dowob/xyz/stockwebv2/start/AuthPersistenceIT.java` | Use `StringRedisTemplate`, `JdbcTemplate`, and Redis flush in `@AfterEach` for auth state assertions. |
| Frontend contract consumers | `/mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/apiClient.ts`, `runtimeDataMode.ts`, `Header.vue`, `Toast.vue` | Phase 1 should document `credentials: "include"`, CSRF, auth/session/error states, not edit Vue code. |

## Concrete Patterns

### Security Filter

`SecurityConfig.JwtAuthenticationFilter`:

- Reads request auth material.
- Parses with `JwtService`.
- Reads `user:auth:{userId}` from Redis.
- Clears `SecurityContextHolder` and writes security envelope on invalid token/version/status/Redis errors.
- Builds `UsernamePasswordAuthenticationToken` with `ROLE_*` and permission authorities.

Phase 1 should extend this path to accept access cookies only when bearer header is absent. Cookie-auth request state should stay inside request attributes/security infrastructure, not feature controllers.

### Security Error Writer

`SecurityConfig.ApiSecurityErrorWriter` already centralizes security envelopes. Extend or reuse it for:

- `AUTH_CSRF_TOKEN_INVALID` as HTTP 403.
- Refresh invalid/replay as HTTP 401 `AUTH_REFRESH_TOKEN_INVALID`.
- Existing unauthenticated/forbidden paths.

### Auth Controller

Current controller methods map directly to user actions:

- `register`
- `login`
- `me`
- `logout`

Phase 1 should keep browser endpoints at these names/paths and add explicit non-browser token issuance, for example `token`. Browser register/login/refresh/logout should set/clear cookies through a focused helper rather than manually formatting repeated `Set-Cookie` headers in every method.

### Redis Refresh Store

Refresh state currently stores:

- `userId`
- `tokenVersion`
- `deviceInfo`
- `createdAt`

Rotation should read the old token, validate `userId` and `tokenVersion`, delete old token/index membership, issue a new token, and preserve `user:auth:{userId}` invariants. Replay/missing state should not silently succeed.

### Test Style

Use `BrowserAuthFlowIT` for new cookie/CSRF behavior:

- Extend `ContainerIT`.
- Use `@AutoConfigureMockMvc`.
- Use `MockMvc` cookie/header support and `jsonPath`.
- Assert `Set-Cookie` attributes by response header when cookie object does not expose every attribute.
- Keep helper methods local and deterministic.

Use `AuthPersistenceIT` or module service tests for Redis rotation details that are awkward to assert through HTTP only.

## Must-Read Before Execution

- `AGENTS.md`
- `CLAUDE.md`
- `ai-docs/security.md`
- `ai-docs/testing-standards.md`
- `stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java`
- `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java`
- `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/service/RefreshTokenService.java`
- `stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/ErrorCode.java`
- `stock-start/src/test/java/dowob/xyz/stockwebv2/start/AuthFlowIT.java`
- `stock-start/src/test/java/dowob/xyz/stockwebv2/start/CorsIT.java`
- `.planning/phases/01-browser-auth-contract-backend-security-foundation/01-UI-SPEC.md`

## Pattern Mapping Complete
