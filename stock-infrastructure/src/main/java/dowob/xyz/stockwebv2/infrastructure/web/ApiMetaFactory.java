package dowob.xyz.stockwebv2.infrastructure.web;

import dowob.xyz.stockwebv2.common.api.ApiMeta;

import java.time.OffsetDateTime;

/**
 * 統一組裝 API 回應的 {@link ApiMeta}（trace id + 時間戳）。
 *
 * <p>此前每個 controller 各自持有一份內容相同的 private {@code meta()}，
 * trace id 的回退值也散落硬編在各處；一旦回退語意或時間戳來源需要調整，
 * 就得同步修改十餘個檔案，且極易漏改造成回應 meta 不一致。
 *
 * <p>置於 {@code stock-infrastructure} 而非 {@code stock-common} 的原因：
 * trace id 由 {@link TraceIdFilter} 寫入 MDC，而 {@code stock-common} 位於依賴上游，
 * 不能反向依賴 infrastructure。
 *
 * @author Yuan
 * @version 1.0
 */
public final class ApiMetaFactory {

    private ApiMetaFactory() {
    }

    /**
     * 以目前請求的 trace id 與當下時間組出 {@link ApiMeta}。
     *
     * <p>不在請求範圍內時 trace id 回退為 {@link TraceIdFilter#MISSING_TRACE_ID}。
     *
     * @return 本次回應的 meta，永不為 null
     */
    public static ApiMeta current() {
        return new ApiMeta(TraceIdFilter.currentTraceId(), OffsetDateTime.now());
    }
}
