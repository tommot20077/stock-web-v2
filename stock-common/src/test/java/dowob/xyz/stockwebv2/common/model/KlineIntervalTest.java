package dowob.xyz.stockwebv2.common.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link KlineInterval} 枚舉的單元測試。
 *
 * @author Yuan
 * @version 1.0.0
 */
class KlineIntervalTest {

    // ── code() wire string tests ───────────────────────────────────────────

    @Test
    void oneMinute_codeIs1m() {
        assertThat(KlineInterval.ONE_MINUTE.code()).isEqualTo("1m");
    }

    @Test
    void fiveMinutes_codeIs5m() {
        assertThat(KlineInterval.FIVE_MINUTES.code()).isEqualTo("5m");
    }

    @Test
    void fifteenMinutes_codeIs15m() {
        assertThat(KlineInterval.FIFTEEN_MINUTES.code()).isEqualTo("15m");
    }

    @Test
    void oneHour_codeIs1h() {
        assertThat(KlineInterval.ONE_HOUR.code()).isEqualTo("1h");
    }

    @Test
    void oneDay_codeIs1d() {
        assertThat(KlineInterval.ONE_DAY.code()).isEqualTo("1d");
    }

    // ── duration() tests ───────────────────────────────────────────────────

    @Test
    void oneMinute_durationIs1Minute() {
        assertThat(KlineInterval.ONE_MINUTE.duration()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void fiveMinutes_durationIs5Minutes() {
        assertThat(KlineInterval.FIVE_MINUTES.duration()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void fifteenMinutes_durationIs15Minutes() {
        assertThat(KlineInterval.FIFTEEN_MINUTES.duration()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void oneHour_durationIs1Hour() {
        assertThat(KlineInterval.ONE_HOUR.duration()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void oneDay_durationIs1Day() {
        assertThat(KlineInterval.ONE_DAY.duration()).isEqualTo(Duration.ofDays(1));
    }

    // ── fromCode() — happy path ────────────────────────────────────────────

    @Test
    void fromCode_lowercase1m_returnsOneMinute() {
        assertThat(KlineInterval.fromCode("1m")).isEqualTo(KlineInterval.ONE_MINUTE);
    }

    @Test
    void fromCode_uppercase1M_returnsOneMinute_caseInsensitive() {
        assertThat(KlineInterval.fromCode("1M")).isEqualTo(KlineInterval.ONE_MINUTE);
    }

    @ParameterizedTest
    @CsvSource({
            "1m, ONE_MINUTE",
            "5m, FIVE_MINUTES",
            "15m, FIFTEEN_MINUTES",
            "1h, ONE_HOUR",
            "1d, ONE_DAY"
    })
    void fromCode_allKnownCodes_returnsCorrectConstant(String code, String expectedName) {
        KlineInterval result = KlineInterval.fromCode(code);
        assertThat(result.name()).isEqualTo(expectedName);
    }

    @ParameterizedTest
    @CsvSource({"1M", "5M", "15M", "1H", "1D"})
    void fromCode_uppercaseCodes_returnsCorrectConstant(String upperCode) {
        KlineInterval lower = KlineInterval.fromCode(upperCode.toLowerCase());
        KlineInterval upper = KlineInterval.fromCode(upperCode);
        assertThat(upper).isEqualTo(lower);
    }

    // ── fromCode() — error cases ───────────────────────────────────────────

    @Test
    void fromCode_unknownCode_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> KlineInterval.fromCode("xyz"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("xyz");
    }

    @Test
    void fromCode_emptyString_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> KlineInterval.fromCode(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromCode_null_throwsNullPointerException() {
        assertThatThrownBy(() -> KlineInterval.fromCode(null))
                .isInstanceOf(NullPointerException.class);
    }
}
