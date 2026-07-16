package dowob.xyz.stockwebv2.start.e2e.support;

import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnection;
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
     * 清除測試產生的使用者相關資料(transactions、holdings、backtest_runs、users)並清空 Redis。
     *
     * <p>transactions 為 append-only 帳本(V8 trigger 禁止 DELETE),故整批刪除在單一交易內以
     * {@code SET LOCAL session_replication_role = 'replica'} 暫時停用 user triggers 後執行;
     * Testcontainers 的資料庫使用者為 superuser,交易結束後設定自動還原,不影響 production schema。
     */
    public void cleanUserData() {
        jdbcTemplate.execute("""
            BEGIN;
            SET LOCAL session_replication_role = 'replica';
            DELETE FROM transactions;
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
