---
phase: 03-portfolio-read-api-mode
plan: 04
subsystem: ui
tags: [vue, typescript, vitest, portfolio, positions, api-adapter]

# Dependency graph
requires:
  - phase: 03-portfolio-read-api-mode
    provides: PortfolioApi 介面(getSummary / listHoldings / live)、pageApiClients.portfolio 註冊、11 組狀態 i18n key(Plan 02)
  - phase: 03-portfolio-read-api-mode
    provides: 區塊級四態樣板、BlockState<T> union + computed 投影、`?raw` 原始碼斷言手法(Plan 03)
provides:
  - Positions 頁的雙模式實作(API mode 真實 holdings/summary,mock mode 現狀)
  - D-04「後端值 ≠ 前端算式」的 fixture 級測試手法(marketValue / costBasis / unrealizedPnl / roi / holdingCount 五處)
  - Q5 大清單實測數據與建議分頁門檻
affects: [03-05 Trades 頁改寫, Phase 4 post-trade refetch]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "衍生欄位無可信分母時顯示破折號,而不是退回 0(不編造數字)"
    - "同一表格以兩個 tbody 分流 mock / API 列,thead 與排序狀態共用"
    - "mutation testing 作為測試有效性驗收,未被抓到的變異即補測試"

key-files:
  created:
    - ../../vue/stock-v2/vue-app/src/pages/Positions.test.ts
  modified:
    - ../../vue/stock-v2/vue-app/src/pages/Positions.vue

key-decisions:
  - "weight 在 summary 未載入 / 分母為 0 時顯示 `—` 而非 0.0%,避免把「不知道」呈現成「權重為零」"
  - "priceAsOf 文案放在 title 屬性、儲存格只顯示時間戳,維持表格密度(D-03 的資訊仍可觸及)"
  - "Top movers 卡在 API mode 改佔 span 12(權益曲線隱藏後不留空欄),沿用 03-03 的 kpiSpan 先例"
  - "Top movers 卡在 holdings 未載入/失敗時整張不渲染,不重複一份錯誤 UI(錯誤呈現集中在表格區塊)"
  - "API tbody 不綁 fresh class(lastFill 恆為 false),Phase 4 接 post-trade refetch"

patterns-established:
  - "『後端值 ≠ 前端算式』fixture:刻意讓 marketValue ≠ qty×price、costBasis ≠ qty×avg、unrealizedPnl ≠ 兩者相減、roi ≠ pnl/cost、holdingCount ≠ 實際列數,使任何平行重算的 regression 必然翻紅"

requirements-completed: [PORT-02, PORT-05]

# Metrics
duration: 35min
completed: 2026-07-26
---

# Phase 03 Plan 04: Positions 雙模式改寫 Summary

**Positions 從直接 import mock store 改為經 `getRuntimeApiClients().portfolio`;API mode 的每一個數字都是後端欄位(唯一例外 weight 衍生自 `marketValue / summary.totalMarketValue`),sector 卡 / 權益曲線 / 時光機 / Sharpe·年化·MaxDD 假 KPI 全部隱藏;mock mode 行為零變化。**

## Performance

- **Duration:** ~35 min(含 13 次 mutation testing 與一次工作區還原事故,見 Issues)
- **Tasks:** 2(TDD red→green 在同一輪收斂)
- **Files modified:** 2(1 新增 / 1 修改,皆在 sibling 前端 repo)
- **Tests:** 189 → 210(淨增 21),雙模式皆綠

## 處置清單逐項落地結果(plan `<objective>` 表格逐項打勾)

