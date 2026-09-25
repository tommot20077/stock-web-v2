package dowob.xyz.stockwebv2.marketdata.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 把 WS 廣播的實際寫出從 Kafka consumer 執行緒移開：session 之間互不阻塞，同一 session 內依序送出。
 *
 * <p>原本 {@code WsBroadcastConsumer} 在 consumer 的單一執行緒上逐一同步呼叫 {@code sendMessage}。
 * {@code ConcurrentWebSocketSessionDecorator} 的逾時只在「下一次送出」時檢查：遇到接收緩衝已滿的慢客戶端，
 * 持有 flush 鎖的正是 consumer 執行緒本身，它會卡在實際寫出上，其他所有客戶端跟著等
 * （2026-09-02 性能審查 MED-1）。
 *
 * <p>做法是每個 session 一條待送佇列（actor 式序列化）：
 * <ul>
 *   <li>{@link #send} 只負責入列，立刻返回，呼叫端永遠不做 I/O。</li>
 *   <li>同一 session 同時最多只有一個 drain 任務在共用執行緒池上跑，所以訊息依入列順序送出——
 *       tick 與 K 線若亂序，客戶端會短暫顯示較舊的價格。</li>
 *   <li>慢客戶端只會卡住自己那條 drain，佔用池中一條執行緒；其他 session 由其他執行緒服務。</li>
 *   <li>待送量超過上限即視為慢消費者，以 4500 關閉連線，與原本「送出失敗即關閉」的語意一致；
 *       行情資料持續產生，無上限的佇列只會把問題變成記憶體耗盡。</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0.0
 */
public class SessionSendDispatcher {

    private static final Logger log = LoggerFactory.getLogger(SessionSendDispatcher.class);

    /** 送出失敗或積壓過量時使用的關閉碼，沿用原本廣播端的約定。 */
    static final CloseStatus CLOSE_SEND_FAILURE = new CloseStatus(4500, "Send failure");

    private final Executor executor;
    private final int maxPendingPerSession;
    private final ConcurrentMap<String, Outbox> outboxes = new ConcurrentHashMap<>();

    /**
     * @param executor             實際執行寫出的執行緒池，不可為 null
     * @param maxPendingPerSession 單一 session 的待送上限，須為正數
     */
    public SessionSendDispatcher(Executor executor, int maxPendingPerSession) {
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        if (maxPendingPerSession <= 0) {
            throw new IllegalArgumentException("maxPendingPerSession must be positive");
        }
        this.maxPendingPerSession = maxPendingPerSession;
    }

    /**
     * 排入一則訊息，立即返回。
     *
     * @param session   目標連線
     * @param sessionId 連線 id（佇列的鍵）
     * @param message   要送出的訊息
     */
    public void send(WebSocketSession session, String sessionId, TextMessage message) {
        if (!session.isOpen()) {
            return;
        }
        Outbox outbox = outboxes.computeIfAbsent(sessionId, id -> new Outbox(session, id));
        if (outbox.pending.incrementAndGet() > maxPendingPerSession) {
            outbox.pending.decrementAndGet();
            log.warn("Session {} exceeded {} pending messages, closing as slow consumer", sessionId, maxPendingPerSession);
            closeQuietly(session, sessionId);
            outboxes.remove(sessionId, outbox);
            return;
        }
        outbox.queue.add(message);
        outbox.scheduleDrain();
    }

    /**
     * 連線關閉時丟棄其待送佇列。
     *
     * @param sessionId 連線 id
     */
    public void discard(String sessionId) {
        outboxes.remove(sessionId);
    }

    private void closeQuietly(WebSocketSession session, String sessionId) {
        try {
            session.close(CLOSE_SEND_FAILURE);
        } catch (Exception ex) {
            log.debug("Closing session {} failed: {}", sessionId, ex.toString());
        }
    }

    /** 單一 session 的待送佇列與其 drain 狀態。 */
    private final class Outbox {

        private final WebSocketSession session;
        private final String sessionId;
        private final Queue<TextMessage> queue = new ConcurrentLinkedQueue<>();
        private final AtomicInteger pending = new AtomicInteger();
        private final AtomicBoolean draining = new AtomicBoolean();

        private Outbox(WebSocketSession session, String sessionId) {
            this.session = session;
            this.sessionId = sessionId;
        }

        private void scheduleDrain() {
            if (!draining.compareAndSet(false, true)) {
                return;
            }
            try {
                executor.execute(this::drain);
            } catch (RejectedExecutionException ex) {
                draining.set(false);
                log.warn("Send executor rejected drain for session {}: {}", sessionId, ex.toString());
            }
        }

        private void drain() {
            try {
                TextMessage next;
                while ((next = queue.poll()) != null) {
                    pending.decrementAndGet();
                    if (!session.isOpen()) {
                        queue.clear();
                        pending.set(0);
                        return;
                    }
                    try {
                        session.sendMessage(next);
                    } catch (Exception ex) {
                        log.warn("Send to session {} failed, closing with 4500: {}", sessionId, ex.toString());
                        queue.clear();
                        pending.set(0);
                        closeQuietly(session, sessionId);
                        outboxes.remove(sessionId, this);
                        return;
                    }
                }
            } finally {
                draining.set(false);
            }
            // drain 結束與新訊息入列之間的競態：若這段空檔有人入列但 CAS 失敗，這裡補排一次。
            if (!queue.isEmpty()) {
                scheduleDrain();
            }
        }
    }
}
