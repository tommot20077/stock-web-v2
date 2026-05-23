package dowob.xyz.stockwebv2.marketdata.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 市場價格 tick，對應 TimescaleDB hypertable {@code market_prices}。
 *
 * <p>無 surrogate ID，以組合鍵 {@code (assetId, time)} 識別唯一記錄。
 * 所有欄位皆使用 immutable value type，適合批次寫入與快取。
 *
 * @param assetId 對應 {@code assets.id} 的 FK，不可為 null
 * @param time    tick 時間戳（UTC），對應 hypertable 分區鍵，不可為 null
 * @param price   成交價格，精度 NUMERIC(24,8)，不可為 null
 * @param volume  成交量，精度 NUMERIC(24,8)，允許為 null
 * @author Yuan
 * @version 1.0.0
 */
public record MarketPrice(
    Long assetId,
    Instant time,
    BigDecimal price,
    BigDecimal volume
) {
    /**
     * Compact constructor：強制驗證必填欄位。
     *
     * @throws NullPointerException 若 {@code assetId}、{@code time} 或 {@code price} 為 null
     */
    public MarketPrice {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(time,    "time must not be null");
        Objects.requireNonNull(price,   "price must not be null");
        // volume 允許為 null，不驗證
    }
}
