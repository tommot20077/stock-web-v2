---
created: 2026-09-04
title: 前端 app shell header 在 320px 撐破版面,整頁水平滾動
area: frontend-rwd
repo: ../../vue/stock-v2
files:
  - vue-app/src/components/Header.vue
---

## Problem

在 320px 寬度(DevTools 手機模式)下,整份文件的 `scrollWidth` 是 **1070px**,`clientWidth` 320px
—— 頁面會水平滾動。溢位元素全部來自 app shell 的 header:

| 元素 | class / testid | 右緣 | 寬 |
|---|---|---|---|
| 頭像 | `.avatar` | 1070 | 14 |
| 登出鈕 | `[data-testid=header-logout]` | 1063 | 42 |
| 通知鈴 | `.bell-wrap` | 1032 | 36 |
| 鈴鐺 icon 按鈕 | `.icon-btn` | 1032 | 36 |

量測方式(2026-09-04,headless Chromium,API mode dev server @ 5173):

```js
const vw = document.documentElement.clientWidth;           // 320
document.documentElement.scrollWidth;                      // 1070
[...document.querySelectorAll('*')]
  .filter(e => e.getBoundingClientRect().right > vw + 2);  // 上表
```

## 這**不是** Phase 4 的問題

`04-13` Task 2 的步驟 14 要求「320px 下錯誤區塊 code 與追蹤 ID 完整換行,沒有水平滾動」。
逐項確認:

- **錯誤區塊本身合格** —— 寬 244px、高 65px(已換行)、`scrollWidth == clientWidth`,未溢出。
- **水平滾動來自 header**,而 header 不在 Phase 4 的改動範圍(Phase 4 只動 `OrderTicket.vue`、
  三個 portfolio 頁與 `services/`)。屬既有的 RWD 缺口,在 Phase 4 之前就存在。

Yuan 於 2026-09-04 走查時已知悉並接受此判定,Phase 4 不因此卡關。

## Scope

只處理 header 的窄螢幕行為,不動 Phase 4 的任何檔案。

可能做法(未定案,實作時再評估):

- 窄螢幕下把身分區(頭像 + email + 登出)收成單一 icon 或下拉選單。
- 導覽列已經有橫向捲動處理的話,讓右側工具區沿用同一套斷點。
- 給 header 一個 `min-width: 0` + `overflow` 的收斂策略,避免子元素把容器撐開。

## Verification

- 320px 下 `document.documentElement.scrollWidth <= clientWidth`(無水平捲軸)。
- 375px / 768px / 1280px 下 header 版面不退化。
- 登出、通知鈴、身分顯示在窄螢幕仍可操作(不是靠隱藏解決可用性)。
- 前端閘門:`npm test && npm run build`,涉 API mode 加 `VITE_DATA_MODE=api npm test`。
