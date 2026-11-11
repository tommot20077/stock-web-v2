package dowob.xyz.stockwebv2.marketdata.ingest;

import dowob.xyz.stockwebv2.common.event.PriceTickEvent;
import dowob.xyz.stockwebv2.marketdata.config.KafkaConfig;
import dowob.xyz.stockwebv2.marketdata.support.MarketDataContainers;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MarketDataIngestService} 整合測試 — 以共用 Kafka 容器驗證：
 * <ul>
 *   <li>{@code publishTick} 寫入注入的 tick topic 且 partition key 為 assetId 字串</li>
 *   <li>{@code publishBackfillTick} 寫入注入的 backfill topic 且 partition key 正確</li>
 *   <li>傳入 {@code null} 拋出 {@link NullPointerException}</li>
 * </ul>
 *
 * <p><strong>隔離(D-29)</strong>:Kafka 由 {@link MarketDataContainers#kafka()} 與其他 IT 共用,
 * 因此 tick / backfill topic 皆以 {@link MarketDataContainers#uniqueName(String)} 產生、consumer group 每次唯一,
 * 且 {@link #pollOne(KafkaConsumer, String)} 只接受 key 相符的那一筆,不取「第一筆」。</p>
 *
 * @author Yuan
 * @version 1.1.0
 */
class MarketDataIngestServiceIT {

    /** 本類別專用的 tick topic;正式名稱只當前綴,方便在 broker log 中辨識。 */
    static final String TICK_TOPIC = MarketDataContainers.uniqueName(KafkaConfig.TOPIC_PRICE_TICK);

    /** 本類別專用的 backfill topic。 */
    static final String BACKFILL_TOPIC = MarketDataContainers.uniqueName(KafkaConfig.TOPIC_PRICE_BACKFILL);

    /** {@link #pollOne(KafkaConsumer, String)} 等待相符訊息的上限。 */
    private static final long POLL_TIMEOUT_MS = 10_000;

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
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, MarketDataContainers.kafka().getBootstrapServers(),
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true
        );

        DefaultKafkaProducerFactory<String, Object> factory = new DefaultKafkaProducerFactory<>(
                producerProps, new StringSerializer(), valueSerializer);
        template = new KafkaTemplate<>(factory);
        service = new MarketDataIngestService(template, TICK_TOPIC, BACKFILL_TOPIC);

        // 手動建立 topics（Spring KafkaAdmin 未啟動）;共用 broker 下經 ensureTopics(已存在視為成功)
        try (AdminClient admin = AdminClient.create(
                Map.of("bootstrap.servers", MarketDataContainers.kafka().getBootstrapServers()))) {
            MarketDataContainers.ensureTopics(admin,
                    TopicBuilder.name(TICK_TOPIC).partitions(3).replicas((short) 1).build(),
                    TopicBuilder.name(BACKFILL_TOPIC).partitions(3).replicas((short) 1).build());
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

        assertThat(result.getRecordMetadata().topic()).isEqualTo(TICK_TOPIC);
        assertThat(result.getProducerRecord().key()).isEqualTo("42");

        // 以 consumer 驗證訊息確實寫入
        try (KafkaConsumer<String, PriceTickEvent> consumer = newConsumer(PriceTickEvent.class)) {
            consumer.subscribe(List.of(TICK_TOPIC));
            ConsumerRecord<String, PriceTickEvent> rec = pollOne(consumer, "42");
            assertThat(rec.key()).isEqualTo("42");
            assertThat(rec.value().symbol()).isEqualTo("AAPL");
            assertThat(rec.value().price()).isEqualByComparingTo("187.45");
        }
    }

    @Test
    void publishBackfillTick_sendsToBackfillTopic_withAssetIdAsKey() throws Exception {
        PriceTickEvent event = PriceTickEvent.of(7L, "BTC", new BigDecimal("50000"), null, "backfill");

        SendResult<String, Object> result = service.publishBackfillTick(event).get(5, TimeUnit.SECONDS);

        assertThat(result.getRecordMetadata().topic()).isEqualTo(BACKFILL_TOPIC);
        assertThat(result.getProducerRecord().key()).isEqualTo("7");

        // 以 consumer 驗證訊息確實寫入
        try (KafkaConsumer<String, PriceTickEvent> consumer = newConsumer(PriceTickEvent.class)) {
            consumer.subscribe(List.of(BACKFILL_TOPIC));
            ConsumerRecord<String, PriceTickEvent> rec = pollOne(consumer, "7");
            assertThat(rec.key()).isEqualTo("7");
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
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, MarketDataContainers.kafka().getBootstrapServers());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, MarketDataContainers.uniqueName("ingest-it"));
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        return new KafkaConsumer<>(consumerProps, new StringDeserializer(), valueDeserializer);
    }

    /**
     * 輪詢直到收到 key 等於 {@code expectedKey} 的訊息;其他 key 的訊息略過(D-29 風險處理)。
     *
     * @param consumer    已訂閱目標 topic 的 consumer
     * @param expectedKey 預期的 partition key(assetId 字串)
     * @param <V>         value 型別
     * @return 第一筆 key 相符的訊息
     * @throws AssertionError {@value #POLL_TIMEOUT_MS} ms 內沒收到相符訊息
     */
    private <V> ConsumerRecord<String, V> pollOne(KafkaConsumer<String, V> consumer, String expectedKey) {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, V> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, V> rec : records) {
                if (expectedKey.equals(rec.key())) {
                    return rec;
                }
            }
        }
        throw new AssertionError("No record with key " + expectedKey + " received within 10s");
    }
}
