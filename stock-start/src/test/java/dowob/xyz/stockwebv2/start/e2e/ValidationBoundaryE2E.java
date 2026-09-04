package dowob.xyz.stockwebv2.start.e2e;

import dowob.xyz.stockwebv2.start.e2e.support.AbstractStockE2ETest;
import dowob.xyz.stockwebv2.start.e2e.support.AuthE2EHelper;
import dowob.xyz.stockwebv2.start.e2e.support.DatabaseCleaner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static dowob.xyz.stockwebv2.start.e2e.support.AuthE2EHelper.bearerToken;
import static dowob.xyz.stockwebv2.start.e2e.support.StockE2EAssertions.apiError;
import static dowob.xyz.stockwebv2.start.e2e.support.StockE2EAssertions.apiSuccess;
import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Validation Boundary E2E")
class ValidationBoundaryE2E extends AbstractStockE2ETest {

    @Autowired
    private AuthE2EHelper auth;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanUserData();
    }

    @Nested
    @DisplayName("A4 密碼規則組合")
    class PasswordRuleCombos {

        @Test
        @DisplayName("缺少小寫字母的密碼註冊失敗並回報 password 欄位")
        void passwordMissingLowercaseIsRejected() throws Exception {
            registerExpectingPasswordValidationFailure("a4-no-lower@example.com", "PASSWORD1");
        }

        @Test
        @DisplayName("缺少大寫字母的密碼註冊失敗並回報 password 欄位")
        void passwordMissingUppercaseIsRejected() throws Exception {
            registerExpectingPasswordValidationFailure("a4-no-upper@example.com", "password1");
        }

        @Test
        @DisplayName("缺少數字的密碼註冊失敗並回報 password 欄位")
        void passwordMissingDigitIsRejected() throws Exception {
            registerExpectingPasswordValidationFailure("a4-no-digit@example.com", "Password");
        }

        @Test
        @DisplayName("長度僅 7 碼的密碼註冊失敗並回報 password 欄位")
        void passwordWithSevenCharsIsRejected() throws Exception {
            registerExpectingPasswordValidationFailure("a4-too-short@example.com", "Pass1wd");
        }

        private void registerExpectingPasswordValidationFailure(String email, String password) throws Exception {
            mockMvc.perform(post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(registerBody(email, "validusername", password)))
                .andExpect(status().isBadRequest())
                .andExpect(apiError("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fields.password").exists());
        }
    }

    @Nested
    @DisplayName("A6 username 長度邊界")
    class UsernameBoundary {

        @Test
        @DisplayName("username 為 3 碼(下界)可註冊成功")
        void usernameWithThreeCharsIsAccepted() throws Exception {
            mockMvc.perform(post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(registerBody("a6-min@example.com", "u".repeat(3), "Password1")))
                .andExpect(status().isOk())
                .andExpect(apiSuccess());
        }

        @Test
        @DisplayName("username 為 50 碼(上界)可註冊成功")
        void usernameWithFiftyCharsIsAccepted() throws Exception {
            mockMvc.perform(post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(registerBody("a6-max@example.com", "u".repeat(50), "Password1")))
                .andExpect(status().isOk())
                .andExpect(apiSuccess());
        }

        @Test
        @DisplayName("username 為 2 碼(低於下界)註冊失敗並回報 username 欄位")
        void usernameWithTwoCharsIsRejected() throws Exception {
            mockMvc.perform(post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(registerBody("a6-below@example.com", "u".repeat(2), "Password1")))
                .andExpect(status().isBadRequest())
                .andExpect(apiError("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fields.username").exists());
        }

        @Test
        @DisplayName("username 為 51 碼(超過上界)註冊失敗並回報 username 欄位")
        void usernameWithFiftyOneCharsIsRejected() throws Exception {
            mockMvc.perform(post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(registerBody("a6-above@example.com", "u".repeat(51), "Password1")))
                .andExpect(status().isBadRequest())
                .andExpect(apiError("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fields.username").exists());
        }
    }

    @Nested
    @DisplayName("D3 initialCapital 下界")
    class InitialCapitalBoundary {

        @Test
        @DisplayName("initialCapital 為 0.01(最小正值)可建立回測並成功完成")
        void backtestRunWithMinimalPositiveInitialCapitalSucceeds() throws Exception {
            var session = auth.register("d3-capital@example.com", "d3capital", "Password1");

            mockMvc.perform(post("/api/v1/backtests/runs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .with(bearerToken(session.accessToken()))
                    .content("""
                        {"strategyId":"ma_cross","strategyCode":null,"symbol":"NVDA","period":"1Y","initialCapital":0.01,"currency":"USD","benchmark":"buy_hold","dataMode":"cached"}
                        """))
                .andExpect(status().isOk())
                .andExpect(apiSuccess())
                .andExpect(jsonPath("$.data.status", equalTo("succeeded")));
        }
    }

    @Nested
    @DisplayName("C7 交易備註長度邊界")
    class TradeNoteBoundary {

        @Test
        @DisplayName("note 為 500 字(上界)可成功建立交易")
        void tradeNoteWithFiveHundredCharsIsAccepted() throws Exception {
            var session = auth.register("c7-note-max@example.com", "c7notemax", "Password1");

            mockMvc.perform(post("/api/v1/trades")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .with(bearerToken(session.accessToken()))
                    .content(tradeBody("NVDA", "BUY", "1", "100", "n".repeat(500))))
                .andExpect(status().isOk())
                .andExpect(apiSuccess())
                .andExpect(jsonPath("$.data.symbol", equalTo("NVDA")));
        }

        @Test
        @DisplayName("note 為 501 字(超過上界)回 400 並回報 note 欄位")
        void tradeNoteWithFiveHundredOneCharsIsRejected() throws Exception {
            var session = auth.register("c7-note-over@example.com", "c7noteover", "Password1");

            mockMvc.perform(post("/api/v1/trades")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .with(bearerToken(session.accessToken()))
                    .content(tradeBody("NVDA", "BUY", "1", "100", "n".repeat(501))))
                .andExpect(status().isBadRequest())
                .andExpect(apiError("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fields.note").exists());
        }
    }

    @Nested
    @DisplayName("C8 不存在的標的")
    class NonexistentSymbol {

        @Test
        @DisplayName("對不存在的 symbol 下單回 404 ASSET_NOT_FOUND")
        void tradeOnNonexistentSymbolReturnsAssetNotFound() throws Exception {
            var session = auth.register("c8-nosuch@example.com", "c8nosuch", "Password1");

            mockMvc.perform(post("/api/v1/trades")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .with(bearerToken(session.accessToken()))
                    .content(tradeBody("NOSUCH", "BUY", "1", "100", null)))
                .andExpect(status().isNotFound())
                .andExpect(apiError("ASSET_NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("Finding #1 quantity/price 上限驗證(2026-07-17 Yuan 裁決方案 A:quantity ≤ 10^9、price ≤ 10^6)")
    class QuantityPriceUpperBound {

        @Test
        @DisplayName("quantity 為 10^15 → 400 且回報 quantity 欄位")
        void tradeQuantityAtTenToFifteenIsRejected() throws Exception {
            var session = auth.register("f1-qty-1e15@example.com", "f1qty1e15", "Password1");

            mockMvc.perform(post("/api/v1/trades")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .with(bearerToken(session.accessToken()))
                    .content(tradeBody("NVDA", "BUY", "1000000000000000", "100", null)))
                .andExpect(status().isBadRequest())
                .andExpect(apiError("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fields.quantity").exists());
        }

        @Test
        @DisplayName("price 為 10^15 → 400 且回報 price 欄位")
        void tradePriceAtTenToFifteenIsRejected() throws Exception {
            var session = auth.register("f1-price-1e15@example.com", "f1price1e15", "Password1");

            mockMvc.perform(post("/api/v1/trades")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .with(bearerToken(session.accessToken()))
                    .content(tradeBody("NVDA", "BUY", "1", "1000000000000000", null)))
                .andExpect(status().isBadRequest())
                .andExpect(apiError("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fields.price").exists());
        }

        @Test
        @DisplayName("quantity 為 10^17(原 DB overflow 回 500 案例)→ 400 且回報 quantity 欄位")
        void tradeQuantityAtTenToSeventeenIsRejected() throws Exception {
            var session = auth.register("f1-qty-1e17@example.com", "f1qty1e17", "Password1");

            mockMvc.perform(post("/api/v1/trades")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .with(bearerToken(session.accessToken()))
                    .content(tradeBody("NVDA", "BUY", "100000000000000000", "100", null)))
                .andExpect(status().isBadRequest())
                .andExpect(apiError("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fields.quantity").exists());
        }

        @Test
        @DisplayName("quantity 恰為上限 10^9 → 成功")
        void tradeQuantityAtUpperBoundSucceeds() throws Exception {
            var session = auth.register("f1-qty-max@example.com", "f1qtymax", "Password1");

            mockMvc.perform(post("/api/v1/trades")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .with(bearerToken(session.accessToken()))
                    .content(tradeBody("NVDA", "BUY", "1000000000", "100", null)))
                .andExpect(status().isOk())
                .andExpect(apiSuccess());
        }

        @Test
        @DisplayName("quantity 超過上限一個最小刻度(10^9 + 0.00000001)→ 400")
        void tradeQuantityJustAboveUpperBoundIsRejected() throws Exception {
            var session = auth.register("f1-qty-over@example.com", "f1qtyover", "Password1");

            mockMvc.perform(post("/api/v1/trades")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .with(bearerToken(session.accessToken()))
                    .content(tradeBody("NVDA", "BUY", "1000000000.00000001", "100", null)))
                .andExpect(status().isBadRequest())
                .andExpect(apiError("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fields.quantity").exists());
        }

        @Test
        @DisplayName("price 恰為上限 10^6 → 成功")
        void tradePriceAtUpperBoundSucceeds() throws Exception {
            var session = auth.register("f1-price-max@example.com", "f1pricemax", "Password1");

            mockMvc.perform(post("/api/v1/trades")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .with(bearerToken(session.accessToken()))
                    .content(tradeBody("NVDA", "BUY", "1", "1000000", null)))
                .andExpect(status().isOk())
                .andExpect(apiSuccess());
        }

        @Test
        @DisplayName("price 超過上限一個最小刻度(10^6 + 0.00000001)→ 400")
        void tradePriceJustAboveUpperBoundIsRejected() throws Exception {
            var session = auth.register("f1-price-over@example.com", "f1priceover", "Password1");

            mockMvc.perform(post("/api/v1/trades")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .with(bearerToken(session.accessToken()))
                    .content(tradeBody("NVDA", "BUY", "1", "1000000.00000001", null)))
                .andExpect(status().isBadRequest())
                .andExpect(apiError("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fields.price").exists());
        }

        @Test
        @DisplayName("quantity 小數超過 8 位(第 9 位)→ 400 且回報 quantity 欄位")
        void tradeQuantityWithNineDecimalsIsRejected() throws Exception {
            var session = auth.register("f1-qty-scale@example.com", "f1qtyscale", "Password1");

            mockMvc.perform(post("/api/v1/trades")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .with(bearerToken(session.accessToken()))
                    .content(tradeBody("NVDA", "BUY", "1.123456789", "100", null)))
                .andExpect(status().isBadRequest())
                .andExpect(apiError("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fields.quantity").exists());
        }
    }

    private String registerBody(String email, String username, String password) {
        return objectMapper.writeValueAsString(Map.of(
            "email", email,
            "username", username,
            "password", password
        ));
    }

    private String tradeBody(String symbol, String type, String quantity, String price, String note) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("symbol", symbol);
        body.put("type", type);
        body.put("quantity", new java.math.BigDecimal(quantity));
        body.put("price", new java.math.BigDecimal(price));
        body.put("fee", 0);
        if (note != null) {
            body.put("note", note);
        }
        return objectMapper.writeValueAsString(body);
    }
}
