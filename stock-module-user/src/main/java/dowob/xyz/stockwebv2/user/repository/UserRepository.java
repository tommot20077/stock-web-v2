package dowob.xyz.stockwebv2.user.repository;

import dowob.xyz.stockwebv2.common.model.CurrencyCode;
import dowob.xyz.stockwebv2.user.domain.User;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository {
    Optional<User> findByEmail(String email);
    Optional<User> findByUsername(String username);
    Optional<User> findById(Long id);
    Optional<User> findByUuid(UUID uuid);
    User save(User user);

    /**
     * 遞增指定使用者的 token version，用於即時撤銷其所有 access token（security.md §5）。
     *
     * @param id 使用者主鍵
     * @return 遞增後的新 token version
     */
    int incrementTokenVersion(Long id);

    /**
     * 更新指定使用者的基準幣並回傳更新後的使用者。
     *
     * @param id           使用者主鍵
     * @param baseCurrency 新的基準幣
     * @return 更新後的使用者；使用者不存在時為空
     */
    Optional<User> updateBaseCurrency(Long id, CurrencyCode baseCurrency);
}
