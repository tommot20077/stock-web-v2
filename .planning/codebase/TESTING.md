---
last_mapped_commit: adae04117fcfa99db40b8ca304fb5cb9c5e36ec9
last_mapped_at: 2026-11-07
---
# Testing Patterns

**Analysis Date:** 2026-11-07

涵蓋後端(本 repo 根)與前端 repo 根(本機 `../../vue/stock-v2`、cloud `../stock-web-v2-front-end`,路徑對照見 `ai-docs/judgment.md` §8;前端路徑相對於前端 repo 根)。權威規範:`ai-docs/testing-standards.md`、前端 `AGENTS.md`「驗證命令」與 `docs/LEARNINGS.md`。

規則:Unit、Integration、E2E 都是必要的,除非 Yuan 明確豁免;「不適用」不是理由。

## Test Framework

**後端 Runner:**
- JUnit 5(Spring Boot 4.0.4 parent 管理版本)+ Mockito + AssertJ;Spring Test(`@WebMvcTest`、`@SpringBootTest`、`MockMvc`、`@MockitoBean`)。
- Testcontainers `1.21.4`(BOM)、ArchUnit `1.3.0`、Awaitility(`stock-start`)。
- JaCoCo `0.8.13`:`prepare-agent` + `verify` phase 產 report(無門檻)。
- Config:根 `pom.xml`、`stock-start/pom.xml`(surefire exclude `**/*E2E.java`、failsafe、`e2e` profile)。

**前端 Runner:**
- Vitest `^4.1.6`,`environment: 'jsdom'`、`globals: true`、`setupFiles: ['./src/testSetup.ts']`、`pool: 'threads'`、`fileParallelism: false`、排除 `e2e/**`;Config 在 `vue-app/vite.config.ts` 的 `test` 區塊。
- Playwright `^1.61.1`,Config `vue-app/playwright.config.ts`(chromium、`fullyParallel`、`retries: 1`、`workers: 4`、trace/video `retain-on-failure`;webServer 以 `VITE_DATA_MODE=api` 重新 build 後 `vite preview` 在 4173)。

**Assertion Library:** 後端 AssertJ `assertThat` / `assertThatThrownBy` + MockMvc `jsonPath` / Hamcrest;前端 Vitest `expect`、Playwright `expect`。

**Run Commands:**

```bash

# 後端(本 repo 根)

./mvnw spotless:check                                   # 格式(CI Lint job)
./mvnw test                                             # Unit + WebMvc(surefire,*Test.java)
./mvnw -pl stock-start -am verify -Dspring-boot.repackage.skip=true   # *IT(failsafe + Testcontainers,CI Integration job)
./mvnw -pl stock-start -am test -Pe2e                   # 後端 *E2E.java(CI E2E job)

# JaCoCo report: <module>/target/site/jacoco/index.html(verify 後)

# 前端(前端 repo 根/vue-app)

npm run lint && npm test && npm run build               # 標準閘門(=前端 CI;build 含 vue-tsc)
VITE_DATA_MODE=api npm test && npm run build            # 動到 API mode 時加跑
npm run test:watch                                      # Watch mode
npx playwright test e2e/tests/layout.spec.ts            # 動到版面 / CSS(page.route stub,不需後端)
npm run test:e2e                                        # Playwright @smoke(需真後端,e2e/run-e2e.sh)
```

**CI:**
- 後端 `.github/workflows/ci.yml`:`lint` → `unit`(`./mvnw -B test`)、`integration`(verify)、`e2e`(`-Pe2e`)、`Browser E2E`(checkout 前端同名分支否則 `develop`,Node 20 跑 Playwright)。報告經 `dorny/test-reporter` 發布。
- 前端 `.github/workflows/ci.yml`(Node 20):`npm ci → lint → test → build`。

## Test File Organization

**後端 Location:** 鏡像 main 套件,放在各模組 `src/test/java/...`;跨模組 / 需完整 context 的 IT 與 E2E 集中在 `stock-start/src/test/java/dowob/xyz/stockwebv2/start/`。

**後端 Naming(後綴決定由誰執行):**

