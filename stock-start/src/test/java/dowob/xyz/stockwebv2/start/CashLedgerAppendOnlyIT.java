package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.start.support.ContainerIT;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * cash_ledger_entries 的資料庫層保護整合測試（D-09 / D-25）。
 *
 * <p>鎖兩件事：其一，帳本 append-only——UPDATE / DELETE / TRUNCATE 一律被 trigger 拒絕；
 * 其二，沖銷規則由資料庫強制——沖銷分錄必須與<strong>同一使用者</strong>未被沖銷的原分錄方向相反、
 * 幣別與金額相同，且同一原分錄只能被沖銷一次。</p>
 *
 * <p>全部測試以 {@link JdbcClient} 直接下 SQL，繞過應用層以證明是 DB 層在擋；
 * 測試內<strong>不得</strong>自行建立 trigger 或更動表結構——保護必須來自 migration 本身。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("現金分錄的資料庫層保護")
class CashLedgerAppendOnlyIT extends ContainerIT {

    private static final String APPEND_ONLY_MESSAGE = "cash_ledger_entries is append-only";

    /**
     * 直接對資料庫下 SQL 的用戶端，繞過應用層以驗證 DB 層強制。
     */
    @Autowired
    JdbcClient jdbcClient;

    /**
     * 原分錄擁有者。
     */
    private Long ownerId;

    /**
     * 另一名使用者，用於「沖銷他人分錄」的案例。
     */
    private Long otherUserId;

    /**
     * 原分錄：owner 的 DEPOSIT USD 1000。
     */
    private Long originalId;

    /**
     * 每個測試前種下兩名使用者與一筆原分錄。
     */
    @BeforeEach
    void seedOriginalEntry() {
        ownerId = insertUser();
        otherUserId = insertUser();
        originalId = insertEntry(ownerId, "DEPOSIT", "USD", "1000", null);
    }

    @Test
    @DisplayName("對現金分錄執行 UPDATE 會被 DB trigger 拒絕")
    void updateIsRejected() {
        assertThatThrownBy(() -> jdbcClient.sql("UPDATE cash_ledger_entries SET note = 'tampered' WHERE id = :id")
            .param("id", originalId)
            .update())
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining(APPEND_ONLY_MESSAGE);
    }

    @Test
    @DisplayName("對現金分錄執行 DELETE 會被 DB trigger 拒絕")
    void deleteIsRejected() {
        assertThatThrownBy(() -> jdbcClient.sql("DELETE FROM cash_ledger_entries WHERE id = :id")
            .param("id", originalId)
            .update())
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining(APPEND_ONLY_MESSAGE);
    }

    @Test
    @DisplayName("對現金分錄執行 TRUNCATE 會被 DB trigger 拒絕")
    void truncateIsRejected() {
        assertThatThrownBy(() -> jdbcClient.sql("TRUNCATE TABLE cash_ledger_entries").update())
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining(APPEND_ONLY_MESSAGE);
    }

    @Test
    @DisplayName("方向相反、幣別與金額相同的同使用者沖銷分錄可以寫入")
    void validReversalIsAccepted() {
        Long reversalId = insertEntry(ownerId, "WITHDRAWAL", "USD", "1000", originalId);

        Long storedReversalOf = jdbcClient.sql("SELECT reversal_of_id FROM cash_ledger_entries WHERE id = :id")
            .param("id", reversalId)
            .query(Long.class)
            .single();
        assertThat(storedReversalOf).isEqualTo(originalId);
    }

    @Test
    @DisplayName("同一原分錄被沖銷第二次會被唯一約束拒絕（併發雙沖銷的最後防線）")
    void secondReversalOfSameEntryIsRejected() {
        insertEntry(ownerId, "WITHDRAWAL", "USD", "1000", originalId);

        assertThatThrownBy(() -> insertEntry(ownerId, "WITHDRAWAL", "USD", "1000", originalId))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("沖銷一筆「沖銷分錄」會被拒絕")
    void reversalOfReversalIsRejected() {
        Long reversalId = insertEntry(ownerId, "WITHDRAWAL", "USD", "1000", originalId);

        assertThatThrownBy(() -> insertEntry(ownerId, "DEPOSIT", "USD", "1000", reversalId))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("沖銷分錄的幣別與原分錄不同會被拒絕")
    void reversalWithMismatchedCurrencyIsRejected() {
        assertThatThrownBy(() -> insertEntry(ownerId, "WITHDRAWAL", "TWD", "1000", originalId))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("沖銷分錄的金額與原分錄不同會被拒絕")
    void reversalWithMismatchedAmountIsRejected() {
        assertThatThrownBy(() -> insertEntry(ownerId, "WITHDRAWAL", "USD", "999", originalId))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("沖銷分錄的方向與原分錄相同會被拒絕")
    void reversalWithSameTypeIsRejected() {
        assertThatThrownBy(() -> insertEntry(ownerId, "DEPOSIT", "USD", "1000", originalId))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("沖銷他人的分錄會被拒絕")
    void reversalOfAnotherUsersEntryIsRejected() {
        assertThatThrownBy(() -> insertEntry(otherUserId, "WITHDRAWAL", "USD", "1000", originalId))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("沖銷一筆不存在的分錄會被拒絕")
    void reversalOfNonexistentEntryIsRejected() {
        Long missingId = jdbcClient.sql("SELECT COALESCE(MAX(id), 0) + 1000000 FROM cash_ledger_entries")
            .query(Long.class)
            .single();

        assertThatThrownBy(() -> insertEntry(ownerId, "WITHDRAWAL", "USD", "1000", missingId))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * 以 SQL 種一名使用者。
     *
     * @return 使用者主鍵
     */
    private Long insertUser() {
        return jdbcClient.sql("""
                INSERT INTO users(email, username, password_hash, role, status)
                VALUES (:email, :username, 'x', 'USER', 'ACTIVE')
                RETURNING id
                """)
            .param("email", "cash-ledger-" + UUID.randomUUID() + "@example.com")
            .param("username", "cash-ledger-" + UUID.randomUUID())
            .query(Long.class)
            .single();
    }

    /**
     * 以 SQL 直接寫入一筆分錄，冪等鍵每次隨機以免互相衝突。
     *
     * @param userId       擁有者
     * @param type         DEPOSIT 或 WITHDRAWAL
     * @param currency     幣別
     * @param amount       金額
     * @param reversalOfId 被沖銷的原分錄主鍵；一般分錄為 null
     * @return 新分錄主鍵
     */
    private Long insertEntry(Long userId, String type, String currency, String amount, Long reversalOfId) {
        return jdbcClient.sql("""
                INSERT INTO cash_ledger_entries(user_id, type, currency, amount, occurred_at, idempotency_key, reversal_of_id)
                VALUES (:userId, :type, :currency, :amount, NOW(), :idempotencyKey, :reversalOfId)
                RETURNING id
                """)
            .param("userId", userId)
            .param("type", type)
            .param("currency", currency)
            .param("amount", new BigDecimal(amount))
            .param("idempotencyKey", UUID.randomUUID().toString())
            .param("reversalOfId", reversalOfId)
            .query(Long.class)
            .single();
    }
}
