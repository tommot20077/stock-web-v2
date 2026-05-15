package dowob.xyz.stockwebv2.common.api;

import java.time.OffsetDateTime;

public record ApiMeta(String traceId, OffsetDateTime timestamp) {
}
