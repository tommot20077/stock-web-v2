package dowob.xyz.stockwebv2.trading.repository;

import dowob.xyz.stockwebv2.common.model.CurrencyCode;
import dowob.xyz.stockwebv2.trading.domain.CashEntryType;
import dowob.xyz.stockwebv2.trading.domain.CashLedgerEntry;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 現金分錄帳本的 JDBC 實作。
 *
 * <p>所有值一律走 JDBC 具名參數；本表不 join {@code assets}（現金分錄與標的無關，
 * 跨模組資料只能經 Facade 取得）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Repository
public class JdbcCashLedgerRepository implements CashLedgerRepository {
    private static final String COLUMNS = """
        id, uuid, user_id, type, currency, amount, note, occurred_at, created_at,
        idempotency_key, reversal_of_id
        """;

    private final JdbcClient jdbcClient;

    public JdbcCashLedgerRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * 帶冪等鍵的分錄寫入，也是本模組<strong>唯一</strong>的分錄寫入路徑。
     *
     * <p>與 {@code JdbcTradingRepository#insertTransactionIfAbsent} 同一個 insert-first 模式：
     * 衝突時 do nothing 不丟例外、{@code returning} 回零列，因此結尾必須是 {@code optional} 取值，
     * <strong>不可</strong>改成要求恰好一列的取值方式（那會拋 {@code EmptyResultDataAccessException}，
     * 把「冪等命中」這個正常結果誤報成系統錯誤）。也不可改成「先 insert、撞唯一約束例外再重讀」：
     * PostgreSQL 在例外後該交易已中止，重讀必定失敗。</p>
     *
     * <p>衝突目標的欄位組合與 V15 的 {@code uk_cash_ledger_entries_user_idempotency} 一致。
     * 04.1-06 的沖銷寫入會把衝突目標放寬以涵蓋 {@code uk_cash_ledger_entries_reversal_of}。</p>
     *
     * @param entry 待寫入的分錄
     * @return 實際建立的分錄；冪等鍵已被同一使用者用過時回傳空 Optional
     */
    @Override
    public Optional<CashLedgerEntry> insertIfAbsent(CashLedgerEntry entry) {
        return jdbcClient.sql("""
                insert into cash_ledger_entries (
                    uuid, user_id, type, currency, amount, note, occurred_at, idempotency_key, reversal_of_id
                )
                values (
                    coalesce(:uuid, uuid_generate_v4()), :userId, :type, :currency,
                    :amount, :note, :occurredAt, :idempotencyKey, :reversalOfId
                )
                on conflict (user_id, idempotency_key) do nothing
                returning """ + " " + COLUMNS)
            .param("uuid", entry.uuid())
            .param("userId", entry.userId())
            .param("type", entry.type().name())
            .param("currency", entry.currency().name())
            .param("amount", entry.amount())
            .param("note", entry.note())
            .param("occurredAt", entry.occurredAt())
            .param("idempotencyKey", entry.idempotencyKey())
            .param("reversalOfId", entry.reversalOfId())
            .query(this::mapEntry)
            .optional();
    }

    /**
     * 以冪等鍵查出該使用者既有的分錄。
     *
     * <p>{@code where} 同時綁 {@code user_id} 與 {@code idempotency_key}：冪等鍵只在單一使用者範圍內唯一，
     * 少了 {@code user_id} 會讓 A 的 key 命中 B 的分錄（威脅 T-04.1-11）。刻意不加 {@code for update}：
     * 要查的列可能還不存在，列鎖對不存在的列不生效；併發由唯一約束擋。</p>
     *
     * @param userId         分錄擁有者 id
     * @param idempotencyKey 冪等鍵
     * @return 既有分錄；查無此 key 時回傳空 Optional
     */
    @Override
    public Optional<CashLedgerEntry> findByIdempotencyKey(Long userId, String idempotencyKey) {
        return jdbcClient.sql("select " + COLUMNS + """
                from cash_ledger_entries
                where user_id = :userId and idempotency_key = :idempotencyKey
                """)
            .param("userId", userId)
            .param("idempotencyKey", idempotencyKey)
            .query(this::mapEntry)
            .optional();
    }

    private CashLedgerEntry mapEntry(ResultSet rs, int rowNum) throws SQLException {
        long reversalOfId = rs.getLong("reversal_of_id");
        return new CashLedgerEntry(
            rs.getLong("id"),
            rs.getObject("uuid", UUID.class),
            rs.getLong("user_id"),
            CashEntryType.valueOf(rs.getString("type")),
            CurrencyCode.valueOf(rs.getString("currency")),
            rs.getBigDecimal("amount"),
            rs.getString("note"),
            rs.getObject("occurred_at", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getString("idempotency_key"),
            rs.wasNull() ? null : reversalOfId
        );
    }
}
