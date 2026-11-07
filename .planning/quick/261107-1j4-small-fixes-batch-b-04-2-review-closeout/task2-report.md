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
