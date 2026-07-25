---
phase: 03-portfolio-read-api-mode
plan: 05
subsystem: ui
tags: [vue, typescript, vitest, trades, pagination, sorting, csv, api-adapter]

# Dependency graph
requires:
  - phase: 03-portfolio-read-api-mode
    provides: GET /api/v1/trades 的 type/dateFrom/dateTo/sort/direction 參數與白名單契約(Plan 01)
  - phase: 03-portfolio-read-api-mode
    provides: PortfolioApi.listTrades / TradeListParams / pageApiClients.portfolio 註冊(Plan 02)
  - phase: 03-portfolio-read-api-mode
    provides: 區塊級四態樣板、BlockState<T> + computed 投影、`?raw` 原始碼斷言手法(Plan 03 / 04)
provides:
  - Trades 頁的雙模式實作(API mode server-side 篩選/排序/分頁 + 全頁 CSV,mock mode 現狀)
  - D-15 頁碼重置與溢出回退(含「自動重試至多一次」的防迴圈實作)
  - 本地時區 ISO-8601 年界組裝(toLocalIso),避開 toISOString 的 UTC 陷阱
  - 「偽造系統年度」的測試手法:讓寫死年份的實作在當年度就現形
affects: [Phase 4 交易建立與 post-trade refetch]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "查詢參數單一來源 queryParams():列表與 CSV 匯出共用,兩者語意不可能漂移"
    - "溢出回退以「遞迴呼叫時關閉回退旗標」實作防迴圈,自動重試至多一次"
    - "以 vi.setSystemTime 推到未來年度,鎖定『不得寫死年份』這類當年度看不出來的缺陷"

key-files:
  created:
    - ../../vue/stock-v2/vue-app/src/pages/Trades.test.ts
  modified:
    - ../../vue/stock-v2/vue-app/src/pages/Trades.vue

key-decisions:
  - "預設 sort/direction/page/size 一律明確送出,不倚賴後端預設值 —— 契約漂移時測試會立刻紅,而不是靜默改變行為"
  - "分頁列只在『已載入且有列』時渲染,空/載入/錯誤三態不顯示頁碼指示器(避免出現 3 / 1 這種矛盾頁碼)"
  - "換頁不套 D-15 重置(重置只適用於篩選/排序變更),否則下一頁永遠回到第 0 頁"
  - "匯出失敗以獨立的 trades-export-error 區塊呈現,不覆蓋列表狀態 —— 匯出失敗不該讓已載入的列表消失"
  - "mock mode 表頭完全不可點:mock 無伺服端排序,加 client-side 排序等於新增 mock 行為(違反『mock 零變化』)"

patterns-established:
  - "『偽造系統年度』測試:凡是與當前年/月/日綁定的邏輯,期望值不得由同一個 new Date() 推導,否則寫死值與動態值在今天完全等價"

requirements-completed: [PORT-03, PORT-05, PORT-08]

# Metrics
duration: 40min
completed: 2026-07-26
---

# Phase 03 Plan 05: Trades 雙模式改寫 Summary

**Trades 從「對完整陣列做 client-side 篩選」改為 API mode 的 server-side 篩選/排序/分頁:每個 chip、每個可排序表頭、每次換頁都轉成 `GET /api/v1/trades` 的 query 參數,CSV 匯出改為以當前條件逐頁拉完再組檔;mock mode 行為零變化。**

## Performance

- **Duration:** ~40 min(含 19 次 mutation testing)
- **Tasks:** 3(TDD red→green 在同一輪收斂)
- **Files modified:** 2(1 新增 / 1 修改,皆在 sibling 前端 repo)
- **Tests:** 210 → 233(淨增 23),雙模式皆綠

## (1)UI ↔ 參數對應表(最終版,以實際請求 URL 為證)

下列 URL 為實測擷取(臨時 probe,執行後已刪除,未進 commit),測試機時區 UTC+8:

