# Phase 3: Portfolio Read API Mode - Context

**Gathered:** 2026-07-19
**Status:** Ready for planning

<domain>
## Phase Boundary

讓 API mode 能從後端讀取 portfolio 三塊資料(summary / holdings / trade history)並映射到**現有** Overview、Positions、Trades 三個頁面,同時 mock mode 維持完全獨立運作。

**⚠️ 本階段包含後端改動 —— 這是 discuss 過程中的決議,與 ROADMAP 原本「前端讀取」的描述不同。**
`GET /api/v1/trades` 目前只支援 `symbol` / `page` / `size`,缺篩選與排序參數。若不補,前端在分頁後做 client-side 篩選/排序只會作用於當前頁,產生「看起來對、實際錯」的結果(見 D-05/D-06 的理由)。因此後端 API 擴充屬於本階段的必要範圍。

**⚠️ 第二個範圍認知:PORT-01 的「映射 summary 到現有 overview UI」實際上是「取代合成假資料」,不是接線。**
查證後確認 `Overview.vue` 的四張 KPI 卡**沒有一張來自真實持倉**:總資產與總報酬來自 `genSeries(80, 1_000_000, 0.012, 5)` 的亂數序列(:132-137),「今日損益」`'+$12,481'` 與「可用現金」`'$84,210'` 是**寫死的字串**,資產配置 donut(`alloc` :139-142)也是寫死陣列。`Overview.vue` 全檔只有 `:95` 一處讀 `portfolio.trades`。
因此本階段要做的是**重建 KPI 區塊的資料來源**,而非把既有欄位接上 API。四張卡中只有兩張有後端對應(見 D-14)。

**不在本階段:** 交易建立(Phase 4)、Analytics/Alerts/Watchlist 等頁面(PORT-06,v2 deferred)、股利(DIV)交易類型、日級損益、可用現金、資產分類(皆已記 todo)。

</domain>

<decisions>
## Implementation Decisions

### 欄位映射與落差

- **D-01:** `sector` 本階段不處理(後端 `HoldingDto` 不加此欄位)。
  **⚠️ 2026-07-23 research 更正:** 原文「sector 不在 Positions 頁」是查證錯誤(grep 輸出被截斷)。實際上 `Positions.vue:118-140` 有一張 **Sector breakdown 卡**(`sectorStats` 衍生自 `p.sector`),Analytics 之外這裡也用了 sector。**結論不變**(後端仍不加 sector),但 API mode 必須把這張卡列入隱藏清單(適用 D-14/D-16 原則),已併入資產分類 todo。
- **D-02:** 股利(DIV):API mode **隱藏**交易頁的「Dividend」篩選頁籤。後端 `TradeType` enum 只有 BUY/SELL,API mode 永遠回不出 DIV,露出入口會讓使用者以為功能壞掉。mock mode 保留該頁籤不受影響。後端支援 DIV 已記為 todo(見 Deferred)。
- **D-03:** 顯示 `HoldingDto.priceTime` 作為行情時間。市價來自快取/預計算(judgment §7),可能有延遲;顯示時間讓使用者知道資料新鮮度,避免誤以為是即時報價。

### 計算歸屬(單一真相來源)

- **D-04:** API mode 的 P&L / ROI / 市值 / 成本 **一律使用後端算好的值**(`costBasis`、`marketValue`、`unrealizedPnl`、`realizedPnl`、`roi`,以及 `PortfolioSummaryDto` 的彙總),前端**不得自行重算後端已提供的數值**。
  **例外(重要):後端未提供、只能由後端值衍生的欄位仍在前端計算。** 已知案例:`Positions.vue:154` 的 `weight`(持倉權重)—— 後端 `HoldingDto` 13 個欄位與 `PortfolioSummaryDto` 7 個欄位都沒有 weight,只能以 `marketValue / totalMarketValue` 前端算出。executor 請勿為了服從本條而去找不存在的後端欄位或刪除 weight 欄。判準是:**後端有的就用,不要平行實作;後端沒有的才衍生,且必須衍生自後端值而非原始 qty×price。**
  理由有二:(1) judgment §7 明訂高頻計算走 Redis 預計算、API 只讀,後端是指定的計算方;(2) **`realizedPnl` 前端根本算不出來** —— 它需要完整交易歷史,不是持倉快照能推導的,現有 mock 的 client-side 算法本質上不完整。
  影響:`Positions.vue` 現有的 `totalCost = reduce((s,p) => s + p.qty * p.avg)`(:237)與 `qty * effPrice(p)`(:174)等計算,在 API mode 需改為讀後端欄位。

