package dowob.xyz.stockwebv2.marketdata.api;

import dowob.xyz.stockwebv2.common.model.AssetType;
import dowob.xyz.stockwebv2.common.model.KlineInterval;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetFacade;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetSummary;
import dowob.xyz.stockwebv2.marketdata.persistence.MarketPrice;
import dowob.xyz.stockwebv2.marketdata.persistence.MarketPriceRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link KlineQueryService} 端對端整合測試。
 *
 * <p>使用 TimescaleDB testcontainer + Flyway 遷移建立完整 schema，
 * 插入真實 tick 資料後手動觸發 CA 刷新，再透過 {@link KlineQueryService} 查詢驗證
 * OHLCV 聚合結果的正確性。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Testcontainers
class MarketControllerKlinesIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
        DockerImageName.parse("timescale/timescaledb:2.17.2-pg16")
            .asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("klines_e2e")
        .withUsername("test")
        .withPassword("test");

    static MarketPriceRepository repo;
    static KlineQueryService service;

    /** asset_id = 1L，對應 V2 seed 資料中第一筆 assets 記錄（AAPL）。 */
    static final Long ASSET_ID = 1L;

    @BeforeAll
    static void setup() throws Exception {
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
        NamedParameterJdbcTemplate npjt = new NamedParameterJdbcTemplate(ds);
        repo = new MarketPriceRepository(client, npjt);

        AssetFacade fakeFacade = mock(AssetFacade.class);
        when(fakeFacade.findBySymbol("AAPL")).thenReturn(Optional.of(
            new AssetSummary(ASSET_ID, "AAPL", "Apple Inc.", AssetType.STOCK, "NASDAQ", true, true)));

        service = new KlineQueryService(fakeFacade, client);
    }

    // ── 1m Continuous Aggregate ────────────────────────────────────────────────

    @Test
    @DisplayName("klines (1m): 插入跨 3 分鐘的 tick → CA 刷新後可查到 3 個 bucket")
    void klines_returnsAggregatedBuckets() throws Exception {
        // 插入跨 3 分鐘的 tick，每分鐘每 10 秒一筆
        Instant baseTime = Instant.parse("2026-01-01T10:00:00Z");
        List<MarketPrice> ticks = new ArrayList<>();
        for (int min = 0; min < 3; min++) {
            for (int sec = 0; sec < 60; sec += 10) {
                BigDecimal price = new BigDecimal("100").add(BigDecimal.valueOf(min * 10L + sec / 10L));
                ticks.add(new MarketPrice(ASSET_ID,
                    baseTime.plusSeconds((long) min * 60 + sec),
                    price,
                    new BigDecimal("100")));
            }
        }
        repo.insertAll(ticks);

        // 手動刷新 kline_1m continuous aggregate
        try (Connection c = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement()) {
            s.execute("CALL refresh_continuous_aggregate('kline_1m', NULL, NULL)");
        }

        List<KlineDto> klines = service.findKlines(
            "AAPL",
            KlineInterval.ONE_MINUTE,
            baseTime.minusSeconds(10),
            baseTime.plusSeconds(180),
            null
        );

        assertThat(klines).hasSize(3);

        // 第一個 bucket 應為 2026-01-01T10:00:00Z
        KlineDto first = klines.get(0);
        assertThat(first.bucket()).isEqualTo(baseTime);

        // open = 第一筆價格（price @ 10:00:00 = 100 + 0 = 100）
        assertThat(first.open()).isEqualByComparingTo(new BigDecimal("100"));
        // high = 最高價（price @ 10:00:50 = 100 + 5 = 105）
        assertThat(first.high()).isEqualByComparingTo(new BigDecimal("105"));
        // volume = 6 筆 × 100 = 600
        assertThat(first.volume()).isEqualByComparingTo(new BigDecimal("600"));
    }
}
