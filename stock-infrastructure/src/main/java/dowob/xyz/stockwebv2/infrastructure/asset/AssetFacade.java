package dowob.xyz.stockwebv2.infrastructure.asset;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 提供其他 module 查詢 asset 元資料的 facade。
 *
 * <p>唯一的跨 module 進入點 — 消費者不可直接依賴 stock-module-asset 內部 service/repository。
 * 實作類別 {@code AssetFacadeImpl} 位於 stock-module-asset，由 Spring 容器注入。
 *
 * @author Yuan
 * @version 1.0.0
 */
public interface AssetFacade {

    /**
     * 取得所有 active + tradeable 的 asset summary。
     *
     * <p>主要供 MockDataProvider 啟動時載入支援的 symbol 清單，以及 ScheduledIngestor
     * 每秒掃描需要拉 tick 的 asset 列表。結果按 symbol 升冪排序。
     *
     * @return asset summary list，按 symbol 升冪排序；若無資料回空 list
     */
    List<AssetSummary> findAllTradeable();

    /**
     * 依 symbol 查詢單一 asset 元資料。Symbol 大小寫敏感。
     *
     * @param symbol 資產代號，不可為 null
     * @return Optional 包含對應 AssetSummary；找不到回 Optional.empty()
     * @throws NullPointerException 若 symbol 為 null
     */
    Optional<AssetSummary> findBySymbol(String symbol);

    /**
     * 批次查詢多個資產的屬性（類型 / 市場 / 幣別 / 產業）。
     *
     * <p>以單一查詢完成；空集合直接回空 map 且不查 DB；查無的 id 不會出現在結果中；
     * 不篩選 {@code active}，因已下架的資產仍可能是使用者的歷史持倉，需要其幣別。
     * 供 trading 取得資產屬性以免 SQL JOIN {@code assets}（D-21、架構審查 H-1）。
     *
     * @param assetIds 資產主鍵集合，不可為 null
     * @return 以資產 ID 為 key 的 profile map；查無者省略
     */
    Map<Long, AssetProfile> findProfiles(Collection<Long> assetIds);
}
