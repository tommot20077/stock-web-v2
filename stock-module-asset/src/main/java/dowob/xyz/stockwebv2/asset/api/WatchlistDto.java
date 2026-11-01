package dowob.xyz.stockwebv2.asset.api;

import java.util.List;
import java.util.UUID;

/**
 * 觀察清單回應（契約見 {@code ai-docs/watchlist-contract.md}）。
 *
 * @param id    清單 UUID
 * @param name  清單名稱（trim 後）
 * @param items 依 position 排序的項目；與 {@code GET /api/v1/assets} 的 {@link AssetDto} 逐欄同形，無行情的報價欄位為 null
 * @author Yuan
 * @version 1.0
 */
public record WatchlistDto(UUID id, String name, List<AssetDto> items) {
}
