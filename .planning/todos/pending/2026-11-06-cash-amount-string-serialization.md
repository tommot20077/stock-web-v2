---
created: 2026-11-06
title: 現金金額改以字串序列化(或限制有效位數),避免 JSON double 無聲改寫極大值
area: portfolio
target: Phase 5
files:
  - stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/CreateCashEntryRequest.java
  - "前端 repo 根/vue-app/src/components/CashEntryDialog.vue"
  - "前端 repo 根/vue-app/src/services/cashApi.ts"
source: 04.1-REVIEW.md §WR-04;Yuan 2026-11-06 裁決延後至 Phase 5(quick-261106-fbs 關閉 `2026-10-26-phase-04.1-review-followups.md` 時另立本 todo)
---

## Problem

後端 `CreateCashEntryRequest.amount` 驗證為 `@DecimalMin("0.00000001")`、`@DecimalMax("1000000000000")`、
`@Digits(integer = 13, fraction = 8)`(最多約 20 位有效數字),但前端 `cashApi.ts` 的 `amount` 型別是 `number`,
`CashEntryDialog` 以 `Number(...)` → `JSON.stringify` 送出 IEEE-754 double。

例:使用者輸入 `123456789012.12345678`,實際送出最近的 double `123456789012.12346`,後端照存,
帳本金額與使用者輸入不同且沒有任何錯誤提示。後端註解以「1e12 < 2^53」論證安全,但那只涵蓋整數;
加上 8 位小數後,超過約 15–17 位有效數字就會被悄悄四捨五入。沖銷路徑同理(`reversalOf.amount` 是從 JSON 讀回的 double)。

一般金額(< 約 1e7 且 ≤ 2 位小數)不受影響。

## Suggested direction(已裁決,見下方〈決定〉;保留作為背景)

擇一(屬跨 repo 契約變更,需同步 `ai-docs/trading-portfolio-contract.md`、前端 `apiTypes` / mock adapter 與 E2E):

- 送字串金額:前端 `amount` 改送 decimal 字串;Jackson 預設會把 JSON 字串轉成 `BigDecimal`,`@DecimalMin` / `@Digits` 仍適用。
  回應端是否一併改字串(含交易 / 股利金額)需在 Phase 5 一起決定。
- 或限制前端驗證器總有效位數 ≤ 15,並在契約註明。

## 決定(Yuan 2026-11-07)

> 前端 repo 根的路徑定義見 `ai-docs/judgment.md` §8。

- 前端**引入 decimal 套件**(套件選型與合法性確認在 Phase 5 規劃時做;本 quick task 不新增任何依賴)。
- **金額 / 數量 / 價格**欄位在**請求與回應**都改為字串序列化(不只現金:含交易、股利、股數調整、持倉、summary 等 DTO 的同類欄位)。
- 前端 **service 層**負責字串 ↔ decimal 轉換;元件不直接處理字串金額。
- 上方〈Suggested direction〉的「限制有效位數 ≤ 15」選項不採用。
- 在 **Phase 5** 執行(見 `2026-11-07-phase-5-scope-decisions.md`);屬跨 repo 契約變更,需同步 `ai-docs/trading-portfolio-contract.md`、前端 `apiTypes` / mock adapter 與 E2E。
- 來源:Yuan 2026-11-07 待辦裁決第 2 題;記錄於 quick-261107-1j4。
