package dowob.xyz.stockwebv2.trading.service;

import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.error.FieldValidationException;
import dowob.xyz.stockwebv2.common.model.CurrencyCode;
import dowob.xyz.stockwebv2.trading.api.CashEntryDto;
import dowob.xyz.stockwebv2.trading.api.CreateCashEntryRequest;
import dowob.xyz.stockwebv2.trading.domain.CashEntryType;
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
    private CashLedgerService service;

    @BeforeEach
    void setup() {
        repository = mock(CashLedgerRepository.class);
        service = new CashLedgerService(repository);
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
            new CreateCashEntryRequest("deposit", "twd", new BigDecimal("50000"), " salary ", null),
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
            new CreateCashEntryRequest("WITHDRAWAL", "USD", new BigDecimal("1000"), null, null),
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
            new CreateCashEntryRequest("DEPOSIT", "TWD", new BigDecimal("50000"), null, occurredAt),
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
            7L, new CreateCashEntryRequest("DEPOSIT", "TWD", new BigDecimal("50001"), null, occurredAt), "key-x"))
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
            7L, new CreateCashEntryRequest("WITHDRAWAL", "TWD", new BigDecimal("50000"), null, occurredAt), "key-x"))
            .satisfies(CashLedgerServiceTest::assertReused);
        assertThatThrownBy(() -> service.createEntry(
            7L, new CreateCashEntryRequest("DEPOSIT", "USD", new BigDecimal("50000"), null, occurredAt), "key-x"))
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
            7L, new CreateCashEntryRequest("DEPOSIT", "TWD", new BigDecimal("50000"), "changed note", occurredAt), "key-x");

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
            7L, new CreateCashEntryRequest("DEPOSIT", "TWD", new BigDecimal("50000"), null, null), "key-x");

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
            7L, new CreateCashEntryRequest("DEPOSIT", "TWD", new BigDecimal("50000"), null, occurredAt), "key-x");

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
            7L, new CreateCashEntryRequest("DEPOSIT", "TWD", new BigDecimal("99"), null, occurredAt), "key-x"))
            .satisfies(CashLedgerServiceTest::assertReused);
    }

    @Test
    @DisplayName("insert 落空且重讀仍落空（理論殘餘競態）：409 DUPLICATE_RESOURCE，而非 500")
    void insertMissThenRereadMissIsDuplicateResource() {
        when(repository.findByIdempotencyKey(7L, "key-x")).thenReturn(Optional.empty());
        when(repository.insertIfAbsent(any(CashLedgerEntry.class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createEntry(
            7L, new CreateCashEntryRequest("DEPOSIT", "TWD", new BigDecimal("50000"), null, null), "key-x"))
            .isInstanceOfSatisfying(BusinessException.class, exception -> {
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
                assertThat(exception.getMessage()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE.defaultMessage());
            });
    }

    private static CreateCashEntryRequest validRequest() {
        return new CreateCashEntryRequest("DEPOSIT", "TWD", new BigDecimal("100"), null, null);
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
            7L, new CreateCashEntryRequest("DEPOSIT", "GBP", new BigDecimal("100"), null, null), "key-v"))
            .satisfies(thrown -> assertFieldRejected(thrown, "currency", "unsupported currency"))
            .satisfies(thrown -> assertThat(thrown.getMessage()).doesNotContain("GBP"));
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("type TRANSFER：fields.type，訊息不回射輸入，repository 零互動")
    void unsupportedTypeIsRejectedBeforeAnyRepositoryCall() {
        assertThatThrownBy(() -> service.createEntry(
            7L, new CreateCashEntryRequest("TRANSFER", "TWD", new BigDecimal("100"), null, null), "key-v"))
            .satisfies(thrown -> assertFieldRejected(thrown, "type", "unsupported type"))
            .satisfies(thrown -> assertThat(thrown.getMessage()).doesNotContain("TRANSFER"));
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("occurredAt 為現在 + 10 分鐘：fields.occurredAt = must not be in the future，repository 零互動")
    void futureOccurredAtIsRejectedBeforeAnyRepositoryCall() {
        OffsetDateTime future = OffsetDateTime.now().plus(Duration.ofMinutes(10));

        assertThatThrownBy(() -> service.createEntry(
            7L, new CreateCashEntryRequest("DEPOSIT", "TWD", new BigDecimal("100"), null, future), "key-v"))
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
            7L, new CreateCashEntryRequest("DEPOSIT", "TWD", new BigDecimal("100"), null, slightlyAhead), "key-v");

        assertThat(dto.type()).isEqualTo("DEPOSIT");
    }
}
