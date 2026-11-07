---
created: 2026-09-28
title: 資產主檔管理(新增 / 下架 / sector / 交易時區)
area: backend-asset
source: Phase 04.1 discuss (Deferred)
target: v1.0 之後的新 milestone,主題「真實行情與參考資料來源」
---

## Context

Phase 04.1 D-20:新資產的 sector 目前只能靠 migration seed 維護,系統沒有新增資產的介面。
Yuan 問要不要在 04.1 做 ADMIN 編輯 sector:Claude 建議不做——只改 sector 是半套,真正的需求點在接真實行情源時,
資產會由行情商批量帶入,sector 也可由行情商欄位帶入。

## Scope(之後,隨真實行情源規劃)

- 資產新增 / 下架 / 編輯(sector、market、交易時區 / TradingDay 例外)。
- 行情商資產清單同步與 sector 對應。

## 排程(Yuan 2026-11-07)

五個大型功能都不插隊:先完成 Phase 5 → 收 v1.0 → 開新 milestone。本 todo 與另外兩條同屬新 milestone 的
**「真實行情與參考資料來源」主題**:`2026-09-28-fx-pnl-and-rate-history`(匯率歷史)、`2026-09-28-kline-adjusted-prices`(K 線還原)、
`2026-09-28-asset-master-management`(資產主檔),應一起規劃(共同前置是接真實行情 / 參考資料來源)。
新 milestone 的第一個 phase 是帳戶層級報酬率(`2026-09-28-account-level-return`)。
來源:Yuan 2026-11-07 待辦裁決第 1 題;記錄於 quick-261107-1j4。
