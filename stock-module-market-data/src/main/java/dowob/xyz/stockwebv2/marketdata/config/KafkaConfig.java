package dowob.xyz.stockwebv2.marketdata.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Kafka 設定 — 自動建立 market-data 模組使用的兩個主要 topic 與對應的
 * Dead Letter Topic (DLT)，並提供批次消費所需的
 * {@code batchKafkaListenerContainerFactory}。
 *
 * <p>Producer/Consumer 細部設定走 application.yaml 的 spring.kafka.* 標準屬性
 * (acks=all, enable.idempotence=true, JSON serde)，由 Spring Boot
 * auto-config 套用。
 *
 * <p>Topic partition key 由 producer 端以 assetId 控制，
 * 確保相同資產的 tick 事件落在同一 partition 保序。
 *
 * <h2>Retry 與 DLT 策略</h2>
 * <p>批次監聽器（batch listener）不支援 {@code @RetryableTopic}（非阻塞式 retry）。
 * 因此採用 {@link DefaultErrorHandler} + {@link DeadLetterPublishingRecoverer}：
 * <ul>
 *   <li>固定間隔 backoff retry 3 次（1s、1s、1s）</li>
 *   <li>最終失敗後將訊息發送至 {@code <topic>.DLT}</li>
 *   <li>每次失敗計入 {@code market.tick.persist.failed} metric</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0.0
 */
@Configuration
public class KafkaConfig {

    /** market.price.tick.v1 — 即時 tick 事件 topic。 */
    public static final String TOPIC_PRICE_TICK = "market.price.tick.v1";

    /** market.price.backfill.v1 — 補資料事件 topic。 */
    public static final String TOPIC_PRICE_BACKFILL = "market.price.backfill.v1";

    /** market.price.tick.v1.DLT — 即時 tick 死信 topic。 */
    public static final String TOPIC_PRICE_TICK_DLT = TOPIC_PRICE_TICK + ".DLT";

    /** market.price.backfill.v1.DLT — 補資料死信 topic。 */
    public static final String TOPIC_PRICE_BACKFILL_DLT = TOPIC_PRICE_BACKFILL + ".DLT";

    /**
     * 建立 {@value #TOPIC_PRICE_TICK} topic，3 partitions / replication factor 1。
     *
     * @return NewTopic 定義
     */
    @Bean
    NewTopic priceTickTopic() {
        return TopicBuilder.name(TOPIC_PRICE_TICK)
                .partitions(3)
                .replicas(1)
                .build();
    }

    /**
     * 建立 {@value #TOPIC_PRICE_BACKFILL} topic，3 partitions / replication factor 1。
     *
     * @return NewTopic 定義
     */
    @Bean
    NewTopic priceBackfillTopic() {
        return TopicBuilder.name(TOPIC_PRICE_BACKFILL)
                .partitions(3)
                .replicas(1)
                .build();
    }

    /**
     * 建立 {@value #TOPIC_PRICE_TICK_DLT} 死信 topic，3 partitions / replication factor 1。
     *
     * <p>明確宣告 DLT topic bean，避免依賴 broker 的 {@code auto.create.topics.enable}，
     * 確保 prod 環境 partition 數一致。
     *
     * @return NewTopic 定義
     */
    @Bean
    NewTopic priceTickDltTopic() {
        return TopicBuilder.name(TOPIC_PRICE_TICK_DLT)
                .partitions(3)
                .replicas(1)
                .build();
    }

    /**
     * 建立 {@value #TOPIC_PRICE_BACKFILL_DLT} 死信 topic，3 partitions / replication factor 1。
     *
     * @return NewTopic 定義
     */
    @Bean
    NewTopic priceBackfillDltTopic() {
        return TopicBuilder.name(TOPIC_PRICE_BACKFILL_DLT)
                .partitions(3)
                .replicas(1)
                .build();
    }

