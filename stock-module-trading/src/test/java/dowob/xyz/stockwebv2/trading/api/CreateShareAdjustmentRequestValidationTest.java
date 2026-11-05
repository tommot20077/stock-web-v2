package dowob.xyz.stockwebv2.trading.api;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 股數調整請求的 bean validation 欄位歸屬（04.2-08）。
 *
 * <p>{@code ShareAdjustmentControllerWebMvcTest} 的切片不含 stock-start 的 {@code GlobalExceptionHandler}，
 * 只能斷言 400 與「service 無互動」；錯誤落在哪個 {@code fields} 鍵取決於違規的 property path。
 * 這裡直接鎖住 property path，確保範圍外的值會指名 {@code ratioNew} / {@code ratioOld} / {@code quantity} /
 * {@code reversalOfId}，前端才能把錯誤掛到對應欄位。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("股數調整請求：bean validation 欄位歸屬")
class CreateShareAdjustmentRequestValidationTest {

    private static final LocalDate EFFECTIVE_DATE = LocalDate.parse("2026-10-29");

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("ratioNew 為 0 或 1001 → 違規指名 ratioNew")
    void ratioNewOutOfRangeNamesRatioNew() {
        assertThat(violatedFields(split(0, 1))).containsExactly("ratioNew");
        assertThat(violatedFields(split(1001, 1))).containsExactly("ratioNew");
    }

    @Test
    @DisplayName("ratioOld 為 0 或 1001 → 違規指名 ratioOld")
    void ratioOldOutOfRangeNamesRatioOld() {
        assertThat(violatedFields(split(4, 0))).containsExactly("ratioOld");
        assertThat(violatedFields(split(4, 1001))).containsExactly("ratioOld");
    }

    @Test
    @DisplayName("ratio 邊界 1 與 1000 可接受（相等與否由 service 判斷）")
    void ratioBoundariesAreAccepted() {
        assertThat(violatedFields(split(1000, 1))).isEmpty();
        assertThat(violatedFields(split(1, 1000))).isEmpty();
    }

    @Test
    @DisplayName("quantity 小數 9 位 → 違規指名 quantity；8 位可接受")
    void quantityWithNineDecimalsNamesQuantity() {
        assertThat(violatedFields(stockDividend(new BigDecimal("1.123456789")))).containsExactly("quantity");
        assertThat(violatedFields(stockDividend(new BigDecimal("1.12345678")))).isEmpty();
    }

    @Test
    @DisplayName("quantity 為 0 或超過 1,000,000,000 → 違規指名 quantity")
    void quantityOutOfRangeNamesQuantity() {
        assertThat(violatedFields(stockDividend(BigDecimal.ZERO))).containsExactly("quantity");
        assertThat(violatedFields(stockDividend(new BigDecimal("1000000000.00000001")))).containsExactly("quantity");
    }

    @Test
    @DisplayName("reversalOfId 超過 36 字 → 違規指名 reversalOfId；36 字可接受（格式由 service 驗）")
    void oversizedReversalOfIdNamesReversalOfId() {
        assertThat(violatedFields(reversal("x".repeat(37)))).containsExactly("reversalOfId");
        assertThat(violatedFields(reversal("x".repeat(36)))).isEmpty();
    }

    private Set<String> violatedFields(CreateShareAdjustmentRequest request) {
        return validator.validate(request).stream()
            .map(ConstraintViolation::getPropertyPath)
            .map(Object::toString)
            .collect(Collectors.toSet());
    }

    private static CreateShareAdjustmentRequest split(int ratioNew, int ratioOld) {
        return new CreateShareAdjustmentRequest("AAPL", "SPLIT", null, ratioNew, ratioOld, EFFECTIVE_DATE, null, null);
    }

    private static CreateShareAdjustmentRequest stockDividend(BigDecimal quantity) {
        return new CreateShareAdjustmentRequest("AAPL", "STOCK_DIVIDEND", quantity, null, null, EFFECTIVE_DATE, null,
            null);
    }

    private static CreateShareAdjustmentRequest reversal(String reversalOfId) {
        return new CreateShareAdjustmentRequest("AAPL", "SPLIT", null, 4, 1, EFFECTIVE_DATE, null, reversalOfId);
    }
}
