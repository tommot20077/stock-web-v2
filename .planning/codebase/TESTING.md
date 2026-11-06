---
last_mapped_commit: 5ca148d0e17ea7e2cafc957738dbfc70fe6dfa74
sibling_frontend_last_mapped_commit: 5e215a00510e25fddce7d02b6aa3bf7d0942748d
---
# Testing Patterns

**Analysis Date:** 2026-11-06(初次分析 2026-05-30)

**Refresh:** 2026-11-06 targeted refresh (quick-261106-fbs Task 3) — 以程式核對並修正與現況衝突處,**不是** `/gsd-map-codebase` 全量重產(本次執行環境無法派 mapper agents;建議日後全量重產)。前端路徑以 Yuan 本機佈局 `../../vue/stock-v2/vue-app` 表示(cloud 環境為 `../stock-web-v2-front-end/vue-app`)。

## TDD Expectations

**Mandatory workflow:**
- Red: write a failing test before production changes and run it to confirm failure.
- Green: implement the smallest production change that passes the test.
- Refactor: improve structure while keeping tests green.
- This rule comes from `CLAUDE.md` and applies to backend and sibling frontend integration work.

**Coverage expectation:**
- Unit, integration, and E2E coverage are expected unless Yuan explicitly waives a layer.
- Test output should stay clean unless the test is explicitly exercising error logging.
- Security-sensitive changes need positive and negative tests, especially ownership, authentication, authorization, and non-leaking error responses.

## Test Framework

**Backend runner:**
- JUnit 5 through Spring Boot test dependencies.
- Maven wrapper: `mvnw` and `mvnw.cmd`.
- Config: root `pom.xml`, module POMs such as `stock-start/pom.xml` and `stock-module-market-data/pom.xml`.

**Backend assertion library:**
- AssertJ for unit/integration assertions, e.g. `assertThat` and `assertThatThrownBy` in `stock-module-user/src/test/java/dowob/xyz/stockwebv2/user/service/AuthServiceTest.java`.
- Spring MockMvc result matchers for web/API assertions, e.g. `stock-start/src/test/java/dowob/xyz/stockwebv2/start/AuthFlowIT.java`.
- Hamcrest appears in MockMvc JSON path assertions, e.g. `equalTo` and `notNullValue` in `stock-start/src/test/java/dowob/xyz/stockwebv2/start/AuthFlowIT.java`.

**Backend test support:**
- Mockito and Spring `@MockitoBean` / `@MockitoSpyBean` for mocks and spies, e.g. `stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/api/MarketControllerTest.java`.
- Testcontainers for PostgreSQL/TimescaleDB, Redis, and Kafka, e.g. `stock-start/src/test/java/dowob/xyz/stockwebv2/start/support/ContainerIT.java`.
- Awaitility for async/background assertions, e.g. `stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/batch/BackfillJobIT.java`.

**Frontend runner:**
- Vitest 4 with jsdom through Vite config.
- Config: `../../vue/stock-v2/vue-app/vite.config.ts`.
- Package scripts: `../../vue/stock-v2/vue-app/package.json`.

**Frontend assertion library:**
- Vitest `expect`, `vi`, `describe`, `it`, `afterEach`, e.g. `../../vue/stock-v2/vue-app/src/services/apiClient.test.ts`.
- DOM mounting uses Vue `createApp`, Pinia `createPinia`, and custom helpers in `../../vue/stock-v2/vue-app/src/testUtils.ts`.

**Run Commands:**
```bash
./mvnw test --fail-at-end --no-transfer-progress
./mvnw -pl stock-start -am verify -Dspring-boot.repackage.skip=true --fail-at-end --no-transfer-progress
./mvnw -pl stock-start -am test -Pe2e --no-transfer-progress
cd ../../vue/stock-v2/vue-app && npm test
cd ../../vue/stock-v2/vue-app && npm run test:watch
cd ../../vue/stock-v2/vue-app && npm run build
cd ../../vue/stock-v2/vue-app && npm run lint
cd ../../vue/stock-v2/vue-app && npx playwright test e2e/tests/layout.spec.ts
./mvnw spotless:check
```

## CI Pipeline

