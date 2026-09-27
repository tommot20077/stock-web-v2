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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    static JdbcClient jdbc;

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
        jdbc = client;
        NamedParameterJdbcTemplate npjt = new NamedParameterJdbcTemplate(ds);
        repo = new MarketPriceRepository(client, npjt);

        AssetFacade fakeFacade = mock(AssetFacade.class);
        when(fakeFacade.findBySymbol("AAPL")).thenReturn(Optional.of(
            new AssetSummary(ASSET_ID, "AAPL", "Apple Inc.", AssetType.STOCK, "NASDAQ", true, true)));
        for (String symbol : List.of("NVDA", "TSLA", "EUR/USD")) {
            AssetSummary seeded = client.sql("select id, asset_type, market from assets where symbol = :s")
                .param("s", symbol)
                .query((rs, n) -> new AssetSummary(rs.getLong("id"), symbol, symbol,
                    AssetType.valueOf(rs.getString("asset_type")), rs.getString("market"), true, true))
                .single();
            when(fakeFacade.findBySymbol(symbol)).thenReturn(Optional.of(seeded));
        }

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

    // ── 即時性:未刷新的最近資料也要查得到 ─────────────────────────────────────

    @Test
    @DisplayName("klines (1m): 未手動刷新 CA,最近的 tick 仍即時出現在 K 線(real-time aggregate)")
    void klines_includeRecentTicksWithoutRefresh() {
        Long nvda = assetId("NVDA");
        Instant minute = Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(5, ChronoUnit.MINUTES);
        repo.insertAll(List.of(
            new MarketPrice(nvda, minute.plusSeconds(5), new BigDecimal("500"), BigDecimal.ONE),
            new MarketPrice(nvda, minute.plusSeconds(35), new BigDecimal("510"), BigDecimal.ONE)));

        List<KlineDto> klines = service.findKlines("NVDA", KlineInterval.ONE_MINUTE,
            minute, minute.plusSeconds(60), null);

        assertThat(klines).hasSize(1);
        assertThat(klines.get(0).close()).isEqualByComparingTo("510");
    }

    // ── 1D 依市場交易日 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("klines (1d): 美股依紐約午夜切日,UTC 同一天的兩筆 tick 分成兩根日 K")
    void oneDay_usStock_bucketsByNewYorkDay() {
        Long tsla = assetId("TSLA");
        repo.insertAll(List.of(
            // 紐約 2/1 23:30(EST)
            new MarketPrice(tsla, Instant.parse("2026-02-02T04:30:00Z"), new BigDecimal("300"), BigDecimal.ONE),
            // 紐約 2/2 01:00 與 02:00
            new MarketPrice(tsla, Instant.parse("2026-02-02T06:00:00Z"), new BigDecimal("310"), new BigDecimal("2")),
            new MarketPrice(tsla, Instant.parse("2026-02-02T07:00:00Z"), new BigDecimal("305"), new BigDecimal("3"))));

        List<KlineDto> klines = service.findKlines("TSLA", KlineInterval.ONE_DAY,
            Instant.parse("2026-02-01T00:00:00Z"), Instant.parse("2026-02-03T00:00:00Z"), null);

        assertThat(klines).extracting(KlineDto::bucket).containsExactly(
            Instant.parse("2026-02-01T05:00:00Z"), Instant.parse("2026-02-02T05:00:00Z"));
        KlineDto feb2 = klines.get(1);
        assertThat(feb2.open()).isEqualByComparingTo("310");
        assertThat(feb2.high()).isEqualByComparingTo("310");
        assertThat(feb2.low()).isEqualByComparingTo("305");
        assertThat(feb2.close()).isEqualByComparingTo("305");
        assertThat(feb2.volume()).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("klines (1d): 外匯依紐約 17:00 換日")
    void oneDay_fx_bucketsByNewYorkFivePm() {
        Long eurUsd = assetId("EUR/USD");
        repo.insertAll(List.of(
            // 紐約 3/2 16:30 EST → 屬 3/1 17:00 起的交易日
            new MarketPrice(eurUsd, Instant.parse("2026-03-02T21:30:00Z"), new BigDecimal("1.08"), BigDecimal.ONE),
            // 紐約 3/2 17:30 → 新交易日
            new MarketPrice(eurUsd, Instant.parse("2026-03-02T22:30:00Z"), new BigDecimal("1.09"), BigDecimal.ONE)));

        List<KlineDto> klines = service.findKlines("EUR/USD", KlineInterval.ONE_DAY,
            Instant.parse("2026-03-01T00:00:00Z"), Instant.parse("2026-03-04T00:00:00Z"), null);

        assertThat(klines).extracting(KlineDto::bucket).containsExactly(
            Instant.parse("2026-03-01T22:00:00Z"), Instant.parse("2026-03-02T22:00:00Z"));
    }

    private static Long assetId(String symbol) {
        return jdbc.sql("select id from assets where symbol = :s").param("s", symbol).query(Long.class).single();
    }
}
