package dowob.xyz.stockwebv2.trading.domain;

import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import org.apache.commons.lang3.StringUtils;

import java.util.Locale;

/**
 * 交易查詢的排序方向白名單（D-06 / D-07）。
 *
 * <p>與 {@link TradeSortKey} 同理：ORDER BY 的方向關鍵字無法參數化，故以 enum 白名單
 * 映射到寫死的 {@code asc} / {@code desc} 常數，使用者輸入僅用於挑選常數。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public enum SortDirection {
    /**
     * 升冪排序。
     */
    ASC("asc"),

    /**
     * 降冪排序；為預設方向（D-07）。
     */
    DESC("desc");

    /**
     * 寫死的 SQL 排序關鍵字，唯一的 SQL 文字來源。
     */
    private final String sqlKeyword;

    SortDirection(String sqlKeyword) {
        this.sqlKeyword = sqlKeyword;
    }

    /**
     * 取得此方向對應的 SQL 關鍵字。
     *
     * @return 寫死的關鍵字 {@code asc} 或 {@code desc}
     */
    public String sqlKeyword() {
        return sqlKeyword;
    }

    /**
     * 將 API 傳入的 direction 參數解析為排序方向。
     *
     * @param value 使用者傳入的原始字串；null 或空白代表未指定
     * @return 對應的排序方向；未指定時回傳預設值 {@link #DESC}（D-07）
     * @throws BusinessException 當值不在白名單內時丟出 {@link ErrorCode#VALIDATION_FAILED}；
     *                           錯誤訊息刻意不回射原始輸入
     */
    public static SortDirection fromApiValue(String value) {
        if (StringUtils.isBlank(value)) {
            return DESC;
        }
        try {
            return SortDirection.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "direction must be asc or desc");
        }
    }
}
