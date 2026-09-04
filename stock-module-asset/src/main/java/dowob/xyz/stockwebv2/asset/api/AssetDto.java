package dowob.xyz.stockwebv2.asset.api;

import dowob.xyz.stockwebv2.common.model.AssetType;
import dowob.xyz.stockwebv2.common.model.CurrencyCode;

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
    BigDecimal latestPrice,
    BigDecimal change,
    BigDecimal changePercent,
    String volumeText,
    BigDecimal high,
    BigDecimal low
) {
}
