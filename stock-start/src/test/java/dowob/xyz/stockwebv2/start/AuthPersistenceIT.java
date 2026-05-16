package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.common.error.DuplicateResourceException;
import dowob.xyz.stockwebv2.common.model.UserStatus;
import dowob.xyz.stockwebv2.infrastructure.security.JwtProperties;
import dowob.xyz.stockwebv2.start.support.ContainerIT;
import dowob.xyz.stockwebv2.user.api.RegisterRequest;
import dowob.xyz.stockwebv2.user.domain.User;
import dowob.xyz.stockwebv2.user.repository.UserRepository;
import dowob.xyz.stockwebv2.user.service.AuthService;
import dowob.xyz.stockwebv2.user.service.RefreshTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthPersistenceIT extends ContainerIT {
    private static final String LONG_DEVICE_INFO = "  " + "x".repeat(140) + "  ";

    @Autowired
    AuthService authService;

    @Autowired
    RefreshTokenService refreshTokenService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    JwtProperties jwtProperties;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Test
    void registerPersistsUserAndRefreshTokenUsesRedis() {
        User user = authService.register(new RegisterRequest("persistence@example.com", "persistence", "Password1"));
        String refreshToken = refreshTokenService.issue(user, LONG_DEVICE_INFO);
        String refreshKey = "user:refresh:" + refreshToken;
        String indexKey = "user:refresh:index:" + user.id();

        assertThat(user.id()).isNotNull();
        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(userRepository.findByUsername("persistence")).contains(user);
        assertThat(redisTemplate.hasKey(refreshKey)).isTrue();
        assertThat(redisTemplate.getExpire(refreshKey)).isPositive().isLessThanOrEqualTo(jwtProperties.refreshTokenTtl().toSeconds());
        assertThat(redisTemplate.getExpire(indexKey)).isPositive().isLessThanOrEqualTo(jwtProperties.refreshTokenTtl().toSeconds());
        assertThat(redisTemplate.opsForHash().get(refreshKey, "deviceInfo")).isEqualTo("x".repeat(128));
        assertThat(redisTemplate.opsForSet().members(indexKey)).contains(refreshToken);

        refreshTokenService.revoke(refreshToken);

        assertThat(redisTemplate.hasKey(refreshKey)).isFalse();
        assertThat(redisTemplate.opsForSet().members(indexKey)).doesNotContain(refreshToken);
        refreshTokenService.revoke(refreshToken);
    }

    @Test
    void registerCanonicalizesEmailAndRejectsDuplicateUsernameBeforeSave() {
        User user = authService.register(new RegisterRequest(" CASEY@Example.COM ", " trader ", "Password1"));

        assertThat(user.email()).isEqualTo("casey@example.com");
        assertThat(user.username()).isEqualTo("trader");
        assertThat(userRepository.findByEmail("casey@example.com")).contains(user);

        assertThatThrownBy(() -> authService.register(new RegisterRequest("other@example.com", "trader", "Password1")))
            .isInstanceOf(DuplicateResourceException.class);
        assertThat(userRepository.findByEmail("other@example.com")).isEmpty();
    }
}
