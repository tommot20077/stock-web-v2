package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.start.support.ContainerIT;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * transactions 表 append-only DB trigger 的整合測試。
 *
 * <p>驗證合規硬需求（architecture.md §Compliance、security.md §11）：資料庫層必須以 trigger
 * 阻擋對 transactions 的 UPDATE 與 DELETE，僅允許 INSERT（append-only）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("transactions 表 append-only DB trigger")
class TransactionsAppendOnlyIT extends ContainerIT {

    /**
     * 直接對資料庫下 SQL 的用戶端，繞過應用層以驗證 DB 層強制。
     */
    @Autowired
    JdbcClient jdbcClient;

    /**
     * 每個測試前置的交易列主鍵，供 UPDATE/DELETE 嘗試使用。
     */
    private Long transactionId;

    /**
     * 種下一名使用者與一筆交易列，作為 append-only 驗證的目標資料。
     */
    @BeforeEach
    void seedTransaction() {
        Long userId = jdbcClient.sql("""
                INSERT INTO users(email, username, password_hash, role, status)
                VALUES (:email, :username, 'x', 'USER', 'ACTIVE')
                RETURNING id
                """)
            .param("email", "append-only-" + UUID.randomUUID() + "@example.com")
            .param("username", "append-only-" + UUID.randomUUID())
            .query(Long.class)
            .single();

        Long assetId = jdbcClient.sql("SELECT id FROM assets WHERE symbol = 'AAPL'")
            .query(Long.class)
            .single();

        transactionId = jdbcClient.sql("""
                INSERT INTO transactions(user_id, asset_id, type, quantity, price, fee, executed_at)
                VALUES (:userId, :assetId, 'BUY', 1, 100, 0, NOW())
                RETURNING id
                """)
            .param("userId", userId)
            .param("assetId", assetId)
            .query(Long.class)
            .single();
    }

    @Test
    @DisplayName("對 transactions 執行 UPDATE 會被 DB trigger 拒絕")
    void updateOnTransactionsIsRejected() {
        assertThatThrownBy(() -> jdbcClient.sql("UPDATE transactions SET note = 'tampered' WHERE id = :id")
            .param("id", transactionId)
            .update())
            .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("對 transactions 執行 DELETE 會被 DB trigger 拒絕")
    void deleteOnTransactionsIsRejected() {
        assertThatThrownBy(() -> jdbcClient.sql("DELETE FROM transactions WHERE id = :id")
            .param("id", transactionId)
            .update())
            .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("對 transactions 執行 INSERT 仍然可以成功（append-only 不阻擋新增）")
    void insertOnTransactionsStillSucceeds() {
        Long assetId = jdbcClient.sql("SELECT id FROM assets WHERE symbol = 'AAPL'")
            .query(Long.class)
            .single();
        Long ownerId = jdbcClient.sql("SELECT user_id FROM transactions WHERE id = :id")
            .param("id", transactionId)
            .query(Long.class)
            .single();

        int inserted = jdbcClient.sql("""
                INSERT INTO transactions(user_id, asset_id, type, quantity, price, fee, executed_at)
                VALUES (:userId, :assetId, 'SELL', 1, 120, 0, NOW())
                """)
            .param("userId", ownerId)
            .param("assetId", assetId)
            .update();

        assertThat(inserted).isEqualTo(1);
    }
}
