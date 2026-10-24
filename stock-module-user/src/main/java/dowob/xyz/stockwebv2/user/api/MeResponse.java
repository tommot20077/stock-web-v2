package dowob.xyz.stockwebv2.user.api;

import dowob.xyz.stockwebv2.common.model.CurrencyCode;
import dowob.xyz.stockwebv2.common.model.Role;
import dowob.xyz.stockwebv2.common.model.UserStatus;

import java.util.UUID;

/**
 * {@code GET /api/v1/me} 的回應，亦內嵌於 login / register / refresh 回應。
 *
 * @param id           使用者主鍵
 * @param uuid         對外識別碼
 * @param email        登入 email
 * @param username     使用者名稱
 * @param role         角色
 * @param status       帳號狀態
 * @param baseCurrency 使用者基準幣，JSON 序列化為 enum 常數名（如 {@code "TWD"}）
 * @author Yuan
 * @version 1.0
 */
public record MeResponse(Long id, UUID uuid, String email, String username, Role role, UserStatus status,
                         CurrencyCode baseCurrency) {
}
