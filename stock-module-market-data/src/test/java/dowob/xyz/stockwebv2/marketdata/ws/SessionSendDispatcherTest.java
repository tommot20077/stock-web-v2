package dowob.xyz.stockwebv2.marketdata.ws;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SessionSendDispatcher}：session 之間互不阻塞，同一 session 內依序送出。
 *
 * <p>原本 {@code WsBroadcastConsumer} 在 Kafka consumer 的單一執行緒上逐一同步呼叫 {@code sendMessage}。
 * {@code ConcurrentWebSocketSessionDecorator} 的逾時只在「下一次送出」時檢查：遇到接收緩衝已滿的慢客戶端，
 * 持有 flush 鎖的正是 consumer 執行緒本身，它會卡在實際寫出上，其他所有客戶端跟著等
 * （2026-09-02 性能審查 MED-1，head-of-line blocking）。
 *
 * @author Yuan
 * @version 1.0.0
 */
@DisplayName("WS 送出派發：session 隔離與順序")
class SessionSendDispatcherTest {

    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    @AfterEach
    void shutdown() throws InterruptedException {
        pool.shutdownNow();
        pool.awaitTermination(2, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("慢客戶端卡在寫出時，快客戶端照常收到，呼叫端也不被阻塞")
    void slowSessionDoesNotBlockOthers() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        WebSocketSession slow = openSession("slow");
        doAnswer(inv -> {
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(slow).sendMessage(any());
        WebSocketSession fast = openSession("fast");

        SessionSendDispatcher dispatcher = new SessionSendDispatcher(pool, 64);
        TextMessage message = new TextMessage("tick");

        long started = System.nanoTime();
        dispatcher.send(slow, "slow", message);
        dispatcher.send(fast, "fast", message);
        Duration callerBlocked = Duration.ofNanos(System.nanoTime() - started);

        assertThat(callerBlocked).as("呼叫端（Kafka consumer 執行緒）不應等待任何實際寫出").isLessThan(Duration.ofMillis(200));
        verify(fast, timeout(500)).sendMessage(message);
        release.countDown();
        verify(slow, timeout(2000)).sendMessage(message);
    }

    @Test
    @DisplayName("同一 session 的訊息依送出順序抵達，不因並行而亂序")
    void preservesOrderPerSession() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        WebSocketSession session = openSession("s1");
        doAnswer(inv -> {
            received.add(((TextMessage) inv.getArgument(0)).getPayload());
            return null;
        }).when(session).sendMessage(any());

        SessionSendDispatcher dispatcher = new SessionSendDispatcher(pool, 1000);
        for (int i = 0; i < 200; i++) {
            dispatcher.send(session, "s1", new TextMessage("m" + i));
        }

        verify(session, timeout(3000).times(200)).sendMessage(any());
        assertThat(received).hasSize(200);
        for (int i = 0; i < 200; i++) {
            assertThat(received.get(i)).isEqualTo("m" + i);
        }
    }

    @Test
    @DisplayName("待送佇列超過上限時視為慢消費者，以 4500 關閉該連線")
    void closesSessionWhenBacklogExceedsLimit() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        WebSocketSession stuck = openSession("stuck");
        doAnswer(inv -> {
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(stuck).sendMessage(any());

        SessionSendDispatcher dispatcher = new SessionSendDispatcher(pool, 3);
        for (int i = 0; i < 10; i++) {
            dispatcher.send(stuck, "stuck", new TextMessage("m" + i));
        }

        verify(stuck, timeout(1000)).close(new CloseStatus(4500, "Send failure"));
        release.countDown();
    }

    @Test
    @DisplayName("寫出失敗時以 4500 關閉該連線，與原本的錯誤隔離語意一致")
    void closesSessionWhenSendFails() throws Exception {
        WebSocketSession broken = openSession("broken");
        doAnswer(inv -> {
            throw new java.io.IOException("broken pipe");
        }).when(broken).sendMessage(any());

        new SessionSendDispatcher(pool, 64).send(broken, "broken", new TextMessage("m"));

        verify(broken, timeout(1000)).close(new CloseStatus(4500, "Send failure"));
    }

    private static WebSocketSession openSession(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn(id);
        return session;
    }
}
