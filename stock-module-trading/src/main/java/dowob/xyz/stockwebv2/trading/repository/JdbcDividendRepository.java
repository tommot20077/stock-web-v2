package dowob.xyz.stockwebv2.trading.repository;

import dowob.xyz.stockwebv2.trading.domain.CashDividend;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 現金股利帳本的 JDBC 實作。
 *
 * <p>所有值一律走 JDBC 具名參數。本表只存 {@code asset_id}，<strong>不與資產主檔表做 SQL JOIN</strong>：
 * 標的的代號與幣別一律經 {@code AssetFacade} 取得（D-21，{@code TradingSqlBoundaryTest} 守門）。</p>
 *
 * @author Yuan
 * @version 1.1
 */
@Repository
public class JdbcDividendRepository implements DividendRepository {
    private static final String COLUMNS = """
        id, uuid, user_id, asset_id, amount, withholding_tax, note, paid_at, created_at,
        idempotency_key, reversal_of_id
        """;

    private final JdbcClient jdbcClient;

    /**
     * 建立現金股利帳本的 JDBC 實作。
     *
     * @param jdbcClient Spring JDBC 客戶端
     */
    public JdbcDividendRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * 帶冪等鍵的寫入，也是本表<strong>唯一</strong>的寫入路徑。
     *
     * <p>與 {@code JdbcCashLedgerRepository#insertIfAbsent} 同一個 insert-first 模式：衝突時 do nothing、
     * {@code returning} 回零列，因此結尾必須是 {@code optional} 取值；不可改成「先 insert、撞唯一約束例外再重讀」
     * （PostgreSQL 在例外後該交易已中止，重讀必定失敗）。</p>
     *
     * <p><strong>衝突子句刻意不指定目標</strong>：本表有兩個會讓正常請求撞上的唯一約束 ——
     * {@code uk_cash_dividends_user_idempotency}（冪等重送／同 key 併發）與 {@code uk_cash_dividends_reversal_of}
     * （兩個不同 key 的請求併發沖銷同一筆）。指定其中一個為目標，另一個撞上時仍會拋例外並變成 500；
     * 不指定則兩者都被吸收成「回零列」，由呼叫端以冪等鍵重讀區分。</p>
     *
     * <p>注意：不指定目標的 {@code on conflict do nothing} 會吸收<strong>所有</strong>唯一約束，包含
     * {@code uk_cash_dividends_uuid}；只有 CHECK、外鍵與 trigger（含沖銷驗證 trigger）的錯誤照常拋出。
     * uuid 是隨機 v4，真的碰撞時會變成回零列 → 呼叫端以 key 重讀落空 → {@code DUPLICATE_RESOURCE}
     * 或 {@code DIVIDEND_ALREADY_REVERSED}（機率可忽略，不另行處理）。</p>
     *
     * @param dividend 待寫入的紀錄
     * @return 實際建立的紀錄；任一唯一約束衝突時回傳空 Optional
     */
    @Override
    public Optional<CashDividend> insertIfAbsent(CashDividend dividend) {
        return jdbcClient.sql("""
                insert into cash_dividends (
                    uuid, user_id, asset_id, amount, withholding_tax, note, paid_at, idempotency_key, reversal_of_id
                )
                values (
                    coalesce(:uuid, uuid_generate_v4()), :userId, :assetId, :amount, :withholdingTax,
                    :note, :paidAt, :idempotencyKey, :reversalOfId
                )
                on conflict do nothing
                returning """ + " " + COLUMNS)
            .param("uuid", dividend.uuid())
            .param("userId", dividend.userId())
            .param("assetId", dividend.assetId())
            .param("amount", dividend.amount())
            .param("withholdingTax", dividend.withholdingTax())
            .param("note", dividend.note())
            .param("paidAt", dividend.paidAt())
            .param("idempotencyKey", dividend.idempotencyKey())
            .param("reversalOfId", dividend.reversalOfId())
            .query(JdbcDividendRepository::mapDividend)
            .optional();
    }

    /**
     * 以冪等鍵查出該使用者既有的紀錄。
     *
     * <p>{@code where} 同時綁 {@code user_id} 與 {@code idempotency_key}：冪等鍵只在單一使用者範圍內唯一，
     * 少了 {@code user_id} 會讓 A 的 key 命中 B 的紀錄。刻意不加 {@code for update}：要查的列可能還不存在，
     * 列鎖對不存在的列不生效；併發由唯一約束擋。</p>
     *
     * @param userId         紀錄擁有者 id
     * @param idempotencyKey 冪等鍵
     * @return 既有紀錄；查無此 key 時回傳空 Optional
     */
    @Override
    public Optional<CashDividend> findByIdempotencyKey(Long userId, String idempotencyKey) {
        return jdbcClient.sql("select " + COLUMNS + """
                from cash_dividends
                where user_id = :userId and idempotency_key = :idempotencyKey
                """)
            .param("userId", userId)
            .param("idempotencyKey", idempotencyKey)
            .query(JdbcDividendRepository::mapDividend)
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
    public Optional<CashDividend> findByUuid(Long userId, UUID uuid) {
        return jdbcClient.sql("select " + COLUMNS + """
                from cash_dividends
                where user_id = :userId and uuid = :uuid
                """)
            .param("userId", userId)
            .param("uuid", uuid)
            .query(JdbcDividendRepository::mapDividend)
            .optional();
    }

    /**
     * 判斷某筆紀錄是否已有沖銷列指向它（走 {@code uk_cash_dividends_reversal_of} 的唯一索引）。
     *
     * @param dividendId 原紀錄的內部主鍵
     * @return 已被沖銷時為 true
     */
    @Override
    public boolean isReversed(Long dividendId) {
        return jdbcClient.sql("select exists (select 1 from cash_dividends where reversal_of_id = :dividendId)")
            .param("dividendId", dividendId)
            .query(Boolean.class)
            .single();
    }

    /**
     * 取某筆紀錄的對外 UUID。
     *
     * @param dividendId 紀錄的內部主鍵
     * @return 對外 UUID；查無時回傳空 Optional
     */
    @Override
    public Optional<UUID> uuidOf(Long dividendId) {
        return jdbcClient.sql("select uuid from cash_dividends where id = :dividendId")
            .param("dividendId", dividendId)
            .query((rs, rowNum) -> rs.getObject("uuid", UUID.class))
            .optional();
    }

    /**
     * 依標的加總該使用者的股利淨額與預扣稅。
     *
     * <p>{@code where user_id = :userId} 是跨使用者隔離的唯一防線（T-04.2-12），不可省略。
     * {@code case} 的兩支都寫明：一般列（{@code reversal_of_id is null}）取正值、沖銷列取負值，
     * 使原列與其沖銷列相加為 0。不與資產主檔 join（D-21）：幣別由呼叫端經 {@code AssetFacade} 取得。</p>
     *
     * @param userId 紀錄擁有者 id
     * @return 標的 id → 淨額與預扣稅；沒有任何股利時為空 map
     */
    @Override
    public Map<Long, DividendTotals> netByAsset(Long userId) {
        Map<Long, DividendTotals> totals = new LinkedHashMap<>();
        jdbcClient.sql("""
                select asset_id,
                       sum(case when reversal_of_id is null then amount else -amount end) as net,
                       sum(case when reversal_of_id is null then withholding_tax else -withholding_tax end) as tax
                from cash_dividends
                where user_id = :userId
                group by asset_id
                order by asset_id
                """)
            .param("userId", userId)
            .query(rs -> {
                totals.put(rs.getLong("asset_id"),
                    new DividendTotals(rs.getBigDecimal("net"), rs.getBigDecimal("tax")));
            });
        return totals;
    }

    /**
     * 把結果集的當前列對應成 {@link CashDividend}。
     *
     * @param rs     已定位在目標列的結果集
     * @param rowNum 列序號（RowMapper 介面要求，未使用）
     * @return 對應的紀錄
     * @throws SQLException 讀取欄位失敗時
     */
    static CashDividend mapDividend(ResultSet rs, int rowNum) throws SQLException {
        return new CashDividend(
            rs.getLong("id"),
            rs.getObject("uuid", UUID.class),
            rs.getLong("user_id"),
            rs.getLong("asset_id"),
            rs.getBigDecimal("amount"),
            rs.getBigDecimal("withholding_tax"),
            rs.getString("note"),
            rs.getObject("paid_at", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getString("idempotency_key"),
            /* 可為 NULL 的 BIGINT 一律用 getObject(欄位, Long.class)：getLong 對 NULL 回 0，
               會讓一般紀錄被誤判成沖銷（理由詳見 JdbcCashLedgerRepository#mapEntry）。 */
            rs.getObject("reversal_of_id", Long.class)
        );
    }
}
