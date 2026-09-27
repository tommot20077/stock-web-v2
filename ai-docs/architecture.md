# Architecture & Design

> Full architecture design discussion available at [docs/plans/2026-03-21-architecture-design.md](../docs/plans/2026-03-21-architecture-design.md)

## Core Design Philosophy

*   **Modular Monolith**: Distinct modules (`user`, `asset`, `trading`, `market-data`) with strict boundaries.
*   **Facade Pattern**: Modules interact ONLY via Service Interfaces defined in `stock-infrastructure`, never direct Repository/SQL access.
*   **DDD-Lite (as implemented: transaction script)**: Domain objects are immutable `record`s with no behaviour (e.g. `Holding`, `TradeTransaction`); business rules live in pure calculators (`HoldingCalculator`, `TradePayloadMatcher`) and application services (`TradingService`). Aggregate boundaries below still govern persistence. This is a deliberate fit for Spring Data JDBC — do not describe it as "rich domain models".
*   **Switchable module (not independently deployable)**: `stock-module-market-data` can be *turned off* piecewise (`market-data.ingestor.enabled`, `market-data.scheduling.enabled`, mock provider) but there is only one runnable application (`stock-start`); market-data has no `@SpringBootApplication` or repackage config. True standalone deployment is not implemented.

## Module Structure

```
stock-web-v2/                        ← parent pom
├── stock-common/                    ← L0: Shared DTOs, Enums, ErrorCodes, Exceptions
├── stock-db-migration/              ← L0: Centralized Flyway migration scripts
├── stock-infrastructure/            ← L1: Facade interfaces, Security, shared config
├── stock-module-user/               ← L2: User management
├── stock-module-asset/              ← L2: Asset definitions (metadata)
├── stock-module-trading/            ← L2: Trade records, portfolios, ROI
├── stock-module-market-data/        ← L2: Market data collection, K-lines, WebSocket push (switchable)
└── stock-start/                     ← L3: Aggregating starter
```

## Tech Stack & Decisions

*   **Data Access**: **Spring Data JDBC** (not JPA). Explicit SQL mapping, no lazy loading, no session management.
*   **Security**:
    *   **ECDSA (ES256)** for JWT (Never use RSA).
    *   **Stateful JWT**: Redis `user:auth:{id}` stores Token Version.
*   **Infrastructure**: K3s, PostgreSQL, **TimescaleDB** (time-series), Redis, Kafka. Elasticsearch is planned for Phase 3 (not present).
*   **Batch Processing**: **Spring Batch** is used only for historical market-data backfill (`marketdata/batch`). K-line aggregation uses **TimescaleDB Continuous Aggregates** (`kline_1m`…`kline_1d`, real-time aggregation on since V12). ROI / risk batch jobs are not implemented.
*   **Frontend Communication**: Pure REST API + **WebSocket** for real-time market data push (K-line with dynamic interval switching, real-time prices, system notifications). Login required — no anonymous connections.
*   **API**: Always return `ApiResponse<T>`. All external IDs must be **UUIDs**.

## Data Flow

As implemented (2026-09-27):

```
DataProvider (pluggable; mock today) ─ ScheduledIngestor (parallel, per-call timeout)
        │
        ▼
Kafka  market.price.tick.v1   (key = assetId)
        │
        ├─► PriceWriterConsumer ──► market_prices (TimescaleDB hypertable)
        │                                 └─► kline_1m / 5m / 15m / 1h / 1d (continuous aggregates)
        │
        └─► WsBroadcastConsumer ──► Redis market:latest:{assetId} (TTL 5 min)
                                 └─► WebSocket push (TICK + KLINE, per-session send queue)

Readers in other modules go through MarketDataFacade (L1):
  latest price  = Redis market:latest → market_prices
  daily quote   = latest + previous close (market_prices) + intraday range (kline_1h), by TradingDay
```

Historical backfill: `POST /api/v1/market/backfill` → Spring Batch job → Kafka `market.price.backfill.v1` → `market_prices`.

## Cross-Module Communication (Dual-Channel Model)

Inter-module communication uses two independent channels, each with clearly defined use cases:

| Channel | Method | Use Case | Definition Location |
|---------|--------|----------|---------------------|
| **Facade** (synchronous pull) | `XxxFacade` interface call | Queries, CRUD, synchronous data retrieval | `stock-infrastructure` (L1) |
| **Event** (asynchronous push) | Kafka (`KafkaTemplate` in the producing module) | Market-data fan-out today; cross-module domain events are not used yet | Event classes defined in `stock-common` (L0) |

**Rules:**
- Facade interfaces may only be called by Application Services — Controllers must NOT call them directly
- Kafka Consumers consume events directly, not through Facades (Event channel is independent of Facade)
- Application layer Facade calls are limited to **≤ 3 per request** — beyond that, use Redis pre-computation

## Phased Introduction Strategy (Hard Requirement)

| Phase | Modules | Infrastructure | Milestone |
|-------|---------|---------------|-----------|
| **Phase 1** | common, db-migration, infrastructure, user, asset, start | PG18+TimescaleDB, Redis, Flyway, Security, Actuator | User+Auth+Asset CRUD+PG search |
| **Phase 2** | + trading, market-data | + Kafka(KRaft), Spring Batch | Market data collection+Trading+Portfolios+ROI |
| **Phase 3** | No new modules | + Elasticsearch, Resilience4j | Full-text search+External API resilience |

### Abstraction Layer Interfaces (Defined in Phase 1, implementation deferred)

