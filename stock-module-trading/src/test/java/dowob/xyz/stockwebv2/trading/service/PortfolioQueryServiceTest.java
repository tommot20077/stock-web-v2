package dowob.xyz.stockwebv2.trading.service;

import dowob.xyz.stockwebv2.common.model.AssetType;
import dowob.xyz.stockwebv2.common.model.CurrencyCode;
import dowob.xyz.stockwebv2.common.model.TradingDay;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetFacade;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetProfile;
import dowob.xyz.stockwebv2.infrastructure.fx.FxRate;
import dowob.xyz.stockwebv2.infrastructure.fx.FxRateFacade;
import dowob.xyz.stockwebv2.infrastructure.fx.FxRates;
import dowob.xyz.stockwebv2.infrastructure.marketdata.DailyQuote;
import dowob.xyz.stockwebv2.infrastructure.marketdata.MarketDataFacade;
import dowob.xyz.stockwebv2.infrastructure.user.UserPreferenceFacade;
import dowob.xyz.stockwebv2.trading.api.HoldingDto;
import dowob.xyz.stockwebv2.trading.api.PortfolioSummaryDto;
import dowob.xyz.stockwebv2.trading.domain.DayFill;
import dowob.xyz.stockwebv2.trading.domain.HoldingPosition;
import dowob.xyz.stockwebv2.trading.domain.HoldingSnapshot;
import dowob.xyz.stockwebv2.trading.domain.TradeType;
import dowob.xyz.stockwebv2.trading.repository.TradingRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
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

    /** 美股交易日 2026-09-04（紐約時間）的最新成交時間；交易日起點為 2026-09-04T00:00-04:00。 */
    private static final OffsetDateTime PRICE_TIME = OffsetDateTime.parse("2026-09-04T10:00:00-04:00");

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
        // 預設：基準幣 TWD、V2 seed 匯率、任何被查的標的都是 USD 的 STOCK；個別測試再覆寫。
        when(userPreferenceFacade.findBaseCurrency(7L)).thenReturn(CurrencyCode.TWD);
        when(fxRateFacade.currentRates()).thenReturn(seedRates());
        when(assetFacade.findProfiles(anyCollection())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            Map<Long, AssetProfile> byId = new java.util.HashMap<>();
            ids.forEach(id -> byId.put(id, new AssetProfile(id, "SYM" + id, AssetType.STOCK, "US", CurrencyCode.USD, "Tech")));
            return byId;
        });
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
        when(marketDataFacade.findDailyQuotes(anyMap())).thenReturn(Map.of(42L,
            quote(new BigDecimal("150"), null, OffsetDateTime.parse("2026-09-04T10:00:00+08:00"))));

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
        when(marketDataFacade.findDailyQuotes(anyMap())).thenReturn(Map.of());

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
            OffsetDateTime.parse("2026-09-04T10:00:00+08:00"), OffsetDateTime.parse("2026-09-01T00:00:00+08:00"),
            "USD", "STOCK", "Tech", null, null, null, null, null, null);
        when(portfolioCache.readHoldings(eq(7L), anyCollection())).thenReturn(Map.of(42L, cachedMsft));
        when(marketDataFacade.findDailyQuotes(anyMap())).thenReturn(Map.of(
            41L, quote(new BigDecimal("150"), null, OffsetDateTime.parse("2026-09-04T10:00:00+08:00")),
            43L, quote(new BigDecimal("310"), null, OffsetDateTime.parse("2026-09-04T10:00:00+08:00"))));

        List<HoldingDto> holdings = service.listHoldings(7L);

        assertThat(holdings).extracting(HoldingDto::symbol).containsExactly("AAPL", "MSFT", "NVDA");
        // 快取命中者不重算估值；只在回傳時依本次基準幣補上 baseCurrency / marketValueInBase（新 record，非同一實例）。
        assertThat(holdings.get(1)).usingRecursiveComparison()
            .ignoringFields("baseCurrency", "marketValueInBase").isEqualTo(cachedMsft);
        assertThat(holdings.get(0).marketPrice()).isEqualByComparingTo("150");
        assertThat(holdings.get(2).marketPrice()).isEqualByComparingTo("310");

        verify(portfolioCache, times(1)).readHoldings(eq(7L), anyCollection());
        ArgumentCaptor<Map<Long, TradingDay>> priced = ArgumentCaptor.captor();
        verify(marketDataFacade, times(1)).findDailyQuotes(priced.capture());
        assertThat(priced.getValue().keySet()).as("只為快取未命中的持倉取價").containsExactlyInAnyOrder(41L, 43L);
        verify(marketDataFacade, never()).findLatestPrices(anyCollection());
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
        verify(marketDataFacade, never()).findDailyQuotes(anyMap());
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
        when(marketDataFacade.findDailyQuotes(anyMap())).thenReturn(Map.of(
            43L, quote(new BigDecimal("1000"), null, OffsetDateTime.parse("2026-09-04T10:00:00+08:00"))));

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
        when(marketDataFacade.findDailyQuotes(anyMap())).thenReturn(Map.of(
            41L, quote(new BigDecimal("200"), null, OffsetDateTime.parse("2026-09-04T10:00:00+08:00"))));

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
        verify(marketDataFacade, times(1)).findDailyQuotes(anyMap());
    }

    @Test
    @DisplayName("summary 依基準幣分別快取：寫入鍵帶本次基準幣")
    void summaryIsCachedPerBaseCurrency() {
        givenBase(CurrencyCode.TWD, seedRates());
        givenMixedPortfolio(BigDecimal.ZERO, BigDecimal.ZERO);

        PortfolioSummaryDto dto = service.summary(7L);

        verify(portfolioCache).writeSummary(7L, CurrencyCode.TWD, dto);
    }

    @Test
    @DisplayName("持倉帶原幣幣別、資產類型、產業與基準幣市值；既有 marketValue 維持原幣")
    void holdingsCarryProfileFieldsAndBaseValuation() {
        HoldingPosition aapl = position(1L, 41L, "AAPL", new BigDecimal("10"), new BigDecimal("100"));
        when(repository.listHoldings(7L)).thenReturn(List.of(aapl));
        when(portfolioCache.readHoldings(eq(7L), anyCollection())).thenReturn(Map.of());
        when(assetFacade.findProfiles(anyCollection())).thenReturn(profiles());
        when(marketDataFacade.findDailyQuotes(anyMap())).thenReturn(Map.of(
            41L, quote(new BigDecimal("200"), null, OffsetDateTime.parse("2026-09-04T10:00:00+08:00"))));

        HoldingDto dto = service.listHoldings(7L).getFirst();

        assertThat(dto.currency()).isEqualTo("USD");
        assertThat(dto.assetType()).isEqualTo("STOCK");
        assertThat(dto.sector()).isEqualTo("Tech");
        assertThat(dto.baseCurrency()).isEqualTo("TWD");
        assertThat(dto.marketValue()).isEqualByComparingTo("2000.00000000");
        assertThat(dto.marketValueInBase()).isEqualByComparingTo("64836.00000000");
    }

    @Test
    @DisplayName("換不到匯率時 marketValueInBase 為 null，其餘欄位照常")
    void holdingWithoutRateHasNullMarketValueInBase() {
        when(fxRateFacade.currentRates()).thenReturn(FxRates.empty());
        HoldingPosition aapl = position(1L, 41L, "AAPL", new BigDecimal("10"), new BigDecimal("100"));
        when(repository.listHoldings(7L)).thenReturn(List.of(aapl));
        when(portfolioCache.readHoldings(eq(7L), anyCollection())).thenReturn(Map.of());
        when(assetFacade.findProfiles(anyCollection())).thenReturn(profiles());
        when(marketDataFacade.findDailyQuotes(anyMap())).thenReturn(Map.of(
            41L, quote(new BigDecimal("200"), null, OffsetDateTime.parse("2026-09-04T10:00:00+08:00"))));

        HoldingDto dto = service.listHoldings(7L).getFirst();

        assertThat(dto.marketValueInBase()).isNull();
        assertThat(dto.baseCurrency()).isEqualTo("TWD");
        assertThat(dto.currency()).isEqualTo("USD");
        assertThat(dto.marketValue()).isEqualByComparingTo("2000.00000000");
    }

    @Test
    @DisplayName("快取中的原幣持倉依本次基準幣重新折算，快取內容不被覆寫成基準幣值")
    void cachedHoldingIsReconvertedWithCurrentBase() {
        when(userPreferenceFacade.findBaseCurrency(7L)).thenReturn(CurrencyCode.USD);
        HoldingPosition tsmc = position(2L, 43L, "2330.TW", new BigDecimal("2"), new BigDecimal("900"));
        when(repository.listHoldings(7L)).thenReturn(List.of(tsmc));
        HoldingDto cachedOriginal = new HoldingDto("u", "2330.TW", "TSMC",
            new BigDecimal("2"), new BigDecimal("900"), new BigDecimal("1800"), new BigDecimal("1000"),
            new BigDecimal("2000"), BigDecimal.ZERO, new BigDecimal("200"), new BigDecimal("0.11111111"),
            OffsetDateTime.parse("2026-09-04T10:00:00+08:00"), OffsetDateTime.parse("2026-09-01T00:00:00+08:00"),
            "TWD", "STOCK", "Tech", null, null, null, null, null, null);
        when(portfolioCache.readHoldings(eq(7L), anyCollection())).thenReturn(Map.of(43L, cachedOriginal));

        HoldingDto dto = service.listHoldings(7L).getFirst();

        assertThat(dto.baseCurrency()).isEqualTo("USD");
        assertThat(dto.marketValueInBase()).isEqualByComparingTo("61.69400000");
        assertThat(dto.marketValue()).isEqualByComparingTo("2000");
        assertThat(cachedOriginal.baseCurrency()).isNull();
        assertThat(cachedOriginal.marketValueInBase()).isNull();
        verify(portfolioCache, never()).writeHolding(anyLong(), anyLong(), any(HoldingDto.class));
    }

    @Test
    @DisplayName("持倉快取全部未命中時各 Facade 各一次批次呼叫；全部命中時只剩基準幣與匯率")
    void holdingsMissPathCallsEachFacadeOnce() {
        HoldingPosition aapl = position(1L, 41L, "AAPL", new BigDecimal("10"), new BigDecimal("100"));
        HoldingPosition msft = position(2L, 42L, "MSFT", new BigDecimal("5"), new BigDecimal("200"));
        when(repository.listHoldings(7L)).thenReturn(List.of(aapl, msft));
        when(portfolioCache.readHoldings(eq(7L), anyCollection())).thenReturn(Map.of());
        when(marketDataFacade.findDailyQuotes(anyMap())).thenReturn(Map.of());

        service.listHoldings(7L);

        verify(userPreferenceFacade, times(1)).findBaseCurrency(7L);
        verify(assetFacade, times(1)).findProfiles(anyCollection());
        verify(marketDataFacade, times(1)).findDailyQuotes(anyMap());
        verify(fxRateFacade, times(1)).currentRates();
    }

    @Test
    @DisplayName("持倉快取全部命中時只呼叫基準幣與匯率，不查資產屬性也不取價")
    void holdingsHitPathOnlyResolvesBaseCurrencyAndRates() {
        HoldingPosition aapl = position(1L, 41L, "AAPL", new BigDecimal("10"), new BigDecimal("100"));
        when(repository.listHoldings(7L)).thenReturn(List.of(aapl));
        HoldingDto cached = new HoldingDto("u", "AAPL", "AAPL",
            new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("1000"), new BigDecimal("200"),
            new BigDecimal("2000"), BigDecimal.ZERO, new BigDecimal("1000"), new BigDecimal("1"),
            OffsetDateTime.parse("2026-09-04T10:00:00+08:00"), OffsetDateTime.parse("2026-09-01T00:00:00+08:00"),
            "USD", "STOCK", "Tech", null, null, null, null, null, null);
        when(portfolioCache.readHoldings(eq(7L), anyCollection())).thenReturn(Map.of(41L, cached));

        service.listHoldings(7L);

        verify(userPreferenceFacade, times(1)).findBaseCurrency(7L);
        verify(fxRateFacade, times(1)).currentRates();
        verify(assetFacade, never()).findProfiles(anyCollection());
        verify(marketDataFacade, never()).findDailyQuotes(anyMap());
    }

    @Test
    @DisplayName("資產沒有產業分類時 sector 為 null，由前端顯示「未分類」")
    void sectorNullIsPassedThrough() {
        HoldingPosition usdTwd = position(1L, 44L, "USDTWD", new BigDecimal("1000"), new BigDecimal("32"));
        when(repository.listHoldings(7L)).thenReturn(List.of(usdTwd));
        when(portfolioCache.readHoldings(eq(7L), anyCollection())).thenReturn(Map.of());
        when(assetFacade.findProfiles(anyCollection())).thenReturn(Map.of(
            44L, new AssetProfile(44L, "USDTWD", AssetType.FX, "FX", CurrencyCode.TWD, null)));
        when(marketDataFacade.findDailyQuotes(anyMap())).thenReturn(Map.of());

        HoldingDto dto = service.listHoldings(7L).getFirst();

        assertThat(dto.sector()).isNull();
        assertThat(dto.assetType()).isEqualTo("FX");
        assertThat(dto.currency()).isEqualTo("TWD");
    }

    @Test
    @DisplayName("持倉未命中路徑以 findDailyQuotes 取價：交易日由資產屬性建立，不再呼叫 findLatestPrices")
    void holdingsUseDailyQuotesWithTradingDayFromProfile() {
        HoldingPosition aapl = position(1L, 41L, "AAPL", new BigDecimal("10"), new BigDecimal("100"));
        HoldingPosition tsmc = position(2L, 43L, "2330.TW", new BigDecimal("2"), new BigDecimal("900"));
        when(repository.listHoldings(7L)).thenReturn(List.of(aapl, tsmc));
        when(portfolioCache.readHoldings(eq(7L), anyCollection())).thenReturn(Map.of());
        when(assetFacade.findProfiles(anyCollection())).thenReturn(profiles());

        service.listHoldings(7L);

        ArgumentCaptor<Map<Long, TradingDay>> days = ArgumentCaptor.captor();
        verify(marketDataFacade, times(1)).findDailyQuotes(days.capture());
        assertThat(days.getValue()).containsOnly(
            entry(41L, TradingDay.of(AssetType.STOCK, "US")),
            entry(43L, TradingDay.of(AssetType.STOCK, "TW")));
        verify(marketDataFacade, never()).findLatestPrices(anyCollection());
    }

    @Test
    @DisplayName("持倉帶單檔今日損益：昨日持有 10 股、今日買 5@195，前收 190、最新價 200")
    void holdingCarriesDayPnlFromQuoteAndTodaysFills() {
        HoldingPosition msft = position(1L, 42L, "MSFT", new BigDecimal("15"), new BigDecimal("160"));
        givenMissingHolding(msft);
        when(marketDataFacade.findDailyQuotes(anyMap())).thenReturn(Map.of(42L, quote("200", "190")));
        when(repository.listFillsSince(eq(7L), any(OffsetDateTime.class))).thenReturn(List.of(
            fill(42L, TradeType.BUY, "5", "195", "2026-09-04T09:00:00-04:00")));

        HoldingDto dto = service.listHoldings(7L).getFirst();

        // 15×200 − 10×190 − 5×195
        assertThat(dto.dayPricePnl()).isEqualByComparingTo("125");
        assertThat(dto.dayPnlBasis()).isEqualByComparingTo("2875");
        assertThat(dto.dayPricePnlRatio()).isEqualByComparingTo("0.04347826");
        assertThat(dto.previousClose()).isEqualByComparingTo("190");
        assertThat(dto.marketPrice()).isEqualByComparingTo("200");
    }

    @Test
    @DisplayName("交易日起點之前的成交不計入今日損益；成交查詢的 since 等於該市場的交易日起點")
    void fillsBeforeTradingDayStartAreNotCounted() {
        HoldingPosition msft = position(1L, 42L, "MSFT", new BigDecimal("15"), new BigDecimal("160"));
        givenMissingHolding(msft);
        when(marketDataFacade.findDailyQuotes(anyMap())).thenReturn(Map.of(42L, quote("200", "190")));
        when(repository.listFillsSince(eq(7L), any(OffsetDateTime.class))).thenReturn(List.of(
            fill(42L, TradeType.BUY, "5", "195", "2026-09-03T15:00:00-04:00")));

        HoldingDto dto = service.listHoldings(7L).getFirst();

        // 昨天買的 5 股已在 Q0 內：15×(200 − 190)
        assertThat(dto.dayPricePnl()).isEqualByComparingTo("150");
        assertThat(dto.dayPnlBasis()).isEqualByComparingTo("2850");
        ArgumentCaptor<OffsetDateTime> since = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repository).listFillsSince(eq(7L), since.capture());
        Instant expected = TradingDay.of(AssetType.STOCK, "US").dayStart(PRICE_TIME.toInstant());
        assertThat(since.getValue().toInstant()).isEqualTo(expected);
    }

    @Test
    @DisplayName("今天才首次買入且無前收：照 D-12 今日買入項計算，不回 null（D-26）")
    void firstBoughtTodayIsComputedWithoutPreviousClose() {
        HoldingPosition meta = position(1L, 42L, "META", new BigDecimal("2"), new BigDecimal("500"));
        givenMissingHolding(meta);
        when(marketDataFacade.findDailyQuotes(anyMap())).thenReturn(Map.of(42L, quote("510", null)));
        when(repository.listFillsSince(eq(7L), any(OffsetDateTime.class))).thenReturn(List.of(
            fill(42L, TradeType.BUY, "2", "500", "2026-09-04T09:00:00-04:00")));

        HoldingDto dto = service.listHoldings(7L).getFirst();

        assertThat(dto.dayPricePnl()).isEqualByComparingTo("20");
        assertThat(dto.dayPricePnlRatio()).isEqualByComparingTo("0.02");
        assertThat(dto.previousClose()).isNull();
    }

    @Test
    @DisplayName("昨日即持有卻無前收：三個今日欄位為 null，其餘估值照常")
    void heldSinceYesterdayWithoutPreviousCloseHasNullDayFields() {
        HoldingPosition amzn = position(1L, 42L, "AMZN", new BigDecimal("3"), new BigDecimal("180"));
        givenMissingHolding(amzn);
        when(marketDataFacade.findDailyQuotes(anyMap())).thenReturn(Map.of(42L, quote("190", null)));

        HoldingDto dto = service.listHoldings(7L).getFirst();

        assertThat(dto.dayPricePnl()).isNull();
        assertThat(dto.dayPricePnlRatio()).isNull();
        assertThat(dto.previousClose()).isNull();
        assertThat(dto.marketValue()).isEqualByComparingTo("570");
    }

    @Test
    @DisplayName("無行情：沿用均價估值，今日欄位為 null，也不查今日成交")
    void noQuoteKeepsAverageCostValuationAndNullDayFields() {
        HoldingPosition aapl = position(1L, 42L, "AAPL", new BigDecimal("10"), new BigDecimal("100"));
        givenMissingHolding(aapl);
        when(marketDataFacade.findDailyQuotes(anyMap())).thenReturn(Map.of());

        HoldingDto dto = service.listHoldings(7L).getFirst();

        assertThat(dto.marketValue()).isEqualByComparingTo("1000");
        assertThat(dto.dayPricePnl()).isNull();
        assertThat(dto.dayPricePnlRatio()).isNull();
        assertThat(dto.previousClose()).isNull();
        verify(repository, never()).listFillsSince(anyLong(), any(OffsetDateTime.class));
    }

    @Test
    @DisplayName("多檔持倉只查一次今日成交，since 取各市場交易日起點的最小值")
    void todaysFillsAreFetchedOnceWithEarliestDayStart() {
        HoldingPosition aapl = position(1L, 41L, "AAPL", new BigDecimal("10"), new BigDecimal("100"));
        HoldingPosition tsmc = position(2L, 43L, "2330.TW", new BigDecimal("2"), new BigDecimal("900"));
        when(repository.listHoldings(7L)).thenReturn(List.of(aapl, tsmc));
        when(portfolioCache.readHoldings(eq(7L), anyCollection())).thenReturn(Map.of());
        when(assetFacade.findProfiles(anyCollection())).thenReturn(profiles());
        OffsetDateTime twTime = OffsetDateTime.parse("2026-09-04T10:00:00+08:00");
        when(marketDataFacade.findDailyQuotes(anyMap())).thenReturn(Map.of(
            41L, quote(new BigDecimal("200"), new BigDecimal("190"), PRICE_TIME),
            43L, quote(new BigDecimal("1000"), new BigDecimal("990"), twTime)));

        service.listHoldings(7L);

        ArgumentCaptor<OffsetDateTime> since = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repository, times(1)).listFillsSince(eq(7L), since.capture());
        Instant usStart = TradingDay.of(AssetType.STOCK, "US").dayStart(PRICE_TIME.toInstant());
        Instant twStart = TradingDay.of(AssetType.STOCK, "TW").dayStart(twTime.toInstant());
        assertThat(since.getValue().toInstant()).isEqualTo(twStart).isBefore(usStart);
    }

    /**
     * 讓單一持倉走快取未命中路徑。
     *
     * @param position 持倉位置
     */
    private void givenMissingHolding(HoldingPosition position) {
        when(repository.listHoldings(7L)).thenReturn(List.of(position));
        when(portfolioCache.readHoldings(eq(7L), anyCollection())).thenReturn(Map.of());
    }

    /**
     * 建立一筆今日成交。
     *
     * @param assetId    標的 id
     * @param type       買賣別
     * @param quantity   數量
     * @param price      成交價
     * @param executedAt 成交時間（ISO-8601）
     * @return 成交紀錄
     */
    private DayFill fill(Long assetId, TradeType type, String quantity, String price, String executedAt) {
        return new DayFill(assetId, type, new BigDecimal(quantity), new BigDecimal(price),
            OffsetDateTime.parse(executedAt));
    }

    /**
     * 建立一筆以 {@link #PRICE_TIME} 為成交時間的行情；最高 / 最低價皆取最新價。
     *
     * @param latest        最新價
     * @param previousClose 前收；null 表示本交易日前無成交
     * @return 報價
     */
    private DailyQuote quote(String latest, String previousClose) {
        return quote(new BigDecimal(latest), previousClose == null ? null : new BigDecimal(previousClose), PRICE_TIME);
    }

    private DailyQuote quote(BigDecimal latest, BigDecimal previousClose, OffsetDateTime priceTime) {
        return new DailyQuote(latest, priceTime, previousClose, latest, latest, null);
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
        when(marketDataFacade.findDailyQuotes(anyMap())).thenReturn(Map.of(
            41L, quote(new BigDecimal("200"), null, priceTime),
            43L, quote(new BigDecimal("1000"), null, priceTime)));
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
