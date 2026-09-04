package dowob.xyz.stockwebv2.common.event;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 股票價格 Tick 事件，代表單筆即時成交價格資訊。
 *
 * <p>此 record 用於跨模組事件傳遞，例如透過 Kafka 發佈至下游消費者。
 *
 * <p>JSON 序列化注意事項：
 * <ul>
 *   <li>{@code price} 與 {@code volume} 欄位序列化為 JSON 字串，避免 JavaScript Number 精度損失。</li>
 *   <li>{@code occurredAt} 序列化為 ISO-8601 字串（需搭配 Jackson {@code JavaTimeModule}
 *       並停用 {@code WRITE_DATES_AS_TIMESTAMPS}）。</li>
 * </ul>
 *
 * @param eventId    事件唯一識別碼（UUID 字串格式）
 * @param occurredAt 事件發生時間
 * @param assetId    資產表 FK（對應 assets 資料表主鍵）
 * @param symbol     資產代號（例如 {@code "AAPL"}），供可讀性使用
 * @param price      成交價格；序列化為 JSON 字串
 * @param volume     成交量（可為 {@code null}）；序列化為 JSON 字串
 * @param source     資料來源（例如 {@code "mock"}、{@code "backfill"}）
 * @author Yuan
 * @version 1.0.0
 */
public record PriceTickEvent(
        String eventId,
        Instant occurredAt,
        Long assetId,
        String symbol,
        @JsonSerialize(using = ToStringSerializer.class)
        BigDecimal price,
        @JsonSerialize(using = ToStringSerializer.class)
        BigDecimal volume,
        String source
) {

    /**
     * 緊湊建構子，對必填欄位執行 null 檢查（{@code volume} 允許為 {@code null}）。
     */
    public PriceTickEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(symbol, "symbol must not be null");
        Objects.requireNonNull(price, "price must not be null");
        Objects.requireNonNull(source, "source must not be null");
        // volume 允許為 null
    }

    /**
     * 靜態工廠方法，自動產生 {@code eventId}（UUID）與 {@code occurredAt}（當前時間）。
     *
     * @param assetId 資產表 FK
     * @param symbol  資產代號
     * @param price   成交價格，不得為 {@code null}
     * @param volume  成交量，可為 {@code null}
     * @param source  資料來源，不得為 {@code null}
     * @return 已填入 {@code eventId} 與 {@code occurredAt} 的 {@link PriceTickEvent}
     */
    public static PriceTickEvent of(Long assetId, String symbol, BigDecimal price, BigDecimal volume, String source) {
        return new PriceTickEvent(
                UUID.randomUUID().toString(),
                Instant.now(),
                assetId,
                symbol,
                price,
                volume,
                source
        );
    }
}
