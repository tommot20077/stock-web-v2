# Task 2 報告(前端 repo `/home/user/stock-web-v2-front-end`,分支 `claude/epic-curie-lj8alo`)

## Part 1 (2a–2c)

執行者:part 1 executor(sequential,主工作樹)。範圍:2a(B2 前端 1900 下限)、2b(B1 + WR-09 + IN-09)、2c(B3 移除訂單類型 / 有效時間)。2d–2f 由 part 2 executor 接續。

### Commits(`9a63391..c35d55b`,共 7 個,皆 scope `quick-261107-1j4`)

| 子項 | 類型 | Hash | Subject |
|---|---|---|---|
| 2a | RED | `1053738` | test: 現金發生時間與成交時間 1900-01-01 下限三種文案的失敗測試 |
| 2a | GREEN | `d885b6f` | feat: 現金發生時間與成交時間加 1900-01-01 下限並分三種錯誤文案 |
| 2b | RED | `ef63a58` | test: 股利日期三種文案、除權日市場日期與 reactive 時鐘的失敗測試 |
| 2b | GREEN | `281281f` | feat: 股利日期分三種文案、除權日改用標的市場今天並加 reactive 時鐘 |
| 2c | RED | `1ab6edf` | test: OrderTicket 兩模式移除訂單類型與有效時間的失敗測試 |
| 2c | GREEN | `be182ad` | feat: OrderTicket 兩模式移除訂單類型與有效時間 |
| 2c | 修正 | `c35d55b` | test: 交易後現金 D-04 斷言拆回兩個獨立測試以支援 API 模式測試 |

RED 證據:2a RED 為 6 個失敗(eventTimeBounds 模組不存在 + 5 個斷言);2b RED 為 18 個失敗 + 2 個檔案無法載入(marketDate / useNow 尚不存在);2c RED 為 4 個失敗。

### 測試數字

| 項目 | 動工前基準(`9a63391`) | Part 1 完成後(`c35d55b`) |
|---|---|---|
| `npm test`(mock) | 59 檔 / 1138 | 62 檔 / **1188**(+50) |
| `VITE_DATA_MODE=api npm test` | 未量測(基準時沒跑) | 62 檔 / **1188** |
| `npx playwright test e2e/tests/layout.spec.ts` | 101 passed | **101 passed**(在 `be182ad` 量測;`c35d55b` 只改測試檔) |
| `npm run lint` | — | 0 errors / 40 warnings;本次新增或修改的檔案以 `npx eslint <檔案>` 單獨跑為 0 warning(40 個 warning 全在未動的檔案) |
| `npm run build`(含 vue-tsc) | — | 通過 |

### 屬於 2a–2c 的 grep 閘門(皆達標)

- `grep -cE "\b(orderType|tif|gtc|market|limit|day): '" vue-app/src/i18n.ts` = 0(baseline 4 行);`grep -c 'trade-ord-type\|trade-tif' OrderTicket.vue` = 0(baseline 4)
- `grep -c todayLocalDate HoldingEventDialog.vue` = 0(baseline 4);`marketDayRuleOf` 出現在 HoldingEventDialog;`MIN_EVENT_INSTANT_MS` 在 `eventTimeBounds.ts`
- `git diff --name-only 9a63391..HEAD -- vue-app/src/services/apiClient.ts` 為空(鐵律 1)
- 新檔與 HoldingEventDialog 沒有 `fetch(`;資產查詢經 `getRuntimeApiClients().market.searchAssets`

### 新增 / 刪除的 i18n 鍵(zh / en 全文,供 Task 3 逐字寫進 UI-SPEC)

新增(全部列在 `i18n.test.ts` 的 `QUICK_261107_1J4_KEYS`,part 2 新鍵請續加):

| 鍵 | zh | en |
|---|---|---|
| `cashErrOccurredAtTooEarly` | 發生時間不可早於 1900-01-01。 | Occurred at cannot be earlier than 1900-01-01. |
| `tradeErrExecutedAtRequired` | 請填寫成交時間。 | Executed at is required. |
| `tradeErrExecutedAtTooEarly` | 成交時間不可早於 1900-01-01。 | Executed at cannot be earlier than 1900-01-01. |
| `holdingEventErrPaidAtRequired` | 請填寫入帳時間。 | Paid at is required. |
| `holdingEventErrPaidAtTooEarly` | 入帳時間不可早於 1900-01-01。 | Paid at cannot be earlier than 1900-01-01. |
| `holdingEventErrEffectiveDateRequired` | 請填寫除權日。 | Ex-date is required. |
| `holdingEventErrEffectiveDateTooEarly` | 除權日不可早於 1900-01-01。 | The ex-date cannot be earlier than 1900-01-01. |
| `holdingEventMarketDate` | 以{zone}日期計 | Uses the {zone} date |
| `marketZoneNewYork` | 美東 | US Eastern |
| `marketZoneTaipei` | 台北 | Taipei |
| `marketZoneTokyo` | 東京 | Tokyo |
| `marketZoneBerlin` | 中歐 | Central European |
| `marketZoneUtc` | UTC | UTC |

刪除(2c;兩語皆刪,`i18n.test.ts` 有斷言):

| 鍵 | zh(舊) | en(舊) |
|---|---|---|
| `orderType` | 單別 | Type |
| `market` | 市價 | Market |
| `limit` | 限價 | Limit |
| `tif` | 有效時間 | Time in force |
| `day` | 當日 | Day |
| `gtc` | 長效 | GTC |

未改動的既有文案:`cashErrOccurredAt`、`cashErrOccurredAtRequired`、`tradeErrExecutedAt`、`holdingEventErrPaidAt`、`holdingEventErrEffectiveDate`、`holdingEventEffectiveDateHint`。

