package dowob.xyz.stockwebv2.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class TraceIdFilter extends OncePerRequestFilter {
    public static final String TRACE_ID = "traceId";
    public static final String TRACE_HEADER = "X-Trace-Id";
    public static final String MISSING_TRACE_ID = "missing-trace-id";
    private static final int MAX_TRACE_ID_LENGTH = 128;
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9._:-]+");

    /**
     * 取得目前請求的 trace id;不在請求範圍內（或 MDC 已清除）時回退為 {@link #MISSING_TRACE_ID}。
     *
     * <p>供各層組裝 {@code ApiMeta} 時共用,避免回退值散落在各 controller 中重複硬編。</p>
     *
     * @return trace id,永不為 null
     */
    public static String currentTraceId() {
        return Objects.requireNonNullElse(MDC.get(TRACE_ID), MISSING_TRACE_ID);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        String traceId = resolveTraceId(request.getHeader(TRACE_HEADER));
        MDC.put(TRACE_ID, traceId);
        response.setHeader(TRACE_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID);
        }
    }

    private String resolveTraceId(String header) {
        if (header == null) {
            return UUID.randomUUID().toString();
        }
        String traceId = header.trim();
        if (traceId.isEmpty() || traceId.length() > MAX_TRACE_ID_LENGTH || !SAFE_TRACE_ID.matcher(traceId).matches()) {
            return UUID.randomUUID().toString();
        }
        return traceId;
    }
}