### 交易查詢:分頁 / 篩選 / 排序

- **D-05:** **後端 `GET /trades` 新增篩選參數**(交易類型、日期區間)。現有前端篩選 chips `['All','Buy','Sell','Dividend','2026']` 是對完整陣列做 client-side 過濾(`Trades.vue:68-79`);分頁後 client-side 過濾只會作用於當前頁,使用者按「Buy」會以為看到所有買入,實際只有這 20 筆裡的買入。這是正確性問題,不是體驗問題。
  **注意 `2026` 這個 chip 是硬編年份**(篩選邏輯為 `tr.d.startsWith('2026')`)。原樣搬到後端日期區間參數等於固定查 2026 年,2027 年就是壞的。實作時改為**動態當年度**(或直接改成日期區間選擇器),不要把年份寫死。
- **D-06:** **後端 `GET /trades` 新增排序參數**,支援三個排序鍵:`executedAt`(成交時間)、**`total`(金額 = `quantity × price`)**、`quantity`(數量),皆需升降序。與 D-05 同一個理由:分頁下的 client-side 排序只排當前頁,比沒有排序更危險(看起來正確但結果是錯的)。
  **⚠️ `total` 是計算值,後端沒有這個欄位。** `TradeDto` 只有 `quantity` / `price` / `fee`,而交易表格同時存在 `price`(單價 `tr.px`)與 `total`(總額 `tr.qty * tr.px`)兩欄 —— 使用者要的「金額」是**總額**,不是單價,兩者排序結果完全不同(1 股 $1000 vs 1000 股 $50)。因此後端必須以 `quantity * price` 的**計算式排序**,並評估運算式索引(否則大量交易時會全表掃描)。這與「加一個 sort 參數」不是同一量級的工作,規劃時請估進去,必要時依 `ai-docs/flyway-convention.md` 新增 migration。
  *(註:交易頁目前並無排序功能。討論初期曾判定為 scope creep,經 Yuan 指出「資料一多會有問題」後改列入本階段 —— 因為分頁一旦做了,排序就必須同時在後端做才正確。)*
- **D-07:** 預設排序 `executedAt` 降序(最新在前,與現有 mock 行為一致),預設 `size` 20(與後端既有預設相同,不需額外傳參)。
- **D-08:** 分頁 UI 採 **換頁按鈕**(上一頁/下一頁 + 當前頁數),**不用** append / infinite-scroll。
  理由:後端是 page-number 分頁,插入新資料會造成頁面位移;append 模式會出現重複列。Phase 4 就要加入交易建立,這個風險是真實的。換頁按鈕讓使用者明確知道自己在第幾頁。(參見 `pagination-page-number` 的既有結論。)
- **D-09:** `Overview.vue` 的「近期交易」(現為 `trades.slice(0, 5)`,:95)改為 `GET /trades?page=0&size=5`,只取需要的量,不與交易頁共用跨頁面狀態。
- **D-10:** CSV 匯出範圍為**當前篩選與排序條件下的所有頁**,而非只有當前頁。匯出時以相同的 filter/sort 參數循環拉完所有頁再組 CSV。
  **明確界定(避免誤讀):「所有頁」不等於「忽略篩選匯出全部交易」。** 現有 `exportCsv()`(`Trades.vue:83`)本來就以 `filteredTrades` 為來源,檔名也帶入 `activeFilter`(`trades-${activeFilter}-....csv`),篩選語意必須保留 —— 使用者篩了 Buy 之後匯出,檔案裡不該出現 Sell。本決策只修正「分頁導致只匯出當前頁」這個問題。

- **D-14:** **Overview KPI 卡在 API mode 只保留有後端資料的兩張**:總資產 ← `totalMarketValue`、總報酬 ← `roi`。
  **隱藏**「今日損益」與「可用現金」—— 後端無對應資料(前者需日級歷史快照,後者需帳戶餘額模型,皆已記 todo)。同時**隱藏資產配置 donut**(`alloc`,需資產類別分類,已記 todo)。與 D-01(sector)、D-02(股利)同一原則:**後端沒有的就不露,不顯示假資料**。mock mode 四張卡與 donut 全部保留不受影響。
  `PortfolioSummaryDto` 其餘欄位(`totalCostBasis` / `realizedPnl` / `unrealizedPnl` / `totalPnl` / `holdingCount`)的呈現位置由 planner 決定;`Positions.vue` 既有的彙總條(`pnlAbs` / `totalCost`,:79-83)是自然的落點。