### 行為摘要(給 Task 3 對照 UI-SPEC)

- **2a** 新檔 `vue-app/src/eventTimeBounds.ts`:`MIN_EVENT_INSTANT_MS = Date.UTC(1900,0,1)`、`MIN_EVENT_DATE = '1900-01-01'`、`TimeFieldIssue`、`classifyInstantInput(value, nowMs)`、`classifyDateInput(value, maxDate)`。下限以 instant 比較(台北本地 1900-01-01T00:00 = 1899-12-31T16:00Z → tooEarly,測試以 `Date.parse('…+08:00')` 直接建構驗證)。
  - CashEntryDialog:發生時間的錯誤節點依**目前輸入值**歸類:空白 → `cashErrOccurredAtRequired`、過早 → `cashErrOccurredAtTooEarly`、其餘(未來,或後端 `fields.occurredAt` 但輸入合法)→ `cashErrOccurredAt`;取代 quick-261106-fbs 的「清空才給專屬文案」分支。
  - OrderTicket:底部自檢錯誤(`validationError`)依歸類給 `tradeErrExecutedAtRequired` / `tradeErrExecutedAtTooEarly` / `tradeErrExecutedAt`,三者都讓「確認內容」鈕 disabled;後端 `fields.executedAt` 欄位節點在輸入歸為 tooEarly 時回 TooEarly 鍵,否則維持 `tradeErrExecutedAt`。欄位節點上方註解已改為「後端以 fields.executedAt 回下限與股數調整時序兩種錯誤」。
- **2b** 新檔 `vue-app/src/marketDate.ts`(檔頭註明鏡像 `TradingDay.java:55-71 / :80-83`、兩邊需同步)與 `vue-app/src/composables/useNow.ts`。
  - HoldingEventDialog:入帳時間 / 除權日依目前輸入歸類 → Required / TooEarly 鍵,其餘維持 `FIELD_ERROR_COPY` 既有鍵(後端時序錯誤因此仍得到 `holdingEventErrEffectiveDate`)。
  - 除權日預設與 `max` = 標的市場今天(computed,跟隨規則與時鐘);typeahead 看選定資產的 `assetType` / `market`;從持倉開啟時開啟當下以 `market.searchAssets({ query: symbol, page: 0, size: 10 })` 查一次,取代號不分大小寫完全相同者,序號守門丟棄過時回應,失敗吞掉 → fallback 紐約;沖銷模式不查(既有「沖銷不需要搜尋標的」測試仍通過)。規則或日期改變時,未 blur 過且仍等於前一個預設的值跟著更新,動過的不覆寫。
  - 除權日 hint 下方新增 `<span id="holding-event-market-date" class="hint market-date" data-testid="holding-event-market-date">`,文字 `tf(lang,'holdingEventMarketDate',{ zone })`,納入除權日欄位 `aria-describedby`。
  - IN-09:`useNow({ active: () => props.open })`;合法性 computed 用 `max(時鐘值, Date.now())`,送出前 `isFormValid()` 仍以當下 `Date.now()` 重算。瀏覽器本地今天 helper 與全部呼叫點已刪除。
  - LiveQuoteStatus:`timeText` 的 sameDay 改用 `useNow({ intervalMs: 60_000, active: degraded|closed })`。
- **2c** OrderTicket:刪除兩段 `v-if="live"` 分段控制、review 的訂單類型列、`ordType` / `tif` 兩個 ref 與 `resetTicket` 兩行重設、已無使用者的 `.seg` / `.seg-btn` 樣式與 focus-visible 選擇器中的 `.seg-btn`(Positions / Overview 有各自 scoped 的 `.seg-btn`,不受影響)。交易後現金(mock 專屬)保留。

### 待 CI(非 layout 的 Playwright,需真後端,本機未跑)

逐一檢查 `trade.spec.ts`、`cash-journey.spec.ts`、`holding-event-journey.spec.ts` 用到的 `getByTestId(...)`:這次沒有刪除任何 testid(移除的分段控制原本就沒有 testid),所有 id 都還在 `src`(`nav-*` 由 `Header.vue` 的 `` `nav-${it.k}` ``、`trades-chip-*` 由 `Trades.vue` 的 `` `trades-chip-${…}` `` 動態產生,兩個檔案都沒動)。三個 spec 都沒有使用 Market / Limit / Day / GTC 文字或訂單類型控件。

- `holding-event-journey.spec.ts:145` 以瀏覽器本地昨天填除權日:從持倉開啟 AAPL → 前端會以真後端 `/assets?query=AAPL` 查一次市場(US → 紐約)。本地昨天在 UTC−5 以東、UTC+19 以西的任何時區都 ≤ 紐約今天,所以在新的 max 下仍合法。**待 CI 確認**。
- `cash-journey.spec.ts`、`trade.spec.ts`:沒有填發生時間 / 成交時間(用預設值 = 現在),不受下限影響。**待 CI 確認**。
- `watchlist-journey.spec.ts` / `watchlist-live.spec.ts` 屬 2e 範圍,由 part 2 檢查。

### 偏離計畫(Deviations)

