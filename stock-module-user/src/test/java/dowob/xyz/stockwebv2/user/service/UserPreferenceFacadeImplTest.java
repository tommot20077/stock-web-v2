package dowob.xyz.stockwebv2.user.service;

import dowob.xyz.stockwebv2.common.model.CurrencyCode;
import dowob.xyz.stockwebv2.user.repository.UserRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link UserPreferenceFacadeImpl} 的單元測試：基準幣讀取與查無使用者時的預設值。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("使用者偏好 Facade")
class UserPreferenceFacadeImplTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserPreferenceFacadeImpl facade = new UserPreferenceFacadeImpl(userRepository);

    @Test
    @DisplayName("repository 有值時回傳該使用者的基準幣")
    void findBaseCurrencyReturnsStoredCurrency() {
        when(userRepository.findBaseCurrency(7L)).thenReturn(Optional.of(CurrencyCode.USD));

        assertThat(facade.findBaseCurrency(7L)).isEqualTo(CurrencyCode.USD);
    }

    @Test
    @DisplayName("查無使用者時回傳 TWD，與資料庫預設值一致")
    void findBaseCurrencyDefaultsToTwdWhenUserMissing() {
        when(userRepository.findBaseCurrency(404L)).thenReturn(Optional.empty());

        assertThat(facade.findBaseCurrency(404L)).isEqualTo(CurrencyCode.TWD);
    }
}
