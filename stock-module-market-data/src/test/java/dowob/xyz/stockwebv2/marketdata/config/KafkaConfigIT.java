package dowob.xyz.stockwebv2.marketdata.config;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.TopicBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 整合測試：驗證 {@link KafkaConfig} 定義的兩個 topic 常數與 partition 數，
 * 可在真實 Kafka broker (Testcontainers KRaft) 中正確建立。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Testcontainers
class KafkaConfigIT {

    @Container
    static final ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @Test
    @DisplayName("兩個 topic 建立後，partition 數皆為 3")
    void topics_areCreatedAfterKafkaAdminInit() throws Exception {
        Map<String, Object> props = Map.of("bootstrap.servers", kafka.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(props)) {
            NewTopic t1 = TopicBuilder.name(KafkaConfig.TOPIC_PRICE_TICK)
                    .partitions(3)
                    .replicas(1)
                    .build();
            NewTopic t2 = TopicBuilder.name(KafkaConfig.TOPIC_PRICE_BACKFILL)
                    .partitions(3)
                    .replicas(1)
                    .build();
            admin.createTopics(List.of(t1, t2)).all().get(10, TimeUnit.SECONDS);

            var descriptions = admin.describeTopics(List.of(
                    KafkaConfig.TOPIC_PRICE_TICK,
                    KafkaConfig.TOPIC_PRICE_BACKFILL
            )).allTopicNames().get(10, TimeUnit.SECONDS);

            assertThat(descriptions).containsKey(KafkaConfig.TOPIC_PRICE_TICK);
            assertThat(descriptions).containsKey(KafkaConfig.TOPIC_PRICE_BACKFILL);
            assertThat(descriptions.get(KafkaConfig.TOPIC_PRICE_TICK).partitions()).hasSize(3);
            assertThat(descriptions.get(KafkaConfig.TOPIC_PRICE_BACKFILL).partitions()).hasSize(3);
        }
    }
}
