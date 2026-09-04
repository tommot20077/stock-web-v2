package dowob.xyz.stockwebv2.start.e2e;

import dowob.xyz.stockwebv2.start.e2e.support.AbstractWsE2ETest;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WebSocket 每帳號連線上限 FIFO 驅逐的 E2E 測試（security.md §18）。
 *
 * <p>預設每帳號上限為 2；同帳號建立第 3 條連線時，最舊連線應被以自訂關閉碼 4002
 * （SESSION_REPLACED）驅逐。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("WebSocket 每帳號連線上限 E2E")
class WsConnectionLimitE2ETest extends AbstractWsE2ETest {

    @Test
    @DisplayName("同帳號第 3 條連線建立時最舊連線被以 4002 驅逐")
    void thirdConnectionEvictsOldestPerAccount() throws Exception {
        AuthSession session = registerAndLogin("ws-limit-e2e@example.com", "wslimite2e", "Password1");

        Connection first = openConnection(session.accessToken());
        awaitWelcome(first);
        Connection second = openConnection(session.accessToken());
        awaitWelcome(second);
        Connection third = openConnection(session.accessToken());
        awaitWelcome(third);

        Awaitility.await()
            .atMost(10, TimeUnit.SECONDS)
            .until(() -> first.closeCode.get() != 0);

        assertThat(first.closeCode.get()).isEqualTo(4002);
        assertThat(third.session.get()).isNotNull();
        assertThat(third.session.get().isOpen()).isTrue();

        closeQuietly(second, third);
    }

    /**
     * 以一次性 ticket 建立一條 WebSocket 連線並追蹤其 WELCOME 訊息與關閉碼。
     *
     * @param accessToken Bearer access token
     * @return 連線追蹤器
     * @throws Exception 若 ticket 請求或連線建立失敗
     */
    private Connection openConnection(String accessToken) throws Exception {
        String ticket = requestWsTicket(accessToken);
        Connection connection = new Connection();
        StandardWebSocketClient client = new StandardWebSocketClient();
        URI uri = URI.create(wsBaseUrl() + "?ticket=" + ticket);
        AbstractWebSocketHandler handler = new AbstractWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession wsSession) {
                connection.session.set(wsSession);
            }

            @Override
            protected void handleTextMessage(WebSocketSession wsSession, TextMessage message) {
                connection.welcome.compareAndSet(null, message.getPayload());
            }

            @Override
            public void afterConnectionClosed(WebSocketSession wsSession, CloseStatus status) {
                connection.closeCode.set(status.getCode());
            }
        };
        WebSocketSession established = client.execute(handler, new WebSocketHttpHeaders(), uri)
            .get(10, TimeUnit.SECONDS);
        connection.session.compareAndSet(null, established);
        return connection;
    }

    /**
     * 等待連線收到 WELCOME 訊息。
     *
     * @param connection 連線追蹤器
     */
    private void awaitWelcome(Connection connection) {
        Awaitility.await()
            .atMost(5, TimeUnit.SECONDS)
            .until(() -> connection.welcome.get() != null);
    }

    /**
     * 安靜關閉多條連線。
     *
     * @param connections 連線追蹤器
     */
    private void closeQuietly(Connection... connections) {
        for (Connection connection : connections) {
            WebSocketSession wsSession = connection.session.get();
            if (wsSession != null && wsSession.isOpen()) {
                try {
                    wsSession.close();
                } catch (Exception ignored) {
                    // 測試清理，忽略
                }
            }
        }
    }

    /**
     * 單一連線的狀態追蹤：session、WELCOME 訊息、關閉碼。
     */
    private static final class Connection {
        private final AtomicReference<WebSocketSession> session = new AtomicReference<>();
        private final AtomicReference<String> welcome = new AtomicReference<>();
        private final AtomicInteger closeCode = new AtomicInteger(0);
    }
}
