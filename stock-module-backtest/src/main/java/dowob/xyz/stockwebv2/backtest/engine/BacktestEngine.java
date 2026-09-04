package dowob.xyz.stockwebv2.backtest.engine;

import dowob.xyz.stockwebv2.backtest.domain.BacktestResult;

public interface BacktestEngine {
    BacktestResult run(BacktestEngineInput input);
}
