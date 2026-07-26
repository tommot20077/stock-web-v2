package dowob.xyz.stockwebv2.user.service;

import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.infrastructure.security.JwtProperties;
import dowob.xyz.stockwebv2.user.domain.User;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class RefreshTokenService {
    private static final int MAX_DEVICE_INFO_LENGTH = 128;

    /**
     * 已消費 refresh token 的標記 key 前綴，用於重放偵測（security.md §5a 步驟 5）。
     */
    private static final String CONSUMED_KEY_PREFIX = "user:refresh:used:";

    private final StringRedisTemplate redisTemplate;
    private final JwtProperties jwtProperties;

    public RefreshTokenService(StringRedisTemplate redisTemplate, JwtProperties jwtProperties) {
        this.redisTemplate = redisTemplate;
        this.jwtProperties = jwtProperties;
    }

    public String issue(User user, String deviceInfo) {
        if (user.id() == null) {
            throw new IllegalArgumentException("Cannot issue refresh token for an unsaved user");
        }

        try {
            String token = UUID.randomUUID().toString();
            String refreshKey = "user:refresh:" + token;
            redisTemplate.opsForHash().putAll(refreshKey, Map.of(
                "userId", String.valueOf(user.id()),
                "tokenVersion", String.valueOf(user.tokenVersion()),
                "deviceInfo", normalizeDeviceInfo(deviceInfo),
                "createdAt", OffsetDateTime.now().toString()
            ));
            Duration refreshTokenTtl = jwtProperties.refreshTokenTtl();
            String indexKey = "user:refresh:index:" + user.id();
            redisTemplate.expire(refreshKey, refreshTokenTtl);
            redisTemplate.opsForSet().add(indexKey, token);
            redisTemplate.expire(indexKey, refreshTokenTtl);
            redisTemplate.opsForHash().putAll("user:auth:" + user.id(), Map.of(
                "tokenVersion", String.valueOf(user.tokenVersion()),
                "status", user.status().name()
            ));
            return token;
        } catch (DataAccessResourceFailureException exception) {
            throw new BusinessException(ErrorCode.AUTH_REDIS_UNAVAILABLE, ErrorCode.AUTH_REDIS_UNAVAILABLE.defaultMessage());
        }
    }

    public void revoke(String token) {
        revoke(token, null);
    }

    /**
     * 讀取 refresh token 所屬的使用者 id，供登出時定位需撤銷 session 的帳號。
     *
     * @param token refresh token
     * @return 擁有者使用者 id；token 不存在時回 {@code null}
     */
    public Long findOwner(String token) {
        if (StringUtils.isBlank(token)) {
            return null;
        }
        Object userId = redisTemplate.opsForHash().get("user:refresh:" + token, "userId");
        return userId == null ? null : parseUserId(String.valueOf(userId));
    }

    /**
     * 將 Redis {@code user:auth:{userId}} 的 token version 同步為新值，使既有 access token
     * 立即失效（security.md §5「Instant logout」）。Redis 不可用時以 fail-closed 拋出。
     *
     * @param userId       使用者 id
     * @param tokenVersion 遞增後的新 token version
     */
    public void updateAuthTokenVersion(Long userId, int tokenVersion) {
        try {
            redisTemplate.opsForHash().put("user:auth:" + userId, "tokenVersion", String.valueOf(tokenVersion));
        } catch (DataAccessResourceFailureException exception) {
            throw new BusinessException(ErrorCode.AUTH_REDIS_UNAVAILABLE, ErrorCode.AUTH_REDIS_UNAVAILABLE.defaultMessage());
        }
    }

    public RefreshSession consumeForRotation(String token) {
        if (StringUtils.isBlank(token)) {
            throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID, ErrorCode.AUTH_REFRESH_TOKEN_INVALID.defaultMessage());
        }

        String refreshKey = "user:refresh:" + token;
        Map<Object, Object> refreshEntries = redisTemplate.opsForHash().entries(refreshKey);
        if (ObjectUtils.isEmpty(refreshEntries)) {
            revokeFamilyIfReplayed(token);
            throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID, ErrorCode.AUTH_REFRESH_TOKEN_INVALID.defaultMessage());
        }

        String userIdText = String.valueOf(refreshEntries.get("userId"));
        String tokenVersion = String.valueOf(refreshEntries.get("tokenVersion"));
        Long userId = parseUserId(userIdText);
        Map<Object, Object> authEntries = redisTemplate.opsForHash().entries("user:auth:" + userId);
        if (ObjectUtils.isEmpty(authEntries)) {
            revoke(token);
            throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID, ErrorCode.AUTH_REFRESH_TOKEN_INVALID.defaultMessage());
        }
        if (!tokenVersion.equals(String.valueOf(authEntries.get("tokenVersion")))) {
            revoke(token);
            throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID, ErrorCode.AUTH_REFRESH_TOKEN_INVALID.defaultMessage());
        }
        if (!"ACTIVE".equals(String.valueOf(authEntries.get("status")))) {
            revoke(token);
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN, ErrorCode.AUTH_FORBIDDEN.defaultMessage());
        }

        if (!Boolean.TRUE.equals(redisTemplate.delete(refreshKey))) {
            throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID, ErrorCode.AUTH_REFRESH_TOKEN_INVALID.defaultMessage());
        }
        redisTemplate.opsForSet().remove("user:refresh:index:" + userId, token);
        markConsumed(token, userId);
        return new RefreshSession(userId);
    }

    /**
     * 撤銷指定使用者的所有 refresh token（security.md §5a 步驟 5、§11 刪除流程）。
     * 以 reverse index 找出全部 token 後逐一刪除，最後移除索引本身。
     *
     * @param userId 使用者 id
     */
    public void revokeAllForUser(Long userId) {
        String indexKey = "user:refresh:index:" + userId;
        Set<String> tokens = redisTemplate.opsForSet().members(indexKey);
        if (tokens != null) {
            for (String token : tokens) {
                redisTemplate.delete("user:refresh:" + token);
            }
        }
        redisTemplate.delete(indexKey);
    }

    /**
     * 若該 token 曾被成功輪替消費過（即為重放），撤銷其擁有者的全部 refresh token。
     * 從未存在的 token 則不做任何事（避免以隨機值觸發他人 session 失效）。
     *
     * @param token 疑似被重放的 refresh token
     */
    private void revokeFamilyIfReplayed(String token) {
        String owner = redisTemplate.opsForValue().get(CONSUMED_KEY_PREFIX + token);
        if (owner == null) {
            return;
        }
        revokeAllForUser(parseUserId(owner));
    }

    /**
     * 標記 token 已被輪替消費，供日後重放偵測使用；TTL 與 refresh token 效期一致，
     * 超出效期後重放已無意義。
     *
     * @param token  已消費的 refresh token
     * @param userId 擁有者使用者 id
     */
    private void markConsumed(String token, Long userId) {
        redisTemplate.opsForValue().set(
            CONSUMED_KEY_PREFIX + token,
            String.valueOf(userId),
            jwtProperties.refreshTokenTtl()
        );
    }

    public void revoke(String token, Long expectedUserId) {
        String refreshKey = "user:refresh:" + token;
        Object userId = redisTemplate.opsForHash().get(refreshKey, "userId");
        if (expectedUserId != null && userId != null && !String.valueOf(expectedUserId).equals(String.valueOf(userId))) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN, ErrorCode.AUTH_FORBIDDEN.defaultMessage());
        }
        redisTemplate.delete(refreshKey);
        if (userId != null) {
            redisTemplate.opsForSet().remove("user:refresh:index:" + userId, token);
        }
    }

    private String normalizeDeviceInfo(String deviceInfo) {
        if (StringUtils.isBlank(deviceInfo)) {
            return "unknown";
        }

        String normalized = deviceInfo.trim();
        if (normalized.length() <= MAX_DEVICE_INFO_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_DEVICE_INFO_LENGTH);
    }

    private Long parseUserId(String userId) {
        try {
            return Long.valueOf(userId);
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID, ErrorCode.AUTH_REFRESH_TOKEN_INVALID.defaultMessage());
        }
    }

    public record RefreshSession(Long userId) {
    }
}
