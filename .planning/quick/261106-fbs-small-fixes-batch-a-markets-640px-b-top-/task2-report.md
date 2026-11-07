---
task: quick-261106-fbs Task 2(前端 repo)
repo: /home/user/stock-web-v2-front-end
branch: claude/epic-curie-lj8alo
status: complete
plan_head_before: b4ff61e2bd8aa03a83db32b8e5569f91cecb7b49
plan_head_after: fbae35148e96dc787679fa67dfabb27b7910b604
commits: 10   # git rev-list --count b4ff61e..HEAD(實測)
actuals:
  tokens: 32500   # chars/4,git diff b4ff61e..HEAD = 130,293 字元
  tasks: 1        # Task 2(子項 2a–2e)
  commits: 10
files_changed: 43   # +1063 / −238
---

# quick-261106-fbs Task 2(前端)報告

A1 / A2(含 2c-addendum)/ A3、B 前端 Top movers、C 前端 info(04.1 IN-02 / IN-03 / IN-04)已全部完成。每個子項都是 RED → GREEN 兩個 commit,RED 一律在斷言上失敗(下方附證據)。後端 repo 只寫了本檔,沒有任何 commit。

## Commits(前端,依時間序)

| 子項 | RED | GREEN |
|---|---|---|
| 2a B Top movers 以基準幣 `unrealizedPnlInBase` 排序 | `7d6e503` | `0fcbca7` |
| 2b A1 清空發生時間的專屬文案 | `ca051af` | `1eb8b14` |
| 2c A2 對比度 token + 2c-addendum | `15b9814` | `f958c2a` |
| 2d A3 Markets < 640px + L12 收緊 + L17 交易單 footer | `af1d22a` | `b311fed` |
| 2e IN-02 / IN-03 / IN-04 | `5202cd8` | `fbae351` |

RED 證據(取自 vitest / playwright 原始輸出,不是 gsd-tools):
- 2a:共 8 個失敗,全部是 `AssertionError`,例如 `expected [ 'ZA','ZE','ZD','ZC' ] to deeply equal [ 'ZB','ZC','ZD','ZA' ]`、`expected '…+$225,000…' not to contain '$'`。
- 2b:7 個失敗。核心兩條是 `expected '發生時間不可晚於現在。' to be 'cashErrOccurredAtRequired'`;另有兩條 TypeError,來源是既有的 PHASE41 迴圈讀到還沒定義的新鍵,屬同一個缺口。
- 2c:17 個失敗,全部是 `AssertionError`(例如 `expected '44px' to be '36px'`、`expected undefined to be 'red'`)。
- 2d:L12 在 320 / 375 × zh / en 共 4 個失敗,L17 4 個失敗,原因都是 `.main` 的 scrollWidth 大於 clientWidth;L12 在 768 / 1280 仍為綠。
- 2e:16 個失敗,全部是 `AssertionError`。T2.8 在 render 時拋出 `Cannot read properties of undefined (reading 'filter')`,這正是 IN-04 要修的錯誤本身。

## 閘門結果(實測)

| 閘門 | 動工前 | 完成後 |
|---|---|---|
| `npx playwright test e2e/tests/layout.spec.ts` | **97 passed** | **101 passed**(新增 L17 共 4 條;L12 已收緊) |
| `npm test`(mock) | 1089 tests(背景執行時見到 1 failed,見下方說明) | **59 files / 1138 passed** |
| `VITE_DATA_MODE=api npm test` | — | **59 files / 1138 passed** |
| 8 個重點測試檔(plan verify 第 1 條) | — | 8 files / 327 passed |
| `npm run build`(含 vue-tsc) | — | exit 0(只有既有的 chunk > 500 kB 警告) |
| `npm run lint` | 0 errors / 40 warnings | **0 errors / 40 warnings**;逐檔與 b4ff61e 比對 warning 數,沒有任何一檔增加 |
| plan 的 grep 閘門 G1–G6 | — | 全部 OK(`tradesSeq` 出現 8 處、`type: 'deferred'` 0 處、`apiClient.ts` 未改動) |
| `mockStoreBoundary.test.ts` / `wsBoundary.test.ts` | 綠 | 綠(包含在 npm test 內) |

