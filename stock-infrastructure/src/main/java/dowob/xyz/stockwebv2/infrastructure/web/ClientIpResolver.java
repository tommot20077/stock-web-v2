package dowob.xyz.stockwebv2.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.ServerHttpRequest;

import java.net.InetSocketAddress;

/**
 * 統一判定請求來源 IP 的工具（security.md §15/§18 的限流與連線上限之計數維度）。
 *
 * <p><b>信任政策:一律以 TCP 對端位址（{@code getRemoteAddr} / {@code getRemoteAddress}）為準,
 * 不採信客戶端可任意偽造的 {@code X-Forwarded-For} 等轉送標頭。</b>否則攻擊者只要每次請求
 * 更換轉送標頭值,即可讓 per-IP 限流 / per-IP WS 連線上限的計數桶永不累積而完全繞過。</p>
 *
 * <p><b>部署注意:</b>此政策假設後端可能被直接存取。若日後確定後端只經可信反向代理 / K3s
 * ingress 對外,ingress 後所有請求的對端位址會是 ingress 本身,per-IP 限流將退化為全域共用;
 * 屆時應改以 Spring {@code server.forward-headers-strategy=NATIVE} 或 {@code RemoteIpValve}
 * 搭配 internalProxies 白名單,由框架層驗證後再採信 {@code X-Forwarded-For},而非在此手動解析。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public final class ClientIpResolver {

    /**
     * 無法判定對端位址時的回退值。
     */
    private static final String UNKNOWN = "unknown";

    private ClientIpResolver() {
    }

    /**
     * 取得 servlet 請求的來源 IP（TCP 對端位址）。
     *
     * @param request servlet 請求,不可為 null
     * @return 來源 IP 字串
     */
    public static String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        return remoteAddr == null || remoteAddr.isBlank() ? UNKNOWN : remoteAddr;
    }

    /**
     * 取得 WebSocket handshake 請求的來源 IP（TCP 對端位址）。
     *
     * @param request handshake 的 HTTP upgrade 請求,不可為 null
     * @return 來源 IP 字串;無法判定時回傳 {@code "unknown"}
     */
    public static String resolve(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote != null && remote.getAddress() != null) {
            return remote.getAddress().getHostAddress();
        }
        return UNKNOWN;
    }
}
