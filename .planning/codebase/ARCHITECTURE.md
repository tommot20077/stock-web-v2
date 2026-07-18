---
last_mapped_commit: b4459745f0bdf575818d0613cfa9e5b5276f55d8
---
<!-- refreshed: 2026-05-30 -->
# Architecture

**Analysis Date:** 2026-05-30

## System Overview

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Spring Boot composition app                           │
│  `stock-start/src/main/java/dowob/xyz/stockwebv2/start/StockWebV2Application.java` │
├──────────────────┬──────────────────┬──────────────────┬───────────────────┤
│ User/Auth        │ Assets           │ Backtest         │ Trading           │
│ `stock-module-user` │ `stock-module-asset` │ `stock-module-backtest` │ `stock-module-trading` │
└────────┬─────────┴────────┬─────────┴────────┬─────────┴─────────┬─────────┘
         │                  │                  │                   │
         ▼                  ▼                  ▼                   ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ Market Data module: REST + WebSocket + scheduler + Kafka consumers          │
│ `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata`     │
└───────────────────────────────┬─────────────────────────────────────────────┘
                                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ Shared contracts and infrastructure                                         │
│ `stock-common`, `stock-infrastructure`, `stock-db-migration`                │
└───────────────────────────────┬─────────────────────────────────────────────┘
                                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ PostgreSQL/TimescaleDB + Redis + Kafka                                      │
│ `stock-db-migration/src/main/resources/db/migration`                        │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│ Sibling Vue frontend                                                        │
│ `../vue/stock-v2/vue-app/src/App.vue` + `../vue/stock-v2/vue-app/src/services` │
└─────────────────────────────────────────────────────────────────────────────┘
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

**Overall:** Modular monolith with package-by-feature Spring modules, shared common/infrastructure contracts, and a separate Vite/Vue frontend that is gradually moving from mock data to backend API adapters.

**Key Characteristics:**
- Use one runtime application in `stock-start`; it depends on every backend module in `stock-start/pom.xml` and scans `dowob.xyz.stockwebv2`.
- Keep business features in Maven modules named `stock-module-*`; inside each module, use `api`, `service`, `domain`, and `repository` packages.
- Put stable cross-module contracts in `stock-common` or `stock-infrastructure`; avoid direct feature-module imports except where the Maven dependency explicitly allows them.
- Use raw SQL through Spring `JdbcClient` repositories rather than JPA entities.
- Return all REST responses through the common `ApiResponse<T>` envelope from `stock-common/src/main/java/dowob/xyz/stockwebv2/common/api/ApiResponse.java`.
- Use Redis for auth state, refresh token state, latest price cache, portfolio cache, and WebSocket tickets.
- Use Kafka as the market-data event bus; real-time ticks and historical backfill ticks use separate topics.
- Keep frontend API integration behind `../vue/stock-v2/vue-app/src/services/*Api.ts`, selected by `../vue/stock-v2/vue-app/src/services/runtimeDataMode.ts`.

## Layers

**Start/Composition Layer:**
- Purpose: Compose all modules into one executable Spring Boot process.
- Location: `stock-start/src/main/java/dowob/xyz/stockwebv2/start`
- Contains: `StockWebV2Application`, `SecurityConfig`, `GlobalExceptionHandler`, test-only support controller.
- Depends on: Every domain module plus `stock-common`, `stock-infrastructure`, `stock-db-migration`.
- Used by: Maven boot plugin in `stock-start/pom.xml`.

**API Layer:**
- Purpose: Define HTTP endpoints, parse request parameters, resolve `Authentication`, apply validation annotations, and wrap responses.
- Location: `stock-module-*/src/main/java/dowob/xyz/stockwebv2/*/api`
- Contains: `*Controller`, `*Dto`, request records.
- Depends on: Service classes, `ApiResponse`, `ApiMeta`, `BusinessException`, `TraceIdFilter`.
- Used by: Spring WebMVC auto-detection from `StockWebV2Application`.

**Application Service Layer:**
- Purpose: Orchestrate domain rules, repository calls, cache invalidation, external/event publishing, and DTO mapping.
- Location: `stock-module-*/src/main/java/dowob/xyz/stockwebv2/*/service`
- Contains: `AuthService`, `AssetQueryService`, `BacktestService`, `TradingService`, `PortfolioCache`, module-specific mappers.
- Depends on: Repositories, domain records, infrastructure facades, Redis/Kafka clients where appropriate.
- Used by: Controllers and scheduled/background components.

