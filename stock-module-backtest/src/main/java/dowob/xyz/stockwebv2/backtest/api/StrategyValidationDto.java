package dowob.xyz.stockwebv2.backtest.api;

import java.util.List;

public record StrategyValidationDto(boolean valid, String normalizedName, List<String> warnings) {
    public StrategyValidationDto {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
