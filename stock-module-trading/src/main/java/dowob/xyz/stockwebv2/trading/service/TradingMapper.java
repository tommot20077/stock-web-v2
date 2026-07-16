package dowob.xyz.stockwebv2.trading.service;

import dowob.xyz.stockwebv2.trading.api.TradeDto;
import dowob.xyz.stockwebv2.trading.domain.TradeTransaction;
import org.springframework.stereotype.Component;

@Component
public class TradingMapper {

    public TradeDto toTradeDto(TradeTransaction transaction) {
        return new TradeDto(
            transaction.uuid().toString(),
            transaction.symbol(),
            transaction.type().name(),
            transaction.quantity(),
            transaction.price(),
            transaction.fee(),
            transaction.note(),
            transaction.executedAt(),
            transaction.createdAt()
        );
    }
}
