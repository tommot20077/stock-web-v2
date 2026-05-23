package dowob.xyz.stockwebv2.infrastructure.asset;

import java.util.List;
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
}
