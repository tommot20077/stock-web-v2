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
    void staleAccessCookieDoesNotBlockReLoginOnPublicEndpoint() throws Exception {
        // 全域登出會遞增 tokenVersion，使既有 access cookie 失效（security.md §5）。瀏覽器仍會自動
        // 帶著這個失效的 stock_access cookie；重新登入時，filter 不應在 permitAll 的 /auth/login
        // 端點因 cookie 失效而回 401，否則使用者會被卡在無法重新登入（除非手動清 cookie）。
        MvcResult login = login("stale-relogin@example.com", "stalerelogin");
        Cookie staleAccess = login.getResponse().getCookie(ACCESS_COOKIE);
        Cookie refresh = login.getResponse().getCookie(REFRESH_COOKIE);
        Cookie xsrf = csrfCookie();

        mockMvc.perform(post("/api/v1/auth/logout")
                .cookie(staleAccess)
                .cookie(refresh)
                .cookie(xsrf)
                .header(XSRF_HEADER, xsrf.getValue()))
            .andExpect(status().is2xxSuccessful());

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"stale-relogin@example.com","password":"Password1"}
                    """)
                .cookie(staleAccess))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", equalTo(true)));
    }

    /**
     * 瀏覽器已帶<strong>有效</strong> access cookie 時,重新 POST /auth/login 也必須通過 CSRF。
     *
     * <p>這是 {@code ai-docs/browser-auth-contract.md} CSRF 節補述(D-03,由 05-05 寫入契約)的測試證據:
     * {@code JwtAuthenticationFilter} 對有效 access cookie 設定 cookie 認證(transport=cookie),
     * {@code BrowserCsrfFilter} 因此對 permitAll 的 login 也要求 double-submit。失效 cookie 不會擋
     * 重新登入,見 {@link #staleAccessCookieDoesNotBlockReLoginOnPublicEndpoint()}。Phase 5 SC1 缺口 G3。
     *
     * @throws Exception MockMvc 執行失敗時
     */
    @Test
    void loginWithValidAccessCookieRequiresCsrf() throws Exception {
        MvcResult login = login("valid-cookie-login@example.com", "validcookielogin");
        Cookie validAccess = login.getResponse().getCookie(ACCESS_COOKIE);

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"valid-cookie-login@example.com","password":"Password1"}
                    """)
                .cookie(validAccess))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.success", equalTo(false)))
            .andExpect(jsonPath("$.error.code", equalTo("AUTH_CSRF_TOKEN_INVALID")))
            .andExpect(jsonPath("$.meta.traceId").exists());

        Cookie xsrf = csrfCookie();
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"valid-cookie-login@example.com","password":"Password1"}
                    """)
                .cookie(validAccess)
                .cookie(xsrf)
                .header(XSRF_HEADER, xsrf.getValue()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", equalTo(true)));
    }

    /**
     * 瀏覽器已帶<strong>有效</strong> access cookie 時,POST /auth/register 也必須通過 CSRF。
     *
     * <p>與 {@link #loginWithValidAccessCookieRequiresCsrf()} 同屬 {@code browser-auth-contract.md}
     * CSRF 節補述(D-03)的測試證據。缺 CSRF 被拒後,以相符 CSRF 用<strong>同一個</strong> email 註冊
     * 仍成功,順帶證明被拒的請求沒有建立帳號。Phase 5 SC1 缺口 G3。
     *
     * @throws Exception MockMvc 執行失敗時
     */
    @Test
    void registerWithValidAccessCookieRequiresCsrf() throws Exception {
        MvcResult login = login("valid-cookie-register@example.com", "validcookieregister");
        Cookie validAccess = login.getResponse().getCookie(ACCESS_COOKIE);
        String newAccountBody = """
            {"email":"valid-cookie-register-new@example.com","username":"validcookieregnew","password":"Password1"}
            """;

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(newAccountBody)
                .cookie(validAccess))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.success", equalTo(false)))
            .andExpect(jsonPath("$.error.code", equalTo("AUTH_CSRF_TOKEN_INVALID")))
            .andExpect(jsonPath("$.meta.traceId").exists());

        Cookie xsrf = csrfCookie();
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(newAccountBody)
                .cookie(validAccess)
                .cookie(xsrf)
                .header(XSRF_HEADER, xsrf.getValue()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", equalTo(true)));
    }

    /**
     * cookie 模式登出後,登出前取得的 access cookie 立即失效(security.md §5 Instant logout)。
     *
     * <p>bearer 版見 {@code LogoutInvalidatesAccessTokenIT#accessTokenIsRejectedAfterLogout};
     * 這裡鎖住瀏覽器實際走的 cookie 路徑,並斷言 401 為完整失敗信封。Phase 5 SC1 缺口 G4(SC4 的後端面)。
     *
     * @throws Exception MockMvc 執行失敗時
     */
    @Test
    void cookieLogoutRevokesAccessCookieForMe() throws Exception {
        MvcResult login = login("cookie-logout-me@example.com", "cookielogoutme");
        Cookie accessBeforeLogout = login.getResponse().getCookie(ACCESS_COOKIE);
        Cookie refresh = login.getResponse().getCookie(REFRESH_COOKIE);
        Cookie xsrf = csrfCookie();

        mockMvc.perform(get("/api/v1/me").cookie(accessBeforeLogout))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/logout")
                .cookie(accessBeforeLogout)
                .cookie(refresh)
                .cookie(xsrf)
                .header(XSRF_HEADER, xsrf.getValue()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", equalTo(true)));

        mockMvc.perform(get("/api/v1/me").cookie(accessBeforeLogout))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success", equalTo(false)))
            .andExpect(jsonPath("$.error.code", equalTo("AUTH_INVALID_CREDENTIALS")))
            .andExpect(jsonPath("$.meta.traceId").exists());
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
