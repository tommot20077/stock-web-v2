package dowob.xyz.stockwebv2.marketdata.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.ObjectMapper;

/**
 * {@link WsMessageParser} 單元測試。
 *
 * <p>驗證範圍：
 * <ul>
 *   <li>有效的 SUBSCRIBE 訊息 → 回傳 {@link ClientMessage.Subscribe} 並包含 channels 清單</li>
 *   <li>有效的 UNSUBSCRIBE 訊息 → 回傳 {@link ClientMessage.Unsubscribe}</li>
 *   <li>有效的 PONG 訊息 → 回傳 {@link ClientMessage.Pong}</li>
 *   <li>未知的 type → 拋出 {@link InvalidMessageException}</li>
 *   <li>缺少 type 欄位 → 拋出 {@link InvalidMessageException}</li>
 *   <li>格式錯誤的 JSON → 拋出 {@link InvalidMessageException}</li>
 *   <li>SUBSCRIBE 的 channels 不是陣列 → 拋出 {@link InvalidMessageException}</li>
 *   <li>SUBSCRIBE 的 channels 包含無效名稱 → 拋出 {@link InvalidMessageException}</li>
 *   <li>有效的 kline channel 名稱（例如 kline.AAPL.1m）→ 正常接受</li>
 *   <li>有效的 tick channel 名稱（例如 tick.BTC）→ 正常接受</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0.0
 */
class WsMessageParserTest {

    private WsMessageParser parser;

    @BeforeEach
    void setup() {
        parser = new WsMessageParser(new ObjectMapper());
    }

    /** 有效 SUBSCRIBE → 回傳 Subscribe record，channels 清單正確 */
    @Test
    @DisplayName("有效 SUBSCRIBE 訊息 → 回傳 Subscribe record")
    void parse_validSubscribe_returnsSubscribeRecord() throws Exception {
        String json = """
                {"type":"SUBSCRIBE","channels":["tick.AAPL","tick.BTC"]}
                """;
        ClientMessage msg = parser.parse(json);

        assertThat(msg).isInstanceOf(ClientMessage.Subscribe.class);
        ClientMessage.Subscribe sub = (ClientMessage.Subscribe) msg;
        assertThat(sub.channels()).containsExactly("tick.AAPL", "tick.BTC");
    }

    /** 有效 UNSUBSCRIBE → 回傳 Unsubscribe record */
    @Test
    @DisplayName("有效 UNSUBSCRIBE 訊息 → 回傳 Unsubscribe record")
    void parse_validUnsubscribe_returnsUnsubscribeRecord() throws Exception {
        String json = """
                {"type":"UNSUBSCRIBE","channels":["tick.AAPL"]}
                """;
        ClientMessage msg = parser.parse(json);

        assertThat(msg).isInstanceOf(ClientMessage.Unsubscribe.class);
        ClientMessage.Unsubscribe unsub = (ClientMessage.Unsubscribe) msg;
        assertThat(unsub.channels()).containsExactly("tick.AAPL");
    }

    /** 有效 PONG → 回傳 Pong record */
    @Test
    @DisplayName("有效 PONG 訊息 → 回傳 Pong record")
    void parse_validPong_returnsPongRecord() throws Exception {
        String json = """
                {"type":"PONG"}
                """;
        ClientMessage msg = parser.parse(json);

        assertThat(msg).isInstanceOf(ClientMessage.Pong.class);
    }

    /** 未知 type → 拋出 InvalidMessageException */
    @Test
    @DisplayName("未知的 type → 拋出 InvalidMessageException")
    void parse_unknownType_throwsInvalidMessageException() {
        String json = """
                {"type":"UNKNOWN_OP","channels":[]}
                """;
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(InvalidMessageException.class);
    }

    /** 缺少 type 欄位 → 拋出 InvalidMessageException */
    @Test
    @DisplayName("缺少 type 欄位 → 拋出 InvalidMessageException")
    void parse_missingType_throwsInvalidMessageException() {
        String json = """
                {"channels":["tick.AAPL"]}
                """;
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(InvalidMessageException.class);
    }

    /** 格式錯誤的 JSON → 拋出 InvalidMessageException */
    @Test
    @DisplayName("格式錯誤的 JSON → 拋出 InvalidMessageException")
    void parse_malformedJson_throwsInvalidMessageException() {
        String json = "not-a-json{{{";
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(InvalidMessageException.class);
    }