- **D-15:** **篩選或排序條件變更時,頁碼一律重置為第 0 頁**;另需處理「請求頁碼 ≥ `totalPages`」的回退(例如篩選後總頁數變少)。
  否則使用者在第 5 頁切換篩選,前端仍送 `page=4`,後端回空 `items` 與 `totalPages=1`,畫面顯示空列表 —— 使用者會以為沒有符合的交易。這是 server-side 分頁 + 篩選的必經處理,不可省略。

- **D-16:**(2026-07-23,research 盤點合成資料邊界後,依 D-14 原則的一致延伸)**API mode 隱藏所有「無後端資料來源的合成/寫死區塊」**,除 D-14 已列者(今日損益、可用現金、資產配置 donut)外,還包括:
  - Positions 的 **Sector breakdown 卡**(:118-140,見 D-01 更正)
  - Positions 的**權益曲線 / 時光機(time machine)/ Sharpe・年化・MaxDD 假 KPI**(合成序列與寫死值)
  - Overview 的**資產走勢圖**(`genSeries(80, 1_000_000, 0.012, 5)` 亂數序列)
  mock mode 全部保留不受影響。此為 Yuan 於 discuss 中四度選擇「隱藏假資料」(D-02 股利、D-14 兩張 KPI + donut)之同一原則,不再逐項請示。**例外判準**:若某區塊可由既有後端欄位**真實推導**(如 weight ← `marketValue / totalMarketValue`),則以真實資料呈現而非隱藏 —— 隱藏只適用於「後端推導不出來」的內容。planner 對每個區塊需在 PLAN 中明確標記「隱藏」或「真實推導」並附依據。

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
- `.planning/REQUIREMENTS.md` — PORT-01 ~ PORT-05,以及 **PORT-08**(2026-07-19 新增:`/trades` 的篩選與排序參數)
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
  但同一事實的另一面是**風險**:`GET /portfolio/holdings` 沒有 page/size 參數,持倉數量無上界,每筆 13 個欄位(含多個 BigDecimal 與兩個時間戳)。持有數千檔標的的使用者會在單一回應中取得全部資料,影響首屏載入與記憶體,client-side 排序成本也隨之上升。本階段不改後端(維持不分頁),但 planner 應在 loading 狀態設計上考慮大清單,並在 SUMMARY 記錄實測的持倉筆數上限,供未來決定是否要分頁。
- 排序欄位刻意收斂為 `executedAt` / `price` / `quantity` 三個,而非「所有顯示欄位都可排」:後端要驗證欄位名以防注入、也要開索引,三個涵蓋「最近的」與「最大筆」兩種真實需求。
- 換頁按鈕而非 append,是為了迴避 page-number 分頁在插入資料時的位移/重複問題 —— 這個結論在 2026-07-19 的分頁對齊工作中已經驗證過(mock 測試 `reports page-number drift when a newer log is prepended` 明確斷言該行為)。

</specifics>

<deferred>
## Deferred Ideas

- **後端支援 DIV(股利)交易類型** — 已寫入 `.planning/todos/pending/2026-07-19-backend-dividend-trade-type.md`。不是加一個 enum 值就好:股利會改變成本/損益計算語意,`HoldingCalculator` 需要新分支,`CreateTradeRequest` 的 quantity/price 語意也不同,規模上很可能值得自己一個 phase。
- **後端支援日級損益(今日損益 KPI)** — `.planning/todos/pending/2026-07-19-backend-daily-pnl.md`。需日級持倉市值快照,並先定義「今日」(交易日/自然日、時區)。
- **後端支援可用現金 / 帳戶餘額模型** — `.planning/todos/pending/2026-07-19-backend-available-cash.md`。是新增領域模型而非加欄位,且可能讓語意往「帳戶系統」偏移,需先確認是否符合 PROJECT.md 範圍(judgment §1)。
- **後端支援資產分類(產業別 / 資產類別)** — `.planning/todos/pending/2026-07-19-backend-asset-classification.md`。涵蓋 Analytics 的產業分布(`sector`,PORT-06 v2)與 Overview 的資產配置 donut,兩者同源,關鍵是先決定分類資料的權威來源與分類標準。
- **多幣別呈現、空持倉的初始引導體驗、mock↔api 切換時的資料殘留** — 討論尾聲列為可選深入項目,使用者選擇直接進 CONTEXT,未展開。若 planner 認為影響驗收條件可回頭補。

</deferred>

---

*Phase: 3-portfolio-read-api-mode*
*Context gathered: 2026-07-19*
