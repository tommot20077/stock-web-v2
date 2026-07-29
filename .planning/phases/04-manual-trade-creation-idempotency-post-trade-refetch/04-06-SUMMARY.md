---
phase: 04-manual-trade-creation-idempotency-post-trade-refetch
plan: 06
subsystem: frontend
tags: [vue, typescript, domain-adapter, market-data, klines, vitest, mock-parity]

# Dependency graph
requires:
  - phase: 02-frontend-session-api-client-foundation
    provides: apiClient 的 apiRequest / apiPaginatedRequest / buildQueryString 與 ApiClientError
provides:
  - "MarketApi domain adapter 三件組（createHttpMarketApi / createMockMarketApi / createMarketApi）"
  - "AssetDto / KlineDto / KlineInterval 型別（apiTypes.ts:106,137,153）"
  - "closeSeries()：KlineDto.close（string）→ number[] 的唯一轉換點"
affects: [04-07 tradingApi 與 portfolioRevision, 04-09 OrderTicket symbol typeahead, 04-10 報價卡與走勢圖]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "domain adapter 三件組（http / mock / factory）——沿用既有 portfolioApi 慣例"
    - "string→number 轉換集中成單一具名純函式，讓 vue-tsc 抓不到的模板內型別誤用可被單測鎖住"

key-files:
  created:
    - "[FE] src/services/marketApi.ts"
    - "[FE] src/services/marketApi.test.ts"
  modified:
    - "[FE] src/services/apiTypes.ts"

key-decisions:
  - "DP-5：asset 搜尋與 klines 合併成單一 marketApi.ts，不拆 assetApi + marketApi，也不塞進 tradingApi.ts"
  - "MarketApi 刻意不提供 live：market domain 沒有 mock 專屬 reactive 狀態（與 portfolioApi 的 lastFill 相反），宣告成 live?: undefined 讓此事在型別層可被斷言"
  - "symbol 只 encodeURIComponent 絕不 toUpperCase——MarketController javadoc 明文大小寫敏感"
  - "mock 的 OHLCV 以 toFixed(8) 產生字串，與後端 BigDecimal 的 JSON 形狀同形，確保消費端只有一條資料路徑"
  - "mock 宇宙排除 BONDS：Bond 沒有 price/high/low，湊不出 AssetDto 且不可交易"

patterns-established:
  - "非分頁端點走 apiRequest、分頁端點走 apiPaginatedRequest——由端點的信封形狀決定，不由 domain 決定"
  - "typeahead 的 AbortSignal 由 adapter 原樣轉交 fetch，debounce 與「只採最後一次結果」留在元件層（DP-12）"

requirements-completed: [TRAD-01]

# Metrics
duration: 25min
completed: 2026-07-30
---

# Phase 04 Plan 06: market domain adapter Summary

**建立 D-01 缺少的 `marketApi` adapter —— 前端原本對 `api/v1/assets` / `klines` 零命中,OrderTicket 的 symbol 選單、報價卡、走勢圖全都無法接真後端。**

## ⚠️ 本 SUMMARY 的撰寫情境（誠實揭露）

原 executor 在 Task 2 的 GREEN commit **之前**因 session 限額被中止（`2026-07-30 03:20 重置`），留下已 commit 的 RED 測試與**未提交**的 `marketApi.ts`。本 SUMMARY 由 orchestrator 接手完成,因此:

- **Task 1 / Task 2 的 RED 失敗輸出我沒有親眼看到**,只能從 commit 訊息與 commit 順序推斷。下方「驗收證據」只列我**實際執行並看到輸出**的部分。
- `marketApi.ts` 的實作內容是原 executor 寫的,我做的是驗證(全套測試 + 型別檢查 + build)與提交。
- 我逐條機械驗證了 `must_haves` 的 artifacts 與 key_links(見對帳表),但**沒有**重跑一次 RED 來確認那些測試真的會因缺少實作而失敗。

## Performance

- **Duration:** 約 25 min（跨兩段:executor 23:45–23:47,orchestrator 接手 02:07–02:12）
- **Started:** 2026-07-29T23:45+08:00
- **Completed:** 2026-07-30T02:12+08:00
- **Tasks:** 2/2
- **Files modified:** 3（2 新增 / 1 修改）

## Accomplishments

