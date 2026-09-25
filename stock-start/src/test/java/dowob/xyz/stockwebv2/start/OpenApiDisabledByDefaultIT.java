package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.start.support.ContainerIT;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI 與 Swagger UI 在未設環境變數時不可存取。
 *
 * <p>本 IT 以預設 profile（{@code profiles.default: dev}）啟動——也就是「部署時忘了指定 profile」的情境。
 * {@code SecurityConfig} 對 {@code /v3/api-docs/**}、{@code /swagger-ui/**} permitAll，
 * 所以唯一的防線就是 springdoc 本身的開關。這裡以預設設定啟動，確認兩個端點都拿不到內容
 * （2026-09-02 安全審查 M-3）。
 *
 * @author Yuan
 * @version 1.0.0
 */
@AutoConfigureMockMvc
@DisplayName("OpenAPI 預設不可存取")
class OpenApiDisabledByDefaultIT extends ContainerIT {

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("未開啟時 /v3/api-docs 不回 200")
    void apiDocsIsNotServedByDefault() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().is(not(200)));
    }

    @Test
    @DisplayName("未開啟時 /swagger-ui/index.html 不回 200")
    void swaggerUiIsNotServedByDefault() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().is(not(200)));
    }
}