1. **[Rule 1 - Bug] 自己引入的 API 模式測試失敗(`c35d55b`)**:2c RED 把「API 不渲染 / mock 保留交易後現金」合成一個測試,中途 `cleanupMounted()` 的 `vi.unstubAllEnvs()` 會清掉 `testSetup.ts` beforeEach 的 mock 預設,導致 `VITE_DATA_MODE=api npm test` 下第二次掛載變成 API mode。拆回兩個獨立測試後兩種模式都綠。
2. **[Rule 2 - 可測性] OrderTicket 底部自檢錯誤節點加 `data-testid="ticket-form-error"`**:原本沒有 testid,T2.4 需要斷言其文案。是新增的 test-visible id,Task 3 若維護 04-UI-SPEC 的 testid 清單可補上。
3. `classifyDateInput` 另外拒絕不存在的日曆日(例如 `2026-02-30`)→ `required`,並以 `setUTCFullYear` 檢查,避開 `Date.UTC` 把 0–99 年映射到 1900 年代的問題。計畫只寫「不符 YYYY-MM-DD → required」。
4. `resetForm` 順序調整:先 `resetSymbolSearch()` + 清掉鎖定標的查詢結果,再 `resetModeFields()`,確保重開時除權日預設用的是新規則,不是上一次 typeahead 選的資產。
5. LiveQuoteStatus 的時鐘只在 `degraded` / `closed`(唯一顯示時間的兩態)啟用;計畫只寫 `useNow({ intervalMs: 60_000 })`。
6. 測試的假時鐘預設只假 `Date`(`useWr09Clock()`),需要推進 debounce / interval 的 3 個測試才假全部計時器:`flushAsync` 在任何假計時器下都只跑 microtask 輪次,送出路徑跑不完 fetch → json → nextTick → emit。配股送出測試另外補一次 `flushAsync(16)`。
7. 既有測試調整(計畫允許):`exDateIsADateInputCappedAtTodayAndSentVerbatim`、`recordsStockDividendWithSharesAndExDate`、`recordsSplitAndTitlesResultFromBackendRatio` 改用假時鐘 2026-11-05T01:00Z 並斷言紐約日期 `2026-11-04`;刪除不再使用的測試 helper `localDate()`。task4 兩個重設測試改名為 `resets ticket side when reopened with a symbol-only preset` / `resets side when recording another trade`。

### 給 Yuan 的觀察(不在本次範圍,未改)

- CashEntryDialog 的發生時間與 OrderTicket 的成交時間,「未來時間」判斷仍只在欄位變動時重算(computed 裡讀 `Date.now()`),跟 IN-09 修掉的 HoldingEventDialog 是同一類問題。04.2 IN-09 只點名 HoldingEventDialog / LiveQuoteStatus,所以沒有擴大範圍;之後如果要統一,可以直接套用 `useNow`。
- HoldingEventDialog 入帳時間的原生 `max`(`maxPaidAt`)仍固定在開啟當下(既有測試 `paidAtMaxIsNowAndFutureIsRejected` 鎖住 `max === 開啟時的值`);合法性已隨時鐘更新,只有瀏覽器日期選擇器的上限不會跟著前進。
- 從持倉開啟股利 / 股數調整對話框時,API 模式下會多一次 `/api/v1/assets?query=<symbol>` 請求(只為了取 market,因為 `HoldingDto` 沒有 market)。後端 HoldingDto 加 market 欄位已列為本 quick task 的 out of scope。
- `holdingEventEffectiveDateHint` 的「不可晚於今天」依計畫不改;現在旁邊多了「以{市場}日期計」提示,兩句合起來才是完整意思。

### 學到的坑(LEARNINGS 候選,由 Task 3 決定是否寫入)

- 測試中途呼叫 `cleanupMounted()` 會一併清掉 `testSetup.ts` 的 mock 預設 stubEnv;之後再掛載的元件在 `VITE_DATA_MODE=api npm test` 下會走 API mode。同一測試要掛兩次不同模式時,拆成兩個測試或明確 `vi.stubEnv`。
- `testUtils.flushAsync` 在**任何**假計時器(含 `toFake: ['Date']`)下都不做 macrotask hop;送出路徑測試若需要固定時鐘,要多跑幾輪 `flushAsync(16)`。

### 04.2 finding → commit 對照(part 1 負責的部分)

| Finding | RED | GREEN |
|---|---|---|
| WR-09 除權日用市場日期 | `ef63a58` | `281281f` |
| IN-09 時間相依 computed 隨時間更新 | `ef63a58` | `281281f` |
| (B1 / B2 / B3 屬 Yuan 3B 裁決,非 04.2 finding) | `ef63a58` / `1053738` / `1ab6edf` | `281281f` / `d885b6f` / `be182ad` |

WR-08、WR-10、IN-05 ~ IN-08 屬 2e / 2f,由 part 2 補這張表。

### 給 part 2 executor 的交接

- 工作樹乾淨,HEAD = `c35d55b`;7 個 commit 都已在分支上(未 push)。報告此節之後請另加「Part 2 (2d–2f)」。
- 基準數字(part 2 的「之前」):vitest mock 1188 / api 1188、layout 101、lint 0 errors / 40 warnings。
- `i18n.test.ts` 已有 `QUICK_261107_1J4_KEYS` + `QUICK_261107_1J4_PLACEHOLDERS_BY_KEY` 與禁用字測試,新鍵直接加進清單。
- `useNow`(`vue-app/src/composables/useNow.ts`)已可用;IN-07 的 quoteStream 逾時不需要它。
- 計畫中的行號已位移:OrderTicket 刪了約 45 行(成功打勾圓圈 CSS、`.btn-accent` 都往上移);HoldingEventDialog 多了約 70 行(成功打勾圓圈、`.btn-accent` 往下移);`i18n.ts` 多了約 40 行。請以 grep 定位,不要照計畫行號。
- OrderTicket 的 `.seg` / `.seg-btn` 已刪除;2d 的 `var(--up)` 審計時 OrderTicket 少了這組樣式。
- 兩個測試坑見上方「學到的坑」:測試中途 `cleanupMounted()` 會清掉 mock 預設 stubEnv;假計時器下 `flushAsync` 只跑 microtask。
- 不要用 `vi.runAllTimers()`:HoldingEventDialog(開啟時)與 LiveQuoteStatus(degraded / closed 時)現在都有 interval。

