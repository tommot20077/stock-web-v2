package dowob.xyz.stockwebv2.backtest.api;

import dowob.xyz.stockwebv2.backtest.domain.BacktestResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record BacktestResultDto(
    String runId,
    String status,
    BacktestKpisDto kpis,
    List<EquityPointDto> equityCurve,
    List<MonthlyReturnDto> monthlyReturns,
    List<DrawdownPointDto> drawdownCurve,
    List<BacktestTradeDto> trades
) {
    public BacktestResultDto {
        equityCurve = List.copyOf(equityCurve);
        monthlyReturns = List.copyOf(monthlyReturns);
        drawdownCurve = List.copyOf(drawdownCurve);
        trades = List.copyOf(trades);
    }

    public static BacktestResultDto fromDomain(String runId, String status, BacktestResult result) {
        return new BacktestResultDto(
            runId,
            status,
            BacktestKpisDto.fromDomain(result.kpis()),
            result.equityCurve().stream().map(EquityPointDto::fromDomain).toList(),
            result.monthlyReturns().stream().map(MonthlyReturnDto::fromDomain).toList(),
            result.drawdownCurve().stream().map(DrawdownPointDto::fromDomain).toList(),
            result.trades().stream().map(BacktestTradeDto::fromDomain).toList()
        );
    }

    public record BacktestKpisDto(
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
        private static BacktestKpisDto fromDomain(BacktestResult.BacktestKpis kpis) {
            return new BacktestKpisDto(
                kpis.totalReturnPct(),
                kpis.buyHoldReturnPct(),
                kpis.sharpe(),
                kpis.cagrPct(),
                kpis.maxDrawdownPct(),
                kpis.drawdownDays(),
                kpis.winRatePct(),
                kpis.tradeCount(),
                kpis.profitFactor(),
                kpis.avgTradePct()
            );
        }
    }

    public record EquityPointDto(LocalDate t, BigDecimal strategy, BigDecimal benchmark) {
        private static EquityPointDto fromDomain(BacktestResult.EquityPoint point) {
            return new EquityPointDto(point.date(), point.strategy(), point.benchmark());
        }
    }

    public record MonthlyReturnDto(int year, int month, BigDecimal returnPct) {
        private static MonthlyReturnDto fromDomain(BacktestResult.MonthlyReturn monthlyReturn) {
            return new MonthlyReturnDto(monthlyReturn.year(), monthlyReturn.month(), monthlyReturn.returnPct());
        }
    }

    public record DrawdownPointDto(LocalDate t, BigDecimal drawdownPct) {
        private static DrawdownPointDto fromDomain(BacktestResult.DrawdownPoint point) {
            return new DrawdownPointDto(point.date(), point.drawdownPct());
        }
    }

    public record BacktestTradeDto(
        LocalDate date,
        String side,
        BigDecimal entry,
        BigDecimal exit,
        int bars,
        BigDecimal pnl,
        BigDecimal pnlPct
    ) {
        private static BacktestTradeDto fromDomain(BacktestResult.BacktestTrade trade) {
            return new BacktestTradeDto(
                trade.date(),
                trade.side(),
                trade.entry(),
                trade.exit(),
                trade.bars(),
                trade.pnl(),
                trade.pnlPct()
            );
        }
    }
}
