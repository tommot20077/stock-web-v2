package dowob.xyz.stockwebv2.infrastructure.security;

import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.error.RateLimitExceededException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 以 Redis 固定視窗計數實作的認證端點限流服務（security.md §15）。
 *
 * <p>Resilience4j 於架構規劃屬 Phase 3，故 Phase 1 的認證端點限流以 Redis {@code INCR + EXPIRE}
 * 手動實作。超過門檻時拋出 {@link RateLimitExceededException}，由 GlobalExceptionHandler 轉為
 * HTTP 429 並帶 {@code Retry-After} 標頭。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Service
public class RateLimitService {

    /**
     * 限流計數的 Redis key 前綴。
     */
    private static final String KEY_PREFIX = "rl:";

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties properties;

    public RateLimitService(StringRedisTemplate redisTemplate, RateLimitProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * 對指定計數桶執行限流檢查；超過門檻即拋出 {@link RateLimitExceededException}。
     * 當限流整體停用（{@code enabled=false}）時為無操作。
     *
     * @param bucket   計數桶名稱（如 {@code login} / {@code register} / {@code refresh}）
     * @param identity 計數維度識別（IP 或使用者 id）
     * @param rule     套用的限流規則
     */
    public void enforce(String bucket, String identity, RateLimitProperties.Rule rule) {
        if (!properties.isEnabled()) {
            return;
        }

        String key = KEY_PREFIX + bucket + ":" + identity;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, rule.window());
        }
        if (count != null && count > rule.limit()) {
            throw new RateLimitExceededException(
                ErrorCode.AUTH_RATE_LIMITED,
                ErrorCode.AUTH_RATE_LIMITED.defaultMessage(),
                retryAfterSeconds(key, rule)
            );
        }
    }

    /**
     * 讀取計數 key 的剩餘存活秒數作為 Retry-After；無法取得時回退為視窗長度。
     *
     * @param key  計數 key
     * @param rule 限流規則
     * @return 建議重試秒數
     */
    private long retryAfterSeconds(String key, RateLimitProperties.Rule rule) {
        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        if (ttl == null || ttl <= 0) {
            return rule.window().toSeconds();
        }
        return ttl;
    }
}
