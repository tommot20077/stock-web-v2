package dowob.xyz.stockwebv2.marketdata.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * REST {@code /api/v1/market/{symbol}/latest} 與批次端點的單一資產回應。
 * BigDecimal 序列化為 JSON 字串避免精度損失。
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
    @JsonSerialize(using = ToStringSerializer.class) @JsonProperty("price") BigDecimal price,
    @JsonSerialize(using = ToStringSerializer.class) @JsonProperty("volume") BigDecimal volume,
    @JsonProperty("time") Instant time
) {}
