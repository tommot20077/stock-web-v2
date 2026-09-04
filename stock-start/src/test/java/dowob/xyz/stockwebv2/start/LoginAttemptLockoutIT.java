package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.error.RateLimitExceededException;
import dowob.xyz.stockwebv2.infrastructure.security.RateLimitProperties;
import dowob.xyz.stockwebv2.start.support.ContainerIT;
import dowob.xyz.stockwebv2.user.service.LoginAttemptService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link LoginAttemptService} 對真 Redis 的鎖定行為整合測試（security.md §15）。
 *
 * <p>聚焦兩個修補點:</p>
 * <ul>
 *   <li>#3 原子性:每次 {@code recordFailure} 都必須讓計數 key 帶有 TTL,不得殘留無 TTL 的永久計數;</li>
 *   <li>#2 時長語意:鎖定時長以最後一次失敗起算的完整 {@code lockout.duration},而非只在首次失敗設定。</li>
 * </ul>
 *
 * <p>共用 IT 套件預設關閉限流(見 {@link ContainerIT}),故此處以自建 {@code enabled=true} 的
 * {@link RateLimitProperties} 直接建立受測服務,只借用容器提供的真實 Redis。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("登入失敗鎖定 — Redis 行為")
class LoginAttemptLockoutIT extends ContainerIT {

    private static final String FAIL_KEY_PREFIX = "user:login:fail:";
    private static final int THRESHOLD = 3;
    private static final Duration DURATION = Duration.ofMinutes(15);

    @Autowired
    StringRedisTemplate redisTemplate;

    private LoginAttemptService service;

    @BeforeEach
    void setup() {
        RateLimitProperties properties = new RateLimitProperties(
            true, null, null, null, new RateLimitProperties.Lockout(THRESHOLD, DURATION));
        service = new LoginAttemptService(redisTemplate, properties);
    }

    @Test
    @DisplayName("首次失敗即為計數 key 設定 TTL")
    void firstFailureSetsTtl() {
        Long userId = 9001L;
        String key = FAIL_KEY_PREFIX + userId;
        redisTemplate.delete(key);

        service.recordFailure(userId);

        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttl).isNotNull().isGreaterThan(0).isLessThanOrEqualTo(DURATION.toSeconds());
    }

    @Test
    @DisplayName("計數 key 若無 TTL,後續失敗必須補回 TTL(不得殘留永久計數)")
    void subsequentFailureAlwaysRefreshesTtl() {
        Long userId = 9002L;
        String key = FAIL_KEY_PREFIX + userId;
        // 模擬 #3:計數已存在但先前 EXPIRE 漏設,key 為永久(TTL = -1)
        redisTemplate.delete(key);
        redisTemplate.opsForValue().set(key, "1");
        assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS)).isEqualTo(-1);

        service.recordFailure(userId);

        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttl).isNotNull().isGreaterThan(0).isLessThanOrEqualTo(DURATION.toSeconds());
    }

    @Test
    @DisplayName("連續失敗達門檻後 assertNotLocked 拋 AUTH_ACCOUNT_LOCKED 並帶 Retry-After")
    void locksAfterThreshold() {
        Long userId = 9003L;
        redisTemplate.delete(FAIL_KEY_PREFIX + userId);

        for (int i = 0; i < THRESHOLD; i++) {
            service.recordFailure(userId);
        }

        assertThatThrownBy(() -> service.assertNotLocked(userId))
            .isInstanceOfSatisfying(RateLimitExceededException.class, exception -> {
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.AUTH_ACCOUNT_LOCKED);
                assertThat(exception.retryAfterSeconds()).isGreaterThan(0);
            });
    }

    @Test
    @DisplayName("reset 清除計數,解除鎖定")
    void resetClearsLock() {
        Long userId = 9004L;
        for (int i = 0; i < THRESHOLD; i++) {
            service.recordFailure(userId);
        }

        service.reset(userId);

        service.assertNotLocked(userId); // 不應拋出
        assertThat(redisTemplate.hasKey(FAIL_KEY_PREFIX + userId)).isFalse();
    }
}
