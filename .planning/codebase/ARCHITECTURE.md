---
last_mapped_commit: adae04117fcfa99db40b8ca304fb5cb9c5e36ec9
last_mapped_at: 2026-11-07
---
<!-- refreshed: 2026-11-07 -->

# Architecture

**Analysis Date:** 2026-11-07

> 跨 repo 產品:後端 = 本 repo(Java 21 / Spring Boot 多模組 Maven modular monolith);前端 = 前端 repo 根(程式在 `vue-app/`)。本機 `../../vue/stock-v2` 與 cloud `../stock-web-v2-front-end` 的路徑對照見 `ai-docs/judgment.md` §8。前端檔案路徑一律相對於前端 repo 根。

## System Overview

```text
┌──────────────────────────────────────────────────────────────────────┐
│ 前端 repo 根 `vue-app/` (Vue 3 + Pinia + Vue Router + Vite)            │
│  pages/*.vue ── components/*.vue ── composables/use*.ts               │
│            │ (只經 service interface)                                 │
│  `vue-app/src/services/pageApiClients.ts` getRuntimeApiClients()      │
│     ├─ mode=mock → `vue-app/src/stores/mock*.ts`                      │
│     └─ mode=api  → `vue-app/src/services/apiClient.ts` (唯一 fetch)    │
│                    `vue-app/src/services/quoteStream.ts` (唯一 WS)     │
└───────────────┬────────────────────────────────────┬─────────────────┘
   REST /api/v1/** (cookie + X-XSRF-TOKEN)   WS /ws/v1/market (ticket)
                ▼                                    ▼
┌──────────────────────────────────────────────────────────────────────┐
│ L3 `stock-start` — StockWebV2Application, SecurityConfig(JWT cookie   │
│    filter + BrowserCsrfFilter), GlobalExceptionHandler, RateLimit     │
├──────────┬──────────┬───────────┬──────────────┬─────────────────────┤
│ L2 user  │ L2 asset │ L2 trading│ L2 backtest  │ L2 market-data       │
│ auth/csrf│ assets/  │ trades/   │ runs/engine  │ ingest→Kafka→TSDB/   │
│ prefs    │ watchlist│ cash/div  │              │ Redis→WebSocket      │
│          │ FX       │ portfolio │              │                      │
├──────────┴──────────┴───────────┴──────────────┴─────────────────────┤
│ L1 `stock-infrastructure` — Facade 介面、JwtService、RateLimit、       │
│    TraceIdFilter、ApiMetaFactory、AuditLogger、Redis config            │
├──────────────────────────────────────────────────────────────────────┤
│ L0 `stock-common` (ApiResponse/ErrorCode/enums/PriceTickEvent)        │
│ L0 `stock-db-migration` (Flyway V1..V19)                              │
└───────────────┬──────────────────────────────────────────────────────┘
                ▼
   PostgreSQL + TimescaleDB · Redis · Kafka (KRaft)
```

## Component Responsibilities

| Component | Responsibility | File |
|-----------|----------------|------|
| Application entry | `@SpringBootApplication(scanBasePackages="dowob.xyz.stockwebv2")` | `stock-start/src/main/java/dowob/xyz/stockwebv2/start/StockWebV2Application.java` |
| Security chain | JWT(header 或 access cookie)、自訂 `BrowserCsrfFilter`(double-submit)、路徑授權 | `stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java` |
| 全域錯誤 → envelope | Exception → `ApiResponse` failure | `stock-start/src/main/java/dowob/xyz/stockwebv2/start/error/GlobalExceptionHandler.java` |
| API envelope | `ApiResponse<T>{success,data,error,meta}`、`PageResponse<T>` | `stock-common/src/main/java/dowob/xyz/stockwebv2/common/api/` |
| 錯誤碼 / 例外 | `ErrorCode`、`BusinessException` 等 | `stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/` |
| 跨模組 Facade | `AssetFacade`、`FxRateFacade`、`MarketDataFacade`、`UserPreferenceFacade` | `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/{asset,fx,marketdata,user}/` |
| Auth / cookie | 登入、refresh rotation、browser cookie、CSRF bootstrap | `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/{api/AuthController,api/CsrfController,service/BrowserAuthCookieService}.java` |
| 交易 / 持倉 | 已成交手動紀錄、持倉計算、現金、股利、股數調整 | `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/` |
| 行情 | Ingest、Kafka consumer、K 線查詢、WS ticket、WS push | `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/` |
| 回測 | 決定性回測引擎、策略驗證 | `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/engine/DeterministicBacktestEngine.java` |
| 前端 transport 邊界 | credentials、CSRF header、信封解析、401 refresh 重試 | `vue-app/src/services/apiClient.ts` |
| 前端 service 組裝 | 依 `VITE_DATA_MODE` 建立各 domain API(singleton) | `vue-app/src/services/pageApiClients.ts`、`vue-app/src/services/runtimeDataMode.ts` |
| 前端即時報價 | WS ticket → 連線 → 訂閱、狀態機 | `vue-app/src/services/quoteStream.ts`、`vue-app/src/composables/useLiveQuotes.ts` |

