package dowob.xyz.stockwebv2.marketdata.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MarketPriceRepository} 整合測試。
 *
 * <p>使用 TimescaleDB testcontainer 驗證：
 * <ul>
 *   <li>批次插入（idempotency / ON CONFLICT DO NOTHING）</li>
 *   <li>查最近一筆 tick</li>
 *   <li>時間範圍查詢（[from, to)）</li>
 *   <li>null volume 的持久化與取回</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0.0
 */
@Testcontainers
class MarketPriceRepositoryIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.17.2-pg16")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("market_data_test")
            .withUsername("test")
            .withPassword("test");

    static MarketPriceRepository repo;

    /** 固定 assetId：對應 V2 seed 資料中第一筆 assets 記錄（id=1）。 */
    static final Long ASSET_A = 1L;
    static final Long ASSET_B = 2L;

    /** 基準時間，截斷至毫秒以避免 TIMESTAMPTZ 精度問題。 */
    static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");
    static final Instant T1 = T0.plus(1, ChronoUnit.HOURS);
    static final Instant T2 = T0.plus(2, ChronoUnit.HOURS);
    static final Instant T3 = T0.plus(3, ChronoUnit.HOURS);
    static final Instant T4 = T0.plus(4, ChronoUnit.HOURS);

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

        JdbcClient jdbcClient = JdbcClient.create(ds);
        NamedParameterJdbcTemplate npjt = new NamedParameterJdbcTemplate(ds);
        repo = new MarketPriceRepository(jdbcClient, npjt);
    }

    @BeforeEach
    void cleanTable() {
        // 每個測試前清空 market_prices，保持隔離
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUsername(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        JdbcClient.create(ds).sql("DELETE FROM market_prices").update();
    }

    // ── insertAll ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("insertAll：插入 5 筆不同 (assetId, time) → findRange 可取回 5 筆")
    void insertAll_persistsAllRows() {
        List<MarketPrice> prices = List.of(
                price(ASSET_A, T0, "100.00", "10.00"),
                price(ASSET_A, T1, "101.00", "11.00"),
                price(ASSET_A, T2, "102.00", "12.00"),
                price(ASSET_A, T3, "103.00", "13.00"),
                price(ASSET_A, T4, "104.00", "14.00")
        );

        repo.insertAll(prices);

        List<MarketPrice> result = repo.findRange(ASSET_A, T0, T4.plus(1, ChronoUnit.HOURS));
        assertThat(result).hasSize(5);
    }

    @Test
    @DisplayName("insertAll：相同 3 筆重複插入兩次 → DB 仍只有 3 筆（ON CONFLICT DO NOTHING）")
    void insertAll_isIdempotent() {
        List<MarketPrice> prices = List.of(
                price(ASSET_A, T0, "100.00", "10.00"),
                price(ASSET_A, T1, "101.00", "11.00"),
                price(ASSET_A, T2, "102.00", "12.00")
        );

        repo.insertAll(prices);
        repo.insertAll(prices); // 重複插入

        List<MarketPrice> result = repo.findRange(ASSET_A, T0, T3);
        assertThat(result).hasSize(3);
    }

    @Test
    @DisplayName("insertAll：3 筆後再插入 5 筆（2 重複 + 3 新）→ DB 共 6 筆")
    void insertAll_mixedNewAndDuplicate() {
        List<MarketPrice> first = List.of(
                price(ASSET_A, T0, "100.00", "10.00"),
                price(ASSET_A, T1, "101.00", "11.00"),
                price(ASSET_A, T2, "102.00", "12.00")
        );
        List<MarketPrice> second = List.of(
                price(ASSET_A, T1, "101.00", "11.00"), // 重複
                price(ASSET_A, T2, "102.00", "12.00"), // 重複
                price(ASSET_A, T3, "103.00", "13.00"),
                price(ASSET_A, T4, "104.00", "14.00"),
                price(ASSET_A, T0.plus(5, ChronoUnit.HOURS), "105.00", "15.00")
        );

        repo.insertAll(first);
        repo.insertAll(second);

        List<MarketPrice> result = repo.findRange(ASSET_A, T0, T0.plus(6, ChronoUnit.HOURS));
        assertThat(result).hasSize(6);
    }

    @Test
    @DisplayName("insertAll：空 list → 無例外，DB 無資料")
    void insertAll_emptyList_noOp() {
        int inserted = repo.insertAll(List.of());

        assertThat(inserted).isEqualTo(0);
        List<MarketPrice> result = repo.findRange(ASSET_A, T0, T4.plus(1, ChronoUnit.HOURS));
        assertThat(result).isEmpty();
    }

    // ── findLatest ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("findLatest：插入 3 筆不同時間 → 回傳時間最晚的那筆")
    void findLatest_returnsMostRecent() {
        repo.insertAll(List.of(
                price(ASSET_A, T0, "100.00", "10.00"),
                price(ASSET_A, T1, "101.00", "11.00"),
                price(ASSET_A, T2, "102.00", "12.00")
        ));

        Optional<MarketPrice> latest = repo.findLatest(ASSET_A);

        assertThat(latest).isPresent();
        assertThat(latest.get().time()).isEqualTo(T2);
        assertThat(latest.get().price()).isEqualByComparingTo(new BigDecimal("102.00"));
    }

    @Test
    @DisplayName("findLatest：無資料的 assetId → Optional.empty()")
    void findLatest_unknownAsset_returnsEmpty() {
        Optional<MarketPrice> result = repo.findLatest(999L);

        assertThat(result).isEmpty();
    }

    // ── findRange ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findRange：查 [T1, T3) → 回傳 T1, T2 兩筆，按 time DESC 排序")
    void findRange_returnsMatchingRowsDesc() {
        repo.insertAll(List.of(
                price(ASSET_A, T0, "100.00", "10.00"),
                price(ASSET_A, T1, "101.00", "11.00"),
                price(ASSET_A, T2, "102.00", "12.00"),
                price(ASSET_A, T3, "103.00", "13.00")
        ));

        List<MarketPrice> result = repo.findRange(ASSET_A, T1, T3);

        // T3 excluded (half-open interval [T1, T3))
        assertThat(result).hasSize(2);
        assertThat(result.get(0).time()).isEqualTo(T2); // DESC: T2 先
        assertThat(result.get(1).time()).isEqualTo(T1);
    }

    @Test
    @DisplayName("findRange：範圍內無資料 → 回傳空 list（非 null）")
    void findRange_emptyRange_returnsEmptyList() {
        repo.insertAll(List.of(
                price(ASSET_A, T0, "100.00", "10.00")
        ));

        List<MarketPrice> result = repo.findRange(ASSET_A, T3, T4);

        assertThat(result).isNotNull().isEmpty();
    }

    // ── null volume ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("volume 為 null：可正確持久化並取回 null")
    void nullVolume_isPersistedAndRetrieved() {
        MarketPrice withNullVolume = new MarketPrice(ASSET_A, T0, new BigDecimal("99.99"), null);
        repo.insertAll(List.of(withNullVolume));

        Optional<MarketPrice> latest = repo.findLatest(ASSET_A);

        assertThat(latest).isPresent();
        assertThat(latest.get().volume()).isNull();
    }

    // ── helper ───────────────────────────────────────────────────────────────

    private static MarketPrice price(Long assetId, Instant time, String price, String volume) {
        return new MarketPrice(assetId, time, new BigDecimal(price), new BigDecimal(volume));
    }
}
