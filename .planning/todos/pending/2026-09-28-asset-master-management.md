---
created: 2026-09-28
title: 資產主檔管理(新增 / 下架 / sector / 交易時區)
area: backend-asset
source: Phase 04.1 discuss (Deferred)
---

## Context

Phase 04.1 D-20:新資產的 sector 目前只能靠 migration seed 維護,系統沒有新增資產的介面。
Yuan 問要不要在 04.1 做 ADMIN 編輯 sector:Claude 建議不做——只改 sector 是半套,真正的需求點在接真實行情源時,
資產會由行情商批量帶入,sector 也可由行情商欄位帶入。

## Scope(之後,隨真實行情源規劃)

- 資產新增 / 下架 / 編輯(sector、market、交易時區 / TradingDay 例外)。
- 行情商資產清單同步與 sector 對應。
