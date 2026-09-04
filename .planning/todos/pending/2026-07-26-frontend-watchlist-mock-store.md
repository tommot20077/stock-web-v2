---
created: 2026-07-26
title: Chart/Markets 仍直連 mock store(watchlist 未 API 化)
area: frontend
files:
  - ../../vue/stock-v2/vue-app/src/pages/Chart.vue:157,163,167,86
  - ../../vue/stock-v2/vue-app/src/pages/Markets.vue:130,137,173,236,71-73
  - ../../vue/stock-v2/vue-app/src/stores/mockPortfolio.ts
---

## Problem

Phase 3 完成後,Overview / Positions / Trades 三頁都已改走 `getRuntimeApiClients().portfolio`,
不再 import mock store(PORT-04 / judgment §3)。

但 **`Chart.vue` 與 `Markets.vue` 仍直接 `import { useMockPortfolioStore }`**,
在 API mode 下也是直接讀寫 mock store。

**性質澄清(查證後):** 這兩頁用 mock store **不是為了 portfolio 讀取**,而是 **watchlist(自選股)**:

| 檔案 | 用途 |
|---|---|
| `Chart.vue:167` | `portfolio.isWatched(...)` |
| `Chart.vue:86` | `portfolio.toggleWatch(...)` |
| `Markets.vue:71-73, 236` | `portfolio.isWatched` / `portfolio.toggleWatch` |
| `Markets.vue:173` | `portfolio.watchlists` |

因此這**不是 Phase 3 的遺漏** —— PORT-04 的範圍是 portfolio adapters(summary/holdings/trades),
watchlist 屬 **PORT-06「Vue API mode 支援 analytics、alerts、notifications、watchlists、settings
等完整 portfolio-adjacent 頁面」**,已列為 v2 deferred。

記錄本 todo 的原因:嚴格讀 judgment §3「元件不得 import mock store」時,這兩頁是**目前唯二**
的例外。未來若有人 grep 檢查 §3 合規性,會看到這兩處而困惑是否為 Phase 3 的漏網 —— 本檔即為
該疑問的答案。

## Solution

TBD。兩條路,取決於 watchlist 何時 API 化:

1. **等 PORT-06** — watchlist 後端 API 出現時,比照 portfolioApi 建立 `watchlistApi` 三件組
   (mock/http/factory),兩頁改走 `getRuntimeApiClients().watchlist`。這是最終形態。
2. **先做介面隔離** — 若短期內不會有後端 watchlist API,也可以先把 mock store 包成
   `watchlistApi` 的 mock-only 實作,讓元件不再直接 import store(符合 §3 的形式),
   http 實作留空待後端。成本低但收益也有限,屬純結構整理。

判斷點:如果 v2 的 watchlist 後端形狀還沒定,做 (2) 可能白工(介面會跟著改);
若只是想讓 §3 合規性檢查乾淨,(2) 就夠。建議等 PORT-06 有明確排程再一起做。

## Scheduling

**已排入 Phase 04.1**(2026-07-26,Phase 4 discuss 時 Yuan 決定)。見 `.planning/ROADMAP.md` 的
`### Phase 04.1: Backend Data Gap Backfill (INSERTED)`。

**排程本身就回答了上方的判斷點**:既然 watchlist 後端 API 已排入 04.1,就走路線 (1)(比照
portfolioApi 建 `watchlistApi` 三件組),不要做路線 (2) 的純介面隔離 —— 那會是白工。

補充查證(2026-07-26):後端目前**完全沒有** watchlist —— 整個 repo 只有
`Permission.WATCHLIST_MANAGE` 一個 enum 值(`Permission.java:4`、`Role.java:13`),無表、無 endpoint。
所以 04.1 要從 schema 開始做,不是接線。
