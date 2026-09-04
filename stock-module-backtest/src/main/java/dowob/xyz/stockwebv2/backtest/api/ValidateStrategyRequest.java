package dowob.xyz.stockwebv2.backtest.api;

import jakarta.validation.constraints.NotBlank;

public record ValidateStrategyRequest(@NotBlank String strategyCode) {
}
