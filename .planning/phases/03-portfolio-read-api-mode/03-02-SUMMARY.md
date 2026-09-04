---
phase: 03-portfolio-read-api-mode
plan: 02
subsystem: ui
tags: [vue, pinia, typescript, vitest, api-adapter, portfolio]

# Dependency graph
requires:
  - phase: 02-browser-auth-api-mode
    provides: apiClient 的 ApiResponse 信封拆解、apiPaginatedRequest 分頁 helper、pageApiClients 依 mode 分派的 client 工廠模式
provides:
  - PortfolioApi 介面與 createMockPortfolioApi / createHttpPortfolioApi / createPortfolioApi 三件組
  - PortfolioSummaryDto / HoldingDto / TradeDto 的 TS 型別(與後端 record 逐欄同形)
  - pageApiClients.RuntimeApiClients.portfolio 註冊(API mode 永不建立 mock 實作,有測試證據)
  - mock mode 的 reactive 資料視窗 PortfolioApi.live(property getter 延遲解析 store)
  - Wave 2 三個頁面共用的 11 組 zh/en 狀態文案
affects: [03-03 Positions 頁改寫, 03-04 Trades 頁改寫, 03-05 Overview 頁改寫]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "domain service 三件組(mock/http/factory 共用單一介面),延續 backtestApi.ts 樣板"
    - "mock 實作以 property getter 延遲解析 Pinia store,避免捕捉陣列參照"
    - "元件以 api.live 是否存在分支,而非讀 mode 字串"

key-files:
  created:
    - ../../vue/stock-v2/vue-app/src/services/portfolioApi.ts
    - ../../vue/stock-v2/vue-app/src/services/portfolioApi.test.ts
    - ../../vue/stock-v2/vue-app/src/i18n.test.ts
  modified:
    - ../../vue/stock-v2/vue-app/src/services/apiTypes.ts
    - ../../vue/stock-v2/vue-app/src/services/pageApiClients.ts
    - ../../vue/stock-v2/vue-app/src/api-adapter-wiring.test.ts
    - ../../vue/stock-v2/vue-app/src/i18n.ts

key-decisions:
  - "retry 文案複用既有 authRetry(重試 / Retry),不新增 retry key —— 語意與文案完全相同"
  - "mock listTrades 的 total 排序定義為 quantity × price(不含 fee),與 Plan 01 後端 D-06 一致"
  - "mock 的 realizedPnl 一律為 0:mock store 只有現有部位,沒有已實現損益來源"
  - "mock HoldingDto 的 priceTime / lastUpdated 為 null:mock store 無行情時間欄位,D-03 顯示邏輯需容忍 null"
  - "mock TradeDto.id 用 `mock-{store 陣列索引}` 合成,供頁面 :key 使用"
  - "新增 src/i18n.test.ts 以滿足 TDD 紅燈要求(plan 的 files_modified 未列此檔)"

patterns-established:
  - "PortfolioApi.live 選配屬性:僅 mock 實作提供,頁面據此分支,元件永不 import mock store(judgment §3)"
  - "http 實作零 shape 兼容解析:一律 apiRequest / apiPaginatedRequest + buildQueryString(judgment §4/§10)"

requirements-completed: [PORT-03, PORT-04]

# Metrics
duration: 25min
completed: 2026-07-25
---

# Phase 03 Plan 02: Portfolio Service 三件組 Summary

**portfolio domain 的 mock/http/factory 三件組落地並註冊進 pageApiClients,mock 端以 property getter 延遲解析 Pinia store 保住 reactivity,http 端經 apiRequest/apiPaginatedRequest 直接消費後端信封。**

## Performance

- **Duration:** 25 min
- **Started:** 2026-07-25T03:00:39Z
- **Completed:** 2026-07-25T03:25:58Z
- **Tasks:** 3
- **Files modified:** 7(3 新增 / 4 修改,皆在 sibling 前端 repo)

## Accomplishments

- `PortfolioApi` 介面與三件組完成,Wave 2 的 03-03 / 03-04 / 03-05 可直接消費(介面全文見下)。
- `apiTypes.ts` 補上 `PortfolioSummaryDto` / `HoldingDto` / `TradeDto`,與後端 record 逐欄同形,零欄位增刪。
- `pageApiClients` 註冊 `portfolio`,wiring 測試證明 API mode 下 `createMockPortfolioApi` 未被呼叫、`live` 為 `undefined`(PORT-04 / T-03-05)。
- mock reactivity(Q3 裁決)以三個測試證明:整個陣列被替換、`executeOrder` 變異、以及跨 pinia 實例交換後仍讀到最新 store。
- 失敗診斷 `code` / `status` / `requestId` 在 service 層與 wiring 層各有斷言(PORT-05 半部 / T-03-06)。
- 新增 20 個測試,雙模式全量 174/174 綠、`npm run build`(vue-tsc)通過。

