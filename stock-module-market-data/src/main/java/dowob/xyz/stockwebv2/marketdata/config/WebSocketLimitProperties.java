package dowob.xyz.stockwebv2.marketdata.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * WebSocket 連線數上限的可設定參數（security.md §18）。預設值即憲法規定值。
 *
 * @param maxConnectionsGlobal     全域最大連線數（預設 1000，超過拒絕 handshake 回 503）
 * @param maxConnectionsPerIp      每 IP 最大連線數（預設 5，超過拒絕 handshake 回 429）
 * @param maxConnectionsPerAccount 每帳號最大連線數（預設 2，超過 FIFO 驅逐最舊連線並以 4002 關閉）
 * @author Yuan
 * @version 1.0
 */
@ConfigurationProperties(prefix = "stock.security.websocket")
public record WebSocketLimitProperties(
    Integer maxConnectionsGlobal,
    Integer maxConnectionsPerIp,
    Integer maxConnectionsPerAccount
) {

    public WebSocketLimitProperties {
        maxConnectionsGlobal = maxConnectionsGlobal == null ? 1000 : maxConnectionsGlobal;
        maxConnectionsPerIp = maxConnectionsPerIp == null ? 5 : maxConnectionsPerIp;
        maxConnectionsPerAccount = maxConnectionsPerAccount == null ? 2 : maxConnectionsPerAccount;
    }
}
