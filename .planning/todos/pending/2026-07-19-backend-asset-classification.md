---
created: 2026-07-19
title: 後端支援資產分類(產業別 / 資產類別)
area: asset
files:
  - stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/HoldingDto.java
  - stock-module-asset/src/main/java/dowob/xyz/stockwebv2/asset/api/AssetController.java
  - ../../vue/stock-v2/vue-app/src/pages/Overview.vue:139-142
  - ../../vue/stock-v2/vue-app/src/pages/Analytics.vue:58,161,198
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

## Solution

TBD。關鍵是先決定**分類資料的權威來源**:

- asset 主檔(`stock-module-asset`)自行維護分類欄位?誰負責填?怎麼更新?
- 或由外部行情商提供(需要 INTEGRATIONS 決策與可能的授權成本)
- 產業別的分類標準要選一套(GICS?自訂?),跨市場(美股/台股/加密)一致性是難點
- 加密貨幣沒有「產業」概念,分類體系要能容納異質資產
- 若只是要 donut,較輕量的替代方案是「各標的市值佔比」——用 `HoldingDto.marketValue`
  就算得出來,不需新欄位(Phase 3 discuss 曾列為選項但未採用)
