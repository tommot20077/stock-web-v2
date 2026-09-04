package dowob.xyz.stockwebv2.marketdata.consumer;

import dowob.xyz.stockwebv2.common.event.PriceTickEvent;
import dowob.xyz.stockwebv2.marketdata.ws.MarketWebSocketHandler;
import dowob.xyz.stockwebv2.marketdata.ws.SubscriptionManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import tools.jackson.databind.ObjectMapper;

/**
 * {@link WsBroadcastConsumer} 單元測試（Mockito-based）。
 *
 * <p>驗證範圍：
 * <ul>
 *   <li>接收 tick 後寫入 Redis latest cache，key = {@code market:latest:{assetId}}，TTL = 5 分鐘</li>
 *   <li>廣播 TICK 訊息至 {@code tick.{symbol}} channel 的訂閱者</li>
 *   <li>無訂閱者時不廣播 tick</li>
 *   <li>廣播 5 個 KLINE 訊息至對應 channel</li>
 *   <li>單一 session 傳送失敗 → 以 4500 關閉該 session，其他 session 不受影響</li>
 *   <li>已關閉的 session 不嘗試傳送</li>
 *   <li>handler.findSession 回傳 empty → 跳過，不拋出例外</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
class WsBroadcastConsumerTest {

    @Mock
    SubscriptionManager subscriptionManager;

    @Mock
    MarketWebSocketHandler handler;

    @Mock
    StringRedisTemplate redisTemplate;

    @Mock
    ValueOperations<String, String> valueOps;

    KlineBucketAccumulator accumulator;
    ObjectMapper objectMapper;

    WsBroadcastConsumer consumer;

    @BeforeEach
    void setup() {
        accumulator = new KlineBucketAccumulator();
        objectMapper = new ObjectMapper();
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        consumer = new WsBroadcastConsumer(subscriptionManager, handler, accumulator, redisTemplate, objectMapper);
    }

    // ── Redis latest cache ────────────────────────────────────────────────────

    @Test
    @DisplayName("onTick → 寫入 Redis latest cache，key = market:latest:42，TTL = 5 分鐘")
    void onTick_writesRedisLatest_withCorrectKey() {
        PriceTickEvent event = PriceTickEvent.of(42L, "AAPL", new BigDecimal("100"), new BigDecimal("500"), "mock");
        when(subscriptionManager.subscribersOf(anyString())).thenReturn(Set.of());

        consumer.onTick(event);

        verify(valueOps).set(eq("market:latest:42"), anyString(), eq(Duration.ofMinutes(5)));
    }

    // ── tick broadcast ────────────────────────────────────────────────────────

    @Test
    @DisplayName("onTick → 廣播 TICK 訊息至 tick.AAPL 訂閱者")
    void onTick_broadcastsTickToSubscribers() throws Exception {
        PriceTickEvent event = PriceTickEvent.of(42L, "AAPL", new BigDecimal("100"), new BigDecimal("500"), "mock");
        WebSocketSession sess = mock(WebSocketSession.class);
        when(sess.isOpen()).thenReturn(true);
        when(subscriptionManager.subscribersOf("tick.AAPL")).thenReturn(Set.of("s1"));
        when(subscriptionManager.subscribersOf(startsWith("kline."))).thenReturn(Set.of());
        when(handler.findSession("s1")).thenReturn(Optional.of(sess));

        consumer.onTick(event);

        ArgumentCaptor<TextMessage> cap = ArgumentCaptor.forClass(TextMessage.class);
        verify(sess, atLeastOnce()).sendMessage(cap.capture());
        boolean hasTickMessage = cap.getAllValues().stream()
                .anyMatch(m -> m.getPayload().contains("\"type\":\"TICK\"")
                        && m.getPayload().contains("\"channel\":\"tick.AAPL\"")
                        && m.getPayload().contains("\"symbol\":\"AAPL\""));
        assertThat(hasTickMessage).isTrue();
    }

    @Test
    @DisplayName("onTick → 無訂閱者時不廣播 tick")
    void onTick_doesNotBroadcastTickIfNoSubscribers() throws Exception {
        PriceTickEvent event = PriceTickEvent.of(42L, "AAPL", new BigDecimal("100"), new BigDecimal("500"), "mock");
        when(subscriptionManager.subscribersOf(anyString())).thenReturn(Set.of());

        consumer.onTick(event);

        // handler.findSession 不應被呼叫（因為沒有訂閱者）
        verify(handler, never()).findSession(anyString());
    }

    // ── KLINE broadcast ───────────────────────────────────────────────────────

    @Test
    @DisplayName("onTick → 廣播 5 個 KLINE 訊息（1m/5m/15m/1h/1d）")
    void onTick_broadcasts5KlineChannels() throws Exception {
        PriceTickEvent event = PriceTickEvent.of(42L, "AAPL", new BigDecimal("100"), new BigDecimal("500"), "mock");
        WebSocketSession sess = mock(WebSocketSession.class);
        when(sess.isOpen()).thenReturn(true);
        when(subscriptionManager.subscribersOf(anyString())).thenReturn(Set.of("s1"));
        when(handler.findSession("s1")).thenReturn(Optional.of(sess));

        consumer.onTick(event);

        // 1 TICK + 5 KLINE = 6 messages
        verify(sess, atLeast(6)).sendMessage(any());

        ArgumentCaptor<TextMessage> cap = ArgumentCaptor.forClass(TextMessage.class);
        verify(sess, atLeast(6)).sendMessage(cap.capture());
        long klineCount = cap.getAllValues().stream()
                .filter(m -> m.getPayload().contains("\"type\":\"KLINE\""))
                .count();
        assertThat(klineCount).isEqualTo(5);
    }

    // ── send failure isolation ─────────────────────────────────────────────────

    @Test
    @DisplayName("onTick → 單一 session 傳送失敗 → 以 4500 關閉，其他 session 不受影響")
    void onTick_individualSendFailure_closesThatSessionOnly_othersContinue() throws Exception {
        PriceTickEvent event = PriceTickEvent.of(42L, "AAPL", new BigDecimal("100"), new BigDecimal("500"), "mock");
        WebSocketSession failing = mock(WebSocketSession.class);
        WebSocketSession ok = mock(WebSocketSession.class);
        when(failing.isOpen()).thenReturn(true);
        when(ok.isOpen()).thenReturn(true);
        doThrow(new IOException("simulated failure")).when(failing).sendMessage(any());

        // tick.AAPL 有兩個訂閱者
        when(subscriptionManager.subscribersOf("tick.AAPL")).thenReturn(Set.of("sFail", "sOk"));
        // kline channels 無訂閱者，避免干擾驗證
        when(subscriptionManager.subscribersOf(startsWith("kline."))).thenReturn(Set.of());
        when(handler.findSession("sFail")).thenReturn(Optional.of(failing));
        when(handler.findSession("sOk")).thenReturn(Optional.of(ok));

        consumer.onTick(event);

        // failing session 應被以 4500 關閉
        verify(failing).close(argThat(s -> s.getCode() == 4500));
        // ok session 仍應收到訊息
        verify(ok).sendMessage(any());
    }

    @Test
    @DisplayName("onTick → 已關閉的 session 不嘗試傳送")
    void onTick_closedSessionsSkipped() throws Exception {
        PriceTickEvent event = PriceTickEvent.of(42L, "AAPL", new BigDecimal("100"), new BigDecimal("500"), "mock");
        WebSocketSession sess = mock(WebSocketSession.class);
        when(sess.isOpen()).thenReturn(false);
        when(subscriptionManager.subscribersOf(anyString())).thenReturn(Set.of("s1"));
        when(handler.findSession("s1")).thenReturn(Optional.of(sess));

        consumer.onTick(event);

        verify(sess, never()).sendMessage(any());
    }

    @Test
    @DisplayName("onTick → handler.findSession 回傳 empty → 跳過，不拋出例外")
    void onTick_unknownSessionIdInSubscribers_skipped() throws Exception {
        PriceTickEvent event = PriceTickEvent.of(42L, "AAPL", new BigDecimal("100"), new BigDecimal("500"), "mock");
        when(subscriptionManager.subscribersOf("tick.AAPL")).thenReturn(Set.of("ghost"));
        when(subscriptionManager.subscribersOf(startsWith("kline."))).thenReturn(Set.of());
        when(handler.findSession("ghost")).thenReturn(Optional.empty());

        // 不應拋出任何例外
        consumer.onTick(event);
    }

    // ── null volume handling ──────────────────────────────────────────────────

    @Test
    @DisplayName("onTick → volume 為 null → Redis cache 正常寫入，不拋出例外")
    void onTick_nullVolume_redisAndBroadcastSucceed() throws Exception {
        PriceTickEvent event = PriceTickEvent.of(42L, "AAPL", new BigDecimal("100"), null, "mock");
        WebSocketSession sess = mock(WebSocketSession.class);
        when(sess.isOpen()).thenReturn(true);
        when(subscriptionManager.subscribersOf(anyString())).thenReturn(Set.of("s1"));
        when(handler.findSession("s1")).thenReturn(Optional.of(sess));

        consumer.onTick(event);

        verify(valueOps).set(eq("market:latest:42"), anyString(), eq(Duration.ofMinutes(5)));
        // 訊息正常廣播（不因 null volume 而拋出）
        verify(sess, atLeast(1)).sendMessage(any());
    }
}
