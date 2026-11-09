<!-- generated-by: gsd-doc-writer -->
# 測試總覽(Testing)

本文件說明 Stock Web V2 後端的測試分層、各層在本機怎麼跑、命名規範、ArchUnit 架構規則,以及 CI 的各個 job(包含跨 repo 的 Browser E2E)。

> **規範以 [`ai-docs/testing-standards.md`](../../ai-docs/testing-standards.md) 為準。** 本文件只說明現況與操作方式。兩者有出入時,以 `testing-standards.md` 為準,並回報差異。

基本原則(摘自 `testing-standards.md`):

- Unit、Integration、E2E 三層測試都要寫,除非 Yuan 明確豁免。「不適用」不能當理由。
- **輸出保持乾淨**:除非是在測錯誤處理,測試過程不應該印出雜訊 log。
- **禁止硬編 JWT token fixture**(例如 `String TOKEN = "eyJ..."`)。JWT 私鑰每次執行都隨機產生;需要認證上下文時用 `@WithMockUser` 或 `RequestPostProcessor`。

---

## 測試框架與前置需求

| 工具 | 版本 / 來源 | 用途 |
|------|-------------|------|
| JUnit 5 + Mockito + AssertJ | 由 Spring Boot `4.0.4` parent 管理 | 所有層的基本 runner 與斷言 |
| Spring Test(`spring-boot-starter-test`、`spring-boot-starter-webmvc-test`、`spring-security-test`) | Spring Boot parent | `@WebMvcTest`、`@SpringBootTest`、`MockMvc`、`@MockitoBean` |
| Testcontainers | `1.21.4`(BOM,根 `pom.xml`) | `*IT` / E2E 用的真 PostgreSQL(TimescaleDB)、Redis、Kafka |
| ArchUnit | `1.3.0`(`archunit-junit5`,`stock-start` test scope) | 模組邊界與端點授權守門 |
| Awaitility、`spring-kafka-test` | `stock-start` test scope | 非同步斷言、Kafka 相關測試 |
| JaCoCo | `0.8.13`(根 `pom.xml`) | 只產報告,不設門檻 |
| Surefire / Failsafe | Spring Boot parent | Surefire 跑單元與 Web 層測試;Failsafe 在 `verify` 階段跑 `*IT` |

前置需求:

- **JDK 21**:Maven 一律用 repo 內的 Maven Wrapper(`./mvnw`,Windows 用 `mvnw.cmd`)。
- **Docker**:`*IT`、後端 E2E、Browser E2E 都要用 Docker。Testcontainers 會自己拉起容器,不需要先手動 `docker compose up`。`./mvnw test`(單元與 Web 層)不需要 Docker。
- **Spotless**:綁在 `validate` 階段,所以每次 Maven build(包含 `test`、`verify`)都會先檢查格式。格式不對時跑 `./mvnw spotless:apply` 修正。

---

## 測試分層

| 層 | 命名 | 工具 | 範圍 | 由誰執行 |
|----|------|------|------|----------|
| Unit | `*Test.java` | JUnit 5 + Mockito | Service 業務邏輯、`domain` 純計算、SQL / mapper 邊界 | Surefire(`./mvnw test`) |
| Web 層 | `*ControllerTest.java`、`*ControllerWebMvcTest.java`、`*RequestValidationTest.java` | `@WebMvcTest` + `@MockitoBean` | Controller、請求驗證、Security | Surefire |
| 架構 | `ArchitectureRulesTest`、`EndpointAuthorizationRulesTest`、`E2eNamingRulesTest` | ArchUnit | 全專案正式程式碼的結構規則;`e2e` 套件的測試命名 | Surefire |
| Integration | `*IT.java` | `@SpringBootTest` + Testcontainers | 完整 API 流程:信封、HTTP status、append-only trigger、冪等、CORS、安全標頭、rate limit | **Failsafe**(`verify` 階段) |
| 後端 E2E | `*E2E.java`(`stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/`) | `@SpringBootTest` + Testcontainers,`e2e` profile | 多步驟使用者流程 | Surefire,只在 `-Pe2e` profile 下執行 |
| Browser E2E | `*.spec.ts`(前端 repo 的 `vue-app/e2e/tests/`) | Playwright(Chromium) | 真瀏覽器 → 真後端(`e2e-browser` profile) | 前端 repo 的 `e2e/run-e2e.sh` |

