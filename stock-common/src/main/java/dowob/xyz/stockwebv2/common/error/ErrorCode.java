package dowob.xyz.stockwebv2.common.error;

public enum ErrorCode {
    VALIDATION_FAILED(400, "Validation failed"),
    RESOURCE_NOT_FOUND(404, "Resource not found"),
    AUTH_INVALID_CREDENTIALS(401, "Invalid credentials"),
    AUTH_TOKEN_EXPIRED(401, "Access token expired"),
    AUTH_REFRESH_TOKEN_INVALID(401, "Refresh token invalid"),
    AUTH_FORBIDDEN(403, "Forbidden"),
    AUTH_CSRF_TOKEN_INVALID(403, "CSRF token invalid"),
    AUTH_REDIS_UNAVAILABLE(503, "Authentication state unavailable"),
    DUPLICATE_RESOURCE(409, "Duplicate resource"),

    // Market-data module error codes
    ASSET_NOT_FOUND(404, "Asset not found"),
    KLINE_INTERVAL_INVALID(400, "K-line interval invalid"),
    LATEST_BATCH_TOO_LARGE(400, "Latest batch size exceeds limit"),
    BACKFILL_RANGE_TOO_LARGE(400, "Backfill range exceeds 90 days"),
    BACKFILL_ALREADY_RUNNING(409, "Backfill job already running for this key"),
    BACKFILL_JOB_NOT_FOUND(404, "Backfill job not found"),
    WS_TICKET_INVALID(401, "WebSocket ticket invalid"),
    WS_SUBSCRIPTION_LIMIT_EXCEEDED(400, "WebSocket subscription limit exceeded"),
    WS_MSG_FORMAT_INVALID(400, "WebSocket message format invalid"),
    WS_RATE_LIMIT_EXCEEDED(429, "WebSocket rate limit exceeded"),
    PROVIDER_FETCH_FAILED(502, "Market data provider fetch failed"),

    // Backtest module error codes
    BACKTEST_INVALID_INITIAL_CAPITAL(400, "Initial capital must be greater than 0"),
    BACKTEST_UNSUPPORTED_SYMBOL(400, "Unsupported symbol"),
    BACKTEST_UNSUPPORTED_PERIOD(400, "Unsupported backtest period"),
    BACKTEST_UNSUPPORTED_STRATEGY(400, "Unsupported backtest strategy"),
    BACKTEST_UNSUPPORTED_DATA_MODE(400, "Unsupported backtest data mode"),
    BACKTEST_STRATEGY_COMPILE_FAILED(400, "Strategy compile failed"),
    BACKTEST_RUN_NOT_FOUND(404, "Backtest run not found"),
    BACKTEST_RESULT_NOT_READY(409, "Backtest result is not ready"),

    INTERNAL_ERROR(500, "Internal server error");

    private final int httpStatus;
    private final String defaultMessage;

    ErrorCode(int httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
