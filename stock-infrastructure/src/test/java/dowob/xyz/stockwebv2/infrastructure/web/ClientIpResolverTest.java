package dowob.xyz.stockwebv2.infrastructure.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ClientIpResolver} 來源 IP 解析的單元測試。
 *
 * <p>集中原先散落於各 controller 的 {@code X-Forwarded-For} 解析邏輯，
 * 以便未來導入信任 proxy 白名單時只需改一處。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("來源 IP 解析")
class ClientIpResolverTest {

    @Test
    @DisplayName("有 X-Forwarded-For 時採用首段並去除空白")
    void usesFirstForwardedSegment() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", " 203.0.113.7 , 10.0.0.1 ");
        request.setRemoteAddr("10.0.0.1");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("無 X-Forwarded-For 時回退至 remoteAddr")
    void fallsBackToRemoteAddrWhenHeaderAbsent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.23");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("198.51.100.23");
    }

    @Test
    @DisplayName("X-Forwarded-For 為空白時回退至 remoteAddr")
    void fallsBackToRemoteAddrWhenHeaderBlank() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "   ");
        request.setRemoteAddr("198.51.100.23");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("198.51.100.23");
    }
}
