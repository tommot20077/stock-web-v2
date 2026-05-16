package dowob.xyz.stockwebv2.backtest.engine;

import dowob.xyz.stockwebv2.backtest.domain.BacktestResult;
import dowob.xyz.stockwebv2.backtest.domain.BacktestResult.BacktestKpis;
import dowob.xyz.stockwebv2.backtest.domain.BacktestResult.BacktestTrade;
import dowob.xyz.stockwebv2.backtest.domain.BacktestResult.DrawdownPoint;
import dowob.xyz.stockwebv2.backtest.domain.BacktestResult.EquityPoint;
import dowob.xyz.stockwebv2.backtest.domain.BacktestResult.MonthlyReturn;
import dowob.xyz.stockwebv2.backtest.domain.BacktestStrategyId;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

@Component
public class DeterministicBacktestEngine implements BacktestEngine {
    private static final int CHART_POINT_COUNT = 12;
    private static final int TRADE_COUNT = 12;
    private static final LocalDate START_DATE = LocalDate.of(2026, 1, 1);
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final StrategyValidator strategyValidator;

    public DeterministicBacktestEngine(StrategyValidator strategyValidator) {
        this.strategyValidator = strategyValidator;
    }

    @Override
    public BacktestResult run(BacktestEngineInput input) {
        if (input.strategyId() == BacktestStrategyId.CUSTOM) {
            strategyValidator.validate(input.strategyCode());
        }

        Random random = new Random(seed(input));
        List<MonthlyReturn> monthlyReturns = monthlyReturns(input, random);
        List<EquityPoint> equityCurve = equityCurve(input.initialCapital(), monthlyReturns, random);
        List<DrawdownPoint> drawdownCurve = drawdownCurve(equityCurve);
        List<BacktestTrade> trades = trades(random);
        BacktestKpis kpis = kpis(input.initialCapital(), equityCurve, drawdownCurve, trades);

        return new BacktestResult(kpis, equityCurve, monthlyReturns, drawdownCurve, trades);
    }

    private long seed(BacktestEngineInput input) {
        String capital = input.initialCapital().setScale(2, RoundingMode.HALF_UP).toPlainString();
        int strategyCodeHash = input.strategyId() == BacktestStrategyId.CUSTOM && input.strategyCode() != null
            ? input.strategyCode().hashCode()
            : 0;
        return Objects.hash(
            input.userId(),
            input.strategyId().apiValue(),
            input.symbol(),
            input.period().apiValue(),
            capital,
            strategyCodeHash
        );
    }

    private List<MonthlyReturn> monthlyReturns(BacktestEngineInput input, Random random) {
        List<MonthlyReturn> monthlyReturns = new ArrayList<>();
        for (int index = 0; index < input.period().monthCount(); index++) {
            LocalDate date = START_DATE.plusMonths(index);
            monthlyReturns.add(new MonthlyReturn(date.getYear(), date.getMonthValue(), percent(random, -3500, 4500)));
        }
        return monthlyReturns;
    }

    private List<EquityPoint> equityCurve(BigDecimal initialCapital, List<MonthlyReturn> monthlyReturns, Random random) {
        List<EquityPoint> equityCurve = new ArrayList<>();
        BigDecimal strategy = money(initialCapital);
        BigDecimal benchmark = money(initialCapital);
        for (int index = 0; index < CHART_POINT_COUNT; index++) {
            BigDecimal strategyReturn = monthlyReturns.get(index).returnPct();
            BigDecimal benchmarkReturn = percent(random, -2500, 3500);
            strategy = applyReturn(strategy, strategyReturn);
            benchmark = applyReturn(benchmark, benchmarkReturn);
            equityCurve.add(new EquityPoint(index + 1, START_DATE.plusMonths(index), strategy, benchmark));
        }
        return equityCurve;
    }

