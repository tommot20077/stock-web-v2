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

    /**
     * 同 {@link #findByUuid}，但以 {@code FOR UPDATE} 鎖住該列直到交易結束（改名 / 刪除與並發操作序列化）。
     *
     * @param userId 擁有者
     * @param uuid   清單 UUID
     * @return 屬於該使用者的清單；不存在或屬於他人時為 empty
     */
    Optional<Watchlist> findByUuidForUpdate(Long userId, UUID uuid);

    /**
     * 以 {@code SELECT … FOR UPDATE} 鎖住該使用者全部清單列，讓並發的建立序列化（20 上限的硬保證）。
     *
     * <p>呼叫端必須在取得鎖<strong>之後</strong>以 {@link #countByUser} 另起一個查詢計數：READ COMMITTED 下
     * 本查詢的快照在等鎖之前就已取得，看不到前一個持鎖交易剛 commit 的新列。</p>
     *
     * @param userId 擁有者
     * @return 被鎖住的清單 id
     */
    List<Long> lockAllForUser(Long userId);

    /**
     * @param userId 擁有者
     * @return 該使用者目前的清單數
     */
    int countByUser(Long userId);

    /**
     * 以 {@code ON CONFLICT (user_id, name) DO NOTHING RETURNING} 建立清單。
     *
     * @param userId 擁有者
     * @param name   已 trim 並通過長度檢查的名稱
     * @return 新建的清單；同使用者已有同名清單時為 empty
     */
    Optional<Watchlist> insertIfNameFree(Long userId, String name);

    /**
     * @param id     清單 id（呼叫端已以 {@link #findByUuidForUpdate} 確認擁有權並鎖列）
     * @param userId 擁有者
     * @param name   已 trim 並通過長度檢查的新名稱
     * @return 已更新為 true；同使用者另一份清單已用此名稱時為 false
     */
    boolean rename(Long id, Long userId, String name);

    /**
     * 刪除清單；項目由 {@code ON DELETE CASCADE} 一併刪除。
     *
     * @param id     清單 id
     * @param userId 擁有者
     */
    void delete(Long id, Long userId);
}
