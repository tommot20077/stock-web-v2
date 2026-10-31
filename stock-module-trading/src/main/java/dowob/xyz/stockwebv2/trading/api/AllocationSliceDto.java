package dowob.xyz.stockwebv2.trading.api;

import java.math.BigDecimal;

/**
 * 資產配置的一塊（類別配置，供前端 donut 使用）。
 *
 * <p>金額與權重皆由後端以使用者基準幣算好（D-21），前端不需要知道匯率。</p>
 *
 * @param category 類別：{@code AssetType} 常數名（{@code STOCK} / {@code CRYPTO} / {@code BOND} / {@code FX}）
 *                 或 {@code CASH}（正的可用現金，D-18）
 * @param value    該類別的基準幣價值，scale 8
 * @param weight   佔比，比值 0..1（不是百分比），scale 8；分母 = 可折算持倉市值 + 正現金
 * @author Yuan
 * @version 1.0
 */
public record AllocationSliceDto(String category, BigDecimal value, BigDecimal weight) {
}
