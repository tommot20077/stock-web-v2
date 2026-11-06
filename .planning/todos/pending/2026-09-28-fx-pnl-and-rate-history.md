---
created: 2026-09-28
title: 匯兌損益與匯率歷史來源
area: backend-trading
source: Phase 04.1 discuss (Deferred)
---

## Context

Phase 04.1 D-15:今日損益只算價格損益,一律用目前匯率折算基準幣,並標示「不含匯差」。
Yuan 問「好系統要不要給匯差」:專業平台(如 Interactive Brokers)把損益拆成「價格」與「匯兌」兩塊並列,不做切換選項。

## 需要的前置

- `fx_rates` 目前只有 V2 seed 的靜態值,沒有每日歷史;FX 資產(USD/TWD 等)非 tradeable,ingestor 不推行情。
- 需先決定匯率來源(FX 行情 ingest 或外部匯率 API)並保存每日匯率。

## Scope(之後)

- 新增 `dayFxPnl`(欄位命名已在 04.1 預留拆分)。
- summary / 持倉顯示「價格損益 / 匯兌損益 / 合計」。

## 併入 04.1 WR-01(基準幣總額未標示匯率時間)

2026-11-06 由 quick-261106-fbs 併入(Yuan 裁決延後;原 todo `2026-10-26-phase-04.1-review-followups.md` 已關閉)。來源:`.planning/phases/04.1-backend-data-gap-backfill/04.1-REVIEW.md` §WR-01。

- 問題:`FxRateFacadeImpl#currentRates()` 會讀 `fx_rates.rate_time`,但下游沒有使用;`fx_rates` 除 V2 seed(USD/TWD、EUR/USD、USD/JPY、TWD/USD 四列)外沒有寫入者。所有跨幣別數字(`totalMarketValue`、`cash`、`totalEquity`、`marketValueInBase`、`unrealizedPnlInBase`、配置權重)都用這些靜態匯率,畫面沒有「匯率時間」,使用者看不出換算已過時;`fxExcludedCount` / `unconvertedCurrencies` 只涵蓋「換不到」,不涵蓋「換得到但過時」。
- 方向(擇一或並用,與本 todo 的匯率來源一起做):summary 回傳最舊使用匯率時間(例如 `fxAsOf`)並在 Overview 基準幣總額旁標示「匯率更新於 …」;或在 `FxRates.of` 把超過設定時效的匯率視為換不到,落入 `unconvertedCurrencies`。
