package dowob.xyz.stockwebv2.asset.service;

import dowob.xyz.stockwebv2.common.model.AssetType;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetFacade;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetSummary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link AssetFacade} 的 JDBC 實作，部署於 stock-module-asset (L2)。
 *
 * <p>直接以 {@link JdbcClient} 查詢 {@code assets} 表，僅回傳
 * {@link AssetSummary} — 消費者不需知道 asset module 的內部 domain 結構。
 *
 * <p>此 bean 由 Spring 容器管理，消費模組（如 market-data）透過
 * {@link AssetFacade} 介面注入，符合架構文件 §60-66 的 Facade pattern 規範。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Service
public class AssetFacadeImpl implements AssetFacade {

    private final JdbcClient jdbcClient;

    public AssetFacadeImpl(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * {@inheritDoc}
     *
     * <p>查詢條件：{@code active = true AND tradeable = true}，按 {@code symbol} 升冪排序。
     */
    @Override
    public List<AssetSummary> findAllTradeable() {
        return jdbcClient.sql("""
                        select id, symbol, name, asset_type, market, tradeable, active
                        from assets
                        where active = true and tradeable = true
                        order by symbol asc
                        """)
                .query(this::map)
                .list();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Symbol 查詢為大小寫敏感（使用 {@code =} 而非 {@code ilike}）。
     *
     * @throws NullPointerException 若 symbol 為 null
     */
    @Override
    public Optional<AssetSummary> findBySymbol(String symbol) {
        Objects.requireNonNull(symbol, "symbol must not be null");
        return jdbcClient.sql("""
                        select id, symbol, name, asset_type, market, tradeable, active
                        from assets
                        where symbol = :symbol
                        """)
                .param("symbol", symbol)
                .query(this::map)
                .optional();
    }

    private AssetSummary map(ResultSet rs, int rowNum) throws SQLException {
        return new AssetSummary(
                rs.getLong("id"),
                rs.getString("symbol"),
                rs.getString("name"),
                AssetType.valueOf(rs.getString("asset_type")),
                rs.getString("market"),
                rs.getBoolean("tradeable"),
                rs.getBoolean("active")
        );
    }
}