**Backend CI:**
- GitHub Actions workflow: `.github/workflows/ci.yml`.
- Triggers: push to all branches, pull requests to `main` and `develop`, and manual `workflow_dispatch`.
- Java setup: Temurin Java 21 with Maven cache.
- Spotless job runs `./mvnw -B spotless:check`.
- Unit job runs `./mvnw -B test --fail-at-end --no-transfer-progress`.
- Integration job runs `./mvnw -B -pl stock-start -am verify -Dspring-boot.repackage.skip=true --fail-at-end --no-transfer-progress` after unit tests.
- E2E job runs `./mvnw -B -pl stock-start -am test -Pe2e --no-transfer-progress` after integration tests.
- Browser E2E job checks out the frontend repo (matching branch, else `develop`), sets up Node 20 + Playwright Chromium, and runs `e2e/run-e2e.sh` (Playwright journeys in the frontend `vue-app/e2e/tests/` against the real backend).
- Test reports are published with `dorny/test-reporter@v1` and uploaded as artifacts.

**Frontend CI:**
- The frontend repo has its own workflow at the repo root `.github/workflows/` (Node 20, `vue-app/` working directory): `npm ci → lint → test → build` on develop push / PR.
- Backend CI runs the frontend only for browser E2E (above); Vitest / build / lint run in the frontend CI.

## Test File Organization

**Backend location:**
- Tests are co-located by Maven module under `*/src/test/java`.
- Current backend scan (2026-11-06) found 177 Java test files and 237 Java main files; `./mvnw test -Dtest='!*IT,!*E2E*'` runs 949 unit / web tests.
- Unit tests live beside module code, e.g. `stock-module-trading/src/test/java/dowob/xyz/stockwebv2/trading/domain/HoldingCalculatorTest.java`.
- Web layer tests live in module API packages, e.g. `stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/api/MarketControllerTest.java`.
- Full app integration tests live in `stock-start/src/test/java/dowob/xyz/stockwebv2/start/`.
- E2E tests live in `stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/`.

**Backend naming:**
- Unit tests: `*Test.java`, e.g. `stock-module-user/src/test/java/dowob/xyz/stockwebv2/user/service/AuthServiceTest.java`.
- Web layer tests: `*ControllerTest.java` or `*ControllerWebMvcTest.java`, e.g. `stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/api/BackfillControllerTest.java`.
- Integration tests: `*IT.java`, e.g. `stock-start/src/test/java/dowob/xyz/stockwebv2/start/AuthFlowIT.java`.
- E2E tests: `*E2E.java` (included by the `-Pe2e` profile) or `*E2ETest.java`, e.g. `stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/WatchlistE2E.java`, `MarketRestApiE2ETest.java`.

**Frontend location:**
- Tests are co-located under `../../vue/stock-v2/vue-app/src`.
- Current frontend scan (2026-11-06) found 59 `.test.ts` files (1138 tests) and 80 TypeScript/Vue source files under `src`, plus Playwright specs under `../../vue/stock-v2/vue-app/e2e/tests/` (`layout.spec.ts` with stubs; `auth`, `trade`, `cash-journey`, `holding-event-journey`, `watchlist-*`, `backtest`, `smoke` journeys for full-stack E2E).
- Service tests live next to adapters, e.g. `../../vue/stock-v2/vue-app/src/services/backtestApi.test.ts`.
- Component tests live next to components, e.g. `../../vue/stock-v2/vue-app/src/components/settings/SettingsAiAccess.test.ts`.
- Cross-page behavior tests live at `src` root, e.g. `../../vue/stock-v2/vue-app/src/api-adapter-wiring.test.ts`.

**Structure:**
```text
stock-module-*/src/test/java/dowob/xyz/stockwebv2/<module>/<layer>/*Test.java
stock-module-*/src/test/java/dowob/xyz/stockwebv2/<module>/<layer>/*IT.java
stock-start/src/test/java/dowob/xyz/stockwebv2/start/*IT.java
stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/*E2E.java | *E2ETest.java
../../vue/stock-v2/vue-app/src/**/*.test.ts
```

## Test Structure

**Backend suite organization:**
```java
class AuthServiceTest {
    @Test
    void registerCreatesActiveUserWithHashedPassword() {
        InMemoryUserRepository repository = new InMemoryUserRepository();
        AuthService service = new AuthService(repository, new BCryptPasswordEncoder(10));

        User user = service.register(new RegisterRequest("yuan@example.com", "yuan", "Password1"));

        assertThat(user.id()).isEqualTo(1L);
    }
}
```