基準線說明:動工前背景跑的那次 `npm test` 回報 `1 failed | 1088 passed (1089)`。但那次執行和我開始編輯測試檔的時間重疊,我手上只留了 tail 輸出,無法指出是哪一條。之後在 RED commit 上重跑,只有 8 條預期中的失敗,1088 條其餘測試全綠。所以這比較像當時讀到了編輯到一半的檔案,而不是既有的失敗。後續每次全量執行都是 0 failed。

另外,Playwright 設定是 `retries: 1`。上表最後兩次全量執行都是一次通過,沒有出現 flaky。

## 偏離 plan 之處(皆依 deviation rules 處理)

1. **[Rule 3 — 阻塞] `vue-app/vite.config.ts` 新增 `test.css.include: [/\/src\/styles\.css/]`**(不在 plan 檔案清單內)。原因是 vitest 預設會把 `.css` 匯入(含 `?raw` 與 `import.meta.glob`)換成空字串,實測 `styles.css?raw` 讀到的是 `''`。這導致 `contrastTokens.test.ts` 讀不到全域 token。設定只對 `src/styles.css` 開啟,其他 CSS 維持預設。`viteConfig.test.ts` 綠,全量測試沒有回歸。
2. **[Rule 1 — 錯誤] OrderTicket 在 320px 時記錄交易那一步的 footer 會被撐高。** 這是補上 L17 之後抓到的,而且早於本次變更就存在。原因:面板 `max-width: 92vw` 在 320px 實際只有約 292px,footer 扣掉內距只剩 244px;但「← Back to edit」ghost 約 123px,加 gap 12px 與送出鈕 136px,共需約 271px。結果 ghost 換成兩行,整列被撐到 50–54px。原 CSS 註解以 320px 推算,所以是錯的。修法只動 OrderTicket:`@media (max-width: 400px)` 時面板兩側只留 8px、`.ft` 內距 16px / gap 8px、ghost 內距 8px 12px。這樣可用 270px、需要約 259px,單列、36px、文字不換行。註解也已更正。`min-width: 136px` 保留。
3. **[Rule 2 — 依 2c-addendum 擴及同類] 以 `--dn` 為底色的另外兩處也改成 `--btn-dn-bg` 白字:** `AuthPanel .auth-submit.danger` 與 `SettingsBrokers .banner.warn-banner .banner-icon`。原因:它們的基底規則(accent 底)已改為深色字;若不處理,深色字會落在 `--dn` 上,而深色主題的 `--dn` 改成 #ef4444 後,白字本身也只有 3.76:1。`Chart.vue` 的 `.btn-accent.buy / .sell` 和 OrderTicket 同屬買賣方向鈕,依 addendum 一併處理。
4. **[範圍內補強] Analytics waterfall 的數值標籤(SVG `<text fill>`)** 改用新的 `labelColor`(`--up-text`)。bar 本身的 `fill` 維持 `--up`,與 plan「waterfall bar 維持 --up」一致。
5. **2e GREEN 的 commit type 寫成 `refactor`。** 實際上它同時含 IN-03 / IN-04 兩個行為修正,較精確應是 `fix`。依規定不得 amend,所以只在這裡記錄。
6. **e2e stub `portfolioStubs.ts` 新增 opt-in 的 `assetSearch`(GET /assets)。** 預設關閉,既有測試的頁面狀態不受影響。只有 L17 需要用它開啟交易單並走到審核步驟。

## 2c:`var(--up)` 歸類表

規則是:用作**文字顏色**的改成 `var(--up-text)`;用作底色、邊框、陰影、圓點、圖表填色,或以 `color` 驅動 SVG `currentColor` 的,維持 `var(--up)`。

