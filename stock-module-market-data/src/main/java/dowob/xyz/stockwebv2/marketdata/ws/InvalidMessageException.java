package dowob.xyz.stockwebv2.marketdata.ws;

/**
 * 當 WebSocket 客戶端傳入的訊息格式無效或無法解析時拋出此例外。
 *
 * <p>觸發條件包括：
 * <ul>
 *   <li>JSON 格式錯誤（無法解析）</li>
 *   <li>缺少必要欄位（如 {@code type}）</li>
 *   <li>未知的訊息類型</li>
 *   <li>channels 欄位格式錯誤或含有無效的 channel 名稱</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0.0
 */
public class InvalidMessageException extends Exception {

    /**
     * 建立含有說明訊息的例外。
     *
     * @param message 說明此例外的訊息
     */
    public InvalidMessageException(String message) {
        super(message);
    }

    /**
     * 建立含有說明訊息與原因的例外。
     *
     * @param message 說明此例外的訊息
     * @param cause   造成此例外的原始例外
     */
    public InvalidMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
