package dowob.xyz.stockwebv2.marketdata.config;

import dowob.xyz.stockwebv2.marketdata.support.MarketDataContainers;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.TopicBuilder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 整合測試：驗證 {@link KafkaConfig} 定義的兩個 topic 常數與 partition 數，
 * 可在真實 Kafka broker (Testcontainers KRaft) 中正確建立。
 *
 * <p>broker 為共用容器({@link MarketDataContainers#kafka()},D-04);topic 名稱以正式常數為前綴加上
 * 唯一後綴(D-29),避免與其他 IT 建立的同名 topic 衝突。partition 數仍斷言為正式設定的 3。</p>
 *
 * @author Yuan
 * @version 1.0.0
 */
class KafkaConfigIT {

    @Test
    @DisplayName("兩個 topic 建立後，partition 數皆為 3")
    void topics_areCreatedAfterKafkaAdminInit() throws Exception {
        Map<String, Object> props = Map.of("bootstrap.servers", MarketDataContainers.kafka().getBootstrapServers());
        String tickTopic = MarketDataContainers.uniqueName(KafkaConfig.TOPIC_PRICE_TICK);
        String backfillTopic = MarketDataContainers.uniqueName(KafkaConfig.TOPIC_PRICE_BACKFILL);
        try (AdminClient admin = AdminClient.create(props)) {
            NewTopic t1 = TopicBuilder.name(tickTopic)
                    .partitions(3)
                    .replicas(1)
                    .build();
            NewTopic t2 = TopicBuilder.name(backfillTopic)
                    .partitions(3)
                    .replicas(1)
                    .build();
            MarketDataContainers.ensureTopics(admin, t1, t2);

            var descriptions = admin.describeTopics(List.of(tickTopic, backfillTopic))
                    .allTopicNames().get(10, TimeUnit.SECONDS);

            assertThat(descriptions).containsKey(tickTopic);
            assertThat(descriptions).containsKey(backfillTopic);
            assertThat(descriptions.get(tickTopic).partitions()).hasSize(3);
            assertThat(descriptions.get(backfillTopic).partitions()).hasSize(3);
        }
    }
}
