package dowob.xyz.stockwebv2.user.service;

import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.DuplicateResourceException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.error.ResourceNotFoundException;
import dowob.xyz.stockwebv2.common.model.UserStatus;
import dowob.xyz.stockwebv2.user.api.RegisterRequest;
import dowob.xyz.stockwebv2.user.domain.User;
import dowob.xyz.stockwebv2.user.repository.UserRepository;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final LoginAttemptService loginAttemptService;

    public AuthService(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        RefreshTokenService refreshTokenService,
        LoginAttemptService loginAttemptService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
        this.loginAttemptService = loginAttemptService;
    }

    /**
     * 執行即時登出：遞增 DB token version（權威來源）並同步至 Redis，使該使用者所有既有
     * access token 立即失效（security.md §5）。Redis 同步失敗時整筆交易回滾，維持 DB 與
     * Redis 版本一致。
     *
     * <p><b>語意:登出即「登出所有裝置」。</b>token version 為每使用者單一的全域計數,遞增後
     * 該使用者在所有裝置 / 分頁的既有 access token 一併失效,其餘 refresh token 也會因版本
     * 不符而在下次換發時作廢。此為刻意的安全設計(單處登出即全面撤銷),而非只登出當前 session;
     * 若日後需支援「僅登出當前裝置」,須改為 per-session 撤銷(如以 session id / jti 建立黑名單)。</p>
     *
     * @param userId 登出的使用者 id
     */
    @Transactional
    public void logout(Long userId) {
        int newVersion = userRepository.incrementTokenVersion(userId);
        refreshTokenService.updateAuthTokenVersion(userId, newVersion);
    }

    /**
     * 依內部 id 取得使用者；不存在時拋出 {@link ResourceNotFoundException}。
     *
     * <p>提供給 Controller 使用，使其毋須直接相依 Repository（architecture.md §DDD-Lite）。</p>
     *
     * @param userId 使用者內部 id
     * @return 使用者
     */
    @Transactional(readOnly = true)
    public User requireById(Long userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("user"));
    }

    /**
     * 解除指定使用者的登入失敗鎖定（ADMIN 操作，security.md §15）。
     *
     * @param uuid 使用者對外 UUID
     */
    @Transactional
    public void unlockByUuid(UUID uuid) {
        User user = userRepository.findByUuid(uuid)
            .orElseThrow(() -> new ResourceNotFoundException("user"));
        loginAttemptService.reset(user.id());
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
        loginAttemptService.assertNotLocked(user.id());
        if (!passwordEncoder.matches(password, user.passwordHash())) {
            loginAttemptService.recordFailure(user.id());
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS, ErrorCode.AUTH_INVALID_CREDENTIALS.defaultMessage());
        }
        if (user.status() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS, ErrorCode.AUTH_INVALID_CREDENTIALS.defaultMessage());
        }
        loginAttemptService.reset(user.id());
        return user;
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeUsername(String username) {
        return username.trim();
    }
}
