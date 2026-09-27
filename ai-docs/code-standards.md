# Code Standards

## Code Quality

*   **Simplicity**: Simple > Clever. Readability is paramount.
*   **Small Changes**: Atomic commits. Ask before rewriting systems.
*   **Consistency**: Follow existing local style over external "standards".
*   **No "Mock Mode"**: Use REAL libraries and REAL patterns.

## Null and Empty Value Checks

Prefer the following utility classes for null/empty checks to improve readability and consistency.

> **Note**: The following utility classes require the Apache Commons Lang3 dependency (`org.apache.commons:commons-lang3`).

### `ObjectUtils` (`org.apache.commons.lang3.ObjectUtils`) — General object checks

*   `ObjectUtils.isEmpty(obj)` — replaces `obj == null` and empty checks for collections, arrays, strings
*   `ObjectUtils.isNotEmpty(obj)` — replaces `obj != null` and non-empty checks
*   `ObjectUtils.requireNonEmpty(obj, message)` — precondition check

> **Do NOT use `ObjectUtils.defaultIfNull(obj, defaultValue)`.** It is deprecated as of
> commons-lang3 3.19.0 (the version this project resolves) and will be removed in 4.0.
> For a pure null default use `Objects.requireNonNullElse` / `requireNonNullElseGet` below;
> the decision table at the end of this section already routes pure null checks to `Objects`.

### `StringUtils` (`org.apache.commons.lang3.StringUtils`) — String checks

*   `StringUtils.isBlank(str)` — replaces `str == null || str.trim().isEmpty()`
*   `StringUtils.isNotBlank(str)` — replaces `str != null && !str.trim().isEmpty()`
*   `StringUtils.defaultIfBlank(str, defaultValue)` — replaces ternary expressions for blank string defaults

### `Objects` (`java.util.Objects`) — Complementary usage

*   `Objects.equals(a, b)` — null-safe equality comparison
*   `Objects.nonNull(obj)` / `Objects.isNull(obj)` — for Stream filter and method reference scenarios
*   `Objects.requireNonNull(obj, message)` — parameter precondition check (use when checking pure null without "empty" semantics)
*   `Objects.requireNonNullElse(obj, defaultValue)` — replaces `obj != null ? obj : defaultValue` (constant / already-built default)
*   `Objects.requireNonNullElseGet(obj, supplier)` — same, but the supplier is only evaluated when `obj` is null (use when the default must be constructed)

### When to use which

| Scenario | Recommended utility |
|---|---|
| Pure null check | `Objects` |
| null + empty (empty string, empty collection) | `ObjectUtils` |
| String null + blank | `StringUtils` |

## Error Handling & HTTP Status Codes (CRITICAL)

*   **Always use `ResponseEntity`**: All `@ExceptionHandler` methods MUST return `ResponseEntity<ApiResponse<Void>>` with an explicit HTTP status. Returning `ApiResponse` directly results in HTTP 200 regardless of the error.
*   **Status code mapping** (all handlers live in `stock-start/.../error/GlobalExceptionHandler.java`):
    *   `BusinessException` → status from **`ErrorCode.httpStatus()`** (e.g. `VALIDATION_FAILED` 400, `ASSET_NOT_FOUND` 404, `TRADE_CONFLICT` 409); `error.fields` carries `BusinessException.fields()`
    *   `FieldValidationException` (subclass) → 400 `VALIDATION_FAILED` with `fields`
    *   `MethodArgumentNotValidException` / `MissingRequestHeaderException` / `MethodArgumentTypeMismatchException` → **HTTP 400**
    *   `RateLimitExceededException` → 429 + `Retry-After`
    *   Anything implementing Spring's `ErrorResponse` → its own status, mapped to an `ErrorCode`
    *   Catch-all `Exception` → **HTTP 500** `INTERNAL_ERROR`, fixed message; details only in the log with `traceId`
    *   `AccessDeniedException` → re-throw to let Spring Security handle (returns HTTP 403)
*   **Forbidden pattern**: returning `ApiResponse.failure(...)` directly from an `@ExceptionHandler` — this is always HTTP 200.
*   **Correct pattern**: `return ResponseEntity.status(code.httpStatus()).body(ApiResponse.failure(error, ApiMetaFactory.current()))`
*   There is no `SystemException`; unexpected failures fall through to the catch-all.

## Facade Call Rules

Application layer (Controller / Application Service) Facade calls are limited to **≤ 3 per request**. When exceeding this limit:
- Core metrics (total market value, P&L, allocation ratios) → Redis pre-computation
- Batch queries → Facade provides batch methods (e.g., `MarketDataFacade.findLatestPrices(Collection<Long>)`, `findDailyQuotes(Map<Long, TradingDay>)`) to avoid N+1
- Complex aggregations → Spring Batch pre-computation written to Redis

