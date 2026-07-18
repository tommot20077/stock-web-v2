package dowob.xyz.stockwebv2.infrastructure.security;

import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.error.RateLimitExceededException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
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

    /**
     * 原子遞增計數並於視窗首次建立時設定 TTL 的 Lua 腳本。以單一 round-trip 執行
     * {@code INCR}(+首次 {@code PEXPIRE}),杜絕 INCR 與 EXPIRE 分兩步時首次請求可能
     * 因 EXPIRE 遺漏而產生無 TTL 的永久計數桶(#3)。僅在 {@code count == 1} 時設定 TTL,
     * 維持固定視窗語意(視窗到期後自然滾動,而非每次請求都刷新)。ARGV[1] 為視窗毫秒數。
     */
    private static final RedisScript<Long> INCR_AND_EXPIRE_IF_FIRST = RedisScript.of(
        "local count = redis.call('INCR', KEYS[1])\n"
            + "if count == 1 then\n"
            + "  redis.call('PEXPIRE', KEYS[1], ARGV[1])\n"
            + "end\n"
            + "return count",
        Long.class);

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
        long windowMillis = rule.window().toMillis();
        Long count = redisTemplate.execute(INCR_AND_EXPIRE_IF_FIRST, List.of(key), String.valueOf(windowMillis));
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
