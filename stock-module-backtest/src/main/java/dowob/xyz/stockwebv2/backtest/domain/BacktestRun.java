package dowob.xyz.stockwebv2.backtest.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record BacktestRun(
    Long id,
    UUID uuid,
    Long userId,
    Long strategyVersionId,
    BacktestStrategyId strategyId,
    String strategyLabel,
    String strategyCode,
    String symbol,
    BacktestPeriod period,
    BigDecimal initialCapital,
    String currency,
    String benchmark,
    String dataMode,
    BacktestRunStatus status,
    BigDecimal progress,
    String errorCode,
    String errorMessage,
    OffsetDateTime createdAt,
    OffsetDateTime startedAt,
    OffsetDateTime completedAt
) {
}
