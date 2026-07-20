# Phase 3: Portfolio Read API Mode - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-07-19
**Phase:** 3-portfolio-read-api-mode
**Areas discussed:** 後端沒有的欄位、P&L/ROI 計算歸屬、交易分頁與篩選排序、loading/error 呈現層級

---

## 後端沒有的欄位 — `sector`

| Option | Description | Selected |
|--------|-------------|----------|
| API mode 隱藏該欄 | 資料不存在就不假裝有 | |
| 顯示「—」佔位 | 欄位結構兩種 mode 一致 | |
| 前端維 symbol→sector 對照表 | UI 完整但資料會過時 | |

**User's choice:** 反問「UI 有 `sector` 欄位 這是甚麼?」
**Notes:** 使用者的反問促成了重要更正 —— Claude 原本的提問前提**是錯的**。查證後確認 `sector` **不在** Positions 頁,而是用於 `Analytics.vue`(treemap 標籤 :58、按產業分群 :161、hover chip :198)與 i18n 「產業分布」。Analytics 屬 PORT-06(v2 deferred),**因此本題在 Phase 3 根本不需決定**,選項全數作廢。教訓:提問前應先查證 UI 實際使用位置。

---

## 後端沒有的欄位 — `DIV`(股利)

| Option | Description | Selected |
|--------|-------------|----------|
| API mode 收斂為 BUY/SELL | DIV 僅存於 mock | |
| 型別不動,DIV 自然不出現 | 保留永不發生的分支 | |
| 要求後端支援 DIV | 擴到後端 | ✓(方向) |

**User's choice:** 「3 但我們先標記 todo」
**Notes:** 使用者反問「這會用在哪邊?」後查證:DIV 的真正用途是 `Trades.vue:75` 的「股利」篩選頁籤(chips 陣列 :65),種子資料僅一筆(`data.ts:47` AAPL 季度配息)。方向確定為後端支援,但**不在 Phase 3 做**,已寫入 `.planning/todos/pending/2026-07-19-backend-dividend-trade-type.md`。Phase 3 期間的過渡行為另問(見下)。

---

## 資料新鮮度 — `priceTime` / `lastUpdated`

| Option | Description | Selected |
|--------|-------------|----------|
| 顯示行情時間 | 市價來自快取/預計算,可能延遲 | ✓ |
| 不顯示,只做欄位映射 | 範圍最小 | |
| 你決定 | — | |

**User's choice:** 顯示行情時間

---

## P&L / ROI 計算歸屬

| Option | Description | Selected |
|--------|-------------|----------|
| 全部改用後端值 | 符合 §7,realizedPnl 前端算不出 | ✓ |
| 混合:能算的前端算 | 兩組數字來源不同,可能加總對不起來 | |
| 保留前端計算 | 改動最小但與後端分歧 | |

**User's choice:** 全部改用後端值
**Notes:** 查證確認現有 UI 全是 client-side 計算(`Positions.vue:237` 的 `reduce((s,p) => s + p.qty * p.avg)`、`:174` 的 `qty * effPrice(p)`)。決定性論據:`realizedPnl` 需要完整交易歷史,持倉快照推導不出來,mock 的算法本質上不完整。

---

## 交易頁篩選(Buy/Sell/2026)

| Option | Description | Selected |
|--------|-------------|----------|
| 後端補篩選參數 | 唯一語意正確的做法,但要動後端 | ✓ |
| API mode 暗去這些篩選 | 不騙人但功能變少 | |
| 一次拉全部再前端篩 | 改動最小,交易量大時會爆 | |

**User's choice:** 後端補篩選參數
**Notes:** 陷阱查證:`Trades.vue:68-79` 對**完整陣列**做 client-side 篩選;分頁後只會篩到當前頁,使用者按「Buy」會誤以為看到所有買入。這是正確性問題而非體驗問題。**此決定使 Phase 3 包含後端改動**,與 ROADMAP 原本「前端讀取」的框定不同,已在 CONTEXT.md 顯著標註。

---

## CSV 匯出範圍

| Option | Description | Selected |
|--------|-------------|----------|
| 匯出全部 | 循環拉完所有頁 | ✓ |
| 只匯出當前頁 + 明示 | 保持單一請求 | |
| API mode 先藏起匯出 | 避免出錯的結果 | |