## Task Commits

前端 repo(`D:/end/workspace/vue/stock-v2`,branch `feature/phase-03-portfolio-read`):

1. **Task 1: apiTypes DTO 型別 + portfolioApi 三件組(TDD)** - `4bdb229` (feat)
2. **Task 2: pageApiClients 註冊 + wiring 回歸 + i18n 文案(TDD)** - `157f7d8` (feat)
3. **Task 3: 前端全量 gate** - 無 code 變更,不產生 commit(驗證性任務)

後端 repo(`D:/end/workspace/java/stock-web-v2`):本 SUMMARY 的 docs commit。

_TDD 節奏:兩個任務都先寫測試跑出紅燈(Task 1 為模組解析失敗、Task 2 為 4 個斷言失敗),再實作至綠燈。因單一任務的 red→green 在同一個原子 commit 內收斂,未拆成 test/feat 兩個 commit。_

## Files Created/Modified

- `vue-app/src/services/portfolioApi.ts` - PortfolioApi 介面 + mock/http/factory 三件組(202 行)
- `vue-app/src/services/portfolioApi.test.ts` - 16 個測試:URL/query string 逐參數、信封拆解、ApiClientError 診斷欄位、mock reactivity、篩選/排序/分頁、不打網路
- `vue-app/src/services/apiTypes.ts` - 新增三個 portfolio DTO 型別
- `vue-app/src/services/pageApiClients.ts` - `RuntimeApiClients.portfolio` 欄位與 `createPortfolioApi(mode, basePath)` 呼叫
- `vue-app/src/api-adapter-wiring.test.ts` - 既有 API-mode case 擴充 portfolio;新增 live 存在性 case 與 503 診斷 case
- `vue-app/src/i18n.ts` - 11 組 zh/en 狀態文案
- `vue-app/src/i18n.test.ts` - 新增:文案 zh/en 成對存在 + authRetry 複用決策的鎖定測試

## PortfolioApi 最終介面(Wave 2 消費契約)

```typescript
// src/services/portfolioApi.ts

/** `GET /api/v1/trades` 的參數,與後端契約一一對應(白名單以外的值後端回 400)。 */
export interface TradeListParams {
  symbol?: string;
  type?: 'BUY' | 'SELL';
  /** ISO-8601 含 offset;半開區間起點 */
  dateFrom?: string;
  /** ISO-8601 含 offset;半開區間終點,不含 */
  dateTo?: string;
  sort?: 'executedAt' | 'total' | 'quantity';
  direction?: 'asc' | 'desc';
  /** 預設 0 */
  page?: number;
  /** 預設 20 */
  size?: number;
}

/**
 * mock mode 專用的 reactive 資料視窗。
 * 三個屬性都是 getter,每次存取才解析 store —— 絕不在 factory 時捕捉陣列參照,
 * 否則整個陣列被替換(`portfolio.trades = [...]`)或測試換 pinia 後會讀到過期資料。
 */
export interface PortfolioLiveMockData {
  readonly trades: Trade[];        // src/types.ts 的 mock Trade(d/type/sym/qty/px/fee/note)
  readonly positions: Position[];  // src/types.ts 的 mock Position(sym/name/qty/avg/price/sector)
  readonly lastFill: { sym: string; type: 'BUY' | 'SELL'; qty: number; px: number } | null;
}

/**
 * portfolio domain 的唯一消費介面。
 *
 * 頁面用法(judgment §3:元件永遠不 import mock store):
 * - mock mode:經 `live` 取得 reactive 資料(Pinia reactivity 完整保留,含 lastFill 高亮)。
 * - API mode:`live` 為 undefined,改走 Promise 方法拿快照 + 明確 refetch。
 * - 分支判斷依據是 `api.live` 是否存在,不是 mode 字串。
 */
export interface PortfolioApi {
  mode: RuntimeDataMode;
  getSummary(): Promise<PortfolioSummaryDto>;
  listHoldings(): Promise<HoldingDto[]>;
  listTrades(params?: TradeListParams): Promise<PaginatedResponse<TradeDto>>;
  /** 僅 mock 實作提供(Q3 裁決)。 */
  live?: PortfolioLiveMockData;
}

export function createMockPortfolioApi(): PortfolioApi;
export function createHttpPortfolioApi(basePath?: string): PortfolioApi;   // basePath 預設 '/api/v1'
export function createPortfolioApi(mode: RuntimeDataMode, basePath?: string): PortfolioApi;
```