```
初始載入        /api/v1/trades?sort=executedAt&direction=desc&page=0&size=20
點 Buy          /api/v1/trades?type=BUY&sort=executedAt&direction=desc&page=0&size=20
點 2026 chip    /api/v1/trades?dateFrom=2026-01-01T00%3A00%3A00%2B08%3A00&dateTo=2027-01-01T00%3A00%3A00%2B08%3A00&sort=executedAt&direction=desc&page=0&size=20
點 Total 表頭   ...&sort=total&direction=desc&page=0&size=20
點 匯出         ...&sort=total&direction=desc&page=0&size=100
                ...&sort=total&direction=desc&page=1&size=100
                ...&sort=total&direction=desc&page=2&size=100
```

| UI 控制 | 送出的參數 | 實際行為 |
|---------|-----------|---------|
| chip `All`(預設選中) | 不帶 `type` / `dateFrom` / `dateTo` | 參數整個不出現(比送空字串更保險 —— 後端對空白 type 才需要短路處理) |
| chip `Buy` / `Sell` | `type=BUY` / `type=SELL` | 大寫送出;後端大小寫不敏感,但不倚賴該寬容 |
| chip `Dividend` | **不渲染** | 後端 `TradeType` 只有 BUY/SELL(D-02);mock mode 仍保留該 chip |
| chip `{當年度}` | `dateFrom` / `dateTo` | 本地時區的今年年初 / 明年年初,ISO-8601 含 offset(`+08:00`),半開區間 `[from, to)`。標籤與值皆為動態 `getFullYear()`,不寫死 |
| 表頭 `Date` | `sort=executedAt` | 點擊切換 `direction`;首次切到新鍵一律 `desc` |
| 表頭 `Total` | `sort=total` | 同上;`total = quantity × price`(不含 fee),與後端排序鍵同語意 |
| 表頭 `Qty` | `sort=quantity` | 同上 |
| 表頭 `Type` / `Symbol` / `Price` / `Fee` | **無** | 不綁 click,點了不發任何請求(後端白名單只有三鍵) |
| 上一頁 / 下一頁 | `page` | 顯示 `{page+1} / {totalPages}`;第 0 頁 prev 禁用、最後一頁 next 禁用(D-08) |
| 匯出 CSV | 當前全部篩選/排序 + `size=100`、`page=0..totalPages-1` | 見 (3) |
| (全部請求) | `size=20` | D-07 預設,明確送出 |

**任一篩選或排序變更 → `page` 先重置為 0 再請求(D-15);換頁本身不重置。**

## (2)D-15 溢出回退的實作行為(含防迴圈)

**觸發條件(三者同時成立)**:回應 `items` 為空 **且** `totalPages > 0` **且** 送出的 `page ≥ totalPages`。

**動作**:把 `page` 設為 `totalPages - 1`,立刻重新請求一次,並以該次結果渲染。使用者看到的是「最後一頁的資料」,而不是「空列表」。

**防迴圈**:`loadTrades(allowOverflowFallback = true)`,回退時的遞迴呼叫傳入 `false`。因此**自動重試至多一次**,即使伺服端總頁數連續縮水也不會遞迴下去。

```ts
if (allowOverflowFallback && result.items.length === 0
    && result.totalPages > 0 && requestedPage >= result.totalPages) {
  pageNo.value = result.totalPages - 1;
  await loadTrades(false);   // ← 第二次請求不再允許回退
  return;
}
```

兩條規則各有獨立測試:

| 測試 | fetch page 序列 | 結果 |
|------|----------------|------|
| 正常回退 | `0,1,2,3,4,0` | 第 5 次請求 `page=4` 回空且 `totalPages=1` → 自動以 `page=0` 重取,渲染出 1 列、指示器 `1 / 9`、無 `trades-empty` |
| 防迴圈 | `0,1,2,3,4,2` | `page=4` 回空 `totalPages=3` → 回退 `page=2`;`page=2` 又回空 `totalPages=1`(仍溢出)→ **不再重試**,停在已載入的空狀態,總共只多打一個請求 |

