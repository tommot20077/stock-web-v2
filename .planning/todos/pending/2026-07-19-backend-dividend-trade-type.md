---
created: 2026-07-19
title: 後端支援 DIV(股利)交易類型
area: trading
files:
  - stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/domain/TradeType.java:9-10
  - stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/TradingService.java:65-66
  - ../../vue/stock-v2/vue-app/src/types.ts:78
  - ../../vue/stock-v2/vue-app/src/pages/Trades.vue:65,75
---

## Problem

前端交易頁有「股利(Dividend)」篩選頁籤(`Trades.vue:65` 的 chips 陣列與 `:75` 的
`filter(tr => tr.type === 'DIV')`),前端 `Trade` 型別也宣告了 `'BUY' | 'SELL' | 'DIV'`。

但後端 `TradeType` enum **只有 BUY 與 SELL**(`TradeType.java:9-10`),
`TradingService` 的持倉計算也只處理這兩種(`:65-66` 的 `case BUY` / `case SELL`)。

結果:**API mode 下「股利」頁籤永遠是空的**——後端不可能回傳 DIV。
目前 mock mode 有一筆種子資料(`data.ts:47`,AAPL 季度配息)讓它看起來能用,
這是 mock 優於現實的假象。

於 Phase 3 discuss(2026-07-19)決議:方向是**由後端支援 DIV**,但不在 Phase 3 做,
先記為 todo。Phase 3 期間 API mode 的過渡行為另行決定。

## Solution

TBD。注意這不是「加一個 enum 值」就好:

- 股利會改變成本/損益計算語意(配息通常不影響持股數量,但影響 realized PnL 或成本基礎),
  `HoldingCalculator` 需要新的分支,不能沿用 applyBuy/applySell
- `CreateTradeRequest` 的 quantity/price 語意在 DIV 下不同(每股配息 × 持股數)
- 可能需要獨立的 migration 與 append-only transactions 的相容性確認
- 規模上很可能值得自己一個 phase,而非塞進既有 phase
