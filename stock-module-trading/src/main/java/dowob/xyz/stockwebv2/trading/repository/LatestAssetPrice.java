package dowob.xyz.stockwebv2.trading.repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record LatestAssetPrice(BigDecimal price, OffsetDateTime priceTime) {
}
