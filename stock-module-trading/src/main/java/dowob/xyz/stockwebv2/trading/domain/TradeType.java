package dowob.xyz.stockwebv2.trading.domain;

import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;

import java.util.Locale;

public enum TradeType {
    BUY,
    SELL;

    public static TradeType fromApiValue(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.TRADE_UNSUPPORTED_TYPE, ErrorCode.TRADE_UNSUPPORTED_TYPE.defaultMessage());
        }
        try {
            return TradeType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.TRADE_UNSUPPORTED_TYPE, ErrorCode.TRADE_UNSUPPORTED_TYPE.defaultMessage());
        }
    }
}
