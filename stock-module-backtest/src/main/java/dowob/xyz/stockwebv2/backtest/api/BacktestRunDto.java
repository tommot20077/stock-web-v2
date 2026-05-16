package dowob.xyz.stockwebv2.backtest.api;

import dowob.xyz.stockwebv2.common.api.ApiError;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record BacktestRunDto(
    String id,
    String strategyId,
    String label,
    String symbol,
    String period,
    BigDecimal initialCapital,
    String currency,
    String status,
    BigDecimal progress,
    OffsetDateTime createdAt,
    OffsetDateTime startedAt,
    OffsetDateTime completedAt,
    ApiError error
) {
}
