package dowob.xyz.stockwebv2.start.e2e;

import dowob.xyz.stockwebv2.start.e2e.support.AbstractWsE2E;
import dowob.xyz.stockwebv2.start.e2e.support.DatabaseCleaner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;

/**
 * WebSocket 每連線訂閱上限 E2E 測試（WATCH-05 / D-22）。
 *
 * <p>以真實 ticket + 真實 WebSocket 連線驗證：
 * <ol>
 *   <li>WELCOME 告知 {@code maxTickSubscriptions = 100}、{@code maxKlineSubscriptions = 10}，
 *       並保留 deprecated 的 {@code maxSubscriptions = 10}</li>
 *   <li>同一連線一則 SUBSCRIBE 帶 11 個 {@code tick.*} → 全部 accepted（舊的總上限 10 會拒第 11 個）</li>
 *   <li>同一連線再訂 11 個 {@code kline.*} → accepted 10、rejected 1（reason {@code SUBSCRIPTION_LIMIT_EXCEEDED}），
 *       且 tick 已佔用的 11 個不影響 kline 額度，連線不關閉</li>
 * </ol>
 *
 * <p>類別名以 {@code E2E} 結尾 → 由 {@code -Pe2e} profile 收進 CI E2E job 執行(D-23;需要 Docker)。
 *
 * @author Yuan
 * @version 1.0.0
 */
@DisplayName("WebSocket 訂閱上限 E2E（tick 100 / kline 10）")
class WsSubscriptionLimitE2E extends AbstractWsE2E {

    /** 等待單一伺服器訊息的逾時秒數。 */
    private static final long MESSAGE_TIMEOUT_SECONDS = 5;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanUserData();
    }

    /**
     * 一條連線先訂 11 個 tick（全收），再訂 11 個 kline（收 10 拒 1），連線維持開啟。
     *
     * @throws Exception 若連線、傳送或 JSON 解析失敗
     */
    @Test
    @DisplayName("WELCOME 告知兩個上限；11 個 tick 全收；11 個 kline 收 10 拒 1 且連線不關閉")
    void tickAndKlineLimitsAreCountedSeparatelyOnOneConnection() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        AuthSession auth = registerAndLogin(
            "ws-limit-" + suffix + "@example.com", "wslimit" + suffix, "Password1");
        String ticket = requestWsTicket(auth.accessToken());

        BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        AbstractWebSocketHandler handler = new AbstractWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                inbox.add(message.getPayload());
            }
        };
        WebSocketSession ws = new StandardWebSocketClient()
            .execute(handler, new WebSocketHttpHeaders(), URI.create(wsBaseUrl() + "?ticket=" + ticket))
            .get(10, TimeUnit.SECONDS);

        try {
            JsonNode welcome = awaitMessageOfType(inbox, "WELCOME");
            assertThat(welcome.get("maxTickSubscriptions").asInt()).isEqualTo(100);
            assertThat(welcome.get("maxKlineSubscriptions").asInt()).isEqualTo(10);
            assertThat(welcome.get("maxSubscriptions").asInt()).isEqualTo(10);

            List<String> ticks = IntStream.rangeClosed(1, 11)
                .mapToObj(i -> String.format("tick.S%02d", i))
                .toList();
            ws.sendMessage(new TextMessage(subscribeMessage(ticks)));
            JsonNode tickAck = awaitMessageOfType(inbox, "SUB_ACK");
            assertThat(tickAck.get("accepted")).hasSize(11);
            assertThat(tickAck.get("rejected")).isEmpty();

            List<String> klines = new ArrayList<>();
            for (String symbol : List.of("AAPL", "MSFT")) {
                for (String interval : List.of("1m", "5m", "15m", "1h", "1d")) {
                    klines.add("kline." + symbol + "." + interval);
                }
            }
            klines.add("kline.TSLA.1m");
            ws.sendMessage(new TextMessage(subscribeMessage(klines)));
            JsonNode klineAck = awaitMessageOfType(inbox, "SUB_ACK");
            assertThat(klineAck.get("accepted")).hasSize(10);
            assertThat(klineAck.get("rejected")).hasSize(1);
            assertThat(klineAck.get("rejected").get(0).get("channel").asString()).isEqualTo("kline.TSLA.1m");
            assertThat(klineAck.get("rejected").get(0).get("reason").asString())
                .isEqualTo("SUBSCRIPTION_LIMIT_EXCEEDED");

            assertThat(ws.isOpen()).as("超額只列入 rejected，連線不得關閉").isTrue();
        } finally {
            if (ws.isOpen()) {
                ws.close();
            }
        }
    }

    /**
     * 組出 SUBSCRIBE 訊息 JSON。
     *
     * @param channels 欲訂閱的 channel 清單
     * @return SUBSCRIBE 訊息字串
     * @throws Exception 若序列化失敗
     */
    private String subscribeMessage(List<String> channels) throws Exception {
        var node = objectMapper.createObjectNode();
        node.put("type", "SUBSCRIBE");
        var arr = node.putArray("channels");
        channels.forEach(arr::add);
        return objectMapper.writeValueAsString(node);
    }

    /**
     * 自 inbox 依序取出訊息，直到出現指定 type（略過 PING 等其他訊息）。
     *
     * @param inbox 伺服器訊息佇列
     * @param type  期望的訊息 type
     * @return 第一則符合 type 的訊息 JSON
     * @throws Exception 若逾時或 JSON 解析失敗
     */
    private JsonNode awaitMessageOfType(BlockingQueue<String> inbox, String type) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(MESSAGE_TIMEOUT_SECONDS);
        while (true) {
            long remaining = deadline - System.nanoTime();
            String payload = inbox.poll(Math.max(remaining, 0), TimeUnit.NANOSECONDS);
            assertThat(payload).as("等待 %s 訊息逾時", type).isNotNull();
            JsonNode node = objectMapper.readTree(payload);
            if (type.equals(node.path("type").asString())) {
                return node;
            }
        }
    }
}
