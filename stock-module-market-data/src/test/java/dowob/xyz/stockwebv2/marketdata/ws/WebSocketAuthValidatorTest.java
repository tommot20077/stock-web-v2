package dowob.xyz.stockwebv2.marketdata.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WebSocketAuthValidator} 持續驗證邏輯的單元測試（security.md §9、§18）。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("WebSocket 連線持續驗證")
class WebSocketAuthValidatorTest {

    private MarketWebSocketHandler handler;
    private SubscriptionManager subscriptionManager;
    private StringRedisTemplate redisTemplate;
    @SuppressWarnings("unchecked")
    private final HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
    private WebSocketAuthValidator validator;

    @BeforeEach
    void setup() {
        handler = mock(MarketWebSocketHandler.class);
        subscriptionManager = mock(SubscriptionManager.class);
        redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        validator = new WebSocketAuthValidator(handler, subscriptionManager, redisTemplate);
    }

    private void givenSession(String sessionId, Long userId, Integer tokenVersion, Instant connectedAt) {
        givenSession(sessionId, userId, tokenVersion, connectedAt, connectedAt);
    }

    private void givenSession(String sessionId, Long userId, Integer tokenVersion,
                              Instant connectedAt, Instant lastActiveAt) {
        when(handler.snapshotSessions()).thenReturn(List.of(
            new MarketWebSocketHandler.SessionSnapshot(sessionId, userId, tokenVersion, connectedAt, lastActiveAt)));
    }

    @Test
    @DisplayName("tokenVersion 與 Redis 現值不符（已登出／撤銷）→ 以 auth_expired 關閉")
    void revokedTokenVersionClosesSession() {
        givenSession("s1", 7L, 1, Instant.now());
        when(hashOps.entries("user:auth:7")).thenReturn(Map.of("tokenVersion", "2", "status", "ACTIVE"));

        validator.revalidateOnce();

        verify(handler).closeAuthExpired("s1", "token_revoked");
    }

    @Test
    @DisplayName("使用者已被停權 → 以 auth_expired 關閉")
    void suspendedUserClosesSession() {
        givenSession("s1", 7L, 1, Instant.now());
        when(hashOps.entries("user:auth:7")).thenReturn(Map.of("tokenVersion", "1", "status", "SUSPENDED"));

        validator.revalidateOnce();

        verify(handler).closeAuthExpired("s1", "user_suspended");
    }

    @Test
    @DisplayName("Redis 不可用 → fail-closed 以 redis_unavailable 關閉")
    void redisUnavailableClosesSession() {
        givenSession("s1", 7L, 1, Instant.now());
        when(hashOps.entries("user:auth:7")).thenThrow(new QueryTimeoutException("redis down"));

        validator.revalidateOnce();

        verify(handler).closeAuthExpired("s1", "redis_unavailable");
    }

    @Test
    @DisplayName("認證仍有效且有訂閱 → 不關閉連線")
    void validSubscribedSessionIsKept() {
        givenSession("s1", 7L, 1, Instant.now().minus(2, ChronoUnit.HOURS));
        when(hashOps.entries("user:auth:7")).thenReturn(Map.of("tokenVersion", "1", "status", "ACTIVE"));
        when(subscriptionManager.channelsOf("s1")).thenReturn(Set.of("price:1"));

        validator.revalidateOnce();

        verify(handler, never()).closeAuthExpired(anyString(), any());
        verify(handler, never()).closeIdle(anyString());
    }

    @Test
    @DisplayName("無訂閱且連線逾 30 分鐘 → 以閒置逾時關閉")
    void idleSessionWithoutSubscriptionIsClosed() {
        givenSession("s1", 7L, 1, Instant.now().minus(31, ChronoUnit.MINUTES));
        when(hashOps.entries("user:auth:7")).thenReturn(Map.of("tokenVersion", "1", "status", "ACTIVE"));
        when(subscriptionManager.channelsOf("s1")).thenReturn(Set.of());

        validator.revalidateOnce();

        verify(handler).closeIdle("s1");
    }

    @Test
    @DisplayName("無訂閱、連線已久但近期仍有活動 → 不以閒置逾時關閉")
    void recentlyActiveOldConnectionIsKept() {
        givenSession("s1", 7L, 1,
            Instant.now().minus(2, ChronoUnit.HOURS),
            Instant.now().minus(1, ChronoUnit.MINUTES));
        when(hashOps.entries("user:auth:7")).thenReturn(Map.of("tokenVersion", "1", "status", "ACTIVE"));
        when(subscriptionManager.channelsOf("s1")).thenReturn(Set.of());

        validator.revalidateOnce();

        verify(handler, never()).closeIdle(anyString());
    }

    @Test
    @DisplayName("無訂閱但尚未達閒置門檻 → 保留連線")
    void recentSessionWithoutSubscriptionIsKept() {
        givenSession("s1", 7L, 1, Instant.now().minus(5, ChronoUnit.MINUTES));
        when(hashOps.entries("user:auth:7")).thenReturn(Map.of("tokenVersion", "1", "status", "ACTIVE"));
        when(subscriptionManager.channelsOf("s1")).thenReturn(Set.of());

        validator.revalidateOnce();

        verify(handler, never()).closeIdle(anyString());
    }

    @Test
    @DisplayName("Redis 中已無 auth 狀態 → 視為已撤銷並關閉")
    void missingAuthStateClosesSession() {
        givenSession("s1", 7L, 1, Instant.now());
        when(hashOps.entries("user:auth:7")).thenReturn(Map.of());

        validator.revalidateOnce();

        verify(handler).closeAuthExpired("s1", "token_revoked");
    }
}
