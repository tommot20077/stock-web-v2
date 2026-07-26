package dowob.xyz.stockwebv2.trading.domain;

import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;

/**
 * 交易類型白名單。
 *
 * <p>同一個列舉有兩條語意不同的入口：建立交易時 {@code type} 是必填欄位（空白即為錯誤），
 * 查詢篩選時 {@code type} 可省略（空白代表不篩選）。兩者以獨立方法表達，
 * 呼叫端不需要在外面補一層空白前置檢查來遷就必填語意。</p>
 *
 * @author Yuan
 * @version 1.1
 */
public enum TradeType {
    /**
     * 買進。
     */
    BUY,

    /**
     * 賣出。
     */
    SELL;

    /**
     * 解析建立交易請求中的<strong>必填</strong> type 欄位。
     *
     * @param value 使用者傳入的原始字串
     * @return 對應的交易類型
     * @throws BusinessException 值為空白或不在白名單內時丟出 {@link ErrorCode#TRADE_UNSUPPORTED_TYPE}
     */
    public static TradeType fromApiValue(String value) {
        return ApiValueParser.parseRequired(
            value, values(), Enum::name, ErrorCode.TRADE_UNSUPPORTED_TYPE,
            ErrorCode.TRADE_UNSUPPORTED_TYPE.defaultMessage());
    }

    /**
     * 解析交易查詢中的<strong>可選</strong> type 篩選參數。
     *
     * <p>錯誤碼沿用 {@link ErrorCode#TRADE_UNSUPPORTED_TYPE}（HTTP 400）而非 sort / direction
     * 使用的 {@code VALIDATION_FAILED}：兩者 HTTP 狀態相同，但錯誤碼已寫入前端所依循的
     * API 契約表，改動屬於契約破壞，故在此保留並記錄取捨。</p>
     *
     * @param value 使用者傳入的原始字串；null 或空白代表不依類型篩選
     * @return 對應的交易類型；未指定時回傳 null
     * @throws BusinessException 值不在白名單內時丟出 {@link ErrorCode#TRADE_UNSUPPORTED_TYPE}
     */
    public static TradeType fromFilterValue(String value) {
        return ApiValueParser.parseOptional(
            value, values(), Enum::name, null, ErrorCode.TRADE_UNSUPPORTED_TYPE,
            ErrorCode.TRADE_UNSUPPORTED_TYPE.defaultMessage());
    }
}
