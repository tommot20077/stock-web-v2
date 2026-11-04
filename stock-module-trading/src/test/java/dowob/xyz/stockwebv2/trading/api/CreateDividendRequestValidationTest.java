package dowob.xyz.stockwebv2.trading.api;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 現金股利請求的 bean validation 欄位歸屬（04.2-04）。
 *
 * <p>{@code CashDividendControllerWebMvcTest} 的切片不含 stock-start 的 {@code GlobalExceptionHandler}，
 * 只能斷言 400 與「service 無互動」；錯誤落在哪個 {@code fields} 鍵取決於違規的 property path
 * （{@code handleValidation} 以 {@code FieldError#getField()} 為鍵）。這裡直接鎖住 property path，確保範圍外的值會指名
 * {@code amount} / {@code withholdingTax} / {@code note} / {@code reversalOfId} / {@code symbol}，前端才能把錯誤掛到對應欄位。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("現金股利請求：bean validation 欄位歸屬")
class CreateDividendRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("合法請求（含邊界 0.00000001 與 1e12）無違規")
    void validRequestHasNoViolations() {
        assertThat(violatedFields(request("AAPL", "0.00000001", "0", null, null))).isEmpty();
        assertThat(violatedFields(request("AAPL", "1000000000000", "1000000000000", "n".repeat(500),
            "x".repeat(36)))).isEmpty();
    }

    @Test
    @DisplayName("amount = 0、超過 1e12、小數 9 位、null → 違規指名 amount")
    void amountOutOfRangeNamesAmount() {
        assertThat(violatedFields(request("AAPL", "0", null, null, null))).containsExactly("amount");
        assertThat(violatedFields(request("AAPL", "1000000000000.00000001", null, null, null)))
            .containsExactly("amount");
        assertThat(violatedFields(request("AAPL", "1.123456789", null, null, null))).containsExactly("amount");
        assertThat(violatedFields(new CreateDividendRequest("AAPL", null, null, null, null, null)))
            .containsExactly("amount");
    }

    @Test
    @DisplayName("withholdingTax = -1 或小數 9 位 → 違規指名 withholdingTax")
    void withholdingTaxOutOfRangeNamesWithholdingTax() {
        assertThat(violatedFields(request("AAPL", "120", "-1", null, null))).containsExactly("withholdingTax");
        assertThat(violatedFields(request("AAPL", "120", "0.000000001", null, null)))
            .containsExactly("withholdingTax");
    }

    @Test
    @DisplayName("note 501 字 → 違規指名 note")
    void oversizedNoteNamesNote() {
        assertThat(violatedFields(request("AAPL", "120", null, "n".repeat(501), null))).containsExactly("note");
    }

    @Test
    @DisplayName("reversalOfId 37 字 → 違規指名 reversalOfId（格式由 service 驗）")
    void oversizedReversalOfIdNamesReversalOfId() {
        assertThat(violatedFields(request("AAPL", "120", null, null, "x".repeat(37))))
            .containsExactly("reversalOfId");
    }

    @Test
    @DisplayName("symbol 空白或超過 32 字 → 違規指名 symbol")
    void invalidSymbolNamesSymbol() {
        assertThat(violatedFields(request(" ", "120", null, null, null))).containsExactly("symbol");
        assertThat(violatedFields(request("S".repeat(33), "120", null, null, null))).containsExactly("symbol");
    }

    private Set<String> violatedFields(CreateDividendRequest request) {
        return validator.validate(request).stream()
            .map(ConstraintViolation::getPropertyPath)
            .map(Object::toString)
            .collect(Collectors.toSet());
    }

    private static CreateDividendRequest request(String symbol, String amount, String withholdingTax, String note,
                                                 String reversalOfId) {
        return new CreateDividendRequest(symbol, new BigDecimal(amount),
            withholdingTax == null ? null : new BigDecimal(withholdingTax), null, note, reversalOfId);
    }
}
