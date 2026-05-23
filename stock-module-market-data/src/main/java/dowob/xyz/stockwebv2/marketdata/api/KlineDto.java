package dowob.xyz.stockwebv2.marketdata.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * REST {@code /api/v1/market/{symbol}/klines} 回應的單筆 OHLCV K 線資料。
 * BigDecimal 欄位序列化為 JSON 字串，避免浮點精度損失。
 *
 * @param bucket K 線所屬時間桶的起始時間（UTC）
 * @param open   開盤價，序列化為字串
 * @param high   最高價，序列化為字串
 * @param low    最低價，序列化為字串
 * @param close  收盤價，序列化為字串
 * @param volume 成交量，序列化為字串
 * @author Yuan
 * @version 1.0.0
 */
public record KlineDto(
    @JsonProperty("bucket") Instant bucket,
    @JsonSerialize(using = ToStringSerializer.class) @JsonProperty("open") BigDecimal open,
    @JsonSerialize(using = ToStringSerializer.class) @JsonProperty("high") BigDecimal high,
    @JsonSerialize(using = ToStringSerializer.class) @JsonProperty("low") BigDecimal low,
    @JsonSerialize(using = ToStringSerializer.class) @JsonProperty("close") BigDecimal close,
    @JsonSerialize(using = ToStringSerializer.class) @JsonProperty("volume") BigDecimal volume
) {}
