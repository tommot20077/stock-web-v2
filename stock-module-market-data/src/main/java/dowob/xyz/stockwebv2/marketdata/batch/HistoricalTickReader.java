package dowob.xyz.stockwebv2.marketdata.batch;

import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.model.KlineInterval;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetFacade;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetSummary;
import dowob.xyz.stockwebv2.marketdata.provider.DataProvider;
import dowob.xyz.stockwebv2.marketdata.provider.PriceTick;
import dowob.xyz.stockwebv2.marketdata.provider.ProviderRegistry;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Iterator;
import java.util.List;

/**
 * Spring Batch ItemReader — 從 DataProvider.fetchHistorical 取 ticks 並逐筆吐出。
 *
 * <p>Mock 階段 fetchHistorical 回傳完整 list（例如 1 天 1m = 1440 筆），整批載入記憶體。
 * 未來真實 provider 應加入 streaming 變體，避免 OOM。
 *
 * <p>Reader 為 {@code @StepScope}，每次 Job 執行時從 Job Parameters 取得：
 * {@code symbol}、{@code from}、{@code to}、{@code interval}。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Component
@StepScope
public class HistoricalTickReader implements ItemReader<PriceTick> {

    private final ProviderRegistry providerRegistry;
    private final AssetFacade assetFacade;
    private final String symbol;
    private final Instant from;
    private final Instant to;
    private final KlineInterval interval;

    /** 初始化後才設定；{@code null} 表示尚未初始化。 */
    private Iterator<PriceTick> iterator;

    /** DataProvider 解析出的 asset 元資料；初始化後才設定。 */
    private AssetSummary asset;

    /**
     * 建構子注入 Provider Registry、AssetFacade 與 Job Parameters。
     *
     * @param providerRegistry provider 路由器，不可為 null
     * @param assetFacade      asset 元資料 facade，不可為 null
     * @param symbol           Job Parameter {@code symbol}，目標資產代號
     * @param from             Job Parameter {@code from}，ISO-8601 字串，範圍下界（含）
     * @param to               Job Parameter {@code to}，ISO-8601 字串，範圍上界（不含）
     * @param interval         Job Parameter {@code interval}，K 線粒度代號（如 {@code "1m"}）
     */
    public HistoricalTickReader(ProviderRegistry providerRegistry,
                                AssetFacade assetFacade,
                                @Value("#{jobParameters['symbol']}") String symbol,
                                @Value("#{jobParameters['from']}") String from,
                                @Value("#{jobParameters['to']}") String to,
                                @Value("#{jobParameters['interval']}") String interval) {
        this.providerRegistry = providerRegistry;
        this.assetFacade = assetFacade;
        this.symbol = symbol;
        this.from = Instant.parse(from);
        this.to = Instant.parse(to);
        this.interval = KlineInterval.fromCode(interval);
    }

    /**
     * 逐筆回傳 tick；第一次呼叫時才初始化（Lazy initialization）。
     *
     * <p>呼叫 {@link DataProvider#fetchHistorical} 取得完整清單後，
     * 以 iterator 逐筆回傳；清單耗盡後回傳 {@code null} 通知 Spring Batch 結束 Reader。
     *
     * @return 下一筆 {@link PriceTick}，或 {@code null} 表示已讀完
     * @throws BusinessException 若找不到指定 symbol 的 asset
     */
    @Override
    public PriceTick read() {
        if (iterator == null) {
            asset = assetFacade.findBySymbol(symbol)
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.ASSET_NOT_FOUND, "Asset not found: " + symbol));
            DataProvider provider = providerRegistry.findFor(asset);
            List<PriceTick> ticks = provider.fetchHistorical(symbol, from, to, interval);
            iterator = ticks.iterator();
        }
        return iterator.hasNext() ? iterator.next() : null;
    }

    /**
     * 回傳已解析的 {@link AssetSummary}（供測試驗證，package-private）。
     *
     * @return 解析後的 asset，初始化前為 {@code null}
     */
    AssetSummary asset() {
        return asset;
    }
}
