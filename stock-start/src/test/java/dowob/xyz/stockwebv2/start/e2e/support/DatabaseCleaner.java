package dowob.xyz.stockwebv2.start.e2e.support;

import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.context.annotation.Profile;
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

    public void cleanUserData() {
        jdbcTemplate.execute("DELETE FROM users");
        redisConnectionFactory.getConnection().serverCommands().flushDb();
    }
}
