package dowob.xyz.stockwebv2.common.api;

import dowob.xyz.stockwebv2.common.error.ErrorCode;

import java.util.Map;

public record ApiError(String code, String message, Map<String, String> fields) {

    public ApiError {
        fields = fields == null ? Map.of() : Map.copyOf(fields);
    }

    public static ApiError of(ErrorCode code, String message, Map<String, String> fields) {
        return new ApiError(code.name(), message, fields);
    }

    public static ApiError of(ErrorCode code, String message) {
        return of(code, message, Map.of());
    }
}
