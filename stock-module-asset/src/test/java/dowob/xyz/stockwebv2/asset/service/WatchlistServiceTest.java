package dowob.xyz.stockwebv2.asset.service;

import dowob.xyz.stockwebv2.asset.api.AssetDto;
import dowob.xyz.stockwebv2.asset.api.WatchlistDto;
import dowob.xyz.stockwebv2.asset.domain.Asset;
import dowob.xyz.stockwebv2.asset.domain.Watchlist;
import dowob.xyz.stockwebv2.asset.repository.WatchlistRepository;
import dowob.xyz.stockwebv2.common.model.AssetType;
import dowob.xyz.stockwebv2.common.model.CurrencyCode;
import dowob.xyz.stockwebv2.common.model.TradingDay;
import dowob.xyz.stockwebv2.infrastructure.marketdata.DailyQuote;
import dowob.xyz.stockwebv2.infrastructure.marketdata.MarketDataFacade;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WatchlistService} 單元測試：清單列出、預設清單、報價批次（WATCH-01；D-14、D-17、D-19）。
 *
 * @author Yuan
 * @version 1.0
 */
class WatchlistServiceTest {

    private static final long USER_ID = 7L;

    private final WatchlistRepository repository = mock(WatchlistRepository.class);
    private final MarketDataFacade marketData = mock(MarketDataFacade.class);
    private final WatchlistService service = new WatchlistService(repository, marketData);

    private static Asset asset(long id, String symbol, AssetType type, String market) {
        return new Asset(id, UUID.randomUUID(), symbol, symbol, type, market, CurrencyCode.USD, null, true, true);
    }

    private static Watchlist watchlist(long id, String name) {
        return new Watchlist(id, UUID.randomUUID(), USER_ID, name, OffsetDateTime.parse("2026-11-01T00:00:00Z"));
    }

    @Test
    @DisplayName("0 個清單：先 ensureDefault 再讀清單，回傳一個空的「我的自選」")
    void list_newUser_createsDefaultThenReads() {
        Watchlist defaultList = watchlist(1L, WatchlistService.DEFAULT_NAME);
        when(repository.listByUser(USER_ID)).thenReturn(List.of(defaultList));
        when(repository.itemsByWatchlist(List.of(1L))).thenReturn(Map.of());

        List<WatchlistDto> result = service.list(USER_ID);

        InOrder order = inOrder(repository);
        order.verify(repository).ensureDefault(USER_ID);
        order.verify(repository).listByUser(USER_ID);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(defaultList.uuid());
        assertThat(result.get(0).name()).isEqualTo("我的自選");
        assertThat(result.get(0).items()).isEmpty();
    }

    @Test
    @DisplayName("清單皆空時不呼叫 findDailyQuotes")
    void list_allEmpty_skipsQuoteLookup() {
        when(repository.listByUser(USER_ID)).thenReturn(List.of(watchlist(1L, "A"), watchlist(2L, "B")));
        when(repository.itemsByWatchlist(List.of(1L, 2L))).thenReturn(Map.of());

        List<WatchlistDto> result = service.list(USER_ID);

        assertThat(result).extracting(WatchlistDto::name).containsExactly("A", "B");
        verify(marketData, never()).findDailyQuotes(anyMap());
    }

    @Test
    @DisplayName("兩個清單共 3 個 distinct 資產：findDailyQuotes 只呼叫一次且參數恰為 3 個資產；順序沿用 repository")
    void list_batchesDistinctAssetsIntoOneQuoteLookup() {
        Asset aapl = asset(10L, "AAPL", AssetType.STOCK, "US");
        Asset btc = asset(11L, "BTC", AssetType.CRYPTO, "CRYPTO");
        Asset usdTwd = asset(12L, "USD/TWD", AssetType.FX, "FX");
        Watchlist first = watchlist(1L, "我的自選");
        Watchlist second = watchlist(2L, "加密");
        when(repository.listByUser(USER_ID)).thenReturn(List.of(first, second));
        when(repository.itemsByWatchlist(List.of(1L, 2L))).thenReturn(Map.of(
            1L, List.of(usdTwd, aapl),
            2L, List.of(btc, aapl)
        ));
        Map<Long, TradingDay> expectedDays = Map.of(
            10L, TradingDay.of(AssetType.STOCK, "US"),
            11L, TradingDay.of(AssetType.CRYPTO, "CRYPTO"),
            12L, TradingDay.of(AssetType.FX, "FX")
        );
        when(marketData.findDailyQuotes(expectedDays)).thenReturn(Map.of(10L,
            new DailyQuote(new BigDecimal("105"), OffsetDateTime.parse("2026-01-05T15:00:00Z"),
                new BigDecimal("100"), new BigDecimal("106"), new BigDecimal("99"), new BigDecimal("52100000"))));

        List<WatchlistDto> result = service.list(USER_ID);

        verify(marketData, times(1)).findDailyQuotes(anyMap());
        verify(marketData).findDailyQuotes(expectedDays);
        assertThat(result).extracting(WatchlistDto::name).containsExactly("我的自選", "加密");
        assertThat(result.get(0).items()).extracting(AssetDto::symbol).containsExactly("USD/TWD", "AAPL");
        assertThat(result.get(1).items()).extracting(AssetDto::symbol).containsExactly("BTC", "AAPL");

        AssetDto quoted = result.get(0).items().get(1);
        assertThat(quoted).isEqualTo(AssetQuoteMapper.toDto(aapl, new DailyQuote(new BigDecimal("105"),
            OffsetDateTime.parse("2026-01-05T15:00:00Z"), new BigDecimal("100"), new BigDecimal("106"),
            new BigDecimal("99"), new BigDecimal("52100000"))));
        assertThat(quoted.changePercent()).isEqualByComparingTo("5.00");
        assertThat(quoted.volumeText()).isEqualTo("52.1M");

        AssetDto unquoted = result.get(0).items().get(0);
        assertThat(unquoted.latestPrice()).isNull();
        assertThat(unquoted.change()).isNull();
        assertThat(unquoted.changePercent()).isNull();
        assertThat(unquoted.volumeText()).isNull();
        assertThat(unquoted.high()).isNull();
        assertThat(unquoted.low()).isNull();
    }

    @Test
    @DisplayName("AssetQuoteMapper：quote 為 null 時六個報價欄位皆 null，識別欄位照抄")
    void mapper_nullQuote_hasNullQuoteFields() {
        Asset bond = asset(9L, "US10Y", AssetType.BOND, "US");

        AssetDto dto = AssetQuoteMapper.toDto(bond, null);

        assertThat(dto.uuid()).isEqualTo(bond.uuid());
        assertThat(dto.symbol()).isEqualTo("US10Y");
        assertThat(dto.tradeable()).isTrue();
        assertThat(dto.latestPrice()).isNull();
        assertThat(dto.change()).isNull();
        assertThat(dto.changePercent()).isNull();
        assertThat(dto.volumeText()).isNull();
        assertThat(dto.high()).isNull();
        assertThat(dto.low()).isNull();
    }
}
