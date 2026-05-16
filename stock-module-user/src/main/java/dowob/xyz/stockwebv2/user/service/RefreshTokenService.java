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
}
