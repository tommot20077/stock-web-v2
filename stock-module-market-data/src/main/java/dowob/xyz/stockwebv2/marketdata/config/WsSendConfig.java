package dowob.xyz.stockwebv2.marketdata.config;

import dowob.xyz.stockwebv2.marketdata.ws.SessionSendDispatcher;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * WS 廣播實際寫出所用的執行緒池與派發器。
 *
 * <p>執行緒數決定「同時能被慢客戶端卡住多少條而仍不影響其他人」；佇列只放 drain 任務（每個 session
 * 同時最多一個），所以容量上限約等於同時有待送訊息的 session 數。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Configuration
public class WsSendConfig {

    /**
     * @param threads 寫出執行緒數
     * @return 由 Spring 管理生命週期（關閉時等待）的執行緒池
     */
    @Bean(name = "wsSendExecutor")
    public ThreadPoolTaskExecutor wsSendExecutor(@Value("${market-data.ws.send-threads:4}") int threads) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(10_000);
        executor.setThreadNamePrefix("ws-send-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    /**
     * @param wsSendExecutor       寫出執行緒池
     * @param maxPendingPerSession 單一 session 的待送上限，超過即視為慢消費者關閉
     * @return WS 送出派發器
     */
    @Bean
    public SessionSendDispatcher sessionSendDispatcher(
            ThreadPoolTaskExecutor wsSendExecutor,
            @Value("${market-data.ws.max-pending-per-session:256}") int maxPendingPerSession) {
        return new SessionSendDispatcher(wsSendExecutor, maxPendingPerSession);
    }
}
