package dowob.xyz.stockwebv2.trading.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ShareAdjustmentPayloadMatcher} 的單元測試：同一把冪等鍵重送的股數調整請求是否為同一筆意圖。
 *
 * <p>比對維度：assetId、type、配股 quantity（compareTo）或拆股 ratioNew + ratioOld、effectiveDate、reversalOfId；
 * 不比 note 與後端算出的 quantityDelta（拆股）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("股數調整冪等 payload 比對")
class ShareAdjustmentPayloadMatcherTest {

    private static final Long AAPL_ID = 1L;
    private static final LocalDate EX_DATE = LocalDate.parse("2026-10-29");

    @Test
    @DisplayName("配股 quantity 50 vs 讀回的 50.00000000 → 相同（compareTo）")
    void stockDividendQuantityIsComparedByValue() {
        assertThat(ShareAdjustmentPayloadMatcher.matches(dividend("50.00000000", null), AAPL_ID,
            ShareAdjustmentType.STOCK_DIVIDEND, new BigDecimal("50"), null, null, EX_DATE, null)).isTrue();
    }

    @Test
    @DisplayName("配股 quantity 不同 → 不同")
    void stockDividendQuantityDifferenceIsDetected() {
        assertThat(ShareAdjustmentPayloadMatcher.matches(dividend("50", null), AAPL_ID,
            ShareAdjustmentType.STOCK_DIVIDEND, new BigDecimal("51"), null, null, EX_DATE, null)).isFalse();
    }

    @Test
    @DisplayName("配股請求順帶送的 ratio 不比（寫入時也忽略）")
    void stockDividendIgnoresRatio() {
        assertThat(ShareAdjustmentPayloadMatcher.matches(dividend("50", null), AAPL_ID,
            ShareAdjustmentType.STOCK_DIVIDEND, new BigDecimal("50"), 4, 1, EX_DATE, null)).isTrue();
    }

    @Test
    @DisplayName("拆股 4:1 vs 4:2 → 不同；4:1 vs 4:1 → 相同（不比後端算出的 quantityDelta、不比請求順帶送的 quantity）")
    void splitRatioIsCompared() {
        ShareAdjustmentEntry existing = split(4, 1, null);
        assertThat(ShareAdjustmentPayloadMatcher.matches(existing, AAPL_ID, ShareAdjustmentType.SPLIT, null, 4, 2,
            EX_DATE, null)).isFalse();
        assertThat(ShareAdjustmentPayloadMatcher.matches(existing, AAPL_ID, ShareAdjustmentType.SPLIT, null, 3, 1,
            EX_DATE, null)).isFalse();
        assertThat(ShareAdjustmentPayloadMatcher.matches(existing, AAPL_ID, ShareAdjustmentType.SPLIT,
            new BigDecimal("999"), 4, 1, EX_DATE, null)).isTrue();
    }

    @Test
    @DisplayName("effectiveDate 不同 → 不同")
    void effectiveDateDifferenceIsDetected() {
        assertThat(ShareAdjustmentPayloadMatcher.matches(split(4, 1, null), AAPL_ID, ShareAdjustmentType.SPLIT,
            null, 4, 1, EX_DATE.plusDays(1), null)).isFalse();
    }

    @Test
    @DisplayName("type 不同 → 不同")
    void typeDifferenceIsDetected() {
        assertThat(ShareAdjustmentPayloadMatcher.matches(dividend("50", null), AAPL_ID, ShareAdjustmentType.SPLIT,
            null, 4, 1, EX_DATE, null)).isFalse();
    }

    @Test
    @DisplayName("assetId 不同 → 不同")
    void assetDifferenceIsDetected() {
        assertThat(ShareAdjustmentPayloadMatcher.matches(dividend("50", null), 2L,
            ShareAdjustmentType.STOCK_DIVIDEND, new BigDecimal("50"), null, null, EX_DATE, null)).isFalse();
    }

    @Test
    @DisplayName("reversalOfId：皆 null 或相同 → 相同；一個 null 一個不是、或指向不同原列 → 不同")
    void reversalOfIdIsCompared() {
        assertThat(ShareAdjustmentPayloadMatcher.matches(split(4, 1, 90L), AAPL_ID, ShareAdjustmentType.SPLIT,
            null, 4, 1, EX_DATE, 90L)).isTrue();
        assertThat(ShareAdjustmentPayloadMatcher.matches(split(4, 1, 90L), AAPL_ID, ShareAdjustmentType.SPLIT,
            null, 4, 1, EX_DATE, 91L)).isFalse();
        assertThat(ShareAdjustmentPayloadMatcher.matches(split(4, 1, null), AAPL_ID, ShareAdjustmentType.SPLIT,
            null, 4, 1, EX_DATE, 90L)).isFalse();
        assertThat(ShareAdjustmentPayloadMatcher.matches(split(4, 1, 90L), AAPL_ID, ShareAdjustmentType.SPLIT,
            null, 4, 1, EX_DATE, null)).isFalse();
    }

    @Test
    @DisplayName("note 不在比對簽章內：同 key 重送時順手改備註不會 409")
    void noteIsNotCompared() {
        ShareAdjustmentEntry withNote = new ShareAdjustmentEntry(9L, UUID.randomUUID(), 7L, AAPL_ID,
            ShareAdjustmentType.STOCK_DIVIDEND, null, null, new BigDecimal("50"), EX_DATE,
            OffsetDateTime.parse("2026-10-29T04:00:00Z"), "第一次的備註", OffsetDateTime.parse("2026-10-29T10:00:00Z"),
            "k", null);
        assertThat(ShareAdjustmentPayloadMatcher.matches(withNote, AAPL_ID, ShareAdjustmentType.STOCK_DIVIDEND,
            new BigDecimal("50"), null, null, EX_DATE, null)).isTrue();
    }

    private static ShareAdjustmentEntry dividend(String quantity, Long reversalOfId) {
        return new ShareAdjustmentEntry(9L, UUID.randomUUID(), 7L, AAPL_ID, ShareAdjustmentType.STOCK_DIVIDEND,
            null, null, new BigDecimal(quantity), EX_DATE, OffsetDateTime.parse("2026-10-29T04:00:00Z"), null,
            OffsetDateTime.parse("2026-10-29T10:00:00Z"), "k", reversalOfId);
    }

    private static ShareAdjustmentEntry split(int ratioNew, int ratioOld, Long reversalOfId) {
        return new ShareAdjustmentEntry(9L, UUID.randomUUID(), 7L, AAPL_ID, ShareAdjustmentType.SPLIT,
            ratioNew, ratioOld, new BigDecimal("300.00000000"), EX_DATE, OffsetDateTime.parse("2026-10-29T04:00:00Z"),
            null, OffsetDateTime.parse("2026-10-29T10:00:00Z"), "k", reversalOfId);
    }
}
