package dowob.xyz.stockwebv2.marketdata.persistence;

import java.math.BigDecimal;

/**
 * 單一資產在某個交易日的統計:前收與當日高低量。
 *
 * @param assetId       資產 id
 * @param previousClose 交易日起點之前最後一筆成交價;沒有時為 null
 * @param high          當日最高價;當日尚無成交時為 null
 * @param low           當日最低價;當日尚無成交時為 null
 * @param volume        當日累計成交量;可為 null
 * @author Yuan
 * @version 1.0.0
 */
public record DailyStats(Long assetId, BigDecimal previousClose, BigDecimal high, BigDecimal low,
                         BigDecimal volume) {
}
