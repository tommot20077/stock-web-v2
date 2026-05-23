package dowob.xyz.stockwebv2.marketdata.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 觸發 backfill job 的 request body。
 *
 * @author Yuan
 * @version 1.0.0
 */
public record BackfillTriggerRequest(
    @JsonProperty("symbol") @NotNull String symbol,
    @JsonProperty("from") @NotNull Instant from,
    @JsonProperty("to") @NotNull Instant to,
    @JsonProperty("interval") @NotNull String interval
) {}