（M2 / M16 兩個變異證明這兩條各自有效,見下方 mutation 表。）

## (3)CSV 匯出的請求數實測

- **公式**:`ceil(totalElements / 100)`,即 `totalPages`(以 `size=100` 計)。上限受後端 `size` clamp(1..100)決定,不可能更少。
- **實測**:250 筆 BUY(篩選 Buy、排序 total/asc)→ **3 個請求**,`page=0,1,2`,每個都帶 `size=100&type=BUY&sort=total&direction=asc`。產出 CSV **251 行**(250 筆 + 表頭)。
- 若沿用列表的 `size=20`,同一筆資料要 13 個請求 —— `size=100` 把請求數壓到 1/5(T-03-19)。
- 匯出期間匯出鈕 `disabled`,防重複觸發;失敗後復原可用。

**「所有頁」不等於「忽略篩選」**:測試以「會真的套用 query 參數的假後端」(250 BUY + 40 SELL)驗證 —— 前端若漏送 `type=BUY`,SELL 列會直接出現在 CSV 裡。M4 變異(匯出不帶 `queryParams()`)確實被抓到。

**中止語意**:第 2 頁請求失敗 → 不呼叫 `URL.createObjectURL`(無部分檔案)、顯示 `code + traceId`、列表本身不受影響、匯出鈕復原(T-03-17)。

**檔名**:沿用既有慣例 `trades-{activeFilter 小寫}-{YYYY-MM-DD}.csv`(例:`trades-buy-2026-07-26.csv`)。

## (4)與 Plan 01 後端契約的對齊查核(逐項 checklist)

比對來源:`03-01-SUMMARY.md` §（3）最終 API 契約表。判定依據為上方實測 URL 與 `portfolioApi.ts` 的 `buildQueryString` 欄位。

| # | 查核項 | 後端(03-01-SUMMARY) | 前端實際送出 | 結果 |
|---|--------|---------------------|-------------|------|
| a1 | param `type` | `BUY`/`SELL`,大小寫不敏感,空白等同不帶 | `type=BUY` / `type=SELL`;All 時整個不帶 | **PASS** |
| a2 | param `dateFrom` | ISO-8601 OffsetDateTime | `2026-01-01T00:00:00+08:00` | **PASS** |
| a3 | param `dateTo` | ISO-8601 OffsetDateTime | `2027-01-01T00:00:00+08:00` | **PASS** |
| a4 | param `sort` | string | `sort=executedAt\|total\|quantity` | **PASS** |
| a5 | param `direction` | string | `direction=asc\|desc` | **PASS** |
| a6 | param `page` | int | `page=0..` | **PASS** |
| a7 | param `size` | int | `size=20`(列表)/ `size=100`(匯出) | **PASS** |
| a8 | param `symbol` | string,查無標的回 400 | 本頁無標的搜尋 UI,**不送出** | **N/A**(名稱一致,未使用) |
| b | `sort` 白名單值 | `executedAt` / `total` / `quantity` | TS 型別 `NonNullable<TradeListParams['sort']>` 恰為此三值;只有三個表頭綁 click | **PASS** |
| c1 | 預設 `sort` | `executedAt` | 初始明確送 `sort=executedAt` | **PASS** |
| c2 | 預設 `direction` | `desc` | 初始明確送 `direction=desc` | **PASS** |
| c3 | 預設 `page` | `0` | 初始明確送 `page=0` | **PASS** |
| c4 | 預設 `size` | `20` | 初始明確送 `size=20` | **PASS** |
| d1 | `dateFrom` 語意 | `executed_at >= dateFrom`(**含**) | 年初當作區間起點(含) | **PASS** |
| d2 | `dateTo` 語意 | `executed_at < dateTo`(**不含**,半開) | 明年年初當作區間終點(不含)→ 年界不重複計入 | **PASS** |
| e1 | `total` 定義 | `quantity × price`,**不含 fee** | Total 欄與 CSV total 欄皆為 `quantity * price`;fee 另有獨立欄 | **PASS** |
| e2 | 回應信封 | `ApiResponse<PageResponse<T>>`,`items/page/size/totalElements/totalPages` | 經 `apiPaginatedRequest` 消費,零 shape 兼容解析 | **PASS** |
| e3 | `TradeDto.id` 是 uuid 字串,tie-breaker 用的是 DB 數字 id | 兩者不同 | 前端只把 `id` 當 Vue `:key`,**從不參與排序或頁碼推導** | **PASS**(未混用) |
| e4 | `page`/`size` 夾限 0..10000 / 1..100 | 超出不報錯直接夾限 | 前端 `size` 恆為 20 或 100(≤100)、`page` 受 `totalPages` 約束 | **PASS**(不倚賴夾限兜底) |
| e5 | 非法值錯誤碼 | `VALIDATION_FAILED` / `TRADE_UNSUPPORTED_TYPE` | 錯誤區塊原樣顯示 `error.code`,不做碼別分支 | **PASS** |