| 檔案 | 位置 / 選擇器 | 用途 | 結果 |
|---|---|---|---|
| CashEntryDialog / OrderTicket / HoldingEventDialog | 48px 成功打勾圓圈 `background` | 圖示底色 | 維持 `--up`(見觀察 3) |
| OrderTicket | `changeColor()` helper(標的漲跌文字) | 文字 | `--up-text` |
| OrderTicket | `.side-btn.buy.active` 的 color(邊框維持 `--up`) | 文字 | `--up-text` |
| OrderTicket | `.big-side.buy` | 文字 | `--up-text` |
| OrderTicket / Chart | `.btn-accent.buy`(`.sell`) | 按鈕底色 | `--btn-up-bg` / `--btn-dn-bg` + `#fff` |
| SettingsAiAccess | `.chip.ok`、`.chip.live`、`.mcp-tag.read` 的 color(color-mix 底色維持 `--up`) | 文字 | `--up-text` |
| SettingsBrokers | 模板 `✓ connected`、`.chip.live` | 文字 | `--up-text` |
| SettingsDataSources | 模板 `✓ connected`、`.chip.livefeed`、`.chip.ok` | 文字 | `--up-text` |
| StrategyEditor | `.status.ok` | 文字 | `--up-text` |
| LiveQuoteStatus | `.live-dot.solid` | 圓點 | 維持 |
| Positions | 模板 11 處(KPI 值與 delta、時光機差額、損益與 %、mover 金額與 %、產業損益、scrub 比較、mock 與 API 損益欄) | 文字 | `--up-text` |
| Positions | `.day-up` | 文字 | `--up-text` |
| Ops | 模板「Healthy」、`.health .v` | 文字 | `--up-text` |
| Ops | `.ldot`(模板 background)、`.hdot` | 圓點 | 維持 |
| Notifications / Watchlist / Backtest / Analytics | `.up` 類別 | 文字 | `--up-text` |
| Watchlist / Overview / Chart / Markets | `.live-dot` 系列 | 圓點 | 維持 |
| Markets | 漲跌欄(模板)、`.live.on` 的 color、`.tick-up` | 文字 | `--up-text` |
| Markets | `.live.on` 邊框、LIVE 脈動 box-shadow、`flash-up` 底色 | 邊框 / 陰影 / 底色 | 維持 |
| Analytics | 持有天數 `.dot`(模板 background) | 圓點 | 維持 |
| Analytics | waterfall bar `fill` | 圖表填色 | 維持;數值標籤改用 `labelColor` = `--up-text` |
| Analytics | `.metric-delta.good` 的 color、`.num.up` | 文字 | `--up-text` |
| Analytics | `.diversity-bar .fill` 漸層 | 圖表 | 維持 |
| Chart | `.px-chg.up`、`.live-pill` 的 color、`.ob .bid td:first-child`、`.ts tr.b td:nth-child(2)` | 文字 | `--up-text` |
| Backtest / Overview / Trades | `.pill.buy`、`.kind-pill.buy` 的 color | 文字 | `--up-text` |
| Overview | 70×24 sparkline 容器(`color` 驅動 SVG currentColor) | 圖表 | 維持(已加註解) |
| Overview | 觀察清單 % 文字、`toneColor()`(KPI 副標)、`.wl-chg.up` | 文字 | `--up-text` |

上述規則由 `contrastTokens.test.ts` 鎖住:style 區塊不得出現 `color: var(--up)`、模板不得出現靜態 `color:var(--up)`;模板 / script 內的 `'var(--up)'` 只允許白名單三檔共 4 處,每處都附理由。

## 2c:accent 底白字改 `--on-accent`(22 條,與規劃時掃描數一致)

AuthPanel `.auth-submit`;CashEntryDialog、HoldingEventDialog、OrderTicket、StrategyEditor、SettingsNotificationPreferences、Alerts、Backtest、Ops、Overview、Positions、Settings、Trades 的 `.btn-accent`(13 條);Header `.logo`;OrderTicket `.sym-tag`;PrefixIndicator `kbd`;SettingsAiAccess / SettingsBrokers / SettingsDataSources 的 `.banner-icon`;Analytics `.apply-btn`;Markets `.quick-order:hover`;Notifications `.btn-ghost.active`。

## 對比度(由色值推算,WCAG 相對亮度)

