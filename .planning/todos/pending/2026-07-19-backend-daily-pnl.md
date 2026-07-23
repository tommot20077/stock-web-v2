---
created: 2026-07-19
title: 後端支援日級損益(今日損益 KPI)
area: trading
files:
  - stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/PortfolioSummaryDto.java
  - ../../vue/stock-v2/vue-app/src/pages/Overview.vue:134
---

## Problem

Overview 的「今日損益」KPI 卡目前是**寫死的字串** `'+$12,481'`(`Overview.vue:134`),
不是任何真實計算。

後端 `PortfolioSummaryDto` 只有 `realizedPnl` / `unrealizedPnl` / `totalPnl`,
**沒有任何時間維度** —— 無法回答「今天賺賠多少」。

Phase 3 discuss(2026-07-19)決議:API mode **先隱藏這張卡**,不顯示假資料。
本 todo 追蹤補齊後端能力。

## Solution

TBD。難點在於這需要**日級歷史快照**,不是加個欄位:

- 需要每日收盤時的持倉市值快照(或可回溯的價格時間序列 + 當日持倉)
- 「今日」的定義要先確定:交易日?自然日?使用者時區還是市場時區?
- 跨時區與非交易日(週末/假日)要顯示什麼
- market-data 模組已有 TimescaleDB hypertable 與 continuous aggregates
  (`V4__market_data_hypertable.sql`、`V5__market_data_continuous_aggregates.sql`),
  可能可以沿用,但持倉快照是 trading 模組的責任
- 規模上很可能值得自己一個 phase,而非塞進既有 phase
