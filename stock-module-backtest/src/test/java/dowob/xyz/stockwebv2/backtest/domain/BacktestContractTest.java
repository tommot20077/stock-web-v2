package dowob.xyz.stockwebv2.backtest.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BacktestContractTest {

    @Test
    void strategyIdsParseApiValues() {
        assertThat(BacktestStrategyId.fromApiValue("ma_cross")).isEqualTo(BacktestStrategyId.MA_CROSS);
        assertThat(BacktestStrategyId.fromApiValue("rsi")).isEqualTo(BacktestStrategyId.RSI);
        assertThat(BacktestStrategyId.fromApiValue("momentum")).isEqualTo(BacktestStrategyId.MOMENTUM);
        assertThat(BacktestStrategyId.fromApiValue("dca")).isEqualTo(BacktestStrategyId.DCA);
        assertThat(BacktestStrategyId.fromApiValue("custom")).isEqualTo(BacktestStrategyId.CUSTOM);
    }

    @Test
    void periodsParseApiValuesAndExposeMonthCounts() {
        assertThat(BacktestPeriod.fromApiValue("1Y").monthCount()).isEqualTo(12);
        assertThat(BacktestPeriod.fromApiValue("3Y").monthCount()).isEqualTo(36);
        assertThat(BacktestPeriod.fromApiValue("5Y").monthCount()).isEqualTo(60);
    }

    @Test
    void statusesParseApiValues() {
        assertThat(BacktestRunStatus.fromApiValue("queued")).isEqualTo(BacktestRunStatus.QUEUED);
        assertThat(BacktestRunStatus.fromApiValue("running")).isEqualTo(BacktestRunStatus.RUNNING);
        assertThat(BacktestRunStatus.fromApiValue("succeeded")).isEqualTo(BacktestRunStatus.SUCCEEDED);
        assertThat(BacktestRunStatus.fromApiValue("failed")).isEqualTo(BacktestRunStatus.FAILED);
        assertThat(BacktestRunStatus.fromApiValue("rejected")).isEqualTo(BacktestRunStatus.REJECTED);
    }

    @Test
    void invalidValuesAreRejected() {
        assertThatThrownBy(() -> BacktestStrategyId.fromApiValue("other"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BacktestPeriod.fromApiValue("10Y"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BacktestRunStatus.fromApiValue("done"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