- **分頁契約與裸陣列的差別被測試鎖住**:`searchAssets` 走 `apiPaginatedRequest`(`items / page / size / totalElements / totalPages`),`listKlines` 走 `apiRequest`(裸陣列)。兩者由端點的信封形狀決定,測試分別斷言「消費分頁信封而非裸陣列」與「非分頁端點永不送 page/size」。
- **string→number 的轉換點唯一化**:`KlineDto` 的 OHLCV 在 JSON 是字串、`AssetDto` 的價格是 number,兩者相反。`closeSeries()` 是唯一轉換點,測試斷言「只透過 closeSeries 解析」—— 這正是 `vue-tsc` 在模板內抓不到的那類錯誤(Pitfall 8)。
- **mock 與 API mode 同形**:mock 的 `AssetDto` 由 `data.ts` 的 `SYMBOLS/CRYPTO/FX` 投影,OHLCV 一樣是字串。消費端沒有 `if (live)` 分支去讀本地假資料(judgment §3:元件永遠不 import mock store)。
- **symbol 大小寫保留**:`encodeURIComponent` 而不 `toUpperCase`,並有專測鎖住(後端 `MarketController` 明文大小寫敏感)。
- **typeahead 取消能力就位**:`AbortSignal` 原樣轉交,測試斷言「未被 adapter 加工」,供 04-09 的 250ms debounce 使用。

## Task Commits

1. **Task 1:apiTypes 新增 AssetDto / KlineDto / KlineInterval** — `c11280e` (feat)
2. **Task 2:marketApi.ts 三件組 + 測試**
   - `9003313` (test) — RED:先寫 `marketApi.test.ts`（12 條）
   - `de4fde4` (feat) — GREEN:`marketApi.ts` 207 行（由 orchestrator 接手提交）

## Files Created/Modified

- `[FE] src/services/marketApi.ts`（新增,207 行）— `MarketApi` 介面 + `createHttpMarketApi` / `createMockMarketApi` / `createMarketApi` + `closeSeries`。
- `[FE] src/services/marketApi.test.ts`（新增,12 條測試）— 4 個 describe:http searchAssets(4)、http listKlines(4)、mock adapter(3)、factory(1),合計 12,與執行結果 `12 passed` 一致,0 skip。
- `[FE] src/services/apiTypes.ts`（修改）— `:106 AssetDto`、`:137 KlineDto`、`:153 KlineInterval`。

## must_haves 對帳（機械驗證）

| 項目 | 要求 | 實測 | 狀態 |
|------|------|------|------|
| `marketApi.ts` 行數 | ≥ 80 | 207 | PASS |
| `marketApi.ts` exports | MarketApi / createMarketApi / createHttpMarketApi / createMockMarketApi | 四者皆在（另有 `closeSeries`） | PASS |
| `apiTypes.ts` contains | `export interface AssetDto` | 1 處命中（:106） | PASS |
| `marketApi.test.ts` contains | `vi.stubGlobal('fetch'` | 9 處命中 | PASS |
| key_link → `/api/v1/assets` | `apiPaginatedRequest<AssetDto>` | 1 處命中 | PASS |
| key_link → `/api/v1/market/{symbol}/klines` | `klines` | 5 處命中 | PASS |

## Success Criteria 對帳

| 條件 | 狀態 | 證據 |
|------|------|------|
| 可查後端真實可交易標的（含 tradeable / latestPrice / change 等欄位） | PASS | `AssetDto`（apiTypes:106）欄位齊全;mock 投影測試斷言同形 |
| 可取 K 線並轉成 LineChart 需要的 `number[]` | PASS | `closeSeries` + 「只透過 closeSeries 解析」測試 |
| `searchAssets` 走分頁契約而非裸陣列 | PASS | 「consumes the paginated envelope, not a bare array」綠 |
| OHLCV 的 string→number 對消費端透明 | PASS | 同上;mock 亦回字串（「same shape as API mode」測試） |
| mock mode 由 `data.ts` 組出同形資料 | PASS | 「returns AssetDto-shaped items without touching the network」綠 |

## 驗收證據（我實際執行並看到的輸出）

### 聚焦測試 — `npx vitest run src/services/marketApi.test.ts`

```
 RUN  v4.1.6 D:/end/workspace/vue/stock-v2/vue-app

 Test Files  1 passed (1)
      Tests  12 passed (12)
   Duration  3.26s
```

### 全套回歸 — `npm test`

```
 Test Files  32 passed (32)
      Tests  245 passed (245)
   Duration  117.26s
```

### 型別檢查 + build — `npm run build`（`vue-tsc --noEmit && vite build`）

```
vite v8.0.13 building client environment for production...
✓ 133 modules transformed.
dist/assets/index-_8zIP8u2.js   358.28 kB │ gzip: 120.13 kB
✓ built in 2.12s
```

