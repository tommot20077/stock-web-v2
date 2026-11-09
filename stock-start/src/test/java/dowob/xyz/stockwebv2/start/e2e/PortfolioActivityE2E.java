package dowob.xyz.stockwebv2.start.e2e;

import dowob.xyz.stockwebv2.start.e2e.support.AbstractPortfolioE2E;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;

/**
 * 統一活動列表（PORT-13）的 REST 層 E2E：交易、現金股利、股數調整混合後的預設排序、type / symbol 篩選、
 * {@code NULLS LAST} 排序、逐頁走訪無重複，以及跨使用者隔離。
 *
 * <p>契約見 {@code ai-docs/trading-portfolio-contract.md}〈GET /api/v1/portfolio/activities〉：預設依 {@code occurredAt}
 * （交易成交時間 / 股利入帳時間 / 調整生效起點）降冪；所選排序欄位 {@code NULLS LAST}，tie-breaker 為 {@code createdAt}、
 * {@code id}。與 {@code PortfolioActivityApiIT} 不同，本類別的時間全部相對於今日美股交易日起點
 * （{@code TradingDay.of(STOCK, "US")}）產生，事件都經真實寫入端點建立（含 {@code POST /trades} 的調整守門）。</p>
 *
 * <p>情境（時間由舊到新）：AAPL 買 10 @150（今日起點前 72 小時）→ TSLA 買 20 @100（前 48 小時）→
 * AAPL 現金股利 120（前 1 小時）→ TSLA 配股 5（今日除權，occurredAt = 今日起點）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("Portfolio Activity E2E")
class PortfolioActivityE2E extends AbstractPortfolioE2E {

    /** 情境中四筆事件的 UUID。 */
    private record Seeded(String aaplBuy, String tslaBuy, String dividend, String adjustment) {
    }

    /**
     * 為使用者記下四筆混合事件（兩筆交易、一筆股利、一筆配股）。
     *
     * @param token access token
     * @return 四筆事件的 UUID
     * @throws Exception MockMvc 失敗時
     */
    private Seeded seedMixedHistory(String token) throws Exception {
        Instant todayStart = US_STOCK.dayStart(Instant.now());
        String aaplBuy = recordTrade(token, "AAPL", "BUY", "10", "150", iso(todayStart.minus(Duration.ofHours(72))))
            .get("id").asText();
        String tslaBuy = recordTrade(token, "TSLA", "BUY", "20", "100", iso(todayStart.minus(Duration.ofHours(48))))
            .get("id").asText();
        String dividend = dataOf(postDividend(token, UUID.randomUUID().toString(), """
            {"symbol":"AAPL","amount":120,"withholdingTax":30,"paidAt":"%s"}
            """.formatted(iso(todayStart.minus(Duration.ofHours(1)))))
            .andExpect(status().isOk())).get("id").asText();
        String adjustment = recordShareAdjustment(token, """
            {"symbol":"TSLA","type":"STOCK_DIVIDEND","quantity":5,"effectiveDate":"%s"}
            """.formatted(usTradingDate(todayStart))).get("id").asText();
        return new Seeded(aaplBuy, tslaBuy, dividend, adjustment);
    }

    @Test
    @DisplayName("混合 2 筆交易 + 1 股利 + 1 配股：預設依 occurredAt desc 交錯；type / symbol 篩選；另一使用者看不到任何一筆")
    void mixedEventsAreInterleavedFilteredAndIsolated() throws Exception {
        var alice = auth.register("activity-e2e-mixed@example.com", "activitye2emixed", PASSWORD);
        var bob = auth.register("activity-e2e-other@example.com", "activitye2eother", PASSWORD);
        String token = alice.accessToken();
        Seeded seeded = seedMixedHistory(token);

        JsonNode all = activitiesOf(token);
        assertThat(all.get("totalElements").asLong()).isEqualTo(4);
        assertThat(ids(all)).containsExactly(seeded.adjustment(), seeded.dividend(), seeded.tslaBuy(), seeded.aaplBuy());
        assertThat(kinds(all)).containsExactly("STOCK_DIVIDEND", "CASH_DIVIDEND", "BUY", "BUY");

        JsonNode adjustments = activitiesOf(token, "type", "SHARE_ADJUSTMENT");
        assertThat(adjustments.get("totalElements").asLong()).isEqualTo(1);
        JsonNode adjustment = adjustments.get("items").get(0);
        assertThat(adjustment.get("id").asText()).isEqualTo(seeded.adjustment());
        assertThat(adjustment.get("symbol").asText()).isEqualTo("TSLA");
        assertThat(adjustment.get("quantity").decimalValue()).isEqualByComparingTo("5");
        assertThat(adjustment.get("effectiveDate").asText()).isEqualTo(usTradingDate(Instant.now()).toString());
        assertThat(adjustment.get("amount").isNull()).isTrue();
        assertThat(adjustment.get("reversible").asBoolean()).isTrue();

        assertThat(ids(activitiesOf(token, "type", "DIVIDEND"))).containsExactly(seeded.dividend());
        assertThat(ids(activitiesOf(token, "type", "BUY"))).containsExactly(seeded.tslaBuy(), seeded.aaplBuy());
        assertThat(activitiesOf(token, "type", "SELL").get("totalElements").asLong()).isZero();
        assertThat(ids(activitiesOf(token, "symbol", "TSLA"))).containsExactly(seeded.adjustment(), seeded.tslaBuy());

        JsonNode bobAll = activitiesOf(bob.accessToken());
        assertThat(bobAll.get("totalElements").asLong()).isZero();
        assertThat(bobAll.get("items")).isEmpty();
        assertThat(activitiesOf(bob.accessToken(), "type", "SHARE_ADJUSTMENT").get("totalElements").asLong()).isZero();
        assertThat(activitiesOf(bob.accessToken(), "symbol", "AAPL").get("totalElements").asLong()).isZero();
    }

    @Test
    @DisplayName("sort=quantity：股利（quantity null）不論 desc / asc 都排最後；size=1 逐頁走完 id 不重複且與單頁順序一致")
    void quantitySortPutsNullsLastAndPagingIsStable() throws Exception {
        var session = auth.register("activity-e2e-sort@example.com", "activitye2esort", PASSWORD);
        String token = session.accessToken();
        Seeded seeded = seedMixedHistory(token);

        /* 數量：TSLA 買 20、AAPL 買 10、配股 5、股利 null。 */
        JsonNode desc = activitiesOf(token, "sort", "quantity", "direction", "desc");
        assertThat(ids(desc)).containsExactly(seeded.tslaBuy(), seeded.aaplBuy(), seeded.adjustment(), seeded.dividend());
        JsonNode asc = activitiesOf(token, "sort", "quantity", "direction", "asc");
        assertThat(ids(asc)).containsExactly(seeded.adjustment(), seeded.aaplBuy(), seeded.tslaBuy(), seeded.dividend());

        /* 金額：TSLA 2000、AAPL 1500、股利 120、配股 null（排最後）。 */
        JsonNode byTotal = activitiesOf(token, "sort", "total", "direction", "desc");
        assertThat(ids(byTotal)).containsExactly(seeded.tslaBuy(), seeded.aaplBuy(), seeded.dividend(), seeded.adjustment());

        List<String> singlePage = ids(activitiesOf(token, "sort", "quantity", "direction", "desc", "size", "20"));
        JsonNode firstPage = activitiesOf(token, "sort", "quantity", "direction", "desc", "size", "1", "page", "0");
        long total = firstPage.get("totalElements").asLong();
        assertThat(total).isEqualTo(4);
        assertThat(firstPage.get("totalPages").asLong()).isEqualTo(4);
        List<String> walked = new ArrayList<>();
        for (int page = 0; page < total; page++) {
            JsonNode slice = activitiesOf(token, "sort", "quantity", "direction", "desc", "size", "1",
                "page", String.valueOf(page));
            assertThat(slice.get("items")).hasSize(1);
            walked.addAll(ids(slice));
        }
        assertThat(walked).hasSize(4).doesNotHaveDuplicates().containsExactlyElementsOf(singlePage);
    }

    /**
     * 將瞬間轉成截到秒的 ISO-8601 字串。
     *
     * @param time 瞬間
     * @return ISO-8601 字串
     */
    private static String iso(Instant time) {
        return time.truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /**
     * 取出分頁回應中依序的 id。
     *
     * @param page {@code PageResponse} 節點
     * @return id 清單
     */
    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(item.get("id").asText()));
        return ids;
    }

    /**
     * 取出分頁回應中依序的 kind。
     *
     * @param page {@code PageResponse} 節點
     * @return kind 清單
     */
    private static List<String> kinds(JsonNode page) {
        List<String> kinds = new ArrayList<>();
        page.get("items").forEach(item -> kinds.add(item.get("kind").asText()));
        return kinds;
    }
}
