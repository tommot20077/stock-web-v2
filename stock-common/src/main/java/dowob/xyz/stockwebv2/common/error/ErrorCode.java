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