### 測試檔放在哪裡

- 單元與 Web 層測試照 main 的套件結構,放在各模組的 `src/test/java/...`。需要做模組切片測試的模組另外有自己的 `@SpringBootApplication` 測試啟動類別:`TradingTestApplication`、`AssetTestApplication`、`MarketDataTestApplication`。
- 需要完整 Spring context 的跨模組 `*IT` 與後端 E2E 集中在 `stock-start/src/test/java/dowob/xyz/stockwebv2/start/`。
- `stock-module-asset` 與 `stock-module-market-data` 也有自己的模組級 `*IT`(例如 `AssetFacadeImplIT`、`MarketPriceRepositoryIT`),由各模組 `pom.xml` 設定的 Failsafe 執行。

### Surefire 與 Failsafe 的分工

**檔名後綴決定測試由誰執行。** 需要容器的測試**必須**以 `IT`(後端 E2E 則是 `E2E`)結尾,而且**不得**加進 Surefire 的 includes。這樣 `./mvnw test` 的單元測試迴圈才不必啟動容器。

`stock-start/.../e2e/` 套件(含 `support/`)內的類別一律不得以 `Test` 結尾,具體的 E2E 類別一律以 `E2E` 結尾、抽象基底也不例外(例如 `AbstractStockE2E`)。這條規則由 `E2eNamingRulesTest`(ArchUnit,跑在 Surefire)守門:`e2e` 套件裡以 `Test` 結尾的類別會被 Unit job 執行(需要容器),卻不會被 `-Pe2e` 的 E2E job 執行。

| 模組 | Surefire | Failsafe |
|------|----------|----------|
| `stock-start` | 預設 includes(`*Test.java` 等),exclude `**/*E2E.java`;`-Pe2e` 時改成**只** include `**/*E2E.java` | 預設 includes(`*IT.java`),跑 `integration-test` + `verify` |
| `stock-module-asset`、`stock-module-market-data` | 明確 include `**/*Test.java`、`**/*Tests.java` | 同上 |
| 其他模組 | Maven 預設 | 未設定(沒有 `*IT`) |

---

## 執行測試

以下命令都在後端 repo 根目錄執行。

### 格式檢查(Spotless)

```bash
./mvnw spotless:check     # 只檢查
./mvnw spotless:apply     # 自動修正 import 順序與空白
```

### 單元與 Web 層測試(Surefire)

```bash
./mvnw test
```

只跑單一模組或單一測試類別:

```bash
./mvnw -pl stock-module-trading test
./mvnw -pl stock-module-trading test -Dtest=TradingServiceTest
./mvnw -pl stock-module-trading test -Dtest='TradingServiceTest#<methodName>'   # 只跑單一方法(支援 * 萬用字元)
```

`-pl` 只跑指定模組,但它依賴的其他模組必須已經在本機 Maven repository 裡。第一次跑之前,先執行一次 `./mvnw -B install -DskipTests`;或者改用 `-am` 連同上游模組一起建置,這時要加 `-Dsurefire.failIfNoSpecifiedTests=false`,否則上游模組找不到指定的測試會直接失敗。

### 整合測試(`*IT.java`,Failsafe,需要 Docker)

CI 的 Integration job 跑的就是這條命令:

```bash
./mvnw -pl stock-start -am verify -Dspring-boot.repackage.skip=true
```

- `-am` 會一起建置 `stock-start` 依賴的所有模組,所以 `stock-module-asset` 與 `stock-module-market-data` 的模組級 `*IT` 也會跑。
- `verify` 會先經過 `test` 階段,所以 Surefire 測試也會再跑一次。
- `-Dspring-boot.repackage.skip=true` 用來跳過 fat jar 打包,縮短時間。

