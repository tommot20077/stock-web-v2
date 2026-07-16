package dowob.xyz.stockwebv2.marketdata.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dowob.xyz.stockwebv2.common.event.PriceTickEvent;
import dowob.xyz.stockwebv2.marketdata.config.KafkaConfig;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MarketDataIngestService} 整合測試 — 以 Kafka Testcontainer 驗證：
 * <ul>
 *   <li>{@code publishTick} 寫入正確 topic 且 partition key 為 assetId 字串</li>
 *   <li>{@code publishBackfillTick} 寫入 backfill topic 且 partition key 正確</li>
 *   <li>傳入 {@code null} 拋出 {@link NullPointerException}</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0.0
 */
@Testcontainers
class MarketDataIngestServiceIT {

    @Container
    static final ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    static MarketDataIngestService service;
    static KafkaTemplate<String, Object> template;

    @BeforeAll
    static void setup() throws Exception {
        // 建立支援 Java 8 日期時間的 ObjectMapper（PriceTickEvent 含 Instant 欄位）
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        JsonSerializer<Object> valueSerializer = new JsonSerializer<>(objectMapper);
        valueSerializer.setAddTypeInfo(false);

        Map<String, Object> producerProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true
        );

        DefaultKafkaProducerFactory<String, Object> factory = new DefaultKafkaProducerFactory<>(
                producerProps, new StringSerializer(), valueSerializer);
        template = new KafkaTemplate<>(factory);
        service = new MarketDataIngestService(template);

        // 手動建立 topics（Spring KafkaAdmin 未啟動）
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(
                    TopicBuilder.name(KafkaConfig.TOPIC_PRICE_TICK).partitions(3).replicas((short) 1).build(),
                    TopicBuilder.name(KafkaConfig.TOPIC_PRICE_BACKFILL).partitions(3).replicas((short) 1).build()
            )).all().get(10, TimeUnit.SECONDS);
        }
    }

    @AfterAll
    static void tearDown() {
        if (template != null) {
            template.destroy();
        }
    }

    @Test
    void publishTick_sendsToTickTopic_withAssetIdAsKey() throws Exception {
        PriceTickEvent event = PriceTickEvent.of(42L, "AAPL", new BigDecimal("187.45"), new BigDecimal("1000"), "mock");

        SendResult<String, Object> result = service.publishTick(event).get(5, TimeUnit.SECONDS);

        assertThat(result.getRecordMetadata().topic()).isEqualTo(KafkaConfig.TOPIC_PRICE_TICK);
        assertThat(result.getProducerRecord().key()).isEqualTo("42");

        // 以 consumer 驗證訊息確實寫入
        try (KafkaConsumer<String, PriceTickEvent> consumer = newConsumer(PriceTickEvent.class)) {
            consumer.subscribe(List.of(KafkaConfig.TOPIC_PRICE_TICK));
            ConsumerRecord<String, PriceTickEvent> rec = pollOne(consumer);
            assertThat(rec.key()).isEqualTo("42");
            assertThat(rec.value().symbol()).isEqualTo("AAPL");
            assertThat(rec.value().price()).isEqualByComparingTo("187.45");
        }
    }

    @Test
    void publishBackfillTick_sendsToBackfillTopic_withAssetIdAsKey() throws Exception {
        PriceTickEvent event = PriceTickEvent.of(7L, "BTC", new BigDecimal("50000"), null, "backfill");

        SendResult<String, Object> result = service.publishBackfillTick(event).get(5, TimeUnit.SECONDS);

        assertThat(result.getRecordMetadata().topic()).isEqualTo(KafkaConfig.TOPIC_PRICE_BACKFILL);
        assertThat(result.getProducerRecord().key()).isEqualTo("7");

        // 以 consumer 驗證訊息確實寫入
        try (KafkaConsumer<String, PriceTickEvent> consumer = newConsumer(PriceTickEvent.class)) {
            consumer.subscribe(List.of(KafkaConfig.TOPIC_PRICE_BACKFILL));
            ConsumerRecord<String, PriceTickEvent> rec = pollOne(consumer);
            assertThat(rec.value().source()).isEqualTo("backfill");
            assertThat(rec.value().assetId()).isEqualTo(7L);
        }
    }

    @Test
    void publishTick_nullEvent_throwsNPE() {
        assertThatThrownBy(() -> service.publishTick(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void publishBackfillTick_nullEvent_throwsNPE() {
        assertThatThrownBy(() -> service.publishBackfillTick(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ──────────────────────────────────────────────────
    // 測試輔助方法
    // ──────────────────────────────────────────────────

    private <V> KafkaConsumer<String, V> newConsumer(Class<V> valueType) {
        // 建立支援 Java 8 日期時間的 ObjectMapper 供 consumer 端反序列化使用
        ObjectMapper consumerMapper = new ObjectMapper();
        consumerMapper.registerModule(new JavaTimeModule());
        consumerMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        JsonDeserializer<V> valueDeserializer = new JsonDeserializer<>(valueType, consumerMapper);
        valueDeserializer.setRemoveTypeHeaders(false);
        valueDeserializer.addTrustedPackages("*");

        Map<String, Object> consumerProps = new HashMap<>();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        return new KafkaConsumer<>(consumerProps, new StringDeserializer(), valueDeserializer);
    }

    private <V> ConsumerRecord<String, V> pollOne(KafkaConsumer<String, V> consumer) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, V> records = consumer.poll(Duration.ofMillis(500));
            if (!records.isEmpty()) {
                return records.iterator().next();
            }
        }
        throw new AssertionError("No record received within 10s");
    }
}
