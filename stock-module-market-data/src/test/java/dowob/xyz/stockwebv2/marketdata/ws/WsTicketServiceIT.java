package dowob.xyz.stockwebv2.marketdata.ws;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link WsTicketService} 整合測試。
 *
 * <p>使用 Redis testcontainer 驗證：
 * <ul>
 *   <li>簽發 → 回傳非空 ticket</li>
 *   <li>消耗 → 一次性，第二次 miss</li>
 *   <li>過期 → TTL 過後 consume 回 empty</li>
 *   <li>tokenVersion 封裝正確保存於 payload</li>
 *   <li>null 參數 → NullPointerException</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0.0
 */
@Testcontainers
class WsTicketServiceIT {

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    static StringRedisTemplate template;
    static ObjectMapper om = new ObjectMapper();

    @BeforeAll
    static void setup() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)));
        factory.afterPropertiesSet();
        template = new StringRedisTemplate(factory);
    }

    @BeforeEach
    void flush() {
        template.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    // ── issue ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("issue：回傳非空且長度合理的 ticket（32 bytes → ~43 chars base64url）")
    void issue_returnsNonBlankTicket() {
        var svc = new WsTicketService(template, om);
        String ticket = svc.issue(42L, 1);
        assertThat(ticket).isNotBlank();
        // 32 bytes → ~43 chars in URL-safe base64
        assertThat(ticket.length()).isBetween(40, 50);
    }

    // ── consume ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("consume：簽發後首次 consume → 回傳正確 payload")
    void consume_returnsPayloadAfterIssue() {
        var svc = new WsTicketService(template, om);
        String ticket = svc.issue(42L, 5);
        var payload = svc.consume(ticket);
        assertThat(payload).isPresent();
        assertThat(payload.get().userId()).isEqualTo(42L);
        assertThat(payload.get().tokenVersion()).isEqualTo(5);
        assertThat(payload.get().issuedAt()).isNotNull();
    }

    @Test
    @DisplayName("consume：單次消耗 — 第二次 consume 回 empty")
    void consume_secondCallMisses_singleUseEnforced() {
        var svc = new WsTicketService(template, om);
        String ticket = svc.issue(42L, 1);
        assertThat(svc.consume(ticket)).isPresent();
        assertThat(svc.consume(ticket)).isEmpty();  // single-use
    }

    @Test
    @DisplayName("consume：未知 ticket → 回 empty")
    void consume_unknownTicket_returnsEmpty() {
        var svc = new WsTicketService(template, om);
        assertThat(svc.consume("never-issued-xyz")).isEmpty();
    }

    // ── TTL ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("issue：TTL 過期後 consume → 回 empty")
    void issue_expiresAfterTtl() throws InterruptedException {
        // 使用短 TTL 讓測試快速完成
        var svc = new WsTicketService(template, om, Duration.ofMillis(500));
        String ticket = svc.issue(42L, 1);
        Thread.sleep(800);
        assertThat(svc.consume(ticket)).isEmpty();
    }

    // ── null guard ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("issue：userId 為 null → NullPointerException")
    void issue_nullUserId_throwsNPE() {
        var svc = new WsTicketService(template, om);
        assertThatThrownBy(() -> svc.issue(null, 1))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("issue：tokenVersion 為 null → NullPointerException")
    void issue_nullTokenVersion_throwsNPE() {
        var svc = new WsTicketService(template, om);
        assertThatThrownBy(() -> svc.issue(42L, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("consume：ticket 為 null → NullPointerException")
    void consume_nullTicket_throwsNPE() {
        var svc = new WsTicketService(template, om);
        assertThatThrownBy(() -> svc.consume(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ── independence ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("multipleTickets：不同 user 的 ticket 互相獨立，不互相影響")
    void multipleTickets_areIndependent() {
        var svc = new WsTicketService(template, om);
        String t1 = svc.issue(1L, 1);
        String t2 = svc.issue(2L, 5);
        assertThat(t1).isNotEqualTo(t2);
        assertThat(svc.consume(t1).get().userId()).isEqualTo(1L);
        assertThat(svc.consume(t2).get().userId()).isEqualTo(2L);
    }
}
