package dowob.xyz.stockwebv2.start.e2e.support;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static dowob.xyz.stockwebv2.start.e2e.support.AuthE2EHelper.bearerToken;
import static dowob.xyz.stockwebv2.start.e2e.support.StockE2EAssertions.apiSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;

/**
 * 投資組合 / 現金帳本 E2E 的共用基底：所有請求都走真實 HTTP（MockMvc）層與 bearer 身分，
 * 行情則以 SQL 直接種進 {@code market_prices}（E2E profile 關閉了行情排程，沒有任何背景 tick）。
 *
 * <p>每個測試結束後一律清掉自己種的行情與全部使用者資料（含 {@code cash_ledger_entries}），
 * 讓 E2E 套件內的類別彼此不留殘影。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public abstract class AbstractPortfolioE2ETest extends AbstractStockE2ETest {

    /** 測試使用者共用的合法密碼。 */
    protected static final String PASSWORD = "Password1";

    @Autowired
    protected AuthE2EHelper auth;

    @Autowired
    protected DatabaseCleaner databaseCleaner;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 本測試種過行情的資產 id；結束時一併刪除，避免行情殘留到其他 E2E 類別。 */
    private final List<Long> seededAssetIds = new ArrayList<>();

    /**
     * 刪除本測試種的行情並清除所有使用者資料與 Redis。
     */
    @AfterEach
    protected void cleanUpPortfolioData() {
        seededAssetIds.forEach(assetId -> jdbcTemplate.update("delete from market_prices where asset_id = ?", assetId));
        seededAssetIds.clear();
        databaseCleaner.cleanUserData();
    }

    // ── 現金分錄 ────────────────────────────────────────────────────────────────

    /**
     * 送出一筆現金分錄請求，不預設結果。
     *
     * @param token          access token
     * @param idempotencyKey 冪等鍵
     * @param body           JSON 請求本文
     * @return 回應
     * @throws Exception MockMvc 失敗時
     */
    protected ResultActions postCashEntry(String token, String idempotencyKey, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/portfolio/cash-entries")
            .contentType(MediaType.APPLICATION_JSON)
            .with(bearerToken(token))
            .header("Idempotency-Key", idempotencyKey)
            .content(body));
    }

    /**
     * 記一筆入出金並要求成功（每次使用新的冪等鍵、發生時間由後端補現在）。
     *
     * @param token    access token
     * @param type     DEPOSIT / WITHDRAWAL
     * @param currency 幣別代碼
     * @param amount   金額
     * @return 回應 {@code data}（分錄 DTO）
     * @throws Exception MockMvc 失敗時
     */
    protected JsonNode recordCashEntry(String token, String type, String currency, String amount) throws Exception {
        return dataOf(postCashEntry(token, UUID.randomUUID().toString(), entryBody(type, currency, amount))
            .andExpect(status().isOk())
            .andExpect(apiSuccess()));
    }

    /**
     * 組出入出金請求本文（不含 occurredAt 與沖銷資訊）。
     *
     * @param type     DEPOSIT / WITHDRAWAL
     * @param currency 幣別代碼
     * @param amount   金額
     * @return JSON 字串
     */
    protected static String entryBody(String type, String currency, String amount) {
        return "{\"type\":\"%s\",\"currency\":\"%s\",\"amount\":%s}".formatted(type, currency, amount);
    }

    /**
     * 組出沖銷請求本文。
     *
     * @param type         與原分錄方向相反的類型
     * @param currency     與原分錄相同的幣別
     * @param amount       與原分錄相同的金額
     * @param reversalOfId 原分錄 id
     * @return JSON 字串
     */
    protected static String reversalBody(String type, String currency, String amount, String reversalOfId) {
        return "{\"type\":\"%s\",\"currency\":\"%s\",\"amount\":%s,\"reversalOfId\":\"%s\"}"
            .formatted(type, currency, amount, reversalOfId);
    }

    /**
     * 查詢現金分錄列表。
     *
     * @param token access token
     * @param page  頁碼；null 表示不帶參數
     * @param size  每頁筆數；null 表示不帶參數
     * @return 回應
     * @throws Exception MockMvc 失敗時
     */
    protected ResultActions getCashEntries(String token, String page, String size) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/portfolio/cash-entries").with(bearerToken(token));
        if (page != null) {
            request = request.param("page", page);
        }
        if (size != null) {
            request = request.param("size", size);
        }
        return mockMvc.perform(request);
    }

    /**
     * 在分錄列表的 {@code items} 中依 id 找出一筆。
     *
     * @param items 列表 items 節點
     * @param id    分錄 id
     * @return 該筆；找不到時為 null
     */
    protected static JsonNode entryById(JsonNode items, String id) {
        for (JsonNode item : items) {
            if (id.equals(item.get("id").asText())) {
                return item;
            }
        }
        return null;
    }

    // ── 交易 ───────────────────────────────────────────────────────────────────

    /**
     * 送出一筆已成交的手動交易（手續費 0）並要求成功。
     *
     * @param token    access token
     * @param symbol   標的代號
     * @param type     BUY / SELL
     * @param quantity 數量
     * @param price    成交價
     * @return 回應 {@code data}（交易 DTO）
     * @throws Exception MockMvc 失敗時
     */
    protected JsonNode recordTrade(String token, String symbol, String type, String quantity, String price)
        throws Exception {
        return recordTrade(token, symbol, type, quantity, price, null);
    }

    /**
     * 送出一筆已成交的手動交易（手續費 0）並要求成功。
     *
     * @param token      access token
     * @param symbol     標的代號
     * @param type       BUY / SELL
     * @param quantity   數量
     * @param price      成交價
     * @param executedAt 明確的成交時間（ISO-8601）；null 表示由後端補現在
     * @return 回應 {@code data}（交易 DTO）
     * @throws Exception MockMvc 失敗時
     */
    protected JsonNode recordTrade(
        String token, String symbol, String type, String quantity, String price, String executedAt) throws Exception {
        String executed = executedAt == null ? "" : ",\"executedAt\":\"" + executedAt + "\"";
        return dataOf(mockMvc.perform(post("/api/v1/trades")
                .contentType(MediaType.APPLICATION_JSON)
                .with(bearerToken(token))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content("{\"symbol\":\"%s\",\"type\":\"%s\",\"quantity\":%s,\"price\":%s,\"fee\":0%s}"
                    .formatted(symbol, type, quantity, price, executed)))
            .andExpect(status().isOk())
            .andExpect(apiSuccess()));
    }

    // ── 投資組合讀取 ────────────────────────────────────────────────────────────

    /**
     * 讀取 {@code GET /api/v1/portfolio/summary} 的 data 節點。
     *
     * @param token access token
     * @return summary 的 data 節點
     * @throws Exception MockMvc 失敗時
     */
    protected JsonNode summaryOf(String token) throws Exception {
        return dataOf(mockMvc.perform(get("/api/v1/portfolio/summary").with(bearerToken(token)))
            .andExpect(status().isOk())
            .andExpect(apiSuccess()));
    }

    /**
     * 讀取 {@code GET /api/v1/portfolio/holdings} 的 data 節點（陣列）。
     *
     * @param token access token
     * @return holdings 陣列
     * @throws Exception MockMvc 失敗時
     */
    protected JsonNode holdingsOf(String token) throws Exception {
        return dataOf(mockMvc.perform(get("/api/v1/portfolio/holdings").with(bearerToken(token)))
            .andExpect(status().isOk())
            .andExpect(apiSuccess()));
    }

    /**
     * 在 {@code cashBalances} 中依幣別找出一列。
     *
     * @param summary  summary 的 data 節點
     * @param currency 幣別代碼
     * @return 該列；沒有該幣別時為 null
     */
    protected static JsonNode balanceOf(JsonNode summary, String currency) {
        for (JsonNode balance : summary.get("cashBalances")) {
            if (currency.equals(balance.get("currency").asText())) {
                return balance;
            }
        }
        return null;
    }

    /**
     * 在 holdings 陣列中依代號找出一列。
     *
     * @param holdings holdings 陣列
     * @param symbol   標的代號
     * @return 該列；找不到時為 null
     */
    protected static JsonNode holdingOf(JsonNode holdings, String symbol) {
        for (JsonNode holding : holdings) {
            if (symbol.equals(holding.get("symbol").asText())) {
                return holding;
            }
        }
        return null;
    }

    /**
     * 以 bearer 呼叫 {@code PATCH /api/v1/me/preferences} 切換基準幣並要求成功。
     *
     * @param token        access token
     * @param baseCurrency 目標基準幣代碼
     * @throws Exception MockMvc 失敗時
     */
    protected void switchBaseCurrency(String token, String baseCurrency) throws Exception {
        mockMvc.perform(patch("/api/v1/me/preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .with(bearerToken(token))
                .content("{\"baseCurrency\":\"" + baseCurrency + "\"}"))
            .andExpect(status().isOk())
            .andExpect(apiSuccess());
    }

    /**
     * 取出回應信封的 {@code data} 節點。
     *
     * @param result 已執行的請求
     * @return {@code $.data}
     * @throws Exception 讀取回應失敗時
     */
    protected JsonNode dataOf(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    // ── 行情 ───────────────────────────────────────────────────────────────────

    /**
     * 為標的種最新價（1 分鐘前的單一列），先清掉舊行情與 Redis latest 快取。
     *
     * @param symbol 標的代號
     * @param latest 最新價
     */
    protected void seedLatestPrice(String symbol, String latest) {
        seedPrices(symbol, null, latest);
    }

    /**
     * 為標的種前收與最新價：前收列在 26 小時前（必然早於任何市場的當日起點）、最新列在 1 分鐘前；
     * 先清掉舊行情與 Redis latest 快取。
     *
     * @param symbol        標的代號
     * @param previousClose 前收；null 表示不寫前收列
     * @param latest        最新價
     */
    protected void seedPrices(String symbol, String previousClose, String latest) {
        Long assetId = jdbcTemplate.queryForObject("select id from assets where symbol = ?", Long.class, symbol);
        redisTemplate.delete("market:latest:" + assetId);
        jdbcTemplate.update("delete from market_prices where asset_id = ?", assetId);
        if (previousClose != null) {
            insertPrice(assetId, previousClose, "26 hours");
        }
        insertPrice(assetId, latest, "1 minute");
        seededAssetIds.add(assetId);
    }

    private void insertPrice(Long assetId, String price, String ago) {
        jdbcTemplate.update("""
                insert into market_prices(asset_id, time, price, volume)
                values (?, now() - cast(? as interval), ?, 1000)
                """, assetId, ago, new BigDecimal(price));
    }
}
