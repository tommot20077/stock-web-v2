package dowob.xyz.stockwebv2.asset.service;

import dowob.xyz.stockwebv2.common.model.AssetType;
import dowob.xyz.stockwebv2.common.model.CurrencyCode;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetFacade;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetProfile;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetSummary;

import org.apache.commons.lang3.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>{@code asset_type} / {@code currency} 無法轉成 enum 的壞列一律略過並記 WARN（只記 id），不拋例外；
 * {@code currency} 另由 V19 的 {@code ck_assets_currency} 在 DB 層擋下。
 *
 * @author Yuan
 * @version 1.1.0
 */
@Service
public class AssetFacadeImpl implements AssetFacade {

    private static final Logger log = LoggerFactory.getLogger(AssetFacadeImpl.class);

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
                .query(this::mapRawSummary)
                .list()
                .stream()
                .flatMap(raw -> toSummary(raw).stream())
                .toList();
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
                .query(this::mapRawSummary)
                .optional()
                .flatMap(AssetFacadeImpl::toSummary);
    }

    /**
     * {@inheritDoc}
     *
     * <p>以單一 {@code IN} 子句查詢取得，ID 僅以具名參數綁定。
     * 結果以 {@link LinkedHashMap} 保留查詢回傳順序；不加 {@code active} 篩選。
     * 無法轉換 enum 的壞列不在結果內（呼叫端視同查無資產屬性）。
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
                .query(this::mapRawProfile)
                .list()
                .forEach(raw -> toProfile(raw).ifPresent(profile -> profiles.put(profile.id(), profile)));
        return profiles;
    }

    /**
     * 把尚未轉 enum 的原始列轉成 {@link AssetProfile}。
     *
     * <p>{@code asset_type} 或 {@code currency} 不在 enum 內（或為 null）的列回 empty 並記 WARN，
     * WARN <strong>只記 id</strong>，不記 symbol / 原始值（避免 log injection）。一列壞資料不得讓
     * summary / holdings / trades 整個 500（04.1 REVIEW WR-02），與 {@link FxRateFacadeImpl} 略過壞列同一做法。
     *
     * @param raw 原始列
     * @return 資產屬性；無法轉換時為 empty
     */
    static Optional<AssetProfile> toProfile(RawProfile raw) {
        Optional<AssetType> assetType = parseEnum(AssetType.class, raw.assetType());
        Optional<CurrencyCode> currency = parseEnum(CurrencyCode.class, raw.currency());
        if (assetType.isEmpty() || currency.isEmpty()) {
            log.warn("assets 略過無法解析 asset_type 或 currency 的列：id={}", raw.id());
            return Optional.empty();
        }
        return Optional.of(new AssetProfile(
                raw.id(),
                raw.symbol(),
                assetType.get(),
                raw.market(),
                currency.get(),
                raw.sector()));
    }

    private RawProfile mapRawProfile(ResultSet rs, int rowNum) throws SQLException {
        return new RawProfile(
                rs.getLong("id"),
                rs.getString("symbol"),
                rs.getString("asset_type"),
                rs.getString("market"),
                rs.getString("currency"),
                rs.getString("sector"));
    }

    /**
     * {@code assets} 尚未轉 enum 的原始列（findProfiles 用）。
     *
     * @param id        資產 id
     * @param symbol    標的代號
     * @param assetType asset_type 原始字串
     * @param market    交易市場代號
     * @param currency  currency 原始字串
     * @param sector    產業分類；可為 null
     */
    record RawProfile(long id, String symbol, String assetType, String market, String currency, String sector) {
    }

    /**
     * 把尚未轉 enum 的原始列轉成 {@link AssetSummary}。
     *
     * <p>{@code asset_type} 不在 enum 內（或為 null）的列回 empty 並記 WARN（只記 id）：
     * 一列壞資料不得讓 market-data ingestor 的 {@code findAllTradeable} 整批失敗；{@code findBySymbol} 遇壞列回 empty。
     *
     * @param raw 原始列
     * @return 資產摘要；無法轉換時為 empty
     */
    static Optional<AssetSummary> toSummary(RawSummary raw) {
        Optional<AssetType> assetType = parseEnum(AssetType.class, raw.assetType());
        if (assetType.isEmpty()) {
            log.warn("assets 略過無法解析 asset_type 的列：id={}", raw.id());
            return Optional.empty();
        }
        return Optional.of(new AssetSummary(
                raw.id(),
                raw.symbol(),
                raw.name(),
                assetType.get(),
                raw.market(),
                raw.tradeable(),
                raw.active()));
    }

    /**
     * 以 {@link Enum#valueOf} 轉換 DB 原始字串；null 或不在 enum 內時回 empty（呼叫端負責記 WARN）。
     *
     * @param type  enum 類別
     * @param value DB 原始字串
     * @param <E>   enum 型別
     * @return 轉換結果；無法轉換時為 empty
     */
    private static <E extends Enum<E>> Optional<E> parseEnum(Class<E> type, String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Enum.valueOf(type, value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private RawSummary mapRawSummary(ResultSet rs, int rowNum) throws SQLException {
        return new RawSummary(
                rs.getLong("id"),
                rs.getString("symbol"),
                rs.getString("name"),
                rs.getString("asset_type"),
                rs.getString("market"),
                rs.getBoolean("tradeable"),
                rs.getBoolean("active"));
    }

    /**
     * {@code assets} 尚未轉 enum 的原始列（findAllTradeable / findBySymbol 用）。
     *
     * @param id        資產 id
     * @param symbol    標的代號
     * @param name      標的名稱
     * @param assetType asset_type 原始字串
     * @param market    交易市場代號
     * @param tradeable 是否可交易
     * @param active    是否啟用
     */
    record RawSummary(
            long id,
            String symbol,
            String name,
            String assetType,
            String market,
            boolean tradeable,
            boolean active
    ) {
    }
}
