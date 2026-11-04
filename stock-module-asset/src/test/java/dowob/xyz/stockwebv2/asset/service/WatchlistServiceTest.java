package dowob.xyz.stockwebv2.asset.service;

import dowob.xyz.stockwebv2.asset.api.AssetDto;
import dowob.xyz.stockwebv2.asset.api.WatchlistDto;
import dowob.xyz.stockwebv2.asset.domain.Asset;
import dowob.xyz.stockwebv2.asset.domain.Watchlist;
import dowob.xyz.stockwebv2.asset.repository.WatchlistRepository;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link WatchlistService} 單元測試：清單列出、預設清單、報價批次、建立 / 改名 / 刪除與上限（WATCH-01；D-14、D-15、D-17、D-19）。
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

    // ── 建立 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("建立：名稱 trim 後寫入；順序為 ensureDefault → 鎖全部清單 → 鎖後重新計數 → insert")
    void create_trimsNameAndLocksBeforeCounting() {
        Watchlist created = watchlist(5L, "Tech");
        when(repository.countByUser(USER_ID)).thenReturn(3);
        when(repository.insertIfNameFree(USER_ID, "Tech")).thenReturn(Optional.of(created));

        WatchlistDto dto = service.create(USER_ID, "  Tech  ");

        InOrder order = inOrder(repository);
        order.verify(repository).ensureDefault(USER_ID);
        order.verify(repository).lockAllForUser(USER_ID);
        order.verify(repository).countByUser(USER_ID);
        order.verify(repository).insertIfNameFree(USER_ID, "Tech");
        assertThat(dto.id()).isEqualTo(created.uuid());
        assertThat(dto.name()).isEqualTo("Tech");
        assertThat(dto.items()).isEmpty();
        verify(marketData, never()).findDailyQuotes(anyMap());
    }

    @Test
    @DisplayName("建立：已有 20 個清單 → 400 VALIDATION_FAILED fields.watchlists，不 insert")
    void create_atLimit_rejectedWithWatchlistsField() {
        when(repository.countByUser(USER_ID)).thenReturn(WatchlistService.MAX_WATCHLISTS);

        assertThatThrownBy(() -> service.create(USER_ID, "第21個"))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> {
                BusinessException exception = (BusinessException) error;
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                assertThat(exception.fields()).containsOnlyKeys("watchlists");
                assertThat(exception.getMessage()).doesNotContain("第21個");
            });
        verify(repository).lockAllForUser(USER_ID);
        verify(repository, never()).insertIfNameFree(anyLong(), anyString());
    }

    @Test
    @DisplayName("建立：同使用者重名 → 409 DUPLICATE_RESOURCE fields.name")
    void create_duplicateName_conflict() {
        when(repository.countByUser(USER_ID)).thenReturn(2);
        when(repository.insertIfNameFree(USER_ID, "Tech")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(USER_ID, "Tech"))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> {
                BusinessException exception = (BusinessException) error;
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
                assertThat(exception.fields()).containsOnlyKeys("name");
            });
    }

    @Test
    @DisplayName("建立：名稱為空、全空白（含全形空白）或 trim 後超過 30 字 → 400 fields.name，不碰資料庫")
    void create_invalidName_rejectedBeforeTouchingDatabase() {
        for (String invalid : new String[] {null, "", "   ", "\u3000\u3000", "a".repeat(31), "  " + "字".repeat(31) + "  "}) {
            assertThatThrownBy(() -> service.create(USER_ID, invalid))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException exception = (BusinessException) error;
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(exception.fields()).containsOnlyKeys("name");
                });
        }
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("建立：30 字（以 code point 計，含補充平面字元）與前後空白合計超過 30 仍可建立")
    void create_thirtyCodePoints_accepted() {
        String emoji = "\uD83D\uDE00".repeat(30);
        String padded = "  " + "字".repeat(30) + "  ";
        when(repository.countByUser(USER_ID)).thenReturn(1);
        when(repository.insertIfNameFree(anyLong(), anyString()))
            .thenAnswer(invocation -> Optional.of(watchlist(6L, invocation.getArgument(1))));

        assertThat(service.create(USER_ID, emoji).name()).isEqualTo(emoji);
        assertThat(service.create(USER_ID, padded).name()).isEqualTo("字".repeat(30));
    }

    // ── 改名 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("改名：查無（不存在或屬於他人）→ 404 WATCHLIST_NOT_FOUND，不更新")
    void rename_notFound() {
        UUID uuid = UUID.randomUUID();
        when(repository.findByUuidForUpdate(USER_ID, uuid)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rename(USER_ID, uuid, "新名稱"))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).errorCode())
                .isEqualTo(ErrorCode.WATCHLIST_NOT_FOUND));
        verify(repository, never()).rename(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("改名：與另一份清單重名 → 409 DUPLICATE_RESOURCE fields.name")
    void rename_duplicate() {
        Watchlist existing = watchlist(3L, "舊名");
        when(repository.findByUuidForUpdate(USER_ID, existing.uuid())).thenReturn(Optional.of(existing));
        when(repository.rename(3L, USER_ID, "Tech")).thenReturn(false);

        assertThatThrownBy(() -> service.rename(USER_ID, existing.uuid(), " Tech "))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> {
                BusinessException exception = (BusinessException) error;
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
                assertThat(exception.fields()).containsOnlyKeys("name");
            });
    }

    @Test
    @DisplayName("改名：成功回新名稱與既有項目（含報價）")
    void rename_success_returnsNewNameWithItems() {
        Watchlist existing = watchlist(3L, "舊名");
        Asset aapl = asset(10L, "AAPL", AssetType.STOCK, "US");
        when(repository.findByUuidForUpdate(USER_ID, existing.uuid())).thenReturn(Optional.of(existing));
        when(repository.rename(3L, USER_ID, "新名稱")).thenReturn(true);
        when(repository.itemsByWatchlist(List.of(3L))).thenReturn(Map.of(3L, List.of(aapl)));
        when(marketData.findDailyQuotes(any())).thenReturn(Map.of());

        WatchlistDto dto = service.rename(USER_ID, existing.uuid(), "新名稱");

        assertThat(dto.id()).isEqualTo(existing.uuid());
        assertThat(dto.name()).isEqualTo("新名稱");
        assertThat(dto.items()).extracting(AssetDto::symbol).containsExactly("AAPL");
    }

    @Test
    @DisplayName("改名：名稱非法 → 400 fields.name，不查清單")
    void rename_invalidName() {
        assertThatThrownBy(() -> service.rename(USER_ID, UUID.randomUUID(), " "))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).fields()).containsOnlyKeys("name"));
        verifyNoInteractions(repository);
    }

    // ── 刪除 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("刪除：查無 → 404 WATCHLIST_NOT_FOUND，不刪除")
    void delete_notFound() {
        UUID uuid = UUID.randomUUID();
        when(repository.findByUuidForUpdate(USER_ID, uuid)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(USER_ID, uuid))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).errorCode())
                .isEqualTo(ErrorCode.WATCHLIST_NOT_FOUND));
        verify(repository, never()).delete(anyLong(), anyLong());
    }

    @Test
    @DisplayName("刪除：成功時以 (id, userId) 刪除")
    void delete_success() {
        Watchlist existing = watchlist(4L, "暫存");
        when(repository.findByUuidForUpdate(USER_ID, existing.uuid())).thenReturn(Optional.of(existing));

        service.delete(USER_ID, existing.uuid());

        verify(repository).delete(4L, USER_ID);
    }

    // ── 加入項目 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("加入：先鎖清單列、鎖後另起 countItems 計數、再 insertItemAtEnd；symbol strip 後解析；回 DTO 尾端為 AAPL")
    void addItem_locksThenCountsThenAppends() {
        Watchlist list = watchlist(1L, "我的自選");
        Asset tsla = asset(20L, "TSLA", AssetType.STOCK, "US");
        Asset msft = asset(21L, "MSFT", AssetType.STOCK, "US");
        Asset aapl = asset(10L, "AAPL", AssetType.STOCK, "US");
        when(repository.findByUuidForUpdate(USER_ID, list.uuid())).thenReturn(Optional.of(list));
        when(repository.findActiveAssetIdBySymbol("AAPL")).thenReturn(Optional.of(10L));
        when(repository.containsItem(1L, 10L)).thenReturn(false);
        when(repository.countItems(1L)).thenReturn(2);
        when(repository.insertItemAtEnd(1L, 10L)).thenReturn(true);
        when(repository.itemsByWatchlist(List.of(1L))).thenReturn(Map.of(1L, List.of(tsla, msft, aapl)));
        when(marketData.findDailyQuotes(any())).thenReturn(Map.of());

        WatchlistDto dto = service.addItem(USER_ID, list.uuid(), "  AAPL ");

        InOrder order = inOrder(repository);
        order.verify(repository).findByUuidForUpdate(USER_ID, list.uuid());
        order.verify(repository).countItems(1L);
        order.verify(repository).insertItemAtEnd(1L, 10L);
        order.verify(repository).itemsByWatchlist(List.of(1L));
        assertThat(dto).isNotNull();
        assertThat(dto.id()).isEqualTo(list.uuid());
        assertThat(dto.items()).extracting(AssetDto::symbol).containsExactly("TSLA", "MSFT", "AAPL");
        verify(marketData, times(1)).findDailyQuotes(anyMap());
    }

    @Test
    @DisplayName("加入：已在清單中 → 原樣回傳，不計數、不 insert、不報錯（清單已滿 100 也一樣）")
    void addItem_alreadyPresent_returnsUnchanged() {
        Watchlist list = watchlist(1L, "我的自選");
        Asset aapl = asset(10L, "AAPL", AssetType.STOCK, "US");
        when(repository.findByUuidForUpdate(USER_ID, list.uuid())).thenReturn(Optional.of(list));
        when(repository.findActiveAssetIdBySymbol("AAPL")).thenReturn(Optional.of(10L));
        when(repository.containsItem(1L, 10L)).thenReturn(true);
        when(repository.countItems(1L)).thenReturn(WatchlistService.MAX_ITEMS);
        when(repository.itemsByWatchlist(List.of(1L))).thenReturn(Map.of(1L, List.of(aapl)));
        when(marketData.findDailyQuotes(any())).thenReturn(Map.of());

        WatchlistDto dto = service.addItem(USER_ID, list.uuid(), "AAPL");

        verify(repository, never()).insertItemAtEnd(anyLong(), anyLong());
        assertThat(dto).isNotNull();
        assertThat(dto.items()).extracting(AssetDto::symbol).containsExactly("AAPL");
    }

    @Test
    @DisplayName("加入：active 但不可交易的 USD/TWD 可加入（D-18 不要求 tradeable），無行情時報價欄位為 null")
    void addItem_nonTradeableFx_accepted() {
        Watchlist list = watchlist(1L, "我的自選");
        Asset usdTwd = new Asset(12L, UUID.randomUUID(), "USD/TWD", "美元/新台幣", AssetType.FX, "FX",
            CurrencyCode.TWD, null, false, true);
        when(repository.findByUuidForUpdate(USER_ID, list.uuid())).thenReturn(Optional.of(list));
        when(repository.findActiveAssetIdBySymbol("USD/TWD")).thenReturn(Optional.of(12L));
        when(repository.countItems(1L)).thenReturn(0);
        when(repository.insertItemAtEnd(1L, 12L)).thenReturn(true);
        when(repository.itemsByWatchlist(List.of(1L))).thenReturn(Map.of(1L, List.of(usdTwd)));
        when(marketData.findDailyQuotes(any())).thenReturn(Map.of());

        WatchlistDto dto = service.addItem(USER_ID, list.uuid(), "USD/TWD");

        verify(repository).insertItemAtEnd(1L, 12L);
        assertThat(dto).isNotNull();
        AssetDto item = dto.items().get(0);
        assertThat(item.symbol()).isEqualTo("USD/TWD");
        assertThat(item.tradeable()).isFalse();
        assertThat(item.latestPrice()).isNull();
    }

    @Test
    @DisplayName("加入：資產不存在或 inactive → 404 ASSET_NOT_FOUND fields.symbol，訊息不回射 symbol，不計數、不 insert")
    void addItem_unknownOrInactiveSymbol_assetNotFound() {
        Watchlist list = watchlist(1L, "我的自選");
        when(repository.findByUuidForUpdate(USER_ID, list.uuid())).thenReturn(Optional.of(list));
        when(repository.findActiveAssetIdBySymbol("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addItem(USER_ID, list.uuid(), "NOPE"))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> {
                BusinessException exception = (BusinessException) error;
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.ASSET_NOT_FOUND);
                assertThat(exception.fields()).containsOnlyKeys("symbol");
                assertThat(exception.getMessage()).doesNotContain("NOPE");
            });
        verify(repository, never()).countItems(anyLong());
        verify(repository, never()).insertItemAtEnd(anyLong(), anyLong());
    }

    @Test
    @DisplayName("加入：他人或不存在的清單 → 404 WATCHLIST_NOT_FOUND，不解析 symbol")
    void addItem_otherUsersList_watchlistNotFound() {
        UUID uuid = UUID.randomUUID();
        when(repository.findByUuidForUpdate(USER_ID, uuid)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addItem(USER_ID, uuid, "AAPL"))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> {
                BusinessException exception = (BusinessException) error;
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.WATCHLIST_NOT_FOUND);
                assertThat(exception.getMessage()).doesNotContain(uuid.toString());
            });
        verify(repository, never()).findActiveAssetIdBySymbol(anyString());
        verify(repository, never()).insertItemAtEnd(anyLong(), anyLong());
    }

    @Test
    @DisplayName("加入：已有 99 檔 → 第 100 檔成功；已有 100 檔 → 第 101 檔 400 VALIDATION_FAILED fields.items，不 insert")
    void addItem_hundredthAcceptedHundredFirstRejected() {
        Watchlist list = watchlist(1L, "我的自選");
        when(repository.findByUuidForUpdate(USER_ID, list.uuid())).thenReturn(Optional.of(list));
        when(repository.findActiveAssetIdBySymbol("AAPL")).thenReturn(Optional.of(10L));
        when(repository.itemsByWatchlist(List.of(1L))).thenReturn(Map.of());
        when(repository.countItems(1L)).thenReturn(WatchlistService.MAX_ITEMS - 1);
        when(repository.insertItemAtEnd(1L, 10L)).thenReturn(true);

        assertThat(service.addItem(USER_ID, list.uuid(), "AAPL")).isNotNull();
        verify(repository).insertItemAtEnd(1L, 10L);

        when(repository.countItems(1L)).thenReturn(WatchlistService.MAX_ITEMS);
        assertThatThrownBy(() -> service.addItem(USER_ID, list.uuid(), "AAPL"))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> {
                BusinessException exception = (BusinessException) error;
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                assertThat(exception.fields()).containsOnlyKeys("items");
            });
        verify(repository, times(1)).insertItemAtEnd(1L, 10L);
    }

    @Test
    @DisplayName("加入：上限計數只採用鎖後的 countItems（不依賴鎖查詢的結果，避免 READ COMMITTED 快照過時而超過 100）")
    void addItem_capUsesCountTakenAfterLock() {
        Watchlist list = watchlist(1L, "我的自選");
        when(repository.findByUuidForUpdate(USER_ID, list.uuid())).thenReturn(Optional.of(list));
        when(repository.findActiveAssetIdBySymbol("AAPL")).thenReturn(Optional.of(10L));
        when(repository.countItems(1L)).thenReturn(WatchlistService.MAX_ITEMS);

        assertThatThrownBy(() -> service.addItem(USER_ID, list.uuid(), "AAPL"))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).fields()).containsOnlyKeys("items"));

        InOrder order = inOrder(repository);
        order.verify(repository).findByUuidForUpdate(USER_ID, list.uuid());
        order.verify(repository).countItems(1L);
        verify(repository, never()).insertItemAtEnd(anyLong(), anyLong());
    }
}