**Backend web slice pattern:**
```java
@WebMvcTest(MarketController.class)
@Import({MarketControllerTest.OpenSecurityConfig.class, MarketControllerTest.TestExceptionHandler.class})
class MarketControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean MarketLatestService latestService;

    @Test
    @DisplayName("GET /{symbol}/latest: 有資料 → 200 + DTO（BigDecimal 序列化為字串）")
    void latest_returns200WithDto() throws Exception {
        when(latestService.findLatest("AAPL")).thenReturn(Optional.of(aaplDto()));

        mvc.perform(get("/api/v1/market/AAPL/latest").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }
}
```

**Backend integration pattern:**
```java
@AutoConfigureMockMvc
class AuthFlowIT extends ContainerIT {
    @Autowired MockMvc mockMvc;

    @Test
    void registerLoginMeLogoutFlowWorks() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"yuan@example.com\",\"username\":\"yuan\",\"password\":\"Password1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", equalTo(true)));
    }
}
```

**Frontend suite organization:**
```typescript
describe('apiClient', () => {
  it('throws typed errors for API error envelopes', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
      error: { code: 'OPS_PERMISSION_DENIED', message: 'Forbidden' },
      requestId: 'req_2',
    }), { status: 403, headers: { 'Content-Type': 'application/json' } })));

    await expect(apiRequest('/api/v1/ops/jobs')).rejects.toMatchObject({
      name: 'ApiClientError',
      status: 403,
      code: 'OPS_PERMISSION_DENIED',
    });
  });
});
```

**Patterns:**
- Arrange/Act/Assert is implicit: create collaborators, invoke behavior, assert state/output.
- Prefer behavior names in English camelCase test methods for Java.
- Use `@DisplayName("Traditional Chinese description")` for Java tests when adding or touching tests.
- Use frontend `describe` names by unit under test and `it` names by observable behavior.

## Mocking

**Backend framework:** Mockito, Spring Boot test Mockito integration, custom in-memory fakes.

**Backend patterns:**
```java
@MockitoBean
MarketLatestService latestService;

when(latestService.findLatest("AAPL")).thenReturn(Optional.of(aaplDto()));
```

```java
static class InMemoryUserRepository implements UserRepository {
    private final Map<Long, User> byId = new ConcurrentHashMap<>();

    @Override
    public Optional<User> findById(Long id) {
        return Optional.ofNullable(byId.get(id));
    }
}
```

**Frontend framework:** Vitest `vi`.

**Frontend patterns:**
```typescript
vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
  data: { ok: true },
  requestId: 'req_1',
}), { status: 200, headers: { 'Content-Type': 'application/json' } })));
```

**What to Mock:**
- Backend unit tests: repositories, external clients, clocks/randomness when deterministic behavior matters.
- Backend web slice tests: services behind controllers and security config when the slice is not testing security.
- Frontend service tests: `fetch` with representative backend envelopes.
- Frontend component tests: callbacks/events and DOM, using helpers from `../../vue/stock-v2/vue-app/src/testUtils.ts`.

**What NOT to Mock:**
- Do not hardcode JWT token fixtures; generate tokens or use Spring Security test support.
- Do not mock database/Redis/Kafka in integration tests that are validating persistence, cache, or messaging semantics; use Testcontainers.
- Do not mock the API envelope contract in only one layer when changing backend/frontend integration; add backend MockMvc/API tests and frontend adapter tests.

## Fixtures and Factories

**Backend test data:**
```java
private AuthTokens register(String email, String username, String password) throws Exception {
    String body = mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"%s","username":"%s","password":"%s"}
                """.formatted(email, username, password)))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
    return readTokens(body);
}
```

**Frontend test data:**
```typescript
const request: BacktestRunRequest = {
  strategyId: 'ma_cross',
  strategyCode: null,
  symbol: 'AAPL',
  period: '3Y',
  initialCapital: 100000,
  currency: 'USD',
  benchmark: 'buy_hold',
  dataMode: 'cached',
};
```