    /**
     * Kafka 批次消費錯誤處理器 — 搭配 DLT recoverer 與固定間隔 backoff retry。
     *
     * <p>批次監聽器不支援 {@code @RetryableTopic}，因此使用
     * {@link DefaultErrorHandler} 搭配 {@link FixedBackOff}：
     * <ul>
     *   <li>每次失敗等待 1000ms（1 秒）後重試，最多 3 次</li>
     *   <li>第 4 次仍失敗 → {@link DeadLetterPublishingRecoverer} 將訊息
     *       發送至 {@code <topic>.DLT}</li>
     *   <li>每次失敗計入 {@code market.tick.persist.failed} counter metric</li>
     * </ul>
     *
     * @param kafkaTemplate   用於 DLT 發布的 Kafka template（Spring Boot auto-config 建立）
     * @param meterRegistry   Micrometer registry，用於記錄失敗 metric
     * @return 設定好 DLT 與 retry 的 {@link DefaultErrorHandler}
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            KafkaTemplate<String, Object> kafkaTemplate,
            MeterRegistry meterRegistry) {

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> {
                    // 每筆失敗計入 metric（T27 時再補完整 tag 設定）
                    meterRegistry.counter("market.tick.persist.failed",
                            "topic", record.topic(),
                            "reason", ex.getClass().getSimpleName()).increment();
                    return new TopicPartition(record.topic() + ".DLT", record.partition());
                });

        // FixedBackOff(intervalMs, maxAttempts)：1s 間隔，最多 retry 3 次
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
    }

    /**
     * 單筆 Kafka Listener Container Factory — 供 {@code WsBroadcastConsumer} 使用。
     *
     * <p>Ack mode 明確設為 {@link ContainerProperties.AckMode#RECORD}：每筆處理成功後由
     * Spring Kafka commit offset。<strong>不可省略而改用預設 factory</strong> —— 預設 factory 會套用
     * {@code application.yaml} 的全域 {@code spring.kafka.listener.ack-mode}，而
     * {@code WsBroadcastConsumer.onTick} 的簽章沒有 {@link org.springframework.kafka.support.Acknowledgment}
     * 參數。若全域值是 {@code manual}，這個 listener 就<strong>永遠不會 commit offset</strong>，
     * 搭配 {@code auto-offset-reset: earliest} 會讓 consumer group 在每次應用重啟時重播整個 tick topic
     * （Redis latest cache 被舊價覆寫、WS 客戶端收到歷史 tick 洪流）。此不變量由
     * {@code KafkaAckModeTest} 鎖住。
     *
     * <p>失敗路徑沿用與批次消費相同的 {@link DefaultErrorHandler}：retry 3 次後進 DLT，
     * 由錯誤處理器負責推進 offset，不會卡在同一筆。
     *
     * @param consumerFactory   Spring Boot auto-config 建立的 consumer factory
     * @param kafkaErrorHandler 包含 DLT recoverer 與 backoff retry 的錯誤處理器
     * @return 單筆模式且每筆 commit offset 的 {@link ConcurrentKafkaListenerContainerFactory}
     */
    @Bean(name = "wsBroadcastKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, Object> wsBroadcastKafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory,
            DefaultErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }

    /**
     * 批次 Kafka Listener Container Factory — 供 {@code PriceWriterConsumer} 使用。
     *
     * <p>啟用 {@code batchListener = true}，讓 {@code @KafkaListener} 可接收
     * {@code List<PriceTickEvent>}，一次 batch insert 避免 N 次 round-trip。
     * Ack mode 設為 {@link ContainerProperties.AckMode#BATCH}，
     * 整批成功後由 Spring Kafka 自動 commit offset，不需手動呼叫 {@code Acknowledgment}。
     *
     * <p>{@code consumerFactory} 由 Spring Boot auto-config 根據
     * {@code spring.kafka.consumer.*} 屬性自動建立並注入。
     *
     * @param consumerFactory Spring Boot auto-config 建立的 consumer factory
     * @param kafkaErrorHandler 包含 DLT recoverer 與 backoff retry 的錯誤處理器
     * @return 批次模式且具備 DLT retry 能力的 {@link ConcurrentKafkaListenerContainerFactory}
     */
    @Bean(name = "batchKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, Object> batchKafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory,
            DefaultErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }
}
