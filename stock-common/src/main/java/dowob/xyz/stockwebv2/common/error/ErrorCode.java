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
