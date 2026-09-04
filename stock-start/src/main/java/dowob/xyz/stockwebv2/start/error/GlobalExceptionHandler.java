package dowob.xyz.stockwebv2.start.error;

import dowob.xyz.stockwebv2.common.api.ApiError;
import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.error.FieldValidationException;
import dowob.xyz.stockwebv2.common.error.RateLimitExceededException;
import dowob.xyz.stockwebv2.infrastructure.web.ApiMetaFactory;
import dowob.xyz.stockwebv2.infrastructure.web.TraceIdFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 方法層授權（{@code @PreAuthorize}）拒絕時必須重新拋出，交由 Spring Security 的
     * {@code ExceptionTranslationFilter} 回應 403；若被 catch-all handler 接住會誤回 500
     * （security.md §7）。
     *
     * @param exception 授權拒絕例外
     * @throws AccessDeniedException 一律重新拋出
     */
    @ExceptionHandler(AccessDeniedException.class)
    public void handleAccessDenied(AccessDeniedException exception) throws AccessDeniedException {
        throw exception;
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleRateLimited(RateLimitExceededException exception) {
        ErrorCode code = exception.errorCode();
        ApiError error = ApiError.of(code, exception.getMessage());
        return ResponseEntity.status(code.httpStatus())
            .header("Retry-After", String.valueOf(exception.retryAfterSeconds()))
            .body(ApiResponse.failure(error, ApiMetaFactory.current()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException exception) {
        ErrorCode code = exception.errorCode();
        ApiError error = ApiError.of(code, exception.getMessage());
        return ResponseEntity.status(code.httpStatus()).body(ApiResponse.failure(error, ApiMetaFactory.current()));
    }

    /**
     * service 層判定的、帶欄位對照的驗證失敗，例如 {@code Idempotency-Key:} 空值或過長。
     *
     * <p>必須排在 {@link #handleBusiness} 之前被選中（Spring 以例外型別的距離挑 handler，
     * 子類別優先）。若落到 {@code handleBusiness}，回應仍是 400 {@code VALIDATION_FAILED}
     * 但 {@code fields} 為空，前端就無法分辨「header 問題」與「body 欄位錯」（D-16）——
     * 與 {@link #handleMissingRequestHeader} 存在的理由相同。</p>
     *
     * @param exception 帶 fields 的驗證失敗
     * @return 400 VALIDATION_FAILED，fields 為例外攜帶的欄位對照
     */
    @ExceptionHandler(FieldValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleFieldValidation(FieldValidationException exception) {
        ApiError error = ApiError.of(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), exception.fields());
        return ResponseEntity.badRequest().body(ApiResponse.failure(error, ApiMetaFactory.current()));
    }

    /**
     * 必填請求 header 缺漏，例如 {@code POST /api/v1/trades} 沒帶 {@code Idempotency-Key}。
     *
     * <p>不加這個 handler 也能得到 400 {@code VALIDATION_FAILED}——{@link MissingRequestHeaderException}
     * 實作了 {@link ErrorResponse}，會落到 catch-all 的 {@code handleErrorResponse} 分支。但那條路徑
     * 產生的 {@code fields} 是<strong>空的</strong>、message 是通用的，前端無從分辨「缺 header」與
     * 「body 欄位驗證失敗」——兩者都是 400 {@code VALIDATION_FAILED}，只能靠解析展示字串來猜。
     * 獨立 handler 把 header 名稱放進 {@code fields}，讓兩種錯誤在契約層面可區分。</p>
     *
     * <p>{@code fields} 的 value 用靜態英文字串而非 {@code exception.getMessage()}：沿用本類別
     * 「絕不把例外原始訊息當 API message」的既有慣例，避免框架訊息成為對外契約的一部分。</p>
     *
     * @param exception 必填 header 缺漏例外
     * @return 400 VALIDATION_FAILED，fields 指出是哪一個 header 缺漏
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingRequestHeader(MissingRequestHeaderException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(exception.getHeaderName(), "required header is missing");
        ApiError error = ApiError.of(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), fields);
        return ResponseEntity.badRequest().body(ApiResponse.failure(error, ApiMetaFactory.current()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
            fields.put(error.getField(), error.getDefaultMessage())
        );
        ApiError error = ApiError.of(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), fields);
        return ResponseEntity.badRequest().body(ApiResponse.failure(error, ApiMetaFactory.current()));
    }

    /**
     * 型別化 {@code @RequestParam} / {@code @PathVariable} 的轉換失敗。
     *
     * <p>例如 {@code GET /api/v1/market/{symbol}/klines?from=2026-01-01}——{@code from} 宣告為
     * {@link java.time.Instant}，而 {@code Instant} 只接受帶偏移量的完整時間戳。這類例外
     * <strong>沒有</strong>實作 {@link ErrorResponse}，若不在此攔截就會落到 catch-all，
     * 讓純粹的使用者輸入錯誤變成 HTTP 500 並在 log 留下 ERROR 級噪音。</p>
     *
     * <p>亦涵蓋 query string 的 {@code '+'} 被 servlet 依 {@code x-www-form-urlencoded} 規則
     * 解成空白的情形（{@code from=...T00:00:00+08:00} 未百分比編碼時）。</p>
     *
     * <p>只回報參數名稱、不回射使用者傳入的值（code-standards 錯誤訊息安全規則）。</p>
     *
     * @param exception 參數型別轉換失敗例外
     * @return 400 VALIDATION_FAILED，fields 指出是哪一個參數格式錯誤
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(exception.getName(), "invalid format");
        ApiError error = ApiError.of(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), fields);
        return ResponseEntity.badRequest().body(ApiResponse.failure(error, ApiMetaFactory.current()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
        if (exception instanceof ErrorResponse errorResponse) {
            return handleErrorResponse(errorResponse);
        }
        log.error("Unexpected exception while handling request, traceId={}",
            TraceIdFilter.currentTraceId(), exception);
        ApiError error = ApiError.of(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage());
        return ResponseEntity.status(500).body(ApiResponse.failure(error, ApiMetaFactory.current()));
    }

    private ResponseEntity<ApiResponse<Void>> handleErrorResponse(ErrorResponse errorResponse) {
        ErrorCode code = codeForStatus(errorResponse.getStatusCode().value());
        ApiError error = ApiError.of(code, code.defaultMessage());
        return ResponseEntity.status(errorResponse.getStatusCode()).body(ApiResponse.failure(error, ApiMetaFactory.current()));
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
}
