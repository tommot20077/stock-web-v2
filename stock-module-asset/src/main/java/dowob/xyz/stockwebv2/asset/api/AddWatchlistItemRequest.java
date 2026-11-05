package dowob.xyz.stockwebv2.asset.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 加入觀察清單項目的請求（{@code POST /api/v1/watchlists/{id}/items}）。
 *
 * <p>以 body 指定 symbol 而非放進路徑：FX 代號含「/」（{@code USD/TWD}）。可加入任何 active 資產，
 * 不要求 tradeable（D-18）；資產是否存在由 service 判定（404 {@code ASSET_NOT_FOUND}，{@code fields.symbol}）。</p>
 *
 * @param symbol 資產代號，不得空白、最多 32 字（與交易類請求的 symbol 上限一致）
 * @author Yuan
 * @version 1.0
 */
public record AddWatchlistItemRequest(@NotBlank @Size(max = 32) String symbol) {
}
