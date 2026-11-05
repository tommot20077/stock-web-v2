package dowob.xyz.stockwebv2.trading.domain;

import dowob.xyz.stockwebv2.common.error.FieldValidationException;

import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.Map;

/**
 * 股數調整類型白名單（ADJ-01）；與 {@code share_adjustments.type} 的 {@code ck_share_adjustments_type} 一致。
 *
 * <p>刻意與 {@link TradeType} 分開：股數調整沒有成交價、不產生現金流，不得經 {@code POST /trades} 寫入
 * （judgment §1；D-11）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public enum ShareAdjustmentType {
    /**
     * 配股（股票股利）：股數增加實領股數、總成本不變。
     */
    STOCK_DIVIDEND,

    /**
     * 拆股 / 合股：股數依 new:old 比例換算（以除權日起點持股為基準）、總成本不變。
     */
    SPLIT;

    /**
     * 解析建立請求中的<strong>必填</strong> {@code type} 欄位，大小寫不敏感、忽略前後空白。
     *
     * @param value 使用者傳入的原始字串
     * @return 對應的調整類型
     * @throws FieldValidationException 值為空白或不在白名單內時（{@code fields.type}）；訊息不回射輸入值
     */
    public static ShareAdjustmentType fromApiValue(String value) {
        if (StringUtils.isBlank(value)) {
            throw new FieldValidationException("type is required", Map.of("type", "must not be blank"));
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (ShareAdjustmentType candidate : values()) {
            if (candidate.name().equals(normalized)) {
                return candidate;
            }
        }
        throw new FieldValidationException("Unsupported share adjustment type", Map.of("type", "unsupported"));
    }
}
