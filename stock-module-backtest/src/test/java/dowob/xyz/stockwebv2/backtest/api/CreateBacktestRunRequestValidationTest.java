package dowob.xyz.stockwebv2.backtest.api;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CreateBacktestRunRequest} 的 Bean Validation 邊界。
 *
 * <ul>
 *   <li>請求欄位長度上限(2026-09-02 安全審查 L-1)。</li>
 *   <li>{@code initialCapital} 上限 1e12、最多 6 位小數(BE-CR-01 / T-05-37,Yuan 2026-11-14 裁決):
 *       指數字串與超位數值必須在驗證層擋下,不得進入回測引擎。</li>
 * </ul>
 *
 * <p><strong>尾端零</strong>:Hibernate Validator 的 {@code @Digits} 對 {@code BigDecimal} 直接取原始
 * {@code scale()}(不先 {@code stripTrailingZeros}),因此 {@code "100.00000000"} 在 HTTP 層會被拒。
 * 這比「以數值判斷」嚴格,但也避免對超長尾零字串做 O(n²) 的去零運算;前端 big.js 送出前已去尾零
 * (05-28),正式路徑不會送出超過 6 位的尾零。</p>
 *
 * @author Yuan
 * @version 1.1.0
 */
class CreateBacktestRunRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("symbol 超過 32 字元即驗證失敗")
    void symbolLongerThan32_isRejected() {
        CreateBacktestRunRequest request = new CreateBacktestRunRequest("ma-cross", null, "A".repeat(33), "1Y",
            new BigDecimal("10000"), "USD", "SPY", "MOCK");

        assertThat(validator.validate(request))
            .anyMatch(v -> v.getPropertyPath().toString().equals("symbol"));
    }

    @ParameterizedTest(name = "initialCapital = {0} → 違規")
    @ValueSource(strings = {
        "1e999999999",
        "0.0000001",
        "123456789012.12345678",
        "1000000000000.000001",
        "1E+13",
        "100.00000000"
    })
    @DisplayName("initialCapital 超過 1e12 或超過 6 位小數即驗證失敗(BE-CR-01;@Digits 以原始 scale 計,尾端零也算)")
    void initialCapitalOutOfRange_isRejected(String capital) {
        assertThat(validator.validate(requestWithCapital(new BigDecimal(capital))))
            .anyMatch(v -> v.getPropertyPath().toString().equals("initialCapital"));
    }

    @ParameterizedTest(name = "initialCapital = {0} → 無違規")
    @ValueSource(strings = {
        "1000000000000",
        "123456789012.123456",
        "0.000001",
        "1E+12",
        "100.000000",
        "10000"
    })
    @DisplayName("initialCapital 在 1e12 以內且小數不超過 6 位即通過")
    void initialCapitalWithinRange_isAccepted(String capital) {
        assertThat(validator.validate(requestWithCapital(new BigDecimal(capital))))
            .noneMatch(v -> v.getPropertyPath().toString().equals("initialCapital"));
    }

    private CreateBacktestRunRequest requestWithCapital(BigDecimal initialCapital) {
        return new CreateBacktestRunRequest("ma-cross", null, "AAPL", "1Y", initialCapital, "USD", "SPY", "MOCK");
    }
}
