package dowob.xyz.stockwebv2.marketdata.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 整合測試：驗證 Flyway V3、V4 遷移腳本正確建立 market_prices TimescaleDB hypertable、
 * 預期索引，以及 5 個 K 線 Continuous Aggregate views 與對應的自動刷新 Policy。
 */
@Testcontainers
class MarketDataMigrationIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:latest-pg16")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("market_data_test")
            .withUsername("test")
            .withPassword("test");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .mixed(true)
                .load()
                .migrate();
    }

    @Test
    void market_prices_isCreatedAsHypertable() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM _timescaledb_catalog.hypertable WHERE table_name = 'market_prices'");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void market_prices_hasExpectedIndex() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM pg_indexes WHERE tablename = 'market_prices' AND indexname = 'idx_market_prices_asset_time'");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("V4 遷移後，5 個 K 線 Continuous Aggregate views 應全數存在")
    void kline_continuousAggregateViews_allExist() throws Exception {
        List<String> expectedViews = List.of("kline_1d", "kline_1h", "kline_15m", "kline_5m", "kline_1m");
        List<String> actualViews = new ArrayList<>();

        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT view_name FROM timescaledb_information.continuous_aggregates ORDER BY view_name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                actualViews.add(rs.getString("view_name"));
            }
        }

        assertThat(actualViews)
                .containsExactlyInAnyOrderElementsOf(expectedViews);
    }

    @Test
    @DisplayName("V4 遷移後，5 個 K 線 Continuous Aggregate views 均應有對應的自動刷新 Policy")
    void kline_continuousAggregatePolicies_allExist() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM timescaledb_information.jobs WHERE proc_name = 'policy_refresh_continuous_aggregate'");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(5);
        }
    }
}
