package dowob.xyz.stockwebv2.backtest.api;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 請求欄位長度上限(2026-09-02 安全審查 L-1)。
 *
 * @author Yuan
 * @version 1.0.0
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
}
