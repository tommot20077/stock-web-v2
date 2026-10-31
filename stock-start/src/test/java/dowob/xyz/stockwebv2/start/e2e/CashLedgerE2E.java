package dowob.xyz.stockwebv2.start.e2e;

import dowob.xyz.stockwebv2.start.e2e.support.AbstractPortfolioE2ETest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static dowob.xyz.stockwebv2.start.e2e.support.AuthE2EHelper.bearerToken;
import static dowob.xyz.stockwebv2.start.e2e.support.StockE2EAssertions.apiError;
import static dowob.xyz.stockwebv2.start.e2e.support.StockE2EAssertions.apiSuccess;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasLength;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;

/**
 * 現金分錄帳本（CASH-01 / CASH-03）的 REST 層 E2E：入出金、冪等、分頁列表、一鍵沖銷與輸入邊界。
 *
 * <p>契約見 {@code ai-docs/trading-portfolio-contract.md}：{@code POST /api/v1/portfolio/cash-entries}
 * 必帶 {@code Idempotency-Key}；同 key 同 payload 回既有分錄、同 key 改 payload 回 409
 * {@code CASH_IDEMPOTENCY_KEY_REUSED}；沖銷是帶 {@code reversalOfId} 的反向分錄，且帳本 append-only。
 * 全部走真實 Spring 應用與真實 Postgres（V15 trigger / 唯一約束）。</p>
 *
 * <p>其中「同 key 重送一般入金」與「同 key 重送沖銷」兩條會把已存在的列從資料庫讀回——這是 04.1-26 修掉的
 * {@code JdbcCashLedgerRepository#mapEntry} {@code wasNull()} 缺陷（讀回後 {@code reversalOfId} 變 0 而非 null，
 * 重送被誤判為 409）的 REST 層回歸防線。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("Cash Ledger E2E")
class CashLedgerE2E extends AbstractPortfolioE2ETest {

    private static final String DEPOSIT_TWD_50000 = """
        {"type":"DEPOSIT","currency":"TWD","amount":50000,"occurredAt":"2026-10-01T09:00:00+08:00"}
        """;

    @Nested
    @DisplayName("入金與冪等")
    class DepositAndIdempotency {

        @Test
        @DisplayName("入金一筆：200 與完整 DTO（UUID id、數字金額、未沖銷、不含冪等鍵），列表恰一筆")
        void depositIsRecordedAndListedWithoutIdempotencyKey() throws Exception {
            var session = auth.register("cash-e2e-deposit@example.com", "cashe2edeposit", PASSWORD);

            String id = dataOf(postCashEntry(session.accessToken(), UUID.randomUUID().toString(), DEPOSIT_TWD_50000)
                .andExpect(status().isOk())
                .andExpect(apiSuccess())
                .andExpect(jsonPath("$.data.id", hasLength(36)))
                .andExpect(jsonPath("$.data.type", equalTo("DEPOSIT")))
                .andExpect(jsonPath("$.data.currency", equalTo("TWD")))
                .andExpect(jsonPath("$.data.amount", equalTo(50000.00000000)))
                .andExpect(jsonPath("$.data.reversed", equalTo(false)))
                .andExpect(jsonPath("$.data.reversalOfId").value(nullValue()))
                .andExpect(jsonPath("$.data.idempotencyKey").doesNotExist())).get("id").asText();

            getCashEntries(session.accessToken(), null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements", equalTo(1)))
                .andExpect(jsonPath("$.data.items[0].id", equalTo(id)))
                .andExpect(jsonPath("$.data.items[0].idempotencyKey").doesNotExist());
        }

        @Test
        @DisplayName("出金不受餘額限制：新使用者直接出金 USD 1000 照常記帳（D-01 / D-08）")
        void withdrawalLargerThanAnyBalanceIsRecorded() throws Exception {
            var session = auth.register("cash-e2e-withdraw@example.com", "cashe2ewithdraw", PASSWORD);

            postCashEntry(session.accessToken(), UUID.randomUUID().toString(),
                entryBody("WITHDRAWAL", "USD", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type", equalTo("WITHDRAWAL")))
                .andExpect(jsonPath("$.data.amount", equalTo(1000.00000000)));
        }

        @Test
        @DisplayName("同 key 同 payload 重送：回同一筆（同 id），帳本只有 1 列（04.1-26 讀回既有列的回歸防線）")
        void sameKeySamePayloadReplayReturnsTheExistingEntry() throws Exception {
            var session = auth.register("cash-e2e-replay@example.com", "cashe2ereplay", PASSWORD);
            String key = "replay-" + UUID.randomUUID();

            String firstId = dataOf(postCashEntry(session.accessToken(), key, DEPOSIT_TWD_50000)
                .andExpect(status().isOk())).get("id").asText();
            String secondId = dataOf(postCashEntry(session.accessToken(), key, DEPOSIT_TWD_50000)
                .andExpect(status().isOk())
                .andExpect(apiSuccess())
                .andExpect(jsonPath("$.data.reversalOfId").value(nullValue()))).get("id").asText();

            assertThat(secondId).isEqualTo(firstId);
            getCashEntries(session.accessToken(), null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements", equalTo(1)));
        }

        @Test
        @DisplayName("同 key 改 amount：409 CASH_IDEMPOTENCY_KEY_REUSED，不回射 key，帳本仍只有 1 列")
        void sameKeyDifferentPayloadIsConflict() throws Exception {
            var session = auth.register("cash-e2e-reuse@example.com", "cashe2ereuse", PASSWORD);
            String key = "E2E-REUSED-KEY-" + UUID.randomUUID();

            postCashEntry(session.accessToken(), key, DEPOSIT_TWD_50000).andExpect(status().isOk());

            String body = postCashEntry(session.accessToken(), key, """
                {"type":"DEPOSIT","currency":"TWD","amount":50001,"occurredAt":"2026-10-01T09:00:00+08:00"}
                """)
                .andExpect(status().isConflict())
                .andExpect(apiError("CASH_IDEMPOTENCY_KEY_REUSED"))
                .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain("E2E-REUSED-KEY-");
            getCashEntries(session.accessToken(), null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements", equalTo(1)));
        }

        @Test
        @DisplayName("兩位使用者用同一把 key：各自建立分錄，互不命中（key 以使用者隔離）")
        void sameKeyAcrossUsersCreatesOneEntryPerUser() throws Exception {
            var alice = auth.register("cash-e2e-keya@example.com", "cashe2ekeya", PASSWORD);
            var bob = auth.register("cash-e2e-keyb@example.com", "cashe2ekeyb", PASSWORD);
            String key = "shared-" + UUID.randomUUID();

            String aliceId = dataOf(postCashEntry(alice.accessToken(), key, DEPOSIT_TWD_50000)
                .andExpect(status().isOk())).get("id").asText();
            String bobId = dataOf(postCashEntry(bob.accessToken(), key, DEPOSIT_TWD_50000)
                .andExpect(status().isOk())).get("id").asText();

            assertThat(bobId).isNotEqualTo(aliceId);
        }
    }

    @Nested
    @DisplayName("分頁列表")
    class Listing {

        @Test
        @DisplayName("依 occurredAt 新到舊（刻意與插入順序不一致），totalElements / totalPages 與分頁切片正確")
        void listIsNewestFirstWithPagingTotals() throws Exception {
            var session = auth.register("cash-e2e-list@example.com", "cashe2elist", PASSWORD);
            String token = session.accessToken();

            // 插入順序：中間、最新、最舊；列表必須依 occurredAt 而非插入順序排列。
            String middleId = dataOf(postCashEntry(token, UUID.randomUUID().toString(), """
                {"type":"DEPOSIT","currency":"TWD","amount":200,"occurredAt":"2026-10-02T09:00:00+08:00"}
                """).andExpect(status().isOk())).get("id").asText();
            String newestId = dataOf(postCashEntry(token, UUID.randomUUID().toString(), """
                {"type":"DEPOSIT","currency":"TWD","amount":300,"occurredAt":"2026-10-03T09:00:00+08:00"}
                """).andExpect(status().isOk())).get("id").asText();
            String oldestId = dataOf(postCashEntry(token, UUID.randomUUID().toString(), """
                {"type":"WITHDRAWAL","currency":"USD","amount":100,"occurredAt":"2026-10-01T09:00:00+08:00"}
                """).andExpect(status().isOk())).get("id").asText();

            getCashEntries(token, null, null)
                .andExpect(status().isOk())
                .andExpect(apiSuccess())
                .andExpect(jsonPath("$.data.totalElements", equalTo(3)))
                .andExpect(jsonPath("$.data.totalPages", equalTo(1)))
                .andExpect(jsonPath("$.data.items.length()", equalTo(3)))
                .andExpect(jsonPath("$.data.items[0].id", equalTo(newestId)))
                .andExpect(jsonPath("$.data.items[1].id", equalTo(middleId)))
                .andExpect(jsonPath("$.data.items[2].id", equalTo(oldestId)));

            getCashEntries(token, "0", "2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page", equalTo(0)))
                .andExpect(jsonPath("$.data.size", equalTo(2)))
                .andExpect(jsonPath("$.data.totalElements", equalTo(3)))
                .andExpect(jsonPath("$.data.totalPages", equalTo(2)))
                .andExpect(jsonPath("$.data.items.length()", equalTo(2)))
                .andExpect(jsonPath("$.data.items[0].id", equalTo(newestId)));
            getCashEntries(token, "1", "2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()", equalTo(1)))
                .andExpect(jsonPath("$.data.items[0].id", equalTo(oldestId)));
        }

        @Test
        @DisplayName("page / size 超出範圍會被夾限：size 0 → 1、size 1000 → 100、page -5 → 0、page 超大 → 10000 且 items 為空")
        void pageAndSizeAreClamped() throws Exception {
            var session = auth.register("cash-e2e-clamp@example.com", "cashe2eclamp", PASSWORD);
            String token = session.accessToken();
            recordCashEntry(token, "DEPOSIT", "TWD", "100");
            recordCashEntry(token, "DEPOSIT", "TWD", "200");

            getCashEntries(token, null, "0")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size", equalTo(1)))
                .andExpect(jsonPath("$.data.items.length()", equalTo(1)))
                .andExpect(jsonPath("$.data.totalElements", equalTo(2)));
            getCashEntries(token, null, "1000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size", equalTo(100)))
                .andExpect(jsonPath("$.data.items.length()", equalTo(2)));
            getCashEntries(token, "-5", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page", equalTo(0)))
                .andExpect(jsonPath("$.data.items.length()", equalTo(2)));
            getCashEntries(token, "99999999", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page", equalTo(10000)))
                .andExpect(jsonPath("$.data.items.length()", equalTo(0)))
                .andExpect(jsonPath("$.data.totalElements", equalTo(2)));
        }

        @Test
        @DisplayName("page / size 不是數字：400 VALIDATION_FAILED（不是 500）")
        void nonNumericPagingIsRejected() throws Exception {
            var session = auth.register("cash-e2e-badpage@example.com", "cashe2ebadpage", PASSWORD);

            getCashEntries(session.accessToken(), "abc", null)
                .andExpect(status().isBadRequest())
                .andExpect(apiError("VALIDATION_FAILED"));
            getCashEntries(session.accessToken(), null, "abc")
                .andExpect(status().isBadRequest())
                .andExpect(apiError("VALIDATION_FAILED"));
        }

        @Test
        @DisplayName("只含自己的分錄：另一位使用者的 id 與筆數都不會出現；新使用者列表為空；未認證回 401")
        void listNeverLeaksOtherUsersEntries() throws Exception {
            var alice = auth.register("cash-e2e-lista@example.com", "cashe2elista", PASSWORD);
            var bob = auth.register("cash-e2e-listb@example.com", "cashe2elistb", PASSWORD);
            var carol = auth.register("cash-e2e-listc@example.com", "cashe2elistc", PASSWORD);
            String aliceId = recordCashEntry(alice.accessToken(), "DEPOSIT", "TWD", "111").get("id").asText();
            String bobId = recordCashEntry(bob.accessToken(), "DEPOSIT", "TWD", "222").get("id").asText();

            String aliceBody = getCashEntries(alice.accessToken(), null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements", equalTo(1)))
                .andExpect(jsonPath("$.data.items[0].id", equalTo(aliceId)))
                .andReturn().getResponse().getContentAsString();
            assertThat(aliceBody).doesNotContain(bobId);

            String bobBody = getCashEntries(bob.accessToken(), null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements", equalTo(1)))
                .andReturn().getResponse().getContentAsString();
            assertThat(bobBody).doesNotContain(aliceId);

            getCashEntries(carol.accessToken(), null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()", equalTo(0)))
                .andExpect(jsonPath("$.data.totalElements", equalTo(0)))
                .andExpect(jsonPath("$.data.totalPages", equalTo(0)));

            mockMvc.perform(get("/api/v1/portfolio/cash-entries"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success", equalTo(false)));
        }
    }

    @Nested
    @DisplayName("一鍵沖銷")
    class Reversal {

        @Test
        @DisplayName("沖銷是方向相反、幣別金額相同的新分錄：原分錄在列表 reversed = true，沖銷分錄指回原分錄")
        void reversalMirrorsTheOriginalAndMarksItReversed() throws Exception {
            var session = auth.register("cash-e2e-rev@example.com", "cashe2erev", PASSWORD);
            String token = session.accessToken();
            String originalId = recordCashEntry(token, "DEPOSIT", "USD", "1000").get("id").asText();

            JsonNode reversal = dataOf(postCashEntry(token, UUID.randomUUID().toString(),
                reversalBody("WITHDRAWAL", "USD", "1000", originalId))
                .andExpect(status().isOk())
                .andExpect(apiSuccess())
                .andExpect(jsonPath("$.data.type", equalTo("WITHDRAWAL")))
                .andExpect(jsonPath("$.data.currency", equalTo("USD")))
                .andExpect(jsonPath("$.data.amount", equalTo(1000.00000000)))
                .andExpect(jsonPath("$.data.reversalOfId", equalTo(originalId)))
                .andExpect(jsonPath("$.data.reversalOfOccurredAt").exists())
                .andExpect(jsonPath("$.data.reversed", equalTo(false))));
            String reversalId = reversal.get("id").asText();
            assertThat(reversalId).isNotEqualTo(originalId);

            JsonNode items = dataOf(getCashEntries(token, null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements", equalTo(2)))).get("items");
            assertThat(entryById(items, originalId).get("reversed").asBoolean()).isTrue();
            assertThat(entryById(items, reversalId).get("reversed").asBoolean()).isFalse();
            assertThat(entryById(items, reversalId).get("reversalOfId").asText()).isEqualTo(originalId);
        }

        @Test
        @DisplayName("同一把 key 重送已成功的沖銷：200 同一 id（冪等優先於「已沖銷」），帳本仍 2 列（04.1-26 回歸防線）")
        void replayOfASuccessfulReversalReturnsTheSameEntry() throws Exception {
            var session = auth.register("cash-e2e-revreplay@example.com", "cashe2erevreplay", PASSWORD);
            String token = session.accessToken();
            String originalId = recordCashEntry(token, "DEPOSIT", "USD", "1000").get("id").asText();
            String key = "rev-replay-" + UUID.randomUUID();
            String body = reversalBody("WITHDRAWAL", "USD", "1000", originalId);

            String firstId = dataOf(postCashEntry(token, key, body).andExpect(status().isOk())).get("id").asText();
            String secondId = dataOf(postCashEntry(token, key, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reversalOfId", equalTo(originalId)))).get("id").asText();

            assertThat(secondId).isEqualTo(firstId);
            getCashEntries(token, null, null)
                .andExpect(jsonPath("$.data.totalElements", equalTo(2)));
        }

        @Test
        @DisplayName("同一筆以另一把 key 再沖銷 → 409 CASH_ENTRY_ALREADY_REVERSED；沖銷「沖銷分錄」→ 409 CASH_ENTRY_NOT_REVERSIBLE；帳本不增列")
        void secondReversalAndReversalOfAReversalAreRejected() throws Exception {
            var session = auth.register("cash-e2e-revtwice@example.com", "cashe2erevtwice", PASSWORD);
            String token = session.accessToken();
            String originalId = recordCashEntry(token, "DEPOSIT", "USD", "1000").get("id").asText();
            String reversalId = dataOf(postCashEntry(token, UUID.randomUUID().toString(),
                reversalBody("WITHDRAWAL", "USD", "1000", originalId))
                .andExpect(status().isOk())).get("id").asText();

            postCashEntry(token, UUID.randomUUID().toString(), reversalBody("WITHDRAWAL", "USD", "1000", originalId))
                .andExpect(status().isConflict())
                .andExpect(apiError("CASH_ENTRY_ALREADY_REVERSED"));

            postCashEntry(token, UUID.randomUUID().toString(), reversalBody("DEPOSIT", "USD", "1000", reversalId))
                .andExpect(status().isConflict())
                .andExpect(apiError("CASH_ENTRY_NOT_REVERSIBLE"));

            getCashEntries(token, null, null)
                .andExpect(jsonPath("$.data.totalElements", equalTo(2)));
        }

        @Test
        @DisplayName("沖銷別人的或不存在的分錄 → 404 CASH_ENTRY_NOT_FOUND，body 不含該 UUID（不洩漏存在性），對方帳本不受影響")
        void reversingSomeoneElsesOrUnknownEntryIsNotFound() throws Exception {
            var alice = auth.register("cash-e2e-owner@example.com", "cashe2eowner", PASSWORD);
            var bob = auth.register("cash-e2e-intruder@example.com", "cashe2eintruder", PASSWORD);
            String aliceEntryId = recordCashEntry(alice.accessToken(), "DEPOSIT", "USD", "1000").get("id").asText();
            String unknownId = UUID.randomUUID().toString();

            String foreignBody = postCashEntry(bob.accessToken(), UUID.randomUUID().toString(),
                reversalBody("WITHDRAWAL", "USD", "1000", aliceEntryId))
                .andExpect(status().isNotFound())
                .andExpect(apiError("CASH_ENTRY_NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
            assertThat(foreignBody).doesNotContain(aliceEntryId);

            postCashEntry(bob.accessToken(), UUID.randomUUID().toString(),
                reversalBody("WITHDRAWAL", "USD", "1000", unknownId))
                .andExpect(status().isNotFound())
                .andExpect(apiError("CASH_ENTRY_NOT_FOUND"));

            getCashEntries(bob.accessToken(), null, null)
                .andExpect(jsonPath("$.data.totalElements", equalTo(0)));
            JsonNode aliceItems = dataOf(getCashEntries(alice.accessToken(), null, null)
                .andExpect(jsonPath("$.data.totalElements", equalTo(1)))).get("items");
            assertThat(entryById(aliceItems, aliceEntryId).get("reversed").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("金額、方向或幣別與原分錄不符 → 400 VALIDATION_FAILED，fields.reversalOfId；reversalOfId 非 UUID 亦同；帳本不增列")
        void mismatchedReversalNamesTheReversalOfIdField() throws Exception {
            var session = auth.register("cash-e2e-mismatch@example.com", "cashe2emismatch", PASSWORD);
            String token = session.accessToken();
            String originalId = recordCashEntry(token, "DEPOSIT", "USD", "1000").get("id").asText();

            postCashEntry(token, UUID.randomUUID().toString(), reversalBody("WITHDRAWAL", "USD", "999", originalId))
                .andExpect(status().isBadRequest())
                .andExpect(apiError("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fields.reversalOfId", equalTo("must mirror the original entry")));
            postCashEntry(token, UUID.randomUUID().toString(), reversalBody("DEPOSIT", "USD", "1000", originalId))
                .andExpect(status().isBadRequest())
                .andExpect(apiError("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fields.reversalOfId", equalTo("must mirror the original entry")));
            postCashEntry(token, UUID.randomUUID().toString(), reversalBody("WITHDRAWAL", "TWD", "1000", originalId))
                .andExpect(status().isBadRequest())
                .andExpect(apiError("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fields.reversalOfId", equalTo("must mirror the original entry")));
            postCashEntry(token, UUID.randomUUID().toString(), reversalBody("WITHDRAWAL", "USD", "1000", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(apiError("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fields.reversalOfId", equalTo("invalid format")));

            getCashEntries(token, null, null)
                .andExpect(jsonPath("$.data.totalElements", equalTo(1)));
        }
    }

    @Nested
    @DisplayName("輸入邊界")
    class InputBoundary {

        @Test
        @DisplayName("格式錯誤的 JSON body：400 VALIDATION_FAILED（D-27，不是 500），帳本無新增")
        void malformedJsonBodyIsBadRequest() throws Exception {
            var session = auth.register("cash-e2e-json@example.com", "cashe2ejson", PASSWORD);

            postCashEntry(session.accessToken(), UUID.randomUUID().toString(), "{\"type\":")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success", equalTo(false)))
                .andExpect(apiError("VALIDATION_FAILED"));

            getCashEntries(session.accessToken(), null, null)
                .andExpect(jsonPath("$.data.totalElements", equalTo(0)));
        }

        @Test
        @DisplayName("缺少 Idempotency-Key header：400 VALIDATION_FAILED，fields 指名該 header")
        void missingIdempotencyKeyIsRejected() throws Exception {
            var session = auth.register("cash-e2e-nokey@example.com", "cashe2enokey", PASSWORD);

            mockMvc.perform(post("/api/v1/portfolio/cash-entries")
                    .contentType(MediaType.APPLICATION_JSON)
                    .with(bearerToken(session.accessToken()))
                    .content(entryBody("DEPOSIT", "TWD", "100")))
                .andExpect(status().isBadRequest())
                .andExpect(apiError("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fields['Idempotency-Key']").exists());
        }

        @Test
        @DisplayName("不支援的幣別、類型與非正金額：400 VALIDATION_FAILED，fields 各自指名 currency / type / amount")
        void unsupportedCurrencyTypeAndAmountNameTheirFields() throws Exception {
            var session = auth.register("cash-e2e-fields@example.com", "cashe2efields", PASSWORD);
            String token = session.accessToken();

            postCashEntry(token, UUID.randomUUID().toString(), entryBody("DEPOSIT", "GBP", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(apiError("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fields.currency").exists());
            postCashEntry(token, UUID.randomUUID().toString(), entryBody("TRANSFER", "TWD", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(apiError("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fields.type").exists());
            postCashEntry(token, UUID.randomUUID().toString(), entryBody("DEPOSIT", "TWD", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(apiError("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fields.amount").exists());

            getCashEntries(token, null, null)
                .andExpect(jsonPath("$.data.totalElements", equalTo(0)));
        }

        @Test
        @DisplayName("未認證的 POST 回 401")
        void unauthenticatedPostIsUnauthorized() throws Exception {
            mockMvc.perform(post("/api/v1/portfolio/cash-entries")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .content(entryBody("DEPOSIT", "TWD", "100")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success", equalTo(false)));
        }
    }
}
