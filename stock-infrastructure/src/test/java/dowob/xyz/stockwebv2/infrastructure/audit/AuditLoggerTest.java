package dowob.xyz.stockwebv2.infrastructure.audit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AuditLogger} 輸出格式的單元測試（security.md §13）。
 *
 * <p>以 logback {@link ListAppender} 掛載於 {@code AUDIT} logger，驗證輸出訊息與憲法規定的
 * 固定格式逐字相符。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("Audit 日誌格式")
class AuditLoggerTest {

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
    @DisplayName("輸出符合 [AUDIT] userId= action= target= result= ip= 的固定格式")
    void emitsMandatedFormat() {
        new AuditLogger().log(7L, "login", "user", "success", "1.2.3.4");

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage())
            .isEqualTo("[AUDIT] userId=7 action=login target=user result=success ip=1.2.3.4");
    }

    @Test
    @DisplayName("缺漏欄位以 - 佔位而不輸出 null")
    void nullFieldsBecomePlaceholder() {
        new AuditLogger().log(null, "login_failed", "user", "failure", null);

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage())
            .isEqualTo("[AUDIT] userId=- action=login_failed target=user result=failure ip=-");
    }
}
