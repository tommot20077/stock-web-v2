package dowob.xyz.stockwebv2.asset.service;

import dowob.xyz.stockwebv2.asset.api.AssetDto;
import dowob.xyz.stockwebv2.asset.domain.Asset;
import dowob.xyz.stockwebv2.asset.repository.AssetRepository;
import dowob.xyz.stockwebv2.common.api.PageResponse;
import dowob.xyz.stockwebv2.common.model.TradingDay;
import dowob.xyz.stockwebv2.infrastructure.marketdata.DailyQuote;
import dowob.xyz.stockwebv2.infrastructure.marketdata.MarketDataFacade;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 資產搜尋;報價欄位由 market-data 依各資產市場的交易日提供,映射委派 {@link AssetQuoteMapper}(與觀察清單共用)。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Service
public class AssetQueryService {

    private final AssetRepository assetRepository;
    private final MarketDataFacade marketDataFacade;

    /**
     * @param assetRepository  資產儲存庫
     * @param marketDataFacade 行情 facade,提供最新價與交易日統計
     */
    public AssetQueryService(AssetRepository assetRepository, MarketDataFacade marketDataFacade) {
        this.assetRepository = assetRepository;
        this.marketDataFacade = marketDataFacade;
    }

    /**
     * @param query 代號或名稱關鍵字
     * @param page  頁碼
     * @param size  每頁筆數
     * @return 分頁結果;沒有行情的資產報價欄位為 null
     */
    public PageResponse<AssetDto> search(String query, int page, int size) {
        List<Asset> assets = assetRepository.search(query, page, size);
        Map<Long, TradingDay> tradingDays = new LinkedHashMap<>();
        assets.forEach(asset -> tradingDays.put(asset.id(), TradingDay.of(asset.assetType(), asset.market())));
        Map<Long, DailyQuote> quotes = tradingDays.isEmpty() ? Map.of() : marketDataFacade.findDailyQuotes(tradingDays);

        var items = assets.stream().map(asset -> AssetQuoteMapper.toDto(asset, quotes.get(asset.id()))).toList();
        long total = assetRepository.count(query);
        return PageResponse.of(items, page, size, total);
    }
}