| 層 | 命名 | 工具 | 執行者 |
|---|---|---|---|
| Unit | `*Test.java`(`TradingServiceTest`、`HoldingCalculatorTest`) | JUnit 5 + Mockito | surefire(`./mvnw test`) |
| Web 層 | `*ControllerWebMvcTest.java` / `*ControllerTest.java` / `*RequestValidationTest.java` | `@WebMvcTest` + `@MockitoBean` | surefire |
| SQL / mapper | `Jdbc*Repository*Test.java`、`TradingSqlBoundaryTest.java` | 純單元 | surefire |
| Integration | `*IT.java`(約 51 個,`TradingApiIT`、`ErrorHandlingIT`) | `@SpringBootTest` + Testcontainers | **failsafe**(`verify`) |
| 後端 E2E | `*E2E.java`(`stock-start/.../e2e/`,14 個) | 繼承 `AbstractStockE2ETest` | surefire `-Pe2e` |
| 架構 | `ArchitectureRulesTest`、`EndpointAuthorizationRulesTest` | ArchUnit | surefire |

需要容器的測試**必須**以 `IT` 結尾(或 `E2E`),不得加進 surefire includes。注意:`stock-start/.../e2e/` 下 `*E2ETest.java`(`Ws*E2ETest`、`MarketRestApiE2ETest`、`BackfillAdminFlowE2ETest`)符合 surefire 預設 `*Test` 且未被 exclude,會在 `./mvnw test` 中啟動容器;新增後端 E2E 一律命名 `*E2E.java`。

**後端 Structure:**

```
stock-module-trading/src/test/java/dowob/xyz/stockwebv2/trading/
├── TradingTestApplication.java     # 模組切片測試用 @SpringBootApplication
├── api/        # *ControllerWebMvcTest、*RequestValidationTest
├── domain/     # *CalculatorTest、*PayloadMatcherTest、*SortKeyTest
├── repository/ # Jdbc*Test(SQL / mapper)
└── service/    # *ServiceTest(Mockito)
stock-start/src/test/java/dowob/xyz/stockwebv2/start/
├── *IT.java、ArchitectureRulesTest.java
├── support/ContainerIT.java        # 共用容器基底
└── e2e/ + e2e/support/             # AbstractStockE2ETest、AbstractPortfolioE2ETest、AbstractWsE2ETest、AuthE2EHelper、DatabaseCleaner、StockE2EAssertions
```

**前端 Location / Naming:**
- Vitest 與被測檔**同目錄**,`<name>.test.ts`(`vue-app/src/services/tradingApi.test.ts`、`components/OrderTicket.test.ts`、`pages/Trades.test.ts`);跨領域守護測試放 `vue-app/src/` 根(`mockStoreBoundary.test.ts`、`wsBoundary.test.ts`、`contrastTokens.test.ts`)。共 64 個測試檔。
- 測試替身放 `vue-app/src/testing/`(`fakeWebSocket.ts`、`fakeQuoteStream.ts`、`watchlistFetchDouble.ts`、`wcag.ts`);掛載工具 `vue-app/src/testUtils.ts`。
- Playwright:`vue-app/e2e/tests/*.spec.ts`(kebab-case,`trade.spec.ts`、`cash-journey.spec.ts`),支援檔 `vue-app/e2e/support/`(`fixtures.ts`、`journeyApi.ts`、`portfolioStubs.ts`、`selectors.md`)。
- 歷史檔名 `task4.test.ts`…`task8.test.ts` 不要再仿效;新測試以被測模組命名。

## Test Structure

**後端命名規則:** 方法名英文 camelCase(**禁止中文**),`@DisplayName` 寫繁中描述;類別也加 `@DisplayName`。類別 Javadoc 說明本測試鎖住的契約與決策編號。

```java
@DisplayName("交易清單查詢參數解析")
class TradingServiceTest {
    /** 刻意可辨識的冪等鍵:用來斷言任何錯誤訊息都沒有把它回射出去(T-04-03)。 */
    private static final String CANARY_KEY = "LEAK-CANARY-12345";

    @Test
    @DisplayName("Traditional Chinese description here")
    void whenAssetNotFound_returnsEmpty() { ... }
}
```

- 測試類別與方法為 package-private(無 `public`)。
- `@BeforeEach` 手動建 mock 並 new 被測物件(`mock(TradingRepository.class)`),不使用 `@ExtendWith(MockitoExtension.class)` 為必要條件。
- 斷言錯誤:`assertThatThrownBy(...).isInstanceOf(BusinessException.class)` 並檢查 `errorCode()` 與訊息不含 canary。
- `@ParameterizedTest` + `@ValueSource` 用於非法輸入列舉。

**前端:**

