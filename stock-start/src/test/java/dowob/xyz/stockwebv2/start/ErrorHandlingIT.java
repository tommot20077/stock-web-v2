package dowob.xyz.stockwebv2.start;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.config.name=application-test")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ErrorHandlingIT {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    StringRedisTemplate redisTemplate;

    @Test
    void businessExceptionReturnsApiResponseAndTraceId() throws Exception {
        mockMvc.perform(get("/test-only/error/business").with(user("test")).header("X-Trace-Id", "trace-test-1"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success", equalTo(false)))
            .andExpect(jsonPath("$.data").isEmpty())
            .andExpect(jsonPath("$.error.code", equalTo("DUPLICATE_RESOURCE")))
            .andExpect(jsonPath("$.meta.traceId", equalTo("trace-test-1")))
            .andExpect(jsonPath("$.meta.timestamp", notNullValue()));
    }

    @Test
    void traceIdHeaderIsTrimmedWhenValid() throws Exception {
        mockMvc.perform(get("/test-only/error/business").with(user("test")).header("X-Trace-Id", " trace-test-2 "))
            .andExpect(status().isConflict())
            .andExpect(header().string("X-Trace-Id", equalTo("trace-test-2")))
            .andExpect(jsonPath("$.meta.traceId", equalTo("trace-test-2")));
    }

    @Test
    void invalidTraceIdHeaderIsReplaced() throws Exception {
        String invalidTraceId = "x".repeat(129);

        mockMvc.perform(get("/test-only/error/business").with(user("test")).header("X-Trace-Id", invalidTraceId))
            .andExpect(status().isConflict())
            .andExpect(header().exists("X-Trace-Id"))
            .andExpect(header().string("X-Trace-Id", not(equalTo(invalidTraceId))))
            .andExpect(jsonPath("$.meta.traceId", not(equalTo(invalidTraceId))));
    }

    @Test
    void blankTraceIdHeaderIsReplaced() throws Exception {
        String invalidTraceId = "   ";

        mockMvc.perform(get("/test-only/error/business").with(user("test")).header("X-Trace-Id", invalidTraceId))
            .andExpect(status().isConflict())
            .andExpect(header().exists("X-Trace-Id"))
            .andExpect(header().string("X-Trace-Id", not(equalTo(invalidTraceId))))
            .andExpect(jsonPath("$.meta.traceId", not(equalTo(invalidTraceId))));
    }

    /**
     * 交易建立的合法 body；{@code note} 帶可辨識字串，用來驗證錯誤回應不會回射使用者輸入。
     */
    private static final String LEAK_CANARY = "LEAK-CANARY-12345";

    private static final String TRADE_BODY = """
        {
          "symbol": "AAPL",
          "type": "BUY",
          "quantity": 10,
          "price": 150.00,
          "note": "%s",
          "executedAt": "2026-01-10T00:00:00Z"
        }
        """.formatted(LEAK_CANARY);

    @Test
    void missingRequiredHeaderReturnsValidationEnvelopeWithHeaderName() throws Exception {
        mockMvc.perform(post("/api/v1/trades")
                .with(user("42"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(TRADE_BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success", equalTo(false)))
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")))
            /*
             * fields 指出 header 名稱是 D-16 的核心：沒有它，前端只能靠解析 message 來分辨
             * 「缺 header」與「body 欄位錯」，而 message 是可以被改動的展示字串。
             */
            .andExpect(jsonPath("$.error.fields['Idempotency-Key']", notNullValue()))
            .andExpect(jsonPath("$.error.fields['Idempotency-Key']", not(equalTo(""))));
    }

    @Test
    void missingRequiredHeaderResponseDoesNotEchoUserInput() throws Exception {
        String body = mockMvc.perform(post("/api/v1/trades")
                .with(user("42"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(TRADE_BODY))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();

        /*
         * 斷言整個 body 而非只有 message：使用者輸入也可能從 fields 的 value、meta、
         * 或某個未預期的欄位漏出去（T-04-03）。
         */
        assertThat(body).doesNotContain(LEAK_CANARY);
    }

    @Test
    void frameworkStatusExceptionPreservesStatusAndApiEnvelope() throws Exception {
        mockMvc.perform(get("/test-only/error/status").with(user("test")).header("X-Trace-Id", "trace-status-1"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success", equalTo(false)))
            .andExpect(jsonPath("$.data").isEmpty())
            .andExpect(jsonPath("$.error.code", equalTo("RESOURCE_NOT_FOUND")))
            .andExpect(jsonPath("$.error.message", equalTo("Resource not found")))
            .andExpect(jsonPath("$.meta.traceId", equalTo("trace-status-1")))
            .andExpect(jsonPath("$.meta.timestamp", notNullValue()));
    }
}
