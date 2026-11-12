---
phase: 05-cross-repo-browser-flow-verification-contract-hardening
plan: 12
subsystem: verification-gate
tags: [ci, pr, coverage-audit, wave-gate, D-01, D-25]
requires: [05-01, 05-02, 05-03, 05-04, 05-05, 05-06, 05-07, 05-08, 05-09, 05-10, 05-11]
provides: [05-COVERAGE-AUDIT.md, Wave 1 merged into develop (both repos), develop CI green]
affects: [05-13, 05-16]
key-files:
  created:
    - .planning/phases/05-cross-repo-browser-flow-verification-contract-hardening/05-COVERAGE-AUDIT.md
  modified:
    - stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/support/MarketDataContainers.java
    - stock-start/pom.xml
    - ai-docs/testing-standards.md
    - AGENTS.md
    - .planning/codebase/STACK.md
    - .planning/codebase/TESTING.md
    - docs/guides/getting-started.md
    - docs/testing/overview.md
decisions:
  - "Jacoco stock-start 下限依 D-23 調為 0.87 / 0.64(Yuan 2026-11-10 核准;05-06 把 7 個 E2E 移出 verify)"
  - "develop 的 PR #54(測試映像升級)衝突以本分支共用容器版本為準,映像升級套到 MarketDataContainers"
metrics:
  completed: 2026-11-11
---

# Phase 05 Plan 12: Wave 1 閘門(本機閘門 → PR → CI 證據 → 合併 → develop 綠)Summary

Wave 1(安全網)兩 repo PR 經 CI 全綠、覆蓋稽核表成形,Yuan 核准後合併,兩 repo develop CI 綠 —— D-25 閘門通過,Wave 2 可開始。

## Task 1 — 本機閘門、推送、開 PR

- 後端:`./mvnw -B spotless:check` OK;`./mvnw -B test --fail-at-end` 10 模組 BUILD SUCCESS,993 tests / 0 failures / 0 errors。
- 前端:lint 0 errors(40 既有 warning);`npm test` 1337 passed;build OK;`VITE_DATA_MODE=api` test 1337 passed + build OK;`layout.spec.ts` 101 passed;`playwright --list` 149 tests。
- PR:tommot20077/stock-web-v2#55、tommot20077/stock-web-v2-front-end#22(head `claude/epic-curie-lj8alo` → `develop`)。GitHub 操作經 MCP GitHub 工具(本環境無 `gh` CLI)。

## Task 2 — CI 證據與稽核表

- 後端 PR run 42741526488(head `ee4094c`)全綠;前端 PR run 42648098145(head `41469e1`)全綠。稽核表 `05-COVERAGE-AUDIT.md`(commit `bc0f2ea`,其後 PR run 42744222267 亦全綠)。
- 重點:21 個 `*E2E` 全綠(含 05-06 改名 7 個);unit job 無 E2E 報告;11 個 market-data IT 亂序(seed 176983128627)全綠、三容器各啟動一次;兩邊 Browser E2E 149/149、J0 與 18 個 S5 passed。

## Task 3 — 合併與 develop CI

- 2026-11-11 Yuan 回覆「好合併」,依序以 merge commit 合併:後端 `56ee65c`、前端 `9e920b8`;兩分支 fast-forward 到 develop。
- develop push CI:後端 run 42946358757(5 job 皆 success)、前端 run 42946379447(2 job 皆 success)。

## Deviations from Plan

1. **[Rule 3 — 阻擋] develop 合併衝突**:開 PR 後 develop 已合入 PR #54(TimescaleDB 2.27.2-pg18 / apache/kafka 4.2.0 / Redis 8.8.0-alpine、Jacoco 棘輪),與 05-07 / 05-08 的 11 個 market-data IT 衝突,導致 PR 事件 CI 不觸發。合併 develop(`f12d909`):IT 保留共用容器版本,映像升級套到 `MarketDataContainers`;另以 `180a652` 更新 5 份文件的映像版本與 testing-standards 共用容器敘述。
2. **[Rule 4 — 需裁決] Jacoco stock-start 低於下限**:IT 全綠但 stock-start 0.87 < 0.88、0.64 < 0.66(05-06 把 7 個 E2E 移出 verify)。依 testing-standards「調降須先問 Yuan」於 PR 留言說明並詢問;Yuan 選擇調降,`ee4094c` 改為 0.87 / 0.64 並在下限表註明。
3. **前端 05-01 尾端兩個 commit 開 PR 時未推送**(PR 首輪 CI 跑在 `3b1c62c`),發現後補推 `41469e1` 重跑。
4. **Codex review bot** 在兩 PR 皆因用量上限未完成審查,無可處理的 finding。

## Open items(不阻擋 Wave 2)

- `05-COVERAGE-AUDIT.md` §VER-01 的 12 個人工檢視勾選框尚未勾選(含 05-10 紅漲綠跌視覺、`deferred-items.md` 的登入後頁面不重讀 UX 缺陷、stock-start Jacoco 下限是否另排補回、market-data failsafe 報告未上傳 artifact)。

## Self-Check: PASSED

- 05-COVERAGE-AUDIT.md 存在且 plan verify(4 個 SC 標題、G1~G5 ≥5、run id ≥1)通過。
- 兩 PR `merged=true`;兩 repo develop 最新 push run conclusion = success(42946358757、42946379447)。
