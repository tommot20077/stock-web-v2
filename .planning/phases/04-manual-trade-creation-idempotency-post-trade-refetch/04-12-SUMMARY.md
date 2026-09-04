---
phase: 04-manual-trade-creation-idempotency-post-trade-refetch
plan: 12
subsystem: frontend
tags: [post-trade-refetch, stale-data, a11y, fresh-highlight, vue, vitest, dp-10-test-reversal]

# Dependency graph
requires:
  - phase: 04-manual-trade-creation-idempotency-post-trade-refetch
    provides: 04-07 的 portfolioRevision / apiLastFill / lastCreatedTradeId 三個訊號與 notifyTradeCreated、04-08 的四條 refetch 文案 key、04-11 送出成功後真的會呼叫 notifyTradeCreated
  - phase: 03-portfolio-read-api-mode
    provides: 三頁的區塊狀態機(loading | loaded | error)、診斷列形狀、既有 load 函式與 applyQueryChange 單一入口
provides:
  - "D-10:三頁各自 watch(portfolioRevision) 重讀自己的資料源,未掛載的頁不被觸發"
  - "U-05:與 status 並存的 refreshing 旗標 —— 重讀不清空表格"
  - "U-06 / D-12:重讀失敗時舊資料留存 + portfolioStaleAfterTrade + 診斷列 + 重試,且不進 status:'error'"
  - "D-11:Trades 走既有單一入口重讀(保留篩選排序、頁碼歸零)與「不在檢視範圍」提示(只比 id)"
  - "D-13 / U-12:兩頁 API mode 的 fresh 高亮與非顏色線索的「新」標記"
affects: [04-13 跨 repo 收尾驗證, Phase 5 真實後端與瀏覽器視覺驗收]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "重讀用一組**與既有狀態機並存**的旗標,而不是把 refetch 塞進原本的 loading —— 舊值留存是 D-12 的前提"
    - "重讀失敗不進 error 態:error 態的語意是「沒有可顯示的資料」,而 stale 的語意是「有資料但可能過期」"
    - "跨元件的 D-12 驗收:同一個測試同時掛 OrderTicket 與 Positions,才證明得了「兩件事分開呈現」"
    - "CSS 的版面契約(列高 / 寬度)在 jsdom 測不到,一律用 ?raw 原始碼斷言補(04-11 的 min-width 手法第二次使用)"

key-files:
  created: []
  modified:
    - "[FE] src/pages/Overview.vue"
    - "[FE] src/pages/Overview.test.ts"
    - "[FE] src/pages/Positions.vue"
    - "[FE] src/pages/Positions.test.ts"
    - "[FE] src/pages/Trades.vue"
    - "[FE] src/pages/Trades.test.ts"

key-decisions:
  - "refresh 用獨立的 refreshXxx() 函式而不是給 loadXxx() 加參數:既有的 @click=\"loadSummary\" 會把 MouseEvent 當第一個參數傳進去,布林旗標會被 truthy 的事件物件觸發"
  - "refreshXxx() 在區塊不是 loaded 態時退回一般載入:沒有舊值就沒有 U-05 要保護的東西,硬走 refresh 只會讓 error 與 stale 兩條訊息同時出現"
  - "「不在檢視範圍」用 computed 而不是 ref:清除時機(clearLastCreatedTrade)自動生效,不需要在四個地方各記得清一次;重讀期間用 !tradesRefreshing 擋住閃現"
  - "fresh 標記在 mock 與 API 兩條路徑都加:U-12 的 a11y 要求與 mode 無關"
  - "App.vue 一個位元組都沒改(plan 的 files_modified 有列,但 action D 的前提「確實需要時」不成立)"

patterns-established:
  - "既有函式若已被 @click 直接綁定,就不能再加位置參數 —— 事件物件會變成那個參數"

requirements-completed: [TRAD-05, TRAD-06]

# Metrics
duration: 約 75min
completed: 2026-08-16
---

# Phase 04 Plan 12: 三頁 post-trade refetch、部分失敗分開呈現與 fresh 高亮

**交易成功後,已掛載的 portfolio 頁自己重讀自己的資料:重讀期間舊值留在畫面上、失敗時舊值仍留存但明示「可能不是最新」、ticket 的成功畫面完全不受影響 —— 31 條新測試鎖住,含一條同時掛載 OrderTicket 與 Positions 的跨元件驗收。**

## Performance

- **Duration:** 約 75 min
- **Tasks:** 2/2(各一組 RED → GREEN)+ 1 條自查後補的偏離修正
- **Files modified:** 6(皆在前端 repo,即 plan 的 `files_modified` 扣掉未動的 `App.vue`)

## Accomplishments

