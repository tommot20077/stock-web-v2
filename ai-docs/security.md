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
*   Whether *this resource belongs to the caller* is enforced by **scoping the data access to the caller** (decided 2026-09-28).
*   **Never perform ownership checks in the Controller layer** — the controller only resolves the caller (`AuthenticatedUserResolver`) and passes `userId` down.

#### Ownership Mechanism (query scoping)

Every repository query for a user-owned resource filters by the caller:

```java
// Repository — the caller's id is part of the WHERE clause, never a post-hoc check
jdbcClient.sql("select ... from backtest_runs where user_id = :userId and uuid = :uuid")
    .param("userId", userId)
    .param("uuid", runUuid)
    .query(this::mapRun)
    .optional();                      // empty → service throws ResourceNotFoundException / *_NOT_FOUND
```

**Rules:**
- Another user's resource is simply *not found* → **404** with a static message (resource type only, never the id), so existence is not leaked
- **ADMIN does not bypass** ownership on user-owned resources. If admin support / audit views are ever needed, add **dedicated admin endpoints** (`@PreAuthorize` ADMIN, audit-logged) instead of a bypass inside the normal endpoints
- Why query scoping instead of a post-hoc `assertOwnerOrAdmin` check: a forgotten check cannot leak data because the query never returns it, and nothing is over-fetched
- Every ownership scenario must have a "non-owner returns 404" test
- (History: the original design called for `SecurityUtils.assertOwnerOrAdmin` with an ADMIN bypass; it was never implemented and was retired on 2026-09-28.)

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
*   The private key is a **P-256 EC JWK** (single-line JSON including the private `d` component and a fixed `kid`), stored in a K8s Secret and injected via the `STOCK_JWT_PRIVATE_KEY` environment variable. **Not PEM** — a PEM value is rejected at startup with conversion instructions.
*   Generate a new key with `java scripts/GenerateJwtJwk.java <kid>` (JDK only); convert a legacy PEM with `JwkKeyConverter` (see its javadoc). Never use online tools to generate or convert the signing key.
*   When `STOCK_JWT_PRIVATE_KEY` is blank, only `dev` / `test` / `e2e` / `e2e-browser` profiles start, with a fresh ephemeral key pair (tokens invalidated on restart); every other profile refuses to start.
*   The public key is part of the JWK (`x` / `y`) — no separate public key setting and no derivation at startup.
*   **K3s Secret encryption**: K3s must have `--secrets-encryption` flag enabled to ensure at-rest encryption of Secrets in etcd.

#### Key Rotation Strategy

- **MVP phase (current)**: Single key. Replacing `STOCK_JWT_PRIVATE_KEY` invalidates **access tokens only**. Refresh tokens are opaque Redis entries (`user:refresh:{token}`), not signed by the JWT key, so existing sessions survive: the client's next 401 triggers one refresh and receives an access token signed with the new key. This is the intended behaviour for routine rotation (no forced re-login).
- **When every session must end** (suspected session theft, not just key replacement): rotating the key is not enough. Also revoke refresh tokens — per user via token-version increment / `RefreshTokenService.revokeAllForUser`, or globally by deleting `user:refresh:*` in the Redis database configured by `STOCK_REDIS_DATABASE` (e.g. `redis-cli -n <db> --scan --pattern 'user:refresh:*' | xargs -r redis-cli -n <db> del`).
- **Production phase (not implemented)**: Dual key coexistence (`STOCK_JWT_PRIVATE_KEY` + a previous key), selected by the `kid` already present in the JWT header. New tokens signed with new key, old tokens verified with old key until expiry. No JWKS needed (monolith does not require it).

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
| ANY | `/ws/v1/market/**` | WebSocket 握手。URL 層 `permitAll`，驗證全在 `MarketHandshakeInterceptor`（一次性 ticket，見 §9）；Origin 白名單同 `stock.cors.allowed-origins` |

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
- `allowedHeaders`: `*`(`SecurityConfig` 實際設定;業務端點會用到 `Authorization`、`Content-Type`、`X-XSRF-TOKEN`、`X-Trace-Id`,以及 `POST /api/v1/trades` **必填**的 `Idempotency-Key`——若日後收緊白名單,這幾個都必須保留,否則瀏覽器 preflight 會直接擋掉下單)
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

