package dowob.xyz.stockwebv2.marketdata.ws;

import java.util.List;
import java.util.Objects;

/**
 * WebSocket 客戶端傳入的訊息 sealed interface。
 *
 * <p>支援三種訊息類型：
 * <ul>
 *   <li>{@link Subscribe} — 訂閱指定 channels</li>
 *   <li>{@link Unsubscribe} — 取消訂閱指定 channels</li>
 *   <li>{@link Pong} — 心跳回應</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0.0
 */
public sealed interface ClientMessage permits ClientMessage.Subscribe, ClientMessage.Unsubscribe, ClientMessage.Pong {

    /**
     * 訂閱指定 channels 的訊息。
     *
     * @param channels 欲訂閱的 channel 名稱清單，不可為 null
     */
    record Subscribe(List<String> channels) implements ClientMessage {
        /**
         * 緊湊建構子：驗證 channels 不為 null 並建立不可變副本。
         *
         * @throws NullPointerException 若 channels 為 null
         */
        public Subscribe {
            Objects.requireNonNull(channels, "channels");
            channels = List.copyOf(channels);
        }
    }

    /**
     * 取消訂閱指定 channels 的訊息。
     *
     * @param channels 欲取消訂閱的 channel 名稱清單，不可為 null
     */
    record Unsubscribe(List<String> channels) implements ClientMessage {
        /**
         * 緊湊建構子：驗證 channels 不為 null 並建立不可變副本。
         *
         * @throws NullPointerException 若 channels 為 null
         */
        public Unsubscribe {
            Objects.requireNonNull(channels, "channels");
            channels = List.copyOf(channels);
        }
    }

    /**
     * 心跳 PONG 回應訊息。
     */
    record Pong() implements ClientMessage {}
}
