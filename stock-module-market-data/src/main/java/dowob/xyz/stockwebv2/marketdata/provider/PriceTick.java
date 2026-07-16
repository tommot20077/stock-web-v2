package dowob.xyz.stockwebv2.marketdata.provider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Provider 回傳的單筆原始 tick，尚未對應內部 assetId（轉換在更上游 ingest service 處理）。
 * Symbol 是 caller 傳入並由 provider 回填，以利除錯與 contract 驗證。
 *
 * <p>此 record 為 immutable value object；{@code volume} 允許為 null（部分資料源無此欄位）。
 *
 * @param symbol provider 識別資產的代號，例如 {@code "AAPL"}；不可為 null
 * @param time   tick 發生時間（UTC）；不可為 null
 * @param price  成交價；不可為 null
 * @param volume 成交量；可為 null（部分資料源無此欄位）
 * @author Yuan
 * @version 1.0.0
 */
public record PriceTick(
        String symbol,
        Instant time,
        BigDecimal price,
        BigDecimal volume
) {
    /**
     * 緊湊建構子：對必填欄位執行 null 檢查。
     *
     * @throws NullPointerException 若 {@code symbol}、{@code time} 或 {@code price} 為 null
     */
    public PriceTick {
        Objects.requireNonNull(symbol, "symbol must not be null");
        Objects.requireNonNull(time,   "time must not be null");
        Objects.requireNonNull(price,  "price must not be null");
        // volume 允許為 null
    }
}
