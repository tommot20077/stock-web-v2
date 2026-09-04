package dowob.xyz.stockwebv2.marketdata.ws;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 每個 WebSocket session 的心跳（heartbeat）排程器。
 *
 * <p>職責：
 * <ul>
 *   <li>每 30 秒向客戶端傳送 PING 訊息</li>
 *   <li>若 90 秒內未收到 PONG，則強制關閉 session</li>
 *   <li>在 session 關閉時取消排程任務並清理資源</li>
 * </ul>
 *
 * <p>使用全域 {@link ScheduledExecutorService}（daemon threads），
 * 每個 session 獨立維護 sendPingTask 和 timeoutTask。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Component
public class WsHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(WsHeartbeat.class);

    /** 送 PING 的間隔（秒）。 */
    static final long PING_INTERVAL_SECONDS = 30L;

    /** 等待 PONG 的超時時間（秒）；超過則關閉 session。 */
    static final long TIMEOUT_SECONDS = 90L;

    /** PING 訊息的 JSON 文字。 */
    private static final String PING_MESSAGE = "{\"type\":\"PING\"}";

    /** 全域排程執行器（daemon threads）。 */
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
            2,
            r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName("ws-heartbeat");
                return t;
            });

    /** 每個 session 的心跳狀態 map。 */
    private final ConcurrentMap<String, SessionHeartbeat> sessions = new ConcurrentHashMap<>();

    /**
     * 為指定 session 註冊心跳排程。
     *
     * <p>立即排程定期 PING 任務與超時監控任務。
     *
     * @param session 目標 WebSocket session，不可為 null
     */
    public void register(WebSocketSession session) {
        SessionHeartbeat sh = new SessionHeartbeat(session);
        sessions.put(session.getId(), sh);
        sh.scheduleTasks(scheduler);
    }

    /**
     * 處理客戶端的 PONG 回應，重置超時計時器。
     *
     * @param sessionId 傳送 PONG 的 session ID
     */
    public void onPong(String sessionId) {
        SessionHeartbeat sh = sessions.get(sessionId);
        if (sh != null) {
            sh.onPong();
        }
    }

    /**
     * 取消指定 session 的心跳排程並清理資源。
     *
     * <p>當 session 關閉時由 {@link MarketWebSocketHandler} 呼叫。
     *
     * @param sessionId 目標 session ID
     */
    public void unregister(String sessionId) {
        SessionHeartbeat sh = sessions.remove(sessionId);
        if (sh != null) {
            sh.cancel();
        }
    }

    /**
     * Spring context 關閉時被呼叫,清掉所有 session 心跳任務並 shutdown scheduler。
     * 避免 @SpringBootTest 連續啟動或 production 熱重載時 daemon thread 堆積。
     */
    @PreDestroy
    public void shutdown() {
        sessions.values().forEach(SessionHeartbeat::cancel);
        sessions.clear();
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("WsHeartbeat scheduler 5s 內未完全終止,放棄等待");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** 暴露給測試用:目前還在 register 中的 session 數。 */
    int trackedSessions() {
        return sessions.size();
    }

    /** 暴露給測試用:scheduler 是否已 shutdown。 */
    boolean isSchedulerShutdown() {
        return scheduler.isShutdown();
    }

    /**
     * 單一 session 的心跳狀態與排程任務持有者。
     */
    static class SessionHeartbeat {

        /** 目標 WebSocket session。 */
        private final WebSocketSession session;

        /** 最後一次收到 PONG 的時間戳（毫秒，System.currentTimeMillis）。 */
        private final AtomicLong lastPongAt = new AtomicLong(System.currentTimeMillis());

        /** 定期送 PING 的排程任務。 */
        private ScheduledFuture<?> sendPingTask;

        /** 超時檢查任務。 */
        private ScheduledFuture<?> timeoutTask;

        /**
         * 建立 SessionHeartbeat。
         *
         * @param session 目標 session，不可為 null
         */
        SessionHeartbeat(WebSocketSession session) {
            this.session = session;
        }

        /**
         * 啟動定期 PING 與超時監控排程。
         *
         * @param scheduler 全域 {@link ScheduledExecutorService}
         */
        void scheduleTasks(ScheduledExecutorService scheduler) {
            sendPingTask = scheduler.scheduleAtFixedRate(
                    this::sendPing,
                    PING_INTERVAL_SECONDS,
                    PING_INTERVAL_SECONDS,
                    TimeUnit.SECONDS);

            timeoutTask = scheduler.scheduleAtFixedRate(
                    this::checkTimeout,
                    TIMEOUT_SECONDS,
                    TIMEOUT_SECONDS,
                    TimeUnit.SECONDS);
        }

        /**
         * 重置 PONG 接收時間，重置超時計時器。
         */
        void onPong() {
            lastPongAt.set(System.currentTimeMillis());
        }

        /**
         * 取消所有排程任務。
         */
        void cancel() {
            if (sendPingTask != null) {
                sendPingTask.cancel(false);
            }
            if (timeoutTask != null) {
                timeoutTask.cancel(false);
            }
        }

        /**
         * 向客戶端傳送 PING 訊息。
         */
        private void sendPing() {
            if (!session.isOpen()) {
                return;
            }
            try {
                session.sendMessage(new TextMessage(PING_MESSAGE));
            } catch (IOException e) {
                log.warn("無法傳送 PING 至 session={}：{}", session.getId(), e.getMessage());
            }
        }

        /**
         * 檢查是否已超時（90秒內未收到 PONG），若超時則關閉 session。
         */
        private void checkTimeout() {
            long elapsed = System.currentTimeMillis() - lastPongAt.get();
            if (elapsed > TIMEOUT_SECONDS * 1000L) {
                log.warn("Session {} 心跳超時（{}ms），強制關閉", session.getId(), elapsed);
                try {
                    session.close(new org.springframework.web.socket.CloseStatus(4000, "Heartbeat timeout"));
                } catch (IOException e) {
                    log.warn("關閉超時 session {} 失敗：{}", session.getId(), e.getMessage());
                }
            }
        }
    }
}
