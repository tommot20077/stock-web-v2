package dowob.xyz.stockwebv2.marketdata.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket 連線的持續驗證（security.md §9 Continuous Validation）。
 *
 * <p>每 5 分鐘掃描所有活躍連線：
 * <ul>
 *   <li>比對 handshake 當下的 tokenVersion 與 Redis {@code user:auth:{userId}} 現值，
 *       不符（登出／撤銷）即推送 {@code auth_expired} 並以 4001 關閉</li>
 *   <li>使用者狀態非 ACTIVE（停權／刪除）同樣以 4001 關閉</li>
 *   <li>Redis 不可用時 fail-closed：以 {@code redis_unavailable} 關閉連線（§9）</li>
 *   <li>無任何訂閱且連線已逾 30 分鐘者，以 4003 關閉回收資源（§18）</li>
 * </ul>
 *
 * <p>本類別僅涵蓋單一 JVM 內的週期性檢查；跨 JVM 的即時停權傳播（Kafka
 * {@code user.status-changed} 事件）尚未實作，撤銷延遲以 5 分鐘掃描為上界。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Component
public class WebSocketAuthValidator {

    private static final Logger log = LoggerFactory.getLogger(WebSocketAuthValidator.class);
    private static final String USER_AUTH_KEY_PREFIX = "user:auth:";
    private static final String ACTIVE_STATUS = "ACTIVE";

    /**
     * 閒置逾時門檻：無訂閱且連線超過此時間即回收（security.md §18）。
     */
    static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);

    private final MarketWebSocketHandler handler;
    private final SubscriptionManager subscriptionManager;
    private final StringRedisTemplate redisTemplate;

    public WebSocketAuthValidator(MarketWebSocketHandler handler,
                                  SubscriptionManager subscriptionManager,
                                  StringRedisTemplate redisTemplate) {
        this.handler = handler;
        this.subscriptionManager = subscriptionManager;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 每 5 分鐘執行一次持續驗證（security.md §9）。
     */
    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    public void scheduledRevalidate() {
        revalidateOnce();
    }

    /**
     * 掃描一輪所有活躍連線並關閉已失效或閒置者。
     */
    public void revalidateOnce() {
        for (MarketWebSocketHandler.SessionSnapshot snapshot : handler.snapshotSessions()) {
            String expiryReason = expiryReasonFor(snapshot);
            if (expiryReason != null) {
                log.info("WS session {} 認證失效（{}），以 4001 關閉", snapshot.sessionId(), expiryReason);
                handler.closeAuthExpired(snapshot.sessionId(), expiryReason);
                continue;
            }
            if (isIdle(snapshot)) {
                log.info("WS session {} 閒置逾時且無訂閱，以 4003 關閉", snapshot.sessionId());
                handler.closeIdle(snapshot.sessionId());
            }
        }
    }

    /**
     * 判斷連線的認證是否已失效。
     *
     * @param snapshot 連線快照
     * @return 失效原因；仍有效時回傳 {@code null}
     */
    private String expiryReasonFor(MarketWebSocketHandler.SessionSnapshot snapshot) {
        if (snapshot.userId() == null) {
            return null;
        }
        Map<Object, Object> authState;
        try {
            authState = redisTemplate.opsForHash().entries(USER_AUTH_KEY_PREFIX + snapshot.userId());
        } catch (DataAccessException exception) {
            return "redis_unavailable";
        }
        if (authState == null || authState.isEmpty()) {
            return "token_revoked";
        }
        Object currentVersion = authState.get("tokenVersion");
        if (currentVersion == null
            || !String.valueOf(currentVersion).equals(String.valueOf(snapshot.tokenVersion()))) {
            return "token_revoked";
        }
        if (!ACTIVE_STATUS.equals(String.valueOf(authState.get("status")))) {
            return "user_suspended";
        }
        return null;
    }

    /**
     * 判斷連線是否為「無訂閱且自最後活動起已逾閒置門檻」。
     *
     * <p>以最後活動時間（{@code lastActiveAt}）而非連線建立時間計算，
     * 才能正確反映「閒置」語意：近期仍有收送訊息的連線不應被回收，
     * 反之才在真正靜默逾門檻時關閉。</p>
     *
     * @param snapshot 連線快照
     * @return 是否應以閒置逾時回收
     */
    private boolean isIdle(MarketWebSocketHandler.SessionSnapshot snapshot) {
        if (!subscriptionManager.channelsOf(snapshot.sessionId()).isEmpty()) {
            return false;
        }
        if (snapshot.lastActiveAt() == null) {
            return false;
        }
        return Duration.between(snapshot.lastActiveAt(), Instant.now()).compareTo(IDLE_TIMEOUT) >= 0;
    }
}
