package dowob.xyz.stockwebv2.infrastructure.web;

import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.core.Authentication;

/**
 * 從 Spring Security 的 {@link Authentication} 取出內部 userId。
 *
 * <p>JWT 的 subject 存的是內部 userId 的字串形式，controller 幾乎都需要它。這段邏輯原本在四個
 * controller 各有一份逐字相同的私有方法（trading / backtest / auth / ws-ticket），
 * 且四份都沒有測試 —— 而它決定「認證主體轉不成 userId 時要拒絕」，漏掉就是把
 * {@link NumberFormatException} 放到 catch-all 變成 500。
 *
 * @author Yuan
 * @version 1.0.0
 */
public final class AuthenticatedUserResolver {

    private AuthenticatedUserResolver() {
    }

    /**
     * 取出當前請求的 userId。
     *
     * @param authentication Spring Security 注入的認證資訊，可為 null（未認證）
     * @return 內部 userId
     * @throws BusinessException 未認證、主體為空白、或主體不是數字時丟 AUTH_INVALID_CREDENTIALS
     */
    public static Long resolve(Authentication authentication) {
        if (authentication == null || StringUtils.isBlank(authentication.getName())) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS, ErrorCode.AUTH_INVALID_CREDENTIALS.defaultMessage());
        }
        try {
            return Long.valueOf(authentication.getName());
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS, ErrorCode.AUTH_INVALID_CREDENTIALS.defaultMessage());
        }
    }
}