**Domain Layer:**
- Purpose: Represent module-owned business concepts and deterministic domain calculations.
- Location: `stock-module-*/src/main/java/dowob/xyz/stockwebv2/*/domain`
- Contains: Java records/enums such as `User`, `Asset`, `BacktestRun`, `Holding`, `TradeTransaction`, `HoldingCalculator`.
- Depends on: `stock-common` enums/errors where needed.
- Used by: Services and repositories.

**Persistence Layer:**
- Purpose: Read and write PostgreSQL/TimescaleDB using hand-written SQL.
- Location: `stock-module-*/src/main/java/dowob/xyz/stockwebv2/*/repository` and `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/persistence`
- Contains: Repository interfaces plus `Jdbc*Repository` implementations and row records.
- Depends on: `JdbcClient`, module domain records, `PageResponse`.
- Used by: Services and Kafka consumers.

**Shared Contract Layer:**
- Purpose: Hold API envelopes, error catalog, enums, event records, and facade interfaces used by multiple modules.
- Location: `stock-common/src/main/java/dowob/xyz/stockwebv2/common` and `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure`
- Contains: `ApiResponse`, `ErrorCode`, `PriceTickEvent`, `JwtService`, `TraceIdFilter`, `AssetFacade`.
- Depends on: Spring/security/Jackson/Redis where infrastructure requires it.
- Used by: All modules.

**Market Event Layer:**
- Purpose: Ingest, persist, cache, and broadcast market ticks.
- Location: `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata`
- Contains: `ScheduledIngestor`, `MarketDataIngestService`, `PriceWriterConsumer`, `WsBroadcastConsumer`, WebSocket handler/interceptor, Spring Batch backfill.
- Depends on: Kafka, Redis, `AssetFacade`, market persistence repositories.
- Used by: REST market endpoints and WebSocket clients.

**Frontend Presentation Layer:**
- Purpose: Render the app, manage local UI state, and route user actions to mock or HTTP service adapters.
- Location: `../vue/stock-v2/vue-app/src`
- Contains: `App.vue`, `pages/*.vue`, `components/*.vue`, `store.ts`, `data.ts`, `services/*.ts`.
- Depends on: Vue 3, Pinia, Vue Router, fetch API.
- Used by: Vite dev/build pipeline in `../vue/stock-v2/vue-app/package.json`.

## Data Flow

### REST Request Path

1. Client calls an endpoint under `/api/v1` such as `/api/v1/assets`, `/api/v1/backtests/runs`, or `/api/v1/trades` (`stock-module-asset/src/main/java/dowob/xyz/stockwebv2/asset/api/AssetController.java:17`, `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/api/BacktestController.java:24`, `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/TradingController.java:25`).
2. `TraceIdFilter` creates or validates `X-Trace-Id` and puts it in MDC (`stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/web/TraceIdFilter.java:350`).
3. `SecurityConfig.JwtAuthenticationFilter` parses bearer JWTs and validates Redis auth state at `user:auth:{userId}` (`stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java:138`).
4. Controller resolves request/auth data and delegates to a service (`stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/TradingController.java:35`).
5. Service applies business rules, calls repositories/caches/facades, and returns DTOs (`stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/TradingService.java:144`).
6. Controller wraps DTOs in `ApiResponse.success(...)` with `ApiMeta` trace/timestamp (`stock-common/src/main/java/dowob/xyz/stockwebv2/common/api/ApiResponse.java:14`).
7. `GlobalExceptionHandler` maps `BusinessException`, validation failures, and unexpected runtime errors to common error envelopes (`stock-start/src/main/java/dowob/xyz/stockwebv2/start/error/GlobalExceptionHandler.java`).

### Authentication Path

1. `AuthController.register` or `AuthController.login` receives credentials at `/api/v1/auth/register` or `/api/v1/auth/login` (`stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java:47`).
2. `AuthService` normalizes email/username, validates uniqueness, hashes passwords, or verifies credentials (`stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/service/AuthService.java:121`).
3. `JdbcUserRepository` persists/loads `users` rows with `JdbcClient` (`stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/repository/JdbcUserRepository.java:171`).
4. `JwtService` signs ES256 access tokens with `stock.jwt` settings (`stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/security/JwtService.java:68`).
5. `RefreshTokenService` issues and revokes refresh tokens in Redis using `JwtProperties.refreshTokenTtl()` (`stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/service/RefreshTokenService.java`).
6. Later requests are authenticated by the security filter and authorized with roles/permissions from `Role.permissions()` (`stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java:197`).

### Market Data Ingest and Broadcast Path

