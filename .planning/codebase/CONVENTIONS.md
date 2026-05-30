---
last_mapped_commit: b4459745f0bdf575818d0613cfa9e5b5276f55d8
sibling_frontend_last_mapped_commit: 0d942c2af74440ce4509383206b38a3021136841
---
# Coding Conventions

**Analysis Date:** 2026-05-30

## Project Instructions

**Required agent behavior:**
- Address the user as Yuan and answer in Traditional Chinese per `CLAUDE.md`.
- Use mandatory TDD for all production code changes: write a failing test, run it red, implement the minimum code, run green, then refactor.
- Keep implementation evidence-based: cite logs, test output, or source files when claiming behavior.
- Do not modify production code without tests; do not treat "not applicable" as a test substitute unless Yuan explicitly waives it.

**Project guidance sources:**
- `CLAUDE.md`: collaboration style, Traditional Chinese responses, mandatory TDD.
- `ai-docs/code-standards.md`: backend code quality, null handling, HTTP error mapping, SQL safety, comments.
- `ai-docs/testing-standards.md`: backend test layers, naming, Testcontainers, JWT testing, ArchUnit expectations.
- `ai-docs/security.md`: authorization, ownership, JWT, Redis, CORS, WebSocket, and security error-handling rules.

## Naming Patterns

**Backend files:**
- Maven modules use `stock-*` names, e.g. `stock-common`, `stock-infrastructure`, `stock-module-user`, `stock-start` in `pom.xml`.
- Java packages use `dowob.xyz.stockwebv2.<module>.<layer>`, e.g. `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/service/AuthService.java`.
- API classes use explicit suffixes: `*Controller`, `*Request`, `*Response`, `*Dto`, e.g. `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java`.
- Domain classes are noun-based records/classes without framework suffixes, e.g. `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/domain/Holding.java`.
- Repository implementations use `Jdbc*Repository` when backed by `JdbcClient`, e.g. `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/repository/JdbcTradingRepository.java`.
- Facade boundaries live in `stock-infrastructure`, e.g. `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/asset/AssetFacade.java`, and module implementations use `*FacadeImpl`, e.g. `stock-module-asset/src/main/java/dowob/xyz/stockwebv2/asset/service/AssetFacadeImpl.java`.

**Backend functions:**
- Use English `camelCase` method names for production and tests, e.g. `registerCreatesActiveUserWithHashedPassword` in `stock-module-user/src/test/java/dowob/xyz/stockwebv2/user/service/AuthServiceTest.java`.
- Controller methods map directly to user actions or resources: `register`, `login`, `me`, `logout` in `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java`.
- Private helpers use verb phrases that describe normalization, parsing, validation, or mapping: `normalizeEmail`, `parseStrategy`, `validateCapital`, `mapTransaction`.

**Backend variables:**
- Constants use `UPPER_SNAKE_CASE`, e.g. `USD`, `BUY_HOLD`, `MAX_PAGE` in `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/service/BacktestService.java`.
- Injected collaborators are final fields named by role, e.g. `userRepository`, `passwordEncoder`, `jwtService`.
- Test fixtures use small helpers and nested fakes when practical, e.g. `InMemoryUserRepository` in `stock-module-user/src/test/java/dowob/xyz/stockwebv2/user/service/AuthServiceTest.java`.

**Backend types:**
- Error categories are centralized in `stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/ErrorCode.java`.
- Domain enumerations live in `stock-common/src/main/java/dowob/xyz/stockwebv2/common/model/` or module `domain/` packages.
- API envelope types live in `stock-common/src/main/java/dowob/xyz/stockwebv2/common/api/`.

**Frontend files:**
- Vue components use PascalCase `.vue` filenames, e.g. `../../vue/stock-v2/vue-app/src/components/OrderTicket.vue`.
- Frontend services use camelCase `*Api.ts`, e.g. `../../vue/stock-v2/vue-app/src/services/backtestApi.ts`.
- Frontend tests are co-located under `src` with `.test.ts`, e.g. `../../vue/stock-v2/vue-app/src/services/apiClient.test.ts`.
- Shared UI test helpers live in `../../vue/stock-v2/vue-app/src/testUtils.ts`.

**Frontend functions and types:**
- Use camelCase functions such as `apiRequest`, `buildQueryString`, `createMockBacktestApi` in `../../vue/stock-v2/vue-app/src/services/apiClient.ts` and `../../vue/stock-v2/vue-app/src/services/backtestApi.ts`.
- Use PascalCase for classes and interfaces such as `ApiClientError`, `ApiRequestOptions`, and `BacktestApi`.
- Vue `script setup` state uses `ref` names that mirror UI state, e.g. `page`, `cmdk`, `toast`, `ticketOpen` in `../../vue/stock-v2/vue-app/src/App.vue`.

## Code Style

