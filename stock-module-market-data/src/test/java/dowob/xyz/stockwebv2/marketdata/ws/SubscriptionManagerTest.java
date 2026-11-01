package dowob.xyz.stockwebv2.marketdata.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SubscriptionManager} 單元測試。
 *
 * <p>驗證範圍：
 * <ul>
 *   <li>subscribe 正常加入 channels 並更新 reverse index</li>
 *   <li>重複訂閱同 channel 為 no-op</li>
 *   <li>單一 session tick 最多 100 個、kline 最多 10 個，各自計數，超過者 rejected</li>
 *   <li>channel 前綴大小寫不敏感（TICK.* 計入 tick 額度）</li>
 *   <li>unsubscribe 正確移除 channels 及清理 reverse index</li>
 *   <li>最後一個 subscriber 退出後 channel entry 應被移除</li>
 *   <li>removeSession 清除所有訂閱</li>
 *   <li>多個 session 訂閱同一 channel</li>
 *   <li>並發操作無 race condition（@RepeatedTest）</li>
 *   <li>null 參數拋出 NullPointerException</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.1.0
 */
class SubscriptionManagerTest {

    private SubscriptionManager mgr;

    @BeforeEach
    void setup() {
        mgr = new SubscriptionManager();
    }

    // ── subscribe ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("subscribe 加入 channels 並更新 reverse index")
    void subscribe_addsChannelsAndUpdatesReverseIndex() {
        var r = mgr.subscribe("s1", List.of("tick.AAPL", "tick.BTC"));

        assertThat(r.accepted()).containsExactly("tick.AAPL", "tick.BTC");
        assertThat(r.rejected()).isEmpty();
        assertThat(mgr.channelsOf("s1")).containsExactlyInAnyOrder("tick.AAPL", "tick.BTC");
        assertThat(mgr.subscribersOf("tick.AAPL")).containsExactly("s1");
        assertThat(mgr.subscribersOf("tick.BTC")).containsExactly("s1");
    }

    @Test
    @DisplayName("重複訂閱同 channel 為 no-op，accepted 與 rejected 皆為空")
    void subscribe_duplicate_isNoOp() {
        mgr.subscribe("s1", List.of("tick.AAPL"));
        var r = mgr.subscribe("s1", List.of("tick.AAPL"));

        assertThat(r.accepted()).isEmpty();
        assertThat(r.rejected()).isEmpty();
        assertThat(mgr.channelsOf("s1")).containsExactly("tick.AAPL");
    }

    @Test
    @DisplayName("同一 session 一次訂閱 11 個 tick channel 全部 accepted（tick 上限 100）")
    void subscribe_elevenTickChannels_allAccepted() {
        List<String> ticks = IntStream.rangeClosed(1, 11).mapToObj(i -> String.format("tick.S%02d", i)).toList();

        var r = mgr.subscribe("s1", ticks);

        assertThat(r.accepted()).hasSize(11);
        assertThat(r.rejected()).isEmpty();
        assertThat(mgr.channelsOf("s1")).hasSize(11);
    }

    @Test
    @DisplayName("tick 訂閱上限 100 個，第 101 個被 rejected 並附 SUBSCRIPTION_LIMIT_EXCEEDED")
    void subscribe_tickLimitOf100_perSession() {
        List<String> ticks = IntStream.range(0, 101).mapToObj(i -> "tick.T" + i).toList();

        var r = mgr.subscribe("s1", ticks);

        assertThat(r.accepted()).hasSize(100);
        assertThat(r.rejected()).hasSize(1);
        assertThat(r.rejected().get(0).channel()).isEqualTo("tick.T100");
        assertThat(r.rejected().get(0).reason()).isEqualTo("SUBSCRIPTION_LIMIT_EXCEEDED");
        assertThat(mgr.subscribersOf("tick.T100")).isEmpty();
    }

    @Test
    @DisplayName("kline 訂閱上限維持 10 個，超過者被 rejected 並附原因")
    void subscribe_klineLimitOf10_perSession() {
        List<String> first10 = IntStream.range(0, 10).mapToObj(i -> "kline.X" + i + ".1m").toList();
        var r1 = mgr.subscribe("s1", first10);
        assertThat(r1.accepted()).hasSize(10);
        assertThat(r1.rejected()).isEmpty();

        var r2 = mgr.subscribe("s1", List.of("kline.EXTRA.1m"));
        assertThat(r2.accepted()).isEmpty();
        assertThat(r2.rejected()).hasSize(1);
        assertThat(r2.rejected().get(0).channel()).isEqualTo("kline.EXTRA.1m");
        assertThat(r2.rejected().get(0).reason()).isEqualTo("SUBSCRIPTION_LIMIT_EXCEEDED");
    }

