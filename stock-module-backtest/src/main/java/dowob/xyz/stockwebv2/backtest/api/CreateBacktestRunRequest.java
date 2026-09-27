package dowob.xyz.stockwebv2.backtest.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateBacktestRunRequest(
    @NotBlank String strategyId,
    String strategyCode,
    @NotBlank @Size(max = 32) String symbol,
    @NotBlank String period,
    @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal initialCapital,
    @NotBlank String currency,
    @NotBlank String benchmark,
    @NotBlank String dataMode
) {
}
