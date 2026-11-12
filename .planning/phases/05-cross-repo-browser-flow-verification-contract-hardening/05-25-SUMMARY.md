---
phase: 05-cross-repo-browser-flow-verification-contract-hardening
plan: 25
subsystem: ci-gate
tags: [ci, pr, coverage-audit, wave-gate, D-07, D-24, hard-cutover]

requires:
  - phase: 05-15
    provides: "後端 Wave 2 鏈完成(BigDecimal 一律字串、ArchUnit 守門、IT / E2E 字串斷言、字串契約)"
  - phase: 05-24
    provides: "前端 Wave 2 鏈完成(big.js Decimal domain、真後端 E2E 字串化、decimalBoundary 守門)"
provides:
  - "Wave 2 PR 組:tommot20077/stock-web-v2#56、tommot20077/stock-web-v2-front-end#23(內文互相連結、逐字載明合併須知)"
  - "05-COVERAGE-AUDIT.md:G6 / F3 補上測試名、commit 與 CI run;新增 §Wave 2(兩邊 Browser E2E 對方 ref、D-24 安全網表)與 Wave 2 合併後 develop 表"
  - "兩 repo develop 在 Wave 2 合併後 CI 綠(前端 43141443593、後端 43141473501)"
affects: [phase-05-verification]

actuals:
  tasks: 3
  commits: 2
commits: 2
plan_head_before: "backend 281b2b4 / frontend 5dc7511"
plan_head_after: "backend develop 87d5bdf / frontend develop 06ab920"

key-files:
  created:
    - .planning/phases/05-cross-repo-browser-flow-verification-contract-hardening/05-25-SUMMARY.md
  modified:
    - .planning/phases/05-cross-repo-browser-flow-verification-contract-hardening/05-COVERAGE-AUDIT.md
    - stock-start/src/test/java/dowob/xyz/stockwebv2/start/ShareAdjustmentApiIT.java

key-decisions:
  - "首輪 PR CI 的 IT 紅由本 plan 直接修(`e2e7fa0`),不另開 gap-closure:根因單一(05-15 清查漏掉 helper 內 double 比對)、修正一行、與既有寫法一致"
  - "FE Browser E2E 的後端證據取自 `281b2b4`:到 `e2e7fa0` 只改測試檔,伺服器程式碼相同;`e2e7fa0` 另有 BE Browser E2E 證據"
  - "Yuan 回覆「合併」後由 Claude 依宣告順序以 merge commit 代為合併(同 Wave 1 慣例)"
---

# Phase 05 Plan 25: Wave 2 合併閘門 Summary

Wave 2(金額字串化硬切)前後端 PR 組經 CI 全綠、證據入表,依宣告順序(前端先、後端緊接)合併,兩 repo develop CI 綠 —— D-07 硬切同步完成。

## Task 1 — 本機全套閘門、開 PR

- 本機閘門(2026-11-11,開 PR 前):後端 `spotless:check` OK、`./mvnw -B test --fail-at-end` 8 模組 1012 tests 0 failures / 0 errors;前端 lint 0 errors(38 個既有 warning)、`npm test` 與 `VITE_DATA_MODE=api npm test` 各 67 files / 1516 passed、build ×2 OK、`layout.spec.ts` 101 passed、`playwright --list` 149 tests / 12 files。
- 兩 repo 確認 develop 無新 commit、無殘留 open PR 後,先開前端 PR #23、再開後端 PR #56,兩邊內文互相連結並逐字包含 `<merge_order>` 五點。GitHub 操作經 MCP GitHub 工具(本環境無 `gh` CLI)。

## Task 2 — CI 證據

- 後端首輪(head `281b2b4`,push run 42970726018):Integration Tests 288 中 5 個失敗,全在 `ShareAdjustmentApiIT.assertHolding:463` —— `equalTo(expectedQuantity * 1.0)` 對上字串 `"400.00000000"`。`e2e7fa0` 改用 `decimalString(String.valueOf(expectedQuantity))`(本機 test-compile、spotless、`DecimalStringMatchersTest` 通過)。
- 後端 PR run 42971740727(head `e2e7fa0`):Lint / Unit / Integration(288 / 0)/ E2E / Browser E2E 全綠;Browser E2E 解析前端 ref = `claude/epic-curie-lj8alo`,149 passed。稽核表 commit `4cc820f` 之後 PR run 42973830020、push run 42973824212 亦全綠。
- 前端 PR run 42971316716(head `5dc7511`):`frontend` 與 `Browser E2E (real backend)` 全綠;解析後端 ref = `claude/epic-curie-lj8alo`,149 passed。
- D-24:兩邊 Browser E2E 的 J0、18 個 S5、cash-journey C1~C3、holding-event-journey J1~J3、backtest D1 皆 passed。
- 新案例確認:`TradingApiIT#stringAmountReplayReturnsExistingTrade`、`#stringPrecisionTradeRoundTrips`、`CashEntryApiIT#amountRoundTripsAsExactString`、`#stringAmountReplayIsSameEntry` 皆 ✅;05-15 擔心的三個逐字 scale 斷言首輪即綠。

## Task 3 — 合併與 develop

- 合併前以 REST 再確認:兩 PR head 與取證時相同(前端 `5dc7511`、後端 `4cc820f`)、`mergeable_state = clean`、最新 CI 綠、無 review thread。
- Yuan 回覆「合併」後,依序以 merge commit 合併:前端 #23 `06ab920`(merged_at 02:26:35Z)→ 後端 #56 `87d5bdf`(約 02:26:55Z)。
- develop push run:前端 43141443593、後端 43141473501 皆 success。預期的前端過渡紅燈未出現(前端 browser-e2e 於 02:29 才 checkout 後端 develop,後端已合併)。兩 repo `claude/epic-curie-lj8alo` 隨後 fast-forward 到 develop。

## Deviations from Plan

1. **[Rule 3 — 阻擋] 首輪 IT 紅**:見 Task 2,修正 commit `e2e7fa0`(scope `fix(05-25)`)。
2. **合併須知第 1 點偏離(執行層)**:本次 execute-phase 因環境 stop hook 要求,每個 plan 完成後即推送兩 repo 分支,未等兩條鏈都完成;期間後端分支 push run 為紅(新後端 × 未完成前端)。影響僅限分支上的 CI 紀錄,合併前提以同 head 的 PR run 為準,已寫進兩 PR 內文與稽核表。
3. **Codex review bot** 兩 PR 皆因用量上限未完成審查,無 finding 可處理。

## 待 Yuan 決定(不阻擋)

- 05-19:OrderTicket「預估總額」維持「數量 × 價格」(不含手續費),與既有測試、後端 `total` 定義、mock cashAfter 一致;plan 原寫含手續費。
- 05-20:股利對話框未加「淨額 = 金額 − 稅」提示與「稅 > 金額」錯誤,因後端 `CreateDividendRequest.amount` 定義為實領總額(扣稅後);如需稅前總額提示需另定 UI。

## Self-Check: PASSED

- 兩 PR `merged = true`,前端 merged_at 早於後端。
- 兩 repo develop 最新 push run conclusion = success(43141443593、43141473501),run id 已寫入 05-COVERAGE-AUDIT.md。
- 稽核表含 `ApiDecimalStringRulesTest` 與 `## Wave 2`(plan verify)。
