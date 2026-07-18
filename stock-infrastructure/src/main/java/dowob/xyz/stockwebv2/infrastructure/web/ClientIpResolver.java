package dowob.xyz.stockwebv2.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 由 HTTP 請求解析來源 IP。
 *
 * <p>集中原先散落於各 controller 的相同解析邏輯：優先採用 {@code X-Forwarded-For} 的首段，
 * 否則回退至 {@link HttpServletRequest#getRemoteAddr()}。集中於單一入口，
 * 未來若要導入信任 proxy 白名單（避免客戶端偽造來源 IP）只需修改此處。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public final class ClientIpResolver {

    /** {@code X-Forwarded-For} 標頭名稱。 */
    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private ClientIpResolver() {
    }

    /**
     * 解析請求來源 IP。
     *
     * @param request HTTP 請求，不可 null
     * @return 來源 IP：{@code X-Forwarded-For} 首段（若存在且非空白），否則為 remote address
     */
    public static String resolve(HttpServletRequest request) {
        String forwarded = request.getHeader(X_FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
