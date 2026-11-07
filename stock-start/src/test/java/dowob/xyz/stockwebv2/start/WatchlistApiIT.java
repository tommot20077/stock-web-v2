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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 觀察清單 API（WATCH-01 / WATCH-02；D-14 ~ D-19）的端到端驗收：預設清單、報價形狀、
 * 建立 / 改名 / 刪除、項目加入 / 移除 / 重排、上限（含併發）、擁有者隔離與 CSRF。
 *
 * <p>需要 Docker（Testcontainers），僅能在 CI Integration job 執行。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@AutoConfigureMockMvc
@DisplayName("觀察清單 API")
class WatchlistApiIT extends ContainerIT {
    private static final String PASSWORD = "Password1";
    private static final String ACCESS_COOKIE = "stock_access";
    private static final String XSRF_COOKIE = "XSRF-TOKEN";
    private static final String XSRF_HEADER = "X-XSRF-TOKEN";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    @DisplayName("新使用者第一次 GET 得到恰一個空的「我的自選」；再 GET 仍是同一個")
    void firstGetCreatesDefaultList() throws Exception {
        String token = registerAndLogin("watch-first@example.com", "watchfirst");

        JsonNode first = getWatchlists(token);
        assertThat(first).hasSize(1);
        assertThat(first.get(0).get("name").asText()).isEqualTo("我的自選");
        assertThat(first.get(0).get("items")).isEmpty();

        JsonNode second = getWatchlists(token);
        assertThat(second).hasSize(1);
        assertThat(second.get(0).get("id").asText()).isEqualTo(first.get(0).get("id").asText());
    }

