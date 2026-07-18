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
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認證流程稽核日誌覆蓋的整合測試（security.md §13）。
 *
 * <p>以 logback {@link ListAppender} 掛載於 {@code AUDIT} logger，驗證登入成功與失敗皆
 * 實際輸出符合規定格式的稽核事件（證明 AuditLogger 已接進實際請求路徑）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@AutoConfigureMockMvc
@DisplayName("認證流程稽核日誌覆蓋")
class AuditLoggingIT extends ContainerIT {

    @Autowired
    MockMvc mockMvc;

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
}
