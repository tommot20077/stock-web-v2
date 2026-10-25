---
created: 2026-10-25
title: Positions「Top movers」卡改以基準幣計算並移除寫死的 $
area: portfolio
files:
  - ../stock-web-v2-front-end/vue-app/src/pages/Positions.vue
  - stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/HoldingDto.java
  - stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/PortfolioQueryService.java
source: Phase 04.1 plan 04.1-16 executor 回報(Yuan 於 2026-10-25 裁決:記待辦,不在 04.1 處理)
---

## Problem

Positions 頁的 Top movers 卡(mock 與 API 兩模式共用):

1. 金額前綴仍寫死 `$`,違反 04.1 D-30(金額一律「數字 + ISO 代碼」)。
2. 以**原幣**的 `unrealizedPnl` 排序。API mode 在 04.1 之後是多幣別持倉(USD / TWD …),
   不同幣別的損益數字直接比大小,與 04.1 修掉的「權重單位錯誤」(D-05)同一類問題。

## Why not in 04.1

要正確排序需要後端在 `HoldingDto` 追加**基準幣未實現損益**欄位(目前只有原幣
`unrealizedPnl` 與基準幣市值 `marketValueInBase`)。HoldingDto 欄位已在 04.1 凍結並寫進契約檔,
再追加欄位屬於新的後端變更,Yuan 裁決不擴 04.1 範圍。

## Suggested direction

- 後端:`HoldingDto` 追加 `unrealizedPnlInBase`(缺匯率時 null,沿用 `fxExcludedCount` 語意);
  同步契約檔與前端 `apiTypes.ts`。
- 前端:API mode 以 `unrealizedPnlInBase` 排序、金額走共用 `moneyFormat`(數字 + 基準幣 ISO);
  缺值的持倉不進榜並提示。
