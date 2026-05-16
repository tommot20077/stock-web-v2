package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.backtest.domain.BacktestPeriod;
import dowob.xyz.stockwebv2.backtest.domain.BacktestRun;
import dowob.xyz.stockwebv2.backtest.domain.BacktestRunStatus;
import dowob.xyz.stockwebv2.backtest.domain.BacktestStrategyId;
import dowob.xyz.stockwebv2.backtest.engine.BacktestEngineInput;
import dowob.xyz.stockwebv2.backtest.engine.DeterministicBacktestEngine;
import dowob.xyz.stockwebv2.backtest.engine.StrategyValidator;
import dowob.xyz.stockwebv2.backtest.repository.JdbcBacktestRepository;
import dowob.xyz.stockwebv2.start.support.ContainerIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class BacktestPersistenceIT extends ContainerIT {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void backtestTablesAreCreatedByFlyway() {
        Integer tableCount = jdbcTemplate.queryForObject("""
            select count(*)
            from information_schema.tables
            where table_schema = 'public'
              and table_name in (
                'backtest_runs',
                'backtest_kpis',
                'backtest_equity_points',
                'backtest_drawdown_points',
                'backtest_monthly_returns',
                'backtest_trades'
              )
            """, Integer.class);

        assertThat(tableCount).isEqualTo(6);
    }

    @Test
    void persistsAndReadsBackNormalizedBacktestResult() {
        Long userId = createUser("backtest-reader@example.com", "backtest-reader");
        JdbcBacktestRepository repository = new JdbcBacktestRepository(jdbcTemplate.getDataSource());
        OffsetDateTime timestamp = OffsetDateTime.parse("2026-05-16T01:30:00Z");
        BacktestRun run = new BacktestRun(
            null,
            null,
            userId,
            null,
            BacktestStrategyId.MA_CROSS,
            BacktestStrategyId.MA_CROSS.label(),
            null,
            "AAPL",
            BacktestPeriod.THREE_YEARS,
            new BigDecimal("100000"),
            "USD",
            "buy_hold",
            "cached",
            BacktestRunStatus.SUCCEEDED,
            BigDecimal.ONE,
            null,
            null,
            timestamp,
            timestamp,
            timestamp
        );

        var result = new DeterministicBacktestEngine(new StrategyValidator()).run(new BacktestEngineInput(
            userId,
            BacktestStrategyId.MA_CROSS,
            "AAPL",
            BacktestPeriod.THREE_YEARS,
            new BigDecimal("100000"),
            null
        ));

        BacktestRun saved = repository.createSucceededRun(run, result);

        assertThat(repository.findRunForUser(userId, "bt_" + saved.uuid()).orElseThrow().symbol()).isEqualTo("AAPL");
        assertThat(repository.findResultForUser(userId, "bt_" + saved.uuid()).orElseThrow().trades()).hasSize(12);
    }

    private Long createUser(String email, String username) {
        return jdbcTemplate.queryForObject("""
            insert into users (email, username, password_hash, role, status, token_version)
            values (?, ?, 'hash', 'USER', 'ACTIVE', 1)
            returning id
            """, Long.class, email, username);
    }
}
