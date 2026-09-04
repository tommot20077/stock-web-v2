---
last_mapped_commit: b4459745f0bdf575818d0613cfa9e5b5276f55d8
---
# Codebase Structure

**Analysis Date:** 2026-05-30

## Directory Layout

```text
stock-web-v2/
├── pom.xml                         # Maven aggregator and shared Java/Spring/Testcontainers versions
├── mvnw, mvnw.cmd                  # Maven wrapper launchers
├── stock-common/                   # Shared API/error/model/event contracts
├── stock-infrastructure/           # Shared Spring infrastructure and cross-module facades
├── stock-db-migration/             # Flyway migrations packaged as a module
├── stock-module-user/              # Auth/user feature module
├── stock-module-asset/             # Asset search and asset facade implementation
├── stock-module-backtest/          # Backtest REST/service/engine/persistence
├── stock-module-market-data/       # Market REST, WebSocket, Kafka, scheduler, backfill, observability
├── stock-module-trading/           # Trading, holdings, portfolio cache, trading persistence
├── stock-start/                    # Executable Spring Boot application and runtime config
├── src/main/resources/             # Legacy/root resources directory, not the active start module config
├── scripts/                        # Developer scripts
├── ai-docs/                        # Project architecture/convention reference docs
├── docs/                           # Plans and supporting docs
└── .planning/codebase/             # GSD generated codebase maps

../vue/stock-v2/vue-app/
├── package.json                    # Vite/Vue scripts and dependencies
├── vite.config.ts                  # Vue plugin, dev server, Vitest config
├── src/main.ts                     # Vue app mount
├── src/App.vue                     # Current app shell and page switcher
├── src/router.ts                   # Hash route registry; page rendering remains App-owned
├── src/pages/                      # Page-level Vue components
├── src/components/                 # Reusable UI components
├── src/components/settings/        # Settings subcomponents and tests
├── src/services/                   # API/mock adapter layer
├── src/composables/                # Reusable composition functions
├── src/stores/                     # Mock/domain-specific store helpers
├── src/store.ts                    # App-level reactive mock portfolio/trading/alerts store
├── src/data.ts                     # Static mock market/portfolio/news/ops data
├── src/types.ts                    # Frontend UI/domain types
└── src/*.test.ts                   # Vitest unit/integration tests
```

## Directory Purposes

**Root Maven Project (`.`):**
- Purpose: Aggregate all backend modules and pin shared Maven parent/properties.
- Contains: Root `pom.xml`, Maven wrapper, docs, scripts, planning artifacts.
- Key files: `pom.xml`, `mvnw`, `.gitignore`, `AGENTS.md`, `CLAUDE.md`.

**`stock-common`:**
- Purpose: Shared Java contracts that do not depend on feature modules.
- Contains: API response records, error types, enums, and cross-module event records.
- Key files: `stock-common/src/main/java/dowob/xyz/stockwebv2/common/api/ApiResponse.java`, `stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/ErrorCode.java`, `stock-common/src/main/java/dowob/xyz/stockwebv2/common/event/PriceTickEvent.java`.

**`stock-infrastructure`:**
- Purpose: Shared Spring infrastructure and cross-module interfaces.
- Contains: JWT service/properties, Redis/password config, trace filter, event/search abstractions, `AssetFacade`.
- Key files: `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/security/JwtService.java`, `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/web/TraceIdFilter.java`, `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/asset/AssetFacade.java`.

**`stock-db-migration`:**
- Purpose: Own database schema lifecycle for the full backend.
- Contains: Flyway SQL migrations under `src/main/resources/db/migration`.
- Key files: `stock-db-migration/src/main/resources/db/migration/V1__foundation_schema.sql`, `stock-db-migration/src/main/resources/db/migration/V3__backtest_schema.sql`, `stock-db-migration/src/main/resources/db/migration/V4__market_data_hypertable.sql`, `stock-db-migration/src/main/resources/db/migration/V7__trading_schema.sql`.

**`stock-module-user`:**
- Purpose: User registration, login, refresh token lifecycle, and current-user endpoint.
- Contains: `api`, `domain`, `repository`, `service` packages.
- Key files: `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java`, `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/service/AuthService.java`, `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/service/RefreshTokenService.java`, `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/repository/JdbcUserRepository.java`.

**`stock-module-asset`:**
- Purpose: Asset catalog REST search and module-owned implementation of cross-module asset lookup.
- Contains: `api`, `domain`, `repository`, `service` packages.
- Key files: `stock-module-asset/src/main/java/dowob/xyz/stockwebv2/asset/api/AssetController.java`, `stock-module-asset/src/main/java/dowob/xyz/stockwebv2/asset/service/AssetQueryService.java`, `stock-module-asset/src/main/java/dowob/xyz/stockwebv2/asset/service/AssetFacadeImpl.java`.