    /** SUBSCRIBE 的 channels 欄位不是陣列 → 拋出 InvalidMessageException */
    @Test
    @DisplayName("SUBSCRIBE channels 不是陣列 → 拋出 InvalidMessageException")
    void parse_subscribeChannelsNotArray_throwsInvalidMessageException() {
        String json = """
                {"type":"SUBSCRIBE","channels":"tick.AAPL"}
                """;
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(InvalidMessageException.class);
    }

    /** SUBSCRIBE 含有無效 channel 名稱 → 拋出 InvalidMessageException */
    @Test
    @DisplayName("SUBSCRIBE channels 含有無效名稱 → 拋出 InvalidMessageException")
    void parse_subscribeInvalidChannelName_throwsInvalidMessageException() {
        String json = """
                {"type":"SUBSCRIBE","channels":["weird-channel"]}
                """;
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(InvalidMessageException.class);
    }

    /** 有效的 kline channel（kline.AAPL.1m） → 正常接受 */
    @Test
    @DisplayName("有效 kline channel 名稱（kline.AAPL.1m）→ 正常接受")
    void parse_validKlineChannel_accepted() throws Exception {
        String json = """
                {"type":"SUBSCRIBE","channels":["kline.AAPL.1m"]}
                """;
        ClientMessage msg = parser.parse(json);

        assertThat(msg).isInstanceOf(ClientMessage.Subscribe.class);
        ClientMessage.Subscribe sub = (ClientMessage.Subscribe) msg;
        assertThat(sub.channels()).containsExactly("kline.AAPL.1m");
    }

    /** 有效的 tick channel（tick.BTC） → 正常接受 */
    @Test
    @DisplayName("有效 tick channel 名稱（tick.BTC）→ 正常接受")
    void parse_validTickChannel_accepted() throws Exception {
        String json = """
                {"type":"SUBSCRIBE","channels":["tick.BTC"]}
                """;
        ClientMessage msg = parser.parse(json);

        assertThat(msg).isInstanceOf(ClientMessage.Subscribe.class);
        ClientMessage.Subscribe sub = (ClientMessage.Subscribe) msg;
        assertThat(sub.channels()).containsExactly("tick.BTC");
    }

    /** SUBSCRIBE 帶多種 interval 的 kline channels → 全部接受 */
    @Test
    @DisplayName("kline channel 支援多種 interval（1m/5m/15m/1h/1d）→ 全部接受")
    void parse_klineAllIntervals_allAccepted() throws Exception {
        String json = """
                {"type":"SUBSCRIBE","channels":["kline.AAPL.1m","kline.AAPL.5m","kline.AAPL.15m","kline.AAPL.1h","kline.AAPL.1d"]}
                """;
        ClientMessage msg = parser.parse(json);

        assertThat(msg).isInstanceOf(ClientMessage.Subscribe.class);
        ClientMessage.Subscribe sub = (ClientMessage.Subscribe) msg;
        assertThat(sub.channels()).hasSize(5);
    }

    /** kline channel 帶無效的 interval → 拋出 InvalidMessageException */
    @Test
    @DisplayName("kline channel 帶無效 interval → 拋出 InvalidMessageException")
    void parse_klineInvalidInterval_throwsInvalidMessageException() {
        String json = """
                {"type":"SUBSCRIBE","channels":["kline.AAPL.99m"]}
                """;
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(InvalidMessageException.class);
    }

    /** SUBSCRIBE 的 channels 為空陣列 → 拋出 InvalidMessageException */
    @Test
    @DisplayName("SUBSCRIBE channels 為空陣列 → 拋出 InvalidMessageException")
    void parse_subscribeEmptyChannels_throwsInvalidMessageException() {
        String json = """
                {"type":"SUBSCRIBE","channels":[]}
                """;
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(InvalidMessageException.class);
    }

    /** SUBSCRIBE 缺少 channels 欄位 → 拋出 InvalidMessageException */
    @Test
    @DisplayName("SUBSCRIBE 缺少 channels 欄位 → 拋出 InvalidMessageException")
    void parse_subscribeMissingChannels_throwsInvalidMessageException() {
        String json = """
                {"type":"SUBSCRIBE"}
                """;
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(InvalidMessageException.class);
    }
}
