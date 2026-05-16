package dowob.xyz.stockwebv2.common.api;

import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiResponseTest {

    @Test
    void successWrapsDataAndMeta() {
        ApiMeta meta = new ApiMeta("trace-1", OffsetDateTime.parse("2026-05-16T10:30:00+08:00"));

        ApiResponse<String> response = ApiResponse.success("ok", meta);

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isEqualTo("ok");
        assertThat(response.error()).isNull();
        assertThat(response.meta()).isEqualTo(meta);
    }

    @Test
    void failureWrapsErrorAndMeta() {
        ApiMeta meta = new ApiMeta("trace-2", OffsetDateTime.parse("2026-05-16T10:31:00+08:00"));
        ApiError error = ApiError.of(ErrorCode.VALIDATION_FAILED, "Validation failed", Map.of("email", "invalid"));

        ApiResponse<Void> response = ApiResponse.failure(error, meta);

        assertThat(response.success()).isFalse();
        assertThat(response.data()).isNull();
        assertThat(response.error().code()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.error().fields()).containsEntry("email", "invalid");
        assertThat(response.meta().traceId()).isEqualTo("trace-2");
    }

    @Test
    void pageResponseCarriesItemsAndTotals() {
        PageResponse<String> page = PageResponse.of(List.of("AAPL", "NVDA"), 0, 2, 5);

        assertThat(page.items()).containsExactly("AAPL", "NVDA");
        assertThat(page.page()).isEqualTo(0);
        assertThat(page.size()).isEqualTo(2);
        assertThat(page.totalElements()).isEqualTo(5);
        assertThat(page.totalPages()).isEqualTo(3);
    }

    @Test
    void errorCodeContainsHttpStatus() {
        assertThat(ErrorCode.AUTH_REDIS_UNAVAILABLE.httpStatus()).isEqualTo(503);
        assertThat(ErrorCode.RESOURCE_NOT_FOUND.httpStatus()).isEqualTo(404);
    }

    @Test
    void apiErrorDirectConstructorCopiesFieldsAndNormalizesNull() {
        Map<String, String> fields = new HashMap<>();
        fields.put("email", "invalid");

        ApiError error = new ApiError("VALIDATION_FAILED", "Validation failed", fields);
        fields.put("name", "blank");

        assertThat(error.fields()).containsOnly(Map.entry("email", "invalid"));
        assertThatThrownBy(() -> error.fields().put("age", "invalid"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(new ApiError("VALIDATION_FAILED", "Validation failed", null).fields()).isEmpty();
    }

    @Test
    void pageResponseDirectConstructorCopiesItemsAndNormalizesNull() {
        List<String> items = new ArrayList<>();
        items.add("AAPL");

        PageResponse<String> page = new PageResponse<>(items, 0, 1, 1, 1);
        items.add("NVDA");

        assertThat(page.items()).containsExactly("AAPL");
        assertThatThrownBy(() -> page.items().add("TSLA"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(new PageResponse<String>(null, 0, 1, 0, 0).items()).isEmpty();
    }

    @Test
    void apiResponseRejectsContradictoryStates() {
        ApiError error = ApiError.of(ErrorCode.INTERNAL_ERROR, "Internal server error");

        assertThatThrownBy(() -> new ApiResponse<>(true, "ok", error, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApiResponse<>(false, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void businessExceptionRejectsNullErrorCode() {
        assertThatThrownBy(() -> new BusinessException(null, "message"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("errorCode");
    }
}
