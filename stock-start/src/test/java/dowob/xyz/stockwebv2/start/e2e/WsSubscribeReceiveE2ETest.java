package dowob.xyz.stockwebv2.start.e2e;

import dowob.xyz.stockwebv2.common.event.PriceTickEvent;
import dowob.xyz.stockwebv2.marketdata.ingest.MarketDataIngestService;
import dowob.xyz.stockwebv2.start.e2e.support.AbstractWsE2ETest;
import dowob.xyz.stockwebv2.start.e2e.support.DatabaseCleaner;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WebSocket 訂閱與接收 E2E 測試。
 *
 * <p>驗證完整的 WS 訂閱與 tick 推播流程：
 * <ol>
 *   <li>建立 WS 連線（ticket 驗證）</li>
 *   <li>傳送 SUBSCRIBE {@code tick.AAPL}</li>
 *   <li>透過 {@link MarketDataIngestService#publishTick} 直接注入 tick 事件至 Kafka</li>
 *   <li>等待 WsBroadcastConsumer 消費並廣播，驗證 client 收到 TICK 訊息</li>
 * </ol>
 *
 * <p>此測試需要真實 Kafka + 真實 WebSocket 連線，依賴 {@link AbstractWsE2ETest} 提供的 container 環境。
 *
 * @author Yuan
 * @version 1.0.0
 */
@DisplayName("WebSocket Subscribe & Receive E2E")
class WsSubscribeReceiveE2ETest extends AbstractWsE2ETest {

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private MarketDataIngestService ingestService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanUserData();
    }

    /**
     * 訂閱 {@code tick.AAPL} 並注入 tick 事件，驗證 client 收到 TICK 廣播。
     *
     * <p>AAPL 的 assetId 從種子資料（V2 migration）中查詢，
     * 注入的 tick 事件透過 Kafka 流轉至 {@code WsBroadcastConsumer}，
     * 後者將 TICK 訊息廣播至訂閱了 {@code tick.AAPL} 的 WS session。
     */
    @Test
    @DisplayName("訂閱 tick.AAPL 後注入 tick → 收到 TICK 廣播訊息")
    void subscribeAndReceiveTick() throws Exception {
        // 從種子資料中查詢 AAPL 的 assetId
        Long aaplAssetId = jdbcTemplate.queryForObject(
            "SELECT id FROM assets WHERE symbol = 'AAPL' LIMIT 1", Long.class);
        assertThat(aaplAssetId).as("AAPL asset must exist in seed data").isNotNull();

        AuthSession session = registerAndLogin(
            "ws-subscribe-e2e@example.com", "wssubscribee2e", "Password1");
        String ticket = requestWsTicket(session.accessToken());

        // WS client callback thread 與 test thread 共享, 必須 thread-safe
        List<String> received = new CopyOnWriteArrayList<>();
        WebSocketSession wsSession = connectAndSubscribe(ticket, "tick.AAPL", received);

        // 注入一個 AAPL tick 至 Kafka（市場即時 topic）
        PriceTickEvent event = PriceTickEvent.of(
            aaplAssetId, "AAPL",
            new BigDecimal("182.50"),
            new BigDecimal("1000"),
            "e2e-test"
        );
        ingestService.publishTick(event).get(5, TimeUnit.SECONDS);

        // 等待 WsBroadcastConsumer 消費並廣播（允許最多 15 秒含 Kafka lag）
        Awaitility.await()
            .atMost(15, TimeUnit.SECONDS)
            .until(() -> received.stream().anyMatch(msg -> msg.contains("TICK")));

        String tickMsg = received.stream()
            .filter(msg -> msg.contains("TICK"))
            .findFirst()
            .orElseThrow();
        assertThat(tickMsg).contains("AAPL");

        if (wsSession != null && wsSession.isOpen()) {
            wsSession.close();
        }
    }

    /**
     * 建立 WS 連線、等待 WELCOME，然後傳送 SUBSCRIBE 訊息。
     *
     * @param ticket   一次性 WS ticket
     * @param channel  訂閱的 channel 名稱（例如 {@code "tick.AAPL"}）
     * @param received 接收到的所有訊息列表（由 handler 持續填入）
     * @return 已建立的 {@link WebSocketSession}
     * @throws Exception 若連線或訂閱失敗
     */
    private WebSocketSession connectAndSubscribe(
            String ticket,
            String channel,
            List<String> received) throws Exception {

        StandardWebSocketClient client = new StandardWebSocketClient();
        URI uri = URI.create(wsBaseUrl() + "?ticket=" + ticket);

        CompletableFuture<String> welcomeFuture = new CompletableFuture<>();

        AbstractWebSocketHandler handler = new AbstractWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
                String payload = message.getPayload();
                received.add(payload);
                if (payload.contains("WELCOME") && !welcomeFuture.isDone()) {
                    welcomeFuture.complete(payload);
                }
            }
        };

        CompletableFuture<WebSocketSession> sessionFuture =
            client.execute(handler, new WebSocketHttpHeaders(), uri);
        WebSocketSession wsSession = sessionFuture.get(10, TimeUnit.SECONDS);

        // 等待 WELCOME 後再訂閱
        welcomeFuture.get(5, TimeUnit.SECONDS);

        String subscribeMsg = String.format(
            "{\"type\":\"SUBSCRIBE\",\"channels\":[\"%s\"]}", channel);
        wsSession.sendMessage(new TextMessage(subscribeMsg));

        return wsSession;
    }
}
