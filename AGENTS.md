# gstack

Use the `/browse` skill from gstack for all web browsing. Never use `mcp__claude-in-chrome__*` tools.

Available gstack skills:

- `/office-hours`
- `/plan-ceo-review`
- `/plan-eng-review`
- `/plan-design-review`
- `/design-consultation`
- `/design-shotgun`
- `/design-html`
- `/review`
- `/ship`
- `/land-and-deploy`
- `/canary`
- `/benchmark`
- `/browse`
- `/connect-chrome`
- `/qa`
- `/qa-only`
- `/design-review`
- `/setup-browser-cookies`
- `/setup-deploy`
- `/setup-gbrain`
- `/retro`
- `/investigate`
- `/document-release`
- `/document-generate`
- `/codex`
- `/cso`
- `/autoplan`
- `/plan-devex-review`
- `/devex-review`
- `/careful`
- `/freeze`
- `/guard`
- `/unfreeze`
- `/gstack-upgrade`
- `/learn`

<!-- GSD:project-start source:PROJECT.md -->
## Project

**Stock Web V2**

Stock Web V2 is a modular stock trading web application with a Java/Spring Boot backend and a sibling Vue frontend. The backend already provides auth, assets, market data, WebSocket tickets, backtests, and trading/portfolio APIs; the next product step is to connect the Vue app to those APIs through a safe browser auth contract while preserving mock mode for demos and frontend development.

**Core Value:** Users can safely sign in, inspect portfolio state, and record trades through one coherent frontend/backend flow.

### Constraints

- **Tech stack**: Keep Java 21/Spring Boot backend modules and Vue 3/Vite frontend; do not introduce a new application framework for this milestone.
- **Repository layout**: Backend and frontend are sibling repositories, so cross-repo work must make file ownership and verification commands explicit.
- **Security**: Browser cookie auth must include CSRF protection before unsafe endpoints rely on cookies.
- **Compatibility**: Preserve mock mode for frontend demos and development while adding API mode.
- **API contract**: REST responses should continue using the common `ApiResponse<T>` envelope and existing backend error semantics.
- **Trading semantics**: Treat current backend trading API as executed manual trades, not live broker orders.
- **Verification**: Backend changes need Maven tests; frontend changes need type-check/build and focused Vitest coverage where affected.
<!-- GSD:project-end -->

<!-- GSD:stack-start source:codebase/STACK.md -->
## Technology Stack