    @Test
    @DisplayName("kline 批次訂閱中部分超限，超限者 rejected，其餘 accepted")
    void subscribe_klinePartialBatch_someRejected() {
        List<String> first9 = IntStream.range(0, 9).mapToObj(i -> "kline.X" + i + ".1m").toList();
        mgr.subscribe("s1", first9);

        /* kline 剩餘空間 1，送 3 個新 kline channel */
        var r = mgr.subscribe("s1", List.of("kline.A.1m", "kline.B.1m", "kline.C.1m"));
        assertThat(r.accepted()).hasSize(1);
        assertThat(r.rejected()).hasSize(2);
        assertThat(r.rejected()).allMatch(rc -> rc.reason().equals("SUBSCRIPTION_LIMIT_EXCEEDED"));
    }

    @Test
    @DisplayName("tick 與 kline 各自計數、互不佔額")
    void subscribe_tickAndKline_countedSeparately() {
        List<String> klines = IntStream.range(0, 10).mapToObj(i -> "kline.X" + i + ".1m").toList();
        mgr.subscribe("s1", klines);

        var tickResult = mgr.subscribe("s1", List.of("tick.AAPL"));
        assertThat(tickResult.accepted()).containsExactly("tick.AAPL");
        assertThat(tickResult.rejected()).isEmpty();

        List<String> ticks = IntStream.range(0, 100).mapToObj(i -> "tick.T" + i).toList();
        mgr.subscribe("s2", ticks);

        var klineResult = mgr.subscribe("s2", List.of("kline.AAPL.1m"));
        assertThat(klineResult.accepted()).containsExactly("kline.AAPL.1m");
        assertThat(klineResult.rejected()).isEmpty();
        assertThat(mgr.channelsOf("s2")).hasSize(101);
    }

    @Test
    @DisplayName("大寫前綴 TICK.* 計入 tick 額度，第 101 個被 rejected（不可繞過計數）")
    void subscribe_upperCaseTickPrefix_countsTowardTickLimit() {
        List<String> ticks = IntStream.range(0, 101).mapToObj(i -> "TICK.U" + i).toList();

        var r = mgr.subscribe("s1", ticks);

        assertThat(r.accepted()).hasSize(100);
        assertThat(r.rejected()).hasSize(1);
        assertThat(r.rejected().get(0).channel()).isEqualTo("TICK.U100");
        assertThat(r.rejected().get(0).reason()).isEqualTo("SUBSCRIPTION_LIMIT_EXCEEDED");

        var klineResult = mgr.subscribe("s1", List.of("KLINE.AAPL.1m"));
        assertThat(klineResult.accepted()).containsExactly("KLINE.AAPL.1m");
    }

    @Test
    @DisplayName("多個 session 可訂閱同一 channel，reverse index 包含所有 sessions")
    void subscribe_multipleSessionsToSameChannel() {
        mgr.subscribe("s1", List.of("tick.AAPL"));
        mgr.subscribe("s2", List.of("tick.AAPL"));
        mgr.subscribe("s3", List.of("tick.AAPL"));

        assertThat(mgr.subscribersOf("tick.AAPL")).containsExactlyInAnyOrder("s1", "s2", "s3");
    }

    // ── unsubscribe ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("unsubscribe 移除指定 channels 並清理 reverse index")
    void unsubscribe_removesChannelsAndCleansReverseIndex() {
        mgr.subscribe("s1", List.of("tick.AAPL", "tick.BTC"));
        mgr.unsubscribe("s1", List.of("tick.AAPL"));

        assertThat(mgr.channelsOf("s1")).containsExactly("tick.BTC");
        assertThat(mgr.subscribersOf("tick.AAPL")).isEmpty();
        assertThat(mgr.subscribersOf("tick.BTC")).containsExactly("s1");
    }

    @Test
    @DisplayName("最後一個 subscriber 退出後 channel entry 應被移除")
    void unsubscribe_lastSubscriber_cleansChannelEntry() {
        mgr.subscribe("s1", List.of("tick.AAPL"));
        mgr.unsubscribe("s1", List.of("tick.AAPL"));

        assertThat(mgr.subscribersOf("tick.AAPL")).isEmpty();
        assertThat(mgr.channelCount()).isZero();
        assertThat(mgr.sessionCount()).isZero();
    }

