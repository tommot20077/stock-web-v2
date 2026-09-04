package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.infrastructure.security.JwtProperties;
import dowob.xyz.stockwebv2.infrastructure.security.RateLimitProperties;
import dowob.xyz.stockwebv2.user.service.BrowserAuthCookieProperties;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(scanBasePackages = "dowob.xyz.stockwebv2")
@EnableConfigurationProperties({JwtProperties.class, BrowserAuthCookieProperties.class, RateLimitProperties.class})
public class StockWebV2Application {

    public static void main(String[] args) {
        SpringApplication.run(StockWebV2Application.class, args);
    }
}
