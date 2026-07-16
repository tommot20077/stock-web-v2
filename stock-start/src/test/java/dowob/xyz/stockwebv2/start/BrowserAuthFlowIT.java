package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.start.support.ContainerIT;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class BrowserAuthFlowIT extends ContainerIT {
    private static final String ACCESS_COOKIE = "stock_access";
    private static final String REFRESH_COOKIE = "stock_refresh";
    private static final String XSRF_COOKIE = "XSRF-TOKEN";
    private static final String XSRF_HEADER = "X-XSRF-TOKEN";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Test
    void registerSetsHttpOnlyAuthCookiesAndOmitsTokenBody() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"browser-register@example.com","username":"browserregister","password":"Password1"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", equalTo(true)))
            .andExpect(jsonPath("$.data.user.email", equalTo("browser-register@example.com")))
            .andExpect(jsonPath("$.data.accessToken").doesNotExist())
            .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
            .andReturn();

        assertAuthCookieHeaders(result);
    }

    @Test
    void loginSetsHttpOnlyAuthCookiesAndOmitsTokenBody() throws Exception {
        register("browser-login@example.com", "browserlogin");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"browser-login@example.com","password":"Password1"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", equalTo(true)))
            .andExpect(jsonPath("$.data.user.email", equalTo("browser-login@example.com")))
            .andExpect(jsonPath("$.data.accessToken").doesNotExist())
            .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
            .andReturn();

        assertAuthCookieHeaders(result);
    }

    @Test
    void meAcceptsAccessCookieWithoutBearerHeader() throws Exception {
        MvcResult login = login("cookie-me@example.com", "cookieme");
        Cookie accessCookie = login.getResponse().getCookie(ACCESS_COOKIE);

        mockMvc.perform(get("/api/v1/me").cookie(accessCookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.email", equalTo("cookie-me@example.com")));
    }

    @Test
    void csrfBootstrapSetsReadableXsrfCookie() throws Exception {
        mockMvc.perform(get("/api/v1/csrf"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", equalTo(true)))
            .andExpect(cookie().exists(XSRF_COOKIE))
            .andExpect(cookie().httpOnly(XSRF_COOKIE, false));
    }

    @Test
    void cookieLogoutWithoutCsrfHeaderReturnsCsrfErrorEnvelope() throws Exception {
        MvcResult login = login("csrf-missing@example.com", "csrfmissing");

        mockMvc.perform(post("/api/v1/auth/logout")
                .cookie(login.getResponse().getCookie(ACCESS_COOKIE))
                .cookie(login.getResponse().getCookie(REFRESH_COOKIE)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.success", equalTo(false)))
            .andExpect(jsonPath("$.error.code", equalTo("AUTH_CSRF_TOKEN_INVALID")))
            .andExpect(jsonPath("$.meta.traceId").exists());
    }

    @Test
    void cookieLogoutWithMatchingCsrfDoesNotFailWithCsrfError() throws Exception {
        MvcResult login = login("csrf-present@example.com", "csrfpresent");
        MvcResult csrf = mockMvc.perform(get("/api/v1/csrf"))
            .andExpect(status().isOk())
            .andReturn();
        Cookie xsrfCookie = csrf.getResponse().getCookie(XSRF_COOKIE);

        mockMvc.perform(post("/api/v1/auth/logout")
                .cookie(login.getResponse().getCookie(ACCESS_COOKIE))
                .cookie(login.getResponse().getCookie(REFRESH_COOKIE))
                .cookie(xsrfCookie)
                .header(XSRF_HEADER, xsrfCookie.getValue()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", equalTo(true)));
    }

    @Test
    void refreshWithoutCsrfHeaderReturnsCsrfErrorEnvelope() throws Exception {
        MvcResult login = login("refresh-csrf@example.com", "refreshcsrf");

        mockMvc.perform(post("/api/v1/auth/refresh")
                .cookie(login.getResponse().getCookie(REFRESH_COOKIE)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.success", equalTo(false)))
            .andExpect(jsonPath("$.error.code", equalTo("AUTH_CSRF_TOKEN_INVALID")));
    }

    @Test
    void refreshWithMatchingCsrfRotatesAuthCookiesAndOmitsTokenBody() throws Exception {
        MvcResult login = login("refresh-rotate@example.com", "refreshrotate");
        Cookie oldRefresh = login.getResponse().getCookie(REFRESH_COOKIE);
        Cookie xsrfCookie = csrfCookie();

        MvcResult refresh = mockMvc.perform(post("/api/v1/auth/refresh")
                .cookie(oldRefresh)
                .cookie(xsrfCookie)
                .header(XSRF_HEADER, xsrfCookie.getValue()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", equalTo(true)))
            .andExpect(jsonPath("$.data.user.email", equalTo("refresh-rotate@example.com")))
            .andExpect(jsonPath("$.data.accessToken").doesNotExist())
            .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
            .andReturn();

        Cookie newRefresh = refresh.getResponse().getCookie(REFRESH_COOKIE);
        assertThat(newRefresh).isNotNull();
        assertThat(newRefresh.getValue()).isNotEqualTo(oldRefresh.getValue());
        assertThat(redisTemplate.hasKey("user:refresh:" + oldRefresh.getValue())).isFalse();
        assertThat(redisTemplate.hasKey("user:refresh:" + newRefresh.getValue())).isTrue();
    }

    @Test
    void replayedRefreshReturnsInvalidRefreshEnvelopeAndClearsCookies() throws Exception {
        MvcResult login = login("refresh-replay@example.com", "refreshreplay");
        Cookie oldRefresh = login.getResponse().getCookie(REFRESH_COOKIE);
        Cookie xsrfCookie = csrfCookie();

        mockMvc.perform(post("/api/v1/auth/refresh")
                .cookie(oldRefresh)
                .cookie(xsrfCookie)
                .header(XSRF_HEADER, xsrfCookie.getValue()))
            .andExpect(status().isOk());

        MvcResult replay = mockMvc.perform(post("/api/v1/auth/refresh")
                .cookie(oldRefresh)
                .cookie(xsrfCookie)
                .header(XSRF_HEADER, xsrfCookie.getValue()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success", equalTo(false)))
            .andExpect(jsonPath("$.error.code", equalTo("AUTH_REFRESH_TOKEN_INVALID")))
            .andExpect(jsonPath("$.meta.traceId").exists())
            .andReturn();

        assertClearingAuthCookies(replay);
    }

    @Test
    void invalidRefreshReturnsInvalidRefreshEnvelopeAndClearsCookies() throws Exception {
        Cookie xsrfCookie = csrfCookie();
        Cookie invalidRefresh = new Cookie(REFRESH_COOKIE, "not-a-refresh-token");

        MvcResult response = mockMvc.perform(post("/api/v1/auth/refresh")
                .cookie(invalidRefresh)
                .cookie(xsrfCookie)
                .header(XSRF_HEADER, xsrfCookie.getValue()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success", equalTo(false)))
            .andExpect(jsonPath("$.error.code", equalTo("AUTH_REFRESH_TOKEN_INVALID")))
            .andReturn();

        assertClearingAuthCookies(response);
    }

    private void register(String email, String username) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","username":"%s","password":"Password1"}
                    """.formatted(email, username)))
            .andExpect(status().isOk());
    }

    private MvcResult login(String email, String username) throws Exception {
        register(email, username);
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"Password1"}
                    """.formatted(email)))
            .andExpect(status().isOk())
            .andReturn();
    }

    private Cookie csrfCookie() throws Exception {
        return mockMvc.perform(get("/api/v1/csrf"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getCookie(XSRF_COOKIE);
    }

    private void assertAuthCookieHeaders(MvcResult result) {
        List<String> setCookieHeaders = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(setCookieHeaders)
            .anySatisfy(header -> assertThat(header).contains(ACCESS_COOKIE + "=", "HttpOnly"))
            .anySatisfy(header -> assertThat(header).contains(REFRESH_COOKIE + "=", "HttpOnly"));
    }

    private void assertClearingAuthCookies(MvcResult result) {
        assertThat(result.getResponse().getCookie(ACCESS_COOKIE)).isNotNull();
        assertThat(result.getResponse().getCookie(ACCESS_COOKIE).getMaxAge()).isZero();
        assertThat(result.getResponse().getCookie(REFRESH_COOKIE)).isNotNull();
        assertThat(result.getResponse().getCookie(REFRESH_COOKIE).getMaxAge()).isZero();
    }
}
