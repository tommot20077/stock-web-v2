package dowob.xyz.stockwebv2.trading.service;

import dowob.xyz.stockwebv2.common.api.PageResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.error.FieldValidationException;
import dowob.xyz.stockwebv2.common.model.AssetType;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetFacade;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetSummary;
import dowob.xyz.stockwebv2.infrastructure.marketdata.LatestMarketPrice;
import dowob.xyz.stockwebv2.infrastructure.marketdata.MarketDataFacade;
import dowob.xyz.stockwebv2.trading.api.CreateTradeRequest;
import dowob.xyz.stockwebv2.trading.api.HoldingDto;
import dowob.xyz.stockwebv2.trading.api.TradeDto;
import dowob.xyz.stockwebv2.trading.domain.Holding;
import dowob.xyz.stockwebv2.trading.domain.HoldingPosition;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
 * <p>後半段鎖的是 {@link TradingService#createTrade} 的冪等契約：同一把冪等鍵重送時
 * <strong>絕不可再次改動 holdings</strong>（judgment §5），同鍵不同 payload 必須回 409，
 * 而空白／過長的鍵要在任何 I/O 之前就被拒絕，且錯誤訊息一律不得回射鍵值本身。</p>
 *
 * @author Yuan
 * @version 1.1
 */
@DisplayName("交易清單查詢參數解析")
class TradingServiceTest {

    /** 刻意可辨識的冪等鍵：用來斷言任何錯誤訊息都沒有把它回射出去（T-04-03）。 */
    private static final String CANARY_KEY = "LEAK-CANARY-12345";

    /** 既有交易的金額：模擬 {@code NUMERIC(24,8)} 讀回值，scale 一律為 8。 */
    private static final BigDecimal STORED_QUANTITY = new BigDecimal("10.00000000");

    /** 重送請求的金額與時間：同一個意圖，但 scale 與偏移量都與讀回值不同。 */
    private static final BigDecimal SENT_QUANTITY = new BigDecimal("10");
    private static final BigDecimal SENT_PRICE = new BigDecimal("123.45");
    private static final BigDecimal SENT_FEE = new BigDecimal("1.5");
    private static final OffsetDateTime SENT_EXECUTED_AT = OffsetDateTime.parse("2026-01-10T08:00:00+08:00");

    private TradingRepository repository;
    private MarketDataFacade marketDataFacade;
    private AssetFacade assetFacade;
    private PortfolioCache portfolioCache;
    private TradingService service;

    @BeforeEach
    void setup() {
        repository = mock(TradingRepository.class);
        marketDataFacade = mock(MarketDataFacade.class);
        assetFacade = mock(AssetFacade.class);
        portfolioCache = mock(PortfolioCache.class);
        service = new TradingService(repository, assetFacade, marketDataFacade, portfolioCache, new TradingMapper());
        when(repository.listTransactions(any(TradeQuery.class)))
            .thenReturn(PageResponse.of(List.<TradeTransaction>of(), 0, 20, 0L));
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
        when(portfolioCache.readHolding(7L, 42L)).thenReturn(Optional.empty());
        when(marketDataFacade.findLatestPrice(42L)).thenReturn(Optional.of(
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
        when(portfolioCache.readHolding(7L, 42L)).thenReturn(Optional.empty());
        when(marketDataFacade.findLatestPrice(42L)).thenReturn(Optional.empty());

        HoldingDto dto = service.listHoldings(7L).getFirst();

        assertThat(dto.marketPrice()).isEqualByComparingTo("100");
        assertThat(dto.marketValue()).isEqualByComparingTo("1000");
        assertThat(dto.unrealizedPnl()).isEqualByComparingTo("0");
    }

    /**
     * 建立一筆測試用持倉。
     *
     * @param quantity 持有數量
     * @param avgCost  平均成本
     * @return 持倉位置
     */
    private HoldingPosition position(BigDecimal quantity, BigDecimal avgCost) {
        return new HoldingPosition(
            1L, 7L, 42L, UUID.fromString("11111111-1111-1111-1111-111111111111"),
            "AAPL", "Apple Inc.", quantity, avgCost, BigDecimal.ZERO, 0,
            OffsetDateTime.parse("2026-09-01T00:00:00+08:00"));
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

        assertThatThrownBy(() -> service.createTrade(7L, request, "key-1"))
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

        service.createTrade(7L, request, "key-1");

        ArgumentCaptor<TradeTransaction> captor = ArgumentCaptor.forClass(TradeTransaction.class);
        verify(repository).insertTransactionIfAbsent(captor.capture());
        assertThat(captor.getValue().executedAt()).isEqualTo(backdated);
    }

    @Test
    @DisplayName("建立交易的零 I/O 驗證先於 symbol 解析：非法 type 不會浪費一次資產查詢")
    void createTradeValidatesWhitelistBeforeAssetLookup() {
        CreateTradeRequest request = new CreateTradeRequest(
            "AAPL", "DIV", BigDecimal.ONE, BigDecimal.TEN, null, null, null);

        assertThatThrownBy(() -> service.createTrade(7L, request, "key-1"))
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

        assertThatThrownBy(() -> service.createTrade(7L, request, "key-1"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED);

        verifyNoInteractions(assetFacade);
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("同 key 重送相同意圖：回傳既有交易，holdings 與快取完全不被再次改動")
    void duplicateKeyReturnsExistingTradeWithoutTouchingHoldings() {
        /*
         * judgment §5 最直接的驗收：duplicate 進來時，帳本上那筆交易已經存在，
         * 持倉也早就套用過一次。任何一次 holdings 寫入都會讓成本基礎被重複計算，
         * 而 transactions 是 append-only 帳本 —— 改錯了就改不回來。
         */
        stubTradeableAsset();
        TradeTransaction existing = storedTransaction(STORED_QUANTITY);
        when(repository.findByIdempotencyKey(7L, "key-1")).thenReturn(Optional.of(existing));

        TradeDto dto = service.createTrade(7L, buyRequest(SENT_QUANTITY, "重試時順手改過的備註"), "key-1");

        assertThat(dto.id()).isEqualTo(existing.uuid().toString());
        verify(repository, never()).findHoldingForUpdate(any(), any());
        verify(repository, never()).insertHoldingIfAbsent(any());
        verify(repository, never()).updateHolding(any());
        verify(repository, never()).insertTransactionIfAbsent(any());
        // 快路徑沒有任何資料變動，快取自然不需要失效（DP-8）。
        verifyNoInteractions(portfolioCache);
    }

    @Test
    @DisplayName("同 key 送不同 payload 回 TRADE_IDEMPOTENCY_KEY_REUSED，且沒有任何寫入發生")
    void reusedKeyWithDifferentPayloadIsRejected() {
        stubTradeableAsset();
        when(repository.findByIdempotencyKey(7L, CANARY_KEY))
            .thenReturn(Optional.of(storedTransaction(STORED_QUANTITY)));

        CreateTradeRequest changed = buyRequest(new BigDecimal("11"), "第一次送出的備註");

        assertThatThrownBy(() -> service.createTrade(7L, changed, CANARY_KEY))
            .isInstanceOf(BusinessException.class)
            .hasMessageNotContaining(CANARY_KEY)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.TRADE_IDEMPOTENCY_KEY_REUSED);

        verify(repository, never()).findHoldingForUpdate(any(), any());
        verify(repository, never()).updateHolding(any());
        verify(repository, never()).insertTransactionIfAbsent(any());
        verifyNoInteractions(portfolioCache);
    }

    @Test
    @DisplayName("空白或 null 的冪等鍵在 service 層被拒（400），repository 零互動")
    void blankIdempotencyKeyIsRejectedBeforeAnyIo() {
        /*
         * `Idempotency-Key:`（空值）會通過 controller 的 required = true，
         * 所以空白檢查只能落在這裡。若放任它往下走，key 為空字串的請求會落在部分唯一索引
         * 的範圍內，第一個空字串就會把所有後續請求都擋成衝突。
         */
        CreateTradeRequest request = buyRequest(SENT_QUANTITY, null);

        assertThatThrownBy(() -> service.createTrade(7L, request, "   "))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThatThrownBy(() -> service.createTrade(7L, request, null))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED);

        verifyNoInteractions(repository);
        verifyNoInteractions(assetFacade);
    }

    @Test
    @DisplayName("超過 128 字元的冪等鍵在 service 層被拒（400），不落到 DB 變成資料完整性例外")
    void oversizedIdempotencyKeyIsRejectedBeforeAnyIo() {
        /*
         * 欄位是 VARCHAR(128)。只靠 DB 上限的話，過長的 key 會在 insert 時拋
         * DataIntegrityViolationException —— 那個例外與「冪等命中」長得一模一樣，
         * 會被誤判成衝突，也讓攻擊者能用超長字串免費製造 500（T-04-04）。
         */
        CreateTradeRequest request = buyRequest(SENT_QUANTITY, null);
        String oversized = CANARY_KEY + "x".repeat(129 - CANARY_KEY.length());
        assertThat(oversized).hasSize(129);

        assertThatThrownBy(() -> service.createTrade(7L, request, oversized))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED);

        verifyNoInteractions(repository);
        verifyNoInteractions(assetFacade);
    }

    @Test
    @DisplayName("空白與過長的冪等鍵都以 fields['Idempotency-Key'] 指名 header，前端才能與 body 欄位錯誤區分（D-16）")
    void idempotencyKeyFailuresNameTheHeaderInFields() {
        CreateTradeRequest request = buyRequest(SENT_QUANTITY, null);
        String oversized = "k".repeat(129);

        assertThatThrownBy(() -> service.createTrade(7L, request, "   "))
            .isInstanceOf(FieldValidationException.class)
            .extracting("fields")
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsKey("Idempotency-Key");

        assertThatThrownBy(() -> service.createTrade(7L, request, oversized))
            .isInstanceOf(FieldValidationException.class)
            .extracting("fields")
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsKey("Idempotency-Key");

        verifyNoInteractions(repository);
        verifyNoInteractions(assetFacade);
    }

    @Test
    @DisplayName("三種冪等鍵失敗情境的錯誤訊息都不含 key 本身（T-04-03）")
    void idempotencyKeyIsNeverEchoedInErrorMessages() {
        stubTradeableAsset();
        when(repository.findByIdempotencyKey(7L, CANARY_KEY))
            .thenReturn(Optional.of(storedTransaction(STORED_QUANTITY)));
        CreateTradeRequest request = buyRequest(SENT_QUANTITY, null);
        String oversized = CANARY_KEY + "x".repeat(129 - CANARY_KEY.length());

        assertThatThrownBy(() -> service.createTrade(7L, request, "   "))
            .hasMessageNotContaining(CANARY_KEY);
        assertThatThrownBy(() -> service.createTrade(7L, request, oversized))
            .hasMessageNotContaining(CANARY_KEY);
        assertThatThrownBy(() -> service.createTrade(7L, buyRequest(new BigDecimal("11"), null), CANARY_KEY))
            .hasMessageNotContaining(CANARY_KEY);
    }

    @Test
    @DisplayName("insert 回空且重讀落空的殘餘競態回 TRADE_CONFLICT，而不是 NPE 或 500")
    void concurrentInsertMissWithoutVisibleRowFallsBackToConflict() {
        stubTradeableAsset();
        when(repository.findByIdempotencyKey(7L, "key-1")).thenReturn(Optional.empty());
        when(repository.insertTransactionIfAbsent(any(TradeTransaction.class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createTrade(7L, buyRequest(SENT_QUANTITY, null), "key-1"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.TRADE_CONFLICT);

        verify(repository, never()).updateHolding(any());
        verifyNoInteractions(portfolioCache);
    }

    private void stubTradeableAsset() {
        when(assetFacade.findBySymbol("AAPL")).thenReturn(Optional.of(
            new AssetSummary(55L, "AAPL", "Apple Inc.", AssetType.STOCK, "US", true, true)));
    }

    /**
     * 建立一筆重送請求。數量與備註以外的欄位固定，讓斷言只受被測維度影響。
     *
     * @param quantity 本次送出的數量
     * @param note     本次送出的備註；D-06 明示它不納入 payload 比對
     * @return 交易建立請求
     */
    private CreateTradeRequest buyRequest(BigDecimal quantity, String note) {
        return new CreateTradeRequest("AAPL", "BUY", quantity, SENT_PRICE, SENT_FEE, note, SENT_EXECUTED_AT);
    }

    /**
     * 建立一筆「從 PostgreSQL 讀回」形狀的既有交易：金額 scale 補滿 8、成交時間為 UTC、備註與重送值不同。
     *
     * @param quantity 既有交易的數量
     * @return 既有交易
     */
    private TradeTransaction storedTransaction(BigDecimal quantity) {
        return new TradeTransaction(
            9L,
            UUID.fromString("00000000-0000-0000-0000-0000000000ff"),
            7L,
            55L,
            "AAPL",
            TradeType.BUY,
            quantity,
            new BigDecimal("123.45000000"),
            new BigDecimal("1.50000000"),
            "第一次送出的備註",
            OffsetDateTime.parse("2026-01-10T00:00:00Z"),
            OffsetDateTime.parse("2026-01-10T00:00:01Z"),
            "key-1"
        );
    }

    private TradeQuery captureQuery() {
        ArgumentCaptor<TradeQuery> captor = ArgumentCaptor.forClass(TradeQuery.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).listTransactions(captor.capture());
        return captor.getValue();
    }
}