**`stock-module-backtest`:**
- Purpose: Backtest run lifecycle, strategy validation, deterministic engine, and persistence.
- Contains: `api`, `domain`, `engine`, `repository`, `service` packages.
- Key files: `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/api/BacktestController.java`, `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/service/BacktestService.java`, `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/engine/DeterministicBacktestEngine.java`, `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/repository/JdbcBacktestRepository.java`.

**`stock-module-market-data`:**
- Purpose: Market-data APIs, providers, ingest pipeline, WebSocket subscriptions, Kafka consumers, backfill jobs, and observability.
- Contains: `api`, `batch`, `config`, `consumer`, `ingest`, `observability`, `persistence`, `provider`, `ws` packages.
- Key files: `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/api/MarketController.java`, `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/api/BackfillController.java`, `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/ingest/ScheduledIngestor.java`, `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/consumer/PriceWriterConsumer.java`, `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/ws/MarketWebSocketHandler.java`.

**`stock-module-trading`:**
- Purpose: Trade creation/listing, holdings, portfolio summary, and portfolio Redis cache.
- Contains: `api`, `domain`, `repository`, `service` packages.
- Key files: `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/TradingController.java`, `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/TradingService.java`, `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/PortfolioCache.java`, `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/repository/JdbcTradingRepository.java`.

**`stock-start`:**
- Purpose: Runtime Spring Boot application module.
- Contains: Entry point, security config, global exception handler, resources, and integration tests.
- Key files: `stock-start/src/main/java/dowob/xyz/stockwebv2/start/StockWebV2Application.java`, `stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java`, `stock-start/src/main/java/dowob/xyz/stockwebv2/start/error/GlobalExceptionHandler.java`, `stock-start/src/main/resources/application.yaml`, `stock-start/src/main/resources/application-dev.yaml`.

**`../vue/stock-v2/vue-app/src/pages`:**
- Purpose: Top-level frontend screens.
- Contains: `Overview.vue`, `Markets.vue`, `Chart.vue`, `Positions.vue`, `Analytics.vue`, `Trades.vue`, `Alerts.vue`, `Notifications.vue`, `Settings.vue`, `Watchlist.vue`, `Backtest.vue`, `Ops.vue`.
- Key files: `../vue/stock-v2/vue-app/src/pages/Backtest.vue`, `../vue/stock-v2/vue-app/src/pages/Markets.vue`, `../vue/stock-v2/vue-app/src/pages/Ops.vue`.

**`../vue/stock-v2/vue-app/src/services`:**
- Purpose: Mock/API adapter boundary for backend integration.
- Contains: Generic fetch client, DTO/API types, domain-specific API modules, runtime mode selector, and tests.
- Key files: `../vue/stock-v2/vue-app/src/services/apiClient.ts`, `../vue/stock-v2/vue-app/src/services/pageApiClients.ts`, `../vue/stock-v2/vue-app/src/services/backtestApi.ts`, `../vue/stock-v2/vue-app/src/services/opsApi.ts`, `../vue/stock-v2/vue-app/src/services/aiAccessApi.ts`, `../vue/stock-v2/vue-app/src/services/runtimeDataMode.ts`.

## Key File Locations

**Entry Points:**
- `stock-start/src/main/java/dowob/xyz/stockwebv2/start/StockWebV2Application.java`: Backend executable Spring Boot entry point.
- `../vue/stock-v2/vue-app/src/main.ts`: Frontend Vue mount entry point.
- `../vue/stock-v2/vue-app/src/App.vue`: Active frontend page composition entry point.

**Configuration:**
- `pom.xml`: Backend Maven parent, modules, Java version, managed test dependencies.
- `stock-start/pom.xml`: Backend runtime module dependencies and Spring Boot Maven plugin.
- `stock-start/src/main/resources/application.yaml`: Backend base application, server, management, Kafka, CORS, JWT config.
- `stock-start/src/main/resources/application-dev.yaml`: Backend dev datasource/Redis/Flyway config using environment variables and optional `.env`.
- `../vue/stock-v2/vue-app/package.json`: Frontend npm scripts and dependencies.
- `../vue/stock-v2/vue-app/vite.config.ts`: Frontend Vite/Vitest configuration.

