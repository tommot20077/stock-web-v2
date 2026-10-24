package dowob.xyz.stockwebv2.trading.api;

import java.math.BigDecimal;

/**
 * 產業分布的一列（供前端產業卡使用）。
 *
 * <p>{@code sector} 直接取自資產的 {@code sector} 原值（D-19），沒有分類時為 {@code null}，
 * 由前端顯示「未分類」（後端不回在地化字串）。金額皆為使用者基準幣。</p>
 *
 * @param sector        產業分類原值；可為 null（未分類）
 * @param value         該產業的基準幣市值，scale 8
 * @param weight        佔比，比值 0..1（不是百分比），scale 8；分母 = 可折算持倉市值（不含現金）
 * @param holdingCount  該產業內的持倉檔數
 * @param unrealizedPnl 該產業的基準幣未實現損益（市值 − 成本，皆已折算）
 * @author Yuan
 * @version 1.0
 */
public record SectorAllocationDto(
    String sector,
    BigDecimal value,
    BigDecimal weight,
    int holdingCount,
    BigDecimal unrealizedPnl
) {
}
