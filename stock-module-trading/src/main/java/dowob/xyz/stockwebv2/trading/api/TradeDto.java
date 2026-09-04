package dowob.xyz.stockwebv2.trading.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record TradeDto(
    String id,
    String symbol,
    String type,
    BigDecimal quantity,
    BigDecimal price,
    BigDecimal fee,
    String note,
    OffsetDateTime executedAt,
    OffsetDateTime createdAt
) {
}