**Core Logic:**
- `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/service/AuthService.java`: Auth registration and credential verification.
- `stock-module-asset/src/main/java/dowob/xyz/stockwebv2/asset/service/AssetQueryService.java`: Asset search orchestration.
- `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/service/BacktestService.java`: Backtest request validation and run lifecycle.
- `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/engine/DeterministicBacktestEngine.java`: Deterministic backtest calculations.
- `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/ingest/MarketDataIngestService.java`: Kafka publication for market ticks/backfills.
- `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/consumer/WsBroadcastConsumer.java`: Latest cache and WebSocket broadcast fanout.
- `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/TradingService.java`: Trade execution, holdings, summary orchestration.
- `../vue/stock-v2/vue-app/src/services/apiClient.ts`: Frontend generic backend envelope parsing and error type.
- `../vue/stock-v2/vue-app/src/store.ts`: Frontend mutable mock state for positions, trades, and alerts.

**Testing:**
- `stock-*/src/test/java`: Backend module test roots.
- `stock-start/src/test/java`: Backend start/integration test root.
- `../vue/stock-v2/vue-app/src/*.test.ts`: Frontend Vitest tests.
- `../vue/stock-v2/vue-app/src/components/settings/*.test.ts`: Settings component tests.
- `../vue/stock-v2/vue-app/src/services/*.test.ts`: Frontend API adapter tests.

**Database:**
- `stock-db-migration/src/main/resources/db/migration/V1__foundation_schema.sql`: Foundation users/assets schema.
- `stock-db-migration/src/main/resources/db/migration/V2__foundation_seed_assets.sql`: Seed asset data.
- `stock-db-migration/src/main/resources/db/migration/V3__backtest_schema.sql`: Backtest tables.
- `stock-db-migration/src/main/resources/db/migration/V4__market_data_hypertable.sql`: Market price hypertable.
- `stock-db-migration/src/main/resources/db/migration/V5__market_data_continuous_aggregates.sql`: K-line aggregate views.
- `stock-db-migration/src/main/resources/db/migration/V6__market_data_spring_batch_metadata.sql`: Spring Batch metadata.
- `stock-db-migration/src/main/resources/db/migration/V7__trading_schema.sql`: Trading and holdings tables.

## Naming Conventions

**Backend Files:**
- Maven modules use `stock-*` and feature modules use `stock-module-*`: `stock-module-market-data`, `stock-module-trading`.
- Java package root is `dowob.xyz.stockwebv2`.
- Feature packages use lowercase domain names: `user`, `asset`, `backtest`, `marketdata`, `trading`.
- Controllers end with `Controller`: `AuthController`, `MarketController`, `TradingController`.
- DTOs and request records end with `Dto`, `Request`, or `Response`: `LatestPriceDto`, `CreateTradeRequest`, `AuthResponse`.
- Services end with `Service` or domain-specific role names: `AuthService`, `MarketLatestService`, `PortfolioCache`, `BacktestMapper`.
- Repository interfaces end with `Repository`; JDBC implementations start with `Jdbc`: `UserRepository`, `JdbcUserRepository`.
- Domain records/enums use business nouns: `User`, `Asset`, `BacktestRun`, `TradeTransaction`, `TradeType`.
- Config classes end with `Config`: `SecurityConfig`, `KafkaConfig`, `WsConfig`.
- Flyway files use `V{number}__description.sql`: `V7__trading_schema.sql`.

**Frontend Files:**
- Vue components/pages use PascalCase `.vue`: `Backtest.vue`, `OrderTicket.vue`, `SettingsAiAccess.vue`.
- TypeScript services use camelCase file names: `apiClient.ts`, `backtestApi.ts`, `runtimeDataMode.ts`.
- Tests are co-located and use `.test.ts`: `backtestApi.test.ts`, `SettingsAiAccess.test.ts`.
- Shared frontend types live in `../vue/stock-v2/vue-app/src/types.ts` and API DTO types live in `../vue/stock-v2/vue-app/src/services/apiTypes.ts`.

**Directories:**
- Backend feature modules should keep `api`, `domain`, `repository`, `service`; add specialized packages only when the module has a distinct runtime concern, as market data does with `batch`, `consumer`, `ingest`, `provider`, and `ws`.
- Frontend reusable UI goes in `src/components`; route/page-level UI goes in `src/pages`; backend integration code goes in `src/services`; composition functions go in `src/composables`.

## Where to Add New Code

**New Backend Feature Module:**
- Primary code: `stock-module-{feature}/src/main/java/dowob/xyz/stockwebv2/{feature}`
- Tests: `stock-module-{feature}/src/test/java/dowob/xyz/stockwebv2/{feature}`
- Maven: Add module to root `pom.xml` and dependency to `stock-start/pom.xml`.
- Database: Add Flyway migration in `stock-db-migration/src/main/resources/db/migration`.
- Use packages: `api`, `domain`, `repository`, `service`.

