package dowob.xyz.stockwebv2.marketdata.observability;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.KafkaAdminClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
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
    /** 整個健康檢查的時間預算。K8s probe 的逾時通常只有幾秒，這裡不能比它久。 */
    private static final Duration BUDGET = Duration.ofSeconds(3);

    @Override
    public Health health() {
        Map<String, Object> config = boundedConfig(kafkaAdmin.getConfigurationProperties());
        AdminClient admin = KafkaAdminClient.create(config);
        try {
            int nodeCount = admin.describeCluster()
                    .nodes()
                    .get(BUDGET.toMillis(), TimeUnit.MILLISECONDS)
                    .size();
            return Health.up()
                    .withDetail("kafkaNodes", nodeCount)
                    .withDetail("kafkaBootstrap", config.get("bootstrap.servers"))
                    .build();
        } catch (Exception ex) {
            return Health.down(ex)
                    .withDetail("reason", "Kafka cluster unreachable")
                    .build();
        } finally {
            // 不能用 try-with-resources：預設的 close() 會等在途請求直到 admin client 自己的 API 逾時（約 60 秒）。
            // Kafka 不通時 get() 已經逾時回來，這裡再卡一分鐘，probe 就會判定應用本身不健康而重啟它
            // ——而重啟解決不了 Kafka 的問題（性能審查 MED-2）。
            admin.close(Duration.ZERO);
        }
    }

    /**
     * 在共用的 Kafka 設定上，把這個短命 admin client 的逾時壓進健康檢查的預算。
     *
     * @param base Spring 管理的 KafkaAdmin 設定（不修改原物件）
     * @return 加上逾時限制的副本
     */
    private static Map<String, Object> boundedConfig(Map<String, Object> base) {
        Map<String, Object> config = new HashMap<>(base);
        int budgetMs = (int) BUDGET.toMillis();
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, budgetMs);
        config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, budgetMs);
        return config;
    }
}
