---
phase: 05-cross-repo-browser-flow-verification-contract-hardening
plan: 07
subsystem: testing
tags: [testcontainers, timescaledb, kafka, redis, failsafe, market-data, integration-test]

requires: []
provides:
  - "MarketDataContainers:market-data IT 共用單例容器(KafkaHolder / PostgresHolder / RedisHolder lazy start)+ freshDatabase / ensureTopics / uniqueName"
  - "8 個 market-data IT 改用共用容器,DB / Redis / Kafka topic 各自隔離"
affects: [05-08, 05-12]

actuals:
  tokens: 10700    # chars/4 over realized diff (42777 chars)
  tasks: 2
  commits: 2
plan_head_before: 37cedae48b06a8d70c17be4f7efa36019f1a7184
plan_head_after: e90d406d9377220a718e4b94aa721e4948225cb5

tech-stack:
  added: []
  patterns:
    - "initialization-on-demand holder 單例容器(不加 @Container / @Testcontainers,Ryuk 回收)"
    - "共用 Postgres 容器內每 IT 一個 database(CREATE DATABASE md_<name> + Flyway)"
    - "共用 Redis 每 IT 一個 db index,清理用 flushDb"
    - "共用 Kafka 以 uniqueName topic + ensureTopics(吞 TopicExistsException)隔離"

key-files:
  created:
    - stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/support/MarketDataContainers.java
  modified:
    - stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/persistence/MarketPriceRepositoryIT.java
    - stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/ws/WsTicketServiceIT.java
    - stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/config/KafkaConfigIT.java
    - stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/consumer/PriceWriterConsumerIT.java
    - stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/api/MarketControllerKlinesIT.java
    - stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/migration/MarketDataMigrationIT.java
    - stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/api/BackfillIdempotencyServiceIT.java
    - stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/observability/MarketDataHealthIndicatorIT.java

key-decisions:
  - "freshDatabase 一律先 DROP DATABASE IF EXISTS ... WITH (FORCE) 再 CREATE,等價於「已存在則砍掉重建」且少一次查詢;名稱限 [a-z0-9_]+ 防 DDL 注入"
  - "freshDatabase 宣告 synchronized(failsafe 目前單執行緒,僅為防日後開 JUnit 平行化)"
  - "Redis db index 分配:WsTicketServiceIT=1、BackfillIdempotencyServiceIT=2(index 0 留給預設 / 05-08 的 BackfillJobIT 另行決定)"

patterns-established:
  - "新增 market-data IT:容器一律經 MarketDataContainers.postgres()/redis()/kafka(),禁止自建 @Container"

requirements-completed: [VER-01]

coverage:
  - id: D1
    description: "MarketDataContainers 共用容器基礎(三個 holder、freshDatabase、ensureTopics、uniqueName)"
    requirement: "VER-01"
    verification:
      - kind: other
        ref: "./mvnw -B -q -pl stock-module-market-data -am test-compile && ./mvnw -B spotless:check"
        status: pass
    human_judgment: true
    rationale: "本機無 Docker,只驗證到編譯與格式;容器實際啟動與隔離行為須由 05-12 收集 CI Integration job 的 failsafe 報告確認"
  - id: D2
    description: "8 個 market-data IT 遷移至共用容器且各自資料隔離(4 Postgres 獨立 DB、2 Redis 獨立 index、2 Kafka-only)"
    requirement: "VER-01"
    verification:
      - kind: other
        ref: "grep:8 個 IT 無 @Testcontainers/@Container;flushAll=0;freshDatabase 各 ≥1"
        status: pass
      - kind: integration
        ref: "CI Integration job failsafe 報告(05-12 收集)"
        status: unknown
    human_judgment: true
    rationale: "IT 執行證據只能在 CI 取得(本機無 Docker)"

duration: 8min
completed: 2026-11-09
status: complete
---

# Phase 05 Plan 07: market-data IT 共用容器(方案 B,首批 8 個)Summary

**以 initialization-on-demand holder 建立 market-data 共用 TimescaleDB / Redis / Kafka 單例容器,8 個 IT 改用之並以獨立 database、Redis db index、唯一 topic 名稱隔離資料**

## Performance

- **Duration:** 約 8 min(14:53 → 15:01 UTC)
- **Started:** 2026-11-09T14:53Z(約)
- **Completed:** 2026-11-09T15:01:33Z
- **Tasks:** 2
- **Files modified:** 9(1 新增、8 修改)

## Accomplishments

