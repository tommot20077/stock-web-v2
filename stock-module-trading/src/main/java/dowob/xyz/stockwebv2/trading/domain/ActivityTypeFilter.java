package dowob.xyz.stockwebv2.trading.domain;

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
     * 將 API 傳入的 type 參數解析為篩選（RED 骨架）。
     *
     * @param value 使用者傳入的原始字串
     * @return empty
     */
    public static Optional<ActivityTypeFilter> fromApiValue(String value) {
        return Optional.empty();
    }
}
