package dowob.xyz.stockwebv2.asset.service;

import dowob.xyz.stockwebv2.asset.api.AssetDto;
import dowob.xyz.stockwebv2.asset.domain.Asset;
import dowob.xyz.stockwebv2.asset.repository.AssetRepository;
import dowob.xyz.stockwebv2.common.api.PageResponse;
import dowob.xyz.stockwebv2.common.model.TradingDay;
import dowob.xyz.stockwebv2.infrastructure.marketdata.DailyQuote;
import dowob.xyz.stockwebv2.infrastructure.marketdata.MarketDataFacade;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 資產搜尋;報價欄位由 market-data 依各資產市場的交易日提供。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Service
public class AssetQueryService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);
    private static final String[] VOLUME_SUFFIXES = {"K", "M", "B", "T"};

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

        var items = assets.stream().map(asset -> toDto(asset, quotes.get(asset.id()))).toList();
        long total = assetRepository.count(query);
        return PageResponse.of(items, page, size, total);
    }

    private AssetDto toDto(Asset asset, DailyQuote quote) {
        BigDecimal change = null;
        BigDecimal changePercent = null;
        if (quote != null && quote.previousClose() != null && quote.previousClose().signum() != 0) {
            change = quote.latestPrice().subtract(quote.previousClose());
            changePercent = change.multiply(HUNDRED).divide(quote.previousClose(), 2, RoundingMode.HALF_UP);
        }
        return new AssetDto(
            asset.uuid(),
            asset.symbol(),
            asset.name(),
            asset.assetType(),
            asset.market(),
            asset.currency(),
            asset.sector(),
            asset.tradeable(),
            quote == null ? null : quote.latestPrice(),
            change,
            changePercent,
            quote == null ? null : volumeText(quote.volume()),
            quote == null ? null : quote.high(),
            quote == null ? null : quote.low()
        );
    }

    /**
     * 成交量縮寫:千以上取一位小數並加 K / M / B / T;零或 null 回 null(前端顯示「—」)。
     *
     * @param volume 成交量
     * @return 縮寫字串
     */
    static String volumeText(BigDecimal volume) {
        if (volume == null || volume.signum() == 0) {
            return null;
        }
        BigDecimal scaled = volume.abs();
        int unit = -1;
        while (scaled.compareTo(THOUSAND) >= 0 && unit < VOLUME_SUFFIXES.length - 1) {
            scaled = scaled.divide(THOUSAND);
            unit++;
        }
        if (unit < 0) {
            return volume.stripTrailingZeros().toPlainString();
        }
        return scaled.setScale(1, RoundingMode.HALF_UP).toPlainString() + VOLUME_SUFFIXES[unit];
    }
}