## Languages
- Java 21 - Backend application and all Maven modules. Set by `<java.version>21</java.version>` and compiler release in `pom.xml`.
- TypeScript ES2020 - Sibling Vue frontend at `../vue/stock-v2/vue-app/src/**/*.ts` and `../vue/stock-v2/vue-app/src/**/*.vue`, configured in `../vue/stock-v2/vue-app/tsconfig.json`.
- SQL - Flyway migrations in `stock-db-migration/src/main/resources/db/migration/*.sql`.
- YAML - Spring profiles in `stock-start/src/main/resources/application.yaml`, `stock-start/src/main/resources/application-dev.yaml`, and `stock-start/src/main/resources/application-demo.yaml`.
- XML - Maven module and dependency declarations in `pom.xml` and `stock-*/pom.xml`.
## Runtime
- Java 21 JVM. GitHub Actions uses Temurin Java 21 in `.github/workflows/ci.yml`.
- Spring Boot 4.0.4 servlet application. Parent version is declared in `pom.xml`.
- Node.js version is not pinned for the sibling frontend. `../vue/stock-v2/vue-app/package.json` has no `engines` field and no `.nvmrc` was detected.
- Backend: Maven Wrapper with Maven 3.9.14, configured in `.mvn/wrapper/maven-wrapper.properties`.
- Backend lockfile: Not applicable for Maven; dependency versions are controlled by Spring Boot parent and explicit properties in `pom.xml`.
- Frontend: npm, with `package-lock.json` lockfile v3 at `../vue/stock-v2/vue-app/package-lock.json`.
## Frameworks
- Spring Boot 4.0.4 - Application bootstrap and auto-configuration through `stock-start/src/main/java/dowob/xyz/stockwebv2/start/StockWebV2Application.java`.
- Spring Web MVC - REST controllers across `stock-module-user/src/main/java`, `stock-module-asset/src/main/java`, `stock-module-backtest/src/main/java`, `stock-module-market-data/src/main/java`, and `stock-module-trading/src/main/java`.
- Spring Security - Stateless JWT authorization and method security in `stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java`.
- Spring Data JDBC - Repository persistence through `JdbcClient`, for example `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/repository/JdbcUserRepository.java`.
- Spring Data Redis - Redis-backed auth/session/cache state through `StringRedisTemplate`, configured in `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/config/RedisConfig.java`.
- Spring Kafka - Market data event bus and consumers in `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/config/KafkaConfig.java`.
- Spring Batch - Market-data backfill jobs in `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/batch/BackfillJobConfig.java`.
- Spring WebSocket - Market data WebSocket endpoint `/ws/v1/market` in `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/config/WsConfig.java`.
- Vue 3.5.34 - Sibling frontend app mounted from `../vue/stock-v2/vue-app/src/main.ts`.
- Pinia 3.0.4 - Sibling frontend state stores under `../vue/stock-v2/vue-app/src/stores/`.
- Vue Router 5.0.7 - Sibling frontend routing in `../vue/stock-v2/vue-app/src/router.ts`.
- JUnit Jupiter via `spring-boot-starter-test` - Backend unit/integration tests throughout `stock-*/src/test/java`.
- Spring Security Test - Auth test support in `stock-start/pom.xml` and `stock-module-market-data/pom.xml`.
- Testcontainers 1.21.4 - PostgreSQL, Redis, and Kafka-backed integration tests, managed by `pom.xml`.
- H2 - Test-only in-memory database dependency in `stock-start/pom.xml`.
- Awaitility - Async Kafka/backfill test assertions in `stock-start/pom.xml` and `stock-module-market-data/pom.xml`.
- Vitest 4.1.6 with jsdom 29.1.1 - Sibling frontend tests configured in `../vue/stock-v2/vue-app/vite.config.ts`.
- Maven Wrapper - Use `./mvnw` from the backend root for backend build/test commands.
- Spring Boot Maven Plugin - Backend executable packaging in `stock-start/pom.xml`.
- Maven Surefire/Failsafe - Unit, integration, and E2E split in `stock-start/pom.xml`.
- Vite 8.0.13 - Sibling frontend dev server/build tool in `../vue/stock-v2/vue-app/package.json`.
- `@vitejs/plugin-vue` 6.0.7 - Vue SFC support in `../vue/stock-v2/vue-app/vite.config.ts`.
- `vue-tsc` 3.2.9 - Frontend type-check command in `../vue/stock-v2/vue-app/package.json`.
## Key Dependencies
- `spring-boot-starter-webmvc` - REST API surface for backend modules, declared in `stock-start/pom.xml` and feature module POMs.
- `spring-boot-starter-security` - API authorization, JWT filter chain, and `@PreAuthorize`, declared in `stock-start/pom.xml`.
- `spring-security-oauth2-jose` - ES256 JWT encode/decode in `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/security/JwtService.java`.
- `spring-boot-starter-data-jdbc` - SQL persistence via `JdbcClient`, declared in `stock-start/pom.xml`.
- `postgresql` JDBC driver - Runtime PostgreSQL connectivity, declared in `stock-start/pom.xml`.
- `flyway-database-postgresql` - PostgreSQL migrations from `stock-db-migration/src/main/resources/db/migration/`.
- `spring-boot-starter-data-redis` - Auth state, refresh tokens, WS tickets, market latest cache, backfill idempotency, and trading portfolio cache.
- `spring-kafka` and `spring-boot-kafka` - Market tick and backfill event streaming in `stock-module-market-data/pom.xml`.
- `spring-boot-starter-websocket` - Market WebSocket subscriptions in `stock-module-market-data/pom.xml`.
- `spring-boot-starter-batch` - Backfill job execution in `stock-module-market-data/pom.xml`.
- `springdoc-openapi-starter-webmvc-ui` 3.0.2 - OpenAPI docs and Swagger UI, declared in `stock-start/pom.xml`.
- `micrometer-core` - Market-data metrics in `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/observability/MarketDataMetrics.java`.
- PostgreSQL with TimescaleDB extension - Schema uses standard PostgreSQL tables plus Timescale hypertables and continuous aggregates in `stock-db-migration/src/main/resources/db/migration/V4__market_data_hypertable.sql` and `stock-db-migration/src/main/resources/db/migration/V5__market_data_continuous_aggregates.sql`.
- Redis - Runtime dependency for auth, cache, tickets, idempotency, and portfolio cache, configured through `stock-start/src/main/resources/application-dev.yaml`.
- Kafka - Runtime dependency for market-data ingestion, persistence, and WebSocket fanout, configured through `stock-start/src/main/resources/application.yaml`.
- TradingView widget script - Sibling frontend loads `https://s3.tradingview.com/tv.js` in `../vue/stock-v2/vue-app/src/pages/Chart.vue`.
## Configuration
- Backend default profile is `dev`, set in `stock-start/src/main/resources/application.yaml`.
- Dev profile imports optional `.env` as Spring properties via `stock-start/src/main/resources/application-dev.yaml`; `.env.example` exists but was not read because `.env*` files may contain secrets.
- Runtime server port defaults to `11180`, configured in `stock-start/src/main/resources/application.yaml`.
- Management server port defaults to `11181`, configured in `stock-start/src/main/resources/application.yaml`.
- CORS defaults to the frontend dev origin `http://localhost:5173`, configured in `stock-start/src/main/resources/application.yaml` and enforced in `stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java`.
- JWT private key is required outside `dev`, `test`, and `e2e`; blank non-production profiles generate an ephemeral ES256 key in `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/security/JwtService.java`.
- Sibling frontend runtime mode is `mock` unless `VITE_DATA_MODE=api`, defined by `../vue/stock-v2/vue-app/src/services/runtimeDataMode.ts`.
- Backend root Maven modules are declared in `pom.xml`: `stock-common`, `stock-db-migration`, `stock-infrastructure`, `stock-module-user`, `stock-module-asset`, `stock-module-backtest`, `stock-module-market-data`, `stock-module-trading`, and `stock-start`.
- Main backend artifact and dependency aggregation live in `stock-start/pom.xml`.
- Backend application config lives in `stock-start/src/main/resources/application.yaml`, `stock-start/src/main/resources/application-dev.yaml`, and `stock-start/src/main/resources/application-demo.yaml`.
- Backend CI lives in `.github/workflows/ci.yml`.
- Frontend build config lives in `../vue/stock-v2/vue-app/vite.config.ts` and `../vue/stock-v2/vue-app/tsconfig.json`.
## Platform Requirements
- Java 21 and Maven Wrapper from `mvnw`.
- PostgreSQL compatible with `uuid-ossp` and TimescaleDB extensions for full migration support in `stock-db-migration/src/main/resources/db/migration/`.
- Redis configured through `STOCK_REDIS_HOST`, `STOCK_REDIS_PORT`, `STOCK_REDIS_DATABASE`, and optional `STOCK_REDIS_PASSWORD`.
- Kafka broker configured through `STOCK_KAFKA_BOOTSTRAP_SERVERS`, defaulting to `localhost:9092`.
- npm for the sibling frontend; run scripts are `dev`, `build`, `preview`, `test`, and `test:watch` in `../vue/stock-v2/vue-app/package.json`.
- Deployment target is not detected in repository files. No Dockerfile, Kubernetes manifests, Helm charts, or deployment workflow were detected under the backend root.
- Production needs durable PostgreSQL/TimescaleDB, Redis, Kafka, and a configured `STOCK_JWT_PRIVATE_KEY`.
<!-- GSD:stack-end -->