**結論:16 項 PASS、1 項 N/A、0 項 FAIL —— 無契約漂移,不需要停下回報。**

兩個值得記錄的觀察(皆不構成不一致):
1. 後端對 `type` 空白字串有短路處理、對 `sort`/`direction` 大小寫不敏感。前端不倚賴這兩項寬容(All 時整個省略參數、一律送標準大小寫),因此後端日後若收緊也不會壞。
2. `CONTEXT specifics` 提過「排序欄位收斂為 executedAt / **price** / quantity」,與 D-06 的 `total` 不一致。**權威是 D-06 與 03-01 的實作(`total`)**;`specifics` 那句是討論早期的措辭,D-06 本文已明確說明使用者要的是「總額」不是「單價」。前端依 `total` 實作。

## Task Commits

前端 repo(`D:/end/workspace/vue/stock-v2`,branch `feature/phase-03-portfolio-read`):

1. **Task 1 + Task 2 + Task 3:`feat(trades): API mode 改走 server-side 篩選/排序/分頁,CSV 匯出涵蓋全頁`** — `ef1fb35`(+1149 / -33,2 檔)
2. **`test(trades): 以偽造系統年度鎖定年度 chip 不得寫死 2026`** — `c4abd7e`(+22 / -1,1 檔)

後端 repo(`D:/end/workspace/java/stock-web-v2`):本 SUMMARY 的 docs commit。

_TDD 節奏:先寫 `Trades.test.ts`(API mode 13 + CSV 3 + mock mode 5)跑出紅燈 **21 failed / 1 passed (22)**,再改寫 `Trades.vue` 至 **22 passed**。三個 task 的測試同屬一個檔案、在同一輪 red→green 收斂,拆成三個 commit 會產生「測試已綠但刻意只 commit 一部分」的假中間態,故合為一個原子 commit(與 03-02 / 03-03 / 03-04 一致)。第二個 commit 是 mutation testing 後補的斷言,獨立成 `test(...)` commit。_

**mutation testing 前已先 commit** —— 03-04 的教訓(在未 commit 狀態下 `git checkout --` 會把還原點指向改寫前的舊版,等於清掉整份新實作)。19 次變異全部在 `ef1fb35` 之後進行,每次還原都正確。

## Files Created/Modified