1. `ScheduledIngestor` loads active/tradeable assets through `AssetFacade` after startup (`stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/ingest/ScheduledIngestor.java:140`).
2. Each scheduled tick fetches a provider tick, creates `PriceTickEvent`, and calls `MarketDataIngestService.publishTick(...)` (`stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/ingest/ScheduledIngestor.java:159`).
3. `MarketDataIngestService` publishes to Kafka topic constants in `KafkaConfig` using `assetId` as the partition key (`stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/ingest/MarketDataIngestService.java:45`).
4. `PriceWriterConsumer` consumes real-time and backfill topics in batch and writes `market_prices` rows (`stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/consumer/PriceWriterConsumer.java:268`).
5. `WsBroadcastConsumer` consumes real-time tick topic only, updates Redis `market:latest:{assetId}`, updates K-line buckets, and broadcasts `TICK`/`KLINE` payloads to subscribers (`stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/consumer/WsBroadcastConsumer.java:376`).
6. REST latest/K-line endpoints read from `MarketLatestService` and `KlineQueryService` (`stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/api/MarketController.java:65`).

### WebSocket Ticket Path

1. Authenticated clients call `POST /api/v1/market/ws/ticket` (`stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/api/WsTicketController.java:197`).
2. `WsTicketController` reads the current Redis token version from `user:auth:{userId}` and asks `WsTicketService` to issue a short-lived ticket (`stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/api/WsTicketController.java:246`).
3. `WsConfig` registers `/ws/v1/market` with `MarketHandshakeInterceptor` and `MarketWebSocketHandler` (`stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/config/WsConfig.java`).
4. The handshake consumes the ticket and injects user identity into the session (`stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/ws/MarketHandshakeInterceptor.java`).
5. `MarketWebSocketHandler` manages messages and subscriptions through `SubscriptionManager` (`stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/ws/MarketWebSocketHandler.java`).
6. `WsBroadcastConsumer` sends Kafka-derived tick/K-line payloads to subscribed sessions (`stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/consumer/WsBroadcastConsumer.java:427`).

### Backtest Path

1. Client creates a run via `POST /api/v1/backtests/runs` (`stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/api/BacktestController.java:32`).
2. `BacktestService` parses strategy/period/capital/options and verifies active symbol support (`stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/service/BacktestService.java:148`).
3. Custom strategies are validated by `StrategyValidator` (`stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/service/BacktestService.java:285`).
4. `DeterministicBacktestEngine` generates repeatable KPI/equity/drawdown/trade output from seeded inputs (`stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/engine/DeterministicBacktestEngine.java:329`).
5. `BacktestRepository` persists the run/result and service returns `BacktestRunDto` (`stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/repository/BacktestRepository.java`).

### Trading and Portfolio Path

1. Client posts `/api/v1/trades` with authority `TRADE_EXECUTE` (`stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/TradingController.java:33`).
2. `TradingService` resolves a tradeable asset through `AssetFacade`, locks current holdings, applies `HoldingCalculator`, writes holdings and transactions, then invalidates Redis portfolio cache (`stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/TradingService.java:144`).
3. Portfolio reads use `PortfolioCache` with 60-second TTL before recalculating from repository rows and latest prices (`stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/PortfolioCache.java:297`).
4. Holding and summary endpoints require `PORTFOLIO_VIEW` (`stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/TradingController.java:55`).

### Frontend API Adapter Path

1. `../vue/stock-v2/vue-app/src/main.ts` mounts Vue, Pinia, and a hash router.
2. `../vue/stock-v2/vue-app/src/App.vue` still owns actual page rendering through a local `page` ref; `../vue/stock-v2/vue-app/src/router.ts` has null-render routes as a transition registry.
3. Pages call adapters from `getRuntimeApiClients()` (`../vue/stock-v2/vue-app/src/pages/Backtest.vue:409`).
4. `../vue/stock-v2/vue-app/src/services/runtimeDataMode.ts` maps `VITE_DATA_MODE=api` to HTTP clients and everything else to mock clients.
5. HTTP clients call backend-compatible `/api/v1` paths through `apiRequest(...)` (`../vue/stock-v2/vue-app/src/services/apiClient.ts:71`).
6. Mock-first pages still read local fixtures from `../vue/stock-v2/vue-app/src/data.ts` and mutable local store state from `../vue/stock-v2/vue-app/src/store.ts`.

