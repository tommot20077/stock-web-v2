package dowob.xyz.stockwebv2.marketdata.batch;

import dowob.xyz.stockwebv2.common.model.KlineInterval;
import dowob.xyz.stockwebv2.marketdata.consumer.WsBroadcastConsumer;
import dowob.xyz.stockwebv2.marketdata.persistence.MarketPrice;
import dowob.xyz.stockwebv2.marketdata.persistence.MarketPriceRepository;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

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
 * @author Yuan
 * @version 1.0.0
 */
@SpringBootTest(classes = BackfillJobIT.BatchTestApplication.class)
@Testcontainers
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
        "dowob.xyz.stockwebv2.asset"
    })
    static class BatchTestApplication {
    }

    @Container
    static final ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:latest-pg16")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("backfill_e2e")
            .withUsername("test")
            .withPassword("test");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    static {
        Startables.deepStart(kafka, postgres, redis).join();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.database", () -> 0);
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
}
