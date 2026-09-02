package dowob.xyz.stockwebv2.trading.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TradePayloadMatcher} 的純函式單元測試（D-07 同一冪等鍵重送時的 payload 比對）。
 *
 * <p>這個測試鎖的是「合法重試不得吃到假性 409」這條契約。既有交易是從 PostgreSQL 讀回來的，
 * 客戶端重送的值則是原封不動的請求內容，兩者在數值與時間上必然存在表示法差異：
 * {@code NUMERIC(24,8)} 讀回的 {@link BigDecimal} 一律是 scale 8，
 * 時間戳讀回的偏移量也未必等於送出時的偏移量。若比對誤用 {@code equals}，
 * 每一次合法重試都會被判定成「同鍵不同 payload」而回 409 —— 冪等保護反而變成故障源。</p>
 *
 * <p>另一面向是 D-06：只有備註不同不算 payload 不一致。使用者在逾時重試前順手改了備註，
 * 不該得到一個無法理解的 409。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("同 key 重送的 payload 比對")
class TradePayloadMatcherTest {

    private static final Long ASSET_ID = 55L;

    /** 模擬從 {@code NUMERIC(24,8)} 讀回的既有值：一律補滿 scale 8。 */
    private static final BigDecimal STORED_QUANTITY = new BigDecimal("10.00000000");
    private static final BigDecimal STORED_PRICE = new BigDecimal("123.45000000");
    private static final BigDecimal STORED_FEE = new BigDecimal("1.50000000");

    /** 模擬客戶端重送的原始值：同一個數，但 scale 完全不同。 */
    private static final BigDecimal SENT_QUANTITY = new BigDecimal("10");
    private static final BigDecimal SENT_PRICE = new BigDecimal("123.45");
    private static final BigDecimal SENT_FEE = new BigDecimal("1.5");

    /** 同一個瞬間的兩種寫法：既有值為 UTC，重送值帶 +08:00 偏移量。 */
    private static final OffsetDateTime STORED_EXECUTED_AT = OffsetDateTime.parse("2026-01-10T00:00:00Z");
    private static final OffsetDateTime SENT_EXECUTED_AT = OffsetDateTime.parse("2026-01-10T08:00:00+08:00");

    @Test
    @DisplayName("scale 不同的等值 BigDecimal 視為相同金額（絕不可用 equals）")
    void sameAmountIgnoresScaleDifference() {
        assertThat(TradePayloadMatcher.sameAmount(new BigDecimal("10"), new BigDecimal("10.00000000"))).isTrue();
    }

    @Test
    @DisplayName("真正不同的金額判定為不同")
    void sameAmountRejectsDifferentValue() {
        assertThat(TradePayloadMatcher.sameAmount(new BigDecimal("10"), new BigDecimal("10.5"))).isFalse();
    }

    @Test
    @DisplayName("金額兩邊皆 null 為相同；單邊 null 為不同")
    void sameAmountHandlesNulls() {
        assertThat(TradePayloadMatcher.sameAmount(null, null)).isTrue();
        assertThat(TradePayloadMatcher.sameAmount(null, BigDecimal.ZERO)).isFalse();
        assertThat(TradePayloadMatcher.sameAmount(BigDecimal.ZERO, null)).isFalse();
    }

    @Test
    @DisplayName("偏移量不同但瞬間相同的時間視為同一瞬間（絕不可用 equals）")
    void sameInstantIgnoresOffsetDifference() {
        assertThat(TradePayloadMatcher.sameInstant(
            OffsetDateTime.parse("2026-01-10T00:00:00Z"),
            OffsetDateTime.parse("2026-01-10T08:00:00+08:00"))).isTrue();
    }

    @Test
    @DisplayName("真正不同的瞬間判定為不同；兩邊皆 null 為相同、單邊 null 為不同")
    void sameInstantRejectsDifferentInstantAndHandlesNulls() {
        assertThat(TradePayloadMatcher.sameInstant(
            OffsetDateTime.parse("2026-01-10T00:00:00Z"),
            OffsetDateTime.parse("2026-01-10T00:00:01Z"))).isFalse();
        assertThat(TradePayloadMatcher.sameInstant(null, null)).isTrue();
        assertThat(TradePayloadMatcher.sameInstant(null, STORED_EXECUTED_AT)).isFalse();
        assertThat(TradePayloadMatcher.sameInstant(STORED_EXECUTED_AT, null)).isFalse();
    }