- `vue-app/src/pages/Trades.vue` — 移除 mock store import;新增 `BlockState<PaginatedResponse<TradeDto>>` 狀態機、`queryParams()` 單一來源、`loadTrades(allowOverflowFallback)`、`applyQueryChange()`(D-15 重置入口)、`toLocalIso()`、非同步 `exportApiCsv()`;模板依 `live` 分流(兩個 tbody 共用 thead)、新增分頁列與區塊狀態樣式
- `vue-app/src/pages/Trades.test.ts` — 新增,23 個測試(API mode 參數/分頁/D-15 共 10、四態與 PORT-04 共 4、CSV 3、mock mode 回歸 5、年度動態 1)

**既有測試零修改。** `src/task4.test.ts` **一字未改**(`git log` 顯示該檔最後一次變更是初始 scaffold commit `a214f1b`),其「filters Trades rows and exports the active filter as CSV」在新結構下一次通過 —— 這是 mock CSV 路徑的回歸網。`App.test.ts` 也未動。

## Decisions Made

- **預設參數明確送出而非倚賴後端預設。** 後端 `sort` 預設就是 `executedAt`、`size` 預設就是 20,理論上可以不送。但明確送出讓「前端認知的預設」被測試釘住:後端哪天改預設,壞掉的是後端測試而不是使用者的畫面。代價只是 URL 長一點。
- **分頁列只在「已載入且有列」時渲染。** 若在空狀態也渲染,溢出回退的防迴圈情境會顯示 `3 / 1` 這種自相矛盾的頁碼。載入中不渲染分頁列也避免「按鈕還在但點了沒反應」。
- **換頁不套 D-15 重置。** `goToPage()` 直接改 `pageNo` 後請求,不經 `applyQueryChange()` —— 否則按下一頁永遠回到第 0 頁。D-15 的措辭是「篩選或排序條件變更」,換頁本身不是條件變更。
- **匯出錯誤獨立成一個區塊(`trades-export-error`),不共用列表的 error 狀態。** 匯出失敗時列表是好的,把列表換成錯誤畫面等於用一個次要動作的失敗摧毀主要內容。兩個區塊都遵守 D-12(只露 code + traceId,不露後端 message)。
- **mock mode 表頭完全不可點。** `toggleSort()` 在 `live` 存在時直接 return,且 `SortArrow` 與 `sortable` class 都加 `v-if="!live"`。mock 現狀沒有排序功能,補上等於新增 mock 行為,違反「mock 零變化」原則(而且 mock 無分頁,client-side 排序雖然安全,但那是 scope 之外的功能)。
- **`live` 分支下 `filteredTrades` 的 `'2026'` case 逐字保留。** D-05 的動態年度只約束 API mode;plan Task 3 明文「mock 行為不變原則優先於 D-05 的動態年度」。

## Deviations from Plan

### 1. [Rule 2 - Missing Critical] 補上「偽造系統年度」的測試

- **Found during:** commit 後的 mutation testing(第 6 個變異)
- **Issue:** plan acceptance criteria 明訂「年度 chip 的日期值不寫死年份(動態 getFullYear)」,我也照做了,但我寫的測試用 `new Date().getFullYear()` 當期望值 —— **今年剛好就是 2026**,所以把實作改回寫死的 `'2026'` 時,23 個測試**全部照樣綠**。這是「規格有要求、實作有做對、但防護網在今天完全無效」的漏網,而且會在 2027-01-01 才於線上爆炸。
- **Fix:** 新增測試,以 `vi.useFakeTimers({ toFake: ['Date'] })` + `vi.setSystemTime(new Date(2029, 5, 15))` 把系統年度推到 2029,斷言 chip 標籤為 `2029`、`dateFrom` 起於 `2029-01-01`、`dateTo` 起於 `2030-01-01`、且 `dateFrom` 不含 `2026`。重新套用同一變異確認翻紅(1 failed),還原後 23 passed。
- **Files modified:** `vue-app/src/pages/Trades.test.ts`
- **Committed in:** `c4abd7e`

### 2. [計畫內選項的具體選擇] 三個 task 合為單一實作 commit

三個 task 都以同一個新檔 `Trades.test.ts` 為載體,紅燈在同一次執行中一起出現。列於此處僅為可追溯性,非行為偏離。

