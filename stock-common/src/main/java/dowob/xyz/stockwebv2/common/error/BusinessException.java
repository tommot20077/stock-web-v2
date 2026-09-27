package dowob.xyz.stockwebv2.common.error;

import java.util.Map;
import java.util.Objects;

/**
 * 業務規則違反。
 *
 * <p>訊息會出現在 API 回應的 {@code error.message},必須是<strong>靜態描述,不得回射使用者輸入</strong>
 * (2026-09-02 安全審查 L-1)。需要指出是哪個輸入出錯時,用 {@link #fields()}:欄位名 → 靜態原因。
 *
 * @author Yuan
 * @version 1.1
 */
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;
    private final Map<String, String> fields;

    /**
     * @param errorCode 錯誤碼
     * @param message   靜態錯誤訊息
     */
    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, Map.of());
    }

    /**
     * @param errorCode 錯誤碼
     * @param message   靜態錯誤訊息
     * @param fields    欄位名 → 靜態原因;不得含使用者輸入
     */
    public BusinessException(ErrorCode errorCode, String message, Map<String, String> fields) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        this.fields = Map.copyOf(Objects.requireNonNull(fields, "fields"));
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /**
     * @return 欄位名 → 原因(不可變);沒有時為空 map
     */
    public Map<String, String> fields() {
        return fields;
    }
}
