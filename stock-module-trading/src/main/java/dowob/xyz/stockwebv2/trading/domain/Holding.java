package dowob.xyz.stockwebv2.trading.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record Holding(
    Long id,
    Long userId,
    Long assetId,
    BigDecimal totalQuantity,
    BigDecimal avgCost,
    BigDecimal realizedPnl,
    int version,
    OffsetDateTime lastUpdated
) {
}
