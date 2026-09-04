package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.start.support.ContainerIT;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FoundationMigrationIT extends ContainerIT {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void flywayCreatesFoundationTablesAndSeedsAssets() {
        Integer userTables = jdbcTemplate.queryForObject("select count(*) from information_schema.tables where table_name = 'users'", Integer.class);
        Integer assets = jdbcTemplate.queryForObject("select count(*) from assets", Integer.class);
        Integer prices = jdbcTemplate.queryForObject("select count(*) from asset_latest_prices", Integer.class);
        Integer fx = jdbcTemplate.queryForObject("select count(*) from fx_rates", Integer.class);
        List<String> keyAssetSymbols = jdbcTemplate.queryForList(
            "select symbol from assets where symbol in ('AAPL', '2330.TW', 'BTC', 'USD/TWD', 'US10Y')",
            String.class
        );
        List<String> fxPairs = jdbcTemplate.queryForList(
            "select base_currency || '/' || quote_currency from fx_rates where (base_currency, quote_currency) in (('USD', 'TWD'), ('EUR', 'USD'), ('USD', 'JPY'), ('TWD', 'USD'))",
            String.class
        );
        BigDecimal applePrice = jdbcTemplate.queryForObject(
            "select p.price from assets a join asset_latest_prices p on p.asset_id = a.id where a.symbol = 'AAPL'",
            BigDecimal.class
        );

        assertThat(userTables).isEqualTo(1);
        assertThat(assets).isEqualTo(19);
        assertThat(prices).isEqualTo(19);
        assertThat(fx).isEqualTo(4);
        assertThat(keyAssetSymbols).containsExactlyInAnyOrder("AAPL", "2330.TW", "BTC", "USD/TWD", "US10Y");
        assertThat(fxPairs).containsExactlyInAnyOrder("USD/TWD", "EUR/USD", "USD/JPY", "TWD/USD");
        assertThat(applePrice).isPositive();
    }
}
