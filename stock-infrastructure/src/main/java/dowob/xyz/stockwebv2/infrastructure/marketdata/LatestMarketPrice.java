package dowob.xyz.stockwebv2.infrastructure.marketdata;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 一筆最新成交價快照，跨模組傳遞用。
 *
 * <p>時間型別採 {@link OffsetDateTime} 以對齊 API 層的 DTO（trading 的 {@code HoldingDto.priceTime}）；
 * market-data 內部以 {@code Instant} 儲存，由 facade 實作負責轉換。
 *
 * @param price     最新成交價，不可為 null
 * @param priceTime 該價格的時間戳，不可為 null
 * @author Yuan
 * @version 1.0.0
 */
public record LatestMarketPrice(BigDecimal price, OffsetDateTime priceTime) {

    /**
     * @param price     最新成交價
     * @param priceTime 該價格的時間戳
     */
    public LatestMarketPrice {
        Objects.requireNonNull(price, "price must not be null");
        Objects.requireNonNull(priceTime, "priceTime must not be null");
    }
}