- **D-12 的核心主張有一條真正的跨元件測試。** Test 10 在同一個測試裡掛載 `Positions` 與 `OrderTicket`,走完真實送出流程(review → submit → 後端 200 → `notifyTradeCreated`),同時讓 `/portfolio/summary` 的**重讀**回 503。結論是可執行的:`ticket-result-trade-id` 顯示完整 UUID、`ticket-error` 為 null、ticket 沒被關閉,而 stale 提示只出現在頁面上。這正是「使用者以為交易沒成功 → 再送一次 → 換了 key → 真的建出第二筆」那條路徑的防線。
- **U-05 的「不得清空表格」不是靠人眼,是靠 pending promise。** 三頁的 Test 6/7 都用一個未 resolve 的 `deferred<Response>()` 卡住重讀,在那個時間點斷言:舊列**仍在 DOM**、`{page}-loading` 為 null、`.skeleton-row` 為 null、`{page}-refreshing` 出現、區塊 `aria-busy="true"`。resolve 之後才換成新值。若有人日後把 refetch 改回重用 `status: 'loading'`,這三條會立刻紅。
- **D-10 的架構論證被寫成測試。** Test 5 只掛載 `Positions`,bump 之後斷言 summary / holdings 各 +1 而 `/trades` **零次**。這條把 CONTEXT.md 那段「未掛載的頁沒有消費者,代它發請求是純粹的無效工」變成可執行的規格。
- **D-12 的「各自獨立」有分開的證據。** Test 9 讓同一次 bump 中 summary 回 503、holdings 回 200:彙總條保留**舊的** `totalMarketValue`(4242)並顯示 stale,持倉表**同時**換成新資料。兩個資料源共用一組旗標的實作過不了這條。
- **D-11 只比 id 被 `?raw` 鎖死。** Test 15 從原始碼切出 `inResultSet` 的 computed 區塊,對 `activeFilter` / `sortKey` / `sortDir` / `dateFrom` / `dateTo` / `filterParams` 六個字面各一條 `not.toContain`,同時正向斷言它含 `lastCreatedTradeId` 與 `.id ===`(避免「把功能刪掉也能過」的假綠)。
- **不得複製第二條重置邏輯也被鎖死。** Test 12 切出 `watch(portfolioRevision` 區塊,斷言它含 `applyQueryChange`、**不含** `pageNo.value = 0`、**不含** `loadTrades(`。
- **DP-10 的測試反轉留下了可追溯的紀錄**(詳見下方專節):`git show f381188` 可見同一個 `it(` 被改寫而非刪除新增。
- **零新增依賴、零 i18n 新 key**(四條文案全部是 04-08 已建立的 `portfolioRefreshing` / `portfolioStaleAfterTrade` / `tradeNotInCurrentView` / `freshBadge`,重試與追蹤 ID 沿用 `authRetry` / `authRequestId`)、**`testSetup.ts` / `i18n.ts` / `App.vue` 一個位元組都沒動**。

## Task Commits

前端 repo `D:\end\workspace\vue\stock-v2`,分支 `feature/phase-04-manual-trade-creation`:

1. **Task 1:三頁 watch revision + refreshing 旗標(D-10 / D-12)**
   - `2b01407` (test) — RED:15 條
   - `6cb521c` (feat) — GREEN
2. **Task 2:D-11 重讀規則與提示 + D-13 fresh 高亮(含 DP-10 測試反轉)**
   - `f381188` (test) — RED:16 條新測試 + 1 條既有測試反轉
   - `4d71b13` (feat) — GREEN
3. **偏離修正(自查後補)**
   - `51711b0` (fix) — 「新」標記撐高列高(見 Deviations)

本 SUMMARY 提交在後端 repo `feature/phase-04-trade-idempotency` 分支。**兩個 repo 都未 push,也未開 PR。**

## Files Modified

| 檔案 | 變更 |
|------|------|
| `[FE] src/pages/Overview.vue` | `summaryRefreshing` / `summaryRefreshError` / `tradesRefreshing` / `tradesRefreshError` 四個旗標、`refreshSummary` / `refreshRecentTrades`、`watch(portfolioRevision)`、KPI 區上方的 refresh strip、近期交易卡內的指示列、`.block-refreshing` 與 stale 樣式 |
| `[FE] src/pages/Positions.vue` | 同形的四個旗標與兩個 refresh 函式、`watch`、彙總條上方 strip、holdings 卡內指示列、`effectiveLastFill`、兩條 tbody 的 fresh class 與 `positions-fresh-badge`、`@media (prefers-reduced-motion: reduce)` |
| `[FE] src/pages/Trades.vue` | `loadTrades(options)` 支援 refresh 模式(含 D-15 溢出回退的遞迴)、`applyQueryChange(mutate, options)`、`retryRefresh`、`watch` 走空 mutate、`inResultSet` / `notInCurrentView`、`clearLastCreatedTrade` 的三個清除點、`effectiveLastFill`、兩條 tbody 的 fresh class 與 `trades-fresh-badge`、reduced-motion |
| `[FE] src/pages/Overview.test.ts` | 15 → **19** 條(+4) |
| `[FE] src/pages/Positions.test.ts` | 20 → **33** 條(+13,另有 1 條既有測試反轉) |
| `[FE] src/pages/Trades.test.ts` | 24 → **38** 條(+14) |

