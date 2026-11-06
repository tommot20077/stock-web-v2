---
last_mapped_commit: 5ca148d0e17ea7e2cafc957738dbfc70fe6dfa74
---

# External Integrations

**Analysis Date:** 2026-11-06(初次分析 2026-05-30)

**Refresh:** 2026-11-06 targeted refresh (quick-261106-fbs Task 3) — 以程式核對並修正與現況衝突處,**不是** `/gsd-map-codebase` 全量重產(本次執行環境無法派 mapper agents;建議日後全量重產)。前端路徑以 Yuan 本機佈局 `../../vue/stock-v2/vue-app` 表示(cloud 環境為 `../stock-web-v2-front-end/vue-app`)。

## APIs & External Services

**Backend HTTP API:**
- Spring MVC REST API - Main backend API under `/api/v1`.
  - SDK/Client: Browser `fetch` through sibling frontend `../../vue/stock-v2/vue-app/src/services/apiClient.ts`.
  - Auth: browser clients use HttpOnly cookies `stock_access` / `stock_refresh` plus double-submit CSRF (`XSRF-TOKEN` cookie + `X-XSRF-TOKEN` header) for unsafe methods; non-browser clients use `Authorization: Bearer <accessToken>` from `POST /api/v1/auth/token`. Both are parsed by `SecurityConfig` (`JwtAuthenticationFilter`, `BrowserCsrfFilter`); contract in `ai-docs/browser-auth-contract.md`.
  - Public endpoints (URL layer): `GET /api/v1/assets`, `GET /api/v1/csrf`, `/api/v1/auth/{register,login,token,refresh,logout}`, `/actuator/health`, `/v3/api-docs/**`, `/swagger-ui/**`, and `/ws/v1/market/**` in `stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java`.
  - Protected endpoints: `/api/v1/me`, `/api/v1/me/preferences`, market latest/K-line/backfill/ticket APIs, backtests, trades, portfolio (holdings, summary, cash entries, dividends, share adjustments, activities), watchlists, and `/api/admin/**` (ADMIN) in `stock-module-*`.

**OpenAPI:**
- Springdoc OpenAPI/Swagger UI - API docs exposed through `/v3/api-docs/**` and `/swagger-ui/**`.
  - SDK/Client: `springdoc-openapi-starter-webmvc-ui` in `stock-start/pom.xml`.
  - Auth: Public route in `stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java`.
  - Toggle: `STOCK_OPENAPI_ENABLED` and `STOCK_SWAGGER_UI_ENABLED` in `stock-start/src/main/resources/application.yaml`.

**Market Data Provider:**
- Mock market data provider - Current runtime provider implementation for latest and historical ticks.
  - SDK/Client: Internal `DataProvider` abstraction in `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/provider/DataProvider.java`.
  - Auth: Not applicable.
  - Implementation: `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/provider/MockDataProvider.java`.
  - Routing: `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/provider/MockOnlyProviderRegistry.java`.
  - Toggle: `market-data.mock.enabled=true` by default via `@ConditionalOnProperty`.
  - Planned external providers are implied by interface comments, but no Binance/Finnhub/Polygon backend SDK or HTTP client implementation was detected.

**Frontend Third-Party Script:**
- TradingView chart widget - Sibling frontend dynamically loads `https://s3.tradingview.com/tv.js`.
  - SDK/Client: Browser script injection in `../../vue/stock-v2/vue-app/src/pages/Chart.vue`.
  - Auth: None detected.
  - Usage: Symbol mapping to TradingView ticker namespaces in `../../vue/stock-v2/vue-app/src/pages/Chart.vue`.

**Frontend API Adapter Contracts:**
- All domain adapters go through `../../vue/stock-v2/vue-app/src/services/apiClient.ts` (`credentials: 'include'`, CSRF header on unsafe methods, `ApiResponse<T>` envelope parsing, 401 refresh-once). Adapters wired to real backend endpoints in API mode: auth, portfolio, trading, cash, holding events (dividends / share adjustments), profile (base currency), market (REST + `quoteStream` WebSocket), watchlist, backtest.
- Backtest API adapter - calls `/api/v1/backtests/*`.
  - SDK/Client: `../../vue/stock-v2/vue-app/src/services/backtestApi.ts`.
- Ops API adapter - Sibling frontend can call `/api/v1/ops/*` endpoints, but backend ops controllers were not detected.
  - SDK/Client: `../../vue/stock-v2/vue-app/src/services/opsApi.ts`.
  - Auth: Same `apiClient.ts` behavior as above; the pages stay labelled Preview.
- AI Access API adapter - Sibling frontend can call `/api/v1/ai-access/*` endpoints, but backend AI-access controllers were not detected.
  - SDK/Client: `../../vue/stock-v2/vue-app/src/services/aiAccessApi.ts`.
  - Auth: Same `apiClient.ts` behavior as above.
