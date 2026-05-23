package dowob.xyz.stockwebv2.marketdata.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 開啟 Spring {@code @Scheduled} 支援，控制 ScheduledIngestor 等定時任務是否啟動。
 *
 * <p>預設啟用（matchIfMissing = true），測試環境可透過
 * {@code market-data.scheduling.enabled=false} 關閉，避免排程任務干擾測試。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
        prefix = "market-data.scheduling",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class SchedulingConfig {
}
