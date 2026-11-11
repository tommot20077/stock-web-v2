# Phase 5: Cross-Repo Browser Flow Verification & Contract Hardening - Pattern Map

**Mapped:** 2026-11-07
**Files analyzed:** 16 個新檔 + 主要修改檔群組
**Analogs found:** 15 / 16

> 後端路徑相對於後端 repo 根(`/home/user/stock-web-v2`);前端路徑一律以「前端 repo 根/」開頭(cloud:`/home/user/stock-web-v2-front-end`,程式碼在 `vue-app/`)。
> 所有 analog 皆為兩個 repo 中已追蹤的原始碼(無 gitignored mirror)。

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match |
|---|---|---|---|---|
| 前端 repo 根/`vue-app/e2e/tests/contract-journey.spec.ts` (new) | test (browser E2E) | request-response | 前端 `vue-app/e2e/tests/trade.spec.ts` T1 + `auth.spec.ts` A11 | exact |
| 前端 repo 根/`vue-app/e2e/tests/api-failure-states.spec.ts` (new) | test (browser E2E, table-driven) | request-response + route 注入 | 前端 `vue-app/e2e/support/portfolioStubs.ts` `mockPortfolioApi` / `unavailable` | role-match |
| 前端 repo 根/`vue-app/e2e/support/mockSentinels.ts` (new) | utility (E2E const) | — | 前端 `vue-app/e2e/support/portfolioStubs.ts` 頂部常數 | role-match |
| 前端 repo 根/`vue-app/src/mockSentinels.test.ts`(名稱 planner 定,new) | test (Vitest guard) | transform | 前端 `vue-app/src/mockStoreBoundary.test.ts` | exact |
| 前端 repo 根/`vue-app/src/services/decimal.ts` (new) | utility | transform | 前端 `vue-app/src/services/runtimeDataMode.ts`、`vue-app/src/moneyFormat.ts` | role-match |
| 前端 repo 根/`vue-app/src/services/apiClient.ts` (mod, D-26 `NETWORK_ERROR`) | service (transport) | request-response | 同檔 `errorFromResponse`(:235-250) | exact |
| 前端 repo 根/`vue-app/vite.config.ts`、`playwright.config.ts` (mod, D-21) | config | — | 同檔 | exact |
| 前端 repo 根/`.github/workflows/ci.yml` (mod, new `browser-e2e` job) | config (CI) | batch | 後端 `.github/workflows/ci.yml:155-230` `browser-e2e` | exact(鏡像) |
| `stock-infrastructure/.../infrastructure/web/json/DecimalString.java` (new) | annotation | transform | `stock-module-market-data/.../marketdata/api/LatestPriceDto.java:8-25` | partial |
| `stock-infrastructure/.../infrastructure/web/json/PlainDecimalStringSerializer.java` (new) | utility (Jackson 3 serializer) | transform | 同上 + RESEARCH Pattern 1 | partial |
| `stock-start/src/test/java/dowob/xyz/stockwebv2/start/ApiDecimalStringRulesTest.java` (new) | test (ArchUnit + 序列化) | — | `stock-start/src/test/java/dowob/xyz/stockwebv2/start/ArchitectureRulesTest.java` | exact |
| `stock-start/src/test/java/dowob/xyz/stockwebv2/start/E2eNamingRulesTest.java` (new) | test (ArchUnit) | — | 同上 | exact |
| `stock-module-user/.../user/service/AccessTokenTtlConsistencyGuard.java` (new) + `...Test.java` | config guard | startup | `stock-module-user/.../user/service/BrowserAuthCookieSecureGuard.java` + `BrowserAuthCookieSecureGuardTest.java` | exact |
| `stock-module-user/src/test/java/dowob/xyz/stockwebv2/user/service/BrowserAuthCookieServiceTest.java` (new, G1) | test (unit) | — | `BrowserAuthCookieSecureGuardTest.java`(建 properties 的 helper) | role-match |
| `stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/support/MarketDataContainers.java` (new) | test support | — | `stock-start/src/test/java/dowob/xyz/stockwebv2/start/support/ContainerIT.java` | exact |
| `stock-db-migration/src/main/resources/db/migration/V20__watchlists_name_blank_like_check.sql` (new) | migration | — | `V19__assets_currency_check.sql` + `V18__watchlists.sql:33-34` | exact |
| 7 個 `*E2ETest` → `*E2E` + 3 個 `Abstract*E2ETest` 改名 (D-23/D-27) | test rename | — | `stock-start/pom.xml:160-201`(不需改) | n/a |
| trading / backtest / asset `*Dto` / `*Request` (mod, ~90 欄位) | model | transform | `LatestPriceDto.java:24-25` | exact |

