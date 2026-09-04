package dowob.xyz.stockwebv2.backtest.engine;

import dowob.xyz.stockwebv2.backtest.domain.BacktestPeriod;
import dowob.xyz.stockwebv2.backtest.domain.BacktestStrategyId;

import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;

public record BacktestEngineInput(
    Long userId,
    BacktestStrategyId strategyId,
    String symbol,
    BacktestPeriod period,
    BigDecimal initialCapital,
    String strategyCode
) {
    public BacktestEngineInput {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
        if (strategyId == null) {
            throw new IllegalArgumentException("strategyId is required");
        }
        if (StringUtils.isBlank(symbol)) {
            throw new IllegalArgumentException("symbol is required");
        }
        if (period == null) {
            throw new IllegalArgumentException("period is required");
        }
        if (initialCapital == null) {
            throw new IllegalArgumentException("initialCapital is required");
        }
        if (initialCapital.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("initialCapital must be positive");
        }

        symbol = symbol.trim();
    }
}
