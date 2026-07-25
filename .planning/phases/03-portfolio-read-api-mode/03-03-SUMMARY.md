---
phase: 03-portfolio-read-api-mode
plan: 03
subsystem: ui
tags: [vue, typescript, vitest, portfolio, overview, api-adapter]

# Dependency graph
requires:
  - phase: 03-portfolio-read-api-mode
    provides: PortfolioApi 介面(getSummary / listTrades / live)、pageApiClients.portfolio 註冊、11 組狀態 i18n key(Plan 02)
provides:
  - Overview 頁的雙模式實作(API mode 真實 summary/trades,mock mode 現狀)
  - 區塊級四態狀態機樣板(loading / loaded / empty / error+retry,error 帶 code + traceId)供 03-04 / 03-05 沿用
  - data-testid 命名慣例(overview-kpi / overview-trade-row / overview-{block}-{state})
affects: [03-04 Positions 頁改寫, 03-05 Trades 頁改寫]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "頁面以 `const live = api.live` 分流,mock 路徑零網路、API 路徑純快照 + 明確 refetch"
    - "區塊級 BlockState<T> discriminated union + 三個 computed 投影(loading / error / data),避免在模板做型別窄化"
    - "測試以 Vite `?raw` 匯入 SFC 原始碼字面,對 import 做結構性斷言(不需 @types/node)"

key-files:
  created:
    - ../../vue/stock-v2/vue-app/src/pages/Overview.test.ts
  modified:
    - ../../vue/stock-v2/vue-app/src/pages/Overview.vue
    - ../../vue/stock-v2/vue-app/src/App.test.ts

key-decisions:
  - "totalPnl 落在總報酬卡副標(帶正負號金額),holdingCount 落在總資產卡副標(`{n} Positions`)——D-14 授權 planner 決定,plan 已指定,executor 照做"
  - "holdingCount 副標複用既有 i18n key `positions`,不新增文案 key"
  - "totalCostBasis / realizedPnl / unrealizedPnl 三欄本頁不呈現,依 CONTEXT D-14 留給 Positions 頁彙總條(03-04)"
  - "KPI 卡 grid span 由 mock 的 3 改為 API mode 的 6(兩張卡仍佔滿一列),避免留白"
  - "錯誤區塊沿用 SessionBanner 的 code/traceId 呈現慣例與 authRetry 文案,但不共用元件(D-13 邊界)"

patterns-established:
  - "區塊錯誤只 inline 呈現,絕不觸發全域 session 通道——以 configureApiClientSessionHandlers spy 未被呼叫作為測試證據"

requirements-completed: [PORT-01, PORT-05]

# Metrics
duration: 22min
completed: 2026-07-25
---

# Phase 03 Plan 03: Overview 雙模式改寫 Summary

**Overview 從直接 import mock store 改為經 `getRuntimeApiClients().portfolio`,API mode 只呈現後端真的有的兩張 KPI 與 `/trades?page=0&size=5` 的近期交易,其餘合成/寫死區塊全部不渲染;mock mode 行為零變化。**

## Performance

- **Duration:** ~22 min
- **Tasks:** 2(TDD red→green 在同一輪收斂,見下方 commit 說明)
- **Files modified:** 3(1 新增 / 2 修改,皆在 sibling 前端 repo)
- **Tests:** 174 → 189(淨增 15),雙模式皆綠

## 處置清單逐項落地結果(plan `<objective>` 表格逐項打勾)

| 區塊 | 計畫處置 | 落地結果 | 證據(測試名) |
|------|---------|---------|--------------|
| 總資產 KPI 卡 | 真實資料 `totalMarketValue`,副標 `holdingCount` | ✅ `$` + `fmtNum(totalMarketValue, 0)`;副標 `{holdingCount} {t(lang,'positions')}`;假副標 `+1.04% vs yesterday` 已消失 | 「只渲染兩張有後端資料的 KPI 卡,數值來自 summary(D-14)」 |
| 總報酬 KPI 卡 | 真實資料 `roi × 100`,副標帶符號 `totalPnl` | ✅ `fmtPct(roi * 100)`;副標 `{t('totalPnlLabel')} +$135,802`;假副標 `annualized 18.4%` 已消失 | 同上 |
| 今日損益 KPI 卡 | 隱藏 | ✅ 文案與 `+$12,481` 皆不在 DOM | 「隱藏所有無後端資料來源的合成/寫死區塊(D-14 / D-16)」 |
| 可用現金 KPI 卡 | 隱藏 | ✅ 文案與 `$84,210` 皆不在 DOM | 同上 |
| 資產配置 donut | 隱藏 | ✅ `allocation` 標題與 `.alloc-row` 皆不存在 | 同上 |
| 資產走勢圖 + range 切換 | 隱藏 | ✅ `assetTrend` 標題不存在、`.seg-btn` 為 0、七個 range 按鈕逐一斷言不存在 | 同上 |
| 近期交易表 | 真實資料 `listTrades({ page: 0, size: 5 })` | ✅ fetch URL 含 `page=0` 與 `size=5` 且 `/trades` 只被呼叫一次;5 列;日期只取 `YYYY-MM-DD`;total = `quantity × price`;mock 種子代號(NVDA/TSLA)不得出現在該卡 | 「近期交易來自 GET /trades?page=0&size=5(D-09)」 |
| Watchlist / News 卡 | 維持現狀不動 | ✅ API mode 下 `.wl-row` 與 `.news-row` 仍渲染 | 「Watchlist 與 News 卡在 API mode 照常渲染」 |

