package dowob.xyz.stockwebv2.trading.service;

import dowob.xyz.stockwebv2.common.api.PageResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.error.FieldValidationException;
import dowob.xyz.stockwebv2.common.model.CurrencyCode;
import dowob.xyz.stockwebv2.trading.api.CashEntryDto;
import dowob.xyz.stockwebv2.trading.api.CreateCashEntryRequest;
import dowob.xyz.stockwebv2.trading.domain.CashEntryType;
import dowob.xyz.stockwebv2.trading.domain.CashEntryView;
import dowob.xyz.stockwebv2.trading.domain.CashLedgerEntry;
import dowob.xyz.stockwebv2.trading.repository.CashLedgerRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link CashLedgerService#createEntry} 的 insert-first 冪等寫入骨架單元測試（CASH-01）。
 *
 * <p>涵蓋 happy path、「已有同鍵紀錄走快路徑」，以及冪等命中時的 payload 比對（D-11）：
 * 相同回既有、不同回 409 {@code CASH_IDEMPOTENCY_KEY_REUSED}，快路徑與 insert 落空後重讀兩處都要經過比對；
 * 以及欄位驗證規則：key / type / currency / occurredAt 不合法時以 {@code fields} 指名欄位，
 * 且一律在任何 repository 呼叫之前、錯誤訊息不回射輸入。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("現金分錄寫入服務")
class CashLedgerServiceTest {

    private CashLedgerRepository repository;
    private PortfolioCache portfolioCache;
    private CashLedgerService service;

    @BeforeEach
    void setup() {
        repository = mock(CashLedgerRepository.class);
        portfolioCache = mock(PortfolioCache.class);
        service = new CashLedgerService(repository, portfolioCache);
    }

    @Test
    @DisplayName("入金：type / currency 正規化、note trim、occurredAt 預設現在並截到微秒，回傳 DTO 不含冪等鍵")
    void depositIsNormalizedInsertedAndMappedToDto() {
        when(repository.findByIdempotencyKey(7L, "key-1")).thenReturn(Optional.empty());
        when(repository.insertIfAbsent(any(CashLedgerEntry.class))).thenAnswer(invocation -> {
            CashLedgerEntry candidate = invocation.getArgument(0);
            return Optional.of(new CashLedgerEntry(
                11L, candidate.uuid(), candidate.userId(), candidate.type(), candidate.currency(),
                candidate.amount(), candidate.note(), candidate.occurredAt(),
                candidate.occurredAt(), candidate.idempotencyKey(), candidate.reversalOfId()));
        });
        OffsetDateTime before = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);

        CashEntryDto dto = service.createEntry(
            7L,
            new CreateCashEntryRequest("deposit", "twd", new BigDecimal("50000"), " salary ", null, null),
            "key-1");

        ArgumentCaptor<CashLedgerEntry> captor = ArgumentCaptor.forClass(CashLedgerEntry.class);
        verify(repository).insertIfAbsent(captor.capture());
        CashLedgerEntry written = captor.getValue();
        assertThat(written.userId()).isEqualTo(7L);
        assertThat(written.type()).isEqualTo(CashEntryType.DEPOSIT);
        assertThat(written.currency()).isEqualTo(CurrencyCode.TWD);
        assertThat(written.amount()).isEqualByComparingTo("50000");
        assertThat(written.note()).isEqualTo("salary");
        assertThat(written.idempotencyKey()).isEqualTo("key-1");
        assertThat(written.reversalOfId()).isNull();
        assertThat(written.occurredAt()).isAfterOrEqualTo(before);
        assertThat(written.occurredAt().getNano() % 1_000).as("截到微秒").isZero();