    private List<DrawdownPoint> drawdownCurve(List<EquityPoint> equityCurve) {
        List<DrawdownPoint> drawdownCurve = new ArrayList<>();
        BigDecimal peak = equityCurve.getFirst().strategy();
        for (EquityPoint point : equityCurve) {
            if (point.strategy().compareTo(peak) > 0) {
                peak = point.strategy();
            }
            BigDecimal drawdownPct = point.strategy()
                .subtract(peak)
                .multiply(ONE_HUNDRED)
                .divide(peak, 6, RoundingMode.HALF_UP);
            drawdownCurve.add(new DrawdownPoint(point.pointIndex(), point.date(), scale(drawdownPct)));
        }
        return drawdownCurve;
    }

    private List<BacktestTrade> trades(Random random) {
        List<BacktestTrade> trades = new ArrayList<>();
        for (int index = 0; index < TRADE_COUNT; index++) {
            BigDecimal entry = money(new BigDecimal("80").add(value(random.nextInt(7000), 2)));
            BigDecimal pnlPct = percent(random, -5000, 7000);
            BigDecimal exit = applyReturn(entry, pnlPct);
            BigDecimal pnl = money(exit.subtract(entry));
            String side = index % 2 == 0 ? "BUY" : "SELL";
            trades.add(new BacktestTrade(index + 1, START_DATE.plusMonths(index), side, entry, exit, 5 + random.nextInt(20), pnl, pnlPct));
        }
        return trades;
    }

    private BacktestKpis kpis(
        BigDecimal initialCapital,
        List<EquityPoint> equityCurve,
        List<DrawdownPoint> drawdownCurve,
        List<BacktestTrade> trades
    ) {
        BigDecimal finalStrategy = equityCurve.getLast().strategy();
        BigDecimal finalBenchmark = equityCurve.getLast().benchmark();
        BigDecimal totalReturnPct = returnPct(initialCapital, finalStrategy);
        BigDecimal buyHoldReturnPct = returnPct(initialCapital, finalBenchmark);
        BigDecimal maxDrawdownPct = drawdownCurve.stream()
            .map(DrawdownPoint::drawdownPct)
            .min(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO);
        long wins = trades.stream().filter(trade -> trade.pnl().compareTo(BigDecimal.ZERO) > 0).count();
        BigDecimal winRatePct = scale(BigDecimal.valueOf(wins)
            .multiply(ONE_HUNDRED)
            .divide(BigDecimal.valueOf(trades.size()), 6, RoundingMode.HALF_UP));
        BigDecimal avgTradePct = scale(trades.stream()
            .map(BacktestTrade::pnlPct)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(trades.size()), 6, RoundingMode.HALF_UP));

        return new BacktestKpis(
            totalReturnPct,
            buyHoldReturnPct,
            scale(totalReturnPct.divide(new BigDecimal("10"), 6, RoundingMode.HALF_UP)),
            scale(totalReturnPct.divide(new BigDecimal("3"), 6, RoundingMode.HALF_UP)),
            scale(maxDrawdownPct),
            CHART_POINT_COUNT * 30,
            winRatePct,
            trades.size(),
            scale(new BigDecimal("1.500000")),
            avgTradePct
        );
    }

    private BigDecimal returnPct(BigDecimal initialCapital, BigDecimal finalCapital) {
        return scale(finalCapital.subtract(initialCapital)
            .multiply(ONE_HUNDRED)
            .divide(initialCapital, 6, RoundingMode.HALF_UP));
    }

    private BigDecimal applyReturn(BigDecimal amount, BigDecimal returnPct) {
        BigDecimal multiplier = BigDecimal.ONE.add(returnPct.divide(ONE_HUNDRED, 6, RoundingMode.HALF_UP));
        return money(amount.multiply(multiplier));
    }

    private BigDecimal percent(Random random, int minBasisPoints, int maxBasisPointsExclusive) {
        int basisPoints = minBasisPoints + random.nextInt(maxBasisPointsExclusive - minBasisPoints);
        return scale(value(basisPoints, 4));
    }

    private BigDecimal value(int unscaled, int scale) {
        return BigDecimal.valueOf(unscaled, scale);
    }

    private BigDecimal money(BigDecimal value) {
        return scale(value);
    }

    private BigDecimal scale(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP);
    }
}
