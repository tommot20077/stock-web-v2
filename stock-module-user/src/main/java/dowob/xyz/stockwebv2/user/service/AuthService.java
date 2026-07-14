package dowob.xyz.stockwebv2.user.service;

import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.DuplicateResourceException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.model.UserStatus;
import dowob.xyz.stockwebv2.user.api.RegisterRequest;
import dowob.xyz.stockwebv2.user.domain.User;
import dowob.xyz.stockwebv2.user.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class AuthService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, RefreshTokenService refreshTokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
    }

    /**
     * 執行即時登出：遞增 DB token version（權威來源）並同步至 Redis，使該使用者所有既有
     * access token 立即失效（security.md §5）。Redis 同步失敗時整筆交易回滾，維持 DB 與
     * Redis 版本一致。
     *
     * @param userId 登出的使用者 id
     */
    @Transactional
    public void logout(Long userId) {
        int newVersion = userRepository.incrementTokenVersion(userId);
        refreshTokenService.updateAuthTokenVersion(userId, newVersion);
    }

    @Transactional
    public User register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        String username = normalizeUsername(request.username());
        userRepository.findByEmail(email).ifPresent(existing -> {
            throw new DuplicateResourceException("email");
        });
        userRepository.findByUsername(username).ifPresent(existing -> {
            throw new DuplicateResourceException("username");
        });
        User user = User.newUser(email, username, passwordEncoder.encode(request.password()));
        return userRepository.save(user);
    }

    public User verifyCredentials(String email, String password) {
        User user = userRepository.findByEmail(normalizeEmail(email))
            .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS, ErrorCode.AUTH_INVALID_CREDENTIALS.defaultMessage()));
        if (user.status() != UserStatus.ACTIVE || !passwordEncoder.matches(password, user.passwordHash())) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS, ErrorCode.AUTH_INVALID_CREDENTIALS.defaultMessage());
        }
        return user;
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeUsername(String username) {
        return username.trim();
    }
}
