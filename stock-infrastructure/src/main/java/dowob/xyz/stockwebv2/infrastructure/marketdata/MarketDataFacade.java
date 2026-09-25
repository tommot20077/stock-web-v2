package dowob.xyz.stockwebv2.infrastructure.marketdata;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * market-data 模組對其他模組公開的同步查詢介面。
 *
 * <p>存在的理由是一個實際發生過的缺陷：trading 模組原本以自己的 SQL 直接查
 * {@code asset_latest_prices} 來為持倉估值，而那張表<strong>只有 V2 seed 寫過一次</strong>，
 * 全 repo 沒有任何程式更新它。真正的即時價格走 Kafka → Redis {@code market:latest:{assetId}}
 * 與 {@code market_prices} hypertable，兩者都在 market-data 模組手上。結果是持倉市值永遠停在
 * 種子價，使用者的損益不會隨行情變動。
 *
 * <p>同時這也修掉一條分層違規：跨模組取資料一律走 Facade（{@code ai-docs/architecture.md}），
 * 不得由 A 模組的 SQL 直接 join/select B 模組的表。
 *
 * @author Yuan
 * @version 1.0.0
 */
public interface MarketDataFacade {

    /**
     * 取得單一資產的最新成交價。
     *
     * <p>來源順序：Redis latest cache → {@code market_prices} 最新一列。兩者皆無（例如標的從未有過
     * tick、或 backfill 尚未執行）時回傳 {@link Optional#empty()}，由呼叫端決定如何退場——
     * <strong>不要在這裡塞任何預設價</strong>，那會讓「沒有行情」與「行情等於某個數字」無法區分。
     *
     * @param assetId 資產 id，不可為 null
     * @return 最新價與其時間戳；查無資料時 {@link Optional#empty()}
     */
    Optional<LatestMarketPrice> findLatestPrice(Long assetId);

    /**
     * 批次取得多個資產的最新成交價。
     *
     * <p>語意與 {@link #findLatestPrice(Long)} 相同，但以固定次數的往返完成（一次快取批次讀取，未命中者一次查詢），
     * 供需要同時估值多筆持倉的呼叫端使用，避免逐筆查詢的 N+1。
     *
     * @param assetIds 資產 id 集合，不可為 null；可為空
     * @return 資產 id → 最新價；查無行情的資產<strong>不出現在結果中</strong>（不塞預設價）
     */
    Map<Long, LatestMarketPrice> findLatestPrices(Collection<Long> assetIds);
}
