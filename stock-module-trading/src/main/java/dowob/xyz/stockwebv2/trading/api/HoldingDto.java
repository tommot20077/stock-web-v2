package dowob.xyz.stockwebv2.trading.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 持倉與估值。
 *
 * <p>金額欄位（{@code avgCost}、{@code costBasis}、{@code marketPrice}、{@code marketValue}、
 * {@code realizedPnl}、{@code unrealizedPnl}）一律為<strong>原幣</strong>（{@code currency}）。
 * {@code baseCurrency} 與 {@code marketValueInBase} 是回應時才依使用者基準幣補上的折算值，
 * <strong>不寫入持倉快取</strong>（快取只存原幣，基準幣為 null），避免基準幣變更後讀到舊單位。
 *
 * @param assetId           資產 UUID
 * @param symbol            標的代號
 * @param assetName         標的名稱
 * @param totalQuantity     持有數量
 * @param avgCost           平均成本（原幣）
 * @param costBasis         持倉成本（原幣）
 * @param marketPrice       市價（原幣）
 * @param marketValue       市值（原幣）
 * @param realizedPnl       已實現損益（原幣）
 * @param unrealizedPnl     未實現損益（原幣）
 * @param roi               報酬率
 * @param priceTime         市價時間
 * @param lastUpdated       持倉最後更新時間
 * @param currency          標的計價幣別代碼；查無資產屬性時為 null
 * @param assetType         資產類型（STOCK / CRYPTO / FX / BOND）；查無資產屬性時為 null
 * @param sector            產業分類；可為 null，由前端顯示「未分類」
 * @param baseCurrency      使用者基準幣代碼；快取中的原幣 DTO 為 null
 * @param marketValueInBase 折算成基準幣的市值；換不到匯率時為 null
 * @param previousClose     前收（原幣）；本交易日前沒有成交或查無行情時為 null
 * @param dayPricePnl       單檔今日價格損益（原幣）；只含價格損益、不含匯差、不扣手續費（D-15）；
 *                          計算所需價格缺失時為 null（D-17、D-26）
 * @param dayPricePnlRatio  今日價格損益比率 = dayPricePnl / dayPnlBasis；分母為零或損益不可得時為 null
 * @param dayPnlBasis       今日損益比率的分母（原幣）= 前收時市值 + 今日買入成本；供 summary 折算後計算總額比率，
 *                          隨持倉一起快取，所以快取命中的持倉也能參與總額
 * @author Yuan
 * @version 3.0
 */
public record HoldingDto(
    String assetId,
    String symbol,
    String assetName,
    BigDecimal totalQuantity,
    BigDecimal avgCost,
    BigDecimal costBasis,
    BigDecimal marketPrice,
    BigDecimal marketValue,
    BigDecimal realizedPnl,
    BigDecimal unrealizedPnl,
    BigDecimal roi,
    OffsetDateTime priceTime,
    OffsetDateTime lastUpdated,
    String currency,
    String assetType,
    String sector,
    String baseCurrency,
    BigDecimal marketValueInBase,
    BigDecimal previousClose,
    BigDecimal dayPricePnl,
    BigDecimal dayPricePnlRatio,
    BigDecimal dayPnlBasis
) {

    /**
     * 回傳補上基準幣折算值的新 record，其餘欄位不變。
     *
     * @param baseCurrency 使用者基準幣代碼
     * @param valueInBase 折算成基準幣的市值；換不到匯率時為 null
     * @return 新的 HoldingDto
     */
    public HoldingDto withBaseValuation(String baseCurrency, BigDecimal valueInBase) {
        return new HoldingDto(assetId, symbol, assetName, totalQuantity, avgCost, costBasis, marketPrice, marketValue,
            realizedPnl, unrealizedPnl, roi, priceTime, lastUpdated, currency, assetType, sector,
            baseCurrency, valueInBase, previousClose, dayPricePnl, dayPricePnlRatio, dayPnlBasis);
    }
}
