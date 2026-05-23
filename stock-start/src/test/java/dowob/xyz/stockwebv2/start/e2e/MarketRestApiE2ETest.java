package dowob.xyz.stockwebv2.start.e2e;

import dowob.xyz.stockwebv2.start.e2e.support.AbstractStockE2ETest;
import dowob.xyz.stockwebv2.start.e2e.support.DatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static dowob.xyz.stockwebv2.start.e2e.support.StockE2EAssertions.apiError;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Market Data REST API E2E 整合測試。
 *
 * <p>驗證 market-data 模組正確接入 stock-start 應用程式，REST 端點可通過 JWT 驗證正常存取。
 *
 * <p>測試範圍：
 * <ul>
 *   <li>未帶 JWT 存取 market 端點 → 401</li>
 *   <li>帶有效 JWT 存取 {@code GET /api/v1/market/{symbol}/latest} → 200 或 404（資料庫無資料時）</li>
 *   <li>帶有效 JWT 存取 {@code GET /api/v1/market/latest?symbols=...} → 200（批次查詢空結果合法）</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0.0
 */
@DisplayName("Market REST API E2E")
class MarketRestApiE2ETest extends AbstractStockE2ETest {

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanUserData();
    }

    /**
     * 未帶 JWT 存取 market latest 端點應回傳 401。
     *
     * <p>驗證 SecurityConfig 中 {@code anyRequest().authenticated()} 規則對 market 路徑生效。
     */
    @Test
    @DisplayName("未帶 JWT 存取 market latest 端點 → 401 AUTH_INVALID_CREDENTIALS")
    void unauthenticatedRequestToLatestReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/market/AAPL/latest"))
            .andExpect(status().isUnauthorized())
            .andExpect(apiError("AUTH_INVALID_CREDENTIALS"));
    }

    /**
     * 帶有效 JWT 存取 {@code GET /api/v1/market/{symbol}/latest} 應回傳 200 或 404。
     *
     * <p>E2E 環境無預植入 tick 資料，因此 Redis/DB 均無資料 → 期望 404（ASSET_NOT_FOUND）。
     * 這仍驗證了 wiring 正確：端點可被訪問、JWT 認證通過、服務層正常初始化。
     */
    @Test
    @DisplayName("帶有效 JWT 存取 /api/v1/market/{symbol}/latest → 200 或 404（無資料時）")
    void authenticatedLatestRequestReturns200Or404() throws Exception {
        String accessToken = registerAndGetToken("market-rest-e2e@example.com", "marketreste2e", "Password1");

        // 無 tick 資料時期望 404（ASSET_NOT_FOUND）；有資料時期望 200
        mockMvc.perform(get("/api/v1/market/AAPL/latest")
                .header("Authorization", "Bearer " + accessToken))
            .andExpect(result -> {
                int statusCode = result.getResponse().getStatus();
                if (statusCode != 200 && statusCode != 404) {
                    throw new AssertionError(
                        "Expected 200 or 404, but got: " + statusCode +
                        " body: " + result.getResponse().getContentAsString());
                }
            });
    }

    /**
     * 帶有效 JWT 存取批次查詢端點 {@code GET /api/v1/market/latest?symbols=...}，
     * 無資料時應回傳 200 空陣列（未知 symbol 略過，不報錯）。
     */
    @Test
    @DisplayName("帶有效 JWT 存取批次 latest → 200 + 空或含資料陣列")
    void authenticatedBatchLatestReturns200() throws Exception {
        String accessToken = registerAndGetToken("market-batch-e2e@example.com", "marketbatche2e", "Password1");

        mockMvc.perform(get("/api/v1/market/latest?symbols=AAPL,TSLA")
                .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk());
    }

    /**
     * 帶有效 JWT 存取 K 線端點，缺少必填參數 {@code from} 時應回傳 400。
     */
    @Test
    @DisplayName("帶有效 JWT 存取 klines 端點缺少必填參數 → 400")
    void klinesWithMissingParamsReturns400() throws Exception {
        String accessToken = registerAndGetToken("market-klines-e2e@example.com", "marketklinese2e", "Password1");

        mockMvc.perform(get("/api/v1/market/AAPL/klines?interval=1m")
                .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isBadRequest());
    }

    /**
     * 輔助方法：透過 MockMvc 呼叫 register API 並取出 access token。
     *
     * @param email    使用者 email
     * @param username 使用者名稱
     * @param password 密碼
     * @return access token 字串
     * @throws Exception 若請求失敗
     */
    private String registerAndGetToken(String email, String username, String password) throws Exception {
        String responseBody = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of(
                    "email", email,
                    "username", username,
                    "password", password
                ))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readTree(responseBody).get("data").get("accessToken").asText();
    }
}
