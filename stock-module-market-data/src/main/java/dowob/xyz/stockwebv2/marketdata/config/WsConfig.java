package dowob.xyz.stockwebv2.marketdata.config;

import dowob.xyz.stockwebv2.marketdata.ws.MarketHandshakeInterceptor;
import dowob.xyz.stockwebv2.marketdata.ws.MarketWebSocketHandler;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 註冊 WebSocket endpoint {@value #WS_PATH} 並掛上 {@link MarketHandshakeInterceptor}。
 *
 * <p>允許跨來源連線（CORS 由 stock-start 全域 config 控制）。
 *
 * <p>Endpoint 說明：
 * <ul>
 *   <li>路徑：{@value #WS_PATH}</li>
 *   <li>Handler：{@link MarketWebSocketHandler}（負責訂閱、心跳、速率限制）</li>
 *   <li>Interceptor：{@link MarketHandshakeInterceptor}（ticket 驗證 → 注入 userId）</li>
 *   <li>CORS：{@code setAllowedOriginPatterns("*")}（全域策略）</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0.0
 */
@Configuration
@EnableWebSocket
@EnableConfigurationProperties(WebSocketLimitProperties.class)
public class WsConfig implements WebSocketConfigurer {

    /** WebSocket endpoint 路徑。 */
    public static final String WS_PATH = "/ws/v1/market";

    private final MarketWebSocketHandler handler;
    private final MarketHandshakeInterceptor interceptor;

    /**
     * 建構子注入。
     *
     * @param handler     WebSocket 主要訊息處理器，不可為 null
     * @param interceptor Handshake 攔截器（ticket 驗證），不可為 null
     */
    public WsConfig(MarketWebSocketHandler handler, MarketHandshakeInterceptor interceptor) {
        this.handler = handler;
        this.interceptor = interceptor;
    }

    /**
     * 向 Spring 注冊 WebSocket handler 至 {@value #WS_PATH}，並掛上 interceptor。
     *
     * @param registry WebSocket handler registry
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, WS_PATH)
                .addInterceptors(interceptor)
                .setAllowedOriginPatterns("*");
    }
}
