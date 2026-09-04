package dowob.xyz.stockwebv2.start;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dowob.xyz.stockwebv2.start.support.ContainerIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認證流程稽核日誌覆蓋的整合測試（security.md §13）。
 *
 * <p>以 logback {@link ListAppender} 掛載於 {@code AUDIT} logger，驗證各寫入端點實際輸出符合規定
 * 格式的稽核事件（證明 AuditLogger 已接進實際請求路徑）。</p>
 *
 * <p>涵蓋登入成功／失敗，以及 2026-09-02 功能審查 M-3 指出的三個缺口：ADMIN 解鎖、建立回測、
 * 觸發 backfill —— 這三個都是<strong>改變系統狀態</strong>的操作，卻沒有留下「誰在什麼時候做了什麼」。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@AutoConfigureMockMvc
@DisplayName("認證流程稽核日誌覆蓋")
class AuditLoggingIT extends ContainerIT {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    PasswordEncoder passwordEncoder;

    private Logger auditLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        auditLogger = (Logger) LoggerFactory.getLogger("AUDIT");
        appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        auditLogger.detachAppender(appender);
    }

    @Test
    @DisplayName("登入成功輸出符合格式的稽核事件")
    void successfulLoginEmitsAuditEvent() throws Exception {
        register("audit-login@example.com", "auditlogin", "Password1");
        appender.list.clear();

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"audit-login@example.com","password":"Password1"}
                    """))
            .andExpect(status().isOk());

        assertThat(appender.list)
            .extracting(ILoggingEvent::getFormattedMessage)
            .anyMatch(message -> message.matches(
                "\\[AUDIT] userId=\\d+ action=login target=user result=success ip=\\S+"));
    }

    @Test
    @DisplayName("登入失敗輸出稽核事件且不揭露帳號 id")
    void failedLoginEmitsAuditEvent() throws Exception {
        register("audit-fail@example.com", "auditfail", "Password1");
        appender.list.clear();

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"audit-fail@example.com","password":"WrongPass1"}
                    """))
            .andExpect(status().isUnauthorized());

        assertThat(appender.list)
            .extracting(ILoggingEvent::getFormattedMessage)
            .anyMatch(message -> message.matches(
                "\\[AUDIT] userId=- action=login target=user result=failure:\\S+ ip=\\S+"));
    }

    @Test
    @DisplayName("註冊輸出稽核事件")
    void registerEmitsAuditEvent() throws Exception {
        appender.list.clear();

        register("audit-register@example.com", "auditregister", "Password1");

        assertThat(appender.list)
            .extracting(ILoggingEvent::getFormattedMessage)
            .anyMatch(message -> message.matches(
                "\\[AUDIT] userId=\\d+ action=register target=user result=success ip=\\S+"));
    }

    private void register(String email, String username, String password) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","username":"%s","password":"%s"}
                    """.formatted(email, username, password)))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("ADMIN 解鎖帳號輸出稽核事件，記下的是操作者而非被解鎖者")
    void adminUnlockEmitsAuditEvent() throws Exception {
        register("audit-unlock-target@example.com", "auditunlocktarget", "Password1");
        String targetUuid = uuidOf("audit-unlock-target@example.com");
        seedAdmin("audit-unlock-admin@example.com", "auditunlockadmin", "AdminPass1");
        String adminToken = tokenFor("audit-unlock-admin@example.com", "AdminPass1");
        appender.list.clear();

        mockMvc.perform(post("/api/admin/users/{uuid}/unlock", targetUuid)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk());

        assertThat(appender.list)
            .extracting(ILoggingEvent::getFormattedMessage)
            .anyMatch(message -> message.matches(
                "\\[AUDIT] userId=\\d+ action=admin_unlock target=user:\\S+ result=success ip=\\S+"));
    }

    @Test
    @DisplayName("建立回測輸出稽核事件")
    void createBacktestRunEmitsAuditEvent() throws Exception {
        register("audit-backtest@example.com", "auditbacktest", "Password1");
        String token = tokenFor("audit-backtest@example.com", "Password1");
        appender.list.clear();

        mockMvc.perform(post("/api/v1/backtests/runs")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(backtestRunBody()))
            .andExpect(status().isOk());

        assertThat(appender.list)
            .extracting(ILoggingEvent::getFormattedMessage)
            .anyMatch(message -> message.matches(
                "\\[AUDIT] userId=\\d+ action=backtest_create target=backtest:\\S+ result=success ip=\\S+"));
    }

    @Test
    @DisplayName("非 ADMIN 觸發 backfill 被拒時也留下稽核事件（誰嘗試過同樣要記）")
    void rejectedBackfillTriggerEmitsAuditEvent() throws Exception {
        register("audit-backfill@example.com", "auditbackfill", "Password1");
        String token = tokenFor("audit-backfill@example.com", "Password1");
        appender.list.clear();

        mockMvc.perform(post("/api/v1/market/backfill")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"symbol":"AAPL","interval":"1m","from":"2026-01-01T00:00:00Z","to":"2026-01-02T00:00:00Z"}
                    """))
            .andExpect(status().isForbidden());

        assertThat(appender.list)
            .extracting(ILoggingEvent::getFormattedMessage)
            .anyMatch(message -> message.matches(
                "\\[AUDIT] userId=\\d+ action=backfill_trigger target=\\S+ result=failure:\\S+ ip=\\S+"));
    }

    /**
     * @return 一份合法的建立回測請求 body
     */
    private String backtestRunBody() {
        return """
            {"strategyId":"ma_cross","strategyCode":null,"symbol":"AAPL","period":"3Y","initialCapital":100000,"currency":"USD","benchmark":"buy_hold","dataMode":"cached"}
            """;
    }

    /**
     * @param email    帳號 email
     * @param password 密碼
     * @return bearer access token
     * @throws Exception 取 token 失敗時
     */
    private String tokenFor(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"%s"}
                    """.formatted(email, password)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        JsonNode data = objectMapper.readTree(body).get("data");
        return data.get("accessToken").asText();
    }

    /**
     * 直接以 ADMIN 角色寫入一筆使用者（註冊端點不允許指定角色）。
     *
     * @param email    帳號 email
     * @param username 帳號名稱
     * @param password 密碼
     */
    private void seedAdmin(String email, String username, String password) {
        jdbcClient.sql("""
                insert into users(email, username, password_hash, role, status, token_version)
                values (:email, :username, :passwordHash, 'ADMIN', 'ACTIVE', 1)
                """)
            .param("email", email)
            .param("username", username)
            .param("passwordHash", passwordEncoder.encode(password))
            .update();
    }

    /**
     * @param email 帳號 email
     * @return 該帳號的對外 UUID
     */
    private String uuidOf(String email) {
        return jdbcClient.sql("select uuid from users where email = :email")
            .param("email", email)
            .query(String.class)
            .single();
    }
}
