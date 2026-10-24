package dowob.xyz.stockwebv2.trading.service;

import dowob.xyz.stockwebv2.infrastructure.marketdata.LatestMarketPrice;
import dowob.xyz.stockwebv2.infrastructure.marketdata.MarketDataFacade;
import dowob.xyz.stockwebv2.trading.api.HoldingDto;
import dowob.xyz.stockwebv2.trading.api.PortfolioSummaryDto;
import dowob.xyz.stockwebv2.trading.domain.HoldingPosition;
import dowob.xyz.stockwebv2.trading.repository.TradingRepository;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * 投資組合的讀時計算：持倉估值與組合摘要。
 *
 * <p>本類別自 {@link TradingService} 原樣抽出，讀取路徑（持倉快取 MGET、對未命中者批次取價、
 * 摘要快取）與交易寫入路徑（冪等鍵、持倉改寫、{@code afterCommit} 快取失效）分屬不同類別，
 * 讓兩條線往後各自演進而不改同一個檔案。寫入端仍由 {@link TradingService#createTrade} 在交易提交後
 * 透過 {@link PortfolioCache#invalidateAfterTrade} 使快取失效，本類別只負責讀取與回填。</p>
 *
 * <p>D-21 / H-1 的原則照舊：持倉與摘要<strong>不 join {@code assets}</strong>，
 * 標的資訊由持倉位置（{@link HoldingPosition}）帶入，最新價一律經 {@link MarketDataFacade} 取得。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Service
public class PortfolioQueryService {
    private static final int MONEY_SCALE = 8;

    private final TradingRepository repository;
    private final MarketDataFacade marketDataFacade;
    private final PortfolioCache portfolioCache;

    /**
     * 建立讀時計算服務。
     *
     * @param repository       交易與持倉資料存取
     * @param marketDataFacade 跨模組最新價查詢
     * @param portfolioCache   持倉與摘要的 Redis 快取
     */
    public PortfolioQueryService(
        TradingRepository repository,
        MarketDataFacade marketDataFacade,
        PortfolioCache portfolioCache
    ) {
        this.repository = repository;
        this.marketDataFacade = marketDataFacade;
        this.portfolioCache = portfolioCache;
    }

    /**
     * 列出使用者的持倉與估值。
     *
     * <p>以固定次數的往返完成：一次批次讀持倉快取、對未命中者一次批次取價，再逐筆寫回快取。
     * 原本每筆持倉各自讀快取、取價、寫快取，N 筆約 3N 次往返，成交後三個頁面同時 refetch 還會放大
     * （性能審查 MED-5）。回傳順序與 {@code repository.listHoldings} 相同。
     *
     * @param userId 使用者 id
     * @return 持倉 DTO 列表
     */
    public List<HoldingDto> listHoldings(Long userId) {
        List<HoldingPosition> positions = repository.listHoldings(userId);
        if (positions.isEmpty()) {
            return List.of();
        }
        List<Long> assetIds = positions.stream().map(HoldingPosition::assetId).toList();
        Map<Long, HoldingDto> cached = portfolioCache.readHoldings(userId, assetIds);
        List<Long> misses = assetIds.stream().filter(id -> !cached.containsKey(id)).toList();
        Map<Long, LatestMarketPrice> prices = misses.isEmpty()
            ? Map.of()
            : marketDataFacade.findLatestPrices(misses);
        return positions.stream()
            .map(position -> {
                HoldingDto hit = cached.get(position.assetId());
                return hit != null ? hit : calculateAndCacheHolding(position, prices.get(position.assetId()));
            })
            .toList();
    }

    /**
     * 取得使用者的投資組合摘要；優先讀摘要快取，未命中才由持倉估值與已實現損益重算並回填。
     *
     * @param userId 使用者 id
     * @return 投資組合摘要 DTO
     */
    public PortfolioSummaryDto summary(Long userId) {
        return portfolioCache.readSummary(userId).orElseGet(() -> {
            List<HoldingDto> holdings = listHoldings(userId);
            BigDecimal marketValue = sum(holdings.stream().map(HoldingDto::marketValue).toList());
            BigDecimal costBasis = sum(holdings.stream().map(HoldingDto::costBasis).toList());
            // realized PnL 需涵蓋已平倉部位（total_quantity = 0，不在 listHoldings 內），故直接由
            // repository 加總所有持倉的 realized_pnl，避免平倉後遺失已實現損益。
            BigDecimal realizedPnl = repository.sumRealizedPnl(userId);
            BigDecimal unrealizedPnl = sum(holdings.stream().map(HoldingDto::unrealizedPnl).toList());
            BigDecimal totalPnl = realizedPnl.add(unrealizedPnl);
            PortfolioSummaryDto dto = new PortfolioSummaryDto(
                marketValue,
                costBasis,
                realizedPnl,
                unrealizedPnl,
                totalPnl,
                roi(totalPnl, costBasis),
                holdings.size()
            );
            portfolioCache.writeSummary(userId, dto);
            return dto;
        });
    }

    /**
     * 為單一持倉估值並寫入快取。
     *
     * <p>最新價一律經 {@link MarketDataFacade} 取得（Redis latest → {@code market_prices}）。
     * <strong>不得改回自己查 {@code asset_latest_prices}</strong> —— 那張表只有 V2 seed 寫過一次，
     * 沒有任何程式更新它，讀它等於讓市值永遠停在種子價；何況跨模組取資料本來就該走 Facade。
     *
     * <p>查無行情時退回平均成本估值：市值等於成本、未實現損益為零。這是刻意的——寧可顯示「沒有變化」，
     * 也不要拿一個不知道多舊的價格假裝賺賠。
     *
     * @param position    持倉位置
     * @param marketPrice 已批次取得的最新價；null 表示查無行情
     * @return 估值後的持倉 DTO
     */
    private HoldingDto calculateAndCacheHolding(HoldingPosition position, LatestMarketPrice marketPrice) {
        LatestMarketPrice latest = marketPrice != null
            ? marketPrice
            : new LatestMarketPrice(position.avgCost(), position.lastUpdated());
        BigDecimal costBasis = position.totalQuantity().multiply(position.avgCost()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal marketValue = position.totalQuantity().multiply(latest.price()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal unrealized = marketValue.subtract(costBasis);
        HoldingDto dto = new HoldingDto(
            position.assetUuid().toString(),
            position.symbol(),
            position.assetName(),
            position.totalQuantity(),
            position.avgCost(),
            costBasis,
            latest.price(),
            marketValue,
            position.realizedPnl(),
            unrealized,
            roi(unrealized, costBasis),
            latest.priceTime(),
            position.lastUpdated()
        );
        portfolioCache.writeHolding(position.userId(), position.assetId(), dto);
        return dto;
    }

    private BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal roi(BigDecimal pnl, BigDecimal costBasis) {
        if (costBasis == null || costBasis.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return pnl.divide(costBasis, MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
