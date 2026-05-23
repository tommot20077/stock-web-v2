package dowob.xyz.stockwebv2.start.e2e;

import dowob.xyz.stockwebv2.start.e2e.support.AbstractWsE2ETest;
import dowob.xyz.stockwebv2.start.e2e.support.DatabaseCleaner;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WebSocket 認證流程 E2E 測試。
 *
 * <p>驗證完整 ticket 流程：
 * <ul>
 *   <li>無 JWT 時請求 ticket → 401（HttpClientErrorException）</li>
 *   <li>有效 JWT 請求 ticket → 200 + ticket 字串</li>
 *   <li>帶有效 ticket 連接 WebSocket → 收到 WELCOME 訊息</li>
 *   <li>同一 ticket 重複使用 → handshake 被拒（single-use）</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0.0
 */
@DisplayName("WebSocket Auth Flow E2E")
class WsAuthFlowE2ETest extends AbstractWsE2ETest {

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanUserData();
    }

    /**
     * 未帶 JWT 請求 WS ticket 應收到 401。
     *
     * <p>{@link org.springframework.web.client.RestTemplate} 對 4xx 回應拋
     * {@link HttpClientErrorException}，以此驗證端點要求 JWT 驗證。
     */
    @Test
    @DisplayName("未帶 JWT 請求 WS ticket → 401")
    void requestTicketWithoutJwtReturns401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        try {
            restTemplate.postForEntity(
                baseUrl() + "/api/v1/market/ws/ticket", request, String.class);
            throw new AssertionError("Expected HttpClientErrorException (401), but request succeeded");
        } catch (HttpClientErrorException ex) {
            assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    /**
     * 帶有效 JWT 請求 WS ticket 應回傳 200 及 ticket 字串。
     */
    @Test
    @DisplayName("帶有效 JWT 請求 WS ticket → 200 + ticket")
    void requestTicketWithValidJwtReturns200() throws Exception {
        AuthSession session = registerAndLogin(
            "ws-auth-ticket@example.com", "wsauthticket", "Password1");

        String ticket = requestWsTicket(session.accessToken());

        assertThat(ticket).isNotBlank();
        assertThat(ticket.length()).isGreaterThan(20);
    }

    /**
     * 帶有效 ticket 連接 WebSocket 應成功並收到 WELCOME 訊息。
     */
    @Test
    @DisplayName("帶有效 ticket 連接 WS → 收到 WELCOME 訊息")
    void connectWithValidTicketReceivesWelcome() throws Exception {
        AuthSession session = registerAndLogin(
            "ws-connect-e2e@example.com", "wsconnecte2e", "Password1");
        String ticket = requestWsTicket(session.accessToken());

        AtomicReference<String> receivedMessage = new AtomicReference<>();
        AtomicBoolean connected = new AtomicBoolean(false);

        CompletableFuture<WebSocketSession> sessionFuture = connectWebSocket(
            ticket, receivedMessage, connected);

        WebSocketSession wsSession = sessionFuture.get(10, TimeUnit.SECONDS);

        Awaitility.await()
            .atMost(5, TimeUnit.SECONDS)
            .until(() -> receivedMessage.get() != null);

        String msg = receivedMessage.get();
        assertThat(msg).contains("WELCOME");
        assertThat(msg).contains("sessionId");

        if (wsSession != null && wsSession.isOpen()) {
            wsSession.close();
        }
    }

    /**
     * 同一 ticket 重複使用應被拒絕（single-use 語義）。
     *
     * <p>第一次連線成功後，ticket 已被 Redis GETDEL 消耗，
     * 第二次嘗試連線應因 ticket 無效而被 HandshakeInterceptor 以 HTTP 401 拒絕。
     */
    @Test
    @DisplayName("同一 ticket 重複使用 → 第二次連線被拒")
    void reuseTicketIsRejected() throws Exception {
        AuthSession session = registerAndLogin(
            "ws-reuse-e2e@example.com", "wsreusee2e", "Password1");
        String ticket = requestWsTicket(session.accessToken());

        // 第一次連線 — 應成功
        AtomicReference<String> firstMessage = new AtomicReference<>();
        AtomicBoolean firstConnected = new AtomicBoolean(false);
        CompletableFuture<WebSocketSession> firstSession = connectWebSocket(
            ticket, firstMessage, firstConnected);

        WebSocketSession ws1 = firstSession.get(10, TimeUnit.SECONDS);

        Awaitility.await()
            .atMost(5, TimeUnit.SECONDS)
            .until(() -> firstMessage.get() != null);
        assertThat(firstMessage.get()).contains("WELCOME");

        // 第二次連線使用同一 ticket — 應被拒絕（handshake exception）
        AtomicReference<String> secondMessage = new AtomicReference<>();
        AtomicBoolean secondConnected = new AtomicBoolean(false);
        CompletableFuture<WebSocketSession> secondFuture = connectWebSocket(
            ticket, secondMessage, secondConnected);

        // 第二次連線期望 exception（401 拒絕 upgrade）
        boolean secondRejected = false;
        try {
            secondFuture.get(10, TimeUnit.SECONDS);
            // 若未拋例外，連線成功（不應發生）
        } catch (Exception ex) {
            // 預期：HandshakeInterceptor 回傳 401 → WebSocket client 拋例外
            secondRejected = true;
        }
        assertThat(secondRejected).as("Second WS connection with same ticket should be rejected").isTrue();

        if (ws1 != null && ws1.isOpen()) {
            ws1.close();
        }
    }

    /**
     * 建立 WebSocket 連線並以 {@link AbstractWebSocketHandler} 接收訊息。
     *
     * @param ticket          一次性 WS ticket
     * @param receivedMessage 接收到的第一則訊息（由 handler 設入）
     * @param connected       連線建立旗標
     * @return CompletableFuture of WebSocketSession
     */
    private CompletableFuture<WebSocketSession> connectWebSocket(
            String ticket,
            AtomicReference<String> receivedMessage,
            AtomicBoolean connected) {

        StandardWebSocketClient client = new StandardWebSocketClient();
        URI uri = URI.create(wsBaseUrl() + "?ticket=" + ticket);

        AbstractWebSocketHandler handler = new AbstractWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) {
                connected.set(true);
            }

            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                receivedMessage.compareAndSet(null, message.getPayload());
            }
        };

        return client.execute(handler, new WebSocketHttpHeaders(), uri);
    }
}
