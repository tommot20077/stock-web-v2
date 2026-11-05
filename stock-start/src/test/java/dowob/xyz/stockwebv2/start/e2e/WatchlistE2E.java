package dowob.xyz.stockwebv2.start.e2e;

import dowob.xyz.stockwebv2.start.e2e.support.AbstractPortfolioE2ETest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dowob.xyz.stockwebv2.start.e2e.support.AuthE2EHelper.bearerToken;
import static dowob.xyz.stockwebv2.start.e2e.support.StockE2EAssertions.apiError;
import static dowob.xyz.stockwebv2.start.e2e.support.StockE2EAssertions.apiSuccess;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;

/**
 * 觀察清單（WATCH-01 / WATCH-02）的 REST 層 E2E：預設清單、建立 / 改名 / 刪除、加入 FX、整份重排持久化、
 * 20 份上限與跨使用者 404。
 *
 * <p>契約見 {@code ai-docs/watchlist-contract.md}：0 個清單時 {@code GET} 先建「我的自選」；路徑一律用 UUID
 * （FX 代號含 {@code /}）；加入任何 active 資產（不要求 tradeable）；重名 409 {@code DUPLICATE_RESOURCE}
 * {@code fields.name}；第 21 份 400 {@code fields.watchlists}；清單不存在或屬於他人一律 404
 * {@code WATCHLIST_NOT_FOUND}。全部以 bearer 呼叫（cookie + CSRF 路徑由 {@code WatchlistApiIT} 覆蓋）。</p>
 *
 * <p>與 {@code WatchlistApiIT} 的分工：IT 逐端點鎖錯誤矩陣、並發與 100 檔上限；本類別把使用者實際的操作順序串成一條路，
 * 驗證每一步的結果都被下一步的讀取看見（持久化），並以真實 {@code DELETE} 驗證 {@code ON DELETE CASCADE}。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("Watchlist E2E")
class WatchlistE2E extends AbstractPortfolioE2ETest {

    private static final String DEFAULT_NAME = "我的自選";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("預設「我的自選」→ 建立「科技股」加 AAPL / NVDA / USD/TWD（FX 報價 null）→ 重排持久化 → 改名「半導體」、改成既有名稱 409 → 刪除（項目隨之消失）→ 刪光後 GET 重建預設")
    void watchlistLifecyclePersistsEveryStep() throws Exception {
        var session = auth.register("watch-e2e-flow@example.com", "watche2eflow", PASSWORD);
        String token = session.accessToken();

        JsonNode initial = watchlistsOf(token);
        assertThat(initial).hasSize(1);
        assertThat(initial.get(0).get("name").asText()).isEqualTo(DEFAULT_NAME);
        assertThat(initial.get(0).get("items")).isEmpty();
        String defaultId = initial.get(0).get("id").asText();

        String techId = dataOf(createWatchlist(token, "科技股")
            .andExpect(status().isOk())
            .andExpect(apiSuccess())
            .andExpect(jsonPath("$.data.name", equalTo("科技股")))).get("id").asText();

        addItem(token, techId, "AAPL").andExpect(status().isOk());
        addItem(token, techId, "NVDA").andExpect(status().isOk());
        JsonNode withFx = dataOf(addItem(token, techId, "USD/TWD")
            .andExpect(status().isOk())
            .andExpect(apiSuccess()));
        assertThat(symbols(withFx)).containsExactly("AAPL", "NVDA", "USD/TWD");
        JsonNode fx = withFx.get("items").get(2);
        assertThat(fx.get("assetType").asText()).isEqualTo("FX");
        assertThat(fx.get("tradeable").asBoolean()).isFalse();
        assertThat(fx.has("latestPrice")).isTrue();
        assertThat(fx.get("latestPrice").isNull()).isTrue();
        String aaplUuid = uuidOf(withFx, "AAPL");
        String nvdaUuid = uuidOf(withFx, "NVDA");
        String fxUuid = uuidOf(withFx, "USD/TWD");

        JsonNode reordered = dataOf(reorder(token, techId, fxUuid, nvdaUuid, aaplUuid)
            .andExpect(status().isOk())
            .andExpect(apiSuccess()));
        assertThat(symbols(reordered)).containsExactly("USD/TWD", "NVDA", "AAPL");
        /* 重排要被下一次 GET 看見，不只是回應本身。 */
        assertThat(symbols(watchlistById(watchlistsOf(token), techId))).containsExactly("USD/TWD", "NVDA", "AAPL");

        renameWatchlist(token, techId, "半導體")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id", equalTo(techId)))
            .andExpect(jsonPath("$.data.name", equalTo("半導體")));
        renameWatchlist(token, techId, DEFAULT_NAME)
            .andExpect(status().isConflict())
            .andExpect(apiError("DUPLICATE_RESOURCE"))
            .andExpect(jsonPath("$.error.fields.name", notNullValue()));
        JsonNode renamed = watchlistById(watchlistsOf(token), techId);
        assertThat(renamed.get("name").asText()).isEqualTo("半導體");
        assertThat(symbols(renamed)).containsExactly("USD/TWD", "NVDA", "AAPL");

        Long techInternalId = internalIdOf(techId);
        assertThat(itemRows(techInternalId)).isEqualTo(3);
        deleteWatchlist(token, techId)
            .andExpect(status().isOk())
            .andExpect(apiSuccess());
        /* 項目隨 ON DELETE CASCADE 刪除（真實 DELETE，不是測試清理的 replica 模式）。 */
        assertThat(itemRows(techInternalId)).isZero();
        JsonNode afterDelete = watchlistsOf(token);
        assertThat(afterDelete).hasSize(1);
        assertThat(afterDelete.get(0).get("id").asText()).isEqualTo(defaultId);
        addItem(token, techId, "AAPL")
            .andExpect(status().isNotFound())
            .andExpect(apiError("WATCHLIST_NOT_FOUND"));

        deleteWatchlist(token, defaultId)
            .andExpect(status().isOk());
        JsonNode recreated = watchlistsOf(token);
        assertThat(recreated).hasSize(1);
        assertThat(recreated.get(0).get("name").asText()).isEqualTo(DEFAULT_NAME);
        assertThat(recreated.get(0).get("id").asText()).isNotEqualTo(defaultId);
        assertThat(recreated.get(0).get("items")).isEmpty();
    }

    @Test
    @DisplayName("預設加 19 份共 20 份皆成功，第 21 份 → 400 VALIDATION_FAILED fields.watchlists，清單數仍為 20")
    void twentyFirstWatchlistIsRejected() throws Exception {
        var session = auth.register("watch-e2e-limit@example.com", "watche2elimit", PASSWORD);
        String token = session.accessToken();
        assertThat(watchlistsOf(token)).hasSize(1);

        for (int i = 2; i <= 20; i++) {
            createWatchlist(token, "清單" + i).andExpect(status().isOk());
        }
        assertThat(watchlistsOf(token)).hasSize(20);

        createWatchlist(token, "清單21")
            .andExpect(status().isBadRequest())
            .andExpect(apiError("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.error.fields.watchlists", notNullValue()));
        JsonNode lists = watchlistsOf(token);
        assertThat(lists).hasSize(20);
        assertThat(names(lists)).doesNotContain("清單21");
    }

    @Test
    @DisplayName("使用者 B 對 A 的清單：改名 / 刪除 / 加入 / 移除 / 重排皆 404 WATCHLIST_NOT_FOUND 且不回射 uuid；B 的 GET 只看到自己的預設清單；A 的清單原封不動")
    void otherUserGetsNotFoundOnEveryEndpoint() throws Exception {
        var alice = auth.register("watch-e2e-alice@example.com", "watche2ealice", PASSWORD);
        var bob = auth.register("watch-e2e-bob@example.com", "watche2ebob", PASSWORD);
        String aliceToken = alice.accessToken();
        String bobToken = bob.accessToken();
        String listId = dataOf(createWatchlist(aliceToken, "私人清單").andExpect(status().isOk())).get("id").asText();
        JsonNode aliceList = dataOf(addItem(aliceToken, listId, "AAPL").andExpect(status().isOk()));
        String aaplUuid = uuidOf(aliceList, "AAPL");

        List<ResultActions> attempts = List.of(
            renameWatchlist(bobToken, listId, "被改了"),
            deleteWatchlist(bobToken, listId),
            addItem(bobToken, listId, "NVDA"),
            mockMvc.perform(delete("/api/v1/watchlists/" + listId + "/items/" + aaplUuid).with(bearerToken(bobToken))),
            reorder(bobToken, listId, aaplUuid));
        for (ResultActions attempt : attempts) {
            String body = attempt
                .andExpect(status().isNotFound())
                .andExpect(apiError("WATCHLIST_NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain(listId);
        }

        JsonNode bobLists = watchlistsOf(bobToken);
        assertThat(bobLists).hasSize(1);
        assertThat(bobLists.get(0).get("name").asText()).isEqualTo(DEFAULT_NAME);
        assertThat(bobLists.get(0).get("id").asText()).isNotEqualTo(listId);

        JsonNode untouched = watchlistById(watchlistsOf(aliceToken), listId);
        assertThat(untouched.get("name").asText()).isEqualTo("私人清單");
        assertThat(symbols(untouched)).containsExactly("AAPL");
    }

    // ── 請求 helper ─────────────────────────────────────────────────────────────

    /**
     * 讀取 {@code GET /api/v1/watchlists} 的 data 陣列並要求成功。
     *
     * @param token access token
     * @return 清單陣列
     * @throws Exception MockMvc 失敗時
     */
    private JsonNode watchlistsOf(String token) throws Exception {
        return dataOf(mockMvc.perform(get("/api/v1/watchlists").with(bearerToken(token)))
            .andExpect(status().isOk())
            .andExpect(apiSuccess()));
    }

    private ResultActions createWatchlist(String token, String name) throws Exception {
        return mockMvc.perform(post("/api/v1/watchlists")
            .contentType(MediaType.APPLICATION_JSON)
            .with(bearerToken(token))
            .content(nameBody(name)));
    }

    private ResultActions renameWatchlist(String token, String id, String name) throws Exception {
        return mockMvc.perform(patch("/api/v1/watchlists/" + id)
            .contentType(MediaType.APPLICATION_JSON)
            .with(bearerToken(token))
            .content(nameBody(name)));
    }

    private ResultActions deleteWatchlist(String token, String id) throws Exception {
        return mockMvc.perform(delete("/api/v1/watchlists/" + id).with(bearerToken(token)));
    }

    private ResultActions addItem(String token, String id, String symbol) throws Exception {
        return mockMvc.perform(post("/api/v1/watchlists/" + id + "/items")
            .contentType(MediaType.APPLICATION_JSON)
            .with(bearerToken(token))
            .content(objectMapper.writeValueAsString(Map.of("symbol", symbol))));
    }

    private ResultActions reorder(String token, String id, String... assetIds) throws Exception {
        return mockMvc.perform(put("/api/v1/watchlists/" + id + "/items/order")
            .contentType(MediaType.APPLICATION_JSON)
            .with(bearerToken(token))
            .content(objectMapper.writeValueAsString(Map.of("assetIds", List.of(assetIds)))));
    }

    private String nameBody(String name) {
        return objectMapper.writeValueAsString(Map.of("name", name));
    }

    /**
     * 以清單 UUID 查出內部 id（刪除前先取得，刪除後才能以 id 檢查項目列是否隨之消失）。
     *
     * @param watchlistUuid 清單 UUID
     * @return watchlists.id
     */
    private Long internalIdOf(String watchlistUuid) {
        return jdbcTemplate.queryForObject("select id from watchlists where uuid = ?", Long.class,
            UUID.fromString(watchlistUuid));
    }

    /**
     * 直接查資料庫中某份清單的項目列數（含清單已刪除後殘留的孤兒列）。
     *
     * @param watchlistId watchlists.id
     * @return watchlist_items 列數
     */
    private int itemRows(Long watchlistId) {
        Integer rows = jdbcTemplate.queryForObject(
            "select count(*) from watchlist_items where watchlist_id = ?", Integer.class, watchlistId);
        return rows == null ? 0 : rows;
    }

    // ── 回應解析 ────────────────────────────────────────────────────────────────

    private static JsonNode watchlistById(JsonNode lists, String id) {
        for (JsonNode list : lists) {
            if (id.equals(list.get("id").asText())) {
                return list;
            }
        }
        throw new AssertionError("watchlist not found in GET response");
    }

    private static String uuidOf(JsonNode watchlist, String symbol) {
        for (JsonNode item : watchlist.get("items")) {
            if (symbol.equals(item.get("symbol").asText())) {
                return item.get("uuid").asText();
            }
        }
        throw new AssertionError("item not found: " + symbol);
    }

    private static List<String> symbols(JsonNode watchlist) {
        List<String> symbols = new ArrayList<>();
        watchlist.get("items").forEach(item -> symbols.add(item.get("symbol").asText()));
        return symbols;
    }

    private static List<String> names(JsonNode lists) {
        List<String> names = new ArrayList<>();
        lists.forEach(list -> names.add(list.get("name").asText()));
        return names;
    }
}
