package dowob.xyz.stockwebv2.trading.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 一筆用於計算今日價格損益的成交（買或賣），純資料。
 *
 * <p>只含計算今日損益需要的欄位；不含手續費，因為今日價格損益不扣手續費（D-15）。
 *
 * @param assetId    標的 id
 * @param type       買賣別
 * @param quantity   成交數量
 * @param price      成交價（原幣）
 * @param executedAt 成交時間
 * @author Yuan
 * @version 1.0
 */
public record DayFill(
    Long assetId,
    TradeType type,
    BigDecimal quantity,
    BigDecimal price,
    OffsetDateTime executedAt
) {
}