## Pattern Assignments

### 前端 repo 根/`vue-app/e2e/tests/contract-journey.spec.ts`

**Analog:** 前端 `vue-app/e2e/tests/trade.spec.ts`(T1,:39-66)、`auth.spec.ts`(A11,:120-147)

**Imports / 命名**(auth.spec.ts:1;trade.spec.ts:1-2):
```ts
import { test, expect, loginViaUi } from '../support/fixtures';
import type { Page } from '@playwright/test';
// test 名稱:繁中 + 案例編號 + tag,例 'T1 買進 → ... @smoke'
```
**下單 + 三頁重讀**(trade.spec.ts:39-66):沿用 `openTicketFromTrades` / `pickSymbol` / `submitTrade`(同檔 :20-37,可複製或抽到 support),斷言 `ticket-result-trade-id` UUID、`trades-fresh-badge`、`positions-row` filter `AAPL`。
**登出後以舊 cookie 打 API(out-of-band)**(auth.spec.ts:127-138):
```ts
const cookies = await page.context().cookies();
const cookieHeader = cookies.map(c => `${c.name}=${c.value}`).join('; ');
const xsrf = cookies.find(c => c.name === 'XSRF-TOKEN')?.value ?? '';
const outOfBand = await playwright.request.newContext({ baseURL: baseURL ?? 'http://localhost:4173' });
try { const res = await outOfBand.get('/api/v1/me', { headers: { Cookie: cookieHeader } }); /* expect 401 envelope */ }
finally { await outOfBand.dispose(); }
```
**請求監聽骨架**:照 RESEARCH Pattern 2(`page.on('request')` + `req.allHeaders()`,`phase` 旗標;CSRF 比對用同一請求的 cookie header,不用事後 `context.cookies()`)。

---

### 前端 repo 根/`vue-app/e2e/tests/api-failure-states.spec.ts`

**Analog:** 前端 `vue-app/e2e/support/portfolioStubs.ts:390-398`(失敗信封 fulfill 形狀)
```ts
await route.fulfill({
  status: 503,
  json: { success: false, data: null, error: { code: 'SERVICE_UNAVAILABLE', message: 'layout test' }, meta: { traceId } },
});
```
- 與 stubs 不同:本 spec 是**真後端**,只對目標端點注入失敗,其餘 `route.continue()`;重試時切 `failing = false`(RESEARCH Pattern 3)。
- 種決定性資料用 `journeyApi.apiBuy(page, 'AAPL', 3, 123.45)`(`journeyApi.ts:51-62`;Wave 2 後 quantity/price 改字串)。
- 斷言碼:abort → `NETWORK_ERROR`(D-26);5xx → 注入 code + traceId 可見;502 HTML → `HTTP_ERROR`(`apiClient.ts:246-250`)且 trace-id 隱藏。
- 哨兵斷言限定區塊 locator(`positions-table` 等),不得對 `body`。

---

### 前端 repo 根/`vue-app/e2e/support/mockSentinels.ts`

**Analog:** 前端 `vue-app/e2e/support/portfolioStubs.ts:16-33`(檔頭 JSDoc 說明「為什麼」 + `export const` 常數)
```ts
/** 500 字備註:前 300 字中文,後 200 字元無空白英數。 */
export const LONG_NOTE = '...';
export const LONG_TRACE_ID = `trace-${'a1b2c3d4'.repeat(8)}`;
```
內容來源:`src/data.ts:35-50`、`stores/mockPortfolio.ts:5-9`(`NVDA`、`2330.TW`、`BTC`、`1142.83`、`67842.40`、`核心持倉`、`AI 概念`…)。匯出 `{ symbols, prices, notes, watchlistNames }`。

### 前端 repo 根/`vue-app/src/mockSentinels.test.ts`

**Analog:** 前端 `vue-app/src/mockStoreBoundary.test.ts:1-40` —— 含「掃描範圍非空」與「偵測規則本身有效(避免守門永遠綠)」兩種 self-check:
```ts
it('偵測規則本身有效(避免守門永遠綠)', () => {
  expect(violations({ a: "import ... from '../stores/mockPortfolio';" })).toEqual(['a']);
});
```
斷言每個哨兵仍出現在 mock 資料輸出中。

---

### 前端 repo 根/`vue-app/src/services/decimal.ts`

