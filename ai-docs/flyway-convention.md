# Flyway Migration Convention

## Core Rule

**All production migration scripts are centralized in the `stock-db-migration` module (`stock-db-migration/src/main/resources/db/migration/`).**

Other modules MUST NOT place any migration files under `src/main/resources/db/migration/`.

**How modules consume migrations:**
- **Production** (`stock-start`): compile-scope Maven dependency on `stock-db-migration`
- **Module IT tests**: test-scope Maven dependency on `stock-db-migration`

---

## Directory Structure

```
stock-db-migration/
  src/
    main/resources/db/migration/     ← ALL production migrations go here

stock-module-*/
  src/
    test/resources/db/testdata/      ← Test seed data only (repeatable migrations)
```

---

## Version Numbering

- Use **consecutive integers**: V1, V2, V3, ...
- **No gaps** in version numbers
- **Never modify** a migration that has already been applied to any environment (Flyway checksum validation will fail)

---

## Naming Convention

```
V{N}__{description}.sql
```

- `{N}` = next available integer (check stock-start migrations before choosing)
- `{description}` = English snake_case description
- Double underscore (`__`) between version and description

**Examples:**
- `V1__create_users_table.sql`
- `V2__create_assets_table.sql`
- `V3__create_transactions_table.sql`
- `V4__create_market_data_hypertable.sql`

---

## Adding Schema for a New Module

1. Check the current highest version in `stock-db-migration/src/main/resources/db/migration/`
2. Create `V{N+1}__{module_name}_schema.sql` in stock-db-migration
3. Write the DDL matching the module's Model classes exactly
4. Do NOT create any file under the module's `src/main/resources/db/migration/`

---

## Test Migrations

All Flyway migration SQL files (V1..VN and beyond) are **centralized** in the `stock-db-migration` module (`stock-db-migration/src/main/resources/db/migration/`).

**How modules consume migrations:**

- **Production** (`stock-start`): compile-scope Maven dependency on `stock-db-migration`
- **Module IT tests**: test-scope Maven dependency on `stock-db-migration`

```xml
<!-- In each module's pom.xml, test scope -->
<dependency>
    <artifactId>stock-db-migration</artifactId>
    <scope>test</scope>
</dependency>
```

**Per-module test schemas are ELIMINATED.** No more divergence between test and production schemas. Do NOT create `src/test/resources/db/migration/` directories in individual modules.

**For test-specific seed data**, use Flyway repeatable migrations (`R__*.sql`) placed in `src/test/resources/db/testdata/` and add the location to `application-test.yaml`:

```yaml
spring:
  flyway:
    locations:
      - classpath:db/migration
      - classpath:db/testdata
```

---

## TimescaleDB Section

### Extension Installation

```sql
-- In V1 or the earliest migration
CREATE EXTENSION IF NOT EXISTS timescaledb;
```

Note: `CREATE EXTENSION` may require superuser privileges. Ensure the migration user has sufficient permissions, or pre-install during DB initialization.

### Hypertable Creation

```sql
-- Create a regular table first, then convert to hypertable
CREATE TABLE market_prices (
    time        TIMESTAMPTZ NOT NULL,
    asset_id    BIGINT      NOT NULL,
    open        NUMERIC(20,8),
    high        NUMERIC(20,8),
    low         NUMERIC(20,8),
    close       NUMERIC(20,8),
    volume      NUMERIC(20,8),
    PRIMARY KEY (asset_id, time)
);

SELECT create_hypertable('market_prices', by_range('time'));
```

### Continuous Aggregates

```sql
CREATE MATERIALIZED VIEW market_prices_1h
WITH (timescaledb.continuous) AS
SELECT
    time_bucket('1 hour', time) AS bucket,
    asset_id,
    first(open, time) AS open,
    max(high) AS high,
    min(low) AS low,
    last(close, time) AS close,
    sum(volume) AS volume
FROM market_prices
GROUP BY bucket, asset_id;

-- Auto-refresh policy (set in the same migration)
SELECT add_continuous_aggregate_policy('market_prices_1h',
    start_offset => INTERVAL '3 hours',
    end_offset   => INTERVAL '1 hour',
    schedule_interval => INTERVAL '1 hour'
);
```

### Important Notes

- Continuous Aggregate DDL is managed by Flyway migrations — Java layer **only reads via `@Query`** (appears as a regular View to JDBC)
- Auto-refresh policies are set in migrations — Java does not manage them
- Some TimescaleDB DDL cannot execute within a transaction — migration files may need a `-- non-transactional` marker at the top
- Modifying Continuous Aggregates (e.g., adding columns) requires `DROP` + recreate — pay attention to migration idempotency

---

## Prohibited Actions

| Prohibited | Reason |
|------------|--------|
| Placing `.sql` files in module `src/main/resources/db/migration/` | Causes Flyway version conflicts with stock-start |
| Editing an already-deployed migration | Flyway checksum mismatch → startup failure |
| Skipping version numbers | Causes confusion about migration history |
| Using different column types between production and test migrations | Schema divergence causes hard-to-debug test failures |
