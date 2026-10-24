package dowob.xyz.stockwebv2.trading.service;

import dowob.xyz.stockwebv2.infrastructure.marketdata.LatestMarketPrice;
import dowob.xyz.stockwebv2.infrastructure.marketdata.MarketDataFacade;
import dowob.xyz.stockwebv2.trading.api.HoldingDto;
import dowob.xyz.stockwebv2.trading.domain.HoldingPosition;
import dowob.xyz.stockwebv2.trading.repository.TradingRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PortfolioQueryService} 讀時計算的單元測試；由 TradingServiceTest 搬入。
 *
 * <p>三條持倉估值測試的斷言與搬移前逐字相同：行情一律經 {@link MarketDataFacade} 取得、查無行情時退回
 * 平均成本、多筆持倉只做常數次往返。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("投資組合讀時計算")
class PortfolioQueryServiceTest {

    private TradingRepository repository;
    private MarketDataFacade marketDataFacade;
    private PortfolioCache portfolioCache;
    private PortfolioQueryService service;

    @BeforeEach
    void setup() {
        repository = mock(TradingRepository.class);
        marketDataFacade = mock(MarketDataFacade.class);
        portfolioCache = mock(PortfolioCache.class);
        service = new PortfolioQueryService(repository, marketDataFacade, portfolioCache);
    }

    @Test
    @DisplayName("持倉估值用 market-data 的即時價，不是自己查 asset_latest_prices")
    void holdingValuationUsesLiveMarketPrice() {
        /*
         * 原本 trading 以自己的 SQL 查 asset_latest_prices —— 那張表只有 V2 seed 寫過一次，
         * 全 repo 沒有任何程式更新它，於是市值永遠停在種子價。真正的即時價在 market-data 手上
         * （Redis market:latest:{assetId} → market_prices），只能經 MarketDataFacade 取得。
         */
        HoldingPosition position = position(new BigDecimal("10"), new BigDecimal("100"));
        when(repository.listHoldings(7L)).thenReturn(List.of(position));
        when(portfolioCache.readHoldings(eq(7L), anyCollection())).thenReturn(Map.of());
        when(marketDataFacade.findLatestPrices(anyCollection())).thenReturn(Map.of(42L,
            new LatestMarketPrice(new BigDecimal("150"), OffsetDateTime.parse("2026-09-04T10:00:00+08:00"))));

        List<HoldingDto> holdings = service.listHoldings(7L);

        assertThat(holdings).hasSize(1);
        HoldingDto dto = holdings.getFirst();
        assertThat(dto.marketPrice()).isEqualByComparingTo("150");
        assertThat(dto.marketValue()).isEqualByComparingTo("1500");
        assertThat(dto.unrealizedPnl()).isEqualByComparingTo("500");
        assertThat(dto.priceTime()).isEqualTo(OffsetDateTime.parse("2026-09-04T10:00:00+08:00"));
    }

