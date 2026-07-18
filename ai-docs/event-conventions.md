# Event & Kafka Conventions

## EventPublisher Interface Contract

### Core Interface

```java
public interface EventPublisher {
    void publish(DomainEvent event);
}
```

### Semantic Guarantees

- Events are delivered **only after DB transaction commit** (`AFTER_COMMIT` semantics)
- Phase 1 implementation: `SpringEventPublisher` (`@TransactionalEventListener(AFTER_COMMIT)` + `@Async`)
- Phase 2 implementation: `KafkaEventPublisher` (`@Profile("kafka")`)
- Switching via Spring Profile — business code requires no changes

### Best Practices

Aggregate Root collects domain events, which are published after Repository save:

```java
public class Transaction extends AggregateRoot {
    // Register event after business operation
    public void execute() {
        // ... business logic

        registerEvent(new TradeExecutedEvent(this.id, this.userId, this.assetId));
    }
}
```

---

## Event Class Standards

### Definition Location

All shared event classes are defined in **`stock-common` (L0)** to ensure all modules can access them.

### Naming Convention

```
{Domain}{Action}Event
```

Examples: `TradeExecutedEvent`, `UserRegisteredEvent`, `MarketPriceUpdatedEvent`

### Version Field

Every event class must include a `version` field:

```java
public record TradeExecutedEvent(
    int version,           // Event version for schema evolution
    Long transactionId,
    Long userId,
    Long assetId,
    // ...
) implements DomainEvent {
    public TradeExecutedEvent { version = 1; }  // Default version
}
```

---

## Three Schema Evolution Rules

1. **Additive-only changes**: Only add new fields — never delete or rename existing fields
2. **Breaking changes use new topic**: If fields must be deleted/modified, create a new topic (e.g., `trade.executed.v2`) — retain the old topic until all consumers have migrated
3. **CI compatibility checks**: CI pipeline includes DTO compatibility tests (ensure new event versions can be deserialized by old consumers)

**Jackson configuration**: `FAIL_ON_UNKNOWN_PROPERTIES = false` (consumers ignore unknown fields)

---

## Kafka Topic Standards

### Topic Naming

```
{domain}.{entity}.{action}
```

| Topic | Producer | Consumer | Purpose |
|-------|----------|----------|---------|
| `market.price.{assetType}` | market-data | trading, asset | Real-time price updates |
| `market.kline.{assetType}` | market-data | trading | K-line raw data |
| `market.news` | market-data | — | News |
| `user.registered` | user | — | User registration event |
| `user.status-changed` | user | WebSocket (`WebSocketConnectionManager`, cross-JVM notification) | User status change |
| `trade.executed` | trading | trading (portfolio recalculation) | Trade completion notification |

### Partition Key

**Asset symbol** is used as the partition key to ensure events for the same asset are ordered within the same partition.

### Retention Policy

| Topic Type | Retention | Reason |
|-----------|-----------|--------|
| `market.price.*` | 1 hour | Price events are ephemeral — meaningless once expired |
| `trade.executed` | 7 days | Gives consumers sufficient re-consumption window |
| `user.*` | 7 days | Same as above |
| `market.kline.*` | 24 hours | K-line data is already written to TimescaleDB |

---

## Consumer Standards

### Consumer Group Naming

```
{module}-{purpose}
```

Examples: `trading-portfolio-recalc`, `asset-price-update`

### Ack Strategy

- **Business events** (`trade.executed`, `user.*`): `MANUAL_IMMEDIATE`, manually ack after processing. **Never discard.**
- **Market events** (`market.price.*`): `MANUAL_IMMEDIATE`, but allow discarding expired prices (timestamp check: if event time > consumption time + threshold, skip)

### Backpressure Strategy

1. `max.poll.records` controls batch size
2. Merge price updates for the same asset_id within a batch (keep only the latest)
3. Market events allow discarding stale values during consumer lag

### Idempotency Guarantees

- Portfolio recalculation is **full recalculation** (total_quantity * latest_price), naturally idempotent
- Optimistic locking (`@Version`) prevents concurrent overwrites
- Daily batch full reconciliation as the ultimate safety net

### Dead Letter Topic (DLT)

- Business event consumption failure → retry 3 times → write to DLT (`{topic}.DLT`)
- DLT messages: alert + audit record, no automatic retry
- Daily batch full reconciliation catches and fixes any inconsistencies

### Partition Assignment Strategy

Use `CooperativeStickyAssignor` for incremental rebalance to minimize market data consumption interruptions.

---

## Kafka Degradation Strategy (Phase 2)

### Producer Side

When Kafka is unavailable: write to **`event_outbox` table**, scheduled task scans and resends.

**`event_outbox` Table Schema:**
```sql
CREATE TABLE event_outbox (
    id           BIGSERIAL PRIMARY KEY,
    event_type   VARCHAR(100)  NOT NULL,   -- e.g., 'TradeExecutedEvent'
    payload      JSONB         NOT NULL,   -- serialized event
    partition_key VARCHAR(100),            -- Kafka partition key (asset symbol)
    status       VARCHAR(20)   NOT NULL DEFAULT 'PENDING',  -- PENDING / SENT / FAILED
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    sent_at      TIMESTAMPTZ,
    retry_count  INT           NOT NULL DEFAULT 0
);
```

**Resend Strategy:**
- Scheduled task scans every **10 seconds** for `status = 'PENDING'` AND `created_at < NOW() - INTERVAL '10 seconds'`
- Order by `created_at ASC, partition_key` to preserve ordering within same partition key
- Max retry: **3 times** — after 3 failures, status set to `FAILED`, alert generated
- Successfully sent records: status set to `SENT`, retained for 7 days then cleaned up
- Business events ensure zero loss; market events may be discarded (not written to outbox)

### Consumer Side

- When Kafka is unavailable: API returns stale data from Redis (marked as stale)
- Daily early-morning batch job performs full reconciliation to fix any inconsistencies
