package dowob.xyz.stockwebv2.marketdata.config;

import dowob.xyz.stockwebv2.marketdata.ws.MarketHandshakeInterceptor;
import dowob.xyz.stockwebv2.marketdata.ws.MarketWebSocketHandler;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.Arrays;

/**
 * 註冊 WebSocket endpoint {@value #WS_PATH} 並掛上 {@link MarketHandshakeInterceptor}。
 *
 * <p>跨來源連線只允許 {@code stock.cors.allowed-origins} 內的來源。
 *
 * <p>Endpoint 說明：
 * <ul>
 *   <li>路徑：{@value #WS_PATH}</li>
 *   <li>Handler：{@link MarketWebSocketHandler}（負責訂閱、心跳、速率限制）</li>
 *   <li>Interceptor：{@link MarketHandshakeInterceptor}（ticket 驗證 → 注入 userId）</li>
 *   <li>Origin：與 REST CORS 共用 {@code stock.cors.allowed-origins}</li>
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
    private final String[] allowedOrigins;

    /**
     * 建構子注入。
     *
     * @param handler     WebSocket 主要訊息處理器，不可為 null
     * @param interceptor    Handshake 攔截器（ticket 驗證），不可為 null
     * @param allowedOrigins 逗號分隔的允許來源，與 REST CORS 共用 {@code stock.cors.allowed-origins}
     */
    public WsConfig(MarketWebSocketHandler handler, MarketHandshakeInterceptor interceptor,
                    @Value("${stock.cors.allowed-origins:}") String allowedOrigins) {
        this.handler = handler;
        this.interceptor = interceptor;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toArray(String[]::new);
    }

    /**
     * 向 Spring 注冊 WebSocket handler 至 {@value #WS_PATH}，並掛上 interceptor。
     *
     * @param registry WebSocket handler registry
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        WebSocketHandlerRegistration registration = registry.addHandler(handler, WS_PATH)
                .addInterceptors(interceptor);
        // 與 REST 同一份白名單（安全審查 L-4）；未設定時維持 Spring 預設的同源限制
        if (allowedOrigins.length > 0) {
            registration.setAllowedOrigins(allowedOrigins);
        }
    }
}
