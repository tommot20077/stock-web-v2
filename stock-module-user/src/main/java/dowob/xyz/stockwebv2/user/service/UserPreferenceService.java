package dowob.xyz.stockwebv2.user.service;

import dowob.xyz.stockwebv2.common.error.FieldValidationException;
import dowob.xyz.stockwebv2.common.error.ResourceNotFoundException;
import dowob.xyz.stockwebv2.common.model.CurrencyCode;
import dowob.xyz.stockwebv2.user.api.MeResponse;
import dowob.xyz.stockwebv2.user.repository.UserRepository;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

/**
 * 使用者偏好設定（目前僅基準幣，PORT-09 / D-02）。
 *
 * <p>白名單解析在此層完成，錯誤訊息與 {@code fields} 一律為靜態字串，不回射使用者輸入。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Service
public class UserPreferenceService {
    private final UserRepository userRepository;

    /**
     * @param userRepository 使用者儲存庫
     */
    public UserPreferenceService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * 修改指定使用者的基準幣。
     *
     * @param userId    使用者主鍵，由已驗證的身分解析而來，不接受請求本文提供
     * @param requested 請求的幣別代碼；trim 並轉大寫後與 {@link CurrencyCode} 比對
     * @return 更新後的 {@link MeResponse}
     * @throws FieldValidationException  幣別為空白，或不在支援清單內（{@code fields.baseCurrency}）
     * @throws ResourceNotFoundException 使用者不存在
     */
    @Transactional
    public MeResponse updateBaseCurrency(Long userId, String requested) {
        CurrencyCode baseCurrency = parse(requested);
        return userRepository.updateBaseCurrency(userId, baseCurrency)
            .orElseThrow(() -> new ResourceNotFoundException("user"))
            .toMeResponse();
    }

    private CurrencyCode parse(String requested) {
        if (StringUtils.isBlank(requested)) {
            throw new FieldValidationException("baseCurrency is required", Map.of("baseCurrency", "must not be blank"));
        }
        String normalized = requested.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(CurrencyCode.values())
            .filter(code -> code.name().equals(normalized))
            .findFirst()
            .orElseThrow(() -> new FieldValidationException("Unsupported base currency",
                Map.of("baseCurrency", "unsupported currency")));
    }
}
