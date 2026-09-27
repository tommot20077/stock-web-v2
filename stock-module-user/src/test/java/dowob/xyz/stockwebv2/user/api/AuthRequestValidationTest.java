package dowob.xyz.stockwebv2.user.api;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 請求欄位長度上限(2026-09-02 安全審查 L-2)。
 *
 * @author Yuan
 * @version 1.0.0
 */
class AuthRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    /** 超長密碼會讓 BCrypt 全量雜湊,每個請求都成為 CPU 放大器。 */
    @Test
    @DisplayName("登入密碼超過 128 字元即驗證失敗")
    void loginPasswordLongerThan128_isRejected() {
        assertThat(validator.validate(new LoginRequest("a@example.com", "x".repeat(129))))
            .anyMatch(v -> v.getPropertyPath().toString().equals("password"));
    }

    @Test
    @DisplayName("註冊密碼超過 128 字元即驗證失敗")
    void registerPasswordLongerThan128_isRejected() {
        assertThat(validator.validate(new RegisterRequest("a@example.com", "yuan", "Aa1" + "x".repeat(126))))
            .anyMatch(v -> v.getPropertyPath().toString().equals("password"));
    }

    @Test
    @DisplayName("128 字元的密碼仍可接受")
    void passwordOf128_isAccepted() {
        assertThat(validator.validate(new RegisterRequest("a@example.com", "yuan", "Aa1" + "x".repeat(125))))
            .isEmpty();
    }
}
