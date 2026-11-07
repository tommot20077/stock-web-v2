---
created: 2026-10-25
title: 入出金對話框「發生時間被清空」的錯誤文案不貼切,需專屬 i18n 鍵
area: frontend-cash
files:
  - ../stock-web-v2-front-end/vue-app/src/components/CashEntryDialog.vue
  - ../stock-web-v2-front-end/vue-app/src/i18n.ts
source: Phase 04.1 plan 04.1-15 executor 回報(判斷性決定),04.1-25 稽核列為建議待辦
resolved: 2026-11-06
resolved_by: quick-261106-fbs(後端 da4b541..7221c7f、前端 b4ff61e..fbae351;PR 待 orchestrator 開)
---

## Problem

`CashEntryDialog` 的發生時間欄位被使用者清空時,沿用 `cashErrOccurredAt` 文案(語意是「不可晚於現在」),
對「清空」這個情況不貼切。04.1-15 當時不得動 `i18n.ts`(鍵已由 04.1-12 一次加完),所以沿用。

## Suggested direction

在 `i18n.ts` 新增專屬鍵(例如 `cashErrOccurredAtRequired`,zh/en 各一),對話框清空時改用它;
同步更新 `CashEntryDialog.test.ts` 與 UI-SPEC Copywriting 對照。

## Resolution

- 前端 `ca051af`(RED)→ `1eb8b14`(GREEN):`i18n.ts` zh / en 新增 `cashErrOccurredAtRequired`(`請填寫發生時間。` / `Occurred at is required.`);`CashEntryDialog` 的 `fieldErrorText('occurredAt')` 在本地自檢失敗且輸入為空時改用新鍵,本地未來時間與後端 `fields.occurredAt` 仍為 `cashErrOccurredAt`。
- `04.1-UI-SPEC.md` Copywriting 對照已補一列(Task 3 文件掃描)。
- 偏離原建議:無。後續觀察(未處理):`HoldingEventDialog` 清空 `paidAt` 仍沿用「不可晚於現在」文案,且後端新增 1900-01-01 下限後 `fields.paidAt` 文案也不貼切——列在 quick-261106-fbs 報告給 Yuan。
- 證據:`.planning/quick/261106-fbs-small-fixes-batch-a-markets-640px-b-top-/task2-report.md`(2b)。
