package dowob.xyz.stockwebv2.marketdata.persistence;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 市場價格 hypertable ({@code market_prices}) 的 Repository。
 *
 * <p>採用 Spring Framework 6.1+ {@link JdbcClient} fluent API 進行查詢，
 * 批次寫入則使用 {@link NamedParameterJdbcTemplate#batchUpdate} 以達到
 * O(1) round-trip 語義（JDBC batch 模式）。
 *
 * <p>INSERT 語法使用 {@code ON CONFLICT (asset_id, time) DO NOTHING}
 * 保證 idempotency，重複資料靜默跳過。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Repository
public class MarketPriceRepository {

    private static final String INSERT_SQL = """
            INSERT INTO market_prices (asset_id, time, price, volume)
            VALUES (:assetId, :time, :price, :volume)
            ON CONFLICT (asset_id, time) DO NOTHING
            """;

    private static final String FIND_LATEST_SQL = """
            SELECT asset_id, time, price, volume
            FROM market_prices
            WHERE asset_id = :assetId
            ORDER BY time DESC
            LIMIT 1
            """;

    private static final String FIND_RANGE_SQL = """
            SELECT asset_id, time, price, volume
            FROM market_prices
            WHERE asset_id = :assetId
              AND time >= :from
              AND time < :to
            ORDER BY time DESC
            """;

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    /**
     * 建構子注入 {@link JdbcClient} 與 {@link NamedParameterJdbcTemplate}。
     *
     * @param jdbcClient                  Spring 6.1+ fluent JDBC client，用於查詢
     * @param namedParameterJdbcTemplate  named-parameter JDBC template，用於批次寫入
     */
    public MarketPriceRepository(JdbcClient jdbcClient,
                                 NamedParameterJdbcTemplate namedParameterJdbcTemplate) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
    }

    /**
     * 批次插入市場價格 tick，重複資料（相同 {@code asset_id} + {@code time}）靜默跳過。
     *
     * <p>空 list 直接回傳 0，不發出 DB round-trip。
     *
     * @param prices 要插入的 tick list，允許為空
     * @return 實際插入的筆數（不含被 ON CONFLICT 跳過的筆數）
     */
    public int insertAll(List<MarketPrice> prices) {
        if (prices.isEmpty()) {
            return 0;
        }

        SqlParameterSource[] batchParams = prices.stream()
                .map(p -> new MapSqlParameterSource()
                        .addValue("assetId", p.assetId())
                        .addValue("time",    Timestamp.from(p.time()))
                        .addValue("price",   p.price())
                        .addValue("volume",  p.volume()))
                .toArray(SqlParameterSource[]::new);

        int[] results = namedParameterJdbcTemplate.batchUpdate(INSERT_SQL, batchParams);

        int totalInserted = 0;
        for (int count : results) {
            if (count > 0) {
                totalInserted += count;
            }
        }
        return totalInserted;
    }

    /**
     * 查詢指定 asset 最近一筆 tick。
     *
     * @param assetId 資產 ID
     * @return 最新 tick，若無資料則 {@link Optional#empty()}
     */
    public Optional<MarketPrice> findLatest(Long assetId) {
        return jdbcClient.sql(FIND_LATEST_SQL)
                .param("assetId", assetId)
                .query(this::map)
                .optional();
    }

    /**
     * 查詢指定 asset 在 {@code [from, to)} 半開區間內的 tick，按 {@code time DESC} 排序。
     *
     * @param assetId 資產 ID
     * @param from    起始時間（含）
     * @param to      結束時間（不含）
     * @return 符合條件的 tick list，無資料時回傳空 list（非 null）
     */
    public List<MarketPrice> findRange(Long assetId, Instant from, Instant to) {
        return jdbcClient.sql(FIND_RANGE_SQL)
                .param("assetId", assetId)
                .param("from",    Timestamp.from(from))
                .param("to",      Timestamp.from(to))
                .query(this::map)
                .list();
    }

    /**
     * 將 {@link ResultSet} 一列對映至 {@link MarketPrice} record。
     *
     * @param rs     result set（游標已定位至目標列）
     * @param rowNum 列號（供 Spring callback 使用）
     * @return 對映後的 {@link MarketPrice}
     * @throws SQLException 若欄位讀取失敗
     */
    private MarketPrice map(ResultSet rs, int rowNum) throws SQLException {
        return new MarketPrice(
                rs.getLong("asset_id"),
                rs.getTimestamp("time").toInstant(),
                rs.getBigDecimal("price"),
                rs.getBigDecimal("volume")  // nullable — returns null naturally
        );
    }
}
