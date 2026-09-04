package dowob.xyz.stockwebv2.start.e2e;

import dowob.xyz.stockwebv2.start.e2e.support.AbstractStockE2ETest;
import dowob.xyz.stockwebv2.start.e2e.support.DatabaseCleaner;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static dowob.xyz.stockwebv2.start.e2e.support.StockE2EAssertions.apiError;
import static dowob.xyz.stockwebv2.start.e2e.support.StockE2EAssertions.apiSuccess;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;

/**
 * Backfill 管理員流程 E2E 測試。
 *
 * <p>驗證 backfill API 的端對端行為：
 * <ul>
 *   <li>USER 角色觸發 backfill → 403</li>
 *   <li>ADMIN 角色觸發 backfill → 202 + jobExecutionId</li>
 *   <li>ADMIN 查詢 job 狀態 → 最終 COMPLETED</li>
 * </ul>
 *
 * <p>ADMIN 使用者透過 JdbcTemplate 直接插入 DB，並使用 PasswordEncoder 生成有效密碼雜湊。
 * 不依賴 {@code AuthE2EHelper}，直接透過 MockMvc 呼叫 auth API。
 *
 * @author Yuan
 * @version 1.0.0
 */
@DisplayName("Backfill Admin Flow E2E")
class BackfillAdminFlowE2ETest extends AbstractStockE2ETest {

