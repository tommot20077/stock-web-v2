# Security & Authorization Rules

## Core Principles

### 1. Two Layers of Protection — Both Are Required

| Layer | Mechanism | Granularity |
|-------|-----------|-------------|
| URL layer | `HttpSecurity.requestMatchers()` | Coarse-grained, by URL pattern |
| Method layer | `@PreAuthorize` | Fine-grained, by Permission / Role |

The URL layer protects `/api/admin/**` (ADMIN only). The method layer uses `hasAuthority('XXX')` to guard specific operations.
**Both layers must be applied. Never rely on only one.**

### 2. URL Rule Order Must Not Be Reversed

In `SecurityConfig`, **more specific rules must come before `anyRequest()`**:

```
/api/admin/**  → hasRole("ADMIN")    ← must be first
anyRequest()   → authenticated()     ← must be last
```

If the order is wrong, `anyRequest()` matches first and shadows the admin rule, creating a security hole.

### 3. Role vs Fine-Grained Permission

*   **Role** (`ROLE_XXX`) is used for coarse-grained URL protection (e.g., `/api/admin/**`).
*   **Permission** (e.g., `TRADE_EXECUTE`) is used for fine-grained method protection (e.g., `@PreAuthorize`).
*   The static mapping between roles and permissions is defined in the `Role` enum — nowhere else.

**Permission boundaries per role:**

| Role | Spring Role | Permissions |
|------|-------------|-------------|
| USER | ROLE_USER | WATCHLIST_MANAGE, TRADE_EXECUTE, PORTFOLIO_VIEW, PROFILE_EDIT |
| ADMIN | ROLE_ADMIN | All permissions (`EnumSet.allOf`) |

**Permission overrides**: The `user_permissions` table allows per-user GRANT/REVOKE of individual permissions.
Loading logic: `Role default permissions ∪ Individual GRANTs − Individual REVOKEs = Final permission set`

### 4. Capability vs Ownership Separation

*   `@PreAuthorize("hasAuthority('TRADE_EXECUTE')")` only checks whether the caller *can* execute trades.
*   Whether *this portfolio belongs to the caller* is determined in the **Service layer**. ADMIN automatically bypasses ownership checks.
*   **Never perform ownership checks in the Controller layer.**

#### Ownership Check Mechanism

Use `SecurityUtils.assertOwnerOrAdmin(currentUserId, resourceOwnerId)` utility method.

```java
// Service layer example
public PortfolioDto getPortfolio(Long portfolioId) {
    Portfolio portfolio = portfolioRepository.findById(portfolioId)
        .orElseThrow(() -> new ResourceNotFoundException("Portfolio"));
    SecurityUtils.assertOwnerOrAdmin(SecurityContextHelper.getCurrentUserId(), portfolio.getUserId());
    return mapper.toDto(portfolio);
}
```

**Rules:**
- Ownership check failure throws `ResourceNotFoundException` (not `AccessDeniedException`) to avoid leaking resource existence
- ADMIN automatically bypasses ownership check
- ArchUnit rules automatically scan to ensure Service methods do not omit ownership checks
- Every ownership scenario must have a "non-owner returns 404" test

### 5. Stateful JWT

Every Access Token carries a `version` claim. Redis key `user:auth:{userId}` stores the current valid version.

**Filter validation flow:**
1. Extract `Authorization: Bearer <jwt>` header.
2. Verify JWT signature.
3. Extract `userId`, `tokenVersion`, `role`.
4. Look up Redis `user:auth:{userId}` for version and status; fall back to DB on cache miss and backfill.
5. `tokenVersion` must **exactly match** the Redis version (`Objects.equals`).
6. User status must be `ACTIVE` or `PENDING_VERIFICATION`.
7. **PENDING_VERIFICATION restriction**: If status is `PENDING_VERIFICATION`, the permission set is restricted to `PROFILE_EDIT` only (dynamically determined by status in JWT Filter).
8. Build `Authentication` with principal = `userId (Long)`.

