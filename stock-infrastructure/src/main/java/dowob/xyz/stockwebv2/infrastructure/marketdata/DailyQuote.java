package dowob.xyz.stockwebv2.infrastructure.marketdata;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 一個資產在「目前交易日」的報價快照,跨模組傳遞用。
 *
 * <p>交易日依資產市場切(見 {@code TradingDay});前收是交易日起點之前的最後一筆成交,
 * 與 1D K 線前一根的 close 為同一個定義。
 *
 * @param latestPrice   最新成交價,不可為 null
 * @param priceTime     最新成交價的時間,不可為 null
 * @param previousClose 前一交易日收盤;資產在本交易日之前沒有任何成交時為 null
 * @param high          本交易日最高價(涵蓋最新價),不可為 null
 * @param low           本交易日最低價(涵蓋最新價),不可為 null
 * @param volume        本交易日累計成交量;無成交量資料時為 null
 * @author Yuan
 * @version 1.0.0
 */
public record DailyQuote(BigDecimal latestPrice,
                         OffsetDateTime priceTime,
                         BigDecimal previousClose,
                         BigDecimal high,
                         BigDecimal low,
                         BigDecimal volume) {

    /**
     * 必填欄位檢查。
     */
    public DailyQuote {
        Objects.requireNonNull(latestPrice, "latestPrice must not be null");
        Objects.requireNonNull(priceTime, "priceTime must not be null");
        Objects.requireNonNull(high, "high must not be null");
        Objects.requireNonNull(low, "low must not be null");
    }
}