**Analog:** 前端 `vue-app/src/services/runtimeDataMode.ts:1-22`(小型純函式模組、具名 export、錯誤 class)與 `vue-app/src/moneyFormat.ts:1-25`(檔頭繁中區塊註解說明決策,引 D-編號)。
- 無 barrel;直接 `import { parseDecimal } from './decimal'`。
- 實作照 RESEARCH Pattern 4(big.js clone、`strict = true`、`RM = roundHalfUp`、`toWire` 用 `toFixed()`)。
- `moneyFormat.ts` 是 decimal → 顯示的唯一出口(目前 `isFiniteNumber(value: number)`,:18-20,需改簽名)。
- 測試:同目錄 `decimal.test.ts`,仿 `runtimeDataMode.test.ts`。

---

### 前端 repo 根/`vue-app/src/services/apiClient.ts`(D-26)

**Analog(同檔):** `ApiClientError` 建構(:3-30)、`errorFromResponse`(:235-250)。三處裸 `fetch`:`:179`(bootstrapCsrf)、`:276`(refresh)、`:305` / `:320`(fetchWithSessionRecovery)全部改走 `transportFetch`(RESEARCH Code Examples)。
```ts
return new ApiClientError({ status: response.status, code: 'HTTP_ERROR', message: `Request failed with status ${response.status}`, requestId: null });
```
連帶:`src/baseCurrencyErrorKey.ts` 的 `!(error instanceof ApiClientError)` 判斷要加 `code === 'NETWORK_ERROR'`;單元測試加到 `apiClient.test.ts`。

---

### 前端 repo 根/`vue-app/vite.config.ts` / `playwright.config.ts`(D-21)

現況 `vite.config.ts:7-12`:
```ts
const backendProxy = { '/api': 'http://localhost:8080', '/ws': { target: 'ws://localhost:8080', ws: true } };
```
維持物件形式 `defineConfig({...})`(`src/viteConfig.test.ts` 直接 import 物件)。`playwright.config.ts:28-35` webServer `env: { VITE_DATA_MODE: 'api' }` 加 `VITE_API_PROXY_TARGET`。

---

### 前端 repo 根/`.github/workflows/ci.yml`(新 `browser-e2e` job)

**Analog:** 後端 `.github/workflows/ci.yml:155-230`,方向反轉(`FE_REPO`→`BE_REPO`、`path: backend` 互換)。關鍵段(:174-190):
```yaml
      - name: Resolve frontend ref (match backend branch, else develop)
        id: fe_ref
        run: |
          CANDIDATE="${{ github.head_ref || github.ref_name }}"
          FE_REPO="https://github.com/tommot20077/stock-web-v2-front-end.git"
          if git ls-remote --exit-code --heads "$FE_REPO" "$CANDIDATE" >/dev/null 2>&1; then
            echo "ref=$CANDIDATE" >> "$GITHUB_OUTPUT"
          else
            echo "ref=develop" >> "$GITHUB_OUTPUT"
          fi
```
其餘 step(setup-java 21 temurin maven cache、setup-node 20 `cache-dependency-path: frontend/vue-app/package-lock.json`、`npm ci`、`npx playwright install --with-deps chromium`、`E2E_BACKEND_DIR` + `bash e2e/run-e2e.sh`、`if: always()` upload `frontend/vue-app/e2e/artifacts/`)逐條照抄。前端現有 job 名為 `frontend`(:10),新 job `needs: frontend`。後端 repo public,用預設 token,無人工 checkpoint。同時後端 :215-217 step 名「Run Browser E2E (@smoke)」與註解「(預設只跑 @smoke)」要改(D-14)。

---

### `DecimalString.java` / `PlainDecimalStringSerializer.java`(stock-infrastructure `web/json/`)

**Analog:** `stock-module-market-data/.../marketdata/api/LatestPriceDto.java`
```java
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.annotation.JsonSerialize;      // Jackson 3
import tools.jackson.databind.ser.std.ToStringSerializer;
...
    @JsonSerialize(using = ToStringSerializer.class) @JsonProperty("price") BigDecimal price,
```
- 新寫法照 RESEARCH Pattern 1(`StdSerializer<BigDecimal>`、`gen.writeString(value.toPlainString())`、`@JacksonAnnotationsInside`)。**不用** `ToStringSerializer`(輸出 `0E-8`)。
- `stock-infrastructure` 目前無 Jackson 依賴 → pom 需加 Jackson 3 databind(planner 確認 artifact)。`stock-infrastructure/.../web/` 已有 `ApiMetaFactory` 等,新子套件 `web/json/`。
- 需要 Javadoc + `@author Yuan` / `@version`。`PriceTickEvent`(Jackson 2,Kafka)不要動。
- 套用:`LatestPriceDto` / `KlineDto` 是否改用 `@DecimalString` 由 planner 決定(對齊一般記號)。

