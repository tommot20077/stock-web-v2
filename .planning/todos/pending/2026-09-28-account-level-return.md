---
created: 2026-09-28
title: 帳戶層級報酬率(以淨入金為分母)
area: backend-trading
source: Phase 04.1 discuss (Deferred)
target: v1.0 之後的新 milestone(第一個 phase)
---

## Context

Phase 04.1 D-22:有了現金帳後,summary 新增 `totalEquity` 與 `cash`,但既有 `roi` 維持「以持倉成本計」的持倉報酬。
帳戶層級報酬率 `(總資產 − 淨入金) ÷ 淨入金` 延後,原因:功能上線前的既有交易沒有入金紀錄,淨入金可能為 0 或負,數字會失真。

## Scope(之後)

- 決定計算方式(簡單報酬 / 時間加權 / 金額加權)。
- 需要使用者補齊入金紀錄後才有意義;考慮顯示條件。

## 排程(Yuan 2026-11-07)

五個大型功能都不插隊:先完成 Phase 5 → 收 v1.0 → 開新 milestone。帳戶層級報酬率是**新 milestone 的第一個 phase**。
(其餘:匯率歷史 / K 線還原 / 資產主檔歸為「真實行情與參考資料來源」主題;資產分類剩下的 Analytics 產業分布隨 Analytics API 化。)
來源:Yuan 2026-11-07 待辦裁決第 1 題;記錄於 quick-261107-1j4。
