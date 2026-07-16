package dowob.xyz.stockwebv2.marketdata.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link MarketHandshakeInterceptor} 單元測試。
 *
 * <p>驗證範圍：
 * <ul>
 *   <li>無 ticket query param → 拒絕（HTTP 401）</li>
 *   <li>ticket 值為空字串 → 拒絕</li>
 *   <li>ticket 無法 consume（invalid/expired）→ 拒絕</li>
 *   <li>ticket 有效且 tokenVersion 相符 → 接受並注入 userId</li>
 *   <li>tokenVersion 不符 → 拒絕</li>
 *   <li>Redis 找不到 tokenVersion → 拒絕</li>
 *   <li>多個 query param 時仍能正確取出 ticket</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0.0
 */
class MarketHandshakeInterceptorTest {

    @SuppressWarnings("unchecked")
    WsTicketService ticketService = mock(WsTicketService.class);
    @SuppressWarnings("unchecked")
    StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);

    MarketHandshakeInterceptor interceptor;

    @BeforeEach
    void setup() {
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        WebSocketConnectionManager connectionManager = new WebSocketConnectionManager(
            new dowob.xyz.stockwebv2.marketdata.config.WebSocketLimitProperties(null, null, null));
        interceptor = new MarketHandshakeInterceptor(
            ticketService, redisTemplate, connectionManager,
            new dowob.xyz.stockwebv2.infrastructure.audit.AuditLogger());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ServerHttpRequest reqWithQuery(String query) throws URISyntaxException {
        ServerHttpRequest req = mock(ServerHttpRequest.class);
        when(req.getURI()).thenReturn(
            new URI("http://localhost/ws/v1/market" + (query == null ? "" : "?" + query)));
        return req;
    }

    // ── test cases ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("無 ticket query param → 拒絕 handshake，回傳 HTTP 401")
    void missingTicket_rejects() throws Exception {
        var resp = mock(ServerHttpResponse.class);
        Map<String, Object> attrs = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(reqWithQuery(null), resp, null, attrs);

        assertThat(accepted).isFalse();
        verify(resp).setStatusCode(HttpStatus.UNAUTHORIZED);
        assertThat(attrs).isEmpty();
    }

    @Test
    @DisplayName("ticket 值為空字串 → 拒絕 handshake")
    void emptyTicketValue_rejects() throws Exception {
        var resp = mock(ServerHttpResponse.class);
        Map<String, Object> attrs = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(reqWithQuery("ticket="), resp, null, attrs);

        assertThat(accepted).isFalse();
        verify(resp).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("consume 回傳 empty（ticket 無效或已過期）→ 拒絕")
    void unknownTicket_rejects() throws Exception {
        when(ticketService.consume("xyz")).thenReturn(Optional.empty());
        var resp = mock(ServerHttpResponse.class);
        Map<String, Object> attrs = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(reqWithQuery("ticket=xyz"), resp, null, attrs);

        assertThat(accepted).isFalse();
        verify(resp).setStatusCode(HttpStatus.UNAUTHORIZED);
        assertThat(attrs).isEmpty();
    }

    @Test
    @DisplayName("有效 ticket 且 tokenVersion 相符 → 接受，注入 userId 至 attributes")
    void validTicket_currentTokenVersionMatches_accepts() throws Exception {
        when(ticketService.consume("good")).thenReturn(Optional.of(
            new TicketPayload(42L, Instant.now(), 7)));
        when(hashOps.get("user:auth:42", "tokenVersion")).thenReturn("7");
        var resp = mock(ServerHttpResponse.class);
        Map<String, Object> attrs = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(reqWithQuery("ticket=good"), resp, null, attrs);

        assertThat(accepted).isTrue();
        assertThat(attrs).containsEntry("userId", 42L);
        verify(resp, never()).setStatusCode(any());
    }

    @Test
    @DisplayName("tokenVersion 不符（ticket 舊版）→ 拒絕")
    void tokenVersionMismatch_rejects() throws Exception {
        when(ticketService.consume("stale")).thenReturn(Optional.of(
            new TicketPayload(42L, Instant.now(), 3)));
        when(hashOps.get("user:auth:42", "tokenVersion")).thenReturn("5"); // user 已 re-issue token
        var resp = mock(ServerHttpResponse.class);
        Map<String, Object> attrs = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(reqWithQuery("ticket=stale"), resp, null, attrs);

        assertThat(accepted).isFalse();
        verify(resp).setStatusCode(HttpStatus.UNAUTHORIZED);
        assertThat(attrs).isEmpty();
    }

    @Test
    @DisplayName("Redis 找不到 tokenVersion（null）→ 拒絕")
    void redisMiss_rejects() throws Exception {
        when(ticketService.consume("good")).thenReturn(Optional.of(
            new TicketPayload(42L, Instant.now(), 7)));
        when(hashOps.get("user:auth:42", "tokenVersion")).thenReturn(null);
        var resp = mock(ServerHttpResponse.class);
        Map<String, Object> attrs = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(reqWithQuery("ticket=good"), resp, null, attrs);

        assertThat(accepted).isFalse();
        verify(resp).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("多個 query param 時仍能正確取出 ticket 並接受")
    void extractTicket_multipleQueryParams_returnsTicket() throws Exception {
        when(ticketService.consume("xyz")).thenReturn(Optional.of(
            new TicketPayload(1L, Instant.now(), 1)));
        when(hashOps.get("user:auth:1", "tokenVersion")).thenReturn("1");
        var resp = mock(ServerHttpResponse.class);
        Map<String, Object> attrs = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(
            reqWithQuery("foo=bar&ticket=xyz&baz=qux"), resp, null, attrs);

        assertThat(accepted).isTrue();
        assertThat(attrs).containsEntry("userId", 1L);
    }
}