**Location:**
- Backend helper classes: `stock-start/src/test/java/dowob/xyz/stockwebv2/start/support/` and `stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/support/`.
- Frontend helpers: `../../vue/stock-v2/vue-app/src/testUtils.ts`.
- Frontend mock state: `../../vue/stock-v2/vue-app/src/stores/mockPortfolio.ts`, `../../vue/stock-v2/vue-app/src/stores/mockNotifications.ts`, `../../vue/stock-v2/vue-app/src/stores/mockPreview.ts`.

## Coverage

**Requirements:** JaCoCo 0.8.13 is configured in the root `pom.xml` (`prepare-agent` + `report`) without a numeric threshold. Project standards require feature coverage and all three test layers unless Yuan waives a layer.

**View Coverage:**
```bash
./mvnw verify   # JaCoCo report (bound to verify) under each module's target/site/jacoco/; no frontend coverage script
```

**Practical expectation:**
- Add focused unit tests for business rules.
- Add web/API tests for status codes, envelopes, validation, and security behavior.
- Add integration/E2E tests for persistence, Redis, Kafka, JWT, and backend/frontend contract changes.

## Test Types

**Unit Tests:**
- Scope: service/domain logic, record invariants, mapping, pure adapters.
- Backend examples: `stock-module-user/src/test/java/dowob/xyz/stockwebv2/user/service/AuthServiceTest.java`, `stock-module-trading/src/test/java/dowob/xyz/stockwebv2/trading/domain/HoldingCalculatorTest.java`.
- Frontend examples: `../../vue/stock-v2/vue-app/src/services/apiClient.test.ts`, `../../vue/stock-v2/vue-app/src/settingsAiAccessView.test.ts`.

**Web Layer Tests:**
- Scope: controller routing, validation, response envelope, HTTP status, and security behavior at the slice level.
- Use `@WebMvcTest`, `MockMvc`, `@MockitoBean`, and local test exception handlers when `stock-start` global advice is outside the slice.
- Examples: `stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/api/MarketControllerTest.java`, `stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/api/BackfillControllerTest.java`.

**Integration Tests:**
- Scope: full Spring context, database migrations, repositories, Redis, Kafka, auth flow, CORS, and error handling.
- Shared full-app container base: `stock-start/src/test/java/dowob/xyz/stockwebv2/start/support/ContainerIT.java`.
- Examples: `stock-start/src/test/java/dowob/xyz/stockwebv2/start/AuthFlowIT.java`, `stock-start/src/test/java/dowob/xyz/stockwebv2/start/ErrorHandlingIT.java`.

**E2E Tests:**
- Backend E2E uses MockMvc against a full app context with `@ActiveProfiles("e2e")`.
- Base class: `stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/support/AbstractStockE2ETest.java`.
- Examples: `stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/MarketRestApiE2ETest.java`, `stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/WsAuthFlowE2ETest.java`.
- Frontend E2E: Playwright (`../../vue/stock-v2/vue-app/e2e/`); browser full-stack runs via backend CI `e2e/run-e2e.sh`.

**Contract / Adapter Tests:**
- Frontend adapter tests assert exact backend paths, HTTP methods, headers, JSON payloads, paginated envelopes, and typed error conversion.
- Examples: `../../vue/stock-v2/vue-app/src/services/backtestApi.test.ts`, `../../vue/stock-v2/vue-app/src/services/opsApi.test.ts`, `../../vue/stock-v2/vue-app/src/services/aiAccessApi.test.ts`.
- Backend/frontend integration phases should pair these with backend MockMvc tests for the same endpoint.

## Common Patterns

**Async Testing:**
```java
Awaitility.await()
    .atMost(Duration.ofSeconds(10))
    .untilAsserted(() -> assertThat(repository.findRange(1L, from, to)).hasSize(1440));
```

```typescript
vi.useFakeTimers();
await vi.advanceTimersByTimeAsync(650);
```

**Error Testing:**
```java
assertThatThrownBy(() -> service.verifyCredentials("yuan@example.com", "bad"))
    .isInstanceOf(BusinessException.class)
    .extracting("errorCode")
    .isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS);
```

```typescript
await expect(apiRequest('/api/v1/example')).rejects.toMatchObject({
  name: 'ApiClientError',
  status: 200,
  code: 'INVALID_JSON_RESPONSE',
});
```

**MockMvc API Envelope Testing:**
```java
mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + tokens.accessToken()))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.data.email", equalTo("yuan@example.com")));
```