只跑單一 IT 類別:

```bash
./mvnw -B install -DskipTests     # 上游模組先裝進本機 repository(只需做一次)
./mvnw -pl stock-start verify -Dit.test=TradingApiIT -Dspring-boot.repackage.skip=true
```

### 後端 E2E(`*E2E.java`,需要 Docker)

CI 的 E2E job 跑的是:

```bash
./mvnw -pl stock-start -am test -Pe2e
```

`e2e` profile 只定義在 `stock-start/pom.xml`。所以在 `stock-start` 裡只會跑 `*E2E.java`,上游模組則照常跑自己的 Surefire 單元測試。只跑單一 E2E 類別:

```bash
./mvnw -pl stock-start test -Pe2e -Dtest=SmokeE2E
```

### Browser E2E(Playwright,需要 Docker 與前端 repo)

Browser E2E 的 harness 放在**前端 repo**,不在本 repo。前端 repo 根的位置對照見 [`ai-docs/judgment.md`](../../ai-docs/judgment.md) §8。在前端 repo 的 `vue-app/` 目錄下執行:

```bash
npm ci
npx playwright install chromium
E2E_BACKEND_DIR=<後端 repo 根> bash e2e/run-e2e.sh                     # 無參數 = 全部(@smoke + @extended)
E2E_BACKEND_DIR=<後端 repo 根> bash e2e/run-e2e.sh --grep @smoke       # 本機快速迴圈
```

`run-e2e.sh` 的流程:

1. `docker compose up -d --wait` 拉起暫時的基礎設施(前端 repo 的 `vue-app/e2e/docker-compose.yml`)。映像版本與 `ContainerIT` 一致,固定 host port 為 15432 / 16379 / 19092。
2. 在後端 repo 執行 `./mvnw -pl stock-start -am package -DskipTests`。
3. 以 `java -jar stock-start/target/stock-start-*.jar --spring.profiles.active=e2e-browser` 啟動後端(預設 port 8080)。
4. 等待 `/actuator/health` 回報 `UP`,最多 90 秒;逾時就印出 compose 與後端 log。
5. 執行 `npx playwright test`。沒帶參數時跑全部 tag(D-14)。Playwright 的 `webServer` 會用 `VITE_DATA_MODE=api` 重新 build 前端,再以 `vite preview` 在 4173 port 提供服務,並以 `VITE_API_PROXY_TARGET=http://localhost:8080` 把 proxy 指到 E2E 後端(`vite.config.ts` 的預設是 `11180`)。
6. 結束後停掉後端,並執行 `docker compose down -v`。

`E2E_BACKEND_DIR` 的預設值是 Yuan 本機的目錄配置。其他環境(例如 cloud)請明確指定這個變數。腳本另外支援 `E2E_BACKEND_PORT`、`E2E_SKIP_BUILD=1`、`E2E_KEEP=1`、`E2E_ENV_ONLY=1`,說明見前端 repo 的 `vue-app/e2e/README.md`。失敗時的 artifacts(`backend.log`、`playwright-report/`、`test-results/`)放在前端 repo 的 `vue-app/e2e/artifacts/`。

`e2e-browser` profile 的設定說明見 [`docs/configuration/overview.md`](../configuration/overview.md)。設計文件是 [`docs/plans/2026-07-16-browser-e2e-testing-design.md`](../plans/2026-07-16-browser-e2e-testing-design.md)。

### 覆蓋率報告

```bash
./mvnw verify    # 報告輸出到 <module>/target/site/jacoco/index.html
```

---

## 撰寫新測試

### 命名規則

