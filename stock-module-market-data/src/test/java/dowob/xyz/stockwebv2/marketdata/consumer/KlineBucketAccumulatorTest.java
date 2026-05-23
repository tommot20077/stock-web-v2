package dowob.xyz.stockwebv2.marketdata.consumer;

import dowob.xyz.stockwebv2.common.model.KlineInterval;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * {@link KlineBucketAccumulator} 單元測試。
 *
 * <p>驗證：
 * <ul>
 *   <li>首筆 tick 初始化 bucket，OHLC 四值皆相同</li>
 *   <li>同一 bucket 內多筆 tick 正確更新 high/low/close 及累計 volume</li>
 *   <li>跨 bucket 邊界時 1m bucket 自動重置，較長周期繼續累積</li>
 *   <li>邊界 tick（bucket.start / bucket.end-1ms）歸屬正確</li>
 *   <li>不同資產的狀態互相獨立</li>
 *   <li>null volume 視為 0</li>
 *   <li>currentBuckets 對從未餵過 tick 的 assetId 回傳空 list</li>
 *   <li>updateAndSnapshot 回傳的 5 個 interval 順序固定</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0.0
 */
class KlineBucketAccumulatorTest {

    KlineBucketAccumulator acc;

    @BeforeEach
    void setup() {
        acc = new KlineBucketAccumulator();
    }

