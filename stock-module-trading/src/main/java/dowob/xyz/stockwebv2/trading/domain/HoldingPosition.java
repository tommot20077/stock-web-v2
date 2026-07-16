package dowob.xyz.stockwebv2.trading.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record HoldingPosition(
    Long holdingId,
    Long userId,
    Long assetId,
    UUID assetUuid,
    String symbol,
    String assetName,
    BigDecimal totalQuantity,
    BigDecimal avgCost,
    BigDecimal realizedPnl,
    int version,
    OffsetDateTime lastUpdated
) {

    public Holding holding() {
        return new Holding(holdingId, userId, assetId, totalQuantity, avgCost, realizedPnl, version, lastUpdated);
    }
}