WebSocket endpoint: `ws(s)://{host}/ws/v1/market?ticket={ticket}` — 必須登入，不接受匿名連線。URL 層 `/ws/v1/market/**` 為 `permitAll`，驗證全部在握手攔截器 `MarketHandshakeInterceptor`（見 Public Endpoints 表）。

### Handshake Authentication (Short-lived Ticket Pattern)

瀏覽器原生 `WebSocket` API 無法設定自訂 header，而 JWT 不得出現在 URL（log / Referrer / proxy 洩漏風險），因此改用一次性 ticket：

1. **客戶端取 ticket**：`POST /api/v1/market/ws/ticket`（`WsTicketController`，class 層 `@PreAuthorize("isAuthenticated()")`）。
   - 瀏覽器：HttpOnly access cookie + `X-XSRF-TOKEN`（unsafe request，經 `BrowserCsrfFilter`；缺 / 錯 → 403 `AUTH_CSRF_TOKEN_INVALID`，IT 覆蓋：`WsTicketCookieCsrfIT`）。
   - 非瀏覽器：`Authorization: Bearer {accessToken}`（豁免 CSRF）。
   - 回應：`{ ticket, expiresIn: 30, wsUrl: "/ws/v1/market" }`；不回傳任何 access / refresh token。
2. **伺服器簽發 ticket**（`WsTicketService`）：32 bytes `SecureRandom` 以 URL-safe Base64（無 padding，約 43 字元）編碼 → 存 Redis `ws:ticket:{ticket}`，值為 `{ userId, issuedAt, tokenVersion }`（`TicketPayload`，不含 role），TTL **30 秒**。tokenVersion 讀自 Redis `user:auth:{userId}`；讀不到 → 503 `AUTH_REDIS_UNAVAILABLE`（不簽發一張必然被拒的 ticket）。
3. **客戶端連線**：`ws(s)://{host}/ws/v1/market?ticket={ticket}` — URL 只含不透明 ticket，不含 JWT。
4. **`MarketHandshakeInterceptor` 驗證**（依序）：
   - 缺 ticket → HTTP 401
   - Redis `GETDEL ws:ticket:{ticket}` 原子取出並刪除（**單次消耗**）；miss / 過期 → HTTP 401
   - payload 的 tokenVersion 與 Redis 現值不符（含 Redis 讀不到）→ HTTP 401
   - 全域連線上限 → HTTP 503；每 IP 連線上限 5 → HTTP 429
   - 通過 → 將 `userId` 等寫入 `WebSocketSession.attributes`
   - **每帳號上限 2 不在握手擋**：連線建立後由 `WebSocketConnectionManager` 以 FIFO 驅逐最舊連線（先推 `auth_expired` reason `session_replaced`，再以 4002 關閉）
   - Origin 白名單：與 REST CORS 共用 `stock.cors.allowed-origins`（`WsConfig`）

**Security properties:**
- JWT 不出現在 URL —— ticket 為不透明隨機字串
- Ticket 單次使用 —— 握手時以 `GETDEL` 消耗，無法重放
- Ticket TTL 30 秒 —— 未使用也很快過期
- 身分驗證（JWT / cookie + CSRF）發生在取 ticket 的 REST 端點；握手只驗 ticket 與 tokenVersion

