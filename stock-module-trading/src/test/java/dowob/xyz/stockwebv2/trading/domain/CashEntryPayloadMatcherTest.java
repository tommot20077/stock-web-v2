package dowob.xyz.stockwebv2.trading.domain;

import dowob.xyz.stockwebv2.common.model.CurrencyCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CashEntryPayloadMatcher} 的純函式單元測試（D-11：同一冪等鍵重送時的 payload 比對）。
 *
 * <p>既有分錄是從 PostgreSQL 讀回來的：金額一律是 scale 8、時間偏移量帶連線時區；
 * 客戶端重送的則是原封不動的請求內容。比對若誤用 {@code equals}，每次合法重試都會吃到假性 409。
 * 另一面向：備註不同不算 payload 不一致；請求省略 {@code occurredAt} 時不比對時間。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("入出金同 key 重送的 payload 比對")
class CashEntryPayloadMatcherTest {

    /** 模擬從 {@code NUMERIC(24,8)} 讀回的既有值：一律補滿 scale 8。 */
    private static final BigDecimal STORED_AMOUNT = new BigDecimal("100.00000000");

    private static final OffsetDateTime STORED_OCCURRED_AT = OffsetDateTime.parse("2026-10-01T01:00:00Z");

    /** 同一個瞬間的另一種寫法：重送值帶 +08:00 偏移量。 */
    private static final OffsetDateTime SENT_OCCURRED_AT = OffsetDateTime.parse("2026-10-01T09:00:00+08:00");

    private static CashLedgerEntry stored(String note) {
        return new CashLedgerEntry(
            1L, UUID.randomUUID(), 7L, CashEntryType.DEPOSIT, CurrencyCode.TWD, STORED_AMOUNT,
            note, STORED_OCCURRED_AT, STORED_OCCURRED_AT, "key-1", null);
    }

    @Test
    @DisplayName("amount 100 與 100.00000000 視為相同（絕不可用 equals）")
    void amountIgnoresScaleDifference() {
        assertThat(CashEntryPayloadMatcher.matches(
            stored(null), CashEntryType.DEPOSIT, CurrencyCode.TWD, new BigDecimal("100"), SENT_OCCURRED_AT)).isTrue();
    }

    @Test
    @DisplayName("occurredAt 偏移量不同但同一瞬間視為相同")
    void occurredAtIgnoresOffsetDifference() {
        assertThat(CashEntryPayloadMatcher.matches(
            stored(null), CashEntryType.DEPOSIT, CurrencyCode.TWD, new BigDecimal("100"), SENT_OCCURRED_AT)).isTrue();
    }

    @Test
    @DisplayName("請求省略 occurredAt（null）時不比對時間：伺服器首次決定的時間成立，否則每次重試都是假性 409")
    void omittedOccurredAtIsNotCompared() {
        assertThat(CashEntryPayloadMatcher.matches(
            stored(null), CashEntryType.DEPOSIT, CurrencyCode.TWD, new BigDecimal("100"), null)).isTrue();
    }

    @Test
    @DisplayName("occurredAt 有送且瞬間不同 → 不符")
    void differentOccurredAtDoesNotMatch() {
        assertThat(CashEntryPayloadMatcher.matches(
            stored(null), CashEntryType.DEPOSIT, CurrencyCode.TWD, new BigDecimal("100"),
            STORED_OCCURRED_AT.plusSeconds(1))).isFalse();
    }

    @Test
    @DisplayName("type 不同 → 不符")
    void differentTypeDoesNotMatch() {
        assertThat(CashEntryPayloadMatcher.matches(
            stored(null), CashEntryType.WITHDRAWAL, CurrencyCode.TWD, new BigDecimal("100"), null)).isFalse();
    }

    @Test
    @DisplayName("currency 不同 → 不符")
    void differentCurrencyDoesNotMatch() {
        assertThat(CashEntryPayloadMatcher.matches(
            stored(null), CashEntryType.DEPOSIT, CurrencyCode.USD, new BigDecimal("100"), null)).isFalse();
    }

    @Test
    @DisplayName("amount 數值不同 → 不符")
    void differentAmountDoesNotMatch() {
        assertThat(CashEntryPayloadMatcher.matches(
            stored(null), CashEntryType.DEPOSIT, CurrencyCode.TWD, new BigDecimal("100.00000001"), null)).isFalse();
    }

    @Test
    @DisplayName("備註不影響比對：既有分錄有備註、比對方法根本不接收備註（D-06 同 trade）")
    void noteDoesNotAffectMatching() {
        assertThat(CashEntryPayloadMatcher.matches(
            stored("salary of October"), CashEntryType.DEPOSIT, CurrencyCode.TWD, new BigDecimal("100"), null)).isTrue();
    }
}
