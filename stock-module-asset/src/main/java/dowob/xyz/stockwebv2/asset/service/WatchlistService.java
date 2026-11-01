package dowob.xyz.stockwebv2.asset.service;

import dowob.xyz.stockwebv2.asset.api.WatchlistDto;
import dowob.xyz.stockwebv2.asset.domain.Asset;
import dowob.xyz.stockwebv2.asset.domain.Watchlist;
import dowob.xyz.stockwebv2.asset.repository.WatchlistRepository;
import dowob.xyz.stockwebv2.common.model.TradingDay;
import dowob.xyz.stockwebv2.infrastructure.marketdata.DailyQuote;
import dowob.xyz.stockwebv2.infrastructure.marketdata.MarketDataFacade;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 觀察清單（WATCH-01；D-14、D-17、D-19）。契約見 {@code ai-docs/watchlist-contract.md}。
 *
 * <p>回傳的每檔項目都經 {@link AssetQuoteMapper}——與 {@code GET /api/v1/assets} 同一段程式計算漲跌與量縮寫；
 * 每次回應只呼叫一次 {@link MarketDataFacade#findDailyQuotes}（以 distinct 資產批次）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Service
public class WatchlistService {

    /** 預設清單名稱（D-17）。 */
    public static final String DEFAULT_NAME = Watchlist.DEFAULT_NAME;

    private final WatchlistRepository watchlistRepository;
    private final MarketDataFacade marketDataFacade;

    /**
     * @param watchlistRepository 觀察清單儲存庫
     * @param marketDataFacade    行情 facade，提供各資產市場交易日的報價統計
     */
    public WatchlistService(WatchlistRepository watchlistRepository, MarketDataFacade marketDataFacade) {
        this.watchlistRepository = watchlistRepository;
        this.marketDataFacade = marketDataFacade;
    }

    /**
     * 列出使用者全部清單與每檔報價；使用者 0 個清單時先建立「我的自選」（刪光後下一次呼叫會重建）。
     *
     * @param userId 擁有者，一律由 controller 自已驗證的身分取得
     * @return 依 created_at, id 排序的清單；項目依 position 排序
     */
    @Transactional
    public List<WatchlistDto> list(Long userId) {
        watchlistRepository.ensureDefault(userId);
        return toDtos(watchlistRepository.listByUser(userId));
    }

    private List<WatchlistDto> toDtos(List<Watchlist> watchlists) {
        if (watchlists.isEmpty()) {
            return List.of();
        }
        Map<Long, List<Asset>> items = watchlistRepository.itemsByWatchlist(
            watchlists.stream().map(Watchlist::id).toList());
        Map<Long, TradingDay> tradingDays = new LinkedHashMap<>();
        items.values().forEach(assets -> assets.forEach(asset ->
            tradingDays.putIfAbsent(asset.id(), TradingDay.of(asset.assetType(), asset.market()))));
        Map<Long, DailyQuote> quotes = tradingDays.isEmpty() ? Map.of() : marketDataFacade.findDailyQuotes(tradingDays);

        return watchlists.stream()
            .map(watchlist -> new WatchlistDto(
                watchlist.uuid(),
                watchlist.name(),
                items.getOrDefault(watchlist.id(), List.of()).stream()
                    .map(asset -> AssetQuoteMapper.toDto(asset, quotes.get(asset.id())))
                    .toList()))
            .toList();
    }
}
