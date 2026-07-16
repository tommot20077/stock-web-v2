package dowob.xyz.stockwebv2.marketdata.api;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BackfillIdempotencyService} 的 Redis 整合測試。
 *
 * <p>使用 Testcontainers 啟動 Redis 7.4-alpine，驗證 SET NX EX 語義。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Testcontainers
class BackfillIdempotencyServiceIT {

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(
        DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    static StringRedisTemplate template;
    static BackfillIdempotencyService service;

    @BeforeAll
    static void setup() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)));
        factory.afterPropertiesSet();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        service = new BackfillIdempotencyService(template);
    }

    @BeforeEach
    void flush() {
        template.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    @DisplayName("第一次 acquire → true（成功獲取鎖）")
    void firstAcquire_returnsTrue() {
        assertThat(service.tryAcquire("key1")).isTrue();
    }

    @Test
    @DisplayName("相同 key 第二次 acquire → false（鎖已被持有）")
    void secondAcquire_sameKey_returnsFalse() {
        service.tryAcquire("key1");
        assertThat(service.tryAcquire("key1")).isFalse();
    }

    @Test
    @DisplayName("不同 key 各自獨立，均可 acquire")
    void differentKeys_areIndependent() {
        assertThat(service.tryAcquire("a")).isTrue();
        assertThat(service.tryAcquire("b")).isTrue();
    }

    @Test
    @DisplayName("KEY_PREFIX 正確加入到 Redis key 中")
    void keyPrefix_isApplied() {
        service.tryAcquire("mykey");
        Boolean exists = template.hasKey(BackfillIdempotencyService.KEY_PREFIX + "mykey");
        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("TTL 應為 1 小時（3600 秒），誤差在 5 秒內")
    void ttl_isApproximatelyOneHour() {
        service.tryAcquire("ttlkey");
        Long ttl = template.getExpire(BackfillIdempotencyService.KEY_PREFIX + "ttlkey",
            java.util.concurrent.TimeUnit.SECONDS);
        assertThat(ttl).isNotNull()
            .isGreaterThan(3595L)
            .isLessThanOrEqualTo(3600L);
    }
}
