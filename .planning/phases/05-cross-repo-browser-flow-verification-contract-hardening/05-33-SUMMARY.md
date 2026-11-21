---
phase: 05-cross-repo-browser-flow-verification-contract-hardening
plan: 33
subsystem: ci-gate
tags: [ci, pr, coverage-audit, gap-closure, G-05-7, G-05-9, G-05-10]

requires:
  - phase: 05-26
    provides: "回測 initialCapital 上限 1e12 / 6 位小數(BE-CR-01)"
  - phase: 05-27
    provides: "D-09 BigDecimal 守門擴大、.env.example access TTL 成對(BE-WR-01、G-05-10)"
  - phase: 05-28
    provides: "前端回測初始資金驗證、漲跌色 token 化(FE-WR-03、FE-WR-04)"
  - phase: 05-29
    provides: "apiRequestMapped → INVALID_API_RESPONSE、OrderTicket(FE-WR-01 一)"
  - phase: 05-30
    provides: "基準幣與寫入對話框 UNKNOWN_ERROR(FE-WR-01 二)"
  - phase: 05-31
    provides: "觀察清單 / App 啟動 UNKNOWN_ERROR、網路錯誤碼源碼守門(FE-WR-01 三)"
  - phase: 05-32
    provides: "SC5 哨兵補強、J0 禁 stub 守門(FE-WR-02、G-05-9)"
provides:
  - "gap closure PR 組:tommot20077/stock-web-v2#57、tommot20077/stock-web-v2-front-end#24(內文互相連結、逐字載明合併須知),皆已合併"
  - "05-COVERAGE-AUDIT.md §Gap closure:8 個 finding / gap 對應測試、plan、commit、CI run;本機不可證項目(ValidationBoundaryE2E D3b、api-failure-states overview / cash、J0)皆有 CI 證據"
  - "兩 repo develop 在 gap closure 合併後 CI 綠(前端 44885150492、後端 44886864147 attempt 2)"
  - "中文 i18n 文案逗號全面統一為全形(Yuan 指示,前端 a23f0fc、628aac5)"
affects: [phase-05-verification]

actuals:
  tasks: 3
  commits: 7
plan_head_before: "backend 2735141 / frontend 1454916"
plan_head_after: "backend develop d4843ef / frontend develop 0783da9"

key-files:
  created:
    - .planning/phases/05-cross-repo-browser-flow-verification-contract-hardening/05-33-SUMMARY.md
  modified:
    - .planning/phases/05-cross-repo-browser-flow-verification-contract-hardening/05-COVERAGE-AUDIT.md
    - .planning/phases/05-cross-repo-browser-flow-verification-contract-hardening/05-26-SUMMARY.md
    - .planning/STATE.md
    - (前端)vue-app/src/i18n.ts、vue-app/src/i18n.test.ts、vue-app/src/components/OrderTicket.test.ts、vue-app/e2e/tests/watchlist-journey.spec.ts、vue-app/e2e/tests/layout.spec.ts

key-decisions:
  - "Yuan 確認:initialCapital 尾零超過 6 位(如 \"100.00000000\")在 HTTP 層維持 400(05-26 偏差 1 定案),記入 05-26-SUMMARY"
  - "Yuan 指示中文文案逗號全面改全形:zh 字典 75 條,數字千分位維持半形;分號 / 冒號 / 問號 / 括號未動(只要求逗號)"
  - "後端 PR 的 CI 重跑權限不足(rerun / workflow_dispatch 皆 403):以記錄進度的 STATE.md commit(0cff973)觸發新 run,使後端 Browser E2E 取到前端最終 head 628aac5"
  - "後端 Integration 兩度在測試前因 Maven Central HTTP 429 失敗(jacoco-maven-plugin 解析),由 Yuan 重跑後綠;根因為 setup-java cache: maven 只存約 14 MB(由 Lint 先寫入,缺 jacoco 等 plugin)。CI 快取修正依 Yuan 決定於合併後另開 quick task"
  - "Yuan 指示由 Claude 代為合併;依宣告順序前端 PR 先(merge commit 0783da9)、後端 PR 後(d4843ef),合併前以 REST 確認 head SHA、mergeable_state clean、CI 全綠"
  - "orchestrator 直接執行本 plan(未派 executor):PR / CI / 合併只能用 orchestrator 才有的 GitHub MCP 工具;Task 2 證據收集交由 subagent"

