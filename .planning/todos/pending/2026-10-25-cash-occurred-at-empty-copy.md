---
created: 2026-10-25
title: 入出金對話框「發生時間被清空」的錯誤文案不貼切,需專屬 i18n 鍵
area: frontend-cash
files:
  - ../stock-web-v2-front-end/vue-app/src/components/CashEntryDialog.vue
  - ../stock-web-v2-front-end/vue-app/src/i18n.ts
source: Phase 04.1 plan 04.1-15 executor 回報(判斷性決定),04.1-25 稽核列為建議待辦
---

## Problem

`CashEntryDialog` 的發生時間欄位被使用者清空時,沿用 `cashErrOccurredAt` 文案(語意是「不可晚於現在」),
對「清空」這個情況不貼切。04.1-15 當時不得動 `i18n.ts`(鍵已由 04.1-12 一次加完),所以沿用。

## Suggested direction

在 `i18n.ts` 新增專屬鍵(例如 `cashErrOccurredAtRequired`,zh/en 各一),對話框清空時改用它;
同步更新 `CashEntryDialog.test.ts` 與 UI-SPEC Copywriting 對照。
