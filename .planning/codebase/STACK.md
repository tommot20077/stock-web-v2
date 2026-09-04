---
last_mapped_commit: b4459745f0bdf575818d0613cfa9e5b5276f55d8
---

# Technology Stack

**Analysis Date:** 2026-05-30

## Languages

**Primary:**
- Java 21 - Backend application and all Maven modules. Set by `<java.version>21</java.version>` and compiler release in `pom.xml`.
- TypeScript ES2020 - Sibling Vue frontend at `../vue/stock-v2/vue-app/src/**/*.ts` and `../vue/stock-v2/vue-app/src/**/*.vue`, configured in `../vue/stock-v2/vue-app/tsconfig.json`.

**Secondary:**
- SQL - Flyway migrations in `stock-db-migration/src/main/resources/db/migration/*.sql`.
- YAML - Spring profiles in `stock-start/src/main/resources/application.yaml`, `stock-start/src/main/resources/application-dev.yaml`, and `stock-start/src/main/resources/application-demo.yaml`.
- XML - Maven module and dependency declarations in `pom.xml` and `stock-*/pom.xml`.

## Runtime

**Environment:**
- Java 21 JVM. GitHub Actions uses Temurin Java 21 in `.github/workflows/ci.yml`.
- Spring Boot 4.0.4 servlet application. Parent version is declared in `pom.xml`.
- Node.js version is not pinned for the sibling frontend. `../vue/stock-v2/vue-app/package.json` has no `engines` field and no `.nvmrc` was detected.

**Package Manager:**
- Backend: Maven Wrapper with Maven 3.9.14, configured in `.mvn/wrapper/maven-wrapper.properties`.
- Backend lockfile: Not applicable for Maven; dependency versions are controlled by Spring Boot parent and explicit properties in `pom.xml`.
- Frontend: npm, with `package-lock.json` lockfile v3 at `../vue/stock-v2/vue-app/package-lock.json`.

## Frameworks

**Core:**
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

**Testing:**
- JUnit Jupiter via `spring-boot-starter-test` - Backend unit/integration tests throughout `stock-*/src/test/java`.
- Spring Security Test - Auth test support in `stock-start/pom.xml` and `stock-module-market-data/pom.xml`.
- Testcontainers 1.21.4 - PostgreSQL, Redis, and Kafka-backed integration tests, managed by `pom.xml`.
- H2 - Test-only in-memory database dependency in `stock-start/pom.xml`.
- Awaitility - Async Kafka/backfill test assertions in `stock-start/pom.xml` and `stock-module-market-data/pom.xml`.
- Vitest 4.1.6 with jsdom 29.1.1 - Sibling frontend tests configured in `../vue/stock-v2/vue-app/vite.config.ts`.

**Build/Dev:**
- Maven Wrapper - Use `./mvnw` from the backend root for backend build/test commands.
- Spring Boot Maven Plugin - Backend executable packaging in `stock-start/pom.xml`.
- Maven Surefire/Failsafe - Unit, integration, and E2E split in `stock-start/pom.xml`.
- Vite 8.0.13 - Sibling frontend dev server/build tool in `../vue/stock-v2/vue-app/package.json`.
- `@vitejs/plugin-vue` 6.0.7 - Vue SFC support in `../vue/stock-v2/vue-app/vite.config.ts`.
- `vue-tsc` 3.2.9 - Frontend type-check command in `../vue/stock-v2/vue-app/package.json`.

## Key Dependencies

**Critical:**
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

**Infrastructure:**
- PostgreSQL with TimescaleDB extension - Schema uses standard PostgreSQL tables plus Timescale hypertables and continuous aggregates in `stock-db-migration/src/main/resources/db/migration/V4__market_data_hypertable.sql` and `stock-db-migration/src/main/resources/db/migration/V5__market_data_continuous_aggregates.sql`.
- Redis - Runtime dependency for auth, cache, tickets, idempotency, and portfolio cache, configured through `stock-start/src/main/resources/application-dev.yaml`.
- Kafka - Runtime dependency for market-data ingestion, persistence, and WebSocket fanout, configured through `stock-start/src/main/resources/application.yaml`.
- TradingView widget script - Sibling frontend loads `https://s3.tradingview.com/tv.js` in `../vue/stock-v2/vue-app/src/pages/Chart.vue`.

## Configuration

**Environment:**
- Backend default profile is `dev`, set in `stock-start/src/main/resources/application.yaml`.
- Dev profile imports optional `.env` as Spring properties via `stock-start/src/main/resources/application-dev.yaml`; `.env.example` exists but was not read because `.env*` files may contain secrets.
- Runtime server port defaults to `11180`, configured in `stock-start/src/main/resources/application.yaml`.
- Management server port defaults to `11181`, configured in `stock-start/src/main/resources/application.yaml`.
- CORS defaults to the frontend dev origin `http://localhost:5173`, configured in `stock-start/src/main/resources/application.yaml` and enforced in `stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java`.
- JWT private key is required outside `dev`, `test`, and `e2e`; blank non-production profiles generate an ephemeral ES256 key in `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/security/JwtService.java`.
- Sibling frontend runtime mode is `mock` unless `VITE_DATA_MODE=api`, defined by `../vue/stock-v2/vue-app/src/services/runtimeDataMode.ts`.

**Build:**
- Backend root Maven modules are declared in `pom.xml`: `stock-common`, `stock-db-migration`, `stock-infrastructure`, `stock-module-user`, `stock-module-asset`, `stock-module-backtest`, `stock-module-market-data`, `stock-module-trading`, and `stock-start`.
- Main backend artifact and dependency aggregation live in `stock-start/pom.xml`.
- Backend application config lives in `stock-start/src/main/resources/application.yaml`, `stock-start/src/main/resources/application-dev.yaml`, and `stock-start/src/main/resources/application-demo.yaml`.
- Backend CI lives in `.github/workflows/ci.yml`.
- Frontend build config lives in `../vue/stock-v2/vue-app/vite.config.ts` and `../vue/stock-v2/vue-app/tsconfig.json`.

## Platform Requirements

**Development:**
- Java 21 and Maven Wrapper from `mvnw`.
- PostgreSQL compatible with `uuid-ossp` and TimescaleDB extensions for full migration support in `stock-db-migration/src/main/resources/db/migration/`.
- Redis configured through `STOCK_REDIS_HOST`, `STOCK_REDIS_PORT`, `STOCK_REDIS_DATABASE`, and optional `STOCK_REDIS_PASSWORD`.
- Kafka broker configured through `STOCK_KAFKA_BOOTSTRAP_SERVERS`, defaulting to `localhost:9092`.
- npm for the sibling frontend; run scripts are `dev`, `build`, `preview`, `test`, and `test:watch` in `../vue/stock-v2/vue-app/package.json`.

**Production:**
- Deployment target is not detected in repository files. No Dockerfile, Kubernetes manifests, Helm charts, or deployment workflow were detected under the backend root.
- Production needs durable PostgreSQL/TimescaleDB, Redis, Kafka, and a configured `STOCK_JWT_PRIVATE_KEY`.

---

*Stack analysis: 2026-05-30*