---

**Total deviations:** 1 auto-fixed(Rule 2:規格有要求但防護網在當年度無效)
**Impact on plan:** 對應表六行、D-15 兩條規則、D-10 三項驗收全部照 plan 落地,無 scope creep。未碰 `Overview.vue` / `Positions.vue`(03-03 / 03-04 成果完整保留)、未碰任何既有測試檔。

## Known Limitations(誠實揭露)

1. **`row :key` 的斷言是結構性的,不是行為性的。** plan `<behavior>` 要求「row key 不重複」。我先嘗試以 DOM 節點識別(重取後同內容不同 id 的列是否被移動)做行為斷言,但本頁在每次重取時會經過 `loading` 狀態、整張表被 `v-if` 拆掉重建,DOM 節點本來就不會保留,該手法對本設計不成立。Vue 對重複 key 也只發 dev warning、仍會渲染兩列,因此純 DOM 計數無法分辨。最終改用本專案既有的 `?raw` 手法斷言 `:key="tr.id"`(與 PORT-04 的 `useMockPortfolioStore` 斷言同一模式)。**這條斷言對「改回多欄拼接 key」有效,但對「改成別的唯一欄位」無感。**
2. **未與 Plan 01 的真實後端做端到端對帳。** 上方 §(4) 的查核是「前端實際送出的 URL」對「03-01-SUMMARY 記錄的契約」的逐項比對,不是跑起後端打真實請求。Phase 3 全部前端測試皆 mock fetch;真實整合要到 Phase 5 的瀏覽器流程才會發生。若後端契約日後變動,`portfolioApi.ts` 的 `buildQueryString` 是唯一修改點,測試會即時紅。
3. **API mode 的列不帶 `fresh` 高亮。** 與 03-04 的 Positions 同一理由:API mode 沒有成交事件來源。Phase 4 接 post-trade refetch 時再補。此行為未加測試鎖定(Positions 有;Trades 因 API tbody 根本沒有 `:class` 綁定,mutation 需要「憑空加一行」才會出現)。

## Issues Encountered

1. **`flushAsync()` 預設 6 輪不足以跑完連鎖請求。** 溢出回退(2 個連鎖 fetch)與 CSV 匯出(3 個循序 fetch)在預設輪次下只跑完一半,測試會誤判為「請求沒發出」。處置:這些案例改用 `flushAsync(20~40)`。**這是測試輔助的問題,不是實作問題** —— 請求確實有發出,只是還在 microtask 佇列裡。
2. **stubbing 全域 `URL` 會打壞假後端。** task4 用 `vi.stubGlobal('URL', { createObjectURL, revokeObjectURL })` 攔截下載,但我的假後端要用 `new URLSearchParams` / `new URL` 解析 query。處置:改成只覆寫真實 `URL` 上的兩個靜態方法,並在 `afterEach` 還原。
3. **溢出防迴圈測試最初以「頁碼」腳本化 fetch,導致情境對不上。** 頁碼會被回退邏輯重複請求,同一頁碼要回不同結果。處置:改以「第幾次呼叫」腳本化。
4. **CSV 內容斷言的 fixture 日期算錯**(`100 % 28 + 1 = 17` 我寫成 16)。純測試期望值筆誤,一次修正。

## Verification Evidence

| Gate | 指令 | 結果 |
|------|------|------|
| Task 1/2/3 RED | `npm test -- src/pages/Trades.test.ts` | **21 failed \| 1 passed (22)** |
| Task 1/2/3 GREEN | `npm test -- src/pages/Trades.test.ts` | **22 passed (22)** |
| Focused gate | `npm test -- src/pages/Trades.test.ts src/task4.test.ts` | **35 passed (35)**(task4 未改動) |
| 補測試後 GREEN | `npm test -- src/pages/Trades.test.ts` | **23 passed (23)** |
| 補測試後 RED 驗證 | 重新套用 M6 變異 | **1 failed \| 22 passed (23)** |
| Plan gate 1 | `npm test` | **31 files / 233 passed** |
| Plan gate 2 | `VITE_DATA_MODE=api npm test` | **31 files / 233 passed** |
| Plan gate 3 | `npm run build`(vue-tsc --noEmit && vite build) | exit 0,built in 1.10s |

