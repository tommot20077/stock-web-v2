package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.start.support.ContainerIT;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * 經反向代理（K3s ingress）進來的請求，稽核與限流看到的必須是原始客戶端 IP。
 *
 * <p>原本 {@code ClientIpResolver} 只取 {@code getRemoteAddr()}，又沒有設定
 * {@code server.forward-headers-strategy}：部署在 ingress 後面時，所有請求的來源都是 ingress 本身，
 * 以 IP 為鍵的登入限流與鎖定會把全站使用者當成同一個人一起鎖（2026-09-02 安全審查 M-2）。
 *
 * <p>修法不是自己去讀 {@code X-Forwarded-For}（那是任何人都能偽造的標頭），而是交給容器：
 * Tomcat 只在直接連線端屬於內網代理時才採信轉發標頭。本測試從 127.0.0.1 發出真實 HTTP 請求，
 * 驗證轉發標頭被採信後稽核日誌記下的是原始 IP。
 *
 * <p>必須用真實 HTTP（RANDOM_PORT）：MockMvc 不經過 Tomcat 的 RemoteIpValve，驗不到這件事。
 *
 * @author Yuan
 * @version 1.0.0
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("代理後方的原始客戶端 IP")
class ForwardedClientIpIT extends ContainerIT {

    private static final String ORIGINAL_CLIENT_IP = "203.0.113.7";

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
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
    @DisplayName("內網代理轉發的請求，稽核日誌記錄 X-Forwarded-For 中的原始客戶端 IP")
    void auditRecordsOriginalClientIpBehindProxy() throws Exception {
        post("/api/v1/auth/register",
            "{\"email\":\"forwarded-ip@example.com\",\"username\":\"forwardedip\",\"password\":\"Password1\"}");
        appender.list.clear();

        HttpResponse<String> response = post("/api/v1/auth/login",
            "{\"email\":\"forwarded-ip@example.com\",\"password\":\"WrongPass1\"}");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(appender.list)
            .extracting(ILoggingEvent::getFormattedMessage)
            .anyMatch(message -> message.contains("action=login") && message.contains("ip=" + ORIGINAL_CLIENT_IP));
    }

    /**
     * 以 ingress 的身分（本機 127.0.0.1 直連，並帶上轉發標頭）送出 JSON POST。
     *
     * @param path 請求路徑
     * @param body JSON 內容
     * @return HTTP 回應
     * @throws Exception 請求失敗時
     */
    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json")
            .header("X-Forwarded-For", ORIGINAL_CLIENT_IP)
            .header("X-Forwarded-Proto", "https")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
