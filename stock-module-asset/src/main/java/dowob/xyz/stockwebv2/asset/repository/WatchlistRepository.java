package dowob.xyz.stockwebv2.asset.repository;

import dowob.xyz.stockwebv2.asset.domain.Asset;
import dowob.xyz.stockwebv2.asset.domain.Watchlist;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 觀察清單儲存庫。所有查詢都綁 {@code user_id}：清單只屬於建立者，以他人 uuid 查詢一律查無（不洩漏存在性）。
 *
 * @author Yuan
 * @version 1.0
 */
public interface WatchlistRepository {

    /**
     * 使用者 0 個清單時建立預設清單「我的自選」；已有任何清單則不動。
     * 以 {@code ON CONFLICT (user_id, name) DO NOTHING} 吸收並發的首次呼叫，只會留下一列。
     *
     * @param userId 擁有者
     */
    void ensureDefault(Long userId);

    /**
     * @param userId 擁有者
     * @return 該使用者全部清單，依 created_at, id 排序
     */
    List<Watchlist> listByUser(Long userId);

    /**
     * @param userId 擁有者
     * @param uuid   清單 UUID
     * @return 屬於該使用者的清單；不存在或屬於他人時為 empty
     */
    Optional<Watchlist> findByUuid(Long userId, UUID uuid);

    /**
     * @param watchlistIds 清單 id
     * @return 清單 id → 依 position 排序的 active 資產；沒有項目的清單不出現在 map 中
     */
    Map<Long, List<Asset>> itemsByWatchlist(List<Long> watchlistIds);
}
