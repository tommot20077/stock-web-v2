package dowob.xyz.stockwebv2.marketdata.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link MarketWebSocketHandler} 單元測試（Mockito-based）。
 *
 * <p>驗證範圍：
 * <ul>
 *   <li>{@code afterConnectionEstablished} → 送出含 "WELCOME" 的 TextMessage</li>
 *   <li>{@code handleTextMessage} SUBSCRIBE → 呼叫 SubscriptionManager.subscribe 並送出 SUB_ACK</li>
 *   <li>{@code handleTextMessage} UNSUBSCRIBE → 呼叫 SubscriptionManager.unsubscribe</li>
 *   <li>{@code handleTextMessage} PONG → 呼叫 heartbeat.onPong</li>
 *   <li>{@code handleTextMessage} 格式錯誤 → 送出 ERROR 訊息並遞增計數器</li>
 *   <li>連續 10 個格式錯誤 → 以 4400 關閉 session</li>
 *   <li>超過速率限制 → 以 4429 關閉 session</li>
 *   <li>{@code afterConnectionClosed} → 呼叫 SubscriptionManager.removeSession 和 heartbeat.unregister</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0.0
 */
class MarketWebSocketHandlerTest {

    private WsMessageParser parser;
    private SubscriptionManager subscriptionManager;
    private WsHeartbeat heartbeat;
    private MarketWebSocketHandler handler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setup() {
        objectMapper = new ObjectMapper();
        parser = mock(WsMessageParser.class);
        subscriptionManager = mock(SubscriptionManager.class);
        heartbeat = mock(WsHeartbeat.class);
        WebSocketConnectionManager connectionManager = new WebSocketConnectionManager(
            new dowob.xyz.stockwebv2.marketdata.config.WebSocketLimitProperties(null, null, null));
        handler = new MarketWebSocketHandler(parser, subscriptionManager, heartbeat, objectMapper, connectionManager);
    }

    private WebSocketSession mockSession(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(new java.util.HashMap<>());
        return session;
    }

