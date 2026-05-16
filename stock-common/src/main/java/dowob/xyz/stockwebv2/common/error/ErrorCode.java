package dowob.xyz.stockwebv2.common.error;

public enum ErrorCode {
    VALIDATION_FAILED(400, "Validation failed"),
    RESOURCE_NOT_FOUND(404, "Resource not found"),
    AUTH_INVALID_CREDENTIALS(401, "Invalid credentials"),
    AUTH_TOKEN_EXPIRED(401, "Access token expired"),
    AUTH_REFRESH_TOKEN_INVALID(401, "Refresh token invalid"),
    AUTH_FORBIDDEN(403, "Forbidden"),
    AUTH_REDIS_UNAVAILABLE(503, "Authentication state unavailable"),
    DUPLICATE_RESOURCE(409, "Duplicate resource"),
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
