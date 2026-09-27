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
