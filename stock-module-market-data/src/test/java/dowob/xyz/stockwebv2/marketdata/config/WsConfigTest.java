package dowob.xyz.stockwebv2.marketdata.config;

import dowob.xyz.stockwebv2.marketdata.ws.MarketHandshakeInterceptor;
import dowob.xyz.stockwebv2.marketdata.ws.MarketWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link WsConfig} 單元測試。
 *
 * <p>驗證 WebSocket endpoint 的注冊設定：
 * <ul>
 *   <li>路徑常數 {@link WsConfig#WS_PATH} 為 {@code /ws/v1/market}</li>
 *   <li>{@code registerWebSocketHandlers} 以正確路徑注冊 handler</li>
 *   <li>{@link MarketHandshakeInterceptor} 被加入至 registration</li>
 *   <li>設定允許所有來源（{@code setAllowedOriginPatterns("*")}）</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0.0
 */
class WsConfigTest {

    MarketWebSocketHandler handler;
    MarketHandshakeInterceptor interceptor;
    WsConfig wsConfig;

    @BeforeEach
    void setUp() {
        handler = mock(MarketWebSocketHandler.class);
        interceptor = mock(MarketHandshakeInterceptor.class);
        wsConfig = new WsConfig(handler, interceptor);
    }

    // ── 路徑常數 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("WS_PATH 常數值為 /ws/v1/market")
    void wsPath_constant_isCorrect() {
        assertThat(WsConfig.WS_PATH).isEqualTo("/ws/v1/market");
    }

    // ── registerWebSocketHandlers ─────────────────────────────────────────────

    @Test
    @DisplayName("registerWebSocketHandlers：以正確路徑注冊 MarketWebSocketHandler")
    void registerWebSocketHandlers_registersHandlerAtCorrectPath() {
        WebSocketHandlerRegistry registry = mock(WebSocketHandlerRegistry.class);
        WebSocketHandlerRegistration registration = mock(WebSocketHandlerRegistration.class);

        when(registry.addHandler(eq(handler), eq(WsConfig.WS_PATH))).thenReturn(registration);
        when(registration.addInterceptors(any())).thenReturn(registration);
        when(registration.setAllowedOriginPatterns(any())).thenReturn(registration);

        wsConfig.registerWebSocketHandlers(registry);

        verify(registry).addHandler(eq(handler), eq("/ws/v1/market"));
    }

    @Test
    @DisplayName("registerWebSocketHandlers：MarketHandshakeInterceptor 被加入至 registration")
    void registerWebSocketHandlers_attachesInterceptor() {
        WebSocketHandlerRegistry registry = mock(WebSocketHandlerRegistry.class);
        WebSocketHandlerRegistration registration = mock(WebSocketHandlerRegistration.class);

        when(registry.addHandler(any(), any())).thenReturn(registration);
        when(registration.addInterceptors(interceptor)).thenReturn(registration);
        when(registration.setAllowedOriginPatterns(any())).thenReturn(registration);

        wsConfig.registerWebSocketHandlers(registry);

        verify(registration).addInterceptors(interceptor);
    }

    @Test
    @DisplayName("registerWebSocketHandlers：設定允許所有來源 (*)")
    void registerWebSocketHandlers_allowsAllOrigins() {
        WebSocketHandlerRegistry registry = mock(WebSocketHandlerRegistry.class);
        WebSocketHandlerRegistration registration = mock(WebSocketHandlerRegistration.class);

        when(registry.addHandler(any(), any())).thenReturn(registration);
        when(registration.addInterceptors(any())).thenReturn(registration);
        when(registration.setAllowedOriginPatterns("*")).thenReturn(registration);

        wsConfig.registerWebSocketHandlers(registry);

        verify(registration).setAllowedOriginPatterns("*");
    }
}
