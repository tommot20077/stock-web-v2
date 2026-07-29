package dowob.xyz.stockwebv2.trading.service;

import dowob.xyz.stockwebv2.common.api.PageResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.model.AssetType;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetFacade;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetSummary;
import dowob.xyz.stockwebv2.trading.api.CreateTradeRequest;
import dowob.xyz.stockwebv2.trading.domain.Holding;
import dowob.xyz.stockwebv2.trading.domain.SortDirection;
import dowob.xyz.stockwebv2.trading.domain.TradeSortKey;
import dowob.xyz.stockwebv2.trading.domain.TradeTransaction;
import dowob.xyz.stockwebv2.trading.domain.TradeType;
import dowob.xyz.stockwebv2.trading.repository.TradeQuery;
import dowob.xyz.stockwebv2.trading.repository.TradingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link TradingService#listTrades} 的參數驗證與解析單元測試（D-05 / D-06 / D-07）。
 *
 * <p>service 是 HTTP 原始字串進入型別化查詢的唯一關卡：所有篩選與排序參數都必須在此
 * 被驗證成 {@link TradeQuery}，非法值不得抵達 repository。本測試以 mock repository
 * 攔截 {@link TradeQuery} 來鎖定這層契約。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("交易清單查詢參數解析")
class TradingServiceTest {

    private TradingRepository repository;
    private AssetFacade assetFacade;
    private PortfolioCache portfolioCache;
    private TradingService service;

    @BeforeEach
    void setup() {
        repository = mock(TradingRepository.class);
        assetFacade = mock(AssetFacade.class);
        portfolioCache = mock(PortfolioCache.class);
        service = new TradingService(repository, assetFacade, portfolioCache, new TradingMapper());
        when(repository.listTransactions(any(TradeQuery.class)))
            .thenReturn(PageResponse.of(List.<TradeTransaction>of(), 0, 20, 0L));
    }

    @Test
    @DisplayName("不帶篩選與排序參數時使用預設值：executedAt 降冪、無任何篩選")
    void defaultsToExecutedAtDescendingWithoutFilters() {
        service.listTrades(7L, null, null, null, null, null, null, 0, 20);

        TradeQuery query = captureQuery();
        assertThat(query.userId()).isEqualTo(7L);
        assertThat(query.assetId()).isNull();
        assertThat(query.type()).isNull();
        assertThat(query.dateFrom()).isNull();
        assertThat(query.dateTo()).isNull();
        assertThat(query.sortKey()).isEqualTo(TradeSortKey.EXECUTED_AT);
        assertThat(query.direction()).isEqualTo(SortDirection.DESC);
        assertThat(query.page()).isZero();
        assertThat(query.size()).isEqualTo(20);
    }

    @Test
    @DisplayName("page 與 size 的夾限行為維持既有規則：page 0..10000、size 1..100")
    void clampsPageAndSizeToExistingBounds() {
        service.listTrades(7L, null, null, null, null, null, null, -3, 0);
        assertThat(captureQuery().page()).isZero();
        assertThat(captureQuery().size()).isEqualTo(1);

        service.listTrades(7L, null, null, null, null, null, null, 99_999, 500);
        assertThat(captureQuery().page()).isEqualTo(10_000);
        assertThat(captureQuery().size()).isEqualTo(100);
    }

    @Test
    @DisplayName("sort=total 與 direction=asc 正確映射進查詢物件")
    void mapsWhitelistedSortAndDirection() {
        service.listTrades(7L, null, null, null, null, "total", "asc", 0, 20);

        TradeQuery query = captureQuery();
        assertThat(query.sortKey()).isEqualTo(TradeSortKey.TOTAL);
        assertThat(query.direction()).isEqualTo(SortDirection.ASC);
    }

    @Test
    @DisplayName("非法 sort 值丟 VALIDATION_FAILED 且 repository 完全未被呼叫")
    void unknownSortKeyIsRejectedBeforeRepository() {
        assertThatThrownBy(() -> service.listTrades(7L, null, null, null, null, "bogus", null, 0, 20))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED);

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("非法 direction 值丟 VALIDATION_FAILED 且 repository 完全未被呼叫")
    void unknownDirectionIsRejectedBeforeRepository() {
        assertThatThrownBy(() -> service.listTrades(7L, null, null, null, null, null, "up", 0, 20))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED);

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("type 大小寫不敏感解析為 TradeType")
    void lowercaseTypeIsParsed() {
        service.listTrades(7L, null, "buy", null, null, null, null, 0, 20);

        assertThat(captureQuery().type()).isEqualTo(TradeType.BUY);
    }

