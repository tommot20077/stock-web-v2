package dowob.xyz.stockwebv2.marketdata.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
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

import java.time.Duration;

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
 * <h2>Topic / consumer group 可由 property 覆寫(D-29)</h2>
 * <p>topic 與 listener group 以 {@code ${property:預設值}} placeholder 宣告,預設值逐字等於
 * {@link #TOPIC_PRICE_TICK} / {@link #TOPIC_PRICE_BACKFILL} / {@link #GROUP_PERSIST} /
 * {@link #GROUP_WS_BROADCAST}。placeholder <strong>只為讓整合測試在共用 Kafka broker 下以唯一 topic /
 * group 隔離</strong>;正式、{@code e2e-browser} 與 {@code stock-start} 整合測試都不設定這些 property,
 * 行為與改動前完全相同。預設值由 {@code KafkaTopicPlaceholderTest} 鎖住(T-05-15)。
 *
 * @author Yuan
 * @version 1.1.0
 */
@Configuration
public class KafkaConfig {

    /**
     * 行情 topic 保留 24 小時:即 consumer / DB 故障時可補讀的時間窗(2026-09-28 裁決)。
     * 1 小時太短(夜間故障隔天才處理即遺失),broker 預設 7 天對高頻行情太占磁碟。
     */
    static final String RETENTION_PRICE_MS = String.valueOf(Duration.ofDays(1).toMillis());

    /** DLT 保留 7 天,供事後調查;量很小。 */
    static final String RETENTION_DLT_MS = String.valueOf(Duration.ofDays(7).toMillis());

    /** market.price.tick.v1 — 即時 tick 事件 topic。 */
    public static final String TOPIC_PRICE_TICK = "market.price.tick.v1";

    /** market.price.backfill.v1 — 補資料事件 topic。 */
    public static final String TOPIC_PRICE_BACKFILL = "market.price.backfill.v1";

    /** market.price.tick.v1.DLT — 即時 tick 死信 topic(未覆寫 topic 時的預設名稱)。 */
    public static final String TOPIC_PRICE_TICK_DLT = TOPIC_PRICE_TICK + ".DLT";

    /** market.price.backfill.v1.DLT — 補資料死信 topic(未覆寫 topic 時的預設名稱)。 */
    public static final String TOPIC_PRICE_BACKFILL_DLT = TOPIC_PRICE_BACKFILL + ".DLT";

    /** {@code market-data.persist} — {@code PriceWriterConsumer} 的預設 consumer group。 */
    public static final String GROUP_PERSIST = "market-data.persist";

    /** {@code market-data.ws-broadcast} — {@code WsBroadcastConsumer} 的預設 consumer group。 */
    public static final String GROUP_WS_BROADCAST = "market-data.ws-broadcast";

    /** 覆寫即時 tick topic 名稱的 property;正式環境不設定(D-29,僅供 IT 隔離)。 */
    public static final String PROP_TOPIC_PRICE_TICK = "market-data.kafka.topics.price-tick";

    /** 覆寫補資料 topic 名稱的 property;正式環境不設定(D-29,僅供 IT 隔離)。 */
    public static final String PROP_TOPIC_PRICE_BACKFILL = "market-data.kafka.topics.price-backfill";

    /** 覆寫 {@code PriceWriterConsumer} consumer group 的 property;正式環境不設定(D-29)。 */
    public static final String PROP_GROUP_PERSIST = "market-data.kafka.groups.persist";

    /** 覆寫 {@code WsBroadcastConsumer} consumer group 的 property;正式環境不設定(D-29)。 */
    public static final String PROP_GROUP_WS_BROADCAST = "market-data.kafka.groups.ws-broadcast";

    /**
     * 即時 tick topic 的 placeholder,未設 {@value #PROP_TOPIC_PRICE_TICK} 時解析為 {@value #TOPIC_PRICE_TICK}。
     *
     * <p>必須是編譯期常數,才能放進 {@code @KafkaListener(topics = ...)} 與 {@code @Value}。
     */
    public static final String TOPIC_PRICE_TICK_PLACEHOLDER =
            "${" + PROP_TOPIC_PRICE_TICK + ":" + TOPIC_PRICE_TICK + "}";

    /** 補資料 topic 的 placeholder,未設 property 時解析為 {@value #TOPIC_PRICE_BACKFILL}。 */
    public static final String TOPIC_PRICE_BACKFILL_PLACEHOLDER =
            "${" + PROP_TOPIC_PRICE_BACKFILL + ":" + TOPIC_PRICE_BACKFILL + "}";

    /** persist consumer group 的 placeholder,未設 property 時解析為 {@value #GROUP_PERSIST}。 */
    public static final String GROUP_PERSIST_PLACEHOLDER =
            "${" + PROP_GROUP_PERSIST + ":" + GROUP_PERSIST + "}";

    /** ws-broadcast consumer group 的 placeholder,未設 property 時解析為 {@value #GROUP_WS_BROADCAST}。 */
    public static final String GROUP_WS_BROADCAST_PLACEHOLDER =
            "${" + PROP_GROUP_WS_BROADCAST + ":" + GROUP_WS_BROADCAST + "}";

    /** 死信 topic 後綴;DLT topic bean 與 {@link #kafkaErrorHandler} 的 resolver 共用,topic 被覆寫時 DLT 跟著變。 */
    private static final String DLT_SUFFIX = ".DLT";

    /**
     * 建立即時 tick topic(預設 {@value #TOPIC_PRICE_TICK})，3 partitions / replication factor 1。
     *
     * @param topic 由 {@value #TOPIC_PRICE_TICK_PLACEHOLDER} 解析的 topic 名稱
     * @return NewTopic 定義
     */
    @Bean
    NewTopic priceTickTopic(@Value(TOPIC_PRICE_TICK_PLACEHOLDER) String topic) {
        return TopicBuilder.name(topic)
                .partitions(3)
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, RETENTION_PRICE_MS)
                .build();
    }

    /**
     * 建立補資料 topic(預設 {@value #TOPIC_PRICE_BACKFILL})，3 partitions / replication factor 1。
     *
     * @param topic 由 {@value #TOPIC_PRICE_BACKFILL_PLACEHOLDER} 解析的 topic 名稱
     * @return NewTopic 定義
     */
    @Bean
    NewTopic priceBackfillTopic(@Value(TOPIC_PRICE_BACKFILL_PLACEHOLDER) String topic) {
        return TopicBuilder.name(topic)
                .partitions(3)
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, RETENTION_PRICE_MS)
                .build();
    }

    /**
     * 建立即時 tick 的死信 topic {@code <topic>.DLT}(預設 {@value #TOPIC_PRICE_TICK_DLT})，3 partitions / replication factor 1。
     *
     * <p>明確宣告 DLT topic bean，避免依賴 broker 的 {@code auto.create.topics.enable}，
     * 確保 prod 環境 partition 數一致。
     *
     * @param topic 由 {@value #TOPIC_PRICE_TICK_PLACEHOLDER} 解析的 topic 名稱
     * @return NewTopic 定義
     */
    @Bean
    NewTopic priceTickDltTopic(@Value(TOPIC_PRICE_TICK_PLACEHOLDER) String topic) {
        return TopicBuilder.name(topic + DLT_SUFFIX)
                .partitions(3)
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, RETENTION_DLT_MS)
                .build();
    }

    /**
     * 建立補資料的死信 topic {@code <topic>.DLT}(預設 {@value #TOPIC_PRICE_BACKFILL_DLT})，3 partitions / replication factor 1。
     *
     * @param topic 由 {@value #TOPIC_PRICE_BACKFILL_PLACEHOLDER} 解析的 topic 名稱
     * @return NewTopic 定義
     */
    @Bean
    NewTopic priceBackfillDltTopic(@Value(TOPIC_PRICE_BACKFILL_PLACEHOLDER) String topic) {
        return TopicBuilder.name(topic + DLT_SUFFIX)
                .partitions(3)
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, RETENTION_DLT_MS)
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
                    return new TopicPartition(record.topic() + DLT_SUFFIX, record.partition());
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
