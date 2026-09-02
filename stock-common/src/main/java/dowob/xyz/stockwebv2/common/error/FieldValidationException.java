package dowob.xyz.stockwebv2.common.error;

import java.util.Map;
import java.util.Objects;

/**
 * 帶「欄位 → 原因」對照的驗證失敗。
 *
 * <p>與 {@link BusinessException} 的差別只在多了 {@link #fields()}：全域例外處理會把它放進
 * {@code ApiError.fields}，讓前端能分辨「哪個欄位／header 出錯」，而不是只拿到一個籠統的
 * {@code VALIDATION_FAILED}。適用於 Bean Validation 覆蓋不到、必須在 service 層判定的輸入錯誤
 * （例如通過 required 檢查但值為空白的 header）。</p>
 *
 * <p>原因字串必須是靜態描述，<strong>不得回射使用者輸入</strong>。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public class FieldValidationException extends BusinessException {
    private final Map<String, String> fields;

    /**
     * @param message 錯誤訊息（靜態描述，不含使用者輸入）
     * @param fields  欄位／header 名稱 → 原因；不得為 null 或空
     */
    public FieldValidationException(String message, Map<String, String> fields) {
        super(ErrorCode.VALIDATION_FAILED, message);
        Objects.requireNonNull(fields, "fields");
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("fields must not be empty");
        }
        this.fields = Map.copyOf(fields);
    }

    /**
     * @return 欄位／header 名稱 → 原因（不可變）
     */
    public Map<String, String> fields() {
        return fields;
    }
}