**Formatting:**
- Backend uses Maven/Java defaults; no Checkstyle, Spotless, or formatter config is detected. Follow existing indentation: 4 spaces in Java files such as `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/service/BacktestService.java`.
- Frontend uses Vite/Vue/TypeScript defaults; no ESLint, Prettier, or Biome config is detected under `../../vue/stock-v2/vue-app`.
- Keep Java imports explicit and sorted by package groups: project imports, framework imports, JDK imports, then static imports in tests.
- Keep TypeScript imports with value imports before type-only imports when both appear, as in `../../vue/stock-v2/vue-app/src/services/backtestApi.ts`.

**Linting:**
- Backend linting tool: Not detected.
- Frontend linting tool: Not detected.
- CI currently enforces tests through `.github/workflows/ci.yml`; it does not run a dedicated lint job.

**Backend style rules:**
- Prefer constructor injection with final fields, e.g. `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/service/AuthService.java`.
- Use Java text blocks for multi-line SQL with named parameters, e.g. `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/repository/JdbcTradingRepository.java`.
- Prefer domain-specific `BusinessException` and `ErrorCode` for user-visible failures.
- Prefer Apache Commons `ObjectUtils` / `StringUtils` and `java.util.Objects` per `ai-docs/code-standards.md`; existing code still contains direct null/blank checks in `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/service/BacktestService.java`, so new code should follow the documented utility pattern where dependencies are available.

**Frontend style rules:**
- Prefer typed adapters around fetch rather than raw fetch from components. Use `apiRequest` from `../../vue/stock-v2/vue-app/src/services/apiClient.ts`.
- Keep mock and HTTP implementations behind the same interface, e.g. `BacktestApi` in `../../vue/stock-v2/vue-app/src/services/backtestApi.ts`.
- Clone mutable mock state before returning it from adapters to avoid external mutation leaks, matching tests in `../../vue/stock-v2/vue-app/src/services/aiAccessApi.test.ts`.

## Import Organization

**Backend order:**
1. Project imports under `dowob.xyz.stockwebv2.*`.
2. Third-party and Spring imports.
3. JDK imports.
4. Static imports in tests.

**Backend examples:**
- `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java` follows project, servlet/validation, Spring, JDK import grouping.
- `stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/api/MarketControllerTest.java` uses static Mockito imports before static MockMvc result imports at the end.

**Frontend order:**
1. Runtime imports from libraries, e.g. `vue`, `pinia`, `vitest`.
2. Local runtime imports.
3. Type-only imports with `import type`.

**Path Aliases:**
- Backend: no source path aliases; Maven module dependencies define boundaries in `pom.xml`.
- Frontend: no TS path aliases in `../../vue/stock-v2/vue-app/tsconfig.json`; use relative imports such as `./services/apiClient`.

## Error Handling

**Backend patterns:**
- Use `ResponseEntity<ApiResponse<Void>>` from exception handlers to preserve HTTP status codes. The concrete handler is `stock-start/src/main/java/dowob/xyz/stockwebv2/start/error/GlobalExceptionHandler.java`.
- Map `BusinessException` through `exception.errorCode().httpStatus()` and return `ApiResponse.failure(...)`.
- Map validation failures into field errors and `ErrorCode.VALIDATION_FAILED` in `GlobalExceptionHandler.handleValidation`.
- Log unexpected exceptions once with the trace id from `TraceIdFilter.TRACE_ID`, then return `ErrorCode.INTERNAL_ERROR`.
- Preserve Spring `ErrorResponse` status via `handleErrorResponse`.
- Controllers should return `ApiResponse<T>` for normal success envelopes, e.g. `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java`.
- Use `BusinessException` for business validation and permission failures, e.g. `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/service/BacktestService.java`.
- Use `ResourceNotFoundException` for not-found semantics that must not leak internal identifiers, e.g. `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java`.

**Hard error-handling constraints:**
- Do not return `ApiResponse.failed(...)` directly from `@ExceptionHandler`; always wrap with `ResponseEntity`.
- `AccessDeniedException` must be re-thrown for Spring Security to produce HTTP 403 per `ai-docs/security.md`; verify this when changing `stock-start/src/main/java/dowob/xyz/stockwebv2/start/error/GlobalExceptionHandler.java`.
- `ResourceNotFoundException` messages should include only the resource type name, never IDs or paths.
- `SystemException`/unexpected errors must return a fixed external message and keep details in logs.

**Frontend patterns:**
- Throw `ApiClientError` for non-OK responses, malformed JSON, malformed success envelopes, and malformed error envelopes in `../../vue/stock-v2/vue-app/src/services/apiClient.ts`.
- Convert backend error envelopes into typed errors with `status`, `code`, `message`, `requestId`, optional `field`, and optional `details`.
- HTTP adapters should validate paginated envelopes before returning them, as in `../../vue/stock-v2/vue-app/src/services/backtestApi.ts`.
- UI-level tests assert safe fallback states on failed adapter loads, e.g. `../../vue/stock-v2/vue-app/src/api-adapter-wiring.test.ts`.

