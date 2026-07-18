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
