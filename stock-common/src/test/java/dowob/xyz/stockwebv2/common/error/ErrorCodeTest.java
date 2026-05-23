package dowob.xyz.stockwebv2.common.error;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ErrorCode} 枚舉的單元測試，涵蓋原有常數及新增的 Market 相關錯誤碼。
 *
 * @author Yuan
 * @version 1.0.0
 */
class ErrorCodeTest {

    private static final Set<String> ALL_NAMES = Arrays.stream(ErrorCode.values())
            .map(Enum::name)
            .collect(Collectors.toSet());

    // ── 11 new market error codes — existence ──────────────────────────────

    @Test
    void assetNotFound_exists() {
        assertThat(ALL_NAMES).contains("ASSET_NOT_FOUND");
    }

    @Test
    void klineIntervalInvalid_exists() {
        assertThat(ALL_NAMES).contains("KLINE_INTERVAL_INVALID");
    }

    @Test
    void latestBatchTooLarge_exists() {
        assertThat(ALL_NAMES).contains("LATEST_BATCH_TOO_LARGE");
    }

    @Test
    void backfillRangeTooLarge_exists() {
        assertThat(ALL_NAMES).contains("BACKFILL_RANGE_TOO_LARGE");
    }

    @Test
    void backfillAlreadyRunning_exists() {
        assertThat(ALL_NAMES).contains("BACKFILL_ALREADY_RUNNING");
    }

    @Test
    void backfillJobNotFound_exists() {
        assertThat(ALL_NAMES).contains("BACKFILL_JOB_NOT_FOUND");
    }

    @Test
    void wsTicketInvalid_exists() {
        assertThat(ALL_NAMES).contains("WS_TICKET_INVALID");
    }

    @Test
    void wsSubscriptionLimitExceeded_exists() {
        assertThat(ALL_NAMES).contains("WS_SUBSCRIPTION_LIMIT_EXCEEDED");
    }

    @Test
    void wsMsgFormatInvalid_exists() {
        assertThat(ALL_NAMES).contains("WS_MSG_FORMAT_INVALID");
    }

    @Test
    void wsRateLimitExceeded_exists() {
        assertThat(ALL_NAMES).contains("WS_RATE_LIMIT_EXCEEDED");
    }

    @Test
    void providerFetchFailed_exists() {
        assertThat(ALL_NAMES).contains("PROVIDER_FETCH_FAILED");
    }

    // ── HTTP status assertions for new constants ───────────────────────────

    @Test
    void assetNotFound_httpStatusIs404() {
        assertThat(ErrorCode.ASSET_NOT_FOUND.httpStatus()).isEqualTo(404);
    }

    @Test
    void klineIntervalInvalid_httpStatusIs400() {
        assertThat(ErrorCode.KLINE_INTERVAL_INVALID.httpStatus()).isEqualTo(400);
    }

    @Test
    void backfillRangeTooLarge_httpStatusIs400() {
        assertThat(ErrorCode.BACKFILL_RANGE_TOO_LARGE.httpStatus()).isEqualTo(400);
    }

    @Test
    void backfillAlreadyRunning_httpStatusIs409() {
        assertThat(ErrorCode.BACKFILL_ALREADY_RUNNING.httpStatus()).isEqualTo(409);
    }

    @Test
    void backfillJobNotFound_httpStatusIs404() {
        assertThat(ErrorCode.BACKFILL_JOB_NOT_FOUND.httpStatus()).isEqualTo(404);
    }

    @Test
    void wsTicketInvalid_httpStatusIs401() {
        assertThat(ErrorCode.WS_TICKET_INVALID.httpStatus()).isEqualTo(401);
    }

    @Test
    void wsRateLimitExceeded_httpStatusIs429() {
        assertThat(ErrorCode.WS_RATE_LIMIT_EXCEEDED.httpStatus()).isEqualTo(429);
    }

    @Test
    void providerFetchFailed_httpStatusIs502() {
        assertThat(ErrorCode.PROVIDER_FETCH_FAILED.httpStatus()).isEqualTo(502);
    }

    // ── defaultMessage not empty for all new constants ─────────────────────

    @Test
    void allNewConstants_haveNonEmptyDefaultMessage() {
        ErrorCode[] newCodes = {
                ErrorCode.ASSET_NOT_FOUND,
                ErrorCode.KLINE_INTERVAL_INVALID,
                ErrorCode.LATEST_BATCH_TOO_LARGE,
                ErrorCode.BACKFILL_RANGE_TOO_LARGE,
                ErrorCode.BACKFILL_ALREADY_RUNNING,
                ErrorCode.BACKFILL_JOB_NOT_FOUND,
                ErrorCode.WS_TICKET_INVALID,
                ErrorCode.WS_SUBSCRIPTION_LIMIT_EXCEEDED,
                ErrorCode.WS_MSG_FORMAT_INVALID,
                ErrorCode.WS_RATE_LIMIT_EXCEEDED,
                ErrorCode.PROVIDER_FETCH_FAILED
        };

        for (ErrorCode code : newCodes) {
            assertThat(code.defaultMessage())
                    .as("defaultMessage for %s should not be empty", code.name())
                    .isNotNull()
                    .isNotEmpty();
        }
    }

    // ── regression: original 9 constants still exist ──────────────────────

    @Test
    void existingConstants_notRemoved() {
        assertThat(ALL_NAMES).containsAll(Set.of(
                "VALIDATION_FAILED",
                "RESOURCE_NOT_FOUND",
                "AUTH_INVALID_CREDENTIALS",
                "AUTH_TOKEN_EXPIRED",
                "AUTH_REFRESH_TOKEN_INVALID",
                "AUTH_FORBIDDEN",
                "AUTH_REDIS_UNAVAILABLE",
                "DUPLICATE_RESOURCE",
                "INTERNAL_ERROR"
        ));
    }

    @Test
    void existingConstants_httpStatusesUnchanged() {
        assertThat(ErrorCode.VALIDATION_FAILED.httpStatus()).isEqualTo(400);
        assertThat(ErrorCode.RESOURCE_NOT_FOUND.httpStatus()).isEqualTo(404);
        assertThat(ErrorCode.AUTH_INVALID_CREDENTIALS.httpStatus()).isEqualTo(401);
        assertThat(ErrorCode.AUTH_TOKEN_EXPIRED.httpStatus()).isEqualTo(401);
        assertThat(ErrorCode.AUTH_REFRESH_TOKEN_INVALID.httpStatus()).isEqualTo(401);
        assertThat(ErrorCode.AUTH_FORBIDDEN.httpStatus()).isEqualTo(403);
        assertThat(ErrorCode.AUTH_REDIS_UNAVAILABLE.httpStatus()).isEqualTo(503);
        assertThat(ErrorCode.DUPLICATE_RESOURCE.httpStatus()).isEqualTo(409);
        assertThat(ErrorCode.INTERNAL_ERROR.httpStatus()).isEqualTo(500);
    }
}
