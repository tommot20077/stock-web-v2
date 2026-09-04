package dowob.xyz.stockwebv2.marketdata.ws;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link WsHeartbeat} 的單元測試,聚焦 lifecycle (register/unregister/@PreDestroy shutdown)。
 * Timing-based 的 PING/timeout 行為(30s/90s)不在此測試,避免 flaky test。
 *
 * @author Yuan
 * @version 1.0.0
 */
class WsHeartbeatTest {

    @Test
    @DisplayName("shutdown 後 scheduler 停止 + 所有 tracked sessions 清空")
    void shutdown_cancelsAllSessionsAndShutsScheduler() {
        WsHeartbeat heartbeat = new WsHeartbeat();
        WebSocketSession s1 = mock(WebSocketSession.class);
        WebSocketSession s2 = mock(WebSocketSession.class);
        when(s1.getId()).thenReturn("s1");
        when(s2.getId()).thenReturn("s2");

        heartbeat.register(s1);
        heartbeat.register(s2);
        assertThat(heartbeat.trackedSessions()).isEqualTo(2);
        assertThat(heartbeat.isSchedulerShutdown()).isFalse();

        heartbeat.shutdown();

        assertThat(heartbeat.trackedSessions()).isZero();
        assertThat(heartbeat.isSchedulerShutdown()).isTrue();
    }

    @Test
    @DisplayName("unregister 移除單一 session 但 scheduler 持續運行")
    void unregister_removesOneSessionOnly() {
        WsHeartbeat heartbeat = new WsHeartbeat();
        WebSocketSession s1 = mock(WebSocketSession.class);
        WebSocketSession s2 = mock(WebSocketSession.class);
        when(s1.getId()).thenReturn("s1");
        when(s2.getId()).thenReturn("s2");

        heartbeat.register(s1);
        heartbeat.register(s2);
        heartbeat.unregister("s1");

        assertThat(heartbeat.trackedSessions()).isEqualTo(1);
        assertThat(heartbeat.isSchedulerShutdown()).isFalse();

        heartbeat.shutdown();
    }

    @Test
    @DisplayName("shutdown 對已 unregister 完空的 heartbeat 也安全 (idempotent)")
    void shutdown_emptyState_isSafe() {
        WsHeartbeat heartbeat = new WsHeartbeat();
        heartbeat.shutdown();
        assertThat(heartbeat.isSchedulerShutdown()).isTrue();
    }
}