## Pattern Overview

**Overall:** 後端為分層 modular monolith(L0→L3)+ Facade 跨模組 + Kafka 事件扇出;前端為 SPA,以 service interface + runtime data mode(mock/api)隔離 transport。

**Key Characteristics:**
- 模組只能經 `stock-infrastructure` 的 Facade 介面互相呼叫,禁止跨模組直接存取 Repository/SQL(由 `stock-start/src/test/java/dowob/xyz/stockwebv2/start/ArchitectureRulesTest.java` ArchUnit 守護)。
- DDD-Lite 實為 transaction script:domain 是不可變 `record`,規則在純 calculator(`HoldingCalculator`、`CashBalanceCalculator`、`DayPnlCalculator`)與 application service。
- 資料存取用手寫 `Jdbc*Repository` + `JdbcClient`(market-data 批次寫入用 `NamedParameterJdbcTemplate`);非 JPA、非 Spring Data JDBC repository,`spring-boot-starter-data-jdbc` 只為 JDBC 基礎設施。
- 對外 ID 一律 UUID;所有 REST 回 `ApiResponse<T>`,traceId 只在 `meta.traceId`。
- 前端元件不得 `fetch`、不得 import mock store;mock 是明確模式,無效值 fail fast(`RuntimeDataModeError`)。

## Layers

**L0 `stock-common`:**
- Purpose: 共用 DTO、enum、錯誤碼、事件
- Location: `stock-common/src/main/java/dowob/xyz/stockwebv2/common/`
- Contains: `api/`、`error/`、`event/PriceTickEvent`、`model/`(`AssetType`、`CurrencyCode`、`Role`…)、`time/ApiTimeParser`
- Depends on: 無(專案內)
- Used by: 所有模組

**L0 `stock-db-migration`:**
- Purpose: 集中 Flyway schema(`V1__`…`V19__`)
- Location: `stock-db-migration/src/main/resources/db/migration/`
- Used by: `stock-start` 啟動時

**L1 `stock-infrastructure`:**
- Purpose: Facade 介面、安全(`JwtService` ES256)、rate limit、web 支援(`TraceIdFilter`、`ApiMetaFactory`、`AuthenticatedUserResolver`、`ClientIpResolver`)、`AuditLogger`、`RedisConfig`
- Location: `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/`
- Depends on: `stock-common`
- Used by: L2 模組、`stock-start`

**L2 業務模組(`stock-module-*`):**
- Purpose: 各領域 `api/`(Controller + DTO)→ `service/` → `domain/` → `repository/`
- Location: `stock-module-{user,asset,trading,backtest,market-data}/`
- Depends on: L0、L1(Facade 介面)
- Used by: `stock-start`;他模組僅透過 Facade 實作(如 `asset/service/AssetFacadeImpl.java`、`marketdata/api/MarketDataFacadeImpl.java`、`user/service/UserPreferenceFacadeImpl.java`)

**L3 `stock-start`:**
- Purpose: 聚合啟動、SecurityConfig、全域例外、API rate limit interceptor、整合測試(`*IT.java`)
- Location: `stock-start/`

**前端 `vue-app/src`:**
- pages(`vue-app/src/pages/*.vue`)→ components / composables → services(`vue-app/src/services/*Api.ts`)→ `apiClient.ts` / `quoteStream.ts` 或 mock stores(`vue-app/src/stores/mock*.ts`)

## Data Flow