全套 338 → **369**(+31)。

## plan output 要求記錄的四件事

### 1. DP-10 測試反轉的完整交代

**被反轉的測試:** `[FE] src/pages/Positions.test.ts`,Phase 3 commit `587e84e` 建立。

| | 反轉前 | 反轉後 |
|---|---|---|
| 測試名 | `API mode 的持倉列不帶 lastFill 高亮(無成交事件來源,Phase 4 才接 post-trade refetch)` | `API mode 持倉列依 apiLastFill 帶 fresh 高亮(Phase 4 D-13,反轉 Phase 3 的鎖定)` |
| 斷言 | 掛載後 `rows().filter(r => r.classList.contains('fresh'))` 長度為 **0**,結束 | 掛載後仍為 **0**(前提不變);`notifyTradeCreated(freshTrade('BBB'))` 之後 BBB 那一列 `fresh` 為 **true**,且全表恰 **1** 列高亮 |

**為什麼這是「測試意圖改變」而不是「改測試遷就實作」(judgment §10 的合法例外):**

Phase 3 的那條測試鎖的不是一個永恆真理,而是**當時的事實**:API mode 沒有任何成交事件來源,所以「綁 fresh class」在當時只可能是憑空發明的高亮。它的測試名與 Phase 3 留在 `Positions.vue` 的 TODO 註解(「Phase 4 引入 post-trade refetch 時再接」)逐字互相印證 —— **Phase 3 自己就寫明了這是暫時狀態**。

D-13 是 Yuan 在 Phase 4 CONTEXT 裡的**新決策**:成交後既然有了 `apiLastFill`,剛成交的那一列就該看得出來。判準因此不是「實作做不到,所以改測試」,而是「使用者要的東西變了,原測試鎖住的行為已經不是需求」。

**處置(四項全部執行):**
1. 改寫斷言(不是刪除)——`git show f381188 -- vue-app/src/pages/Positions.test.ts` 可見同一個 `it(` 一減一增,不是「刪一條 + 另處新增一條」。
2. 測試名更新並含 `D-13` 字樣。
3. 測試上方留了一段繁中註解,寫明「Phase 3 刻意鎖住不高亮 / D-13 讓測試意圖本身改變 / 因此反轉而非刪除」,並記下**反轉前的原名**,好讓後人 grep 得到。
4. 本節即 SUMMARY 的交代。

**額外保留的一半:** 反轉後的測試**第一段仍然斷言「沒有成交事件時一列都不高亮」**。Phase 3 真正想防的「憑空高亮」在新測試裡沒有被放掉,只是加上了「有成交事件時才高亮」的另一半。

### 2. `refreshing` 為什麼與 `loading` 並存,而不是重用它

Phase 3 的區塊狀態機是 `loading | loaded | error`,而 `loading` 的視覺後果是**把表格換成骨架列**(`Positions.vue` 的 `.skeleton-row` × 6)或換成 loading 文字。

重用它會產生這個序列:**使用者按下記錄交易 → 成功 → 整頁資料瞬間消失變骨架 → 一秒後長回來。** 「資料消失」在使用者的心智模型裡幾乎一定是「出事了」,而這正是 D-12 明文要防的最糟失敗模式的觸發條件:以為交易沒成功 → 重開 ticket(換了新 key,D-14)或改了數量 → **真的**建出第二筆,而 `transactions` 是 append-only(V8 trigger 禁 UPDATE/DELETE),改不回來。

所以重讀走一組**並存**的旗標:

| 情境 | 狀態 | 畫面 |
|---|---|---|
| 首次載入 | `status: 'loading'` | 骨架列 + 「載入中…」(**完全沒改**) |
| revision 觸發的重讀 | `status: 'loaded'` + `refreshing: true` | 舊值留存、`.block-refreshing`(opacity .72)、12px 的「更新中…」、`aria-busy="true"` |
| 重讀成功 | `refreshing: false` | 新值直接替換 |
| 重讀失敗 | `refreshing: false` + `refreshError` | **舊值留存** + `portfolioStaleAfterTrade` + code/traceId + 重試鈕;**不進** `status: 'error'` |

最後一列同樣重要:`status: 'error'` 的語意是「**沒有可顯示的資料**」,而重讀失敗的語意是「**有資料,但可能過期**」。把後者塞進前者會清掉舊值,等於用另一種方式做出「資料消失」。

**兩個實作細節值得留給後續 plan:**

- **`refreshXxx()` 是獨立函式,不是給 `loadXxx()` 加參數。** 因為 `loadSummary` / `loadHoldings` / `loadRecentTrades` 都已經被 `@click="loadSummary"` 直接綁在重試鈕上 —— 加一個布林參數會讓 MouseEvent 變成那個參數(truthy),重試鈕就會意外走進 refresh 路徑。`Trades.vue` 沒有這個問題(它的重試鈕綁的是包一層的 `reloadTrades`),所以那邊才用得起 options 物件。
- **`refreshXxx()` 在區塊不是 `loaded` 態時退回一般載入。** 沒有舊值就沒有 U-05 要保護的東西;硬走 refresh 只會讓 `status: 'error'` 的錯誤區塊與 stale 提示同時出現,對使用者是兩個問題而不是一個。

