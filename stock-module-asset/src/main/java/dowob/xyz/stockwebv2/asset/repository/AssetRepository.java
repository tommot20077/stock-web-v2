package dowob.xyz.stockwebv2.asset.repository;

import dowob.xyz.stockwebv2.asset.domain.Asset;
import dowob.xyz.stockwebv2.common.model.AssetType;
import dowob.xyz.stockwebv2.common.model.CurrencyCode;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

@Repository
public class AssetRepository {
    private final JdbcClient jdbcClient;

    public AssetRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<Asset> search(String query, int page, int size) {
        String like = "%" + StringUtils.trimToEmpty(query) + "%";
        long offset = (long) page * size;
        return jdbcClient.sql("""
                select a.*, p.price latest_price, p.change, p.change_percent, p.volume_text, p.high, p.low
                from assets a
                left join asset_latest_prices p on p.asset_id = a.id
                where a.active = true
                  and (:query = '%%' or a.symbol ilike :query or a.name ilike :query)
                order by a.symbol asc
                limit :limit offset :offset
                """)
            .param("query", like)
            .param("limit", size)
            .param("offset", offset)
            .query(this::map)
            .list();
    }

    public long count(String query) {
        String like = "%" + StringUtils.trimToEmpty(query) + "%";
        Long count = jdbcClient.sql("""
                select count(*)
                from assets a
                where a.active = true
                  and (:query = '%%' or a.symbol ilike :query or a.name ilike :query)
                """)
            .param("query", like)
            .query(Long.class)
            .single();
        return count;
    }

    private Asset map(ResultSet rs, int rowNum) throws SQLException {
        return new Asset(
            rs.getLong("id"),
            UUID.fromString(rs.getString("uuid")),
            rs.getString("symbol"),
            rs.getString("name"),
            AssetType.valueOf(rs.getString("asset_type")),
            rs.getString("market"),
            CurrencyCode.valueOf(rs.getString("currency")),
            rs.getString("sector"),
            rs.getBoolean("tradeable"),
            rs.getBoolean("active"),
            rs.getBigDecimal("latest_price"),
            rs.getBigDecimal("change"),
            rs.getBigDecimal("change_percent"),
            rs.getString("volume_text"),
            rs.getBigDecimal("high"),
            rs.getBigDecimal("low")
        );
    }
}
