# 前端 Phase 4 分支 code review

- Repo: `D:/backup/backup/程式/workspace/vue/stock-v2`(專案根 `vue-app/`)
- Range: `origin/develop...feature/phase-04-manual-trade-creation`(21 檔、29 commits,HEAD `51711b0`)
- 方式:逐檔親讀 diff 與新模組全文;測試只讀、未執行(主線另跑)。

## 裁決

**MERGE-WITH-FIXES** —— 必修 2 條(F-1、F-2),其餘可在合併後另開小修。
沒有 BLOCKER:六條鐵律、凍結契約、D-14 key 生命週期、TRAD-04 連點防護、payload 投影、
T-04-09(不露 message / fields value / key)全部由程式碼與測試雙重確認。

---

## 發現清單

### F-1 MAJOR — review 步驟的「記錄交易」可能靜默 no-op(違反 AGENTS.md 鐵律 6)

- `vue-app/src/components/OrderTicket.vue:569-575`(送出鈕只有 `:disabled="submitting"`)、
  `:1268-1272`(`submitTrade` 開頭 `if (!selected.value || !canSubmit.value) return;` 無任何回饋)、
  `:354`(`validationError` 節點只存在於 `step === 'ticket'` 區塊 `:31-457` 內)。
- 失敗情境(API mode、SELL):切到 SELL → `watch(side)` 發 `listHoldings`(`:943-947`)→ 回應未到前
  `sellableQty === null` ⇒ `oversellError === ''` ⇒ `canSubmit` 為 true,使用者按「確認內容」進 review →
  holdings 回應落地,可賣 5、qty 10 ⇒ `oversellError` 有值 ⇒ `canSubmit` 轉 false →
  使用者按「記錄交易」:按鈕仍 enabled,`submitTrade` 靜默 return,畫面零變化、沒有錯誤文字。
  `preset: { sym, side: 'SELL' }`(Positions 列點擊)一開就是 SELL,更容易踩到。
- 測試缺口:Test 47(`OrderTicket.test.ts:1612`)只斷言 ticket 步驟的 `advanceButton().disabled`,
  沒有覆蓋「進 review 後 canSubmit 翻轉」。
- 建議:review 步驟送出鈕綁 `:disabled="submitting || !canSubmit"`,並在 review 步驟渲染
  `validationError`(或 canSubmit 翻 false 時自動退回 ticket 步驟)。

### F-2 MAJOR — API mode「新」標記與 D-11 提示的清除時機未照 UI-SPEC 實作

- 規格:`04-UI-SPEC.md:546`(提示清除時機:變更篩選/排序/頁碼,**或再次開啟 ticket**)、
  `:557`(標記清除時機:**再次開啟 ticket**、Trades 篩選/排序/頁碼變更、或頁面 unmount)。
- 實作:
  - `vue-app/src/services/portfolioRevision.ts:97-99` `clearLastCreatedTrade()` 只清 `lastTradeId`,
    **沒有任何 API 可清 `lastFill`**(除 `resetPortfolioRevisionForTests`);
  - `OrderTicket.vue:604` 只 import `notifyTradeCreated, portfolioRevision`,開 ticket(`:1192-1204`)
    與 `resetTicket()`(`:1122`)都不呼叫 `clearLastCreatedTrade`;
  - `apiLastFill` 是模組 singleton,頁面 unmount 不會清它,重新 mount 時 `effectiveLastFill`
    (`Positions.vue:393`、`Trades.vue:223`)又讀到同一個值。
- 失敗情境(Trades、API mode):記錄一筆 AAPL → 列表第 0 列正確帶「新」→ 使用者改排序為 total asc
  (`applyQueryChange` 只清 id)→ 重讀後第 0 列若是**另一筆舊的 AAPL** 交易,`Trades.vue:122,130-134`
  的條件 `i === 0 && tr.symbol === effectiveLastFill.sym` 仍成立 ⇒ 舊交易被標成「新」。
  Positions 的 AAPL 持倉列則會**永久**帶「新」,直到下一筆成交。
- 註:`portfolioRevision.ts:92-95` 的註解自己也寫「或再次開啟 ticket」,與程式碼不符。
- 建議:新增 `clearLastFill()`(或讓 `clearLastCreatedTrade` 一併清 lastFill),在 ticket 開啟、
  Trades 篩選/排序/頁碼變更、頁面 `onUnmounted` 呼叫;補對應測試(現有 Test 20/21 未覆蓋此壽命)。

### F-3 MINOR — `fields` 含未知 key 時,錯誤被整個吞掉(無任何可見回饋)