### 3. D-11 為什麼傳空 mutate 給 `applyQueryChange`,而不是複製兩行

`Trades.vue` 的 `applyQueryChange(mutate)` 的 javadoc 從 Phase 3 就宣示了「**任何**篩選或排序變更都經此入口,頁碼一律重置為 0 後再請求」。D-11 要的三件事(保留 `activeFilter`、保留 `sortKey`/`sortDir`、頁碼歸零後重新請求)**逐字就是那個入口已經在做的事** —— 差別只在「這次沒有要 mutate 任何東西」。

複製 `pageNo.value = 0; void loadTrades();` 會產生第二份重置邏輯。兩份實作日後必然漂移:任何人在入口加一條新規則(例如「重置時也清掉某個提示」——本 plan 就真的加了 `clearLastCreatedTrade()`)都得記得改兩個地方,而漏掉的那次不會有任何東西提醒他。

因此:
```
watch(portfolioRevision, () => {
  if (live) return;
  applyQueryChange(() => {}, { refresh: true });
});
```
並用 Test 12 的 `?raw` 斷言把「不得出現第二條重置邏輯」機械化(watch 區塊不含 `pageNo.value = 0`、不含 `loadTrades(`)。

**`{ refresh: true }` 是本 plan 對那個入口唯一的擴充**,而且它同時被用來表達一條 D-11 的規則:`applyQueryChange` 在**非** refresh 路徑才呼叫 `clearLastCreatedTrade()`。理由是重讀本身正是提示的產生來源 —— 在重讀前先清掉 id,就永遠比不到「這筆在不在結果集內」。

### 4. 人工檢視項目的實際結果(誠實揭露:**我沒有開過瀏覽器**)

plan 的 `<verification>` 列了三項人工檢視。**本次執行環境沒有瀏覽器,以下是程式碼層與 CSS 盒模型層的檢視,不是視覺確認。**

| 項目 | 我實際做了什麼 | 結論 |
|------|--------------|------|
| **refresh 指示是否造成版面高度跳動** | 逐項核對 §Layout Contract 的三條:(a)**不新增卡片** —— diff 中沒有任何新的 `.card`,指示列是 `.refresh-note` / `.refresh-stale` 純文字列;(b)**不改 grid span** —— diff 中沒有任何既有元素的 `grid-column` 被更動;(c)**不得造成版面高度跳動** —— **未達成** | **(a)(b) 通過,(c) 未達成,見下方說明** |
| **fresh 動畫與新值替換是否互相打架** | 兩者的時間軸實際上是**接續**而不是重疊:`refreshing` 在資料落地的同一個 tick 轉 false,`.block-refreshing` 的 `opacity .15s` 開始回到 1;而 `highlight` 1.6s 動畫由**新掛載的那一列**觸發。前 0.15s 兩者確實同時進行(高亮列在 .72 → 1 的淡入中),但作用的是不同屬性(opacity vs background),不會互相覆寫。另外 `Trades.vue` 的 `tbody tr { transition: background .4s }` 是 Phase 3 既有物,animation 執行期間優先於 transition | 程式碼層**無衝突**;「觀感上會不會覺得閃」屬視覺判斷,**未經人眼確認** |
| **「新」pill 是否改變列高** | **實際發現缺口並修正**(見 Deviations #1):原本的 `padding: 2px 8px` 讓 12px 標記的外框(14.4 + 4 = 18.4px)高於 Positions 列文字 13px 的行高(約 15.6px),會把列撐高約 3px。已改為 `padding: 0 8px` + `line-height: 1.2`(= 14.4px),完整落在既有 line box 內 | **缺口已修**;數字為盒模型推算,**未在瀏覽器量測** |

**關於 (c) 版面高度跳動 —— 誠實說明未達成的原因與已評估的替代方案:**

我的實作把「更新中…」做成**流內**的 12px 說明列,所以它出現/消失時,下方內容會位移約 28px。這確實不符合 §Layout Contract 那一句。我評估過兩個替代方案並都放棄:

1. **絕對定位浮層**(`.card { position: relative }` + 指示列 `position: absolute; top/right`):三個資料源中有兩個(Positions holdings、Trades 列表)的卡片內容從頂端就是 `<thead>`,浮層必然壓在「Weight」/「Notes」表頭文字上,把一個版面問題換成一個可讀性問題。
2. **永久保留版位**(指示列常駐、以 `min-height` 佔位):非重讀期間會在每張卡片頂端留一條永久空白,代價比偶發的位移大。

真正的解法需要在瀏覽器裡量測後決定(例如把指示列放進表頭列的空白處),**屬 Phase 5 / VER-03 的視覺驗收範圍**。本 plan 不宣稱這一項已完成。

## Decisions Made

### 1. `notInCurrentView` 用 computed,不用「載入完成後設一次的 ref」

plan 的 behavior 寫「refetch 完成後比對」,字面做法是在每次成功載入後設一個 ref。實作改為 computed(`lastCreatedTradeId` × 當前 `items`),理由:

- 清除時機有三個入口(chip、表頭排序、換頁),`clearLastCreatedTrade()` 一呼叫,computed 立刻變 false。用 ref 就得在三個地方各記得再設一次,而漏掉的那次不會有測試自動抓到。
- 提示的真正語意就是「當前顯示的這一頁裡有沒有那筆」,computed 是它的直譯。

代價是「重讀還沒回來之前,舊 items 也不含新 id」會讓提示閃現一瞬。用 `!tradesRefreshing.value` 擋掉 —— 這也是為什麼 Test 13/14 都在 `flushAsync()` 之後才斷言。

### 2. fresh 標記在 mock 與 API 兩條路徑都加

U-12 的理由(色盲、高對比、動畫已結束)與資料來源無關,所以兩條 tbody 都加。連帶好處是 Test 20 的「來源切換」變成真的在測切換:mock mode 下 `notifyTradeCreated` 設好了 `apiLastFill`,但畫面**沒有**任何 fresh 列也沒有標記 —— 若有人把 `effectiveLastFill` 寫成 `live.lastFill ?? apiLastFill.value`(看起來更「安全」的寫法),這條會立刻紅。

### 3. 沒有動 `App.vue`

plan 的 `files_modified` 列了它,但 action D 的前提是「只在確實需要時調整 overlay props(例如把 `lang` 傳給新的 refresh 文案)」。三個頁面本來就各自收 `lang` prop,四條文案全部在頁面內部 `t(lang, ...)`,沒有任何東西需要從 `App.vue` 傳下來。action D 的另一半(**不得**在 `App.vue` 加「refetch 失敗 → 全域錯誤」的邏輯)因此也自動成立。

### 4. 區塊級 `aria-busy` 掛在「持有資料的那個元素」上

`aria-busy` 掛在卡片(Trades 列表卡、Positions holdings 卡、Overview 近期交易卡)與六張彙總卡上,**不掛**在 `role="status"` 的提示節點上 —— 在 live region 上設 `aria-busy="true"` 的語意是「先別播報」,與我們要的「這塊資料正在更新」正好相反。

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - 漏做 plan 明文要求] 「新」標記會撐高列高**

