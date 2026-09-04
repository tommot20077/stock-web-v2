package dowob.xyz.stockwebv2.marketdata.ws;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 簽發與消耗 WebSocket 連線用一次性 ticket。
 *
 * <p>避免 JWT 暴露於 WebSocket URL query string，改以短期（30s）ticket 換取連線授權。
 * Ticket 以 Redis {@code GETDEL} 原子操作確保單次消耗 — 第二次 {@code consume} 必 miss。
 *
 * <p>Ticket 格式：32 bytes 隨機資料以 URL-safe Base64（無 padding）編碼，約 43 字元。
 * Redis key 格式：{@code ws:ticket:{ticket}}，TTL 預設 30 秒。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Service
public class WsTicketService {

    /** 預設 ticket TTL。 */
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(30);

    private static final String KEY_PREFIX = "ws:ticket:";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();
    private static final int TICKET_BYTES = 32;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    /**
     * 正式使用的建構子，使用預設 TTL（30 秒）。
     *
     * @param redisTemplate Spring Data Redis template，不可 null
     * @param objectMapper  Jackson 3.x ObjectMapper（內建 Java Time 支援），不可 null
     */
    @Autowired
    public WsTicketService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this(redisTemplate, objectMapper, DEFAULT_TTL);
    }

    /**
     * 測試用建構子，允許指定自訂 TTL 以加速過期相關測試。
     *
     * @param redisTemplate Spring Data Redis template，不可 null
     * @param objectMapper  Jackson 3.x ObjectMapper，不可 null
     * @param ttl           ticket 在 Redis 中的存活時間，不可 null
     */
    WsTicketService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, Duration ttl) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.ttl = Objects.requireNonNull(ttl, "ttl must not be null");
    }

    /**
     * 簽發新 ticket 並以指定 TTL 寫入 Redis。
     *
     * @param userId       簽發 ticket 的 user id，不可 null
     * @param tokenVersion 當下 JWT token version，不可 null
     * @return ticket 字串（URL-safe Base64 無 padding，約 43 字元）
     * @throws NullPointerException  若 {@code userId} 或 {@code tokenVersion} 為 null
     * @throws IllegalStateException 若序列化 payload 失敗（不應發生）
     */
    public String issue(Long userId, Integer tokenVersion) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(tokenVersion, "tokenVersion must not be null");

        String ticket = generateTicket();
        TicketPayload payload = new TicketPayload(userId, Instant.now(), tokenVersion);
        try {
            String json = objectMapper.writeValueAsString(payload);
            redisTemplate.opsForValue().set(KEY_PREFIX + ticket, json, ttl);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize ticket payload", e);
        }
        return ticket;
    }

    /**
     * 原子取出並刪除 ticket（Redis {@code GETDEL}）。
     *
     * <p>成功 → 回傳含 payload 的 {@code Optional}；
     * 失敗（過期 / 不存在 / 已用）→ 回 {@code Optional.empty()}。
     *
     * @param ticket 客戶端提供的 ticket 字串，不可 null
     * @return 若 ticket 有效則包含 {@link TicketPayload}，否則 empty
     * @throws NullPointerException 若 {@code ticket} 為 null
     */
    public Optional<TicketPayload> consume(String ticket) {
        Objects.requireNonNull(ticket, "ticket must not be null");

        String json = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + ticket);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, TicketPayload.class));
        } catch (JacksonException e) {
            // payload 損毀或格式不符 — 視為無效 ticket
            return Optional.empty();
        }
    }

    /**
     * 回傳此 service 實例使用的 TTL，供測試驗證用。
     *
     * @return ticket TTL
     */
    Duration ttl() {
        return ttl;
    }

    /**
     * 產生 32 bytes 隨機資料並以 URL-safe Base64（無 padding）編碼。
     *
     * @return 約 43 字元的 ticket 字串
     */
    private String generateTicket() {
        byte[] bytes = new byte[TICKET_BYTES];
        RANDOM.nextBytes(bytes);
        return BASE64_URL.encodeToString(bytes);
    }
}
