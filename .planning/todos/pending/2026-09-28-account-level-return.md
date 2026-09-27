---
created: 2026-09-28
title: 帳戶層級報酬率(以淨入金為分母)
area: backend-trading
source: Phase 04.1 discuss (Deferred)
---

## Context

Phase 04.1 D-22:有了現金帳後,summary 新增 `totalEquity` 與 `cash`,但既有 `roi` 維持「以持倉成本計」的持倉報酬。
帳戶層級報酬率 `(總資產 − 淨入金) ÷ 淨入金` 延後,原因:功能上線前的既有交易沒有入金紀錄,淨入金可能為 0 或負,數字會失真。

## Scope(之後)

- 決定計算方式(簡單報酬 / 時間加權 / 金額加權)。
- 需要使用者補齊入金紀錄後才有意義;考慮顯示條件。