- **Found during:** Task 2 完成後對 §Layout Contract 的自查
- **Issue:** UI-SPEC §Layout Contract 明文「`新` 標記:表格列內 inline pill,**不改列高**」。我第一版沿用 `Trades.vue` 既有 `.pill` 的 `padding: 2px 8px`,但 12px 字 × line-height 1.2 = 14.4px,加上下各 2px 內距 = 18.4px,而 Positions 列文字(13px)的行高約 15.6px → **列會被撐高約 3px**。Trades 因為同列已有 11px 的 type pill(17.2px)撐著,只會多 1.2px,但一樣不是 0。
- **Fix:** 改為 `padding: 0 8px; line-height: 1.2`(= 14.4px),小於兩頁的既有行高,inline-block 完整落在既有 line box 內。視覺上仍是有底色的圓角小標,只是不再自己撐高度。
- **TDD:** 先在 Test 19(Positions)與 Test 18/19(Trades)各加兩條原始碼斷言(`padding: 0 8px`、`line-height: 1.2`),**實測轉紅**(`expected '<template>…' to match /\.fresh-badge\s*\{[^}]*padding:\s*0 8…/`)後才改 CSS。jsdom 不套用 scoped CSS 也算不出高度,所以用原始碼斷言而非 `getComputedStyle` —— 與 04-11 的 `min-width` 是同一手法。
- **Committed in:** `51711b0`

**2. [Rule 3 - 既有 API 形狀的限制] `loadXxx()` 不能加位置參數**

- **Found during:** Task 1 實作
- **計畫原文:** 「把 load 函式改成接受一個 `refresh` 選項」
- **Issue:** `Overview.vue` 與 `Positions.vue` 的三個 load 函式**已經**被 `@click="loadSummary"` / `@click="loadHoldings"` / `@click="loadRecentTrades"` 直接綁在 Phase 3 的重試鈕上。加一個 `refresh = false` 的位置參數之後,重試鈕會把 **MouseEvent** 當成那個參數傳進去(truthy)→ 重試會走進 refresh 路徑,在 `status: 'error'` 的區塊上設 stale 旗標,產生兩條錯誤訊息。改成 options 物件雖然不會誤觸(`event.refresh` 是 undefined),但那是「靠一個巧合安全」的設計。
- **Fix:** 改為獨立的 `refreshSummary()` / `refreshHoldings()` / `refreshRecentTrades()`,零參數,可以安全地直接綁在 stale 提示的重試鈕上。`Trades.vue` 的重試鈕綁的是包一層的 `reloadTrades`,沒有這個限制,所以那邊才用 options 物件擴充 `loadTrades`(它還要同時承載 D-15 溢出回退的 `allowOverflowFallback`)。
- **Verification:** 三頁的 Test 8 都包含「點重試 → 再送一次請求 → stale 提示消失」,若誤觸路徑存在會失敗。
- **Committed in:** `6cb521c`

