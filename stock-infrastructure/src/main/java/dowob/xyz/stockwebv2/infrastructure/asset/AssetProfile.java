package dowob.xyz.stockwebv2.infrastructure.asset;

import dowob.xyz.stockwebv2.common.model.AssetType;
import dowob.xyz.stockwebv2.common.model.CurrencyCode;

/**
 * 跨 module 傳遞的資產屬性 DTO，供 trading 等模組取得幣別 / 類型 / 產業而不 SQL JOIN {@code assets}。
 *
 * <p>與 {@link AssetSummary} 並存而不擴充它：{@code AssetSummary} 有大量無關呼叫端（market-data 測試等），
 * 新增欄位會造成不必要的連帶修改。
 *
 * @param id        資產主鍵 ID
 * @param symbol    資產代號
 * @param assetType 資產類型（STOCK / CRYPTO / FX / BOND）
 * @param market    交易市場代號（US / TW / CRYPTO 等）
 * @param currency  計價幣別
 * @param sector    產業分類；可為 null（如 FX），顯示層歸「未分類」（D-19）
 * @author Yuan
 * @version 1.0.0
 */
public record AssetProfile(
        Long id,
        String symbol,
        AssetType assetType,
        String market,
        CurrencyCode currency,
        String sector
) {
}
