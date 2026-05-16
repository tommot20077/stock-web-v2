package dowob.xyz.stockwebv2.backtest.engine;

import dowob.xyz.stockwebv2.backtest.domain.BacktestPeriod;
import dowob.xyz.stockwebv2.backtest.domain.BacktestStrategyId;

import java.math.BigDecimal;

public record BacktestEngineInput(
    Long userId,
    BacktestStrategyId strategyId,
    String symbol,
    BacktestPeriod period,
    BigDecimal initialCapital,
    String strategyCode
) {
}