| 區塊 | 計畫處置 | 落地結果 | 證據(測試名) |
|------|---------|---------|--------------|
| 持倉表 | **真實資料**:HoldingDto 映射 | ✅ symbol / assetName / totalQuantity / avgCost / marketPrice / marketValue / unrealizedPnl / roi 逐欄映射;`:key` 用 `assetId` | 「持倉列的市值/損益/ROI 一律用後端欄位,不做 qty×price 之類的前端重算(D-04)」 |
| 彙總條(6 張 stat 卡) | **真實資料**:六欄全來自 summary | ✅ 市值←totalMarketValue、未實現←unrealizedPnl、ROI←roi×100、已實現←realizedPnl、總損益←totalPnl、成本←totalCostBasis | 「彙總條六張卡全部來自 summary 後端欄位…」 |
| Sharpe / 年化 / MaxDD 假 KPI | **隱藏**,位置由真實欄位取代 | ✅ 三個標籤與 `1.68` / `+17.4%` / `-3.4%` 寫死值皆不在 DOM | 同上(同一測試的後半段) |
| 權益曲線圖 + range 選擇器 | **隱藏** | ✅ `assetTrend` 標題、`.seg-btn`、`svg`、六個 range 按鈕逐一斷言不存在 | 「隱藏 sector 卡、權益曲線 + range 選擇器、時光機(D-01 更正 / D-16)」 |
| 時光機(按鈕 + scrubber + 表格 scrub) | **隱藏** | ✅ `Time machine` 文案、`.btn-tm`、`.tm-bar`、`.tm-slider` 皆不存在 | 同上 |
| Sector breakdown 卡 | **隱藏**(D-01 更正) | ✅ `By sector` 標題與 `.sec-fill` 皆不存在 | 同上 |
| Top movers 卡 | **真實推導**:unrealizedPnl 絕對值前 4 | ✅ 5 筆持倉(pnl 10 / -900 / 300 / -50 / 7)渲染出 `['ZB','ZC','ZD','ZA']` | 「Top movers 依後端 unrealizedPnl 絕對值排序取前 4(D-16 例外:真實推導)」 |
| weight 欄 + 表頭排序 | **真實推導** + client-side 排序保留 | ✅ 750/250 + totalMarketValue=1000 → `75.0%` / `25.0%`;點 P&L 表頭依 unrealizedPnl 降冪→升冪切換 | 「weight 由 marketValue / summary.totalMarketValue 衍生(D-04 明文例外)」、「點 P&L 表頭以後端 unrealizedPnl 排序,再點一次反轉」 |
| 標題列彙總($總值 · N 檔) | **真實資料** | ✅ `$4,242 · 7 holdings`,而畫面只有 1 列 —— 證明 holdingCount 讀後端而非 `holdings.length` | 「標題列彙總顯示 summary 的 totalMarketValue 與 holdingCount(D-04)」 |

**額外落地(plan `<behavior>` 明列但不在表格內):**

| 項目 | 結果 | 證據 |
|------|------|------|
| priceTime 顯示(D-03) | ✅ 每列 price 欄下方顯示本地短格式時間戳;`null` 退回 `—`(mock 端恆為 null 的已知限制) | 「每列 price 欄顯示 priceTime 行情時間,null 時退回破折號(D-03)」 |
| PORT-04 不 import mock store | ✅ `Positions.vue` 內 `useMockPortfolioStore` / `stores/mockPortfolio` 出現次數皆為 **0**(`grep -c` 實測) | 「Positions 不 import mock store,一律經 getRuntimeApiClients」 |

**mock mode 鏡像驗證:** sector 卡、`.sec-fill`、`assetTrend`、`.seg-btn` 恰為 `['1D','1W','1M','3M','1Y','All']`、`Time machine` 按鈕、六張含 Sharpe/Annualized/Max DD 的 stat 卡、`1.68` 寫死值全部仍在 —— 與 API mode 的隱藏清單互為鏡像。

## D-04「後端值 ≠ 前端算式」的實質證據(T-03-11)

acceptance criteria 要求 fixture 至少覆蓋三欄。實際覆蓋 **五處**,每一處的後端值都刻意與任何前端算式不相等:

