package dowob.xyz.stockwebv2.trading.domain;

import dowob.xyz.stockwebv2.common.error.FieldValidationException;

import org.apache.commons.lang3.StringUtils;

import java.util.Map;
import java.util.Optional;

/**
 * 統一活動列表的 {@code type} 篩選白名單（PORT-13）。
 *
 * <p>每個常數決定 UNION ALL 要拼接哪些<strong>寫死</strong>的分支：股利只啟用 {@code cash_dividends} 分支、
 * 股數調整只啟用 {@code share_adjustments} 分支、買入 / 賣出只啟用 {@code transactions} 分支並加上
 * 以參數繫結的交易類型條件。使用者輸入只用於挑選常數，永遠不會成為 SQL 文字。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public enum ActivityTypeFilter {
    /**
     * 只回買入成交。
     */
    BUY,

    /**
     * 只回賣出成交。
     */
    SELL,

    /**
     * 只回現金股利（含沖銷列）。
     */
    DIVIDEND,

    /**
     * 只回配股與拆股 / 合股（含沖銷列）。
     */
    SHARE_ADJUSTMENT;

    /**
     * 白名單外的值統一使用的錯誤原因；刻意列出合法值而不回射原始輸入。
     */
    private static final String INVALID_REASON = "must be one of BUY, SELL, DIVIDEND, SHARE_ADJUSTMENT";

    /**
     * 將 API 傳入的 type 參數解析為篩選（trim 後大小寫不敏感比對常數名）。
     *
     * <p>kind 名稱（{@code CASH_DIVIDEND}、{@code STOCK_DIVIDEND}、{@code SPLIT}）不是合法的 type：
     * 頁籤以「股利」「股數調整」分組，契約只開放四個值。</p>
     *
     * @param value 使用者傳入的原始字串；null 或空白代表未指定
     * @return 對應的篩選；未指定時回傳 empty（三種事件全部）
     * @throws FieldValidationException 值不在白名單內時，fields 指名 {@code type}；訊息不回射原始輸入
     */
    public static Optional<ActivityTypeFilter> fromApiValue(String value) {
        if (StringUtils.isBlank(value)) {
            return Optional.empty();
        }
        String normalized = value.trim();
        for (ActivityTypeFilter filter : values()) {
            if (filter.name().equalsIgnoreCase(normalized)) {
                return Optional.of(filter);
            }
        }
        throw new FieldValidationException("type " + INVALID_REASON, Map.of("type", INVALID_REASON));
    }
}
