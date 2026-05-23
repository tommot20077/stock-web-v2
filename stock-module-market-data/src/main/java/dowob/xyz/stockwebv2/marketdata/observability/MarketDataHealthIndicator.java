package dowob.xyz.stockwebv2.marketdata.observability;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.KafkaAdminClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 自訂 health indicator — 檢查 Kafka broker 可達性。
 *
 * <p>名稱 {@code marketData} 出現在 {@code /actuator/health} 回應的
 * {@code components.marketData} 欄位下。
 *
 * <p>檢查邏輯：透過 {@link KafkaAdminClient} 呼叫 {@code describeCluster()}，
 * 若在 3 秒內取得 node 清單則視為健康（UP）；逾時或連線失敗則回傳 DOWN。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Component("marketData")
public class MarketDataHealthIndicator implements HealthIndicator {

    private final KafkaAdmin kafkaAdmin;

    /**
     * 建構 MarketDataHealthIndicator。
     *
     * @param kafkaAdmin Spring Kafka admin，提供 bootstrap.servers 等設定屬性；不可為 null
     */
    public MarketDataHealthIndicator(KafkaAdmin kafkaAdmin) {
        this.kafkaAdmin = kafkaAdmin;
    }

    /**
     * 執行 Kafka cluster 可達性檢查。
     *
     * <p>成功時回傳 {@code UP}，details 包含：
     * <ul>
     *   <li>{@code kafkaNodes} — cluster 節點數量</li>
     *   <li>{@code kafkaBootstrap} — bootstrap servers 位址</li>
     * </ul>
     *
     * <p>失敗時回傳 {@code DOWN}，details 包含：
     * <ul>
     *   <li>{@code reason} — 固定文字 {@code "Kafka cluster unreachable"}</li>
     *   <li>exception 資訊由 {@link Health#down(Exception)} 附加</li>
     * </ul>
     *
     * @return health 狀態物件；不會為 null
     */
    @Override
    public Health health() {
        Map<String, Object> config = kafkaAdmin.getConfigurationProperties();
        try (AdminClient admin = KafkaAdminClient.create(config)) {
            int nodeCount = admin.describeCluster()
                    .nodes()
                    .get(3, TimeUnit.SECONDS)
                    .size();
            return Health.up()
                    .withDetail("kafkaNodes", nodeCount)
                    .withDetail("kafkaBootstrap", config.get("bootstrap.servers"))
                    .build();
        } catch (Exception ex) {
            return Health.down(ex)
                    .withDetail("reason", "Kafka cluster unreachable")
                    .build();
        }
    }
}