| 欄位 | 後端 fixture 值 | 前端若重算會得到 | 斷言 |
|------|---------------|----------------|------|
| `marketValue` | 999 | `10 × 100 = 1000` | 列內含 `$999`,且 **not** 含 `$1,000` |
| `costBasis` | 777 | `10 × 20 = 200` | 經 `unrealizedPnl` / `roi` 兩條路徑間接鎖定(見下兩列) |
| `unrealizedPnl` | 555 | `999-777=222` 或 `1000-200=800` | 列內含 `+$555`,且 **not** 含 `$222` / `$800` |
| `roi` | 0.0117 → `+1.17%` | `555/777 = +71.43%` | 列內含 `+1.17%`,且 **not** 含 `+71.43%` |
| `holdingCount` | 7 | `holdings.length = 1` | 標題列含 `7 holdings`,同時斷言只有 1 列 |
| 彙總條六欄 | 4242 / 3131 / 222 / 1111 / 1333 / 0.4258 | 由單筆持倉加總得到的任何值 | 六張卡逐一比對 summary 欄位 |

weight 的分母同樣被鎖定:單筆持倉 `marketValue=999` 對 `totalMarketValue=4242` → `23.6%`;若改用「本頁 holdings 市值總和」當分母會得到 `100.0%`,測試明文斷言 **not** 含 `100.0%`。

## Q5 大清單觀察(plan `<output>` 第 2 項)

**實測筆數(mock 種子資料):6 筆**(`AAPL` / `NVDA` / `2330.TW` / `BTC` / `ETH` / `MSFT`),渲染瞬時、無感知延遲。這是目前唯一有資料的來源 —— 後端 `GET /portfolio/holdings` 的真實筆數取決於使用者持倉,無上界。

為了給未來的分頁決策一個有數字的基礎,另以臨時 probe(執行後已刪除,未進 commit)量測 API mode 在 jsdom 下的線性成本:

| holdings 筆數 | 渲染列數 | mount+render | DOM 節點數 | JSON payload |
|---:|---:|---:|---:|---:|
| 6 | 6 | 256 ms | 174 | 1.5 KB |
| 100 | 100 | 525 ms | 1,678 | 25 KB |
| 500 | 500 | 2,536 ms | 8,078 | 129 KB |
| 1,000 | 1,000 | 4,011 ms | 16,078 | 260 KB |
| 2,000 | 2,000 | 8,060 ms | 32,078 | 529 KB |

**讀法(重要):jsdom 的絕對時間不等於瀏覽器時間**(jsdom 建 DOM 通常比真實瀏覽器慢一個量級),但兩個可直接外推的常數是:**每筆持倉約 16 個 DOM 節點、約 265 bytes 未壓縮 payload**,且成本嚴格線性(無 N² 行為 —— client-side 排序是 `O(n log n)`,不是瓶頸)。

**建議的分頁討論門檻:**

- **< 200 筆:** 現狀完全足夠,不需任何處理(約 3.2k 節點、53 KB)。
- **≥ 500 筆(約 8k 節點、130 KB):** 建議**開始討論**。此時單次回應已達百 KB 級,首屏會出現可感知的延遲,且 `.bar-fill` 的 CSS transition 會同時觸發 500 次。
- **≥ 1,000–2,000 筆(16k–32k 節點、260–530 KB):** 建議**必須處理**。可選路徑依成本排序:(1) 前端虛擬捲動(不動後端契約);(2) 後端補 `page`/`size`(需重新檢視 D-04 —— 分頁後 weight 的分母仍必須是 summary.totalMarketValue,這點反而因為 summary 是獨立端點而天然成立);(3) 表格欄位精簡以壓低每列節點數。

本階段依 CONTEXT 鎖定**不改後端**、且 T-03-14 的處置是 `accept`;loading 骨架列的筆數固定為 6、不隨資料量變動,已在測試中鎖定(「載入中兩個區塊各自顯示骨架/loading,不依賴資料筆數」)。

## lastFill 在 API mode 的處置(plan `<output>` 第 3 項)

**恆為 false —— API 路徑的 `<tbody>` 完全不綁 `fresh` class。**

理由:`lastFill` 是 mock store 在 `executeOrder()` 當下寫入的成交事件,API mode 沒有等價來源(讀取端點只回持倉快照,沒有「剛剛成交了什麼」的資訊)。硬要在前端猜(例如比對前後兩次 holdings 差異)會在「使用者在另一個分頁下單」「後端行情更新」等情境下亮錯行 —— 那是編造事件,與 D-16 的原則衝突。