<!-- GSD:conventions-start source:CONVENTIONS.md -->
## Conventions

## Project Instructions
- Address the user as Yuan and answer in Traditional Chinese per `CLAUDE.md`.
- Use mandatory TDD for all production code changes: write a failing test, run it red, implement the minimum code, run green, then refactor.
- Keep implementation evidence-based: cite logs, test output, or source files when claiming behavior.
- Do not modify production code without tests; do not treat "not applicable" as a test substitute unless Yuan explicitly waives it.
- `CLAUDE.md`: collaboration style, Traditional Chinese responses, mandatory TDD.
- `ai-docs/code-standards.md`: backend code quality, null handling, HTTP error mapping, SQL safety, comments.
- `ai-docs/testing-standards.md`: backend test layers, naming, Testcontainers, JWT testing, ArchUnit expectations.
- `ai-docs/security.md`: authorization, ownership, JWT, Redis, CORS, WebSocket, and security error-handling rules.
## Naming Patterns
- Maven modules use `stock-*` names, e.g. `stock-common`, `stock-infrastructure`, `stock-module-user`, `stock-start` in `pom.xml`.
- Java packages use `dowob.xyz.stockwebv2.<module>.<layer>`, e.g. `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/service/AuthService.java`.
- API classes use explicit suffixes: `*Controller`, `*Request`, `*Response`, `*Dto`, e.g. `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java`.
- Domain classes are noun-based records/classes without framework suffixes, e.g. `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/domain/Holding.java`.
- Repository implementations use `Jdbc*Repository` when backed by `JdbcClient`, e.g. `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/repository/JdbcTradingRepository.java`.
- Facade boundaries live in `stock-infrastructure`, e.g. `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/asset/AssetFacade.java`, and module implementations use `*FacadeImpl`, e.g. `stock-module-asset/src/main/java/dowob/xyz/stockwebv2/asset/service/AssetFacadeImpl.java`.
- Use English `camelCase` method names for production and tests, e.g. `registerCreatesActiveUserWithHashedPassword` in `stock-module-user/src/test/java/dowob/xyz/stockwebv2/user/service/AuthServiceTest.java`.
- Controller methods map directly to user actions or resources: `register`, `login`, `me`, `logout` in `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java`.
- Private helpers use verb phrases that describe normalization, parsing, validation, or mapping: `normalizeEmail`, `parseStrategy`, `validateCapital`, `mapTransaction`.
- Constants use `UPPER_SNAKE_CASE`, e.g. `USD`, `BUY_HOLD`, `MAX_PAGE` in `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/service/BacktestService.java`.
- Injected collaborators are final fields named by role, e.g. `userRepository`, `passwordEncoder`, `jwtService`.
- Test fixtures use small helpers and nested fakes when practical, e.g. `InMemoryUserRepository` in `stock-module-user/src/test/java/dowob/xyz/stockwebv2/user/service/AuthServiceTest.java`.
- Error categories are centralized in `stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/ErrorCode.java`.
- Domain enumerations live in `stock-common/src/main/java/dowob/xyz/stockwebv2/common/model/` or module `domain/` packages.
- API envelope types live in `stock-common/src/main/java/dowob/xyz/stockwebv2/common/api/`.
- Vue components use PascalCase `.vue` filenames, e.g. `../../vue/stock-v2/vue-app/src/components/OrderTicket.vue`.
- Frontend services use camelCase `*Api.ts`, e.g. `../../vue/stock-v2/vue-app/src/services/backtestApi.ts`.
- Frontend tests are co-located under `src` with `.test.ts`, e.g. `../../vue/stock-v2/vue-app/src/services/apiClient.test.ts`.
- Shared UI test helpers live in `../../vue/stock-v2/vue-app/src/testUtils.ts`.
- Use camelCase functions such as `apiRequest`, `buildQueryString`, `createMockBacktestApi` in `../../vue/stock-v2/vue-app/src/services/apiClient.ts` and `../../vue/stock-v2/vue-app/src/services/backtestApi.ts`.
- Use PascalCase for classes and interfaces such as `ApiClientError`, `ApiRequestOptions`, and `BacktestApi`.
- Vue `script setup` state uses `ref` names that mirror UI state, e.g. `page`, `cmdk`, `toast`, `ticketOpen` in `../../vue/stock-v2/vue-app/src/App.vue`.
## Code Style
- Backend uses Maven/Java defaults; no Checkstyle, Spotless, or formatter config is detected. Follow existing indentation: 4 spaces in Java files such as `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/service/BacktestService.java`.
- Frontend uses Vite/Vue/TypeScript defaults; no ESLint, Prettier, or Biome config is detected under `../../vue/stock-v2/vue-app`.
- Keep Java imports explicit and sorted by package groups: project imports, framework imports, JDK imports, then static imports in tests.
- Keep TypeScript imports with value imports before type-only imports when both appear, as in `../../vue/stock-v2/vue-app/src/services/backtestApi.ts`.
- Backend linting tool: Not detected.
- Frontend linting tool: Not detected.
- CI currently enforces tests through `.github/workflows/ci.yml`; it does not run a dedicated lint job.
- Prefer constructor injection with final fields, e.g. `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/service/AuthService.java`.
- Use Java text blocks for multi-line SQL with named parameters, e.g. `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/repository/JdbcTradingRepository.java`.
- Prefer domain-specific `BusinessException` and `ErrorCode` for user-visible failures.
- Prefer Apache Commons `ObjectUtils` / `StringUtils` and `java.util.Objects` per `ai-docs/code-standards.md`; existing code still contains direct null/blank checks in `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/service/BacktestService.java`, so new code should follow the documented utility pattern where dependencies are available.
- Prefer typed adapters around fetch rather than raw fetch from components. Use `apiRequest` from `../../vue/stock-v2/vue-app/src/services/apiClient.ts`.
- Keep mock and HTTP implementations behind the same interface, e.g. `BacktestApi` in `../../vue/stock-v2/vue-app/src/services/backtestApi.ts`.
- Clone mutable mock state before returning it from adapters to avoid external mutation leaks, matching tests in `../../vue/stock-v2/vue-app/src/services/aiAccessApi.test.ts`.
## Import Organization
- `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java` follows project, servlet/validation, Spring, JDK import grouping.
- `stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/api/MarketControllerTest.java` uses static Mockito imports before static MockMvc result imports at the end.
- Backend: no source path aliases; Maven module dependencies define boundaries in `pom.xml`.
- Frontend: no TS path aliases in `../../vue/stock-v2/vue-app/tsconfig.json`; use relative imports such as `./services/apiClient`.
## Error Handling
- Use `ResponseEntity<ApiResponse<Void>>` from exception handlers to preserve HTTP status codes. The concrete handler is `stock-start/src/main/java/dowob/xyz/stockwebv2/start/error/GlobalExceptionHandler.java`.
- Map `BusinessException` through `exception.errorCode().httpStatus()` and return `ApiResponse.failure(...)`.
- Map validation failures into field errors and `ErrorCode.VALIDATION_FAILED` in `GlobalExceptionHandler.handleValidation`.
- Log unexpected exceptions once with the trace id from `TraceIdFilter.TRACE_ID`, then return `ErrorCode.INTERNAL_ERROR`.
- Preserve Spring `ErrorResponse` status via `handleErrorResponse`.
- Controllers should return `ApiResponse<T>` for normal success envelopes, e.g. `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java`.
- Use `BusinessException` for business validation and permission failures, e.g. `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/service/BacktestService.java`.
- Use `ResourceNotFoundException` for not-found semantics that must not leak internal identifiers, e.g. `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java`.
- Do not return `ApiResponse.failed(...)` directly from `@ExceptionHandler`; always wrap with `ResponseEntity`.
- `AccessDeniedException` must be re-thrown for Spring Security to produce HTTP 403 per `ai-docs/security.md`; verify this when changing `stock-start/src/main/java/dowob/xyz/stockwebv2/start/error/GlobalExceptionHandler.java`.
- `ResourceNotFoundException` messages should include only the resource type name, never IDs or paths.
- `SystemException`/unexpected errors must return a fixed external message and keep details in logs.
- Throw `ApiClientError` for non-OK responses, malformed JSON, malformed success envelopes, and malformed error envelopes in `../../vue/stock-v2/vue-app/src/services/apiClient.ts`.
- Convert backend error envelopes into typed errors with `status`, `code`, `message`, `requestId`, optional `field`, and optional `details`.
- HTTP adapters should validate paginated envelopes before returning them, as in `../../vue/stock-v2/vue-app/src/services/backtestApi.ts`.
- UI-level tests assert safe fallback states on failed adapter loads, e.g. `../../vue/stock-v2/vue-app/src/api-adapter-wiring.test.ts`.
## Logging
- Use `private static final Logger log = LoggerFactory.getLogger(ClassName.class)` for operational logs, e.g. `stock-start/src/main/java/dowob/xyz/stockwebv2/start/error/GlobalExceptionHandler.java`.
- Use dedicated `AUDIT` logger for audit events, e.g. `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/api/WsTicketController.java` and `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/ws/MarketHandshakeInterceptor.java`.
- Include non-secret identifiers only; avoid tokens, raw secrets, SQL fragments, or stack traces in API responses.
- Test output should be pristine unless the test explicitly covers error handling per `ai-docs/testing-standards.md`.
## Comments
- Backend JavaDoc and comments must be Traditional Chinese per `ai-docs/code-standards.md`.
- Classes should include description, author, and version JavaDoc when adding or touching files.
- Public methods should document behavior, parameters, and returns.
- Member variables should document purpose and meaning where project standards require it.
- Prefer JavaDoc block comments over `//` comments. Existing tests contain some `//` comments, e.g. `stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/api/MarketControllerTest.java`; new backend comments should follow the block style.
- Frontend TypeScript does not use heavy TSDoc; types and interfaces carry most structure. Add comments only when code intent is not clear from names and types.
## Function Design
- Keep service methods focused on one application action, with parsing/validation/mapping extracted into private helpers, as in `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/service/BacktestService.java`.
- Keep frontend adapter helpers small and testable, e.g. `buildQueryString`, `readJson`, and envelope type guards in `../../vue/stock-v2/vue-app/src/services/apiClient.ts`.
- Backend controllers accept validated request records with `@Valid @RequestBody`, e.g. `RegisterRequest` in `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java`.
- Backend service methods accept normalized primitives or request DTOs and validate null/blank/limits at boundaries.
- Frontend service methods accept typed request objects and query params, e.g. `BacktestRunRequest` in `../../vue/stock-v2/vue-app/src/services/backtestApi.ts`.
- Backend controllers return `ApiResponse<T>` for successful responses and `ResponseEntity<ApiResponse<T>>` only when they need explicit non-200 success status, e.g. accepted backfill in `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/api/BackfillController.java`.
- Backend repositories return `Optional<T>` for missing records and concrete domain objects for inserts/updates.
- Frontend API clients return unwrapped `data` for standard success envelopes and full `PaginatedResponse<T>` for paginated endpoints.
## Module Design
- Backend module boundaries are Maven modules. Keep shared API contracts in `stock-common` and cross-module facades in `stock-infrastructure`.
- Feature modules should keep API, domain, repository, and service code in their module package, e.g. `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/`.
- `stock-start` owns application bootstrapping, global config, security config, and global exception handling.
- Backend: Not applicable.
- Frontend: no barrel-file convention detected; import modules directly by relative path.
## SQL and Persistence
- Use `JdbcClient` with named parameters for SQL, e.g. `:userId`, `:assetId`, `:limit` in `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/repository/JdbcTradingRepository.java`.
- Never concatenate untrusted values into SQL. Static fragment composition is present for fixed column and `where` clauses in `JdbcTradingRepository`; keep any dynamic values in `.param(...)`.
- For LIKE queries, escape wildcards with the documented `LikeEscapeUtil.escape(keyword)` pattern from `ai-docs/code-standards.md`.
## Security and Authorization
- URL-layer authorization belongs in `stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java`.
- Method-layer authorization uses `@PreAuthorize` with permissions, e.g. `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/TradingController.java`.
- Ownership checks belong in the service layer, not controllers, using `SecurityUtils.assertOwnerOrAdmin(currentUserId, resourceOwnerId)` per `ai-docs/security.md`.
- JWT tests must not hardcode token fixtures; use generated tokens, `@WithMockUser`, or request post-processors per `ai-docs/testing-standards.md`.
## Frontend Integration Rules
- Backend success envelopes are `ApiResponse<T>` from `stock-common/src/main/java/dowob/xyz/stockwebv2/common/api/ApiResponse.java`.
- Frontend clients expect envelope fields matching `../../vue/stock-v2/vue-app/src/services/apiTypes.ts`.
- Frontend integration code should go through `../../vue/stock-v2/vue-app/src/services/apiClient.ts` and feature adapters in `../../vue/stock-v2/vue-app/src/services/`.
- Add contract tests on both sides when changing envelope shape, status code mapping, pagination, or error code names.
<!-- GSD:conventions-end -->

