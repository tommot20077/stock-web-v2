package dowob.xyz.stockwebv2.trading.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TradeTransaction(
    Long id,
    UUID uuid,
    Long userId,
    Long assetId,
    String symbol,
    TradeType type,
    BigDecimal quantity,
    BigDecimal price,
    BigDecimal fee,
    String note,
    OffsetDateTime executedAt,
    OffsetDateTime createdAt,
    String idempotencyKey
) {
}
