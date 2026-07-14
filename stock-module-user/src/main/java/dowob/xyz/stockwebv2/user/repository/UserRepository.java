package dowob.xyz.stockwebv2.user.repository;

import dowob.xyz.stockwebv2.user.domain.User;

import java.util.Optional;

public interface UserRepository {
    Optional<User> findByEmail(String email);
    Optional<User> findByUsername(String username);
    Optional<User> findById(Long id);
    User save(User user);

    /**
     * 遞增指定使用者的 token version，用於即時撤銷其所有 access token（security.md §5）。
     *
     * @param id 使用者主鍵
     * @return 遞增後的新 token version
     */
    int incrementTokenVersion(Long id);
}
