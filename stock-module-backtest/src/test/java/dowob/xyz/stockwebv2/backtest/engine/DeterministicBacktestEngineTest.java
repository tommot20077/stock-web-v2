package dowob.xyz.stockwebv2.backtest.engine;

import dowob.xyz.stockwebv2.backtest.api.StrategyValidationDto;
import dowob.xyz.stockwebv2.backtest.domain.BacktestPeriod;
import dowob.xyz.stockwebv2.backtest.domain.BacktestResult;
import dowob.xyz.stockwebv2.backtest.domain.BacktestStrategyId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
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
    void equityAndKpisUseFullRequestedPeriod() {
        BacktestResult result = engine.run(input(BacktestPeriod.THREE_YEARS));

        assertThat(result.equityCurve()).last().satisfies(point -> {
            assertThat(point.date()).isEqualTo(lastMonthlyReturnDate(result.monthlyReturns()));
            assertThat(point.strategy()).isEqualByComparingTo(compound(new BigDecimal("100000.00"), result.monthlyReturns()));
        });
        assertThat(result.drawdownCurve()).last().satisfies(point ->
            assertThat(point.date()).isEqualTo(lastMonthlyReturnDate(result.monthlyReturns())));
        assertThat(result.kpis().drawdownDays()).isEqualTo(36 * 30);
    }

    @Test
    void presetStrategyAllowsNullStrategyCode() {
        BacktestResult result = engine.run(input(BacktestStrategyId.MA_CROSS, BacktestPeriod.ONE_YEAR, null));

        assertThat(result.equityCurve()).hasSize(12);
        assertThat(result.drawdownCurve()).hasSize(12);
        assertThat(result.monthlyReturns()).hasSize(12);
        assertThat(result.trades()).hasSize(12);
        assertThat(result.kpis().tradeCount()).isEqualTo(12);
    }

    @Test
    void presetStrategyResultIgnoresSuppliedStrategyCode() {
        BacktestResult withoutCode = engine.run(input(BacktestStrategyId.MA_CROSS, BacktestPeriod.ONE_YEAR, null));
        BacktestResult withCode = engine.run(input(BacktestStrategyId.MA_CROSS, BacktestPeriod.ONE_YEAR, "function strategy() { return 'ignored'; }"));

        assertThat(withCode).isEqualTo(withoutCode);
    }

    @Test
    void customStrategyRejectsNullStrategyCode() {
        assertThatThrownBy(() -> engine.run(input(BacktestStrategyId.CUSTOM, BacktestPeriod.ONE_YEAR, null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("strategyCode is required");
    }

    @Test
    void customStrategyRejectsBlankStrategyCode() {
        assertThatThrownBy(() -> engine.run(input(BacktestStrategyId.CUSTOM, BacktestPeriod.ONE_YEAR, "  ")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("strategyCode is required");
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
    void strategyValidatorIgnoresDelimitersInsideStringsAndComments() {
        assertThatCode(() -> new StrategyValidator().validate("""
            function strategy({ bars }) {
              const text = "})";
              const single = '{';
              const template = `] } (`;
              // comment with } )
              /* block comment with { ( */
              return text + single + template;
            }
            """))
            .doesNotThrowAnyException();
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

    @Test
    void inputRejectsNullInitialCapital() {
        assertThatThrownBy(() -> inputWithCapital(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("initialCapital is required");
    }

    @Test
    void inputRejectsZeroInitialCapital() {
        assertThatThrownBy(() -> inputWithCapital(BigDecimal.ZERO))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("initialCapital must be positive");
    }

    @Test
    void inputRejectsBlankSymbol() {
        assertThatThrownBy(() -> new BacktestEngineInput(
            42L,
            BacktestStrategyId.CUSTOM,
            "  ",
            BacktestPeriod.ONE_YEAR,
            new BigDecimal("100000.00"),
            "function strategy({ bars }) { return null; }"
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("symbol is required");
    }

    private BacktestEngineInput input(BacktestPeriod period) {
        return input(BacktestStrategyId.CUSTOM, period, "function strategy({ bars }) { return null; }");
    }

    private BacktestEngineInput input(BacktestStrategyId strategyId, BacktestPeriod period, String strategyCode) {
        return new BacktestEngineInput(
            42L,
            strategyId,
            "AAPL",
            period,
            new BigDecimal("100000.00"),
            strategyCode
        );
    }

    private BacktestEngineInput inputWithCapital(BigDecimal initialCapital) {
        return new BacktestEngineInput(
            42L,
            BacktestStrategyId.CUSTOM,
            "AAPL",
            BacktestPeriod.ONE_YEAR,
            initialCapital,
            "function strategy({ bars }) { return null; }"
        );
    }

    private BigDecimal compound(BigDecimal initialCapital, List<BacktestResult.MonthlyReturn> monthlyReturns) {
        BigDecimal amount = initialCapital.setScale(6, RoundingMode.HALF_UP);
        for (BacktestResult.MonthlyReturn monthlyReturn : monthlyReturns) {
            BigDecimal multiplier = BigDecimal.ONE.add(monthlyReturn.returnPct().divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
            amount = amount.multiply(multiplier).setScale(6, RoundingMode.HALF_UP);
        }
        return amount;
    }

    private java.time.LocalDate lastMonthlyReturnDate(List<BacktestResult.MonthlyReturn> monthlyReturns) {
        BacktestResult.MonthlyReturn last = monthlyReturns.getLast();
        return java.time.LocalDate.of(last.year(), last.month(), 1);
    }
}
