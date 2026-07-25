package dowob.xyz.stockwebv2.backtest.repository;

import dowob.xyz.stockwebv2.backtest.domain.BacktestPeriod;
import org.apache.commons.lang3.StringUtils;
import dowob.xyz.stockwebv2.backtest.domain.BacktestResult;
import dowob.xyz.stockwebv2.backtest.domain.BacktestRun;
import dowob.xyz.stockwebv2.backtest.domain.BacktestRunStatus;
import dowob.xyz.stockwebv2.backtest.domain.BacktestStrategyId;
import dowob.xyz.stockwebv2.common.api.PageResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcBacktestRepository implements BacktestRepository {
    private static final String RUN_COLUMNS = """
        id, uuid, user_id, strategy_version_id, strategy_id, strategy_label, strategy_code,
        symbol, period, initial_capital, currency, benchmark, data_mode, status, progress,
        error_code, error_message, created_at, started_at, completed_at
        """;

    private final JdbcClient jdbcClient;

    public JdbcBacktestRepository(DataSource dataSource) {
        this.jdbcClient = JdbcClient.create(dataSource);
    }

    @Override
    @Transactional
    public BacktestRun createSucceededRun(BacktestRun run, BacktestResult result) {
        BacktestRun saved = jdbcClient.sql("""
                insert into backtest_runs (
                    uuid, user_id, strategy_version_id, strategy_id, strategy_label, strategy_code,
                    symbol, period, initial_capital, currency, benchmark, data_mode, status, progress,
                    error_code, error_message, created_at, started_at, completed_at
                )
                values (
                    coalesce(:uuid, uuid_generate_v4()), :userId, :strategyVersionId, :strategyId, :strategyLabel, :strategyCode,
                    :symbol, :period, :initialCapital, :currency, :benchmark, :dataMode, :status, :progress,
                    :errorCode, :errorMessage, :createdAt, :startedAt, :completedAt
                )
                returning """ + " " + RUN_COLUMNS)
            .param("uuid", run.uuid())
            .param("userId", run.userId())
            .param("strategyVersionId", run.strategyVersionId())
            .param("strategyId", run.strategyId().apiValue())
            .param("strategyLabel", run.strategyLabel())
            .param("strategyCode", run.strategyCode())
            .param("symbol", run.symbol())
            .param("period", run.period().apiValue())
            .param("initialCapital", run.initialCapital())
            .param("currency", run.currency())
            .param("benchmark", run.benchmark())
            .param("dataMode", run.dataMode())
            .param("status", run.status().apiValue())
            .param("progress", run.progress())
            .param("errorCode", run.errorCode())
            .param("errorMessage", run.errorMessage())
            .param("createdAt", run.createdAt())
            .param("startedAt", run.startedAt())
            .param("completedAt", run.completedAt())
            .query(this::mapRun)
            .single();

        insertKpis(saved.id(), result.kpis());
        result.equityCurve().forEach(point -> insertEquityPoint(saved.id(), point));
        result.drawdownCurve().forEach(point -> insertDrawdownPoint(saved.id(), point));
        result.monthlyReturns().forEach(month -> insertMonthlyReturn(saved.id(), month));
        result.trades().forEach(trade -> insertTrade(saved.id(), trade));
        return saved;
    }

    @Override
    public Optional<BacktestRun> findRunForUser(Long userId, String externalRunId) {
        Optional<UUID> uuid = parseExternalRunId(externalRunId);
        if (uuid.isEmpty()) {
            return Optional.empty();
        }
        return jdbcClient.sql("select " + RUN_COLUMNS + " from backtest_runs where user_id = :userId and uuid = :uuid")
            .param("userId", userId)
            .param("uuid", uuid.get())
            .query(this::mapRun)
            .optional();
    }

    @Override
    public Optional<BacktestResult> findResultForUser(Long userId, String externalRunId) {
        Optional<BacktestRun> run = findRunForUser(userId, externalRunId);
        if (run.isEmpty()) {
            return Optional.empty();
        }
        Long runId = run.get().id();
        Optional<BacktestResult.BacktestKpis> kpis = jdbcClient.sql("select * from backtest_kpis where run_id = :runId")
            .param("runId", runId)
            .query((rs, rowNum) -> new BacktestResult.BacktestKpis(
                rs.getBigDecimal("total_return_pct"),
                rs.getBigDecimal("buy_hold_return_pct"),
                rs.getBigDecimal("sharpe"),
                rs.getBigDecimal("cagr_pct"),
                rs.getBigDecimal("max_drawdown_pct"),
                rs.getInt("drawdown_days"),
                rs.getBigDecimal("win_rate_pct"),
                rs.getInt("trade_count"),
                rs.getBigDecimal("profit_factor"),
                rs.getBigDecimal("avg_trade_pct")
            ))
            .optional();
        if (kpis.isEmpty()) {
            return Optional.empty();
        }

        List<BacktestResult.EquityPoint> equity = jdbcClient.sql("""
                select point_index, point_date, strategy_value, benchmark_value
                from backtest_equity_points
                where run_id = :runId
                order by point_index
                """)
            .param("runId", runId)
            .query((rs, rowNum) -> new BacktestResult.EquityPoint(
                rs.getInt("point_index"),
                rs.getObject("point_date", LocalDate.class),
                rs.getBigDecimal("strategy_value"),
                rs.getBigDecimal("benchmark_value")
            ))
            .list();
        List<BacktestResult.MonthlyReturn> monthly = jdbcClient.sql("""
                select return_year, return_month, return_pct
                from backtest_monthly_returns
                where run_id = :runId
                order by return_year, return_month
                """)
            .param("runId", runId)
            .query((rs, rowNum) -> new BacktestResult.MonthlyReturn(
                rs.getInt("return_year"),
                rs.getInt("return_month"),
                rs.getBigDecimal("return_pct")
            ))
            .list();
        List<BacktestResult.DrawdownPoint> drawdown = jdbcClient.sql("""
                select point_index, point_date, drawdown_pct
                from backtest_drawdown_points
                where run_id = :runId
                order by point_index
                """)
            .param("runId", runId)
            .query((rs, rowNum) -> new BacktestResult.DrawdownPoint(
                rs.getInt("point_index"),
                rs.getObject("point_date", LocalDate.class),
                rs.getBigDecimal("drawdown_pct")
            ))
            .list();
        List<BacktestResult.BacktestTrade> trades = jdbcClient.sql("""
                select trade_index, trade_date, side, entry_price, exit_price, bars, pnl, pnl_pct
                from backtest_trades
                where run_id = :runId
                order by trade_index
                """)
            .param("runId", runId)
            .query((rs, rowNum) -> new BacktestResult.BacktestTrade(
                rs.getInt("trade_index"),
                rs.getObject("trade_date", LocalDate.class),
                rs.getString("side"),
                rs.getBigDecimal("entry_price"),
                rs.getBigDecimal("exit_price"),
                rs.getInt("bars"),
                rs.getBigDecimal("pnl"),
                rs.getBigDecimal("pnl_pct")
            ))
            .list();

        return Optional.of(new BacktestResult(kpis.get(), equity, monthly, drawdown, trades));
    }

    @Override
    public PageResponse<BacktestRun> listRuns(Long userId, String symbol, int page, int size) {
        boolean filterSymbol = StringUtils.isNotBlank(symbol);
        long offset = (long) page * size;
        String where = filterSymbol ? "where user_id = :userId and symbol = :symbol " : "where user_id = :userId ";
        JdbcClient.StatementSpec listSpec = jdbcClient.sql("select " + RUN_COLUMNS + """
                from backtest_runs
                """ + where + """
                order by created_at desc, id desc
                limit :limit offset :offset
                """)
            .param("userId", userId)
            .param("limit", size)
            .param("offset", offset);
        JdbcClient.StatementSpec countSpec = jdbcClient.sql("select count(*) from backtest_runs " + where)
            .param("userId", userId);
        if (filterSymbol) {
            listSpec = listSpec.param("symbol", symbol);
            countSpec = countSpec.param("symbol", symbol);
        }
        long total = countSpec.query(Long.class).single();
        return PageResponse.of(listSpec.query(this::mapRun).list(), page, size, total);
    }

    private Optional<UUID> parseExternalRunId(String externalRunId) {
        if (externalRunId == null || !externalRunId.startsWith("bt_")) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(externalRunId.substring(3)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private void insertKpis(Long runId, BacktestResult.BacktestKpis kpis) {
        jdbcClient.sql("""
                insert into backtest_kpis (
                    run_id, total_return_pct, buy_hold_return_pct, sharpe, cagr_pct,
                    max_drawdown_pct, drawdown_days, win_rate_pct, trade_count, profit_factor, avg_trade_pct
                )
                values (
                    :runId, :totalReturnPct, :buyHoldReturnPct, :sharpe, :cagrPct,
                    :maxDrawdownPct, :drawdownDays, :winRatePct, :tradeCount, :profitFactor, :avgTradePct
                )
                """)
            .param("runId", runId)
            .param("totalReturnPct", kpis.totalReturnPct())
            .param("buyHoldReturnPct", kpis.buyHoldReturnPct())
            .param("sharpe", kpis.sharpe())
            .param("cagrPct", kpis.cagrPct())
            .param("maxDrawdownPct", kpis.maxDrawdownPct())
            .param("drawdownDays", kpis.drawdownDays())
            .param("winRatePct", kpis.winRatePct())
            .param("tradeCount", kpis.tradeCount())
            .param("profitFactor", kpis.profitFactor())
            .param("avgTradePct", kpis.avgTradePct())
            .update();
    }

    private void insertEquityPoint(Long runId, BacktestResult.EquityPoint point) {
        jdbcClient.sql("""
                insert into backtest_equity_points (run_id, point_index, point_date, strategy_value, benchmark_value)
                values (:runId, :pointIndex, :pointDate, :strategyValue, :benchmarkValue)
                """)
            .param("runId", runId)
            .param("pointIndex", point.pointIndex())
            .param("pointDate", point.date())
            .param("strategyValue", point.strategy())
            .param("benchmarkValue", point.benchmark())
            .update();
    }

    private void insertDrawdownPoint(Long runId, BacktestResult.DrawdownPoint point) {
        jdbcClient.sql("""
                insert into backtest_drawdown_points (run_id, point_index, point_date, drawdown_pct)
                values (:runId, :pointIndex, :pointDate, :drawdownPct)
                """)
            .param("runId", runId)
            .param("pointIndex", point.pointIndex())
            .param("pointDate", point.date())
            .param("drawdownPct", point.drawdownPct())
            .update();
    }

    private void insertMonthlyReturn(Long runId, BacktestResult.MonthlyReturn month) {
        jdbcClient.sql("""
                insert into backtest_monthly_returns (run_id, return_year, return_month, return_pct)
                values (:runId, :returnYear, :returnMonth, :returnPct)
                """)
            .param("runId", runId)
            .param("returnYear", month.year())
            .param("returnMonth", month.month())
            .param("returnPct", month.returnPct())
            .update();
    }

    private void insertTrade(Long runId, BacktestResult.BacktestTrade trade) {
        jdbcClient.sql("""
                insert into backtest_trades (run_id, trade_index, trade_date, side, entry_price, exit_price, bars, pnl, pnl_pct)
                values (:runId, :tradeIndex, :tradeDate, :side, :entryPrice, :exitPrice, :bars, :pnl, :pnlPct)
                """)
            .param("runId", runId)
            .param("tradeIndex", trade.tradeIndex())
            .param("tradeDate", trade.date())
            .param("side", trade.side())
            .param("entryPrice", trade.entry())
            .param("exitPrice", trade.exit())
            .param("bars", trade.bars())
            .param("pnl", trade.pnl())
            .param("pnlPct", trade.pnlPct())
            .update();
    }

    private BacktestRun mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new BacktestRun(
            rs.getLong("id"),
            rs.getObject("uuid", UUID.class),
            rs.getLong("user_id"),
            rs.getObject("strategy_version_id", Long.class),
            BacktestStrategyId.fromApiValue(rs.getString("strategy_id")),
            rs.getString("strategy_label"),
            rs.getString("strategy_code"),
            rs.getString("symbol"),
            BacktestPeriod.fromApiValue(rs.getString("period")),
            rs.getBigDecimal("initial_capital"),
            rs.getString("currency"),
            rs.getString("benchmark"),
            rs.getString("data_mode"),
            BacktestRunStatus.fromApiValue(rs.getString("status")),
            rs.getBigDecimal("progress"),
            rs.getString("error_code"),
            rs.getString("error_message"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("started_at", OffsetDateTime.class),
            rs.getObject("completed_at", OffsetDateTime.class)
        );
    }
}
