package dowob.xyz.stockwebv2.common.model;

import java.time.Duration;
import java.util.Objects;

/**
 * K 線時間間隔枚舉，定義支援的 K 線周期及其對應的 wire 字串與持續時間。
 *
 * <p>wire 字串（{@link #code()}）用於 REST 查詢參數、WebSocket 頻道名稱及 Kafka 訊息 payload。
 *
 * @author Yuan
 * @version 1.0.0
 */
public enum KlineInterval {

    /** 1 分鐘 K 線。 */
    ONE_MINUTE("1m", Duration.ofMinutes(1)),

    /** 5 分鐘 K 線。 */
    FIVE_MINUTES("5m", Duration.ofMinutes(5)),

    /** 15 分鐘 K 線。 */
    FIFTEEN_MINUTES("15m", Duration.ofMinutes(15)),

    /** 1 小時 K 線。 */
    ONE_HOUR("1h", Duration.ofHours(1)),

    /** 1 日 K 線。 */
    ONE_DAY("1d", Duration.ofDays(1));

    private final String code;
    private final Duration duration;

    KlineInterval(String code, Duration duration) {
        this.code = code;
        this.duration = duration;
    }

    /**
     * 回傳此間隔的 wire 字串，例如 {@code "1m"}、{@code "1h"}。
     *
     * @return wire 字串，不會為 {@code null}
     */
    public String code() {
        return code;
    }

    /**
     * 回傳此間隔對應的 {@link Duration}。
     *
     * @return 持續時間，不會為 {@code null}
     */
    public Duration duration() {
        return duration;
    }

    /**
     * 依 wire 字串（大小寫不敏感）查找對應的 {@link KlineInterval}。
     *
     * @param code wire 字串，例如 {@code "1m"} 或 {@code "1M"}；不得為 {@code null}
     * @return 對應的 {@link KlineInterval} 常數
     * @throws NullPointerException     若 {@code code} 為 {@code null}
     * @throws IllegalArgumentException 若 {@code code} 不符合任何已知間隔
     */
    public static KlineInterval fromCode(String code) {
        Objects.requireNonNull(code, "code must not be null");
        String normalized = code.toLowerCase();
        for (KlineInterval interval : values()) {
            if (interval.code.equals(normalized)) {
                return interval;
            }
        }
        throw new IllegalArgumentException(
                "Unknown KlineInterval code: '" + code + "'. Valid codes are: 1m, 5m, 15m, 1h, 1d");
    }
}