**State Management:**
- Backend request state is stateless except for Redis-backed JWT token version/status and refresh token/session adjunct state.
- Backend durable state is PostgreSQL/TimescaleDB with Flyway migrations in `stock-db-migration/src/main/resources/db/migration`.
- Backend ephemeral/cache state is Redis (`user:auth:*`, refresh tokens, `market:latest:*`, `portfolio:valuation:*`, `portfolio:summary:*`, WebSocket tickets).
- Backend event state moves through Kafka topics declared in `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/config/KafkaConfig.java`.
- Frontend app state is mostly local Vue refs/reactive objects in `../vue/stock-v2/vue-app/src/App.vue` and `../vue/stock-v2/vue-app/src/store.ts`; Pinia is installed but the inspected shared store is a plain `reactive(...)` export.

## Key Abstractions

**API Envelope:**
- Purpose: Make REST success/error responses consistent.
- Examples: `stock-common/src/main/java/dowob/xyz/stockwebv2/common/api/ApiResponse.java`, `stock-common/src/main/java/dowob/xyz/stockwebv2/common/api/ApiError.java`, `stock-common/src/main/java/dowob/xyz/stockwebv2/common/api/ApiMeta.java`.
- Pattern: Controllers return `ApiResponse.success(...)`; security and global exception handling return `ApiResponse.failure(...)`.

**Error Catalog:**
- Purpose: Centralize stable API error codes and HTTP statuses.
- Examples: `stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/ErrorCode.java`, `stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/BusinessException.java`.
- Pattern: Throw `BusinessException(ErrorCode, message)` from services/controllers; add new feature error codes to `ErrorCode`.

**Repository Interface + JDBC Implementation:**
- Purpose: Keep service code independent from SQL details while avoiding JPA.
- Examples: `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/repository/UserRepository.java`, `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/repository/JdbcUserRepository.java`, `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/repository/TradingRepository.java`.
- Pattern: Interface in `repository`, implementation named `Jdbc*Repository`, SQL via `JdbcClient`.

**Cross-Module Facade:**
- Purpose: Let modules consume asset summaries without importing asset domain internals.
- Examples: `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/asset/AssetFacade.java`, `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/asset/AssetSummary.java`, `stock-module-asset/src/main/java/dowob/xyz/stockwebv2/asset/service/AssetFacadeImpl.java`.
- Pattern: Define consumer-facing interface/DTO in `stock-infrastructure`; implement it in the owning feature module.

**Domain Event Record:**
- Purpose: Share Kafka payload shape across producer and consumers.
- Examples: `stock-common/src/main/java/dowob/xyz/stockwebv2/common/event/PriceTickEvent.java`.
- Pattern: Java record with null checks and explicit JSON serialization for decimal precision.

**Frontend Runtime API Client:**
- Purpose: Route pages to mock or HTTP implementations without changing page code.
- Examples: `../vue/stock-v2/vue-app/src/services/pageApiClients.ts`, `../vue/stock-v2/vue-app/src/services/backtestApi.ts`, `../vue/stock-v2/vue-app/src/services/apiClient.ts`.
- Pattern: Define an interface per API domain, provide `createMock*Api` and `createHttp*Api`, choose through `create*Api(mode, basePath)`.

## Entry Points

**Backend Application:**
- Location: `stock-start/src/main/java/dowob/xyz/stockwebv2/start/StockWebV2Application.java`
- Triggers: `mvn spring-boot:run`, packaged jar, or IDE Spring Boot run.
- Responsibilities: Boot the Spring application and scan all `dowob.xyz.stockwebv2` packages.

**REST Controllers:**
- Location: `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java`, `stock-module-asset/src/main/java/dowob/xyz/stockwebv2/asset/api/AssetController.java`, `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/api/BacktestController.java`, `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/api/MarketController.java`, `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/TradingController.java`.
- Triggers: HTTP requests under `/api/v1`.
- Responsibilities: Request validation, auth extraction, service delegation, response envelope creation.

**WebSocket Endpoint:**
- Location: `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/config/WsConfig.java`
- Triggers: WebSocket handshake at `/ws/v1/market`.
- Responsibilities: Register handler/interceptor and route ticket-authenticated sessions to `MarketWebSocketHandler`.

**Scheduled Market Ingest:**
- Location: `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/ingest/ScheduledIngestor.java`
- Triggers: `@Scheduled(fixedDelayString = "${market-data.ingestor.fixed-delay-ms:1000}")`.
- Responsibilities: Poll providers and publish `PriceTickEvent` to Kafka.

**Kafka Consumers:**
- Location: `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/consumer/PriceWriterConsumer.java`, `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/consumer/WsBroadcastConsumer.java`.
- Triggers: Kafka messages on market-data topics.
- Responsibilities: Persist prices, update caches, broadcast WebSocket messages.

