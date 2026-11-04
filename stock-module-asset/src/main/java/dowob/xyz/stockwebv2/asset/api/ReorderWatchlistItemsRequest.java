package dowob.xyz.stockwebv2.asset.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * 整份重排觀察清單項目的請求（{@code PUT /api/v1/watchlists/{id}/items/order}）。
 *
 * <p>{@code assetIds} 須為清單目前全部項目的 {@code AssetDto.uuid} 依新順序排列；集合不符由 service 判為
 * 409 {@code WATCHLIST_ORDER_CONFLICT}。{@code @Size(max = 100)} 與每清單 100 檔上限一致，擋下超長陣列
 * （T-04.2-29）。非 UUID 字串由 JSON 解析失敗回 400。</p>
 *
 * @param assetIds 新順序的資產 UUID
 * @author Yuan
 * @version 1.0
 */
public record ReorderWatchlistItemsRequest(@NotNull @Size(max = 100) List<UUID> assetIds) {
}