---

## Part 2 (2d–2f)

執行者:part 2 executor(sequential,主工作樹,接在 `c35d55b` 之後)。範圍:2d(A1–A4 對比度 token 與按鈕尺寸)、2e(04.2 WR-08 / WR-10)、2f(04.2 IN-05 ~ IN-08)。

### Commits(`c35d55b..60541f0`,共 7 個,皆 scope `quick-261107-1j4`)

| 子項 | 類型 | Hash | Subject |
|---|---|---|---|
| 2d | RED | `888d16a` | test: 對話框主按鈕 36px、--accent-text、--success 與 --fg-mute 對比度的失敗測試 |
| 2d | GREEN | `4206533` | feat: 對話框主按鈕 36px,新增 --accent-text 與 --success 並加深 --fg-mute |
| 2e | RED | `01dc4c5` | test: 選擇器觸發鈕脫離 DOM 時定位與焦點、建立期間換代號的失敗測試 |
| 2e | GREEN | `fe69add` | fix: 選擇器觸發鈕脫離 DOM 時保留位置並回填焦點,建立並加入固定送出當下代號 |
| 2f | RED | `93e3368` | test: CSV 公式中和、Overview 股利 stale 文案與 WELCOME 逾時的失敗測試 |
| 2f | GREEN | `6fd322a` | fix: CSV 匯出中和公式、Overview 股利 stale 文案對應、WELCOME 逾時重連 |
| 2f(IN-08) | refactor | `60541f0` | refactor: mock 事件列舉函式更名為 listMockHoldingEvents |

RED 證據:
- 2d:`contrastTokens.test.ts` 39 個失敗(A1 / A2 / A3 / A4 全部新斷言 + 兩個既有白名單測試),`accentTokens.test.ts` 整檔無法載入(`./accentTokens` 不存在);既有 20 個測試照常通過。
- 2e:`WatchlistPicker.test.ts` 3 個失敗(脫離後位置變成 16px / 16px、焦點落到 body、addItem 收到 `TSLA`);`Markets.test.ts` 1 個失敗(找不到 `markets-table`)。另有一個「觸發鈕仍在 DOM 時 ✕ 焦點還給觸發鈕」的守護測試在 RED 時即通過(屬回歸守護)。
- 2f:`csvCell.test.ts` 整檔無法載入;Overview 2 個失敗(dividend / shareAdjustment 顯示交易文案);quoteStream 1 個失敗(10 秒後仍 connecting)。quoteStream「舊 socket 計時器不影響新連線」為守護測試,RED 時即通過。
- IN-08 是純更名(無行為改變),依計畫用單一 `refactor` commit,沒有 RED。

### 測試數字

| 項目 | Part 2 動工前(`c35d55b`) | Part 2 完成後(`60541f0`) |
|---|---|---|
| `npm test`(mock) | 62 檔 / 1188 | 64 檔 / **1262**(+74) |
| `VITE_DATA_MODE=api npm test` | 62 檔 / 1188 | **1262** 全綠 |
| `npx playwright test e2e/tests/layout.spec.ts` | 101 passed | **101 passed**(`4206533` 後與 `60541f0` 各跑一次,皆 101) |
| `npm run lint` | 0 errors / 40 warnings | **0 errors / 40 warnings**(不增加;新增 / 修改檔案單獨 `npx eslint` 為 0 warning,Markets.vue 的 10 個 `no-explicit-any` 為既有) |
| `npm run build`(含 vue-tsc) | 通過 | **通過** |

### Task 2 全部 grep 閘門(2a–2f 重跑,皆達標)

- G1 `grep -cE "\b(orderType|tif|gtc|market|limit|day): '" i18n.ts` = 0;`trade-ord-type\|trade-tif` in OrderTicket = 0
- G2 styles.css:`--accent-text` 3 行(≥ 2)、`--success` 3 行(≥ 2)、`#a1a1aa\|#62605a` 0 行
- G3 `th.s { color: var(--accent); }` 0 處;Markets `tab-count.*color: var(--accent);` 0 行
- G4 兩個對話框 `padding: 8px 24px; min-height: 44px` 0 行
- G5 HoldingEventDialog `todayLocalDate` 0;`marketDayRuleOf` 存在;`MIN_EVENT_INSTANT_MS` 在 `eventTimeBounds.ts`
- G6 portfolioApi.ts `ForTests` 0;WatchlistPicker 有 `isConnected`;quoteStream 有 `WELCOME_TIMEOUT_MS`;useTweaks 有 `accentTextFor`
- G7 `git diff --name-only 9a63391..HEAD -- vue-app/src/services/apiClient.ts` 為空(鐵律 1);`9a63391..HEAD` 在 `vue-app/src` 沒有新增任何 `fetch(`

### 2d — token 最終值與量測比值

量測方式:新增測試專用工具 `vue-app/src/testing/wcag.ts`(WCAG 相對亮度、對比度、`composite(color, alpha, base)` 以 sRGB 線性內插合成、從 styles.css 解析 `--bg` / `--surface` / `--surface2`)。`color-mix(in oklch, X N%, transparent)` 的結果即 X 帶 N% alpha,疊在 surface 上等同 `composite`。surface:淺 `#fafaf9` / `#ffffff` / `#f4f3f0`;深 `#0c0d10` / `#15171c` / `#1c1e25`。

