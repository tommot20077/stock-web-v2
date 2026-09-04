package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.error.RateLimitExceededException;
import dowob.xyz.stockwebv2.infrastructure.security.RateLimitProperties;
import dowob.xyz.stockwebv2.infrastructure.security.RateLimitService;
import dowob.xyz.stockwebv2.start.support.ContainerIT;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RateLimitService} 對真 Redis 的固定視窗限流行為整合測試（security.md §15）。
 *
 * <p>驗證 #3 加固重構(改以原子 Lua {@code INCR + 首次 PEXPIRE})後,固定視窗語意維持不變:
 * 視窗內達門檻即拒絕、計數桶帶有 TTL、不同 identity 各自獨立。</p>
 *
 * <p>共用 IT 套件預設關閉限流(見 {@link ContainerIT}),故此處以自建 {@code enabled=true} 的
 * {@link RateLimitProperties} 直接建立受測服務,只借用容器提供的真實 Redis。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("認證端點限流 — Redis 固定視窗")
class RateLimitServiceIT extends ContainerIT {

    private static final int LIMIT = 3;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    @Autowired
    StringRedisTemplate redisTemplate;

    private RateLimitService service;
    private final RateLimitProperties.Rule rule = new RateLimitProperties.Rule(LIMIT, WINDOW);

    @BeforeEach
    void setup() {
        RateLimitProperties properties = new RateLimitProperties(true, null, null, null, null);
        service = new RateLimitService(redisTemplate, properties);
    }

    @Test
    @DisplayName("視窗內達門檻後拋 AUTH_RATE_LIMITED 並帶 Retry-After")
    void throttlesAfterLimit() {
        String identity = "10.0.0.100";
        redisTemplate.delete("rl:login:" + identity);

        for (int i = 0; i < LIMIT; i++) {
            assertThatCode(() -> service.enforce("login", identity, rule)).doesNotThrowAnyException();
        }

        assertThatThrownBy(() -> service.enforce("login", identity, rule))
            .isInstanceOfSatisfying(RateLimitExceededException.class, exception -> {
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.AUTH_RATE_LIMITED);
                assertThat(exception.retryAfterSeconds()).isGreaterThan(0);
            });
    }

    @Test
    @DisplayName("視窗計數桶於首次請求即帶有 TTL")
    void firstRequestSetsWindowTtl() {
        String identity = "10.0.0.101";
        String key = "rl:login:" + identity;
        redisTemplate.delete(key);

        service.enforce("login", identity, rule);

        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttl).isNotNull().isGreaterThan(0).isLessThanOrEqualTo(WINDOW.toSeconds());
    }

    @Test
    @DisplayName("不同 identity 各自獨立計數")
    void identitiesAreIsolated() {
        String a = "10.0.0.102";
        String b = "10.0.0.103";
        redisTemplate.delete("rl:login:" + a);
        redisTemplate.delete("rl:login:" + b);

        for (int i = 0; i < LIMIT; i++) {
            service.enforce("login", a, rule);
        }

        // a 已達門檻,b 仍可正常通過
        assertThatCode(() -> service.enforce("login", b, rule)).doesNotThrowAnyException();
    }
}
