package dowob.xyz.stockwebv2.marketdata.provider;

/**
 * 市場資料提供者的通用例外，所有 {@link DataProvider} 操作的執行期錯誤皆繼承此類。
 *
 * <p>採用 unchecked exception（繼承 {@link RuntimeException}），
 * 讓呼叫端可選擇性處理，無需強制宣告 {@code throws}。
 * 常見情境：網路錯誤、上游 API 回傳非預期格式、rate limit 超限等。
 *
 * @author Yuan
 * @version 1.0.0
 */
public class DataProviderException extends RuntimeException {

    /**
     * 以訊息建立例外。
     *
     * @param message 描述錯誤原因的訊息；不可為 null
     */
    public DataProviderException(String message) {
        super(message);
    }

    /**
     * 以訊息與根因建立例外。
     *
     * @param message 描述錯誤原因的訊息；不可為 null
     * @param cause   造成此例外的原始例外；可為 null
     */
    public DataProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
