package dowob.xyz.stockwebv2.trading.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * 投資組合摘要。
 *
 * <p><strong>2026-10-24 單位語意變更（Phase 04.1 D-05）：</strong>所有金額欄位改以 {@code baseCurrency}
 * 表示（使用者基準幣）；先前版本是未折算的混幣加總。欄位名不變，消費者須改讀 {@code baseCurrency}
 * 判斷單位。{@code roi} 語意不變：{@code totalPnl / totalCostBasis}（兩者同為基準幣）。
 *
 * @param totalMarketValue      持倉總市值（基準幣）
 * @param totalCostBasis        持倉總成本（基準幣）
 * @param realizedPnl           已實現損益（基準幣；逐資產依各自幣別折算，含已平倉）
 * @param unrealizedPnl         未實現損益（基準幣）
 * @param totalPnl              總損益 = realizedPnl + unrealizedPnl（基準幣）
 * @param roi                   報酬率 = totalPnl / totalCostBasis
 * @param holdingCount          持有數量大於 0 的持倉檔數（含換不到匯率者）
 * @param baseCurrency          金額欄位使用的基準幣代碼
 * @param fxExcludedCount       持有數量大於 0 但換不到匯率、因此未計入總額的持倉檔數（D-04）
 * @param unconvertedCurrencies 換不到匯率的幣別代碼，字母排序、去重
 * @author Yuan
 * @version 1.0
 */
public record PortfolioSummaryDto(
    BigDecimal totalMarketValue,
    BigDecimal totalCostBasis,
    BigDecimal realizedPnl,
    BigDecimal unrealizedPnl,
    BigDecimal totalPnl,
    BigDecimal roi,
    int holdingCount,
    String baseCurrency,
    int fxExcludedCount,
    List<String> unconvertedCurrencies
) {
}
