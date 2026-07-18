package dowob.xyz.stockwebv2.user.service;

import dowob.xyz.stockwebv2.common.error.DuplicateResourceException;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.model.Role;
import dowob.xyz.stockwebv2.common.model.UserStatus;
import dowob.xyz.stockwebv2.user.api.RegisterRequest;
import dowob.xyz.stockwebv2.user.domain.User;
import dowob.xyz.stockwebv2.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuthServiceTest {

    @Test
    void registerCreatesActiveUserWithHashedPassword() {
        InMemoryUserRepository repository = new InMemoryUserRepository();
        AuthService service = new AuthService(repository, new BCryptPasswordEncoder(10), mock(RefreshTokenService.class), mock(LoginAttemptService.class));

        User user = service.register(new RegisterRequest("yuan@example.com", "yuan", "Password1"));

        assertThat(user.id()).isEqualTo(1L);
        assertThat(user.email()).isEqualTo("yuan@example.com");
        assertThat(user.role()).isEqualTo(Role.USER);
        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.tokenVersion()).isEqualTo(1);
        assertThat(user.passwordHash()).isNotEqualTo("Password1");
    }

    @Test
    void registerRejectsDuplicateEmail() {
        InMemoryUserRepository repository = new InMemoryUserRepository();
        AuthService service = new AuthService(repository, new BCryptPasswordEncoder(10), mock(RefreshTokenService.class), mock(LoginAttemptService.class));
        service.register(new RegisterRequest("yuan@example.com", "yuan", "Password1"));

        assertThatThrownBy(() -> service.register(new RegisterRequest("yuan@example.com", "yuan2", "Password1")))
            .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void registerRejectsDuplicateEmailWithCaseAndWhitespaceVariants() {
        InMemoryUserRepository repository = new InMemoryUserRepository();
        AuthService service = new AuthService(repository, new BCryptPasswordEncoder(10), mock(RefreshTokenService.class), mock(LoginAttemptService.class));
        service.register(new RegisterRequest(" yuan@example.com ", "yuan", "Password1"));

        assertThatThrownBy(() -> service.register(new RegisterRequest("YUAN@example.com", "yuan2", "Password1")))
            .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void registerRejectsDuplicateUsername() {
        InMemoryUserRepository repository = new InMemoryUserRepository();
        AuthService service = new AuthService(repository, new BCryptPasswordEncoder(10), mock(RefreshTokenService.class), mock(LoginAttemptService.class));
        service.register(new RegisterRequest("yuan@example.com", " yuan ", "Password1"));

        assertThatThrownBy(() -> service.register(new RegisterRequest("yuan2@example.com", "yuan", "Password1")))
            .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void registerStoresNormalizedEmailAndTrimmedUsername() {
        InMemoryUserRepository repository = new InMemoryUserRepository();
        AuthService service = new AuthService(repository, new BCryptPasswordEncoder(10), mock(RefreshTokenService.class), mock(LoginAttemptService.class));

        User user = service.register(new RegisterRequest(" Yuan@Example.COM ", " yuan ", "Password1"));

        assertThat(user.email()).isEqualTo("yuan@example.com");
        assertThat(user.username()).isEqualTo("yuan");
    }

    @Test
    void verifyCredentialsNormalizesEmailForLookup() {
        InMemoryUserRepository repository = new InMemoryUserRepository();
        AuthService service = new AuthService(repository, new BCryptPasswordEncoder(10), mock(RefreshTokenService.class), mock(LoginAttemptService.class));
        service.register(new RegisterRequest("yuan@example.com", "yuan", "Password1"));

        User user = service.verifyCredentials(" YUAN@example.com ", "Password1");

        assertThat(user.email()).isEqualTo("yuan@example.com");
    }

    @Test
    void verifyCredentialsRejectsWrongPassword() {
        InMemoryUserRepository repository = new InMemoryUserRepository();
        AuthService service = new AuthService(repository, new BCryptPasswordEncoder(10), mock(RefreshTokenService.class), mock(LoginAttemptService.class));
        service.register(new RegisterRequest("yuan@example.com", "yuan", "Password1"));

        assertThatThrownBy(() -> service.verifyCredentials("yuan@example.com", "bad"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    @Test
    void verifyCredentialsRejectsSuspendedUserWithCorrectPassword() {
        InMemoryUserRepository repository = new InMemoryUserRepository();
        AuthService service = new AuthService(repository, new BCryptPasswordEncoder(10), mock(RefreshTokenService.class), mock(LoginAttemptService.class));
        User user = service.register(new RegisterRequest("yuan@example.com", "yuan", "Password1"));
        repository.save(userWithStatus(user, UserStatus.SUSPENDED));

        assertThatThrownBy(() -> service.verifyCredentials("yuan@example.com", "Password1"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    @Test
    void verifyCredentialsRejectsDeletedUserWithCorrectPassword() {
        InMemoryUserRepository repository = new InMemoryUserRepository();
        AuthService service = new AuthService(repository, new BCryptPasswordEncoder(10), mock(RefreshTokenService.class), mock(LoginAttemptService.class));
        User user = service.register(new RegisterRequest("yuan@example.com", "yuan", "Password1"));
        repository.save(userWithStatus(user, UserStatus.DELETED));

        assertThatThrownBy(() -> service.verifyCredentials("yuan@example.com", "Password1"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    @Test
    @DisplayName("登出遞增 token version 並同步至 Redis")
    void logoutIncrementsTokenVersionAndSyncsRedis() {
        InMemoryUserRepository repository = new InMemoryUserRepository();
        RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
        AuthService service = new AuthService(repository, new BCryptPasswordEncoder(10), refreshTokenService, mock(LoginAttemptService.class));
        User user = service.register(new RegisterRequest("yuan@example.com", "yuan", "Password1"));

        service.logout(user.id());

        assertThat(repository.findById(user.id()).orElseThrow().tokenVersion()).isEqualTo(2);
        verify(refreshTokenService).updateAuthTokenVersion(user.id(), 2);
    }

    private static User userWithStatus(User user, UserStatus status) {
        return new User(
            user.id(),
            user.uuid(),
            user.email(),
            user.username(),
            user.passwordHash(),
            user.role(),
            status,
            user.tokenVersion(),
            user.createdAt(),
            user.updatedAt()
        );
    }

    static class InMemoryUserRepository implements UserRepository {
        private final Map<Long, User> byId = new ConcurrentHashMap<>();
        private long seq = 1L;

        @Override
        public Optional<User> findByEmail(String email) {
            return byId.values().stream().filter(user -> user.email().equals(email)).findFirst();
        }

        @Override
        public Optional<User> findByUsername(String username) {
            return byId.values().stream().filter(user -> user.username().equals(username)).findFirst();
        }

        @Override
        public Optional<User> findById(Long id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<User> findByUuid(java.util.UUID uuid) {
            return byId.values().stream().filter(user -> uuid.equals(user.uuid())).findFirst();
        }

        @Override
        public User save(User user) {
            Long id = user.id() == null ? seq++ : user.id();
            User saved = user.withId(id);
            byId.put(id, saved);
            return saved;
        }

        @Override
        public int incrementTokenVersion(Long id) {
            User current = byId.get(id);
            User updated = new User(
                current.id(),
                current.uuid(),
                current.email(),
                current.username(),
                current.passwordHash(),
                current.role(),
                current.status(),
                current.tokenVersion() + 1,
                current.createdAt(),
                current.updatedAt()
            );
            byId.put(id, updated);
            return updated.tokenVersion();
        }
    }
}
