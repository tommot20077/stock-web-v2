---
created: 2026-10-26
title: 文字對比度:--up 綠、橘底白字、--fg-mute 提示字、深色主題 --dn 不足 WCAG AA
area: frontend-design-system
files:
  - ../stock-web-v2-front-end/vue-app/src/styles.css
source: Phase 04.1 UI 審查 Top Priority Fix 3(04.1-UI-REVIEW.md);對比度為由色值推算,未在實際渲染上量測
resolved: 2026-11-06
resolved_by: quick-261106-fbs(後端 da4b541..7221c7f、前端 b4ff61e..fbae351;PR 待 orchestrator 開)
---

## Problem

- `--up`(#16a34a)在白底約 3.3:1,用於 12–13px 的報酬率與損益文字,低於一般文字的 4.5:1。
- 白字在 `--accent`(#ff6600)上約 2.9:1,影響 13px/600 的送出按鈕。
- `.quote-lab` 使用 `--fg-mute`,UI-SPEC 明定提示字不得用它。
- 深色主題 `--dn` 約 3.7:1。

這是整個設計系統的 token,**不是 Phase 04.1 引入**,改動會影響每個頁面,故未在 04.1 處理。

## Suggested direction

新增文字用 token(例如 `--up-text: #15803d` 約 5.0:1;按鈕改用較深的強調色或深色字),`.quote-lab` 改 `--fg-dim`,深色主題淺化 `--dn`;
先用實際渲染 + 對比度工具量過再改,並跑全頁視覺回歸。

## Resolution

- 前端 `15b9814`(RED)→ `f958c2a`(GREEN),採 Yuan 2026-11-06 於對比度樣式頁裁定的值:
  - `:root` 新增 `--up-text: #15803d`、`--on-accent: #15171c`;深色主題 `--up-text: #16a34a`、`--dn: #ef4444`;新增紅漲綠跌區塊 `:root[data-up-color="red"]`(淺 / 深)。
  - `useTweaks.ts` 改以 `documentElement.dataset.upColor` 切換,不再寫 inline `--up` / `--dn`(根因:inline 優先權壓過深色主題 token)。
  - 文字用途的 `var(--up)` 改 `var(--up-text)`;22 條 accent 底白字改 `var(--on-accent)`;`.quote-lab` / `.qm-l` 改 `--fg-dim`。
  - 2c-addendum:買賣方向鈕改用 `--btn-up-bg: #15803d` / `--btn-dn-bg: #dc2626` 白字(紅漲模式一同對調);OrderTicket「記錄交易」鈕 36px / 8px 16px。
  - `contrastTokens.test.ts` 以原始碼掃描鎖住上述規則;layout.spec 97 → 101 全綠。
- 偏離原建議:未做「實際渲染量測」,對比度仍由色值推算;`--fg-mute` 提示字、accent 色文字、成功打勾圓圈等未處理,列在 task2-report 觀察清單給 Yuan。
- 證據:`.planning/quick/261106-fbs-small-fixes-batch-a-markets-640px-b-top-/task2-report.md`(2c 歸類表與對比度數值)。
