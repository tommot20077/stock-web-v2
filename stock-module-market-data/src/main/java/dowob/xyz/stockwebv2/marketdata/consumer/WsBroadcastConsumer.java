package dowob.xyz.stockwebv2.marketdata.consumer;

import dowob.xyz.stockwebv2.common.event.PriceTickEvent;
import dowob.xyz.stockwebv2.marketdata.config.KafkaConfig;
import dowob.xyz.stockwebv2.marketdata.ws.MarketWebSocketHandler;
import dowob.xyz.stockwebv2.marketdata.ws.SubscriptionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 將即時 tick 廣播給訂閱中的 WS 連線，同時更新 Redis latest cache 與 KLINE bucket。
 *
 * <p>只訂閱 tick topic（{@value KafkaConfig#TOPIC_PRICE_TICK}），不訂 backfill，
 * 避免歷史回填事件誤推給 WebSocket 客戶端。
 *
 * <p>每筆 tick 的處理流程：
 * <ol>
 *   <li>寫入 Redis {@code market:latest:{assetId}}（TTL {@link #LATEST_TTL}），供 REST {@code /latest} 端點使用</li>
 *   <li>廣播 TICK 訊息至 {@code tick.{symbol}} channel 的訂閱者</li>
 *   <li>更新 {@link KlineBucketAccumulator}，對各 interval 的 bucket 廣播 KLINE 訊息</li>
 * </ol>
 *
 * <p>錯誤隔離：單一 session 傳送失敗時，以 4500 關閉該 session，不影響其他連線。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Component
public class WsBroadcastConsumer {

    private static final Logger log = LoggerFactory.getLogger(WsBroadcastConsumer.class);

    /** Redis latest cache key 前綴。 */
    private static final String REDIS_LATEST_KEY = "market:latest:";

    /** Redis latest cache 存活時間：5 分鐘。 */
    private static final Duration LATEST_TTL = Duration.ofMinutes(5);

    /** 廣播傳送失敗時使用的自訂 WS 關閉碼。 */
    private static final CloseStatus CLOSE_SEND_FAILURE = new CloseStatus(4500, "Send failure");

    private final SubscriptionManager subscriptionManager;
    private final MarketWebSocketHandler handler;
    private final KlineBucketAccumulator klineAccumulator;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 建構子注入所有依賴。
     *
     * @param subscriptionManager 訂閱管理器，不可為 null
     * @param handler             WebSocket 處理器（提供 session 查找），不可為 null
     * @param klineAccumulator    K-線 bucket 累加器，不可為 null
     * @param redisTemplate       Redis 操作模板，不可為 null
     * @param objectMapper        JSON 序列化器，不可為 null
     */
    public WsBroadcastConsumer(SubscriptionManager subscriptionManager,
                               MarketWebSocketHandler handler,
                               KlineBucketAccumulator klineAccumulator,
                               StringRedisTemplate redisTemplate,
                               ObjectMapper objectMapper) {
        this.subscriptionManager = Objects.requireNonNull(subscriptionManager, "subscriptionManager must not be null");
        this.handler = Objects.requireNonNull(handler, "handler must not be null");
        this.klineAccumulator = Objects.requireNonNull(klineAccumulator, "klineAccumulator must not be null");
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * 消費單筆 {@link PriceTickEvent}：
     * <ol>
     *   <li>更新 Redis latest cache</li>
     *   <li>廣播 TICK 至訂閱者</li>
     *   <li>更新 KLINE bucket 並廣播 5 個 interval 訊息</li>
     * </ol>
     *
     * @param event 來自 Kafka 的 tick 事件
     */
    @KafkaListener(
            groupId = "market-data.ws-broadcast",
            topics = KafkaConfig.TOPIC_PRICE_TICK
    )
    public void onTick(PriceTickEvent event) {
        // Step 1: 更新 Redis latest cache
        writeLatestCache(event);

        // Step 2: 廣播 TICK
        String tickChannel = "tick." + event.symbol();
        Map<String, Object> tickData = buildTickData(event);
        Map<String, Object> tickPayload = buildPayload("TICK", tickChannel, tickData);
        broadcast(tickChannel, tickPayload);

        // Step 3: 更新 KLINE bucket 並廣播
        List<KlineBucket> buckets = klineAccumulator.updateAndSnapshot(
                event.assetId(), event.occurredAt(), event.price(), event.volume());
        for (KlineBucket bucket : buckets) {
            String klineChannel = "kline." + event.symbol() + "." + bucket.interval().code();
            Map<String, Object> klineData = buildKlineData(event.symbol(), bucket);
            Map<String, Object> klinePayload = buildPayload("KLINE", klineChannel, klineData);
            broadcast(klineChannel, klinePayload);
        }
    }

    /**
     * 寫入 Redis latest cache。
     *
     * <p>若序列化或 Redis 操作失敗，記錄 WARN 日誌並繼續處理，不影響廣播流程。
     *
     * @param event tick 事件
     */
    private void writeLatestCache(PriceTickEvent event) {
        try {
            Map<String, Object> latestData = buildTickData(event);
            String json = objectMapper.writeValueAsString(latestData);
            redisTemplate.opsForValue().set(REDIS_LATEST_KEY + event.assetId(), json, LATEST_TTL);
        } catch (Exception ex) {
            log.warn("Failed to write latest cache for assetId={}: {}", event.assetId(), ex.toString());
        }
    }

    /**
     * 廣播訊息至指定 channel 的所有訂閱者。
     *
     * <p>若 JSON 序列化失敗，記錄 WARN 日誌並提早返回。
     * 若特定 session 傳送失敗，以 4500 關閉該 session，繼續廣播至其他 session。
     *
     * @param channel channel 名稱
     * @param payload 要廣播的訊息 payload Map
     */
    private void broadcast(String channel, Map<String, Object> payload) {
        Set<String> sessionIds = subscriptionManager.subscribersOf(channel);
        if (sessionIds.isEmpty()) {
            return;
        }

        String text;
        try {
            text = objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            log.warn("Failed to serialize broadcast payload for channel={}: {}", channel, ex.toString());
            return;
        }

        TextMessage message = new TextMessage(text);
        for (String sessionId : sessionIds) {
            handler.findSession(sessionId).ifPresent(session -> sendToSession(session, sessionId, message));
        }
    }

    /**
     * 向單一 session 傳送訊息；若 session 已關閉則跳過；若傳送失敗則以 4500 關閉。
     *
     * @param session   目標 WebSocket session
     * @param sessionId session ID（用於日誌）
     * @param message   要傳送的文字訊息
     */
    private void sendToSession(WebSocketSession session, String sessionId, TextMessage message) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(message);
        } catch (Exception ex) {
            log.warn("Send to session {} failed, closing with 4500: {}", sessionId, ex.toString());
            try {
                session.close(CLOSE_SEND_FAILURE);
            } catch (Exception ignore) {
                // 若 close 也失敗，靜默忽略
            }
        }
    }

    /**
     * 建立 tick 資料 Map（用於 Redis cache 與 TICK 訊息 data 欄位）。
     *
     * <p>使用 {@link LinkedHashMap} 以保留 key 順序；volume 為 null 時以 {@code null} 存入。
     *
     * @param event tick 事件
     * @return tick 資料 Map
     */
    private Map<String, Object> buildTickData(PriceTickEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("symbol", event.symbol());
        data.put("price", event.price().toPlainString());
        data.put("volume", event.volume() == null ? null : event.volume().toPlainString());
        data.put("time", event.occurredAt().toString());
        return data;
    }

    /**
     * 建立 KLINE 訊息的資料 Map。
     *
     * @param symbol 資產代號
     * @param bucket K-線 bucket 快照
     * @return KLINE 資料 Map
     */
    private Map<String, Object> buildKlineData(String symbol, KlineBucket bucket) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("symbol", symbol);
        data.put("interval", bucket.interval().code());
        data.put("bucket", bucket.bucketStart().toString());
        data.put("open", bucket.open().toPlainString());
        data.put("high", bucket.high().toPlainString());
        data.put("low", bucket.low().toPlainString());
        data.put("close", bucket.close().toPlainString());
        data.put("volume", bucket.volume() == null ? null : bucket.volume().toPlainString());
        return data;
    }

    /**
     * 組裝頂層 payload Map：{@code type}、{@code channel}、{@code data}。
     *
     * @param type    訊息類型（{@code "TICK"} 或 {@code "KLINE"}）
     * @param channel channel 名稱
     * @param data    訊息資料
     * @return 完整 payload Map
     */
    private Map<String, Object> buildPayload(String type, String channel, Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        payload.put("channel", channel);
        payload.put("data", data);
        return payload;
    }
}
