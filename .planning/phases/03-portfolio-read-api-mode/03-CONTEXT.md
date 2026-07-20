# Phase 3: Portfolio Read API Mode - Context

**Gathered:** 2026-07-19
**Status:** Ready for planning

<domain>
## Phase Boundary

讓 API mode 能從後端讀取 portfolio 三塊資料(summary / holdings / trade history)並映射到**現有** Overview、Positions、Trades 三個頁面,同時 mock mode 維持完全獨立運作。

**⚠️ 本階段包含後端改動 —— 這是 discuss 過程中的決議,與 ROADMAP 原本「前端讀取」的描述不同。**
`GET /api/v1/trades` 目前只支援 `symbol` / `page` / `size`,缺篩選與排序參數。若不補,前端在分頁後做 client-side 篩選/排序只會作用於當前頁,產生「看起來對、實際錯」的結果(見 D-05/D-06 的理由)。因此後端 API 擴充屬於本階段的必要範圍。

**不在本階段:** 交易建立(Phase 4)、Analytics/Alerts/Watchlist 等頁面(PORT-06,v2 deferred)、股利(DIV)交易類型(已記 todo)。

</domain>

<decisions>
## Implementation Decisions

### 欄位映射與落差

- **D-01:** `sector` 本階段不處理。查證後確認它**不在** Positions 頁,只用於 `Analytics.vue`(treemap 標籤、按產業分群、hover chip)與 i18n 的「產業分布」,而 Analytics 屬 PORT-06(v2 deferred)。後端 `HoldingDto` 沒有此欄位是可接受的。
- **D-02:** 股利(DIV):API mode **隱藏**交易頁的「Dividend」篩選頁籤。後端 `TradeType` enum 只有 BUY/SELL,API mode 永遠回不出 DIV,露出入口會讓使用者以為功能壞掉。mock mode 保留該頁籤不受影響。後端支援 DIV 已記為 todo(見 Deferred)。
- **D-03:** 顯示 `HoldingDto.priceTime` 作為行情時間。市價來自快取/預計算(judgment §7),可能有延遲;顯示時間讓使用者知道資料新鮮度,避免誤以為是即時報價。

### 計算歸屬(單一真相來源)

- **D-04:** API mode 的 P&L / ROI / 市值 / 成本 **一律使用後端算好的值**(`costBasis`、`marketValue`、`unrealizedPnl`、`realizedPnl`、`roi`,以及 `PortfolioSummaryDto` 的彙總),前端不再自行計算。
  理由有二:(1) judgment §7 明訂高頻計算走 Redis 預計算、API 只讀,後端是指定的計算方;(2) **`realizedPnl` 前端根本算不出來** —— 它需要完整交易歷史,不是持倉快照能推導的,現有 mock 的 client-side 算法本質上不完整。
  影響:`Positions.vue` 現有的 `totalCost = reduce((s,p) => s + p.qty * p.avg)`(:237)與 `qty * effPrice(p)`(:174)等計算,在 API mode 需改為讀後端欄位。

### 交易查詢:分頁 / 篩選 / 排序

- **D-05:** **後端 `GET /trades` 新增篩選參數**(交易類型、日期區間)。現有前端篩選 chips `['All','Buy','Sell','Dividend','2026']` 是對完整陣列做 client-side 過濾(`Trades.vue:68-79`);分頁後 client-side 過濾只會作用於當前頁,使用者按「Buy」會以為看到所有買入,實際只有這 20 筆裡的買入。這是正確性問題,不是體驗問題。
- **D-06:** **後端 `GET /trades` 新增排序參數**,支援 `executedAt` / `price` / `quantity` 三個欄位的升降序。與 D-05 同一個理由:分頁下的 client-side 排序只排當前頁,比沒有排序更危險(看起來正確但結果是錯的)。
  *(註:交易頁目前並無排序功能。討論初期曾判定為 scope creep,經 Yuan 指出「資料一多會有問題」後改列入本階段 —— 因為分頁一旦做了,排序就必須同時在後端做才正確。)*
