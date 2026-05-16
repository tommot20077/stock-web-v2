package dowob.xyz.stockwebv2.backtest.service;

import dowob.xyz.stockwebv2.backtest.api.BacktestResultDto;
import dowob.xyz.stockwebv2.backtest.api.BacktestRunDto;
import dowob.xyz.stockwebv2.backtest.domain.BacktestResult;
import dowob.xyz.stockwebv2.backtest.domain.BacktestRun;
import org.springframework.stereotype.Component;

@Component
public class BacktestMapper {

    public BacktestRunDto toRunDto(BacktestRun run) {
        return new BacktestRunDto(
            externalRunId(run),
            run.strategyId().apiValue(),
            run.strategyLabel(),
            run.symbol(),
            run.period().apiValue(),
            run.initialCapital(),
            run.currency(),
            run.status().apiValue(),
            run.progress(),
            run.createdAt(),
            run.startedAt(),
            run.completedAt(),
            null
        );
    }

    public BacktestResultDto toResultDto(BacktestRun run, BacktestResult result) {
        return BacktestResultDto.fromDomain(externalRunId(run), run.status(), result);
    }

    private String externalRunId(BacktestRun run) {
        return "bt_" + run.uuid();
    }
}
