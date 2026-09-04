package dowob.xyz.stockwebv2.marketdata.ws;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * WebSocket 客戶端訊息解析器：將 JSON 文字解析為 {@link ClientMessage} 物件。
 *
 * <p>負責：
 * <ul>
 *   <li>解析 JSON 格式</li>
 *   <li>驗證 {@code type} 欄位（SUBSCRIBE / UNSUBSCRIBE / PONG）</li>
 *   <li>驗證 SUBSCRIBE / UNSUBSCRIBE 的 {@code channels} 欄位（字串陣列且名稱符合規範）</li>
 * </ul>
 *
 * <p>Channel 名稱規範：
 * <ul>
 *   <li>{@code tick.{symbol}} — 例如 {@code tick.AAPL}</li>
 *   <li>{@code kline.{symbol}.{interval}} — 例如 {@code kline.AAPL.1m}，
 *       interval 限制為 1m / 5m / 15m / 1h / 1d</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0.0
 */
@Component
public class WsMessageParser {

    /** tick.{symbol} 模式（大小寫不敏感）。 */
    private static final Pattern TICK_PATTERN =
            Pattern.compile("^tick\\.[A-Z0-9./-]+$", Pattern.CASE_INSENSITIVE);

    /** kline.{symbol}.{interval} 模式（大小寫不敏感，interval 限制為已知值）。 */
    private static final Pattern KLINE_PATTERN =
            Pattern.compile("^kline\\.[A-Z0-9./-]+\\.(1m|5m|15m|1h|1d)$", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper objectMapper;

    /**
     * 建構子注入 ObjectMapper。
     *
     * @param objectMapper Jackson ObjectMapper，不可為 null
     */
    public WsMessageParser(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * 將 JSON 文字訊息解析為 {@link ClientMessage}。
     *
     * @param json 客戶端傳入的 JSON 文字，不可為 null
     * @return 解析成功的 {@link ClientMessage}
     * @throws InvalidMessageException 若 JSON 格式錯誤、缺少欄位、type 未知或 channel 名稱無效
     * @throws NullPointerException    若 json 為 null
     */
    public ClientMessage parse(String json) throws InvalidMessageException {
        Objects.requireNonNull(json, "json must not be null");

        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (JacksonException e) {
            throw new InvalidMessageException("JSON 格式錯誤：" + e.getMessage(), e);
        }

        JsonNode typeNode = root.get("type");
        if (typeNode == null || typeNode.isNull()) {
            throw new InvalidMessageException("缺少必要欄位：type");
        }

        String type = typeNode.asText();
        return switch (type) {
            case "SUBSCRIBE" -> new ClientMessage.Subscribe(parseChannels(root, "SUBSCRIBE"));
            case "UNSUBSCRIBE" -> new ClientMessage.Unsubscribe(parseChannels(root, "UNSUBSCRIBE"));
            case "PONG" -> new ClientMessage.Pong();
            default -> throw new InvalidMessageException("未知的訊息類型：" + type);
        };
    }

    /**
     * 解析並驗證 channels 欄位。
     *
     * @param root    JSON 根節點
     * @param msgType 訊息類型（用於錯誤訊息）
     * @return 驗證通過的 channel 名稱清單
     * @throws InvalidMessageException 若 channels 欄位缺少、不是陣列、為空或包含無效名稱
     */
    private List<String> parseChannels(JsonNode root, String msgType) throws InvalidMessageException {
        JsonNode channelsNode = root.get("channels");
        if (channelsNode == null || channelsNode.isNull()) {
            throw new InvalidMessageException(msgType + " 缺少必要欄位：channels");
        }
        if (!channelsNode.isArray()) {
            throw new InvalidMessageException(msgType + " 的 channels 欄位必須為陣列");
        }
        if (channelsNode.isEmpty()) {
            throw new InvalidMessageException(msgType + " 的 channels 不可為空陣列");
        }

        List<String> channels = new ArrayList<>();
        for (JsonNode item : channelsNode) {
            if (!item.isTextual()) {
                throw new InvalidMessageException("channels 的元素必須為字串");
            }
            String ch = item.asText();
            if (!isValidChannel(ch)) {
                throw new InvalidMessageException("無效的 channel 名稱：" + ch);
            }
            channels.add(ch);
        }
        return channels;
    }

    /**
     * 驗證 channel 名稱是否符合 tick 或 kline 模式。
     *
     * @param ch channel 名稱
     * @return {@code true} 表示名稱有效
     */
    static boolean isValidChannel(String ch) {
        if (StringUtils.isBlank(ch)) {
            return false;
        }
        return TICK_PATTERN.matcher(ch).matches() || KLINE_PATTERN.matcher(ch).matches();
    }
}