**Instant logout**: update the token version in Redis — existing tokens become invalid immediately without waiting for JWT expiry.

### 5a. Refresh Token

Access Token is short-lived (15-30 minutes), paired with a long-lived Refresh Token (7-30 days).

**Storage**: Redis server-side opaque token (not JWT).

**Redis Key Structure:**
- `user:refresh:{opaqueToken}` → `{ userId, tokenVersion, deviceInfo, createdAt, expiresAt }`
- `user:refresh:index:{userId}` → `Set<opaqueToken>` (reverse index for account deletion / all-device logout)

**Rotation Flow:**
1. Client sends refresh request with opaque token
2. Server validates token exists and is not expired
3. Validates token version matches Redis `user:auth:{userId}`
4. Deletes old refresh token, issues new refresh token + new access token
5. If old token was already used (replay detection), revokes all refresh tokens for that user

**Revocation**: Integrated with token version mechanism — when token version is incremented, all refresh tokens with mismatched versions are automatically invalidated.

### 6. Key Management

*   JWT uses **ECDSA ES256** (never RSA).
*   The private key is stored as a **PKCS8 PEM** string in a K8s Secret, injected via the `JWT_PRIVATE_KEY` environment variable.
*   When `JWT_PRIVATE_KEY` is not set (local / dev), `JwtService` generates a fresh key pair on startup (tokens invalidated on restart).
*   **The public key is derived from the private key** — no need to store it separately.
*   **K3s Secret encryption**: K3s must have `--secrets-encryption` flag enabled to ensure at-rest encryption of Secrets in etcd.

#### Key Rotation Strategy

- **MVP phase**: Single key — increment token version on rotation to force re-login.
- **Production phase**: Dual key coexistence (`JWT_PRIVATE_KEY` + `JWT_PREVIOUS_KEY`), add `kid` field in JWT header. New tokens signed with new key, old tokens verified with old key until expiry. No JWKS needed (monolith does not require it).

### 7. AccessDeniedException Must Be Re-thrown

In `GlobalExceptionHandler`, `AccessDeniedException` **must be re-thrown** — it must not be swallowed by a catch-all handler (which would return HTTP 200 instead of 403):

```java
@ExceptionHandler(AccessDeniedException.class)
public void handleAccessDeniedException(AccessDeniedException e) throws AccessDeniedException {
    throw e;  // Let Spring Security return HTTP 403
}
```

---

## Public Endpoints (No Authentication Required)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/auth/login` | Login |
| POST | `/api/v1/auth/register` | Registration |
| POST | `/api/v1/auth/refresh` | Refresh Token |
| GET | `/api/v1/assets/**` | Public asset browsing (stock list, crypto pairs, currencies) |
| GET | `/api/v1/market-data/**` | Public market data (prices, charts, K-line) |
| GET | `/api/v1/search` | Search (assets only, not users) |
| GET | `/api/v1/search/suggest` | Search suggestions (assets only) |
| ANY | Static resources | css, js, images, etc. |

**Notes:**
- `/api/v1/auth/change-password`, `/api/v1/auth/logout`, etc. **require authentication**
- `/api/v1/users/**` has been moved to authenticated endpoints (to prevent user enumeration attacks)
- If a public profile is needed, restrict to `GET /api/v1/users/{uuid}/profile` (authenticated + strict Rate Limiting + `PublicUserProfileDto` with minimal fields)
- **Swagger UI**: Must be disabled in production (`springdoc.swagger-ui.enabled=false`), only available in dev/demo environments. Controlled via Spring Profile.

All other endpoints require **authentication** (`authenticated()`).
`/api/admin/**` additionally requires the **ADMIN role**.

---

## 8. CORS & CSRF

