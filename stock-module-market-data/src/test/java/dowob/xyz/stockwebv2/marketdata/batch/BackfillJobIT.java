package dowob.xyz.stockwebv2.marketdata.batch;

import dowob.xyz.stockwebv2.common.model.KlineInterval;
import dowob.xyz.stockwebv2.marketdata.config.KafkaConfig;
import dowob.xyz.stockwebv2.marketdata.consumer.WsBroadcastConsumer;
import dowob.xyz.stockwebv2.marketdata.persistence.MarketPrice;
import dowob.xyz.stockwebv2.marketdata.persistence.MarketPriceRepository;
import dowob.xyz.stockwebv2.marketdata.support.MarketDataContainers;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link BackfillJobLauncher} 全流程整合測試。
 *
 * <p>驗證：
 * <ol>
 *   <li>觸發 1 天 × 1m backfill（1440 筆） → Job 完成 → DB 有 1440 筆</li>
 *   <li>第二次觸發相同範圍 → DB 筆數不變（idempotency via ON CONFLICT DO NOTHING）</li>
 *   <li>{@link WsBroadcastConsumer#onTick} 從未被呼叫（backfill 不推 WebSocket）</li>
 * </ol>
 *
 * <p>使用 {@code @MockitoSpyBean} 監控 {@link WsBroadcastConsumer}，
 * 驗證它確實從未收到 backfill 事件（backfill topic 不在其 {@code @KafkaListener} 訂閱範圍）。
 *
 * <p>Spring 上下文使用 {@link BatchTestApplication} 作為錨點，掃描所有必要 packages
 * 包含 {@code stock-module-asset} 的 {@code AssetFacadeImpl}。
 *
 * <p><strong>容器與隔離(D-04 / D-29 決策)</strong>:本類別<strong>不保留</strong>自己的 Kafka / Postgres /
 * Redis 容器,也不使用 Testcontainers 的 JUnit extension,改用 {@link MarketDataContainers} 的共用單例容器,
 * 並以下列方式與其他 market-data IT 隔離:
 * <ul>
 *   <li><strong>Kafka topic</strong>:tick / backfill topic 以 {@link MarketDataContainers#uniqueName(String)}
 *       產生,透過 {@link KafkaConfig#PROP_TOPIC_PRICE_TICK} / {@link KafkaConfig#PROP_TOPIC_PRICE_BACKFILL}
 *       注入正式程式的 placeholder;Spring {@code KafkaAdmin} 依 {@code NewTopic} bean 建立這些唯一 topic(含 DLT)。
 *       listener 雖以 {@code auto-offset-reset=earliest} 讀取,但只訂閱自己的唯一 topic,看不到其他 IT 的訊息。</li>
 *   <li><strong>Consumer group</strong>:{@link KafkaConfig#PROP_GROUP_PERSIST} /
 *       {@link KafkaConfig#PROP_GROUP_WS_BROADCAST} 亦設為唯一名稱。</li>
 *   <li><strong>Postgres</strong>:獨立 database {@code md_backfill_job}({@link MarketDataContainers#freshDatabase(String)}),
 *       Flyway 在其中建表;{@code DELETE FROM market_prices} 只影響自己。</li>
 *   <li><strong>Redis</strong>:db index {@value #REDIS_DATABASE}(1、2 已由 {@code WsTicketServiceIT} /
 *       {@code BackfillIdempotencyServiceIT} 使用)。</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.1.0
 */
@SpringBootTest(classes = BackfillJobIT.BatchTestApplication.class)
@TestPropertySource(properties = {
    "market-data.scheduling.enabled=false",
    "market-data.ingestor.enabled=false",
    "market-data.mock.enabled=true",
    "spring.batch.job.enabled=false",    // 避免 Spring Boot 自動執行 Job
    "spring.batch.jdbc.initialize-schema=never",
    // Kafka producer settings
    "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
    "spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer",
    "spring.kafka.producer.acks=all",
    "spring.kafka.producer.properties.enable.idempotence=true",
    "spring.kafka.producer.properties.spring.json.add.type.headers=false",
    // Kafka consumer settings
    "spring.kafka.consumer.auto-offset-reset=earliest",
    "spring.kafka.consumer.enable-auto-commit=false",
    "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
    "spring.kafka.consumer.value-deserializer=org.springframework.kafka.support.serializer.JsonDeserializer",
    "spring.kafka.consumer.properties.spring.json.trusted.packages=dowob.xyz.stockwebv2.common.event",
    "spring.kafka.consumer.properties.spring.json.value.default.type=dowob.xyz.stockwebv2.common.event.PriceTickEvent",
    "spring.kafka.listener.ack-mode=manual"
})
class BackfillJobIT {

    /**
     * 測試專用 Spring Boot Application，掃描所有必要的 packages。
     *
     * <p>明確指定 {@code scanBasePackages} 以包含：
     * <ul>
     *   <li>{@code dowob.xyz.stockwebv2.marketdata} — market-data module beans</li>
     *   <li>{@code dowob.xyz.stockwebv2.asset} — AssetFacadeImpl（跨 module 依賴）</li>
     * </ul>
     *
     * <p>不掃描 {@code infrastructure} 以避免 JWT / Security 相關 bean 的依賴問題。
     * Infrastructure beans（如 Redis config）由 Spring Boot auto-configuration 提供。
     */
    @SpringBootApplication(scanBasePackages = {
        "dowob.xyz.stockwebv2.marketdata",
        "dowob.xyz.stockwebv2.asset",
        "dowob.xyz.stockwebv2.infrastructure.audit"
    })
    static class BatchTestApplication {
    }

    /** 本類別專用的 Redis db index;1、2 已被其他 IT 占用,0 留給預設。 */
    static final int REDIS_DATABASE = 3;

    /** 本類別專用的即時 tick topic(只用來讓 WsBroadcastConsumer / PriceWriterConsumer 訂閱自己的 topic)。 */
    static final String TICK_TOPIC = MarketDataContainers.uniqueName(KafkaConfig.TOPIC_PRICE_TICK);

    /** 本類別專用的 backfill topic;{@code MarketDataIngestService} 會把 backfill 事件送到這裡。 */
    static final String BACKFILL_TOPIC = MarketDataContainers.uniqueName(KafkaConfig.TOPIC_PRICE_BACKFILL);

    /** {@code PriceWriterConsumer} 的 consumer group;固定於 static 欄位,supplier 重複求值也拿到同一名稱。 */
    static final String PERSIST_GROUP = MarketDataContainers.uniqueName("backfill-it.persist");

    /** {@code WsBroadcastConsumer} 的 consumer group。 */
    static final String WS_BROADCAST_GROUP = MarketDataContainers.uniqueName("backfill-it.ws-broadcast");

    /**
     * 本類別專用的 database {@code md_backfill_job} 的 JDBC URL。
     *
     * <p>放在 static 欄位而非 {@code @DynamicPropertySource} 的 supplier 裡,確保 {@code freshDatabase}
     * (會先 DROP 再 CREATE)每個類別只執行一次。</p>
     */
    static final String DATABASE_URL = MarketDataContainers.freshDatabase("backfill_job");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.kafka.bootstrap-servers", MarketDataContainers.kafka()::getBootstrapServers);
        r.add("spring.datasource.url", () -> DATABASE_URL);
        r.add("spring.datasource.username", MarketDataContainers.postgres()::getUsername);
        r.add("spring.datasource.password", MarketDataContainers.postgres()::getPassword);
        r.add("spring.data.redis.host", MarketDataContainers.redis()::getHost);
        r.add("spring.data.redis.port", () -> MarketDataContainers.redis().getMappedPort(6379));
        r.add("spring.data.redis.database", () -> REDIS_DATABASE);
        r.add(KafkaConfig.PROP_TOPIC_PRICE_TICK, () -> TICK_TOPIC);
        r.add(KafkaConfig.PROP_TOPIC_PRICE_BACKFILL, () -> BACKFILL_TOPIC);
        r.add(KafkaConfig.PROP_GROUP_PERSIST, () -> PERSIST_GROUP);
        r.add(KafkaConfig.PROP_GROUP_WS_BROADCAST, () -> WS_BROADCAST_GROUP);
        r.add("spring.flyway.enabled", () -> true);
        r.add("spring.flyway.locations", () -> "classpath:db/migration");
        r.add("spring.flyway.mixed", () -> true);
    }

    @Autowired
    private BackfillJobLauncher launcher;

    @Autowired
    private MarketPriceRepository repository;

    @Autowired
    private JdbcClient jdbcClient;

    /**
     * WsBroadcastConsumer spy — 驗證 backfill 事件不會觸發 onTick 呼叫。
     * backfill topic 不在 WsBroadcastConsumer 的 @KafkaListener topics 清單內，
     * 故此驗證等同驗證 topic 隔離設計正確。
     */
    @MockitoSpyBean
    private WsBroadcastConsumer wsBroadcastConsumer;

    @BeforeEach
    void cleanMarketPrices() {
        jdbcClient.sql("DELETE FROM market_prices").update();
    }

    @Test
    @DisplayName("1 天 × 1m backfill（1440 筆）→ DB 有 1440 筆，WsBroadcastConsumer.onTick 從未被呼叫")
    void backfill1Day1m_writesAllRowsToDb_andDoesNotBroadcastWs() throws Exception {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to   = Instant.parse("2026-01-02T00:00:00Z");  // 1 天 = 1440 分鐘

        JobExecution execution = launcher.launch("AAPL", from, to, KlineInterval.ONE_MINUTE);

        // 等待 Job 完成（TaskExecutorJobLauncher 預設同步執行）
        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .until(() -> execution.getStatus() == BatchStatus.COMPLETED
                        || execution.getStatus().isUnsuccessful());

        assertThat(execution.getStatus())
                .as("Job 應成功完成")
                .isEqualTo(BatchStatus.COMPLETED);

        // 等待 PriceWriterConsumer 從 Kafka 消費並寫入 DB（非同步）
        // AAPL 在 V2 migration 中是第一筆插入，id = 1
        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    List<MarketPrice> ticks = repository.findRange(1L, from, to);
                    assertThat(ticks)
                            .as("1 天 × 1m backfill 應有 1440 筆")
                            .hasSize(1440);
                });

        // backfill 事件只走 backfill topic，WsBroadcastConsumer 只訂閱 tick topic，
        // 因此 onTick 應從未被呼叫
        verify(wsBroadcastConsumer, never()).onTick(any());
    }

    @Test
    @DisplayName("第二次觸發相同範圍 → DB 筆數不變（idempotency）")
    void backfill_secondInvocation_isIdempotent() throws Exception {
        Instant from = Instant.parse("2026-01-03T00:00:00Z");
        Instant to   = Instant.parse("2026-01-04T00:00:00Z");  // 1 天 = 1440 筆

        // 第一次觸發
        JobExecution exec1 = launcher.launch("AAPL", from, to, KlineInterval.ONE_MINUTE);
        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .until(() -> exec1.getStatus() == BatchStatus.COMPLETED
                        || exec1.getStatus().isUnsuccessful());
        assertThat(exec1.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // 等待第一次寫入 DB
        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() ->
                        assertThat(repository.findRange(1L, from, to)).hasSize(1440));

        long countAfterFirst = repository.findRange(1L, from, to).size();

        // 第二次觸發相同範圍（startedAt 不同，故為不同 Job Instance）
        JobExecution exec2 = launcher.launch("AAPL", from, to, KlineInterval.ONE_MINUTE);
        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .until(() -> exec2.getStatus() == BatchStatus.COMPLETED
                        || exec2.getStatus().isUnsuccessful());
        assertThat(exec2.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // 等待第二次 Kafka 消費完成（讓 consumer 有機會處理）
        // 由於 ON CONFLICT DO NOTHING，DB 筆數應與第一次相同
        Thread.sleep(5_000);  // 讓 consumer 有足夠時間處理潛在的重複訊息

        long countAfterSecond = repository.findRange(1L, from, to).size();
        assertThat(countAfterSecond)
                .as("第二次觸發後 DB 筆數應與第一次相同（idempotent）")
                .isEqualTo(countAfterFirst);
    }

    @Test
    @DisplayName("backfill job 在專用執行緒池非同步執行,HTTP 執行緒不必等整個 job 跑完(效能審查 MED-8)")
    void jobLauncher_runsJobsOnDedicatedAsyncExecutor() {
        Object jobLauncher = ReflectionTestUtils.getField(launcher, "jobLauncher");
        Object taskExecutor = ReflectionTestUtils.getField(jobLauncher, "taskExecutor");

        assertThat(taskExecutor).isInstanceOf(ThreadPoolTaskExecutor.class);
        assertThat(((ThreadPoolTaskExecutor) taskExecutor).getThreadNamePrefix()).isEqualTo("backfill-");
    }
}
