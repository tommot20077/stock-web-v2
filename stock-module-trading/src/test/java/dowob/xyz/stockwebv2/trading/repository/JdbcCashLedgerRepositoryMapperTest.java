package dowob.xyz.stockwebv2.trading.repository;

import dowob.xyz.stockwebv2.common.model.CurrencyCode;
import dowob.xyz.stockwebv2.trading.domain.CashEntryPayloadMatcher;
import dowob.xyz.stockwebv2.trading.domain.CashEntryType;
import dowob.xyz.stockwebv2.trading.domain.CashLedgerEntry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JdbcCashLedgerRepository#mapEntry} 的欄位對應單元測試。
 *
 * <p>這個 mapper 是 service 層以外唯一一段不經 Mockito 替身、也不經 SQL 的程式碼：service 測試把 repository
 * 整個替換掉，migration 的 SQL 檢查只驗資料庫端，兩者都碰不到「資料庫的 NULL 被 Java 讀成什麼」。
 * 這裡用一個遵守 JDBC 契約的結果集替身補上這一段，特別是 {@link ResultSet#wasNull()} 的語意：
 * 它只回報<strong>最近一次讀取</strong>的那個欄位是否為 SQL NULL，不是「某個欄位」。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("現金分錄 JDBC 欄位對應")
class JdbcCashLedgerRepositoryMapperTest {

    private static final UUID ENTRY_UUID = UUID.fromString("0b6a3f0e-5d0a-4c53-9a4e-0d6c8c1f6a11");
    private static final OffsetDateTime OCCURRED_AT = OffsetDateTime.parse("2026-10-01T09:00:00+08:00");

    @Test
    @DisplayName("一般分錄（reversal_of_id 為 SQL NULL）：reversalOfId 對應成 null，而不是 0")
    void ordinaryEntryMapsNullReversalOfIdToNull() throws SQLException {
        CashLedgerEntry entry = JdbcCashLedgerRepository.mapEntry(row(null), 0);

        assertThat(entry.reversalOfId()).isNull();
    }

    @Test
    @DisplayName("沖銷分錄（reversal_of_id 有值）：reversalOfId 對應成原分錄主鍵")
    void reversalEntryMapsReversalOfId() throws SQLException {
        CashLedgerEntry entry = JdbcCashLedgerRepository.mapEntry(row(42L), 0);

        assertThat(entry.reversalOfId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("其餘欄位逐一對應（含 NULL 的備註）")
    void otherColumnsAreMappedFieldByField() throws SQLException {
        CashLedgerEntry entry = JdbcCashLedgerRepository.mapEntry(row(null), 0);

        assertThat(entry.id()).isEqualTo(7L);
        assertThat(entry.uuid()).isEqualTo(ENTRY_UUID);
        assertThat(entry.userId()).isEqualTo(3L);
        assertThat(entry.type()).isEqualTo(CashEntryType.DEPOSIT);
        assertThat(entry.currency()).isEqualTo(CurrencyCode.USD);
        assertThat(entry.amount()).isEqualByComparingTo("1000");
        assertThat(entry.note()).isNull();
        assertThat(entry.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(entry.createdAt()).isEqualTo(OCCURRED_AT);
        assertThat(entry.idempotencyKey()).isEqualTo("key-1");
    }

    @Test
    @DisplayName("從資料庫讀回的一般分錄，與帶同一把 key 重送的一般請求比對為同一筆（不是假性 409）")
    void ordinaryEntryReadBackMatchesAnOrdinaryReplay() throws SQLException {
        CashLedgerEntry entry = JdbcCashLedgerRepository.mapEntry(row(null), 0);

        boolean sameIntent = CashEntryPayloadMatcher.matches(
            entry, CashEntryType.DEPOSIT, CurrencyCode.USD, new BigDecimal("1000.00"), OCCURRED_AT, null);

        assertThat(sameIntent).isTrue();
    }

    @Test
    @DisplayName("結果集替身遵守 JDBC 契約：wasNull 只反映最近一次讀取的欄位")
    void resultSetFakeFollowsTheJdbcWasNullContract() throws SQLException {
        ResultSet rs = row(null);

        rs.getLong("reversal_of_id");
        assertThat(rs.wasNull()).isTrue();

        rs.getString("idempotency_key");
        assertThat(rs.wasNull()).isFalse();
    }

    /**
     * 組出一列 {@code cash_ledger_entries} 的結果集替身。
     *
     * <p>語意與 JDBC 一致：讀到 SQL NULL 時，基本型別 getter 回 0 / false、物件 getter 回 null，並且
     * {@code wasNull()} 只反映<strong>最近一次</strong> getter 讀取的欄位。</p>
     *
     * @param reversalOfId {@code reversal_of_id} 欄位的值；null 代表 SQL NULL
     * @return 結果集替身
     */
    private static ResultSet row(Long reversalOfId) {
        Map<String, Object> columns = new HashMap<>();
        columns.put("id", 7L);
        columns.put("uuid", ENTRY_UUID);
        columns.put("user_id", 3L);
        columns.put("type", "DEPOSIT");
        columns.put("currency", "USD");
        columns.put("amount", new BigDecimal("1000.00000000"));
        columns.put("note", null);
        columns.put("occurred_at", OCCURRED_AT);
        columns.put("created_at", OCCURRED_AT);
        columns.put("idempotency_key", "key-1");
        columns.put("reversal_of_id", reversalOfId);

        boolean[] lastReadWasNull = {false};
        return (ResultSet) Proxy.newProxyInstance(
            ResultSet.class.getClassLoader(),
            new Class<?>[] {ResultSet.class},
            (proxy, method, args) -> {
                if (method.getName().equals("wasNull")) {
                    return lastReadWasNull[0];
                }
                if (method.getName().startsWith("get") && args != null && args[0] instanceof String column) {
                    if (!columns.containsKey(column)) {
                        throw new SQLException("Column not found: " + column);
                    }
                    Object value = columns.get(column);
                    lastReadWasNull[0] = value == null;
                    return value == null ? nullValueOf(method.getReturnType()) : value;
                }
                throw new UnsupportedOperationException(method.toString());
            });
    }

    private static Object nullValueOf(Class<?> returnType) {
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == boolean.class) {
            return false;
        }
        return null;
    }
}
