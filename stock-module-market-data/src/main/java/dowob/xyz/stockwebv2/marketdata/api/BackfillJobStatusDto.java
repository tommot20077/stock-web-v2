package dowob.xyz.stockwebv2.marketdata.api;

import java.time.Instant;

/**
 * Backfill job 執行狀態回應 DTO。
 *
 * <p>{@code failureMessage} 在 Job 無錯誤時為 {@code null}。
 *
 * @author Yuan
 * @version 1.0.0
 */
public record BackfillJobStatusDto(
    Long jobExecutionId,
    String status,
    Instant startTime,
    Instant endTime,
    Long readCount,
    Long writeCount,
    String failureMessage
) {}
