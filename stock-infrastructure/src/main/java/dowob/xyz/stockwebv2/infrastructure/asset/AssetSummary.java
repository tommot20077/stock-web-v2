package dowob.xyz.stockwebv2.infrastructure.asset;

import dowob.xyz.stockwebv2.common.model.AssetType;

/**
 * 跨 module 傳遞 asset 元資料的輕量 DTO。
 *
 * <p>消費者（如 market-data module）透過此 record 取得所需資訊，
 * 不需直接依賴 stock-module-asset 的內部 domain class {@code Asset}。
 *
 * <ul>
 *   <li>{@code id} — 跨 aggregate 的 FK，market_prices 等表以此關聯</li>
 *   <li>{@code symbol} — 資產代號，provider 以此識別標的</li>
 *   <li>{@code name} — 資產全名</li>
 *   <li>{@code assetType} — 資產類型（STOCK / CRYPTO / FX / BOND）</li>
 *   <li>{@code market} — 交易市場代號（US / TW / CRYPTO 等）</li>
 *   <li>{@code tradeable} — 是否可交易（false 表示僅參考用）</li>
 *   <li>{@code active} — 是否啟用中</li>
 * </ul>
 *
 * @param id        資產主鍵 ID
 * @param symbol    資產代號
 * @param name      資產全名
 * @param assetType 資產類型
 * @param market    交易市場代號
 * @param tradeable 是否可交易
 * @param active    是否啟用中
 * @author Yuan
 * @version 1.0.0
 */
public record AssetSummary(
        Long id,
        String symbol,
        String name,
        AssetType assetType,
        String market,
        boolean tradeable,
        boolean active
) {
}
