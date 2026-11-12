package dowob.xyz.stockwebv2.marketdata.api;

import dowob.xyz.stockwebv2.infrastructure.web.json.DecimalString;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * REST {@code /api/v1/market/{symbol}/latest} 與批次端點的單一資產回應。
 * BigDecimal 以 {@link DecimalString} 序列化為一般記號、保留 scale 的 JSON 字串，避免精度損失（Phase 5 D-05）。
 *
 * @param symbol 資產代號
 * @param price  最新成交價，序列化為字串
 * @param volume 成交量，序列化為字串，可為 null
 * @param time   tick 時間戳（UTC）
 * @author Yuan
 * @version 1.0.0
 */
public record LatestPriceDto(
    @JsonProperty("symbol") String symbol,
    @DecimalString @JsonProperty("price") BigDecimal price,
    @DecimalString @JsonProperty("volume") BigDecimal volume,
    @JsonProperty("time") Instant time
) {}