取得方式(頁面端):`getRuntimeApiClients().portfolio`。

**http 端點與 URL(已由測試逐字串斷言):**

| 方法 | URL | helper |
|------|-----|--------|
| `getSummary` | `GET {basePath}/portfolio/summary` | `apiRequest<PortfolioSummaryDto>` |
| `listHoldings` | `GET {basePath}/portfolio/holdings` | `apiRequest<HoldingDto[]>` |
| `listTrades` | `GET {basePath}/trades?symbol&type&dateFrom&dateTo&sort&direction&page&size` | `apiPaginatedRequest<TradeDto>` |

query string 由 `buildQueryString` 依 `symbol → type → dateFrom → dateTo → sort → direction → page → size` 的固定順序組出;未提供的參數不出現;`page`/`size` 缺省補 `0`/`20`。

**mock 實作語意(Wave 2 需知):**

- `getSummary`:`totalMarketValue = Σ qty×price`、`totalCostBasis = Σ qty×avg`、`unrealizedPnl = 差額`、`realizedPnl = 0`、`roi = totalPnl / totalCostBasis`(成本為 0 時回 0)、`holdingCount = positions.length`。
- `listHoldings`:每個 position 映射一筆,`assetId = 'mock-asset-{sym}'`,`priceTime` / `lastUpdated` 皆為 `null`(mock store 無此欄位)。
- `listTrades`:store trade 映射為 `id = 'mock-{索引}'`、`executedAt = createdAt = '{d}T00:00:00Z'`;支援 `symbol` / `type` 篩選、`[dateFrom, dateTo)` 半開區間、三種排序(預設 `executedAt` desc)、page/size 切片。mock 專屬的 `DIV` 型別原樣通過(後端契約無此值,mock mode 頁面走 `live.trades` 不經此方法)。

## i18n 實際新增的 key(zh / en)

| key | zh | en |
|-----|----|----|
| `loading` | 載入中… | Loading… |
| `loadFailed` | 讀取失敗 | Failed to load |
| `noTrades` | 尚無交易 | No trades yet |
| `noHoldings` | 尚無持倉 | No holdings yet |
| `noData` | 尚無資料 | No data yet |
| `prevPage` | 上一頁 | Prev |
| `nextPage` | 下一頁 | Next |
| `priceAsOf` | 行情時間 | Price as of |
| `realizedPnl` | 已實現損益 | Realized P&L |
| `totalPnlLabel` | 總損益 | Total P&L |
| `costBasis` | 總成本 | Cost basis |

**retry key 的決定:複用既有 `authRetry`,未新增 `retry`。** 理由:`authRetry` 的文案就是 `重試` / `Retry`,與 plan 描述的 retry 完全同義同字;plan 明文允許「若判斷與既有 authRetry 語意重複可直接複用」。Wave 2 的重試鈕請用 `t(lang, 'authRetry')`。此決定由 `src/i18n.test.ts` 的第二個測試鎖定。

頁碼指示器維持純數字(`{page+1} / {totalPages}`),不需 i18n key。

## Decisions Made

- **複用 `authRetry`**(見上)。
- **`total` 排序 = quantity × price**,與 Plan 01 的 D-06 定義對齊(不含 fee),寫成註解留在 `sortValue()` 旁。
- **mock `realizedPnl` 恆為 0**:mock store 只保留現有部位,沒有平倉歷史可算已實現損益。Wave 2 在 mock mode 顯示「已實現損益」時會看到 0,這是資料源限制不是 bug。
- **mock `priceTime` / `lastUpdated` 為 `null`**:D-03 的「行情時間」顯示在 mock mode 無值,頁面需容忍 `null`(建議顯示 `—`)。
- **`live` 用物件 literal getter 而非 `Object.defineProperty`**:型別上直接滿足 `readonly`,且每次存取都重跑 `useMockPortfolioStore()`,Vue 的 computed 會正確追蹤到 store state。

## Deviations from Plan

### 1. [Rule 2 - Missing Critical] 新增 `src/i18n.test.ts`