    @Test
    @DisplayName("市場沒有這檔的價格時退回平均成本，市值等於成本、未實現損益為零")
    void holdingValuationFallsBackToAverageCostWhenNoMarketPrice() {
        /*
         * 「查不到行情」與「行情剛好等於某個數字」必須可區分，所以 facade 回 empty 而不是塞預設價；
         * 退場策略由這裡決定：以平均成本估值 → 未實現損益 0，不會假裝賺賠。
         */
        HoldingPosition position = position(new BigDecimal("10"), new BigDecimal("100"));
        when(repository.listHoldings(7L)).thenReturn(List.of(position));
        when(portfolioCache.readHoldings(eq(7L), anyCollection())).thenReturn(Map.of());
        when(marketDataFacade.findLatestPrices(anyCollection())).thenReturn(Map.of());

        HoldingDto dto = service.listHoldings(7L).getFirst();

        assertThat(dto.marketPrice()).isEqualByComparingTo("100");
        assertThat(dto.marketValue()).isEqualByComparingTo("1000");
        assertThat(dto.unrealizedPnl()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("多筆持倉只做一次批次快取讀取與一次批次取價，快取命中者不重算（N+1 → 常數次往返）")
    void holdingValuationBatchesCacheReadsAndPriceLookups() {
        /*
         * 原本每筆持倉各自一次快取 GET，未命中再各自一次取價與一次快取 SET：N 筆持倉約 3N 次往返，
         * 成交後三個頁面同時 refetch 還會再放大（性能審查 MED-5）。
         */
        HoldingPosition aapl = position(1L, 41L, "AAPL", new BigDecimal("10"), new BigDecimal("100"));
        HoldingPosition msft = position(2L, 42L, "MSFT", new BigDecimal("5"), new BigDecimal("200"));
        HoldingPosition nvda = position(3L, 43L, "NVDA", new BigDecimal("2"), new BigDecimal("300"));
        when(repository.listHoldings(7L)).thenReturn(List.of(aapl, msft, nvda));

        HoldingDto cachedMsft = new HoldingDto("22222222-2222-2222-2222-222222222222", "MSFT", "MSFT",
            new BigDecimal("5"), new BigDecimal("200"), new BigDecimal("1000"), new BigDecimal("210"),
            new BigDecimal("1050"), BigDecimal.ZERO, new BigDecimal("50"), new BigDecimal("0.05"),
            OffsetDateTime.parse("2026-09-04T10:00:00+08:00"), OffsetDateTime.parse("2026-09-01T00:00:00+08:00"));
        when(portfolioCache.readHoldings(eq(7L), anyCollection())).thenReturn(Map.of(42L, cachedMsft));
        when(marketDataFacade.findLatestPrices(anyCollection())).thenReturn(Map.of(
            41L, new LatestMarketPrice(new BigDecimal("150"), OffsetDateTime.parse("2026-09-04T10:00:00+08:00")),
            43L, new LatestMarketPrice(new BigDecimal("310"), OffsetDateTime.parse("2026-09-04T10:00:00+08:00"))));

        List<HoldingDto> holdings = service.listHoldings(7L);

        assertThat(holdings).extracting(HoldingDto::symbol).containsExactly("AAPL", "MSFT", "NVDA");
        assertThat(holdings.get(1)).isSameAs(cachedMsft);
        assertThat(holdings.get(0).marketPrice()).isEqualByComparingTo("150");
        assertThat(holdings.get(2).marketPrice()).isEqualByComparingTo("310");

        verify(portfolioCache, times(1)).readHoldings(eq(7L), anyCollection());
        ArgumentCaptor<Collection<Long>> priced = ArgumentCaptor.captor();
        verify(marketDataFacade, times(1)).findLatestPrices(priced.capture());
        assertThat(priced.getValue()).as("只為快取未命中的持倉取價").containsExactlyInAnyOrder(41L, 43L);
        verify(marketDataFacade, never()).findLatestPrice(anyLong());
    }

    /**
     * 建立一筆測試用持倉（AAPL、assetId 42）。
     *
     * @param quantity 持有數量
     * @param avgCost  平均成本
     * @return 持倉位置
     */
    private HoldingPosition position(BigDecimal quantity, BigDecimal avgCost) {
        return position(1L, 42L, "AAPL", quantity, avgCost);
    }

    /**
     * 建立一筆測試用持倉。
     *
     * @param holdingId 持倉 id
     * @param assetId   標的 id
     * @param symbol    標的代號
     * @param quantity  持有數量
     * @param avgCost   平均成本
     * @return 持倉位置
     */
    private HoldingPosition position(Long holdingId, Long assetId, String symbol, BigDecimal quantity, BigDecimal avgCost) {
        return new HoldingPosition(
            holdingId, 7L, assetId, UUID.nameUUIDFromBytes(symbol.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            symbol, symbol, quantity, avgCost, BigDecimal.ZERO, 0,
            OffsetDateTime.parse("2026-09-01T00:00:00+08:00"));
    }
}
