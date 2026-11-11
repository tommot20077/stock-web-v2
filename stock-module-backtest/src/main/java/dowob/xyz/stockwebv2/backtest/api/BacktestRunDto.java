package dowob.xyz.stockwebv2.backtest.api;

import dowob.xyz.stockwebv2.common.api.ApiError;
import dowob.xyz.stockwebv2.infrastructure.web.json.DecimalString;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record BacktestRunDto(
    String id,
    String strategyId,
    String label,
    String symbol,
    String period,
    @DecimalString BigDecimal initialCapital,
    String currency,
    String status,
    @DecimalString BigDecimal progress,
    OffsetDateTime createdAt,
    OffsetDateTime startedAt,
    OffsetDateTime completedAt,
    ApiError error
) {
}
