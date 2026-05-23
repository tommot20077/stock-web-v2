package dowob.xyz.stockwebv2.marketdata.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

/**
 * Kafka 設定 — 自動建立 market-data 模組使用的兩個 topic，
 * 並提供批次消費所需的 {@code batchKafkaListenerContainerFactory}。
 *
 * <p>Producer/Consumer 細部設定走 application.yaml 的 spring.kafka.* 標準屬性
 * (acks=all, enable.idempotence=true, JSON serde)，由 Spring Boot
 * auto-config 套用。
 *
 * <p>Topic partition key 由 producer 端以 assetId 控制，
 * 確保相同資產的 tick 事件落在同一 partition 保序。
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
     * 批次 Kafka Listener Container Factory — 供 {@code PriceWriterConsumer} 使用。
     *
     * <p>啟用 {@code batchListener = true}，讓 {@code @KafkaListener} 可接收
     * {@code List<PriceTickEvent>}，一次 batch insert 避免 N 次 round-trip。
     * Ack mode 設為 {@link ContainerProperties.AckMode#BATCH}，
     * 整批成功後由 Spring Kafka 自動 commit offset，與 global {@code manual}
     * 設定分離，不需手動呼叫 {@code Acknowledgment}。
     *
     * <p>{@code consumerFactory} 由 Spring Boot auto-config 根據
     * {@code spring.kafka.consumer.*} 屬性自動建立並注入。
     *
     * @param consumerFactory Spring Boot auto-config 建立的 consumer factory
     * @return 批次模式的 {@link ConcurrentKafkaListenerContainerFactory}
     */
    @Bean(name = "batchKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, Object> batchKafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);
        return factory;
    }
}
