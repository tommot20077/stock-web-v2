package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.start.support.ContainerIT;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 瀏覽器 cookie 登入後換取 WebSocket ticket 的 CSRF 整合測試（WATCH-05，judgment §11 negative test）。
 *
 * <p>{@code POST /api/v1/market/ws/ticket} 是 cookie 驗證下的 unsafe request，ticket 又是跨站 WebSocket
 * 劫持的前置條件，因此必須經 {@code BrowserCsrfFilter} 驗證 {@code X-XSRF-TOKEN}：
 * <ul>
 *   <li>cookie 登入、不帶 CSRF header → 403 {@code AUTH_CSRF_TOKEN_INVALID}</li>
 *   <li>cookie 登入、帶正確 CSRF → 200，回應含 ticket、{@code expiresIn = 30}、{@code wsUrl = /ws/v1/market}，
 *       且不回傳任何 access / refresh token</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0.0
 */
@DisplayName("WS ticket：cookie 登入的 CSRF 驗證")
@AutoConfigureMockMvc
class WsTicketCookieCsrfIT extends ContainerIT {

    private static final String ACCESS_COOKIE = "stock_access";
    private static final String XSRF_COOKIE = "XSRF-TOKEN";
    private static final String XSRF_HEADER = "X-XSRF-TOKEN";
    private static final String TICKET_PATH = "/api/v1/market/ws/ticket";

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("cookie 登入後取 ticket 不帶 X-XSRF-TOKEN → 403 AUTH_CSRF_TOKEN_INVALID")
    void cookieTicketRequestWithoutCsrfHeaderIsRejected() throws Exception {
        Cookie access = loginAndGetAccessCookie();

        mockMvc.perform(post(TICKET_PATH).cookie(access))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.success", equalTo(false)))
            .andExpect(jsonPath("$.error.code", equalTo("AUTH_CSRF_TOKEN_INVALID")))
            .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("cookie 登入後取 ticket 帶正確 CSRF → 200，回 ticket / expiresIn=30 / wsUrl，且不含 token")
    void cookieTicketRequestWithMatchingCsrfIssuesTicket() throws Exception {
        Cookie access = loginAndGetAccessCookie();
        Cookie xsrf = csrfCookie();

        MvcResult result = mockMvc.perform(post(TICKET_PATH)
                .cookie(access)
                .cookie(xsrf)
                .header(XSRF_HEADER, xsrf.getValue()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", equalTo(true)))
            .andExpect(jsonPath("$.data.ticket", not(emptyOrNullString())))
            .andExpect(jsonPath("$.data.expiresIn", equalTo(30)))
            .andExpect(jsonPath("$.data.wsUrl", equalTo("/ws/v1/market")))
            .andExpect(jsonPath("$.data.accessToken").doesNotExist())
            .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
            .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(access.getValue());
        assertThat(body).doesNotContainIgnoringCase("refreshToken").doesNotContainIgnoringCase("accessToken");
    }

    /**
     * 以瀏覽器流程註冊並登入，回傳 HttpOnly access cookie。
     *
     * @return access cookie
     * @throws Exception 若註冊或登入失敗
     */
    private Cookie loginAndGetAccessCookie() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "ws-ticket-csrf-" + suffix + "@example.com";
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","username":"%s","password":"Password1"}
                    """.formatted(email, "wsticket" + suffix)))
            .andExpect(status().isOk());

        Cookie access = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"Password1"}
                    """.formatted(email)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getCookie(ACCESS_COOKIE);
        assertThat(access).as("瀏覽器登入必須設定 access cookie").isNotNull();
        return access;
    }

    /**
     * 呼叫 CSRF bootstrap 端點取得可讀的 XSRF cookie。
     *
     * @return XSRF cookie
     * @throws Exception 若請求失敗
     */
    private Cookie csrfCookie() throws Exception {
        Cookie xsrf = mockMvc.perform(get("/api/v1/csrf"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getCookie(XSRF_COOKIE);
        assertThat(xsrf).as("CSRF bootstrap 必須設定 XSRF-TOKEN cookie").isNotNull();
        return xsrf;
    }
}
