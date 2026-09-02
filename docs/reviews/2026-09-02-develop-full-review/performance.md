# develop 全面審查 — 性能維度

- 審查對象:後端 `origin/develop` @ `334cb34`(PR #20 merge;工作樹已驗證與其零差異)、前端 `feature/phase-04-manual-trade-creation` @ `fde9968`
- 方式:read-only 親讀程式碼與既有 surefire 報告;未跑測試、未改檔
- 判斷基準:`ai-docs/architecture.md`(Facade ≤ 3 次/request、Redis 預計算)、`ai-docs/redis-convention.md`、`ai-docs/event-conventions.md`、`ai-docs/flyway-convention.md`、V1–V11 migration

## 總評:**B-**

熱路徑(下單冪等、klines 查詢、tick 批次寫入)的 SQL 與索引設計是對的;問題集中在 **market-data 的 Kafka/WS 運行面**與**測試套件成本**,以及 portfolio 快取的一致性。沒有會在目前規模(mock provider、個位數使用者)立刻出事的項目,但有一條在**重啟**時就會發作(HIGH)。

## 前三優先

1. **HIGH-1** `WsBroadcastConsumer` 在 `ack-mode: manual` 下從不 ack → 每次重啟從 `earliest` 重播整個 tick topic。
2. **MED-2** Kafka 掛掉時 actuator health 會卡 ~60s(`MarketDataHealthIndicatorIT` 71s 就是證據)→ k8s probe 逾時連鎖重啟。
3. **MED-6** market-data 模組 9 個 Testcontainers IT 跑在 surefire、每類自起容器 → `./mvnw test` 4:59 裡它佔 3:27。

---

## 1. 資料存取

| 結論 | 證據 |
|------|------|
| 無 N+1 於 `listTransactions`/`listHoldings` SQL 本身 | `JdbcTradingRepository.java:200-216, 286-299`:單一查詢 + JOIN assets |
| 索引覆蓋 | `listTransactions` 的 WHERE(user_id [+asset_id] [+executed_at 區間])與 4 個 sort key(`TradeSortKey.java:21-42`)分別落在 V7 `idx_transactions_user_created`、V9 `idx_transactions_user_executed` / `idx_transactions_user_amount`(expression index)、V10 `idx_transactions_user_asset_executed`;`quantity` 排序無索引(LOW,使用者列數有限)。冪等查詢走 V11 partial unique index,predicate 與 `ON CONFLICT ... WHERE idempotency_key IS NOT NULL` 逐字一致(`JdbcTradingRepository.java:73`) |
| `count(*)` 每頁重算 | `JdbcTradingRepository.java:212-214`;count 不 JOIN assets,成本可接受(LOW) |
| 深分頁 | `TradingService.java` `MAX_PAGE = 10_000` × size ≤ 100 → OFFSET 可達 1,000,000(LOW;每使用者列數有限,但建議改 keyset) |
| `FOR UPDATE` 範圍 | 只鎖單列 `(user_id, asset_id)`(`JdbcTradingRepository.java:133-144`),持有到交易結束;交易內含 1 次 Facade 查詢 + Redis DELETE(見 MED-4)——鎖持有時間短,可接受 |
| `SELECT *` | 僅 `AssetRepository.java:27` 的 `a.*`(LOW) |
| Backtest 逐列 insert | `JdbcBacktestRepository.java:75-78`:equity/drawdown/monthly/trades 各自 forEach 單列 insert;引擎為合成資料、點數以「月」計(`DeterministicBacktestEngine.java:64-112`)→ LOW,改 `batchUpdate` 即可 |
| 資產搜尋 `ILIKE '%q%'` | `AssetRepository.java:24-33`:symbol/name 皆前綴萬用字元,無 trigram 索引;assets 表小 → LOW,但 typeahead 每次 debounce 後都打它 |

### MED-5 `listHoldings` / `summary` 的 N+1(Redis + DB)
- `TradingService.java` `listHoldings`:每個持倉 1 次 Redis GET;miss 時 `calculateAndCacheHolding` 再 1 次 `findLatestPrice`(`JdbcTradingRepository.java:313-326`)+ 1 次 Redis SET。`summary()` miss 時再呼叫一次 `listHoldings` + `sumRealizedPnl`。
- 影響:Phase 4 成交後三頁 refetch(Overview summary+trades、Positions summary+holdings)—— 50 檔持倉、快取剛被 invalidate 的情況下,一次成交觸發約 100+ 次 Redis/DB 往返。
- 修法:`listHoldings` SQL 直接 `LEFT JOIN asset_latest_prices`(單查詢);Redis 改為每使用者一把 holdings list key 或 MGET。

### MED-4 快取在 commit 前失效(競態 → 最長 60s 髒讀)
- `TradingService.createTrade` 在 `@Transactional` 內最後呼叫 `portfolioCache.invalidateAfterTrade`(`PortfolioCache.java:45-52`),DELETE 發生在 COMMIT 之前。
- 情境:同一使用者另一個請求(第二個分頁、或 Overview/Positions 同時載入)在 DELETE 與 COMMIT 之間讀 holdings → 讀到**交易前**的 DB 值並寫回 Redis(TTL 60s)→ 成交後的 refetch(Phase 4 的核心承諾)拿到舊資料。
- 修法:`TransactionSynchronizationManager.registerSynchronization(afterCommit → invalidate)`,或 delete 兩次(前後各一次)。
- 另:`invalidateAfterTrade` 是同步 Redis I/O 跑在 DB 交易內、持有 `FOR UPDATE` 鎖期間 —— Redis 慢時拉長鎖持有時間(LOW)。

## 2. 快取

| 項目 | 現況 | 評語 |
|------|------|------|
| key 命名 | `portfolio:valuation:{u}:{a}`、`portfolio:summary:{u}`(`PortfolioCache.java:18-19`);`market:latest:{assetId}`(`WsBroadcastConsumer.java:5`) | 與 redis-convention 的 `cache:portfolio:*` / `cache:dashboard:*` / `cache:market:latest:*` 不一致(INFO) |
| TTL | portfolio 60s(convention 5 min);market latest 5 min(convention 30s) | 方向相反;market latest 5 min 讓 REST `/latest` 在 ingestor 停掉後仍回 5 分鐘舊價(LOW) |
| 失效 | 只在 createTrade 後(MED-4) | — |
| 擊穿/雪崩 | 無 singleflight;portfolio 60s 到期後同使用者多分頁同時 miss → 各自算一遍 | LOW(單使用者規模) |
| 序列化 | Jackson JSON 字串,DTO 小 | OK |
| 該快取卻沒快取 | `AssetFacade.findBySymbol` 每個 createTrade/listTrades/klines/latest 都打 DB(`AssetFacadeImpl.java` findBySymbol) | LOW,唯一索引點查;可加短 TTL 快取 |
| 快取前面的資料是靜態的 | `asset_latest_prices` **只有 V2 seed 寫入**,market-data pipeline 從未更新它(`grep asset_latest_prices` 只命中 asset/trading 讀取端)→ holdings 估值永遠用 seed 價 | INFO,交給功能維度;性能上等於在靜態資料前放了一層 60s 快取 |

## 3. market-data

### HIGH-1 `WsBroadcastConsumer` 從不 ack → 重啟即重播整個 topic
- `application.yaml:28` 全域 `spring.kafka.listener.ack-mode: manual`;`WsBroadcastConsumer.onTick(PriceTickEvent)`(`WsBroadcastConsumer.java:24-45`)用預設 container factory、方法簽章沒有 `Acknowledgment`、從未 `acknowledge()`;只有 `PriceWriterConsumer` 走 `batchKafkaListenerContainerFactory`(`KafkaConfig.java:52-63`,AckMode.BATCH)會提交 offset。
- 加上 `auto-offset-reset: earliest`(`application.yaml:20`)且 `TopicBuilder` 未設 retention(`KafkaConfig.java:8-35`;convention 要求 `market.price.*` 1 小時,實際為 broker 預設 7 天)。
- 影響:每次應用重啟,`market-data.ws-broadcast` group 從最舊 offset 重播 —— 1 tick/資產/秒 × 資產數 × 7 天 → 數百萬事件;重播期間 Redis `market:latest:*` 被舊價逐筆覆寫、WS 訂閱者收到舊 tick 洪流、指標失真。
- 依程式碼確認;未做重啟實測(建議在 `KafkaConfigIT` 加一條「重啟後 committed offset 前進」的斷言)。
- 修法:該 listener 改用 `AckMode.RECORD`/`BATCH` 的專屬 factory,或方法加 `Acknowledgment ack; ack.acknowledge()`;同時在 `TopicBuilder` 設 `retention.ms`。

### MED-1 廣播在 Kafka consumer 執行緒上同步送出,單執行緒、慢客戶端 head-of-line blocking
- `WsBroadcastConsumer.java:55-86`:每個 channel 序列化一次(好),但對每個 session 同步 `session.sendMessage`;session 包在 `ConcurrentWebSocketSessionDecorator(sendTimeLimit=10_000ms, buffer=512KB)`(`MarketWebSocketHandler.java:79-82, 154-155`)。
- 沒有設定 `listener.concurrency`(`application.yaml`、`KafkaConfig.java` 皆無)→ 3 個 partition 由 1 個執行緒消費;每個 tick 還做 1 次同步 Redis SET(`writeLatestCache`)與 5 個 interval 的 KLINE 序列化/廣播(`WsBroadcastConsumer.java:37-44`)。
- 影響:一個慢客戶端可讓整個廣播迴圈卡最多 10s/訊息;consumer lag 累積超過 `max.poll.interval.ms`(5 min)會觸發 rebalance。
- 修法:專屬 factory `setConcurrency(3)`;`SEND_TIME_LIMIT_MS` 降到 1–2s;或每 session 一個有界佇列 + 專用 executor 送出。

### MED-2 Kafka 不可用時 health 卡 ~60s
- `MarketDataHealthIndicator.java:59-62`:每次 health 呼叫 `KafkaAdminClient.create(config)`,`describeCluster().get(3s)` 有短逾時,但 try-with-resources 的 `close()` 會等在途請求到 `request.timeout.ms`(預設 30s)/`default.api.timeout.ms`(預設 60s)。
- 證據:`stock-module-market-data/target/surefire-reports` 中 `MarketDataHealthIndicatorIT` 71.09s(其中「dead port → DOWN」一條就是這個等待)。
- 影響:Kafka 故障期間 `/actuator/health` 每次 ~60s → k8s liveness/readiness 逾時 → pod 重啟風暴;management 執行緒被占滿。
- 修法:admin config 設 `request.timeout.ms`/`default.api.timeout.ms` ≈ 2–3s、`admin.close(Duration.ofSeconds(1))`,或共用單一 AdminClient。

### MED-3 `GET /api/v1/market/latest?symbols=` 無上限的扇出
- `MarketController.java:80-83` 不限 symbols 數;`MarketLatestService.java:80-110` 每個 symbol:1 次 `assetFacade.findBySymbol`(DB)+ 1 次 Redis GET + miss 時 1 次 hypertable `ORDER BY time DESC LIMIT 1`。
- 影響:200 個 symbols → 400–600 次往返/請求;違反 architecture「Facade ≤ 3 次/request」;可被當 DoS 面(安全維度已另列)。
- 修法:symbols 上限(例如 50)、`findBySymbols(List)` 單查詢、Redis `MGET`。

### MED-7 `ScheduledIngestor` 單執行緒序列抓價、資產清單啟動後凍結
- `ScheduledIngestor.java:89-110`:`fixedDelay` 1s 迴圈**依序**對每個資產 `provider.fetchLatest`,無逾時、無並行;`assetCache` 在 `@PostConstruct`(`:65-69`)載入一次後不再刷新。
- 影響:目前 mock provider 幾乎零延遲所以感覺不到;換成真實 HTTP provider 時 100 檔 × 200ms = 20s/輪,「每秒一 tick」變成每 20 秒;新上架資產要重啟才會被抓。
- 修法:有界 executor 平行抓取 + 每次呼叫逾時;定期刷新 assetCache。

### MED-8 `POST /backfill` 同步等整個 job
- `grep TaskExecutorJobLauncher|setTaskExecutor` 全 repo 零命中 → Spring Boot 預設同步 `JobLauncher`;`BackfillController.java:108` `launcher.launch(...)` 回來時 job 已跑完,HTTP 執行緒被占用整段時間(上限 `MAX_RANGE` 90 天,`:48`)。有 `GET /{jobExecutionId}` 狀態端點(`:123`)卻沒有非同步啟動。
- 影響:mock provider 下幾秒;真實 provider 下數分鐘,會撞 proxy/LB 逾時,且 Tomcat 執行緒被長佔。
- 修法:`TaskExecutorJobLauncher` + 有界 executor,回 202 + jobExecutionId。

### 其他 market-data(無發現或 LOW)
- `PriceWriterConsumer` 走 batch listener + `batchUpdate`(`MarketPriceRepository.java:51-73`)→ 正確;`KlineBucketAccumulator` 狀態以 `(assetId, interval)` 為 key、有界(`KlineBucketAccumulator.java:36-60`)→ OK。
- `KlineQueryService.java:24-41`:limit 預設 500、上限 5000、走 continuous aggregate view、`(asset_id, bucket)` 區間 → OK。`MarketPriceRepository.findRange`(`:33-40`)無 LIMIT,但未暴露到 REST。
- Backfill:`HistoricalTickReader.java:89` 一次把整段 `fetchHistorical` 讀進記憶體(90 天 × 1m ≈ 13 萬筆,可接受);`KafkaBackfillItemWriter.java:64-90` 先全送再 `allOf` 等 ack(正確)。
- Kafka 參數:producer 無 `linger.ms`/`batch.size`/`compression.type`;consumer 無 `max.poll.records`;未設 `CooperativeStickyAssignor`(event-conventions 要求);topic 無 retention(見 HIGH-1)。(LOW)
- TimescaleDB:V4 hypertable + `(asset_id, time DESC)` 索引、V5 五個 CA 各有 refresh policy → OK;未設 compression/retention policy(LOW,資料量成長後要補)。

## 4. 執行緒與連線池
- HikariCP、Lettuce 全用預設(`application*.yaml` 無任何 pool 設定;`grep HikariConfig|LettuceConnectionFactory|maximum-pool-size` 零命中)。單體 + 3 個 Kafka listener + scheduler 共用預設 10 條 DB 連線,目前規模 OK(LOW,上線前要依併發數校準)。
- 沒有 `@EnableAsync`/自訂 executor(全 repo 零命中);`SpringEventPublisher` 的 `@Async` 語意(event-conventions)實際不存在 —— 交給架構維度。
- 阻塞呼叫跑在事件執行緒:HIGH-1/MED-1 所述的 Redis SET + WS send 在 Kafka consumer 執行緒上。

## 5. API 面
- 分頁上限 100(`TradingService.java` safeSize)、klines ≤ 5000、backfill ≤ 90 天 → 有界;`latest?symbols=` 無界(MED-3)。
- `BigDecimal`/`OffsetDateTime` 以字串序列化,無壓縮(`server.compression` 未開;LOW,klines 5000 筆 JSON 約 400KB 可壓 5 倍)。
- actuator:`health,info,metrics` 曝露、獨立 management port(`application.yaml:30-40`);業務指標有 `MarketDataMetrics`(214 行)與 `market.tick.persist.failed`;trading 面沒有自訂指標(LOW)。

## 6. 測試套件耗時

### MED-6 market-data 模組 `./mvnw test` 3:27,幾乎全是容器與真實等待
| 測試類 | 秒 | 原因(file:line) |
|--------|----|------------------|
| `MarketDataHealthIndicatorIT` | 71.1 | AdminClient close 等 60s(見 MED-2) |
| `BackfillJobIT` | 29.4 | 自起 Kafka+PG+Redis 三容器(`:102-115`)+ `Thread.sleep(5_000)`(`:227`)+ 多個 `atMost(60s)` |
| `MarketDataIngestServiceIT` | 18.6 | 自起 Kafka 容器(`:55`) |
| `PriceWriterRetryIT` | 13.7 | 自起 Kafka(`:73-74`)+ 真實 `FixedBackOff(1000, 3)` 重試等待 |
| `KafkaConfigIT` | 11.7 | 自起 Kafka(`:29-30`) |
| 其餘 5 個 *IT | 5–8 each | 各自 `@Container static` PG/Redis |

- 根因:`stock-module-market-data/pom.xml:167-172` surefire `<include>**/*IT.java</include>` → 容器類 IT 在 `mvn test` 就跑;每個類別自己宣告 `@Testcontainers` + `@Container static`,JUnit 生命週期每類重啟容器(Kafka 冷啟 10–20s)。
- 對照 stock-start 的 `support/ContainerIT.java:15-29`:static 區塊啟動、無 `@Container`,21 個 IT 共用同一組容器 —— 這才是該有的樣子。
- 修法:抽 `MarketDataContainers` singleton 基底(或 `withReuse(true)` + `~/.testcontainers.properties`);IT 移到 failsafe;`Thread.sleep(5000)` 改 awaitility;retry IT 注入短 backoff;health IT 配合 MED-2 的短逾時。預估可把 4:59 壓到 ~2 分鐘。

## 7. 前端
- 打包:單一 `index-*.js` **377 KB** + `index-*.css` **111 KB**(`dist/assets`);`vite.config.ts` 無 `build`/`manualChunks`,頁面以 `App.vue` `v-if` 切換、無 route-level 動態 import → 首屏載入全部頁面(含 Backtest/Markets/Ops)。LOW→MED 視目標裝置;改 `defineAsyncComponent`/動態 import 可切一半。依賴只有 vue/pinia/vue-router,無多餘套件。
- `OrderTicket.vue`:typeahead 250ms debounce + 遞增 seq + `AbortController`(`:789-843`)、klines 也有 seq(`:875-894`)→ 競態處理正確;1.5k 行單檔的重算成本主要是模板大小,非熱點(LOW,架構維度會談拆分)。
- 三頁 refetch:`Overview.vue:362-366`、`Positions.vue:505-509` 兩個 loader 以 `void` 併發呼叫 → 平行(好);但頁面端 refresh 無 seq 守衛(連續兩筆成交可能被舊回應覆蓋,前端 review F-4 已列)。
- CSRF:`apiClient.ts:203-222` 只在 cookie 缺時才 bootstrap `GET /csrf`,不會每個 POST 多一次往返(好)。
- 列表:`Trades.vue:150` API mode 以 `tr.id` 為 key(好);mock mode `:120` 以七欄拼接為 key(LOW);無虛擬化,分頁 ≤ 100 列足夠。
- 無 WS client、無輪詢(`Markets.vue:151` 的 `setInterval` 只在 mock mode 產生假 tick)。

## 殘留未確認
1. HIGH-1 的「從不 commit offset」依程式碼推斷(Spring Kafka MANUAL 模式下未呼叫 `acknowledge()` 不會提交);未做重啟重播的實測。
2. surefire 耗時來自最近一次有 Docker 的執行報告;本機目前無 Docker,無法重跑量測。
3. 未實測 `latest?symbols=` 大量 symbols 的實際延遲,只從程式碼計算往返次數。
4. HikariCP/Lettuce 預設值是否足夠,要等 IT 併發(8 併發同 key)之外的負載測試才能定論。
