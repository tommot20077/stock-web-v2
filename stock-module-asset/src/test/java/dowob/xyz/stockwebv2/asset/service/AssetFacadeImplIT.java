package dowob.xyz.stockwebv2.asset.service;

import dowob.xyz.stockwebv2.common.model.AssetType;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetSummary;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AssetFacadeImpl} 整合測試。
 *
 * <p>使用 TimescaleDB testcontainer，透過 Flyway V1/V2 seed 資料驗證：
 * <ul>
 *   <li>findAllTradeable 只回傳 active + tradeable 的 asset，並按 symbol 升冪排序</li>
 *   <li>findBySymbol 依大小寫敏感的 symbol 查詢，找不到回 Optional.empty()</li>
 *   <li>findBySymbol null 輸入拋出 NullPointerException</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0.0
 */
@Testcontainers
class AssetFacadeImplIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:latest-pg16")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("asset_facade_test")
            .withUsername("test")
            .withPassword("test");

    static AssetFacadeImpl facade;

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

        JdbcClient client = JdbcClient.create(ds);
        facade = new AssetFacadeImpl(client);
    }

    // ── findAllTradeable ──────────────────────────────────────────────────────

    @Test
    @DisplayName("findAllTradeable：V2 seed 含 11 筆 active+tradeable，應全數回傳")
    void findAllTradeable_returnsAllSeededActiveAssets() {
        List<AssetSummary> assets = facade.findAllTradeable();

        // V2 seed: AAPL, NVDA, TSLA, MSFT, 2330.TW, GOOGL, AMZN, META, BTC, ETH, SOL = 11 筆
        assertThat(assets).hasSize(11);
        assertThat(assets).allSatisfy(a -> {
            assertThat(a.active()).isTrue();
            assertThat(a.tradeable()).isTrue();
        });
    }

    @Test
    @DisplayName("findAllTradeable：結果應按 symbol 升冪排序")
    void findAllTradeable_isOrderedBySymbolAsc() {
        List<AssetSummary> assets = facade.findAllTradeable();

        assertThat(assets).isNotEmpty();
        for (int i = 0; i < assets.size() - 1; i++) {
            assertThat(assets.get(i).symbol())
                    .as("index %d symbol should be less than index %d", i, i + 1)
                    .isLessThan(assets.get(i + 1).symbol());
        }
    }

    @Test
    @DisplayName("findAllTradeable：FX / BOND（tradeable=false）不應出現在結果中")
    void findAllTradeable_excludesNonTradeableAssets() {
        List<AssetSummary> assets = facade.findAllTradeable();

        List<String> symbols = assets.stream().map(AssetSummary::symbol).toList();
        assertThat(symbols).doesNotContain("USD/TWD", "EUR/USD", "USD/JPY",
                "US10Y", "US2Y", "DE10Y", "JP10Y", "TW10Y");
    }

    @Test
    @DisplayName("findAllTradeable：每筆資料的 assetType、market 欄位不為 null")
    void findAllTradeable_allFieldsPopulated() {
        List<AssetSummary> assets = facade.findAllTradeable();

        assertThat(assets).allSatisfy(a -> {
            assertThat(a.id()).isNotNull().isPositive();
            assertThat(a.symbol()).isNotBlank();
            assertThat(a.name()).isNotBlank();
            assertThat(a.assetType()).isNotNull();
            assertThat(a.market()).isNotBlank();
        });
    }

    // ── findBySymbol ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("findBySymbol(AAPL)：應回傳 Present，且 assetType 為 STOCK")
    void findBySymbol_returnsAssetForKnownSymbol() {
        Optional<AssetSummary> aapl = facade.findBySymbol("AAPL");

        assertThat(aapl).isPresent();
        assertThat(aapl.get().symbol()).isEqualTo("AAPL");
        assertThat(aapl.get().assetType()).isEqualTo(AssetType.STOCK);
    }

    @Test
    @DisplayName("findBySymbol(BTC)：應回傳 Present，且 assetType 為 CRYPTO")
    void findBySymbol_returnsCorrectTypeForCrypto() {
        Optional<AssetSummary> btc = facade.findBySymbol("BTC");

        assertThat(btc).isPresent();
        assertThat(btc.get().assetType()).isEqualTo(AssetType.CRYPTO);
        assertThat(btc.get().market()).isEqualTo("CRYPTO");
    }

    @Test
    @DisplayName("findBySymbol(UNKNOWN_XYZ)：不存在的 symbol 應回傳 Optional.empty()")
    void findBySymbol_returnsEmptyForUnknownSymbol() {
        Optional<AssetSummary> result = facade.findBySymbol("UNKNOWN_XYZ");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findBySymbol 大小寫敏感：小寫 aapl 應回傳 Optional.empty()")
    void findBySymbol_isCaseSensitive() {
        // V2 seed 只有 'AAPL'（大寫），小寫應找不到
        assertThat(facade.findBySymbol("aapl")).isEmpty();
    }

    @Test
    @DisplayName("findBySymbol(null)：應拋出 NullPointerException")
    void findBySymbol_nullThrows() {
        assertThatThrownBy(() -> facade.findBySymbol(null))
                .isInstanceOf(NullPointerException.class);
    }
}
