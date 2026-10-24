package dowob.xyz.stockwebv2.user.service;

import dowob.xyz.stockwebv2.common.error.FieldValidationException;
import dowob.xyz.stockwebv2.common.error.ResourceNotFoundException;
import dowob.xyz.stockwebv2.common.model.CurrencyCode;
import dowob.xyz.stockwebv2.user.api.MeResponse;
import dowob.xyz.stockwebv2.user.domain.User;
import dowob.xyz.stockwebv2.user.service.AuthServiceTest.InMemoryUserRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link UserPreferenceService} 的單元測試：基準幣白名單解析、正規化與不回射輸入。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("使用者偏好服務")
class UserPreferenceServiceTest {

    private final InMemoryUserRepository repository = new InMemoryUserRepository();
    private final UserPreferenceService service = new UserPreferenceService(repository);

    private Long registeredUserId() {
        return repository.save(User.newUser("pref@example.com", "pref", "hash")).id();
    }

    @Test
    @DisplayName("小寫與前後空白會被正規化後寫入")
    void updateBaseCurrencyNormalizesCaseAndWhitespace() {
        Long userId = registeredUserId();

        MeResponse response = service.updateBaseCurrency(userId, "  usd ");

        assertThat(response.baseCurrency()).isEqualTo(CurrencyCode.USD);
        assertThat(repository.findById(userId).orElseThrow().baseCurrency()).isEqualTo(CurrencyCode.USD);
    }

    @Test
    @DisplayName("不支援的幣別回 fields 指名欄位且訊息不含輸入值")
    void updateBaseCurrencyRejectsUnsupportedCurrencyWithoutEcho() {
        Long userId = registeredUserId();

        assertThatThrownBy(() -> service.updateBaseCurrency(userId, "GBP"))
            .isInstanceOfSatisfying(FieldValidationException.class, exception -> {
                assertThat(exception.fields()).containsOnly(java.util.Map.entry("baseCurrency", "unsupported currency"));
                assertThat(exception.getMessage()).doesNotContain("GBP");
            });
        assertThat(repository.findById(userId).orElseThrow().baseCurrency()).isEqualTo(CurrencyCode.TWD);
    }

    @Test
    @DisplayName("空白或 null 回 must not be blank")
    void updateBaseCurrencyRejectsBlankAndNull() {
        Long userId = registeredUserId();

        for (String blank : new String[] {"  ", "", null}) {
            assertThatThrownBy(() -> service.updateBaseCurrency(userId, blank))
                .isInstanceOfSatisfying(FieldValidationException.class, exception ->
                    assertThat(exception.fields()).containsOnly(java.util.Map.entry("baseCurrency", "must not be blank")));
        }
    }

    @Test
    @DisplayName("使用者不存在時回 ResourceNotFoundException")
    void updateBaseCurrencyThrowsWhenUserMissing() {
        assertThatThrownBy(() -> service.updateBaseCurrency(999L, "USD"))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