`vue-tsc --noEmit` 無輸出即通過（有型別錯會中止,不會走到 vite build）。

### 提交後工作樹乾淨

```
$ git status --porcelain
（空）
```

## Deviations from Plan

**1. [執行中斷] 原 executor 在 GREEN commit 前被 session 限額中止**

- **Found during:** Task 2 GREEN
- **Issue:** `marketApi.ts` 已寫完但未提交,`04-06-SUMMARY.md` 未產生。若不處理,orchestrator 會判定 04-06 未完成而重跑,可能與既有未追蹤檔衝突。
- **Fix:** orchestrator 接手,先驗證(聚焦測試 → 全套 → 型別檢查 → build)再提交 `de4fde4`,並補寫本 SUMMARY。
- **Verification:** 見上方驗收證據;全部為我實際執行的輸出。
- **Committed in:** `de4fde4`

**2. [TDD 順序] Task 1 的型別新增沒有獨立的 RED commit**

- **Found during:** 事後對帳 commit 順序
- **Issue:** Task 1 標記 `tdd="true"`,但 `c11280e`(feat, 23:45:58)在 `9003313`(test, 23:47:54)**之前**。也就是型別先進、會因缺型別而失敗的測試後寫,嚴格說違反 Red→Green。
- **判斷:** 屬低風險。純型別宣告無法單獨構成有意義的 RED(沒有行為可斷言),而 `marketApi.test.ts` 若在無型別的狀態下寫確實會編譯失敗——RED 實質存在,只是順序反了。
- **Fix:** 不回溯重做（回溯需要 revert 已驗證的 commit,風險大於收益）。記錄於此供 review 時判斷。
- **⚠️ 給 Yuan:** 這一條是 CLAUDE.md 的硬約束,若你認為必須嚴格執行,可以要求重做 Task 1 的 commit 順序。

---

**Total deviations:** 2（1 執行中斷已補救 / 1 TDD 順序瑕疵已記錄）
**Impact on plan:** 產出與 `must_haves` 逐條一致,無範圍蔓延。本 plan 未碰 `Markets.vue` / `Chart.vue`（那屬 PORT-06 v2),`git log --name-only` 確認只動了 3 個檔案。

## Issues Encountered

- **原 executor 被 session 限額中止**（`resets 3:20am`）。這是環境限制,非程式問題。後續 wave 若在 03:20 前執行仍可能再次中斷。

## Known Stubs

無。`createHttpMarketApi` 與 `createMockMarketApi` 皆為完整實作,無 TODO、無佔位。

**但注意:** `MarketApi.live` 宣告為 `live?: undefined` 是**刻意**的型別斷言（market domain 沒有 mock 專屬 reactive 狀態），不是待補實作。04-09/04-10 若需要 reactive 的 mock 行情,必須先在此處做設計變更而非直接加欄位。

## User Setup Required

None —— 純前端,無外部服務。

## Next Phase Readiness

**Ready for 04-07（tradingApi 與 portfolioRevision，wave 2）:**

- `apiTypes.ts` 已有 `AssetDto` / `KlineDto` / `KlineInterval`,04-07 可直接 import。
- adapter 三件組慣例已在 market domain 落地,04-07 照同一形狀寫 tradingApi。

**移交注意事項:**

1. **前端 repo 目前分支為 `feature/phase-04-manual-trade-creation`,尚未 push。** 它從 `origin/develop @ a03e030` 開,是 orchestrator 預先建好的（見後端 commit `f8a7eeb`）;executor **不要**自行 `git switch`。
2. **前端 repo 不在 GSD 的 worktree 隔離範圍內**（`sub_repos` 為空）,所有前端 plan 共用同一個工作樹。目前的 wave 編排每個 wave 最多一個 FE plan,所以安全;**若日後有人把兩個 FE plan 排進同一 wave,會直接互相踩踏。**
3. `closeSeries` 是 string→number 的唯一轉換點,04-10 畫走勢圖請走它,不要在元件裡自己 `Number()`。

---
*Phase: 04-manual-trade-creation-idempotency-post-trade-refetch*
*Completed: 2026-07-30*

## Self-Check: PASSED（含已知限制）

- 3 個檔案與 3 個 commit hash 皆已於 repo 驗證存在。
- 所有「驗收證據」段落的輸出均為本次實際執行所得。
- 測試數已核對:`grep -cE "^\s*it\("` → 12,`it.skip/todo` → 0,與 `12 passed` 一致。
- **未驗證項:** Task 1 / Task 2 的 RED 失敗輸出（原 executor 執行,我未親見）。
