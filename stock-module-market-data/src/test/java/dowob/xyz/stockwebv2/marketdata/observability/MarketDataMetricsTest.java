package dowob.xyz.stockwebv2.marketdata.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MarketDataMetrics} 單元測試 — 驗證 metric 計數器、gauge 與 timer 的正確性。
 *
 * <p>使用 {@link SimpleMeterRegistry} 進行純記憶體測試，不需要啟動 Spring 容器。
 *
 * @author Yuan
 * @version 1.0.0
 */
class MarketDataMetricsTest {

    SimpleMeterRegistry registry;
    MarketDataMetrics metrics;

    @BeforeEach
    void setup() {
        registry = new SimpleMeterRegistry();
        metrics = new MarketDataMetrics(registry);
    }

    @Test
    @DisplayName("tickProduced — 同 tags 兩次 increment 後 count == 2.0")
    void tickProduced_registersCounterWithTags() {
        metrics.tickProduced("mock", "STOCK").increment();
        metrics.tickProduced("mock", "STOCK").increment();
        assertThat(registry.find("market.tick.produced")
                .tag("provider", "mock")
                .tag("asset_type", "STOCK")
                .counter()
                .count())
                .isEqualTo(2.0);
    }

    @Test
    @DisplayName("wsConnectionsActive gauge — increment/decrement 後值正確")
    void wsConnectionsActive_gaugeReflectsValue() {
        metrics.incrementWsConnections();
        metrics.incrementWsConnections();
        metrics.decrementWsConnections();
        assertThat(registry.find("market.ws.connections.active").gauge().value())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("wsSubscriptionsActive — tick 與 kline gauge 各自獨立")
    void wsSubscriptionsActive_tickAndKline_independent() {
        metrics.wsSubscriptionsTick(5);
        metrics.wsSubscriptionsKline(20);
        assertThat(registry.find("market.ws.subscriptions.active")
                .tag("channel_type", "tick")
                .gauge()
                .value())
                .isEqualTo(5.0);
        assertThat(registry.find("market.ws.subscriptions.active")
                .tag("channel_type", "kline")
                .gauge()
                .value())
                .isEqualTo(20.0);
    }

    @Test
    @DisplayName("wsTicketConsumed — 不同 result tag 各自計數")
    void wsTicketConsumed_perResultTag() {
        metrics.wsTicketConsumed("success").increment();
        metrics.wsTicketConsumed("expired").increment(2);
        assertThat(registry.find("market.ws.ticket.consumed")
                .tag("result", "success")
                .counter()
                .count())
                .isEqualTo(1.0);
        assertThat(registry.find("market.ws.ticket.consumed")
                .tag("result", "expired")
                .counter()
                .count())
                .isEqualTo(2.0);
    }

    @Test
    @DisplayName("backfillJobDuration — 記錄計時後 count == 1")
    void backfillJobDuration_recordsTiming() {
        metrics.backfillJobDuration("COMPLETED").record(Duration.ofMillis(150));
        assertThat(registry.find("market.backfill.job.duration")
                .tag("status", "COMPLETED")
                .timer()
                .count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("tickConsumed — counter 含 consumer_group 與 asset_type tags")
    void tickConsumed_registersCounterWithTags() {
        metrics.tickConsumed("market-data.persist", "STOCK").increment(3);
        assertThat(registry.find("market.tick.consumed")
                .tag("consumer_group", "market-data.persist")
                .tag("asset_type", "STOCK")
                .counter()
                .count())
                .isEqualTo(3.0);
    }

    @Test
    @DisplayName("wsConnectionsActive setter — 直接設值後 gauge 反映正確")
    void wsConnectionsActive_setter() {
        metrics.wsConnectionsActive(7);
        assertThat(registry.find("market.ws.connections.active").gauge().value())
                .isEqualTo(7.0);
    }

    @Test
    @DisplayName("wsMessagesBroadcast — counter 含 channel_type tag")
    void wsMessagesBroadcast_registersCounter() {
        metrics.wsMessagesBroadcast("tick").increment(10);
        assertThat(registry.find("market.ws.messages.broadcast")
                .tag("channel_type", "tick")
                .counter()
                .count())
                .isEqualTo(10.0);
    }

    @Test
    @DisplayName("tickPersistDuration — timer 記錄一次後 count == 1")
    void tickPersistDuration_recordsTiming() {
        metrics.tickPersistDuration().record(Duration.ofMillis(50));
        assertThat(registry.find("market.tick.persist.duration").timer().count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("currentConnections — package-private accessor 反映最新狀態")
    void currentConnections_reflectsState() {
        metrics.wsConnectionsActive(3);
        assertThat(metrics.currentConnections()).isEqualTo(3);
    }
}
