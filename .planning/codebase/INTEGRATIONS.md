---
last_mapped_commit: adae04117fcfa99db40b8ca304fb5cb9c5e36ec9
last_mapped_at: 2026-11-07
---
# External Integrations

**Analysis Date:** 2026-11-07

> 前端路徑相對於「前端 repo 根」（本機 / cloud 路徑對照見 `ai-docs/judgment.md` §8）。

## APIs & External Services

**行情資料來源（Market Data Provider）:**
- 目前只有 Mock — `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/provider/MockDataProvider.java`，經 `MockOnlyProviderRegistry` 路由
  - 抽象：`DataProvider` / `ProviderRegistry`（同目錄），接真實 HTTP provider 時在此新增實作
  - 排程：`ingest/ScheduledIngestor.java` 每 `market-data.ingestor.fixed-delay-ms`（預設 1000 ms）抓所有 active+tradeable asset，可用 `market-data.ingestor.enabled=false` 關閉
  - 後端目前無任何對外 HTTP client（無 `RestClient` / `WebClient` 使用）

**前端 ↔ 後端（內部契約）:**
- Transport 唯一邊界：`vue-app/src/services/apiClient.ts`（credentials、CSRF header、`ApiResponse<T>` 信封 `{success, data, error, meta}` 解析、401/403 處理）
- Domain services：`vue-app/src/services/{authApi,tradingApi,portfolioApi,cashApi,holdingEventApi,marketApi,watchlistApi,profileApi,backtestApi}.ts` 接真 API；`opsApi.ts`、`aiAccessApi.ts` 後端無端點，仍為 Preview
- 後端端點前綴：`/api/v1`（assets、market、market/backfill、market/ws、backtests、watchlists、trades 等）、`/api/admin`
- 契約文件：`ai-docs/browser-auth-contract.md`、`ai-docs/trading-portfolio-contract.md`、`ai-docs/watchlist-contract.md`；前端 `docs/api-contracts/mock-to-real-contract.md`

**即時行情 WebSocket:**
- 後端 `WS_PATH = "/ws/v1/market"`（`stock-module-market-data/.../config/WsConfig.java`），以 ticket 驗證（`ws/WebSocketAuthValidator.java`，ticket 由 `POST /api/v1/market/ws/ticket` 取得），allowed origins 同 CORS
- 前端唯一建立點：`vue-app/src/services/quoteStream.ts`（`wsBoundary.test.ts` 守門）

## Data Storage

**Databases:**
- PostgreSQL 16 + TimescaleDB（hypertable / continuous aggregates：`V4__market_data_hypertable.sql`、`V5__market_data_continuous_aggregates.sql`、`V12__kline_real_time_aggregates.sql`）
  - Connection：`STOCK_DB_URL`、`STOCK_DB_USERNAME`（+ 密碼變數）
  - Client：Spring Data JDBC；schema 由 Flyway 管理（`stock-db-migration/src/main/resources/db/migration/`），Spring Batch metadata 亦由 migration 建立（`V6__...`，`initialize-schema: never`）
  - 交易表 append-only trigger：`V8__transactions_append_only_trigger.sql`

**Message Broker:**
- Apache Kafka — `STOCK_KAFKA_BOOTSTRAP_SERVERS`（預設 `localhost:9092`）
  - Topics（`stock-module-market-data/.../config/KafkaConfig.java`）：`market.price.tick.v1`、`market.price.backfill.v1`（3 partitions、保留 24h）及 `.DLT` 死信 topic
  - Producer idempotent、`acks: all`；JSON serializer，trusted package `dowob.xyz.stockwebv2.common.event`
  - Consumers：`consumer/PriceWriterConsumer.java`（寫 DB）、`consumer/WsBroadcastConsumer.java`（推 WebSocket）

**File Storage:**
- Local filesystem only（無物件儲存）

**Caching:**
- Redis — `STOCK_REDIS_HOST`、`STOCK_REDIS_PORT`、`STOCK_REDIS_DATABASE`（預設 1）；用於 `stock-infrastructure`、`stock-module-market-data`、`stock-module-trading`

## Authentication & Identity

**Auth Provider:**
- 自建（無第三方 IdP）
  - JWT：EC P-256 JWK 簽章（`stock.jwt.private-key` / `STOCK_JWT_PRIVATE_KEY`），dev/test/e2e 可留空自動產生暫時金鑰；工具 `scripts/GenerateJwtJwk.java`、`JwkKeyConverter`
  - Token 只放 HttpOnly cookie：`stock_access`、`stock_refresh`（`STOCK_AUTH_COOKIE_*` 控制 SameSite/Secure/Domain/TTL）；前端禁止存入 localStorage/sessionStorage/Pinia
  - CSRF header 由 `vue-app/src/services/apiClient.ts` 附帶；契約見 `ai-docs/browser-auth-contract.md`
  - 以 IP 為鍵的登入限流 / 鎖定依賴 `forward-headers-strategy: native`（Tomcat RemoteIpValve）

## Monitoring & Observability

**Error Tracking:**
- None

**Logs / Metrics:**
- SLF4J（Spring Boot 預設 Logback）；`dev` profile 下 `dowob.xyz.stockwebv2: DEBUG`
- Actuator 於 management port `11181` 暴露 `health,info,metrics`，health probes 開啟；Micrometer metrics
- 追蹤 id 走 `ApiResponse.meta.traceId`

## CI/CD & Deployment

**Hosting:**
- K3s（ingress 之後）；repo 內無 Dockerfile / manifest

**CI Pipeline:**
- 後端 GitHub Actions `.github/workflows/ci.yml`：Lint（`spotless:check`）→ Unit（`mvnw test`）→ Integration（`-pl stock-start -am verify`）→ E2E（`-Pe2e`）→ Browser E2E（checkout 前端同名分支否則 `develop`，Node 20、`npm ci`、Playwright chromium、`bash e2e/run-e2e.sh`）
- 前端 GitHub Actions（前端 repo 根 `.github/workflows/ci.yml`）：develop push / PR 跑 `npm ci → lint → test → build`

## Environment Configuration

**Required env vars:**
- 後端：`STOCK_DB_URL`、`STOCK_DB_USERNAME`、DB 密碼、`STOCK_REDIS_HOST`、`STOCK_REDIS_PORT`、`STOCK_KAFKA_BOOTSTRAP_SERVERS`、`STOCK_JWT_PRIVATE_KEY`（正式環境必填）、`STOCK_CORS_ALLOWED_ORIGINS`、`STOCK_AUTH_COOKIE_SECURE`（正式應為 true）
- 前端：`VITE_DATA_MODE`

**Secrets location:**
- 本機：repo 根 `.env`（gitignored，範本 `.env.example`）；正式環境由部署平台注入環境變數

## Webhooks & Callbacks

**Incoming:**
- None

**Outgoing:**
- None

---

*Integration audit: 2026-11-07*
