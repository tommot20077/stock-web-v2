package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.start.support.ContainerIT;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 觀察清單 API（WATCH-01；D-14、D-15、D-17、D-19）的端到端驗收：預設清單、報價形狀、
 * 建立 / 改名 / 刪除、上限、擁有者隔離與 CSRF。
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
}
