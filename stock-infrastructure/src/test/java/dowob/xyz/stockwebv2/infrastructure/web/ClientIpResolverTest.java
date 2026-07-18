package dowob.xyz.stockwebv2.infrastructure.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ClientIpResolver} 來源 IP 判定的單元測試（security.md §15/§18）。
 *
 * <p>核心保證：不信任客戶端可偽造的 {@code X-Forwarded-For},一律以 TCP 對端位址為準,
 * 避免限流 / 連線上限被偽造標頭繞過。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("來源 IP 判定")
class ClientIpResolverTest {

    @Test
    @DisplayName("servlet：忽略偽造的 X-Forwarded-For,採用 TCP 對端位址")
    void servletIgnoresForgedXForwardedFor() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "1.2.3.4");
        request.setRemoteAddr("10.0.0.9");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("10.0.0.9");
    }

    @Test
    @DisplayName("servlet：無 X-Forwarded-For 時採用 TCP 對端位址")
    void servletUsesRemoteAddrWhenNoHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.20");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("192.168.1.20");
    }

    @Test
    @DisplayName("WebSocket：忽略偽造的 X-Forwarded-For,採用 TCP 對端位址")
    void serverHttpRequestIgnoresForgedXForwardedFor() {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getRemoteAddress()).thenReturn(new InetSocketAddress("10.0.0.5", 54321));

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("10.0.0.5");
    }

    @Test
    @DisplayName("WebSocket：無法判定對端位址時回傳 unknown")
    void serverHttpRequestFallsBackToUnknown() {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getRemoteAddress()).thenReturn(null);

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("unknown");
    }
}
