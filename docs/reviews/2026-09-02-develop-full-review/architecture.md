# develop 全面審查 — 架構維度

- 後端:`origin/develop` @ 334cb34(PR #20 merge 後);前端:`feature/phase-04-manual-trade-creation` @ fde9968(等同即將合併的 develop)
- 方法:read-only 親讀 + grep 實查;規範依 `ai-docs/architecture.md`、`code-standards.md`、`testing-standards.md`、`event-conventions.md`、`redis-convention.md`、`flyway-convention.md`、前端 `AGENTS.md`
- 日期:2026-09-02

## 評分:**B-**

邊界在「Java import 層」守得很好(ArchUnit 4 條規則 + 實查零違規),但 `architecture.md` 描述的資料流(Kafka → trading → Redis 預計算)沒有實作、trading 以 SQL 直接讀 asset 模組的表、infrastructure 留著四個零實作的抽象、composition root 承載了本該在 L1 的安全過濾器。文件與程式的落差是主要扣分點。

---

## 1. 模組邊界

### 跨模組 import 矩陣(main sources,`grep import dowob.xyz.stockwebv2.*`)

| 模組 | common | infrastructure | 其他 L2 | 結論 |
|------|--------|----------------|---------|------|
| user | 23 | 10 | 0 | ✓ |
| asset | 10 | 3 | 0 | ✓ |
| backtest | 11 | 3 | 0 | ✓ |
| market-data | 36 | 24 | 0 | ✓ |
| trading | 24 | 5 | 0 | ✓ |
| infrastructure | 5 | — | 0 | ✓ |
| common | — | 0 | 0 | ✓ |
| stock-start | 11 | 8 | **user ×2** | ⚠ 見 M-4 |

ArchUnit(`stock-start/src/test/java/dowob/xyz/stockwebv2/start/ArchitectureRulesTest.java:44-78`)四條規則:L2 slices 互不相依、Facade 介面須在 infrastructure、Controller 不依賴 repository、Controller 不依賴 Facade。全數存在且 CI 綠。2026-07-14 審查的 C3(0 條 ArchUnit)與 H6(AuthController 直呼 Repository)已解。

### 跨模組 SQL 存取(ArchUnit 抓不到的層)

| 模組 | 觸及的表 | 擁有者 |
|------|----------|--------|
| trading | `holdings`、`transactions` | trading ✓ |
| trading | **`assets`**(`JdbcTradingRepository.java:123`、`:205`、`:292` 的 `join assets a`)、**`asset_latest_prices`**(`:317`) | asset /(無人寫入) |
| market-data | `market_prices` | market-data ✓ |
| asset | `assets`、`asset_latest_prices` | asset ✓ |

**H-1(HIGH)** `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/repository/JdbcTradingRepository.java:123,205,292,317` — trading 以 SQL JOIN / SELECT 直接讀 asset 模組的 `assets` 與 `asset_latest_prices`,違反 architecture.md「Modules interact ONLY via Service Interfaces … never direct Repository/SQL access」。`:231` 的 Javadoc 說明這是為了 SELECT `a.symbol` 的取捨,但更嚴重的是:**`asset_latest_prices` 全 repo 只有 `V1` 建表與 `V2` seed 寫入,沒有任何 Java 程式或 SQL 更新它**(`grep -ri asset_latest_prices` 於 `stock-*/src/main` 與 migration,寫入只有 `V1__foundation_schema.sql:36`、`V2__foundation_seed_assets.sql:24`)。market-data 寫的是 `market_prices` 與 Redis `market:latest:`。結果:持倉估值(`findLatestPrice`)永遠讀 seed 價,與 architecture.md「ROI/portfolio updated in real-time via Kafka events → Redis」的資料流完全脫節。修法:新增 `MarketDataFacade.latestPrice(assetId)`(或直接讀 `market:latest:` cache)與 `AssetFacade.findByIds(Set<Long>)` 批次方法,trading 的 SQL 只留自己的兩張表;另補一條「模組 SQL 不得出現他模組表名」的 grep 型測試。

## 2. 分層

- Controller → Service → Repository:實查 11 個 `@RestController`,零個 import Facade、零個 import repository ✓(ArchUnit 規則 3、5)。
- Facade 呼叫次數:`TradingService.java:378`、`BacktestService.java:67` 每請求各 1 次 ✓(≤ 3)。
- **M-3(MEDIUM)** `authenticatedUserId(Authentication)` 逐字重複 4 份:`BacktestController.java:71`、`TradingController.java:145`、`AuthController.java:186`、`WsTicketController.java:99`(名為 `resolveUserId`)。搬到 `stock-infrastructure/web`(與 `ClientIpResolver` 同層)。
- **M-4(MEDIUM)** `stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java`(369 行)把 `JwtAuthenticationFilter`(`:137`)、`BrowserCsrfFilter`(`:295`)、`ApiSecurityErrorWriter`(`:345`)寫成內部類,並 import L2 的 `user.service.BrowserAuthCookieService`(`:11`);`StockWebV2Application.java:5` 也 import `user.service.BrowserAuthCookieProperties`。安全過濾器是橫切的基礎設施,應在 `stock-infrastructure/security`(那裡已有 `JwtService`、`RateLimitService`),composition root 只做 `@Bean` 組裝。現況讓 L3 直接依賴 L2 實作類,也讓過濾器無法在模組層獨立測試。
- **L-3(LOW)** Domain 為 record + 純函式(`Holding`、`TradeTransaction` 為 0 方法 record;邏輯在 `HoldingCalculator`、`TradePayloadMatcher`、`TradingService`),是 transaction script,不是 architecture.md 宣稱的「Rich Domain Models / Aggregate Roots」。對 Spring Data JDBC 而言可接受,但文件要改。
- **L-1(LOW)** `marketdata/api/` 混放 Controller、DTO 與 service(`KlineQueryService`、`MarketLatestService`、`BackfillIdempotencyService`),其他模組皆 `api/` 與 `service/` 分開。

## 3. 事件與 Kafka

- **M-1(MEDIUM)** 零實作的抽象:`stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/event/EventPublisher.java`、`EventSubscriber.java`、`DomainEvent.java`、`infrastructure/search/SearchService.java` 沒有任何實作類也沒有任何呼叫者(`grep -rln` 於全部 main sources 只命中定義檔)。market-data 直接注入 `KafkaTemplate`(`marketdata/ingest/MarketDataIngestService.java:24-31`)。architecture.md 與 event-conventions.md 描述的 `SpringEventPublisher` / `KafkaEventPublisher @Profile("kafka")` 切換不存在。二擇一:實作,或刪除並改文件(`archive/fullstack-review-q5nvfj` 的 `8ec6cdc` 已做過刪除版本)。
- **M-2(MEDIUM)** `stock-common/src/main/java/dowob/xyz/stockwebv2/common/event/PriceTickEvent.java` 未實作 `DomainEvent`、沒有 `version` 欄位(event-conventions「Every event class must include a version field」),命名不符 `{Domain}{Action}Event`;版本改由 topic 名 `market.price.tick.v1` 承載(`KafkaConfig.TOPIC_PRICE_TICK`)。要嘛改文件承認「topic 版本化」,要嘛補 `version`。
- Consumer 端做對的:batch listener + manual ack、`DefaultErrorHandler` + `FixedBackOff` 3 次 + `DeadLetterPublishingRecoverer`(`marketdata/config/KafkaConfig.java:128-165`)、寫入 `ON CONFLICT` 冪等(`consumer/PriceWriterConsumer.java:19`)、producer `acks=all` + `enable.idempotence`(`application.yaml`)✓。
- 沒有任何 `@TransactionalEventListener(AFTER_COMMIT)`;交易建立後也沒有事件發出(`TradeExecutedEvent` 只存在於文件範例)。

## 4. 一致性與重複

- 錯誤處理路徑單一:`GlobalExceptionHandler`(stock-start)8 個 handler(`:38-134`),`ErrorCode.httpStatus` 驅動狀態碼,`ResourceNotFoundException` / `DuplicateResourceException` 經 `BusinessException` 路徑 ✓。但它住在 L3,L2 模組的 `@WebMvcTest` 切片測不到信封(PR #20 review 已指出)。建議把 `GlobalExceptionHandler` 移到 `stock-infrastructure/web` 讓切片測試可 `@Import`。
- **L-4(LOW)** code-standards.md 引用的 `SecurityUtils.assertOwnerOrAdmin`、`SystemException`、`LikeEscapeUtil` 在程式碼裡都不存在(`grep` 零命中)——文件描述的是未實作的設計。
- **M-7(MEDIUM)** 數值 wire 格式不一致:`TradeDto` / `HoldingDto` / `PortfolioSummaryDto` 的 `BigDecimal` 走 Jackson 預設 → JSON number(全 repo 無 `WRITE_BIGDECIMAL_AS_PLAIN` 或全域 serializer);`marketdata/api/KlineDto.java` 與 `PriceTickEvent` 用 `ToStringSerializer` → string。前端 `vue-app/src/services/apiTypes.ts:81-83` 把 quantity/price/fee 型別為 `number`。`@Digits(integer=10, fraction=8)` 允許 18 位有效數字,超過 IEEE double 的 15–17 位。契約應統一(以 klines 的 string 為準)。
- 稽核:`auditLogger.log` 只在 AuthController(7)、TradingController(2)、WsTicketController(1);Backtest、Backfill 寫入端點無稽核 — 歸安全維度,此處僅標一致性。
- DTO 逐欄比對(`TradeDto` / `HoldingDto` / `PortfolioSummaryDto` / `AssetDto` / `KlineDto` vs `apiTypes.ts`):欄位名與數量完全一致 ✓。

## 5. 設定與 profile

- **M-9(MEDIUM)** `stock-start/src/main/resources/application-demo.yaml` 幾乎整份複製 `application.yaml`(application name、jackson tz、server/management port、springdoc、cors、jwt),只有 datasource/redis 是 demo 專屬;任何預設值改動都要改兩處。應只留差異。
- `spring.profiles.default: dev` 而 dev 的 datasource 全是無預設值的 `${STOCK_DB_URL}` → 沒有 `.env` 的乾淨機器 `java -jar` 直接失敗;可接受但要在 README 明說。
- **M-5(MEDIUM)** 「market-data 可獨立部署」:全 repo 只有 `stock-start` 一個 `@SpringBootApplication`,`stock-module-market-data/pom.xml` 無 `spring-boot-maven-plugin` / `mainClass`;只有 `SchedulingConfig`、`ScheduledIngestor`、`MockDataProvider`、`MockOnlyProviderRegistry` 四個 `@ConditionalOnProperty` 開關。這是「可關閉」不是「可獨立部署」,文件要改。
- **M-6(MEDIUM)** Redis key 命名與 redis-convention.md 漂移:`trading/service/PortfolioCache.java:18-19` 用 `portfolio:valuation:` / `portfolio:summary:`(文件:`cache:portfolio:{userId}:{assetId}`、`cache:dashboard:{userId}`);`market:latest:`(文件:`cache:market:latest:`);`rl:`、`ws:ticket:`、`market:backfill:idem:`、`user:refresh:used:` 文件未列。`application-e2e-browser.yaml:21` Redis `database: 0`(文件:所有 profile 必須 1)。
- flyway:所有 migration 只在 `stock-db-migration`,其他模組 `src/main/resources` 為空 ✓。

## 6. 測試架構

| 模組 | unit | web | IT | E2E |
|------|------|-----|----|-----|
| common | 5 | 0 | 0 | 0 |
| infrastructure | 4 | 0 | 0 | 0 |
| user | **1** | 0 | 0 | 0 |
| asset | **0** | 0 | 1 | 0 |
| backtest | 3 | 0 | 0 | 0 |
| market-data | 25 | 3 | 11 | 0 |
| trading | 6 | 2 | 0 | 0 |
| start | 10 | 0 | 21 | 6 |

- **M-8(MEDIUM)** market-data 的 12 個 IT 各自宣告容器(`persistence/MarketPriceRepositoryIT.java:43`、`batch/BackfillJobIT.java:107-115`、`consumer/PriceWriterConsumerIT.java:46`、`ws/WsTicketServiceIT.java:40` …),沒有共用基底、沒有 `withReuse(true)`,與 testing-standards「singleton + withReuse」相反;這是 `stock-module-market-data` 單測 3.5 分鐘的主因之一(性能維度會細看)。`stock-start/src/test/java/dowob/xyz/stockwebv2/start/support/ContainerIT.java` 有共用基底但只服務 start 模組。
- user 模組只有 1 個 unit 測試檔:JWT / cookie / refresh / lockout 的邏輯幾乎全靠 stock-start 的 21 個 IT 覆蓋 → 慢且模組無法獨立驗證。asset 模組 0 unit。backtest 44 個 `@Test` 0 個 `@DisplayName`(其他模組 90%+)。
- 測試方法名零中文 ✓;`@DisplayName` 全 repo 422/524。
- **L-6(LOW)** 前端 6 個測試檔共 14 處 `?raw` 原始碼字面斷言(`vue-app/src/pages/Trades.test.ts:6` 等)——鎖寫法不鎖行為,今天已因註解文字誤觸兩次。
- **L-7(LOW)** `stock-start/src/main/java/dowob/xyz/stockwebv2/start/support/TestOnlyController.java:13` 以 `@Profile("test")` 放在 main sources;應移到 test sources 的 `@TestConfiguration`。
- **L-5(LOW)** ArchUnit 規則編號跳號(1、2、3、5);缺「模組 SQL 不得出現他模組表名」與「`@RestControllerAdvice` 只能在 infrastructure/start」兩條。

## 7. 前端架構

- Transport 邊界:`grep 'fetch('` 於 src(非測試)只命中 `services/apiClient.ts` ✓(鐵律 1)。`localStorage` 只在 `useTweaks.ts`(UI 偏好),無 token ✓。
- 七個 service 三件組(`createHttpXxx` / `createMockXxx` / `createXxx`)命名一致,`services/pageApiClients.ts:31-37` 集中註冊 ✓。
- `apiTypes.ts` 與後端五個核心 DTO 逐欄一致 ✓(見 §4)。
- **M-10(MEDIUM)** `vue-app/src/components/OrderTicket.vue` **1612 行**(單檔含三步驟 UI、typeahead 七態、報價/走勢、key 生命週期、D-16 分派、SELL 預檢);`pages/Positions.vue` 1042 行。建議抽 `useSymbolTypeahead`、`useQuoteAndKlines`、`useTradeSubmission`(含 idempotency key 生命週期與錯誤分派)三個 composable,再拆 `TicketStep` / `ReviewStep` / `ResultStep` 子元件。
- **M-11(MEDIUM)** API-mode 畫面仍 import mock 資料模組 `data.ts`:`pages/Overview.vue`(SYMBOLS、CRYPTO、NEWS、genSeries)、`components/CmdK.vue`(SYMBOLS、CRYPTO)、`components/Header.vue`(NOTIFS);`fmtNum` / `fmtPct` 兩個格式化函式也住在 `data.ts`,讓 `Positions.vue` / `Trades.vue` / `OrderTicket.vue` 不得不 import mock 模組。把 formatter 搬到 `utils/format.ts`,mock 固定資料只允許 mock adapter import。
- **L-8(LOW)** mock-only 頁(Alerts / Chart / Markets / Notifications / Settings / Watchlist)直接 import Pinia store —— PROJECT.md 明列 out of scope,`todos/pending/2026-07-26-frontend-watchlist-mock-store.md` 已追蹤。
- i18n 單檔 385 行、352 個 key、zh/en 兩個平面物件;目前可維護,超過 ~600 key 時應按頁面拆檔。

## 8. 技術債清單(優先順序)

| # | 項目 | 來源 | 優先 |
|---|------|------|------|
| 1 | trading 讀死表 `asset_latest_prices` + SQL JOIN `assets`(H-1) | 本次 | P0 |
| 2 | 四個零實作抽象(EventPublisher / EventSubscriber / DomainEvent / SearchService)+ 文件描述的 profile 切換不存在(M-1) | 本次;`archive/fullstack-review-q5nvfj@8ec6cdc` 有刪除版 | P1 |
| 3 | SecurityConfig 369 行含三個安全過濾器 + 依賴 L2 user 模組(M-4) | 本次 | P1 |
| 4 | 數值 wire 格式 number / string 混用(M-7) | 本次 | P1 |
| 5 | market-data IT 容器不共用(M-8) | 本次 | P1 |
| 6 | 後端資料缺口四條(可用現金、日級損益、資產分類、watchlist) | `todos/pending/2026-07-19-*`、ROADMAP Phase 04.1 | P1(已排程) |
| 7 | Spotless + JaCoCo + CI lint(後端)、ESLint / Prettier(前端) | `todos/pending/2026-07-26-spotless-jacoco-ci-lint.md`;`archive/fullstack-review-q5nvfj@6473bff`、前端 `origin/claude/fullstack-review-architecture-q5nvfj@dda7f35` | P2 |
| 8 | Redis key 命名與文件對齊(M-6)、`application-demo.yaml` 去重(M-9) | 本次 | P2 |
| 9 | `authenticatedUserId` ×4 去重(M-3)、`GlobalExceptionHandler` 下移到 infrastructure | 本次 | P2 |
| 10 | OrderTicket.vue 拆分(M-10)、formatter 搬離 data.ts(M-11) | 本次 | P2 |
| 11 | JwtService 環境遷移(EC JWK 設定) | `todos/pending/2026-07-26-jwtservice-handrolled-ec-arithmetic.md`(程式碼已在 PR #21 完成) | P2 |
| 12 | DIV 股利交易類型 | `todos/pending/2026-07-19-backend-dividend-trade-type.md` | P3 |
| 13 | 文件漂移:architecture.md(Rich Domain / 獨立部署 / 資料流)、code-standards.md(不存在的 SecurityUtils / SystemException / LikeEscapeUtil)、event-conventions.md、redis-convention.md | 本次 | P2 |
| 14 | `?raw` 字面測試 ×14、TestOnlyController 在 main、ArchUnit 缺兩條 | 本次 | P3 |

## 9. 前三名最值得先做的重構

1. **把持倉估值接到真正的價格來源**:新增 `MarketDataFacade`(或讀 `market:latest:` cache)+ `AssetFacade.findByIds`,trading 的 SQL 只碰 `holdings` / `transactions`;`asset_latest_prices` 標記廢棄;加一條「模組 SQL 不得含他模組表名」的測試。這條同時修掉功能上「估值永遠是 seed 價」的問題。
2. **安全過濾器下移到 L1**:`JwtAuthenticationFilter`、`BrowserCsrfFilter`、`ApiSecurityErrorWriter` 與 cookie 屬性 / CSRF 契約搬進 `stock-infrastructure/security`,`SecurityConfig` 只剩 `@Bean` 組裝;`authenticatedUserId` 併入 `infrastructure/web`;`GlobalExceptionHandler` 同步下移讓 `@WebMvcTest` 能驗信封。
3. **對事件抽象做決定並讓文件說實話**:刪除四個零實作介面(沿用 archive 分支的做法)並改寫 architecture.md / event-conventions.md 為「market-data 直接用 KafkaTemplate,topic 名承載版本」;或補 `PriceTickEvent implements DomainEvent` + `version`。順手把 redis-convention.md 的 key 表改成程式碼實際值。

## 殘留風險 / 未確認

- `asset_latest_prices` 是否有 DB 端 trigger / cron 在 migration 之外更新(例如 k3s 的 job)——repo 內查無,但部署環境未查。
- 各模組 IT 的實際耗時分布未量測(未跑測試),M-8 的「主因之一」是依檔案數推斷。
- 前端 `pages/Analytics.vue:16` 的 `v-html`(靜態 icon)屬安全維度,本報告未評。
- 稽核覆蓋缺口(Backtest / Backfill 無 audit)交由安全維度定嚴重度。