**New REST Endpoint in Existing Feature:**
- Controller: `stock-module-{feature}/src/main/java/dowob/xyz/stockwebv2/{feature}/api`
- Request/response DTOs: same `api` package as the controller.
- Business logic: `stock-module-{feature}/src/main/java/dowob/xyz/stockwebv2/{feature}/service`
- SQL: `stock-module-{feature}/src/main/java/dowob/xyz/stockwebv2/{feature}/repository`
- Errors: Add codes to `stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/ErrorCode.java`.

**New Cross-Module Contract:**
- Interface/summary DTO: `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/{area}`
- Owning implementation: Feature module service package, following `stock-module-asset/src/main/java/dowob/xyz/stockwebv2/asset/service/AssetFacadeImpl.java`.
- Shared event payload: `stock-common/src/main/java/dowob/xyz/stockwebv2/common/event`.

**New Persistence Table or View:**
- Migration: `stock-db-migration/src/main/resources/db/migration/V{next}__{description}.sql`
- Repository interface/implementation: Feature module `repository` or market-data `persistence` package.
- Keep SQL out of controllers and services.

**New Market-Data Provider:**
- Provider interface implementation: `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/provider`
- Provider routing: `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/provider/ProviderRegistry.java`
- Ingest publication remains through `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/ingest/MarketDataIngestService.java`.

**New Kafka Consumer or Producer:**
- Topic/config constants: `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/config/KafkaConfig.java`
- Producer orchestration: `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/ingest`
- Consumer: `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/consumer`
- Event record: `stock-common/src/main/java/dowob/xyz/stockwebv2/common/event`

**New WebSocket Message or Channel:**
- Handler/parser/subscription changes: `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/ws`
- Broadcast source: `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/consumer/WsBroadcastConsumer.java`
- Ticket/auth changes: `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/api/WsTicketController.java` and `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/ws/MarketHandshakeInterceptor.java`.

**New Frontend Backend Integration:**
- Types: `../vue/stock-v2/vue-app/src/services/apiTypes.ts`
- Generic request handling: Reuse `../vue/stock-v2/vue-app/src/services/apiClient.ts`
- Domain adapter: Add or extend `../vue/stock-v2/vue-app/src/services/{domain}Api.ts`
- Runtime registration: `../vue/stock-v2/vue-app/src/services/pageApiClients.ts`
- Page usage: `../vue/stock-v2/vue-app/src/pages/{Page}.vue`
- Tests: `../vue/stock-v2/vue-app/src/services/{domain}Api.test.ts` and page/component tests beside existing files.

**New Frontend Page:**
- Page component: `../vue/stock-v2/vue-app/src/pages/{Page}.vue`
- App shell wiring: `../vue/stock-v2/vue-app/src/App.vue`
- Page registry: `../vue/stock-v2/vue-app/src/router.ts`
- Navigation/header/shortcuts as needed: `../vue/stock-v2/vue-app/src/components/Header.vue`, `../vue/stock-v2/vue-app/src/useShortcuts.ts`, `../vue/stock-v2/vue-app/src/types.ts`.

**Utilities:**
- Backend shared API/error/model/event utilities: `stock-common/src/main/java/dowob/xyz/stockwebv2/common`
- Backend Spring/infrastructure utilities: `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure`
- Frontend UI-agnostic helpers: `../vue/stock-v2/vue-app/src/services` for API concerns or `../vue/stock-v2/vue-app/src/composables` for Vue composition concerns.

## Special Directories

**`.planning/codebase`:**
- Purpose: Generated GSD codebase maps used by planning and execution workflows.
- Generated: Yes
- Committed: Yes

**`target`:**
- Purpose: Maven build output for each backend module.
- Generated: Yes
- Committed: No

**`../vue/stock-v2/vue-app/dist`:**
- Purpose: Vite production build output.
- Generated: Yes
- Committed: No

**`../vue/stock-v2/vue-app/node_modules`:**
- Purpose: npm dependency install directory.
- Generated: Yes
- Committed: No

**`.idea`:**
- Purpose: IntelliJ project metadata.
- Generated: Partially
- Committed: Some files are present; avoid relying on IDE metadata for build behavior.

**`.claude` and `docs/superpowers`:**
- Purpose: Local agent/workflow guidance and historical plans.
- Generated: Partially
- Committed: Project-specific workflow context is present; product code conventions should still be verified against source files.

**`src/main/resources`:**
- Purpose: Root-level resource directory outside active Maven modules.
- Generated: No
- Committed: Yes
- Note: Runtime backend configuration is under `stock-start/src/main/resources`, not this root directory.

---

*Structure analysis: 2026-05-30*
