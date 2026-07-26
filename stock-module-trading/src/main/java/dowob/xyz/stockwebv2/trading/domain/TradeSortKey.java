package dowob.xyz.stockwebv2.trading.domain;

import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;

/**
 * 交易查詢的排序鍵白名單（D-06）。
 *
 * <p>SQL 的 ORDER BY 子句無法以 JDBC 具名參數繫結，因此動態排序只有兩種實作方式：
 * 字串拼接（違反 code-standards「絕對禁止串接 SQL」硬規則），或本 enum 採用的白名單映射。
 * 每個列舉常數持有一段<strong>寫死</strong>的 SQL 片段，使用者輸入僅用於「挑選」哪一個常數，
 * 永遠不會成為 SQL 文字的一部分；白名單外的值在進入 repository 前即被擋下。</p>
 *
 * @author Yuan
 * @version 1.1
 */
public enum TradeSortKey {
    /**
     * 依成交時間排序，對應 {@code transactions.executed_at}；為預設排序鍵（D-07）。
     */
    EXECUTED_AT("executedAt", "t.executed_at"),

    /**
     * 依入帳時間排序，對應 {@code transactions.created_at}。
     *
     * <p>{@code executed_at} 由提交者填寫（補登舊交易的必要能力），{@code created_at} 則由
     * 資料庫在寫入當下產生、並受 V8 的 append-only trigger 保護，是本帳本唯一防竄改的時間軸。
     * D-07 把預設排序改為 {@code executed_at} 後，若不保留本排序鍵，防竄改的順序將無任何
     * 查詢參數可以取得；故一併納入白名單，對應 V7 既有的
     * {@code idx_transactions_user_created} 索引。</p>
     */
    CREATED_AT("createdAt", "t.created_at"),

    /**
     * 依交易金額排序，定義為 {@code 數量 × 單價}（不含手續費）；對應 V9 的運算式索引。
     */
    TOTAL("total", "(t.quantity * t.price)"),

    /**
     * 依交易數量排序，對應 {@code transactions.quantity}。
     */
    QUANTITY("quantity", "t.quantity");

    /**
     * 白名單外的值統一使用的錯誤訊息；刻意列出合法值而不回射原始輸入。
     */
    private static final String INVALID_MESSAGE = "sort must be one of executedAt, createdAt, total, quantity";

    /**
     * 對外 API 契約使用的參數值（camelCase），大小寫不敏感比對。
     */
    private final String apiValue;

    /**
     * 寫死的 ORDER BY SQL 片段，唯一的 SQL 文字來源。
     */
    private final String sqlFragment;

    TradeSortKey(String apiValue, String sqlFragment) {
        this.apiValue = apiValue;
        this.sqlFragment = sqlFragment;
    }

    /**
     * 取得此排序鍵對應的 ORDER BY SQL 片段。
     *
     * @return 寫死的 SQL 片段，例如 {@code t.executed_at}
     */
    public String sqlFragment() {
        return sqlFragment;
    }

    /**
     * 將 API 傳入的 sort 參數解析為排序鍵。
     *
     * @param value 使用者傳入的原始字串；null 或空白代表未指定
     * @return 對應的排序鍵；未指定時回傳預設值 {@link #EXECUTED_AT}（D-07）
     * @throws BusinessException 當值不在白名單內時丟出 {@link ErrorCode#VALIDATION_FAILED}；
     *                           錯誤訊息刻意不回射原始輸入，避免反射式輸出
     */
    public static TradeSortKey fromApiValue(String value) {
        return ApiValueParser.parseOptional(
            value, values(), key -> key.apiValue, EXECUTED_AT, ErrorCode.VALIDATION_FAILED, INVALID_MESSAGE);
    }
}
