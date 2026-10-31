package dowob.xyz.stockwebv2.infrastructure.fx;

import dowob.xyz.stockwebv2.common.model.CurrencyCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FxRates} 匯率換算順序的單元測試，期望值以 V2 seed 的四列匯率寫死（Pitfall 8）。
 *
 * <p>V2 seed：{@code USD/TWD 32.418}、{@code EUR/USD 1.0832}、{@code USD/JPY 156.42}、
 * {@code TWD/USD 0.030847}。
 *
 * @author Yuan
 * @version 1.0.0
 */
@DisplayName("匯率三角換算")
class FxRatesTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-01-01T00:00:00Z");

    private static FxRate row(CurrencyCode base, CurrencyCode quote, String rate) {
        return new FxRate(base, quote, new BigDecimal(rate), NOW);
    }

    private static FxRates seed() {
        return FxRates.of(List.of(
                row(CurrencyCode.USD, CurrencyCode.TWD, "32.418"),
                row(CurrencyCode.EUR, CurrencyCode.USD, "1.0832"),
                row(CurrencyCode.USD, CurrencyCode.JPY, "156.42"),
                row(CurrencyCode.TWD, CurrencyCode.USD, "0.030847")));
    }

    @Test
    @DisplayName("同幣別匯率為 1")
    void sameCurrencyIsOne() {
        assertThat(seed().rate(CurrencyCode.TWD, CurrencyCode.TWD))
                .hasValueSatisfying(r -> assertThat(r).isEqualByComparingTo("1"));
    }

    @Test
    @DisplayName("有直接列時取直接列")
    void directRowIsUsed() {
        assertThat(seed().rate(CurrencyCode.USD, CurrencyCode.TWD))
                .hasValueSatisfying(r -> assertThat(r).isEqualByComparingTo("32.418"));
    }

    @Test
    @DisplayName("直接列優先於反向列：TWD→USD 取 seed 的 0.030847 而非 1/32.418")
    void directRowTakesPriorityOverInverse() {
        BigDecimal inverse = BigDecimal.ONE.divide(new BigDecimal("32.418"), 16, RoundingMode.HALF_UP);

        assertThat(seed().rate(CurrencyCode.TWD, CurrencyCode.USD))
                .hasValueSatisfying(r -> {
                    assertThat(r).isEqualByComparingTo("0.030847");
                    assertThat(r).isNotEqualByComparingTo(inverse);
                });
    }

    @Test
    @DisplayName("無直接列時取反向列：JPY→USD = 1 / 156.42")
    void inverseRowIsUsedWhenNoDirectRow() {
        BigDecimal expected = BigDecimal.ONE.divide(new BigDecimal("156.42"), 16, RoundingMode.HALF_UP);

        assertThat(seed().rate(CurrencyCode.JPY, CurrencyCode.USD))
                .hasValueSatisfying(r -> assertThat(r).isEqualByComparingTo(expected));
    }

    @Test
    @DisplayName("經 USD 三角換算：EUR→TWD = 1.0832 × 32.418 = 35.1151776")
    void triangulatesThroughUsd() {
        assertThat(seed().rate(CurrencyCode.EUR, CurrencyCode.TWD))
                .hasValueSatisfying(r -> assertThat(r).isEqualByComparingTo("35.1151776"));
    }

    @Test
    @DisplayName("三角換算含反向腿：JPY→TWD = (1/156.42) × 32.418")
    void triangulatesWithInverseLeg() {
        BigDecimal jpyToUsd = BigDecimal.ONE.divide(new BigDecimal("156.42"), 16, RoundingMode.HALF_UP);
        BigDecimal expected = jpyToUsd.multiply(new BigDecimal("32.418"));

        assertThat(seed().rate(CurrencyCode.JPY, CurrencyCode.TWD))
                .hasValueSatisfying(r -> assertThat(r).isEqualByComparingTo(expected));
    }

    @Test
    @DisplayName("換不到時回 empty：缺腿與空表皆然")
    void returnsEmptyWhenNoPathExists() {
        FxRates onlyUsdTwd = FxRates.of(List.of(row(CurrencyCode.USD, CurrencyCode.TWD, "32.418")));

        assertThat(onlyUsdTwd.rate(CurrencyCode.EUR, CurrencyCode.JPY)).isEmpty();
        assertThat(FxRates.empty().rate(CurrencyCode.USD, CurrencyCode.TWD)).isEmpty();
    }

    @Test
    @DisplayName("非正匯率列被忽略，不參與反向除法也不拋 ArithmeticException")
    void nonPositiveRowsAreIgnored() {
        FxRates rates = FxRates.of(List.of(
                row(CurrencyCode.USD, CurrencyCode.EUR, "0"),
                row(CurrencyCode.USD, CurrencyCode.JPY, "-1"),
                row(CurrencyCode.USD, CurrencyCode.TWD, "32.418")));

        assertThat(rates.rate(CurrencyCode.EUR, CurrencyCode.USD)).isEmpty();
        assertThat(rates.rate(CurrencyCode.USD, CurrencyCode.EUR)).isEmpty();
        assertThat(rates.rate(CurrencyCode.JPY, CurrencyCode.USD)).isEmpty();
        assertThat(rates.rate(CurrencyCode.USD, CurrencyCode.TWD))
                .hasValueSatisfying(r -> assertThat(r).isEqualByComparingTo("32.418"));
    }

    @Test
    @DisplayName("convert：2000 USD → TWD = 64836.00000000（scale 8）；缺腿時為 empty")
    void convertAppliesRateAndScalesToEight() {
        FxRates rates = seed();

        assertThat(rates.convert(new BigDecimal("2000"), CurrencyCode.USD, CurrencyCode.TWD))
                .hasValueSatisfying(v -> assertThat(v.toPlainString()).isEqualTo("64836.00000000"));

        FxRates onlyUsdTwd = FxRates.of(List.of(row(CurrencyCode.USD, CurrencyCode.TWD, "32.418")));
        assertThat(onlyUsdTwd.convert(BigDecimal.TEN, CurrencyCode.EUR, CurrencyCode.JPY)).isEmpty();
    }
}
