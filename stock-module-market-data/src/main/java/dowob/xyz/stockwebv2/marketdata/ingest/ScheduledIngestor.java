package dowob.xyz.stockwebv2.marketdata.ingest;

import dowob.xyz.stockwebv2.common.event.PriceTickEvent;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetFacade;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetSummary;
import dowob.xyz.stockwebv2.marketdata.provider.DataProvider;
import dowob.xyz.stockwebv2.marketdata.provider.PriceTick;
import dowob.xyz.stockwebv2.marketdata.provider.ProviderRegistry;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 排程 ingestor — 每秒對所有 active+tradeable asset 取 tick 並推進 Kafka。
 *
 * <p>透過 {@link ProviderRegistry} 路由不同 provider；Mock 階段一律由 MockDataProvider 服務。
 * 個別 asset 抓取失敗會 catch 並 log warn，不影響其他 asset 繼續處理。
 *
 * <p>透過 {@code market-data.ingestor.enabled=false} 可完全停用此元件（例如整合測試環境）。
 * {@code market-data.ingestor.fixed-delay-ms} 可調整輪詢間隔，預設 1000 ms。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Component
@ConditionalOnProperty(
        prefix = "market-data.ingestor",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ScheduledIngestor {

    private static final Logger log = LoggerFactory.getLogger(ScheduledIngestor.class);

    private final AssetFacade assetFacade;
    private final ProviderRegistry providerRegistry;
    private final MarketDataIngestService ingestService;

    private volatile List<AssetSummary> assetCache = List.of();
    private final AtomicLong successCount = new AtomicLong();
    private final AtomicLong failureCount = new AtomicLong();

    /**
     * 建構子注入所有依賴。
     *
     * @param assetFacade      提供 active+tradeable asset 清單
     * @param providerRegistry 依 asset 路由對應的 DataProvider
     * @param ingestService    將 tick 推入 Kafka
     */
    public ScheduledIngestor(AssetFacade assetFacade,
                             ProviderRegistry providerRegistry,
                             MarketDataIngestService ingestService) {
        this.assetFacade = assetFacade;
        this.providerRegistry = providerRegistry;
        this.ingestService = ingestService;
    }

    /**
     * 容器啟動後立即載入 asset 清單並快取。
     *
     * <p>目前採靜態快取（啟動一次後不再更新）；未來可改為定時重新整理
     * 以反映動態上下架的 asset。
     */
    @PostConstruct
    void loadAssets() {
        assetCache = assetFacade.findAllTradeable();
        log.info("ScheduledIngestor loaded {} assets", assetCache.size());
    }

    /**
     * 每秒對快取的 asset 清單執行一輪 tick 拉取並推入 Kafka。
     *
     * <p>{@code fixedDelay} 確保上一輪完成後才排下一輪,避免因外部 provider 延遲導致輪次重疊。
     *
     * <p>計數語義:
     * <ul>
     *   <li>provider fetch 拋例外或 publishTick 同步階段拋例外 → 立即 {@code failureCount++}</li>
     *   <li>publishTick 回傳 future,在 ack callback 內依結果遞增 {@code successCount} 或
     *       {@code failureCount} — 此 callback 由 Kafka producer thread 觸發,
     *       {@link AtomicLong} 已 thread-safe,直接呼叫即可</li>
     * </ul>
     */
    @Scheduled(fixedDelayString = "${market-data.ingestor.fixed-delay-ms:1000}")
    public void tickAll() {
        for (AssetSummary asset : assetCache) {
            try {
                DataProvider provider = providerRegistry.findFor(asset);
                PriceTick tick = provider.fetchLatest(asset.symbol());
                PriceTickEvent event = PriceTickEvent.of(
                        asset.id(), tick.symbol(), tick.price(), tick.volume(), provider.name());
                ingestService.publishTick(event).whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        failureCount.incrementAndGet();
                        log.warn("Kafka send failed for asset id={} symbol={}: {}",
                                asset.id(), asset.symbol(), throwable.toString());
                    } else {
                        successCount.incrementAndGet();
                    }
                });
            } catch (Exception ex) {
                failureCount.incrementAndGet();
                log.warn("Failed to fetch/publish tick for asset id={} symbol={}: {}",
                        asset.id(), asset.symbol(), ex.toString());
            }
        }
    }

    /**
     * 回傳累計成功推送的 tick 筆數。供 metrics 與測試使用。
     *
     * @return 成功推送筆數
     */
    public long successCount() {
        return successCount.get();
    }

    /**
     * 回傳累計失敗的 tick 筆數。供 metrics 與測試使用。
     *
     * @return 失敗筆數
     */
    public long failureCount() {
        return failureCount.get();
    }

    /**
     * 回傳目前快取的 asset 筆數。供測試驗證 loadAssets 行為。
     *
     * @return 快取 asset 筆數
     */
    int cachedAssetCount() {
        return assetCache.size();
    }
}