- **Found during:** Task 2
- **Issue:** plan 的 Task 2 要求 i18n 新 key,但 `files_modified` 未包含任何能為 i18n 提供紅燈的測試檔;CLAUDE.md 的 TDD 是硬性約束(先紅後綠),沒有測試就無法合規地寫文案。把 i18n 斷言塞進 `api-adapter-wiring.test.ts`(describe 名為 "page API adapter wiring")語意不符。
- **Fix:** 新增 `src/i18n.test.ts`,2 個測試:11 個新 key 的 zh/en 成對存在(並確認 `t()` 不是回傳 key 本身),以及 authRetry 複用決策的鎖定。
- **Verification:** 先跑出紅燈 `AssertionError: zh.loading: expected undefined to be truthy`,實作後轉綠。
- **Committed in:** `157f7d8`(Task 2 commit)

### 2. [計畫內選項的具體選擇] retry key 複用 authRetry

plan 已預留此選項並要求在 SUMMARY 記錄,故非偏離,列於此處僅為可追溯性。

---

**Total deviations:** 1 auto-fixed(1 missing critical:TDD 所需的測試檔)
**Impact on plan:** 純加法,不影響介面契約與既有測試。無 scope creep;未動任何頁面檔(Wave 2 範圍完整保留)。

## Issues Encountered

無。兩個任務都一次紅→綠通過,雙模式全量測試與 build 首次執行即綠。

## Verification Evidence

| Gate | 指令 | 結果 |
|------|------|------|
| Task 1 red | `npm test -- src/services/portfolioApi.test.ts` | `Failed to resolve import "./portfolioApi"` — 1 failed, no tests |
| Task 1 green | 同上 | **16 passed (16)**,Test Files 1 passed |
| Task 2 red | `npm test -- src/api-adapter-wiring.test.ts src/i18n.test.ts` | **4 failed / 10 passed**(3 wiring + 1 i18n 紅) |
| Task 2 green | `npm test -- src/api-adapter-wiring.test.ts src/i18n.test.ts src/services/portfolioApi.test.ts` | **30 passed (30)** |
| Plan gate 1 | `npm test` | **28 files / 174 passed** |
| Plan gate 2 | `VITE_DATA_MODE=api npm test` | **28 files / 174 passed** |
| Plan gate 3 | `npm run build`(vue-tsc --noEmit && vite build) | exit 0,built in 1.79s |

基線 154 測試 → 174,淨增 20(16 portfolioApi + 2 i18n + 2 wiring),既有測試零修改、零紅燈。

## User Setup Required

None - 本 plan 零新套件(T-03-SC:RESEARCH 的 Package Legitimacy Audit 無安裝項)。

## Next Phase Readiness

- **Wave 2 可以起跑。** 03-03(Positions)、03-04(Trades)、03-05(Overview)三個 plan 的共同地基就位:介面契約已鎖定於上、i18n key 已可用、`getRuntimeApiClients().portfolio` 已可取得。
- **給 Wave 2 執行者的三個提醒:**
  1. 分支條件用 `if (api.live)`,不要讀 `api.mode` 字串,也不要 import `useMockPortfolioStore`(judgment §3)。
  2. mock mode 下 `HoldingDto.priceTime`、`lastUpdated` 恆為 `null`,`realizedPnl` 恆為 0 —— UI 需容忍。
  3. 重試鈕文案是 `authRetry`,不是 `retry`。
  4. **`live` 的 getter 必須在有 active pinia 的情境下存取**(setup / computed / render 內)。在 module top-level 或 pinia 安裝前存取會丟 `getActivePinia() was called but there was no active Pinia`。這是延遲解析的必然代價,換來的是陣列替換與 pinia 交換都不會讀到過期資料。
- **與 Plan 01 的介面對帳尚未做端到端驗證:** 本 plan 的 http 實作依 Plan 01 鎖定的契約表撰寫,但 Plan 01 於本 plan 執行期間平行進行,尚未跑過前後端整合。若 Plan 01 的最終參數名/排序白名單有變,`createHttpPortfolioApi` 的 `buildQueryString` 欄位需同步(單點修改,測試會即時紅)。

## Self-Check: PASSED

- 7 個宣稱的檔案全數存在於 `D:/end/workspace/vue/stock-v2/vue-app/`(3 新增 / 4 修改)。
- 2 個 commit hash(`4bdb229` / `157f7d8`)在前端 repo `git log` 中可查。
- 無 stub / placeholder:三件組所有方法皆有實作與測試覆蓋,無 TODO/FIXME/硬編空值。
- 無新增威脅面(零新端點、零新套件、http 實作零直呼 fetch)。

---
*Phase: 03-portfolio-read-api-mode*
*Completed: 2026-07-25*
