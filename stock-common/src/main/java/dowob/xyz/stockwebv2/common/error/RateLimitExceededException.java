package dowob.xyz.stockwebv2.common.error;

/**
 * 限流或帳號鎖定觸發時拋出的例外，攜帶 {@code Retry-After} 秒數供回應標頭使用
 * （security.md §15）。
 *
 * @author Yuan
 * @version 1.0
 */
public class RateLimitExceededException extends BusinessException {

    /**
     * 建議客戶端稍後重試的秒數（對映 HTTP {@code Retry-After} 標頭）。
     */
    private final long retryAfterSeconds;

    /**
     * @param errorCode         錯誤碼（{@code AUTH_RATE_LIMITED} 或 {@code AUTH_ACCOUNT_LOCKED}）
     * @param message           錯誤訊息
     * @param retryAfterSeconds 建議重試秒數，負值會被夾為 0
     */
    public RateLimitExceededException(ErrorCode errorCode, String message, long retryAfterSeconds) {
        super(errorCode, message);
        this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
    }

    /**
     * @return 建議重試秒數
     */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
