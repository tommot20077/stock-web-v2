package dowob.xyz.stockwebv2.trading.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record HoldingDto(
    String assetId,
    String symbol,
    String assetName,
    BigDecimal totalQuantity,
    BigDecimal avgCost,
    BigDecimal costBasis,
    BigDecimal marketPrice,
    BigDecimal marketValue,
    BigDecimal realizedPnl,
    BigDecimal unrealizedPnl,
    BigDecimal roi,
    OffsetDateTime priceTime,
    OffsetDateTime lastUpdated
) {
}
