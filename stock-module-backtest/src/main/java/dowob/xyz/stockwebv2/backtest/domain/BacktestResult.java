package dowob.xyz.stockwebv2.backtest.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record BacktestResult(
    BacktestKpis kpis,
    List<EquityPoint> equityCurve,
    List<MonthlyReturn> monthlyReturns,
    List<DrawdownPoint> drawdownCurve,
    List<BacktestTrade> trades
) {
    public BacktestResult {
        equityCurve = List.copyOf(equityCurve);
        monthlyReturns = List.copyOf(monthlyReturns);
        drawdownCurve = List.copyOf(drawdownCurve);
        trades = List.copyOf(trades);
    }

    public record BacktestKpis(
        BigDecimal totalReturnPct,
        BigDecimal buyHoldReturnPct,
        BigDecimal sharpe,
        BigDecimal cagrPct,
        BigDecimal maxDrawdownPct,
        int drawdownDays,
        BigDecimal winRatePct,
        int tradeCount,
        BigDecimal profitFactor,
        BigDecimal avgTradePct
    ) {
    }

    public record EquityPoint(int pointIndex, LocalDate date, BigDecimal strategy, BigDecimal benchmark) {
    }

    public record MonthlyReturn(int year, int month, BigDecimal returnPct) {
    }

    public record DrawdownPoint(int pointIndex, LocalDate date, BigDecimal drawdownPct) {
    }

    public record BacktestTrade(
        int tradeIndex,
        LocalDate date,
        String side,
        BigDecimal entry,
        BigDecimal exit,
        int bars,
        BigDecimal pnl,
        BigDecimal pnlPct
    ) {
    }
}
