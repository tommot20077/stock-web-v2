package dowob.xyz.stockwebv2.user.service;

import dowob.xyz.stockwebv2.common.model.CurrencyCode;
import dowob.xyz.stockwebv2.infrastructure.user.UserPreferenceFacade;
import dowob.xyz.stockwebv2.user.repository.UserRepository;

import org.springframework.stereotype.Service;

/**
 * {@link UserPreferenceFacade} 的實作，部署於 stock-module-user。
 *
 * <p>是其他模組讀取 {@code users.base_currency} 的唯一合法入口。
 * 查無使用者時回 {@link CurrencyCode#TWD}，與 V14 的 DB 預設一致，
 * 避免已認證請求因此回 500。
 *
 * @author Yuan
 * @version 1.0
 */
@Service
public class UserPreferenceFacadeImpl implements UserPreferenceFacade {

    private final UserRepository userRepository;

    public UserPreferenceFacadeImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public CurrencyCode findBaseCurrency(Long userId) {
        return userRepository.findBaseCurrency(userId).orElse(CurrencyCode.TWD);
    }
}