### CORS
- `allowedOrigins`: Injected via `application.yaml` config — **hardcoding `*` is forbidden**
- `allowedMethods`: `GET, POST, PUT, DELETE, PATCH`
- `allowedHeaders`: `Authorization, Content-Type`
- `allowCredentials`: `true`
- Different environments (dev/demo/prod) define different CORS configurations

### CSRF(Phase 01 起為雙軌設計)
- **瀏覽器 cookie 模式**:unsafe 方法(POST/PUT/PATCH/DELETE)強制 CSRF——cookie `XSRF-TOKEN` + header `X-XSRF-TOKEN` 雙提交,失敗回 403 `AUTH_CSRF_TOKEN_INVALID`。
- **Bearer token 模式**:豁免 CSRF(瀏覽器不會自動附掛 Authorization header)。
- 實作:Spring 內建 CSRF 停用,改用 `SecurityConfig` 內的 `BrowserCsrfFilter`(在 `JwtAuthenticationFilter` 之後);token 簽發端 `GET /api/v1/csrf`。
- 完整契約、豁免清單與 cookie 屬性見 [browser-auth-contract.md](browser-auth-contract.md);IT 覆蓋:`BrowserAuthFlowIT`。
- (歷史註記:2026-05 前本節寫「CSRF 全面停用因為只用 Bearer」;Phase 01 導入瀏覽器 cookie auth 後改為上述雙軌,舊描述曾導致誤判——文件與 SecurityConfig 不一致時,以程式碼與 browser-auth-contract.md 為準。)

---

## 9. WebSocket Connection Security

WebSocket endpoint: `ws(s)://{host}/ws/v1/market` — login required, no anonymous connections.

### Handshake Authentication (Short-lived Ticket Pattern)

Browser native `WebSocket` API cannot set custom headers — JWT must NOT appear in URL (log/Referrer/proxy leakage risk). Use a one-time ticket instead:

1. **Client calls REST endpoint** `POST /api/v1/auth/ws-ticket` (JWT in `Authorization` header, validated by `JwtAuthFilter`)
2. **Server generates opaque ticket** (UUID) → stores in Redis `ws:ticket:{ticket}` with `{ userId, tokenVersion, role }`, TTL **30 seconds**
3. **Client connects** `ws(s)://{host}/ws/v1/market?ticket={uuid}` — URL contains opaque ticket, NOT JWT
4. **`TicketHandshakeInterceptor`** validates:
   - Redis GET `ws:ticket:{ticket}` → retrieve user info
   - Redis DEL `ws:ticket:{ticket}` → **single-use, immediately consumed**
   - If missing/expired → reject handshake (HTTP 401)
   - Store `userId`, `tokenVersion`, `role` in `WebSocketSession.attributes`
   - Check connection limits (account ≤2 / IP ≤5) → FIFO evict if exceeded

**Security properties:**
- JWT never appears in URL — ticket is opaque UUID
- Ticket is single-use — consumed on handshake, replay impossible
- Ticket TTL 30 seconds — even if unconsumed, expires quickly
- JWT validation happens at REST endpoint, not in WebSocket interceptor

### Continuous Validation
1. **Periodic validation**: Server-side Redis token version check every **5 minutes** (via `WebSocketAuthValidator` scheduled task)
2. **On expiry/revocation**: Push `auth_expired` message → close with status code **4001** (AUTH_EXPIRED)
3. **On admin suspension**: Proactively notify `WebSocketConnectionManager` to close all connections for that user (fast path). Cross-JVM notification via Kafka `user.status-changed` event.
4. **userId source**: Obtained entirely from JWT `Authentication.principal` stored during handshake — **request parameters are not accepted**.

### Custom WebSocket Close Status Codes
| Code | Name | Description |
|------|------|-------------|
| 4001 | AUTH_EXPIRED | Token expired/revoked/user suspended |
| 4002 | SESSION_REPLACED | Replaced by newer connection (FIFO) |
| 4003 | IDLE_TIMEOUT | 30-minute idle timeout |
| 4008 | RATE_LIMITED | Command rate exceeded |
| 4009 | SERVER_SHUTDOWN | Graceful server shutdown |

