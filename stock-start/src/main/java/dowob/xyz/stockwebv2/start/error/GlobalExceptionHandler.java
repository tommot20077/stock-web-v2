package dowob.xyz.stockwebv2.start.error;

import dowob.xyz.stockwebv2.common.api.ApiError;
import dowob.xyz.stockwebv2.common.api.ApiMeta;
import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.error.RateLimitExceededException;
import dowob.xyz.stockwebv2.infrastructure.web.TraceIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleRateLimited(RateLimitExceededException exception) {
        ErrorCode code = exception.errorCode();
        ApiError error = ApiError.of(code, exception.getMessage());
        return ResponseEntity.status(code.httpStatus())
            .header("Retry-After", String.valueOf(exception.retryAfterSeconds()))
            .body(ApiResponse.failure(error, meta()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException exception) {
        ErrorCode code = exception.errorCode();
        ApiError error = ApiError.of(code, exception.getMessage());
        return ResponseEntity.status(code.httpStatus()).body(ApiResponse.failure(error, meta()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
            fields.put(error.getField(), error.getDefaultMessage())
        );
        ApiError error = ApiError.of(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), fields);
        return ResponseEntity.badRequest().body(ApiResponse.failure(error, meta()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
        if (exception instanceof ErrorResponse errorResponse) {
            return handleErrorResponse(errorResponse);
        }
        String traceId = MDC.get(TraceIdFilter.TRACE_ID);
        log.error("Unexpected exception while handling request, traceId={}",
            traceId == null ? "missing-trace-id" : traceId, exception);
        ApiError error = ApiError.of(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage());
        return ResponseEntity.status(500).body(ApiResponse.failure(error, meta()));
    }

    private ResponseEntity<ApiResponse<Void>> handleErrorResponse(ErrorResponse errorResponse) {
        ErrorCode code = codeForStatus(errorResponse.getStatusCode().value());
        ApiError error = ApiError.of(code, code.defaultMessage());
        return ResponseEntity.status(errorResponse.getStatusCode()).body(ApiResponse.failure(error, meta()));
    }

    private ErrorCode codeForStatus(int status) {
        if (status == HttpStatus.NOT_FOUND.value()) {
            return ErrorCode.RESOURCE_NOT_FOUND;
        }
        if (status == HttpStatus.BAD_REQUEST.value()) {
            return ErrorCode.VALIDATION_FAILED;
        }
        if (status == HttpStatus.FORBIDDEN.value()) {
            return ErrorCode.AUTH_FORBIDDEN;
        }
        if (status == HttpStatus.UNAUTHORIZED.value()) {
            return ErrorCode.AUTH_INVALID_CREDENTIALS;
        }
        return ErrorCode.INTERNAL_ERROR;
    }

    private ApiMeta meta() {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID);
        return new ApiMeta(traceId == null ? "missing-trace-id" : traceId, OffsetDateTime.now());
    }
}
