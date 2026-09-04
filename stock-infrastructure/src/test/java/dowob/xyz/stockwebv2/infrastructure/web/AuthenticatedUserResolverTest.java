package dowob.xyz.stockwebv2.infrastructure.web;

import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AuthenticatedUserResolver} 單元測試。
 *
 * <p>這段邏輯原本在四個 controller 各有一份逐字相同的私有方法（trading / backtest / auth / ws-ticket），
 * 抽出來的目的不只是去重：四份都沒有測試，而它決定「認證主體轉不成 userId 時要拒絕而不是往下走」。
 *
 * @author Yuan
 * @version 1.0.0
 */
@DisplayName("認證主體解析 userId")
class AuthenticatedUserResolverTest {

    @Test
    @DisplayName("認證主體為數字字串時回傳 userId")
    void resolvesNumericPrincipal() {
        assertThat(AuthenticatedUserResolver.resolve(authentication("42"))).isEqualTo(42L);
    }

    @Test
    @DisplayName("未認證（null）時丟 AUTH_INVALID_CREDENTIALS，不回傳 null 讓呼叫端誤用")
    void rejectsMissingAuthentication() {
        assertThatThrownBy(() -> AuthenticatedUserResolver.resolve(null))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    @Test
    @DisplayName("認證主體為空白時丟 AUTH_INVALID_CREDENTIALS")
    void rejectsBlankPrincipal() {
        assertThatThrownBy(() -> AuthenticatedUserResolver.resolve(authentication("   ")))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    @Test
    @DisplayName("認證主體不是數字時丟 AUTH_INVALID_CREDENTIALS，而不是 NumberFormatException 變成 500")
    void rejectsNonNumericPrincipal() {
        assertThatThrownBy(() -> AuthenticatedUserResolver.resolve(authentication("not-a-number")))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    /**
     * @param principal 認證主體名稱
     * @return 帶該主體的已認證 token
     */
    private Authentication authentication(String principal) {
        return new UsernamePasswordAuthenticationToken(principal, "n/a", List.of());
    }
}
