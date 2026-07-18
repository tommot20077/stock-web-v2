package dowob.xyz.stockwebv2.marketdata.ws;

import dowob.xyz.stockwebv2.infrastructure.audit.AuditLogger;
import dowob.xyz.stockwebv2.infrastructure.web.ClientIpResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.Optional;

/**
 * WebSocket handshake 攔截器：從 query string 取 ticket，consume + 驗 tokenVersion，
 * 通過則注入 userId 至 session.attributes；失敗則設 HTTP 401 拒絕 handshake。
 *
 * <p>流程：
 * <ol>
 *   <li>從 URL query string 取出 {@code ?ticket=} 參數</li>
 *   <li>呼叫 {@link WsTicketService#consume(String)} 取出 {@link TicketPayload}（一次性）</li>
 *   <li>從 Redis {@code user:auth:{userId}} hash 讀取當前 {@code tokenVersion}</li>
 *   <li>比對 ticket 內的 {@code tokenVersion} 與 Redis 當前值是否一致</li>
 *   <li>通過 → 注入 {@code userId} 至 {@code attributes}，回傳 {@code true}</li>
 *   <li>任一失敗 → 設 HTTP 401，記錄 audit log，回傳 {@code false}</li>
 * </ol>
 *
 * @author Yuan
 * @version 1.0.0
 */
@Component
public class MarketHandshakeInterceptor implements HandshakeInterceptor {

    /** 注入至 WebSocket session attributes 的 userId key 名稱。 */
    public static final String ATTR_USER_ID = "userId";

    /** 注入至 WebSocket session attributes 的來源 IP key 名稱。 */
    public static final String ATTR_CLIENT_IP = "clientIp";

    /**
     * 注入至 WebSocket session attributes 的 tokenVersion key 名稱。
     * 供 {@link WebSocketAuthValidator} 週期性重新驗證是否已被撤銷（security.md §9）。
     */
    public static final String ATTR_TOKEN_VERSION = "tokenVersion";

    private static final Logger log = LoggerFactory.getLogger(MarketHandshakeInterceptor.class);
    private static final String USER_AUTH_KEY_PREFIX = "user:auth:";

    private final WsTicketService ticketService;
    private final StringRedisTemplate redisTemplate;
    private final WebSocketConnectionManager connectionManager;
    private final AuditLogger auditLogger;

    /**
     * 建構子注入。
     *
     * @param ticketService     WS ticket 服務，不可 null
     * @param redisTemplate     Spring Data Redis template，不可 null
     * @param connectionManager WebSocket 連線治理器，不可 null
     * @param auditLogger       稽核日誌輸出點，不可 null
     */
    public MarketHandshakeInterceptor(WsTicketService ticketService,
                                      StringRedisTemplate redisTemplate,
                                      WebSocketConnectionManager connectionManager,
                                      AuditLogger auditLogger) {
        this.ticketService = ticketService;
        this.redisTemplate = redisTemplate;
        this.connectionManager = connectionManager;
        this.auditLogger = auditLogger;
    }

    /**
     * 在 WebSocket handshake 前執行驗證。
     *
     * @param request    HTTP upgrade 請求
     * @param response   HTTP upgrade 回應
     * @param wsHandler  目標 WebSocket handler
     * @param attributes 將注入至 WebSocket session 的 attribute map
     * @return {@code true} 表示允許 handshake；{@code false} 表示拒絕
     */
    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        String clientIp = ClientIpResolver.resolve(request);
        String ticket = extractTicket(request);
        if (ticket == null || ticket.isBlank()) {
            return reject(response, HttpStatus.UNAUTHORIZED, "MISSING_TICKET", null, clientIp);
        }

        Optional<TicketPayload> payloadOpt = ticketService.consume(ticket);
        if (payloadOpt.isEmpty()) {
            return reject(response, HttpStatus.UNAUTHORIZED, "TICKET_INVALID_OR_EXPIRED", null, clientIp);
        }

        TicketPayload payload = payloadOpt.get();
        Integer currentTokenVersion = currentTokenVersionOf(payload.userId());
        if (currentTokenVersion == null || !currentTokenVersion.equals(payload.tokenVersion())) {
            return reject(response, HttpStatus.UNAUTHORIZED, "TOKEN_VERSION_MISMATCH", payload.userId(), clientIp);
        }

        if (connectionManager.globalLimitReached()) {
            return reject(response, HttpStatus.SERVICE_UNAVAILABLE, "GLOBAL_CONNECTION_LIMIT", payload.userId(), clientIp);
        }
        if (connectionManager.ipLimitReached(clientIp)) {
            return reject(response, HttpStatus.TOO_MANY_REQUESTS, "IP_CONNECTION_LIMIT", payload.userId(), clientIp);
        }

        attributes.put(ATTR_USER_ID, payload.userId());
        attributes.put(ATTR_CLIENT_IP, clientIp);
        attributes.put(ATTR_TOKEN_VERSION, payload.tokenVersion());
        return true;
    }

    /**
     * Handshake 完成後的回呼（無操作）。
     *
     * @param request   HTTP upgrade 請求
     * @param response  HTTP upgrade 回應
     * @param wsHandler 目標 WebSocket handler
     * @param exception handshake 過程中發生的例外（若有）
     */
    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        // no-op
    }

    /**
     * 從 HTTP 請求的 URI query string 中取出 {@code ticket} 參數值。
     *
     * @param request HTTP 請求
     * @return ticket 值；若不存在則回傳 {@code null}
     */
    private String extractTicket(ServerHttpRequest request) {
        String query = request.getURI().getQuery();
        if (query == null) {
            return null;
        }
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && "ticket".equals(part.substring(0, eq))) {
                return part.substring(eq + 1);
            }
        }
        return null;
    }

    /**
     * 從 Redis {@code user:auth:{userId}} hash 讀取當前 {@code tokenVersion}。
     *
     * @param userId 目標 user id
     * @return 當前 tokenVersion；若 Redis 中不存在或讀取失敗則回傳 {@code null}
     */
    private Integer currentTokenVersionOf(Long userId) {
        try {
            Object v = redisTemplate.opsForHash().get(USER_AUTH_KEY_PREFIX + userId, "tokenVersion");
            if (v == null) {
                return null;
            }
            return Integer.parseInt(v.toString());
        } catch (Exception e) {
            log.warn("Failed to read tokenVersion for userId={}: {}", userId, e.toString());
            return null;
        }
    }

    /**
     * 拒絕 handshake：設定指定 HTTP 狀態碼，以標準格式記錄稽核事件（security.md §13），
     * 並回傳 {@code false}。
     *
     * @param response HTTP 回應
     * @param status   要設定的 HTTP 狀態碼（401 認證失敗 / 429 每 IP 上限 / 503 全域上限）
     * @param reason   拒絕原因
     * @param userId   已知的使用者 id；未知時為 {@code null}
     * @param clientIp 來源 IP
     * @return 永遠回傳 {@code false}
     */
    private boolean reject(ServerHttpResponse response, HttpStatus status, String reason, Long userId, String clientIp) {
        response.setStatusCode(status);
        auditLogger.log(userId, "ws_handshake", "ws_connection", "failure:" + reason, clientIp);
        return false;
    }
}