mock mode 鏡像驗證:四張 KPI、`.alloc-row` 恰 5 列、`.seg-btn` 恰為 `['1D','1W','1M','3M','6M','1Y','All']`、`+$12,481` 與 `$84,210` 仍在——與 API mode 隱藏清單互為鏡像。

## D-14 其餘 summary 欄位的最終落點(plan `<output>` 第 3 項)

| 欄位 | 落點 |
|------|------|
| `totalMarketValue` | 總資產卡主值 |
| `roi` | 總報酬卡主值(×100 格式化) |
| `holdingCount` | **總資產卡副標**(`3 Positions` / `3 持倉`) |
| `totalPnl` | **總報酬卡副標**(帶正負號金額 `Total P&L +$135,802`),同時決定 `↗`/`↘` 與漲跌色 |
| `totalCostBasis` | 本頁不呈現 — 依 CONTEXT D-14 留給 `Positions.vue` 彙總條(03-04) |
| `realizedPnl` | 本頁不呈現 — 同上(且 mock 端恆為 0,見 03-02 限制) |
| `unrealizedPnl` | 本頁不呈現 — 同上 |

三個未呈現欄位不是遺漏:D-14 只鎖定兩張 KPI 卡,並明文指出 `Positions.vue` 既有彙總條是其餘欄位的自然落點。本頁若硬塞會回到「四張卡」的視覺密度,與 D-14 的隱藏原則相衝。

## task4.test.ts 是否有時序性修改(plan `<output>` 第 2 項)

**沒有。`src/task4.test.ts` 零修改**(`git show --stat` 可查:本 plan 的 commit 只動 3 個檔,不含 task4)。
其 Overview case(直接替換 store `trades` 再斷言畫面)在改寫後**一次通過**,證明 `api.live.trades` 的 getter 委派完整保住了 Pinia reactivity。

**但另有一個既有測試檔需要 fixture 補齊:`src/App.test.ts`** —— 見下方 Deviations 第 1 條。該修改**不是時序調整,也沒有改任何斷言語意**,而是補上新契約所需的後端回應 fixture。

## Task Commits

前端 repo(`D:/end/workspace/vue/stock-v2`,branch `feature/phase-03-portfolio-read`):

1. **Task 1 + Task 2:`feat(overview): API mode 改走 portfolio service,隱藏無後端來源的合成區塊`** — `309598f`

後端 repo(`D:/end/workspace/java/stock-web-v2`):本 SUMMARY 的 docs commit。

_TDD 節奏:先寫 `Overview.test.ts`(API mode 11 個 + mock mode 4 個)跑出紅燈 **12 failed / 3 passed (15)**,再改寫 `Overview.vue` 至 **15 passed (15)**。Task 1 與 Task 2 的測試同屬一個檔案、在同一輪 red→green 收斂,拆成兩個 commit 會產生一個「測試已綠但刻意只 commit 一半」的假中間態,故合為一個原子 commit(與 03-02 的處理一致)。_

## Files Created/Modified

- `vue-app/src/pages/Overview.vue` — 移除 mock store import;新增 `BlockState<T>` 雙狀態機、`loadSummary` / `loadRecentTrades` 各自獨立;模板依 `live` 分流並依處置清單 `v-if` 切換區塊;新增區塊狀態樣式
- `vue-app/src/pages/Overview.test.ts` — 新增,15 個測試(API mode 11 / mock mode 4)
- `vue-app/src/App.test.ts` — 既有 API-mode boot case 的 fetch mock 補上 `/portfolio/summary` 與 `/trades` 兩個 fixture(斷言一字未改)

## Decisions Made