- `--up-text` 淺色 #15803d:白底 5.02、`--bg` #fafaf9 上 4.80;深色 #16a34a 在 surface 上 5.44。
- 深色 `--dn` #ef4444 在 surface 上 4.76。
- `--on-accent` #15171c 在 #ff6600 上 6.11(原本白字 2.94)。TweaksPanel 七種可選 accent 配深色字皆 ≥ 4.5,最低為 #a855f7 的 4.53。
- 白字在 `--btn-up-bg` #15803d 上 5.02、在 `--btn-dn-bg` #dc2626 上 4.83。
- 紅漲綠跌:淺色上漲 #dc2626 為 4.83、下跌 #15803d 為 5.02;深色上漲 #ef4444 為 4.76、下跌 #16a34a 為 5.44。

## 給 Yuan 的觀察清單(本 task 未處理)

1. **其他對話框的 `.btn-accent` 高度。** CashEntryDialog 與 HoldingEventDialog 的 `.btn-accent` 仍是 `min-height: 44px` / `padding: 8px 24px`。依 addendum 只改了 OrderTicket(36px / 8px 16px)。是否要統一請你決定。
2. **accent 色文字。** 例如 Markets `.tab.active .tab-count`、`thead th.s`、`.star-btn.on`、`.row:hover .quick-order` 等以 `--accent` #ff6600 當文字色,在白底只有 2.94:1。
3. **成功打勾圓圈。** 白色 ✓ 在 `--up` #16a34a 上為 3.30:1,屬大型圖示,符合非文字的 3:1。但它的語意是「成功」而不是漲跌,紅漲綠跌模式下會變成紅色圓圈。要不要改用獨立的 success token,請你裁定。
4. **`--fg-mute` 提示字。** 淺色 #a1a1aa 白底 2.56、深色 #62605a 為 2.85。仍有多處使用(例如 `.search-icon`、`.tab-count`、Markets 空狀態、waterfall 起點標籤)。這次只依 plan 把 `.quote-lab` / `.qm-l` 改成 `--fg-dim`。
5. **HoldingEventDialog 清空入帳時間(paidAt)時,仍沿用「不可晚於現在」文案。** 情況與 A1 相同,依 plan 不在本 task 修。後端 Task 1 新增 1900-01-01 下限後,`fields.paidAt` 的文案也會不貼切(Task 1 已列為後續觀察)。
6. **Markets tabs 在 < 640px 改為列內水平捲動,且捲軸沒有隱藏。** 這與 Watchlist.vue tabs 的做法一致;全域 `::-webkit-scrollbar` 規則會讓桌面 Chromium 在溢出時顯示 10px 捲軸。若希望窄螢幕隱藏捲軸,需要你決定。
7. **OrderTicket 在 ≤ 400px 時面板改為 `calc(100vw - 16px)`。** 這是偏離 2 的副作用:交易單在窄螢幕比原本寬約 12px,兩側各留 8px。
8. **深色主題 `--dn` 已淺化,但 `--neg: var(--dn)` 會跟著變。** 目前有 8 處使用 `--neg`,都是「負值」文字語意,方向一致;紅漲綠跌時 `--neg` 也會變綠,這與改動前 inline 寫法的行為相同。

## Known Stubs

無。RED commit 中的 `cashBalanceView.ts` / `baseCurrencyErrorKey.ts` 骨架已在 GREEN commit `fbae351` 補上完整實作。

## Threat Flags

無新增的信任邊界。沒有新增 `fetch`、沒有改動 `apiClient.ts`、token 儲存不變、元件沒有 import mock store。新增的 e2e stub 只存在於測試。

## 未執行 / 未涵蓋

- 沒有更新 STATE.md / ROADMAP.md / REQUIREMENTS.md。這些在後端 repo,另一個 executor 正在並行 commit,且依指示不得在後端 repo commit,交由 orchestrator 的 quick workflow 處理。
- 沒有寫 `261106-fbs-SUMMARY.md`(orchestrator 負責);本檔即 Task 2 段落的素材。
- 前端 docs / AGENTS.md 屬 Task 3,未動。

## Self-Check: PASSED

- 10 個 commit 皆為 HEAD 的祖先(`git log b4ff61e..HEAD` 列出 10 筆)。
- 新檔皆存在:`vue-app/src/contrastTokens.test.ts`、`cashBalanceView.ts`、`cashBalanceView.test.ts`、`baseCurrencyErrorKey.ts`、`baseCurrencyErrorKey.test.ts`。
- 前端工作樹乾淨(`git status --short` 無輸出)。
