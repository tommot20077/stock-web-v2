package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.start.support.ContainerIT;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static dowob.xyz.stockwebv2.start.support.DecimalStringMatchers.decimalString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasLength;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code POST /api/v1/portfolio/share-adjustments} 的端到端整合測試（ADJ-01 tracer）。
 *
 * <p>鎖四條主線：配股改寫持倉（股數增加、總成本不變、均價攤薄，D-05）且 {@code share_adjustments} 恰一列；
 * 拆股依<strong>除權日起點持股</strong>換算，除權日之後的買入不參與（D-08，Pitfall 1）；
 * 無持倉記配股回 409 {@code TRADE_INSUFFICIENT_HOLDING}（D-06）；cookie 登入未帶 CSRF header 被擋。
 * 04.2-08 補上沖銷（只能沖銷最晚一筆、已沖銷 409、他人 404、同日依記錄順序疊加與倒著沖）、
 * 冪等 payload 比對 409 與 8 執行緒同 key 恰一列。</p>
 *
 * <p>AAPL 屬美股：除權日以紐約日曆日表示，生效瞬間為紐約午夜；買入的 {@code executedAt} 一律明確送出三天前，
 * 確保落在「今天 / 昨天」除權日起點之前。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("股數調整 API")
@AutoConfigureMockMvc
class ShareAdjustmentApiIT extends ContainerIT {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcClient jdbcClient;