<!-- GSD:architecture-start source:ARCHITECTURE.md -->
## Architecture

## System Overview
```text
```
## Component Responsibilities
| Component | Responsibility | File |
|-----------|----------------|------|
| Spring Boot start module | Composes all backend modules through broad component scanning and owns runtime security/error config. | `stock-start/src/main/java/dowob/xyz/stockwebv2/start/StockWebV2Application.java` |
| Security configuration | Stateless JWT filter, CORS, method security, auth/authorization error envelopes. | `stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java` |
| Global exception handling | Converts validation/business/runtime failures into `ApiResponse.failure(...)`. | `stock-start/src/main/java/dowob/xyz/stockwebv2/start/error/GlobalExceptionHandler.java` |
| Common contracts | API envelopes, error codes, enums, and cross-module event records. | `stock-common/src/main/java/dowob/xyz/stockwebv2/common` |
| Infrastructure | JWT, Redis/password config, trace IDs, event/search abstractions, cross-module asset facade contract. | `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure` |
| Database migrations | Flyway schema and seed ownership for users, assets, backtests, market prices, Spring Batch metadata, and trading. | `stock-db-migration/src/main/resources/db/migration` |
| User module | Registration, login, refresh-token lifecycle, `/me`, and user persistence. | `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user` |
| Asset module | Public asset search plus `AssetFacade` implementation for other modules. | `stock-module-asset/src/main/java/dowob/xyz/stockwebv2/asset` |
| Backtest module | Strategy validation, deterministic backtest engine, run/result persistence, and backtest REST API. | `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest` |
| Market-data module | Latest/K-line REST API, WebSocket tickets, scheduler ingest, Kafka persistence/broadcast consumers, and observability. | `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata` |
| Trading module | Trades, holdings, portfolio summary, portfolio Redis cache, and transaction/holding persistence. | `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading` |
| Vue app shell | Owns visible page state, header/navigation, global overlays, order ticket, shortcuts, and tweaks. | `../vue/stock-v2/vue-app/src/App.vue` |
| Vue service adapters | Switch between mock and HTTP clients using `VITE_DATA_MODE`, currently for AI access, backtest, and ops APIs. | `../vue/stock-v2/vue-app/src/services/pageApiClients.ts` |
## Pattern Overview
- Use one runtime application in `stock-start`; it depends on every backend module in `stock-start/pom.xml` and scans `dowob.xyz.stockwebv2`.
- Keep business features in Maven modules named `stock-module-*`; inside each module, use `api`, `service`, `domain`, and `repository` packages.
- Put stable cross-module contracts in `stock-common` or `stock-infrastructure`; avoid direct feature-module imports except where the Maven dependency explicitly allows them.
- Use raw SQL through Spring `JdbcClient` repositories rather than JPA entities.
- Return all REST responses through the common `ApiResponse<T>` envelope from `stock-common/src/main/java/dowob/xyz/stockwebv2/common/api/ApiResponse.java`.
- Use Redis for auth state, refresh token state, latest price cache, portfolio cache, and WebSocket tickets.
- Use Kafka as the market-data event bus; real-time ticks and historical backfill ticks use separate topics.
- Keep frontend API integration behind `../vue/stock-v2/vue-app/src/services/*Api.ts`, selected by `../vue/stock-v2/vue-app/src/services/runtimeDataMode.ts`.
## Layers
- Purpose: Compose all modules into one executable Spring Boot process.
- Location: `stock-start/src/main/java/dowob/xyz/stockwebv2/start`
- Contains: `StockWebV2Application`, `SecurityConfig`, `GlobalExceptionHandler`, test-only support controller.
- Depends on: Every domain module plus `stock-common`, `stock-infrastructure`, `stock-db-migration`.
- Used by: Maven boot plugin in `stock-start/pom.xml`.
- Purpose: Define HTTP endpoints, parse request parameters, resolve `Authentication`, apply validation annotations, and wrap responses.
- Location: `stock-module-*/src/main/java/dowob/xyz/stockwebv2/*/api`
- Contains: `*Controller`, `*Dto`, request records.
- Depends on: Service classes, `ApiResponse`, `ApiMeta`, `BusinessException`, `TraceIdFilter`.
- Used by: Spring WebMVC auto-detection from `StockWebV2Application`.
- Purpose: Orchestrate domain rules, repository calls, cache invalidation, external/event publishing, and DTO mapping.
- Location: `stock-module-*/src/main/java/dowob/xyz/stockwebv2/*/service`
- Contains: `AuthService`, `AssetQueryService`, `BacktestService`, `TradingService`, `PortfolioCache`, module-specific mappers.
- Depends on: Repositories, domain records, infrastructure facades, Redis/Kafka clients where appropriate.
- Used by: Controllers and scheduled/background components.
- Purpose: Represent module-owned business concepts and deterministic domain calculations.
- Location: `stock-module-*/src/main/java/dowob/xyz/stockwebv2/*/domain`
- Contains: Java records/enums such as `User`, `Asset`, `BacktestRun`, `Holding`, `TradeTransaction`, `HoldingCalculator`.
- Depends on: `stock-common` enums/errors where needed.
- Used by: Services and repositories.
- Purpose: Read and write PostgreSQL/TimescaleDB using hand-written SQL.
- Location: `stock-module-*/src/main/java/dowob/xyz/stockwebv2/*/repository` and `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/persistence`
- Contains: Repository interfaces plus `Jdbc*Repository` implementations and row records.
- Depends on: `JdbcClient`, module domain records, `PageResponse`.
- Used by: Services and Kafka consumers.
- Purpose: Hold API envelopes, error catalog, enums, event records, and facade interfaces used by multiple modules.
- Location: `stock-common/src/main/java/dowob/xyz/stockwebv2/common` and `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure`
- Contains: `ApiResponse`, `ErrorCode`, `PriceTickEvent`, `JwtService`, `TraceIdFilter`, `AssetFacade`.
- Depends on: Spring/security/Jackson/Redis where infrastructure requires it.
- Used by: All modules.
- Purpose: Ingest, persist, cache, and broadcast market ticks.
- Location: `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata`
- Contains: `ScheduledIngestor`, `MarketDataIngestService`, `PriceWriterConsumer`, `WsBroadcastConsumer`, WebSocket handler/interceptor, Spring Batch backfill.
- Depends on: Kafka, Redis, `AssetFacade`, market persistence repositories.
- Used by: REST market endpoints and WebSocket clients.
- Purpose: Render the app, manage local UI state, and route user actions to mock or HTTP service adapters.
- Location: `../vue/stock-v2/vue-app/src`
- Contains: `App.vue`, `pages/*.vue`, `components/*.vue`, `store.ts`, `data.ts`, `services/*.ts`.
- Depends on: Vue 3, Pinia, Vue Router, fetch API.
- Used by: Vite dev/build pipeline in `../vue/stock-v2/vue-app/package.json`.
## Data Flow
### REST Request Path
### Authentication Path
### Market Data Ingest and Broadcast Path
### WebSocket Ticket Path
### Backtest Path
### Trading and Portfolio Path
### Frontend API Adapter Path
- Backend request state is stateless except for Redis-backed JWT token version/status and refresh token/session adjunct state.
- Backend durable state is PostgreSQL/TimescaleDB with Flyway migrations in `stock-db-migration/src/main/resources/db/migration`.
- Backend ephemeral/cache state is Redis (`user:auth:*`, refresh tokens, `market:latest:*`, `portfolio:valuation:*`, `portfolio:summary:*`, WebSocket tickets).
- Backend event state moves through Kafka topics declared in `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/config/KafkaConfig.java`.
- Frontend app state is mostly local Vue refs/reactive objects in `../vue/stock-v2/vue-app/src/App.vue` and `../vue/stock-v2/vue-app/src/store.ts`; Pinia is installed but the inspected shared store is a plain `reactive(...)` export.
## Key Abstractions
- Purpose: Make REST success/error responses consistent.
- Examples: `stock-common/src/main/java/dowob/xyz/stockwebv2/common/api/ApiResponse.java`, `stock-common/src/main/java/dowob/xyz/stockwebv2/common/api/ApiError.java`, `stock-common/src/main/java/dowob/xyz/stockwebv2/common/api/ApiMeta.java`.
- Pattern: Controllers return `ApiResponse.success(...)`; security and global exception handling return `ApiResponse.failure(...)`.
- Purpose: Centralize stable API error codes and HTTP statuses.
- Examples: `stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/ErrorCode.java`, `stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/BusinessException.java`.
- Pattern: Throw `BusinessException(ErrorCode, message)` from services/controllers; add new feature error codes to `ErrorCode`.
- Purpose: Keep service code independent from SQL details while avoiding JPA.
- Examples: `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/repository/UserRepository.java`, `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/repository/JdbcUserRepository.java`, `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/repository/TradingRepository.java`.
- Pattern: Interface in `repository`, implementation named `Jdbc*Repository`, SQL via `JdbcClient`.
- Purpose: Let modules consume asset summaries without importing asset domain internals.
- Examples: `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/asset/AssetFacade.java`, `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/asset/AssetSummary.java`, `stock-module-asset/src/main/java/dowob/xyz/stockwebv2/asset/service/AssetFacadeImpl.java`.
- Pattern: Define consumer-facing interface/DTO in `stock-infrastructure`; implement it in the owning feature module.
- Purpose: Share Kafka payload shape across producer and consumers.
- Examples: `stock-common/src/main/java/dowob/xyz/stockwebv2/common/event/PriceTickEvent.java`.
- Pattern: Java record with null checks and explicit JSON serialization for decimal precision.
- Purpose: Route pages to mock or HTTP implementations without changing page code.
- Examples: `../vue/stock-v2/vue-app/src/services/pageApiClients.ts`, `../vue/stock-v2/vue-app/src/services/backtestApi.ts`, `../vue/stock-v2/vue-app/src/services/apiClient.ts`.
- Pattern: Define an interface per API domain, provide `createMock*Api` and `createHttp*Api`, choose through `create*Api(mode, basePath)`.
## Entry Points
- Location: `stock-start/src/main/java/dowob/xyz/stockwebv2/start/StockWebV2Application.java`
- Triggers: `mvn spring-boot:run`, packaged jar, or IDE Spring Boot run.
- Responsibilities: Boot the Spring application and scan all `dowob.xyz.stockwebv2` packages.
- Location: `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java`, `stock-module-asset/src/main/java/dowob/xyz/stockwebv2/asset/api/AssetController.java`, `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/api/BacktestController.java`, `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/api/MarketController.java`, `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/TradingController.java`.
- Triggers: HTTP requests under `/api/v1`.
- Responsibilities: Request validation, auth extraction, service delegation, response envelope creation.
- Location: `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/config/WsConfig.java`
- Triggers: WebSocket handshake at `/ws/v1/market`.
- Responsibilities: Register handler/interceptor and route ticket-authenticated sessions to `MarketWebSocketHandler`.
- Location: `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/ingest/ScheduledIngestor.java`
- Triggers: `@Scheduled(fixedDelayString = "${market-data.ingestor.fixed-delay-ms:1000}")`.
- Responsibilities: Poll providers and publish `PriceTickEvent` to Kafka.
- Location: `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/consumer/PriceWriterConsumer.java`, `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/consumer/WsBroadcastConsumer.java`.
- Triggers: Kafka messages on market-data topics.
- Responsibilities: Persist prices, update caches, broadcast WebSocket messages.
- Location: `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/batch/BackfillJobConfig.java`, `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/api/BackfillController.java`.
- Triggers: Admin REST call to `/api/v1/market/backfill`.
- Responsibilities: Read historical ticks and write backfill events to the backfill Kafka topic.
- Location: `../vue/stock-v2/vue-app/src/main.ts`
- Triggers: Vite app load.
- Responsibilities: Mount Vue, Pinia, router, and CSS.
## Architectural Constraints
- **Threading:** Backend uses Spring MVC request threads, Spring `@Scheduled` task execution, Kafka listener threads, WebSocket sessions, and Spring Batch job threads. Shared mutable state appears in `ScheduledIngestor.assetCache` and counters, WebSocket subscription/session managers, and Redis-backed caches.
- **Global state:** Frontend has module-level mutable `store` in `../vue/stock-v2/vue-app/src/store.ts` and memoized runtime clients in `../vue/stock-v2/vue-app/src/services/pageApiClients.ts`; tests reset the latter with `resetRuntimeApiClientsForTests()`.
- **Circular imports:** No source-level circular package chain was detected in the sampled imports. Module dependencies form a composition graph through `stock-start`; `stock-module-market-data` depends on `stock-module-asset` at Maven level while also consuming `AssetFacade`, so keep future cross-module calls behind facade contracts.
- **Module boundaries:** `stock-common` must remain dependency-light and free of feature-module imports. `stock-infrastructure` may define cross-module interfaces and infrastructure beans. Feature modules may depend on common/infrastructure; cross-feature access should use infrastructure facades or events.
- **Persistence ownership:** Schema changes belong in `stock-db-migration/src/main/resources/db/migration` even when feature code lives in a `stock-module-*` Maven module.
- **Frontend/backend mismatch:** Backend `ApiResponse` success shape is `{ success, data, error, meta }`, but frontend paginated HTTP adapters in `../vue/stock-v2/vue-app/src/services/backtestApi.ts` expect `{ data, page, requestId }` for paginated endpoints. Align adapters before switching pages to API mode.
- **Secrets:** `stock-start/src/main/resources/application-dev.yaml` imports optional `.env`; do not read or commit `.env` values. Use environment variable names only.
## Anti-Patterns
### Direct Feature Domain Imports Across Modules
### Controller-Owned Business Rules
### New Frontend Fetches Embedded in Pages
### New SQL Outside Repositories
## Error Handling
- Use `BusinessException` with `ErrorCode` for expected business/API failures (`stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/BusinessException.java`).
- Use validation annotations on request records and let `GlobalExceptionHandler` convert binding/validation failures (`stock-start/src/main/java/dowob/xyz/stockwebv2/start/error/GlobalExceptionHandler.java`).
- Translate security failures in `SecurityConfig.ApiSecurityErrorWriter` so auth errors also use the API envelope (`stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java:230`).
- In background market-data paths, isolate per-item/session failures with WARN logging and continue processing (`stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/ingest/ScheduledIngestor.java:176`, `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/consumer/WsBroadcastConsumer.java:454`).
- Frontend service adapters throw `ApiClientError` for non-OK, malformed, or unexpected backend responses (`../vue/stock-v2/vue-app/src/services/apiClient.ts:3`).
## Cross-Cutting Concerns
<!-- GSD:architecture-end -->

