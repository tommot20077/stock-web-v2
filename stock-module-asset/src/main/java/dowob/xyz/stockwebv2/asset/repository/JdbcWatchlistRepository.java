package dowob.xyz.stockwebv2.asset.repository;

import dowob.xyz.stockwebv2.asset.domain.Asset;
import dowob.xyz.stockwebv2.asset.domain.Watchlist;
import dowob.xyz.stockwebv2.common.model.AssetType;
import dowob.xyz.stockwebv2.common.model.CurrencyCode;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link WatchlistRepository} 的 JdbcClient 實作。
 *
 * @author Yuan
 * @version 1.0
 */
@Repository
public class JdbcWatchlistRepository implements WatchlistRepository {

    private static final String WATCHLIST_COLUMNS = "w.id, w.uuid, w.user_id, w.name, w.created_at";

    private final JdbcClient jdbcClient;

    public JdbcWatchlistRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code WHERE NOT EXISTS} 讓「已有其他清單」時不建立；兩個並發的首次呼叫都通過 NOT EXISTS 時，
     * 第二個 INSERT 會等第一個交易結束，再由 {@code ON CONFLICT (user_id, name) DO NOTHING} 吸收。</p>
     */
    @Override
    public void ensureDefault(Long userId) {
        jdbcClient.sql("""
                insert into watchlists (user_id, name)
                select :userId, :name
                where not exists (select 1 from watchlists where user_id = :userId)
                on conflict (user_id, name) do nothing
                """)
            .param("userId", userId)
            .param("name", Watchlist.DEFAULT_NAME)
            .update();
    }

    @Override
    public List<Watchlist> listByUser(Long userId) {
        return jdbcClient.sql("select " + WATCHLIST_COLUMNS + """
                 from watchlists w
                where w.user_id = :userId
                order by w.created_at, w.id
                """)
            .param("userId", userId)
            .query(this::mapWatchlist)
            .list();
    }

    @Override
    public Optional<Watchlist> findByUuid(Long userId, UUID uuid) {
        return jdbcClient.sql("select " + WATCHLIST_COLUMNS + """
                 from watchlists w
                where w.uuid = :uuid and w.user_id = :userId
                """)
            .param("uuid", uuid)
            .param("userId", userId)
            .query(this::mapWatchlist)
            .optional();
    }

    /**
     * {@inheritDoc}
     *
     * <p>asset 模組擁有 {@code assets} 表，直接 JOIN 取 symbol / name / 型別，不經 facade。
     * inactive 資產不回傳（已下架的資產沒有可顯示的報價）。</p>
     */
    @Override
    public Map<Long, List<Asset>> itemsByWatchlist(List<Long> watchlistIds) {
        if (watchlistIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<Asset>> result = new LinkedHashMap<>();
        jdbcClient.sql("""
                select wi.watchlist_id, a.*
                from watchlist_items wi
                join assets a on a.id = wi.asset_id
                where wi.watchlist_id in (:watchlistIds)
                  and a.active = true
                order by wi.watchlist_id, wi.position, a.id
                """)
            .param("watchlistIds", watchlistIds)
            .query((ResultSet rs) -> {
                result.computeIfAbsent(rs.getLong("watchlist_id"), id -> new ArrayList<>()).add(mapAsset(rs));
            });
        return result;
    }

    private Watchlist mapWatchlist(ResultSet rs, int rowNum) throws SQLException {
        return new Watchlist(
            rs.getLong("id"),
            UUID.fromString(rs.getString("uuid")),
            rs.getLong("user_id"),
            rs.getString("name"),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private Asset mapAsset(ResultSet rs) throws SQLException {
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
            rs.getBoolean("active")
        );
    }
}
