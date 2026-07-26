package dowob.xyz.stockwebv2.backtest.service;

import dowob.xyz.stockwebv2.backtest.api.BacktestResultDto;
import dowob.xyz.stockwebv2.backtest.api.BacktestRunDto;
import dowob.xyz.stockwebv2.backtest.domain.BacktestResult;
import dowob.xyz.stockwebv2.backtest.domain.BacktestRun;
import dowob.xyz.stockwebv2.common.api.ApiError;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Map;

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
            toError(run)
        );
    }

    public BacktestResultDto toResultDto(BacktestRun run, BacktestResult result) {
        return BacktestResultDto.fromDomain(externalRunId(run), run.status(), result);
    }

    private String externalRunId(BacktestRun run) {
        return "bt_" + run.uuid();
    }

    private ApiError toError(BacktestRun run) {
        if (StringUtils.isNotBlank(run.errorCode()) || StringUtils.isNotBlank(run.errorMessage())) {
            return new ApiError(run.errorCode(), run.errorMessage(), Map.of());
        }
        return null;
    }
}
