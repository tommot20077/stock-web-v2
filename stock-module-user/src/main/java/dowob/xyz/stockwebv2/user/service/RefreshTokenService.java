package dowob.xyz.stockwebv2.user.service;

import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.infrastructure.security.JwtProperties;
import dowob.xyz.stockwebv2.user.domain.User;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class RefreshTokenService {
    private static final int MAX_DEVICE_INFO_LENGTH = 128;

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
        if (token == null || token.isBlank()) {
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
        if (token == null || token.isBlank()) {
            throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID, ErrorCode.AUTH_REFRESH_TOKEN_INVALID.defaultMessage());
        }

        String refreshKey = "user:refresh:" + token;
        Map<Object, Object> refreshEntries = redisTemplate.opsForHash().entries(refreshKey);
        if (refreshEntries == null || refreshEntries.isEmpty()) {
            throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID, ErrorCode.AUTH_REFRESH_TOKEN_INVALID.defaultMessage());
        }

        String userIdText = String.valueOf(refreshEntries.get("userId"));
        String tokenVersion = String.valueOf(refreshEntries.get("tokenVersion"));
        Long userId = parseUserId(userIdText);
        Map<Object, Object> authEntries = redisTemplate.opsForHash().entries("user:auth:" + userId);
        if (authEntries == null || authEntries.isEmpty()) {
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

        revoke(token, userId);
        return new RefreshSession(userId);
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
        if (deviceInfo == null || deviceInfo.isBlank()) {
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
