package dowob.xyz.stockwebv2.trading.api;

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
class CreateTradeRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("symbol 超過 32 字元即驗證失敗")
    void symbolLongerThan32_isRejected() {
        CreateTradeRequest request = new CreateTradeRequest("A".repeat(33), "BUY", BigDecimal.ONE, BigDecimal.TEN,
            BigDecimal.ZERO, null, null);

        assertThat(validator.validate(request))
            .anyMatch(v -> v.getPropertyPath().toString().equals("symbol"));
    }

    @Test
    @DisplayName("32 字元的 symbol 仍可接受")
    void symbolOf32_isAccepted() {
        CreateTradeRequest request = new CreateTradeRequest("A".repeat(32), "BUY", BigDecimal.ONE, BigDecimal.TEN,
            BigDecimal.ZERO, null, null);

        assertThat(validator.validate(request)).isEmpty();
    }
}
