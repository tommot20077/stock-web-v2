package dowob.xyz.stockwebv2.trading.domain;

import dowob.xyz.stockwebv2.common.error.FieldValidationException;

import org.apache.commons.lang3.StringUtils;

import java.util.Map;

/**
 * 現金分錄的方向白名單（D-06）。
 *
 * <p>金額恆為正，方向完全由本列舉表達；沖銷（D-25）是一筆方向相反的分錄，不是負數金額。
 * 04.2 若加入股利入帳，會在此擴充列舉值並放寬 V15 的 {@code ck_cash_ledger_entries_type}。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public enum CashEntryType {
    /**
     * 入金：增加現金餘額。
     */
    DEPOSIT,

    /**
     * 出金：減少現金餘額。
     */
    WITHDRAWAL;

    /**
     * 解析建立分錄請求中的<strong>必填</strong> type 欄位，大小寫不敏感。
     *
     * <p>type 在請求中是 String 而非列舉，是為了讓失敗能以 {@code fields} 指名 {@code type}
     * （Jackson 反序列化列舉失敗會變成沒有 fields 的籠統 400）。錯誤訊息為靜態字串，
     * 不回射使用者輸入。</p>
     *
     * @param value 使用者傳入的原始字串
     * @return 對應的分錄類型
     * @throws FieldValidationException 值為空白或不在白名單內時丟出 VALIDATION_FAILED，fields 指名 {@code type}
     */
    public static CashEntryType fromApiValue(String value) {
        if (StringUtils.isNotBlank(value)) {
            for (CashEntryType candidate : values()) {
                if (candidate.name().equalsIgnoreCase(value.trim())) {
                    return candidate;
                }
            }
        }
        throw new FieldValidationException("Unsupported cash entry type", Map.of("type", "unsupported type"));
    }
}
