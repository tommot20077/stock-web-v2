package dowob.xyz.stockwebv2.trading.domain;

import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import org.apache.commons.lang3.StringUtils;

import java.util.function.Function;

/**
 * 交易模組 API 列舉參數的共用「白名單比對或拋錯」解析器。
 *
 * <p>{@link TradeSortKey}、{@link SortDirection} 與 {@link TradeType} 原本各自手寫一份
 * trim / 大小寫正規化 / 空白語意 / 錯誤訊息的邏輯，三份已經彼此分歧（空白的處理各不相同）。
 * 由本類統一後，新增列舉只需提供白名單與訊息，不必再複製一次規則。</p>
 *
 * <p>比對一律以 {@link String#equalsIgnoreCase} 進行：它不受預設地區影響，
 * 比 {@code toUpperCase(Locale.ROOT)} 更不易在土耳其地區等情境出錯，也不會為了比對而配置字串。
 * 錯誤訊息一律由呼叫端提供固定字串、刻意不回射原始輸入，避免使用者可控字串被反射回應答
 * （code-standards 錯誤訊息安全規則）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
final class ApiValueParser {

    private ApiValueParser() {
    }

    /**
     * 解析「可省略」的列舉參數，適用於查詢篩選：空白代表不指定。
     *
     * @param rawValue     使用者傳入的原始字串；null 或空白代表未指定
     * @param candidates   白名單候選值，通常為 {@code values()}
     * @param apiValueOf   取出候選值對外契約字串的函式
     * @param defaultValue 未指定時回傳的預設值，可為 null（代表「不篩選」）
     * @param errorCode    白名單外的值要丟出的錯誤碼
     * @param message      固定錯誤訊息，不得包含原始輸入
     * @param <E>          列舉型別
     * @return 對應的列舉常數；未指定時回傳 {@code defaultValue}
     * @throws BusinessException 值不在白名單內時
     */
    static <E extends Enum<E>> E parseOptional(
        String rawValue,
        E[] candidates,
        Function<E, String> apiValueOf,
        E defaultValue,
        ErrorCode errorCode,
        String message
    ) {
        if (StringUtils.isBlank(rawValue)) {
            return defaultValue;
        }
        return match(rawValue, candidates, apiValueOf, errorCode, message);
    }

    /**
     * 解析「必填」的列舉參數，適用於建立資源的請求主體：空白即為錯誤。
     *
     * @param rawValue   使用者傳入的原始字串
     * @param candidates 白名單候選值，通常為 {@code values()}
     * @param apiValueOf 取出候選值對外契約字串的函式
     * @param errorCode  空白或白名單外的值要丟出的錯誤碼
     * @param message    固定錯誤訊息，不得包含原始輸入
     * @param <E>        列舉型別
     * @return 對應的列舉常數
     * @throws BusinessException 值為空白或不在白名單內時
     */
    static <E extends Enum<E>> E parseRequired(
        String rawValue,
        E[] candidates,
        Function<E, String> apiValueOf,
        ErrorCode errorCode,
        String message
    ) {
        if (StringUtils.isBlank(rawValue)) {
            throw new BusinessException(errorCode, message);
        }
        return match(rawValue, candidates, apiValueOf, errorCode, message);
    }

    private static <E extends Enum<E>> E match(
        String rawValue,
        E[] candidates,
        Function<E, String> apiValueOf,
        ErrorCode errorCode,
        String message
    ) {
        String normalized = rawValue.trim();
        for (E candidate : candidates) {
            if (apiValueOf.apply(candidate).equalsIgnoreCase(normalized)) {
                return candidate;
            }
        }
        throw new BusinessException(errorCode, message);
    }
}
