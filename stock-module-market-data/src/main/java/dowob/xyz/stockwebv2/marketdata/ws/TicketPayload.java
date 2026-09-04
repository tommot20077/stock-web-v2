package dowob.xyz.stockwebv2.marketdata.ws;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * WebSocket ticket 儲存於 Redis 的 payload。
 *
 * <p>{@code consume()} 取出後由 controller 驗證 {@code tokenVersion} 是否與
 * 當前 user 的 JWT 一致 — 若 user 已 logout（tokenVersion 遞增），即使 ticket
 * 還在 TTL 內也應拒絕。
 *
 * @param userId       簽發 ticket 的 user id
 * @param issuedAt     簽發時間
 * @param tokenVersion 簽發當下的 JWT token version 快照
 * @author Yuan
 * @version 1.0.0
 */
public record TicketPayload(
        @JsonProperty("userId") Long userId,
        @JsonProperty("issuedAt") Instant issuedAt,
        @JsonProperty("tokenVersion") Integer tokenVersion
) {
    /**
     * Jackson 反序列化用 canonical constructor。
     * {@code @JsonCreator} 搭配各欄位 {@code @JsonProperty} 告知 Jackson
     * 以具名參數方式建構 record。
     */
    @JsonCreator
    public TicketPayload {
        // record canonical constructor — Jackson uses this with @JsonProperty annotations
    }
}
