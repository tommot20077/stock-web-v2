package dowob.xyz.stockwebv2.marketdata.api;

import dowob.xyz.stockwebv2.common.model.TradingDay;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetFacade;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetSummary;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 以 symbol 查出該資產的 {@link TradingDay}。
 *
 * <p>每筆 tick 都會問一次,所以結果快取在記憶體;資產的市場別幾乎不會變,重啟即重新載入。
 * 查不到的 symbol 回 {@link TradingDay#UTC} 但<strong>不快取</strong>,讓之後新增的資產能被正確解析。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Component
public class TradingDayResolver {

    private final AssetFacade assetFacade;
    private final ConcurrentMap<String, TradingDay> cache = new ConcurrentHashMap<>();

    /**
     * @param assetFacade 資產 facade,不可為 null
     */
    public TradingDayResolver(AssetFacade assetFacade) {
        this.assetFacade = Objects.requireNonNull(assetFacade, "assetFacade must not be null");
    }

    /**
     * @param symbol 資產代號,不可為 null
     * @return 該資產市場的交易日規則;資產不存在時為 {@link TradingDay#UTC}
     */
    public TradingDay forSymbol(String symbol) {
        Objects.requireNonNull(symbol, "symbol must not be null");
        TradingDay cached = cache.get(symbol);
        if (cached != null) {
            return cached;
        }
        Optional<AssetSummary> asset = assetFacade.findBySymbol(symbol);
        if (asset.isEmpty()) {
            return TradingDay.UTC;
        }
        return cache.computeIfAbsent(symbol, s -> of(asset.get()));
    }

    /**
     * @param asset 資產摘要
     * @return 該資產的交易日規則
     */
    public static TradingDay of(AssetSummary asset) {
        return TradingDay.of(asset.assetType(), asset.market());
    }
}
