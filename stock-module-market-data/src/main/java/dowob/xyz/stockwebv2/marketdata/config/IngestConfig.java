package dowob.xyz.stockwebv2.marketdata.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 行情擷取向 provider 取價所用的執行緒池。
 *
 * <p>執行緒數決定同時能向 provider 發出多少請求;佇列有界,滿了視為該資產本輪失敗而不是無限堆積。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Configuration
public class IngestConfig {

    /**
     * @param threads 平行取價的執行緒數
     * @return 由 Spring 管理生命週期的執行緒池
     */
    @Bean(name = "ingestFetchExecutor")
    public ThreadPoolTaskExecutor ingestFetchExecutor(@Value("${market-data.ingestor.fetch-threads:8}") int threads) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(1_000);
        executor.setThreadNamePrefix("ingest-fetch-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
