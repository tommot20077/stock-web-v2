package dowob.xyz.stockwebv2.user.service;

import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.error.RateLimitExceededException;
import dowob.xyz.stockwebv2.infrastructure.security.RateLimitProperties;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 以 Redis 記錄連續登入失敗次數並實作帳號鎖定的服務（security.md §15）。
 *
 * <p>連續失敗達門檻後鎖定固定時間，成功登入或 ADMIN 解鎖時重置計數。計數 key 為
 * {@code user:login:fail:{userId}}，隨鎖定時間 TTL 自動過期。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Service
public class LoginAttemptService {

    /**
     * 登入失敗計數的 Redis key 前綴。
     */
    private static final String FAIL_KEY_PREFIX = "user:login:fail:";

    /**
     * 原子遞增失敗計數並刷新 TTL 的 Lua 腳本。以單一 round-trip 執行 {@code INCR + PEXPIRE},
     * 杜絕 INCR 與 EXPIRE 分兩步時可能出現的無 TTL 殘留計數（#3);且每次失敗都刷新 TTL,
     * 使鎖定時長為最後一次失敗起算的完整 {@code lockout.duration}（#2)。ARGV[1] 為 TTL 毫秒數。
     */
    private static final RedisScript<Long> INCR_AND_REFRESH_TTL = RedisScript.of(
        "local count = redis.call('INCR', KEYS[1])\n"
            + "redis.call('PEXPIRE', KEYS[1], ARGV[1])\n"
            + "return count",
        Long.class);

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties properties;

    public LoginAttemptService(StringRedisTemplate redisTemplate, RateLimitProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * 若帳號目前處於鎖定狀態（失敗次數已達門檻）則拋出 {@link RateLimitExceededException}。
     * 應在驗證密碼前呼叫，避免對已鎖定帳號洩漏密碼是否正確。停用時為無操作。
     *
     * <p><b>已知取捨:</b>已存在且被鎖定的帳號會回 {@code AUTH_ACCOUNT_LOCKED}(429),而不存在的
     * email 回 {@code AUTH_INVALID_CREDENTIALS}(401),兩者可被用於推測帳號是否存在(使用者列舉);
     * 且任何知道他人 email 者皆可用連續失敗將該帳號鎖定,形成定向 DoS。此為帳號鎖定機制的固有取捨,
     * 屬可接受範圍;#1 移除 X-Forwarded-For 信任後 per-IP 限流恢復,可縮小上述攻擊的放大面。</p>
     *
     * @param userId 使用者 id
     */
    public void assertNotLocked(Long userId) {
        if (!properties.isEnabled()) {
            return;
        }
        String key = FAIL_KEY_PREFIX + userId;
        String value = redisTemplate.opsForValue().get(key);
        int failures = value == null ? 0 : parse(value);
        if (failures >= properties.lockout().threshold()) {
            throw new RateLimitExceededException(
                ErrorCode.AUTH_ACCOUNT_LOCKED,
                ErrorCode.AUTH_ACCOUNT_LOCKED.defaultMessage(),
                retryAfterSeconds(key)
            );
        }
    }

    /**
     * 記錄一次登入失敗:以原子 Lua 腳本遞增計數並刷新鎖定時間 TTL。停用時為無操作。
     *
     * <p>每次失敗都刷新 TTL,故鎖定持續時間以最後一次失敗起算完整的 {@code lockout.duration},
     * 而非只在首次失敗設定（避免鎖定過早到期);且 INCR 與 PEXPIRE 於單一腳本原子執行,
     * 不會出現無 TTL 的永久殘留計數。</p>
     *
     * @param userId 使用者 id
     */
    public void recordFailure(Long userId) {
        if (!properties.isEnabled()) {
            return;
        }
        String key = FAIL_KEY_PREFIX + userId;
        long ttlMillis = properties.lockout().duration().toMillis();
        redisTemplate.execute(INCR_AND_REFRESH_TTL, List.of(key), String.valueOf(ttlMillis));
    }

    /**
     * 重置登入失敗計數（成功登入或 ADMIN 解鎖時呼叫）。
     *
     * @param userId 使用者 id
     */
    public void reset(Long userId) {
        redisTemplate.delete(FAIL_KEY_PREFIX + userId);
    }

    /**
     * 讀取鎖定 key 的剩餘存活秒數作為 Retry-After；無法取得時回退為設定的鎖定時間。
     *
     * @param key 鎖定計數 key
     * @return 建議重試秒數
     */
    private long retryAfterSeconds(String key) {
        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        if (ttl == null || ttl <= 0) {
            return properties.lockout().duration().toSeconds();
        }
        return ttl;
    }

    /**
     * 將字串計數安全轉為整數，非數值時視為 0。
     *
     * @param value Redis 中的計數字串
     * @return 整數計數
     */
    private int parse(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }
}