## Ownership Check Pattern

**Design (security.md §4)**: `SecurityUtils.assertOwnerOrAdmin(currentUserId, resourceOwnerId)` in the service layer; failure throws `ResourceNotFoundException`; ADMIN bypasses.

**As implemented (2026-09-27)**: `SecurityUtils` does not exist. Ownership is enforced by **scoping every query to the caller**:
- The controller resolves the caller with `AuthenticatedUserResolver` (stock-infrastructure/web) and passes `userId` down
- Repository SQL filters `where user_id = :userId` (e.g. `JdbcTradingRepository`, `JdbcBacktestRepository`), so another user's resource is simply *not found* → 404 (no existence oracle)
- ADMIN therefore does **not** bypass ownership on user-owned resources today
- Every controller endpoint must declare `@PreAuthorize` or be allow-listed with a reason — enforced by the ArchUnit test `EndpointAuthorizationRulesTest`

**Open decision (need Yuan)**: adopt query scoping as the rule (and update security.md §4 / judgment.md), or implement `assertOwnerOrAdmin` with the ADMIN bypass. Until decided, new user-owned resources follow the implemented query-scoping pattern.

## Error Message Security Rules

| Exception Type | HTTP Status | Response Message Rules |
|---------------|-------------|----------------------|
| `ResourceNotFoundException` | 404 | Only include resource type name (e.g., "Portfolio") — **never** include IDs or paths |
| `BusinessException` | per `ErrorCode` | **Static** description — **never** echo user input (symbol, interval, keys), internal IDs, SQL fragments, or stack traces. Name the offending input via `fields` (`field → static reason`) |
| Catch-all | 500 | Fixed `INTERNAL_ERROR` message — details only go to logs with `traceId` |
| `AccessDeniedException` | 403 | Re-throw, let Spring Security handle |

## SQL Injection Prevention (Hard Requirement)

1. **Absolutely forbidden** to concatenate SQL strings (`"SELECT ... WHERE id = " + id`)
2. `@Query` must always use `:namedParam` (e.g., `@Query("SELECT * FROM users WHERE id = :id")`)
3. Dynamic queries may only use `JdbcClient` + `MapSqlParameterSource`
4. LIKE / ILIKE wildcards must be escaped and user input length-capped — see `AssetRepository.likePattern` (escapes `\\`, `%`, `_`; 64-char cap). There is no shared `LikeEscapeUtil`; extract one when a second caller appears
5. Code review checklist must include "SQL string concatenation check" item

## High-Frequency Computation Storage Strategy (CRITICAL)

As implemented (2026-09-27), derived portfolio data is **computed on read and cached in Redis**; there is no event-driven write path and no daily write-back batch:

1. **Read Redis first** — cache hit returns immediately (batch `MGET` for many assets)
2. **Miss → compute** from `holdings` + `MarketDataFacade` prices, then write the cache with a short TTL
3. **Invalidate after commit** — trade writes evict the user's portfolio keys in `afterCommit`, never before (otherwise a concurrent read re-caches pre-trade data)

| Data | Redis Key Pattern | TTL | Invalidation |
|------|------------------|-----|--------------|
| Holding valuation | `portfolio:valuation:{userId}:{assetId}` | 60 s | after trade commit |
| Portfolio summary | `portfolio:summary:{userId}` | 60 s | after trade commit |
| Latest price (market-data) | `market:latest:{assetId}` | 5 min | overwritten per tick |

Risk indicators and a `portfolio_valuations` table are not implemented. If price-driven revaluation is ever needed, revisit this section rather than adding a parallel mechanism.

**NOT applicable (write directly to DB):** `holdings` (trade-triggered, low frequency), `transactions` (append-only), `assets` (metadata), `users`

## Password Hashing

- Use **BCrypt** via `BCryptPasswordEncoder` (Spring Security default, strength = 10)
- Password validation: minimum 8 characters, at least 1 uppercase + 1 lowercase + 1 digit
- See [security.md §16](security.md) for full password security rules

## Documentation & Comments (CRITICAL)

*   **Language**: All JavaDoc and comments MUST be in **Traditional Chinese**.
*   **Mandatory JavaDoc**:
    *   All Classes: Description, Author, Version.
    *   All Public Methods: Functionality, Parameters (@param), Return values (@return).
    *   All Member Variables: Purpose and meaning.
*   **No Single-line Comments**: Avoid `//`. Use JavaDoc `/** ... */` block style for everything to ensure visibility and standardize documentation.
