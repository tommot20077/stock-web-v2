package dowob.xyz.stockwebv2.marketdata.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket 主要訊息處理器：負責連線生命週期管理與訊息路由。
 *
 * <p>職責：
 * <ul>
 *   <li>連線建立：傳送 WELCOME 訊息並向 {@link WsHeartbeat} 註冊</li>
 *   <li>訊息處理：速率限制 → 解析 → 路由至 SUBSCRIBE / UNSUBSCRIBE / PONG 處理</li>
 *   <li>格式錯誤：累計至 10 次後以 4400 關閉連線</li>
 *   <li>速率超限：超過 10 msg/s 時以 4429 關閉連線</li>
 *   <li>連線關閉：通知 SubscriptionManager 與 WsHeartbeat 清理資源</li>
 * </ul>
 *
 * <p>自訂 WebSocket 關閉碼：
 * <ul>
 *   <li>4400 — 連續格式錯誤超過上限（Bad message）</li>
 *   <li>4429 — 速率限制超過（Rate limit exceeded）</li>
 *   <li>4500 — 廣播傳送失敗（Send failure，由 WsBroadcastConsumer 觸發）</li>
 * </ul>
 *
 * <p>Sessions 登錄：持有 {@code sessions} map，讓 {@link dowob.xyz.stockwebv2.marketdata.consumer.WsBroadcastConsumer}
 * 能透過 {@link #findSession(String)} 查找實際 {@link WebSocketSession} 實例進行廣播。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Component
public class MarketWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(MarketWebSocketHandler.class);

    /** 連線後傳送的最大訂閱數量（WELCOME 訊息告知客戶端）。 */
    private static final int MAX_SUBSCRIPTIONS = 10;

    /** 格式錯誤達此上限後關閉連線。 */
    private static final int MAX_FORMAT_ERRORS = 10;

    /** 4400 — 連續格式錯誤超過上限。 */
    static final CloseStatus CLOSE_BAD_MESSAGE = new CloseStatus(4400, "Bad message");

    /** 4008 — 速率限制超過（security.md §9 RATE_LIMITED）。 */
    static final CloseStatus CLOSE_RATE_LIMITED = new CloseStatus(4008, "Rate limited");

    /** 4002 — 因每帳號連線上限被較新連線取代（FIFO 驅逐，security.md §18）。 */
    static final CloseStatus CLOSE_SESSION_REPLACED = new CloseStatus(4002, "Session replaced");

    /** 4001 — token 過期／被撤銷／使用者被停權（security.md §9 AUTH_EXPIRED）。 */
    static final CloseStatus CLOSE_AUTH_EXPIRED = new CloseStatus(4001, "Auth expired");

    /** 4003 — 閒置逾時：逾時且無任何訂閱（security.md §9 IDLE_TIMEOUT）。 */
    static final CloseStatus CLOSE_IDLE_TIMEOUT = new CloseStatus(4003, "Idle timeout");

    /** 4009 — 伺服器正常關閉（security.md §9 SERVER_SHUTDOWN）。 */
    static final CloseStatus CLOSE_SERVER_SHUTDOWN = new CloseStatus(4009, "Server shutdown");

    /** 併發送訊的等待上限（毫秒）：超過即由 decorator 關閉 session，避免慢客戶端拖垮執行緒。 */
    private static final int SEND_TIME_LIMIT_MS = 10_000;

    /** 併發送訊的緩衝上限（位元組）：超過即由 decorator 關閉 session。 */
    private static final int SEND_BUFFER_SIZE_LIMIT = 512 * 1024;

    private final WsMessageParser parser;
    private final SubscriptionManager subscriptionManager;
    private final WsHeartbeat heartbeat;
    private final ObjectMapper objectMapper;
    private final WebSocketConnectionManager connectionManager;

    /** 每個 session 的上下文（速率限制 + 格式錯誤計數）。 */
    private final ConcurrentMap<String, SessionContext> contexts = new ConcurrentHashMap<>();

    /**
     * 活躍 WebSocket session 登錄：sessionId → WebSocketSession。
     *
     * <p>供 {@link dowob.xyz.stockwebv2.marketdata.consumer.WsBroadcastConsumer} 透過
     * {@link #findSession(String)} 查找 session 實例進行廣播。
     * 生命週期由 {@link #afterConnectionEstablished} / {@link #afterConnectionClosed} 管理。
     */
    private final ConcurrentMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    /**
     * 建構子注入所有依賴。
     *
     * @param parser              訊息解析器，不可為 null
     * @param subscriptionManager 訂閱管理器，不可為 null
     * @param heartbeat           心跳排程器，不可為 null
     * @param objectMapper        Jackson ObjectMapper，不可為 null
     * @param connectionManager   WebSocket 連線治理器，不可為 null
     */
    public MarketWebSocketHandler(WsMessageParser parser,
                                   SubscriptionManager subscriptionManager,
                                   WsHeartbeat heartbeat,
                                   ObjectMapper objectMapper,
                                   WebSocketConnectionManager connectionManager) {
        this.parser = Objects.requireNonNull(parser, "parser must not be null");
        this.subscriptionManager = Objects.requireNonNull(subscriptionManager, "subscriptionManager must not be null");
        this.heartbeat = Objects.requireNonNull(heartbeat, "heartbeat must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager must not be null");
    }

    /**
     * WebSocket 連線建立後的回呼：
     * 登錄 session、建立 session context、傳送 WELCOME 訊息、向 heartbeat 註冊。
     *
     * @param session 已建立的 WebSocket session
     * @throws Exception 若傳送 WELCOME 訊息失敗
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Long userId = (Long) session.getAttributes().get(MarketHandshakeInterceptor.ATTR_USER_ID);
        String clientIp = (String) session.getAttributes().get(MarketHandshakeInterceptor.ATTR_CLIENT_IP);
        Integer tokenVersion = (Integer) session.getAttributes().get(MarketHandshakeInterceptor.ATTR_TOKEN_VERSION);
        WebSocketSession concurrentSession = new ConcurrentWebSocketSessionDecorator(
            session, SEND_TIME_LIMIT_MS, SEND_BUFFER_SIZE_LIMIT);
        sessions.put(session.getId(), concurrentSession);
        contexts.put(session.getId(), new SessionContext(userId, tokenVersion, java.time.Instant.now()));
        java.util.List<String> evicted = connectionManager.register(session.getId(), userId, clientIp);
        sendWelcome(concurrentSession);
        heartbeat.register(concurrentSession);
        for (String evictedId : evicted) {
            closeEvictedSession(evictedId);
        }
    }

    /**
     * 關閉因每帳號連線上限被 FIFO 驅逐的較舊 session（以 4002 SESSION_REPLACED）。
     *
     * @param sessionId 被驅逐的 session id
     */
    private void closeEvictedSession(String sessionId) {
        WebSocketSession evicted = sessions.get(sessionId);
        if (evicted == null) {
            return;
        }
        try {
            if (evicted.isOpen()) {
                evicted.close(CLOSE_SESSION_REPLACED);
            }
        } catch (Exception exception) {
            log.warn("關閉被驅逐 session {} 失敗：{}", sessionId, exception.getMessage());
        }
    }

    /**
     * 處理客戶端傳入的文字訊息。
     *
     * <p>處理流程：
     * <ol>
     *   <li>速率限制檢查 → 超限則以 4429 關閉</li>
     *   <li>解析訊息 → 格式錯誤累計至 10 次後以 4400 關閉</li>
     *   <li>依訊息類型路由：SUBSCRIBE / UNSUBSCRIBE / PONG</li>
     * </ol>
     *
     * @param session WebSocket session
     * @param message 客戶端傳入的文字訊息
     * @throws Exception 若傳送回應訊息失敗
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        SessionContext ctx = contexts.get(session.getId());
        if (ctx == null) {
            return;
        }

        WebSocketSession target = sessions.getOrDefault(session.getId(), session);

        if (!ctx.rateLimiter.tryAcquire()) {
            target.close(CLOSE_RATE_LIMITED);
            return;
        }

        ClientMessage msg;
        try {
            msg = parser.parse(message.getPayload());
        } catch (InvalidMessageException ex) {
            int errorCount = ctx.formatErrors.incrementAndGet();
            sendError(target, "WS_MSG_FORMAT_INVALID", ex.getMessage());
            if (errorCount >= MAX_FORMAT_ERRORS) {
                target.close(CLOSE_BAD_MESSAGE);
            }
            return;
        }

        switch (msg) {
            case ClientMessage.Subscribe sub -> {
                SubscribeResult result = subscriptionManager.subscribe(session.getId(), sub.channels());
                sendSubAck(target, result);
            }
            case ClientMessage.Unsubscribe unsub -> subscriptionManager.unsubscribe(session.getId(), unsub.channels());
            case ClientMessage.Pong ignored -> heartbeat.onPong(session.getId());
        }
    }

    /**
     * 傳輸層錯誤回呼：記錄日誌並關閉 session。
     *
     * @param session   發生錯誤的 WebSocket session
     * @param exception 傳輸層例外
     * @throws Exception 若關閉 session 失敗
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.warn("Session {} 傳輸層錯誤：{}", session.getId(), exception.getMessage());
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    /**
     * 連線關閉回呼：從 sessions 登錄移除、清理 session context、通知 SubscriptionManager 與 WsHeartbeat。
     *
     * @param session WebSocket session
     * @param status  關閉狀態
     * @throws Exception 若清理失敗
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session.getId());
        contexts.remove(session.getId());
        connectionManager.unregister(session.getId());
        subscriptionManager.removeSession(session.getId());
        heartbeat.unregister(session.getId());
    }

    /**
     * 依 sessionId 查找對應的 {@link WebSocketSession}。
     *
     * <p>供 {@link dowob.xyz.stockwebv2.marketdata.consumer.WsBroadcastConsumer} 廣播訊息時使用。
     * 若 session 已關閉或不存在（例如非預期斷線），回傳 {@link java.util.Optional#empty()}。
     *
     * @param sessionId WebSocket session ID
     * @return 包含 session 實例的 Optional，若不存在則為 empty
     */
    public java.util.Optional<WebSocketSession> findSession(String sessionId) {
        return java.util.Optional.ofNullable(sessions.get(sessionId));
    }

    /**
     * 取得目前所有活躍連線的快照，供 {@link WebSocketAuthValidator} 週期掃描。
     *
     * @return 連線快照清單（不含尚未取得 context 的 session）
     */
    public java.util.List<SessionSnapshot> snapshotSessions() {
        java.util.List<SessionSnapshot> snapshots = new java.util.ArrayList<>();
        contexts.forEach((sessionId, ctx) ->
            snapshots.add(new SessionSnapshot(sessionId, ctx.userId, ctx.tokenVersion, ctx.connectedAt)));
        return snapshots;
    }

    /**
     * 推送 {@code auth_expired} 通知後以 4001 關閉連線（security.md §9）。
     *
     * @param sessionId 目標 session id
     * @param reason    失效原因（如 {@code token_revoked} / {@code user_suspended} / {@code redis_unavailable}）
     */
    public void closeAuthExpired(String sessionId, String reason) {
        WebSocketSession session = sessions.get(sessionId);
        if (session == null) {
            return;
        }
        try {
            if (session.isOpen()) {
                ObjectNode node = objectMapper.createObjectNode();
                node.put("type", "auth_expired");
                node.put("reason", reason);
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(node)));
                session.close(CLOSE_AUTH_EXPIRED);
            }
        } catch (Exception exception) {
            log.warn("以 auth_expired 關閉 session {} 失敗：{}", sessionId, exception.getMessage());
        }
    }

    /**
     * 以 4003 關閉閒置逾時的連線（security.md §9）。
     *
     * @param sessionId 目標 session id
     */
    public void closeIdle(String sessionId) {
        closeQuietly(sessionId, CLOSE_IDLE_TIMEOUT);
    }

    /**
     * 伺服器正常關閉時，以 4009 關閉所有連線（security.md §9 SERVER_SHUTDOWN）。
     */
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        for (String sessionId : java.util.Set.copyOf(sessions.keySet())) {
            closeQuietly(sessionId, CLOSE_SERVER_SHUTDOWN);
        }
    }

    /**
     * 以指定關閉碼關閉連線並吞掉例外（關閉流程不應影響其他連線）。
     *
     * @param sessionId 目標 session id
     * @param status    關閉碼
     */
    private void closeQuietly(String sessionId, CloseStatus status) {
        WebSocketSession session = sessions.get(sessionId);
        if (session == null) {
            return;
        }
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (Exception exception) {
            log.warn("關閉 session {}（{}）失敗：{}", sessionId, status.getCode(), exception.getMessage());
        }
    }

    /**
     * 傳送 WELCOME 訊息給客戶端。
     *
     * @param session 目標 session
     * @throws Exception 若傳送失敗
     */
    private void sendWelcome(WebSocketSession session) throws Exception {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "WELCOME");
        node.put("sessionId", session.getId());
        node.put("maxSubscriptions", MAX_SUBSCRIPTIONS);
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(node)));
    }

    /**
     * 傳送訂閱確認（SUB_ACK）訊息給客戶端。
     *
     * @param session 目標 session
     * @param result  訂閱結果（含 accepted 與 rejected 清單）
     * @throws Exception 若傳送失敗
     */
    private void sendSubAck(WebSocketSession session, SubscribeResult result) throws Exception {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "SUB_ACK");
        var acceptedArr = node.putArray("accepted");
        result.accepted().forEach(acceptedArr::add);
        var rejectedArr = node.putArray("rejected");
        result.rejected().forEach(r -> {
            ObjectNode rejectedEntry = objectMapper.createObjectNode();
            rejectedEntry.put("channel", r.channel());
            rejectedEntry.put("reason", r.reason());
            rejectedArr.add(rejectedEntry);
        });
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(node)));
    }

    /**
     * 傳送 ERROR 訊息給客戶端。
     *
     * @param session  目標 session
     * @param code     錯誤代碼字串
     * @param detail   錯誤詳細說明
     * @throws Exception 若傳送失敗
     */
    private void sendError(WebSocketSession session, String code, String detail) throws Exception {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "ERROR");
        node.put("code", code);
        node.put("detail", detail);
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(node)));
    }

    /**
     * 單一 session 的上下文：含速率限制器與格式錯誤計數器。
     */
    static class SessionContext {

        /** 每秒最多 10 個訊息的速率限制器。 */
        final RateLimiter rateLimiter = new RateLimiter(10);

        /** 連續格式錯誤計數器。 */
        final AtomicInteger formatErrors = new AtomicInteger(0);

        /** 連線所屬使用者 id（來自 handshake attributes），供週期性重新驗證使用。 */
        final Long userId;

        /** handshake 當下的 tokenVersion 快照，供比對 Redis 是否已被撤銷。 */
        final Integer tokenVersion;

        /** 連線建立時間，供閒置逾時判斷。 */
        final java.time.Instant connectedAt;

        SessionContext(Long userId, Integer tokenVersion, java.time.Instant connectedAt) {
            this.userId = userId;
            this.tokenVersion = tokenVersion;
            this.connectedAt = connectedAt;
        }
    }

    /**
     * 供 {@link WebSocketAuthValidator} 週期掃描的連線快照。
     *
     * @param sessionId    session id
     * @param userId       所屬使用者 id
     * @param tokenVersion handshake 當下的 tokenVersion
     * @param connectedAt  連線建立時間
     */
    public record SessionSnapshot(String sessionId, Long userId, Integer tokenVersion, java.time.Instant connectedAt) {
    }

    /**
     * 簡易滑動視窗速率限制器：維護最近 N 個訊息的時間戳，
     * 若 1 秒內訊息數超過上限則拒絕。
     */
    static class RateLimiter {

        /** 每秒允許的最大訊息數。 */
        private final int maxPerSec;

        /** 最近訊息的時間戳 deque（毫秒）。 */
        private final Deque<Long> timestamps = new ArrayDeque<>();

        /**
         * 建立速率限制器。
         *
         * @param maxPerSec 每秒最大訊息數
         */
        RateLimiter(int maxPerSec) {
            this.maxPerSec = maxPerSec;
        }

        /**
         * 嘗試取得令牌（允許此次請求）。
         *
         * <p>先清除 1 秒外的舊時間戳，若目前 1 秒內的訊息數已達上限則拒絕；
         * 否則記錄此次時間戳並允許。
         *
         * @return {@code true} 表示允許；{@code false} 表示已超限
         */
        synchronized boolean tryAcquire() {
            long now = System.currentTimeMillis();
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() > 1000L) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= maxPerSec) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }
}
