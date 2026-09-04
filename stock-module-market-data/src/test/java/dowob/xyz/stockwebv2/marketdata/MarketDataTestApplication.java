package dowob.xyz.stockwebv2.marketdata;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 測試用 Spring Boot Application — 供 {@code @WebMvcTest} 等 Spring Test 切片使用。
 *
 * <p>不在生產路徑，僅作為 {@code @WebMvcTest} 尋找 {@code @SpringBootConfiguration}
 * 的錨點，使切片測試可正確啟動 Spring 應用程式上下文。
 *
 * @author Yuan
 * @version 1.0.0
 */
@SpringBootApplication
public class MarketDataTestApplication {
}
