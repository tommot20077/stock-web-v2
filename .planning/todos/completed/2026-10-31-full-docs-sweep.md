---
created: 2026-10-31
title: 全部文件掃描更新(ai-docs、.planning/codebase、前端 docs)
area: docs
files:
  - ai-docs/
  - .planning/codebase/
  - ../stock-web-v2-front-end/docs/
  - ../stock-web-v2-front-end/AGENTS.md
resolved: 2026-11-06
resolved_by: quick-261106-fbs(後端 da4b541..7221c7f、前端 b4ff61e..fbae351;PR 待 orchestrator 開)
---

## Problem

Phase 04.2 研究時發現 `ai-docs/security.md` 的 WS 章節與程式有 5 處不一致(端點、channel 命名、K 線週期、頻率限制行為、攔截器名),起因是開工前寫的設計文件在實作後沒有回頭同步。WS 部分已排進 Phase 04.2(D-25),但其他文件很可能有同類漂移:

- `.planning/codebase/*.md` 最後更新 2026-05-30,`verify codebase-drift` 回報大量模組已變動。
- 前端 `docs/api-contracts/mock-to-real-contract.md` 的信封一節已知過時(前端 AGENTS.md 鐵律 4)。
- CONTEXT.md canonical_refs 仍寫舊前端路徑 `../../vue/stock-v2/vue-app/`。

## Solution

Yuan 指定:Phase 04.2 這部分結束之後,做一次**全部文件**的掃描更新——逐份對照程式現況(端點、設定值、類別名、行為),以程式為準修文件,無法判斷的列給 Yuan 裁決。可考慮 `/gsd-docs-update` 或 `/gsd-map-codebase` 重產 codebase 對照圖。

## Resolution

- quick-261106-fbs Task 3 逐份以程式為準掃描並修正:`ai-docs/*.md`(含 04.2 G2 三處)、`.planning/codebase/*.md` 7 份(針對性更新至目前 HEAD)、前端 `AGENTS.md`、`docs/api-contracts/mock-to-real-contract.md`、`docs/LEARNINGS.md` 與 `docs/superpowers/**` 歷史標示。
- 無法由程式判斷者未猜測,列在 `.planning/quick/261106-fbs-small-fixes-batch-a-markets-640px-b-top-/task3-report.md` 的「需 Yuan 裁決」清單。
- 偏離原建議:未使用 `/gsd-map-codebase` 重產(本執行環境不派 mapper agents),改為逐份針對性更新;未驗證的段落同樣列在 task3-report。