**Phase 4 的接法(交易建立進來時):** 下單成功後由 Positions 主動 `loadHoldings()` 重取,並以「本次下單的 symbol」作為高亮來源(來自下單請求本身,而不是從資料反推)。屆時把 API tbody 的 `:class` 補上即可,表格其餘部分不需改動。

此行為現在有測試鎖定(「API mode 的持倉列不帶 lastFill 高亮」)—— 該測試是 mutation testing 過程中補上的,見下方 Deviations 第 1 條。

mock mode 的 `lastFill` 高亮**完全未受影響**:資料源由 `portfolio.lastFill` 換成 `api.live.lastFill`(等價委派),`executeOrder` 後新持倉列仍帶 `fresh`,有測試證據。

## Task Commits

前端 repo(`D:/end/workspace/vue/stock-v2`,branch `feature/phase-03-portfolio-read`):

1. **Task 1 + Task 2:`feat(positions): API mode 改走 portfolio service,持倉與彙總一律讀後端欄位`** — `40a4f2b`(+886 / -45,2 檔)
2. **`test(positions): 鎖定 API mode 持倉列不帶 lastFill 高亮`** — `587e84e`(+10,1 檔)

後端 repo(`D:/end/workspace/java/stock-web-v2`):本 SUMMARY 的 docs commit。

_TDD 節奏:先寫 `Positions.test.ts`(API mode 13 個 + mock mode 6 個)跑出紅燈 **17 failed / 3 passed (20)**,再改寫 `Positions.vue` 至 **20 passed**。Task 1 與 Task 2 的測試同屬一個檔案、在同一輪 red→green 收斂,拆成兩個 commit 會產生「測試已綠但刻意只 commit 一半」的假中間態,故合為一個原子 commit(與 03-02 / 03-03 的處理一致)。第二個 commit 是 mutation testing 後補的斷言,獨立成 `test(...)` commit。_

## Files Created/Modified

- `vue-app/src/pages/Positions.vue` — 移除 mock store import;新增 `BlockState<T>` 雙狀態機(`summaryState` / `holdingsState`)、`loadSummary` / `loadHoldings` 各自獨立;模板依 `live` 分流並依處置清單 `v-if` 切換區塊;表格以兩個 `<tbody>` 分流、`<thead>` 與排序狀態共用;新增區塊狀態與骨架列樣式
- `vue-app/src/pages/Positions.test.ts` — 新增,21 個測試(API mode 真實映射 10 / API mode 四態 5 / mock mode 回歸 6)

**既有測試零修改。** `src/task4.test.ts` **一字未改**(其「renders empty Positions without NaN」在新結構下一次通過),`src/App.test.ts` 也未動 —— 與 03-03 不同,本 plan 沒有產生任何既有測試的 fixture 缺口。

## Decisions Made

- **weight 無可信分母時顯示 `—` 而非 `0.0%`。** summary 失敗但 holdings 成功時(D-11 允許的狀態組合),weight 的分母不存在。退回 0 會讓使用者看到「這檔佔比 0%」這個明確錯誤的陳述;`—` 則誠實表達「現在算不出來」。這與 D-16「後端推導不出來的就不呈現」是同一原則,只是套用在欄位層級。有測試鎖定,並在 summary retry 成功後轉回 `23.6%`。
- **`priceAsOf` 文案放 `title` 屬性,儲存格只顯示時間戳。** 「Price as of 2026-07-24 21:45」放進本來就有 8 欄的表格會嚴重擠壓版面;時間戳在價格正下方,語意在情境中自明,標籤仍可觸及(hover / 輔助技術)。i18n key 因此仍有實際用途,未變成死 key。
- **Top movers 卡在 API mode 佔 span 12。** 權益曲線(span 8)隱藏後,若 movers 仍佔 span 4 會留下 8 欄空白。沿用 03-03 `kpiSpan` 3→6 的先例。
- **Top movers 卡在 holdings 未載入/失敗時整張不渲染。** 它是 holdings 的衍生視圖,錯誤與重試已集中在表格區塊;再放一份會出現兩顆語意相同的重試鈕。
- **`data-testid` 加在兩種 mode 共用的 stat 卡與表格列上**(`positions-stat` / `positions-row`)。這是本 plan 對 mock mode DOM 的**唯一**改動(純新增屬性,無視覺/行為變化),換來兩邊都能用「卡片數量」「列數」「class 名單」做精確斷言。既有測試全部只看 textContent 與 class,故零影響。
- **API 路徑的排序值一律取 HoldingDto 欄位**,`weight` 鍵取衍生值(與 `marketValue` 同單調,但寫成衍生值以免日後分母改變時排序悄悄失準)。holdings 不分頁,client-side 排序正確 —— CONTEXT specifics 明言勿一併套用後端排序(那是 trades 的情境)。