- `OrderTicket.vue:533-534`(底部錯誤 `v-if="submitError && !submitError.fields"`)、
  `:1069-1073`(`fieldErrorText` 對不在 `FIELD_ERROR_COPY` 的 key 回 `''`)、`:1331`(有 fields 就退回 ticket)。
- 情境:後端 400 `VALIDATION_FAILED` 且 `fields` 只有前端不認得的 key(今日唯一實例是
  `MissingRequestHeaderException` 的 `Idempotency-Key`;前端一律送 UUID,故目前不可達,
  但後端日後新增受驗欄位或改 key 命名即觸發)⇒ 退回 ticket 步驟、零錯誤文字、零 aria-invalid。
- 建議:`fields` 的 key 若沒有任何一個命中 `FIELD_ERROR_COPY`,退回底部顯示 `tradeErrValidation`。
- 測試缺口:Test 35-38 都用已知欄位。

### F-4 MINOR — 三頁的成交後重讀沒有序號防護,連續兩筆成交可能被舊回應覆蓋

- `Overview.vue` `refreshSummary`/`refreshRecentTrades`(新增段落,無 seq)、
  `Positions.vue` `refreshSummary`/`refreshHoldings`、`Trades.vue` `loadTrades`(`refresh` 路徑)。
- 情境:「記錄下一筆」連續成交兩次 → `portfolioRevision` 連 bump 兩次 → 兩個 in-flight 請求,
  第一筆的回應晚到就覆寫第二筆的結果,而「更新中…」已消失,畫面呈現的是 stale 值且無提示。
- OrderTicket 自己的 typeahead / klines / holdings 都有 `seq` 守衛(`:793,862,928`),頁面端沒有對齊。

### F-5 MINOR — Trades「不在目前檢視範圍」提示在非 refresh 載入期間會先閃一下

- `Trades.vue:317` `notInCurrentView = !live && !tradesRefreshing && !inResultSet`;
  `inResultSet`(`:272-276`)在 `apiTrades` 為空時為 false。
- 情境:在 Overview 記錄交易後切到 Trades 頁 → `onMounted` 走一般 `loadTrades()`(非 refresh,
  `tradesRefreshing` 從未為 true)→ loading 期間 `apiTrades=[]` ⇒ 提示先顯示,回應落地後才消失。
  註解(`:316`)宣稱的「重讀還沒落地前不下結論」只對 refresh 路徑成立。

### F-6 MINOR — `localTime.ts` 無專屬單元測試;半小時時差與 DST 邊界未被鎖住

- `vue-app/src/services/localTime.ts` 新檔,無 `localTime.test.ts`;僅由 OrderTicket Test 34
  (regex 形狀)與 Trades 年度 chip 測試間接覆蓋。
- 逐行驗證結果(**程式碼本身正確**):`toLocalIso` 用該 instant 自己的 `getTimezoneOffset()`
  ⇒ DST 正確;負半小時 offset(-210 分)→ `-03:30` 正確;offset 0 → `+00:00`(合法 ISO);
  毫秒捨去、秒保留;`toLocalInputValue` 捨秒、無 offset,符合 `datetime-local`。
  `new Date('YYYY-MM-DDTHH:mm')` 依 ES 規格解析為本地時間,再經 `toLocalIso` 回帶 offset ⇒ 往返一致。
- 這條寫進 append-only 帳本,值得一份純函式測試(用固定 `Date` + `vi.setSystemTime`/TZ 環境變數)。

### F-7 MINOR — `maxExecutedAt` 在 ticket 開啟時凍結

- `OrderTicket.vue:1136-1138`。ticket 開著幾分鐘後,`datetime-local` 的 `max` 仍是開啟當下,
  使用者無法用選擇器選「開啟之後、現在之前」的時間;手打仍會被 `executedAtError`(用 `Date.now()`)放行。

### F-8 NIT — mock 專用 SELL 預檢文案未走 i18n(develop 既有)

- `OrderTicket.vue:990-991` `'No holdings available to sell'` / `'Sell quantity exceeds current holding'`
  硬寫英文;develop 原本就如此(`origin/develop` 同檔 `:266-267`),本分支重寫了整個函式但保留了字串。
  mock-only,不影響 API mode。

### F-9 NIT — 同一頁重複的 `data-testid`

- `Overview.vue`:`overview-refreshing` / `overview-refresh-error` / `overview-refresh-error-code` /
  `overview-refresh-retry` 在 summary 條與近期交易區塊各出現一次;`Positions.vue` 同型
  (`positions-refreshing` ...)。測試只能靠出現順序區分兩個區塊,重排版位就會誤判。

### F-10 NIT — 大量「原始碼字面」測試