- **`data-testid` 加在兩種 mode 共用的 KPI 卡與交易列上**(`overview-kpi` / `overview-trade-row`)。這是本 plan 對 mock mode DOM 的唯一改動(新增屬性,無視覺/行為變化),換來 mock 與 API 兩邊都能用「卡片數量」「列數」做精確斷言,而不是靠字串包含。既有測試全部只看 textContent,故零影響。
- **模板不做型別窄化**:`summaryState` 的 discriminated union 在 script 端投影成 `summaryLoading` / `summaryError` / `summary` 三個 computed 再給模板用。理由是 vue-tsc 對模板內 union 窄化的支援不穩定,投影後型別在 `.vue` 編譯與 `vue-tsc --noEmit` 下都確定安全。
- **`?raw` 而非 `node:fs` 讀原始碼**:PORT-04 的「不 import mock store」斷言需要讀 SFC 字面。專案 tsconfig 的 `types` 只有 `vite/client` 與 `vitest/globals`,沒有 `@types/node`,用 `node:fs` 會讓 `npm run build`(vue-tsc)出 3 個 TS2591。本 plan 不得新增套件(T-03-SC),故改用 Vite 內建的 `?raw`(型別由 `vite/client` 提供)。該測試同時斷言 `toContain('getRuntimeApiClients')`,可防止「raw 匯入變空字串導致 not.toContain 假性通過」。
- **重試鈕文案用 `authRetry`**,依 03-02-SUMMARY 的決定,未新增 `retry` key。

## Deviations from Plan

### 1. [Rule 3 - Blocking] `src/App.test.ts` 的 API-mode boot case 需要 portfolio fixture

- **Found during:** Task 2 全量 gate(`npm test`)
- **Issue:** `App.test.ts:60` 斷言 `expect(document.body.textContent).toContain('總資產')`,用意是「認證後頁面內容有渲染」。改寫前 API mode 的 Overview 仍渲染合成 KPI,所以 `總資產` 一定在;改寫後(D-14)該標籤只在 summary 載入成功時才存在,而該測試的 fetch mock 對 `/portfolio/summary` 與 `/trades` 一律回 `500 UNEXPECTED`,於是頁面進入錯誤狀態,斷言失敗。
  **這正是 D-14 的預期後果,不是 bug**:API mode 不再有「後端掛了畫面照樣有數字」這種事。
- **Fix:** 在該 case 的 fetch mock 補上兩個 fixture(summary 回真實形狀的 `PortfolioSummaryDto`、trades 回空頁)。**斷言一字未改**,語意反而更強:`總資產` 現在證明的是「backend summary 真的被渲染」,而不是「合成資料被渲染」。
- **Scope 說明:** 這超出「只碰 Overview.vue + 其測試檔」的原始範圍,但屬於本 task 變更直接造成的阻塞(Rule 3),且是唯一不改斷言語意的修法。替代方案(讓 loading/error 狀態仍渲染 KPI 標籤骨架)會直接違反 plan `<behavior>` 明訂的「fetch pending 時 summary 區塊顯示 loading 文案」與 D-14。
- **Files modified:** `vue-app/src/App.test.ts`(+14 行 fixture)
- **Committed in:** `309598f`

### 2. [計畫內選項的具體選擇] Task 1 與 Task 2 合為單一 commit

plan 的兩個 task 都以同一個新檔 `Overview.test.ts` 為載體,red 燈在同一次執行中一起出現。列於此處僅為可追溯性,非行為偏離。

---

**Total deviations:** 1 auto-fixed(Rule 3 阻塞:既有測試的 fixture 不足以支撐新契約)
**Impact on plan:** 處置清單八項全部照 plan 落地,無 scope creep。未碰 `Positions.vue` / `Trades.vue`(03-04 / 03-05 範圍完整保留)。

## Issues Encountered

1. **`import.meta.url` 在 vitest 下不是 `file:` scheme** → `readFileSync(new URL(...))` 丟 `TypeError: The URL must be of scheme file`。改用 `?raw`(見 Decisions)。
2. **`@types/node` 不存在** → `node:fs` / `node:path` / `process` 在 `vue-tsc --noEmit` 下報 TS2591。同樣由 `?raw` 解決,零新套件。
3. 上述兩點都只影響測試檔的「讀原始碼」手法,與產品程式無關。

## Verification Evidence

| Gate | 指令 | 結果 |
|------|------|------|
| Task 1/2 RED | `npm test -- src/pages/Overview.test.ts` | **12 failed \| 3 passed (15)** |
| Task 1/2 GREEN | `npm test -- src/pages/Overview.test.ts` | **15 passed (15)** |
| Focused gate | `npm test -- src/pages/Overview.test.ts src/task4.test.ts` | **28 passed (28)**(task4 未改動) |
| Plan gate 1 | `npm test` | **29 files / 189 passed** |
| Plan gate 2 | `VITE_DATA_MODE=api npm test` | **29 files / 189 passed** |
| Plan gate 3 | `npm run build`(vue-tsc --noEmit && vite build) | exit 0,built in 1.57s |