    private static final String ADMIN_EMAIL    = "admin-backfill-e2e@example.com";
    private static final String ADMIN_USERNAME = "adminbackfille2e";
    private static final String ADMIN_PASSWORD = "AdminPass1";
    private static final String USER_EMAIL     = "user-backfill-e2e@example.com";
    private static final String USER_USERNAME  = "userbackfille2e";
    private static final String USER_PASSWORD  = "UserPass1";

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanUserData();
    }

    /**
     * USER 角色呼叫 backfill 端點應回傳 403。
     *
     * <p>BackfillController 內部透過 {@code requireAdmin()} 驗證 ADMIN 角色，
     * USER 角色應觸發 {@code AUTH_FORBIDDEN}（403）。
     */
    @Test
    @DisplayName("USER 角色觸發 backfill → 403 AUTH_FORBIDDEN")
    void userRoleCannotTriggerBackfill() throws Exception {
        String userToken = registerUserAndGetToken(USER_EMAIL, USER_USERNAME, USER_PASSWORD);

        mockMvc.perform(post("/api/v1/market/backfill")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + userToken)
                .content(buildBackfillRequest("AAPL", "1m", 7)))
            .andExpect(status().isForbidden())
            .andExpect(apiError("AUTH_FORBIDDEN"));
    }

    /**
     * ADMIN 角色觸發 backfill 應回傳 202 及 jobExecutionId，
     * 並可透過狀態查詢端點查詢最終狀態。
     *
     * <p>此測試注入一個直接插入 DB 的 ADMIN 使用者，登錄取得 JWT 後觸發 backfill。
     * Backfill job 執行時 MockDataProvider（E2E profile 中 mock.enabled=true）提供歷史資料。
     */
    @Test
    @DisplayName("ADMIN 角色觸發 backfill → 202 + jobExecutionId，最終狀態為 COMPLETED")
    void adminRoleCanTriggerBackfillAndPollStatus() throws Exception {
        // 直接在 DB 插入 ADMIN 使用者
        insertAdminUser(ADMIN_EMAIL, ADMIN_USERNAME, ADMIN_PASSWORD);

        // 登錄取得 ADMIN JWT
        String adminToken = loginAndGetToken(ADMIN_EMAIL, ADMIN_PASSWORD);

        // 觸發 backfill — 7 天範圍、1m interval
        MvcResult triggerResult = mockMvc.perform(post("/api/v1/market/backfill")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + adminToken)
                .content(buildBackfillRequest("AAPL", "1m", 7)))
            .andExpect(status().isAccepted())
            .andExpect(apiSuccess())
            .andExpect(jsonPath("$.data.jobExecutionId", notNullValue()))
            .andReturn();

        // 取出 jobExecutionId
        String responseBody = triggerResult.getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(responseBody).get("data");
        long jobExecutionId = data.get("jobExecutionId").asLong();

        // 輪詢 job 狀態，等待 COMPLETED 或 FAILED（最多 60 秒）
        Awaitility.await()
            .atMost(60, TimeUnit.SECONDS)
            .pollInterval(2, TimeUnit.SECONDS)
            .until(() -> {
                MvcResult statusResult = mockMvc.perform(
                        get("/api/v1/market/backfill/" + jobExecutionId)
                            .header("Authorization", "Bearer " + adminToken))
                    .andReturn();
                String statusBody = statusResult.getResponse().getContentAsString();
                JsonNode statusData = objectMapper.readTree(statusBody).get("data");
                if (statusData == null) {
                    return false;
                }
                String jobStatus = statusData.get("status").asText();
                return "COMPLETED".equals(jobStatus) || "FAILED".equals(jobStatus);
            });

        // 驗證最終狀態為 COMPLETED
        MvcResult finalStatus = mockMvc.perform(
                get("/api/v1/market/backfill/" + jobExecutionId)
                    .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(apiSuccess())
            .andReturn();

        String finalBody = finalStatus.getResponse().getContentAsString();
        JsonNode finalData = objectMapper.readTree(finalBody).get("data");
        String finalJobStatus = finalData.get("status").asText();

        // MockDataProvider 應能正常提供資料，job 應 COMPLETED
        org.assertj.core.api.Assertions.assertThat(finalJobStatus)
            .as("Backfill job should complete successfully")
            .isEqualTo("COMPLETED");
    }

    /**
     * ADMIN 查詢不存在的 jobExecutionId 應回傳 404。
     */
    @Test
    @DisplayName("ADMIN 查詢不存在的 jobExecutionId → 404 BACKFILL_JOB_NOT_FOUND")
    void adminQueryNonExistentJobReturns404() throws Exception {
        insertAdminUser(ADMIN_EMAIL + ".notfound", ADMIN_USERNAME + "nf", ADMIN_PASSWORD);
        String adminToken = loginAndGetToken(ADMIN_EMAIL + ".notfound", ADMIN_PASSWORD);

        mockMvc.perform(get("/api/v1/market/backfill/999999")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isNotFound())
            .andExpect(apiError("BACKFILL_JOB_NOT_FOUND"));
    }

    /**
     * 直接透過 JdbcTemplate 插入 ADMIN 角色使用者。
     *
     * <p>繞過 user module 的 register API（預設只能建立 USER role），
     * 直接在 DB 層插入 ADMIN 使用者，並以 {@link PasswordEncoder} 生成有效密碼雜湊，
     * 確保後續 login API 可正常驗證。
     *
     * @param email    使用者 email
     * @param username 使用者名稱
     * @param password 明文密碼
     */
    private void insertAdminUser(String email, String username, String password) {
        String passwordHash = passwordEncoder.encode(password);
        jdbcTemplate.update(
            """
            INSERT INTO users (email, username, password_hash, role, status, token_version)
            VALUES (?, ?, ?, 'ADMIN', 'ACTIVE', 1)
            """,
            email, username, passwordHash
        );
    }

    /**
     * 透過 MockMvc 呼叫 register API 建帳，再透過 token endpoint 取出 access token（USER role）。
     *
     * @param email    使用者 email
     * @param username 使用者名稱
     * @param password 密碼
     * @return access token 字串
     * @throws Exception 若請求失敗
     */
    private String registerUserAndGetToken(String email, String username, String password) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "email", email, "username", username, "password", password
                ))))
            .andExpect(status().isOk())
            .andReturn();

        String response = mockMvc.perform(post("/api/v1/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "email", email, "password", password
                ))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readTree(response).get("data").get("accessToken").asText();
    }

    /**
     * 透過 MockMvc 呼叫 token endpoint 並取出 access token。
     *
     * @param email    使用者 email
     * @param password 密碼
     * @return access token 字串
     * @throws Exception 若請求失敗
     */
    private String loginAndGetToken(String email, String password) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "email", email, "password", password
                ))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readTree(response).get("data").get("accessToken").asText();
    }

    /**
     * 建立 backfill 請求 JSON。
     *
     * @param symbol   資產代號
     * @param interval K 線間隔
     * @param days     回填天數（相對於現在往前推算）
     * @return JSON 字串
     * @throws Exception 若序列化失敗
     */
    private String buildBackfillRequest(String symbol, String interval, int days) throws Exception {
        Instant to = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant from = to.minus(days, ChronoUnit.DAYS);
        return objectMapper.writeValueAsString(Map.of(
            "symbol", symbol,
            "from", from.toString(),
            "to", to.toString(),
            "interval", interval
        ));
    }
}
