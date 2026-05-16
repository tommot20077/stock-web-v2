package dowob.xyz.stockwebv2.start;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @MockitoBean
    ObjectMapper objectMapper;

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