- **D-07:** 預設排序 `executedAt` 降序(最新在前,與現有 mock 行為一致),預設 `size` 20(與後端既有預設相同,不需額外傳參)。
- **D-08:** 分頁 UI 採 **換頁按鈕**(上一頁/下一頁 + 當前頁數),**不用** append / infinite-scroll。
  理由:後端是 page-number 分頁,插入新資料會造成頁面位移;append 模式會出現重複列。Phase 4 就要加入交易建立,這個風險是真實的。換頁按鈕讓使用者明確知道自己在第幾頁。(參見 `pagination-page-number` 的既有結論。)
- **D-09:** `Overview.vue` 的「近期交易」(現為 `trades.slice(0, 5)`,:95)改為 `GET /trades?page=0&size=5`,只取需要的量,不與交易頁共用跨頁面狀態。
- **D-10:** CSV 匯出範圍為**全部交易**,而非當前頁。匯出時循環拉完所有頁再組 CSV。使用者對「匯出我的交易」的預期不是「匯出這 20 筆」。

### 錯誤與狀態呈現

- **D-11:** loading / empty / error / retry **各 view 內嵌**(Overview、Positions、Trades 各自處理)。一個區塊失敗不應讓整頁不可用,retry 也只重試該區塊。
- **D-12:** trace id **只在錯誤狀態顯示**(錯誤碼 + traceId),與 Phase 2 `SessionBanner` 的既有慣例一致 —— 正常使用時不干擾畫面,出錯時使用者能回報。
- **D-13:** 全域 `SessionBanner` **保留給 session / 認證類錯誤**。portfolio 讀取失敗與 session 過期是不同性質的問題,混在同一個全域元件會讓使用者困惑。

### Claude's Discretion

本次討論中使用者對所有提問都給了明確選擇,無「你決定」項目。實作細節(元件拆分、loading 骨架樣式、換頁按鈕的具體版面)由 planner/executor 依現有 UI 慣例決定。

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### 判斷準則(最高優先)
- `ai-docs/judgment.md` §3 — mock/api 雙模式:元件**不得** import mock store,一律經 `services/` 的 domain service interface,由 `pageApiClients.ts` 依 mode 選實作。API mode 功能驗證必須看到真實 network call,「畫面看起來正常」不算證據。
- `ai-docs/judgment.md` §4 — 信封權威為後端 `ApiResponse<T>`;分頁為 `ApiResponse<PageResponse<T>>`(page-number)。前端已於 2026-07-19 完成對齊。
- `ai-docs/judgment.md` §7 — 高頻計算走 Redis 預計算、API 只讀。這是 D-04 的依據。
- `ai-docs/judgment.md` §8 — 「何時算真完成」:跨 repo 變更兩邊驗證都要跑。
- `ai-docs/judgment.md` §9 — 變更 API 契約 shape 前要停下來問(D-05/D-06 已取得同意)。

### 契約與規範
- `../../vue/stock-v2/docs/api-contracts/mock-to-real-contract.md` — 信封與分頁契約(2026-07-19 已與後端對齊,整份不再是草案)
- `ai-docs/browser-auth-contract.md` — 瀏覽器 auth 契約(cookie/CSRF)
- `ai-docs/code-standards.md`、`ai-docs/testing-standards.md`、`ai-docs/flyway-convention.md`(若 D-05/D-06 需要新增索引)

### 需求與前期脈絡
- `.planning/REQUIREMENTS.md` — PORT-01 ~ PORT-05
- `.planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md` — Phase 2 的 API client 決策
- `.planning/todos/pending/2026-07-19-backend-dividend-trade-type.md` — DIV 待辦

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `vue-app/src/services/apiClient.ts` — `apiRequest<T>`(拆 `ApiResponse.data`)、`apiPaginatedRequest<T>`(消費 `ApiResponse<PageResponse<T>>`)、`buildQueryString`、`ApiClientError`(含 `requestId` 來自 `meta.traceId`、`fields` 欄位級錯誤)。**分頁與信封已對齊後端,可直接用,不需再寫轉接層。**
- `vue-app/src/services/apiTypes.ts` — `PaginatedResponse<T>` 已與後端 `PageResponse<T>` 同形:`{ items, page, size, totalElements, totalPages }`
- `vue-app/src/services/pageApiClients.ts` — `getRuntimeApiClients()` 依 mode 建立/快取各 domain client;`resetRuntimeApiClientsForTests()` 供測試
- `vue-app/src/services/backtestApi.ts` — **最接近的樣板**:同時有 mock 與 http 實作、分頁 list、錯誤處理,新的 portfolioApi 可照抄其結構
- `vue-app/src/components/SessionBanner.vue` — 錯誤呈現與安全診斷(code/status/traceId)的既有樣式參考
- `vue-app/src/testSetup.ts` — 測試預設鎖 mock 模式;需要 api 模式的測試自行 `vi.stubEnv('VITE_DATA_MODE','api')`

