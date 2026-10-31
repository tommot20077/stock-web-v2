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
 * @param dayPricePnl           今日價格損益總額（基準幣，以<strong>目前匯率</strong>折算，D-15）；只含價格損益，
 *                              不含匯差、不扣手續費。涵蓋持有中的部位與今天賣光的部位。
 *                              <strong>null = 缺資料</strong>（有候選但全部被排除），
 *                              <strong>0 = 真實的零</strong>（沒有任何候選：無持倉且今日無成交）
 * @param dayPricePnlRatio      今日價格損益比率 = Σ損益 / Σ前收時市值與買入成本（皆基準幣）；總額為 null 或分母為零時為 null
 * @param dayPnlExcludedCount   因計算所需價格缺失（D-17）而未計入今日損益的檔數；換不到匯率者改計入
 *                              {@code fxExcludedCount} / {@code unconvertedCurrencies}（D-04），不在此數內
 * @param cash                  可用現金合計（基準幣，D-22）：各幣別現金餘額以<strong>目前匯率</strong>折算後加總，
 *                              換不到匯率的現金幣別不計入並列入 {@code unconvertedCurrencies}（D-04）；
 *                              可為負（只買不入金，D-08）。不含在 {@code roi} 內
 * @param totalEquity           總資產 = {@code totalMarketValue + cash}（基準幣）
 * @param cashBalances          各幣別的原幣現金餘額，依 {@code CurrencyCode} 宣告順序（USD、TWD、EUR、JPY），
 *                              只含有過入出金或交易的幣別；沒有任何紀錄時為空 list（不是 null）
 * @param allocation            類別配置（D-18）：{@code asset_type} 分塊加上正現金 {@code CASH}，基準幣計值，
 *                              依金額降冪（同值依類別字母升冪）；沒有持倉的類別不出現，現金 &lt;= 0 時不列 {@code CASH}；
 *                              分母 = 可折算持倉市值 + 正現金，分母為 0 時為空 list（不是 null）
 * @param sectorAllocation      產業配置（D-19）：以 {@code assets.sector} 原值分組，依金額降冪（同值依產業字母升冪），
 *                              {@code sector = null}（未分類）一律排最後；分母 = 可折算持倉市值（不含現金），
 *                              分母為 0 時為空 list（不是 null）
 * @author Yuan
 * @version 4.0
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
    List<String> unconvertedCurrencies,
    BigDecimal dayPricePnl,
    BigDecimal dayPricePnlRatio,
    int dayPnlExcludedCount,
    BigDecimal cash,
    BigDecimal totalEquity,
    List<CashBalanceDto> cashBalances,
    List<AllocationSliceDto> allocation,
    List<SectorAllocationDto> sectorAllocation
) {
}
