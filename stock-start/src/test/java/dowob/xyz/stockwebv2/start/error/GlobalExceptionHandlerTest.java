package dowob.xyz.stockwebv2.start.error;

import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;

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
}
