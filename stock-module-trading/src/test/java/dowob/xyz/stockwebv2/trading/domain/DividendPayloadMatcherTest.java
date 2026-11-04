package dowob.xyz.stockwebv2.trading.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DividendPayloadMatcher} 的單元測試：同一把冪等鍵重送的現金股利請求是否為同一筆意圖（DIV-01）。
 *
 * <p>比對維度：assetId、amount（compareTo）、withholdingTax（compareTo）、有送才比的 paidAt、reversalOfId；
 * 不比 note。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("現金股利冪等 payload 比對")
class DividendPayloadMatcherTest {

    private static final Long AAPL_ID = 1L;
    private static final OffsetDateTime PAID_AT = OffsetDateTime.parse("2026-10-30T09:15:00.123456+08:00");

    @Test
    @DisplayName("amount 120 vs 讀回的 120.00000000 → 相同（compareTo）")
    void amountIsComparedByValue() {
        assertThat(DividendPayloadMatcher.matches(existing("120.00000000", "0.00000000", null, null), AAPL_ID,
            new BigDecimal("120"), BigDecimal.ZERO, null, null)).isTrue();
    }

    @Test
    @DisplayName("amount 不同 → 不同")
    void amountDifferenceIsDetected() {
        assertThat(DividendPayloadMatcher.matches(existing("120", "0", null, null), AAPL_ID,
            new BigDecimal("120.00000001"), BigDecimal.ZERO, null, null)).isFalse();
    }

    @Test
    @DisplayName("withholdingTax 30 vs 30.00000000 → 相同；30 vs 31 → 不同")
    void withholdingTaxIsComparedByValue() {
        CashDividend existing = existing("120", "30.00000000", null, null);
        assertThat(DividendPayloadMatcher.matches(existing, AAPL_ID, new BigDecimal("120"), new BigDecimal("30"),
            null, null)).isTrue();
        assertThat(DividendPayloadMatcher.matches(existing, AAPL_ID, new BigDecimal("120"), new BigDecimal("31"),
            null, null)).isFalse();
    }

    @Test
    @DisplayName("assetId 不同 → 不同")
    void assetDifferenceIsDetected() {
        assertThat(DividendPayloadMatcher.matches(existing("120", "0", null, null), 2L,
            new BigDecimal("120"), BigDecimal.ZERO, null, null)).isFalse();
    }

    @Test
    @DisplayName("reversalOfId 一個 null 一個非 null（兩個方向）或指向不同原列 → 不同；相同 → 相同")
    void reversalOfIdIsCompared() {
        assertThat(DividendPayloadMatcher.matches(existing("120", "0", null, null), AAPL_ID,
            new BigDecimal("120"), BigDecimal.ZERO, null, 31L)).isFalse();
        assertThat(DividendPayloadMatcher.matches(existing("120", "0", null, 31L), AAPL_ID,
            new BigDecimal("120"), BigDecimal.ZERO, null, null)).isFalse();
        assertThat(DividendPayloadMatcher.matches(existing("120", "0", null, 31L), AAPL_ID,
            new BigDecimal("120"), BigDecimal.ZERO, null, 32L)).isFalse();
        assertThat(DividendPayloadMatcher.matches(existing("120", "0", null, 31L), AAPL_ID,
            new BigDecimal("120"), BigDecimal.ZERO, null, 31L)).isTrue();
    }

    @Test
    @DisplayName("請求未送 paidAt → 不比較（伺服器首次決定的時間成立）")
    void omittedPaidAtIsNotCompared() {
        assertThat(DividendPayloadMatcher.matches(existing("120", "0", null, null), AAPL_ID,
            new BigDecimal("120"), BigDecimal.ZERO, null, null)).isTrue();
    }

    @Test
    @DisplayName("請求有送 paidAt：同一瞬間（不同 offset）→ 相同；差 1 微秒 → 不同")
    void explicitPaidAtIsComparedAsInstant() {
        CashDividend existing = existing("120", "0", null, null);
        assertThat(DividendPayloadMatcher.matches(existing, AAPL_ID, new BigDecimal("120"), BigDecimal.ZERO,
            PAID_AT.withOffsetSameInstant(ZoneOffset.UTC), null)).isTrue();
        assertThat(DividendPayloadMatcher.matches(existing, AAPL_ID, new BigDecimal("120"), BigDecimal.ZERO,
            PAID_AT.plusNanos(1_000), null)).isFalse();
    }

    @Test
    @DisplayName("note 不參與比對：既有列有備註、重送沒有（或不同）仍視為同一筆")
    void noteIsNotCompared() {
        assertThat(DividendPayloadMatcher.matches(existing("120", "0", "Q3 dividend", null), AAPL_ID,
            new BigDecimal("120"), BigDecimal.ZERO, PAID_AT, null)).isTrue();
    }

    private static CashDividend existing(String amount, String withholdingTax, String note, Long reversalOfId) {
        return new CashDividend(11L, UUID.randomUUID(), 7L, AAPL_ID, new BigDecimal(amount),
            new BigDecimal(withholdingTax), note, PAID_AT, PAID_AT, "k1", reversalOfId);
    }
}
