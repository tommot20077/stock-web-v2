package dowob.xyz.stockwebv2.start;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "dowob.xyz.stockwebv2")
public class StockWebV2Application {

    public static void main(String[] args) {
        SpringApplication.run(StockWebV2Application.class, args);
    }
}