基線 210 → 233,淨增 23。既有測試零修改、零紅燈。

### 斷言有效性(mutation testing,19 個變異)

每次變異後 `git checkout -- src/pages/Trades.vue` 還原(全部在 `ef1fb35` 之後執行,還原點正確):

| # | 變異 | 結果 | 翻紅數 |
|---|------|------|-------|
| M1 | 移除 D-15 的 `pageNo.value = 0` 重置 | ✅ 抓到 | 1 |
| M2 | 回退時傳 `loadTrades(true)`(破壞防迴圈) | ✅ 抓到 | 1 |
| M3 | CSV 只匯出當前頁(`while (false)`) | ✅ 抓到 | 2 |
| M4 | CSV 不帶 `queryParams()`(忽略篩選/排序) | ✅ 抓到 | 1 |
| M5 | API mode 也渲染 Dividend chip | ✅ 抓到 | 1 |
| M6 | 年度 chip 寫死回 `'2026'` | ❌ **未抓到** → 補測試後 ✅ | 0 → 1 |
| M7 | 年界改用 `toISOString()`(UTC 陷阱) | ✅ 抓到 | 1 |
| M8 | 列表錯誤區塊不再渲染 traceId | ✅ 抓到 | 1 |
| M9 | 日期年界寫死 `new Date(2026, 0, 1)`(chip 仍動態) | ✅ 抓到 | 1 |
| M10 | 把 `useMockPortfolioStore` import 加回來 | ✅ 抓到 | 1 |
| M11 | 不明確送出 `sort`/`direction` | ✅ 抓到 | 3 |
| M12 | API 路徑改回 client-side 篩選(T-03-15 核心缺陷) | ✅ 抓到 | 1 |
| M13 | 匯出錯誤區塊不再渲染 traceId | ✅ 抓到 | 1 |
| M14 | next 按鈕禁用條件改成永不禁用 | ✅ 抓到 | 1 |
| M15 | mock chips 拿掉 Dividend | ✅ 抓到 | 2 |
| M16 | 完全移除溢出回退 | ✅ 抓到 | 2 |
| M17 | mock mode 也渲染分頁列 | ✅ 抓到 | 2 |
| M18 | 列表 `size` 改 50 | ✅ 抓到 | 1 |
| M19 | 匯出 `size` 改 20 | ✅ 抓到 | 2 |

**18/19 一次抓到,唯一漏網的 M6 已補測試並重新驗證會紅。** 最終工作區乾淨(`git status --short` 無輸出),focused gate 重跑 36 passed。

## Threat Model 落地

| Threat ID | 處置 | 落地 |
|-----------|------|------|
| T-03-15(client-side 篩選殘留在 API mode) | mitigate | 每個 chip / 表頭 / 換頁都有 fetch URL 逐參數斷言;`apiTrades` 直接是回應的 `items`,沒有任何本地 filter/sort。M12 變異(偷偷加回 client-side 篩選)被抓到 |
| T-03-16(D-15 頁碼溢出) | mitigate | 兩個獨立測試(回退成功、防迴圈)以完整 fetch page 序列斷言;M1 / M2 / M16 三個變異證明有效 |
| T-03-17(CSV 部分匯出) | mitigate | 第 2 頁失敗案斷言 `createObjectURL` **未被呼叫**、`blobs` 為空、錯誤區塊有 code + traceId;M3 變異證明「只匯出當前頁」會紅 |
| T-03-18(載入/匯出失敗不可追查) | mitigate | 列表與匯出各自斷言 `code` + `traceId`,並斷言後端 `message`(`backend said no`)**不**外洩;M8 / M13 兩個變異證明有效 |
| T-03-19(CSV 循環請求量) | mitigate | `size=100` 有逐請求斷言(M19 變異抓到改成 20);匯出期間按鈕 `disabled`;請求數實測見 §(3) |
| T-03-SC(npm 安裝) | accept | 零新套件 |