<!-- GSD:skills-start source:skills/ -->
## Project Skills

| Skill | Description | Path |
|-------|-------------|------|
| architecture-review-discussion | "Facilitate multi-role architecture review discussions using an Agent Team. Spawns specialized agents (architects, security lead, CTO) that explore the codebase, evaluate designs, ask each other questions, and resolve disputes through multiple rounds. Use when Yuan wants a team-based architecture review, design critique, or cross-functional discussion." | `.claude/skills/architecture-review-discussion/SKILL.md` |
| brainstorming | "You MUST use this before any creative work - creating features, building components, adding functionality, or modifying behavior. Explores user intent, requirements and design before implementation." | `.claude/skills/brainstorming/SKILL.md` |
| finishing-a-development-branch | Use when implementation is complete, all tests pass, and you need to decide how to integrate the work - guides completion of development work by presenting structured options for merge, PR, or cleanup | `.claude/skills/finishing-a-development-branch/SKILL.md` |
| git-action | Stage, commit, and push changes to the current feature or hotfix branch. Use after completing a logical unit of work to create a well-formed commit following Conventional Commits with Traditional Chinese subject. | `.claude/skills/git-action/SKILL.md` |
| github-review | Fetch all comments and review discussions from an open GitHub PR, discuss each item with Yuan to decide what to implement, then automatically hand off to /review-implementing for approved items. | `.claude/skills/github-review/SKILL.md` |
| open-pr | Open a Pull Request from the current feature or hotfix branch to the correct target branch (develop for features, main for hotfixes). Run once per feature after all commits are pushed. | `.claude/skills/open-pr/SKILL.md` |
| post-bug | Record a post-mortem report after fixing a bug — analyses the fix commit, discusses root cause and category with Yuan, generates a structured report in ai-docs/bug-reports/, and updates the index. | `.claude/skills/post-bug/SKILL.md` |
| review-bugs | Periodically review accumulated bug reports in ai-docs/bug-reports/ — analyse patterns, identify hotspots, suggest improvements to code standards and review checklists, and update guidelines with Yuan's approval. | `.claude/skills/review-bugs/SKILL.md` |
| review-implementing | Process and implement code review feedback systematically. Use when user provides reviewer comments, PR feedback, code review notes, or asks to implement suggestions from reviews. | `.claude/skills/review-implementing/SKILL.md` |
| software-architecture | Guide for stock-web-v2 Java Spring Boot modular monolith architecture. Use this skill when designing new features, reviewing code structure, or making architectural decisions. | `.claude/skills/software-architecture/SKILL.md` |
| subagent-driven-development | Use when executing implementation plans with independent tasks — dispatches fresh subagent per task with TDD + code review between tasks | `.claude/skills/subagent-driven-development/SKILL.md` |
| test-driven-development | Use when implementing any feature or bugfix, before writing implementation code | `.claude/skills/test-driven-development/SKILL.md` |
| test-fixing | Run tests and systematically fix all failing tests using smart error grouping. Use when user asks to fix failing tests, mentions test failures, runs test suite and failures occur, or requests to make tests pass. | `.claude/skills/test-fixing/SKILL.md` |
| using-git-worktrees | Use when starting feature work that needs isolation from current workspace or before executing implementation plans - creates isolated git worktrees with smart directory selection and safety verification | `.claude/skills/using-git-worktrees/SKILL.md` |
| writing-plans | Convert an approved brainstorming design into a concrete implementation plan with ordered tasks. Use after brainstorming design is approved, before dispatching implementation work. | `.claude/skills/writing-plans/SKILL.md` |
<!-- GSD:skills-end -->

<!-- GSD:workflow-start source:GSD defaults -->
## GSD Workflow Enforcement

Before using Edit, Write, or other file-changing tools, start work through a GSD command so planning artifacts and execution context stay in sync.

Use these entry points:
- `/gsd-quick` for small fixes, doc updates, and ad-hoc tasks
- `/gsd-debug` for investigation and bug fixing
- `/gsd-execute-phase` for planned phase work

Do not make direct repo edits outside a GSD workflow unless the user explicitly asks to bypass it.
<!-- GSD:workflow-end -->

<!-- GSD:profile-start -->
## Developer Profile

> Profile not yet configured. Run `/gsd-profile-user` to generate your developer profile.
> This section is managed by `generate-claude-profile` -- do not edit manually.
<!-- GSD:profile-end -->