**Frontend DOM Cleanup:**
```typescript
afterEach(() => {
  cleanupMounted();
});
```

## Testcontainers Pattern

**Shared app container base:**
- `stock-start/src/test/java/dowob/xyz/stockwebv2/start/support/ContainerIT.java` starts PostgreSQL/TimescaleDB, Redis, and Kafka statically and registers dynamic Spring properties.
- Use this base for full-app integration tests in `stock-start`.

**Module-local containers:**
- Some module tests use `@Testcontainers` and `@Container` directly, e.g. `stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/ws/WsTicketServiceIT.java`.
- Use module-local containers when testing one infrastructure dependency without booting the full application.

**Documented target pattern:**
- `ai-docs/testing-standards.md` sketches singleton containers with `withReuse(true)`. Current `ContainerIT` uses static singleton containers (`timescale/timescaledb:2.17.2-pg16`, `redis:7.4-alpine`, `confluentinc/cp-kafka:7.6.0`) without `withReuse(true)`; cross-IT sharing for market-data is backlog todo `2026-09-27-it-shared-containers`.

## Java Test Naming Rules

**Required:**
- Test method names must be English camelCase.
- Chinese characters are forbidden in Java test method names.
- Use `@DisplayName("Traditional Chinese description")` for human-readable Traditional Chinese descriptions.

**Current state:**
- Many market-data and E2E tests use `@DisplayName`, e.g. `stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/api/MarketControllerTest.java`.
- Some older/simple unit tests do not use `@DisplayName`, e.g. `stock-module-user/src/test/java/dowob/xyz/stockwebv2/user/service/AuthServiceTest.java`. Add `@DisplayName` when touching or adding tests.

## Security Testing Rules

**Authentication and JWT:**
- Do not hardcode JWT strings.
- Use registration/login helpers or Spring Security test support.
- Test malformed and missing bearer token behavior, as in `stock-start/src/test/java/dowob/xyz/stockwebv2/start/AuthFlowIT.java`.

**Authorization:**
- Test URL-layer and method-layer protection when changing security config.
- Every ownership scenario requires a non-owner returns 404 test per `ai-docs/security.md`.
- For WebSocket auth, test ticket issuance, single-use consumption, expiration, revocation, and unauthorized handshakes, as represented by `stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/WsAuthFlowE2ETest.java`.

## ArchUnit Expectations

**Implemented (ArchUnit 1.3.0, `stock-start/src/test/java/dowob/xyz/stockwebv2/start/`):**
- `ArchitectureRulesTest`: L2 modules must not depend on each other; facade interfaces live in `stock-infrastructure`; controllers must not depend on repositories; controllers must not depend on facades.
- `EndpointAuthorizationRulesTest`: every endpoint method declares `@PreAuthorize` or is on a reasoned allow-list (stale entries fail).
- Ownership is not an ArchUnit rule: it is query scoping (`where user_id = :userId`) verified by per-endpoint "non-owner returns 404" tests; `SecurityUtils.assertOwnerOrAdmin` was retired 2026-09-28.

**When adding ArchUnit:**
- Place architecture tests under `stock-start/src/test/java` or a dedicated module-level test package that can see all relevant modules.
- Keep rules small and actionable; start with 3-5 foundational rules from `ai-docs/testing-standards.md`.

## Backend/Frontend Integration Testing Guidance

**For each backend endpoint used by the Vue app:**
- Backend: add MockMvc or integration tests for route, auth, validation, success envelope, failure envelope, and status mapping.
- Frontend: add service adapter tests that assert exact URL, query string, method, headers, request body, success unwrap, paginated unwrap, and typed error conversion.
- Cross-check shared types in `../../vue/stock-v2/vue-app/src/services/apiTypes.ts` against backend DTOs such as `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/api/BacktestRunDto.java`.

**Recommended verification set for integration phases:**
```bash
./mvnw -pl stock-start -am test --fail-at-end --no-transfer-progress
./mvnw -pl stock-start -am verify -Dspring-boot.repackage.skip=true --fail-at-end --no-transfer-progress
cd ../../vue/stock-v2/vue-app && npm test
cd ../../vue/stock-v2/vue-app && npm run build
```

---

*Testing analysis: 2026-05-30;targeted refresh 2026-11-06*
