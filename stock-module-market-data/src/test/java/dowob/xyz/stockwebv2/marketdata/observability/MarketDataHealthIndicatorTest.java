package dowob.xyz.stockwebv2.marketdata.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.kafka.core.KafkaAdmin;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * {@link MarketDataHealthIndicator} 在 Kafka 不通時必須快速回報 DOWN。
 *
 * <p>原本以 try-with-resources 建立 {@code AdminClient}：{@code describeCluster().get(3s)} 逾時後，
 * {@code close()} 還會等在途請求直到 admin client 自己的預設 API 逾時（約 60 秒）。對應的 IT 實測要 71 秒
 * （2026-09-02 性能審查 MED-2）。在 K8s 上 liveness / readiness probe 的逾時通常只有幾秒，健康檢查一卡住，
 * Kafka 一掛就會連鎖把應用本身重啟——而重啟解決不了 Kafka 的問題。
 *
 * <p>這裡用必然連不上的位址，鎖住「DOWN 必須在時間預算內回來」。
 *
 * @author Yuan
 * @version 1.0.0
 */
@DisplayName("market-data 健康檢查在 Kafka 不通時快速回報")
class MarketDataHealthIndicatorTest {

    /**
     * 要攔的回歸是「卡在 AdminClient.close() 約 60 秒」,所以上限只需與 60 秒明顯區隔。
     * 健康檢查自身預算 3 秒;原本只留 3 秒緩衝,在共用 CI runner 冷啟動(首次建立 KafkaAdminClient 的類別載入、
     * JMX metrics 初始化,且與其他模組並行)時跑到 6.08 秒而偶發失敗(2026-09-28,PR #46)。15 秒仍遠低於 60 秒。
     */
    private static final Duration BUDGET = Duration.ofSeconds(15);

    @Test
    @DisplayName("Kafka 無法連線時在預算內回傳 DOWN，而不是卡在 AdminClient.close()")
    void returnsDownWithinBudgetWhenKafkaUnreachable() {
        KafkaAdmin admin = new KafkaAdmin(Map.of("bootstrap.servers", "localhost:1"));
        MarketDataHealthIndicator indicator = new MarketDataHealthIndicator(admin);

        Health health = assertTimeoutPreemptively(BUDGET, () -> indicator.health());

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("reason");
    }
}