### Redis Unavailability
When Redis is unavailable, WebSocket auth validation cannot proceed → existing connections receive `auth_expired` + reason `redis_unavailable` → connections closed. New handshakes fail with 503 (consistent with REST API fail-closed policy, see §5).

---

## 10. Kafka Security (Phased)

| Phase | Security Measure | Description |
|-------|-----------------|-------------|
| Phase 2 Day 1 | K3s NetworkPolicy | CNI-level isolation — only stock-web-v2 pods can access Kafka |
| Phase 3 (trigger condition) | SASL_PLAINTEXT + SCRAM-SHA-512 + ACL | Triggered by: cross-node Kafka / multi-app shared access / compliance requirements |

**`trade.executed` payload rules:**
- Allowed: transactionId, userId(Long), assetId, type, quantity, price, fee, executedAt
- Forbidden: email, username, and other PII fields

---

## 11. User Deletion Security Flow

Soft delete → immediate Redis cleanup → anonymization after 30 days → transaction records retained for 2 years after anonymization.

**Redis Cleanup Steps (executed immediately, synchronous):**
1. Set `user:auth:{userId}` status = DELETED
2. Increment token version (invalidates all Access Tokens)
3. Find all refresh tokens via `user:refresh:index:{userId}` reverse index
4. Delete all refresh token keys
5. Delete the reverse index key
6. Delete `user:permissions:{userId}` cache

**Anonymization Schedule (after 30 days, Spring Batch):**
- username → `deleted_user_{hash}`
- email → `deleted_{hash}@anonymized.local`
- `transactions` table is retained but userId points to anonymized user
- `transactions` table has DB-level trigger prohibiting UPDATE/DELETE (append-only)

---

## 12. Standalone Mode Security (market-data)

When `market-data` is deployed independently, it does not depend on `stock-infrastructure` (L1):
- **No user authentication provided** — relies on network-level isolation (K3s ClusterIP, not exposed externally)
- **Actuator endpoint protection**: Restricted to ClusterIP access, or disable sensitive endpoints
- **Management endpoints**: If any, restricted to ClusterIP

---

## 13. Audit Logging

Implement minimal version from Phase 1:

- **Technology**: SLF4J AUDIT logger + Logback dedicated appender (writes to `audit.log`)
- **Recorded events**: Login/logout, trade operations, permission changes, account status changes, admin operations
- **Format**: `[AUDIT] userId={} action={} target={} result={} ip={}`
- **Principle**: Add audit logging alongside each feature implementation — never retrofit

---

## 14. Security Testing

| Phase | Tool | Action |
|-------|------|--------|
| Phase 1 | Dependabot | Automated dependency update PRs |
| Phase 1 | SpotBugs + Find Security Bugs | SAST — High severity = fail build |
| Phase 1 | OWASP Dependency-Check | Dependency vulnerability scanning |
| Phase 2 | ZAP | DAST |
| Phase 2 | Trivy | Container image scanning |

**Spring Boot upgrade strategy**: Apply patches immediately, wait for .1 release on minor versions.

---

## 15. Rate Limiting

### Authentication Endpoints

| Endpoint | Limit | Scope | Action on Exceed |
|----------|-------|-------|-----------------|
| `POST /api/v1/auth/login` | 10 requests/minute | Per IP | HTTP 429 + `Retry-After` header |
| `POST /api/v1/auth/register` | 5 requests/hour | Per IP | HTTP 429 |
| `POST /api/v1/auth/refresh` | 5 requests/minute | Per User | HTTP 429 |
| `POST /api/v1/auth/change-password` | 3 requests/hour | Per User | HTTP 429 |

### Account Lockout

