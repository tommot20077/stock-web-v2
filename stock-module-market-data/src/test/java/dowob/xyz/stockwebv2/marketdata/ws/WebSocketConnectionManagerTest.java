package dowob.xyz.stockwebv2.marketdata.ws;

import dowob.xyz.stockwebv2.marketdata.config.WebSocketLimitProperties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WebSocketConnectionManager} 連線治理邏輯的單元測試（security.md §18）。
 *
 * <p>驗證全域上限、每 IP 上限與每帳號 FIFO 驅逐、以及連線關閉後的計數釋放。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("WebSocket 連線治理")
class WebSocketConnectionManagerTest {

    private WebSocketConnectionManager manager(int global, int perIp, int perAccount) {
        return new WebSocketConnectionManager(new WebSocketLimitProperties(global, perIp, perAccount));
    }

    @Test
    @DisplayName("超過每帳號連線上限時 FIFO 驅逐最舊 session")
    void evictsOldestWhenPerAccountLimitExceeded() {
        WebSocketConnectionManager manager = manager(1000, 5, 2);

        assertThat(manager.register("s1", 7L, "1.1.1.1")).isEmpty();
        assertThat(manager.register("s2", 7L, "1.1.1.1")).isEmpty();
        List<String> evicted = manager.register("s3", 7L, "1.1.1.1");

        assertThat(evicted).containsExactly("s1");
    }

    @Test
    @DisplayName("不同帳號各自獨立計算每帳號上限")
    void perAccountLimitsAreIsolatedPerUser() {
        WebSocketConnectionManager manager = manager(1000, 5, 2);

        manager.register("a1", 1L, "1.1.1.1");
        manager.register("a2", 1L, "1.1.1.1");
        List<String> evicted = manager.register("b1", 2L, "1.1.1.1");

        assertThat(evicted).isEmpty();
    }

    @Test
    @DisplayName("達每 IP 連線上限後回報已達上限")
    void reportsIpLimitReached() {
        WebSocketConnectionManager manager = manager(1000, 2, 5);

        manager.register("s1", 1L, "9.9.9.9");
        assertThat(manager.ipLimitReached("9.9.9.9")).isFalse();
        manager.register("s2", 2L, "9.9.9.9");
        assertThat(manager.ipLimitReached("9.9.9.9")).isTrue();
    }

    @Test
    @DisplayName("達全域連線上限後回報已達上限")
    void reportsGlobalLimitReached() {
        WebSocketConnectionManager manager = manager(2, 5, 2);

        manager.register("s1", 1L, "1.1.1.1");
        assertThat(manager.globalLimitReached()).isFalse();
        manager.register("s2", 2L, "2.2.2.2");
        assertThat(manager.globalLimitReached()).isTrue();
    }

    @Test
    @DisplayName("連線關閉後計數釋放，不再視為已達上限")
    void unregisterReleasesCounts() {
        WebSocketConnectionManager manager = manager(1000, 1, 2);

        manager.register("s1", 1L, "5.5.5.5");
        assertThat(manager.ipLimitReached("5.5.5.5")).isTrue();

        manager.unregister("s1");

        assertThat(manager.ipLimitReached("5.5.5.5")).isFalse();
        assertThat(manager.activeConnections()).isZero();
    }
}
