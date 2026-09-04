package dowob.xyz.stockwebv2.start.e2e;

import dowob.xyz.stockwebv2.start.e2e.support.AbstractStockE2ETest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static dowob.xyz.stockwebv2.start.e2e.support.StockE2EAssertions.apiSuccess;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Foundation Smoke E2E")
class SmokeE2E extends AbstractStockE2ETest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Spring context, Flyway seed data, and public endpoints are available")
    void foundationInfrastructureIsAvailable() throws Exception {
        Integer usersTable = jdbcTemplate.queryForObject(
            "select count(*) from information_schema.tables where table_name = 'users'",
            Integer.class
        );
        Integer assets = jdbcTemplate.queryForObject("select count(*) from assets", Integer.class);
        Integer prices = jdbcTemplate.queryForObject("select count(*) from asset_latest_prices", Integer.class);

        assertThat(usersTable).isEqualTo(1);
        assertThat(assets).isGreaterThanOrEqualTo(19);
        assertThat(prices).isGreaterThanOrEqualTo(19);

        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", equalTo("UP")));

        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.openapi", notNullValue()));

        mockMvc.perform(get("/api/v1/assets?query=NVDA&page=0&size=20"))
            .andExpect(status().isOk())
            .andExpect(apiSuccess())
            .andExpect(jsonPath("$.data.items.length()", greaterThanOrEqualTo(1)));
    }
}
