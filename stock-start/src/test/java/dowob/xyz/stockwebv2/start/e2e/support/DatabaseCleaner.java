package dowob.xyz.stockwebv2.start.e2e.support;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("e2e")
public class DatabaseCleaner {

    private final JdbcTemplate jdbcTemplate;
    private final RedisConnectionFactory redisConnectionFactory;

    public DatabaseCleaner(JdbcTemplate jdbcTemplate, RedisConnectionFactory redisConnectionFactory) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisConnectionFactory = redisConnectionFactory;
    }

    /**
     * 清除測試產生的使用者相關資料並清空 Redis。
     *
     * <p>刪除順序依 FK 由子到父：{@code watchlist_items} → {@code watchlists} → {@code share_adjustments} →
     * {@code cash_dividends} → {@code transactions} → {@code cash_ledger_entries} → {@code holdings} →
     * {@code backtest_runs} → {@code users}。</p>
     *
     * <p>transactions、cash_ledger_entries、cash_dividends、share_adjustments 為 append-only 帳本
     * (V8 / V15 / V16 / V17 trigger 禁止 UPDATE / DELETE / TRUNCATE),故整批刪除在單一交易內以
     * {@code SET LOCAL session_replication_role = 'replica'} 暫時停用 user triggers 後執行;
     * Testcontainers 的資料庫使用者為 superuser,交易結束後設定自動還原,不影響 production schema。</p>
     *
     * <p><strong>新增參照 users 的表時必須同步加入這裡</strong>:replica role 同時停用了 FK 檢查與
     * {@code ON DELETE CASCADE}(兩者皆以系統 trigger 實作),漏加的表<strong>不會報錯</strong>,只會讓資料殘留到
     * 下一個測試、使斷言莫名失準。也因此 {@code watchlist_items} 不能依賴 {@code watchlists} 的 CASCADE,必須明確刪除。</p>
     */
    public void cleanUserData() {
        jdbcTemplate.execute("""
            BEGIN;
            SET LOCAL session_replication_role = 'replica';
            DELETE FROM watchlist_items;
            DELETE FROM watchlists;
            DELETE FROM share_adjustments;
            DELETE FROM cash_dividends;
            DELETE FROM transactions;
            DELETE FROM cash_ledger_entries;
            DELETE FROM holdings;
            DELETE FROM backtest_runs;
            DELETE FROM users;
            COMMIT;
            """);
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }
}
