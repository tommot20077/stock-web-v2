package dowob.xyz.stockwebv2.common.api;

public record ApiResponse<T>(boolean success, T data, ApiError error, ApiMeta meta) {

    public ApiResponse {
        if (success && error != null) {
            throw new IllegalArgumentException("Successful response must not contain an error");
        }
        if (!success && error == null) {
            throw new IllegalArgumentException("Failure response must contain an error");
        }
    }

    public static <T> ApiResponse<T> success(T data, ApiMeta meta) {
        return new ApiResponse<>(true, data, null, meta);
    }

    public static ApiResponse<EmptyResponse> empty(ApiMeta meta) {
        return success(EmptyResponse.INSTANCE, meta);
    }

    public static <T> ApiResponse<T> failure(ApiError error, ApiMeta meta) {
        return new ApiResponse<>(false, null, error, meta);
    }
}
