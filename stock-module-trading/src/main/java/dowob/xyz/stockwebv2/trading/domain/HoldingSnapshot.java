package dowob.xyz.stockwebv2.trading.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 持倉快照：{@code holdings} 的單列純資料，<strong>含已平倉</strong>（{@code total_quantity = 0}）。
 *
 * <p>不含標的代號與名稱（不 join {@code assets}，D-21 / H-1）。標的的幣別等屬性由
 * {@code AssetFacade#findProfiles} 以 {@code assetId} 批次取得；本紀錄主要供 portfolio summary
 * 逐資產折算已實現損益，避免平倉後遺失已實現損益。
 *
 * @param assetId       標的 id
 * @param totalQuantity 目前持有數量（已平倉為 0）
 * @param avgCost       平均成本（原幣）
 * @param realizedPnl   累計已實現損益（原幣）
 * @param lastUpdated   最後更新時間
 * @author Yuan
 * @version 1.0
 */
public record HoldingSnapshot(
    Long assetId,
    BigDecimal totalQuantity,
    BigDecimal avgCost,
    BigDecimal realizedPnl,
    OffsetDateTime lastUpdated
) {
}
