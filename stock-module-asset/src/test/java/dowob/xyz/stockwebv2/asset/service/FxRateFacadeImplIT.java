package dowob.xyz.stockwebv2.asset.service;

import dowob.xyz.stockwebv2.common.model.CurrencyCode;
import dowob.xyz.stockwebv2.infrastructure.fx.FxRates;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FxRateFacadeImpl} 整合測試。
 *
 * <p>使用 TimescaleDB testcontainer，透過 Flyway V1/V2 seed 資料驗證：
 * <ul>
 *   <li>currentRates 載入 seed 的四列匯率並可換算</li>
 *   <li>無法解析的幣別與非正匯率列被略過，不拋例外、不影響其他列</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0.0
 */
@Testcontainers
class FxRateFacadeImplIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.17.2-pg16")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("fx_rate_facade_test")
            .withUsername("test")
            .withPassword("test");

    static FxRateFacadeImpl facade;

    static JdbcClient jdbc;

    @BeforeAll
    static void setup() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .mixed(true)
                .load()
                .migrate();

        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUsername(postgres.getUsername());
        ds.setPassword(postgres.getPassword());

        jdbc = JdbcClient.create(ds);
        facade = new FxRateFacadeImpl(jdbc);
    }

    @Test
    @DisplayName("currentRates：載入 V2 seed 匯率，USD→TWD 為 32.418 且 EUR→TWD 可三角換算")
    void currentRatesLoadsSeedPairs() {
        FxRates rates = facade.currentRates();

        assertThat(rates.rate(CurrencyCode.USD, CurrencyCode.TWD))
                .hasValueSatisfying(r -> assertThat(r).isEqualByComparingTo("32.418"));
        assertThat(rates.rate(CurrencyCode.EUR, CurrencyCode.TWD)).isPresent();
    }

    @Test
    @DisplayName("currentRates：無法解析的幣別與非正匯率列被略過而不拋例外")
    void unparseableRowsAreSkippedNotThrown() {
        jdbc.sql("""
                        insert into fx_rates(base_currency, quote_currency, rate, rate_time)
                        values ('XXX', 'USD', 1, now())
                        """)
                .update();
        jdbc.sql("""
                        insert into fx_rates(base_currency, quote_currency, rate, rate_time)
                        values ('USD', 'EUR', 0, now())
                        """)
                .update();

        FxRates rates = facade.currentRates();

        assertThat(rates.rate(CurrencyCode.USD, CurrencyCode.TWD))
                .hasValueSatisfying(r -> assertThat(r).isEqualByComparingTo("32.418"));
        assertThat(rates.rate(CurrencyCode.USD, CurrencyCode.EUR))
                .as("rate = 0 的列不得被當成有效匯率；USD→EUR 應退回 EUR/USD 的反向列")
                .hasValueSatisfying(r -> assertThat(r).isLessThan(BigDecimal.ONE));
    }
}
