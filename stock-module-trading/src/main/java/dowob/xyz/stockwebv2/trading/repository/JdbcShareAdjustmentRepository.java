package dowob.xyz.stockwebv2.trading.repository;

import dowob.xyz.stockwebv2.trading.domain.ShareAdjustmentEntry;
import dowob.xyz.stockwebv2.trading.domain.ShareAdjustmentType;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 股數調整帳本的 JDBC 實作（ADJ-01）。
 *
 * <p>所有值一律走 JDBC 具名參數。本表只存 {@code asset_id}，<strong>不與資產主檔表做 SQL JOIN</strong>：
 * 標的的代號與幣別一律經 {@code AssetFacade} 取得（D-21，{@code TradingSqlBoundaryTest} 守門）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Repository
public class JdbcShareAdjustmentRepository implements ShareAdjustmentRepository {
    private static final String COLUMNS = """
        id, uuid, user_id, asset_id, type, ratio_new, ratio_old, quantity_delta, effective_date, effective_at,
        note, created_at, idempotency_key, reversal_of_id
        """;

    private final JdbcClient jdbcClient;

    /**
     * 建立股數調整帳本的 JDBC 實作。
     *
     * @param jdbcClient Spring JDBC 客戶端
     */
    public JdbcShareAdjustmentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * 帶冪等鍵的寫入，也是本表<strong>唯一</strong>的寫入路徑。
     *
     * <p>與 {@code JdbcDividendRepository#insertIfAbsent} 同一個 insert-first 模式：衝突時 do nothing、
     * {@code returning} 回零列。<strong>衝突子句刻意不指定目標</strong>：{@code uk_share_adjustments_user_idempotency}
     * 與 {@code uk_share_adjustments_reversal_of} 都可能被正常請求撞上，指定其一則另一個會變成 500。
     * 其他約束（uuid 唯一、CHECK、外鍵）與沖銷驗證 trigger 不受影響，違反時照常拋例外。</p>
     *
     * @param entry 待寫入的紀錄
     * @return 實際建立的紀錄；任一唯一約束衝突時回傳空 Optional
     */
    @Override
    public Optional<ShareAdjustmentEntry> insertIfAbsent(ShareAdjustmentEntry entry) {
        return jdbcClient.sql("""
                insert into share_adjustments (
                    uuid, user_id, asset_id, type, ratio_new, ratio_old, quantity_delta, effective_date, effective_at,
                    note, idempotency_key, reversal_of_id
                )
                values (
                    coalesce(:uuid, uuid_generate_v4()), :userId, :assetId, :type, :ratioNew, :ratioOld,
                    :quantityDelta, :effectiveDate, :effectiveAt, :note, :idempotencyKey, :reversalOfId
                )
                on conflict do nothing
                returning """ + " " + COLUMNS)
            .param("uuid", entry.uuid())
            .param("userId", entry.userId())
            .param("assetId", entry.assetId())
            .param("type", entry.type().name())
            .param("ratioNew", entry.ratioNew())
            .param("ratioOld", entry.ratioOld())
            .param("quantityDelta", entry.quantityDelta())
            .param("effectiveDate", entry.effectiveDate())
            .param("effectiveAt", entry.effectiveAt())
            .param("note", entry.note())
            .param("idempotencyKey", entry.idempotencyKey())
            .param("reversalOfId", entry.reversalOfId())
            .query(JdbcShareAdjustmentRepository::mapEntry)
            .optional();
    }

    /**
     * 以冪等鍵查出該使用者既有的紀錄；{@code where} 同時綁 {@code user_id}，不加 {@code for update}
     * （要查的列可能還不存在，併發由唯一約束擋）。
     *
     * @param userId         紀錄擁有者 id
     * @param idempotencyKey 冪等鍵
     * @return 既有紀錄；查無此 key 時回傳空 Optional
     */
    @Override
    public Optional<ShareAdjustmentEntry> findByIdempotencyKey(Long userId, String idempotencyKey) {
        return jdbcClient.sql("select " + COLUMNS + """
                from share_adjustments
                where user_id = :userId and idempotency_key = :idempotencyKey
                """)
            .param("userId", userId)
            .param("idempotencyKey", idempotencyKey)
            .query(JdbcShareAdjustmentRepository::mapEntry)
            .optional();
    }

    /**
     * 以對外 UUID 查出該使用者自己的紀錄；{@code where} 同時綁 {@code user_id}，他人的紀錄查不到。
     *
     * @param userId 紀錄擁有者 id
     * @param uuid   紀錄的對外 UUID
     * @return 該使用者的紀錄；查無（含屬於他人）時回傳空 Optional
     */
    @Override
    public Optional<ShareAdjustmentEntry> findByUuid(Long userId, UUID uuid) {
        return jdbcClient.sql("select " + COLUMNS + """
                from share_adjustments
                where user_id = :userId and uuid = :uuid
                """)
            .param("userId", userId)
            .param("uuid", uuid)
            .query(JdbcShareAdjustmentRepository::mapEntry)
            .optional();
    }

    /**
     * 判斷某筆紀錄是否已有沖銷列指向它（走 {@code uk_share_adjustments_reversal_of} 的唯一索引）。
     *
     * @param adjustmentId 原紀錄的內部主鍵
     * @return 已被沖銷時為 true
     */
    @Override
    public boolean isReversed(Long adjustmentId) {
        return jdbcClient.sql("select exists (select 1 from share_adjustments where reversal_of_id = :adjustmentId)")
            .param("adjustmentId", adjustmentId)
            .query(Boolean.class)
            .single();
    }

    /**
     * 取某筆紀錄的對外 UUID。
     *
     * @param adjustmentId 紀錄的內部主鍵
     * @return 對外 UUID；查無時回傳空 Optional
     */
    @Override
    public Optional<UUID> uuidOf(Long adjustmentId) {
        return jdbcClient.sql("select uuid from share_adjustments where id = :adjustmentId")
            .param("adjustmentId", adjustmentId)
            .query((rs, rowNum) -> rs.getObject("uuid", UUID.class))
            .optional();
    }

    /**
     * 新調整的換算基準持股 = 生效瞬間<strong>之前</strong>的交易淨股數 + 生效瞬間<strong>（含）之前</strong>的
     * 既有調整淨股數（沖銷列反號）。調整段用 {@code <=} 讓同一除權日先記錄的調整依記錄順序疊加
     * （Yuan 2026-11-04 裁決；同瞬間的原列與其沖銷列淨額為 0），交易段維持 {@code <}。
     *
     * <p>兩段 {@code case} 都把兩支分支寫明、不用 {@code else}：未知 type 會得到 NULL 而被 {@code sum} 忽略，
     * 不會被 {@code else} 默默算成某個方向（04.1 Pitfall 9 的教訓）。兩段分別走
     * {@code idx_transactions_user_asset_executed} 與 {@code idx_share_adjustments_user_asset_effective}。
     * 呼叫端已在同一交易內鎖住該標的的持倉列；交易寫入路徑在 commit 前必須取得同一把持倉列鎖，
     * 因此兩段讀取之間不會有同標的的新成交被 commit 而只被其中一段看見。</p>
     *
     * @param userId      擁有者
     * @param assetId     標的主鍵
     * @param effectiveAt 新調整的生效瞬間
     * @return 換算基準持股；從未持有時為 0
     */
    @Override
    public BigDecimal quantityAsOf(Long userId, Long assetId, OffsetDateTime effectiveAt) {
        BigDecimal traded = jdbcClient.sql("""
                select coalesce(sum(case when type = 'BUY' then quantity when type = 'SELL' then -quantity end), 0)
                from transactions
                where user_id = :userId and asset_id = :assetId and executed_at < :effectiveAt
                """)
            .param("userId", userId)
            .param("assetId", assetId)
            .param("effectiveAt", effectiveAt)
            .query(BigDecimal.class)
            .single();
        BigDecimal adjusted = jdbcClient.sql("""
                select coalesce(sum(case when reversal_of_id is null then quantity_delta
                                         else -quantity_delta end), 0)
                from share_adjustments
                where user_id = :userId and asset_id = :assetId and effective_at <= :effectiveAt
                """)
            .param("userId", userId)
            .param("assetId", assetId)
            .param("effectiveAt", effectiveAt)
            .query(BigDecimal.class)
            .single();
        return traded.add(adjusted);
    }

    /**
     * 該標的最晚一筆「非沖銷列且未被沖銷」的調整。
     *
     * @param userId  擁有者
     * @param assetId 標的主鍵
     * @return 最晚的有效調整；沒有時回傳空 Optional
     */
    @Override
    public Optional<ShareAdjustmentEntry> findLatestActive(Long userId, Long assetId) {
        return jdbcClient.sql("select " + COLUMNS + """
                from share_adjustments s
                where user_id = :userId and asset_id = :assetId and reversal_of_id is null
                  and not exists (select 1 from share_adjustments r where r.reversal_of_id = s.id)
                order by effective_at desc, id desc
                limit 1
                """)
            .param("userId", userId)
            .param("assetId", assetId)
            .query(JdbcShareAdjustmentRepository::mapEntry)
            .optional();
    }

    /**
     * 把結果集的當前列對應成 {@link ShareAdjustmentEntry}。
     *
     * @param rs     已定位在目標列的結果集
     * @param rowNum 列序號（RowMapper 介面要求，未使用）
     * @return 對應的紀錄
     * @throws SQLException 讀取欄位失敗時
     */
    static ShareAdjustmentEntry mapEntry(ResultSet rs, int rowNum) throws SQLException {
        return new ShareAdjustmentEntry(
            rs.getLong("id"),
            rs.getObject("uuid", UUID.class),
            rs.getLong("user_id"),
            rs.getLong("asset_id"),
            ShareAdjustmentType.valueOf(rs.getString("type")),
            /* 可為 NULL 的整數一律用 getObject(欄位, 類別)：getInt / getLong 對 NULL 回 0，
               會讓配股被讀成比例 0:0、一般紀錄被誤判成沖銷（理由同 JdbcCashLedgerRepository#mapEntry）。 */
            rs.getObject("ratio_new", Integer.class),
            rs.getObject("ratio_old", Integer.class),
            rs.getBigDecimal("quantity_delta"),
            rs.getObject("effective_date", LocalDate.class),
            rs.getObject("effective_at", OffsetDateTime.class),
            rs.getString("note"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getString("idempotency_key"),
            rs.getObject("reversal_of_id", Long.class)
        );
    }
}
