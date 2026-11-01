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
 * cash_dividends 的資料庫層保護整合測試（D-13；V16）。
 *
 * <p>鎖三件事：其一，帳本 append-only——UPDATE / DELETE / TRUNCATE 一律被 trigger 拒絕；
 * 其二，沖銷規則由資料庫強制——沖銷列必須指向<strong>同一使用者、同一標的</strong>、本身不是沖銷的原列，
 * 且 amount / withholding_tax 與原列相同，同一原列只能被沖銷一次；其三，金額 CHECK 與冪等鍵唯一約束
 * （以使用者為範圍）。</p>
 *
 * <p>全部測試以 {@link JdbcClient} 直接下 SQL，繞過應用層以證明是 DB 層在擋；測試內<strong>不得</strong>
 * 自行建立 trigger 或更動表結構。帳本不可 DELETE，測試資料以每個測試各自新建的使用者隔離，不做清理。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("現金股利的資料庫層保護")
class CashDividendsAppendOnlyIT extends ContainerIT {

    private static final String APPEND_ONLY_MESSAGE = "cash_dividends is append-only";

    private static final String REVERSAL_REJECTED = "cash dividend reversal rejected";

    /**
     * 直接對資料庫下 SQL 的用戶端，繞過應用層以驗證 DB 層強制。
     */
    @Autowired
    JdbcClient jdbcClient;

    /**
     * 原列擁有者。
     */
    private Long ownerId;

    /**
     * 另一名使用者，用於「沖銷他人紀錄」與「冪等鍵跨使用者」的案例。
     */
    private Long otherUserId;

    /**
     * 原列的標的（seed 的 AAPL）。
     */
    private Long aaplId;

    /**
     * 另一個標的（seed 的 MSFT），用於「沖銷時換標的」的案例。
     */
    private Long msftId;

    /**
     * 原列：owner 的 AAPL 股利，實領 120、預扣稅 30。
     */
    private Long originalId;

    /**
     * 每個測試前種下兩名使用者與一筆原列。
     */
    @BeforeEach
    void seedOriginalDividend() {
        ownerId = insertUser();
        otherUserId = insertUser();
        aaplId = assetId("AAPL");
        msftId = assetId("MSFT");
        originalId = insertDividend(ownerId, aaplId, "120", "30", UUID.randomUUID().toString(), null);
    }

    @Test
    @DisplayName("對現金股利執行 UPDATE 會被 DB trigger 拒絕")
    void updateIsRejected() {
        assertThatThrownBy(() -> jdbcClient.sql("UPDATE cash_dividends SET note = 'tampered' WHERE id = :id")
            .param("id", originalId)
            .update())
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining(APPEND_ONLY_MESSAGE);
    }

    @Test
    @DisplayName("對現金股利執行 DELETE 會被 DB trigger 拒絕")
    void deleteIsRejected() {
        assertThatThrownBy(() -> jdbcClient.sql("DELETE FROM cash_dividends WHERE id = :id")
            .param("id", originalId)
            .update())
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining(APPEND_ONLY_MESSAGE);
    }

    @Test
    @DisplayName("對現金股利執行 TRUNCATE 會被 DB trigger 拒絕")
    void truncateIsRejected() {
        assertThatThrownBy(() -> jdbcClient.sql("TRUNCATE TABLE cash_dividends").update())
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining(APPEND_ONLY_MESSAGE);
    }

    @Test
    @DisplayName("同使用者、同標的、金額與預扣稅相同的沖銷列可以寫入（scale 不同但數值相等亦可）")
    void validReversalIsAccepted() {
        Long reversalId = insertDividend(ownerId, aaplId, "120.00", "30.0", UUID.randomUUID().toString(), originalId);

        Long storedReversalOf = jdbcClient.sql("SELECT reversal_of_id FROM cash_dividends WHERE id = :id")
            .param("id", reversalId)
            .query(Long.class)
            .single();
        assertThat(storedReversalOf).isEqualTo(originalId);
    }

    @Test
    @DisplayName("同一原列被沖銷第二次會被 uk_cash_dividends_reversal_of 拒絕（併發雙沖銷的最後防線）")
    void secondReversalOfSameDividendIsRejected() {
        insertDividend(ownerId, aaplId, "120", "30", UUID.randomUUID().toString(), originalId);

        assertThatThrownBy(() ->
            insertDividend(ownerId, aaplId, "120", "30", UUID.randomUUID().toString(), originalId))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("uk_cash_dividends_reversal_of");
    }

    @Test
    @DisplayName("沖銷一筆不存在的原列會被拒絕")
    void reversalOfNonexistentDividendIsRejected() {
        Long missingId = jdbcClient.sql("SELECT COALESCE(MAX(id), 0) + 1000000 FROM cash_dividends")
            .query(Long.class)
            .single();

        assertReversalRejected(
            () -> insertDividend(ownerId, aaplId, "120", "30", UUID.randomUUID().toString(), missingId),
            "original dividend does not exist");
    }

    @Test
    @DisplayName("沖銷他人的原列會被拒絕")
    void reversalOfAnotherUsersDividendIsRejected() {
        assertReversalRejected(
            () -> insertDividend(otherUserId, aaplId, "120", "30", UUID.randomUUID().toString(), originalId),
            "original dividend belongs to another user");
    }

