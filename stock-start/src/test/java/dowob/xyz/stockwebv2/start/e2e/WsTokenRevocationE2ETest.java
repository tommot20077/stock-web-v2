package dowob.xyz.stockwebv2.start.e2e;

import dowob.xyz.stockwebv2.start.e2e.support.AbstractWsE2ETest;
import dowob.xyz.stockwebv2.start.e2e.support.DatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WebSocket Token Revocation E2E 測試。
 *
 * <p>驗證 token version 不一致時 WS ticket 無法建立連線的安全行為：
 * <ol>
 *   <li>使用者登錄 → 取得 JWT → 請求 WS ticket（ticket 內 tokenVersion=1）</li>
 *   <li>直接修改 Redis {@code user:auth:{userId}.tokenVersion} = 99（模擬 token rotation）</li>
 *   <li>使用舊 ticket 嘗試連接 WS → HandshakeInterceptor 比對失敗（1 vs 99）→ 401</li>
 * </ol>
 *
 * <p>{@link dowob.xyz.stockwebv2.marketdata.ws.MarketHandshakeInterceptor} 在 handshake 時
 * 會比對 ticket 內的 tokenVersion 與 Redis 當前 tokenVersion，不一致即拒絕。
 *
 * @author Yuan
 * @version 1.0.0
 */
@DisplayName("WebSocket Token Revocation E2E")
class WsTokenRevocationE2ETest extends AbstractWsE2ETest {

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanUserData();
    }

    /**
     * Token version 不一致時 ticket 無法建立 WebSocket 連線。
     *
     * <p>流程：
     * <ol>
     *   <li>登錄取得 access token（包含 tokenVersion=1）</li>
     *   <li>請求 WS ticket（ticket 內含 tokenVersion=1）</li>
     *   <li>直接修改 Redis {@code user:auth:{userId}.tokenVersion} 至不一致的值（99）</li>
     *   <li>以舊 ticket 嘗試 WS 連線 → MarketHandshakeInterceptor 比對失敗 → HTTP 401 拒絕</li>
     * </ol>
     *
     * <p>此模式模擬了 token rotation（例如密碼更改、強制登出等操作）後
     * 已簽發 ticket 無法使用的安全行為。
     */
    @Test
    @DisplayName("tokenVersion 不一致時 ticket 無法建立 WS 連線")
    void ticketIsInvalidWhenTokenVersionMismatches() throws Exception {
        // Step 1: 登錄並取得 access token（tokenVersion=1）
        AuthSession session = registerAndLogin(
            "ws-revoke-e2e@example.com", "wsrevokee2e", "Password1");

        // Step 2: 請求 WS ticket（ticket 內含 tokenVersion=1）
        String ticket = requestWsTicket(session.accessToken());

        // Step 3: 查詢 userId 並修改 Redis 中的 tokenVersion 造成 mismatch
        Long userId = jdbcTemplate.queryForObject(
            "SELECT id FROM users WHERE email = 'ws-revoke-e2e@example.com' LIMIT 1",
            Long.class);
        assertThat(userId).isNotNull();

        // 將 Redis tokenVersion 修改為與 ticket 內容不一致的值
        redisTemplate.opsForHash().put("user:auth:" + userId, "tokenVersion", "99");

        // Step 4: 以舊 ticket 嘗試 WS 連線 → 應被拒絕（TOKEN_VERSION_MISMATCH）
        boolean connectionRejected = false;
        WebSocketSession wsSession = null;
        try {
            wsSession = connectWebSocket(ticket).get(10, TimeUnit.SECONDS);
        } catch (Exception ex) {
            // 預期：HandshakeInterceptor 以 HTTP 401 拒絕，WebSocket client 拋例外
            connectionRejected = true;
        }

        assertThat(connectionRejected)
            .as("WS connection with mismatched tokenVersion ticket should be rejected")
            .isTrue();

        if (wsSession != null && wsSession.isOpen()) {
            wsSession.close();
        }
    }

    /**
     * 以 ticket 建立 WebSocket 連線。
     *
     * @param ticket 一次性 WS ticket
     * @return CompletableFuture of WebSocketSession
     */
    private CompletableFuture<WebSocketSession> connectWebSocket(String ticket) {
        StandardWebSocketClient client = new StandardWebSocketClient();
        URI uri = URI.create(wsBaseUrl() + "?ticket=" + ticket);

        AtomicReference<String> receivedMessage = new AtomicReference<>();

        AbstractWebSocketHandler handler = new AbstractWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                receivedMessage.compareAndSet(null, message.getPayload());
            }
        };

        return client.execute(handler, new WebSocketHttpHeaders(), uri);
    }
}
