---
phase: 05-cross-repo-browser-flow-verification-contract-hardening
plan: 08
subsystem: testing
tags: [kafka, testcontainers, failsafe, market-data, integration-test, placeholder]

requires:
  - "05-07:MarketDataContainers(kafka / postgres / redis、freshDatabase、ensureTopics、uniqueName)"
provides:
  - "KafkaConfig topic / group property placeholder(預設值逐字等於原常數)"
  - "MarketDataIngestService 3 參數建構子(KafkaTemplate, tickTopic, backfillTopic)"
  - "KafkaTopicPlaceholderTest 預設值守門(T-05-15)"
  - "market-data 11 個 IT 全部共用三個容器;綁 Kafka 的 3 個 IT 以唯一 topic / group 隔離"
  - "market-data failsafe runOrder=random"
affects: [05-12]

actuals:
  tokens: 13500    # chars/4 over realized diff (53969 chars)
  tasks: 3
  commits: 3
plan_head_before: ecb00d910e86781e3c0b5e62bd195a4db256f8a2
plan_head_after: 59500cc34a1242c8f996675c8f047c94d882e155

tech-stack:
  added: []
  patterns:
    - "正式 topic / group 以編譯期常數拼成 ${property:預設值} placeholder,同時供 @KafkaListener 與 @Value 使用"
    - "Spring IT 以 @DynamicPropertySource 注入唯一 topic / group;唯一名稱固定在 static 欄位,避免 supplier 重複求值換名"
    - "IT 讀 Kafka 時依預期 key 過濾,不取第一筆"

key-files:
  created:
    - stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/config/KafkaTopicPlaceholderTest.java
  modified:
    - stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/config/KafkaConfig.java
    - stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/ingest/MarketDataIngestService.java
    - stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/consumer/PriceWriterConsumer.java
    - stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/consumer/WsBroadcastConsumer.java
    - stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/config/KafkaTopicRetentionTest.java
    - stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/ingest/MarketDataIngestServiceIT.java
    - stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/consumer/PriceWriterRetryIT.java
    - stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/batch/BackfillJobIT.java
    - stock-module-market-data/pom.xml

key-decisions:
  - "採 plan 的最小正式變更(placeholder + 預設值不變),未改用「正式 topic 共用 + 依 key 過濾」替代方案"
  - "DLT topic bean 與 kafkaErrorHandler resolver 共用私有常數 DLT_SUFFIX(\".DLT\"),topic 被覆寫時 DLT 跟著變;TOPIC_*_DLT 公開常數保留為預設名稱文件"
  - "BackfillJobIT 的唯一 topic / group / DB URL 全放在 static final 欄位:freshDatabase 會 DROP 後 CREATE,不能放在可能被重複呼叫的 supplier 裡"
  - "Redis db index 分配:BackfillJobIT=3(1=WsTicketServiceIT、2=BackfillIdempotencyServiceIT、0 留給預設)"
  - "runOrder=random 放在 failsafe plugin 層,只作用於 market-data;stock-start IT 不在 D-04 範圍"

patterns-established:
  - "新 Spring IT 若會啟動 market-data 的 @KafkaListener,必須設定 KafkaConfig.PROP_TOPIC_* 與 PROP_GROUP_* 為 uniqueName"

requirements-completed: [VER-01]

