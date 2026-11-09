package dowob.xyz.stockwebv2.marketdata.api;

import dowob.xyz.stockwebv2.marketdata.support.MarketDataContainers;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BackfillIdempotencyService} 的 Redis 整合測試。
 *
 * <p>使用共用 Redis 容器({@link MarketDataContainers})的 database index {@value #REDIS_DATABASE}，
 * 驗證 SET NX EX 語義(D-04;清理用 {@code flushDb},不影響其他 IT 的 index)。
 *
 * @author Yuan
 * @version 1.0.0
 */
class BackfillIdempotencyServiceIT {

    /** 本類專用的 Redis database index;WsTicketServiceIT 用 1,勿重複。 */
    static final int REDIS_DATABASE = 2;

    static StringRedisTemplate template;
    static BackfillIdempotencyService service;

    @BeforeAll
    static void setup() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
            MarketDataContainers.redis().getHost(), MarketDataContainers.redis().getMappedPort(6379));
        config.setDatabase(REDIS_DATABASE);
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        service = new BackfillIdempotencyService(template);
    }

    @BeforeEach
    void flush() {
        template.getConnectionFactory().getConnection().serverCommands().flushDb();
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
