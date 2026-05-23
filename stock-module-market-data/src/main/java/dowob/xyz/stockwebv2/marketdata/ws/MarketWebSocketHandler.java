package dowob.xyz.stockwebv2.marketdata.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
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
 * </ul>
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

    /** 4429 — 速率限制超過。 */
    static final CloseStatus CLOSE_RATE_LIMITED = new CloseStatus(4429, "Rate limit exceeded");

    private final WsMessageParser parser;
    private final SubscriptionManager subscriptionManager;
    private final WsHeartbeat heartbeat;
    private final ObjectMapper objectMapper;

    /** 每個 session 的上下文（速率限制 + 格式錯誤計數）。 */
    private final ConcurrentMap<String, SessionContext> contexts = new ConcurrentHashMap<>();

    /**
     * 建構子注入所有依賴。
     *
     * @param parser              訊息解析器，不可為 null
     * @param subscriptionManager 訂閱管理器，不可為 null
     * @param heartbeat           心跳排程器，不可為 null
     * @param objectMapper        Jackson ObjectMapper，不可為 null
     */
    public MarketWebSocketHandler(WsMessageParser parser,
                                   SubscriptionManager subscriptionManager,
                                   WsHeartbeat heartbeat,
                                   ObjectMapper objectMapper) {
        this.parser = Objects.requireNonNull(parser, "parser must not be null");
        this.subscriptionManager = Objects.requireNonNull(subscriptionManager, "subscriptionManager must not be null");
        this.heartbeat = Objects.requireNonNull(heartbeat, "heartbeat must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * WebSocket 連線建立後的回呼：
     * 建立 session context、傳送 WELCOME 訊息、向 heartbeat 註冊。
     *
     * @param session 已建立的 WebSocket session
     * @throws Exception 若傳送 WELCOME 訊息失敗
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        contexts.put(session.getId(), new SessionContext());
        sendWelcome(session);
        heartbeat.register(session);
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

        if (!ctx.rateLimiter.tryAcquire()) {
            session.close(CLOSE_RATE_LIMITED);
            return;
        }

        ClientMessage msg;
        try {
            msg = parser.parse(message.getPayload());
        } catch (InvalidMessageException ex) {
            int errorCount = ctx.formatErrors.incrementAndGet();
            sendError(session, "WS_MSG_FORMAT_INVALID", ex.getMessage());
            if (errorCount >= MAX_FORMAT_ERRORS) {
                session.close(CLOSE_BAD_MESSAGE);
            }
            return;
        }

        switch (msg) {
            case ClientMessage.Subscribe sub -> {
                SubscribeResult result = subscriptionManager.subscribe(session.getId(), sub.channels());
                sendSubAck(session, result);
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
     * 連線關閉回呼：清理 session context、通知 SubscriptionManager 與 WsHeartbeat。
     *
     * @param session WebSocket session
     * @param status  關閉狀態
     * @throws Exception 若清理失敗
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        contexts.remove(session.getId());
        subscriptionManager.removeSession(session.getId());
        heartbeat.unregister(session.getId());
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