- **測試方法名稱禁止使用中文**,一律用英文 `camelCase`(例如 `whenAssetNotFound_returnsEmpty`),再用 `@DisplayName("繁體中文描述")` 提供可讀的說明。測試類別也加 `@DisplayName`。
- 檔名後綴照[測試分層](#測試分層)表格選擇。後綴錯了,測試就會跑在錯的階段:需要容器卻沒以 `IT` 結尾,會拖慢單元迴圈;以 `IT` 結尾卻放進 Surefire includes,則違反規範。
- 測試類別與方法用 package-private(不加 `public`)。

```java
@DisplayName("交易清單查詢參數解析")
class TradingServiceTest {

    @Test
    @DisplayName("找不到資產時回傳空結果")
    void whenAssetNotFound_returnsEmpty() { ... }
}
```

### 共用容器基底:`ContainerIT`

`stock-start/src/test/java/dowob/xyz/stockwebv2/start/support/ContainerIT.java` 是 `stock-start` 所有 IT 與 E2E 共用的基底類別:

- 帶有 `@SpringBootTest`,以 static singleton 宣告三個容器:`timescale/timescaledb:2.27.2-pg18`、`redis:8.8.0-alpine`、`apache/kafka:4.2.0`(KRaft)。透過 `Startables.deepStart` 讓每個 JVM 只啟動一次,**沒有**使用 `withReuse(true)`。
- 用 `@DynamicPropertySource` 注入 datasource、Redis、Kafka 連線,啟用 Flyway(`classpath:db/migration`),並把 management port 固定為 11180。
- 預設**關閉 rate limit**,避免 per-IP 計數在測試之間互相污染;也關閉行情排程與 ingestor,讓斷言市價的測試結果穩定。
- 需要開啟 rate limit 的測試(例如 `AuthRateLimitAndLockoutIT`)不繼承 `ContainerIT`,改在自己的 `@DynamicPropertySource` 裡呼叫 `ContainerIT.registerContainerProperties(registry)`,共用同一組容器。

新增 IT 的寫法:

```java
@AutoConfigureMockMvc
class XxxApiIT extends ContainerIT { ... }
```

`stock-module-asset` 與 `stock-module-market-data` 的模組級 IT 目前自己宣告容器,沒有共用 `ContainerIT`。

### 後端 E2E 基底

`stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/support/` 提供:

| 類別 | 用途 |
|------|------|
| `AbstractStockE2E` | 繼承 `ContainerIT`,加上 `@AutoConfigureMockMvc` + `@ActiveProfiles("e2e")`,注入 `mockMvc` 與 `objectMapper` |
| `AbstractPortfolioE2E` | 繼承 `AbstractStockE2E`,提供持倉 / 現金類流程的共用步驟 |
| `AbstractWsE2E` | 繼承 `ContainerIT`,以 `RANDOM_PORT` 啟動真實 server,供 WebSocket 流程使用 |
| `AuthE2EHelper` | 註冊 / 登入等認證步驟 |
| `DatabaseCleaner` | 測試間清理資料 |
| `StockE2EAssertions` | 共用斷言(例如 `apiSuccess()` 信封 matcher) |

`e2e` profile(`stock-start/src/test/resources/application-e2e.yaml`)會開啟 `/v3/api-docs` 給 `SmokeE2E` 驗證,保留 mock provider,並停用行情排程與 ingestor,避免背景 tick 干擾 WebSocket 計數。

### Web 層與單元測試慣例

- Web 層切片用 `@MockitoBean` 取代 service 與 `AuditLogger`。認證用 `SecurityMockMvcRequestPostProcessors.user(...)` 或 `@WithMockUser`。
- **信封形狀(`ApiResponse<T>`)要用 IT 驗證**,例如 `ErrorHandlingIT`。WebMvc 切片不經過完整的例外處理鏈,沒辦法證明信封形狀正確。
- 錯誤訊息不得回射使用者輸入。單元測試用刻意可辨識的 canary 值斷言這一點(見 `TradingServiceTest` 的 `CANARY_KEY`)。
- IT 與 E2E 不 mock DB / Redis / Kafka,一律打真容器。
- 每個 user-owned endpoint 都要有「非擁有者回 404」的測試(ownership 規則見 [`ai-docs/security.md`](../../ai-docs/security.md))。

---

## ArchUnit 架構規則

ArchUnit 測試放在 `stock-start`(L3)。它的測試 classpath 涵蓋所有模組,所以可以一次掃描整個專案的正式程式碼(排除測試類別)。兩支測試都跟 `./mvnw test` 一起跑。

**`ArchitectureRulesTest`**(`stock-start/src/test/java/dowob/xyz/stockwebv2/start/ArchitectureRulesTest.java`):

| `testing-standards.md` 規則 | ArchUnit 規則 | 內容 |
|---|---|---|
| 1. 模組隔離 | `l2ModulesMustNotDependOnEachOther` | `user`、`asset`、`trading`、`backtest`、`marketdata` 這些 L2 套件之間不得互相依賴 |
| 2. Facade 邊界 | `facadeInterfacesMustLiveInInfrastructure` | 名稱以 `Facade` 結尾的介面必須放在 `dowob.xyz.stockwebv2.infrastructure..` |
| 3. DDD 分層 | `controllersMustNotDependOnRepositories` | `@RestController` 不得依賴本專案的 `..repository..` 套件 |
| 5. Controller → Facade | `controllersMustNotDependOnFacades` | `@RestController` 不得直接依賴 `*Facade`,必須經過 Application Service |

規則 4(Ownership)沒有用 ArchUnit 實作,改由各 endpoint 的「非擁有者回 404」測試保證。原因寫在 `ArchitectureRulesTest` 的 Javadoc。

**`EndpointAuthorizationRulesTest`**:每個帶 `@GetMapping` / `@PostMapping` / `@PutMapping` / `@DeleteMapping` / `@PatchMapping` / `@RequestMapping` 的 controller 方法,都必須宣告 `@PreAuthorize`,不然就要列在 `ALLOWED_WITHOUT_PRE_AUTHORIZE` 白名單裡並附上理由。白名單裡的方法如果已經不存在,測試也會失敗,避免白名單堆積過期項目。新增端點時:

- 需要授權的端點:加上 `@PreAuthorize`(例如 `hasAuthority('TRADE_EXECUTE')`)。
- 刻意公開的端點:在白名單加一筆 `類別簡名#方法名` 並寫明理由。

---

## 覆蓋率要求

**沒有設定覆蓋率門檻。** JaCoCo(根 `pom.xml`)只做 `prepare-agent`,並在 `verify` 階段產生報告,沒有 `check` 門檻。這是刻意的決定:現在就設門檻,只會逼人寫湊數的測試。

| Type | Threshold |
|------|-----------|
| Lines | 未設定 |
| Branches | 未設定 |
| Functions / Methods | 未設定 |
| Statements / Instructions | 未設定 |

實際要求是「測試要涵蓋功能」(`testing-standards.md`),加上每個 user-owned endpoint 都要有非擁有者回 404 的測試。

---

## CI 整合

Workflow:`.github/workflows/ci.yml`(名稱 `CI`)。

**觸發條件**:任何分支的 `push`、對 `main` / `develop` 的 `pull_request`,以及手動 `workflow_dispatch`。同一個 ref 有新的執行時會取消舊的(`concurrency` + `cancel-in-progress`)。

| Job | 名稱 | 依賴 | Timeout | 命令 | 報告 |
|-----|------|------|---------|------|------|
| `lint` | Lint | — | 10 分 | `./mvnw -B spotless:check` | — |
| `unit` | Unit Tests | — | 15 分 | `./mvnw -B test --fail-at-end` | `**/target/surefire-reports/` |
| `integration` | Integration Tests | `unit` | 25 分 | `./mvnw -B -pl stock-start -am verify -Dspring-boot.repackage.skip=true --fail-at-end` | `stock-start/target/failsafe-reports/`(另上傳各模組 surefire 報告) |
| `e2e` | E2E Tests | `integration` | 25 分 | `./mvnw -B -pl stock-start -am test -Pe2e` | `stock-start/target/surefire-reports/` |
| `browser-e2e` | Browser E2E Tests | `e2e` | 30 分 | 前端 repo 的 `bash e2e/run-e2e.sh`(`@smoke`) | `browser-e2e-artifacts` |

- 所有 Maven job 都用 Temurin JDK 21,並啟用 Maven cache。
- `lint` 不被其他 job 依賴,存在的目的是在較長的測試 job 跑完前先回報格式問題。Spotless 本來就綁在 `validate` 階段,所以其他 Maven job 也會檢查格式。
- 測試結果透過 `dorny/test-reporter` 發布成 check run(Unit Tests / Integration Tests / E2E Tests),原始報告以 artifact 保留 7 天。

### Browser E2E job

`browser-e2e` 成本較高(compose 基礎設施 + 建置後端 + Playwright),所以**只在以下情況執行**:

```yaml
if: ${{ github.event_name == 'pull_request' || github.ref == 'refs/heads/develop' }}
```

也就是對 `main` / `develop` 的 PR,以及 `develop` 的 push。一般 feature 分支的 push 不會觸發。

執行步驟:

1. 把後端 checkout 到 `backend/`。
2. **決定前端 ref**:候選分支是 `github.head_ref`(PR 的來源分支),沒有的話用 `github.ref_name`(push 的分支)。用 `git ls-remote --heads` 檢查前端 repo `tommot20077/stock-web-v2-front-end` 有沒有**同名分支**:有就用它,沒有就 fallback 到 `develop`。這樣跨 repo 的功能在前端 harness 還沒 merge 進 `develop` 時也能跑 E2E。做跨 repo 變更時,前後端分支請用同一個名稱。
3. 把前端 checkout 到 `frontend/`。前端是 public repo,用預設的 `GITHUB_TOKEN` 就能讀取,不需要 PAT。
4. 安裝 JDK 21 與 Node.js 20(npm cache 以 `frontend/vue-app/package-lock.json` 為 key),在 `frontend/vue-app` 執行 `npm ci`,再跑 `npx playwright install --with-deps chromium`。
5. 設定 `E2E_BACKEND_DIR=${{ github.workspace }}/backend`,在 `frontend/vue-app` 執行 `bash e2e/run-e2e.sh`。沒帶參數,所以**跑全部 tag**(`@smoke` 與 `@extended`,D-14)。step 名稱為 `Run Browser E2E (all tags)`。
6. 不論成功或失敗,都把 `frontend/vue-app/e2e/artifacts/`(後端 log、Playwright HTML report、trace / screenshot / video)上傳為 `browser-e2e-artifacts`,保留 7 天。

### 前端 repo 自己的 CI

前端 repo 有獨立的 `.github/workflows/`(Node 20,`frontend` job:`npm ci → lint → test → build`)。`frontend` 通過後,`browser-e2e` job 會 checkout 與前端分支同名的後端分支(不存在時用 `develop`;後端是 public repo,用預設 `github.token`),同樣執行 `bash e2e/run-e2e.sh` 跑全部 tag,並上傳 `browser-e2e-artifacts`(D-15)。前端的 Vitest 與 Playwright 慣例見前端 repo 的 `AGENTS.md`(「驗證命令」)與 `vue-app/e2e/README.md`。跨 repo 變更時,兩邊的驗證都要跑過,見 [`ai-docs/judgment.md`](../../ai-docs/judgment.md) §8。

---

## 延伸閱讀

- [`ai-docs/testing-standards.md`](../../ai-docs/testing-standards.md):測試規範(權威)
- [`ai-docs/security.md`](../../ai-docs/security.md):ownership 與安全規則
- [`docs/architecture/overview.md`](../architecture/overview.md):模組邊界與 Facade 模式
- [`docs/configuration/overview.md`](../configuration/overview.md):`test`、`e2e`、`e2e-browser` profile
- [`docs/plans/2026-07-16-browser-e2e-testing-design.md`](../plans/2026-07-16-browser-e2e-testing-design.md):Browser E2E 設計