## Deviations from Plan

### 1. [Rule 2 - Missing Critical] 補上「API mode 不帶 lastFill 高亮」的斷言

- **Found during:** Task 2 之後的 mutation testing(第 10 個變異)
- **Issue:** plan `<action>` 明文要求「該 class 綁定在 API 路徑恆為 false」,但我寫的 20 個測試沒有任何一個斷言它。實測:刻意把 `:class="{ fresh: true }"` 塞進 API `<tbody>`,**測試全綠**。這是規格有要求、實作有做對、但沒有防護網的典型漏網。
- **Fix:** 新增測試斷言 API mode 所有列都不帶 `fresh` class;重新套用同一變異確認翻紅(1 failed),還原後 21 passed。
- **Files modified:** `vue-app/src/pages/Positions.test.ts`
- **Committed in:** `587e84e`

### 2. [計畫內選項的具體選擇] weight 的 `—` 退回、priceAsOf 的 title 呈現、movers 卡的 span 與隱藏條件

plan 把「loading 骨架樣式、換頁按鈕版面」等實作細節交給 executor(CONTEXT `Claude's Discretion`)。上列四項屬同一範疇,列於 Decisions Made,非行為偏離。

### 3. [計畫內選項的具體選擇] Task 1 與 Task 2 合為單一 commit

兩個 task 都以同一個新檔 `Positions.test.ts` 為載體,red 燈在同一次執行中一起出現。列於此處僅為可追溯性。

---

**Total deviations:** 1 auto-fixed(Rule 2:規格有要求但缺測試覆蓋)
**Impact on plan:** 處置清單九項全部照 plan 落地,無 scope creep。未碰 `Trades.vue`(03-05 範圍完整保留)、未碰 `Overview.vue`(03-03 成果保留)、未碰任何既有測試檔。

## Issues Encountered

1. **工作區還原事故(過程問題,已完全復原)。** 第一輪 mutation testing 時,我在**實作尚未 commit** 的狀態下用 `git checkout -- src/pages/Positions.vue` 還原變異 —— 該指令把檔案還原到 HEAD,也就是**改寫前的舊版**,等於清掉了整份新實作。處置:立即以相同內容重寫該檔、跑 focused gate 確認 33 passed、**先 commit 再繼續 mutation testing**。後續 12 次變異都在有 commit 的基礎上進行,還原安全。
   **教訓(值得寫進 LESSONS):mutation testing 前必須先有 commit,否則 `git checkout --` 的還原點是舊版而不是你要保護的新版。**
2. **測試輔助函式的選擇器初次寫錯(非實作問題)。** `.mover` 內取代號原本用 `div[style*="font-weight:500"]`,但 Vue 會把 inline style 正規化成 `font-weight: 500`(冒號後有空格),選不到。改用結構選擇器 `.mtag + div > div`。這是 GREEN 階段唯一一個非實作原因的紅燈。
3. **無其他問題。** 雙模式全量測試與 build 在實作完成後首次執行即綠。

## Verification Evidence