| Interface | Phase 1 Implementation | Future Implementation |
|-----------|----------------------|----------------------|
| `EventPublisher` / `EventSubscriber` | **Not implemented** — interfaces exist in `infrastructure/event` with no implementation or caller. market-data injects `KafkaTemplate` directly. | `KafkaEventPublisher` (open decision: implement or delete, see 2026-09-02 architecture review M-1) |
| `SearchService` | **Not implemented** — asset search is plain SQL `ILIKE` in `AssetRepository` (wildcards escaped, 64-char cap) | `ElasticsearchSearchService` (Phase 3) |

### Sprint 0 (Foundation Validation)

1. `mvn dependency:resolve` — validate all artifacts
2. Spring Security 7.x Spike — validate 6 breaking change points
3. Testcontainers Spike — PG+TimescaleDB + Redis
4. springdoc-openapi 3.0.2 on Boot 4.x validation
5. **Boot 4.x Go/No-Go Decision**: all pass = Go, any failure = downgrade to 3.4.x LTS
6. blog-web-v2 PG upgrade
7. K3s `--secrets-encryption` enablement

## Observability

**Actuator is a hard Day 1 requirement for Phase 1**:
- `health`: includes DB/Redis component status
- `info`: application version info
- `metrics`: JVM, HTTP, custom business metrics

Without Actuator, infrastructure issues and shared resource isolation triggers cannot be detected.

## Audit Logging

Implemented from Phase 1. SLF4J AUDIT logger + Logback dedicated appender → `audit.log`.
Added alongside each feature implementation — never retrofitted. See [security.md §13](security.md).

## Compliance Design (Taiwan Personal Data Protection Act)

- **User deletion**: Soft delete → immediate Redis cleanup → anonymization scheduled after 30 days (Spring Batch) → optional full purge
- **Data export**: Full data export API (JSON + CSV), Phase 2
- **Transaction record retention**: Retained for 2 years after anonymization
- `transactions` table is append-only (DB trigger prohibits UPDATE/DELETE)

## Key Design Decisions

*   **Market data isolation**: `market-data` module handles all exchange connections. Crashes don't affect business logic.
*   **Subscription decoupled**: Data collection is independent of user subscriptions. "Watchlist" is just a UI preference toggle (user_id + asset_id), not a data pipeline trigger.
*   **Pre-computation**: K-line aggregation via TimescaleDB Continuous Aggregates (real-time aggregation on). Portfolio valuation is computed **on read** from `holdings` + `MarketDataFacade` prices and cached in Redis (`PortfolioCache`, TTL 60 s, invalidated after commit); trades do not emit events. Risk indicators are not implemented.
*   **Kafka ordering**: `assetId` (as string) is the partition key, ensuring per-asset event ordering.
*   **Pluggable data sources**: `DataProvider` interface allows adding new data sources without changing core logic.
*   **WebSocket ownership**: WebSocket push endpoint (`ws(s)://{host}/ws/v1/market`) resides in the `market-data` module. Supports multiplexed subscriptions (max 10 per connection) with dynamic K-line interval switching. Implementation: native `TextWebSocketHandler` (no STOMP); sends go through `SessionSendDispatcher` so a slow client cannot block others. Allowed origins = `stock.cors.allowed-origins` (same list as REST CORS). Daily K-line buckets follow the asset's `TradingDay` (exchange time zone; FX 17:00 New York; crypto UTC).
*   **Portfolio concurrent writes**: `SELECT … FOR UPDATE` on the `holdings` row plus a `version` column checked in the `UPDATE … WHERE version = :version` SQL; new positions use `INSERT … ON CONFLICT DO NOTHING`. Trade creation is idempotent via `Idempotency-Key` (see [trading-portfolio-contract.md](trading-portfolio-contract.md)). No `@Version` / `@Retryable`.
*   **SecurityConfig ownership**: Centralized in `stock-start` (L3). The security filters (`JwtAuthenticationFilter`, `BrowserCsrfFilter`, `ApiSecurityErrorWriter`) are currently inner classes of `SecurityConfig`; `stock-infrastructure` holds `JwtService`, `RateLimitService`, `ClientIpResolver`, `AuthenticatedUserResolver`. Moving the filters into infrastructure is an open refactor (review M-4).
*   **Redis for hot reads**: latest prices (`market:latest:*`) and portfolio views (`portfolio:*`) are cached in Redis; the DB remains the source of truth. There is no daily write-back batch.
*   **Portfolio storage**: only `holdings` (trade-triggered) is persisted; valuations are derived on read. A separate `portfolio_valuations` table does not exist.

## Aggregate Root Boundaries (Spring Data JDBC)

Spring Data JDBC requires explicit Aggregate Root definitions. Each Aggregate is managed through its Root's Repository.

| Module | Aggregate Root | Value Objects / Child Entities | Cross-Aggregate Reference |
|--------|---------------|-------------------------------|--------------------------|
| user | `User` | — (per-user permissions are not implemented) | — |
| asset | `Asset` | — (type-specific detail tables are not implemented) | — |
| trading | `TradeTransaction` (`transactions` table) | — (standalone, append-only) | `userId` (Long), `assetId` (Long) |
| trading | `Holding` | — | `userId` (Long), `assetId` (Long) |
| market-data | `MarketPrice` | — (TimescaleDB hypertable row) | `assetId` (Long) |

**Rules:**
- Cross-Aggregate references use **ID only** (Long), never object references
- Each Aggregate has exactly ONE Repository
- Transactions are insert-only — `transactions` is append-only (DB trigger, V8)
- `Holding` updates check the `version` column in SQL (see Portfolio concurrent writes)