基線 174 → 189,淨增 15。既有測試零斷言修改(唯一改動是 App.test.ts 的 fetch fixture)。

### 斷言有效性(mutation testing,5 個變異全部被抓到)

刻意破壞實作、確認測試會紅,避免「假性通過」:

| 變異 | 失敗的測試 |
|------|-----------|
| 把 `useMockPortfolioStore` import 加回 `Overview.vue` | PORT-04 案(1 failed) |
| summary 的 retry 改成連帶重打 trades | 「retry 只重打自己的區塊(D-11)」(1 failed) |
| API mode KPI 混入兩張假卡 + donut 不再隱藏 | 4 failed(兩張卡數量、隱藏清單、兩個 retry 案) |
| `listTrades({page:0,size:5})` 改為 `listTrades()` | 「近期交易來自 page=0&size=5(D-09)」(1 failed) |
| 錯誤區塊不再渲染 `traceId` | 「summary 失敗顯示錯誤碼 + traceId(D-12)」(1 failed) |

每次變異後皆 `git checkout -- src/pages/Overview.vue` 還原,最終工作區乾淨、focused gate 重跑 35 passed。

## Threat Model 落地

| Threat ID | 落地 |
|-----------|------|
| T-03-08(假資料偽裝成真資料) | 隱藏清單測試逐項斷言 `+$12,481` / `$84,210` / `allocation` / `assetTrend` / `.seg-btn` / 七個 range 按鈕不在 DOM;近期交易卡另斷言 mock 種子代號 NVDA/TSLA 不得出現 |
| T-03-09(失敗不可追查) | 兩個區塊的錯誤狀態各自斷言 code + traceId 字串;mutation 測試證明拿掉 traceId 會紅 |
| T-03-10(錯誤洩漏內部資訊) | 錯誤區塊只渲染 `error.code` 與 `error.requestId`,不渲染 `error.message`(後端訊息)或 stack |
| T-03-SC(套件供應鏈) | 零新套件;`?raw` 是 Vite 內建能力 |

## D-13 邊界的實測證據

「portfolio 讀取錯誤不劫持全域 SessionBanner」不是靠「Overview 沒 render SessionBanner」這種空洞事實成立的。該測試同時:
1. 以 `configureApiClientSessionHandlers({ onRefreshing, onRefreshFailed })` 注入 spy,讓 summary 與 trades **雙雙 503**,斷言兩個 handler 皆未被呼叫(全域 session 通道零觸發);
2. 斷言 `[data-testid="session-banner"]` 不在 DOM;
3. 斷言兩個 inline 錯誤區塊都在。

## User Setup Required

None — 零新套件、零設定變更。

## Next Phase Readiness

- **03-04(Positions)與 03-05(Trades)可直接沿用本頁建立的樣板:**
  1. `BlockState<T>` union + 三個 computed 投影(不要在模板做窄化,vue-tsc 會咬)。
  2. `const live = api.live` 一次取出,mock 路徑在 `onMounted` 直接 return、零網路(本頁有「mock mode 不打任何網路請求」的測試鎖定)。
  3. 錯誤區塊呈現 `code` + `t(lang,'authRequestId') + traceId` + `authRetry` 鈕,不要碰 SessionBanner。
- **給 03-04 的具體交接:** `totalCostBasis` / `realizedPnl` / `unrealizedPnl` 三個 summary 欄位本頁刻意未用,依 D-14 應落在 Positions 的彙總條。
- **給 03-05 的提醒:** 本頁的近期交易是**獨立**的 `listTrades({page:0,size:5})` 快照(D-09),Trades 頁不要嘗試與它共用狀態或 store。
- **已知限制(非 bug,來自 03-02):** mock mode 的 `realizedPnl` 恆為 0、`priceTime` / `lastUpdated` 恆為 `null`。本頁不呈現這三個欄位,故不受影響;03-04 會直面。

## Self-Check: PASSED

- 3 個宣稱的檔案全數存在於 `D:/end/workspace/vue/stock-v2/vue-app/`(`src/pages/Overview.vue`、`src/pages/Overview.test.ts`、`src/App.test.ts`)。
- commit `309598f` 在前端 repo `git log` 中可查,`--stat` 顯示恰好 3 個檔、+617/-14。
- 無 stub / placeholder:API mode 不存在「渲染空值或假值」的路徑——沒有資料時走 loading / empty / error 三態之一,沒有硬編空陣列流向 UI。
- 無 TODO / FIXME 新增。
- `Overview.vue` 內 `useMockPortfolioStore` 出現次數為 0(由測試持續鎖定,mutation 測試證明會紅)。
- 未新增威脅面:零新端點、零新套件、頁面不直呼 fetch(一律經 `PortfolioApi`)。

---
*Phase: 03-portfolio-read-api-mode*
*Completed: 2026-07-25*
