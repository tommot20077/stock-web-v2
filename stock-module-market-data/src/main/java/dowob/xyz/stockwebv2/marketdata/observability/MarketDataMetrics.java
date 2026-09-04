package dowob.xyz.stockwebv2.marketdata.observability;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

/**
 * Market-data 模組業務 metrics 註冊與存取點。集中此處方便文件化與測試。
 *
 * <p>對應 spec §觀測 中所列的 metric 集合：
 * <ul>
 *   <li>{@code market.tick.produced} — tick 產出計數（producer 端）</li>
 *   <li>{@code market.tick.consumed} — tick 消費計數（consumer 端）</li>
 *   <li>{@code market.tick.persist.duration} — 資料庫寫入耗時</li>
 *   <li>{@code market.tick.persist.failed} — 資料庫寫入失敗計數</li>
 *   <li>{@code market.ws.connections.active} — 當前活躍 WS 連線數（gauge）</li>
 *   <li>{@code market.ws.subscriptions.active} — 當前訂閱數（gauge，含 channel_type tag）</li>
 *   <li>{@code market.ws.messages.broadcast} — 廣播訊息計數</li>
 *   <li>{@code market.ws.messages.rejected} — 拒絕訊息計數</li>
 *   <li>{@code market.ws.ticket.issued} — ticket 發出計數</li>
 *   <li>{@code market.ws.ticket.consumed} — ticket 消費計數（含 result tag）</li>
 *   <li>{@code market.provider.fetch.failed} — provider fetch 失敗計數</li>
 *   <li>{@code market.backfill.job.duration} — backfill job 耗時（含 status tag）</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0.0
 */
@Component
public class MarketDataMetrics {

    private final MeterRegistry registry;

    // ── gauges（AtomicInteger 供 Micrometer gauge 追蹤）────────────────────────
    private final AtomicInteger wsConnectionsActive = new AtomicInteger();
    private final AtomicInteger wsSubscriptionsActiveTick = new AtomicInteger();
    private final AtomicInteger wsSubscriptionsActiveKline = new AtomicInteger();

    /**
     * 建構並向 {@link MeterRegistry} 註冊所有 gauge。
     *
     * @param registry Micrometer meter registry，由 Spring Boot auto-config 注入
     */
    public MarketDataMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge("market.ws.connections.active", wsConnectionsActive);
        registry.gauge("market.ws.subscriptions.active",
                Tags.of("channel_type", "tick"), wsSubscriptionsActiveTick);
        registry.gauge("market.ws.subscriptions.active",
                Tags.of("channel_type", "kline"), wsSubscriptionsActiveKline);
    }

    // ── counter helpers（依 tag combo 懶建立）────────────────────────────────

    /**
     * tick 產出計數器，依 provider 與 asset_type 區分。
     *
     * @param provider  資料來源提供者名稱，例如 {@code "mock"}
     * @param assetType 資產類型，例如 {@code "STOCK"}
     * @return 對應的 {@link Counter}
     */
    public Counter tickProduced(String provider, String assetType) {
        return registry.counter("market.tick.produced",
                "provider", provider, "asset_type", assetType);
    }

    /**
     * tick 消費計數器，依 consumer_group 與 asset_type 區分。
     *
     * @param consumerGroup Kafka consumer group 名稱
     * @param assetType     資產類型，例如 {@code "STOCK"}
     * @return 對應的 {@link Counter}
     */
    public Counter tickConsumed(String consumerGroup, String assetType) {
        return registry.counter("market.tick.consumed",
                "consumer_group", consumerGroup, "asset_type", assetType);
    }

    /**
     * tick 資料庫寫入失敗計數器，依失敗原因區分。
     *
     * @param reason 失敗原因，例如 exception class 名稱
     * @return 對應的 {@link Counter}
     */
    public Counter tickPersistFailed(String reason) {
        return registry.counter("market.tick.persist.failed", "reason", reason);
    }

    /**
     * WS 廣播訊息計數器，依 channel_type 區分。
     *
     * @param channelType 頻道類型，例如 {@code "tick"} 或 {@code "kline"}
     * @return 對應的 {@link Counter}
     */
    public Counter wsMessagesBroadcast(String channelType) {
        return registry.counter("market.ws.messages.broadcast", "channel_type", channelType);
    }

    /**
     * WS 拒絕訊息計數器，依拒絕原因區分。
     *
     * @param reason 拒絕原因，例如 {@code "invalid_format"}
     * @return 對應的 {@link Counter}
     */
    public Counter wsMessagesRejected(String reason) {
        return registry.counter("market.ws.messages.rejected", "reason", reason);
    }

    /**
     * WS ticket 發出計數器。
     *
     * @return 對應的 {@link Counter}
     */
    public Counter wsTicketIssued() {
        return registry.counter("market.ws.ticket.issued");
    }

    /**
     * WS ticket 消費計數器，依消費結果區分。
     *
     * @param result 消費結果，例如 {@code "success"} 或 {@code "expired"}
     * @return 對應的 {@link Counter}
     */
    public Counter wsTicketConsumed(String result) {
        return registry.counter("market.ws.ticket.consumed", "result", result);
    }

    /**
     * Provider fetch 失敗計數器，依 provider 區分。
     *
     * @param provider 資料來源提供者名稱
     * @return 對應的 {@link Counter}
     */
    public Counter providerFetchFailed(String provider) {
        return registry.counter("market.provider.fetch.failed", "provider", provider);
    }

    /**
     * tick 資料庫寫入耗時 timer。
     *
     * @return 對應的 {@link Timer}
     */
    public Timer tickPersistDuration() {
        return registry.timer("market.tick.persist.duration");
    }

    /**
     * backfill job 耗時 timer，依完成狀態區分。
     *
     * @param status job 完成狀態，例如 {@code "COMPLETED"} 或 {@code "FAILED"}
     * @return 對應的 {@link Timer}
     */
    public Timer backfillJobDuration(String status) {
        return registry.timer("market.backfill.job.duration", "status", status);
    }

    // ── gauge setters ─────────────────────────────────────────────────────────

    /**
     * 直接設定活躍 WS 連線數。
     *
     * @param n 連線數
     */
    public void wsConnectionsActive(int n) {
        wsConnectionsActive.set(n);
    }

    /**
     * WS 新連線建立時增計。
     */
    public void incrementWsConnections() {
        wsConnectionsActive.incrementAndGet();
    }

    /**
     * WS 連線關閉時減計。
     */
    public void decrementWsConnections() {
        wsConnectionsActive.decrementAndGet();
    }

    /**
     * 設定活躍 tick 訂閱數。
     *
     * @param n 訂閱數
     */
    public void wsSubscriptionsTick(int n) {
        wsSubscriptionsActiveTick.set(n);
    }

    /**
     * 設定活躍 kline 訂閱數。
     *
     * @param n 訂閱數
     */
    public void wsSubscriptionsKline(int n) {
        wsSubscriptionsActiveKline.set(n);
    }

    // ── testing accessor ──────────────────────────────────────────────────────

    /**
     * 回傳當前活躍連線數，供測試驗證。
     *
     * @return 活躍連線數
     */
    int currentConnections() {
        return wsConnectionsActive.get();
    }
}