    // ── 首筆 tick ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("首筆 tick → bucket 以該價格初始化 OHLC，bucketStart 正確截斷")
    void firstTick_initializesBucketWithSamePriceForOpenHighLowClose() {
        Instant t = Instant.parse("2026-01-01T10:23:45Z");
        var buckets = acc.updateAndSnapshot(1L, t, new BigDecimal("100"), new BigDecimal("500"));
        assertThat(buckets).hasSize(5);

        var oneMin = buckets.get(0);
        assertThat(oneMin.interval()).isEqualTo(KlineInterval.ONE_MINUTE);
        assertThat(oneMin.bucketStart()).isEqualTo(Instant.parse("2026-01-01T10:23:00Z"));
        assertThat(oneMin.open()).isEqualByComparingTo("100");
        assertThat(oneMin.high()).isEqualByComparingTo("100");
        assertThat(oneMin.low()).isEqualByComparingTo("100");
        assertThat(oneMin.close()).isEqualByComparingTo("100");
        assertThat(oneMin.volume()).isEqualByComparingTo("500");

        var oneDay = buckets.get(4);
        assertThat(oneDay.interval()).isEqualTo(KlineInterval.ONE_DAY);
        assertThat(oneDay.bucketStart()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }

    // ── 同 bucket 多筆 tick ───────────────────────────────────────────────────

    @Test
    @DisplayName("同一 bucket 三筆 tick → high/low/close 正確，volume 累計")
    void multipleTicks_withinSameBucket_updateHighLowCloseAndAccumulateVolume() {
        Instant t1 = Instant.parse("2026-01-01T10:23:00Z");
        Instant t2 = Instant.parse("2026-01-01T10:23:15Z");
        Instant t3 = Instant.parse("2026-01-01T10:23:45Z");

        acc.updateAndSnapshot(1L, t1, new BigDecimal("100"), new BigDecimal("500"));
        acc.updateAndSnapshot(1L, t2, new BigDecimal("110"), new BigDecimal("200"));
        var buckets = acc.updateAndSnapshot(1L, t3, new BigDecimal("90"), new BigDecimal("300"));

        var oneMin = buckets.get(0);
        assertThat(oneMin.open()).isEqualByComparingTo("100");   // 首筆
        assertThat(oneMin.high()).isEqualByComparingTo("110");
        assertThat(oneMin.low()).isEqualByComparingTo("90");
        assertThat(oneMin.close()).isEqualByComparingTo("90");   // 末筆
        assertThat(oneMin.volume()).isEqualByComparingTo("1000"); // 500+200+300
    }

    // ── 跨 bucket 邊界 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("跨 1m bucket 邊界 → 1m 重置，5m/15m/1h/1d 繼續累積")
    void crossingBucketBoundary_resets1mBucket_but5m1h1dContinue() {
        Instant t1 = Instant.parse("2026-01-01T10:23:30Z");
        Instant t2 = Instant.parse("2026-01-01T10:24:30Z"); // 新 1m bucket，同 5m/15m/1h/1d

        acc.updateAndSnapshot(1L, t1, new BigDecimal("100"), new BigDecimal("500"));
        var buckets = acc.updateAndSnapshot(1L, t2, new BigDecimal("110"), new BigDecimal("300"));

        var oneMin = buckets.get(0);
        assertThat(oneMin.bucketStart()).isEqualTo(Instant.parse("2026-01-01T10:24:00Z")); // 新 bucket
        assertThat(oneMin.open()).isEqualByComparingTo("110");   // 重置為 t2 的價格
        assertThat(oneMin.high()).isEqualByComparingTo("110");
        assertThat(oneMin.low()).isEqualByComparingTo("110");
        assertThat(oneMin.close()).isEqualByComparingTo("110");
        assertThat(oneMin.volume()).isEqualByComparingTo("300");

        // 5m bucket [10:20:00, 10:25:00) 兩筆都在內
        var fiveMin = buckets.get(1);
        assertThat(fiveMin.open()).isEqualByComparingTo("100");
        assertThat(fiveMin.high()).isEqualByComparingTo("110");
        assertThat(fiveMin.low()).isEqualByComparingTo("100");
        assertThat(fiveMin.close()).isEqualByComparingTo("110");
        assertThat(fiveMin.volume()).isEqualByComparingTo("800");
    }

    // ── 邊界 tick ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("tick 落在 bucketStart 整點 → 正確歸屬該 bucket")
    void tickAtBucketStart_initsBucketCorrectly() {
        Instant tStart = Instant.parse("2026-01-01T10:23:00Z");
        var buckets = acc.updateAndSnapshot(1L, tStart, new BigDecimal("100"), new BigDecimal("500"));
        assertThat(buckets.get(0).bucketStart()).isEqualTo(tStart);
    }

    @Test
    @DisplayName("tick 落在 bucketEnd-1ms → 仍歸屬前一個 bucket")
    void tickAtBucketEndMinus1ms_isStillInPreviousBucket() {
        Instant tEnd = Instant.parse("2026-01-01T10:23:59.999Z");
        var buckets = acc.updateAndSnapshot(1L, tEnd, new BigDecimal("100"), new BigDecimal("500"));
        assertThat(buckets.get(0).bucketStart()).isEqualTo(Instant.parse("2026-01-01T10:23:00Z"));
    }

    // ── 不同資產獨立 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("不同 assetId → 狀態互不干擾")
    void differentAssets_haveIndependentState() {
        Instant t = Instant.parse("2026-01-01T10:23:00Z");
        acc.updateAndSnapshot(1L, t, new BigDecimal("100"), new BigDecimal("500"));
        acc.updateAndSnapshot(2L, t, new BigDecimal("200"), new BigDecimal("100"));

        var asset1 = acc.currentBuckets(1L);
        var asset2 = acc.currentBuckets(2L);
        assertThat(asset1.get(0).open()).isEqualByComparingTo("100");
        assertThat(asset2.get(0).open()).isEqualByComparingTo("200");
    }

    // ── null volume ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("volume 為 null → 視為 0")
    void nullVolume_treatedAsZero() {
        var t = Instant.parse("2026-01-01T10:23:00Z");
        var buckets = acc.updateAndSnapshot(1L, t, new BigDecimal("100"), null);
        assertThat(buckets.get(0).volume()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("首筆 volume 正常，後續 null → 正確累加（null 視為 0）")
    void nullVolumeAfterNonNull_accumulatesAsZero() {
        Instant t1 = Instant.parse("2026-01-01T10:23:00Z");
        Instant t2 = Instant.parse("2026-01-01T10:23:30Z");
        acc.updateAndSnapshot(1L, t1, new BigDecimal("100"), new BigDecimal("300"));
        var buckets = acc.updateAndSnapshot(1L, t2, new BigDecimal("105"), null);
        assertThat(buckets.get(0).volume()).isEqualByComparingTo("300"); // 300 + 0
    }

    // ── currentBuckets ───────────────────────────────────────────────────────

    @Test
    @DisplayName("currentBuckets：從未餵 tick 的 assetId → 回傳空 list")
    void currentBuckets_unknownAsset_returnsEmptyList() {
        assertThat(acc.currentBuckets(999L)).isEmpty();
    }

    @Test
    @DisplayName("currentBuckets：餵過 tick 後 → 回傳 5 個 interval 的快照")
    void currentBuckets_afterUpdate_returnsFiveIntervals() {
        var t = Instant.parse("2026-01-01T10:23:00Z");
        acc.updateAndSnapshot(1L, t, new BigDecimal("100"), new BigDecimal("500"));

        var current = acc.currentBuckets(1L);
        assertThat(current).hasSize(5);
    }

    // ── 回傳順序 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("updateAndSnapshot → 5 個 interval 依序為 1m/5m/15m/1h/1d")
    void updateAndSnapshot_returns5IntervalsInOrder() {
        var t = Instant.parse("2026-01-01T10:23:00Z");
        var buckets = acc.updateAndSnapshot(1L, t, new BigDecimal("100"), new BigDecimal("500"));
        assertThat(buckets).extracting(KlineBucket::interval)
                .containsExactly(
                        KlineInterval.ONE_MINUTE,
                        KlineInterval.FIVE_MINUTES,
                        KlineInterval.FIFTEEN_MINUTES,
                        KlineInterval.ONE_HOUR,
                        KlineInterval.ONE_DAY);
    }

    // ── null 防衛 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("assetId 為 null → 拋出 NullPointerException")
    void updateAndSnapshot_nullAssetId_throwsNpe() {
        assertThatNullPointerException()
                .isThrownBy(() -> acc.updateAndSnapshot(null, Instant.now(), new BigDecimal("100"), null));
    }

    @Test
    @DisplayName("time 為 null → 拋出 NullPointerException")
    void updateAndSnapshot_nullTime_throwsNpe() {
        assertThatNullPointerException()
                .isThrownBy(() -> acc.updateAndSnapshot(1L, null, new BigDecimal("100"), null));
    }

    @Test
    @DisplayName("price 為 null → 拋出 NullPointerException")
    void updateAndSnapshot_nullPrice_throwsNpe() {
        assertThatNullPointerException()
                .isThrownBy(() -> acc.updateAndSnapshot(1L, Instant.now(), null, null));
    }

    // ── bucket start 數學驗證 ─────────────────────────────────────────────────

    @Test
    @DisplayName("5m bucket：10:22:30 → bucketStart = 10:20:00")
    void bucketStart_fiveMinute_correctFloor() {
        Instant t = Instant.parse("2026-01-01T10:22:30Z");
        var buckets = acc.updateAndSnapshot(1L, t, new BigDecimal("100"), new BigDecimal("1"));
        assertThat(buckets.get(1).bucketStart()).isEqualTo(Instant.parse("2026-01-01T10:20:00Z"));
    }

    @Test
    @DisplayName("15m bucket：10:28:00 → bucketStart = 10:15:00")
    void bucketStart_fifteenMinute_correctFloor() {
        Instant t = Instant.parse("2026-01-01T10:28:00Z");
        var buckets = acc.updateAndSnapshot(1L, t, new BigDecimal("100"), new BigDecimal("1"));
        assertThat(buckets.get(2).bucketStart()).isEqualTo(Instant.parse("2026-01-01T10:15:00Z"));
    }

    @Test
    @DisplayName("1h bucket：10:45:00 → bucketStart = 10:00:00")
    void bucketStart_oneHour_correctFloor() {
        Instant t = Instant.parse("2026-01-01T10:45:00Z");
        var buckets = acc.updateAndSnapshot(1L, t, new BigDecimal("100"), new BigDecimal("1"));
        assertThat(buckets.get(3).bucketStart()).isEqualTo(Instant.parse("2026-01-01T10:00:00Z"));
    }
}