    @Test
    @DisplayName("持有 1000 股 @100 記配股 50 股：200、持倉 1050 股 @95.23809524，share_adjustments 恰一列")
    void stockDividendDilutesAverageCost() throws Exception {
        String email = "adj-div-" + UUID.randomUUID() + "@example.com";
        String accessToken = register(email, "adjdiv" + UUID.randomUUID().toString().substring(0, 8));
        buy(accessToken, 1000, 100, threeDaysAgo());
        String today = LocalDate.now(NEW_YORK).toString();

        postAdjustment(accessToken, UUID.randomUUID().toString(), """
            {"symbol":"AAPL","type":"STOCK_DIVIDEND","quantity":50,"effectiveDate":"%s","note":"stock dividend"}
            """.formatted(today))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", equalTo(true)))
            .andExpect(jsonPath("$.data.id", hasLength(36)))
            .andExpect(jsonPath("$.data.symbol", equalTo("AAPL")))
            .andExpect(jsonPath("$.data.currency", equalTo("USD")))
            .andExpect(jsonPath("$.data.type", equalTo("STOCK_DIVIDEND")))
            .andExpect(jsonPath("$.data.quantityDelta", decimalString("50.00000000")))
            .andExpect(jsonPath("$.data.ratioNew").value(nullValue()))
            .andExpect(jsonPath("$.data.ratioOld").value(nullValue()))
            .andExpect(jsonPath("$.data.effectiveDate", equalTo(today)))
            .andExpect(jsonPath("$.data.reversed", equalTo(false)))
            .andExpect(jsonPath("$.data.reversalOfId").value(nullValue()))
            .andExpect(jsonPath("$.data.idempotencyKey").doesNotExist());

        mockMvc.perform(get("/api/v1/portfolio/holdings")
                .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].symbol", equalTo("AAPL")))
            .andExpect(jsonPath("$.data[0].totalQuantity", decimalString("1050.00000000")))
            .andExpect(jsonPath("$.data[0].avgCost", decimalString("95.23809524")));

        assertThat(adjustmentRows(email)).isEqualTo(1);
        assertThat(storedQuantityDelta(email)).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("除權日前 100 股、除權日後再買 50 股，記拆股 4:1（除權日 = 昨天）：ΔQ = +300，持倉 450 股")
    void splitIsComputedFromQuantityAsOfEffectiveDate() throws Exception {
        String email = "adj-split-" + UUID.randomUUID() + "@example.com";
        String accessToken = register(email, "adjsplit" + UUID.randomUUID().toString().substring(0, 8));
        buy(accessToken, 100, 400, threeDaysAgo());
        buy(accessToken, 50, 100, null);
        String yesterday = LocalDate.now(NEW_YORK).minusDays(1).toString();

        postAdjustment(accessToken, UUID.randomUUID().toString(), """
            {"symbol":"AAPL","type":"SPLIT","ratioNew":4,"ratioOld":1,"effectiveDate":"%s"}
            """.formatted(yesterday))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.type", equalTo("SPLIT")))
            .andExpect(jsonPath("$.data.ratioNew", equalTo(4)))
            .andExpect(jsonPath("$.data.ratioOld", equalTo(1)))
            .andExpect(jsonPath("$.data.quantityDelta", decimalString("300.00000000")));

        mockMvc.perform(get("/api/v1/portfolio/holdings")
                .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].totalQuantity", decimalString("450.00000000")));
    }

    @Test
    @DisplayName("沒有任何持倉記配股：409 TRADE_INSUFFICIENT_HOLDING，share_adjustments 無列")
    void stockDividendWithoutHoldingIsRejected() throws Exception {
        String email = "adj-none-" + UUID.randomUUID() + "@example.com";
        String accessToken = register(email, "adjnone" + UUID.randomUUID().toString().substring(0, 8));

        postAdjustment(accessToken, UUID.randomUUID().toString(), """
            {"symbol":"AAPL","type":"STOCK_DIVIDEND","quantity":50,"effectiveDate":"%s"}
            """.formatted(LocalDate.now(NEW_YORK)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success", equalTo(false)))
            .andExpect(jsonPath("$.error.code", equalTo("TRADE_INSUFFICIENT_HOLDING")));

        assertThat(adjustmentRows(email)).isZero();
    }

    @Test
    @DisplayName("cookie 登入但未帶 CSRF header 的 POST：403，share_adjustments 無新增（negative test）")
    void cookieAuthenticatedPostWithoutCsrfIsForbidden() throws Exception {
        String email = "adj-csrf-" + UUID.randomUUID() + "@example.com";
        register(email, "adjcsrf" + UUID.randomUUID().toString().substring(0, 8));
        Cookie accessCookie = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"Password1"}
                    """.formatted(email)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getCookie("stock_access");

        mockMvc.perform(post("/api/v1/portfolio/share-adjustments")
                .cookie(accessCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content("""
                    {"symbol":"AAPL","type":"STOCK_DIVIDEND","quantity":50,"effectiveDate":"%s"}
                    """.formatted(LocalDate.now(NEW_YORK))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.success", equalTo(false)))
            .andExpect(jsonPath("$.error.code", equalTo("AUTH_CSRF_TOKEN_INVALID")));

        assertThat(adjustmentRows(email)).isZero();
    }

    @Test
    @DisplayName("買 100 → 拆股 4:1（昨天）→ 沖銷：200、持倉回到 100 股 @400；再沖銷同一原列 → 409 SHARE_ADJUSTMENT_ALREADY_REVERSED")
    void reversingLatestSplitRestoresHoldingAndSecondReversalIsRejected() throws Exception {
        String email = "adj-rev-" + UUID.randomUUID() + "@example.com";
        String accessToken = register(email, "adjrev" + UUID.randomUUID().toString().substring(0, 8));
        buy(accessToken, 100, 400, threeDaysAgo());
        String yesterday = LocalDate.now(NEW_YORK).minusDays(1).toString();
        String splitId = createdId(postAdjustment(accessToken, UUID.randomUUID().toString(),
            splitBody(4, 1, yesterday, null)).andExpect(status().isOk()));

        postAdjustment(accessToken, UUID.randomUUID().toString(), splitBody(4, 1, yesterday, splitId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.type", equalTo("SPLIT")))
            .andExpect(jsonPath("$.data.quantityDelta", decimalString("300.00000000")))
            .andExpect(jsonPath("$.data.reversalOfId", equalTo(splitId)))
            .andExpect(jsonPath("$.data.reversed", equalTo(false)));

        mockMvc.perform(get("/api/v1/portfolio/holdings")
                .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].totalQuantity", decimalString("100.00000000")))
            .andExpect(jsonPath("$.data[0].avgCost", decimalString("400.00000000")));

        postAdjustment(accessToken, UUID.randomUUID().toString(), splitBody(4, 1, yesterday, splitId))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code", equalTo("SHARE_ADJUSTMENT_ALREADY_REVERSED")));

        assertThat(adjustmentRows(email)).isEqualTo(2);
    }

    @Test
    @DisplayName("配股 A（昨天）→ 配股 B（今天）→ 沖銷 A：409 SHARE_ADJUSTMENT_NOT_REVERSIBLE；沖銷 B 成功、持倉扣回 B")
    void onlyTheLatestAdjustmentCanBeReversed() throws Exception {
        String email = "adj-latest-" + UUID.randomUUID() + "@example.com";
        String accessToken = register(email, "adjlatest" + UUID.randomUUID().toString().substring(0, 8));
        buy(accessToken, 100, 100, threeDaysAgo());
        String yesterday = LocalDate.now(NEW_YORK).minusDays(1).toString();
        String today = LocalDate.now(NEW_YORK).toString();
        String first = createdId(postAdjustment(accessToken, UUID.randomUUID().toString(),
            dividendBody(10, yesterday, null)).andExpect(status().isOk()));
        String second = createdId(postAdjustment(accessToken, UUID.randomUUID().toString(),
            dividendBody(5, today, null)).andExpect(status().isOk()));

        postAdjustment(accessToken, UUID.randomUUID().toString(), dividendBody(10, yesterday, first))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code", equalTo("SHARE_ADJUSTMENT_NOT_REVERSIBLE")));

        postAdjustment(accessToken, UUID.randomUUID().toString(), dividendBody(5, today, second))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.reversalOfId", equalTo(second)));

        mockMvc.perform(get("/api/v1/portfolio/holdings")
                .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].totalQuantity", decimalString("110.00000000")));
    }

    @Test
    @DisplayName("使用者 B 沖銷 A 的調整：404 SHARE_ADJUSTMENT_NOT_FOUND，回應不含 A 的 uuid，A 的持倉不變")
    void reversingAnotherUsersAdjustmentIsNotFound() throws Exception {
        String ownerEmail = "adj-owner-" + UUID.randomUUID() + "@example.com";
        String ownerToken = register(ownerEmail, "adjowner" + UUID.randomUUID().toString().substring(0, 8));
        buy(ownerToken, 100, 100, threeDaysAgo());
        String yesterday = LocalDate.now(NEW_YORK).minusDays(1).toString();
        String ownerAdjustment = createdId(postAdjustment(ownerToken, UUID.randomUUID().toString(),
            splitBody(2, 1, yesterday, null)).andExpect(status().isOk()));

        String intruderToken = register("adj-intruder-" + UUID.randomUUID() + "@example.com",
            "adjintr" + UUID.randomUUID().toString().substring(0, 8));
        buy(intruderToken, 100, 100, threeDaysAgo());
        String body = postAdjustment(intruderToken, UUID.randomUUID().toString(),
                splitBody(2, 1, yesterday, ownerAdjustment))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code", equalTo("SHARE_ADJUSTMENT_NOT_FOUND")))
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertThat(body).doesNotContain(ownerAdjustment);

        mockMvc.perform(get("/api/v1/portfolio/holdings")
                .header("Authorization", "Bearer " + ownerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].totalQuantity", decimalString("200.00000000")));
        assertThat(adjustmentRows(ownerEmail)).isEqualTo(1);
    }

    @Test
    @DisplayName("100 股、同一除權日記兩次 2:1 拆股：400 股（同日依記錄順序疊加）；沖銷依記錄順序倒著來，先沖第一筆 409")
    void sameDaySplitsStackInRecordOrderAndReverseBackwards() throws Exception {
        String email = "adj-sameday-" + UUID.randomUUID() + "@example.com";
        String accessToken = register(email, "adjsame" + UUID.randomUUID().toString().substring(0, 8));
        buy(accessToken, 100, 100, threeDaysAgo());
        String today = LocalDate.now(NEW_YORK).toString();

        String first = createdId(postAdjustment(accessToken, UUID.randomUUID().toString(),
                splitBody(2, 1, today, null))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.quantityDelta", decimalString("100.00000000"))));
        String second = createdId(postAdjustment(accessToken, UUID.randomUUID().toString(),
                splitBody(2, 1, today, null))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.quantityDelta", decimalString("200.00000000"))));
        assertHolding(accessToken, 400);

        postAdjustment(accessToken, UUID.randomUUID().toString(), splitBody(2, 1, today, first))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code", equalTo("SHARE_ADJUSTMENT_NOT_REVERSIBLE")));

        postAdjustment(accessToken, UUID.randomUUID().toString(), splitBody(2, 1, today, second))
            .andExpect(status().isOk());
        assertHolding(accessToken, 200);
        postAdjustment(accessToken, UUID.randomUUID().toString(), splitBody(2, 1, today, first))
            .andExpect(status().isOk());
        assertHolding(accessToken, 100);

        /* 兩筆都已沖銷後再記一次同日 2:1：Q_asOf = 100 + (100 − 100) + (200 − 200) = 100 → ΔQ +100。 */
        postAdjustment(accessToken, UUID.randomUUID().toString(), splitBody(2, 1, today, null))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.quantityDelta", decimalString("100.00000000")));
        assertHolding(accessToken, 200);
    }

    @Test
    @DisplayName("8 條併發的同 key 同 body 配股：全部 200 且同一 id、share_adjustments 恰一列、持倉只增加一次")
    void concurrentSameKeyCreatesExactlyOneAdjustment() throws Exception {
        String email = "adj-concurrent-" + UUID.randomUUID() + "@example.com";
        String accessToken = register(email, "adjconc" + UUID.randomUUID().toString().substring(0, 8));
        buy(accessToken, 100, 100, threeDaysAgo());
        String key = "adj-concurrent-" + UUID.randomUUID();
        String body = dividendBody(10, LocalDate.now(NEW_YORK).toString(), null);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> responses = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                responses.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    var response = postAdjustment(accessToken, key, body).andReturn().getResponse();
                    return response.getStatus() + " " + response.getContentAsString();
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<String> ids = new ArrayList<>();
            for (Future<String> response : responses) {
                String raw = response.get(30, TimeUnit.SECONDS);
                assertThat(raw).startsWith("200 ");
                ids.add(objectMapper.readTree(raw.substring(4)).get("data").get("id").asText());
            }
            /* 斷言不變量（只有一個 id）而非時序：哪一條 thread 贏得 insert 不確定，「大家看到同一筆」才是契約。 */
            assertThat(ids).containsOnly(ids.getFirst());
        } finally {
            pool.shutdownNow();
        }

        assertThat(adjustmentRows(email)).isEqualTo(1);
        assertHolding(accessToken, 110);
    }

    @Test
    @DisplayName("同 key 不同 payload：409 SHARE_ADJUSTMENT_IDEMPOTENCY_KEY_REUSED，持倉與列數不變；只改 note 重送回既有那筆")
    void sameKeyWithDifferentPayloadIsRejectedAsReuse() throws Exception {
        String email = "adj-reuse-" + UUID.randomUUID() + "@example.com";
        String accessToken = register(email, "adjreuse" + UUID.randomUUID().toString().substring(0, 8));
        buy(accessToken, 100, 100, threeDaysAgo());
        String today = LocalDate.now(NEW_YORK).toString();
        String key = "adj-reuse-" + UUID.randomUUID();
        String id = createdId(postAdjustment(accessToken, key, dividendBody(10, today, null))
            .andExpect(status().isOk()));

        String error = postAdjustment(accessToken, key, dividendBody(11, today, null))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code", equalTo("SHARE_ADJUSTMENT_IDEMPOTENCY_KEY_REUSED")))
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertThat(error).doesNotContain(key);

        postAdjustment(accessToken, key, """
            {"symbol":"AAPL","type":"STOCK_DIVIDEND","quantity":10.00000000,"effectiveDate":"%s","note":"changed"}
            """.formatted(today))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id", equalTo(id)));

        assertThat(adjustmentRows(email)).isEqualTo(1);
        assertHolding(accessToken, 110);
    }

    @Test
    @DisplayName("缺少 Idempotency-Key：400 VALIDATION_FAILED、error.fields['Idempotency-Key']，share_adjustments 無列")
    void missingIdempotencyKeyIsRejectedWithFieldError() throws Exception {
        String email = "adj-nokey-" + UUID.randomUUID() + "@example.com";
        String accessToken = register(email, "adjnokey" + UUID.randomUUID().toString().substring(0, 8));
        buy(accessToken, 100, 100, threeDaysAgo());

        mockMvc.perform(post("/api/v1/portfolio/share-adjustments")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accessToken)
                .content(dividendBody(10, LocalDate.now(NEW_YORK).toString(), null)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.error.fields['Idempotency-Key']", notNullValue()));

        assertThat(adjustmentRows(email)).isZero();
    }

    @Test
    @DisplayName("除權日早於 1900-01-01（1800-01-01）：400 VALIDATION_FAILED、error.fields.effectiveDate，不再 500（04.2 WR-04）")
    void effectiveDateBeforeFloorIsRejectedWithFieldError() throws Exception {
        String email = "adj-floor-" + UUID.randomUUID() + "@example.com";
        String accessToken = register(email, "adjfloor" + UUID.randomUUID().toString().substring(0, 8));
        buy(accessToken, 100, 100, threeDaysAgo());

        postAdjustment(accessToken, UUID.randomUUID().toString(), dividendBody(10, "1800-01-01", null))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.error.fields.effectiveDate", notNullValue()));

        assertThat(adjustmentRows(email)).isZero();
        assertHolding(accessToken, 100);
    }

    @Test
    @DisplayName("〔WR-01〕標的設為不可交易後：沖銷最晚一筆拆股 200、持倉扣回、同 key 重送回同一列；新建調整 404 ASSET_NOT_FOUND（quick-261107-1j4）")
    void reversalAndReplaySucceedAfterAssetBecomesUntradeable() throws Exception {
        String email = "adj-halt-" + UUID.randomUUID() + "@example.com";
        String accessToken = register(email, "adjhalt" + UUID.randomUUID().toString().substring(0, 8));
        mockMvc.perform(post("/api/v1/trades")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accessToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content("""
                    {"symbol":"META","type":"BUY","quantity":100,"price":400,"fee":0,"executedAt":"%s"}
                    """.formatted(threeDaysAgo())))
            .andExpect(status().isOk());
        String yesterday = LocalDate.now(NEW_YORK).minusDays(1).toString();
        String split = """
            {"symbol":"META","type":"SPLIT","ratioNew":4,"ratioOld":1,"effectiveDate":"%s"%s}
            """;
        String splitId = createdId(postAdjustment(accessToken, UUID.randomUUID().toString(),
            split.formatted(yesterday, "")).andExpect(status().isOk()));

        /* 用種子資產 META 而非暫存資產：share_adjustments 是 append-only，引用它的暫存資產刪不掉，會破壞資產總數斷言。 */
        setAssetTradeable("META", false);
        try {
            String reversalKey = UUID.randomUUID().toString();
            String reversal = split.formatted(yesterday, reversalField(splitId));
            String reversalId = createdId(postAdjustment(accessToken, reversalKey, reversal)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reversalOfId", equalTo(splitId))));
            assertHolding(accessToken, 100);

            postAdjustment(accessToken, reversalKey, reversal)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", equalTo(reversalId)));
            assertHolding(accessToken, 100);

            postAdjustment(accessToken, UUID.randomUUID().toString(), split.formatted(yesterday, ""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", equalTo("ASSET_NOT_FOUND")))
                .andExpect(jsonPath("$.error.fields.symbol", equalTo("not tradeable")));

            assertThat(adjustmentRows(email)).isEqualTo(2);
        } finally {
            setAssetTradeable("META", true);
        }
    }

    private void setAssetTradeable(String symbol, boolean tradeable) {
        jdbcClient.sql("update assets set tradeable = :tradeable where symbol = :symbol")
            .param("tradeable", tradeable)
            .param("symbol", symbol)
            .update();
    }

    private void assertHolding(String accessToken, int expectedQuantity) throws Exception {
        mockMvc.perform(get("/api/v1/portfolio/holdings")
                .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].totalQuantity", decimalString(String.valueOf(expectedQuantity))));
    }

    private static String threeDaysAgo() {
        return Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /**
     * 以 bearer token 買入 AAPL。
     *
     * @param accessToken access token
     * @param quantity    股數
     * @param price       成交價
     * @param executedAt  成交時間（ISO-8601）；null 代表伺服器現在
     */
    private void buy(String accessToken, int quantity, int price, String executedAt) throws Exception {
        String executedAtField = executedAt == null ? "" : ",\"executedAt\":\"" + executedAt + "\"";
        mockMvc.perform(post("/api/v1/trades")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accessToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content("""
                    {"symbol":"AAPL","type":"BUY","quantity":%d,"price":%d,"fee":0%s}
                    """.formatted(quantity, price, executedAtField)))
            .andExpect(status().isOk());
    }

    private static String splitBody(int ratioNew, int ratioOld, String effectiveDate, String reversalOfId) {
        return """
            {"symbol":"AAPL","type":"SPLIT","ratioNew":%d,"ratioOld":%d,"effectiveDate":"%s"%s}
            """.formatted(ratioNew, ratioOld, effectiveDate, reversalField(reversalOfId));
    }

    private static String dividendBody(int quantity, String effectiveDate, String reversalOfId) {
        return """
            {"symbol":"AAPL","type":"STOCK_DIVIDEND","quantity":%d,"effectiveDate":"%s"%s}
            """.formatted(quantity, effectiveDate, reversalField(reversalOfId));
    }

    private static String reversalField(String reversalOfId) {
        return reversalOfId == null ? "" : ",\"reversalOfId\":\"" + reversalOfId + "\"";
    }

    private String createdId(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString())
            .get("data").get("id").asText();
    }

    private ResultActions postAdjustment(String accessToken, String idempotencyKey, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/portfolio/share-adjustments")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer " + accessToken)
            .header("Idempotency-Key", idempotencyKey)
            .content(body));
    }

    private int adjustmentRows(String email) {
        Integer rows = jdbcClient.sql("""
                SELECT count(*) FROM share_adjustments
                WHERE user_id = (SELECT id FROM users WHERE email = :email)
                """)
            .param("email", email)
            .query(Integer.class)
            .single();
        return rows;
    }

    private BigDecimal storedQuantityDelta(String email) {
        return jdbcClient.sql("""
                SELECT quantity_delta FROM share_adjustments
                WHERE user_id = (SELECT id FROM users WHERE email = :email)
                """)
            .param("email", email)
            .query(BigDecimal.class)
            .single();
    }

    /**
     * 註冊並登入，回傳 bearer access token。
     *
     * @param email    使用者 email
     * @param username 使用者名稱
     * @return access token
     */
    private String register(String email, String username) throws Exception {
        String password = "Password1";
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","username":"%s","password":"%s"}
                    """.formatted(email, username, password)))
            .andExpect(status().isOk());

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
}
