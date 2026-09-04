package dowob.xyz.stockwebv2.marketdata.consumer;

import dowob.xyz.stockwebv2.common.event.PriceTickEvent;
import dowob.xyz.stockwebv2.marketdata.config.KafkaConfig;
import dowob.xyz.stockwebv2.marketdata.persistence.MarketPriceRepository;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.BatchMessageListener;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * {@link PriceWriterConsumer} Retry + DLT 整合測試。
 *
 * <p>以 Testcontainers KRaft Kafka 驗證：
 * <ul>
 *   <li>{@link MarketPriceRepository#insertAll} 拋出 RuntimeException 時，
 *       訊息在 N 次 backoff retry 後最終落入 DLT topic</li>
 *   <li>{@code market.tick.persist.failed} metric counter 遞增</li>
 * </ul>
 *
 * <p>測試採用程式化 {@link KafkaMessageListenerContainer} 設置，不啟動 Spring 容器，
 * 讓整合測試聚焦在 error handler 與 DLT 流程的正確性。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Testcontainers
class PriceWriterRetryIT {

    @Container
    static final ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    static KafkaTemplate<String, Object> producer;
    static MarketPriceRepository repoMock;
    static PriceWriterConsumer consumer;
    static KafkaMessageListenerContainer<String, Object> container;
    static SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @BeforeAll
    static void setup() throws Exception {
        // ── 建立 topic（包含 DLT）──────────────────────────────────────────
        Map<String, Object> adminProps = Map.of(
                "bootstrap.servers", kafka.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(adminProps)) {
            admin.createTopics(List.of(
                    TopicBuilder.name(KafkaConfig.TOPIC_PRICE_TICK)
                            .partitions(3).replicas((short) 1).build(),
                    TopicBuilder.name(KafkaConfig.TOPIC_PRICE_TICK_DLT)
                            .partitions(3).replicas((short) 1).build()
            )).all().get(10, TimeUnit.SECONDS);
        }

        // ── Producer ──────────────────────────────────────────────────────
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // Producer：不加入 type header，訊息 payload 為純 JSON
        JsonSerializer<Object> valueSerializer = new JsonSerializer<>(om);
        valueSerializer.setAddTypeInfo(false);

        Map<String, Object> producerProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.ACKS_CONFIG, "all"
        );
        producer = new KafkaTemplate<>(
                new DefaultKafkaProducerFactory<>(
                        producerProps,
                        new StringSerializer(),
                        valueSerializer));
        producer.setDefaultTopic(KafkaConfig.TOPIC_PRICE_TICK);

        // ── Mock repository：insertAll 永遠拋例外 ──────────────────────────
        repoMock = mock(MarketPriceRepository.class);
        when(repoMock.insertAll(any())).thenThrow(new RuntimeException("simulated DB failure"));

        consumer = new PriceWriterConsumer(repoMock);

        // ── Consumer factory ──────────────────────────────────────────────
        Map<String, Object> consumerProps = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "market-data.persist-retry-test",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false
        );
        // 使用 ErrorHandlingDeserializer 包裝 JsonDeserializer，
        // 避免 SerializationException 無法由 DefaultErrorHandler 直接處理。
        // 明確以 PriceTickEvent 型別建立 JsonDeserializer，不依賴 type header
        @SuppressWarnings("unchecked")
        JsonDeserializer<Object> jsonDeserializer = (JsonDeserializer<Object>)
                (JsonDeserializer<?>) new JsonDeserializer<>(PriceTickEvent.class, om, false);
        jsonDeserializer.addTrustedPackages("*");

        ErrorHandlingDeserializer<Object> valueDeserializer =
                new ErrorHandlingDeserializer<>(jsonDeserializer);

        DefaultKafkaConsumerFactory<String, Object> cf = new DefaultKafkaConsumerFactory<>(
                consumerProps,
                new StringDeserializer(),
                valueDeserializer);

        // ── Error handler：3 次 backoff retry (100ms間隔)，最終落 DLT ──────
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                producer,
                (record, ex) -> {
                    meterRegistry.counter("market.tick.persist.failed",
                            "topic", record.topic(),
                            "reason", ex.getClass().getSimpleName()).increment();
                    return new TopicPartition(record.topic() + ".DLT", record.partition());
                });
        // FixedBackOff(interval, maxAttempts)：retry 3 次 (100ms 間隔)
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(100L, 3L));

        // ── 手動建立 Container ─────────────────────────────────────────────
        ContainerProperties cp = new ContainerProperties(KafkaConfig.TOPIC_PRICE_TICK);
        cp.setGroupId("market-data.persist-retry-test");
        cp.setAckMode(ContainerProperties.AckMode.BATCH);
        cp.setMessageListener((BatchMessageListener<String, Object>) records ->
                consumer.consume(records.stream()
                        .filter(r -> r.value() instanceof PriceTickEvent)
                        .map(r -> (PriceTickEvent) r.value())
                        .toList()));

        container = new KafkaMessageListenerContainer<>(cf, cp);
        container.setCommonErrorHandler(errorHandler);
        container.start();

        // 等待 container 啟動完成
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .until(container::isRunning);
    }

    @AfterAll
    static void tearDown() {
        if (container != null) {
            container.stop();
        }
        if (producer != null) {
            producer.destroy();
        }
    }

    @Test
    @DisplayName("repository.insertAll 拋例外 → 訊息 retry 後落入 DLT，metric counter 遞增")
    void failedRecord_endsUpInDltAfterRetries() throws Exception {
        // ── 發送一筆 tick 事件 ─────────────────────────────────────────────
        PriceTickEvent event = new PriceTickEvent(
                "e1",
                Instant.parse("2026-01-01T00:00:00Z"),
                1L, "AAPL",
                new BigDecimal("100"),
                new BigDecimal("500"),
                "mock");

        producer.send(KafkaConfig.TOPIC_PRICE_TICK, "1", event).get(5, TimeUnit.SECONDS);
        producer.flush();

        // ── 驗證訊息進入 DLT ──────────────────────────────────────────────
        // DLT consumer 使用 ByteArrayDeserializer 讀取 raw bytes，
        // 只驗證訊息是否抵達 DLT，不重新反序列化 payload
        Map<String, Object> dltConsumerProps = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-verify-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                        org.apache.kafka.common.serialization.StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                        org.apache.kafka.common.serialization.ByteArrayDeserializer.class
        );

        try (KafkaConsumer<String, byte[]> dltConsumer = new KafkaConsumer<>(dltConsumerProps)) {
            dltConsumer.subscribe(List.of(KafkaConfig.TOPIC_PRICE_TICK_DLT));

            // 等待最多 30 秒，讓 retry 完成並將訊息送入 DLT
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> {
                        ConsumerRecords<String, byte[]> recs =
                                dltConsumer.poll(Duration.ofMillis(500));
                        assertThat(recs.count()).isGreaterThanOrEqualTo(1);
                    });
        }

        // ── 驗證 metric counter ──────────────────────────────────────────
        // metric 建立時帶有 tags，需以 find() 搜尋而非直接用無 tag 的 counter()
        assertThat(meterRegistry.find("market.tick.persist.failed").counters())
                .as("market.tick.persist.failed counter 應至少存在一個帶 tag 的 counter")
                .isNotEmpty();
        double totalCount = meterRegistry.find("market.tick.persist.failed")
                .counters().stream()
                .mapToDouble(c -> c.count())
                .sum();
        assertThat(totalCount).isGreaterThanOrEqualTo(1.0);
    }
}
