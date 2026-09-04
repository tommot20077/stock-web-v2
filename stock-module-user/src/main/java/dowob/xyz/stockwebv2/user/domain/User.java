package dowob.xyz.stockwebv2.user.domain;

import dowob.xyz.stockwebv2.common.model.Role;
import dowob.xyz.stockwebv2.common.model.UserStatus;
import dowob.xyz.stockwebv2.user.api.MeResponse;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Table("users")
public record User(
    @Id Long id,
    UUID uuid,
    String email,
    String username,
    String passwordHash,
    Role role,
    UserStatus status,
    int tokenVersion,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public static User newUser(String email, String username, String passwordHash) {
        OffsetDateTime now = OffsetDateTime.now();
        return new User(null, UUID.randomUUID(), email, username, passwordHash, Role.USER, UserStatus.ACTIVE, 1, now, now);
    }

    public User withId(Long id) {
        return new User(id, uuid, email, username, passwordHash, role, status, tokenVersion, createdAt, updatedAt);
    }

    public MeResponse toMeResponse() {
        return new MeResponse(id, uuid, email, username, role, status);
    }
}