    @Test
    @DisplayName("六個受比對欄位完全相同時判定為同一 payload，scale 與偏移量差異皆被吸收")
    void matchesAbsorbsScaleAndOffsetDifferences() {
        assertThat(TradePayloadMatcher.matches(
            storedTransaction("原始備註"),
            ASSET_ID, TradeType.BUY, SENT_QUANTITY, SENT_PRICE, SENT_FEE, SENT_EXECUTED_AT)).isTrue();
    }

    @Test
    @DisplayName("只有備註不同仍視為同一 payload（D-06：備註刻意不納入比對）")
    void matchesIgnoresRemarkDifference() {
        assertThat(TradePayloadMatcher.matches(
            storedTransaction("重試前順手改掉的備註"),
            ASSET_ID, TradeType.BUY, SENT_QUANTITY, SENT_PRICE, SENT_FEE, SENT_EXECUTED_AT)).isTrue();
    }

    @Test
    @DisplayName("assetId 不同判定為不同 payload")
    void matchesRejectsDifferentAssetId() {
        assertThat(TradePayloadMatcher.matches(
            storedTransaction("原始備註"),
            56L, TradeType.BUY, SENT_QUANTITY, SENT_PRICE, SENT_FEE, SENT_EXECUTED_AT)).isFalse();
    }

    @Test
    @DisplayName("交易類型不同判定為不同 payload")
    void matchesRejectsDifferentType() {
        assertThat(TradePayloadMatcher.matches(
            storedTransaction("原始備註"),
            ASSET_ID, TradeType.SELL, SENT_QUANTITY, SENT_PRICE, SENT_FEE, SENT_EXECUTED_AT)).isFalse();
    }

    @Test
    @DisplayName("數量不同判定為不同 payload")
    void matchesRejectsDifferentQuantity() {
        assertThat(TradePayloadMatcher.matches(
            storedTransaction("原始備註"),
            ASSET_ID, TradeType.BUY, new BigDecimal("11"), SENT_PRICE, SENT_FEE, SENT_EXECUTED_AT)).isFalse();
    }

    @Test
    @DisplayName("價格不同判定為不同 payload")
    void matchesRejectsDifferentPrice() {
        assertThat(TradePayloadMatcher.matches(
            storedTransaction("原始備註"),
            ASSET_ID, TradeType.BUY, SENT_QUANTITY, new BigDecimal("123.46"), SENT_FEE, SENT_EXECUTED_AT)).isFalse();
    }

    @Test
    @DisplayName("手續費不同判定為不同 payload")
    void matchesRejectsDifferentFee() {
        assertThat(TradePayloadMatcher.matches(
            storedTransaction("原始備註"),
            ASSET_ID, TradeType.BUY, SENT_QUANTITY, SENT_PRICE, new BigDecimal("2"), SENT_EXECUTED_AT)).isFalse();
    }

    @Test
    @DisplayName("成交時間不同判定為不同 payload")
    void matchesRejectsDifferentExecutedAt() {
        assertThat(TradePayloadMatcher.matches(
            storedTransaction("原始備註"),
            ASSET_ID, TradeType.BUY, SENT_QUANTITY, SENT_PRICE, SENT_FEE,
            OffsetDateTime.parse("2026-01-11T00:00:00Z"))).isFalse();
    }

    /**
     * 建立一筆「從資料庫讀回」形狀的既有交易：金額 scale 補滿 8、成交時間為 UTC。
     *
     * @param remark 備註內容，用來證明它不影響比對結果
     * @return 既有交易
     */
    private TradeTransaction storedTransaction(String remark) {
        return new TradeTransaction(
            9L,
            UUID.fromString("00000000-0000-0000-0000-0000000000ff"),
            7L,
            ASSET_ID,
            "AAPL",
            TradeType.BUY,
            STORED_QUANTITY,
            STORED_PRICE,
            STORED_FEE,
            remark,
            STORED_EXECUTED_AT,
            OffsetDateTime.parse("2026-01-10T00:00:01Z"),
            "key-1"
        );
    }
}