**A2 `--accent-text`**(`vue-app/src/accentTokens.ts` 的 `ACCENT_PRESETS`;計畫起始值經測試複核**全數通過、未調整**)。比值依序為 bg / bg 上 18% 膠囊 / surface / surface 上 18% 膠囊 / surface2 / surface2 上 18% 膠囊;最低值全部落在「18% accent 膠囊疊 `--surface2`」:

| accent | 淺色 --accent-text | 淺色比值 | 最低 | 深色 --accent-text | 深色比值 | 最低 |
|---|---|---|---|---|---|---|
| `#ff6600`(預設) | `#a64300` | 5.87 / 4.83 / 6.13 / 5.01 / 5.53 / 4.60 | 4.60 | `#ff6d0b` | 6.89 / 5.52 / 6.36 / 5.00 / 5.90 / 4.60 | 4.60 |
| `#3b82f6` | `#0a58d7` | 5.93 / 4.83 / 6.19 / 5.05 / 5.58 / 4.60 | 4.60 | `#5f99f8` | 6.85 / 5.62 / 6.32 / 5.06 / 5.86 / 4.65 | 4.65 |
| `#10b981` | `#0a714f` | 5.76 / 4.88 / 6.02 / 5.06 / 5.43 / 4.65 | 4.65 | `#10b981` | 7.66 / 5.90 / 7.07 / 5.31 / 6.56 / 4.86 | 4.86 |
| `#a855f7` | `#830df4` | 6.04 / 4.85 / 6.31 / 5.04 / 5.68 / 4.61 | 4.61 | `#ba79f9` | 6.65 / 5.54 / 6.14 / 5.03 / 5.70 / 4.62 | 4.62 |
| `#ef4444` | `#be1010` | 6.15 / 4.86 / 6.42 / 5.05 / 5.79 / 4.63 | 4.63 | `#f26a6a` | 6.52 / 5.47 / 6.02 / 4.99 / 5.58 / 4.60 | 4.60 |
| `#f59e0b` | `#8d5b06` | 5.54 / 4.84 / 5.78 / 5.05 / 5.21 / 4.62 | 4.62 | `#f59e0b` | 9.05 / 6.73 / 8.35 / 6.04 / 7.75 / 5.53 | 5.53 |
| `#0ea5e9` | `#096994` | 5.81 / 4.88 / 6.07 / 5.07 / 5.47 / 4.62 | 4.62 | `#0ea6ea` | 7.09 / 5.57 / 6.54 / 4.96 / 6.07 / 4.61 | 4.61 |

- styles.css 主題預設:`:root --accent-text: #a64300`、深色 `--accent-text: #ff6d0b`(= `#ff6600` 那一列;測試鎖住兩者一致)。紅漲區塊不覆寫。
- `useTweaks` watcher 在設 `--accent` 的同一處以 `accentTextFor(v.accent, v.theme)` 設 inline `--accent-text`;非 preset(localStorage 自訂值)→ `removeProperty('--accent-text')` 退回主題預設。`accentTextFor` 比對前會 trim + 小寫。
- `TweaksPanel.vue` 色票改為 `ACCENT_PRESETS.map(p => p.accent)`(單一來源)。
- 套用:Positions / Markets / Trades `thead th.s { color: var(--accent-text); }`;Markets `.tab.active .tab-count` 文字 `var(--accent-text)`,膠囊底維持 `color-mix(in oklch, var(--accent) 18%, transparent)`。

**A3 `--success` / `--on-success`**(兩主題各一組,紅漲兩區塊不覆寫):

| 主題 | --success | 對 bg / surface / surface2 | 15% 自身色膠囊 | 12% 膠囊(Chart `.live-pill`) | --on-success | 圖示對比 |
|---|---|---|---|---|---|---|
| 淺 | `#166534` | 6.83 / 7.13 / 6.43 | 5.45 / 5.66 / 5.15 | 5.69 / 5.95 / 5.38 | `#ffffff` | 7.13 |
| 深 | `#22c55e` | 8.53 / 7.87 / 7.30 | 6.81 / 6.13 / 5.63 | 7.23 / 6.48 / 5.97 | `#15171c` | 7.87 |

**A4 `--fg-mute`**:淺 `#6b6b73`(5.06 / 5.28 / 4.76)、深 `#8f8c84`(5.79 / 5.34 / 4.96)。

**A1 按鈕**:CashEntryDialog / HoldingEventDialog `.btn-accent` 改 `padding: 8px 16px; min-height: 36px`(與 OrderTicket 同);`.btn-submit` min-width 176px / 208px 與 CashEntryDialog < 400px 的 160px 保留;更新兩處「寬度推算」註解(水平內距 48px → 32px);兩個對話框 < 400px media query 中已多餘的 `.btn-accent { padding-left/right: 16px }` 一併刪除(HoldingEventDialog 那條計畫沒點名,同理刪除)。layout.spec 101 全過,沒有斷言需要改。

### 2d — `var(--up` 歸類表

動工前 63 處(不含測試;計畫寫 61 處,part 1 之後為 63),改為 `--success` 26 處、保留 37 處。

**改為 `--success`(成功 / OK / live / 健康 / 已連線 / 唯讀權限)**

