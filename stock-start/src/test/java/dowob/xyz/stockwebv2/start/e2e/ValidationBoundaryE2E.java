package dowob.xyz.stockwebv2.start.e2e;

import dowob.xyz.stockwebv2.start.e2e.support.AbstractStockE2E;
import dowob.xyz.stockwebv2.start.e2e.support.AuthE2EHelper;
import dowob.xyz.stockwebv2.start.e2e.support.DatabaseCleaner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

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
class ValidationBoundaryE2E extends AbstractStockE2E {

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
    @DisplayName("D3b initialCapital 上界與位數(BE-CR-01)")
    class InitialCapitalUpperBoundAndScale {

        @Test
        @DisplayName("initialCapital 為指數字串 \"1e999999999\" → 400 且回報 initialCapital 欄位(不得進入引擎)")
        void backtestRunWithExponentialInitialCapitalIsRejected() throws Exception {
            var session = auth.register("d3b-exp@example.com", "d3bexp", "Password1");

            createBacktestRun(session.accessToken(), "1e999999999")
                .andExpect(status().isBadRequest())
                .andExpect(apiError("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fields.initialCapital").exists());
        }

        @Test
        @DisplayName("initialCapital 為 7 位小數 → 400 且回報 initialCapital 欄位")
        void backtestRunWithSevenDecimalPlacesIsRejected() throws Exception {
            var session = auth.register("d3b-scale@example.com", "d3bscale", "Password1");

            createBacktestRun(session.accessToken(), "123456789012.1234567")
                .andExpect(status().isBadRequest())
                .andExpect(apiError("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fields.initialCapital").exists());
        }

        @Test
        @DisplayName("initialCapital 超過上限一個最小刻度(1e12 + 0.000001)→ 400 且回報 initialCapital 欄位")
        void backtestRunJustAboveUpperBoundIsRejected() throws Exception {
            var session = auth.register("d3b-over@example.com", "d3bover", "Password1");

            createBacktestRun(session.accessToken(), "1000000000000.000001")
                .andExpect(status().isBadRequest())
                .andExpect(apiError("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fields.initialCapital").exists());
        }

        @Test
        @DisplayName("initialCapital 恰為上限 1e12 → 成功完成")
        void backtestRunAtUpperBoundSucceeds() throws Exception {
            var session = auth.register("d3b-max@example.com", "d3bmax", "Password1");

            createBacktestRun(session.accessToken(), "1000000000000")
                .andExpect(status().isOk())
                .andExpect(apiSuccess())
                .andExpect(jsonPath("$.data.status", equalTo("succeeded")));
        }

        @Test
        @DisplayName("initialCapital 為最小刻度 0.000001 → 成功完成(drawdown 不除以 0)")
        void backtestRunAtMinimalScaleSucceeds() throws Exception {
            var session = auth.register("d3b-min@example.com", "d3bmin", "Password1");

            createBacktestRun(session.accessToken(), "0.000001")
                .andExpect(status().isOk())
                .andExpect(apiSuccess())
                .andExpect(jsonPath("$.data.status", equalTo("succeeded")));
        }

        /**
         * 沿用 D3 的請求本體,只把 {@code initialCapital} 換成 JSON 字串(D-07 金額走字串的正式路徑)。
         */
        private ResultActions createBacktestRun(String accessToken, String initialCapital) throws Exception {
            return mockMvc.perform(post("/api/v1/backtests/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .with(bearerToken(accessToken))
                .content("""
                    {"strategyId":"ma_cross","strategyCode":null,"symbol":"NVDA","period":"1Y","initialCapital":"%s","currency":"USD","benchmark":"buy_hold","dataMode":"cached"}
                    """.formatted(initialCapital)));
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

    /**
     * 交易請求 body。金額依前端契約送 JSON 字串(Phase 5 D-09),讓上限 / 小數位驗證走的是瀏覽器實際送的字串路徑;
     * JSON number 的相容行為由 {@code TradingApiIT} 等既有案例覆蓋。
     */
    private String tradeBody(String symbol, String type, String quantity, String price, String note) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("symbol", symbol);
        body.put("type", type);
        body.put("quantity", quantity);
        body.put("price", price);
        body.put("fee", "0");
        if (note != null) {
            body.put("note", note);
        }
        return objectMapper.writeValueAsString(body);
    }
}
