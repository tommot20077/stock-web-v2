package dowob.xyz.stockwebv2.marketdata.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka 設定 — 自動建立 market-data 模組使用的兩個 topic。
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
}