coverage:
  - id: D1
    description: "topic / group placeholder,未設 property 時預設值逐字等於原常數"
    requirement: "VER-01"
    verification:
      - kind: unit
        ref: "KafkaTopicPlaceholderTest(6 tests)+ KafkaAckModeTest、ScheduledIngestorTest、KafkaBackfillItemWriterTest、KafkaTopicRetentionTest;market-data 全部單元測試 228/228"
        status: pass
    human_judgment: false
  - id: D2
    description: "3 個綁 Kafka 的 IT 改用共用容器 + 唯一 topic / group(BackfillJobIT 另有獨立 DB、Redis db 3)"
    requirement: "VER-01"
    verification:
      - kind: other
        ref: "./mvnw -B -q -pl stock-module-market-data -am test-compile;grep 無 @Testcontainers / ConfluentKafkaContainer( 的 *IT.java = 0"
        status: pass
      - kind: integration
        ref: "CI Integration job failsafe 報告(05-12 收集)"
        status: unknown
    human_judgment: true
    rationale: "本機無 Docker,IT 執行證據只能在 CI 取得"
  - id: D3
    description: "market-data failsafe 亂序執行"
    requirement: "VER-01"
    verification:
      - kind: other
        ref: "effective-pom 中 <runOrder>random</runOrder> = 2;本機 failsafe log 出現 'Tests will run in random order. To reproduce ordering use flag -Dfailsafe.runOrder.random.seed=2449168536723'"
        status: pass
      - kind: integration
        ref: "CI 中 11 個 market-data IT 亂序全綠(05-12 收集)"
        status: unknown
    human_judgment: true
    rationale: "亂序設定已生效,但「11 個 IT 亂序下全綠」需 CI 的 Docker 環境"

duration: 8min
completed: 2026-11-09
status: complete
---

# Phase 05 Plan 08: Kafka topic / group 可設定 + 剩餘 3 個 Kafka IT 共用容器 + failsafe 亂序 Summary

**正式 Kafka topic / group 改為 `${market-data.kafka.*:原常數}` placeholder(預設行為不變,由單元測試鎖住);MarketDataIngestServiceIT、PriceWriterRetryIT、BackfillJobIT 改用共用容器並以唯一 topic / group 隔離;market-data failsafe 改為亂序執行**

## Performance

- **Duration:** 約 8 min(15:11 → 15:19 UTC)
- **Started:** 2026-11-09T15:11Z(約)
- **Completed:** 2026-11-09T15:19Z
- **Tasks:** 3
- **Files modified:** 10(1 新增、9 修改)

## Accomplishments

- `KafkaConfig` 新增 `PROP_TOPIC_PRICE_TICK` / `PROP_TOPIC_PRICE_BACKFILL` / `PROP_GROUP_PERSIST` / `PROP_GROUP_WS_BROADCAST`、`GROUP_PERSIST` / `GROUP_WS_BROADCAST`,以及四個編譯期 `*_PLACEHOLDER`。四個 `NewTopic` bean 以 `@Value(placeholder) String topic` 命名,DLT = topic + `.DLT`。
- `PriceWriterConsumer` / `WsBroadcastConsumer` 的 `@KafkaListener` 的 `topics` / `groupId` 改用 placeholder;`containerFactory` 不變(`KafkaAckModeTest` 仍綠)。
- `MarketDataIngestService`:新增 `@Autowired` 3 參數建構子;保留 1 參數建構子,改為委派給正式常數(`ScheduledIngestorTest`、`KafkaBackfillItemWriterTest` 不用改就綠;stock-start `WsSubscribeReceiveE2ETest` 用 autowire,test-compile 綠)。
- `KafkaTopicPlaceholderTest`(6 tests):用反射讀 listener annotation 的 placeholder 字面值,並確認預設值等於常數;`StandardEnvironment` 解析得到正式名稱,`MockEnvironment` 設定 property 後得到覆寫值;NewTopic bean 的名稱與 DLT 規則;ingest service 兩個建構子各自送到正確的 topic。
- `MarketDataIngestServiceIT`:改用共用 Kafka,topic 用 `uniqueName(...)`,consumer group 用 `uniqueName("ingest-it")`;`pollOne(consumer, expectedKey)` 只接受 key 相符的那筆,逾時 10 秒。
- `PriceWriterRetryIT`:改用共用 Kafka,`TICK_TOPIC = uniqueName(TOPIC_PRICE_TICK)`、DLT = `TICK_TOPIC + ".DLT"`,兩者用 `ensureTopics` 建立;listener group 用 `uniqueName("persist-retry-it")`,DLT 驗證 group 用 `uniqueName("dlt-verify")`。
- `BackfillJobIT`:刪掉自有的三個容器與 `Startables.deepStart`,改用共用容器;database 用 `freshDatabase("backfill_job")`(即 `md_backfill_job`)、Redis db index 3;以 `PROP_TOPIC_*` / `PROP_GROUP_*` 注入唯一名稱。斷言不變(`findRange(1L, …).hasSize(1440)`、`onTick` never)。D-29 的決策寫在類別 Javadoc。
- `stock-module-market-data/pom.xml`:failsafe 在 plugin 層設定 `<runOrder>random</runOrder>`,並加上 D-29 註解。