---

**Total deviations:** 2 auto-fixed(1 × Rule 2 漏做 plan 明文要求 / 1 × Rule 3 既有 API 形狀限制)
**Impact on plan:** 無範圍蔓延。`git diff --name-only e8d1f35 HEAD` 確認五個 commit 合計只動 6 個檔案,全部是 plan 的 `files_modified` 所列(`App.vue` 未動,理由見 Decisions #3);未觸碰任何後端原始碼、未動 `testSetup.ts`、未動 `i18n.ts`、未新增任何依賴。

## TDD 誠實記錄(CLAUDE.md 硬約束)

**我親眼看到的 RED:**

| 階段 | RED 指令輸出 | 紅 / 綠 |
|------|-------------|--------|
| Task 1 | `VITE_DATA_MODE=api npx vitest run src/pages/{Overview,Positions,Trades}.test.ts` → `12 failed \| 62 passed (74)` | **12 紅** / 3 綠(共 15 條新測試) |
| Task 2 | `VITE_DATA_MODE=api npx vitest run src/pages/{Trades,Positions}.test.ts` → `10 failed \| 61 passed (71)` | **10 紅** / 6 綠(共 16 條新測試 + 1 條反轉) |
| 列高修正 | 同上 `-t "fresh 高亮"` → `2 failed \| 11 passed \| 58 skipped` | **2 紅**(擴充既有測試) |

**一寫就綠的測試,逐條說明(不假稱經歷過 RED):**

| 測試 | 為什麼一寫就綠 |
|------|--------------|
| Test 4 ×3(mock mode 零網路) | 實作前三頁根本沒有 watch,不可能發請求 —— **這是典型的假綠**。它的真實價值在 GREEN **之後**:若有人把 `if (live) return;` 拿掉,三條會同時紅。我保留它並在此明確標示 |
| Test 11(重讀保留篩選排序、頁碼歸零) | Task 1 的 GREEN 已經讓 watch 走 `applyQueryChange`,所以 Task 2 的 RED 階段它就是綠的。真正證明這條行為的是 Task 1 —— 我把斷言留在 Task 2 是因為 D-11 的驗收語意屬於這裡 |
| Test 12(watch 不含第二條重置邏輯) | 同上,Task 1 就已寫成這個形狀。防迴歸性質 |
| Test 13(新交易在結果集內時不顯示提示) | RED 階段連提示節點都不存在,`toBeNull()` 必然成立 —— **也是假綠**。它在 Test 14 通過後才有意義(兩條互為對照:同一段實作,一條要求出現、一條要求不出現) |
| Test 20 ×2(mock 不看 apiLastFill) | mock 路徑本來就只讀 `live.lastFill`。防迴歸性質,但它擋的是一個很容易犯的錯(把來源寫成 `live.lastFill ?? apiLastFill.value`) |
| Test 21 ×2(不含 `setTimeout`) | 兩頁本來就沒有計時器。純防迴歸 |

**沒有任何「先寫實作再補測試」的情況;每個 task 的 `test(...)` commit 都在 `feat(...)` 之前**(`git log --oneline` 可核對)。

## 驗收指令與結果(全部為我實際執行的輸出)

| 指令 | 結果 |
|------|------|
| `VITE_DATA_MODE=api npx vitest run src/pages/` | **91/91 綠**(4 個 page 測試檔) |
| `npx vitest run src/pages/`(mock mode) | **91/91 綠** |
| `npm test`(mock mode) | 35 files / **369 tests** 全綠(基準線 338 → +31) |
| `VITE_DATA_MODE=api npm test` | 35 files / **369 tests** 全綠 |
| `npm run build`(`vue-tsc --noEmit && vite build`) | exit 0 |
| `git status --porcelain`(前端 repo) | 空(工作樹乾淨) |

### acceptance criteria 逐條對帳(`grep` 實測)

**Task 1**

| 條件 | 實測 |
|------|------|
| 三個 `.vue` 各含 `watch(portfolioRevision` | 各 1 ✅ |
| 三個 `.vue` 各含 `if (live) return` | Overview 2 / Positions 2 / Trades 3(含既有的 `onMounted`)✅ |
| `{page}-refreshing` / `-refresh-error` / `-refresh-error-code` / `-refresh-trace-id` / `-refresh-retry` | 三頁 15 個 testid 全在 ✅ |
| 三個 `.vue` 各含 `role="status"` 用於 stale 節點 | Overview 3 / Positions 3 / Trades 1(Overview 與 Positions 有兩個資料源各一個,另加一個既有節點)✅ |
| 三個 `.vue` **不含** `error.message` | 各 0 ✅ |
| 三個測試檔各含 `resetPortfolioRevisionForTests` | 各 1(在各自的 `afterEach`)✅ |
| `[FE] src/testSetup.ts` **未被修改** | 不在 `git diff --name-only e8d1f35 HEAD` ✅ |
| `VITE_DATA_MODE=api npx vitest run src/pages/*.test.ts` exit 0 | ✅ |
| `npx vitest run src/pages/*.test.ts` exit 0(mock mode) | ✅ |

**Task 2**

| 條件 | 實測 |
|------|------|
| `Trades.vue` 含 `applyQueryChange`,且 watch 區塊**不含** `pageNo.value = 0` | Test 12 機械驗證 ✅ |
| `Trades.vue` 含 `trades-not-in-current-view` 與 `lastCreatedTradeId` | 各 1 ✅ |
| 兩頁各含 `effectiveLastFill` 與 `apiLastFill` | ✅ |
| 兩頁各含 `positions-fresh-badge` / `trades-fresh-badge` | 各 1 ✅ |
| 兩頁的 fresh 相關程式碼**不含** `setTimeout` | **整檔**各 0 ✅ |
| 兩頁 CSS 含 `@media (prefers-reduced-motion: reduce)` 且其中取消 `highlight` 動畫 | Test 22 的正則機械驗證 ✅ |
| `grep -n "Phase 4 引入 post-trade refetch 時再接\|Phase 4 接 post-trade refetch"` **無輸出** | rc=1,零命中 ✅ |
| `mockLastFill` 的直接引用已移除 | 三頁合計 0 命中 ✅ |
| `Positions.test.ts` 含反轉後的測試名(含 `D-13`)與說明註解;`git log` 可證明是**改寫** | `git show f381188` 同一 `it(` 一減一增 ✅ |
| `npm test` / `VITE_DATA_MODE=api npm test` / `npm run build` | 全部 exit 0 ✅ |

## must_haves 對帳

| truth | 證據 |
|------|------|
| 交易成功後,當下有掛載的 portfolio 頁會重新讀取自己的資料並顯示後端真相 | Test 1 / 2 / 3(三頁各自 +1 請求)、Test 6/7(新值替換舊值) |
| 重讀期間畫面保留舊資料並顯示「更新中…」,不會把表格清空成骨架 | Test 6/7 ×3(pending promise 期間斷言舊列仍在、`{page}-loading` 為 null、`.skeleton-row` 為 null) |
| 重讀失敗時舊資料留在畫面上並明示「可能不是最新」,ticket 的成功畫面完全不受影響 | Test 8 ×3 + Test 10(跨元件) |
| Trades 頁重讀時保留篩選與排序、頁碼歸零;新交易不在結果集內時明確告知 | Test 11 / 13 / 14 / 16 |
| 剛成交的那一列在 API mode 會有 fresh 高亮,且有非顏色線索的「新」標記 | Test 17(反轉後)/ 18/19 ×2 |
| mock mode 不會因為 revision 變動而發出任何網路請求 | Test 4 ×3 |

| artifact | contains | 實測 |
|---|---|---|
| `[FE] src/pages/Positions.vue` | `portfolioRevision` | 2 處(import + watch)✅ |
| `[FE] src/pages/Trades.vue` | `applyQueryChange` | 4 處(定義 + 3 個呼叫端,含 watch)✅ |

| key_link | pattern | 實測 |
|---|---|---|
| `Positions.vue` → `portfolioRevision.ts` | `watch\(portfolioRevision` | 1 ✅(Overview / Trades 同形各 1) |
| `Trades.vue` → `applyQueryChange` | `applyQueryChange` | watch 區塊內命中 ✅(Test 12 同時鎖住「不得有第二條重置邏輯」) |

## Known Stubs

**無。** 三頁的重讀、stale 呈現、D-11 提示與 D-13 高亮皆為完整實作,無 TODO、無 FIXME、無佔位、無硬編碼空值。本 plan 反而**清掉**了 Phase 3 留下的兩條 TODO 註解。

**一個已知的未達成項(不是 stub,是視覺契約):** §Layout Contract 的「refresh 指示不得造成版面高度跳動」尚未達成,理由與已評估的替代方案見上方第 4 節。

## Threat Model 對帳

| Threat ID | Disposition | 落實情形 |
|-----------|-------------|----------|
| T-04-01 | mitigate | Test 10 是這條的直接驗收:refetch 失敗時 `ticket-result` 完整、`ticket-error` 為 null、ticket 未關閉,stale 只出現在頁面上 |
| —(refetch 重用 `loading` 清空表格) | mitigate | 三頁 Test 6/7 用 pending promise 在重讀中途斷言舊列仍在、骨架未出現 |
| —(stale 值被當成後端真相) | mitigate | 三頁 Test 8:`portfolioStaleAfterTrade` + code + traceId + 重試鈕,且 `role="status"` |
| T-04-09 | mitigate | 三頁 `error.message` 字面零命中;三頁 Test 8 各有一條 `not.toContain('backend said no')` |
| —(mock mode 意外對後端發請求) | mitigate | 三頁 watch 第一行 `if (live) return;`;Test 4 ×3 用 `expect(fetchSpy).not.toHaveBeenCalled()` |
| —(前端重算「是否符合篩選」) | mitigate | Test 15 的 `?raw` 對六個字面各一條 `not.toContain`,並正向斷言只比 id |
| —(fresh 高亮用計時器) | mitigate | Test 21 ×2:兩頁整檔 `setTimeout` 零命中 |
| T-04-SC | accept | **零新增依賴**(`package.json` 不在本 plan 的 diff 清單內) |

**新增威脅面掃描:** 本 plan 未新增任何網路端點(`GET /portfolio/summary`、`/portfolio/holdings`、`/trades` 三者都已在 register 內,只是多呼叫一次)、未新增認證路徑、未新增檔案存取、未改動信任邊界的 schema、未動 `apiClient`。**無新旗標。**

## 誠實列出本 plan **未**涵蓋的事

- **視覺驗證** —— 三項人工檢視我做的是程式碼與盒模型層檢視,**沒有開過瀏覽器**;其中「版面高度跳動」一項明確**未達成**(詳見第 4 節)。
- **真實後端的完整下單 → 重讀流程** —— 本 plan 證明的是「revision 變動時每一頁在其層級做對了事」,端到端串接屬 **Phase 5 / VER-03**(plan 的 `<verification>` 明文要求不得宣稱)。
- **`prefers-reduced-motion` 的實際生效** —— jsdom 不評估 media query,測試斷言的是 CSS 原始碼含該規則,不是瀏覽器真的停用了動畫。
- **`aria-busy` / `role="status"` 的螢幕閱讀器播報行為** —— 斷言的是屬性存在,不是 NVDA/VoiceOver 的實際播報。
- **04-13 的跨 repo 收尾驗證** —— 明確在本 plan 範圍外。
- **`apiTypes.ts` 的 `AssetDto.volumeText` 型別與後端不符** —— 04-10 / 04-11 已記錄,本 plan 未碰(不在範圍內)。

## User Setup Required

None —— 純前端、零新增依賴、無外部服務。

## Next Phase Readiness

**Ready for 04-13:**

- 三頁的 refetch 行為、D-11 提示、D-13 高亮都已就位並在兩個 mode 下全綠,04-13 的跨 repo 收尾可以直接把它們納入雙 mode 驗證清單。
- 全套基準線更新為 **mock 369 / API 369 / build exit 0**。

**移交注意事項:**

1. **前端 repo 分支 `feature/phase-04-manual-trade-creation`,未 push;後端 repo 分支 `feature/phase-04-trade-idempotency`,未 push。本 plan 未開任何 PR。**
2. **§Layout Contract 的「refresh 指示不得造成版面高度跳動」是已知未達成項**,需要在瀏覽器裡量測後決定版位(Phase 5 / VER-03)。
3. **`Overview.vue` / `Positions.vue` 的三個 load 函式仍然直接綁在重試鈕的 `@click` 上**,任何人日後想給它們加位置參數之前請先看 Deviations #2。
4. **`{page}-refreshing` 與 `{page}-refresh-error` 在 Overview 與 Positions 上可能同時存在兩個節點**(兩個資料源各一)。測試若要區分特定區塊,請用 `allTestids()` 或從該區塊的容器往下查,不要假設 `querySelector` 拿到的是哪一個。
5. **前端 repo 不在 GSD 的 worktree 隔離範圍內**,所有前端 plan 共用同一個工作樹;同一 wave 排兩個 FE plan 會互相踩踏(04-06 起每份 SUMMARY 都提過)。

---
*Phase: 04-manual-trade-creation-idempotency-post-trade-refetch*
*Completed: 2026-08-16*

## Self-Check: PASSED

- **5 個 commit hash**(`2b01407` / `6cb521c` / `f381188` / `4d71b13` / `51711b0`)皆已於前端 repo `git log` 驗證存在,且兩個 task 的 `test(...)` 都在對應的 `feat(...)` 之前。
- **6 個修改檔案**皆存在;`git diff --name-only e8d1f35 HEAD` 確認五個 commit 合計只動這 6 個檔案(未含 `App.vue` / `testSetup.ts` / `i18n.ts` / 任何後端檔);`git status --porcelain` 為空。
- **測試數已核對:** `grep -cE "^\s*it\("` → Overview 19 / Positions 33 / Trades 38;`it.skip|it.todo` 三檔皆 0;全套 369 = 338(04-11 收尾)+ 31。
- **本 SUMMARY 的所有指令輸出均為本次實際執行所得**,兩次 RED 的紅綠比例為實測值未經美化;一寫就綠的 9 條已逐條說明,含兩條明確標示為假綠的(Test 4 ×3 與 Test 13)。
- **人工檢視三項據實記錄**,其中「版面高度跳動」明確標示為**未達成**並附上已評估的替代方案,未假稱完成視覺驗證。
- **未修改** `STATE.md` / `ROADMAP.md`;**未觸碰**任何後端原始碼;**未執行** `git push`(兩個 repo 皆是);**未開** PR。