- 新增 `MarketDataContainers`(`final`、私有建構子、繁中 Javadoc):`KafkaHolder` / `PostgresHolder`(admin DB `md_admin`,user/pw `test`)/ `RedisHolder` 各自 `static { CONTAINER.start(); }`,只用 Redis 的類別不會啟動 Kafka;`freshDatabase(name)`、`ensureTopics(admin, topics...)`、`uniqueName(prefix)`。
- Postgres IT 各用獨立 database:`md_price_repository`(MarketPriceRepositoryIT)、`md_price_writer`(PriceWriterConsumerIT)、`md_klines`(MarketControllerKlinesIT,含 :120 的 refresh CA 連線)、`md_migration`(MarketDataMigrationIT)。
- Redis IT:WsTicketServiceIT db index 1、BackfillIdempotencyServiceIT db index 2,`flushAll()` → `flushDb()`。
- Kafka:KafkaConfigIT 用 `uniqueName(TOPIC_PRICE_TICK / TOPIC_PRICE_BACKFILL)` + `ensureTopics`,`partitions().hasSize(3)` 不變;MarketDataHealthIndicatorIT 改用 `MarketDataContainers.kafka()`。
- 8 個 IT 皆移除 `@Testcontainers` / `@Container`;斷言邏輯未改。

## Task Commits

1. **Task 1(tracer):MarketDataContainers + 首批 3 個 IT** — `cad83c4` (test)
2. **Task 2:其餘 5 個 IT** — `e90d406` (test)

## Files Created/Modified

- `stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/support/MarketDataContainers.java` — 共用容器 + 隔離 helper
- 8 個 `*IT.java`(見 frontmatter key-files.modified)— 改用共用容器

## Verification Evidence

- `./mvnw -B -q -pl stock-module-market-data -am test-compile` → exit 0(Task 1、Task 2 各跑一次)
- `./mvnw -B spotless:check` → `BUILD SUCCESS`(Task 1 首次因 import 順序失敗,修正後通過;Task 2 通過)
- Task 2 automated:8 個 IT `grep -l '@Testcontainers'` 數量 = 0 → exit 0
- Acceptance greps:
  - `class KafkaHolder|PostgresHolder|RedisHolder` = 3(≥3 PASS)
  - `TopicExistsException` = 3(≥1 PASS)
  - 8 個遷移 IT `@Testcontainers|@Container` 皆 0(PASS)
  - `flushAll`:BackfillIdempotencyServiceIT 0、WsTicketServiceIT 0(PASS)
  - `freshDatabase`:4 個 Postgres IT 各 1(PASS)
- `replaceDatabase` URL 改寫邏輯以單檔 Java 驗證:`jdbc:postgresql://localhost:32768/md_admin?loggerLevel=OFF` → `.../md_klines?loggerLevel=OFF`;無 query string 亦正確。
- 仍帶 `@Testcontainers` 的只剩 05-08 範圍的 `BackfillJobIT`、`PriceWriterRetryIT`、`MarketDataIngestServiceIT`(符合本 plan 範圍)。

## CI 證據待 05-12 收集(本機無 Docker)

本機 `docker info` 失敗,IT 無法執行。05-12 需從 CI **Integration job**(`./mvnw -B -pl stock-start -am verify`,會跑 market-data 的 failsafe)收集:

- run id,以及 `stock-module-market-data/target/failsafe-reports/` 中下列 8 份報告皆 `failures=0, errors=0, skipped=0`:
  `TEST-dowob.xyz.stockwebv2.marketdata.persistence.MarketPriceRepositoryIT.xml`、
  `...consumer.PriceWriterConsumerIT.xml`、`...api.MarketControllerKlinesIT.xml`、
  `...migration.MarketDataMigrationIT.xml`、`...api.BackfillIdempotencyServiceIT.xml`、
  `...ws.WsTicketServiceIT.xml`、`...observability.MarketDataHealthIndicatorIT.xml`、
  `...config.KafkaConfigIT.xml`
- 預期觀察:同一 JVM 內 timescale / redis / cp-kafka 各只啟動一次(log 中 `Container ... started` 各 1 次;05-08 完成後亦應維持)。
- 05-VALIDATION 列的亂序驗證 `./mvnw -B -pl stock-module-market-data -am verify -Dfailsafe.runOrder=random` 應在 05-08 完成後一併跑(D-29)。

## Decisions Made

見 frontmatter key-decisions。

## Deviations from Plan

None - plan executed exactly as written.(Task 1 spotless import 順序的修正屬同一 task 內的格式調整,非計畫偏離。)

## Issues Encountered

- `./mvnw -pl stock-module-market-data test`(不帶 `-am`)因 sibling 模組未 install 而 DependencyResolutionException;本 plan 只改 failsafe 跑的 `*IT`,surefire 單元測試不受影響,故未另跑。

## Known Stubs

None.

## Next Phase Readiness

- 05-08 可直接使用 `MarketDataContainers.kafka()` / `ensureTopics` / `uniqueName` 遷移剩餘 3 個 Kafka IT;Redis db index 1、2 已占用。

## Self-Check: PASSED

- FOUND: stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/support/MarketDataContainers.java
- FOUND: cad83c4(ancestor of HEAD)
- FOUND: e90d406(ancestor of HEAD)