### Primary Request Path(前端 → 寫入交易)

1. 頁面/對話框(如 `vue-app/src/components/OrderTicket.vue`)呼叫 `getRuntimeApiClients().trading`(`vue-app/src/services/pageApiClients.ts`)
2. `vue-app/src/services/tradingApi.ts` 呼叫 `apiRequest()`(`vue-app/src/services/apiClient.ts:328`);unsafe method 先 `ensureCsrfToken()`(`:202`,打 `GET /api/v1/csrf`),附 `X-XSRF-TOKEN`、`credentials:'include'`(`:168`)
3. 後端 `TraceIdFilter` → `SecurityConfig` 的 JWT cookie filter(讀 access cookie,`SecurityConfig.java:192`)→ `BrowserCsrfFilter` 比對 cookie/header(`:328-329`)
4. `TradingController` → `TradingService` → `TradePayloadMatcher`/`HoldingCalculator` → `JdbcTradingRepository`(transactions 為 append-only,trigger 見 `V8__transactions_append_only_trigger.sql`)
5. 回 `ApiResponse<TradeDto>`;前端 `apiClient` 解析信封,失敗轉 `ApiClientError`;401 走 `fetchWithSessionRecovery` refresh 後重放一次(`apiClient.ts:299-322`)

### 行情 ingest 與推播

1. `ScheduledIngestor` 透過 `ProviderRegistry`/`DataProvider`(目前 `MockDataProvider`)取價(`marketdata/ingest/`)
2. 發佈 Kafka `market.price.tick.v1`(key=assetId)
3. `PriceWriterConsumer` 寫 `market_prices`(TimescaleDB hypertable,`kline_1m…1d` continuous aggregates)
4. `WsBroadcastConsumer` 寫 Redis `market:latest:{assetId}` 並經 `SubscriptionManager`/`SessionSendDispatcher` 推 WS
5. 前端 `quoteStream.ts`:`POST` ws-ticket(`WsTicketController`)→ 連 `/ws/v1/market`(`MarketHandshakeInterceptor` 驗 ticket)→ 訂閱 → `useLiveQuotes` 供元件

### 歷史回補

`POST /api/v1/market/backfill`(`BackfillController`)→ Spring Batch(`marketdata/batch/BackfillJobConfig.java`)→ Kafka `market.price.backfill.v1` → `market_prices`。

**State Management:**
- 後端無 session:stateful JWT,Redis 存 token version(`user:auth:{id}`);portfolio 讀取有 `trading/service/PortfolioCache.java`。
- 前端:Pinia(`vue-app/src/main.ts`),mock 資料在 `vue-app/src/stores/`;token 只在 HttpOnly cookie,不進 JS 可讀儲存。

## Key Abstractions

**Facade:**
- Purpose: 同步跨模組查詢
- Examples: `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/marketdata/MarketDataFacade.java`、`.../asset/AssetFacade.java`
- Pattern: 介面在 L1、實作在擁有模組;只能由 Application Service 呼叫,Controller 不可

**ApiResponse<T> / PageResponse<T>:**
- Examples: `stock-common/src/main/java/dowob/xyz/stockwebv2/common/api/ApiResponse.java`;前端對應型別 `vue-app/src/services/apiTypes.ts`

**DataProvider / ProviderRegistry:**
- Examples: `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/provider/`
- Pattern: 可插拔行情源(Strategy)

**BacktestEngine:**
- Examples: `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/engine/BacktestEngine.java`

**前端 domain API factory:**
- Examples: `vue-app/src/services/{tradingApi,portfolioApi,cashApi,marketApi,watchlistApi,backtestApi,authApi}.ts`
- Pattern: `createXxxApi(mode, basePath)` 回傳 interface,mock / api 兩實作

## Entry Points

**Backend app:**
- Location: `stock-start/src/main/java/dowob/xyz/stockwebv2/start/StockWebV2Application.java`
- Config: `stock-start/src/main/resources/application.yaml`(+ `-dev`、`-demo`、`-e2e-browser` profiles)
- Dev script: `scripts/run-dev.ps1`

**REST controllers:** `*Controller.java` 於各模組 `api/`,路徑前綴 `/api/v1/**`,admin `/api/admin/**`(`AdminUserController`,`hasRole("ADMIN")`)