| file:line(改動後) | 用途 | 改動 |
|---|---|---|
| `components/CashEntryDialog.vue:818` | 成功結果 48px 打勾圓圈 | `background: var(--success); color: var(--on-success)`(原 `var(--up)` + `#fff`) |
| `components/OrderTicket.vue:1550` | 同上 | 同上 |
| `components/HoldingEventDialog.vue:1778` | 同上 | 同上 |
| `components/settings/SettingsAiAccess.vue:123` | `.chip.ok`(連線測試成功) | 底 `color-mix(var(--success) 15%)`、字 `var(--success)` |
| `components/settings/SettingsAiAccess.vue:125` | `.chip.live` | 同上 |
| `components/settings/SettingsAiAccess.vue:166` | `.mcp-tag.read`(唯讀權限) | 同上 |
| `components/settings/SettingsBrokers.vue:43` | 模板「✓ 已連線」 | `style="color:var(--success)"` |
| `components/settings/SettingsBrokers.vue:171` | `.chip.live` | 15% 底 + `var(--success)` |
| `components/settings/SettingsDataSources.vue:70` | 模板「✓ 已連線」 | `style="color:var(--success)"` |
| `components/settings/SettingsDataSources.vue:136` | `.chip.livefeed` | 15% 底 + `var(--success)` |
| `components/settings/SettingsDataSources.vue:140` | `.chip.ok` | 同上 |
| `components/StrategyEditor.vue:159` | `.status.ok`(✓ Compiled) | `color: var(--success)` |
| `components/LiveQuoteStatus.vue:103` | `.live-dot.solid`(即時連線燈) | `background: var(--success)` |
| `pages/Ops.vue:12` | 「Healthy」inline 文字 | `color:var(--success)` |
| `pages/Ops.vue:31` | `.ldot` 任務成功分支 | `'var(--success)'`(失敗分支仍 `var(--dn)`,見觀察) |
| `pages/Ops.vue:155` | `.hdot` 健康燈 | `background: var(--success)` |
| `pages/Ops.vue:156` | `.health .v` | `color: var(--success)` |
| `pages/Markets.vue:478` | `.live.on`(LIVE 鈕文字與邊框) | 字 `var(--success)`、邊框 `color-mix(var(--success) 35%, var(--border))` |
| `pages/Markets.vue:480` | `.live.on .live-dot` | `background: var(--success)` |
| `pages/Markets.vue:482` / `:483` | `pulse` keyframes 光暈(2 行) | `color-mix(var(--success) 50% / 0%)` |
| `pages/Watchlist.vue:1036` | `.live-dot` | `background: var(--success)` |
| `pages/Overview.vue:1100` | `.live-dot` | 同上 |
| `pages/Chart.vue:488` | `.live-pill`(LIVE 膠囊) | 底 `color-mix(var(--success) 12%)`(原 `rgba(22,163,74,0.12)` 同比例)、字 `var(--success)` |
| `pages/Chart.vue:489` | `.live-dot` | `background: var(--success)` |
| `pages/Analytics.vue:325` | 持有天數圓點(> 365 天 = 長期) | `'var(--success)'`——**自行歸類**:這是「達到長期持有門檻」的狀態燈(對照短期的琥珀色),不是價格方向;維持 `--up` 會讓紅漲使用者看到紅色「長期」燈,語意反轉 |

**保留 `--up` / `--up-text`(價格方向 / 損益 / 買進 / 裝飾)**

| file:line | 用途 | 理由 |
|---|---|---|
| `components/OrderTicket.vue:764` | 交易後現金正負色 helper | 損益方向 |
| `components/OrderTicket.vue:1465`、`:1524` | `.side-btn.buy.active`、`.big-side.buy` | 買進方向 |
| `pages/Positions.vue:52`、`:144`、`:145`、`:157`、`:161`、`:191`、`:194`、`:232`、`:336`、`:341`、`:395`、`:1185` | KPI、損益、未實現損益、區段損益、時光機比較、`.day-up` | 損益 / 價格方向 |
| `pages/Markets.vue:112`、`:565`、`:568` | 漲跌欄、`.tick-up` 文字與 `flash-up` 底色 | 價格方向 |
| `pages/Watchlist.vue:1025` | `.up` 漲跌 | 價格方向 |
| `pages/Overview.vue:401` | 70×24 sparkline 容器(SVG currentColor) | 價格方向圖形 |
| `pages/Overview.vue:406`、`:753`、`:1095` | 漲跌 %、toneColor helper、`.wl-chg.up` | 價格方向 |
| `pages/Overview.vue:1152`、`pages/Trades.vue:1044`、`:1056`、`pages/Backtest.vue:501` | `.pill.buy`、`.kind-pill.buy` | 買進方向 |
| `pages/Analytics.vue:659`、`:660`、`:724` | waterfall bar fill / 標籤、`.up` | 損益方向 |
| `pages/Analytics.vue:783` | `.metric-delta.good`(風險指標改善) | 與 `.bad`(`--dn`)成對,隨紅漲一起對調較一致;保留,見觀察 |
| `pages/Analytics.vue:809` | `.diversity-bar .fill` 漸層(accent → up) | 裝飾性漸層,非狀態;保留 |
| `pages/Chart.vue:486`、`:553`、`:554` | `.px-chg.up`、委買價、成交買方 | 價格方向 / 買方 |
| `pages/Backtest.vue:505` | `.up` | 損益方向 |
| `pages/Notifications.vue:430` | `.up`(高峰日通知數高亮) | 非成功狀態的數字強調,維持既有 class 語意;保留 |

測試(`contrastTokens.test.ts`)把上表成功類 19 個選擇器 + keyframes + 5 個模板字面寫成斷言;「var(--up) / var(--dn) 底配白字」白名單清空(打勾圓圈已移出);模板 `'var(--up)'` 白名單剩 Overview 1、Analytics 1。

### 2e — WR-08 / WR-10

