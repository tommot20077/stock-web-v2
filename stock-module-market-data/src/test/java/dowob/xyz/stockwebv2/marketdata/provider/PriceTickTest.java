package dowob.xyz.stockwebv2.marketdata.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * {@link PriceTick} value object 單元測試。
 *
 * <p>驗證：
 * <ul>
 *   <li>合法參數建構成功</li>
 *   <li>必填欄位為 null 時拋出 {@link NullPointerException}</li>
 *   <li>{@code volume} 允許為 null</li>
 *   <li>Java record 的 {@code equals} / {@code hashCode} 正確運作</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0.0
 */
class PriceTickTest {

    private static final String   SYMBOL = "AAPL";
    private static final Instant  TIME   = Instant.parse("2024-01-01T10:00:00Z");
    private static final BigDecimal PRICE  = new BigDecimal("150.00");
    private static final BigDecimal VOLUME = new BigDecimal("1000.00");

    // ── 建構成功 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("合法參數（含 volume）→ 建構成功，所有欄位正確")
    void constructor_validArgs_withVolume_succeeds() {
        PriceTick tick = new PriceTick(SYMBOL, TIME, PRICE, VOLUME);

        assertThat(tick.symbol()).isEqualTo(SYMBOL);
        assertThat(tick.time()).isEqualTo(TIME);
        assertThat(tick.price()).isEqualByComparingTo(PRICE);
        assertThat(tick.volume()).isEqualByComparingTo(VOLUME);
    }

    @Test
    @DisplayName("volume 為 null → 建構成功（volume 可選欄位）")
    void constructor_nullVolume_succeeds() {
        PriceTick tick = new PriceTick(SYMBOL, TIME, PRICE, null);

        assertThat(tick.symbol()).isEqualTo(SYMBOL);
        assertThat(tick.time()).isEqualTo(TIME);
        assertThat(tick.price()).isEqualByComparingTo(PRICE);
        assertThat(tick.volume()).isNull();
    }

    // ── null 驗證 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("symbol 為 null → 拋出 NullPointerException")
    void constructor_nullSymbol_throwsNpe() {
        assertThatNullPointerException()
                .isThrownBy(() -> new PriceTick(null, TIME, PRICE, VOLUME))
                .withMessageContaining("symbol");
    }

    @Test
    @DisplayName("time 為 null → 拋出 NullPointerException")
    void constructor_nullTime_throwsNpe() {
        assertThatNullPointerException()
                .isThrownBy(() -> new PriceTick(SYMBOL, null, PRICE, VOLUME))
                .withMessageContaining("time");
    }

    @Test
    @DisplayName("price 為 null → 拋出 NullPointerException")
    void constructor_nullPrice_throwsNpe() {
        assertThatNullPointerException()
                .isThrownBy(() -> new PriceTick(SYMBOL, TIME, null, VOLUME))
                .withMessageContaining("price");
    }

    // ── equals / hashCode ────────────────────────────────────────────────────

    @Test
    @DisplayName("相同欄位的兩個 PriceTick → equals 為 true，hashCode 相同")
    void equalsAndHashCode_sameFields_areEqual() {
        PriceTick a = new PriceTick(SYMBOL, TIME, PRICE, VOLUME);
        PriceTick b = new PriceTick(SYMBOL, TIME, PRICE, VOLUME);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("price 不同 → equals 為 false")
    void equalsAndHashCode_differentPrice_notEqual() {
        PriceTick a = new PriceTick(SYMBOL, TIME, PRICE, VOLUME);
        PriceTick b = new PriceTick(SYMBOL, TIME, new BigDecimal("999.99"), VOLUME);

        assertThat(a).isNotEqualTo(b);
    }
}