- 5 consecutive failed login attempts → account locked for 15 minutes
- Lockout counter stored in Redis (`user:login:fail:{userId}`, TTL 15 minutes)
- Successful login resets the counter
- ADMIN can manually unlock via `/api/admin/users/{uuid}/unlock`

### General API Rate Limiting

- Authenticated endpoints: 100 requests/minute per user (Resilience4j RateLimiter)
- Public endpoints: 60 requests/minute per IP

---

## 16. Password Security

### Hashing Algorithm

- **BCrypt** via Spring Security's `BCryptPasswordEncoder` (strength = 10)
- Rationale: BCrypt is the Spring Security default, well-tested, sufficient for this scale
- Future consideration: Argon2id if hardware allows (more resistant to GPU attacks)

### Password Complexity Requirements

- Minimum 8 characters
- Must contain at least: 1 uppercase letter, 1 lowercase letter, 1 digit
- Validated via `@Pattern` annotation on registration/password-change DTOs

### JWT Claim Minimization

- **Allowed claims**: `sub` (userId), `role`, `tokenVersion`, `iat`, `exp`
- **Forbidden claims**: `email`, `username`, any PII, any business data
- Rationale: JWT payload is base64-encoded (not encrypted), anyone can decode it

---

## 17. Actuator Security

### Exposed Endpoints (Phase 1)

Only the following Actuator endpoints are enabled:
- `health` — includes DB/Redis component status
- `info` — application version
- `metrics` — JVM, HTTP, custom business metrics

### Access Control

- Actuator runs on a **separate management port** (`management.server.port=8081`)
- In K3s: management port is NOT exposed via Service (ClusterIP only for internal monitoring)
- In development: accessible on localhost only

### Disabled Endpoints

The following MUST be explicitly disabled in all environments:
- `env`, `configprops` — leak environment variables (DB passwords, JWT keys)
- `heapdump`, `threaddump` — leak sensitive runtime data
- `shutdown` — dangerous in production

---

## 18. WebSocket Connection & Subscription Limits

| Limit | Value | Enforcement | Rationale |
|-------|-------|-------------|-----------|
| Max WS connections per account | 2 | FIFO — oldest closed, push `auth_expired` reason `session_replaced` | Multiplexed connection needs fewer than SSE |
| Max WS connections per IP | 5 | Reject new handshake (HTTP 429) | Defense against pre-auth connection flooding |
| Max subscriptions per connection | 10 | Reject + error `SUBSCRIPTION_LIMIT_EXCEEDED` | Covers 4 K-line charts + price panels |
| Global max WS connections | 1000 | Reject handshake (HTTP 503) | Prevents thread pool exhaustion |
| Idle timeout | 30 minutes | Close connections with zero subscriptions | Reclaim resources from abandoned sessions |
| Command rate limit | 20/sec/connection | Error `RATE_LIMITED` | Prevent subscribe/unsubscribe spam |
| Reconnect backoff | Exponential (1s, 2s, 4s, max 30s) | Client-side enforcement | Prevents reconnect storms |

### Subscription Channels

| Channel | Data | Params | Push Type |
|---------|------|--------|-----------|
| `price` | Real-time price updates | `{ assetId }` | `data.price` |
| `kline` | K-line OHLCV (supports dynamic interval switching) | `{ assetId, interval }` | `data.kline` / `data.kline.snapshot` |

Allowed K-line intervals: `1m`, `5m`, `15m`, `30m`, `1h`, `4h`, `1d`

---

## 19. HTTP Security Headers

Enforce via `SecurityConfig`:

- `Strict-Transport-Security: max-age=31536000; includeSubDomains` (HSTS)
- `X-Content-Type-Options: nosniff`
- `X-Frame-Options: DENY`
- `Referrer-Policy: strict-origin-when-cross-origin`
- `Cache-Control: no-store` (for API responses containing sensitive data)

Spring Security enables some of these by default — document which are custom additions.
