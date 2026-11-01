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
 * payload 比對（409）與沖銷流程由 04.2-08 補上。</p>
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
                " 配股 "),
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
            new CreateShareAdjustmentRequest("AAPL", "split", null, 4, 1, TODAY, null), "k-split");

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

        service.create(USER_ID, new CreateShareAdjustmentRequest("AAPL", "SPLIT", null, 1, 10, TODAY, null), "k-rs");

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
            new CreateShareAdjustmentRequest("AAPL", "STOCK_DIVIDEND", new BigDecimal("5"), null, null, TODAY, null),
            "k-none-asof"));
        verifyNothingWritten();
    }

    @Test
    @DisplayName("目前無持倉列 → 409 TRADE_INSUFFICIENT_HOLDING（fields.symbol），不寫入")
    void missingCurrentHoldingIsRejected() {
        when(tradingRepository.findHoldingForUpdate(USER_ID, AAPL_ID)).thenReturn(Optional.empty());
        when(repository.quantityAsOf(USER_ID, AAPL_ID, TODAY_START_US)).thenReturn(new BigDecimal("100"));

        assertInsufficientHolding(() -> service.create(USER_ID,
            new CreateShareAdjustmentRequest("AAPL", "SPLIT", null, 2, 1, TODAY, null), "k-none-now"));
        verifyNothingWritten();
    }

    @Test
    @DisplayName("目前持倉已賣光（0 股）→ 409 TRADE_INSUFFICIENT_HOLDING（fields.symbol），不寫入")
    void zeroCurrentHoldingIsRejected() {
        holding("0", "0");
        when(repository.quantityAsOf(USER_ID, AAPL_ID, TODAY_START_US)).thenReturn(new BigDecimal("100"));

        assertInsufficientHolding(() -> service.create(USER_ID,
            new CreateShareAdjustmentRequest("AAPL", "STOCK_DIVIDEND", new BigDecimal("5"), null, null, TODAY, null),
            "k-zero-now"));
        verifyNothingWritten();
    }

    @Test
    @DisplayName("除權日換算後超前現在 5 分鐘以上 → 400 fields.effectiveDate，不碰帳本")
    void futureEffectiveDateIsRejected() {
        assertThatThrownBy(() -> service.create(USER_ID,
            new CreateShareAdjustmentRequest("AAPL", "STOCK_DIVIDEND", new BigDecimal("5"), null, null,
                TODAY.plusDays(1), null), "k-future"))
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
                TODAY.minusDays(1), null), "k-earlier"))
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                assertThat(ex.fields()).containsKey("effectiveDate");
            });
        verifyNothingWritten();
    }

    @Test
    @DisplayName("與最晚未沖銷調整同一生效時間 → 允許（規則只拒絕「早於」）")
    void effectiveDateEqualToLatestActiveAdjustmentIsAllowed() {
        holding("100", "100");
        when(repository.findLatestActive(USER_ID, AAPL_ID)).thenReturn(Optional.of(
            existing(90L, TODAY, TODAY_START_US)));
        when(repository.quantityAsOf(USER_ID, AAPL_ID, TODAY_START_US)).thenReturn(new BigDecimal("100"));

        service.create(USER_ID,
            new CreateShareAdjustmentRequest("AAPL", "STOCK_DIVIDEND", new BigDecimal("5"), null, null, TODAY, null),
            "k-same-day");

        verify(repository).insertIfAbsent(any(ShareAdjustmentEntry.class));
    }

    @Test
    @DisplayName("type 白名單外 → 400 fields.type，任何 I/O 之前擋下")
    void unsupportedTypeIsRejected() {
        assertFieldRejected(new CreateShareAdjustmentRequest("AAPL", "DIVIDEND", new BigDecimal("5"), null, null,
            TODAY, null), "type");
        assertFieldRejected(new CreateShareAdjustmentRequest("AAPL", " ", new BigDecimal("5"), null, null,
            TODAY, null), "type");
    }

    @Test
    @DisplayName("SPLIT 缺 ratioNew / ratioOld → fields.ratioNew / fields.ratioOld；兩者相等 → fields.ratio")
    void splitShapeIsValidated() {
        assertFieldRejected(new CreateShareAdjustmentRequest("AAPL", "SPLIT", null, null, 1, TODAY, null),
            "ratioNew");
        assertFieldRejected(new CreateShareAdjustmentRequest("AAPL", "SPLIT", null, 4, null, TODAY, null),
            "ratioOld");
        assertFieldRejected(new CreateShareAdjustmentRequest("AAPL", "SPLIT", null, 3, 3, TODAY, null), "ratio");
    }

    @Test
    @DisplayName("STOCK_DIVIDEND 缺 quantity → fields.quantity")
    void stockDividendRequiresQuantity() {
        assertFieldRejected(new CreateShareAdjustmentRequest("AAPL", "STOCK_DIVIDEND", null, null, null, TODAY, null),
            "quantity");
    }

    @Test
    @DisplayName("拆股換算後 ΔQ 為 0（極小零股 × 1000:999 經 DOWN 捨入）→ 400 fields.ratio，不寫入")
    void zeroSplitDeltaIsRejected() {
        holding("1", "100");
        when(repository.quantityAsOf(USER_ID, AAPL_ID, TODAY_START_US)).thenReturn(new BigDecimal("0.00000001"));

        assertThatThrownBy(() -> service.create(USER_ID,
            new CreateShareAdjustmentRequest("AAPL", "SPLIT", null, 1000, 999, TODAY, null), "k-zero-delta"))
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
            "AAPL", "STOCK_DIVIDEND", new BigDecimal("5"), null, null, TODAY, null);
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
            new CreateShareAdjustmentRequest("AAPL", "STOCK_DIVIDEND", new BigDecimal("5"), null, null, TODAY, null),
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
            new CreateShareAdjustmentRequest("AAPL", "STOCK_DIVIDEND", new BigDecimal("5"), null, null, TODAY, null),
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
            new CreateShareAdjustmentRequest("AAPL", "STOCK_DIVIDEND", new BigDecimal("5"), null, null, TODAY, null),
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
            new CreateShareAdjustmentRequest("AAPL", "STOCK_DIVIDEND", new BigDecimal("5"), null, null, TODAY, null),
            "k-dup"))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.errorCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE));
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
