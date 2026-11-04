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

    /**
     * 以 symbol 解析可加入觀察清單的資產：只要求 {@code active = true}，<strong>不</strong>要求 tradeable（D-18，
     * FX 與債券皆可加入）。
     *
     * @param symbol 已 strip 的資產代號（大小寫須完全相符）
     * @return 資產內部 id；不存在或 inactive 時為 empty
     */
    Optional<Long> findActiveAssetIdBySymbol(String symbol);

    /**
     * @param watchlistId 清單 id
     * @param assetId     資產 id
     * @return 該資產已在清單中時為 true
     */
    boolean containsItem(Long watchlistId, Long assetId);

    /**
     * 計算清單目前的項目列數（含已 inactive 的資產，上限以實際儲存的列計）。
     *
     * <p>100 檔上限的計數來源：呼叫端必須先以 {@link #findByUuidForUpdate} 鎖住清單列，<strong>之後</strong>
     * 再呼叫本方法——READ COMMITTED 下每個語句各自取快照，鎖後的這個獨立查詢才看得到前一個持鎖交易剛 commit 的項目。</p>
     *
     * @param watchlistId 清單 id
     * @return 項目列數
     */
    int countItems(Long watchlistId);

    /**
     * 把資產加到清單尾端（{@code position = coalesce(max(position), 0) + 1}），
     * 以 {@code ON CONFLICT (watchlist_id, asset_id) DO NOTHING} 吸收重複。
     *
     * @param watchlistId 清單 id（呼叫端已鎖列）
     * @param assetId     資產 id
     * @return 實際新增一列時為 true；已存在時為 false
     */
    boolean insertItemAtEnd(Long watchlistId, Long assetId);

    /**
     * 以資產 UUID 解析內部 id（不限 active：移除項目時資產可能已下架）。
     *
     * @param assetUuid 資產 UUID（{@code AssetDto.uuid}）
     * @return 資產內部 id；不存在時為 empty
     */
    Optional<Long> findAssetIdByUuid(UUID assetUuid);

    /**
     * 自清單移除一檔；不在清單中時不做任何事。
     *
     * @param watchlistId 清單 id（呼叫端已鎖列）
     * @param assetId     資產 id
     */
    void deleteItem(Long watchlistId, Long assetId);

    /**
     * 清單目前<strong>可見</strong>（active 資產）的項目，依 position 排序。重排以此集合比對前端送來的集合——
     * 與 {@link #itemsByWatchlist} 回給前端的集合一致，否則含下架資產的清單永遠無法重排。
     *
     * @param watchlistId 清單 id
     * @return 資產 UUID → 資產內部 id（保持 position 順序）
     */
    Map<UUID, Long> itemAssetIdsByUuid(Long watchlistId);

    /**
     * 依給定順序把 position 重寫為 1..n（逐列 UPDATE，n ≤ 100，於呼叫端的同一交易內）。
     *
     * @param watchlistId      清單 id（呼叫端已鎖列並確認集合相符）
     * @param assetIdsInOrder  新順序的資產 id
     */
    void rewritePositions(Long watchlistId, List<Long> assetIdsInOrder);
}
