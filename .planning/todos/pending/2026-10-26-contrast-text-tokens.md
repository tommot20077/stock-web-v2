---
created: 2026-10-26
title: 文字對比度:--up 綠、橘底白字、--fg-mute 提示字、深色主題 --dn 不足 WCAG AA
area: frontend-design-system
files:
  - ../stock-web-v2-front-end/vue-app/src/styles.css
source: Phase 04.1 UI 審查 Top Priority Fix 3(04.1-UI-REVIEW.md);對比度為由色值推算,未在實際渲染上量測
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
