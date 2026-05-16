package dowob.xyz.stockwebv2.user.repository;

import dowob.xyz.stockwebv2.user.domain.User;

import java.util.Optional;

public interface UserRepository {
    Optional<User> findByEmail(String email);
    Optional<User> findByUsername(String username);
    Optional<User> findById(Long id);
    User save(User user);
}
