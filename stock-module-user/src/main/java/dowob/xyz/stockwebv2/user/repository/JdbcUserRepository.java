package dowob.xyz.stockwebv2.user.repository;

import dowob.xyz.stockwebv2.common.error.DuplicateResourceException;
import dowob.xyz.stockwebv2.common.model.Role;
import dowob.xyz.stockwebv2.common.model.UserStatus;
import dowob.xyz.stockwebv2.user.domain.User;

import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
@Primary
public class JdbcUserRepository implements UserRepository {
    private static final String USER_COLUMNS = """
        id, uuid, email, username, password_hash, role, status, token_version, created_at, updated_at
        """;

    private final JdbcClient jdbcClient;

    public JdbcUserRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return jdbcClient.sql("select " + USER_COLUMNS + " from users where email = :email")
            .param("email", email)
            .query(this::map)
            .optional();
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return jdbcClient.sql("select " + USER_COLUMNS + " from users where username = :username")
            .param("username", username)
            .query(this::map)
            .optional();
    }

    @Override
    public Optional<User> findById(Long id) {
        return jdbcClient.sql("select " + USER_COLUMNS + " from users where id = :id")
            .param("id", id)
            .query(this::map)
            .optional();
    }

    @Override
    public Optional<User> findByUuid(UUID uuid) {
        return jdbcClient.sql("select " + USER_COLUMNS + " from users where uuid = :uuid")
            .param("uuid", uuid)
            .query(this::map)
            .optional();
    }

    @Override
    public User save(User user) {
        if (user.id() != null) {
            throw new IllegalArgumentException("User updates are not part of foundation save()");
        }

        try {
            return jdbcClient.sql("""
                    insert into users (uuid, email, username, password_hash, role, status, token_version, created_at, updated_at)
                    values (:uuid, :email, :username, :passwordHash, :role, :status, :tokenVersion, :createdAt, :updatedAt)
                    returning id, uuid, email, username, password_hash, role, status, token_version, created_at, updated_at
                    """)
                .param("uuid", user.uuid())
                .param("email", user.email())
                .param("username", user.username())
                .param("passwordHash", user.passwordHash())
                .param("role", user.role().name())
                .param("status", user.status().name())
                .param("tokenVersion", user.tokenVersion())
                .param("createdAt", user.createdAt())
                .param("updatedAt", user.updatedAt())
                .query(this::map)
                .single();
        } catch (DataIntegrityViolationException exception) {
            throw duplicateResourceException(exception);
        }
    }

    @Override
    public int incrementTokenVersion(Long id) {
        return jdbcClient.sql("""
                update users
                set token_version = token_version + 1, updated_at = now()
                where id = :id
                returning token_version
                """)
            .param("id", id)
            .query(Integer.class)
            .single();
    }

    private RuntimeException duplicateResourceException(DataIntegrityViolationException exception) {
        String message = exception.getMessage();
        Throwable cause = exception.getCause();
        while (cause != null) {
            if (cause.getMessage() != null) {
                message = message == null ? cause.getMessage() : message + " " + cause.getMessage();
            }
            cause = cause.getCause();
        }

        if (message != null) {
            if (message.contains("uk_users_email")) {
                return new DuplicateResourceException("email");
            }
            if (message.contains("uk_users_username")) {
                return new DuplicateResourceException("username");
            }
        }
        return exception;
    }

    private User map(ResultSet rs, int rowNum) throws SQLException {
        return new User(
            rs.getLong("id"),
            rs.getObject("uuid", UUID.class),
            rs.getString("email"),
            rs.getString("username"),
            rs.getString("password_hash"),
            Role.valueOf(rs.getString("role")),
            UserStatus.valueOf(rs.getString("status")),
            rs.getInt("token_version"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)
        );
    }
}