**WebSocket:** `/ws/v1/market/**` — `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/ws/MarketWebSocketHandler.java`

**Kafka consumers / schedulers:** `marketdata/consumer/*Consumer.java`、`marketdata/ingest/ScheduledIngestor.java`

**Frontend:**
- `vue-app/index.html` → `vue-app/src/main.ts` → `vue-app/src/App.vue`
- `vue-app/src/router.ts`:routes 的 component 為 `render: () => null`,實際頁面由 `App.vue` 依 route name 切換(import 於 `App.vue:87-99`)
- Dev proxy:`vue-app/vite.config.ts`(`backendProxy`)

## Architectural Constraints

- **Threading:** 後端 servlet thread-per-request;ingest 平行呼叫 provider 並有 per-call timeout;WS 每 session 有獨立 send queue(`SessionSendDispatcher`、`WsSendConfig`)。前端單執行緒。
- **Global state:** 前端 `pageApiClients.ts` 模組級 `clients` singleton(含單一 `QuoteStream` 連線);`apiClient.ts` 模組級 session handlers(`configureApiClientSessionHandlers`)。
- **Circular imports:** 未偵測到;L2 模組間無 Maven 依賴,ArchUnit 守護。
- **單一可執行 app:** market-data 可用 `market-data.ingestor.enabled` 等開關停用,但非獨立部署。
- **CSRF:** Spring 內建 CSRF 關閉(`SecurityConfig.java:69`),改由自訂 `BrowserCsrfFilter` 對 cookie 認證的 unsafe 請求強制 double-submit。
- **交易語義:** `POST /api/v1/trades` 是已成交紀錄,非委託單。

## Anti-Patterns

### 跨模組直接存取 Repository

**What happens:** 在 trading 內注入 asset 的 Repository 或寫 SQL 讀他模組表。
**Why it's wrong:** 破壞模組邊界,ArchUnit 會失敗。
**Do this instead:** 經 L1 Facade,如 `MarketDataFacade`(實作 `marketdata/api/MarketDataFacadeImpl.java`)。

### Controller 直接呼叫 Facade

**What happens:** Controller 注入 `AssetFacade`。
**Why it's wrong:** 違反 `ai-docs/architecture.md` 規則,Facade 只能由 Application Service 呼叫。
**Do this instead:** 由 `service/` 層呼叫(參考 `trading/service/PortfolioQueryService.java`)。

### 元件內自行 fetch 或 import mock store

**What happens:** `.vue` 直接 `fetch('/api/v1/...')` 或 import `stores/mockPortfolio`。
**Why it's wrong:** 繞過 CSRF/信封/401 處理,mock 變成隱性 fallback。
**Do this instead:** 經 `getRuntimeApiClients()`(`vue-app/src/services/pageApiClients.ts`)。

## Error Handling

**Strategy:** 後端丟 `BusinessException`(含 `ErrorCode`)及子類,`GlobalExceptionHandler` 統一轉 `ApiResponse` failure;security 錯誤由 `ApiSecurityErrorWriter` 寫同信封。前端 `apiClient.ts` 轉 `ApiClientError`(含 status、code、fields、traceId)。

**Patterns:**
- 欄位錯誤 `FieldValidationException` → `error.fields`,前端 `fieldsFrom()` 解析
- 401(非 login/refresh 端點)→ refresh 一次再重放;仍 401 → session handler 通知 UI(`SessionBanner.vue`)
- 429 → `RateLimitExceededException`

## Cross-Cutting Concerns

**Logging:** SLF4J + `stock-start/src/main/resources/logback-spring.xml`;`TraceIdFilter` 設 traceId;稽核 `infrastructure/audit/AuditLogger.java`。
**Validation:** Request DTO + Bean Validation;domain parser `trading/domain/ApiValueParser.java`、`common/time/ApiTimeParser.java`。
**Authentication:** ES256 JWT(`JwtService`),browser 走 HttpOnly cookie + CSRF(契約 `ai-docs/browser-auth-contract.md`);登入鎖定 `LoginAttemptService`;rate limit `RateLimitService` + `ApiRateLimitInterceptor`。
**Observability:** Actuator + `marketdata/observability/`(health、info、metrics)。

---

*Architecture analysis: 2026-11-07*
