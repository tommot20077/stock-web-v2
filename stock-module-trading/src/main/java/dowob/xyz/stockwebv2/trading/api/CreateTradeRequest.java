package dowob.xyz.stockwebv2.trading.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 上限約束(2026-07-17 裁決):quantity ≤ 10^9、price ≤ 10^6,
 * 確保 quantity × price 乘積 < 10^16,落在 NUMERIC(24,8) 可表示範圍,
 * 衍生欄位(holdings.total_quantity/realized_pnl)不會觸發 DB numeric overflow。
 */
public record CreateTradeRequest(
    @NotBlank String symbol,
    @NotBlank String type,
    @NotNull @DecimalMin(value = "0.00000001") @DecimalMax(value = "1000000000")
    @Digits(integer = 10, fraction = 8) BigDecimal quantity,
    @NotNull @DecimalMin(value = "0.00000001") @DecimalMax(value = "1000000")
    @Digits(integer = 7, fraction = 8) BigDecimal price,
    @DecimalMin(value = "0.0") BigDecimal fee,
    @Size(max = 500) String note,
    OffsetDateTime executedAt
) {
}
