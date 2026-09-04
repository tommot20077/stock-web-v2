package dowob.xyz.stockwebv2.marketdata.api;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Backfill idempotency 追蹤器，以 Redis key 防止短時間內重複觸發。
 *
 * <p>使用 Redis SET NX EX 指令實作原子性鎖定，確保同一 idempotency key
 * 在 1 小時內只能觸發一次 backfill job。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Service
public class BackfillIdempotencyService {

    /** Redis key 前綴，避免與其他 key 命名空間衝突。 */
    public static final String KEY_PREFIX = "market:backfill:idem:";

    /** Idempotency key 的 Redis TTL，1 小時。 */
    public static final Duration TTL = Duration.ofHours(1);

    private final StringRedisTemplate redisTemplate;

    /**
     * 建構子注入 {@link StringRedisTemplate}。
     *
     * @param redisTemplate Spring Data Redis template，不可 null
     */
    public BackfillIdempotencyService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 嘗試對 idempotency key 加鎖（SET NX EX 1h）。
     *
     * @param idempotencyKey 來自 HTTP header {@code Idempotency-Key} 的值，不可 null
     * @return {@code true} 若成功取得鎖（可繼續觸發 job）；
     *         {@code false} 若鎖已被持有（應回傳 409）
     */
    public boolean tryAcquire(String idempotencyKey) {
        Boolean result = redisTemplate.opsForValue().setIfAbsent(
            KEY_PREFIX + idempotencyKey, "locked", TTL);
        return Boolean.TRUE.equals(result);
    }
}
