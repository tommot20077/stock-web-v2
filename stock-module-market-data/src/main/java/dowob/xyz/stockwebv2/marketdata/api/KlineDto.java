package dowob.xyz.stockwebv2.marketdata.api;

import dowob.xyz.stockwebv2.infrastructure.web.json.DecimalString;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * REST {@code /api/v1/market/{symbol}/klines} 回應的單筆 OHLCV K 線資料。
 * BigDecimal 欄位以 {@link DecimalString} 序列化為一般記號、保留 scale 的 JSON 字串，避免浮點精度損失（Phase 5 D-05）。
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
    @DecimalString @JsonProperty("open") BigDecimal open,
    @DecimalString @JsonProperty("high") BigDecimal high,
    @DecimalString @JsonProperty("low") BigDecimal low,
    @DecimalString @JsonProperty("close") BigDecimal close,
    @DecimalString @JsonProperty("volume") BigDecimal volume
) {}
