package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.start.support.ContainerIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class TradingApiIT extends ContainerIT {

    /**
     * 三筆固定交易的成交時間；刻意與插入順序（即 created_at 順序）不一致，
     * 用來證明排序與日期篩選是以 executed_at 而非 created_at 為準。
     */
    private static final String EXECUTED_AT_OLDEST = "2026-01-10T00:00:00Z";
    private static final String EXECUTED_AT_MIDDLE = "2026-02-10T00:00:00Z";
    private static final String EXECUTED_AT_NEWEST = "2026-03-10T00:00:00Z";

    /**
     * 冪等測試共用的成交時間。所有冪等 payload 都必須明確送出 {@code executedAt}（D-03 / D-07）：
     * 若省略讓後端補 {@code now()}，同一把 key 的重試 payload 每次都不同，
     * 「重試回既有交易」的測試會假性變成「payload 不符 → 409」，看起來綠但驗到的是別件事。
     */
    private static final String IDEMPOTENT_EXECUTED_AT = "2026-04-10T00:00:00Z";

    /**
     * 刻意可辨識的 idempotency key。錯誤回應只要把 key 回射出去，
     * {@code doesNotContain} 就會抓到，不必猜它會從 message、fields 還是 meta 漏出（T-04-03）。
     */
    private static final String IDEM_KEY_CANARY = "LEAK-CANARY-12345";

    /** 129 字元、且開頭帶 canary 的 key：超過服務層 128 字元上限一個字元（T-04-04）。 */
    private static final String IDEM_KEY_TOO_LONG =
        IDEM_KEY_CANARY + "L".repeat(129 - IDEM_KEY_CANARY.length());

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Test
    void tradingEndpointsRequireAuthentication() throws Exception {
        // 帶齊 header：讓這條專測「未帶憑證即 401」，而不是被缺 header 的 400 搶先攔下。
        mockMvc.perform(post("/api/v1/trades")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content(buyBody()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code", equalTo("AUTH_INVALID_CREDENTIALS")));
    }

    @Test
    void buyThenSellUpdatesHoldingsAndPortfolioSummary() throws Exception {
        AuthTokens tokens = register("trading-owner@example.com", "tradingowner", "Password1");

        String buyResponse = mockMvc.perform(post("/api/v1/trades")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + tokens.accessToken())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content(buyBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", equalTo(true)))
            .andExpect(jsonPath("$.data.id", notNullValue()))
            .andExpect(jsonPath("$.data.symbol", equalTo("AAPL")))
            .andExpect(jsonPath("$.data.type", equalTo("BUY")))
            // idempotency key 是請求層的實作細節，不得出現在交易 DTO（T-04-09）。
            .andExpect(jsonPath("$.data.idempotencyKey").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
        String buyId = objectMapper.readTree(buyResponse).get("data").get("id").asText();

        mockMvc.perform(get("/api/v1/trades?page=0&size=20&symbol=AAPL")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].id", equalTo(buyId)))
            .andExpect(jsonPath("$.data.totalElements", equalTo(1)));

        /*
         * 估值價由本測試自己寫進 market-data 的來源（market_prices 最新一列），而不是靠環境裡剛好有什麼。
         * 刻意選一個與 V2 seed（asset_latest_prices.price = 218.4）不同的數字：若哪天有人把估值改回讀
         * 那張死表，這裡會立刻紅。同時清掉 Redis latest cache，確保讀到的是本測試寫的值而非殘留快取。
         */
        seedMarketPrice("AAPL", new BigDecimal("200.00"));

        mockMvc.perform(get("/api/v1/portfolio/holdings")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].symbol", equalTo("AAPL")))
            .andExpect(jsonPath("$.data[0].totalQuantity", equalTo(10.00000000)))
            .andExpect(jsonPath("$.data[0].avgCost", equalTo(100.50000000)))
            .andExpect(jsonPath("$.data[0].marketPrice", equalTo(200.00000000)))
            // 10 × (200.00 − 100.50)
            .andExpect(jsonPath("$.data[0].unrealizedPnl", equalTo(995.00000000)));

        mockMvc.perform(post("/api/v1/trades")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + tokens.accessToken())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content("""
                    {"symbol":"AAPL","type":"SELL","quantity":4,"price":120,"fee":1,"note":"partial exit"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.type", equalTo("SELL")));

        mockMvc.perform(get("/api/v1/portfolio/summary")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.holdingCount", equalTo(1)))
            .andExpect(jsonPath("$.data.totalCostBasis", equalTo(603.00000000)))
            // 賣掉 4 股後剩 6 股：6 × 200.00
            .andExpect(jsonPath("$.data.totalMarketValue", equalTo(1200.00000000)))
            .andExpect(jsonPath("$.data.realizedPnl", equalTo(77.00000000)))
            // 1200.00 − 603.00
            .andExpect(jsonPath("$.data.unrealizedPnl", equalTo(597.00000000)));
    }

    /**
     * 為指定標的寫入一筆「此刻」的市價，作為持倉估值的權威來源。
     *
     * <p>{@code MarketDataFacade} 的取價順序是 Redis latest cache → {@code market_prices} 最新一列，
     * 所以這裡先刪掉快取鍵再寫 DB，避免同一批 IT 共用容器時讀到別的測試留下的殘值。
     *
     * @param symbol 標的代號
     * @param price  要寫入的市價
     */
    private void seedMarketPrice(String symbol, BigDecimal price) {
        Long assetId = jdbcClient.sql("select id from assets where symbol = :symbol")
            .param("symbol", symbol)
            .query(Long.class)
            .single();
        redisTemplate.delete("market:latest:" + assetId);
        jdbcClient.sql("""
                insert into market_prices(asset_id, time, price, volume)
                values (:assetId, now(), :price, 1000)
                on conflict (asset_id, time) do update set price = excluded.price
                """)
            .param("assetId", assetId)
            .param("price", price)
            .update();
    }

    @Test
    void fullyClosedPositionStillCountsRealizedPnlInSummary() throws Exception {
        AuthTokens tokens = register("trading-fullclose@example.com", "tradingfullclose", "Password1");

        mockMvc.perform(post("/api/v1/trades")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + tokens.accessToken())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content(buyBody()))
            .andExpect(status().isOk());

        // 全數賣出 → 部位平倉（total_quantity = 0）；realized_pnl 仍留在該 holdings row
        mockMvc.perform(post("/api/v1/trades")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + tokens.accessToken())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content("""
                    {"symbol":"AAPL","type":"SELL","quantity":10,"price":120,"fee":1,"note":"full exit"}
                    """))
            .andExpect(status().isOk());

        // 已實現損益 (120 - 100.5) * 10 - 1 = 194，即使部位已平倉、不再出現在 holdings 清單，
        // summary 的 realizedPnl / totalPnl 仍應計入（否則平倉即遺失已實現損益）。
        mockMvc.perform(get("/api/v1/portfolio/summary")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.holdingCount", equalTo(0)))
            .andExpect(jsonPath("$.data.realizedPnl", equalTo(194.00000000)))
            .andExpect(jsonPath("$.data.totalPnl", equalTo(194.00000000)));
    }

    @Test
    void sellRejectsOversell() throws Exception {
        AuthTokens tokens = register("trading-oversell@example.com", "tradingoversell", "Password1");

        mockMvc.perform(post("/api/v1/trades")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + tokens.accessToken())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content("""
                    {"symbol":"AAPL","type":"SELL","quantity":1,"price":120,"fee":0}
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code", equalTo("TRADE_INSUFFICIENT_HOLDING")));
    }

    @Test
    void concurrentFirstBuysMergeWithoutUniqueViolation() throws Exception {
        AuthTokens tokens = register("trading-concurrent@example.com", "tradingconcurrent", "Password1");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                /*
                 * 每條 thread 一把不同的 key：這條測的是「8 筆各自獨立的首次 BUY 併入同一持倉」，
                 * 共用同一把 key 會被冪等機制合併成 1 筆，總量變 1 而非 8，測到的就不是原本的
                 * 持倉 upsert 競態了。同 key 併發的驗收在 concurrentSameKeyCreatesExactlyOneTrade。
                 */
                String perThreadKey = "concurrent-first-buy-" + i;
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return mockMvc.perform(post("/api/v1/trades")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", "Bearer " + tokens.accessToken())
                            .header("Idempotency-Key", perThreadKey)
                            .content("""
                                {"symbol":"AAPL","type":"BUY","quantity":1,"price":100,"fee":0}
                                """))
                        .andReturn()
                        .getResponse()
                        .getStatus();
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            // 同一 user+asset 幾乎同時的首次 BUY：findHoldingForUpdate 對不存在的 row 不加鎖，
            // 若無 upsert/重試，多筆都會走 insert，(user_id, asset_id) 唯一鍵讓後續變 500。
            for (Future<Integer> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }

        // 8 筆首次 BUY 應全部併入同一持倉（總量 8），而非丟失或報錯。
        mockMvc.perform(get("/api/v1/portfolio/holdings")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].totalQuantity", equalTo(8.00000000)));
    }

    @Test
    @DisplayName("預設排序以 executed_at 降冪，而非 created_at")
    void defaultSortUsesExecutedAtInsteadOfCreatedAt() throws Exception {
        AuthTokens tokens = register("trading-defaultsort@example.com", "tradingdefaultsort", "Password1");
        TradeFixture fixture = seedThreeTrades(tokens);

        /*
         * 第三筆（backfill）的 created_at 最新但 executed_at 最舊；若排序仍走 created_at，
         * 它會排在最前面。此斷言即是預設排序已切換到 executed_at 的直接證據。
         */
        mockMvc.perform(get("/api/v1/trades")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements", equalTo(3)))
            .andExpect(jsonPath("$.data.items[0].id", equalTo(fixture.sellId())))
            .andExpect(jsonPath("$.data.items[1].id", equalTo(fixture.buyId())))
            .andExpect(jsonPath("$.data.items[2].id", equalTo(fixture.backfillBuyId())));
    }

    @Test
    @DisplayName("type 篩選同時作用於 items 與 totalElements，並可與 symbol 組合")
    void typeFilterAppliesToItemsAndTotalElements() throws Exception {
        AuthTokens tokens = register("trading-typefilter@example.com", "tradingtypefilter", "Password1");
        TradeFixture fixture = seedThreeTrades(tokens);

        /*
         * count 與 list 若使用各自的 WHERE 字串，totalElements 會停留在 3 而 items 只有 2 —
         * 此斷言鎖定兩者共用同一份篩選條件。
         */
        mockMvc.perform(get("/api/v1/trades")
                .param("type", "BUY")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements", equalTo(2)))
            .andExpect(jsonPath("$.data.items.length()", equalTo(2)))
            .andExpect(jsonPath("$.data.items[0].id", equalTo(fixture.buyId())))
            .andExpect(jsonPath("$.data.items[1].id", equalTo(fixture.backfillBuyId())));

        mockMvc.perform(get("/api/v1/trades")
                .param("symbol", "AAPL")
                .param("type", "buy")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements", equalTo(2)))
            .andExpect(jsonPath("$.data.items.length()", equalTo(2)));
    }

    @Test
    @DisplayName("日期區間為半開區間：含 dateFrom、不含 dateTo")
    void dateRangeFilterIsHalfOpen() throws Exception {
        AuthTokens tokens = register("trading-daterange@example.com", "tradingdaterange", "Password1");
        TradeFixture fixture = seedThreeTrades(tokens);

        mockMvc.perform(get("/api/v1/trades")
                .param("dateFrom", EXECUTED_AT_MIDDLE)
                .param("dateTo", EXECUTED_AT_NEWEST)
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements", equalTo(1)))
            .andExpect(jsonPath("$.data.items.length()", equalTo(1)))
            .andExpect(jsonPath("$.data.items[0].id", equalTo(fixture.buyId())));
    }

    @Test
    @DisplayName("只給 dateFrom：下界為含，且同時作用於 items 與 totalElements")
    void dateFromOnlyAppliesInclusiveLowerBound() throws Exception {
        AuthTokens tokens = register("trading-datefrom@example.com", "tradingdatefrom", "Password1");
        TradeFixture fixture = seedThreeTrades(tokens);

        /*
         * 只給單邊時，另一邊的謂詞必須完全不出現。若 buildFilter 誤把兩個邊界綁在一起，
         * 或把 >= 寫成 >，這裡會分別看到 0 筆或 1 筆。totalElements 一併斷言，
         * 讓日期分支也有與 type 分支同等的一致性保護。
         */
        mockMvc.perform(get("/api/v1/trades")
                .param("dateFrom", EXECUTED_AT_MIDDLE)
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements", equalTo(2)))
            .andExpect(jsonPath("$.data.items.length()", equalTo(2)))
            .andExpect(jsonPath("$.data.items[0].id", equalTo(fixture.sellId())))
            .andExpect(jsonPath("$.data.items[1].id", equalTo(fixture.buyId())));
    }

    @Test
    @DisplayName("只給 dateTo：上界為不含，且同時作用於 items 與 totalElements")
    void dateToOnlyAppliesExclusiveUpperBound() throws Exception {
        AuthTokens tokens = register("trading-dateto@example.com", "tradingdateto", "Password1");
        TradeFixture fixture = seedThreeTrades(tokens);

        mockMvc.perform(get("/api/v1/trades")
                .param("dateTo", EXECUTED_AT_MIDDLE)
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements", equalTo(1)))
            .andExpect(jsonPath("$.data.items.length()", equalTo(1)))
            .andExpect(jsonPath("$.data.items[0].id", equalTo(fixture.backfillBuyId())));
    }

    @Test
    @DisplayName("純日期形式的 dateTo 涵蓋當天整日，時間戳形式則維持不含")
    void dateOnlyUpperBoundCoversTheWholeDay() throws Exception {
        AuthTokens tokens = register("trading-dateonly@example.com", "tradingdateonly", "Password1");
        seedThreeTrades(tokens);

        /*
         * 三筆交易的 executed_at 分別是 01-10 / 02-10 / 03-10（皆為當日 00:00Z）。
         * dateTo=2026-03-10 是純日期，代表「到 03-10 這天結束為止」，故 03-10 那筆要被納入；
         * 同一個邊界寫成時間戳 2026-03-10T00:00:00Z 則是嚴格小於，該筆要被排除。
         * 兩個斷言合起來鎖定「純日期＝整天、時間戳＝該瞬間」的契約。
         */
        mockMvc.perform(get("/api/v1/trades")
                .param("dateFrom", "2026-01-10")
                .param("dateTo", "2026-03-10")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements", equalTo(3)))
            .andExpect(jsonPath("$.data.items.length()", equalTo(3)));

        mockMvc.perform(get("/api/v1/trades")
                .param("dateFrom", "2026-01-10")
                .param("dateTo", EXECUTED_AT_NEWEST)
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements", equalTo(2)))
            .andExpect(jsonPath("$.data.items.length()", equalTo(2)));
    }

    @Test
    @DisplayName("顛倒的日期區間回 400，而非靜默回傳空頁")
    void invertedDateRangeIsRejected() throws Exception {
        AuthTokens tokens = register("trading-inverted@example.com", "tradinginverted", "Password1");
        seedThreeTrades(tokens);

        mockMvc.perform(get("/api/v1/trades")
                .param("dateFrom", EXECUTED_AT_NEWEST)
                .param("dateTo", EXECUTED_AT_OLDEST)
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("sort=createdAt 取回入帳順序，與 executed_at 排序明確不同")
    void sortByCreatedAtRestoresInsertionOrder() throws Exception {
        AuthTokens tokens = register("trading-sortcreated@example.com", "tradingsortcreated", "Password1");
        TradeFixture fixture = seedThreeTrades(tokens);

        /*
         * created_at 由資料庫在寫入當下產生並受 V8 append-only trigger 保護，是帳本唯一
         * 防竄改的時間軸；executed_at 則由提交者填寫。三筆交易的兩種順序刻意不同，
         * 因此本斷言同時證明 sort=createdAt 生效、且走的不是 executed_at。
         */
        mockMvc.perform(get("/api/v1/trades")
                .param("sort", "createdAt")
                .param("direction", "asc")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements", equalTo(3)))
            .andExpect(jsonPath("$.data.items[0].id", equalTo(fixture.buyId())))
            .andExpect(jsonPath("$.data.items[1].id", equalTo(fixture.sellId())))
            .andExpect(jsonPath("$.data.items[2].id", equalTo(fixture.backfillBuyId())));
    }

    @Test
    @DisplayName("未來的 executedAt 建立交易被拒，補登舊交易仍可建立")
    void futureExecutedAtIsRejectedButBackfillIsAllowed() throws Exception {
        AuthTokens tokens = register("trading-futuredate@example.com", "tradingfuturedate", "Password1");

        mockMvc.perform(post("/api/v1/trades")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + tokens.accessToken())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content("""
                    {"symbol":"AAPL","type":"BUY","quantity":1,"price":100,"fee":0,"executedAt":"2099-01-01T00:00:00Z"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")));

        mockMvc.perform(post("/api/v1/trades")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + tokens.accessToken())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content("""
                    {"symbol":"AAPL","type":"BUY","quantity":1,"price":100,"fee":0,"executedAt":"2020-01-01T00:00:00Z"}
                    """))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("sort=total 降冪，金額平手時以 id 降冪決定順序")
    void sortByTotalBreaksTiesDeterministicallyById() throws Exception {
        AuthTokens tokens = register("trading-sorttotal@example.com", "tradingsorttotal", "Password1");
        TradeFixture fixture = seedThreeTrades(tokens);

        /*
         * 三筆金額為 1000（10 × 100）、480（4 × 120）、1000（1 × 1000）；兩筆 1000 平手，
         * 由 id 降冪決定後插入者在前。缺少 tie-breaker 時順序不確定，翻頁會重複或遺漏。
         */
        mockMvc.perform(get("/api/v1/trades")
                .param("sort", "total")
                .param("direction", "desc")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].id", equalTo(fixture.backfillBuyId())))
            .andExpect(jsonPath("$.data.items[1].id", equalTo(fixture.buyId())))
            .andExpect(jsonPath("$.data.items[2].id", equalTo(fixture.sellId())));
    }

    @Test
    @DisplayName("sort=quantity 升冪回傳 1 / 4 / 10")
    void sortByQuantityAscending() throws Exception {
        AuthTokens tokens = register("trading-sortquantity@example.com", "tradingsortquantity", "Password1");
        seedThreeTrades(tokens);

        mockMvc.perform(get("/api/v1/trades")
                .param("sort", "quantity")
                .param("direction", "asc")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].quantity", equalTo(1.00000000)))
            .andExpect(jsonPath("$.data.items[1].quantity", equalTo(4.00000000)))
            .andExpect(jsonPath("$.data.items[2].quantity", equalTo(10.00000000)));
    }

    @Test
    @DisplayName("白名單外的 sort 與 direction 回 400 VALIDATION_FAILED")
    void invalidSortAndDirectionAreRejected() throws Exception {
        AuthTokens tokens = register("trading-badsort@example.com", "tradingbadsort", "Password1");

        mockMvc.perform(get("/api/v1/trades")
                .param("sort", "evil")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")));

        mockMvc.perform(get("/api/v1/trades")
                .param("sort", "executed_at; drop table transactions")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")));

        mockMvc.perform(get("/api/v1/trades")
                .param("direction", "up")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("不支援的 type 回 400 TRADE_UNSUPPORTED_TYPE，非法日期回 400 VALIDATION_FAILED")
    void invalidTypeAndDateAreRejected() throws Exception {
        AuthTokens tokens = register("trading-badfilter@example.com", "tradingbadfilter", "Password1");

        mockMvc.perform(get("/api/v1/trades")
                .param("type", "DIV")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("TRADE_UNSUPPORTED_TYPE")));

        mockMvc.perform(get("/api/v1/trades")
                .param("dateFrom", "not-a-date")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("任何篩選與排序組合都不會洩漏其他使用者的交易")
    void filtersNeverLeakOtherUsersTrades() throws Exception {
        AuthTokens owner = register("trading-isolation-a@example.com", "tradingisolationa", "Password1");
        AuthTokens other = register("trading-isolation-b@example.com", "tradingisolationb", "Password1");
        seedThreeTrades(owner);
        String foreignId = createTrade(other, """
            {"symbol":"AAPL","type":"BUY","quantity":7,"price":999,"fee":0,"executedAt":"%s"}
            """.formatted(EXECUTED_AT_NEWEST));

        /*
         * 另一使用者的交易金額（7 × 999 = 6993）與成交時間都足以在每一種排序下排到第一位，
         * 若 WHERE 漏掉 user_id 條件，下列任一組合都會立刻暴露。
         */
        String[][] combinations = {
            {},
            {"type", "BUY"},
            {"sort", "total", "direction", "desc"},
            {"sort", "quantity", "direction", "desc"},
            {"dateFrom", EXECUTED_AT_OLDEST, "dateTo", "2026-12-31T00:00:00Z"}
        };
        for (String[] combination : combinations) {
            var request = get("/api/v1/trades").header("Authorization", "Bearer " + owner.accessToken());
            for (int i = 0; i < combination.length; i += 2) {
                request = request.param(combination[i], combination[i + 1]);
            }
            String body = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
            assertThat(body).doesNotContain(foreignId);
        }

        mockMvc.perform(get("/api/v1/trades")
                .header("Authorization", "Bearer " + other.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements", equalTo(1)))
            .andExpect(jsonPath("$.data.items[0].id", equalTo(foreignId)));
    }

    @Test
    @DisplayName("8 條併發的同 key 請求：只建立 1 筆交易、8 個回應的 id 全同、零 500")
    void concurrentSameKeyCreatesExactlyOneTrade() throws Exception {
        AuthTokens tokens = register("trading-idem-concurrent@example.com", "tradingidemconcurrent", "Password1");
        String key = "concurrent-same-key-" + UUID.randomUUID();
        String body = idempotentBuyBody(10, "initial buy");

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
                    var response = postTrade(tokens, key, body).andReturn().getResponse();
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
            // 斷言不變量（只有一個 id）而非時序（誰先誰後）：哪一條 thread 贏得 insert 是不確定的，
            // 「大家最後看到同一筆交易」才是契約。
            assertThat(ids).containsOnly(ids.getFirst());
        } finally {
            pool.shutdownNow();
        }

        mockMvc.perform(get("/api/v1/trades")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements", equalTo(1)));

        mockMvc.perform(get("/api/v1/portfolio/holdings")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].totalQuantity", equalTo(10.00000000)));
    }

    @Test
    @DisplayName("被拒的交易回滾後，同一把 key 仍可重新使用（rollback 未留下半成品）")
    void rejectedTradeDoesNotBurnTheIdempotencyKey() throws Exception {
        AuthTokens tokens = register("trading-idem-rollback@example.com", "tradingidemrollback", "Password1");
        String key = "rollback-" + UUID.randomUUID();

        // 零持倉下賣出必定被拒；此時交易已先 insert，靠整筆 tx 回滾撤銷。
        postTrade(tokens, key, """
            {"symbol":"AAPL","type":"SELL","quantity":1,"price":120,"fee":0,"executedAt":"%s"}
            """.formatted(IDEMPOTENT_EXECUTED_AT))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code", equalTo("TRADE_INSUFFICIENT_HOLDING")));

        /*
         * 同一把 key 改送合法的 BUY 必須成功。若回 409 KEY_REUSED，代表被拒交易的那一列其實
         * 留在資料庫裡 —— 使用者會被自己的一次失敗永久鎖住這把 key，而且帳本多了一列不該存在的
         * 紀錄（T-04-07）。
         */
        postTrade(tokens, key, idempotentBuyBody(10, "retry after rejection"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/trades")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements", equalTo(1)));
    }

    @Test
    @DisplayName("錯誤回應的完整 body 都不回射使用者送出的 idempotency key 或備註")
    void errorResponsesNeverEchoUserControlledInput() throws Exception {
        AuthTokens tokens = register("trading-idem-canary@example.com", "tradingidemcanary", "Password1");

        postTrade(tokens, IDEM_KEY_CANARY, idempotentBuyBody(10, "initial buy")).andExpect(status().isOk());

        /*
         * 斷言整個 body 而非只有 $.error.message：key 也可能從 fields 的鍵、meta，
         * 或某個未預期的欄位漏出去（T-04-03，避免重蹈 BackfillController 把 key 串進訊息的做法）。
         */
        String reuseBody = postTrade(tokens, IDEM_KEY_CANARY, idempotentBuyBody(11, "initial buy"))
            .andExpect(status().isConflict())
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertThat(reuseBody).doesNotContain(IDEM_KEY_CANARY);

        String blankKeyBody = postTrade(tokens, "   ", idempotentBuyBody(10, IDEM_KEY_CANARY))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertThat(blankKeyBody).doesNotContain(IDEM_KEY_CANARY);

        String oversizedKeyBody = postTrade(tokens, IDEM_KEY_TOO_LONG, idempotentBuyBody(10, "initial buy"))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertThat(oversizedKeyBody).doesNotContain(IDEM_KEY_CANARY);
    }

    @Test
    @DisplayName("同一把 key 連送兩次：回同一筆交易、帳本只有 1 列、持倉只套用一次")
    void sameIdempotencyKeyReturnsExistingTradeAndAppliesHoldingOnce() throws Exception {
        AuthTokens tokens = register("trading-idem-serial@example.com", "tradingidemserial", "Password1");
        String key = "serial-" + UUID.randomUUID();
        String body = idempotentBuyBody(10, "initial buy");

        String firstId = tradeIdOf(postTrade(tokens, key, body).andExpect(status().isOk()));
        String secondId = tradeIdOf(postTrade(tokens, key, body).andExpect(status().isOk()));

        /*
         * 三條斷言缺一不可：id 相同證明「回的是既有交易」，totalElements 為 1 證明帳本沒多一列，
         * totalQuantity 為 10（不是 20）證明持倉的副作用也只套用了一次。只驗前兩條的話，
         * 「重覆套用持倉但回傳同一筆交易」這種最難察覺的錯誤會整條漏掉。
         */
        assertThat(secondId).isEqualTo(firstId);

        mockMvc.perform(get("/api/v1/trades")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements", equalTo(1)));

        mockMvc.perform(get("/api/v1/portfolio/holdings")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].totalQuantity", equalTo(10.00000000)));
    }

    @Test
    @DisplayName("同一把 key 搭配不同 payload → 409 TRADE_IDEMPOTENCY_KEY_REUSED，且不新增交易")
    void sameKeyWithDifferentPayloadIsRejectedAsReuse() throws Exception {
        AuthTokens tokens = register("trading-idem-reuse@example.com", "tradingidemreuse", "Password1");
        String key = "reuse-" + UUID.randomUUID();

        postTrade(tokens, key, idempotentBuyBody(10, "initial buy")).andExpect(status().isOk());

        postTrade(tokens, key, idempotentBuyBody(11, "initial buy"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code", equalTo("TRADE_IDEMPOTENCY_KEY_REUSED")));

        mockMvc.perform(get("/api/v1/trades")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements", equalTo(1)));
    }

    @Test
    @DisplayName("同一把 key 只有 note 不同 → 200 回既有交易（note 不納入 payload 比對）")
    void sameKeyWithOnlyNoteChangedReturnsExistingTrade() throws Exception {
        AuthTokens tokens = register("trading-idem-note@example.com", "tradingidemnote", "Password1");
        String key = "note-" + UUID.randomUUID();

        String firstId = tradeIdOf(
            postTrade(tokens, key, idempotentBuyBody(10, "initial buy")).andExpect(status().isOk()));

        /*
         * note 是自由文字備註，不影響帳務金額。把它納入比對會讓「使用者重試時順手改了備註」
         * 變成 409，那是把重試安全機制變成障礙，因此刻意排除（DP-6）。
         */
        String secondId = tradeIdOf(
            postTrade(tokens, key, idempotentBuyBody(10, "changed note")).andExpect(status().isOk()));

        assertThat(secondId).isEqualTo(firstId);
    }

    @Test
    @DisplayName("兩個不同使用者用同一把 key：各自建立交易，互不命中（跨使用者隔離）")
    void sameKeyAcrossDifferentUsersCreatesSeparateTrades() throws Exception {
        AuthTokens first = register("trading-idem-userA@example.com", "tradingidemusera", "Password1");
        AuthTokens second = register("trading-idem-userB@example.com", "tradingidemuserb", "Password1");
        String sharedKey = "shared-across-users-" + UUID.randomUUID();
        String body = idempotentBuyBody(10, "initial buy");

        String firstId = tradeIdOf(postTrade(first, sharedKey, body).andExpect(status().isOk()));
        String secondId = tradeIdOf(postTrade(second, sharedKey, body).andExpect(status().isOk()));

        /*
         * 唯一約束的 user_id 維度是這條隔離的唯一保證。若約束只建在 idempotency_key 上，
         * 第二個使用者會拿到第一個使用者的交易 —— 那是跨帳戶資料外洩，不只是冪等失效（T-04-02）。
         */
        assertThat(secondId).isNotEqualTo(firstId);

        mockMvc.perform(get("/api/v1/trades")
                .header("Authorization", "Bearer " + first.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements", equalTo(1)))
            .andExpect(jsonPath("$.data.items[0].id", equalTo(firstId)));

        mockMvc.perform(get("/api/v1/trades")
                .header("Authorization", "Bearer " + second.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements", equalTo(1)))
            .andExpect(jsonPath("$.data.items[0].id", equalTo(secondId)));
    }

    @Test
    @DisplayName("缺少 Idempotency-Key → 400，且 error.fields 指出是哪一個 header")
    void missingIdempotencyKeyHeaderReturnsFieldAwareValidationError() throws Exception {
        AuthTokens tokens = register("trading-idem-missing@example.com", "tradingidemmissing", "Password1");

        // 本檔唯一刻意不帶 Idempotency-Key 的請求：header 必填是 D-05 的核心防線。
        mockMvc.perform(post("/api/v1/trades")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + tokens.accessToken())
                .content(idempotentBuyBody(10, "initial buy")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.error.fields['Idempotency-Key']", notNullValue()));

        // 被擋在業務邏輯之前，帳本不得留下任何痕跡。
        mockMvc.perform(get("/api/v1/trades")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements", equalTo(0)));
    }

    @Test
    @DisplayName("空白 Idempotency-Key → 400 VALIDATION_FAILED，而非靜默通過")
    void blankIdempotencyKeyIsRejected() throws Exception {
        AuthTokens tokens = register("trading-idem-blank@example.com", "tradingidemblank", "Password1");

        /*
         * 空白字串能通過 header 的「必填」檢查（值存在，只是全是空白），卻無法作為唯一鍵使用。
         * 這條不寫，空白 key 會一路走到 DB 才出事，而且每次重試都拿到同一把「空」鍵。
         */
        postTrade(tokens, "   ", idempotentBuyBody(10, "initial buy"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")))
            // 與缺 header 同樣要指名 header（D-16）：前端靠 fields 分辨「header 問題」與「body 欄位錯」
            .andExpect(jsonPath("$.error.fields['Idempotency-Key']", notNullValue()))
            .andExpect(jsonPath("$.error.fields['Idempotency-Key']", not(equalTo(""))));

        mockMvc.perform(get("/api/v1/trades")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements", equalTo(0)));
    }

    @Test
    @DisplayName("超過長度上限的 Idempotency-Key → 400，而非 500 或資料庫例外外洩")
    void oversizedIdempotencyKeyIsRejectedWithValidationError() throws Exception {
        AuthTokens tokens = register("trading-idem-toolong@example.com", "tradingidemtoolong", "Password1");

        /*
         * 129 字元剛好超過服務層 128 的上限。若沒有這道長度檢查，超長 key 會走到 DB 才被欄位
         * 長度擋下，變成 500 並把 DataIntegrityViolationException 的內容當成錯誤訊息外洩（T-04-04）。
         */
        postTrade(tokens, IDEM_KEY_TOO_LONG, idempotentBuyBody(10, "initial buy"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")));
    }

    /**
     * 建立三筆交易，刻意讓 created_at 與 executed_at 的順序不一致：
     * 最後插入的 backfill 交易 created_at 最新、executed_at 最舊。
     *
     * @param tokens 交易擁有者的權杖
     * @return 三筆交易的識別碼
     */
    private TradeFixture seedThreeTrades(AuthTokens tokens) throws Exception {
        String buyId = createTrade(tokens, """
            {"symbol":"AAPL","type":"BUY","quantity":10,"price":100,"fee":5,"executedAt":"%s"}
            """.formatted(EXECUTED_AT_MIDDLE));
        String sellId = createTrade(tokens, """
            {"symbol":"AAPL","type":"SELL","quantity":4,"price":120,"fee":1,"executedAt":"%s"}
            """.formatted(EXECUTED_AT_NEWEST));
        String backfillBuyId = createTrade(tokens, """
            {"symbol":"AAPL","type":"BUY","quantity":1,"price":1000,"fee":0,"executedAt":"%s"}
            """.formatted(EXECUTED_AT_OLDEST));
        return new TradeFixture(buyId, sellId, backfillBuyId);
    }

    /**
     * 建立一筆交易並回傳其 id。每次呼叫都用一把新的隨機 key —— 這些 fixture 交易彼此獨立，
     * 若共用 key，第二筆之後會被冪等機制擋成「回傳既有交易」或 409，fixture 就湊不齊了。
     *
     * @param tokens 交易擁有者的權杖
     * @param body   交易 payload
     * @return 建立完成的交易 id
     */
    private String createTrade(AuthTokens tokens, String body) throws Exception {
        String response = mockMvc.perform(post("/api/v1/trades")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + tokens.accessToken())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readTree(response).get("data").get("id").asText();
    }

    private record TradeFixture(String buyId, String sellId, String backfillBuyId) {
    }

    /**
     * 送出一筆交易建立請求，不對狀態碼做任何預期——冪等測試同時需要 200 / 409 / 400 三種結果。
     *
     * @param tokens         交易擁有者的權杖
     * @param idempotencyKey 原樣送出的 {@code Idempotency-Key} header 值，不做 trim 或補值
     * @param body           交易 payload
     * @return 尚未斷言的請求結果，由呼叫端決定要驗什麼
     */
    private ResultActions postTrade(AuthTokens tokens, String idempotencyKey, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/trades")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer " + tokens.accessToken())
            .header("Idempotency-Key", idempotencyKey)
            .content(body));
    }

    /**
     * 冪等測試共用的 BUY payload。{@code executedAt} 固定，讓同一把 key 的重試送出逐字相同的
     * payload；{@code quantity} 是唯一刻意可變的比對欄位，{@code note} 則用來驗證它<strong>不</strong>納入比對。
     *
     * @param quantity 交易數量
     * @param note     自由文字備註
     * @return JSON payload
     */
    private String idempotentBuyBody(int quantity, String note) {
        return """
            {"symbol":"AAPL","type":"BUY","quantity":%d,"price":100,"fee":5,"note":"%s","executedAt":"%s"}
            """.formatted(quantity, note, IDEMPOTENT_EXECUTED_AT);
    }

    private String tradeIdOf(ResultActions actions) throws Exception {
        String body = actions.andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data").get("id").asText();
    }

    private String buyBody() {
        return """
            {"symbol":"AAPL","type":"BUY","quantity":10,"price":100,"fee":5,"note":"initial buy"}
            """;
    }

    private AuthTokens register(String email, String username, String password) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","username":"%s","password":"%s"}
                    """.formatted(email, username, password)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.accessToken").doesNotExist())
            .andExpect(jsonPath("$.data.refreshToken").doesNotExist());

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
        return new AuthTokens(data.get("accessToken").asText(), data.get("refreshToken").asText());
    }

    private record AuthTokens(String accessToken, String refreshToken) {
    }
}