    @Test
    @DisplayName("沖銷列的標的與原列不同會被拒絕")
    void reversalWithDifferentAssetIsRejected() {
        assertReversalRejected(
            () -> insertDividend(ownerId, msftId, "120", "30", UUID.randomUUID().toString(), originalId),
            "asset does not match the original dividend");
    }

    @Test
    @DisplayName("沖銷一筆「沖銷列」會被拒絕")
    void reversalOfReversalIsRejected() {
        Long reversalId = insertDividend(ownerId, aaplId, "120", "30", UUID.randomUUID().toString(), originalId);

        assertReversalRejected(
            () -> insertDividend(ownerId, aaplId, "120", "30", UUID.randomUUID().toString(), reversalId),
            "original dividend is itself a reversal");
    }

    @Test
    @DisplayName("沖銷列的實領金額與原列不同會被拒絕")
    void reversalWithMismatchedAmountIsRejected() {
        assertReversalRejected(
            () -> insertDividend(ownerId, aaplId, "119", "30", UUID.randomUUID().toString(), originalId),
            "amount or withholding tax does not match the original dividend");
    }

    @Test
    @DisplayName("沖銷列的預扣稅與原列不同會被拒絕")
    void reversalWithMismatchedWithholdingTaxIsRejected() {
        assertReversalRejected(
            () -> insertDividend(ownerId, aaplId, "120", "0", UUID.randomUUID().toString(), originalId),
            "amount or withholding tax does not match the original dividend");
    }

    @Test
    @DisplayName("實領金額為 0 或負數會被 ck_cash_dividends_amount_positive 拒絕")
    void nonPositiveAmountIsRejected() {
        assertThatThrownBy(() -> insertDividend(ownerId, aaplId, "0", "0", UUID.randomUUID().toString(), null))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ck_cash_dividends_amount_positive");
        assertThatThrownBy(() -> insertDividend(ownerId, aaplId, "-1", "0", UUID.randomUUID().toString(), null))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ck_cash_dividends_amount_positive");
    }

    @Test
    @DisplayName("預扣稅為負數會被 ck_cash_dividends_tax_non_negative 拒絕")
    void negativeWithholdingTaxIsRejected() {
        assertThatThrownBy(() -> insertDividend(ownerId, aaplId, "1", "-0.01", UUID.randomUUID().toString(), null))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ck_cash_dividends_tax_non_negative");
    }

    @Test
    @DisplayName("同一使用者的同一把冪等鍵寫第二列會被 uk_cash_dividends_user_idempotency 拒絕")
    void duplicateIdempotencyKeyForSameUserIsRejected() {
        String key = "dup-" + UUID.randomUUID();
        insertDividend(ownerId, aaplId, "10", "0", key, null);

        assertThatThrownBy(() -> insertDividend(ownerId, aaplId, "10", "0", key, null))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("uk_cash_dividends_user_idempotency");
    }

    @Test
    @DisplayName("不同使用者使用同一把冪等鍵可以並存（冪等以使用者為範圍）")
    void sameIdempotencyKeyAcrossUsersCoexists() {
        String key = "shared-" + UUID.randomUUID();
        insertDividend(ownerId, aaplId, "10", "0", key, null);
        insertDividend(otherUserId, aaplId, "10", "0", key, null);

        Integer rows = jdbcClient.sql("SELECT count(*) FROM cash_dividends WHERE idempotency_key = :key")
            .param("key", key)
            .query(Integer.class)
            .single();
        assertThat(rows).isEqualTo(2);
    }

    private void assertReversalRejected(Runnable insert, String reason) {
        assertThatThrownBy(insert::run)
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining(REVERSAL_REJECTED)
            .hasMessageContaining(reason);
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
            .param("email", "cash-dividend-" + UUID.randomUUID() + "@example.com")
            .param("username", "cash-dividend-" + UUID.randomUUID())
            .query(Long.class)
            .single();
    }

    /**
     * 取 seed 標的的主鍵。
     *
     * @param symbol 標的代號
     * @return 標的主鍵
     */
    private Long assetId(String symbol) {
        return jdbcClient.sql("SELECT id FROM assets WHERE symbol = :symbol")
            .param("symbol", symbol)
            .query(Long.class)
            .single();
    }

    /**
     * 以 SQL 直接寫入一筆現金股利。
     *
     * @param userId         擁有者
     * @param assetId        標的主鍵
     * @param amount         實領金額
     * @param withholdingTax 預扣稅
     * @param idempotencyKey 冪等鍵
     * @param reversalOfId   被沖銷的原列主鍵；一般紀錄為 null
     * @return 新紀錄主鍵
     */
    private Long insertDividend(
        Long userId, Long assetId, String amount, String withholdingTax, String idempotencyKey, Long reversalOfId) {
        return jdbcClient.sql("""
                INSERT INTO cash_dividends(
                    user_id, asset_id, amount, withholding_tax, paid_at, idempotency_key, reversal_of_id
                )
                VALUES (:userId, :assetId, :amount, :withholdingTax, NOW(), :idempotencyKey, :reversalOfId)
                RETURNING id
                """)
            .param("userId", userId)
            .param("assetId", assetId)
            .param("amount", new BigDecimal(amount))
            .param("withholdingTax", new BigDecimal(withholdingTax))
            .param("idempotencyKey", idempotencyKey)
            .param("reversalOfId", reversalOfId)
            .query(Long.class)
            .single();
    }
}