- `Positions.test.ts:888-905`、`Trades.test.ts:1091-1104`、`OrderTicket.test.ts` Test 22/24
  (`sourceTagOf`、`.btn-submit\s*\{[^}]*min-width:` regex)、「TODO 註解已清除」等,
  用 `?raw` 斷言註解文字、CSS 格式、`setTimeout` 字面不存在。
  它們鎖的是寫法不是行為:合法的 CSS 重排或改寫註解就會紅;`not.toContain('setTimeout')` 也擋不住
  `setInterval`/`requestAnimationFrame`。可接受,但別再擴大。

---

## 各維度結論

| # | 維度 | 結論 |
|---|------|------|
| 1 | AGENTS.md 六條鐵律 | 鐵律 1 ✓ `marketApi.ts`/`tradingApi.ts` 只用 `apiRequest`/`apiPaginatedRequest`,零 `fetch`;鐵律 2 ✓ 無任何 token 讀寫;鐵律 3 ✓ `getRuntimeApiClients()` 經 `getRuntimeDataMode()` fail-fast,OrderTicket 延遲到開啟才解析(`:618-624`),wiring 測試 `:236-246` 鎖住 mock factory 未被呼叫;鐵律 4 ✓ 走 `ApiResponse` 拆封;鐵律 5 ✓ `i18n.test.ts` 禁字清單;鐵律 6 **✗ F-1** |
| 2 | 凍結契約 | header 名 `Idempotency-Key` ✓(`tradingApi.ts:89`),值為 `crypto.randomUUID()` 36 字元恆在 1–128 內 ✓;409 `TRADE_IDEMPOTENCY_KEY_REUSED` 文案 + 丟棄 key ✓(`:1329`,Test 42);payload 逐欄投影七欄 ✓(`tradingApi.ts:94-102`,tradingApi.test:110-154、Test 34);`TradeDto` 無 `idempotencyKey` ✓ |
| 3 | D-04 / D-14 / D-15 / D-16 | D-04 ✓ `v-if="live"` 三處(`:177,337,451`);D-14 ✓ `ensureIdempotencyKey` + `flush:'sync'` dirty watcher(`:1238-1266`),401 replay 沿用同 options ✓(apiClient:319,tradingApi.test:179);D-15 ✓ 三態不阻擋、只讀 symbol/totalQuantity;D-16 ✓ 依 code 分派、403 status fallback、NETWORK_ERROR 合成、401 交全域;缺口見 F-3 |
| 4 | 連點防護 | ✓ 雙層:`:1269` 同步守衛 + `:573` disabled;Test 23 與 task4 測試皆以 programmatic dispatch 驗第二層。失敗後 key 預設保留、只在 KEY_REUSED 丟棄 ✓ |
| 5 | post-trade refetch | ✓ 三頁 `watch(portfolioRevision)` 重打各自 load,mock mode 不發網路(Test 4);U-05 保留舊值 ✓;**壽命問題見 F-2**,競態見 F-4,閃爍見 F-5。無 timer、無 listener 洩漏(watch 隨元件卸載) |
| 6 | 時間處理 | 程式碼正確(見 F-6 逐行結論);測試覆蓋不足 F-6;`maxExecutedAt` 凍結 F-7 |
| 7 | i18n / a11y | 42 key 雙語 ✓(`i18n.test.ts:22-72,111-118`);移除的 5 個舊 key 全 repo 零引用 ✓;combobox `role/aria-*` + ↓↑ Enter Esc ✓(Test 11,`:1167-1190`);焦點進入 dialog 與成功標題 ✓(`:1203,1298`);欄位錯誤走 `aria-describedby` 不用 `role=alert` ✓;F-8 mock 文案 |
| 8 | 測試品質 | 每個新模組有測試;斷言鎖住不變量(七欄 payload、同 key replay、fields value 不入 DOM、mock 不發網路);`2026` 只出現在靜態 fixture,年度邏輯用 `new Date().getFullYear()` ✓;缺口:F-1 review 翻轉、F-2 壽命、F-3 未知 key、F-6 純函式;F-9/F-10 品質提醒 |

## 殘留風險 / 未確認

- 未執行任何 npm 指令;以上皆為靜態閱讀結論。
- F-1 的觸發視 holdings 回應延遲而定,實機出現機率不高,但一旦發生是主要動作的靜默失敗。
- `tradingApi` mock 的 `TRADE_INSUFFICIENT_HOLDING` 與 API mode 同形,但 mock 的 `sellPrecheckError`(F-8)
  仍走另一條英文文案路徑 —— 兩個 mode 的 SELL 預檢文案不一致,屬 mock-only。
- `refresh` 失敗(非 401)時,ticket 底部會顯示 `tradeErrUnknown`/NETWORK,同時 SessionBanner 也可能亮
  (`apiClient.ts:315`)—— 雙重訊息,邊緣情境,未列入清單。
