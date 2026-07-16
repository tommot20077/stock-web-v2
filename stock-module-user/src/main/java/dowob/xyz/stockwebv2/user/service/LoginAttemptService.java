package dowob.xyz.stockwebv2.user.service;

import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.error.RateLimitExceededException;
import dowob.xyz.stockwebv2.infrastructure.security.RateLimitProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

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
     * 記錄一次登入失敗；首次失敗時設定鎖定時間 TTL。停用時為無操作。
     *
     * @param userId 使用者 id
     */
    public void recordFailure(Long userId) {
        if (!properties.isEnabled()) {
            return;
        }
        String key = FAIL_KEY_PREFIX + userId;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, properties.lockout().duration());
        }
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