```typescript
describe('tradingApi http adapter — payload 契約(TRAD-01 / TRAD-02)', () => {
  it('sends exactly the seven backend contract fields, nothing more', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(successEnvelope(TRADE_DTO))));
    await createHttpTradingApi('/api/v1').createTrade(TRADE_REQUEST, 'key-1');
    expect(Object.keys(bodyAt(0)).sort()).toEqual(CONTRACT_FIELDS);
  });
});
```

- `describe` 名稱可含繁中與需求編號,`it` 用英文句子;Playwright `test` 名稱用繁中 + 案例編號 + tag(`'T1 買進 → ... @smoke'`)。
- Setup:`beforeEach` 中 `setActivePinia(createPinia())`;需要 CSRF 時預先 `document.cookie = 'XSRF-TOKEN=...; path=/'` 避免多一次 `/api/v1/csrf` bootstrap。
- Teardown:`afterEach(() => vi.unstubAllGlobals())`;元件用 `cleanupMounted()`。

## Mocking

**後端:**
- Unit:Mockito `mock()` / `when()` / `verify()` / `verifyNoInteractions()` / `verifyNoMoreInteractions()` / `ArgumentCaptor`(攔截 `TradeQuery` 以鎖定解析契約)。
- WebMvc 切片:`@MockitoBean` 取代 service 與 `AuditLogger`;需要繞過授權時以內部 `@TestConfiguration OpenSecurityConfig` 替換 `SecurityFilterChain`;認證用 `SecurityMockMvcRequestPostProcessors.user(...)` 或 `@WithMockUser`。
- **禁止**硬編 JWT fixture(`"eyJ..."`);JWT 私鑰每次執行隨機產生。
- IT / E2E 不 mock:真 DB / Redis / Kafka 容器。

**前端:**

```typescript
// 子元件 stub:vi.hoisted 探針 + vi.mock 動態 import vue
const chartProbe = vi.hoisted(() => ({ data: null as unknown, renders: 0 }));
vi.mock('./LineChart.vue', async () => {
  const { defineComponent, h } = await import('vue');
  return { default: defineComponent({ /* 記錄 props */ }) };
});
// HTTP:stub 全域 fetch,回傳後端信封
vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(successEnvelope(dto))));
// 模式:testSetup.ts 預設 vi.stubEnv('VITE_DATA_MODE','mock');需 api 時在測試內覆蓋
vi.stubEnv('VITE_DATA_MODE', 'api');
```

- 原始碼斷言:以 Vite `?raw` 匯入 `.vue` 原始碼,斷言「某段程式碼不存在」(例:元件不得直接讀 mock store、不得 `Math.random` 生成成交價;`OrderTicket.test.ts`、`Positions.test.ts`)。
- Playwright layout 測試用 `page.route` stub(`e2e/support/portfolioStubs.ts`),journey 測試打真後端並用 `uniqueAccount` fixture(`e2e/support/fixtures.ts` 的 `base.extend`)隔離帳號。

**What to Mock:** 後端單元層的 repository / Facade / 外部 provider;前端的 `fetch`、`WebSocket`(`src/testing/fakeWebSocket.ts`)、重型子元件(圖表)。

**What NOT to Mock:**
- 後端 IT 的 DB / Redis / Kafka、`GlobalExceptionHandler`(信封形狀的權威驗證在 `stock-start` 的 `ErrorHandlingIT` 等 IT,WebMvc 切片不能證明信封)。
- 前端 `apiClient.ts` 本身(service 測試經真 `apiClient` + stub fetch,才能驗 CSRF header、401 refresh replay 沿用同一把 `Idempotency-Key`)。
- `testSetup.ts` 刻意**不 import 任何 service module**,否則會搶在各檔 `vi.mock` 前綁定真實實作。

## Fixtures and Factories

**後端:**
- 共用容器基底 `stock-start/src/test/java/dowob/xyz/stockwebv2/start/support/ContainerIT.java`:static singleton `timescale/timescaledb:2.17.2-pg16`、`redis:7.4-alpine`、`confluentinc/cp-kafka:7.6.0`,`Startables.deepStart` 每 JVM 啟動一次,不用 `withReuse(true)`;預設關閉 rate limit(`AuthRateLimitAndLockoutIT` 覆寫)。新 IT:`@AutoConfigureMockMvc class XxxIT extends ContainerIT`。
- 模組 IT(asset / market-data)自行宣告容器。
- E2E 基底 `e2e/support/AbstractStockE2ETest`(提供 `mockMvc`、`apiSuccess()` matcher 於 `StockE2EAssertions`)、`DatabaseCleaner`、`AuthE2EHelper`。
- 測試資料以具名常數表達意圖並寫 Javadoc 說明為何如此設計:
  ```java
  /** 三筆固定交易的成交時間;刻意與插入順序不一致,用來證明排序以 executed_at 為準。 */
  private static final String EXECUTED_AT_OLDEST = "2026-01-10T00:00:00Z";
  ```
