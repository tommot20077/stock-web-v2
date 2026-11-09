# Testing Standards

## Coverage

*   Tests must cover the feature.
*   **Pristine Output**: Logs during tests should be clean unless testing error handling.
*   **Jacoco 棘輪**:`jacoco:check`(根 pom,綁 `verify`,規則為 BUNDLE 的 LINE / BRANCH `COVEREDRATIO`)。根 pom 的 `jacoco.minimum.line` / `jacoco.minimum.branch` 預設 0.00(不檢查),各模組 pom 的 `<properties>` 覆寫為自己的下限;低於下限 `./mvnw verify` 即失敗。
    *   **只升不降**:下限只可上調;覆蓋率變高時順手把該模組下限調到新實測值(無條件捨去到小數第二位)。
    *   **新增程式碼要附測試**:新功能不得把覆蓋率拉低到下限以下;下限不是讓你少寫測試的額度。
    *   **不可靠排除來過門檻**:`jacoco-maven-plugin` 不設任何 `<excludes>`,不得為了讓數字過關而排除類別;調降下限同樣不可行,要調降必須先問 Yuan。
    *   `stock-db-migration` 只有 SQL、沒有 Java 程式碼與測試,沒有 `jacoco.exec`,`check` 自動略過,故不設下限。
    *   下限來源:2026-11-09 在 develop(92b59e5)跑 `./mvnw verify` 的各模組 BUNDLE 實測值,無條件捨去到小數第二位(Yuan 2026-11-09 同意)。

    | 模組 | LINE 實測 | LINE 下限 | BRANCH 實測 | BRANCH 下限 |
    |------|-----------|-----------|-------------|-------------|
    | stock-common | 0.7970 | 0.79 | 0.8293 | 0.82 |
    | stock-infrastructure | 0.6544 | 0.65 | 0.6915 | 0.69 |
    | stock-module-user | 0.1729 | 0.17 | 0.1727 | 0.17 |
    | stock-module-asset | 0.5951 | 0.59 | 0.8125 | 0.81 |
    | stock-module-backtest | 0.6233 | 0.62 | 0.7750 | 0.77 |
    | stock-module-market-data | 0.9067 | 0.90 | 0.8021 | 0.80 |
    | stock-module-trading | 0.7691 | 0.76 | 0.9014 | 0.90 |
    | stock-start | 0.8813 | 0.88 | 0.6667 | 0.66 |

    上調時改各模組 pom 的 `jacoco.minimum.*` 屬性,並同步更新本表。
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
| Web layer | `*ControllerTest.java` / `*ControllerWebMvcTest.java` | `@WebMvcTest` + MockitoBean | Controller + Security |
| Integration | `*IT.java` | `@SpringBootTest` + Testcontainers | Full API flow |
| Backend E2E | `*E2E.java`(`stock-start/.../e2e/`) | `@SpringBootTest` + Testcontainers, `-Pe2e` profile | Multi-step user flows (`./mvnw -pl stock-start -am test -Pe2e`) |

`*IT.java` 一律由 **failsafe** 在 `verify` 階段執行,不得加進 surefire 的 includes:`./mvnw test` 只跑不需容器的單元 / Web 層測試(2026-09-27 起,IT 移出 surefire 後 `./mvnw test` 由約 5 分鐘降到約 2.5 分鐘)。需要容器的測試必須以 `IT` 結尾,否則會跑在 surefire、拖慢單元迴圈。

## Testcontainers Shared Container Pattern

> As implemented (2026-11-06): `stock-start/src/test/java/dowob/xyz/stockwebv2/start/support/ContainerIT.java` is the shared base — static singleton containers `timescale/timescaledb:2.27.2-pg18`, `redis:8.8.0-alpine` and `apache/kafka:4.2.0`(KRaft, `org.testcontainers.kafka.KafkaContainer`), started once per JVM with `Startables.deepStart`; **no** `withReuse(true)`. It also disables rate limiting for the shared suite (`AuthRateLimitAndLockoutIT` overrides it). Module ITs (asset / market-data) declare their own containers. Sharing containers across market-data ITs is a backlog todo (`2026-09-27-it-shared-containers`). The snippet below is the original design sketch.

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

**Phase 1 only started PG + Redis.** Kafka is now part of `ContainerIT`; Elasticsearch is not used.

## JWT Testing Rules

- **Forbidden to hardcode JWT token fixtures** (e.g., `String TOKEN = "eyJ..."`)
- CI environment JWT private key: **randomly generated** per CI run, self-contained
- Dev environment JWT private key: not fixed, randomly generated on each startup
- Use `@WithMockUser` or `RequestPostProcessor` to inject authentication context in tests

## DataProvider Test Patterns

> Design target — as of 2026-11-06 there is no Circuit Breaker, WireMock or `TestRealtimeDataProvider` in the repo; only the mock provider exists (`MockDataProvider`).

| Test Type | Tool | Purpose |
|-----------|------|---------|
| Unit test | Mockito mock Circuit Breaker state | CB open/closed/half-open state transitions |
| Integration test | WireMock + Scenario | Simulate external API responses (200/5xx/timeout) |
| Realtime | TestRealtimeDataProvider | Simulate real-time data push |

## ArchUnit Testing Requirements

Establish foundational ArchUnit rules (3-5 rules) from Phase 1 Sprint 1.

> As implemented (2026-11-06): `stock-start/.../ArchitectureRulesTest.java` enforces rules 1, 2 (facade interfaces live in `stock-infrastructure`), 3 and 5; `EndpointAuthorizationRulesTest.java` additionally requires every endpoint method to carry `@PreAuthorize` or be on a reasoned allow-list. Rule 4 is enforced by per-endpoint "non-owner returns 404" tests, not by ArchUnit.


1. **Module isolation**: No direct dependencies between L2 modules
2. **Facade boundary**: L2 modules interact only through `stock-infrastructure` Facade interfaces
3. **DDD layering**: Controllers must not call Repositories directly
4. **Ownership**: user-owned data is only reachable through queries scoped by `user_id` (security.md §4); every such endpoint has a "non-owner returns 404" test
5. **Controller → Facade**: Controllers must not call Facades directly (go through Application Service)
