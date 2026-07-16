package dowob.xyz.stockwebv2.marketdata.consumer;

import dowob.xyz.stockwebv2.common.model.KlineInterval;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 單一資產單一 interval 的當前 bucket OHLC 快照。
 *
 * @param assetId     資產 id
 * @param interval    K-線間隔
 * @param bucketStart 此 bucket 的起始時間 (inclusive)
 * @param open        第一筆 tick 價格
 * @param high        bucket 內最高價
 * @param low         bucket 內最低價
 * @param close       最近一筆 tick 價格
 * @param volume      bucket 內累計成交量 (null 時視為 0)
 *
 * @author Yuan
 * @version 1.0.0
 */
public record KlineBucket(
        Long assetId,
        KlineInterval interval,
        Instant bucketStart,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume
) {
    public KlineBucket {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(interval, "interval must not be null");
        Objects.requireNonNull(bucketStart, "bucketStart must not be null");
        Objects.requireNonNull(open, "open must not be null");
        Objects.requireNonNull(high, "high must not be null");
        Objects.requireNonNull(low, "low must not be null");
        Objects.requireNonNull(close, "close must not be null");
        // volume nullable allowed → treated as 0 in accumulator
    }
}
