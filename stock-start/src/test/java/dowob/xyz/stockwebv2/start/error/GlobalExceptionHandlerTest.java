package dowob.xyz.stockwebv2.start.error;

import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.error.FieldValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GlobalExceptionHandler} 的例外映射單元測試。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("全域例外映射")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("型別化參數轉換失敗回 400 VALIDATION_FAILED，而非落到 catch-all 變成 500")
    void typeMismatchIsBadRequestNotInternalError() {
        /*
         * MethodArgumentTypeMismatchException 並未實作 ErrorResponse，因此在加入專屬 handler
         * 之前，GET /api/v1/market/{symbol}/klines?from=2026-01-01 這種純粹的使用者輸入錯誤
         * 會落到 @ExceptionHandler(Exception.class)，回 HTTP 500 並在 log 留下 ERROR 級噪音。
         */
        MethodArgumentTypeMismatchException exception = new MethodArgumentTypeMismatchException(
            "2026-01-01", Instant.class, "from", null, new IllegalArgumentException("unparseable"));

        ResponseEntity<ApiResponse<Void>> response = handler.handleTypeMismatch(exception);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        ApiResponse<Void> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.success()).isFalse();
        assertThat(body.error().code()).isEqualTo(ErrorCode.VALIDATION_FAILED.name());
        assertThat(body.error().fields()).containsKey("from");
    }

    @Test
    @DisplayName("錯誤回應不回射使用者傳入的參數值")
    void typeMismatchDoesNotEchoUserSuppliedValue() {
        MethodArgumentTypeMismatchException exception = new MethodArgumentTypeMismatchException(
            "<script>alert(1)</script>", Instant.class, "from", null, new IllegalArgumentException("unparseable"));

        ResponseEntity<ApiResponse<Void>> response = handler.handleTypeMismatch(exception);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().toString()).doesNotContain("script");
    }

    @Test
    @DisplayName("帶欄位的驗證失敗回 400 VALIDATION_FAILED，且 fields 指名是哪個欄位／header")
    void fieldValidationExceptionCarriesFieldsIntoEnvelope() {
        /*
         * `Idempotency-Key:`（空值）能通過 Spring 的 required 檢查，只能在 service 層擋。
         * 若它以一般 BusinessException 丟出，回應雖是 400 但 fields 為空，前端（D-16）就分不出
         * 「缺 header」與「body 欄位錯」—— 這正是獨立的 MissingRequestHeaderException handler
         * 存在的理由，空值變體必須得到同樣的 fields。
         */
        FieldValidationException exception = new FieldValidationException(
            "Idempotency-Key header is required", Map.of("Idempotency-Key", "must not be blank"));

        ResponseEntity<ApiResponse<Void>> response = handler.handleFieldValidation(exception);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        ApiResponse<Void> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.success()).isFalse();
        assertThat(body.error().code()).isEqualTo(ErrorCode.VALIDATION_FAILED.name());
        assertThat(body.error().fields()).containsEntry("Idempotency-Key", "must not be blank");
    }
}