---

### `stock-start/.../start/ApiDecimalStringRulesTest.java` 與 `E2eNamingRulesTest.java`

**Analog:** `stock-start/src/test/java/dowob/xyz/stockwebv2/start/ArchitectureRulesTest.java:1-13, 35-78`
```java
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "dowob.xyz.stockwebv2", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {
    /** 規則 N:繁中說明 + 引用文件段落。 */
    @ArchTest
    static final ArchRule controllersMustNotDependOnFacades = noClasses()
        .that().areAnnotatedWith(RestController.class)
        .should().dependOnClassesThat().haveSimpleNameEndingWith("Facade");
}
```
- `ApiDecimalStringRulesTest`:`DoNotIncludeTests`;`fields().that().haveRawType(BigDecimal.class).and().areDeclaredInClassesThat(<..api.. 且 simpleName 結尾 Dto/Request,含巢狀>).should().beAnnotatedWith(DecimalString.class)`;另加 JsonMapper 行為測試(`ZERO.setScale(8)` → `"0.00000000"`)。
- `E2eNamingRulesTest`:`importOptions = ImportOption.OnlyIncludeTests.class`、package `dowob.xyz.stockwebv2.start.e2e`;依 D-27 **不排除 abstract**(移除 RESEARCH 草稿的 `doNotHaveModifier(ABSTRACT)`),`.allowEmptyShould(false)`。檔案放在 `e2e` 套件外,以 `Test` 結尾 → unit job。

---

### `AccessTokenTtlConsistencyGuard.java` + `AccessTokenTtlConsistencyGuardTest.java`

**Analog:** `stock-module-user/.../user/service/BrowserAuthCookieSecureGuard.java:16-31`
```java
@Component
public class BrowserAuthCookieSecureGuard {
    public BrowserAuthCookieSecureGuard(BrowserAuthCookieProperties properties, Environment environment) {
        if (!properties.secure() && !environment.acceptsProfiles(LOCAL_PROFILES)) {
            throw new IllegalStateException(
                "STOCK_AUTH_COOKIE_SECURE must be true outside dev/test/e2e/e2e-browser profiles");
        }
    }
}
```
建構子參數改 `(BrowserAuthCookieProperties cookie, JwtProperties jwt)`(`JwtProperties` 在 `stock-infrastructure/.../infrastructure/security/JwtProperties.java:8`,record `accessTokenTtl()`)。注意 `BrowserAuthCookieProperties:38-39` null/非正值會預設 15 分鐘 → 比對用正規化後的值。
**Test analog:** `BrowserAuthCookieSecureGuardTest.java:20-55`
```java
private static BrowserAuthCookieProperties cookie(boolean secure) {
    return new BrowserAuthCookieProperties(null, null, null, "Lax", secure, null,
        Duration.ofMinutes(15), Duration.ofDays(14));
}
assertThatThrownBy(() -> new BrowserAuthCookieSecureGuard(cookie(false), env("demo")))
    .isInstanceOf(IllegalStateException.class).hasMessageContaining("STOCK_AUTH_COOKIE_SECURE");
```
同 PR 必改:`stock-start/src/test/resources/application-test.yaml:28`、`application-e2e.yaml:4` → `PT15M`。

### `BrowserAuthCookieServiceTest.java`(G1)

**Analog:** 同上 test 的 properties helper;受測 `BrowserAuthCookieService`(`:23-81`,`addAuthCookies`/`clearAuthCookies`/`addCsrfCookie`,內部 `ResponseCookie.from(name, value)` :81)。用 `MockHttpServletResponse` 讀 `Set-Cookie`,斷言 Max-Age=900、`SameSite=Lax`、`Path=/`、secure 依設定、空白 domain 不輸出、`XSRF-TOKEN` 非 HttpOnly。

---

### `MarketDataContainers.java`(D-04 / D-29)

