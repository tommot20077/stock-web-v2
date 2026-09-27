# Redis Convention

## Database Allocation

| Application | Redis DB | Purpose |
|------------|----------|---------|
| blog-web-v2 | DB 0 | Blog cache and sessions |
| stock-web-v2 | DB 1 | All stock-web-v2 data |

**Enforcement**: `spring.data.redis.database=1` must be set in all application profiles. Integration tests must verify Redis key prefix contains application identifier.

## Key Naming Convention

Pattern: `{category}:{entity}:{identifier}`

### Security Keys (NO TTL — must not be evicted)

| Key Pattern | Value | Purpose |
|------------|-------|---------|
| `user:auth:{userId}` | `{ tokenVersion, status }` | JWT token version + user status |
| `user:refresh:{opaqueToken}` | `{ userId, tokenVersion, deviceInfo, createdAt, expiresAt }` | Refresh token data |
| `user:refresh:index:{userId}` | `Set<opaqueToken>` | Reverse index for batch revocation |
| `user:login:fail:{userId}` | `Integer` (fail count) | Login failure counter (TTL: 15 min) |

### Permission Cache (TTL: 1 hour)

| Key Pattern | Value | Purpose |
|------------|-------|---------|
| `user:permissions:{userId}` | `Set<Permission>` | Computed permission set (fallback: DB query) |

### High-Frequency Computation Cache (TTL: 5 minutes)

| Key Pattern | Value | Purpose |
|------------|-------|---------|
| `cache:portfolio:{userId}:{assetId}` | `{ marketValue, roi, calculatedAt }` | Portfolio valuation |
| `cache:dashboard:{userId}` | `{ totalMarketValue, totalPnL, allocationRatios }` | Dashboard summary |
| `cache:risk:{userId}` | `{ sharpeRatio, maxDrawdown, calmarRatio }` | Risk indicators |

### Market Data Cache (TTL: 30 seconds)

| Key Pattern | Value | Purpose |
|------------|-------|---------|
| `cache:market:latest:{assetId}` | `{ price, volume, time }` | Latest market price |

## As Implemented (2026-09-27) — read this before adding a key

The tables above are the original design. The code has drifted from them; until Yuan decides whether to
rename the code or amend the design (see "Open decisions"), **new keys should follow the existing
prefixes below** rather than introduce a third style.

| Key pattern | TTL | Owner | Notes |
|-------------|-----|-------|-------|
| `user:auth:{userId}` | none | user / security | token version (matches design) |
| `user:refresh:{token}`, `user:refresh:index:{userId}` | refresh TTL | user | matches design |
| `user:refresh:used:{token}` | refresh TTL | user | replay detection; not in design |
| `user:login:fail:{userId}` | lockout window | user | matches design |
| `rl:{bucket}:{identity}` | rule window | infrastructure `RateLimitService` | rate-limit counters; not in design |
| `ws:ticket:{ticket}` | 30 s | market-data | one-time WebSocket ticket; not in design |
| `market:latest:{assetId}` | 5 min | market-data | design says `cache:market:latest:*`, 30 s |
| `market:backfill:idem:{key}` | 1 h | market-data | backfill idempotency; not in design |
| `portfolio:valuation:{userId}:{assetId}` | 60 s | trading | design says `cache:portfolio:*`, 5 min |
| `portfolio:summary:{userId}` | 60 s | trading | design says `cache:dashboard:*`, 5 min |

Not implemented: `user:permissions:*`, `cache:risk:*`.

Redis DB index: `dev` and `demo` default to **1** (`STOCK_REDIS_DATABASE`); `e2e-browser` defaults to **0**
(throw-away compose stack) — this contradicts the "all profiles use DB 1" rule above.

### Open decisions (need Yuan)

1. Cache prefix: keep `market:` / `portfolio:` as implemented, or migrate to `cache:*` so eviction-safe and
   cache keys are distinguishable by prefix?
2. `e2e-browser` Redis DB 0: allowed exception for the isolated E2E stack, or must be 1?

## Eviction Policy

Use **`volatile-lru`** (only evict keys with TTL set):

- Security keys have **NO TTL** → never evicted
- Cache keys have TTL → evicted under memory pressure
- This ensures JWT token version and refresh tokens are never accidentally evicted

## TTL Strategy

| Category | TTL | Rationale |
|----------|-----|-----------|
| Security (auth, refresh) | None | Critical for authentication, must persist |
| Login failure counter | 15 minutes | Auto-unlock after lockout period |
| Permission cache | 1 hour | Balance between freshness and DB load |
| Portfolio valuation | 5 minutes | High-frequency updates, short TTL acceptable |
| Dashboard | 5 minutes | Derived from portfolio, same TTL |
| Risk indicators | 24 hours | Computed daily, long TTL |
| Market price | 30 seconds | Ephemeral, replaced by next price event |