**新增威脅面掃描:** 無。零新端點(只消費 Plan 02 已存在的 `listTrades`)、零新套件、頁面不直呼 `fetch`(一律經 `PortfolioApi`)、無新的信任邊界。CSV 內容沿用既有 `csvCell()` 跳脫(逗號/引號/換行有專屬測試),未自行造輪子。

## D-13 邊界

Trades 的載入失敗與匯出失敗都只 inline 呈現,測試另斷言 `[data-testid="session-banner"]` 不在 DOM。本頁未注入 `configureApiClientSessionHandlers` spy(03-03 / 03-04 已對同一條 apiClient 通道做過此驗證,且 Trades 走的是同一個 `apiPaginatedRequest`);若要更嚴格,可比照 Positions 補一個 spy 案。

## User Setup Required

None — 零新套件、零設定變更。

## Next Phase Readiness

- **Phase 3 前端出場條件全部達成**:`npm test` / `VITE_DATA_MODE=api npm test` / `npm run build` 三道 gate 皆綠,三個頁面(Overview / Positions / Trades)都不再 import mock store。
- **Phase 3 整體出場條件(judgment §8,跨 repo)**:後端 `./mvnw -pl stock-start -am verify` 由 Plan 01 覆蓋(80/80 綠、BUILD SUCCESS);前端如上。**兩邊各自綠,但兩邊之間尚未跑過真實整合** —— 見 Known Limitations 第 2 點。
- **給 Phase 4(交易建立)的交接:**
  1. 下單成功後的 refetch 接點是 `loadTrades()`;要重置到第一頁請走 `applyQueryChange(() => {})`(它會把 `pageNo` 歸零),不要直接改 `pageNo`。
  2. `fresh` 高亮的接法與 Positions 相同:高亮來源用「本次下單的 symbol」(來自下單請求本身),不要從資料反推。
  3. `queryParams()` 是篩選/排序的單一來源,新增任何查詢條件都改這一處,列表與 CSV 會同時生效。
- **給未來的提醒(值得寫進 LESSONS):** 凡是與「當前年/月/日」綁定的邏輯,測試期望值不得由同一個 `new Date()` 推導 —— 否則寫死值與動態值在今天完全等價,測試永遠是綠的。用 `vi.setSystemTime` 推到別的時間點才有意義。

## Self-Check: PASSED

- 2 個宣稱的檔案全數存在於 `D:/end/workspace/vue/stock-v2/vue-app/`(`src/pages/Trades.vue`、`src/pages/Trades.test.ts`)。
- 2 個 commit hash(`ef1fb35` / `c4abd7e`)在前端 repo `git log` 中可查。
- `Trades.vue` 內 `useMockPortfolioStore` 與 `stores/mockPortfolio` 出現次數皆為 **0**(`grep -c` 實測),`getRuntimeApiClients` 出現 2 次;由測試持續鎖定,M10 變異證明會紅。
- 無 stub / placeholder:API mode 不存在「渲染空值或假值」的路徑 —— 沒有資料時走 loading / empty / error 三態之一。唯一的空值呈現是 `note` 為 null 時的 `—`(既有行為)。
- 無 TODO / FIXME 新增。
- 臨時 contract probe 檔(`src/pages/__contract_probe.test.ts`)執行後已刪除,未進 commit;`git status --short` 無輸出,工作區乾淨。

---
*Phase: 03-portfolio-read-api-mode*
*Completed: 2026-07-26*
