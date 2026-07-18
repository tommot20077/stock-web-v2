package dowob.xyz.stockwebv2.trading.repository;

import dowob.xyz.stockwebv2.common.api.PageResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.trading.domain.Holding;
import dowob.xyz.stockwebv2.trading.domain.HoldingPosition;
import dowob.xyz.stockwebv2.trading.domain.TradeTransaction;
import dowob.xyz.stockwebv2.trading.domain.TradeType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcTradingRepository implements TradingRepository {
    private static final String TRANSACTION_COLUMNS = """
        t.id, t.uuid, t.user_id, t.asset_id, a.symbol, t.type, t.quantity, t.price,
        t.fee, t.note, t.executed_at, t.created_at
        """;

    private final JdbcClient jdbcClient;

    public JdbcTradingRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public TradeTransaction insertTransaction(TradeTransaction transaction) {
        return jdbcClient.sql("""
                insert into transactions (
                    uuid, user_id, asset_id, type, quantity, price, fee, note, executed_at
                )
                values (
                    coalesce(:uuid, uuid_generate_v4()), :userId, :assetId, :type,
                    :quantity, :price, :fee, :note, :executedAt
                )
                returning id, uuid, user_id, asset_id, type, quantity, price, fee, note, executed_at, created_at
                """)
            .param("uuid", transaction.uuid())
            .param("userId", transaction.userId())
            .param("assetId", transaction.assetId())
            .param("type", transaction.type().name())
            .param("quantity", transaction.quantity())
            .param("price", transaction.price())
            .param("fee", transaction.fee())
            .param("note", transaction.note())
            .param("executedAt", transaction.executedAt())
            .query((rs, rowNum) -> new TradeTransaction(
                rs.getLong("id"),
                rs.getObject("uuid", java.util.UUID.class),
                rs.getLong("user_id"),
                rs.getLong("asset_id"),
                transaction.symbol(),
                TradeType.valueOf(rs.getString("type")),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("price"),
                rs.getBigDecimal("fee"),
                rs.getString("note"),
                rs.getObject("executed_at", java.time.OffsetDateTime.class),
                rs.getObject("created_at", java.time.OffsetDateTime.class)
            ))
            .single();
    }

    @Override
    public Optional<Holding> findHoldingForUpdate(Long userId, Long assetId) {
        return jdbcClient.sql("""
                select id, user_id, asset_id, total_quantity, avg_cost, realized_pnl, version, last_updated
                from holdings
                where user_id = :userId and asset_id = :assetId
                for update
                """)
            .param("userId", userId)
            .param("assetId", assetId)
            .query(this::mapHolding)
            .optional();
    }

    @Override
    public Optional<Holding> insertHoldingIfAbsent(Holding holding) {
        // ON CONFLICT DO NOTHING 讓併發首次建倉不會拋唯一鍵例外（不會污染交易）；
        // 回傳空 Optional 表示已被他交易插入，呼叫端重讀後改走 update 併倉。
        return jdbcClient.sql("""
                insert into holdings (user_id, asset_id, total_quantity, avg_cost, realized_pnl, version, last_updated)
                values (:userId, :assetId, :totalQuantity, :avgCost, :realizedPnl, 0, :lastUpdated)
                on conflict (user_id, asset_id) do nothing
                returning id, user_id, asset_id, total_quantity, avg_cost, realized_pnl, version, last_updated
                """)
            .param("userId", holding.userId())
            .param("assetId", holding.assetId())
            .param("totalQuantity", holding.totalQuantity())
            .param("avgCost", holding.avgCost())
            .param("realizedPnl", holding.realizedPnl())
            .param("lastUpdated", holding.lastUpdated())
            .query(this::mapHolding)
            .optional();
    }

    @Override
    public Holding updateHolding(Holding holding) {
        Optional<Holding> updated = jdbcClient.sql("""
                update holdings
                set total_quantity = :totalQuantity,
                    avg_cost = :avgCost,
                    realized_pnl = :realizedPnl,
                    version = version + 1,
                    last_updated = :lastUpdated
                where id = :id and version = :version
                returning id, user_id, asset_id, total_quantity, avg_cost, realized_pnl, version, last_updated
                """)
            .param("id", holding.id())
            .param("version", holding.version())
            .param("totalQuantity", holding.totalQuantity())
            .param("avgCost", holding.avgCost())
            .param("realizedPnl", holding.realizedPnl())
            .param("lastUpdated", holding.lastUpdated())
            .query(this::mapHolding)
            .optional();
        return updated.orElseThrow(() -> new BusinessException(ErrorCode.TRADE_CONFLICT, ErrorCode.TRADE_CONFLICT.defaultMessage()));
    }

    @Override
    public PageResponse<TradeTransaction> listTransactions(Long userId, Long assetId, int page, int size) {
        boolean filterAsset = assetId != null;
        long offset = (long) page * size;
        String where = filterAsset ? "where t.user_id = :userId and t.asset_id = :assetId " : "where t.user_id = :userId ";
        JdbcClient.StatementSpec listSpec = jdbcClient.sql("select " + TRANSACTION_COLUMNS + """
                from transactions t
                join assets a on a.id = t.asset_id
                """ + where + """
                order by t.created_at desc, t.id desc
                limit :limit offset :offset
                """)
            .param("userId", userId)
            .param("limit", size)
            .param("offset", offset);
        JdbcClient.StatementSpec countSpec = jdbcClient.sql("select count(*) from transactions t " + where)
            .param("userId", userId);
        if (filterAsset) {
            listSpec = listSpec.param("assetId", assetId);
            countSpec = countSpec.param("assetId", assetId);
        }
        long total = countSpec.query(Long.class).single();
        return PageResponse.of(listSpec.query(this::mapTransaction).list(), page, size, total);
    }

    @Override
    public List<HoldingPosition> listHoldings(Long userId) {
        return jdbcClient.sql("""
                select h.id, h.user_id, h.asset_id, a.uuid as asset_uuid, a.symbol, a.name,
                       h.total_quantity, h.avg_cost, h.realized_pnl, h.version, h.last_updated
                from holdings h
                join assets a on a.id = h.asset_id
                where h.user_id = :userId and h.total_quantity > 0
                order by a.symbol asc
                """)
            .param("userId", userId)
            .query(this::mapPosition)
            .list();
    }

    @Override
    public BigDecimal sumRealizedPnl(Long userId) {
        return jdbcClient.sql("""
                select coalesce(sum(realized_pnl), 0)
                from holdings
                where user_id = :userId
                """)
            .param("userId", userId)
            .query(BigDecimal.class)
            .single();
    }

    @Override
    public Optional<LatestAssetPrice> findLatestPrice(Long assetId) {
        return jdbcClient.sql("""
                select price, price_time
                from asset_latest_prices
                where asset_id = :assetId
                """)
            .param("assetId", assetId)
            .query((rs, rowNum) -> new LatestAssetPrice(
                rs.getBigDecimal("price"),
                rs.getObject("price_time", java.time.OffsetDateTime.class)
            ))
            .optional();
    }

    private TradeTransaction mapTransaction(ResultSet rs, int rowNum) throws SQLException {
        return new TradeTransaction(
            rs.getLong("id"),
            rs.getObject("uuid", java.util.UUID.class),
            rs.getLong("user_id"),
            rs.getLong("asset_id"),
            rs.getString("symbol"),
            TradeType.valueOf(rs.getString("type")),
            rs.getBigDecimal("quantity"),
            rs.getBigDecimal("price"),
            rs.getBigDecimal("fee"),
            rs.getString("note"),
            rs.getObject("executed_at", java.time.OffsetDateTime.class),
            rs.getObject("created_at", java.time.OffsetDateTime.class)
        );
    }

    private Holding mapHolding(ResultSet rs, int rowNum) throws SQLException {
        return new Holding(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getLong("asset_id"),
            rs.getBigDecimal("total_quantity"),
            rs.getBigDecimal("avg_cost"),
            rs.getBigDecimal("realized_pnl"),
            rs.getInt("version"),
            rs.getObject("last_updated", java.time.OffsetDateTime.class)
        );
    }

    private HoldingPosition mapPosition(ResultSet rs, int rowNum) throws SQLException {
        return new HoldingPosition(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getLong("asset_id"),
            rs.getObject("asset_uuid", java.util.UUID.class),
            rs.getString("symbol"),
            rs.getString("name"),
            rs.getBigDecimal("total_quantity"),
            rs.getBigDecimal("avg_cost"),
            rs.getBigDecimal("realized_pnl"),
            rs.getInt("version"),
            rs.getObject("last_updated", java.time.OffsetDateTime.class)
        );
    }
}
