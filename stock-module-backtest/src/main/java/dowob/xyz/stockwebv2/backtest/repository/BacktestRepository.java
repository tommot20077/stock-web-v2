package dowob.xyz.stockwebv2.backtest.repository;

import dowob.xyz.stockwebv2.backtest.domain.BacktestResult;
import dowob.xyz.stockwebv2.backtest.domain.BacktestRun;
import dowob.xyz.stockwebv2.common.api.PageResponse;

import java.util.Optional;

public interface BacktestRepository {
    boolean activeSymbolExists(String symbol);

    BacktestRun createSucceededRun(BacktestRun run, BacktestResult result);

    Optional<BacktestRun> findRunForUser(Long userId, String externalRunId);

    Optional<BacktestResult> findResultForUser(Long userId, String externalRunId);

    PageResponse<BacktestRun> listRuns(Long userId, String symbol, int page, int size);
}