    private WebSocketSession mockSessionWithUser(String id, Long userId) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        java.util.Map<String, Object> attributes = new java.util.HashMap<>();
        attributes.put(MarketHandshakeInterceptor.ATTR_USER_ID, userId);
        when(session.getAttributes()).thenReturn(attributes);
        return session;
    }

    /** 每帳號連線上限 FIFO 驅逐 → 被驅逐 session 先收到 auth_expired/session_replaced 再以 4002 關閉 */
    @Test
    @DisplayName("FIFO 驅逐 → 先送 auth_expired(session_replaced) 再以 4002 關閉")
    void evictedSession_receivesAuthExpiredBeforeClose() throws Exception {
        // 每帳號預設上限 2;同帳號第 3 條連線建立時最舊(e1)被驅逐
        WebSocketSession e1 = mockSessionWithUser("e1", 42L);
        WebSocketSession e2 = mockSessionWithUser("e2", 42L);
        WebSocketSession e3 = mockSessionWithUser("e3", 42L);

        handler.afterConnectionEstablished(e1);
        handler.afterConnectionEstablished(e2);
        handler.afterConnectionEstablished(e3);

        // e1 應先收到 auth_expired(reason=session_replaced),再被以 4002 關閉
        ArgumentCaptor<TextMessage> msgCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(e1, atLeastOnce()).sendMessage(msgCaptor.capture());
        assertThat(msgCaptor.getAllValues())
            .anyMatch(m -> m.getPayload().contains("auth_expired") && m.getPayload().contains("session_replaced"));

        ArgumentCaptor<CloseStatus> closeCaptor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(e1).close(closeCaptor.capture());
        assertThat(closeCaptor.getValue().getCode()).isEqualTo(4002);

        InOrder inOrder = inOrder(e1);
        inOrder.verify(e1).sendMessage(argThat((TextMessage m) -> m.getPayload().contains("auth_expired")));
        inOrder.verify(e1).close(any(CloseStatus.class));
    }

    /** afterConnectionEstablished → 送出含 "WELCOME" 的訊息 */
    @Test
    @DisplayName("afterConnectionEstablished → 送出含 WELCOME 的 TextMessage")
    void afterConnectionEstablished_sendsWelcomeMessage() throws Exception {
        WebSocketSession session = mockSession("session-1");

        handler.afterConnectionEstablished(session);

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        assertThat(captor.getValue().getPayload()).contains("WELCOME");
        verify(heartbeat).register(session);
    }

    /** handleTextMessage SUBSCRIBE → 呼叫 SubscriptionManager.subscribe 並送出 SUB_ACK */
    @Test
    @DisplayName("handleTextMessage SUBSCRIBE → 呼叫 subscribe 並送出 SUB_ACK")
    void handleTextMessage_subscribe_callsSubscribeAndSendsAck() throws Exception {
        WebSocketSession session = mockSession("s1");
        handler.afterConnectionEstablished(session);

        List<String> channels = List.of("tick.AAPL");
        ClientMessage.Subscribe subscribeMsg = new ClientMessage.Subscribe(channels);
        when(parser.parse(any())).thenReturn(subscribeMsg);
        SubscribeResult result = new SubscribeResult(channels, List.of());
        when(subscriptionManager.subscribe("s1", channels)).thenReturn(result);

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"SUBSCRIBE\",\"channels\":[\"tick.AAPL\"]}"));

        verify(subscriptionManager).subscribe("s1", channels);
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeast(2)).sendMessage(captor.capture());
        boolean hasSub_ack = captor.getAllValues().stream()
                .anyMatch(m -> m.getPayload().contains("SUB_ACK"));
        assertThat(hasSub_ack).isTrue();
    }

    /** handleTextMessage UNSUBSCRIBE → 呼叫 SubscriptionManager.unsubscribe */
    @Test
    @DisplayName("handleTextMessage UNSUBSCRIBE → 呼叫 unsubscribe")
    void handleTextMessage_unsubscribe_callsUnsubscribe() throws Exception {
        WebSocketSession session = mockSession("s1");
        handler.afterConnectionEstablished(session);

        List<String> channels = List.of("tick.AAPL");
        ClientMessage.Unsubscribe unsubMsg = new ClientMessage.Unsubscribe(channels);
        when(parser.parse(any())).thenReturn(unsubMsg);

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"UNSUBSCRIBE\",\"channels\":[\"tick.AAPL\"]}"));

        verify(subscriptionManager).unsubscribe("s1", channels);
    }

    /** handleTextMessage PONG → 呼叫 heartbeat.onPong */
    @Test
    @DisplayName("handleTextMessage PONG → 呼叫 heartbeat.onPong")
    void handleTextMessage_pong_callsHeartbeatOnPong() throws Exception {
        WebSocketSession session = mockSession("s1");
        handler.afterConnectionEstablished(session);

        when(parser.parse(any())).thenReturn(new ClientMessage.Pong());

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"PONG\"}"));

        verify(heartbeat).onPong("s1");
    }

    /** handleTextMessage 格式錯誤 → 送出 ERROR 訊息 */
    @Test
    @DisplayName("handleTextMessage 格式錯誤 → 送出 ERROR 訊息")
    void handleTextMessage_formatError_sendsErrorMessage() throws Exception {
        WebSocketSession session = mockSession("s1");
        handler.afterConnectionEstablished(session);

        when(parser.parse(any())).thenThrow(new InvalidMessageException("bad format"));

        handler.handleTextMessage(session, new TextMessage("bad-json"));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeast(2)).sendMessage(captor.capture());
        boolean hasError = captor.getAllValues().stream()
                .anyMatch(m -> m.getPayload().contains("ERROR"));
        assertThat(hasError).isTrue();
    }

    /** 連續 10 個格式錯誤 → 以 4400 關閉 session */
    @Test
    @DisplayName("連續 10 個格式錯誤 → 以 4400 關閉 session")
    void handleTextMessage_tenFormatErrors_closesWith4400() throws Exception {
        WebSocketSession session = mockSession("s1");
        handler.afterConnectionEstablished(session);

        when(parser.parse(any())).thenThrow(new InvalidMessageException("bad"));

        for (int i = 0; i < 10; i++) {
            handler.handleTextMessage(session, new TextMessage("bad"));
        }

        ArgumentCaptor<CloseStatus> closeCaptor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session).close(closeCaptor.capture());
        assertThat(closeCaptor.getValue().getCode()).isEqualTo(4400);
    }

    /** 超過速率限制（11 msg in <1s） → 以 4429 關閉 session */
    @Test
    @DisplayName("超過速率限制 → 以 4429 關閉 session")
    void handleTextMessage_rateLimitExceeded_closesWith4429() throws Exception {
        WebSocketSession session = mockSession("s1");
        handler.afterConnectionEstablished(session);

        when(parser.parse(any())).thenReturn(new ClientMessage.Pong());

        /** 送 11 個訊息超過 10/s 速率限制 */
        for (int i = 0; i < 11; i++) {
            handler.handleTextMessage(session, new TextMessage("{\"type\":\"PONG\"}"));
        }

        ArgumentCaptor<CloseStatus> closeCaptor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session).close(closeCaptor.capture());
        assertThat(closeCaptor.getValue().getCode()).isEqualTo(4429);
    }

    /** afterConnectionClosed → 呼叫 removeSession 和 heartbeat.unregister */
    @Test
    @DisplayName("afterConnectionClosed → 呼叫 SubscriptionManager.removeSession 和 heartbeat.unregister")
    void afterConnectionClosed_removesSessionAndUnregistersHeartbeat() throws Exception {
        WebSocketSession session = mockSession("s1");
        handler.afterConnectionEstablished(session);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        verify(subscriptionManager).removeSession("s1");
        verify(heartbeat).unregister("s1");
    }

    /** afterConnectionEstablished → session 登錄至 sessions map，findSession 可查到 */
    @Test
    @DisplayName("afterConnectionEstablished → findSession 可查到已登錄的 session")
    void afterConnectionEstablished_registersSessionForLookup() throws Exception {
        WebSocketSession session = mockSession("s1");

        handler.afterConnectionEstablished(session);

        assertThat(handler.findSession("s1")).isPresent().contains(session);
    }

    /** afterConnectionClosed → session 從 sessions map 移除，findSession 回傳 empty */
    @Test
    @DisplayName("afterConnectionClosed → findSession 回傳 empty")
    void afterConnectionClosed_removesSessionFromRegistry() throws Exception {
        WebSocketSession session = mockSession("s1");
        handler.afterConnectionEstablished(session);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertThat(handler.findSession("s1")).isEmpty();
    }

    /** findSession → 不存在的 sessionId 回傳 empty */
    @Test
    @DisplayName("findSession → 不存在的 sessionId → 回傳 empty")
    void findSession_unknownSessionId_returnsEmpty() {
        assertThat(handler.findSession("does-not-exist")).isEmpty();
    }
}
