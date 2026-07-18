package dowob.xyz.stockwebv2.start.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
public abstract class ContainerIT {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
        DockerImageName.parse("timescale/timescaledb:2.17.2-pg16")
                       .asCompatibleSubstituteFor("postgres")
    )
        .withDatabaseName("stock_v2_test")
        .withUsername("stock")
        .withPassword("stock");

    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
        .withExposedPorts(6379);

    static final ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(
        DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    static {
        Startables.deepStart(postgres, redis, kafka).join();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registerContainerProperties(registry);
        // 共用 IT 套件預設關閉限流,避免 per-IP 計數跨測試互相污染。
        // 需驗證限流 / 鎖定行為的測試請改用自身的 @DynamicPropertySource 覆寫為 true
        // （見 AuthRateLimitAndLockoutIT）。
        registry.add("stock.security.rate-limit.enabled", () -> false);
    }

    /**
     * 註冊共用容器（Postgres / Redis / Kafka）與資料源 / Flyway / management 埠等基礎屬性。
     *
     * <p>供本類的 {@link #properties} 使用,亦供無法直接繼承本類、但需相同容器 wiring 的測試
     * （如 {@code AuthRateLimitAndLockoutIT} 需自行掌控限流開關）呼叫,以免複製整段容器設定,
     * 同時讓容器欄位維持 package-private。呼叫本方法會觸發本類初始化並啟動共用容器。</p>
     *
     * @param registry 動態屬性註冊器
     */
    public static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", () -> 0);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.mixed", () -> true);
        registry.add("management.server.port", () -> 11180);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }
}
