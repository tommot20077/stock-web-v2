package dowob.xyz.stockwebv2.backtest.engine;

import dowob.xyz.stockwebv2.backtest.api.StrategyValidationDto;
import dowob.xyz.stockwebv2.backtest.domain.BacktestPeriod;
import dowob.xyz.stockwebv2.backtest.domain.BacktestResult;
import dowob.xyz.stockwebv2.backtest.domain.BacktestStrategyId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeterministicBacktestEngineTest {

    private final DeterministicBacktestEngine engine = new DeterministicBacktestEngine(new StrategyValidator());

    @Test
    void sameInputProducesSameResult() {
        BacktestEngineInput input = input(BacktestPeriod.THREE_YEARS);

        BacktestResult first = engine.run(input);
        BacktestResult second = engine.run(input);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void resultContainsFullFrontendData() {
        BacktestResult result = engine.run(input(BacktestPeriod.THREE_YEARS));

        assertThat(result.equityCurve()).hasSize(12);
        assertThat(result.drawdownCurve()).hasSize(12);
        assertThat(result.monthlyReturns()).hasSize(36);
        assertThat(result.trades()).hasSize(12);
        assertThat(result.kpis().tradeCount()).isEqualTo(12);
    }

    @Test
    void periodControlsMonthlyReturnCount() {
        assertThat(engine.run(input(BacktestPeriod.ONE_YEAR)).monthlyReturns()).hasSize(12);
        assertThat(engine.run(input(BacktestPeriod.THREE_YEARS)).monthlyReturns()).hasSize(36);
        assertThat(engine.run(input(BacktestPeriod.FIVE_YEARS)).monthlyReturns()).hasSize(60);
    }

    @Test
    void strategyValidatorAcceptsNamedStrategyFunction() {
        StrategyValidationDto validation = new StrategyValidator()
            .validate("function strategy({ bars }) { return null; }");

        assertThat(validation.valid()).isTrue();
        assertThat(validation.normalizedName()).isEqualTo("strategy");
        assertThat(validation.warnings()).isEqualTo(List.of());
    }

    @Test
    void strategyValidatorRejectsMissingStrategyFunction() {
        assertThatThrownBy(() -> new StrategyValidator().validate("function helper() { return null; }"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("strategy");
    }

    @Test
    void strategyValidatorRejectsBlankSource() {
        assertThatThrownBy(() -> new StrategyValidator().validate("   "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("strategyCode is required");
    }

    @Test
    void strategyValidatorRejectsUnbalancedDelimiters() {
        assertThatThrownBy(() -> new StrategyValidator().validate("function strategy({ bars }) { return null; "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("strategyCode has unbalanced delimiters");
    }

    private BacktestEngineInput input(BacktestPeriod period) {
        return new BacktestEngineInput(
            42L,
            BacktestStrategyId.CUSTOM,
            "AAPL",
            period,
            new BigDecimal("100000.00"),
            "function strategy({ bars }) { return null; }"
        );
    }
}
