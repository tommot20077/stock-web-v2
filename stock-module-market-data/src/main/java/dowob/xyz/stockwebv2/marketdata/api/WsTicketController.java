package dowob.xyz.stockwebv2.marketdata.api;

import dowob.xyz.stockwebv2.common.api.ApiMeta;
import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.infrastructure.web.TraceIdFilter;
import dowob.xyz.stockwebv2.marketdata.ws.WsTicketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 換取 WebSocket 連線一次性 ticket。需要 JWT 認證。
 *
 * <p>避免 JWT 出現在 WebSocket URL query string，使用一次性 ticket 換取連線授權。
 * Ticket TTL 為 30 秒，僅可消耗一次。
 *
 * <p>tokenVersion 來源：從 Redis key {@code user:auth:{userId}} 讀取，
 * 與 {@code JwtAuthenticationFilter} 驗證時使用的同一資料源保持一致，
 * 無需額外 DB roundtrip 或引入 user module 依賴。
 *
 * @author Yuan
 * @version 1.0.0
 */
@RestController
@RequestMapping("/api/v1/market/ws")
public class WsTicketController {

    private static final Logger log = LoggerFactory.getLogger(WsTicketController.class);
    private static final Logger audit = LoggerFactory.getLogger("AUDIT");

    /** Redis hash key 格式，與 SecurityConfig.JwtAuthenticationFilter 一致。 */
    private static final String AUTH_KEY_PREFIX = "user:auth:";

    /** WebSocket 連線端點的相對路徑。 */
    private static final String WS_URL = "/ws/v1/market";

    private final WsTicketService ticketService;
    private final StringRedisTemplate redisTemplate;

    /**
     * 建構 {@code WsTicketController}。
     *
     * @param ticketService  WsTicketService，不可 null
     * @param redisTemplate  StringRedisTemplate，用於讀取 tokenVersion，不可 null
     */
    public WsTicketController(WsTicketService ticketService, StringRedisTemplate redisTemplate) {
        this.ticketService = ticketService;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 簽發 WebSocket 連線 ticket。
     *
     * <p>從 Spring Security {@link Authentication} 取得 userId，
     * 再從 Redis 讀取當前 tokenVersion，呼叫 {@link WsTicketService#issue(Long, Integer)}
     * 取得 ticket 並回傳 {@link WsTicketResponse}。
     *
     * @param authentication Spring Security 注入的當前請求身份，不可 null
     * @return 包含 ticket、expiresIn、wsUrl 的 {@link ApiResponse}
     */
    @PostMapping("/ticket")
    public ApiResponse<WsTicketResponse> issueTicket(Authentication authentication) {
        Long userId = resolveUserId(authentication);
        Integer tokenVersion = resolveTokenVersion(userId);

        String ticket = ticketService.issue(userId, tokenVersion);
        audit.info("WS_TICKET_ISSUED actor={} ttl={}s", userId, WsTicketService.DEFAULT_TTL.getSeconds());

        WsTicketResponse body = new WsTicketResponse(
            ticket,
            WsTicketService.DEFAULT_TTL.getSeconds(),
            WS_URL
        );
        return ApiResponse.success(body, meta());
    }

    /**
     * 從 {@link Authentication} 解析 userId。
     *
     * @param authentication 當前請求身份
     * @return userId
     * @throws BusinessException 若 authentication 無效
     */
    private Long resolveUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS, ErrorCode.AUTH_INVALID_CREDENTIALS.defaultMessage());
        }
        try {
            return Long.valueOf(authentication.getName());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS, ErrorCode.AUTH_INVALID_CREDENTIALS.defaultMessage());
        }
    }

    /**
     * 從 Redis {@code user:auth:{userId}} hash 讀取 tokenVersion。
     *
     * <p>此 Redis key 由 {@code JwtAuthenticationFilter} 在認證時已驗證，
     * 因此此處讀取的值與 JWT 攜帶的 tokenVersion 一致。
     *
     * <p>若 Redis 讀不到對應 key 或 tokenVersion 欄位,代表認證狀態不一致
     * (例如 Redis 已被清空或 user 已 logout 但 JWT 還沒過期),直接拒絕
     * 簽發 ticket;否則 handshake 端會以 tokenVersion 為 null 必拒,導致
     * client 拿到一張永遠用不了的 ticket。
     *
     * @param userId 目標 user id
     * @return tokenVersion
     * @throws BusinessException 若 Redis 認證狀態缺失或不可讀
     */
    private Integer resolveTokenVersion(Long userId) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(AUTH_KEY_PREFIX + userId);
        if (entries == null || entries.isEmpty()) {
            log.warn("resolveTokenVersion: Redis auth state missing for userId={}", userId);
            throw new BusinessException(ErrorCode.AUTH_REDIS_UNAVAILABLE,
                ErrorCode.AUTH_REDIS_UNAVAILABLE.defaultMessage());
        }
        Object tv = entries.get("tokenVersion");
        if (tv == null) {
            log.warn("resolveTokenVersion: tokenVersion field absent for userId={}", userId);
            throw new BusinessException(ErrorCode.AUTH_REDIS_UNAVAILABLE,
                ErrorCode.AUTH_REDIS_UNAVAILABLE.defaultMessage());
        }
        try {
            return Integer.parseInt(tv.toString());
        } catch (NumberFormatException e) {
            log.warn("resolveTokenVersion: unparseable tokenVersion='{}' for userId={}", tv, userId);
            throw new BusinessException(ErrorCode.AUTH_REDIS_UNAVAILABLE,
                ErrorCode.AUTH_REDIS_UNAVAILABLE.defaultMessage());
        }
    }

    /**
     * 建立 {@link ApiMeta}，traceId 來自 MDC（由 {@code TraceIdFilter} 寫入）。
     *
     * @return ApiMeta
     */
    private ApiMeta meta() {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID);
        return new ApiMeta(traceId == null ? "missing-trace-id" : traceId, OffsetDateTime.now());
    }
}
