package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.common.model.AssetType;
import dowob.xyz.stockwebv2.common.model.TradingDay;
import dowob.xyz.stockwebv2.start.support.ContainerIT;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AssetApiIT extends ContainerIT {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    StringRedisTemplate redisTemplate;

    /**
     * 報價卡的六個數字必須來自 market-data 的行情(market_prices / Redis latest),
     * 且「前收」以該資產市場的交易日為界——不是種子表 asset_latest_prices(NVDA 種子價 1142.83)。
     */
    @Test
    void publicAssetsQuoteComesFromMarketDataByTradingDay() throws Exception {
        Long nvda = jdbcClient.sql("select id from assets where symbol = 'NVDA'").query(Long.class).single();
        redisTemplate.delete("market:latest:" + nvda);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant dayStart = TradingDay.of(AssetType.STOCK, "US").dayStart(now);
        Instant midDay = dayStart.plus(Duration.between(dayStart, now).dividedBy(2));
        insertTick(nvda, dayStart.minus(2, ChronoUnit.HOURS), "100", "10");
        insertTick(nvda, midDay, "99", "1000");
        insertTick(nvda, now, "105", "2000");

        mockMvc.perform(get("/api/v1/assets?query=NVDA&page=0&size=20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", equalTo(true)))
            .andExpect(jsonPath("$.data.items[0].symbol", equalTo("NVDA")))
            .andExpect(jsonPath("$.data.items[0].latestPrice").value(105.0))
            .andExpect(jsonPath("$.data.items[0].change").value(5.0))
            .andExpect(jsonPath("$.data.items[0].changePercent").value(5.0))
            .andExpect(jsonPath("$.data.items[0].high").value(105.0))
            .andExpect(jsonPath("$.data.items[0].low").value(99.0))
            .andExpect(jsonPath("$.data.items[0].volumeText", equalTo("3.0K")));
    }

    @Test
    void publicAssetsWithoutMarketDataHaveNullQuote() throws Exception {
        mockMvc.perform(get("/api/v1/assets?query=US10Y&page=0&size=20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].symbol", equalTo("US10Y")))
            .andExpect(jsonPath("$.data.items[0].latestPrice").value(nullValue()))
            .andExpect(jsonPath("$.data.items[0].change").value(nullValue()));
    }

    private void insertTick(Long assetId, Instant time, String price, String volume) {
        jdbcClient.sql("""
                insert into market_prices(asset_id, time, price, volume)
                values (:assetId, :time, :price, :volume)
                on conflict (asset_id, time) do update set price = excluded.price, volume = excluded.volume
                """)
            .param("assetId", assetId)
            .param("time", Timestamp.from(time))
            .param("price", new BigDecimal(price))
            .param("volume", new BigDecimal(volume))
            .update();
    }

    @Test
    void publicAssetsClampsHugePageBeforeQuerying() throws Exception {
        mockMvc.perform(get("/api/v1/assets?page=2147483647&size=100"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", equalTo(true)))
            .andExpect(jsonPath("$.data.page", equalTo(10000)))
            .andExpect(jsonPath("$.data.size", equalTo(100)));
    }

    @Test
    void nonGetAssetPathRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/assets"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success", equalTo(false)))
            .andExpect(jsonPath("$.error.code", equalTo("AUTH_INVALID_CREDENTIALS")));
    }
}