requirements-completed: [VER-01, VER-02, VER-03]

coverage:
  - id: D1
    description: "gap closure PR 組開立(前端先、內文互相連結、含合併須知五點)並合併"
    requirement: VER-01
    verification:
      - kind: other
        ref: "tommot20077/stock-web-v2-front-end#24 merged 0783da9;tommot20077/stock-web-v2#57 merged d4843ef"
        status: pass
  - id: D2
    description: "CI 證據入 05-COVERAGE-AUDIT.md §Gap closure(含本機不可證的後端 E2E D3b 與兩邊真後端 Browser E2E)"
    requirement: VER-03
    verification:
      - kind: e2e
        ref: "BE PR run 44882921395(E2E 136785417880、Browser E2E 136786109105);FE PR run 44882835531(Browser E2E 136782039764)"
        status: pass
  - id: D3
    description: "兩 repo develop 合併後 CI 綠"
    requirement: VER-02
    verification:
      - kind: other
        ref: "FE develop push run 44885150492(success);BE develop push run 44886864147 attempt 2(success,attempt 1 因 Maven Central 429 失敗)"
        status: pass
---

# Phase 05 Plan 33: Gap closure 合併閘門 Summary

G-05-7 / G-05-9 / G-05-10 的 gap closure(05-26 ~ 05-32)經本機全套閘門、前後端 PR CI 全綠、證據入表,依宣告順序(前端先、後端後)合併,兩 repo develop CI 綠。

## 經過

- **Task 1(PR 組)**:本機閘門全綠(後端 spotless + 1050 unit;前端 lint 0 error、mock / api 兩模式 1599/1599、build、layout 101/101、`--list` 149)後開 PR,前端 #24 先、後端 #57 後,內文互相連結並逐字含 `<merge_order>`。
- **期間的 Yuan 決定**:initialCapital 尾零維持 HTTP 400;中文文案逗號先改交易組兩條(a23f0fc),再全面統一(628aac5,75 條)。每次推送前重跑前端閘門。
- **Task 2(CI 證據)**:取證 head 後端 `0cff973`、前端 `628aac5`。兩邊 Browser E2E 解析到對方 ref `claude/epic-curie-lj8alo`,且 checkout 到的正是對方取證 head;Playwright 兩邊 149/149。後端 unit 1050、E2E 96(含 D3b 5 案例)全綠;前端 vitest 1599(含四個守門檔)全綠。artifact 皆下載並核對 digest。證據 commit `4ff9a40`。
- **Task 3(合併)**:Yuan 指示由 Claude 合併。前端 #24 合併(0783da9);後端 #57 在 `4ff9a40` 的 PR run 經 429 重跑後全綠、`clean`,合併(d4843ef)。兩 repo 分支已 fast-forward 至 develop。

## 偏離

- 規劃寫 `gh api`;本環境改用 GitHub MCP 工具,功能相同。
- 前端 CI 只跑 mock mode 的 `npm test`;api mode 只有本機閘門可證(稽核表已註明)。
- 合併前提第 2 點「取證後兩邊皆無新推送」:後端在取證後多了證據 commit `4ff9a40`(只改 05-COVERAGE-AUDIT.md),其 PR run 與 push run 也全綠後才合併(同 05-25 慣例)。

## 後續

- CI 快取修正(deps 暖快取 job、其他 job 只讀快取;前端 Browser E2E 的後端打包一併檢查)— Yuan 決定合併後另開 quick task。
- 中文文案其餘半形標點(`;` `:` `?` `()`)是否統一 — 待 Yuan 決定。
- 重跑 `/gsd-verify-work 5` 關閉 G-05-7 / G-05-9 / G-05-10。

## Self-Check: PASSED
