package dowob.xyz.stockwebv2.user.domain;

import dowob.xyz.stockwebv2.common.model.CurrencyCode;
import dowob.xyz.stockwebv2.common.model.Role;
import dowob.xyz.stockwebv2.common.model.UserStatus;
import dowob.xyz.stockwebv2.user.api.MeResponse;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 使用者領域物件。
 *
 * @param id           主鍵；尚未持久化時為 null
 * @param uuid         對外識別碼
 * @param email        登入 email（已正規化）
 * @param username     使用者名稱
 * @param passwordHash BCrypt 雜湊後的密碼
 * @param role         角色
 * @param status       帳號狀態
 * @param tokenVersion access token 版本，遞增即撤銷既有 token
 * @param baseCurrency 使用者基準幣（summary 折算的單位），新註冊預設 TWD（D-02），
 *                     與 {@code users.base_currency} 的 DB 預設值一致
 * @param createdAt    建立時間
 * @param updatedAt    最後更新時間
 * @author Yuan
 * @version 1.0
 */
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
    CurrencyCode baseCurrency,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public static User newUser(String email, String username, String passwordHash) {
        OffsetDateTime now = OffsetDateTime.now();
        return new User(null, UUID.randomUUID(), email, username, passwordHash, Role.USER, UserStatus.ACTIVE, 1, CurrencyCode.TWD, now, now);
    }

    public User withId(Long id) {
        return new User(id, uuid, email, username, passwordHash, role, status, tokenVersion, baseCurrency, createdAt, updatedAt);
    }

    public MeResponse toMeResponse() {
        return new MeResponse(id, uuid, email, username, role, status, baseCurrency);
    }
}
