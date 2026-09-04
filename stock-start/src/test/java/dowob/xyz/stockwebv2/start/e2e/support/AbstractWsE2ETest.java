package dowob.xyz.stockwebv2.start.e2e.support;

import dowob.xyz.stockwebv2.start.support.ContainerIT;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * WebSocket E2E 測試基礎類。
 *
 * <p>使用 {@link SpringBootTest.WebEnvironment#RANDOM_PORT} 啟動真實 HTTP server，
 * 以便 WebSocket client 能建立實際連線（MockMvc 不支援 WebSocket upgrade）。
 *
 * <p>使用 {@link RestTemplate} 進行 HTTP 呼叫（不依賴
 * {@code @AutoConfigureTestRestTemplate}，避免 Spring Boot 4 autoconfigure 的相容性問題）。
 *
 * @author Yuan
 * @version 1.0.0
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("e2e")
public abstract class AbstractWsE2ETest extends ContainerIT {

    @LocalServerPort
    protected int port;

    /**
     * 標準 {@link RestTemplate}，於 {@link #setUp()} 中初始化，避免依賴 Spring Boot autoconfigure。
     */
    protected RestTemplate restTemplate = new RestTemplate();

    @Autowired
    protected ObjectMapper objectMapper;

    /**
     * 回傳完整 base URL，格式為 {@code http://localhost:{port}}。
     *
     * @return HTTP base URL
     */
    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    /**
     * 回傳 WebSocket URL，格式為 {@code ws://localhost:{port}/ws/v1/market}。
     *
     * @return WebSocket 端點 URL（不含 ticket 參數）
     */
    protected String wsBaseUrl() {
        return "ws://localhost:" + port + "/ws/v1/market";
    }

    /**
     * 向 {@code POST /api/v1/auth/register} 建立新使用者，並透過 token endpoint 回傳 access token。
     *
     * @param email    新使用者 email
     * @param username 新使用者名稱
     * @param password 密碼
     * @return access token 字串
     * @throws Exception 若 HTTP 呼叫或 JSON 解析失敗
     */
    protected String registerAndGetToken(String email, String username, String password) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = Map.of("email", email, "username", username, "password", password);
        HttpEntity<String> request = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);

        restTemplate.postForEntity(
            baseUrl() + "/api/v1/auth/register", request, String.class);
        return token(email, password).accessToken();
    }

    /**
     * 向 {@code POST /api/v1/auth/token} 登錄並回傳 {@link AuthSession}（含 accessToken 與 refreshToken）。
     *
     * @param email    使用者 email
     * @param password 密碼
     * @return {@link AuthSession} 包含 access/refresh token
     * @throws Exception 若 HTTP 呼叫或 JSON 解析失敗
     */
    protected AuthSession login(String email, String password) throws Exception {
        return token(email, password);
    }

    private AuthSession token(String email, String password) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = Map.of("email", email, "password", password);
        HttpEntity<String> request = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);

        ResponseEntity<String> response = restTemplate.postForEntity(
            baseUrl() + "/api/v1/auth/token", request, String.class);
        JsonNode data = objectMapper.readTree(response.getBody()).get("data");
        return new AuthSession(data.get("accessToken").asText(), data.get("refreshToken").asText());
    }

    /**
     * 向 {@code POST /api/v1/auth/register} 登錄並立即 login，回傳完整 {@link AuthSession}。
     *
     * @param email    使用者 email
     * @param username 使用者名稱
     * @param password 密碼
     * @return {@link AuthSession}
     * @throws Exception 若任何步驟失敗
     */
    protected AuthSession registerAndLogin(String email, String username, String password) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = Map.of("email", email, "username", username, "password", password);
        HttpEntity<String> request = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);

        restTemplate.postForEntity(
            baseUrl() + "/api/v1/auth/register", request, String.class);
        return token(email, password);
    }

    /**
     * 以指定 access token 請求 WebSocket ticket。
     *
     * @param accessToken Bearer JWT access token
     * @return ticket 字串
     * @throws Exception 若請求失敗或回應格式不符
     */
    protected String requestWsTicket(String accessToken) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.postForEntity(
            baseUrl() + "/api/v1/market/ws/ticket", request, String.class);
        JsonNode data = objectMapper.readTree(response.getBody()).get("data");
        return data.get("ticket").asText();
    }

    /**
     * 登出使用者。
     *
     * @param accessToken  Bearer JWT access token
     * @param refreshToken 使用者 refresh token
     * @throws Exception 若請求失敗
     */
    protected void logout(String accessToken, String refreshToken) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);
        Map<String, String> body = Map.of("refreshToken", refreshToken);
        HttpEntity<String> request = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);
        restTemplate.postForEntity(baseUrl() + "/api/v1/auth/logout", request, String.class);
    }

    /**
     * 認證 session 資料容器，包含 access token 與 refresh token。
     *
     * @param accessToken  JWT access token
     * @param refreshToken JWT refresh token
     */
    public record AuthSession(String accessToken, String refreshToken) {}
}
