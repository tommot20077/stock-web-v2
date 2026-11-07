---
created: 2026-09-28
title: K 線還原權息(拆股 / 配股造成的歷史價格斷層)
area: backend-marketdata
source: Phase 04.1 discuss (Deferred)
target: v1.0 之後的新 milestone,主題「真實行情與參考資料來源」
---

## Context

Phase 04.2 會引入配股與拆股 / 合股(D-04 ~ D-10)。事件日前後的歷史價格會出現斷層(例:1 拆 4 後價格變 1/4),
K 線圖與以歷史價格計算的指標(回測)會失真。04.2 只處理今日損益的前收調整(D-10),不處理整段歷史。

## Scope(之後)

- 還原權息的調整因子來源(使用者記錄的事件 vs 行情商提供)。
- K 線 API 提供「原始 / 還原」兩種價格。
- 回測模組是否改用還原價。

## 排程(Yuan 2026-11-07)

五個大型功能都不插隊:先完成 Phase 5 → 收 v1.0 → 開新 milestone。本 todo 與另外兩條同屬新 milestone 的
**「真實行情與參考資料來源」主題**:`2026-09-28-fx-pnl-and-rate-history`(匯率歷史)、`2026-09-28-kline-adjusted-prices`(K 線還原)、
`2026-09-28-asset-master-management`(資產主檔),應一起規劃(共同前置是接真實行情 / 參考資料來源)。
新 milestone 的第一個 phase 是帳戶層級報酬率(`2026-09-28-account-level-return`)。
來源:Yuan 2026-11-07 待辦裁決第 1 題;記錄於 quick-261107-1j4。
