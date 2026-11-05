package dowob.xyz.stockwebv2.trading.domain;

import dowob.xyz.stockwebv2.common.error.FieldValidationException;

import org.apache.commons.lang3.StringUtils;

import java.util.Map;

/**
 * 統一活動列表的排序鍵白名單（PORT-13）。
 *
 * <p>與 {@link TradeSortKey} 同理：ORDER BY 無法以具名參數繫結，每個常數持有一段<strong>寫死</strong>的 SQL 片段，
 * 使用者輸入只用於挑選常數。片段指向 UNION ALL 外層的別名 {@code a}（三個分支統一輸出的欄位），
 * 因此不能沿用 {@link TradeSortKey}（其片段寫死 {@code t.}）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public enum ActivitySortKey {
    /**
     * 依事件時間排序（交易成交時間 / 股利入帳時間 / 股數調整生效瞬間）；為預設排序鍵。
     * API 值沿用 {@code GET /trades} 的 {@code executedAt}，讓兩個端點的參數同名同語意。
     */
    EXECUTED_AT("executedAt", "a.occurred_at"),

    /**
     * 依寫入時間排序。
     */
    CREATED_AT("createdAt", "a.created_at"),

    /**
     * 依金額排序：交易為 {@code quantity × price}、股利為實領總額；股數調整沒有金額（NULLS LAST）。
     */
    TOTAL("total", "a.amount"),

    /**
     * 依數量排序：交易數量 / 配股股數 / 拆股 ΔQ；股利沒有數量（NULLS LAST）。
     */
    QUANTITY("quantity", "a.quantity");

    /**
     * 白名單外的值統一使用的錯誤原因；刻意列出合法值而不回射原始輸入。
     */
    private static final String INVALID_REASON = "must be one of executedAt, createdAt, total, quantity";

    /**
     * 對外 API 契約使用的參數值（camelCase），大小寫不敏感比對。
     */
    private final String apiValue;

    /**
     * 寫死的 ORDER BY SQL 片段，唯一的 SQL 文字來源。
     */
    private final String sqlFragment;

    ActivitySortKey(String apiValue, String sqlFragment) {
        this.apiValue = apiValue;
        this.sqlFragment = sqlFragment;
    }

    /**
     * 取得此排序鍵對應的 ORDER BY SQL 片段。
     *
     * @return 寫死的 SQL 片段，例如 {@code a.occurred_at}
     */
    public String sqlFragment() {
        return sqlFragment;
    }

    /**
     * 取得此排序鍵的對外 API 參數值。
     *
     * @return API 參數值，例如 {@code executedAt}
     */
    public String apiValue() {
        return apiValue;
    }

    /**
     * 將 API 傳入的 sort 參數解析為排序鍵（trim 後大小寫不敏感比對，與 {@link TradeSortKey} 同規則）。
     *
     * <p>與 {@link TradeSortKey} 的差別只在錯誤附 {@code fields.sort}（契約〈GET /portfolio/activities〉；前端依此標示欄位）。</p>
     *
     * @param value 使用者傳入的原始字串；null 或空白代表未指定
     * @return 對應的排序鍵；未指定時回傳預設值 {@link #EXECUTED_AT}
     * @throws FieldValidationException 值不在白名單內時，fields 指名 {@code sort}；訊息不回射原始輸入
     */
    public static ActivitySortKey fromApiValue(String value) {
        if (StringUtils.isBlank(value)) {
            return EXECUTED_AT;
        }
        String normalized = value.trim();
        for (ActivitySortKey key : values()) {
            if (key.apiValue.equalsIgnoreCase(normalized)) {
                return key;
            }
        }
        throw new FieldValidationException("sort " + INVALID_REASON, Map.of("sort", INVALID_REASON));
    }
}
