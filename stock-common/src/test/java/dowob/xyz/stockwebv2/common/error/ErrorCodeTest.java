package dowob.xyz.stockwebv2.common.error;

import org.junit.jupiter.api.DisplayName;
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

    // ── Phase 4：冪等鍵重用的 409 error code（DP-14 已鎖定字面）─────────────

    @Test
    @DisplayName("TRADE_IDEMPOTENCY_KEY_REUSED 的 HTTP 狀態碼是 409")
    void tradeIdempotencyKeyReused_httpStatusIs409() {
        assertThat(ErrorCode.TRADE_IDEMPOTENCY_KEY_REUSED.httpStatus()).isEqualTo(409);
    }

    @Test
    @DisplayName("TRADE_IDEMPOTENCY_KEY_REUSED 的預設訊息不為空且不含任何請求值佔位字串")
    void tradeIdempotencyKeyReused_defaultMessageDoesNotEchoRequestValues() {
        assertThat(ErrorCode.TRADE_IDEMPOTENCY_KEY_REUSED.defaultMessage())
                .isNotNull()
                .isNotBlank()
                .doesNotContain("%s")
                .doesNotContain("{");
    }

    @Test
    @DisplayName("TRADE_IDEMPOTENCY_KEY_REUSED 與既有三個 409 code 是相異常數且訊息兩兩不同")
    void tradeIdempotencyKeyReused_isDistinctFromOtherConflictCodes() {
        ErrorCode[] conflictCodes = {
                ErrorCode.DUPLICATE_RESOURCE,
                ErrorCode.TRADE_INSUFFICIENT_HOLDING,
                ErrorCode.TRADE_CONFLICT,
                ErrorCode.TRADE_IDEMPOTENCY_KEY_REUSED
        };

        assertThat(conflictCodes).doesNotHaveDuplicates();
        assertThat(Arrays.stream(conflictCodes).map(ErrorCode::defaultMessage).collect(Collectors.toSet()))
                .as("四個 409 code 的 defaultMessage 必須兩兩不同，才能在前端分派出不同文案")
                .hasSize(4);
        assertThat(Arrays.stream(conflictCodes).map(ErrorCode::httpStatus))
                .allMatch(status -> status == 409);
    }

    // ── Phase 4.1：現金分錄冪等鍵重用的 409 error code（04.1-04 凍結契約字面）─────

    @Test
    @DisplayName("CASH_IDEMPOTENCY_KEY_REUSED 的 HTTP 狀態碼是 409")
    void cashIdempotencyKeyReused_httpStatusIs409() {
        assertThat(ErrorCode.CASH_IDEMPOTENCY_KEY_REUSED.httpStatus()).isEqualTo(409);
    }

    @Test
    @DisplayName("CASH_IDEMPOTENCY_KEY_REUSED 的預設訊息不為空且不含任何請求值佔位字串")
    void cashIdempotencyKeyReused_defaultMessageDoesNotEchoRequestValues() {
        assertThat(ErrorCode.CASH_IDEMPOTENCY_KEY_REUSED.defaultMessage())
                .isNotNull()
                .isNotBlank()
                .doesNotContain("%s")
                .doesNotContain("{");
    }

    @Test
    @DisplayName("CASH_IDEMPOTENCY_KEY_REUSED 與 TRADE_IDEMPOTENCY_KEY_REUSED 是相異常數且訊息不同（前端依 code 分派文案）")
    void cashIdempotencyKeyReused_isDistinctFromTradeIdempotencyKeyReused() {
        assertThat(ErrorCode.CASH_IDEMPOTENCY_KEY_REUSED).isNotEqualTo(ErrorCode.TRADE_IDEMPOTENCY_KEY_REUSED);
        assertThat(ErrorCode.CASH_IDEMPOTENCY_KEY_REUSED.defaultMessage())
                .isNotEqualTo(ErrorCode.TRADE_IDEMPOTENCY_KEY_REUSED.defaultMessage());
        assertThat(ErrorCode.TRADE_IDEMPOTENCY_KEY_REUSED.defaultMessage())
                .as("既有 trade 字面不得被動到")
                .isEqualTo("Idempotency key was reused with a different trade payload");
    }

    // ── Phase 4.1：現金分錄沖銷的錯誤碼（04.1-06 凍結契約字面，前端 i18n 依 code 分派）─────

    @Test
    @DisplayName("沖銷錯誤碼的 HTTP 狀態碼：NOT_FOUND 404、ALREADY_REVERSED 409、NOT_REVERSIBLE 409")
    void cashEntryReversalCodes_httpStatuses() {
        assertThat(ErrorCode.CASH_ENTRY_NOT_FOUND.httpStatus()).isEqualTo(404);
        assertThat(ErrorCode.CASH_ENTRY_ALREADY_REVERSED.httpStatus()).isEqualTo(409);
        assertThat(ErrorCode.CASH_ENTRY_NOT_REVERSIBLE.httpStatus()).isEqualTo(409);
    }

    @Test
    @DisplayName("沖銷錯誤碼的預設訊息為凍結字面，且為靜態字串（不含佔位符、不回射輸入）")
    void cashEntryReversalCodes_defaultMessagesAreFrozenAndStatic() {
        assertThat(ErrorCode.CASH_ENTRY_NOT_FOUND.defaultMessage()).isEqualTo("Cash entry not found");
        assertThat(ErrorCode.CASH_ENTRY_ALREADY_REVERSED.defaultMessage())
                .isEqualTo("Cash entry has already been reversed");
        assertThat(ErrorCode.CASH_ENTRY_NOT_REVERSIBLE.defaultMessage())
                .isEqualTo("A reversal entry cannot be reversed");
        for (ErrorCode code : new ErrorCode[] {ErrorCode.CASH_ENTRY_NOT_FOUND,
                ErrorCode.CASH_ENTRY_ALREADY_REVERSED, ErrorCode.CASH_ENTRY_NOT_REVERSIBLE}) {
            assertThat(code.defaultMessage()).doesNotContain("%s").doesNotContain("{");
        }
    }

    @Test
    @DisplayName("三個沖銷錯誤碼兩兩相異，訊息也兩兩不同")
    void cashEntryReversalCodes_areMutuallyDistinct() {
        assertThat(ErrorCode.CASH_ENTRY_NOT_FOUND.defaultMessage())
                .isNotEqualTo(ErrorCode.CASH_ENTRY_ALREADY_REVERSED.defaultMessage())
                .isNotEqualTo(ErrorCode.CASH_ENTRY_NOT_REVERSIBLE.defaultMessage());
        assertThat(ErrorCode.CASH_ENTRY_ALREADY_REVERSED.defaultMessage())
                .isNotEqualTo(ErrorCode.CASH_ENTRY_NOT_REVERSIBLE.defaultMessage());
        assertThat(ErrorCode.CASH_ENTRY_ALREADY_REVERSED).isNotEqualTo(ErrorCode.CASH_ENTRY_NOT_REVERSIBLE);
    }

    // ── Phase 04.2：股利 / 股數調整 / 觀察清單錯誤碼（04.2-02 凍結契約字面，前端 i18n 依 code 分派）─────

    /** Phase 04.2 新增的十個錯誤碼，依契約順序排列。 */
    private static final ErrorCode[] PHASE_04_2_CODES = {
        ErrorCode.DIVIDEND_IDEMPOTENCY_KEY_REUSED,
        ErrorCode.DIVIDEND_NOT_FOUND,
        ErrorCode.DIVIDEND_ALREADY_REVERSED,
        ErrorCode.DIVIDEND_NOT_REVERSIBLE,
        ErrorCode.SHARE_ADJUSTMENT_IDEMPOTENCY_KEY_REUSED,
        ErrorCode.SHARE_ADJUSTMENT_NOT_FOUND,
        ErrorCode.SHARE_ADJUSTMENT_ALREADY_REVERSED,
        ErrorCode.SHARE_ADJUSTMENT_NOT_REVERSIBLE,
        ErrorCode.WATCHLIST_NOT_FOUND,
        ErrorCode.WATCHLIST_ORDER_CONFLICT
    };

    @Test
    @DisplayName("股利錯誤碼的 HTTP 狀態碼與預設訊息為凍結字面")
    void dividendCodes_httpStatusesAndMessagesAreFrozen() {
        assertThat(ErrorCode.DIVIDEND_IDEMPOTENCY_KEY_REUSED.httpStatus()).isEqualTo(409);
        assertThat(ErrorCode.DIVIDEND_IDEMPOTENCY_KEY_REUSED.defaultMessage())
                .isEqualTo("Idempotency key was reused with a different dividend payload");
        assertThat(ErrorCode.DIVIDEND_NOT_FOUND.httpStatus()).isEqualTo(404);
        assertThat(ErrorCode.DIVIDEND_NOT_FOUND.defaultMessage()).isEqualTo("Dividend not found");
        assertThat(ErrorCode.DIVIDEND_ALREADY_REVERSED.httpStatus()).isEqualTo(409);
        assertThat(ErrorCode.DIVIDEND_ALREADY_REVERSED.defaultMessage())
                .isEqualTo("Dividend has already been reversed");
        assertThat(ErrorCode.DIVIDEND_NOT_REVERSIBLE.httpStatus()).isEqualTo(409);
        assertThat(ErrorCode.DIVIDEND_NOT_REVERSIBLE.defaultMessage())
                .isEqualTo("A reversal dividend cannot be reversed");
    }

    @Test
    @DisplayName("股數調整錯誤碼的 HTTP 狀態碼與預設訊息為凍結字面")
    void shareAdjustmentCodes_httpStatusesAndMessagesAreFrozen() {
        assertThat(ErrorCode.SHARE_ADJUSTMENT_IDEMPOTENCY_KEY_REUSED.httpStatus()).isEqualTo(409);
        assertThat(ErrorCode.SHARE_ADJUSTMENT_IDEMPOTENCY_KEY_REUSED.defaultMessage())
                .isEqualTo("Idempotency key was reused with a different share adjustment payload");
        assertThat(ErrorCode.SHARE_ADJUSTMENT_NOT_FOUND.httpStatus()).isEqualTo(404);
        assertThat(ErrorCode.SHARE_ADJUSTMENT_NOT_FOUND.defaultMessage()).isEqualTo("Share adjustment not found");
        assertThat(ErrorCode.SHARE_ADJUSTMENT_ALREADY_REVERSED.httpStatus()).isEqualTo(409);
        assertThat(ErrorCode.SHARE_ADJUSTMENT_ALREADY_REVERSED.defaultMessage())
                .isEqualTo("Share adjustment has already been reversed");
        assertThat(ErrorCode.SHARE_ADJUSTMENT_NOT_REVERSIBLE.httpStatus()).isEqualTo(409);
        assertThat(ErrorCode.SHARE_ADJUSTMENT_NOT_REVERSIBLE.defaultMessage())
                .isEqualTo("Share adjustment cannot be reversed (not the latest, or itself a reversal)");
    }

    @Test
    @DisplayName("觀察清單錯誤碼的 HTTP 狀態碼與預設訊息為凍結字面")
    void watchlistCodes_httpStatusesAndMessagesAreFrozen() {
        assertThat(ErrorCode.WATCHLIST_NOT_FOUND.httpStatus()).isEqualTo(404);
        assertThat(ErrorCode.WATCHLIST_NOT_FOUND.defaultMessage()).isEqualTo("Watchlist not found");
        assertThat(ErrorCode.WATCHLIST_ORDER_CONFLICT.httpStatus()).isEqualTo(409);
        assertThat(ErrorCode.WATCHLIST_ORDER_CONFLICT.defaultMessage())
                .isEqualTo("Watchlist items changed; reload and retry");
    }

    @Test
    @DisplayName("Phase 04.2 十個錯誤碼的預設訊息皆為靜態字串（不含佔位符、不回射輸入）且兩兩不同")
    void phase042Codes_defaultMessagesAreStaticAndDistinct() {
        for (ErrorCode code : PHASE_04_2_CODES) {
            assertThat(code.defaultMessage())
                    .as(code.name())
                    .isNotBlank()
                    .doesNotContain("%")
                    .doesNotContain("{");
        }
        assertThat(Arrays.stream(PHASE_04_2_CODES).map(ErrorCode::defaultMessage).collect(Collectors.toSet()))
                .as("十個 code 的 defaultMessage 必須兩兩不同")
                .hasSize(PHASE_04_2_CODES.length);
        assertThat(Arrays.stream(ErrorCode.values()).map(ErrorCode::defaultMessage)
                        .filter(message -> Arrays.stream(PHASE_04_2_CODES)
                                .map(ErrorCode::defaultMessage)
                                .anyMatch(message::equals))
                        .count())
                .as("新訊息不得與既有錯誤碼的訊息重複")
                .isEqualTo(PHASE_04_2_CODES.length);
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