    @Test
    @DisplayName("空白 type 視為不篩選，不會被當成非法值")
    void blankTypeMeansNoFilter() {
        service.listTrades(7L, null, "  ", null, null, null, null, 0, 20);

        assertThat(captureQuery().type()).isNull();
    }

    @Test
    @DisplayName("不支援的 type 丟 TRADE_UNSUPPORTED_TYPE 且 repository 未被呼叫")
    void unsupportedTypeIsRejectedBeforeRepository() {
        assertThatThrownBy(() -> service.listTrades(7L, null, "DIV", null, null, null, null, 0, 20))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.TRADE_UNSUPPORTED_TYPE);

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("ISO-8601 日期區間解析為 OffsetDateTime 傳入查詢物件")
    void isoDateRangeIsParsed() {
        service.listTrades(7L, null, null, "2026-01-01T00:00:00+08:00", "2026-02-01T00:00:00Z", null, null, 0, 20);

        TradeQuery query = captureQuery();
        assertThat(query.dateFrom()).isEqualTo(OffsetDateTime.parse("2026-01-01T00:00:00+08:00"));
        assertThat(query.dateTo()).isEqualTo(OffsetDateTime.parse("2026-02-01T00:00:00Z"));
    }

    @Test
    @DisplayName("無法解析的 dateFrom 丟 VALIDATION_FAILED，訊息不回射原始輸入")
    void malformedDateFromIsRejected() {
        assertThatThrownBy(() -> service.listTrades(7L, null, null, "not-a-date", null, null, null, 0, 20))
            .isInstanceOf(BusinessException.class)
            .hasMessageNotContaining("not-a-date")
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED);

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("無法解析的 dateTo 丟 VALIDATION_FAILED")
    void malformedDateToIsRejected() {
        assertThatThrownBy(() -> service.listTrades(7L, null, null, null, "2026-13-45", null, null, 0, 20))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED);

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("symbol 正規化後解析為 assetId 帶入查詢物件")
    void symbolIsResolvedToAssetId() {
        when(assetFacade.findBySymbol("AAPL")).thenReturn(Optional.of(
            new AssetSummary(55L, "AAPL", "Apple Inc.", AssetType.STOCK, "US", true, true)));

        service.listTrades(7L, " aapl ", null, null, null, null, null, 0, 20);

        assertThat(captureQuery().assetId()).isEqualTo(55L);
    }

    @Test
    @DisplayName("sort=createdAt 映射為入帳時間排序，保留防竄改時間軸的查詢能力")
    void createdAtIsAWhitelistedSortKey() {
        service.listTrades(7L, null, null, null, null, "createdAt", null, 0, 20);

        assertThat(captureQuery().sortKey()).isEqualTo(TradeSortKey.CREATED_AT);
    }

    @Test
    @DisplayName("servlet 把 '+' 解碼成空白後，帶偏移量的 dateFrom 仍解析成原本的瞬間")
    void offsetDamagedByServletDecodingIsStillParsed() {
        /*
         * Tomcat 依 x-www-form-urlencoded 規則解碼 query string，未百分比編碼的 '+' 會變成空白，
         * 因此 dateFrom=2026-01-01T00:00:00+08:00 抵達 service 時就是下面這個字串。
         * 這一步無法由 MockMvc 重現（.param 不做該解碼），只能在此層鎖定。
         */
        service.listTrades(7L, null, null, "2026-01-01T00:00:00 08:00", null, null, null, 0, 20);

        assertThat(captureQuery().dateFrom()).isEqualTo(OffsetDateTime.parse("2026-01-01T00:00:00+08:00"));
    }

    @Test
    @DisplayName("未帶偏移量的日期時間以 UTC 為基準")
    void offsetLessTimestampIsInterpretedAsUtc() {
        service.listTrades(7L, null, null, "2026-01-01T09:30:00", null, null, null, 0, 20);

        assertThat(captureQuery().dateFrom()).isEqualTo(OffsetDateTime.parse("2026-01-01T09:30:00Z"));
    }