### Continuous Validation
1. **定期驗證**：`WebSocketAuthValidator` 每 **5 分鐘**掃描所有活躍連線，比對 Redis tokenVersion 與使用者狀態。
2. **過期 / 撤銷 / 停權**：先推 `auth_expired`（reason `token_revoked` / `user_suspended` / `redis_unavailable`）→ 以 **4001** 關閉。
3. **快速撤銷路徑（Kafka `user.status-changed` 跨 JVM 通知）尚未實作**：撤銷延遲的上界為 5 分鐘掃描週期。
4. **userId 來源**：完全取自 ticket payload（握手時寫入 session attributes）—— **不接受任何請求參數**。

### Message Format（訊息格式）

伺服器 → 客戶端（`type` 大小寫混用，客戶端須逐字比對）：

| type | 形狀 | 說明 |
|------|------|------|
| `WELCOME` | `{"type":"WELCOME","sessionId":"…","maxSubscriptions":10,"maxTickSubscriptions":100,"maxKlineSubscriptions":10}` | 連線建立後第一則；上限讀 `SubscriptionManager` 公開常數。`maxSubscriptions` **deprecated**（固定 = 較嚴的 kline 上限 10，保留給舊客戶端自我節制） |
| `SUB_ACK` | `{"type":"SUB_ACK","accepted":["tick.AAPL",…],"rejected":[{"channel":"…","reason":"SUBSCRIPTION_LIMIT_EXCEEDED"}]}` | 每則 SUBSCRIBE 回一則；同 session 重複訂閱為 no-op（兩邊皆不列）；訂閱時不推快照，初值由 REST 取得 |
| `ERROR` | `{"type":"ERROR","code":"WS_MSG_FORMAT_INVALID","detail":"…"}` | 指令格式錯誤（含任一 channel 不合法 → 整則拒絕）；累計 10 次以 4400 關閉 |
| `PING` | `{"type":"PING"}` | 每 30 秒；客戶端回 `{"type":"PONG"}` |
| `TICK` | `{"type":"TICK","channel":"tick.AAPL","data":{"symbol":"AAPL","price":"218.4","volume":"1000","time":"2026-10-31T01:23:45.678Z"}}` | `price` / `volume` 為**字串**（`volume` 可為 null，且是單筆 tick 量）；`time` 為 ISO-8601 UTC |
| `KLINE` | `{"type":"KLINE","channel":"kline.AAPL.1m","data":{symbol,interval,bucket,open,high,low,close,volume}}` | OHLCV 數值為字串 |
| `auth_expired` | `{"type":"auth_expired","reason":"session_replaced" \| "token_revoked" \| "user_suspended" \| "redis_unavailable"}` | 隨後以 4001 / 4002 關閉 |

客戶端 → 伺服器：`{"type":"SUBSCRIBE","channels":[…]}`、`{"type":"UNSUBSCRIBE","channels":[…]}`、`{"type":"PONG"}`；`channels` 必須為非空字串陣列。

**心跳**：伺服器每 30 秒送 `PING`；90 秒內未收到 `PONG` → 以 4000 關閉。

### Custom WebSocket Close Status Codes
| Code | Name | Description |
|------|------|-------------|
| 4000 | HEARTBEAT_TIMEOUT | 90 秒未收到 PONG（`WsHeartbeat`） |
| 4001 | AUTH_EXPIRED | Token 過期 / 被撤銷 / 使用者停權 / Redis 不可用；關閉前先推 `auth_expired` |
| 4002 | SESSION_REPLACED | 每帳號連線上限 2，被較新連線 FIFO 取代；關閉前先推 `auth_expired` reason `session_replaced` |
| 4003 | IDLE_TIMEOUT | 30 分鐘無活動且零訂閱 |
| 4008 | COMMAND_RATE_EXCEEDED | 每連線指令超過 10 則 / 秒（含 PONG）；直接關閉，不回 error |
| 4009 | SERVER_SHUTDOWN | Graceful server shutdown |
| 4400 | BAD_MESSAGE | 指令格式錯誤累計 10 次 |
| 4500 | SEND_FAILURE | 廣播送出失敗，或待送訊息 > 256 則（慢消費者，`SessionSendDispatcher`） |

