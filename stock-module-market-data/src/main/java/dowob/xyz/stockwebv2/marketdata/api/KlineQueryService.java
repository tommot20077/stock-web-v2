package dowob.xyz.stockwebv2.marketdata.api;

import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.model.KlineInterval;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetFacade;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetSummary;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/**
 * 查詢 K 線 Continuous Aggregate view 的服務層。
 *
 * <p>依 {@link KlineInterval} 路由至對應的 view（{@code kline_1m} / {@code kline_5m} /
 * {@code kline_15m} / {@code kline_1h} / {@code kline_1d}），
 * 使用 {@link JdbcClient} 執行參數化查詢，結果對映為 {@link KlineDto} list。
 *
 * <p>SQL 中 view 名稱由 switch expression 從 enum 常數映射而來，
 * 非直接使用使用者輸入，無 SQL injection 風險。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Service
public class KlineQueryService {

    /** 預設回傳筆數。 */
    public static final int DEFAULT_LIMIT = 500;

    /** 最大允許回傳筆數。 */
    public static final int MAX_LIMIT = 5000;

    private final AssetFacade assetFacade;
    private final JdbcClient jdbcClient;

    /**
     * 建構子注入 {@link AssetFacade} 與 {@link JdbcClient}。
     *
     * @param assetFacade 資產 facade，用於解析 symbol → assetId
     * @param jdbcClient  Spring 6.1+ fluent JDBC client
     */
    public KlineQueryService(AssetFacade assetFacade, JdbcClient jdbcClient) {
        this.assetFacade = assetFacade;
        this.jdbcClient = jdbcClient;
    }

    /**
     * 查詢指定 symbol 與時間區間的 K 線資料。
     *
     * <p>時間區間為半開區間 {@code [from, to)}；{@code to} 未指定時預設為當前時間；
     * {@code limit} 未指定時預設 {@value #DEFAULT_LIMIT}，超過 {@value #MAX_LIMIT} 時截斷。
     *
     * @param symbol   資產代號，大小寫敏感
     * @param interval K 線間隔
     * @param from     查詢起始時間（含），不可為 null
     * @param to       查詢結束時間（不含），可為 null（預設 now）
     * @param limit    最大回傳筆數，可為 null（預設 500，最大 5000）
     * @return K 線 DTO list，按 {@code bucket ASC} 排序
     * @throws BusinessException {@link ErrorCode#ASSET_NOT_FOUND} 若 symbol 不存在
     * @throws BusinessException {@link ErrorCode#VALIDATION_FAILED} 若 from 為 null 或 to ≤ from
     */
    public List<KlineDto> findKlines(String symbol, KlineInterval interval,
                                     Instant from, Instant to, Integer limit) {
        AssetSummary asset = assetFacade.findBySymbol(symbol)
            .orElseThrow(() -> new BusinessException(
                ErrorCode.ASSET_NOT_FOUND, "Asset not found: " + symbol));

        if (from == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "from is required");
        }
        Instant effectiveTo = (to == null) ? Instant.now() : to;
        if (!effectiveTo.isAfter(from)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "to must be after from");
        }
        int effectiveLimit = (limit == null)
            ? DEFAULT_LIMIT
            : Math.min(Math.max(1, limit), MAX_LIMIT);

        String view = viewName(interval);
        String sql = """
                select bucket, open, high, low, close, volume
                from %s
                where asset_id = :assetId and bucket >= :from and bucket < :to
                order by bucket asc
                limit :limit
                """.formatted(view);

        return jdbcClient.sql(sql)
            .param("assetId", asset.id())
            .param("from", java.sql.Timestamp.from(from))
            .param("to", java.sql.Timestamp.from(effectiveTo))
            .param("limit", effectiveLimit)
            .query(this::map)
            .list();
    }

    /**
     * 將 {@link ResultSet} 一列對映至 {@link KlineDto} record。
     *
     * @param rs     result set（游標已定位至目標列）
     * @param rowNum 列號（供 Spring callback 使用）
     * @return 對映後的 {@link KlineDto}
     * @throws SQLException 若欄位讀取失敗
     */
    private KlineDto map(ResultSet rs, int rowNum) throws SQLException {
        return new KlineDto(
            rs.getTimestamp("bucket").toInstant(),
            rs.getBigDecimal("open"),
            rs.getBigDecimal("high"),
            rs.getBigDecimal("low"),
            rs.getBigDecimal("close"),
            rs.getBigDecimal("volume")
        );
    }

    /**
     * 將 {@link KlineInterval} 對映至 TimescaleDB continuous aggregate view 名稱。
     *
     * <p>view 名稱硬編碼在 switch expression 中（非使用者輸入），無 SQL injection 風險。
     *
     * @param interval K 線間隔
     * @return view 名稱
     */
    private static String viewName(KlineInterval interval) {
        return switch (interval) {
            case ONE_MINUTE     -> "kline_1m";
            case FIVE_MINUTES   -> "kline_5m";
            case FIFTEEN_MINUTES -> "kline_15m";
            case ONE_HOUR       -> "kline_1h";
            case ONE_DAY        -> "kline_1d";
        };
    }
}
