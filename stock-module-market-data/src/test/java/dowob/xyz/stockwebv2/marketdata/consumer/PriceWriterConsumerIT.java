package dowob.xyz.stockwebv2.marketdata.consumer;

import dowob.xyz.stockwebv2.common.event.PriceTickEvent;
import dowob.xyz.stockwebv2.marketdata.persistence.MarketPrice;
import dowob.xyz.stockwebv2.marketdata.persistence.MarketPriceRepository;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link PriceWriterConsumer} 整合測試。
 *
 * <p>使用 TimescaleDB testcontainer 驗證：
 * <ul>
 *   <li>批次寫入多筆事件 → DB 有對應筆數</li>
 *   <li>重複推送相同事件 → DB 仍只有一筆（idempotent）</li>
 *   <li>空批次 → 無例外、DB 無資料</li>
 * </ul>
 *
 * <p>直接呼叫 {@code consumer.consume(List)} 方法，不啟動 Spring 容器，
 * 讓測試快速且聚焦在邏輯正確性。Kafka @KafkaListener 接線驗證留給 T28 E2E。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Testcontainers
class PriceWriterConsumerIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.17.2-pg16")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("price_writer_test")
            .withUsername("test")
            .withPassword("test");

    static MarketPriceRepository repository;
    static PriceWriterConsumer consumer;

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
        repository = new MarketPriceRepository(jdbcClient, npjt);
        consumer = new PriceWriterConsumer(repository);
    }

    @BeforeEach
    void cleanTable() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUsername(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        JdbcClient.create(ds).sql("DELETE FROM market_prices").update();
    }

    // ── consume (batch insert) ────────────────────────────────────────────────

    @Test
    @DisplayName("consume：3 筆不同 (assetId, time) 事件 → DB 有 3 筆")
    void consume_persistsAllEventsFromBatch() {
        List<PriceTickEvent> events = List.of(
                event("e1", "2026-01-01T10:00:00Z", 1L, "AAPL", "100.00", "500.00"),
                event("e2", "2026-01-01T10:00:01Z", 1L, "AAPL", "101.00", "510.00"),
                event("e3", "2026-01-01T10:00:00Z", 2L, "BTC",  "50000.00", null)
        );

        consumer.consume(events);

        Instant from = Instant.parse("2026-01-01T09:00:00Z");
        Instant to   = Instant.parse("2026-01-01T11:00:00Z");
        assertThat(repository.findRange(1L, from, to)).hasSize(2);
        assertThat(repository.findRange(2L, from, to)).hasSize(1);
    }

    @Test
    @DisplayName("consume：相同事件重複推送 3 次 → DB 仍只有 1 筆（idempotent）")
    void consume_isIdempotent_duplicateBatchDoesNotDuplicate() {
        PriceTickEvent event = event("e1", "2026-01-01T10:00:00Z", 1L, "AAPL", "100.00", "500.00");
        Instant t = Instant.parse("2026-01-01T10:00:00Z");

        consumer.consume(List.of(event));
        consumer.consume(List.of(event));
        consumer.consume(List.of(event));

        List<MarketPrice> result = repository.findRange(1L, t.minusSeconds(1), t.plusSeconds(1));
        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("consume：空批次 → 無例外、DB 無資料")
    void consume_emptyBatch_isNoOp() {
        assertThatCode(() -> consumer.consume(List.of())).doesNotThrowAnyException();

        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to   = Instant.parse("2026-01-02T00:00:00Z");
        assertThat(repository.findRange(1L, from, to)).isEmpty();
    }

    @Test
    @DisplayName("consume：volume 為 null 的事件 → 可正確持久化")
    void consume_nullVolume_isPersistedCorrectly() {
        PriceTickEvent event = event("e1", "2026-01-01T10:00:00Z", 2L, "BTC", "50000.00", null);

        consumer.consume(List.of(event));

        Instant from = Instant.parse("2026-01-01T09:00:00Z");
        Instant to   = Instant.parse("2026-01-01T11:00:00Z");
        List<MarketPrice> result = repository.findRange(2L, from, to);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).volume()).isNull();
    }

    @Test
    @DisplayName("consume：多 asset 混合批次 → 各 asset 筆數正確")
    void consume_multiAssetBatch_persistsCorrectly() {
        List<PriceTickEvent> events = List.of(
                event("a1", "2026-06-01T08:00:00Z", 1L, "AAPL",  "190.00", "100.00"),
                event("a2", "2026-06-01T08:01:00Z", 1L, "AAPL",  "191.00", "110.00"),
                event("a3", "2026-06-01T08:02:00Z", 1L, "AAPL",  "192.00", "120.00"),
                event("b1", "2026-06-01T08:00:00Z", 2L, "BTC",  "67000.00", "5.00"),
                event("b2", "2026-06-01T08:01:00Z", 2L, "BTC",  "67100.00", "6.00")
        );

        consumer.consume(events);

        Instant from = Instant.parse("2026-06-01T07:00:00Z");
        Instant to   = Instant.parse("2026-06-01T09:00:00Z");
        assertThat(repository.findRange(1L, from, to)).hasSize(3);
        assertThat(repository.findRange(2L, from, to)).hasSize(2);
    }

    // ── helper ───────────────────────────────────────────────────────────────

    private static PriceTickEvent event(String eventId, String occurredAt,
                                        Long assetId, String symbol,
                                        String price, String volume) {
        return new PriceTickEvent(
                eventId,
                Instant.parse(occurredAt),
                assetId,
                symbol,
                new BigDecimal(price),
                volume != null ? new BigDecimal(volume) : null,
                "mock"
        );
    }
}