| Gate | 指令 | 結果 |
|------|------|------|
| Task 1/2 RED | `npm test -- src/pages/Positions.test.ts` | **17 failed \| 3 passed (20)** |
| Task 1/2 GREEN | `npm test -- src/pages/Positions.test.ts` | **20 passed (20)** |
| Focused gate | `npm test -- src/pages/Positions.test.ts src/task4.test.ts` | **33 passed (33)**(task4 未改動) |
| 補測試後 RED 驗證 | 重新套用 M10 變異 | **1 failed \| 20 passed (21)** |
| Plan gate 1 | `npm test` | **30 files / 210 passed** |
| Plan gate 2 | `VITE_DATA_MODE=api npm test` | **30 files / 210 passed** |
| Plan gate 3 | `npm run build`(vue-tsc --noEmit && vite build) | exit 0,built in 1.52s |

基線 189 → 210,淨增 21。既有測試零修改、零紅燈。

### 斷言有效性(mutation testing,13 個變異)

刻意破壞實作、確認測試會紅,避免「假性通過」。每次變異後 `git checkout -- src/pages/Positions.vue` 還原(在 `40a4f2b` commit 之後執行,還原點正確):

| # | 變異 | 結果 | 翻紅的測試 |
|---|------|------|-----------|
| M1 | Sector 卡拿掉 `v-if="live"`(不再隱藏) | ✅ 抓到 | 隱藏清單案(1 failed) |
| M2 | holdings 錯誤區塊不再渲染 traceId | ✅ 抓到 | holdings 失敗案(1 failed) |
| M3 | weight 改用 `Σ(qty×price)` 當分母(繞過後端 marketValue 與 summary) | ✅ 抓到 | 3 failed(D-04 主案、weight 案、summary 失敗案) |
| M4 | 把 `useMockPortfolioStore` import 加回 | ✅ 抓到 | PORT-04 案(1 failed) |
| M5 | 市值改回 `totalQuantity × marketPrice` | ✅ 抓到 | D-04 主案(1 failed) |
| M6 | 標題列 `holdingCount` 改成 `holdings.length` | ✅ 抓到 | 標題列彙總案(1 failed) |
| M7 | ROI 改回 `unrealizedPnl / costBasis` 前端重算 | ✅ 抓到 | D-04 主案(1 failed) |
| M8 | 重試 holdings 時連帶重打 summary(破壞 D-11 隔離) | ✅ 抓到 | 2 failed(兩個 retry 案的 fetch 次數斷言) |
| M9 | 移除 priceTime 副文字 | ✅ 抓到 | D-03 案(1 failed) |
| M10 | API tbody 加上 `:class="{ fresh: true }"` | ❌ **未抓到** → 補測試後 ✅ | (補測試前全綠;補後 1 failed) |
| M11 | 移除 mock tbody 的 lastFill fresh 綁定 | ✅ 抓到 | executeOrder 高亮案(1 failed) |
| M12 | Top movers 改用原值排序(非絕對值) | ✅ 抓到 | Top movers 案(1 failed) |
| M13 | API 彙總條混入一張 Sharpe 假卡 | ✅ 抓到 | 3 failed(六卡數量 + 兩個四態案) |

**12/13 一次抓到,唯一漏網的 M10 已補測試並重新驗證會紅。** 最終工作區乾淨(`git status --short` 無輸出),focused gate 重跑 21 passed。

## Threat Model 落地

| Threat ID | 處置 | 落地 |
|-----------|------|------|
| T-03-11(前端平行重算後端數值) | mitigate | 五處 fixture 級「後端值 ≠ 前端算式」斷言;M3 / M5 / M6 / M7 四個變異證明任何重算 regression 必然翻紅 |
| T-03-12(合成區塊偽裝成真資料) | mitigate | 隱藏清單逐項斷言:`By sector` / `.sec-fill` / `Equity curve` / `.seg-btn` / `svg` / 六個 range 按鈕 / `Time machine` / `.btn-tm` / `.tm-bar` / `.tm-slider` / `Sharpe` / `Annualized` / `Max DD` / `1.68` / `+17.4%` / `-3.4%` 皆不在 DOM;M1 / M13 變異證明有效 |
| T-03-13(載入失敗不可追查) | mitigate | 兩個區塊的錯誤狀態各自斷言 `code` + `traceId`;另斷言後端 `message`(`backend said no`)**不**外洩;M2 變異證明有效 |
| T-03-14(大量持倉一次渲染) | accept | 本階段不改後端;loading 骨架列筆數固定不隨資料量變動(有測試);Q5 實測數據與門檻建議見上方專節 |
| T-03-SC(npm 安裝) | accept | 零新套件 |

