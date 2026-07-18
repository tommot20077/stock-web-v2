# Architecture & Design

> Full architecture design discussion available at [docs/plans/2026-03-21-architecture-design.md](../docs/plans/2026-03-21-architecture-design.md)

## Core Design Philosophy

*   **Modular Monolith**: Distinct modules (`user`, `asset`, `trading`, `market-data`) with strict boundaries.
*   **Facade Pattern**: Modules interact ONLY via Service Interfaces defined in `stock-infrastructure`, never direct Repository/SQL access.
*   **DDD-Lite**: Rich Domain Models (Entity encapsulates logic), Aggregate Roots enforce consistency.
*   **Deployable Module**: `stock-module-market-data` can run independently or as part of the full application.

## Module Structure

```
stock-web-v2/                        ← parent pom
├── stock-common/                    ← L0: Shared DTOs, Enums, ErrorCodes, Exceptions
├── stock-db-migration/              ← L0: Centralized Flyway migration scripts
├── stock-infrastructure/            ← L1: Facade interfaces, Security, shared config
├── stock-module-user/               ← L2: User management
├── stock-module-asset/              ← L2: Asset definitions (metadata)
├── stock-module-trading/            ← L2: Trade records, portfolios, ROI
├── stock-module-market-data/        ← L2: Market data collection ★ independently deployable ★
└── stock-start/                     ← L3: Aggregating starter
```

## Tech Stack & Decisions

*   **Data Access**: **Spring Data JDBC** (not JPA). Explicit SQL mapping, no lazy loading, no session management.
*   **Security**:
    *   **ECDSA (ES256)** for JWT (Never use RSA).
    *   **Stateful JWT**: Redis `user:auth:{id}` stores Token Version.
*   **Infrastructure**: K3s, PostgreSQL, **TimescaleDB** (time-series), Redis, Kafka, Elasticsearch.
*   **Batch Processing**: **Spring Batch** for ROI calculation, risk indicators. K-line aggregation uses **TimescaleDB Continuous Aggregates**.
*   **Frontend Communication**: Pure REST API + **WebSocket** for real-time market data push (K-line with dynamic interval switching, real-time prices, system notifications). Login required — no anonymous connections.
*   **API**: Always return `ApiResponse<T>`. All external IDs must be **UUIDs**.

## Data Flow

```
External Sources → DataProvider (pluggable) → DataNormalizer → Kafka
                                                                 │
                     ┌───────────────────────────────────────────┤
                     ▼                    ▼                      ▼
              asset module        trading module           TimescaleDB
                                                                 │
                                                          Spring Batch
                                                          (pre-computation)
                                                                 │
                                                           Redis Cache
                                                                 │
                                                     REST API + WebSocket
```

## Cross-Module Communication (Dual-Channel Model)

Inter-module communication uses two independent channels, each with clearly defined use cases:

| Channel | Method | Use Case | Definition Location |
|---------|--------|----------|---------------------|
| **Facade** (synchronous pull) | `XxxFacade` interface call | Queries, CRUD, synchronous data retrieval | `stock-infrastructure` (L1) |
| **Event** (asynchronous push) | `EventPublisher` publishes events | State change notifications, cross-module async processing | Event classes defined in `stock-common` (L0) |

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
| `EventPublisher` / `EventSubscriber` | `SpringEventPublisher` (ApplicationEvent + `@TransactionalEventListener(AFTER_COMMIT)` + `@Async`) | `KafkaEventPublisher` (Phase 2, `@Profile("kafka")`) |
| `SearchService` | `PgSearchService` (ILIKE / tsvector + GIN index) | `ElasticsearchSearchService` (Phase 3) |

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
*   **Pre-computation**: K-line aggregation via TimescaleDB Continuous Aggregates. ROI/portfolio updated in real-time via Kafka events. Risk indicators computed daily by Spring Batch. Results cached in Redis.
*   **Kafka ordering**: Asset symbol as partition key ensures per-asset event ordering.
*   **Pluggable data sources**: `DataProvider` interface allows adding new data sources without changing core logic.
*   **WebSocket ownership**: WebSocket push endpoint (`ws(s)://{host}/ws/v1/market`) resides in the `market-data` module. Supports multiplexed subscriptions (max 10 per connection) with dynamic K-line interval switching. Standalone mode uses `@ConditionalOnBean(UserFacade.class)` to conditionally disable. Implementation: native `TextWebSocketHandler` (no STOMP).
*   **Portfolio concurrent writes**: Optimistic locking (`@Version` column) + `@Retryable` + idempotent full recalculation.
*   **SecurityConfig ownership**: Centralized in `stock-start` (L3), using `@Order` to control FilterChain loading order. `stock-infrastructure` holds shared components (JwtAuthFilter, SecurityContextHelper).
*   **High-frequency computation → Redis first**: All event-driven computed data (portfolio valuations, ROI, dashboard) is written to Redis immediately. DB is the eventual-consistency store via daily batch. See [code-standards.md](code-standards.md).
*   **Portfolio table split**: `holdings` (trade-triggered, low frequency, in DB) + `portfolio_valuations` (price-triggered, high frequency, Redis-first + daily batch to DB). Eliminates lock contention between trades and price updates.

## Aggregate Root Boundaries (Spring Data JDBC)

Spring Data JDBC requires explicit Aggregate Root definitions. Each Aggregate is managed through its Root's Repository.

| Module | Aggregate Root | Value Objects / Child Entities | Cross-Aggregate Reference |
|--------|---------------|-------------------------------|--------------------------|
| user | `User` | `UserPermission` (embedded) | — |
| asset | `Asset` | `StockTwDetail` / `CryptoDetail` / `CurrencyDetail` (1:1 child) | — |
| trading | `Transaction` | — (standalone, append-only) | `userId` (Long), `assetId` (Long) |
| trading | `Holding` | — | `userId` (Long), `assetId` (Long) |
| market-data | `MarketPrice` | — (TimescaleDB hypertable row) | `assetId` (Long) |

**Rules:**
- Cross-Aggregate references use **ID only** (Long), never object references
- Each Aggregate has exactly ONE Repository
- `Transaction` Repository only exposes `insert()` + `findXxx()` — no `save()` for update (append-only)
- `Holding` uses `@Version` for optimistic locking
