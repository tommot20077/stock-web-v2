package dowob.xyz.stockwebv2.marketdata.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.kafka.core.KafkaAdmin;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MarketDataHealthIndicator} 整合測試 — 使用 Testcontainers 啟動真實 Kafka。
 *
 * <ul>
 *   <li>Kafka 正常啟動 → health() 回傳 UP，且 details 包含 kafkaNodes</li>
 *   <li>Kafka 無法連線（dead port）→ health() 回傳 DOWN</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0.0
 */
@Testcontainers
class MarketDataHealthIndicatorIT {

    @Container
    static final ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @Test
    @DisplayName("Kafka 正常啟動 → health() 回傳 UP，details 包含 kafkaNodes")
    void health_whenKafkaUp_returnsUp() {
        KafkaAdmin admin = new KafkaAdmin(
                Map.of("bootstrap.servers", kafka.getBootstrapServers()));
        MarketDataHealthIndicator indicator = new MarketDataHealthIndicator(admin);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsKey("kafkaNodes");
        assertThat(health.getDetails()).containsKey("kafkaBootstrap");
    }

    @Test
    @DisplayName("Kafka 無法連線（dead port）→ health() 回傳 DOWN")
    void health_whenKafkaUnreachable_returnsDown() {
        KafkaAdmin admin = new KafkaAdmin(
                Map.of("bootstrap.servers", "localhost:1")); // 必然無法連線的 port
        MarketDataHealthIndicator indicator = new MarketDataHealthIndicator(admin);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("reason");
    }
}
