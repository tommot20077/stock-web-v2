package dowob.xyz.stockwebv2.trading.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record CreateTradeRequest(
    @NotBlank String symbol,
    @NotBlank String type,
    @NotNull @DecimalMin(value = "0.00000001") BigDecimal quantity,
    @NotNull @DecimalMin(value = "0.00000001") BigDecimal price,
    @DecimalMin(value = "0.0") BigDecimal fee,
    @Size(max = 500) String note,
    OffsetDateTime executedAt
) {
}