### Established Patterns
- **Service 三件組**:`createMockXxxApi()` / `createHttpXxxApi(basePath)` / `createXxxApi(mode, basePath)`,介面同一份 → 見 `backtestApi.ts` / `opsApi.ts` / `aiAccessApi.ts`
- **頁面取得 client**:`getRuntimeApiClients().xxx`,不直接 import mock store(judgment §3)
- **API mode 不回退 mock**:Phase 2 Plan 05 已用「mock factory 未被呼叫」的斷言把靜默回退變成測試失敗(`api-adapter-wiring.test.ts`)
- **TDD**:先寫失敗測試再實作(CLAUDE.md 硬性要求)

### Integration Points
- `vue-app/src/pages/Overview.vue` — 目前 `import { useMockPortfolioStore }`(:114),近期交易在 :95
- `vue-app/src/pages/Positions.vue` — 目前 `import { useMockPortfolioStore }`(:196);client-side 排序 `sort('pnl')` 等;`totalCost` 計算在 :237
- `vue-app/src/pages/Trades.vue` — 目前 `import { useMockPortfolioStore }`(:58);篩選 chips 在 :65,`filteredTrades` 在 :68-79,`exportCsv` 在 :83
- **三頁都直接 import mock store,正是 PORT-04 與 judgment §3 要斷開的**

### 後端現況(已查證)
| 端點 | 回傳 | 備註 |
|---|---|---|
| `GET /api/v1/portfolio/summary` | `ApiResponse<PortfolioSummaryDto>` | 7 欄:totalMarketValue / totalCostBasis / realizedPnl / unrealizedPnl / totalPnl / roi / holdingCount |
| `GET /api/v1/portfolio/holdings` | `ApiResponse<List<HoldingDto>>` | **純 List,不分頁**;13 欄含 priceTime / lastUpdated |
| `GET /api/v1/trades` | `ApiResponse<PageResponse<TradeDto>>` | 現只有 `symbol` / `page` / `size` → **D-05/D-06 要擴充** |

三個端點皆有 `@PreAuthorize("hasAuthority('PORTFOLIO_VIEW')")`。
`HoldingDto` 無 `sector`;`TradeType` enum 只有 BUY/SELL。

</code_context>

<specifics>
## Specific Ideas

- holdings 不分頁(後端回 `List`),所以 Positions 現有的 client-side 排序**是安全的**,不需改為後端排序 —— 與 trades 的情況不同,不要一併套用。
- 排序欄位刻意收斂為 `executedAt` / `price` / `quantity` 三個,而非「所有顯示欄位都可排」:後端要驗證欄位名以防注入、也要開索引,三個涵蓋「最近的」與「最大筆」兩種真實需求。
- 換頁按鈕而非 append,是為了迴避 page-number 分頁在插入資料時的位移/重複問題 —— 這個結論在 2026-07-19 的分頁對齊工作中已經驗證過(mock 測試 `reports page-number drift when a newer log is prepended` 明確斷言該行為)。

</specifics>

<deferred>
## Deferred Ideas

- **後端支援 DIV(股利)交易類型** — 已寫入 `.planning/todos/pending/2026-07-19-backend-dividend-trade-type.md`。不是加一個 enum 值就好:股利會改變成本/損益計算語意,`HoldingCalculator` 需要新分支,`CreateTradeRequest` 的 quantity/price 語意也不同,規模上很可能值得自己一個 phase。
- **`sector` 與 Analytics 產業分布** — 屬 PORT-06(v2 deferred)。後端 `HoldingDto` 若未來要支援,需先決定 sector 資料的權威來源(asset 主檔?外部行情商?)。
- **多幣別呈現、空持倉的初始引導體驗、mock↔api 切換時的資料殘留** — 討論尾聲列為可選深入項目,使用者選擇直接進 CONTEXT,未展開。若 planner 認為影響驗收條件可回頭補。

</deferred>

---

*Phase: 3-portfolio-read-api-mode*
*Context gathered: 2026-07-19*
