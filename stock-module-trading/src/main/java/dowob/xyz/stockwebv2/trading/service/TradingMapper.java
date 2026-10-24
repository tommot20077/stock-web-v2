package dowob.xyz.stockwebv2.trading.service;

import dowob.xyz.stockwebv2.trading.api.TradeDto;
import dowob.xyz.stockwebv2.trading.domain.TradeTransaction;

import org.springframework.stereotype.Component;

@Component
public class TradingMapper {

    /**
     * 將交易紀錄轉為對外 DTO。
     *
     * @param transaction 交易紀錄
     * @param currency    標的交易幣別（{@code CurrencyCode} 常數名）；查無 profile 時為 null
     * @return 交易 DTO
     */
    public TradeDto toTradeDto(TradeTransaction transaction, String currency) {
        return new TradeDto(
            transaction.uuid().toString(),
            transaction.symbol(),
            transaction.type().name(),
            transaction.quantity(),
            transaction.price(),
            transaction.fee(),
            transaction.note(),
            transaction.executedAt(),
            transaction.createdAt(),
            currency
        );
    }
}