        assertThat(dto.id()).isEqualTo(written.uuid().toString());
        assertThat(dto.type()).isEqualTo("DEPOSIT");
        assertThat(dto.currency()).isEqualTo("TWD");
        assertThat(dto.amount()).isEqualByComparingTo("50000");
        assertThat(dto.note()).isEqualTo("salary");
        assertThat(dto.reversed()).isFalse();
        assertThat(dto.reversalOfId()).isNull();
        assertThat(dto.reversalOfOccurredAt()).isNull();
    }

    @Test
    @DisplayName("出金不查餘額也不擋（D-01 / D-08）：沒有任何餘額相關互動，直接寫入")
    void withdrawalIsRecordedWithoutAnyBalanceCheck() {
        when(repository.findByIdempotencyKey(7L, "key-2")).thenReturn(Optional.empty());
        when(repository.insertIfAbsent(any(CashLedgerEntry.class))).thenAnswer(invocation -> {
            CashLedgerEntry candidate = invocation.getArgument(0);
            return Optional.of(new CashLedgerEntry(
                12L, candidate.uuid(), candidate.userId(), candidate.type(), candidate.currency(),
                candidate.amount(), candidate.note(), candidate.occurredAt(),
                candidate.occurredAt(), candidate.idempotencyKey(), null));
        });

        CashEntryDto dto = service.createEntry(
            7L,
            new CreateCashEntryRequest("WITHDRAWAL", "USD", new BigDecimal("1000"), null, null, null),
            "key-2");

        assertThat(dto.type()).isEqualTo("WITHDRAWAL");
        assertThat(dto.note()).isNull();
    }

    @Test
    @DisplayName("同使用者同鍵已存在：快路徑回既有分錄，不再寫入")
    void existingKeyReturnsKnownEntryWithoutInserting() {
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-10-01T09:00:00+08:00");
        UUID uuid = UUID.randomUUID();
        when(repository.findByIdempotencyKey(7L, "key-3")).thenReturn(Optional.of(new CashLedgerEntry(
            13L, uuid, 7L, CashEntryType.DEPOSIT, CurrencyCode.TWD, new BigDecimal("50000.00000000"),
            null, occurredAt, occurredAt, "key-3", null)));

        CashEntryDto dto = service.createEntry(
            7L,
            new CreateCashEntryRequest("DEPOSIT", "TWD", new BigDecimal("50000"), null, occurredAt, null),
            "key-3");

        assertThat(dto.id()).isEqualTo(uuid.toString());
        verify(repository, never()).insertIfAbsent(any(CashLedgerEntry.class));
    }

    private static CashLedgerEntry existing(UUID uuid, CashEntryType type, CurrencyCode currency,
                                            String amount, OffsetDateTime occurredAt) {
        return new CashLedgerEntry(
            21L, uuid, 7L, type, currency, new BigDecimal(amount), null, occurredAt, occurredAt, "key-x", null);
    }

    private static void assertReused(Throwable thrown) {
        assertThat(thrown).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.errorCode()).isEqualTo(ErrorCode.CASH_IDEMPOTENCY_KEY_REUSED);
            assertThat(exception.getMessage()).isEqualTo(ErrorCode.CASH_IDEMPOTENCY_KEY_REUSED.defaultMessage());
        });
    }

    @Test
    @DisplayName("快路徑命中但 amount 不同：409 CASH_IDEMPOTENCY_KEY_REUSED，不寫入")
    void fastPathHitWithDifferentAmountIsRejected() {
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-10-01T09:00:00+08:00");
        when(repository.findByIdempotencyKey(7L, "key-x")).thenReturn(Optional.of(
            existing(UUID.randomUUID(), CashEntryType.DEPOSIT, CurrencyCode.TWD, "50000.00000000", occurredAt)));

        assertThatThrownBy(() -> service.createEntry(
            7L, new CreateCashEntryRequest("DEPOSIT", "TWD", new BigDecimal("50001"), null, occurredAt, null), "key-x"))
            .satisfies(CashLedgerServiceTest::assertReused);
        verify(repository, never()).insertIfAbsent(any(CashLedgerEntry.class));
    }

    @Test
    @DisplayName("快路徑命中但 type 或 currency 不同：409")
    void fastPathHitWithDifferentTypeOrCurrencyIsRejected() {
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-10-01T09:00:00+08:00");
        when(repository.findByIdempotencyKey(7L, "key-x")).thenReturn(Optional.of(
            existing(UUID.randomUUID(), CashEntryType.DEPOSIT, CurrencyCode.TWD, "50000.00000000", occurredAt)));

        assertThatThrownBy(() -> service.createEntry(
            7L, new CreateCashEntryRequest("WITHDRAWAL", "TWD", new BigDecimal("50000"), null, occurredAt, null), "key-x"))
            .satisfies(CashLedgerServiceTest::assertReused);
        assertThatThrownBy(() -> service.createEntry(
            7L, new CreateCashEntryRequest("DEPOSIT", "USD", new BigDecimal("50000"), null, occurredAt, null), "key-x"))
            .satisfies(CashLedgerServiceTest::assertReused);
        verify(repository, never()).insertIfAbsent(any(CashLedgerEntry.class));
    }

    @Test
    @DisplayName("快路徑命中、只有 note 不同：視為同一筆，回既有分錄（備註不納入比對）")
    void fastPathHitWithOnlyDifferentNoteReturnsExisting() {
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-10-01T09:00:00+08:00");
        UUID uuid = UUID.randomUUID();
        when(repository.findByIdempotencyKey(7L, "key-x")).thenReturn(Optional.of(
            existing(uuid, CashEntryType.DEPOSIT, CurrencyCode.TWD, "50000.00000000", occurredAt)));

        CashEntryDto dto = service.createEntry(
            7L, new CreateCashEntryRequest("DEPOSIT", "TWD", new BigDecimal("50000"), "changed note", occurredAt, null), "key-x");

        assertThat(dto.id()).isEqualTo(uuid.toString());
        verify(repository, never()).insertIfAbsent(any(CashLedgerEntry.class));
    }

    @Test
    @DisplayName("重送省略 occurredAt：仍命中原分錄（伺服器首次決定的時間成立）")
    void fastPathHitWithOmittedOccurredAtReturnsExisting() {
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-10-01T09:00:00+08:00");
        UUID uuid = UUID.randomUUID();
        when(repository.findByIdempotencyKey(7L, "key-x")).thenReturn(Optional.of(
            existing(uuid, CashEntryType.DEPOSIT, CurrencyCode.TWD, "50000.00000000", occurredAt)));

        CashEntryDto dto = service.createEntry(
            7L, new CreateCashEntryRequest("DEPOSIT", "TWD", new BigDecimal("50000"), null, null, null), "key-x");

        assertThat(dto.id()).isEqualTo(uuid.toString());
        verify(repository, never()).insertIfAbsent(any(CashLedgerEntry.class));
    }

    @Test
    @DisplayName("insert 落空後重讀命中且 payload 相同：回既有分錄")
    void insertMissThenRereadWithSamePayloadReturnsExisting() {
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-10-01T09:00:00+08:00");
        UUID uuid = UUID.randomUUID();
        when(repository.findByIdempotencyKey(7L, "key-x"))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(
                existing(uuid, CashEntryType.DEPOSIT, CurrencyCode.TWD, "50000.00000000", occurredAt)));
        when(repository.insertIfAbsent(any(CashLedgerEntry.class))).thenReturn(Optional.empty());

        CashEntryDto dto = service.createEntry(
            7L, new CreateCashEntryRequest("DEPOSIT", "TWD", new BigDecimal("50000"), null, occurredAt, null), "key-x");

        assertThat(dto.id()).isEqualTo(uuid.toString());
    }

    @Test
    @DisplayName("insert 落空後重讀命中但 payload 不同：409 CASH_IDEMPOTENCY_KEY_REUSED（落空重讀也要經過比對）")
    void insertMissThenRereadWithDifferentPayloadIsRejected() {
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-10-01T09:00:00+08:00");
        when(repository.findByIdempotencyKey(7L, "key-x"))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(
                existing(UUID.randomUUID(), CashEntryType.DEPOSIT, CurrencyCode.TWD, "50000.00000000", occurredAt)));
        when(repository.insertIfAbsent(any(CashLedgerEntry.class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createEntry(
            7L, new CreateCashEntryRequest("DEPOSIT", "TWD", new BigDecimal("99"), null, occurredAt, null), "key-x"))
            .satisfies(CashLedgerServiceTest::assertReused);
    }

    @Test
    @DisplayName("insert 落空且重讀仍落空（理論殘餘競態）：409 DUPLICATE_RESOURCE，而非 500")
    void insertMissThenRereadMissIsDuplicateResource() {
        when(repository.findByIdempotencyKey(7L, "key-x")).thenReturn(Optional.empty());
        when(repository.insertIfAbsent(any(CashLedgerEntry.class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createEntry(
            7L, new CreateCashEntryRequest("DEPOSIT", "TWD", new BigDecimal("50000"), null, null, null), "key-x"))
            .isInstanceOfSatisfying(BusinessException.class, exception -> {
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
                assertThat(exception.getMessage()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE.defaultMessage());
            });
    }

    private static CreateCashEntryRequest validRequest() {
        return new CreateCashEntryRequest("DEPOSIT", "TWD", new BigDecimal("100"), null, null, null);
    }

    private static void assertFieldRejected(Throwable thrown, String field, String reason) {
        assertThat(thrown).isInstanceOfSatisfying(FieldValidationException.class, exception -> {
            assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(exception.fields()).containsOnlyKeys(field).containsEntry(field, reason);
        });
    }

    @Test
    @DisplayName("Idempotency-Key 空白：fields 指名 Idempotency-Key，repository 零互動")
    void blankIdempotencyKeyIsRejectedBeforeAnyRepositoryCall() {
        assertThatThrownBy(() -> service.createEntry(7L, validRequest(), "   "))
            .satisfies(thrown -> assertFieldRejected(thrown, "Idempotency-Key", "must not be blank"));
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("Idempotency-Key 129 字元：fields 指名 Idempotency-Key 且訊息不含鍵值，repository 零互動")
    void oversizedIdempotencyKeyIsRejectedWithoutEchoingIt() {
        String oversized = "k".repeat(129);

        assertThatThrownBy(() -> service.createEntry(7L, validRequest(), oversized))
            .satisfies(thrown -> assertFieldRejected(
                thrown, "Idempotency-Key", "must be at most 128 characters"))
            .satisfies(thrown -> assertThat(thrown.getMessage()).doesNotContain(oversized));
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("Idempotency-Key 恰 128 字元：通過驗證")
    void idempotencyKeyOfExactlyMaxLengthIsAccepted() {
        String atLimit = "k".repeat(128);
        when(repository.findByIdempotencyKey(7L, atLimit)).thenReturn(Optional.empty());
        when(repository.insertIfAbsent(any(CashLedgerEntry.class))).thenAnswer(invocation -> {
            CashLedgerEntry candidate = invocation.getArgument(0);
            return Optional.of(candidate);
        });

        CashEntryDto dto = service.createEntry(7L, validRequest(), atLimit);

        assertThat(dto.type()).isEqualTo("DEPOSIT");
    }

    @Test
    @DisplayName("currency GBP：fields.currency，訊息不回射輸入，repository 零互動")
    void unsupportedCurrencyIsRejectedBeforeAnyRepositoryCall() {
        assertThatThrownBy(() -> service.createEntry(
            7L, new CreateCashEntryRequest("DEPOSIT", "GBP", new BigDecimal("100"), null, null, null), "key-v"))
            .satisfies(thrown -> assertFieldRejected(thrown, "currency", "unsupported currency"))
            .satisfies(thrown -> assertThat(thrown.getMessage()).doesNotContain("GBP"));
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("type TRANSFER：fields.type，訊息不回射輸入，repository 零互動")
    void unsupportedTypeIsRejectedBeforeAnyRepositoryCall() {
        assertThatThrownBy(() -> service.createEntry(
            7L, new CreateCashEntryRequest("TRANSFER", "TWD", new BigDecimal("100"), null, null, null), "key-v"))
            .satisfies(thrown -> assertFieldRejected(thrown, "type", "unsupported type"))
            .satisfies(thrown -> assertThat(thrown.getMessage()).doesNotContain("TRANSFER"));
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("occurredAt 為現在 + 10 分鐘：fields.occurredAt = must not be in the future，repository 零互動")
    void futureOccurredAtIsRejectedBeforeAnyRepositoryCall() {
        OffsetDateTime future = OffsetDateTime.now().plus(Duration.ofMinutes(10));

        assertThatThrownBy(() -> service.createEntry(
            7L, new CreateCashEntryRequest("DEPOSIT", "TWD", new BigDecimal("100"), null, future, null), "key-v"))
            .satisfies(thrown -> assertFieldRejected(thrown, "occurredAt", "must not be in the future"));
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("occurredAt 為現在 + 1 分鐘（5 分鐘容忍內，吸收時鐘偏差）：通過驗證")
    void occurredAtWithinClockSkewToleranceIsAccepted() {
        OffsetDateTime slightlyAhead = OffsetDateTime.now().plus(Duration.ofMinutes(1));
        when(repository.findByIdempotencyKey(7L, "key-v")).thenReturn(Optional.empty());
        when(repository.insertIfAbsent(any(CashLedgerEntry.class))).thenAnswer(invocation -> {
            CashLedgerEntry candidate = invocation.getArgument(0);
            return Optional.of(candidate);
        });

        CashEntryDto dto = service.createEntry(
            7L, new CreateCashEntryRequest("DEPOSIT", "TWD", new BigDecimal("100"), null, slightlyAhead, null), "key-v");

        assertThat(dto.type()).isEqualTo("DEPOSIT");
    }

    @Test
    @DisplayName("列表：page -3 / size 999 被夾限成 0 / 100 再交給 repository；view 映射成 DTO（指回原分錄、reversed 照 view）")
    void listEntriesClampsPagingAndMapsViewsToDtos() {
        OffsetDateTime originalAt = OffsetDateTime.parse("2026-10-01T09:00:00+08:00");
        OffsetDateTime reversalAt = OffsetDateTime.parse("2026-10-02T09:00:00+08:00");
        UUID originalUuid = UUID.randomUUID();
        UUID reversalUuid = UUID.randomUUID();
        CashLedgerEntry original = new CashLedgerEntry(
            1L, originalUuid, 7L, CashEntryType.DEPOSIT, CurrencyCode.USD, new BigDecimal("1000.00000000"),
            "seed", originalAt, originalAt, "k-1", null);
        CashLedgerEntry reversal = new CashLedgerEntry(
            2L, reversalUuid, 7L, CashEntryType.WITHDRAWAL, CurrencyCode.USD, new BigDecimal("1000.00000000"),
            null, reversalAt, reversalAt, "k-2", 1L);
        when(repository.listEntries(7L, 0, 100)).thenReturn(PageResponse.of(List.of(
            new CashEntryView(reversal, originalUuid, originalAt, false),
            new CashEntryView(original, null, null, true)), 0, 100, 2));

        PageResponse<CashEntryDto> page = service.listEntries(7L, -3, 999);

        verify(repository).listEntries(7L, 0, 100);
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.items()).hasSize(2);
        CashEntryDto reversalDto = page.items().get(0);
        assertThat(reversalDto.id()).isEqualTo(reversalUuid.toString());
        assertThat(reversalDto.reversalOfId()).isEqualTo(originalUuid.toString());
        assertThat(reversalDto.reversalOfOccurredAt()).isEqualTo(originalAt);
        assertThat(reversalDto.reversed()).isFalse();
        CashEntryDto originalDto = page.items().get(1);
        assertThat(originalDto.reversalOfId()).isNull();
        assertThat(originalDto.reversalOfOccurredAt()).isNull();
        assertThat(originalDto.reversed()).isTrue();
    }

    @Test
    @DisplayName("列表：page 超過上限 10000 夾限成 10000，size 0 夾限成 1")
    void listEntriesClampsUpperPageAndLowerSize() {
        when(repository.listEntries(7L, 10_000, 1)).thenReturn(PageResponse.of(List.of(), 10_000, 1, 0));

        PageResponse<CashEntryDto> page = service.listEntries(7L, 50_000, 0);

        verify(repository).listEntries(7L, 10_000, 1);
        assertThat(page.items()).isEmpty();
    }

    // ── 一鍵沖銷（D-25）────────────────────────────────────────────────────────

    private static final OffsetDateTime ORIGINAL_AT = OffsetDateTime.parse("2026-10-01T09:00:00+08:00");

    private static CashLedgerEntry original(UUID uuid, CashEntryType type, CurrencyCode currency, String amount) {
        return new CashLedgerEntry(
            31L, uuid, 7L, type, currency, new BigDecimal(amount), null, ORIGINAL_AT, ORIGINAL_AT, "key-orig", null);
    }

    private static CreateCashEntryRequest reversalRequest(
        String type, String currency, String amount, UUID originalUuid) {
        return new CreateCashEntryRequest(
            type, currency, new BigDecimal(amount), null, null, originalUuid.toString());
    }

    private static void assertBusiness(Throwable thrown, ErrorCode code) {
        assertThat(thrown).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.errorCode()).isEqualTo(code);
            assertThat(exception.getMessage()).isEqualTo(code.defaultMessage());
        });
    }

    @Test
    @DisplayName("沖銷：reversalOfId 格式錯誤 → fields.reversalOfId = invalid format，repository 零互動，訊息不回射輸入")
    void malformedReversalOfIdIsRejectedBeforeAnyRepositoryCall() {
        assertThatThrownBy(() -> service.createEntry(
            7L,
            new CreateCashEntryRequest("WITHDRAWAL", "USD", new BigDecimal("1000"), null, null, "not-a-uuid"),
            "key-r"))
            .satisfies(thrown -> assertFieldRejected(thrown, "reversalOfId", "invalid format"))
            .satisfies(thrown -> assertThat(thrown.getMessage()).doesNotContain("not-a-uuid"));
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("沖銷：原分錄查無（含別人的）→ CASH_ENTRY_NOT_FOUND，訊息為靜態字串、不含 UUID，不寫入")
    void unknownOriginalIsNotFoundWithoutLeakingTheUuid() {
        UUID missing = UUID.randomUUID();
        when(repository.findByUuid(7L, missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createEntry(
            7L, reversalRequest("WITHDRAWAL", "USD", "1000", missing), "key-r"))
            .satisfies(thrown -> assertBusiness(thrown, ErrorCode.CASH_ENTRY_NOT_FOUND))
            .satisfies(thrown -> assertThat(thrown.getMessage())
                .isEqualTo("Cash entry not found")
                .doesNotContain(missing.toString()));
        verify(repository, never()).insertIfAbsent(any(CashLedgerEntry.class));
    }

    @Test
    @DisplayName("沖銷：同 key 已存在且 payload（含 reversalOfId）相同 → 回既有，不因「已沖銷」而拒絕（冪等優先）")
    void idempotentReplayOfSuccessfulReversalWinsOverAlreadyReversed() {
        UUID originalUuid = UUID.randomUUID();
        UUID reversalUuid = UUID.randomUUID();
        when(repository.findByUuid(7L, originalUuid)).thenReturn(Optional.of(
            original(originalUuid, CashEntryType.DEPOSIT, CurrencyCode.USD, "1000.00000000")));
        when(repository.findByIdempotencyKey(7L, "key-r")).thenReturn(Optional.of(new CashLedgerEntry(
            32L, reversalUuid, 7L, CashEntryType.WITHDRAWAL, CurrencyCode.USD, new BigDecimal("1000.00000000"),
            null, ORIGINAL_AT.plusDays(1), ORIGINAL_AT.plusDays(1), "key-r", 31L)));
        // 即使此時原分錄「已被沖銷」，重送也必須回同一筆；因此根本不該去問 existsReversalOf。
        when(repository.existsReversalOf(31L)).thenReturn(true);

        CashEntryDto dto = service.createEntry(
            7L, reversalRequest("WITHDRAWAL", "USD", "1000", originalUuid), "key-r");

        assertThat(dto.id()).isEqualTo(reversalUuid.toString());
        assertThat(dto.reversalOfId()).isEqualTo(originalUuid.toString());
        assertThat(dto.reversalOfOccurredAt()).isEqualTo(ORIGINAL_AT);
        assertThat(dto.reversed()).isFalse();
        verify(repository, never()).existsReversalOf(any());
        verify(repository, never()).insertIfAbsent(any(CashLedgerEntry.class));
    }

    @Test
    @DisplayName("沖銷：同 key 已存在但沖銷對象不同 → 409 CASH_IDEMPOTENCY_KEY_REUSED")
    void sameKeyWithDifferentReversalTargetIsRejected() {
        UUID originalUuid = UUID.randomUUID();
        when(repository.findByUuid(7L, originalUuid)).thenReturn(Optional.of(
            original(originalUuid, CashEntryType.DEPOSIT, CurrencyCode.USD, "1000.00000000")));
        when(repository.findByIdempotencyKey(7L, "key-r")).thenReturn(Optional.of(new CashLedgerEntry(
            32L, UUID.randomUUID(), 7L, CashEntryType.WITHDRAWAL, CurrencyCode.USD, new BigDecimal("1000.00000000"),
            null, ORIGINAL_AT, ORIGINAL_AT, "key-r", 99L)));

        assertThatThrownBy(() -> service.createEntry(
            7L, reversalRequest("WITHDRAWAL", "USD", "1000", originalUuid), "key-r"))
            .satisfies(CashLedgerServiceTest::assertReused);
    }

    @Test
    @DisplayName("沖銷：原分錄本身是沖銷分錄 → CASH_ENTRY_NOT_REVERSIBLE，不寫入")
    void reversingAReversalIsNotReversible() {
        UUID reversalUuid = UUID.randomUUID();
        when(repository.findByUuid(7L, reversalUuid)).thenReturn(Optional.of(new CashLedgerEntry(
            32L, reversalUuid, 7L, CashEntryType.WITHDRAWAL, CurrencyCode.USD, new BigDecimal("1000.00000000"),
            null, ORIGINAL_AT, ORIGINAL_AT, "key-prev", 31L)));
        when(repository.findByIdempotencyKey(7L, "key-r")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createEntry(
            7L, reversalRequest("DEPOSIT", "USD", "1000", reversalUuid), "key-r"))
            .satisfies(thrown -> assertBusiness(thrown, ErrorCode.CASH_ENTRY_NOT_REVERSIBLE));
        verify(repository, never()).insertIfAbsent(any(CashLedgerEntry.class));
    }

    @Test
    @DisplayName("沖銷：原分錄已被沖銷 → CASH_ENTRY_ALREADY_REVERSED，不寫入")
    void reversingAnAlreadyReversedEntryIsRejected() {
        UUID originalUuid = UUID.randomUUID();
        when(repository.findByUuid(7L, originalUuid)).thenReturn(Optional.of(
            original(originalUuid, CashEntryType.DEPOSIT, CurrencyCode.USD, "1000.00000000")));
        when(repository.findByIdempotencyKey(7L, "key-r")).thenReturn(Optional.empty());
        when(repository.existsReversalOf(31L)).thenReturn(true);

        assertThatThrownBy(() -> service.createEntry(
            7L, reversalRequest("WITHDRAWAL", "USD", "1000", originalUuid), "key-r"))
            .satisfies(thrown -> assertBusiness(thrown, ErrorCode.CASH_ENTRY_ALREADY_REVERSED));
        verify(repository, never()).insertIfAbsent(any(CashLedgerEntry.class));
    }

    @Test
    @DisplayName("沖銷：方向未相反 / 幣別不同 / 金額不同 → fields.reversalOfId = must mirror the original entry，不寫入")
    void reversalThatDoesNotMirrorTheOriginalIsRejected() {
        UUID originalUuid = UUID.randomUUID();
        when(repository.findByUuid(7L, originalUuid)).thenReturn(Optional.of(
            original(originalUuid, CashEntryType.DEPOSIT, CurrencyCode.USD, "1000.00000000")));
        when(repository.findByIdempotencyKey(7L, "key-r")).thenReturn(Optional.empty());
        when(repository.existsReversalOf(31L)).thenReturn(false);

        assertThatThrownBy(() -> service.createEntry(
            7L, reversalRequest("DEPOSIT", "USD", "1000", originalUuid), "key-r"))
            .as("方向未相反")
            .satisfies(thrown -> assertFieldRejected(thrown, "reversalOfId", "must mirror the original entry"));
        assertThatThrownBy(() -> service.createEntry(
            7L, reversalRequest("WITHDRAWAL", "TWD", "1000", originalUuid), "key-r"))
            .as("幣別不同")
            .satisfies(thrown -> assertFieldRejected(thrown, "reversalOfId", "must mirror the original entry"));
        assertThatThrownBy(() -> service.createEntry(
            7L, reversalRequest("WITHDRAWAL", "USD", "999", originalUuid), "key-r"))
            .as("金額不同")
            .satisfies(thrown -> assertFieldRejected(thrown, "reversalOfId", "must mirror the original entry"));
        verify(repository, never()).insertIfAbsent(any(CashLedgerEntry.class));
    }

    @Test
    @DisplayName("沖銷：金額 scale 不同但數值相等（1000 對 1000.00000000）視為鏡像")
    void mirrorComparisonIgnoresAmountScale() {
        UUID originalUuid = UUID.randomUUID();
        when(repository.findByUuid(7L, originalUuid)).thenReturn(Optional.of(
            original(originalUuid, CashEntryType.DEPOSIT, CurrencyCode.USD, "1000.00000000")));
        when(repository.findByIdempotencyKey(7L, "key-r")).thenReturn(Optional.empty());
        when(repository.existsReversalOf(31L)).thenReturn(false);
        when(repository.insertIfAbsent(any(CashLedgerEntry.class))).thenAnswer(invocation ->
            Optional.of(invocation.<CashLedgerEntry>getArgument(0)));

        CashEntryDto dto = service.createEntry(
            7L, reversalRequest("withdrawal", "usd", "1000", originalUuid), "key-r");

        assertThat(dto.type()).isEqualTo("WITHDRAWAL");
    }

    @Test
    @DisplayName("沖銷成功：寫入的分錄指向原分錄主鍵，DTO 帶原 UUID 與原發生時間，reversed = false")
    void successfulReversalPointsAtTheOriginal() {
        UUID originalUuid = UUID.randomUUID();
        when(repository.findByUuid(7L, originalUuid)).thenReturn(Optional.of(
            original(originalUuid, CashEntryType.DEPOSIT, CurrencyCode.USD, "1000.00000000")));
        when(repository.findByIdempotencyKey(7L, "key-r")).thenReturn(Optional.empty());
        when(repository.existsReversalOf(31L)).thenReturn(false);
        when(repository.insertIfAbsent(any(CashLedgerEntry.class))).thenAnswer(invocation ->
            Optional.of(invocation.<CashLedgerEntry>getArgument(0)));

        CashEntryDto dto = service.createEntry(
            7L, reversalRequest("WITHDRAWAL", "USD", "1000", originalUuid), "key-r");

        ArgumentCaptor<CashLedgerEntry> captor = ArgumentCaptor.forClass(CashLedgerEntry.class);
        verify(repository).insertIfAbsent(captor.capture());
        assertThat(captor.getValue().reversalOfId()).isEqualTo(31L);
        assertThat(captor.getValue().type()).isEqualTo(CashEntryType.WITHDRAWAL);
        assertThat(dto.reversalOfId()).isEqualTo(originalUuid.toString());
        assertThat(dto.reversalOfOccurredAt()).isEqualTo(ORIGINAL_AT);
        assertThat(dto.reversed()).isFalse();
    }

    @Test
    @DisplayName("沖銷併發落敗：insert 回空且以 key 重讀落空 → CASH_ENTRY_ALREADY_REVERSED（另一把 key 的沖銷搶先）")
    void reversalLosingTheRaceIsAlreadyReversed() {
        UUID originalUuid = UUID.randomUUID();
        when(repository.findByUuid(7L, originalUuid)).thenReturn(Optional.of(
            original(originalUuid, CashEntryType.DEPOSIT, CurrencyCode.USD, "1000.00000000")));
        when(repository.findByIdempotencyKey(7L, "key-r")).thenReturn(Optional.empty());
        when(repository.existsReversalOf(31L)).thenReturn(false);
        when(repository.insertIfAbsent(any(CashLedgerEntry.class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createEntry(
            7L, reversalRequest("WITHDRAWAL", "USD", "1000", originalUuid), "key-r"))
            .satisfies(thrown -> assertBusiness(thrown, ErrorCode.CASH_ENTRY_ALREADY_REVERSED));
    }

    @Test
    @DisplayName("沖銷併發落敗但以同一把 key 重讀命中（同 key 併發重送）→ 回既有分錄，不誤報已沖銷")
    void reversalLosingTheRaceToTheSameKeyReturnsExisting() {
        UUID originalUuid = UUID.randomUUID();
        UUID reversalUuid = UUID.randomUUID();
        when(repository.findByUuid(7L, originalUuid)).thenReturn(Optional.of(
            original(originalUuid, CashEntryType.DEPOSIT, CurrencyCode.USD, "1000.00000000")));
        when(repository.findByIdempotencyKey(7L, "key-r"))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(new CashLedgerEntry(
                32L, reversalUuid, 7L, CashEntryType.WITHDRAWAL, CurrencyCode.USD, new BigDecimal("1000.00000000"),
                null, ORIGINAL_AT, ORIGINAL_AT, "key-r", 31L)));
        when(repository.existsReversalOf(31L)).thenReturn(false);
        when(repository.insertIfAbsent(any(CashLedgerEntry.class))).thenReturn(Optional.empty());

        CashEntryDto dto = service.createEntry(
            7L, reversalRequest("WITHDRAWAL", "USD", "1000", originalUuid), "key-r");

        assertThat(dto.id()).isEqualTo(reversalUuid.toString());
        assertThat(dto.reversalOfId()).isEqualTo(originalUuid.toString());
    }

    @Test
    @DisplayName("新建入金：commit 之後失效該使用者的 summary 快取一次（summary 含現金項，D-22）")
    void newDepositInvalidatesSummaryCacheOnce() {
        when(repository.findByIdempotencyKey(7L, "key-inv")).thenReturn(Optional.empty());
        when(repository.insertIfAbsent(any(CashLedgerEntry.class))).thenAnswer(invocation ->
            Optional.of(invocation.<CashLedgerEntry>getArgument(0)));

        service.createEntry(
            7L, new CreateCashEntryRequest("DEPOSIT", "TWD", new BigDecimal("50000"), null, null, null), "key-inv");

        verify(portfolioCache, times(1)).invalidateSummaryAfterCommit(7L);
        verifyNoMoreInteractions(portfolioCache);
    }

    @Test
    @DisplayName("新建沖銷分錄同樣失效 summary 快取一次（沖銷是反向分錄，現金會變）")
    void newReversalInvalidatesSummaryCacheOnce() {
        UUID originalUuid = UUID.randomUUID();
        when(repository.findByUuid(7L, originalUuid)).thenReturn(Optional.of(
            original(originalUuid, CashEntryType.DEPOSIT, CurrencyCode.USD, "1000.00000000")));
        when(repository.findByIdempotencyKey(7L, "key-r")).thenReturn(Optional.empty());
        when(repository.existsReversalOf(31L)).thenReturn(false);
        when(repository.insertIfAbsent(any(CashLedgerEntry.class))).thenAnswer(invocation ->
            Optional.of(invocation.<CashLedgerEntry>getArgument(0)));

        service.createEntry(7L, reversalRequest("WITHDRAWAL", "USD", "1000", originalUuid), "key-r");

        verify(portfolioCache, times(1)).invalidateSummaryAfterCommit(7L);
    }

    @Test
    @DisplayName("冪等命中（快路徑）資料未變，不失效快取（比照 Phase 4 DP-8）")
    void idempotentFastPathHitDoesNotInvalidateSummaryCache() {
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-10-01T09:00:00+08:00");
        when(repository.findByIdempotencyKey(7L, "key-3")).thenReturn(Optional.of(new CashLedgerEntry(
            13L, UUID.randomUUID(), 7L, CashEntryType.DEPOSIT, CurrencyCode.TWD, new BigDecimal("50000.00000000"),
            null, occurredAt, occurredAt, "key-3", null)));

        service.createEntry(
            7L, new CreateCashEntryRequest("DEPOSIT", "TWD", new BigDecimal("50000"), null, occurredAt, null), "key-3");

        verify(portfolioCache, never()).invalidateSummaryAfterCommit(anyLong());
    }

    @Test
    @DisplayName("insert 落空後重讀命中（同 key 併發落敗）也不失效快取：這個請求沒有寫入任何資料")
    void insertMissThenRereadHitDoesNotInvalidateSummaryCache() {
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-10-01T09:00:00+08:00");
        when(repository.findByIdempotencyKey(7L, "key-4"))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(new CashLedgerEntry(
                14L, UUID.randomUUID(), 7L, CashEntryType.DEPOSIT, CurrencyCode.TWD, new BigDecimal("50000.00000000"),
                null, occurredAt, occurredAt, "key-4", null)));
        when(repository.insertIfAbsent(any(CashLedgerEntry.class))).thenReturn(Optional.empty());

        service.createEntry(
            7L, new CreateCashEntryRequest("DEPOSIT", "TWD", new BigDecimal("50000"), null, occurredAt, null), "key-4");

        verify(portfolioCache, never()).invalidateSummaryAfterCommit(anyLong());
    }

    @Test
    @DisplayName("被拒的請求（驗證失敗、原分錄不存在、已沖銷、併發落敗）都不失效快取")
    void rejectedRequestsDoNotInvalidateSummaryCache() {
        UUID missing = UUID.randomUUID();
        when(repository.findByUuid(7L, missing)).thenReturn(Optional.empty());
        UUID reversedUuid = UUID.randomUUID();
        when(repository.findByUuid(7L, reversedUuid)).thenReturn(Optional.of(
            original(reversedUuid, CashEntryType.DEPOSIT, CurrencyCode.USD, "1000.00000000")));
        when(repository.existsReversalOf(31L)).thenReturn(true);
        when(repository.findByIdempotencyKey(7L, "key-lose")).thenReturn(Optional.empty());
        when(repository.insertIfAbsent(any(CashLedgerEntry.class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createEntry(
            7L, new CreateCashEntryRequest("TRANSFER", "TWD", new BigDecimal("1"), null, null, null), "key-v"))
            .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.createEntry(
            7L, reversalRequest("WITHDRAWAL", "USD", "1000", missing), "key-nf"))
            .satisfies(thrown -> assertBusiness(thrown, ErrorCode.CASH_ENTRY_NOT_FOUND));
        assertThatThrownBy(() -> service.createEntry(
            7L, reversalRequest("WITHDRAWAL", "USD", "1000", reversedUuid), "key-ar"))
            .satisfies(thrown -> assertBusiness(thrown, ErrorCode.CASH_ENTRY_ALREADY_REVERSED));
        assertThatThrownBy(() -> service.createEntry(
            7L, new CreateCashEntryRequest("DEPOSIT", "TWD", new BigDecimal("1"), null, null, null), "key-lose"))
            .satisfies(thrown -> assertBusiness(thrown, ErrorCode.DUPLICATE_RESOURCE));

        verifyNoInteractions(portfolioCache);
    }
}