## Task Commits

1. **Task 1(tracer):placeholder + 單元守門 + ingest IT** — `4a2ae1e` (refactor)
2. **Task 2:PriceWriterRetryIT 與 BackfillJobIT 改用共用容器** — `3ed5692` (test)
3. **Task 3:failsafe 亂序** — `59500cc` (test)

## Verification Evidence

- TDD RED:先寫 `KafkaTopicPlaceholderTest` 再跑 → `COMPILATION ERROR … cannot find symbol`(`TOPIC_PRICE_TICK_PLACEHOLDER` 等常數還不存在)。
- Task 1 automated:`./mvnw -B -pl stock-module-market-data -am test -Dtest='KafkaTopicPlaceholderTest,KafkaAckModeTest,ScheduledIngestorTest,KafkaBackfillItemWriterTest,KafkaTopicRetentionTest'` → `Tests run: 25, Failures: 0, Errors: 0` / `BUILD SUCCESS`。其中 KafkaTopicPlaceholderTest 對應 display name「Kafka topic / group placeholder 預設值不變」那行,6 tests。
- market-data 全部單元測試:`./mvnw -B -pl stock-module-market-data -am test` → `Tests run: 228, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`。
- `./mvnw -B -q -pl stock-start -am test-compile` → 無錯誤輸出(綠)。
- Task 2 automated:`./mvnw -B -q -pl stock-module-market-data -am test-compile` rc=0;自己宣告容器的 `*IT.java`(`@Testcontainers` 或 `ConfluentKafkaContainer(`)數量 = 0;market-data `*IT.java` 共 11 個。
- Task 3 automated:effective-pom 中 `<runOrder>random</runOrder>` = 2(plugin 層 + execution 繼承);`pom.xml` 中 = 1。
- 本機 failsafe 實跑(以免 Docker 的 `-Dit.test=KafkaTopicPlaceholderTest`):log 出現 `Tests will run in random order. To reproduce ordering use flag -Dfailsafe.runOrder.random.seed=2449168536723`,`BUILD SUCCESS`。
- `./mvnw -B spotless:check` → `BUILD SUCCESS`(三個 task 各跑一次)。
- Acceptance greps:`market-data.kafka.topics.price-tick` in KafkaConfig = 1;listener 直接用常數 = 0(baseline 2);MarketDataIngestServiceIT `@Testcontainers` = 0;BackfillJobIT `PROP_TOPIC_PRICE_TICK` = 2、`spring.data.redis.database` = 1;PriceWriterRetryIT `uniqueName` = 4。
- 掃描 10 個變更檔:隱形字元(Cf / Cc / 非 ASCII 空白)= 0。

## CI 證據待 05-12 收集(本機無 Docker)

本機 `docker info` 失敗,IT 無法執行。05-12 需從 CI **Integration job**(`./mvnw -B -pl stock-start -am verify`,會跑 market-data 的 failsafe)收集:

