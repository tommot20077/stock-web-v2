package dowob.xyz.stockwebv2.marketdata.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code POST /api/v1/market/ws/ticket} 的回應 DTO。
 *
 * <p>包含一次性 ticket 字串、TTL（秒）及相對 WebSocket 端點 URL，
 * 方便前端直接以此資訊建立 WebSocket 連線，無需額外設定。
 *
 * @param ticket    一次性 ticket 字串（URL-safe Base64 無 padding，約 43 字元）
 * @param expiresIn ticket TTL，單位秒
 * @param wsUrl     後續 WebSocket 連線相對端點 URL（前端自行補全 host）
 * @author Yuan
 * @version 1.0.0
 */
public record WsTicketResponse(
    @JsonProperty("ticket") String ticket,
    @JsonProperty("expiresIn") long expiresIn,
    @JsonProperty("wsUrl") String wsUrl
) {}