**新增威脅面掃描:** 無。零新端點(只消費 Plan 02 已存在的 `getSummary` / `listHoldings`)、零新套件、頁面不直呼 `fetch`(一律經 `PortfolioApi`)、無新的信任邊界。

## D-13 邊界的實測證據

沿用 03-03 的手法:以 `configureApiClientSessionHandlers({ onRefreshing, onRefreshFailed })` 注入 spy,讓 summary 與 holdings **雙雙 503**,斷言(1)兩個 handler 皆未被呼叫、(2)`[data-testid="session-banner"]` 不在 DOM、(3)兩個 inline 錯誤區塊都在。portfolio 讀取失敗不劫持全域 session 通道。

## User Setup Required

None — 零新套件、零設定變更。

## Next Phase Readiness

- **給 03-05(Trades)的交接:**
  1. 本頁的 holdings **不分頁**,client-side 排序是安全的;`Trades.vue` 是分頁情境,**必須**用後端排序參數(D-05/D-06),不要抄本頁的 `sortedHoldings`。
  2. 四態樣板、`BlockState<T>` + computed 投影、錯誤區塊的 `code + authRequestId + traceId + authRetry` 結構、`?raw` 的 PORT-04 斷言,三個頁面現在完全一致,可直接沿用。
  3. D-02:API mode 要隱藏 Dividend 篩選頁籤(後端 `TradeType` 只有 BUY/SELL)。本頁沒有對應情境,但同一原則。
  4. mock mode 的 `data-testid` 命名慣例已收斂為 `{page}-{thing}` / `{page}-{block}-{state}`。
- **給 Phase 4 的交接:** post-trade refetch 的接點在 `loadHoldings()` 與 API `<tbody>` 的 `:class`,見上方 lastFill 專節。
- **已知限制(非 bug,來自 03-02):** mock mode 的 `realizedPnl` 恆為 0、`priceTime` / `lastUpdated` 恆為 `null`。本頁的 mock 路徑不經 `HoldingDto`,故實際不受影響;API 路徑的 `null priceTime` 已有 `—` 的退回並有測試。
- **尚未做的驗證:** 本頁只跑過前端測試,**未與 Plan 01 的真實後端做端到端對帳**(judgment §8 的「跨 repo 兩邊驗證」在 phase 收尾時仍需補)。若後端 `HoldingDto` 欄位名有異動,`Positions.vue` 的映射與 `apiTypes.ts` 需同步,測試會即時紅。

## Self-Check: PASSED

- 2 個宣稱的檔案全數存在於 `D:/end/workspace/vue/stock-v2/vue-app/`(`src/pages/Positions.vue`、`src/pages/Positions.test.ts`)。
- 2 個 commit hash(`40a4f2b` / `587e84e`)在前端 repo `git log` 中可查;`--stat` 顯示分別為 2 檔 +886/-45 與 1 檔 +10。
- `Positions.vue` 內 `useMockPortfolioStore` / `stores/mockPortfolio` 出現次數為 **0**(`grep -c` 實測),由測試持續鎖定,M4 變異證明會紅。
- 無 stub / placeholder:API mode 不存在「渲染空值或假值」的路徑 —— 沒有資料時走 loading / empty / error 三態之一;唯一的「空值呈現」是 weight 的 `—`,那是刻意的誠實退回並有測試鎖定,不是 stub。
- 無 TODO / FIXME 新增。
- Q5 probe 檔案(`src/pages/q5probe.test.ts`、`q5-results.txt`)為臨時量測用,執行後已刪除;`git status --short` 無輸出,工作區乾淨。

---
*Phase: 03-portfolio-read-api-mode*
*Completed: 2026-07-26*
