package dowob.xyz.stockwebv2.user.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code PATCH /api/v1/me/preferences} 的請求本文。
 *
 * <p>{@code baseCurrency} 刻意宣告為 {@link String} 而非 {@code CurrencyCode}：Jackson 對 enum
 * 反序列化失敗會丟 {@code HttpMessageNotReadableException}，使「不支援的幣別」退化成沒有
 * {@code fields} 的通用 400，前端無從指名欄位。改由 service 層白名單解析，錯誤才能指名欄位。</p>
 *
 * @param baseCurrency 要設定的基準幣代碼，如 {@code "USD"}；大小寫與前後空白由 service 正規化
 * @author Yuan
 * @version 1.0
 */
public record UpdatePreferencesRequest(
    @NotBlank(message = "must not be blank") @Size(max = 10) String baseCurrency
) {
}
