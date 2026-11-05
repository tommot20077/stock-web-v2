package dowob.xyz.stockwebv2.trading.service;

import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.model.AssetType;
import dowob.xyz.stockwebv2.common.model.CurrencyCode;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetFacade;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetProfile;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetSummary;
import dowob.xyz.stockwebv2.trading.api.CreateShareAdjustmentRequest;
import dowob.xyz.stockwebv2.trading.api.ShareAdjustmentDto;
import dowob.xyz.stockwebv2.trading.domain.Holding;
import dowob.xyz.stockwebv2.trading.domain.ShareAdjustmentEntry;
import dowob.xyz.stockwebv2.trading.domain.ShareAdjustmentType;
import dowob.xyz.stockwebv2.trading.repository.ShareAdjustmentRepository;
import dowob.xyz.stockwebv2.trading.repository.TradingRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ShareAdjustmentService#create} 的單元測試（ADJ-01 tracer）。
 *
 * <p>鎖住寫入主路徑的每一個決策點：配股的 ΔQ = 實領股數（D-05）、拆股的 ΔQ 以<strong>除權日起點持股</strong>
 * 換算而非目前持股（D-08，Pitfall 1）、除權日起點或目前無持倉回 409（D-06 / D-09）、生效時間超前或早於
 * 最晚未沖銷調整回 400、形狀驗證、冪等快路徑與 insert 落空重讀都不動持倉也不失效快取，以及「先鎖持倉再計算」的順序。
 * 04.2-08 補上沖銷流程：只能沖銷該標的最晚一筆未沖銷調整（同一除權日依記錄順序倒著沖）、撤銷已記錄的 ΔQ、
 * 冪等優先於「已沖銷」。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("股數調整寫入服務")
class ShareAdjustmentServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long AAPL_ID = 1L;
    private static final Long HOLDING_ID = 55L;

    /**
     * 固定時鐘：紐約 2026-10-30 11:00（EDT），因此 AAPL 的「今天」是 2026-10-30。
     */
    private static final Instant NOW = Instant.parse("2026-10-30T15:00:00Z");

    private static final LocalDate TODAY = LocalDate.parse("2026-10-30");

    /**
     * 美股 2026-10-30 交易日起點 = 紐約午夜（EDT，UTC−4）。
     */
    private static final OffsetDateTime TODAY_START_US = OffsetDateTime.parse("2026-10-30T04:00:00Z");

    private static final LocalDate YESTERDAY = LocalDate.parse("2026-10-29");

    /**
     * 美股 2026-10-29 交易日起點（EDT，UTC−4）。
     */
    private static final OffsetDateTime YESTERDAY_START_US = OffsetDateTime.parse("2026-10-29T04:00:00Z");

    private ShareAdjustmentRepository repository;
    private TradingRepository tradingRepository;
    private AssetFacade assetFacade;
    private PortfolioCache portfolioCache;
    private ShareAdjustmentService service;

    @BeforeEach
    void setup() {
        repository = mock(ShareAdjustmentRepository.class);
        tradingRepository = mock(TradingRepository.class);
        assetFacade = mock(AssetFacade.class);
        portfolioCache = mock(PortfolioCache.class);
        service = new ShareAdjustmentService(repository, tradingRepository, assetFacade, portfolioCache,
            Clock.fixed(NOW, ZoneOffset.UTC));
        when(assetFacade.findBySymbol("AAPL")).thenReturn(Optional.of(
            new AssetSummary(AAPL_ID, "AAPL", "Apple Inc.", AssetType.STOCK, "US", true, true)));
        when(assetFacade.findProfiles(Set.of(AAPL_ID))).thenReturn(Map.of(AAPL_ID,
            new AssetProfile(AAPL_ID, "AAPL", AssetType.STOCK, "US", CurrencyCode.USD, "Tech")));
        when(repository.findByIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        when(repository.findLatestActive(USER_ID, AAPL_ID)).thenReturn(Optional.empty());
        when(repository.insertIfAbsent(any(ShareAdjustmentEntry.class))).thenAnswer(invocation ->
            Optional.of(persisted(invocation.getArgument(0), 101L)));
        when(tradingRepository.updateHolding(any(Holding.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("配股 50 股：ΔQ = 50、持倉 1000 @100 → 1050 @95.23809524；先鎖持倉再算、insert 後更新持倉、失效一次")
    void stockDividendAddsQuantityAndDilutesAverageCost() {
        holding("1000", "100");
        when(repository.quantityAsOf(USER_ID, AAPL_ID, TODAY_START_US)).thenReturn(new BigDecimal("1000"));

        ShareAdjustmentDto dto = service.create(USER_ID,
            new CreateShareAdjustmentRequest(" aapl ", "STOCK_DIVIDEND", new BigDecimal("50"), null, null, TODAY,
                " 配股 ", null),
            "k1");

        ArgumentCaptor<ShareAdjustmentEntry> written = ArgumentCaptor.forClass(ShareAdjustmentEntry.class);
        verify(repository).insertIfAbsent(written.capture());
        ShareAdjustmentEntry entry = written.getValue();
        assertThat(entry.userId()).isEqualTo(USER_ID);
        assertThat(entry.assetId()).isEqualTo(AAPL_ID);
        assertThat(entry.type()).isEqualTo(ShareAdjustmentType.STOCK_DIVIDEND);
        assertThat(entry.quantityDelta()).isEqualByComparingTo("50");
        assertThat(entry.ratioNew()).isNull();
        assertThat(entry.ratioOld()).isNull();
        assertThat(entry.effectiveDate()).isEqualTo(TODAY);
        assertThat(entry.effectiveAt()).isEqualTo(TODAY_START_US);
        assertThat(entry.note()).isEqualTo("配股");
        assertThat(entry.idempotencyKey()).isEqualTo("k1");
        assertThat(entry.reversalOfId()).isNull();
        assertThat(entry.uuid()).isNotNull();

        ArgumentCaptor<Holding> updated = ArgumentCaptor.forClass(Holding.class);
        verify(tradingRepository).updateHolding(updated.capture());
        assertThat(updated.getValue().id()).isEqualTo(HOLDING_ID);
        assertThat(updated.getValue().version()).isEqualTo(3);
        assertThat(updated.getValue().totalQuantity()).isEqualByComparingTo("1050");
        assertThat(updated.getValue().avgCost()).isEqualByComparingTo("95.23809524");
        verify(portfolioCache, times(1)).invalidateAfterTrade(USER_ID, AAPL_ID);

        InOrder order = inOrder(tradingRepository, repository);
        order.verify(tradingRepository).findHoldingForUpdate(USER_ID, AAPL_ID);
        order.verify(repository).quantityAsOf(USER_ID, AAPL_ID, TODAY_START_US);
        order.verify(repository).insertIfAbsent(any(ShareAdjustmentEntry.class));
        order.verify(tradingRepository).updateHolding(any(Holding.class));

        assertThat(dto.id()).isEqualTo(entry.uuid().toString());
        assertThat(dto.symbol()).isEqualTo("AAPL");
        assertThat(dto.currency()).isEqualTo("USD");
        assertThat(dto.type()).isEqualTo("STOCK_DIVIDEND");
        assertThat(dto.quantityDelta()).isEqualByComparingTo("50");
        assertThat(dto.ratioNew()).isNull();
        assertThat(dto.ratioOld()).isNull();
        assertThat(dto.effectiveDate()).isEqualTo(TODAY);
        assertThat(dto.note()).isEqualTo("配股");
        assertThat(dto.reversalOfId()).isNull();
        assertThat(dto.reversed()).isFalse();
    }

    @Test
    @DisplayName("拆股 4:1 依除權日持股：目前 150 股、除權日起點 100 股 → ΔQ = +300、持倉 450（不是 150 × 4）")
    void splitUsesQuantityAsOfEffectiveDateNotCurrentHolding() {
        holding("150", "400");
        when(repository.quantityAsOf(USER_ID, AAPL_ID, TODAY_START_US)).thenReturn(new BigDecimal("100"));

        ShareAdjustmentDto dto = service.create(USER_ID,
            new CreateShareAdjustmentRequest("AAPL", "split", null, 4, 1, TODAY, null, null), "k-split");

        ArgumentCaptor<ShareAdjustmentEntry> written = ArgumentCaptor.forClass(ShareAdjustmentEntry.class);
        verify(repository).insertIfAbsent(written.capture());
        assertThat(written.getValue().type()).isEqualTo(ShareAdjustmentType.SPLIT);
        assertThat(written.getValue().ratioNew()).isEqualTo(4);
        assertThat(written.getValue().ratioOld()).isEqualTo(1);
        assertThat(written.getValue().quantityDelta()).isEqualByComparingTo("300");
        assertThat(written.getValue().note()).isNull();

        ArgumentCaptor<Holding> updated = ArgumentCaptor.forClass(Holding.class);
        verify(tradingRepository).updateHolding(updated.capture());
        assertThat(updated.getValue().totalQuantity()).isEqualByComparingTo("450");
        assertThat(dto.type()).isEqualTo("SPLIT");
        assertThat(dto.ratioNew()).isEqualTo(4);
        assertThat(dto.ratioOld()).isEqualTo(1);
    }

    @Test
    @DisplayName("合股 1:10：除權日起點 105 股 → ΔQ = −94.5、持倉 10.5")
    void reverseSplitProducesNegativeDelta() {
        holding("105", "10");
        when(repository.quantityAsOf(USER_ID, AAPL_ID, TODAY_START_US)).thenReturn(new BigDecimal("105"));

        service.create(USER_ID, new CreateShareAdjustmentRequest("AAPL", "SPLIT", null, 1, 10, TODAY, null, null), "k-rs");

        ArgumentCaptor<ShareAdjustmentEntry> written = ArgumentCaptor.forClass(ShareAdjustmentEntry.class);
        verify(repository).insertIfAbsent(written.capture());
        assertThat(written.getValue().quantityDelta()).isEqualByComparingTo("-94.5");
        ArgumentCaptor<Holding> updated = ArgumentCaptor.forClass(Holding.class);
        verify(tradingRepository).updateHolding(updated.capture());
        assertThat(updated.getValue().totalQuantity()).isEqualByComparingTo("10.5");
    }

    @Test
    @DisplayName("除權日起點持股 ≤ 0 → 409 TRADE_INSUFFICIENT_HOLDING（fields.symbol），不寫入也不更新持倉")
    void noHoldingAsOfEffectiveDateIsRejected() {
        holding("50", "100");
        when(repository.quantityAsOf(USER_ID, AAPL_ID, TODAY_START_US)).thenReturn(BigDecimal.ZERO);

        assertInsufficientHolding(() -> service.create(USER_ID,
            new CreateShareAdjustmentRequest("AAPL", "STOCK_DIVIDEND", new BigDecimal("5"), null, null, TODAY, null, null),
            "k-none-asof"));
        verifyNothingWritten();
    }

    @Test
    @DisplayName("目前無持倉列 → 409 TRADE_INSUFFICIENT_HOLDING（fields.symbol），不寫入")
    void missingCurrentHoldingIsRejected() {
        when(tradingRepository.findHoldingForUpdate(USER_ID, AAPL_ID)).thenReturn(Optional.empty());
        when(repository.quantityAsOf(USER_ID, AAPL_ID, TODAY_START_US)).thenReturn(new BigDecimal("100"));

        assertInsufficientHolding(() -> service.create(USER_ID,
            new CreateShareAdjustmentRequest("AAPL", "SPLIT", null, 2, 1, TODAY, null, null), "k-none-now"));
        verifyNothingWritten();
    }

    @Test
    @DisplayName("目前持倉已賣光（0 股）→ 409 TRADE_INSUFFICIENT_HOLDING（fields.symbol），不寫入")
    void zeroCurrentHoldingIsRejected() {
        holding("0", "0");
        when(repository.quantityAsOf(USER_ID, AAPL_ID, TODAY_START_US)).thenReturn(new BigDecimal("100"));

        assertInsufficientHolding(() -> service.create(USER_ID,
            new CreateShareAdjustmentRequest("AAPL", "STOCK_DIVIDEND", new BigDecimal("5"), null, null, TODAY, null, null),
            "k-zero-now"));
        verifyNothingWritten();
    }

    @Test
    @DisplayName("除權日換算後超前現在 5 分鐘以上 → 400 fields.effectiveDate，不碰帳本")
    void futureEffectiveDateIsRejected() {
        assertThatThrownBy(() -> service.create(USER_ID,
            new CreateShareAdjustmentRequest("AAPL", "STOCK_DIVIDEND", new BigDecimal("5"), null, null,
                TODAY.plusDays(1), null, null), "k-future"))
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                assertThat(ex.fields()).containsKey("effectiveDate");
            });
        verifyNoInteractions(repository, tradingRepository, portfolioCache);
    }

    @Test
    @DisplayName("早於該標的最晚未沖銷調整的生效時間 → 400 fields.effectiveDate，不寫入")
    void effectiveDateEarlierThanLatestActiveAdjustmentIsRejected() {
        holding("100", "100");
        when(repository.findLatestActive(USER_ID, AAPL_ID)).thenReturn(Optional.of(
            existing(90L, TODAY, TODAY_START_US)));

        assertThatThrownBy(() -> service.create(USER_ID,
            new CreateShareAdjustmentRequest("AAPL", "STOCK_DIVIDEND", new BigDecimal("5"), null, null,
                TODAY.minusDays(1), null, null), "k-earlier"))
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                assertThat(ex.fields()).containsKey("effectiveDate");
            });
        verifyNothingWritten();
    }

    @Test
    @DisplayName("與最晚未沖銷調整同一生效時間 → 允許（規則只拒絕「早於」；同日依記錄順序疊加）")
    void effectiveDateEqualToLatestActiveAdjustmentIsAllowed() {
        holding("100", "100");
        when(repository.findLatestActive(USER_ID, AAPL_ID)).thenReturn(Optional.of(
            existing(90L, TODAY, TODAY_START_US)));
        when(repository.quantityAsOf(USER_ID, AAPL_ID, TODAY_START_US)).thenReturn(new BigDecimal("100"));

        service.create(USER_ID,
            new CreateShareAdjustmentRequest("AAPL", "STOCK_DIVIDEND", new BigDecimal("5"), null, null, TODAY, null, null),
            "k-same-day");

        verify(repository).insertIfAbsent(any(ShareAdjustmentEntry.class));
    }

    @Test
    @DisplayName("100 股、同一除權日記兩次 2:1 拆股 → 400 股：第二筆的 Q_asOf 含同日先記錄的第一筆（Yuan 2026-11-04 裁決）")
    void sameDaySplitsStackInRecordOrder() {
        holding("100", "100");
        /* 第一筆：除權日起點持股 100（沒有同日先記錄的調整）→ ΔQ +100。 */
        when(repository.quantityAsOf(USER_ID, AAPL_ID, TODAY_START_US)).thenReturn(new BigDecimal("100"));
        service.create(USER_ID, new CreateShareAdjustmentRequest("AAPL", "SPLIT", null, 2, 1, TODAY, null, null),
            "k-split-1");
        ArgumentCaptor<Holding> afterFirst = ArgumentCaptor.forClass(Holding.class);
        verify(tradingRepository).updateHolding(afterFirst.capture());
        assertThat(afterFirst.getValue().totalQuantity()).isEqualByComparingTo("200");

        /* 第二筆：同一瞬間，repository 依契約把先記錄的第一筆（+100）算進 Q_asOf → 200 → ΔQ +200。 */
        when(tradingRepository.findHoldingForUpdate(USER_ID, AAPL_ID)).thenReturn(Optional.of(afterFirst.getValue()));
        when(repository.findLatestActive(USER_ID, AAPL_ID)).thenReturn(Optional.of(
            split(101L, "100", TODAY, TODAY_START_US, null, 2, 1)));
        when(repository.quantityAsOf(USER_ID, AAPL_ID, TODAY_START_US)).thenReturn(new BigDecimal("200"));
        service.create(USER_ID, new CreateShareAdjustmentRequest("AAPL", "SPLIT", null, 2, 1, TODAY, null, null),
            "k-split-2");

        ArgumentCaptor<ShareAdjustmentEntry> written = ArgumentCaptor.forClass(ShareAdjustmentEntry.class);
        verify(repository, times(2)).insertIfAbsent(written.capture());
        assertThat(written.getAllValues().get(1).quantityDelta()).isEqualByComparingTo("200");
        ArgumentCaptor<Holding> updated = ArgumentCaptor.forClass(Holding.class);
        verify(tradingRepository, times(2)).updateHolding(updated.capture());
        assertThat(updated.getAllValues().get(1).totalQuantity()).isEqualByComparingTo("400");
        /* 總成本不變：100 × 100 = 400 × 25。 */
        assertThat(updated.getAllValues().get(1).avgCost()).isEqualByComparingTo("25");
    }

    @Test
    @DisplayName("type 白名單外 → 400 fields.type，任何 I/O 之前擋下")
    void unsupportedTypeIsRejected() {
        assertFieldRejected(new CreateShareAdjustmentRequest("AAPL", "DIVIDEND", new BigDecimal("5"), null, null,
            TODAY, null, null), "type");
        assertFieldRejected(new CreateShareAdjustmentRequest("AAPL", " ", new BigDecimal("5"), null, null,
            TODAY, null, null), "type");
    }

    @Test
    @DisplayName("SPLIT 缺 ratioNew / ratioOld → fields.ratioNew / fields.ratioOld；兩者相等 → fields.ratio")
    void splitShapeIsValidated() {
        assertFieldRejected(new CreateShareAdjustmentRequest("AAPL", "SPLIT", null, null, 1, TODAY, null, null),
            "ratioNew");
        assertFieldRejected(new CreateShareAdjustmentRequest("AAPL", "SPLIT", null, 4, null, TODAY, null, null),
            "ratioOld");
        assertFieldRejected(new CreateShareAdjustmentRequest("AAPL", "SPLIT", null, 3, 3, TODAY, null, null), "ratio");
    }

    @Test
    @DisplayName("STOCK_DIVIDEND 缺 quantity → fields.quantity")
    void stockDividendRequiresQuantity() {
        assertFieldRejected(new CreateShareAdjustmentRequest("AAPL", "STOCK_DIVIDEND", null, null, null, TODAY, null, null),
            "quantity");
    }

    @Test
    @DisplayName("拆股換算後 ΔQ 為 0（極小零股 × 1000:999 經 DOWN 捨入）→ 400 fields.ratio，不寫入")
    void zeroSplitDeltaIsRejected() {
        holding("1", "100");
        when(repository.quantityAsOf(USER_ID, AAPL_ID, TODAY_START_US)).thenReturn(new BigDecimal("0.00000001"));

        assertThatThrownBy(() -> service.create(USER_ID,
            new CreateShareAdjustmentRequest("AAPL", "SPLIT", null, 1000, 999, TODAY, null, null), "k-zero-delta"))
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                assertThat(ex.fields()).containsKey("ratio");
            });
        verifyNothingWritten();
    }

    @Test
    @DisplayName("冪等鍵空白或超過 128 字 → 400 fields.Idempotency-Key，任何 I/O 之前擋下")
    void invalidIdempotencyKeyIsRejectedBeforeAnyIo() {
        CreateShareAdjustmentRequest request = new CreateShareAdjustmentRequest(
            "AAPL", "STOCK_DIVIDEND", new BigDecimal("5"), null, null, TODAY, null, null);
        for (String key : new String[] {" ", "k".repeat(129)}) {
            assertThatThrownBy(() -> service.create(USER_ID, request, key))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.fields()).containsKey("Idempotency-Key");
                });
        }
        verifyNoInteractions(assetFacade, repository, tradingRepository, portfolioCache);
    }

    @Test
    @DisplayName("標的不可交易 → 404 ASSET_NOT_FOUND（fields.symbol）")
    void untradeableAssetIsRejected() {
        when(assetFacade.findBySymbol("AAPL")).thenReturn(Optional.of(
            new AssetSummary(AAPL_ID, "AAPL", "Apple Inc.", AssetType.STOCK, "US", false, true)));

        assertThatThrownBy(() -> service.create(USER_ID,
            new CreateShareAdjustmentRequest("AAPL", "STOCK_DIVIDEND", new BigDecimal("5"), null, null, TODAY, null, null),
            "k-untradeable"))
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.errorCode()).isEqualTo(ErrorCode.ASSET_NOT_FOUND);
                assertThat(ex.fields()).containsKey("symbol");
            });
        verifyNoInteractions(repository, tradingRepository, portfolioCache);
    }

    @Test
    @DisplayName("同 key 命中：回既有那筆，不鎖持倉、不寫入、不更新持倉、不失效快取")
    void idempotentHitReturnsExistingWithoutTouchingHolding() {
        ShareAdjustmentEntry known = existing(77L, TODAY, TODAY_START_US);
        when(repository.findByIdempotencyKey(USER_ID, "k-known")).thenReturn(Optional.of(known));
        when(repository.isReversed(77L)).thenReturn(true);

        ShareAdjustmentDto dto = service.create(USER_ID,
            new CreateShareAdjustmentRequest("AAPL", "STOCK_DIVIDEND", new BigDecimal("5"), null, null, TODAY, null, null),
            "k-known");

        assertThat(dto.id()).isEqualTo(known.uuid().toString());
        assertThat(dto.reversed()).isTrue();
        verify(tradingRepository, never()).findHoldingForUpdate(anyLong(), anyLong());
        verify(tradingRepository, never()).updateHolding(any());
        verify(repository, never()).insertIfAbsent(any());
        verifyNoInteractions(portfolioCache);
    }

    @Test
    @DisplayName("insert 落空、以 key 重讀命中（同 key 併發）：回對方那筆，不更新持倉、不失效")
    void insertConflictWithConcurrentSameKeyReturnsConcurrentRow() {
        holding("100", "100");
        when(repository.quantityAsOf(USER_ID, AAPL_ID, TODAY_START_US)).thenReturn(new BigDecimal("100"));
        ShareAdjustmentEntry concurrent = existing(88L, TODAY, TODAY_START_US);
        when(repository.findByIdempotencyKey(USER_ID, "k-race"))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(concurrent));
        when(repository.insertIfAbsent(any(ShareAdjustmentEntry.class))).thenReturn(Optional.empty());

        ShareAdjustmentDto dto = service.create(USER_ID,
            new CreateShareAdjustmentRequest("AAPL", "STOCK_DIVIDEND", new BigDecimal("5"), null, null, TODAY, null, null),
            "k-race");

        assertThat(dto.id()).isEqualTo(concurrent.uuid().toString());
        verify(tradingRepository, never()).updateHolding(any());
        verifyNoInteractions(portfolioCache);
    }

    @Test
    @DisplayName("insert 落空且以 key 重讀也落空 → 409 DUPLICATE_RESOURCE，不更新持倉")
    void insertConflictWithoutKeyHitIsDuplicate() {
        holding("100", "100");
        when(repository.quantityAsOf(USER_ID, AAPL_ID, TODAY_START_US)).thenReturn(new BigDecimal("100"));
        when(repository.insertIfAbsent(any(ShareAdjustmentEntry.class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(USER_ID,
            new CreateShareAdjustmentRequest("AAPL", "STOCK_DIVIDEND", new BigDecimal("5"), null, null, TODAY, null, null),
            "k-dup"))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.errorCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE));
        verify(tradingRepository, never()).updateHolding(any());
        verifyNoInteractions(portfolioCache);
    }

    @Test
    @DisplayName("沖銷最晚一筆拆股 4:1（ΔQ +300）：寫入值取自原列的沖銷列、持倉 400 → 100（總成本不變）、失效一次、不算 Q_asOf")
    void reversingLatestSplitRestoresHoldingWithoutRecomputingRatio() {
        holding("400", "25");
        ShareAdjustmentEntry original = split(90L, "300", YESTERDAY, YESTERDAY_START_US, null);
        stubOriginal(original);
        when(repository.findLatestActive(USER_ID, AAPL_ID)).thenReturn(Optional.of(original));

        ShareAdjustmentDto dto = service.create(USER_ID,
            reverseSplitRequest(4, 1, YESTERDAY, original.uuid(), " 記錯了 "), "k-reverse");

        ArgumentCaptor<ShareAdjustmentEntry> written = ArgumentCaptor.forClass(ShareAdjustmentEntry.class);
        verify(repository).insertIfAbsent(written.capture());
        ShareAdjustmentEntry entry = written.getValue();
        assertThat(entry.reversalOfId()).isEqualTo(90L);
        assertThat(entry.assetId()).isEqualTo(AAPL_ID);
        assertThat(entry.type()).isEqualTo(ShareAdjustmentType.SPLIT);
        assertThat(entry.ratioNew()).isEqualTo(4);
        assertThat(entry.ratioOld()).isEqualTo(1);
        assertThat(entry.quantityDelta()).isEqualByComparingTo("300");
        assertThat(entry.effectiveDate()).isEqualTo(YESTERDAY);
        assertThat(entry.effectiveAt()).isEqualTo(YESTERDAY_START_US);
        assertThat(entry.idempotencyKey()).isEqualTo("k-reverse");
        assertThat(entry.note()).isEqualTo("記錯了");

        ArgumentCaptor<Holding> updated = ArgumentCaptor.forClass(Holding.class);
        verify(tradingRepository).updateHolding(updated.capture());
        assertThat(updated.getValue().totalQuantity()).isEqualByComparingTo("100");
        assertThat(updated.getValue().avgCost()).isEqualByComparingTo("100");
        verify(portfolioCache, times(1)).invalidateAfterTrade(USER_ID, AAPL_ID);
        verify(repository, never()).quantityAsOf(any(), any(), any());

        InOrder order = inOrder(tradingRepository, repository);
        order.verify(tradingRepository).findHoldingForUpdate(USER_ID, AAPL_ID);
        order.verify(repository).insertIfAbsent(any(ShareAdjustmentEntry.class));
        order.verify(tradingRepository).updateHolding(any(Holding.class));

        assertThat(dto.reversalOfId()).isEqualTo(original.uuid().toString());
        assertThat(dto.reversed()).isFalse();
    }

    @Test
    @DisplayName("沖銷合股 1:10（ΔQ −94.5）：撤銷後持倉 10.5 → 105")
    void reversingReverseSplitAddsSharesBack() {
        holding("10.5", "100");
        ShareAdjustmentEntry original = split(90L, "-94.5", TODAY, TODAY_START_US, null, 1, 10);
        stubOriginal(original);
        when(repository.findLatestActive(USER_ID, AAPL_ID)).thenReturn(Optional.of(original));

        service.create(USER_ID, reverseSplitRequest(1, 10, TODAY, original.uuid(), null), "k-reverse-rs");

        ArgumentCaptor<Holding> updated = ArgumentCaptor.forClass(Holding.class);
        verify(tradingRepository).updateHolding(updated.capture());
        assertThat(updated.getValue().totalQuantity()).isEqualByComparingTo("105");
    }

    @Test
    @DisplayName("沖銷對象不是該標的最晚一筆未沖銷調整 → 409 SHARE_ADJUSTMENT_NOT_REVERSIBLE，不寫入")
    void reversingNonLatestAdjustmentIsNotReversible() {
        holding("400", "25");
        ShareAdjustmentEntry original = split(90L, "300", YESTERDAY, YESTERDAY_START_US, null);
        stubOriginal(original);
        when(repository.findLatestActive(USER_ID, AAPL_ID)).thenReturn(Optional.of(
            split(91L, "400", TODAY, TODAY_START_US, null)));

        assertErrorCode(() -> service.create(USER_ID,
            reverseSplitRequest(4, 1, YESTERDAY, original.uuid(), null), "k-not-latest"),
            ErrorCode.SHARE_ADJUSTMENT_NOT_REVERSIBLE);
        verifyNothingWritten();
    }

    @Test
    @DisplayName("沖銷對象本身是沖銷列 → 409 SHARE_ADJUSTMENT_NOT_REVERSIBLE，不寫入")
    void reversingAReversalRowIsNotReversible() {
        holding("100", "100");
        ShareAdjustmentEntry reversalRow = split(92L, "300", YESTERDAY, YESTERDAY_START_US, 90L);
        stubOriginal(reversalRow);

        assertErrorCode(() -> service.create(USER_ID,
            reverseSplitRequest(4, 1, YESTERDAY, reversalRow.uuid(), null), "k-reverse-reversal"),
            ErrorCode.SHARE_ADJUSTMENT_NOT_REVERSIBLE);
        verifyNothingWritten();
    }

    @Test
    @DisplayName("沖銷對象已被沖銷 → 409 SHARE_ADJUSTMENT_ALREADY_REVERSED，不寫入")
    void reversingAlreadyReversedAdjustmentIsRejected() {
        holding("100", "100");
        ShareAdjustmentEntry original = split(90L, "300", YESTERDAY, YESTERDAY_START_US, null);
        stubOriginal(original);
        when(repository.isReversed(90L)).thenReturn(true);

        assertErrorCode(() -> service.create(USER_ID,
            reverseSplitRequest(4, 1, YESTERDAY, original.uuid(), null), "k-twice"),
            ErrorCode.SHARE_ADJUSTMENT_ALREADY_REVERSED);
        verifyNothingWritten();
    }

    @Test
    @DisplayName("沖銷對象不存在或屬於他人 → 404 SHARE_ADJUSTMENT_NOT_FOUND，不鎖持倉、不寫入、訊息不含 UUID")
    void reversingUnknownAdjustmentIsNotFound() {
        UUID unknown = UUID.randomUUID();
        when(repository.findByUuid(USER_ID, unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(USER_ID,
            reverseSplitRequest(4, 1, YESTERDAY, unknown, null), "k-unknown"))
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.errorCode()).isEqualTo(ErrorCode.SHARE_ADJUSTMENT_NOT_FOUND);
                assertThat(ex.getMessage()).doesNotContain(unknown.toString());
            });
        verifyNoInteractions(tradingRepository, portfolioCache);
        verify(repository, never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("reversalOfId 不是 UUID → 400 fields.reversalOfId，不碰帳本與持倉")
    void malformedReversalOfIdIsRejected() {
        for (String malformed : new String[] {"not-a-uuid", " "}) {
            assertThatThrownBy(() -> service.create(USER_ID,
                new CreateShareAdjustmentRequest("AAPL", "SPLIT", null, 4, 1, YESTERDAY, null, malformed),
                "k-malformed"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.fields()).containsKey("reversalOfId");
                });
        }
        verifyNoInteractions(repository, tradingRepository, portfolioCache);
    }

    @Test
    @DisplayName("沖銷後持股 ≤ 0（持倉 50、撤銷 +300）→ 409 TRADE_INSUFFICIENT_HOLDING，不寫入、不更新持倉")
    void reversalThatWouldLeaveNoSharesIsRejected() {
        holding("50", "100");
        ShareAdjustmentEntry original = split(90L, "300", YESTERDAY, YESTERDAY_START_US, null);
        stubOriginal(original);
        when(repository.findLatestActive(USER_ID, AAPL_ID)).thenReturn(Optional.of(original));

        assertErrorCode(() -> service.create(USER_ID,
            reverseSplitRequest(4, 1, YESTERDAY, original.uuid(), null), "k-too-few"),
            ErrorCode.TRADE_INSUFFICIENT_HOLDING);
        verifyNothingWritten();
    }

    @Test
    @DisplayName("沖銷請求的 symbol / type / ratio / quantity / effectiveDate 與原列不同 → 400，fields 指名不符欄位")
    void reversalValuesMustMatchOriginal() {
        holding("400", "25");
        ShareAdjustmentEntry original = split(90L, "300", YESTERDAY, YESTERDAY_START_US, null);
        stubOriginal(original);
        when(repository.findLatestActive(USER_ID, AAPL_ID)).thenReturn(Optional.of(original));
        when(assetFacade.findBySymbol("TSLA")).thenReturn(Optional.of(
            new AssetSummary(2L, "TSLA", "Tesla", AssetType.STOCK, "US", true, true)));

        assertFieldRejectedOnReversal(reverseSplitRequest(4, 2, YESTERDAY, original.uuid(), null), "ratioOld");
        assertFieldRejectedOnReversal(reverseSplitRequest(2, 1, YESTERDAY, original.uuid(), null), "ratioNew");
        assertFieldRejectedOnReversal(reverseSplitRequest(4, 1, TODAY, original.uuid(), null), "effectiveDate");
        assertFieldRejectedOnReversal(new CreateShareAdjustmentRequest("AAPL", "STOCK_DIVIDEND", new BigDecimal("300"),
            null, null, YESTERDAY, null, original.uuid().toString()), "type");
        assertFieldRejectedOnReversal(new CreateShareAdjustmentRequest("TSLA", "SPLIT", null, 4, 1, YESTERDAY, null,
            original.uuid().toString()), "symbol");
        verifyNothingWritten();
    }

    @Test
    @DisplayName("沖銷配股：quantity 必須等於原列實領股數（scale 不同但數值相等視為相同）")
    void stockDividendReversalComparesQuantity() {
        holding("1050", "95.23809524");
        ShareAdjustmentEntry original = new ShareAdjustmentEntry(90L, UUID.randomUUID(), USER_ID, AAPL_ID,
            ShareAdjustmentType.STOCK_DIVIDEND, null, null, new BigDecimal("50.00000000"), YESTERDAY,
            YESTERDAY_START_US, null, OffsetDateTime.parse("2026-10-29T10:00:00Z"), "k-orig", null);
        stubOriginal(original);
        when(repository.findLatestActive(USER_ID, AAPL_ID)).thenReturn(Optional.of(original));

        assertFieldRejectedOnReversal(new CreateShareAdjustmentRequest("AAPL", "STOCK_DIVIDEND", new BigDecimal("49"),
            null, null, YESTERDAY, null, original.uuid().toString()), "quantity");
        verify(repository, never()).insertIfAbsent(any());

        service.create(USER_ID, new CreateShareAdjustmentRequest("AAPL", "STOCK_DIVIDEND", new BigDecimal("50"),
            null, null, YESTERDAY, null, original.uuid().toString()), "k-div-reverse");

        ArgumentCaptor<Holding> updated = ArgumentCaptor.forClass(Holding.class);
        verify(tradingRepository).updateHolding(updated.capture());
        assertThat(updated.getValue().totalQuantity()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("同一除權日兩筆調整：依記錄順序倒著沖銷——先沖較早記錄者 409 NOT_REVERSIBLE，沖最後記錄者成功")
    void sameDayAdjustmentsAreReversedInRecordOrder() {
        holding("400", "25");
        ShareAdjustmentEntry first = split(90L, "100", TODAY, TODAY_START_US, null, 2, 1);
        ShareAdjustmentEntry second = split(91L, "200", TODAY, TODAY_START_US, null, 2, 1);
        stubOriginal(first);
        stubOriginal(second);
        /* findLatestActive 依 (effective_at DESC, id DESC) 排序：同一瞬間時 id 較大（較晚記錄）者為最晚。 */
        when(repository.findLatestActive(USER_ID, AAPL_ID)).thenReturn(Optional.of(second));

        assertErrorCode(() -> service.create(USER_ID,
            reverseSplitRequest(2, 1, TODAY, first.uuid(), null), "k-first"),
            ErrorCode.SHARE_ADJUSTMENT_NOT_REVERSIBLE);
        verify(repository, never()).insertIfAbsent(any());

        service.create(USER_ID, reverseSplitRequest(2, 1, TODAY, second.uuid(), null), "k-second");

        ArgumentCaptor<ShareAdjustmentEntry> written = ArgumentCaptor.forClass(ShareAdjustmentEntry.class);
        verify(repository).insertIfAbsent(written.capture());
        assertThat(written.getValue().reversalOfId()).isEqualTo(91L);
        ArgumentCaptor<Holding> updated = ArgumentCaptor.forClass(Holding.class);
        verify(tradingRepository).updateHolding(updated.capture());
        assertThat(updated.getValue().totalQuantity()).isEqualByComparingTo("200");
    }

    @Test
    @DisplayName("沖銷成功後以同一把 key 重送：冪等優先於「已沖銷」，回同一筆沖銷列，不鎖持倉、不寫入")
    void reversalRetryWithSameKeyReturnsTheReversalRow() {
        ShareAdjustmentEntry original = split(90L, "300", YESTERDAY, YESTERDAY_START_US, null);
        stubOriginal(original);
        when(repository.isReversed(90L)).thenReturn(true);
        ShareAdjustmentEntry reversalRow = split(93L, "300", YESTERDAY, YESTERDAY_START_US, 90L);
        when(repository.findByIdempotencyKey(USER_ID, "k-reverse")).thenReturn(Optional.of(reversalRow));

        ShareAdjustmentDto dto = service.create(USER_ID,
            reverseSplitRequest(4, 1, YESTERDAY, original.uuid(), null), "k-reverse");

        assertThat(dto.id()).isEqualTo(reversalRow.uuid().toString());
        assertThat(dto.reversalOfId()).isEqualTo(original.uuid().toString());
        verify(tradingRepository, never()).findHoldingForUpdate(anyLong(), anyLong());
        verify(repository, never()).insertIfAbsent(any());
        verifyNoInteractions(portfolioCache);
    }

    @Test
    @DisplayName("沖銷 insert 落空且以 key 重讀落空（另一把 key 的沖銷搶先）→ 409 SHARE_ADJUSTMENT_ALREADY_REVERSED，不更新持倉")
    void reversalLostToAnotherKeyIsAlreadyReversed() {
        holding("400", "25");
        ShareAdjustmentEntry original = split(90L, "300", YESTERDAY, YESTERDAY_START_US, null);
        stubOriginal(original);
        when(repository.findLatestActive(USER_ID, AAPL_ID)).thenReturn(Optional.of(original));
        when(repository.insertIfAbsent(any(ShareAdjustmentEntry.class))).thenReturn(Optional.empty());

        assertErrorCode(() -> service.create(USER_ID,
            reverseSplitRequest(4, 1, YESTERDAY, original.uuid(), null), "k-lost"),
            ErrorCode.SHARE_ADJUSTMENT_ALREADY_REVERSED);
        verify(tradingRepository, never()).updateHolding(any());
        verifyNoInteractions(portfolioCache);
    }

    @Test
    @DisplayName("同 key 命中但 payload 不同（配股股數 5 → 6）→ 409 SHARE_ADJUSTMENT_IDEMPOTENCY_KEY_REUSED，不鎖持倉、不寫入、訊息不含 key")
    void reusedKeyWithDifferentPayloadIsRejected() {
        when(repository.findByIdempotencyKey(USER_ID, "LEAK-CANARY-KEY")).thenReturn(Optional.of(
            existing(77L, TODAY, TODAY_START_US)));

        assertThatThrownBy(() -> service.create(USER_ID,
            new CreateShareAdjustmentRequest("AAPL", "STOCK_DIVIDEND", new BigDecimal("6"), null, null, TODAY, null,
                null), "LEAK-CANARY-KEY"))
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.errorCode()).isEqualTo(ErrorCode.SHARE_ADJUSTMENT_IDEMPOTENCY_KEY_REUSED);
                assertThat(ex.getMessage()).doesNotContain("LEAK-CANARY-KEY");
            });
        verifyNoInteractions(tradingRepository, portfolioCache);
        verify(repository, never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("同 key 命中、只有 note 不同、quantity scale 不同（5 vs 5.00000000）→ 回既有那筆")
    void sameKeyWithOnlyNoteAndScaleChangedReturnsExisting() {
        ShareAdjustmentEntry known = new ShareAdjustmentEntry(77L, UUID.randomUUID(), USER_ID, AAPL_ID,
            ShareAdjustmentType.STOCK_DIVIDEND, null, null, new BigDecimal("5.00000000"), TODAY, TODAY_START_US,
            "第一次的備註", OffsetDateTime.parse("2026-10-30T10:00:00Z"), "k-note", null);
        when(repository.findByIdempotencyKey(USER_ID, "k-note")).thenReturn(Optional.of(known));

        ShareAdjustmentDto dto = service.create(USER_ID,
            new CreateShareAdjustmentRequest("AAPL", "STOCK_DIVIDEND", new BigDecimal("5"), null, null, TODAY,
                "改過的備註", null), "k-note");

        assertThat(dto.id()).isEqualTo(known.uuid().toString());
        verifyNoInteractions(tradingRepository, portfolioCache);
    }

    @Test
    @DisplayName("同 key 先記一般調整、再拿來沖銷（reversalOfId 不同）→ 409 SHARE_ADJUSTMENT_IDEMPOTENCY_KEY_REUSED")
    void reusingAKeyForAReversalIsRejected() {
        ShareAdjustmentEntry original = split(90L, "300", YESTERDAY, YESTERDAY_START_US, null);
        stubOriginal(original);
        when(repository.findByIdempotencyKey(USER_ID, "k-orig-90")).thenReturn(Optional.of(original));

        assertErrorCode(() -> service.create(USER_ID,
            reverseSplitRequest(4, 1, YESTERDAY, original.uuid(), null), "k-orig-90"),
            ErrorCode.SHARE_ADJUSTMENT_IDEMPOTENCY_KEY_REUSED);
        verifyNoInteractions(tradingRepository, portfolioCache);
    }

    @Test
    @DisplayName("insert 落空、以 key 重讀命中但 payload 不同 → 409 SHARE_ADJUSTMENT_IDEMPOTENCY_KEY_REUSED，不更新持倉")
    void insertConflictWithDifferentConcurrentPayloadIsKeyReuse() {
        holding("100", "100");
        when(repository.quantityAsOf(USER_ID, AAPL_ID, TODAY_START_US)).thenReturn(new BigDecimal("100"));
        when(repository.findByIdempotencyKey(USER_ID, "k-race-diff"))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(existing(88L, TODAY, TODAY_START_US)));
        when(repository.insertIfAbsent(any(ShareAdjustmentEntry.class))).thenReturn(Optional.empty());

        assertErrorCode(() -> service.create(USER_ID,
            new CreateShareAdjustmentRequest("AAPL", "SPLIT", null, 2, 1, TODAY, null, null), "k-race-diff"),
            ErrorCode.SHARE_ADJUSTMENT_IDEMPOTENCY_KEY_REUSED);
        verify(tradingRepository, never()).updateHolding(any());
        verifyNoInteractions(portfolioCache);
    }

    @Test
    @DisplayName("create 在單一交易內執行（鎖持倉、寫帳本、更新持倉必須同生共死）")
    void createIsTransactional() throws NoSuchMethodException {
        assertThat(ShareAdjustmentService.class
            .getMethod("create", Long.class, CreateShareAdjustmentRequest.class, String.class)
            .isAnnotationPresent(Transactional.class)).isTrue();
    }

    private void holding(String quantity, String avgCost) {
        when(tradingRepository.findHoldingForUpdate(USER_ID, AAPL_ID)).thenReturn(Optional.of(new Holding(
            HOLDING_ID, USER_ID, AAPL_ID, new BigDecimal(quantity), new BigDecimal(avgCost), BigDecimal.ZERO, 3,
            OffsetDateTime.parse("2026-10-01T00:00:00Z"))));
    }

    private void assertInsufficientHolding(Runnable call) {
        assertThatThrownBy(call::run)
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.errorCode()).isEqualTo(ErrorCode.TRADE_INSUFFICIENT_HOLDING);
                assertThat(ex.fields()).containsKey("symbol");
            });
    }

    private void assertFieldRejected(CreateShareAdjustmentRequest request, String field) {
        assertThatThrownBy(() -> service.create(USER_ID, request, "k-shape"))
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                assertThat(ex.fields()).containsKey(field);
            });
        verifyNoInteractions(repository, tradingRepository, portfolioCache);
    }

    private void verifyNothingWritten() {
        verify(repository, never()).insertIfAbsent(any());
        verify(tradingRepository, never()).updateHolding(any());
        verifyNoInteractions(portfolioCache);
    }

    private void stubOriginal(ShareAdjustmentEntry original) {
        when(repository.findByUuid(USER_ID, original.uuid())).thenReturn(Optional.of(original));
        when(repository.uuidOf(original.id())).thenReturn(Optional.of(original.uuid()));
    }

    private static CreateShareAdjustmentRequest reverseSplitRequest(
        int ratioNew, int ratioOld, LocalDate effectiveDate, UUID reversalOf, String note) {
        return new CreateShareAdjustmentRequest("AAPL", "SPLIT", null, ratioNew, ratioOld, effectiveDate, note,
            reversalOf.toString());
    }

    private void assertErrorCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
            .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.errorCode()).isEqualTo(expected));
    }

    private void assertFieldRejectedOnReversal(CreateShareAdjustmentRequest request, String field) {
        assertThatThrownBy(() -> service.create(USER_ID, request, "k-mismatch-" + field))
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                assertThat(ex.fields()).containsKey(field);
            });
    }

    private static ShareAdjustmentEntry split(Long id, String delta, LocalDate effectiveDate,
                                              OffsetDateTime effectiveAt, Long reversalOfId) {
        return split(id, delta, effectiveDate, effectiveAt, reversalOfId, 4, 1);
    }

    private static ShareAdjustmentEntry split(Long id, String delta, LocalDate effectiveDate,
                                              OffsetDateTime effectiveAt, Long reversalOfId,
                                              int ratioNew, int ratioOld) {
        return new ShareAdjustmentEntry(id, UUID.randomUUID(), USER_ID, AAPL_ID, ShareAdjustmentType.SPLIT,
            ratioNew, ratioOld, new BigDecimal(delta), effectiveDate, effectiveAt, null,
            OffsetDateTime.parse("2026-10-29T10:00:00Z"), "k-orig-" + id, reversalOfId);
    }

    private static ShareAdjustmentEntry existing(Long id, LocalDate effectiveDate, OffsetDateTime effectiveAt) {
        return new ShareAdjustmentEntry(id, UUID.randomUUID(), USER_ID, AAPL_ID, ShareAdjustmentType.STOCK_DIVIDEND,
            null, null, new BigDecimal("5"), effectiveDate, effectiveAt, null,
            OffsetDateTime.parse("2026-10-30T10:00:00Z"), "k-other", null);
    }

    private static ShareAdjustmentEntry persisted(ShareAdjustmentEntry candidate, Long id) {
        return new ShareAdjustmentEntry(id, candidate.uuid(), candidate.userId(), candidate.assetId(),
            candidate.type(), candidate.ratioNew(), candidate.ratioOld(), candidate.quantityDelta(),
            candidate.effectiveDate(), candidate.effectiveAt(), candidate.note(),
            OffsetDateTime.parse("2026-10-30T15:00:00Z"), candidate.idempotencyKey(), candidate.reversalOfId());
    }
}
