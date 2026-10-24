package dowob.xyz.stockwebv2.trading.service;

import dowob.xyz.stockwebv2.common.model.AssetType;
import dowob.xyz.stockwebv2.common.model.CurrencyCode;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetFacade;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetProfile;
import dowob.xyz.stockwebv2.infrastructure.fx.FxRate;
import dowob.xyz.stockwebv2.infrastructure.fx.FxRateFacade;
import dowob.xyz.stockwebv2.infrastructure.fx.FxRates;
import dowob.xyz.stockwebv2.infrastructure.marketdata.LatestMarketPrice;
import dowob.xyz.stockwebv2.infrastructure.marketdata.MarketDataFacade;
import dowob.xyz.stockwebv2.infrastructure.user.UserPreferenceFacade;
import dowob.xyz.stockwebv2.trading.api.HoldingDto;
import dowob.xyz.stockwebv2.trading.api.PortfolioSummaryDto;
import dowob.xyz.stockwebv2.trading.domain.HoldingPosition;
import dowob.xyz.stockwebv2.trading.domain.HoldingSnapshot;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    private UserPreferenceFacade userPreferenceFacade;
    private AssetFacade assetFacade;
    private FxRateFacade fxRateFacade;
    private PortfolioQueryService service;

    @BeforeEach
    void setup() {
        repository = mock(TradingRepository.class);
        marketDataFacade = mock(MarketDataFacade.class);
        portfolioCache = mock(PortfolioCache.class);
        userPreferenceFacade = mock(UserPreferenceFacade.class);
        assetFacade = mock(AssetFacade.class);
        fxRateFacade = mock(FxRateFacade.class);
        service = new PortfolioQueryService(
            repository, marketDataFacade, portfolioCache, userPreferenceFacade, assetFacade, fxRateFacade);
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

    @Test
    @DisplayName("混幣持倉依基準幣折算後加總：USD 與 TWD 不再直接相加（D-05）")
    void mixedCurrencyHoldingsAreConvertedToBaseCurrency() {
        givenBase(CurrencyCode.TWD, seedRates());
        givenMixedPortfolio(BigDecimal.ZERO, BigDecimal.ZERO);

        PortfolioSummaryDto dto = service.summary(7L);

        // AAPL 10 × 200 USD × 32.418 + 2330.TW 2 × 1000 TWD
        assertThat(dto.totalMarketValue()).isEqualByComparingTo("66836.00000000");
        // AAPL 10 × 100.5 USD × 32.418 + 2330.TW 2 × 900 TWD
        assertThat(dto.totalCostBasis()).isEqualByComparingTo("34380.09000000");
        assertThat(dto.unrealizedPnl()).isEqualByComparingTo("32455.91000000");
        assertThat(dto.baseCurrency()).isEqualTo("TWD");
        assertThat(dto.fxExcludedCount()).isZero();
        assertThat(dto.unconvertedCurrencies()).isEmpty();
        assertThat(dto.holdingCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("已實現損益逐資產（含已平倉）依各自幣別折算後加總，不再以 SQL 跨幣別 sum")
    void realizedPnlIncludesClosedPositionsConvertedPerAsset() {
        givenBase(CurrencyCode.TWD, seedRates());
        when(repository.listHoldings(7L)).thenReturn(List.of());
        when(repository.listHoldingSnapshots(7L)).thenReturn(List.of(
            snapshot(41L, BigDecimal.ZERO, new BigDecimal("100.5"), new BigDecimal("194")),
            snapshot(43L, BigDecimal.ZERO, new BigDecimal("900"), new BigDecimal("100"))));
        when(assetFacade.findProfiles(anyCollection())).thenReturn(profiles());

        PortfolioSummaryDto dto = service.summary(7L);

        // 194 USD × 32.418 + 100 TWD
        assertThat(dto.realizedPnl()).isEqualByComparingTo("6389.09200000");
        assertThat(dto.holdingCount()).isZero();
        assertThat(dto.totalPnl()).isEqualByComparingTo("6389.09200000");
        verify(marketDataFacade, never()).findLatestPrices(anyCollection());
    }

    @Test
    @DisplayName("基準幣 USD 時 TWD 持倉取直接列 0.030847，不是 1/32.418")
    void baseUsdUsesDirectTwdUsdRate() {
        givenBase(CurrencyCode.USD, seedRates());
        HoldingPosition tsmc = position(2L, 43L, "2330.TW", new BigDecimal("2"), new BigDecimal("900"));
        when(repository.listHoldings(7L)).thenReturn(List.of(tsmc));
        when(repository.listHoldingSnapshots(7L)).thenReturn(List.of(
            snapshot(43L, new BigDecimal("2"), new BigDecimal("900"), BigDecimal.ZERO)));
        when(assetFacade.findProfiles(anyCollection())).thenReturn(profiles());
        when(marketDataFacade.findLatestPrices(anyCollection())).thenReturn(Map.of(
            43L, new LatestMarketPrice(new BigDecimal("1000"), OffsetDateTime.parse("2026-09-04T10:00:00+08:00"))));

        PortfolioSummaryDto dto = service.summary(7L);

        assertThat(dto.baseCurrency()).isEqualTo("USD");
        assertThat(dto.totalMarketValue()).isEqualByComparingTo("61.69400000");
    }

    @Test
    @DisplayName("換不到匯率的持倉不計入總額，並回報 fxExcludedCount 與 unconvertedCurrencies（D-04）")
    void holdingWithoutRateIsExcludedAndReported() {
        givenBase(CurrencyCode.TWD, FxRates.empty());
        HoldingPosition aapl = position(1L, 41L, "AAPL", new BigDecimal("10"), new BigDecimal("100"));
        when(repository.listHoldings(7L)).thenReturn(List.of(aapl));
        when(repository.listHoldingSnapshots(7L)).thenReturn(List.of(
            snapshot(41L, new BigDecimal("10"), new BigDecimal("100"), BigDecimal.ZERO)));
        when(assetFacade.findProfiles(anyCollection())).thenReturn(profiles());
        when(marketDataFacade.findLatestPrices(anyCollection())).thenReturn(Map.of(
            41L, new LatestMarketPrice(new BigDecimal("200"), OffsetDateTime.parse("2026-09-04T10:00:00+08:00"))));

        PortfolioSummaryDto dto = service.summary(7L);

        assertThat(dto.totalMarketValue()).isEqualByComparingTo("0");
        assertThat(dto.totalCostBasis()).isEqualByComparingTo("0");
        assertThat(dto.fxExcludedCount()).isEqualTo(1);
        assertThat(dto.unconvertedCurrencies()).containsExactly("USD");
    }

    @Test
    @DisplayName("summary 快取命中時只解析基準幣，其餘 Facade 零互動")
    void summaryCacheHitOnlyResolvesBaseCurrency() {
        when(userPreferenceFacade.findBaseCurrency(7L)).thenReturn(CurrencyCode.TWD);
        PortfolioSummaryDto cached = new PortfolioSummaryDto(
            BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            0, "TWD", 0, List.of());
        when(portfolioCache.readSummary(7L, CurrencyCode.TWD)).thenReturn(Optional.of(cached));

        PortfolioSummaryDto dto = service.summary(7L);

        assertThat(dto).isSameAs(cached);
        verify(userPreferenceFacade, times(1)).findBaseCurrency(7L);
        verifyNoInteractions(assetFacade, fxRateFacade, marketDataFacade);
    }

    @Test
    @DisplayName("summary 快取未命中時，每個 Facade 至多一次批次呼叫（D-23）")
    void summaryCacheMissCallsEachFacadeAtMostOnce() {
        givenBase(CurrencyCode.TWD, seedRates());
        givenMixedPortfolio(BigDecimal.ZERO, BigDecimal.ZERO);

        service.summary(7L);

        verify(userPreferenceFacade, times(1)).findBaseCurrency(7L);
        verify(fxRateFacade, times(1)).currentRates();
        verify(assetFacade, times(1)).findProfiles(anyCollection());
        verify(marketDataFacade, times(1)).findLatestPrices(anyCollection());
    }

    @Test
    @DisplayName("summary 依基準幣分別快取：寫入鍵帶本次基準幣")
    void summaryIsCachedPerBaseCurrency() {
        givenBase(CurrencyCode.TWD, seedRates());
        givenMixedPortfolio(BigDecimal.ZERO, BigDecimal.ZERO);

        PortfolioSummaryDto dto = service.summary(7L);

        verify(portfolioCache).writeSummary(7L, CurrencyCode.TWD, dto);
    }

    /**
     * 設定基準幣與匯率；快取一律未命中。
     *
     * @param base  使用者基準幣
     * @param rates 匯率
     */
    private void givenBase(CurrencyCode base, FxRates rates) {
        when(userPreferenceFacade.findBaseCurrency(7L)).thenReturn(base);
        when(fxRateFacade.currentRates()).thenReturn(rates);
        when(portfolioCache.readSummary(eq(7L), any(CurrencyCode.class))).thenReturn(Optional.empty());
        when(portfolioCache.readHoldings(eq(7L), anyCollection())).thenReturn(Map.of());
    }

    /**
     * 混幣組合：AAPL（USD）10 股均價 100.5 市價 200；2330.TW（TWD）2 股均價 900 市價 1000。
     *
     * @param aaplRealized AAPL 的已實現損益
     * @param tsmcRealized 2330.TW 的已實現損益
     */
    private void givenMixedPortfolio(BigDecimal aaplRealized, BigDecimal tsmcRealized) {
        HoldingPosition aapl = position(1L, 41L, "AAPL", new BigDecimal("10"), new BigDecimal("100.5"));
        HoldingPosition tsmc = position(2L, 43L, "2330.TW", new BigDecimal("2"), new BigDecimal("900"));
        when(repository.listHoldings(7L)).thenReturn(List.of(tsmc, aapl));
        when(repository.listHoldingSnapshots(7L)).thenReturn(List.of(
            snapshot(41L, new BigDecimal("10"), new BigDecimal("100.5"), aaplRealized),
            snapshot(43L, new BigDecimal("2"), new BigDecimal("900"), tsmcRealized)));
        when(assetFacade.findProfiles(anyCollection())).thenReturn(profiles());
        OffsetDateTime priceTime = OffsetDateTime.parse("2026-09-04T10:00:00+08:00");
        when(marketDataFacade.findLatestPrices(anyCollection())).thenReturn(Map.of(
            41L, new LatestMarketPrice(new BigDecimal("200"), priceTime),
            43L, new LatestMarketPrice(new BigDecimal("1000"), priceTime)));
    }

    /**
     * V2 seed 的四列匯率。
     *
     * @return 匯率值物件
     */
    private FxRates seedRates() {
        OffsetDateTime now = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        return FxRates.of(List.of(
            new FxRate(CurrencyCode.USD, CurrencyCode.TWD, new BigDecimal("32.418"), now),
            new FxRate(CurrencyCode.EUR, CurrencyCode.USD, new BigDecimal("1.0832"), now),
            new FxRate(CurrencyCode.USD, CurrencyCode.JPY, new BigDecimal("156.42"), now),
            new FxRate(CurrencyCode.TWD, CurrencyCode.USD, new BigDecimal("0.030847"), now)));
    }

    /**
     * AAPL（USD）與 2330.TW（TWD）的資產屬性。
     *
     * @return assetId → 資產屬性
     */
    private Map<Long, AssetProfile> profiles() {
        return Map.of(
            41L, new AssetProfile(41L, "AAPL", AssetType.STOCK, "US", CurrencyCode.USD, "Tech"),
            43L, new AssetProfile(43L, "2330.TW", AssetType.STOCK, "TW", CurrencyCode.TWD, "Tech"));
    }

    /**
     * 建立一筆持倉快照（含已平倉）。
     *
     * @param assetId  標的 id
     * @param quantity 持有數量
     * @param avgCost  平均成本
     * @param realized 已實現損益
     * @return 持倉快照
     */
    private HoldingSnapshot snapshot(Long assetId, BigDecimal quantity, BigDecimal avgCost, BigDecimal realized) {
        return new HoldingSnapshot(assetId, quantity, avgCost, realized,
            OffsetDateTime.parse("2026-09-01T00:00:00+08:00"));
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
