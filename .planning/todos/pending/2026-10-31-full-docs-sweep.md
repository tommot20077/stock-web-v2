---
created: 2026-10-31
title: 全部文件掃描更新(ai-docs、.planning/codebase、前端 docs)
area: docs
files:
  - ai-docs/
  - .planning/codebase/
  - ../stock-web-v2-front-end/docs/
  - ../stock-web-v2-front-end/AGENTS.md
---

## Problem

Phase 04.2 研究時發現 `ai-docs/security.md` 的 WS 章節與程式有 5 處不一致(端點、channel 命名、K 線週期、頻率限制行為、攔截器名),起因是開工前寫的設計文件在實作後沒有回頭同步。WS 部分已排進 Phase 04.2(D-25),但其他文件很可能有同類漂移:

- `.planning/codebase/*.md` 最後更新 2026-05-30,`verify codebase-drift` 回報大量模組已變動。
- 前端 `docs/api-contracts/mock-to-real-contract.md` 的信封一節已知過時(前端 AGENTS.md 鐵律 4)。
- CONTEXT.md canonical_refs 仍寫舊前端路徑 `../../vue/stock-v2/vue-app/`。

## Solution

Yuan 指定:Phase 04.2 這部分結束之後,做一次**全部文件**的掃描更新——逐份對照程式現況(端點、設定值、類別名、行為),以程式為準修文件,無法判斷的列給 Yuan 裁決。可考慮 `/gsd-docs-update` 或 `/gsd-map-codebase` 重產 codebase 對照圖。
