package dowob.xyz.stockwebv2.trading.service;

import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
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
import static org.mockito.Mockito.when;

/**
 * {@link CashLedgerService#createEntry} 的 insert-first 冪等寫入骨架單元測試（CASH-01）。
 *
 * <p>涵蓋 happy path、「已有同鍵紀錄走快路徑」，以及冪等命中時的 payload 比對（D-11）：
 * 相同回既有、不同回 409 {@code CASH_IDEMPOTENCY_KEY_REUSED}，快路徑與 insert 落空後重讀兩處都要經過比對。</p>
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
}
