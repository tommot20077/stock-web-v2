---
created: 2026-10-26
title: API mode 的 Overview 仍顯示未標示的 mock 觀察清單與新聞(違反鐵律 6)
area: frontend-overview
files:
  - ../stock-web-v2-front-end/vue-app/src/pages/Overview.vue
source: Phase 04.1 走查(2026-10-26,real browser + stub),UI-SPEC U-27 明定保留版位
---

## Problem

API mode 的 Overview 下半部仍渲染 mock 的「觀察清單」與「最新新聞」,沒有 Preview / Simulated 標示,
內容還含寫死的 `$`(例:`NVIDIA closes above $1,140…`)。前端鐵律 6:尚未接後端的功能必須標示 Preview/Simulated。

## Why not in 04.1

UI-SPEC U-27 的格線明定 API mode 仍保留「觀察清單(span 6)+ 新聞(span 6)」版位,且 Watchlist 屬 Phase 04.2。
非本 phase 新造成;Phase 04.1 只處理金額幣別與基準幣。

## Suggested direction

在這兩張卡加 `MockDataNotice`(或卡片層級的 Preview 標籤),API mode 下標示為示範資料;
Watchlist 接真資料時(04.2)再移除。
