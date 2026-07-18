# Testing Standards

## Coverage

*   Tests must cover the feature.
*   **Pristine Output**: Logs during tests should be clean unless testing error handling.
*   **No Exceptions**: "Not applicable" is not an excuse. Unit, Integration, E2E tests are required unless explicitly waived by Yuan.

## Test Method Naming

Chinese characters are **forbidden** in test method names.
Method names must use English `camelCase`. Use `@DisplayName("Traditional Chinese description")` to
provide a human-readable Traditional Chinese description.

**Correct**:
```java
@Test
@DisplayName("Traditional Chinese description here")
void whenAssetNotFound_returnsEmpty() { ... }
```

**Forbidden**:
```java
@Test
void methodNameInChinese() { ... }
```

## Three-Layer Test Structure

| Layer | Naming | Tools | Scope |
|-------|--------|-------|-------|
| Unit | `*Test.java` | Mockito + JUnit 5 | Service business logic |
| Web layer | `*ControllerTest.java` | `@WebMvcTest` + MockitoBean | Controller + Security |
| Integration | `*IT.java` | `@SpringBootTest` + Testcontainers | Full API flow |

## Testcontainers Shared Container Pattern

Use **singleton pattern** + `withReuse(true)` to share containers across test classes:

```java
public abstract class BaseIntegrationTest {
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("timescale/timescaledb:latest-pg18")
        .withReuse(true);
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379)
        .withReuse(true);

    static { PG.start(); REDIS.start(); }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
```

**Phase 1 only starts PG + Redis.** Kafka/ES containers are deferred to their respective phases.

## JWT Testing Rules

- **Forbidden to hardcode JWT token fixtures** (e.g., `String TOKEN = "eyJ..."`)
- CI environment JWT private key: **randomly generated** per CI run, self-contained
- Dev environment JWT private key: not fixed, randomly generated on each startup
- Use `@WithMockUser` or `RequestPostProcessor` to inject authentication context in tests

## DataProvider Test Patterns

| Test Type | Tool | Purpose |
|-----------|------|---------|
| Unit test | Mockito mock Circuit Breaker state | CB open/closed/half-open state transitions |
| Integration test | WireMock + Scenario | Simulate external API responses (200/5xx/timeout) |
| Realtime | TestRealtimeDataProvider | Simulate real-time data push |

## ArchUnit Testing Requirements

Establish foundational ArchUnit rules (3-5 rules) from Phase 1 Sprint 1:

1. **Module isolation**: No direct dependencies between L2 modules
2. **Facade boundary**: L2 modules interact only through `stock-infrastructure` Facade interfaces
3. **DDD layering**: Controllers must not call Repositories directly
4. **Ownership check**: Service layer methods must call `SecurityUtils.assertOwnerOrAdmin`
5. **Controller → Facade**: Controllers must not call Facades directly (go through Application Service)