- `WatchlistPicker.vue`:新增可選 prop `fallbackFocus?: HTMLElement | null`;`reposition()` 在 `!anchor.isConnected` 時 return(保留上次位置);`requestClose(true)` 觸發鈕仍在 DOM → focus 觸發鈕,已脫離 → `props.fallbackFocus?.focus()`;`submitCreate` 在第一個 await 前 `const symbol = props.symbol`,`addItem` 用它。`onPointerDown` / `onFocusOut` 的 `contains` 對脫離節點本來就安全(回 false),未改。
- `Markets.vue` 的 fallback 選擇:**行情表 `<table>` 本身**,加 `ref="tableEl" tabindex="-1" data-testid="markets-table"`,傳 `:fallback-focus="tableEl"`。理由:表格在所有分頁都一直存在,是使用者剛操作那一列的所在位置(比跳回分頁列更接近原處);`tabindex="-1"` 不進 Tab 順序。另加 `table:focus-visible { outline: 2px solid var(--accent); outline-offset: -2px; }`,鍵盤使用者看得到落點(內縮避免被卡片圓角裁切)。Chart.vue 不傳(觸發鈕不會消失)。
- 測試技巧:jsdom 不排版、`getBoundingClientRect` 永遠全 0,所以 picker 測試以假 rect 建構觸發鈕(脫離 DOM 時回全 0,與瀏覽器一致),再以 `resize` 事件觸發 reposition,讀面板的 `--wp-top` / `--wp-left`。

### 2f — IN-05 ~ IN-08

- **IN-05** 新檔 `vue-app/src/csvCell.ts`(export `csvCell`、`CsvValue`),Trades.vue 改 import、刪除本地定義與型別。字串以 `= + - @ tab CR` 開頭且不是純數字 → 前綴 `'` 再套原引號規則;number 原樣。
- **IN-06** Overview `staleText`:`cash` / `dividend` / `shareAdjustment` → `portfolioStaleAfterCashEntry`。
- **IN-07** `quoteStream.ts`:export `WELCOME_TIMEOUT_MS = 10_000`;`connect()` 建立 socket 後啟動 `welcomeTimer`;`handleWelcome` 清除;`resetConnectionState()`(被 `detachSocket` / `handleClose` 呼叫,dispose 經 detachSocket)一併清除;回呼先確認 `socket === ws && !disposed`,逾時 → `detachSocket()`(close 1000)、`transition('degraded')`、`scheduleReconnect()`(沿用 1 / 2 / 4 … s 退避)。
- **IN-08** `listMockHoldingEventsForTests` → `listMockHoldingEvents`(holdingEventApi.ts 定義與註解、portfolioApi.ts 3 處、holdingEventApi.test.ts),不保留別名。`resetMockHoldingEventsForTests` 確實只給測試用,保留原名。

### i18n

Part 2 **沒有新增或刪除任何 i18n 鍵**(`QUICK_261107_1J4_KEYS` 不變,仍是 part 1 的 13 個)。

### 給 Task 3 記進 UI-SPEC 的新 testid / token / 介面

- 新 testid:`markets-table`(Markets 行情表,`tabindex="-1"`,WR-08 的焦點落點)。part 1 另有 `ticket-form-error`、`holding-event-market-date`。
- 新 token(styles.css):`--accent-text`(淺 `#a64300` / 深 `#ff6d0b`,其他 preset 由 useTweaks inline 覆寫,對照表見上)、`--success`(淺 `#166534` / 深 `#22c55e`)、`--on-success`(淺 `#ffffff` / 深 `#15171c`);`--fg-mute` 改為淺 `#6b6b73` / 深 `#8f8c84`。使用規則:accent 當文字一律用 `--accent-text`;成功 / OK / live / 健康 / 已連線狀態用 `--success`(紅漲綠跌不對調);價格方向 / 損益 / 買進仍用 `--up` / `--up-text`。
- 新檔:`accentTokens.ts`(`ACCENT_PRESETS`、`accentTextFor`)、`csvCell.ts`、`testing/wcag.ts`(測試專用)。
- 元件介面:`WatchlistPicker` 新增可選 prop `fallbackFocus`;`quoteStream` export `WELCOME_TIMEOUT_MS`。
- A1:CashEntryDialog / HoldingEventDialog 主要按鈕(送出 / 完成)36px、內距 8px 16px(04 / 04.1 / 04.2 UI-SPEC 若寫 44px / 24px 需更新)。
- CSV 匯出:文字儲存格公式中和規則(上方 IN-05)可補進 04.2 UI-SPEC 匯出段。

### 待 CI(非 layout 的 Playwright,需真後端,本機未跑)

逐一核對 `watchlist-journey.spec.ts`、`watchlist-live.spec.ts`、`trade.spec.ts`、`cash-journey.spec.ts`、`holding-event-journey.spec.ts` 用到的 `getByTestId(...)` / `locator(...)`:part 2 **沒有刪除或改名任何 testid**(只新增 `markets-table`),各 spec 用到的 `markets-star`、`watchlist-picker*`、`watchlist-row*`、`live-quote-status`、`ticket-*`、`cash-*`、`holding-event-*`、`trades-*`、`nav-*` 都還在。spec 中沒有顏色、按鈕高度或面板位置的斷言;watchlist-journey 唯一的焦點斷言是 Watchlist 頁的刪除確認(未動)。

- `watchlist-journey.spec.ts`:Markets ★ → 選擇器建立並加入 AAPL;WR-10 改為固定送出當下代號,單一代號流程行為不變。**待 CI 確認**。
- `watchlist-live.spec.ts`:等 `live-quote-status[data-live-status="live"]`;IN-07 新增 10 秒 WELCOME 逾時,真後端 WELCOME 通常即時送達,不應觸發;若 CI 的 WS 握手異常慢,會改成 degraded → 1 秒後重連。**待 CI 確認**。
- `trade.spec.ts`、`cash-journey.spec.ts`、`holding-event-journey.spec.ts`:part 2 只動了成功打勾圓圈顏色與主按鈕尺寸 / 內距,選擇器不受影響。**待 CI 確認**(part 1 的時間下限 / 市場日期註記仍適用)。

