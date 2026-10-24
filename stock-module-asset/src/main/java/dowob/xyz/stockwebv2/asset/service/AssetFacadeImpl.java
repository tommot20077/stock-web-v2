package dowob.xyz.stockwebv2.asset.service;

import dowob.xyz.stockwebv2.common.model.AssetType;
import dowob.xyz.stockwebv2.common.model.CurrencyCode;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetFacade;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetProfile;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetSummary;

import org.apache.commons.lang3.ObjectUtils;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * {@inheritDoc}
     *
     * <p>以單一 {@code IN} 子句查詢取得，ID 僅以具名參數綁定。
     * 結果以 {@link LinkedHashMap} 保留查詢回傳順序；不加 {@code active} 篩選。
     */
    @Override
    public Map<Long, AssetProfile> findProfiles(Collection<Long> assetIds) {
        if (ObjectUtils.isEmpty(assetIds)) {
            return Map.of();
        }
        Map<Long, AssetProfile> profiles = new LinkedHashMap<>();
        jdbcClient.sql("""
                        select id, symbol, asset_type, market, currency, sector
                        from assets
                        where id in (:ids)
                        order by id asc
                        """)
                .param("ids", assetIds)
                .query(this::mapProfile)
                .list()
                .forEach(profile -> profiles.put(profile.id(), profile));
        return profiles;
    }

    private AssetProfile mapProfile(ResultSet rs, int rowNum) throws SQLException {
        return new AssetProfile(
                rs.getLong("id"),
                rs.getString("symbol"),
                AssetType.valueOf(rs.getString("asset_type")),
                rs.getString("market"),
                CurrencyCode.valueOf(rs.getString("currency")),
                rs.getString("sector")
        );
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
