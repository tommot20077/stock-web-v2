package dowob.xyz.stockwebv2.trading.api;

import java.math.BigDecimal;

public record PortfolioSummaryDto(
    BigDecimal totalMarketValue,
    BigDecimal totalCostBasis,
    BigDecimal realizedPnl,
    BigDecimal unrealizedPnl,
    BigDecimal totalPnl,
    BigDecimal roi,
    int holdingCount
) {
}
