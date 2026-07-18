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
*   `ObjectUtils.defaultIfNull(obj, defaultValue)` — replaces `obj != null ? obj : defaultValue`
*   `ObjectUtils.requireNonEmpty(obj, message)` — precondition check

### `StringUtils` (`org.apache.commons.lang3.StringUtils`) — String checks

*   `StringUtils.isBlank(str)` — replaces `str == null || str.trim().isEmpty()`
*   `StringUtils.isNotBlank(str)` — replaces `str != null && !str.trim().isEmpty()`
*   `StringUtils.defaultIfBlank(str, defaultValue)` — replaces ternary expressions for blank string defaults

### `Objects` (`java.util.Objects`) — Complementary usage

*   `Objects.equals(a, b)` — null-safe equality comparison
*   `Objects.nonNull(obj)` / `Objects.isNull(obj)` — for Stream filter and method reference scenarios
*   `Objects.requireNonNull(obj, message)` — parameter precondition check (use when checking pure null without "empty" semantics)

### When to use which

| Scenario | Recommended utility |
|---|---|
| Pure null check | `Objects` |
| null + empty (empty string, empty collection) | `ObjectUtils` |
| String null + blank | `StringUtils` |

## Error Handling & HTTP Status Codes (CRITICAL)

*   **Always use `ResponseEntity`**: All `@ExceptionHandler` methods MUST return `ResponseEntity<ApiResponse<Void>>` with an explicit HTTP status. Returning `ApiResponse` directly results in HTTP 200 regardless of the error.
*   **Status code mapping**:
    *   `BusinessException` → **HTTP 400** (`HttpStatus.BAD_REQUEST`)
    *   `MethodArgumentNotValidException` / `BindException` → **HTTP 400** (`HttpStatus.BAD_REQUEST`)
    *   `SystemException` → **HTTP 500** (`HttpStatus.INTERNAL_SERVER_ERROR`)
    *   Catch-all `Exception` → **HTTP 500** (`HttpStatus.INTERNAL_SERVER_ERROR`)
    *   `ResponseStatusException` → preserve original status code via `e.getStatusCode()`
    *   `AccessDeniedException` → re-throw to let Spring Security handle (returns HTTP 403)
*   **Forbidden pattern**: `return ApiResponse.failed(...)` in an `@ExceptionHandler` — this is always HTTP 200.
*   **Correct pattern**: `return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.failed(...))`

## Facade Call Rules

Application layer (Controller / Application Service) Facade calls are limited to **≤ 3 per request**. When exceeding this limit:
- Core metrics (total market value, P&L, allocation ratios) → Redis pre-computation
- Batch queries → Facade provides batch methods (e.g., `findByIds(Set<Long>)`) to avoid N+1
- Complex aggregations → Spring Batch pre-computation written to Redis

## Ownership Check Pattern

Use `SecurityUtils.assertOwnerOrAdmin(currentUserId, resourceOwnerId)` utility method:
- Failure throws `ResourceNotFoundException` (not `AccessDeniedException`)
- ADMIN automatically bypasses
- ArchUnit rules automatically scan to ensure no omissions
- See [security.md §4](security.md)

## Error Message Security Rules

| Exception Type | HTTP Status | Response Message Rules |
|---------------|-------------|----------------------|
| `ResourceNotFoundException` | 404 | Only include resource type name (e.g., "Portfolio") — **never** include IDs or paths |
| `BusinessException` | 400 | Business error description — **never** include internal IDs, SQL fragments, or stack traces |
| `SystemException` | 500 | Fixed message "System error, please try again later" — details only go to logs |
| `AccessDeniedException` | 403 | Re-throw, let Spring Security handle |

## SQL Injection Prevention (Hard Requirement)

1. **Absolutely forbidden** to concatenate SQL strings (`"SELECT ... WHERE id = " + id`)
2. `@Query` must always use `:namedParam` (e.g., `@Query("SELECT * FROM users WHERE id = :id")`)
3. Dynamic queries may only use `JdbcClient` + `MapSqlParameterSource`
4. LIKE wildcards must be escaped (`LikeEscapeUtil.escape(keyword)`)
5. Code review checklist must include "SQL string concatenation check" item

## High-Frequency Computation Storage Strategy (CRITICAL)

All computed data driven by high-frequency events (e.g., price updates triggering valuation changes) MUST follow this pattern:

1. **Write to Redis first** — immediate updates go to Redis cache
2. **Daily batch writes back to DB** — scheduled job persists Redis data to database tables
3. **API reads Redis** — never query DB for high-frequency computed data

| Data | Redis Key Pattern | DB Table | Update Trigger |
|------|------------------|----------|---------------|
| Portfolio market value / ROI | `cache:portfolio:{userId}:{assetId}` | `portfolio_valuations` | `market.price.*` event |
| Dashboard summary | `cache:dashboard:{userId}` | (computed, no dedicated table) | Derived from portfolio |
| Risk indicators | `cache:risk:{userId}` | `portfolio_valuations` (batch write) | Daily batch |

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