    @Test
    @DisplayName("兩個並發的首次 GET 只留下一列預設清單")
    void concurrentFirstGetsLeaveSingleDefault() throws Exception {
        String token = registerAndLogin("watch-race@example.com", "watchrace");
        Long userId = userId("watch-race@example.com");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> call = () -> {
                start.await();
                return mockMvc.perform(get("/api/v1/watchlists").header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getStatus();
            };
            List<Future<Integer>> futures = new ArrayList<>();
            futures.add(pool.submit(call));
            futures.add(pool.submit(call));
            start.countDown();
            for (Future<Integer> future : futures) {
                assertThat(future.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }

        Integer rows = jdbcClient.sql("select count(*) from watchlists where user_id = :userId")
            .param("userId", userId)
            .query(Integer.class)
            .single();
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("項目與 GET /api/v1/assets 的 AssetDto 逐欄同形；無行情的 USD/TWD 報價欄位為 null")
    void itemsShareAssetDtoShapeAndNullQuotes() throws Exception {
        String token = registerAndLogin("watch-shape@example.com", "watchshape");
        Long userId = userId("watch-shape@example.com");
        getWatchlists(token);
        Long watchlistId = jdbcClient.sql("select id from watchlists where user_id = :userId")
            .param("userId", userId)
            .query(Long.class)
            .single();
        seedItem(watchlistId, "AAPL", 1);
        seedItem(watchlistId, "USD/TWD", 2);

        JsonNode items = getWatchlists(token).get(0).get("items");
        assertThat(items).hasSize(2);
        JsonNode aapl = items.get(0);
        JsonNode usdTwd = items.get(1);
        assertThat(aapl.get("symbol").asText()).isEqualTo("AAPL");
        assertThat(usdTwd.get("symbol").asText()).isEqualTo("USD/TWD");

        String assetsBody = mockMvc.perform(get("/api/v1/assets?query=AAPL&page=0&size=20"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JsonNode assetDto = null;
        for (JsonNode candidate : objectMapper.readTree(assetsBody).get("data").get("items")) {
            if ("AAPL".equals(candidate.get("symbol").asText())) {
                assetDto = candidate;
            }
        }
        assertThat(assetDto).isNotNull();
        assertThat(fieldNames(aapl)).isEqualTo(fieldNames(assetDto));
        assertThat(aapl.get("uuid").asText()).isEqualTo(assetDto.get("uuid").asText());
        assertThat(aapl.get("latestPrice")).isEqualTo(assetDto.get("latestPrice"));
        assertThat(aapl.get("changePercent")).isEqualTo(assetDto.get("changePercent"));

        assertThat(fieldNames(usdTwd)).isEqualTo(fieldNames(assetDto));
        assertThat(usdTwd.get("latestPrice").isNull()).isTrue();
        assertThat(usdTwd.get("tradeable").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("未登入 GET 回 401")
    void unauthenticatedGetIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/watchlists"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("建立 19 個（加預設共 20）皆成功，第 21 個 → 400 VALIDATION_FAILED fields.watchlists")
    void twentyFirstWatchlistIsRejected() throws Exception {
        String token = registerAndLogin("watch-limit@example.com", "watchlimit");

        for (int i = 1; i <= 19; i++) {
            createWatchlist(token, "清單" + i)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name", equalTo("清單" + i)));
        }
        assertThat(getWatchlists(token)).hasSize(20);

        createWatchlist(token, "清單21")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.error.fields.watchlists").exists());
        assertThat(getWatchlists(token)).hasSize(20);
    }

    @Test
    @DisplayName("重名 409 DUPLICATE_RESOURCE fields.name；31 字與全空白名稱 400 fields.name；名稱 trim 後儲存")
    void nameRulesAreEnforced() throws Exception {
        String token = registerAndLogin("watch-name@example.com", "watchname");

        createWatchlist(token, "  科技股  ")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name", equalTo("科技股")))
            .andExpect(jsonPath("$.data.items").isEmpty());

        createWatchlist(token, "科技股")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code", equalTo("DUPLICATE_RESOURCE")))
            .andExpect(jsonPath("$.error.fields.name").exists());

        createWatchlist(token, "a".repeat(31))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.error.fields.name").exists());

        createWatchlist(token, "   ")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.error.fields.name").exists());
    }

    @Test
    @DisplayName("改名成功回新名稱；改成另一份清單的名稱 → 409；非 UUID 路徑 → 400")
    void renameUpdatesNameAndRejectsDuplicates() throws Exception {
        String token = registerAndLogin("watch-rename@example.com", "watchrename");
        String id = createdId(createWatchlist(token, "原名").andExpect(status().isOk()));

        renameWatchlist(token, id, " 新名稱 ")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id", equalTo(id)))
            .andExpect(jsonPath("$.data.name", equalTo("新名稱")));

        renameWatchlist(token, id, "我的自選")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code", equalTo("DUPLICATE_RESOURCE")))
            .andExpect(jsonPath("$.error.fields.name").exists());

        renameWatchlist(token, "not-a-uuid", "任意")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("使用者 B 改名 / 刪除 A 的清單 → 404 WATCHLIST_NOT_FOUND，A 的清單不變")
    void otherUsersCannotTouchWatchlist() throws Exception {
        String ownerToken = registerAndLogin("watch-owner@example.com", "watchowner");
        String intruderToken = registerAndLogin("watch-intruder@example.com", "watchintruder");
        String id = createdId(createWatchlist(ownerToken, "私人清單").andExpect(status().isOk()));

        renameWatchlist(intruderToken, id, "被改了")
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code", equalTo("WATCHLIST_NOT_FOUND")));
        mockMvc.perform(delete("/api/v1/watchlists/" + id).header("Authorization", "Bearer " + intruderToken))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code", equalTo("WATCHLIST_NOT_FOUND")));
        mockMvc.perform(delete("/api/v1/watchlists/" + UUID.randomUUID()).header("Authorization", "Bearer " + ownerToken))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code", equalTo("WATCHLIST_NOT_FOUND")));

        JsonNode ownerLists = getWatchlists(ownerToken);
        assertThat(ownerLists).hasSize(2);
        assertThat(ownerLists.get(1).get("id").asText()).isEqualTo(id);
        assertThat(ownerLists.get(1).get("name").asText()).isEqualTo("私人清單");
    }

    @Test
    @DisplayName("刪除唯一的清單（項目隨 CASCADE 刪除）後，下一次 GET 重建「我的自選」（新 id）")
    void deletingLastWatchlistRecreatesDefault() throws Exception {
        String token = registerAndLogin("watch-delete@example.com", "watchdelete");
        String originalId = getWatchlists(token).get(0).get("id").asText();
        Long watchlistId = jdbcClient.sql("select id from watchlists where uuid = :uuid")
            .param("uuid", UUID.fromString(originalId))
            .query(Long.class)
            .single();
        seedItem(watchlistId, "AAPL", 1);

        mockMvc.perform(delete("/api/v1/watchlists/" + originalId).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", equalTo(true)));
        Integer orphanItems = jdbcClient.sql("select count(*) from watchlist_items where watchlist_id = :id")
            .param("id", watchlistId)
            .query(Integer.class)
            .single();
        assertThat(orphanItems).isZero();

        JsonNode recreated = getWatchlists(token);
        assertThat(recreated).hasSize(1);
        assertThat(recreated.get(0).get("name").asText()).isEqualTo("我的自選");
        assertThat(recreated.get(0).get("id").asText()).isNotEqualTo(originalId);
        assertThat(recreated.get(0).get("items")).isEmpty();
    }

    @Test
    @DisplayName("cookie 路徑 POST / DELETE 不帶 CSRF header → 403；帶 header 則成功")
    void cookieMutationsRequireCsrf() throws Exception {
        Cookie access = registerAndLoginWithCookie("watch-csrf@example.com", "watchcsrf");
        String body = mockMvc.perform(get("/api/v1/watchlists").cookie(access))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String defaultId = objectMapper.readTree(body).get("data").get(0).get("id").asText();

        mockMvc.perform(post("/api/v1/watchlists")
                .cookie(access)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"無 CSRF\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code", equalTo("AUTH_CSRF_TOKEN_INVALID")));
        mockMvc.perform(delete("/api/v1/watchlists/" + defaultId).cookie(access))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code", equalTo("AUTH_CSRF_TOKEN_INVALID")));

        Cookie xsrf = mockMvc.perform(get("/api/v1/csrf"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getCookie(XSRF_COOKIE);
        mockMvc.perform(post("/api/v1/watchlists")
                .cookie(access)
                .cookie(xsrf)
                .header(XSRF_HEADER, xsrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"有 CSRF\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name", equalTo("有 CSRF")));

        Integer rows = jdbcClient.sql("select count(*) from watchlists where user_id = :userId")
            .param("userId", userId("watch-csrf@example.com"))
            .query(Integer.class)
            .single();
        assertThat(rows).isEqualTo(2);
    }

    // ── 項目（WATCH-02，04.2-12）──────────────────────────────────────────

    @Test
    @DisplayName("IT 10b：小寫 symbol（aapl）大小寫不敏感，加入成功且清單含 AAPL（04.2 REVIEW WR-02）")
    void addItemAcceptsLowerCaseSymbol() throws Exception {
        String token = registerAndLogin("watch-add-lower@example.com", "watchaddlower");
        String id = getWatchlists(token).get(0).get("id").asText();

        addItem(token, id, "aapl")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()", equalTo(1)))
            .andExpect(jsonPath("$.data.items[-1].symbol", equalTo("AAPL")));

        assertThat(symbols(getWatchlists(token).get(0).get("items"))).containsExactly("AAPL");
    }

    @Test
    @DisplayName("IT 10：加入 AAPL 出現在尾端；USD/TWD（不可交易）可加入且報價為 null；重複加入 AAPL 原樣回")
    void addItemAppendsAtEndAndIsIdempotent() throws Exception {
        String token = registerAndLogin("watch-add@example.com", "watchadd");
        String id = getWatchlists(token).get(0).get("id").asText();

        addItem(token, id, "AAPL")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id", equalTo(id)))
            .andExpect(jsonPath("$.data.items.length()", equalTo(1)))
            .andExpect(jsonPath("$.data.items[-1].symbol", equalTo("AAPL")));

        JsonNode afterFx = data(addItem(token, id, "USD/TWD").andExpect(status().isOk()));
        assertThat(symbols(afterFx.get("items"))).containsExactly("AAPL", "USD/TWD");
        JsonNode fx = afterFx.get("items").get(1);
        assertThat(fx.get("tradeable").asBoolean()).isFalse();
        assertThat(fx.get("latestPrice").isNull()).isTrue();

        JsonNode again = data(addItem(token, id, "AAPL").andExpect(status().isOk()));
        assertThat(symbols(again.get("items"))).containsExactly("AAPL", "USD/TWD");

        addItem(token, id, "NO-SUCH-SYMBOL")
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code", equalTo("ASSET_NOT_FOUND")))
            .andExpect(jsonPath("$.error.fields.symbol").exists());
        assertThat(symbols(getWatchlists(token).get(0).get("items"))).containsExactly("AAPL", "USD/TWD");
    }

    @Test
    @DisplayName("IT 13：以 SQL 種 99 檔 → 第 100 檔成功；第 101 檔 → 400 VALIDATION_FAILED fields.items")
    void hundredFirstItemIsRejected() throws Exception {
        String token = registerAndLogin("watch-cap@example.com", "watchcap");
        String id = getWatchlists(token).get(0).get("id").asText();
        Long watchlistId = watchlistId(id);
        try {
            createTempAssets("WLCAP-", 101);
            seedTempItems(watchlistId, "WLCAP-", 99);

            addItem(token, id, "WLCAP-100")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()", equalTo(100)))
                .andExpect(jsonPath("$.data.items[-1].symbol", equalTo("WLCAP-100")));

            addItem(token, id, "WLCAP-101")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.error.fields.items").exists());
            assertThat(itemCount(watchlistId)).isEqualTo(100);
        } finally {
            dropTempAssets("WLCAP-");
        }
    }

    @Test
    @DisplayName("IT 13b：已有 99 檔時兩個並發加入不同資產 → 恰一個 200、一個 400 fields.items，最終 100 檔（鎖後另起計數）")
    void concurrentAddsNeverExceedHundred() throws Exception {
        String token = registerAndLogin("watch-cap-race@example.com", "watchcaprace");
        String id = getWatchlists(token).get(0).get("id").asText();
        Long watchlistId = watchlistId(id);
        try {
            createTempAssets("WLRACE-", 101);
            seedTempItems(watchlistId, "WLRACE-", 99);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Integer> statuses = new ArrayList<>();
            try {
                List<Future<Integer>> futures = new ArrayList<>();
                for (String symbol : List.of("WLRACE-100", "WLRACE-101")) {
                    futures.add(pool.submit(() -> {
                        start.await();
                        return addItem(token, id, symbol).andReturn().getResponse().getStatus();
                    }));
                }
                start.countDown();
                for (Future<Integer> future : futures) {
                    statuses.add(future.get(30, TimeUnit.SECONDS));
                }
            } finally {
                pool.shutdownNow();
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 400);
            assertThat(itemCount(watchlistId)).isEqualTo(100);
        } finally {
            dropTempAssets("WLRACE-");
        }
    }

    @Test
    @DisplayName("〔WR-03〕種 100 檔、其中 3 檔下架 → GET 顯示 97 檔；上限只計可見項目，再加入一檔成功（quick-261107-1j4）")
    void hundredItemCapCountsOnlyVisibleItems() throws Exception {
        String token = registerAndLogin("watch-cap-visible-" + UUID.randomUUID() + "@example.com",
            "watchcapvis" + UUID.randomUUID().toString().substring(0, 8));
        String id = getWatchlists(token).get(0).get("id").asText();
        Long watchlistId = watchlistId(id);
        try {
            createTempAssets("WLVIS-", 101);
            seedTempItems(watchlistId, "WLVIS-", 100);
            setTempAssetsActive(List.of("WLVIS-001", "WLVIS-002", "WLVIS-003"), false);

            assertThat(getWatchlists(token).get(0).get("items").size()).isEqualTo(97);
            addItem(token, id, "WLVIS-101")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()", equalTo(98)))
                .andExpect(jsonPath("$.data.items[-1].symbol", equalTo("WLVIS-101")));
        } finally {
            dropTempAssets("WLVIS-");
        }
    }

    @Test
    @DisplayName("〔WR-03〕重排後把隱藏（下架）資產重新上架 → 依原相對順序出現在可見項目尾端（quick-261107-1j4）")
    void reactivatedHiddenItemAppearsAtTheEndAfterReorder() throws Exception {
        String token = registerAndLogin("watch-hidden-" + UUID.randomUUID() + "@example.com",
            "watchhidden" + UUID.randomUUID().toString().substring(0, 8));
        String id = getWatchlists(token).get(0).get("id").asText();
        Long watchlistId = watchlistId(id);
        try {
            createTempAssets("WLHID-", 4);
            seedTempItems(watchlistId, "WLHID-", 4);
            setTempAssetsActive(List.of("WLHID-002"), false);
            JsonNode items = getWatchlists(token).get(0).get("items");
            assertThat(symbols(items)).containsExactly("WLHID-001", "WLHID-003", "WLHID-004");
            String first = items.get(0).get("uuid").asText();
            String third = items.get(1).get("uuid").asText();
            String fourth = items.get(2).get("uuid").asText();

            reorder(token, id, List.of(fourth, third, first)).andExpect(status().isOk());
            setTempAssetsActive(List.of("WLHID-002"), true);

            assertThat(symbols(getWatchlists(token).get(0).get("items")))
                .containsExactly("WLHID-004", "WLHID-003", "WLHID-001", "WLHID-002");
        } finally {
            dropTempAssets("WLHID-");
        }
    }

    private void setTempAssetsActive(List<String> symbols, boolean active) {
        jdbcClient.sql("update assets set active = :active where symbol in (:symbols)")
            .param("active", active)
            .param("symbols", symbols)
            .update();
    }

    @Test
    @DisplayName("IT 11：加入 A、B、C → PUT order [C, A, B] → 200 且順序 C, A, B；GET 也是（持久化）；PUT [A, B] → 409")
    void reorderPersistsAndRejectsDifferentSet() throws Exception {
        String token = registerAndLogin("watch-order@example.com", "watchorder");
        String id = getWatchlists(token).get(0).get("id").asText();
        addItem(token, id, "AAPL").andExpect(status().isOk());
        addItem(token, id, "BTC").andExpect(status().isOk());
        JsonNode items = data(addItem(token, id, "US10Y").andExpect(status().isOk())).get("items");
        assertThat(symbols(items)).containsExactly("AAPL", "BTC", "US10Y");
        String a = items.get(0).get("uuid").asText();
        String b = items.get(1).get("uuid").asText();
        String c = items.get(2).get("uuid").asText();

        JsonNode reordered = data(reorder(token, id, List.of(c, a, b)).andExpect(status().isOk()));
        assertThat(symbols(reordered.get("items"))).containsExactly("US10Y", "AAPL", "BTC");
        assertThat(symbols(getWatchlists(token).get(0).get("items"))).containsExactly("US10Y", "AAPL", "BTC");

        reorder(token, id, List.of(a, b))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code", equalTo("WATCHLIST_ORDER_CONFLICT")));
        reorder(token, id, List.of(a, b, b))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code", equalTo("WATCHLIST_ORDER_CONFLICT")));
        assertThat(symbols(getWatchlists(token).get(0).get("items"))).containsExactly("US10Y", "AAPL", "BTC");

        addItem(token, id, "ETH")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[-1].symbol", equalTo("ETH")));
    }

    @Test
    @DisplayName("IT 12：DELETE items/{AAPL uuid} → items 不含 AAPL；再 DELETE → 200 原樣；未知資產 uuid → 200 原樣")
    void removeItemIsIdempotent() throws Exception {
        String token = registerAndLogin("watch-remove@example.com", "watchremove");
        String id = getWatchlists(token).get(0).get("id").asText();
        String aapl = data(addItem(token, id, "AAPL").andExpect(status().isOk())).get("items").get(0).get("uuid").asText();
        addItem(token, id, "MSFT").andExpect(status().isOk());

        JsonNode removed = data(removeItem(token, id, aapl).andExpect(status().isOk()));
        assertThat(symbols(removed.get("items"))).containsExactly("MSFT");

        JsonNode again = data(removeItem(token, id, aapl).andExpect(status().isOk()));
        assertThat(symbols(again.get("items"))).containsExactly("MSFT");

        JsonNode unknown = data(removeItem(token, id, UUID.randomUUID().toString()).andExpect(status().isOk()));
        assertThat(symbols(unknown.get("items"))).containsExactly("MSFT");

        removeItem(token, id, "not-a-uuid")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("IT 14：使用者 B 對 A 的清單 POST / DELETE / PUT items → 皆 404 WATCHLIST_NOT_FOUND，A 的項目不變")
    void otherUsersCannotTouchItems() throws Exception {
        String ownerToken = registerAndLogin("watch-items-owner@example.com", "watchitemsowner");
        String intruderToken = registerAndLogin("watch-items-intruder@example.com", "watchitemsintruder");
        String id = getWatchlists(ownerToken).get(0).get("id").asText();
        String aapl = data(addItem(ownerToken, id, "AAPL").andExpect(status().isOk()))
            .get("items").get(0).get("uuid").asText();

        addItem(intruderToken, id, "MSFT")
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code", equalTo("WATCHLIST_NOT_FOUND")));
        removeItem(intruderToken, id, aapl)
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code", equalTo("WATCHLIST_NOT_FOUND")));
        reorder(intruderToken, id, List.of(aapl))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code", equalTo("WATCHLIST_NOT_FOUND")));
        String body = addItem(ownerToken, UUID.randomUUID().toString(), "AAPL")
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code", equalTo("WATCHLIST_NOT_FOUND")))
            .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(id);

        assertThat(symbols(getWatchlists(ownerToken).get(0).get("items"))).containsExactly("AAPL");
    }

    @Test
    @DisplayName("IT 15：cookie 路徑 POST / DELETE / PUT items 不帶 CSRF header → 403 AUTH_CSRF_TOKEN_INVALID")
    void cookieItemMutationsRequireCsrf() throws Exception {
        Cookie access = registerAndLoginWithCookie("watch-items-csrf@example.com", "watchitemscsrf");
        String body = mockMvc.perform(get("/api/v1/watchlists").cookie(access))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(body).get("data").get(0).get("id").asText();

        mockMvc.perform(post("/api/v1/watchlists/" + id + "/items")
                .cookie(access)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"symbol\":\"AAPL\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code", equalTo("AUTH_CSRF_TOKEN_INVALID")));
        mockMvc.perform(delete("/api/v1/watchlists/" + id + "/items/" + UUID.randomUUID()).cookie(access))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code", equalTo("AUTH_CSRF_TOKEN_INVALID")));
        mockMvc.perform(put("/api/v1/watchlists/" + id + "/items/order")
                .cookie(access)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assetIds\":[]}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code", equalTo("AUTH_CSRF_TOKEN_INVALID")));

        Integer rows = jdbcClient.sql("select count(*) from watchlist_items where watchlist_id = :id")
            .param("id", watchlistId(id))
            .query(Integer.class)
            .single();
        assertThat(rows).isZero();
    }

    private ResultActions removeItem(String token, String id, String assetId) throws Exception {
        return mockMvc.perform(delete("/api/v1/watchlists/" + id + "/items/" + assetId)
            .header("Authorization", "Bearer " + token));
    }

    private ResultActions reorder(String token, String id, List<String> assetIds) throws Exception {
        return mockMvc.perform(put("/api/v1/watchlists/" + id + "/items/order")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("assetIds", assetIds))));
    }

    private ResultActions addItem(String token, String id, String symbol) throws Exception {
        return mockMvc.perform(post("/api/v1/watchlists/" + id + "/items")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("symbol", symbol))));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    private static List<String> symbols(JsonNode items) {
        List<String> symbols = new ArrayList<>();
        items.forEach(item -> symbols.add(item.get("symbol").asText()));
        return symbols;
    }

    private Long watchlistId(String uuid) {
        return jdbcClient.sql("select id from watchlists where uuid = :uuid")
            .param("uuid", UUID.fromString(uuid))
            .query(Long.class)
            .single();
    }

    private int itemCount(Long watchlistId) {
        return jdbcClient.sql("select count(*) from watchlist_items where watchlist_id = :id")
            .param("id", watchlistId)
            .query(Integer.class)
            .single();
    }

    /**
     * 種子只有 19 檔資產，上限測試需要 101 檔：建立暫時的 active、不可交易資產（不會被 ingestor 輪詢），
     * 測試結束以 {@link #dropTempAssets} 清除（{@code FoundationMigrationIT} 斷言資產總數為 19）。
     */
    private void createTempAssets(String prefix, int count) {
        jdbcClient.sql("""
                insert into assets (symbol, name, asset_type, market, currency, tradeable, active)
                select :prefix || lpad(g::text, 3, '0'), 'Watchlist temp ' || g, 'STOCK', 'US', 'USD', false, true
                from generate_series(1, :count) g
                """)
            .param("prefix", prefix)
            .param("count", count)
            .update();
    }

    private void seedTempItems(Long watchlistId, String prefix, int count) {
        jdbcClient.sql("""
                insert into watchlist_items (watchlist_id, asset_id, position)
                select :watchlistId, a.id, row_number() over (order by a.symbol)
                from assets a
                where a.symbol like :prefix || '%' and a.symbol <= :prefix || lpad(cast(:count as text), 3, '0')
                """)
            .param("watchlistId", watchlistId)
            .param("prefix", prefix)
            .param("count", count)
            .update();
    }

    private void dropTempAssets(String prefix) {
        jdbcClient.sql("""
                delete from watchlist_items
                where asset_id in (select id from assets where symbol like :prefix || '%')
                """)
            .param("prefix", prefix)
            .update();
        jdbcClient.sql("delete from assets where symbol like :prefix || '%'")
            .param("prefix", prefix)
            .update();
    }

    private ResultActions createWatchlist(String token, String name) throws Exception {
        return mockMvc.perform(post("/api/v1/watchlists")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("name", name))));
    }

    private ResultActions renameWatchlist(String token, String id, String name) throws Exception {
        return mockMvc.perform(patch("/api/v1/watchlists/" + id)
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("name", name))));
    }

    private String createdId(ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data").get("id").asText();
    }

    private JsonNode getWatchlists(String token) throws Exception {
        String body = mockMvc.perform(get("/api/v1/watchlists").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", equalTo(true)))
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data");
    }

    private void seedItem(Long watchlistId, String symbol, int position) {
        jdbcClient.sql("""
                insert into watchlist_items (watchlist_id, asset_id, position)
                select :watchlistId, a.id, :position from assets a where a.symbol = :symbol
                """)
            .param("watchlistId", watchlistId)
            .param("position", position)
            .param("symbol", symbol)
            .update();
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    private Long userId(String email) {
        return jdbcClient.sql("select id from users where email = :email")
            .param("email", email)
            .query(Long.class)
            .single();
    }

    private String registerAndLogin(String email, String username) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","username":"%s","password":"%s"}
                    """.formatted(email, username, PASSWORD)))
            .andExpect(status().isOk());

        String body = mockMvc.perform(post("/api/v1/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"%s"}
                    """.formatted(email, PASSWORD)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readTree(body).get("data").get("accessToken").asText();
    }

    private Cookie registerAndLoginWithCookie(String email, String username) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","username":"%s","password":"%s"}
                    """.formatted(email, username, PASSWORD)))
            .andExpect(status().isOk());

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"%s"}
                    """.formatted(email, PASSWORD)))
            .andExpect(status().isOk())
            .andReturn();
        return login.getResponse().getCookie(ACCESS_COOKIE);
    }
}