    @Test
    @DisplayName("純日期區間完整涵蓋 dateTo 當天：下界取當日 00:00、上界取隔日 00:00")
    void dateOnlyRangeCoversTheWholeUpperBoundDay() {
        service.listTrades(7L, null, null, "2026-01-01", "2026-01-31", null, null, 0, 20);

        TradeQuery query = captureQuery();
        assertThat(query.dateFrom()).isEqualTo(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        assertThat(query.dateTo()).isEqualTo(OffsetDateTime.parse("2026-02-01T00:00:00Z"));
    }

    @Test
    @DisplayName("顛倒的日期區間丟 VALIDATION_FAILED，而不是靜默回傳空頁")
    void invertedDateRangeIsRejected() {
        assertThatThrownBy(() -> service.listTrades(
            7L, null, null, "2026-03-01T00:00:00Z", "2026-01-01T00:00:00Z", null, null, 0, 20))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED);

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("dateFrom 等於 dateTo 是合法的退化區間，不視為顛倒")
    void equalBoundsAreAccepted() {
        service.listTrades(7L, null, null, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z", null, null, 0, 20);

        assertThat(captureQuery().dateFrom()).isEqualTo(captureQuery().dateTo());
    }

    @Test
    @DisplayName("零 I/O 的白名單驗證先於 symbol 解析，非法 sort 不會浪費一次資產查詢")
    void whitelistValidationRunsBeforeAssetLookup() {
        assertThatThrownBy(() -> service.listTrades(7L, "NOPE", null, null, null, "bogus", null, 0, 20))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED);

        verifyNoInteractions(assetFacade);
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("建立交易時明顯超前的 executedAt 被拒，且不觸及 repository")
    void futureExecutedAtIsRejectedOnCreate() {
        when(assetFacade.findBySymbol("AAPL")).thenReturn(Optional.of(
            new AssetSummary(55L, "AAPL", "Apple Inc.", AssetType.STOCK, "US", true, true)));

        CreateTradeRequest request = new CreateTradeRequest(
            "AAPL", "BUY", BigDecimal.ONE, BigDecimal.TEN, null, null, OffsetDateTime.now().plusDays(1));

        assertThatThrownBy(() -> service.createTrade(7L, request))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED);

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("補登舊交易仍被接受：executedAt 刻意不設下界")
    void backdatedExecutedAtIsAccepted() {
        when(assetFacade.findBySymbol("AAPL")).thenReturn(Optional.of(
            new AssetSummary(55L, "AAPL", "Apple Inc.", AssetType.STOCK, "US", true, true)));
        when(repository.findHoldingForUpdate(7L, 55L)).thenReturn(Optional.empty());
        when(repository.insertHoldingIfAbsent(any(Holding.class)))
            .thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));
        when(repository.insertTransactionIfAbsent(any(TradeTransaction.class)))
            .thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));

        OffsetDateTime backdated = OffsetDateTime.parse("2020-01-01T00:00:00Z");
        CreateTradeRequest request = new CreateTradeRequest(
            "AAPL", "BUY", BigDecimal.ONE, BigDecimal.TEN, null, null, backdated);

        service.createTrade(7L, request);

        ArgumentCaptor<TradeTransaction> captor = ArgumentCaptor.forClass(TradeTransaction.class);
        verify(repository).insertTransactionIfAbsent(captor.capture());
        assertThat(captor.getValue().executedAt()).isEqualTo(backdated);
    }

    @Test
    @DisplayName("建立交易的零 I/O 驗證先於 symbol 解析：非法 type 不會浪費一次資產查詢")
    void createTradeValidatesWhitelistBeforeAssetLookup() {
        CreateTradeRequest request = new CreateTradeRequest(
            "AAPL", "DIV", BigDecimal.ONE, BigDecimal.TEN, null, null, null);

        assertThatThrownBy(() -> service.createTrade(7L, request))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.TRADE_UNSUPPORTED_TYPE);

        verifyNoInteractions(assetFacade);
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("建立交易的未來 executedAt 檢查同樣先於 symbol 解析")
    void createTradeRejectsFutureExecutedAtBeforeAssetLookup() {
        CreateTradeRequest request = new CreateTradeRequest(
            "AAPL", "BUY", BigDecimal.ONE, BigDecimal.TEN, null, null, OffsetDateTime.now().plusDays(1));

        assertThatThrownBy(() -> service.createTrade(7L, request))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED);

        verifyNoInteractions(assetFacade);
        verifyNoInteractions(repository);
    }

    private TradeQuery captureQuery() {
        ArgumentCaptor<TradeQuery> captor = ArgumentCaptor.forClass(TradeQuery.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).listTransactions(captor.capture());
        return captor.getValue();
    }
}