### Redis Unavailability
Redis 不可用時無法驗證 WS 身分，一律 fail-closed（與 REST 一致，見 §5）：
- **既有連線**：下一次定期驗證推 `auth_expired` reason `redis_unavailable` → 以 4001 關閉。
- **新握手**：tokenVersion 讀不到 → 視為不符，回 HTTP **401**（不是 503）。
- **取 ticket**：`POST /api/v1/market/ws/ticket` 回 **503 `AUTH_REDIS_UNAVAILABLE`**。

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
| Max WS connections per account | 2 | 連線建立後 FIFO 驅逐最舊：先推 `auth_expired` reason `session_replaced`，再以 4002 關閉 | Multiplexed connection needs fewer than SSE |
| Max WS connections per IP | 5 | Reject new handshake (HTTP 429) | Defense against pre-auth connection flooding |
| Max `tick` subscriptions per connection | 100 | 超額列入 `SUB_ACK.rejected`（reason `SUBSCRIPTION_LIMIT_EXCEEDED`），連線不關閉 | tick 為公開行情、不含使用者資料；上限只防資源濫用，與觀察清單上限 100 檔對齊，讓單一連線即可接收整份清單 |
| Max `kline` subscriptions per connection | 10 | 同上（與 tick **各自計數、互不佔額**） | Covers 4 K-line charts + price panels |
| Global max WS connections | 1000 | Reject handshake (HTTP 503) | Prevents thread pool exhaustion |
| Idle timeout | 30 minutes | 以「最後活動時間」計、每 5 分鐘掃描；只關閉零訂閱的連線（4003） | Reclaim resources from abandoned sessions |
| Command rate limit | 10/sec/connection（含 PONG，滑動 1 秒視窗） | 超過直接以 4008 關閉，不回 error | Prevent subscribe/unsubscribe spam |
| Reconnect backoff | Exponential (1s, 2s, 4s, max 30s) | Client-side enforcement | Prevents reconnect storms |

- 訂閱上限讀 `SubscriptionManager.MAX_TICK_CHANNELS_PER_SESSION` / `MAX_KLINE_CHANNELS_PER_SESSION`，並由 WELCOME 的 `maxTickSubscriptions` / `maxKlineSubscriptions` 告知客戶端；`maxSubscriptions` deprecated（固定 = 10），不得改成總和或移除。
- 前綴計數大小寫不敏感（`TICK.X` 計入 tick 額度，不可繞過）；非 `tick.` 前綴者一律以較嚴的 kline 額度計。
- 被拒 reason 字串為 `SUBSCRIPTION_LIMIT_EXCEEDED`（不帶 `WS_` 前綴，對外字面不得改名）。

### Subscription Channels

| Channel | Data | Push Type |
|---------|------|-----------|
| `tick.{symbol}` | Real-time price updates | `TICK` |
| `kline.{symbol}.{interval}` | K-line OHLCV | `KLINE` |

- Channel 為字串，正規式（`WsMessageParser`）**大小寫不敏感**，但訂閱以原字串保存、廣播以 `"tick." + symbol` **精確字串比對** —— `{symbol}` 必須與資產主檔代號完全一致（例：`tick.AAPL`、`tick.2330.TW`），否則會被 accepted 卻永遠收不到推播。
- 訂閱不查資產是否存在；無行情的標的照樣 accepted，只是不會有推播。

Allowed K-line intervals: `1m`, `5m`, `15m`, `1h`, `1d`

---

## 19. HTTP Security Headers

Enforce via `SecurityConfig`:

- `Strict-Transport-Security: max-age=31536000; includeSubDomains` (HSTS)
- `X-Content-Type-Options: nosniff`
- `X-Frame-Options: DENY`
- `Referrer-Policy: strict-origin-when-cross-origin`
- `Cache-Control: no-store` (for API responses containing sensitive data)

Spring Security enables some of these by default — document which are custom additions.
