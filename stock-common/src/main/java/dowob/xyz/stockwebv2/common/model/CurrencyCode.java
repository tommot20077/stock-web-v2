package dowob.xyz.stockwebv2.common.model;

/**
 * 系統支援的幣別代碼（ISO 4217）。
 *
 * <p><strong>值域必須與 DB 的三個 CHECK 約束同步</strong>：{@code ck_users_base_currency}（V14）、
 * {@code ck_cash_ledger_entries_currency}（V15）、{@code ck_assets_currency}（V19）。
 * 新增幣別時要同時加 enum 常數，並以新的 migration 重建這三個約束；只改一邊會讓 DB 拒絕合法值，
 * 或讓 DB 收下應用層轉不了 enum 的值。
 *
 * @author Yuan
 * @version 1.1
 */
public enum CurrencyCode {
    USD,
    TWD,
    EUR,
    JPY
}
