package dowob.xyz.stockwebv2.asset.api;

import dowob.xyz.stockwebv2.common.model.AssetType;
import dowob.xyz.stockwebv2.common.model.CurrencyCode;
import dowob.xyz.stockwebv2.infrastructure.web.json.DecimalString;

import java.math.BigDecimal;
import java.util.UUID;

public record AssetDto(
    UUID uuid,
    String symbol,
    String name,
    AssetType assetType,
    String market,
    CurrencyCode currency,
    String sector,
    boolean tradeable,
    @DecimalString BigDecimal latestPrice,
    @DecimalString BigDecimal change,
    @DecimalString BigDecimal changePercent,
    String volumeText,
    @DecimalString BigDecimal high,
    @DecimalString BigDecimal low
) {
}
