package dowob.xyz.stockwebv2.asset.repository;

import dowob.xyz.stockwebv2.asset.domain.Asset;
import dowob.xyz.stockwebv2.asset.domain.Watchlist;
import dowob.xyz.stockwebv2.common.model.AssetType;
import dowob.xyz.stockwebv2.common.model.CurrencyCode;

import org.springframework.dao.DuplicateKeyException;
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

    @Override
    public Optional<Watchlist> findByUuidForUpdate(Long userId, UUID uuid) {
        return jdbcClient.sql("select " + WATCHLIST_COLUMNS + """
                 from watchlists w
                where w.uuid = :uuid and w.user_id = :userId
                for update
                """)
            .param("uuid", uuid)
            .param("userId", userId)
            .query(this::mapWatchlist)
            .optional();
    }

    @Override
    public List<Long> lockAllForUser(Long userId) {
        return jdbcClient.sql("""
                select id from watchlists
                where user_id = :userId
                order by id
                for update
                """)
            .param("userId", userId)
            .query(Long.class)
            .list();
    }

    @Override
    public int countByUser(Long userId) {
        return jdbcClient.sql("select count(*) from watchlists where user_id = :userId")
            .param("userId", userId)
            .query(Integer.class)
            .single();
    }

    @Override
    public Optional<Watchlist> insertIfNameFree(Long userId, String name) {
        return jdbcClient.sql("""
                insert into watchlists (user_id, name)
                values (:userId, :name)
                on conflict (user_id, name) do nothing
                returning id, uuid, user_id, name, created_at
                """)
            .param("userId", userId)
            .param("name", name)
            .query(this::mapWatchlist)
            .optional();
    }

    /**
     * {@inheritDoc}
     *
     * <p>先以 {@code NOT EXISTS} 排除已 commit 的同名清單（不觸發例外）；兩個並發改名搶同一名稱、都通過
     * NOT EXISTS 時，後者的 UPDATE 會在唯一約束上等前者 commit 後失敗——這個殘餘競態以
     * {@link DuplicateKeyException} 轉成 false。此時交易已處於失敗狀態，但呼叫端隨即丟出業務例外並回滾，
     * 不會在同一交易內再執行任何 SQL。</p>
     */
    @Override
    public boolean rename(Long id, Long userId, String name) {
        try {
            return jdbcClient.sql("""
                    update watchlists
                    set name = :name, updated_at = now()
                    where id = :id
                      and user_id = :userId
                      and not exists (
                          select 1 from watchlists other
                          where other.user_id = :userId and other.name = :name and other.id <> :id
                      )
                    """)
                .param("name", name)
                .param("id", id)
                .param("userId", userId)
                .update() == 1;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    @Override
    public void delete(Long id, Long userId) {
        jdbcClient.sql("delete from watchlists where id = :id and user_id = :userId")
            .param("id", id)
            .param("userId", userId)
            .update();
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