**User's choice:** 匯出全部
**Notes:** 同源陷阱:`exportCsv()`(:83)基於 `filteredTrades`,分頁後會變成只匯出當前頁,但使用者預期是全部。

---

## 股利頁籤過渡行為(Phase 3 期間)

| Option | Description | Selected |
|--------|-------------|----------|
| API mode 先藏起 | 後端支援前不露入口 | ✓ |
| 保留並顯示「尚未支援」 | 讓使用者知道路線圖 | |

**User's choice:** API mode 先藏起

---

## 交易分頁 UI

| Option | Description | Selected |
|--------|-------------|----------|
| 換頁按鈕 | 與 page-number 語意對應,無重複列問題 | ✓ |
| 載入更多(append) | page-number 位移會造成重複列 | |
| 不加控制,只拉第一頁 | 最小改動但看不到舊資料 | |

**User's choice:** 換頁按鈕
**Notes:** 交易頁目前**完全沒有**分頁控制,直接 `v-for` 渲染整串。append 的重複列風險在 2026-07-19 分頁對齊工作中已驗證(mock 測試明確斷言 page drift),Phase 4 加入交易建立後風險會實際發生。

---

## Overview 近期交易

| Option | Description | Selected |
|--------|-------------|----------|
| 直接 size=5 拉第一頁 | 只拿需要的量 | ✓ |
| 與交易頁共用同一份資料 | 減少請求但需跨頁共享狀態 | |

**User's choice:** 直接 size=5 拉第一頁

---

## 交易排序

| Option | Description | Selected |
|--------|-------------|----------|
| 成交時間 + 金額 + 數量 | 三個最常用,後端好做索引 | ✓ |
| 所有顯示欄位都可排 | 彈性高但要防注入、多開索引 | |
| 只要成交時間升降序 | 最簡單 | |

**User's choice:** 成交時間 + 金額 + 數量;預設 `executedAt` 降序、每頁 20
**Notes:** ⚠️ **此項為使用者主動推翻 Claude 的判斷。** Claude 原本認為「交易頁本來就沒有排序,現在加等於 scope creep」而建議延後;使用者指出「要排序跟分頁吧 不然資料一多會有問題」。此判斷正確:分頁一旦做了,client-side 排序就只排當前頁,**比沒有排序更危險**(看起來正確但結果是錯的),因此排序必須與篩選一起做在後端。

---

## loading / error 呈現層級

| Option | Description | Selected |
|--------|-------------|----------|
| 各 view 內嵌 | 單一區塊失敗不災難化整頁 | ✓ |
| 全部走全域 SessionBanner | 汰用既有元件但混淆錯誤性質 | |
| 你決定 | — | |

**User's choice:** 各 view 內嵌

---

## trace id 露出方式

| Option | Description | Selected |
|--------|-------------|----------|
| 只在錯誤狀態顯示 | 與 Phase 2 SessionBanner 慣例一致 | ✓ |
| 錯誤顯示 + console 輸出 | 方便開發者複製 | |
| 只存在錯誤物件,不顯示 | 畫面最乾淨但使用者給不出線索 | |

**User's choice:** 只在錯誤狀態顯示

---

## Claude's Discretion

本次討論使用者對所有提問都給了明確選擇,無「你決定」項目。實作細節(元件拆分、loading 骨架樣式、換頁按鈕版面)留給 planner/executor 依現有 UI 慣例決定。

## Deferred Ideas

- **後端支援 DIV(股利)** — 已寫入 `.planning/todos/pending/2026-07-19-backend-dividend-trade-type.md`
- **`sector` 與 Analytics 產業分布** — PORT-06,v2 deferred
- **多幣別呈現、空持倉初始引導、mock↔api 切換的資料殘留** — 討論尾聲列為可選深入項目,使用者選擇直接進 CONTEXT,未展開

## 流程備註

- Claude 將每個領域的相關問題**合併成單次提問**(等同 `--batch` overlay 的效果),而非預設模式的「4 個單題輪次」,以減少來回次數。此偏離已於討論前向使用者說明。
- 本次討論有 **兩處 Claude 的錯誤/誤判經使用者反問或指正而更正**:`sector` 的使用位置(提問前提錯誤)、排序是否為 scope creep(判斷錯誤)。兩者皆已在上方對應段落如實記錄。