## Logging

**Framework:** SLF4J on backend; console logging is not a primary pattern in source.

**Patterns:**
- Use `private static final Logger log = LoggerFactory.getLogger(ClassName.class)` for operational logs, e.g. `stock-start/src/main/java/dowob/xyz/stockwebv2/start/error/GlobalExceptionHandler.java`.
- Use dedicated `AUDIT` logger for audit events, e.g. `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/api/WsTicketController.java` and `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/ws/MarketHandshakeInterceptor.java`.
- Include non-secret identifiers only; avoid tokens, raw secrets, SQL fragments, or stack traces in API responses.
- Test output should be pristine unless the test explicitly covers error handling per `ai-docs/testing-standards.md`.

## Comments

**When to Comment:**
- Backend JavaDoc and comments must be Traditional Chinese per `ai-docs/code-standards.md`.
- Classes should include description, author, and version JavaDoc when adding or touching files.
- Public methods should document behavior, parameters, and returns.
- Member variables should document purpose and meaning where project standards require it.
- Prefer JavaDoc block comments over `//` comments. Existing tests contain some `//` comments, e.g. `stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/api/MarketControllerTest.java`; new backend comments should follow the block style.

**JSDoc/TSDoc:**
- Frontend TypeScript does not use heavy TSDoc; types and interfaces carry most structure. Add comments only when code intent is not clear from names and types.

## Function Design

**Size:**
- Keep service methods focused on one application action, with parsing/validation/mapping extracted into private helpers, as in `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/service/BacktestService.java`.
- Keep frontend adapter helpers small and testable, e.g. `buildQueryString`, `readJson`, and envelope type guards in `../../vue/stock-v2/vue-app/src/services/apiClient.ts`.

**Parameters:**
- Backend controllers accept validated request records with `@Valid @RequestBody`, e.g. `RegisterRequest` in `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java`.
- Backend service methods accept normalized primitives or request DTOs and validate null/blank/limits at boundaries.
- Frontend service methods accept typed request objects and query params, e.g. `BacktestRunRequest` in `../../vue/stock-v2/vue-app/src/services/backtestApi.ts`.

**Return Values:**
- Backend controllers return `ApiResponse<T>` for successful responses and `ResponseEntity<ApiResponse<T>>` only when they need explicit non-200 success status, e.g. accepted backfill in `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/api/BackfillController.java`.
- Backend repositories return `Optional<T>` for missing records and concrete domain objects for inserts/updates.
- Frontend API clients return unwrapped `data` for standard success envelopes and full `PaginatedResponse<T>` for paginated endpoints.

## Module Design

**Exports:**
- Backend module boundaries are Maven modules. Keep shared API contracts in `stock-common` and cross-module facades in `stock-infrastructure`.
- Feature modules should keep API, domain, repository, and service code in their module package, e.g. `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/`.
- `stock-start` owns application bootstrapping, global config, security config, and global exception handling.

**Barrel Files:**
- Backend: Not applicable.
- Frontend: no barrel-file convention detected; import modules directly by relative path.

## SQL and Persistence

**Required pattern:**
- Use `JdbcClient` with named parameters for SQL, e.g. `:userId`, `:assetId`, `:limit` in `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/repository/JdbcTradingRepository.java`.
- Never concatenate untrusted values into SQL. Static fragment composition is present for fixed column and `where` clauses in `JdbcTradingRepository`; keep any dynamic values in `.param(...)`.
- For LIKE queries, escape wildcards with the documented `LikeEscapeUtil.escape(keyword)` pattern from `ai-docs/code-standards.md`.

## Security and Authorization

**Required pattern:**
- URL-layer authorization belongs in `stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java`.
- Method-layer authorization uses `@PreAuthorize` with permissions, e.g. `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/TradingController.java`.
- Ownership checks belong in the service layer, not controllers, using `SecurityUtils.assertOwnerOrAdmin(currentUserId, resourceOwnerId)` per `ai-docs/security.md`.
- JWT tests must not hardcode token fixtures; use generated tokens, `@WithMockUser`, or request post-processors per `ai-docs/testing-standards.md`.

## Frontend Integration Rules

**Backend/frontend contract:**
- Backend success envelopes are `ApiResponse<T>` from `stock-common/src/main/java/dowob/xyz/stockwebv2/common/api/ApiResponse.java`.
- Frontend clients expect envelope fields matching `../../vue/stock-v2/vue-app/src/services/apiTypes.ts`.
- Frontend integration code should go through `../../vue/stock-v2/vue-app/src/services/apiClient.ts` and feature adapters in `../../vue/stock-v2/vue-app/src/services/`.
- Add contract tests on both sides when changing envelope shape, status code mapping, pagination, or error code names.

---

*Convention analysis: 2026-05-30*
