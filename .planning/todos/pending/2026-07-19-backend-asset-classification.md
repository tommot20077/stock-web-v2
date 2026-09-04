---
created: 2026-07-19
title: 後端支援資產分類(產業別 / 資產類別)
area: asset
files:
  - stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/HoldingDto.java
  - stock-module-asset/src/main/java/dowob/xyz/stockwebv2/asset/api/AssetController.java
  - ../../vue/stock-v2/vue-app/src/pages/Overview.vue:139-142
  - ../../vue/stock-v2/vue-app/src/pages/Analytics.vue:58,161,198
  - ../../vue/stock-v2/vue-app/src/pages/Positions.vue:118-140
---

## Problem

前端有兩處需要「資產分類」資料,後端都沒有:

1. **資產配置 donut**(`Overview.vue:139-142` 的 `alloc`)—— 寫死陣列
   `[{n:'Equity',v:52}, {n:'Crypto',v:22}, {n:'FX',v:14}, {n:'Bonds',v:8}, {n:'Cash',v:4}]`,
   需要**資產類別**(股票/加密/外匯/債券/現金)分類
2. **產業分布 / 按產業分群**(`Analytics.vue:58`、`:161`、`:198`,i18n `sectorBreakdown`)——
   需要**產業別**(Tech/Crypto/Auto/Retail),前端種子資料寫在 `data.ts:36-41`

後端 `HoldingDto` 13 個欄位**沒有 sector,也沒有 assetClass**。

Phase 3 discuss(2026-07-19)決議:
- 資產配置 donut 在 API mode **隱藏**
- `sector` 本階段不處理(Analytics 屬 PORT-06,v2 deferred)

兩者同源,合併成本 todo 追蹤。

## ⚠️ 更正(2026-07-26,Phase 4 discuss 查證)

**下方 Solution 一節的前提「分類資料的權威來源還沒有」是錯的 —— 後端已經有了。**

| 事實 | 證據 |
|---|---|
| `assets.sector VARCHAR(100)` 欄位**存在** | `V1__foundation_schema.sql:27` |
| 該欄位**有 seed 值** | `V2__foundation_seed_assets.sql:3-12`:`'Tech'`/`'Auto'`/`'Retail'`/`'Crypto'` |
| `assets.asset_type` 存在(STOCK/CRYPTO/FX/BOND) | `V1:24`、`AssetType` enum |
| `AssetDto` **已經對外回傳 `sector`** | `AssetDto.java:16` |

所以缺口比原本描述的小很多:**不是「新增分類領域模型」,而是「portfolio 的 SQL 沒 JOIN `assets.sector`、`HoldingDto` 沒這個欄位」**。
資產配置 donut 的四類(Equity/Crypto/FX/Bonds)可由 `asset_type` 直接推導,只有 `Cash` 那一塊要等現金模型(見 `2026-07-19-backend-available-cash.md`)。

**仍未解決的部分**(原 Solution 的疑問只有一部分被推翻):新資產的 sector 由誰維護、要不要選一套標準(GICS/自訂)、跨市場一致性、加密貨幣無「產業」概念 —— 這些對**現有 seed 之外**的資產仍然成立。

## Solution

TBD。關鍵是先決定**分類資料的權威來源**:

- asset 主檔(`stock-module-asset`)自行維護分類欄位?誰負責填?怎麼更新?
- 或由外部行情商提供(需要 INTEGRATIONS 決策與可能的授權成本)
- 產業別的分類標準要選一套(GICS?自訂?),跨市場(美股/台股/加密)一致性是難點
- 加密貨幣沒有「產業」概念,分類體系要能容納異質資產
- 若只是要 donut,較輕量的替代方案是「各標的市值佔比」——用 `HoldingDto.marketValue`
  就算得出來,不需新欄位(Phase 3 discuss 曾列為選項但未採用)

## Scheduling

**已排入 Phase 04.1**(2026-07-26,Phase 4 discuss 時 Yuan 決定)。見 `.planning/ROADMAP.md` 的
`### Phase 04.1: Backend Data Gap Backfill (INSERTED)`。依上方更正,這是四條裡**最便宜**的一條。