    @Test
    @DisplayName("unsubscribe 不存在的 session，不拋出例外")
    void unsubscribe_unknownSession_doesNotThrow() {
        // 不應拋出例外
        mgr.unsubscribe("unknown", List.of("tick.AAPL"));
        assertThat(mgr.sessionCount()).isZero();
    }

    @Test
    @DisplayName("unsubscribe 不存在的 channel，不拋出例外且狀態不變")
    void unsubscribe_unknownChannel_doesNotThrow() {
        mgr.subscribe("s1", List.of("tick.AAPL"));
        mgr.unsubscribe("s1", List.of("tick.NONEXISTENT"));

        assertThat(mgr.channelsOf("s1")).containsExactly("tick.AAPL");
    }

    // ── removeSession ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("removeSession 清除 session 所有訂閱並更新 reverse index")
    void removeSession_cleansAllSubscriptions() {
        mgr.subscribe("s1", List.of("tick.AAPL", "tick.BTC"));
        mgr.subscribe("s2", List.of("tick.AAPL"));
        mgr.removeSession("s1");

        assertThat(mgr.channelsOf("s1")).isEmpty();
        assertThat(mgr.subscribersOf("tick.AAPL")).containsExactly("s2");
        assertThat(mgr.subscribersOf("tick.BTC")).isEmpty();
    }

    @Test
    @DisplayName("removeSession 不存在的 session，不拋出例外")
    void removeSession_unknownSession_doesNotThrow() {
        mgr.removeSession("unknown");
        assertThat(mgr.sessionCount()).isZero();
    }

    // ── subscribersOf / channelsOf ────────────────────────────────────────────

    @Test
    @DisplayName("subscribersOf 不存在的 channel 回傳空 set")
    void subscribersOf_unknownChannel_returnsEmpty() {
        assertThat(mgr.subscribersOf("tick.NONEXISTENT")).isEmpty();
    }

    @Test
    @DisplayName("channelsOf 不存在的 session 回傳空 set")
    void channelsOf_unknownSession_returnsEmpty() {
        assertThat(mgr.channelsOf("unknown")).isEmpty();
    }

    // ── null guard ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("subscribe 傳入 null 參數拋出 NullPointerException")
    void subscribe_null_throws() {
        assertThatThrownBy(() -> mgr.subscribe(null, List.of("x")))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> mgr.subscribe("s1", null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("unsubscribe 傳入 null 參數拋出 NullPointerException")
    void unsubscribe_null_throws() {
        assertThatThrownBy(() -> mgr.unsubscribe(null, List.of("x")))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> mgr.unsubscribe("s1", null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("removeSession 傳入 null 參數拋出 NullPointerException")
    void removeSession_null_throws() {
        assertThatThrownBy(() -> mgr.removeSession(null))
            .isInstanceOf(NullPointerException.class);
    }

    // ── concurrency ───────────────────────────────────────────────────────────

    @RepeatedTest(5)
    @DisplayName("並發 subscribe/unsubscribe 無 race condition，結束後 maps 應為空")
    void concurrentSubscribeUnsubscribe_noRace() throws Exception {
        int threadCount = 10;
        int opsPerThread = 200;
        ExecutorService es = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            int tid = t;
            es.submit(() -> {
                try {
                    start.await();
                    String session = "s" + tid;
                    for (int i = 0; i < opsPerThread; i++) {
                        String ch = "tick.C" + (i % 5);
                        if (i % 3 == 0) {
                            mgr.unsubscribe(session, List.of(ch));
                        } else {
                            mgr.subscribe(session, List.of(ch));
                        }
                    }
                    mgr.removeSession(session);
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        es.shutdown();

        // 最終不變量：所有 removeSession 執行後，兩個 map 必須為空
        assertThat(mgr.sessionCount()).isZero();
        assertThat(mgr.channelCount()).isZero();
    }

    @RepeatedTest(3)
    @DisplayName("並發多 session 同時訂閱同 channel，reverse index 保持一致")
    void concurrentMultiSessionSameChannel_reverseIndexConsistent() throws Exception {
        int threadCount = 20;
        ExecutorService es = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            int tid = t;
            es.submit(() -> {
                try {
                    start.await();
                    mgr.subscribe("s" + tid, List.of("tick.SHARED"));
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        es.shutdown();

        // 所有 threadCount 個 sessions 都應在 reverse index 中
        assertThat(mgr.subscribersOf("tick.SHARED")).hasSize(threadCount);
        assertThat(mgr.sessionCount()).isEqualTo(threadCount);
    }
}
