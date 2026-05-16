package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.start.support.ContainerIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

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
}