**Analog:** `stock-start/src/test/java/dowob/xyz/stockwebv2/start/support/ContainerIT.java:15-31`
```java
static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
    DockerImageName.parse("timescale/timescaledb:2.17.2-pg16").asCompatibleSubstituteFor("postgres"))
    .withDatabaseName("stock_v2_test").withUsername("stock").withPassword("stock");
static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
static final ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));
static { Startables.deepStart(postgres, redis, kafka).join(); }   // 不加 @Container / @Testcontainers
```
- 改成三個 holder(`KafkaHolder`/`PostgresHolder`/`RedisHolder`)各自 `static {...start()}`。
- `freshDatabase(name)` 後沿用現有 Flyway 寫法(`MarketPriceRepositoryIT.java:63-70`):
```java
Flyway.configure().dataSource(url, user, pw).locations("classpath:db/migration").mixed(true).load().migrate();
```
- `ensureTopics(admin, NewTopic...)` 吞 `TopicExistsException`;取代 `KafkaConfigIT.java:46`、`PriceWriterRetryIT:92`、`MarketDataIngestServiceIT:87` 的 `admin.createTopics(...).all().get(10, SECONDS)`。
- 11 個 IT 移除 `@Testcontainers` / `@Container`(例 `MarketPriceRepositoryIT.java:39-48`),`BackfillJobIT` 亦同(D-29)。

---

### `V20__watchlists_name_blank_like_check.sql`

**Analog:** `V19__assets_currency_check.sql`(檔頭繁中註解:出處 quick id、為何、套用失敗時的查詢、「V1~V19 不得修改」)+ 原約束 `V18__watchlists.sql:33-34`:
```sql
CONSTRAINT ck_watchlists_name_not_blank CHECK (btrim(name) <> '')
```
做法:`ALTER TABLE watchlists DROP CONSTRAINT ck_watchlists_name_not_blank;` + `ADD CONSTRAINT ... CHECK (<含 U+00A0 等不換行空白的 trim> <> '')`,註解附違規列查詢。確認目前最新為 V19。

---

### DTO / Request 欄位字串化(Wave 2)

**Analog:** `LatestPriceDto.java:24-25`;改為 `@DecimalString BigDecimal amount`。`CashEntryDto` 的「不加 ToStringSerializer」註解改寫(D-09)。前端對應 `apiTypes.ts` 欄位改 `string`、`portfolioStubs.ts` fixture(`import type` 綁 apiTypes,tsc 會抓漂移)、`journeyApi.apiBuy`(`:51-62`,`quantity`/`price`/`fee` 改字串)。

## Shared Patterns

### 錯誤信封(前端 E2E 注入 / 斷言)
**Source:** 前端 `vue-app/e2e/support/portfolioStubs.ts:390-398`;`apiClient.ts:235-250`
**Apply to:** api-failure-states、contract-journey 的 401 斷言 —— `{ success:false, data:null, error:{code,message}, meta:{traceId} }`。

### 真後端寫入帶 CSRF
**Source:** 前端 `vue-app/e2e/support/journeyApi.ts:16-38`(`csrfHeader` + `apiWrite`,`Idempotency-Key` 用 `crypto.randomUUID()`)
**Apply to:** contract-journey、api-failure-states 的資料種子。

### 啟動守衛
**Source:** `BrowserAuthCookieSecureGuard.java` —— `@Component` 建構子檢查 + 靜態 `IllegalStateException` 訊息(不回射值)。

### ArchUnit 守門
**Source:** `ArchitectureRulesTest.java` —— `@AnalyzeClasses` + `@ArchTest static final ArchRule`,每條附繁中 Javadoc 說明出處。

### 守門自我驗證
**Source:** 前端 `vue-app/src/mockStoreBoundary.test.ts:35-38`「偵測規則本身有效」→ 套用 mockSentinels 測試;後端 ArchUnit 用 `allowEmptyShould(false)`。

### Javadoc / 註解
public 類別繁中 Javadoc + `@author Yuan` + `@version`;引用 D-編號;Spotless import 順序 `dowob,jakarta,org,com,java,#`。

## No Analog Found

| File | Role | Reason |
|---|---|---|
| `PlainDecimalStringSerializer.java` 的自訂 `StdSerializer` 本體 | utility | repo 內無自訂 Jackson 3 serializer;用 RESEARCH Pattern 1(已實測) |

(big.js 為新依賴,無既有 decimal 用法;用 RESEARCH Pattern 4。)

## Metadata

**Analog search scope:** 後端 `stock-start/src/test`、`stock-module-user`、`stock-module-market-data/src/{main,test}`、`stock-db-migration`、`.github/workflows`;前端 `vue-app/e2e/{tests,support}`、`vue-app/src/{services,*.test.ts}`、`.github/workflows`、`vite.config.ts`、`playwright.config.ts`
**Files scanned:** ~25
**Pattern extraction date:** 2026-11-07