### 偏離計畫(Deviations)

1. **[Rule 1 - 測試 bug] 成功類選擇器斷言(`4206533` 內修正)**:RED 版本對「含該選擇器的每一條規則」逐一比對屬性,但 `.check`、`.live-dot.solid` 另有只寫 `animation: none` 的 reduced-motion 規則,導致 GREEN 後仍失敗。改為「只看有宣告該屬性的規則,且至少要有一條」;改動前程式碼在新斷言下依然失敗(宣告值是 `var(--up)`),RED 意義不變。
2. **[計畫內部矛盾] IN-05 純數字豁免不含 `+`**:計畫的 regex 為 `/^[+-]?\d+(\.\d+)?$/`,但 T2.11 同時要求 `'+1'` 前綴、`'-12.50'` 不前綴,兩者無法同時成立。採 `/^-?\d+(\.\d+)?$/`:後端序列化的數字不會帶 `+`,前導 `+` 只出自使用者文字,前綴後在試算表保留原樣文字。
3. **WR-08 fallback 元素**:計畫給兩個選項(表格容器 `tabindex="-1"` 或啟用中分頁按鈕),採 `<table>` 本身並加 `:focus-visible` 外框,理由見 2e。
4. **HoldingEventDialog < 400px 多餘內距覆寫一併刪除**:計畫只點名 CashEntryDialog 那條,HoldingEventDialog 的同一條在 36px / 16px 規格下同樣多餘。
5. **新增測試專用檔**:`testing/wcag.ts` 與 `accentTokens.test.ts`(計畫 files 未列)。accent preset 量測放獨立檔,避免 RED 時 `contrastTokens.test.ts` 因 import 不存在的模組整檔無法載入、看不到逐條 RED。
6. **IN-08 無 RED commit**:純更名,依計畫「純重構用 refactor」。

### 給 Yuan 的觀察(不在本次範圍,未改)

- **Ops `.ldot` 失敗分支用 `--dn`**:紅漲綠跌偏好下,失敗燈會變綠(與成功燈 `--success` 同色系)。計畫已預告;若要修,可另立 `--danger` 固定紅。
- **字面色底在紅漲模式不對調**:`.pill.buy` / `.kind-pill.buy` / `.side-btn.buy.active`(`rgba(22,163,74,0.12)`)、Analytics `.metric-delta.good` / `.bad`(`rgba(16,185,129,…)` / `rgba(239,68,68,…)`)、Chart `.depth.up-bg`、Backtest 熱圖的 `rgba(22,163,74,…)`。紅漲時會出現「紅字配綠色淡底」;這是既有問題(不屬成功語意),本次維持。
- **Analytics `.metric-delta.good`**:語意是「風險指標改善」而非價格方向,但與 `.bad`(`--dn`)成對,所以暫時保留跟著紅漲對調;若要改成固定綠 / 紅,兩者要一起換。
- **仍有約 16 處 `color: var(--accent)` 當文字**(AuthPanel、CmdK、各 Settings 頁、Alerts、Backtest、Chart 等的連結 / 強調字)。本次依裁決只改 th.s 與 tab-count;其他 preset(例如淺色 `#f59e0b` 白底約 2.1:1)在這些地方仍不足 AA,之後可統一改用 `--accent-text`。
- **深色主題預設 accent 文字**:深色 `--accent-text` 為 `#ff6d0b`(僅比 `#ff6600` 稍亮),是為了通過 18% 膠囊疊 `--surface2` 的 4.5。

### 學到的坑(LEARNINGS 候選,由 Task 3 決定是否寫入)

- 原始碼層級的 CSS 斷言要注意同名選擇器:reduced-motion / media query 常有只寫 `animation` 的同名規則,逐條比對屬性會誤判;改為「只檢查有宣告該屬性的規則,且至少一條」。
- jsdom 的 `getBoundingClientRect()` 永遠全 0,定位邏輯(例如 popover 依觸發鈕定位)要測「保留位置」必須自己 stub rect,並讓脫離 DOM 的節點回全 0 才符合瀏覽器行為。

### 04.2 finding → commit 對照(完整,含 part 1)

| Finding | RED | GREEN / 修正 |
|---|---|---|
| WR-08 Markets 觀察清單視圖觸發列消失 → 面板跳位、焦點遺失 | `01dc4c5` | `fe69add` |
| WR-09 除權日用市場日期(part 1) | `ef63a58` | `281281f` |
| WR-10 建立並加入期間換代號加錯代號 | `01dc4c5` | `fe69add` |
| IN-05 CSV 匯出公式中和 | `93e3368` | `6fd322a` |
| IN-06 Overview stale 文案對應 dividend / shareAdjustment | `93e3368` | `6fd322a` |
| IN-07 WELCOME 逾時 | `93e3368` | `6fd322a` |
| IN-08 mock 事件列舉函式更名 | —(純更名) | `60541f0`(refactor) |
| IN-09 時間相依 computed 隨時間更新(part 1) | `ef63a58` | `281281f` |
| (Yuan 3A:A1 / A2 / A3 / A4,非 04.2 finding) | `888d16a` | `4206533` |

### 交接給 orchestrator / Task 3

- 工作樹乾淨,HEAD = `60541f0`;Task 2 共 14 個 commit(`9a63391..60541f0`),全部在 `claude/epic-curie-lj8alo`,未 push。
- 本報告未 commit;未建立 SUMMARY;未改 STATE.md / ROADMAP.md。