- Mock MCP endpoints - Sibling frontend mock data references `https://mcp.resource.app/v1/readonly`, `https://mcp.resource.app/v1/trading`, and `https://mcp.resource.app/v1/admin`.
  - SDK/Client: Mock-only records in `../../vue/stock-v2/vue-app/src/services/aiAccessApi.ts`.
  - Auth: Not implemented; mock data only.

## Data Storage

**Databases:**
- PostgreSQL / TimescaleDB
  - Connection: `STOCK_DB_URL`, `STOCK_DB_USERNAME`, and `STOCK_DB_PASSWORD` in `stock-start/src/main/resources/application-dev.yaml` and `stock-start/src/main/resources/application-demo.yaml`.
  - Client: Spring Data JDBC `JdbcClient`, used by repositories such as `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/repository/JdbcUserRepository.java`.
  - Migrations: Flyway locations `classpath:db/migration` in `stock-start/src/main/resources/application-dev.yaml`.
  - Extensions: `uuid-ossp` in `stock-db-migration/src/main/resources/db/migration/V1__foundation_schema.sql` and `timescaledb` in `stock-db-migration/src/main/resources/db/migration/V4__market_data_hypertable.sql`.
  - Time-series structures: `market_prices` hypertable and `kline_*` continuous aggregates in `V4__market_data_hypertable.sql`, `V5__market_data_continuous_aggregates.sql` and `V12__kline_real_time_aggregates.sql`.
  - FX rates: `fx_rates` (static V2 seed; read by `FxRateFacadeImpl`, no writer) — see todo `2026-09-28-fx-pnl-and-rate-history`.
- H2
  - Connection: Test-only dependency in `stock-start/pom.xml`.
  - Client: Spring Boot test auto-configuration.

**File Storage:**
- Local repository resources only. No S3, GCS, Azure Blob, or filesystem upload integration was detected.

**Caching:**
- Redis
  - Connection: `STOCK_REDIS_HOST`, `STOCK_REDIS_PORT`, `STOCK_REDIS_DATABASE`, and optional `STOCK_REDIS_PASSWORD` in `stock-start/src/main/resources/application-dev.yaml` and `stock-start/src/main/resources/application-demo.yaml`.
  - Client: `StringRedisTemplate` bean in `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/config/RedisConfig.java`.
  - Auth state: `user:auth:{userId}` in `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/service/RefreshTokenService.java` and `stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java`.
  - Refresh tokens: `user:refresh:{token}` and `user:refresh:index:{userId}` in `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/service/RefreshTokenService.java`.
  - WebSocket tickets: `ws:ticket:{ticket}` in `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/ws/WsTicketService.java`.
  - Latest market ticks: `market:latest:{assetId}` in `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/consumer/WsBroadcastConsumer.java` and `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/api/MarketLatestService.java`.
  - Backfill idempotency: `market:backfill:idem:{key}` in `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/api/BackfillIdempotencyService.java`.
  - Trading cache: `portfolio:valuation:v2:{userId}:{assetId}` and `portfolio:summary:v3:{userId}:{baseCurrency}` (60 s) in `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/PortfolioCache.java`.
  - Rate limiting: `rl:{bucket}:{identity}` in `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/security/RateLimitService.java`; login lockout `user:login:fail:{userId}`; refresh replay `user:refresh:used:{token}`. Full table: `ai-docs/redis-convention.md`.

## Authentication & Identity

**Auth Provider:**
- Custom username/password authentication.
  - Implementation: `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java` handles register, login, bearer `token`, refresh, `me`, and logout; `CsrfController` issues the CSRF cookie; `UserPreferenceController` updates the base currency; `AdminUserController` unlocks accounts.
  - Password hashing: BCrypt strength 10 in `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/config/PasswordConfig.java`.
  - User persistence: PostgreSQL `users` table in `stock-db-migration/src/main/resources/db/migration/V1__foundation_schema.sql`.
  - Access token: ES256 JWT generated and parsed by `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/security/JwtService.java`.
  - JWT key configuration: `STOCK_JWT_PRIVATE_KEY`, `STOCK_JWT_ACCESS_TOKEN_TTL`, and `STOCK_JWT_REFRESH_TOKEN_TTL` in `stock-start/src/main/resources/application.yaml`.
  - Refresh token store: Redis through `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/service/RefreshTokenService.java`.
  - Request auth: Stateless Spring Security filter in `stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java`.
  - Authorization: Role and permission authorities derived from JWT claims in `stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java`.

**WebSocket Identity:**
- One-time WebSocket ticket exchange.
  - Ticket issue endpoint: `POST /api/v1/market/ws/ticket` in `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/api/WsTicketController.java`.
  - Ticket storage: Redis `GETDEL` flow in `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/ws/WsTicketService.java`.
  - Handshake validation: `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/ws/MarketHandshakeInterceptor.java`.
  - WebSocket route: `/ws/v1/market` in `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/config/WsConfig.java`.

## Monitoring & Observability

**Error Tracking:**
- No external error tracking service was detected.

