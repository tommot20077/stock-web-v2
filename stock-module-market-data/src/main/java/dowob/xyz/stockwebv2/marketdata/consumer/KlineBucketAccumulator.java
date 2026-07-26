package dowob.xyz.stockwebv2.marketdata.consumer;

import dowob.xyz.stockwebv2.common.model.KlineInterval;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 維護每 (assetId, interval) 的當前 bucket OHLC 狀態，thread-safe (per-key CAS)。
 *
 * <p>每筆 tick 餵入：若仍在同一 bucket → 更新 high/low/close/volume；跨 bucket
 * → reset open=high=low=close=本筆 price，volume=本筆 volume。
 *
 * <p>{@link ConcurrentMap#compute} 提供 per-key atomic 語意，避免需要額外鎖定。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Component
public class KlineBucketAccumulator {

    private static final List<KlineInterval> ALL_INTERVALS = List.of(
            KlineInterval.ONE_MINUTE,
            KlineInterval.FIVE_MINUTES,
            KlineInterval.FIFTEEN_MINUTES,
            KlineInterval.ONE_HOUR,
            KlineInterval.ONE_DAY
    );

    private final ConcurrentMap<Key, KlineBucket> state = new ConcurrentHashMap<>();

    /**
     * 餵入一筆 tick，針對 ALL_INTERVALS 各更新一次，回傳更新後的 5 個 KlineBucket。
     *
     * @param assetId tick 的 asset id，不可為 null
     * @param time    tick 時間，不可為 null
     * @param price   tick 價格，不可為 null
     * @param volume  tick 成交量（可為 null，視為 0）
     * @return 5 個 interval 的當前 bucket 快照（順序固定：1m/5m/15m/1h/1d）
     */
    public List<KlineBucket> updateAndSnapshot(Long assetId, Instant time, BigDecimal price, BigDecimal volume) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(time, "time must not be null");
        Objects.requireNonNull(price, "price must not be null");
        BigDecimal vol = (volume == null) ? BigDecimal.ZERO : volume;

        List<KlineBucket> result = new ArrayList<>(ALL_INTERVALS.size());
        for (KlineInterval interval : ALL_INTERVALS) {
            Instant bucketStart = bucketStart(time, interval.duration().toSeconds());
            Key key = new Key(assetId, interval);
            KlineBucket updated = state.compute(key, (k, prev) -> {
                if (prev == null || !prev.bucketStart().equals(bucketStart)) {
                    // 新 bucket — 重置所有 OHLC 為此筆 tick
                    return new KlineBucket(assetId, interval, bucketStart,
                            price, price, price, price, vol);
                } else {
                    BigDecimal newHigh = prev.high().max(price);
                    BigDecimal newLow = prev.low().min(price);
                    BigDecimal prevVol = Objects.requireNonNullElse(prev.volume(), BigDecimal.ZERO);
                    BigDecimal newVol = prevVol.add(vol);
                    return new KlineBucket(assetId, interval, bucketStart,
                            prev.open(), newHigh, newLow, price, newVol);
                }
            });
            result.add(updated);
        }
        return result;
    }

    /**
     * 回傳指定 asset 目前所有 interval 的 bucket；從未餵 tick 則 list 為空。
     *
     * @param assetId 資產 id，不可為 null
     * @return 各 interval 的當前 bucket 快照，順序固定：1m/5m/15m/1h/1d
     */
    public List<KlineBucket> currentBuckets(Long assetId) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        List<KlineBucket> result = new ArrayList<>();
        for (KlineInterval interval : ALL_INTERVALS) {
            KlineBucket b = state.get(new Key(assetId, interval));
            if (b != null) result.add(b);
        }
        return result;
    }

    /**
     * 為測試暴露；清空所有狀態。
     */
    void reset() {
        state.clear();
    }

    /**
     * 計算給定時間在指定 bucket 秒數下的 bucket 起始時間。
     *
     * <p>所有支援的 interval（1m/5m/15m/1h/1d）均能整除 1 天，
     * 故此公式對所有 interval 均正確。
     *
     * @param time          tick 時間
     * @param bucketSeconds bucket 的秒數（interval duration）
     * @return 此 tick 所屬 bucket 的起始 {@link Instant}
     */
    private static Instant bucketStart(Instant time, long bucketSeconds) {
        long epoch = time.getEpochSecond();
        long floored = (epoch / bucketSeconds) * bucketSeconds;
        return Instant.ofEpochSecond(floored);
    }

    /**
     * 複合 key：(assetId, interval)，作為 state map 的索引鍵。
     */
    private record Key(Long assetId, KlineInterval interval) {}
}
