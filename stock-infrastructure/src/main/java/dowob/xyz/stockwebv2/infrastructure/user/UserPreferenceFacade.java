package dowob.xyz.stockwebv2.infrastructure.user;

import dowob.xyz.stockwebv2.common.model.CurrencyCode;

/**
 * 提供其他 module 查詢使用者偏好的 facade。
 *
 * <p>供其他模組（trading）取得使用者基準幣；其他模組不得以 SQL 讀取 {@code users}，
 * 也不得相依 stock-module-user（架構審查 H-1）。實作類別 {@code UserPreferenceFacadeImpl}
 * 位於 stock-module-user，由 Spring 容器注入。
 *
 * @author Yuan
 * @version 1.0
 */
public interface UserPreferenceFacade {

    /**
     * 取得指定使用者的基準幣。
     *
     * <p>{@code userId} 必須是呼叫端已由 {@code AuthenticatedUserResolver} 解析出的已認證身分，
     * 本介面不做授權，也只回傳幣別代碼、不回傳其他使用者欄位。
     * 每次呼叫直接讀取 {@code users.base_currency}，不經快取，因此 PATCH 後立即生效。
     *
     * @param userId 使用者主鍵，由呼叫端保證為已認證身分
     * @return 使用者基準幣；查無使用者時回 {@link CurrencyCode#TWD}（與 V14 的 DB 預設值一致）
     */
    CurrencyCode findBaseCurrency(Long userId);
}