**Spring Batch Backfill:**
- Location: `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/batch/BackfillJobConfig.java`, `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/api/BackfillController.java`.
- Triggers: Admin REST call to `/api/v1/market/backfill`.
- Responsibilities: Read historical ticks and write backfill events to the backfill Kafka topic.

**Frontend Application:**
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

**What happens:** A consumer module imports another feature module's domain or repository classes directly.
**Why it's wrong:** It bypasses Maven/package boundaries and makes independent feature changes brittle.
**Do this instead:** Add or extend a contract in `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure` and implement it in the owning module, following `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/asset/AssetFacade.java` and `stock-module-asset/src/main/java/dowob/xyz/stockwebv2/asset/service/AssetFacadeImpl.java`.

### Controller-Owned Business Rules

**What happens:** Controllers perform calculations, cache writes, repository orchestration, or permission-specific business decisions beyond request parsing/auth extraction.
**Why it's wrong:** It duplicates behavior across endpoints and makes service tests less valuable.
**Do this instead:** Keep controller logic shaped like `stock-module-asset/src/main/java/dowob/xyz/stockwebv2/asset/api/AssetController.java`: clamp/parse request values, call a service, and wrap the response.

### New Frontend Fetches Embedded in Pages

**What happens:** A Vue page calls `fetch(...)` directly.
**Why it's wrong:** It bypasses mock/API mode, common error handling, and backend envelope parsing.
**Do this instead:** Add an interface and `createMock*Api`/`createHttp*Api` pair in `../vue/stock-v2/vue-app/src/services`, then expose it from `../vue/stock-v2/vue-app/src/services/pageApiClients.ts`.

### New SQL Outside Repositories

**What happens:** Services or controllers embed SQL.
**Why it's wrong:** It mixes orchestration with persistence mapping and makes transaction boundaries harder to review.
**Do this instead:** Keep SQL in `repository` or `persistence` classes, following `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/repository/JdbcUserRepository.java`.

## Error Handling

**Strategy:** Throw typed domain/application exceptions and centralize conversion to API envelopes.

**Patterns:**
- Use `BusinessException` with `ErrorCode` for expected business/API failures (`stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/BusinessException.java`).
- Use validation annotations on request records and let `GlobalExceptionHandler` convert binding/validation failures (`stock-start/src/main/java/dowob/xyz/stockwebv2/start/error/GlobalExceptionHandler.java`).
- Translate security failures in `SecurityConfig.ApiSecurityErrorWriter` so auth errors also use the API envelope (`stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java:230`).
- In background market-data paths, isolate per-item/session failures with WARN logging and continue processing (`stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/ingest/ScheduledIngestor.java:176`, `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/consumer/WsBroadcastConsumer.java:454`).
- Frontend service adapters throw `ApiClientError` for non-OK, malformed, or unexpected backend responses (`../vue/stock-v2/vue-app/src/services/apiClient.ts:3`).

## Cross-Cutting Concerns

**Logging:** Backend uses SLF4J. Request trace IDs are stored in MDC by `TraceIdFilter`. WebSocket ticket issuance uses an `AUDIT` logger in `WsTicketController`. Background components log recoverable failures as warnings.

**Validation:** Backend uses Jakarta Bean Validation in controller request bodies, manual parsing for query params, and service-level validation for domain rules. Frontend validates user inputs locally before calling adapters, especially in backtest custom strategy and initial capital handling.

**Authentication:** Backend uses stateless ES256 JWT access tokens plus Redis auth state (`user:auth:{userId}`) for token version/status validation. Method security uses role/permission authorities from `Role.permissions()`. WebSocket uses one-time Redis tickets instead of JWTs in the WebSocket URL.

**Configuration:** Backend config lives in `stock-start/src/main/resources/application.yaml`, `stock-start/src/main/resources/application-dev.yaml`, and `stock-start/src/main/resources/application-demo.yaml`. Dev imports `.env` via `optional:file:.env[.properties]`. Frontend runtime API/mock mode uses `VITE_DATA_MODE` in `../vue/stock-v2/vue-app/src/services/runtimeDataMode.ts`.

**Database:** Flyway migrations are centralized in `stock-db-migration/src/main/resources/db/migration`. Market data uses TimescaleDB hypertables and continuous aggregates via `V4__market_data_hypertable.sql` and `V5__market_data_continuous_aggregates.sql`.

**Caching:** Redis is used by auth, market latest prices, portfolio summaries/valuations, WebSocket tickets, and backfill idempotency.

---

*Architecture analysis: 2026-05-30*