- Flyway seed 資料(assets ≥ 19 筆)可被 E2E 依賴(`SmokeE2E`)。

**前端:**
- 手寫 fixture 常數置於測試檔頂部,每個標註後端 `file:line` 出處(`TRADE_REQUEST`、`TRADE_DTO`,`tradingApi.test.ts`);信封 helper `successEnvelope` / `failureEnvelope` / `jsonResponse` 目前為各檔內定義。
- 掛載:`mountWithPinia(Component, props)` / `mountComponent(...)`,搭配 `flushAsync(n)` 等待 fetch→json→render 鏈(`vue-app/src/testUtils.ts`)。

## Coverage

**Requirements:** 無數值門檻。後端 JaCoCo 只產報告;前端未設定 coverage。要求是「測試涵蓋功能」+ 每個 user-owned endpoint 都有「非擁有者回 404」測試。

**View Coverage:**

```bash
./mvnw verify    # 後端,<module>/target/site/jacoco/index.html
```

## Test Types

**Unit Tests:** 後端 service 與 `domain/*Calculator` 純邏輯、SQL 字串邊界;前端純函式、service adapter(兩種 mode)、元件行為。

**Integration Tests:** 後端 `*IT` 打完整 Spring context + 真容器,驗信封、HTTP status、append-only trigger(`TransactionsAppendOnlyIT`)、冪等(`TransactionsIdempotencyIT`)、安全標頭、CORS、rate limit。

**E2E Tests:**
- 後端:`*E2E.java` 多步驟使用者流程(MockMvc 或 WebSocket RANDOM_PORT)。
- 瀏覽器:Playwright,`@smoke`(CI / `npm run test:e2e` 跑)與 `@extended` tag;啟動腳本 `vue-app/e2e/run-e2e.sh` / `.ps1` + `e2e/docker-compose.yml`;`layout.spec.ts` 以 page.route stub 掃寬度(300–1440px),量元素是否在視窗與捲動容器可視框內。

## Common Patterns

**Async Testing(前端):**

```typescript
await createHttpTradingApi('/api/v1').createTrade(TRADE_REQUEST, 'key-1');
await flushAsync();      // 真計時器:macrotask hop(setImmediate)
await flushAsync(16);    // 任何假計時器(含 toFake: ['Date'])下只跑 microtask,送出路徑要多輪
```

有 interval 的元件(`HoldingEventDialog`、`LiveQuoteStatus`)不要用 `vi.runAllTimers()`。後端非同步用 Awaitility。

**Error Testing:**

```java
assertThatThrownBy(() -> service.createTrade(userId, request, CANARY_KEY))
    .isInstanceOf(BusinessException.class)
    .satisfies(e -> assertThat(e.getMessage()).doesNotContain(CANARY_KEY));
```

```typescript
vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(failureEnvelope('TRADE_CONFLICT', 'msg', 't1'), 409)));
await expect(api.createTrade(req, 'k')).rejects.toBeInstanceOf(ApiClientError);
```

**已知陷阱(見前端 `docs/LEARNINGS.md`):**
- 中途呼叫 `cleanupMounted()` 會 `vi.unstubAllEnvs()` 清掉 mock 預設;同測試需兩種模式就拆測試或明確 `vi.stubEnv`。
- 斷言 mock adapter 沒被呼叫:先 `stubEnv('mock')` + `resetRuntimeApiClientsForTests()`,再 `vi.spyOn(getRuntimeApiClients().market, ...)`,最後掛載。
- jsdom `getBoundingClientRect()` 恆為 0,版面問題用 Playwright 量。
- 用 sorted key `toEqual` 而非 `toMatchObject` 驗 payload(後者抓不到多送欄位)。
- Node 20(CI)與本機 Node 版本 tick 數不同,`fnm exec --using=20` 重現 CI。

---

*Testing analysis: 2026-11-07*
