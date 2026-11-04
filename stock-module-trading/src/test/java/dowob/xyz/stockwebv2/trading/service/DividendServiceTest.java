package dowob.xyz.stockwebv2.trading.service;

import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.model.AssetType;
import dowob.xyz.stockwebv2.common.model.CurrencyCode;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetFacade;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetProfile;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetSummary;
import dowob.xyz.stockwebv2.trading.api.CreateDividendRequest;
import dowob.xyz.stockwebv2.trading.api.DividendDto;
import dowob.xyz.stockwebv2.trading.domain.CashDividend;
import dowob.xyz.stockwebv2.trading.repository.DividendRepository;
import dowob.xyz.stockwebv2.trading.repository.TradingRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link DividendService#createDividend} 的 insert-first 冪等寫入骨架單元測試（DIV-01）。
 *
 * <p>涵蓋 happy path 的正規化（預扣稅預設 0、入帳時間預設注入時鐘的現在並截到微秒、幣別取自標的）、
 * 冪等快路徑與「insert 落空後以 key 重讀」兩處皆不失效快取、標的不可交易 / 不存在回 {@code ASSET_NOT_FOUND}、
 * 冪等鍵不合法在任何 I/O 之前擋下，以及股利寫入路徑完全不碰持倉（D-01 / D-03）。
 * 04.2-04 起另涵蓋沖銷：值相同才可沖銷、四種沖銷錯誤碼與「沖銷落敗於併發」回 {@code DIVIDEND_ALREADY_REVERSED}。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("現金股利寫入服務")
class DividendServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long AAPL_ID = 1L;
    private static final Long MSFT_ID = 2L;
    private static final Long ORIGINAL_ID = 31L;
    private static final UUID ORIGINAL_UUID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final OffsetDateTime ORIGINAL_PAID_AT = OffsetDateTime.parse("2026-10-01T09:00:00+08:00");

    /**
     * 帶奈秒尾數的固定時鐘：用來證明預設入帳時間取自注入的時鐘，且被截到微秒。
     */
    private static final Instant NOW = Instant.parse("2026-10-31T01:02:03.123456789Z");

    private DividendRepository repository;
    private AssetFacade assetFacade;
    private PortfolioCache portfolioCache;
    private DividendService service;

    @BeforeEach
    void setup() {
        repository = mock(DividendRepository.class);
        assetFacade = mock(AssetFacade.class);
        portfolioCache = mock(PortfolioCache.class);
        service = new DividendService(repository, assetFacade, portfolioCache, Clock.fixed(NOW, ZoneOffset.UTC));
        when(assetFacade.findBySymbol("AAPL")).thenReturn(Optional.of(
            new AssetSummary(AAPL_ID, "AAPL", "Apple Inc.", AssetType.STOCK, "US", true, true)));
        when(assetFacade.findProfiles(Set.of(AAPL_ID))).thenReturn(Map.of(AAPL_ID,
            new AssetProfile(AAPL_ID, "AAPL", AssetType.STOCK, "US", CurrencyCode.USD, "Tech")));
    }

    @Test
    @DisplayName("一般股利：預扣稅預設 0、入帳時間預設時鐘現在並截到微秒、非沖銷；DTO 幣別取自標的、未被沖銷，且失效 summary")
    void dividendIsNormalizedInsertedAndMappedToDto() {
        when(repository.findByIdempotencyKey(USER_ID, "k1")).thenReturn(Optional.empty());
        when(repository.insertIfAbsent(any(CashDividend.class))).thenAnswer(invocation -> {
            CashDividend candidate = invocation.getArgument(0);
            return Optional.of(persisted(candidate, 11L));
        });

        DividendDto dto = service.createDividend(
            USER_ID, new CreateDividendRequest(" aapl ", new BigDecimal("120"), null, null, " Q3 ", null), "k1");

        ArgumentCaptor<CashDividend> captor = ArgumentCaptor.forClass(CashDividend.class);
        verify(repository).insertIfAbsent(captor.capture());
        CashDividend written = captor.getValue();
        assertThat(written.userId()).isEqualTo(USER_ID);
        assertThat(written.assetId()).isEqualTo(AAPL_ID);
        assertThat(written.amount()).isEqualByComparingTo("120");
        assertThat(written.withholdingTax()).isEqualByComparingTo("0");
        assertThat(written.paidAt().toInstant()).isEqualTo(NOW.truncatedTo(ChronoUnit.MICROS));
        assertThat(written.note()).isEqualTo("Q3");
        assertThat(written.idempotencyKey()).isEqualTo("k1");
        assertThat(written.reversalOfId()).isNull();
        assertThat(written.uuid()).isNotNull();

        assertThat(dto.id()).isEqualTo(written.uuid().toString());
        assertThat(dto.symbol()).isEqualTo("AAPL");
        assertThat(dto.currency()).isEqualTo("USD");
        assertThat(dto.amount()).isEqualByComparingTo("120");
        assertThat(dto.withholdingTax()).isEqualByComparingTo("0");
        assertThat(dto.note()).isEqualTo("Q3");
        assertThat(dto.reversalOfId()).isNull();
        assertThat(dto.reversed()).isFalse();
        verify(portfolioCache).invalidateSummaryAfterCommit(USER_ID);
    }

    @Test
    @DisplayName("請求明確帶入帳時間與預扣稅：原樣寫入（入帳時間截到微秒）")
    void explicitPaidAtAndWithholdingTaxAreKept() {
        OffsetDateTime paidAt = OffsetDateTime.parse("2026-10-30T09:15:00.123456789+08:00");
        when(repository.findByIdempotencyKey(USER_ID, "k-explicit")).thenReturn(Optional.empty());
        when(repository.insertIfAbsent(any(CashDividend.class))).thenAnswer(invocation ->
            Optional.of(persisted(invocation.getArgument(0), 12L)));

        service.createDividend(USER_ID,
            new CreateDividendRequest("AAPL", new BigDecimal("120"), new BigDecimal("30"), paidAt, null, null),
            "k-explicit");

        ArgumentCaptor<CashDividend> captor = ArgumentCaptor.forClass(CashDividend.class);
        verify(repository).insertIfAbsent(captor.capture());
        assertThat(captor.getValue().withholdingTax()).isEqualByComparingTo("30");
        assertThat(captor.getValue().paidAt()).isEqualTo(paidAt.truncatedTo(ChronoUnit.MICROS));
        assertThat(captor.getValue().note()).isNull();
    }

    @Test
    @DisplayName("同使用者同鍵已存在：快路徑回既有那筆，不 insert、不失效快取")
    void existingKeyReturnsKnownDividendWithoutInsertingOrInvalidating() {
        CashDividend existing = existingDividend(21L, "k1");
        when(repository.findByIdempotencyKey(USER_ID, "k1")).thenReturn(Optional.of(existing));

        DividendDto dto = service.createDividend(
            USER_ID, new CreateDividendRequest("AAPL", new BigDecimal("120"), null, null, null, null), "k1");

        assertThat(dto.id()).isEqualTo(existing.uuid().toString());
        assertThat(dto.currency()).isEqualTo("USD");
        verify(repository, never()).insertIfAbsent(any());
        verify(portfolioCache, never()).invalidateSummaryAfterCommit(any());
    }

    @Test
    @DisplayName("併發同鍵：insert 落空後以 key 重讀命中 → 回該列，不失效快取")
    void concurrentSameKeyReturnsWinnerWithoutInvalidating() {
        CashDividend winner = existingDividend(22L, "k-race");
        when(repository.findByIdempotencyKey(USER_ID, "k-race"))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(winner));
        when(repository.insertIfAbsent(any(CashDividend.class))).thenReturn(Optional.empty());

        DividendDto dto = service.createDividend(
            USER_ID, new CreateDividendRequest("AAPL", new BigDecimal("120"), null, null, null, null), "k-race");

        assertThat(dto.id()).isEqualTo(winner.uuid().toString());
        verify(repository, times(2)).findByIdempotencyKey(USER_ID, "k-race");
        verify(portfolioCache, never()).invalidateSummaryAfterCommit(any());
    }

    @Test
    @DisplayName("insert 落空且重讀也落空：DUPLICATE_RESOURCE，不失效快取")
    void insertConflictWithoutKeyHitIsDuplicateResource() {
        when(repository.findByIdempotencyKey(USER_ID, "k-ghost")).thenReturn(Optional.empty());
        when(repository.insertIfAbsent(any(CashDividend.class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createDividend(
            USER_ID, new CreateDividendRequest("AAPL", new BigDecimal("120"), null, null, null, null), "k-ghost"))
            .isInstanceOf(BusinessException.class)
            .extracting(exception -> ((BusinessException) exception).errorCode())
            .isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
        verify(portfolioCache, never()).invalidateSummaryAfterCommit(any());
    }

    @Test
    @DisplayName("標的不可交易：ASSET_NOT_FOUND，fields.symbol，未寫入")
    void nonTradeableAssetIsNotFoundWithSymbolField() {
        when(assetFacade.findBySymbol("HALT")).thenReturn(Optional.of(
            new AssetSummary(9L, "HALT", "Halted", AssetType.STOCK, "US", false, true)));

        assertBusinessError(
            () -> service.createDividend(USER_ID,
                new CreateDividendRequest("HALT", new BigDecimal("1"), null, null, null, null), "k-halt"),
            ErrorCode.ASSET_NOT_FOUND, "symbol");
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("標的已下架（inactive）：ASSET_NOT_FOUND，fields.symbol，未寫入")
    void inactiveAssetIsNotFoundWithSymbolField() {
        when(assetFacade.findBySymbol("GONE")).thenReturn(Optional.of(
            new AssetSummary(10L, "GONE", "Delisted", AssetType.STOCK, "US", true, false)));

        assertBusinessError(
            () -> service.createDividend(USER_ID,
                new CreateDividendRequest("GONE", new BigDecimal("1"), null, null, null, null), "k-gone"),
            ErrorCode.ASSET_NOT_FOUND, "symbol");
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("標的不存在：ASSET_NOT_FOUND，fields.symbol，未寫入")
    void unknownAssetIsNotFoundWithSymbolField() {
        when(assetFacade.findBySymbol("NOPE")).thenReturn(Optional.empty());

        assertBusinessError(
            () -> service.createDividend(USER_ID,
                new CreateDividendRequest("NOPE", new BigDecimal("1"), null, null, null, null), "k-nope"),
            ErrorCode.ASSET_NOT_FOUND, "symbol");
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("Idempotency-Key 空白：VALIDATION_FAILED fields['Idempotency-Key']，任何 I/O 之前擋下")
    void blankIdempotencyKeyIsRejectedBeforeAnyIo() {
        assertBusinessError(
            () -> service.createDividend(USER_ID,
                new CreateDividendRequest("AAPL", new BigDecimal("1"), null, null, null, null), "   "),
            ErrorCode.VALIDATION_FAILED, "Idempotency-Key");
        verifyNoInteractions(repository, portfolioCache);
        verify(assetFacade, never()).findBySymbol(any());
    }

    @Test
    @DisplayName("Idempotency-Key 超過 128 字：VALIDATION_FAILED fields['Idempotency-Key']，任何 I/O 之前擋下")
    void oversizedIdempotencyKeyIsRejectedBeforeAnyIo() {
        assertBusinessError(
            () -> service.createDividend(USER_ID,
                new CreateDividendRequest("AAPL", new BigDecimal("1"), null, null, null, null), "k".repeat(129)),
            ErrorCode.VALIDATION_FAILED, "Idempotency-Key");
        verifyNoInteractions(repository, portfolioCache);
        verify(assetFacade, never()).findBySymbol(any());
    }

    @Test
    @DisplayName("Idempotency-Key 恰 128 字：可寫入（邊界與 VARCHAR(128) 一致）")
    void idempotencyKeyOfMaximumLengthIsAccepted() {
        String key = "k".repeat(128);
        when(repository.findByIdempotencyKey(USER_ID, key)).thenReturn(Optional.empty());
        when(repository.insertIfAbsent(any(CashDividend.class))).thenAnswer(invocation ->
            Optional.of(persisted(invocation.getArgument(0), 13L)));

        DividendDto dto = service.createDividend(
            USER_ID, new CreateDividendRequest("AAPL", new BigDecimal("1"), null, null, null, null), key);

        assertThat(dto.symbol()).isEqualTo("AAPL");
    }

    @Test
    @DisplayName("入帳時間晚於時鐘現在 5 分鐘以上：VALIDATION_FAILED fields.paidAt，未寫入")
    void paidAtTooFarInTheFutureIsRejected() {
        OffsetDateTime future = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusMinutes(5).plusSeconds(1);

        assertBusinessError(
            () -> service.createDividend(USER_ID,
                new CreateDividendRequest("AAPL", new BigDecimal("1"), null, future, null, null), "k-future"),
            ErrorCode.VALIDATION_FAILED, "paidAt");
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("入帳時間在 5 分鐘容忍內：可寫入（吸收時鐘偏差）")
    void paidAtWithinToleranceIsAccepted() {
        OffsetDateTime nearFuture = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusMinutes(4);
        when(repository.findByIdempotencyKey(USER_ID, "k-near")).thenReturn(Optional.empty());
        when(repository.insertIfAbsent(any(CashDividend.class))).thenAnswer(invocation ->
            Optional.of(persisted(invocation.getArgument(0), 14L)));

        DividendDto dto = service.createDividend(USER_ID,
            new CreateDividendRequest("AAPL", new BigDecimal("1"), null, nearFuture, null, null), "k-near");

        assertThat(dto.paidAt()).isEqualTo(nearFuture.truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    @DisplayName("null request：VALIDATION_FAILED，未有任何 I/O")
    void nullRequestIsRejected() {
        assertThatThrownBy(() -> service.createDividend(USER_ID, null, "k-null"))
            .isInstanceOf(BusinessException.class)
            .extracting(exception -> ((BusinessException) exception).errorCode())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(repository, assetFacade, portfolioCache);
    }

    @Test
    @DisplayName("無任何持倉也能記（D-03）：服務不依賴持倉 / 交易資料存取，寫入照常成功")
    void dividendIsRecordedWithoutAnyHoldingAccess() {
        boolean dependsOnHoldings = Arrays.stream(DividendService.class.getDeclaredFields())
            .map(Field::getType)
            .anyMatch(type -> type == TradingRepository.class || type == PortfolioQueryService.class);
        assertThat(dependsOnHoldings).as("股利寫入不讀也不改 holdings（D-01 / D-03）").isFalse();

        when(repository.findByIdempotencyKey(99L, "k-fresh")).thenReturn(Optional.empty());
        when(repository.insertIfAbsent(any(CashDividend.class))).thenAnswer(invocation ->
            Optional.of(persisted(invocation.getArgument(0), 15L)));

        DividendDto dto = service.createDividend(
            99L, new CreateDividendRequest("AAPL", new BigDecimal("5"), null, null, null, null), "k-fresh");

        assertThat(dto.reversed()).isFalse();
        verify(portfolioCache).invalidateSummaryAfterCommit(99L);
    }

    @Test
    @DisplayName("createDividend 標記 @Transactional：afterCommit 失效與 insert-first 需在同一交易內")
    void createDividendIsTransactional() throws NoSuchMethodException {
        Method method = DividendService.class.getMethod(
            "createDividend", Long.class, CreateDividendRequest.class, String.class);

        assertThat(method.getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    @DisplayName("沖銷：值與原列相同 → insert 的列 reversalOfId = 原列 id、paidAt = 時鐘現在；DTO reversalOfId = 原列 uuid，並失效 summary")
    void reversalIsInsertedWithOriginalIdAndMappedBackToUuid() {
        stubOriginal(originalDividend(null));
        when(repository.findByIdempotencyKey(USER_ID, "k-rev")).thenReturn(Optional.empty());
        when(repository.isReversed(ORIGINAL_ID)).thenReturn(false);
        when(repository.insertIfAbsent(any(CashDividend.class))).thenAnswer(invocation ->
            Optional.of(persisted(invocation.getArgument(0), 32L)));
        when(repository.uuidOf(ORIGINAL_ID)).thenReturn(Optional.of(ORIGINAL_UUID));

        DividendDto dto = service.createDividend(USER_ID,
            reversalRequest("aapl", "120", "30", ORIGINAL_UUID.toString()), "k-rev");

        ArgumentCaptor<CashDividend> captor = ArgumentCaptor.forClass(CashDividend.class);
        verify(repository).insertIfAbsent(captor.capture());
        CashDividend written = captor.getValue();
        assertThat(written.reversalOfId()).isEqualTo(ORIGINAL_ID);
        assertThat(written.assetId()).isEqualTo(AAPL_ID);
        assertThat(written.amount()).isEqualByComparingTo("120");
        assertThat(written.withholdingTax()).isEqualByComparingTo("30");
        assertThat(written.paidAt().toInstant()).isEqualTo(NOW.truncatedTo(ChronoUnit.MICROS));
        assertThat(written.idempotencyKey()).isEqualTo("k-rev");

        assertThat(dto.id()).isEqualTo(written.uuid().toString());
        assertThat(dto.reversalOfId()).isEqualTo(ORIGINAL_UUID.toString());
        assertThat(dto.reversed()).isFalse();
        verify(portfolioCache).invalidateSummaryAfterCommit(USER_ID);
    }

    @Test
    @DisplayName("沖銷：原列預扣稅 0、請求省略預扣稅 → 視為相同（省略視為 0），可沖銷")
    void reversalWithOmittedTaxMatchesZeroTaxOriginal() {
        stubOriginal(new CashDividend(ORIGINAL_ID, ORIGINAL_UUID, USER_ID, AAPL_ID, new BigDecimal("120.00000000"),
            new BigDecimal("0.00000000"), null, ORIGINAL_PAID_AT, ORIGINAL_PAID_AT, "k-orig", null));
        when(repository.findByIdempotencyKey(USER_ID, "k-rev0")).thenReturn(Optional.empty());
        when(repository.insertIfAbsent(any(CashDividend.class))).thenAnswer(invocation ->
            Optional.of(persisted(invocation.getArgument(0), 33L)));
        when(repository.uuidOf(ORIGINAL_ID)).thenReturn(Optional.of(ORIGINAL_UUID));

        DividendDto dto = service.createDividend(USER_ID,
            reversalRequest("AAPL", "120", null, ORIGINAL_UUID.toString()), "k-rev0");

        assertThat(dto.reversalOfId()).isEqualTo(ORIGINAL_UUID.toString());
    }

    @Test
    @DisplayName("reversalOfId 不是 UUID（含空白字串）：VALIDATION_FAILED fields.reversalOfId，未查帳本")
    void malformedReversalOfIdIsRejectedWithField() {
        for (String malformed : new String[] {"not-a-uuid", "   "}) {
            assertBusinessError(
                () -> service.createDividend(USER_ID, reversalRequest("AAPL", "120", "30", malformed), "k-bad"),
                ErrorCode.VALIDATION_FAILED, "reversalOfId");
        }
        verifyNoInteractions(repository, portfolioCache);
    }

    @Test
    @DisplayName("沖銷對象查無（含他人的）：DIVIDEND_NOT_FOUND，訊息不含 UUID，未寫入")
    void reversalOfUnknownDividendIsNotFound() {
        when(repository.findByUuid(USER_ID, ORIGINAL_UUID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createDividend(USER_ID,
            reversalRequest("AAPL", "120", "30", ORIGINAL_UUID.toString()), "k-404"))
            .isInstanceOf(BusinessException.class)
            .hasMessageNotContaining(ORIGINAL_UUID.toString())
            .extracting(exception -> ((BusinessException) exception).errorCode())
            .isEqualTo(ErrorCode.DIVIDEND_NOT_FOUND);
        verify(repository, never()).insertIfAbsent(any());
        verifyNoInteractions(portfolioCache);
    }

    @Test
    @DisplayName("沖銷對象已被沖銷：DIVIDEND_ALREADY_REVERSED，未寫入、不失效")
    void reversalOfAlreadyReversedDividendIsRejected() {
        stubOriginal(originalDividend(null));
        when(repository.findByIdempotencyKey(USER_ID, "k-twice")).thenReturn(Optional.empty());
        when(repository.isReversed(ORIGINAL_ID)).thenReturn(true);

        assertBusinessCode(() -> service.createDividend(USER_ID,
            reversalRequest("AAPL", "120", "30", ORIGINAL_UUID.toString()), "k-twice"),
            ErrorCode.DIVIDEND_ALREADY_REVERSED);
        verify(repository, never()).insertIfAbsent(any());
        verifyNoInteractions(portfolioCache);
    }

    @Test
    @DisplayName("沖銷對象本身是沖銷列：DIVIDEND_NOT_REVERSIBLE，未寫入、不失效")
    void reversalOfReversalIsNotReversible() {
        stubOriginal(originalDividend(5L));
        when(repository.findByIdempotencyKey(USER_ID, "k-chain")).thenReturn(Optional.empty());

        assertBusinessCode(() -> service.createDividend(USER_ID,
            reversalRequest("AAPL", "120", "30", ORIGINAL_UUID.toString()), "k-chain"),
            ErrorCode.DIVIDEND_NOT_REVERSIBLE);
        verify(repository, never()).insertIfAbsent(any());
        verifyNoInteractions(portfolioCache);
    }

    @Test
    @DisplayName("沖銷值與原列不符：amount / withholdingTax / symbol 分別指名對應 fields，訊息不回射輸入，未寫入")
    void reversalValueMismatchNamesTheField() {
        stubOriginal(originalDividend(null));
        when(repository.findByIdempotencyKey(eq(USER_ID), any())).thenReturn(Optional.empty());
        when(assetFacade.findBySymbol("MSFT")).thenReturn(Optional.of(
            new AssetSummary(MSFT_ID, "MSFT", "Microsoft", AssetType.STOCK, "US", true, true)));

        assertMismatch(reversalRequest("AAPL", "121", "30", ORIGINAL_UUID.toString()), "amount");
        assertMismatch(reversalRequest("AAPL", "120", "31", ORIGINAL_UUID.toString()), "withholdingTax");
        assertMismatch(reversalRequest("MSFT", "120", "30", ORIGINAL_UUID.toString()), "symbol");
        verify(repository, never()).insertIfAbsent(any());
        verifyNoInteractions(portfolioCache);
    }

    @Test
    @DisplayName("沖銷成功後以同一把 key 重送：快路徑回既有沖銷列，不因原列已沖銷而假性 409、不寫入、不失效")
    void replayedReversalReturnsExistingRowInsteadOfAlreadyReversed() {
        stubOriginal(originalDividend(null));
        CashDividend reversal = new CashDividend(34L, UUID.randomUUID(), USER_ID, AAPL_ID,
            new BigDecimal("120.00000000"), new BigDecimal("30.00000000"), null, ORIGINAL_PAID_AT.plusDays(1),
            ORIGINAL_PAID_AT.plusDays(1), "k-replay", ORIGINAL_ID);
        when(repository.findByIdempotencyKey(USER_ID, "k-replay")).thenReturn(Optional.of(reversal));
        when(repository.isReversed(ORIGINAL_ID)).thenReturn(true);
        when(repository.isReversed(34L)).thenReturn(false);
        when(repository.uuidOf(ORIGINAL_ID)).thenReturn(Optional.of(ORIGINAL_UUID));

        DividendDto dto = service.createDividend(USER_ID,
            reversalRequest("AAPL", "120", "30", ORIGINAL_UUID.toString()), "k-replay");

        assertThat(dto.id()).isEqualTo(reversal.uuid().toString());
        assertThat(dto.reversalOfId()).isEqualTo(ORIGINAL_UUID.toString());
        verify(repository, never()).insertIfAbsent(any());
        verifyNoInteractions(portfolioCache);
    }

    @Test
    @DisplayName("沖銷 insert 落空且以 key 重讀也落空（另一把 key 的沖銷搶先，uk_cash_dividends_reversal_of）：DIVIDEND_ALREADY_REVERSED")
    void reversalLosingTheRaceIsAlreadyReversed() {
        stubOriginal(originalDividend(null));
        when(repository.findByIdempotencyKey(USER_ID, "k-lost")).thenReturn(Optional.empty());
        when(repository.isReversed(ORIGINAL_ID)).thenReturn(false);
        when(repository.insertIfAbsent(any(CashDividend.class))).thenReturn(Optional.empty());

        assertBusinessCode(() -> service.createDividend(USER_ID,
            reversalRequest("AAPL", "120", "30", ORIGINAL_UUID.toString()), "k-lost"),
            ErrorCode.DIVIDEND_ALREADY_REVERSED);
        verify(repository, times(2)).findByIdempotencyKey(USER_ID, "k-lost");
        verifyNoInteractions(portfolioCache);
    }

    @Test
    @DisplayName("快路徑命中但 amount 不同：DIVIDEND_IDEMPOTENCY_KEY_REUSED，訊息不回射 key，不寫入、不失效")
    void sameKeyWithDifferentAmountIsKeyReused() {
        when(repository.findByIdempotencyKey(USER_ID, "LEAK-CANARY-KEY"))
            .thenReturn(Optional.of(existingDividend(41L, "LEAK-CANARY-KEY")));

        assertThatThrownBy(() -> service.createDividend(USER_ID,
            new CreateDividendRequest("AAPL", new BigDecimal("121"), null, null, null, null), "LEAK-CANARY-KEY"))
            .isInstanceOf(BusinessException.class)
            .hasMessageNotContaining("LEAK-CANARY-KEY")
            .extracting(exception -> ((BusinessException) exception).errorCode())
            .isEqualTo(ErrorCode.DIVIDEND_IDEMPOTENCY_KEY_REUSED);
        verify(repository, never()).insertIfAbsent(any());
        verifyNoInteractions(portfolioCache);
    }

    @Test
    @DisplayName("快路徑命中但明確送了不同的 paidAt：DIVIDEND_IDEMPOTENCY_KEY_REUSED")
    void sameKeyWithDifferentExplicitPaidAtIsKeyReused() {
        when(repository.findByIdempotencyKey(USER_ID, "k-time"))
            .thenReturn(Optional.of(existingDividend(42L, "k-time")));

        assertBusinessCode(() -> service.createDividend(USER_ID,
            new CreateDividendRequest("AAPL", new BigDecimal("120"), null,
                OffsetDateTime.parse("2026-10-02T09:00:00+08:00"), null, null), "k-time"),
            ErrorCode.DIVIDEND_IDEMPOTENCY_KEY_REUSED);
        verify(repository, never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("同一把 key 從一般紀錄切換成沖銷：DIVIDEND_IDEMPOTENCY_KEY_REUSED（沖銷對象是比對維度）")
    void sameKeySwitchingToReversalIsKeyReused() {
        stubOriginal(originalDividend(null));
        when(repository.findByIdempotencyKey(USER_ID, "k-switch")).thenReturn(Optional.of(
            new CashDividend(43L, UUID.randomUUID(), USER_ID, AAPL_ID, new BigDecimal("120.00000000"),
                new BigDecimal("30.00000000"), null, ORIGINAL_PAID_AT, ORIGINAL_PAID_AT, "k-switch", null)));

        assertBusinessCode(() -> service.createDividend(USER_ID,
            reversalRequest("AAPL", "120", "30", ORIGINAL_UUID.toString()), "k-switch"),
            ErrorCode.DIVIDEND_IDEMPOTENCY_KEY_REUSED);
        verify(repository, never()).insertIfAbsent(any());
        verifyNoInteractions(portfolioCache);
    }

    @Test
    @DisplayName("快路徑命中且只有 note 不同（數值 scale 也不同）：回既有那筆，不寫入、不失效")
    void sameKeyWithOnlyDifferentNoteReturnsExisting() {
        CashDividend existing = existingDividend(44L, "k-note");
        when(repository.findByIdempotencyKey(USER_ID, "k-note")).thenReturn(Optional.of(existing));

        DividendDto dto = service.createDividend(USER_ID,
            new CreateDividendRequest("AAPL", new BigDecimal("120.0"), new BigDecimal("0.00"), null, "changed", null),
            "k-note");

        assertThat(dto.id()).isEqualTo(existing.uuid().toString());
        verify(repository, never()).insertIfAbsent(any());
        verifyNoInteractions(portfolioCache);
    }

    @Test
    @DisplayName("insert 落空後以 key 重讀命中、但 payload 不同：DIVIDEND_IDEMPOTENCY_KEY_REUSED，不失效")
    void concurrentSameKeyWithDifferentPayloadIsKeyReused() {
        when(repository.findByIdempotencyKey(USER_ID, "k-race-diff"))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(existingDividend(45L, "k-race-diff")));
        when(repository.insertIfAbsent(any(CashDividend.class))).thenReturn(Optional.empty());

        assertBusinessCode(() -> service.createDividend(USER_ID,
            new CreateDividendRequest("AAPL", new BigDecimal("120"), new BigDecimal("5"), null, null, null),
            "k-race-diff"),
            ErrorCode.DIVIDEND_IDEMPOTENCY_KEY_REUSED);
        verifyNoInteractions(portfolioCache);
    }

    private void assertMismatch(CreateDividendRequest request, String field) {
        assertThatThrownBy(() -> service.createDividend(USER_ID, request, "k-mismatch-" + field))
            .isInstanceOf(BusinessException.class)
            .hasMessageNotContaining("121")
            .hasMessageNotContaining("31")
            .hasMessageNotContaining("MSFT")
            .satisfies(exception -> {
                BusinessException business = (BusinessException) exception;
                assertThat(business.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                assertThat(business.fields()).containsOnlyKeys(field);
            });
    }

    private void assertBusinessCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run)
            .isInstanceOf(BusinessException.class)
            .extracting(exception -> ((BusinessException) exception).errorCode())
            .isEqualTo(code);
    }

    private void stubOriginal(CashDividend original) {
        when(repository.findByUuid(USER_ID, ORIGINAL_UUID)).thenReturn(Optional.of(original));
    }

    private static CashDividend originalDividend(Long reversalOfId) {
        return new CashDividend(ORIGINAL_ID, ORIGINAL_UUID, USER_ID, AAPL_ID, new BigDecimal("120.00000000"),
            new BigDecimal("30.00000000"), "Q3", ORIGINAL_PAID_AT, ORIGINAL_PAID_AT, "k-orig", reversalOfId);
    }

    private static CreateDividendRequest reversalRequest(String symbol, String amount, String withholdingTax,
                                                         String reversalOfId) {
        return new CreateDividendRequest(symbol, new BigDecimal(amount),
            withholdingTax == null ? null : new BigDecimal(withholdingTax), null, null, reversalOfId);
    }

    private void assertBusinessError(Runnable call, ErrorCode code, String field) {
        assertThatThrownBy(call::run)
            .isInstanceOf(BusinessException.class)
            .satisfies(exception -> {
                BusinessException business = (BusinessException) exception;
                assertThat(business.errorCode()).isEqualTo(code);
                assertThat(business.fields()).containsKey(field);
            });
    }

    private static CashDividend persisted(CashDividend candidate, Long id) {
        return new CashDividend(
            id, candidate.uuid(), candidate.userId(), candidate.assetId(), candidate.amount(),
            candidate.withholdingTax(), candidate.note(), candidate.paidAt(),
            OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), candidate.idempotencyKey(), candidate.reversalOfId());
    }

    private static CashDividend existingDividend(Long id, String key) {
        OffsetDateTime paidAt = OffsetDateTime.parse("2026-10-01T09:00:00+08:00");
        return new CashDividend(id, UUID.randomUUID(), USER_ID, AAPL_ID, new BigDecimal("120.00000000"),
            BigDecimal.ZERO, null, paidAt, paidAt, key, null);
    }
}
