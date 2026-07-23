---
created: 2026-07-19
title: 後端支援可用現金 / 帳戶餘額模型
area: trading
files:
  - stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/PortfolioSummaryDto.java
  - ../../vue/stock-v2/vue-app/src/pages/Overview.vue:135
---

## Problem

Overview 的「可用現金」KPI 卡目前是**寫死的字串** `'$84,210'`(`Overview.vue:135`)。

後端 portfolio 模型**完全沒有現金的概念** —— `PortfolioSummaryDto` 的 7 個欄位
(totalMarketValue / totalCostBasis / realizedPnl / unrealizedPnl / totalPnl / roi /
holdingCount)全是持倉衍生值,`holdings` 表也只記證券部位。

Phase 3 discuss(2026-07-19)決議:API mode **先隱藏這張卡**。本 todo 追蹤補齊。

## Solution

TBD。這是**新增領域模型**,不是加欄位:

- 需要帳戶餘額(cash balance)實體與其異動紀錄(入金/出金/交易扣款/手續費)
- 與現有 append-only `transactions` 表的關係要先釐清:買賣是否要同步扣加現金?
  若要,`TradingService.createTrade` 的語意會改變(目前只記錄已成交交易,不動現金)
- 多幣別:現金餘額幾乎必然是多幣別的,會牽動整個 portfolio 的幣別模型
- 注意 judgment §1:本專案的交易是「已成交紀錄」而非下單系統,加入現金餘額
  可能讓語意往「帳戶系統」偏移,需要先確認是否符合 PROJECT.md 的範圍
- 規模上應該自己一個 phase