**Logs:**
- Spring/SLF4J logging is used throughout backend classes.
- `AUDIT` logger is used for WebSocket auth events in `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/ws/MarketHandshakeInterceptor.java` and `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/api/WsTicketController.java`.
- Request trace IDs are added by `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/web/TraceIdFilter.java` and exposed in CORS via `stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java`.

**Metrics & Health:**
- Spring Boot Actuator exposes `health`, `info`, and `metrics`, configured in `stock-start/src/main/resources/application.yaml`.
- Market-data health checks Kafka via `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/observability/MarketDataHealthIndicator.java`.
- Market-data info contribution lives in `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/observability/MarketDataInfoContributor.java`.
- Market-data Micrometer metrics live in `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/observability/MarketDataMetrics.java`.

## CI/CD & Deployment

**Hosting:**
- Not detected. No Dockerfile, Kubernetes manifests, Helm charts, or deployment workflow were found in the backend root.

**CI Pipeline:**
- GitHub Actions CI in `.github/workflows/ci.yml`.
- Unit job runs `./mvnw -B test --fail-at-end --no-transfer-progress`.
- Integration job runs `./mvnw -B -pl stock-start -am verify -Dspring-boot.repackage.skip=true --fail-at-end --no-transfer-progress`.
- E2E job runs `./mvnw -B -pl stock-start -am test -Pe2e --no-transfer-progress`.
- Test reports use `dorny/test-reporter@v1` and artifacts via `actions/upload-artifact@v4`.
- Spotless job runs `./mvnw -B spotless:check`.
- Browser E2E job checks out the frontend repo (`tommot20077/stock-web-v2-front-end`, same branch name if it exists, else `develop`), installs Node 20 + Playwright Chromium and runs `e2e/run-e2e.sh`.
- The frontend repo has its own CI (`.github/workflows/`, Node 20): `npm ci → lint → test → build` on develop push / PR.

## Environment Configuration

**Required env vars:**
- `STOCK_DB_URL` - JDBC URL for PostgreSQL/TimescaleDB.
- `STOCK_DB_USERNAME` - Database user.
- `STOCK_DB_PASSWORD` - Database password.
- `STOCK_REDIS_HOST` - Redis host.
- `STOCK_REDIS_PORT` - Redis port.
- `STOCK_REDIS_DATABASE` - Redis database index, default `1`.
- `STOCK_REDIS_PASSWORD` - Optional Redis password.
- `STOCK_KAFKA_BOOTSTRAP_SERVERS` - Kafka bootstrap servers, default `localhost:9092`.
- `SERVER_PORT` - HTTP server port, default `11180`.
- `STOCK_MANAGEMENT_PORT` - Actuator management server port, default `11181`.
- `STOCK_CORS_ALLOWED_ORIGINS` - Allowed browser origins, default `http://localhost:5173`.
- `STOCK_JWT_PRIVATE_KEY` - Required outside `dev`, `test`, and `e2e`.
- `STOCK_JWT_ACCESS_TOKEN_TTL` - Access token TTL, default `PT30M`.
- `STOCK_JWT_REFRESH_TOKEN_TTL` - Refresh token TTL, default `P14D`.
- `STOCK_OPENAPI_ENABLED` - OpenAPI docs toggle, default `false`.
- `STOCK_SWAGGER_UI_ENABLED` - Swagger UI toggle, default `false`.
- `STOCK_AUTH_COOKIE_*` - Browser auth cookie names / SameSite / Secure / TTLs (defaults in `ai-docs/browser-auth-contract.md`).
- `VITE_DATA_MODE` - Sibling frontend runtime adapter mode: unset / empty → `mock`, `api` → HTTP adapters, any other value throws `RuntimeDataModeError` (fail fast) in `../../vue/stock-v2/vue-app/src/services/runtimeDataMode.ts`.

**Secrets location:**
- Backend dev profile imports optional `.env` as Spring properties in `stock-start/src/main/resources/application-dev.yaml`.
- `.env.example` exists at the backend root and was not read.
- No secret manager integration was detected.

## Webhooks & Callbacks

**Incoming:**
- No third-party webhook endpoints were detected.
- Browser WebSocket clients connect to `/ws/v1/market`, registered in `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/config/WsConfig.java`.
- Backend market data consumers receive Kafka events through `@KafkaListener` in `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/consumer/PriceWriterConsumer.java` and `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/consumer/WsBroadcastConsumer.java`.

**Outgoing:**
- Kafka producers publish live ticks to `market.price.tick.v1` and backfill ticks to `market.price.backfill.v1` in `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/ingest/MarketDataIngestService.java`.
- Kafka DLT topics are `market.price.tick.v1.DLT` and `market.price.backfill.v1.DLT`, configured in `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/config/KafkaConfig.java`.
- No outbound HTTP integrations were detected in backend source. The current backend market provider is mock-only.
- Sibling frontend performs outbound browser request to TradingView script host from `../../vue/stock-v2/vue-app/src/pages/Chart.vue`.

---

*Integration audit: 2026-05-30;targeted refresh 2026-11-06*