1. **run id**,以及 market-data failsafe 段落中的 `Tests will run in random order. To reproduce ordering use flag -Dfailsafe.runOrder.random.seed=<seed>` 這一行(記下 seed)。
2. `stock-module-market-data/target/failsafe-reports/` 下 **11 份**報告皆 `failures=0, errors=0`(skipped=0):
   - 05-07 的 8 份:`persistence.MarketPriceRepositoryIT`、`consumer.PriceWriterConsumerIT`、`api.MarketControllerKlinesIT`、`migration.MarketDataMigrationIT`、`api.BackfillIdempotencyServiceIT`、`ws.WsTicketServiceIT`、`observability.MarketDataHealthIndicatorIT`、`config.KafkaConfigIT`
   - 本 plan 的 3 份:`TEST-dowob.xyz.stockwebv2.marketdata.ingest.MarketDataIngestServiceIT.xml`、`...consumer.PriceWriterRetryIT.xml`、`...batch.BackfillJobIT.xml`
3. 同一 JVM 內 timescale / redis / cp-kafka 容器**各只啟動一次**(log 中各映像的 `Container ... started` 各 1 次)。
4. D-29 亂序驗證:至少一次 CI run 有 random seed 行且全綠。若要加強證據,可再跑 `./mvnw -B -pl stock-module-market-data -am verify`(runOrder 已寫進 pom,不需 `-Dfailsafe.runOrder=random`),兩次 seed 不同仍全綠。失敗時用 `-Dfailsafe.runOrder.random.seed=<seed>` 重現。
5. stock-start 的 IT / E2E 與 e2e-browser **不設** `market-data.kafka.*` property,走預設 topic / group。CI 中 stock-start 的 `WsSubscribeReceiveE2ETest`(`-Pe2e`)與 Browser E2E 綠燈即可證明預設行為不變(T-05-15)。

## Decisions Made

見 frontmatter key-decisions。

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] KafkaTopicRetentionTest 改傳入 topic 名稱**
- **Found during:** Task 1
- **Issue:** NewTopic bean 改成帶 `@Value String topic` 參數後,既有單元測試 `KafkaTopicRetentionTest` 呼叫無參數的 `priceTickTopic()` 等方法,test-compile 失敗
- **Fix:** 四個呼叫改為傳入 `KafkaConfig.TOPIC_PRICE_TICK` / `TOPIC_PRICE_BACKFILL`;保留期斷言不變。這個檔不在 plan 的 files 清單中
- **Files modified:** stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/config/KafkaTopicRetentionTest.java
- **Commit:** 4a2ae1e

**2. [Rule 2 - Correctness] BackfillJobIT 的唯一 group 固定在 static 欄位**
- **Found during:** Task 2
- **Issue:** 如果在 `@DynamicPropertySource` 的 supplier 裡直接呼叫 `uniqueName(...)`,每次解析 property 都可能拿到不同名稱
- **Fix:** `PERSIST_GROUP` / `WS_BROADCAST_GROUP` / `DATABASE_URL` 都改成 static final 欄位,supplier 只回傳欄位值
- **Commit:** 3ed5692

另外,`MarketDataIngestServiceIT` 的 backfill 測試多加了 `rec.key()` 等於 `"7"` 的斷言,與 tick 測試對稱。這是在 pollOne 依 key 過濾後補的一致性斷言,不算偏離。

## Issues Encountered

- `./mvnw -o -pl stock-module-market-data verify`(不帶 `-am`)出現 `DependencyResolutionException`(這是已知坑);帶 `-am` 就正常。

## Known Stubs

None.

## Threat Flags

無新增安全面。property 覆寫屬於 plan 威脅模型中的 T-05-15,已由 KafkaTopicPlaceholderTest 緩解。

## Next Phase Readiness

- market-data IT 已全部共用容器。05-12 只需按上方清單收集 CI 證據。
- 已用的 Redis db index:1、2、3。

## Self-Check: PASSED

- FOUND: stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/config/KafkaTopicPlaceholderTest.java
- FOUND: 4a2ae1e(ancestor of HEAD)
- FOUND: 3ed5692(ancestor of HEAD)
- FOUND: 59500cc(ancestor of HEAD)
