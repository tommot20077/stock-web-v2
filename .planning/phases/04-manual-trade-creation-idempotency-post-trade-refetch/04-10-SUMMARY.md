---
phase: 04-manual-trade-creation-idempotency-post-trade-refetch
plan: 10
subsystem: frontend
tags: [order-ticket, typeahead, combobox, debounce, abortcontroller, klines, a11y, vue, vitest]

# Dependency graph
requires:
  - phase: 04-manual-trade-creation-idempotency-post-trade-refetch
    provides: 04-06 marketApi(searchAssets / listKlines / closeSeries)、04-08 的 i18n key、04-09 的 OrderTicket 三步驟骨架
provides:
  - "symbol typeahead 的七態(idle/loading/loaded/truncated/empty/filtered-empty/error)"
  - "250ms debounce + 遞增 request id + AbortController 的競態處理"
  - "combobox 的完整鍵盤操作(ArrowUp/ArrowDown/Enter/Esc + aria-activedescendant)"
  - "報價卡六格改接 AssetDto 真實數字,零前端計算"
  - "走勢圖接 GET /market/{symbol}/klines,含 loading/empty/error 三態"
affects: [04-11 key 生命週期與錯誤分派, 04-12 送出後三頁重讀, Phase 5 真實後端驗證]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "per-block 狀態機加一個 idle 態:區塊在「還沒查過」與「查了但空」之間必須可分辨"
    - "debounce 與競態是兩件事:setTimeout 壓請求量,遞增 request id 壓亂序返回,缺一不可"
    - "AbortController 造成的 DOMException 由 seq 守衛順帶吃掉——被取代的請求一律不寫回狀態"
    - "元件測試以 vi.mock 的 stub 元件攔截 prop,斷言傳進去的值而非渲染出來的 SVG"

key-files:
  created: []
  modified:
    - "[FE] src/components/OrderTicket.vue"
    - "[FE] src/components/OrderTicket.test.ts"

key-decisions:
  - "競態同時用遞增 request id 與 AbortController:id 是正確性保證(單獨即足夠),abort 是對公開端點的好公民行為"
  - "使用者打字時的精準命中不關閉下拉,只有 preset 解析才關——否則候選還沒看清楚就被收走"
  - "走勢圖 error 態不鎖死 96px 改用 min-height:診斷列加重試鈕放不進 96px,硬鎖會裁掉重試鈕"
  - "idle 只列前 6 筆,truncated 提示只在有關鍵字時出現——否則會出現「僅顯示前 10 筆」卻只列 6 筆的自相矛盾"

patterns-established:
  - "typeahead 契約:debounce 與競態各要一條獨立測試,兩者不可互相代替"
  - "「刻意矛盾的 fixture」是鎖住『前端不得重算』的最便宜手段"

requirements-completed: [TRAD-01]

# Metrics
duration: 約 45min
completed: 2026-08-16
---

# Phase 04 Plan 10: symbol 選單、報價卡、走勢圖接真實資料

**把 order ticket 右半邊從「一格都不是真的」變成「每一格都可追溯到後端某個欄位」——七態 typeahead、零計算報價卡、klines 走勢圖三態,全部由 34 條元件測試鎖住。**

## Performance

- **Duration:** 約 45 min
- **Tasks:** 2/2(各一組 RED → GREEN)
- **Files modified:** 2(皆在前端 repo)

## Accomplishments

- **symbol 選單的每一筆都來自 `GET /api/v1/assets`**,且只有 `tradeable === true` 的標的能讓送出鈕可用(D-01)。前端唯一的本地過濾就是這個 `tradeable` 判斷,**沒有任何本地關鍵字過濾**(後端已依 `query` 篩選,前端重做等於複製後端邏輯)。
- **250ms debounce 與競態處理各有獨立測試**。Test 2 鎖「連打四次只發一次請求且 query 是最後一次」,Test 3 鎖「舊查詢晚回應不得覆蓋新查詢」。這兩件事不可互相代替 —— debounce 之後的兩個請求在慢網路下仍可能亂序返回。
- **鍵盤使用者現在可以完整選標的**。04-09 的骨架只綁 `@mousedown`,鍵盤路徑完全不存在;本 plan 補上 `aria-activedescendant` 與 ArrowUp/ArrowDown/Enter/Esc,滑鼠路徑原樣保留。
- **報價卡六格零前端計算**。Test 13 用刻意矛盾的 fixture(`high: 999` / `low: 1` / `changePercent: -1.60` 與 `latestPrice: 190.20` 彼此不自洽)—— 任何一格若由前端推導,斷言會立刻紅。
- **走勢圖是前端第一個 klines 消費端**,`data` prop 一律經 04-06 的 `closeSeries` 轉換;Test 16 用字串 OHLCV 的 fixture 鎖住 Pitfall 8。
- **U-11 有測試撐著**:Test 20 逐一驗證走勢圖 loading / empty / error 三態下送出鈕仍可按,並在 `canSubmit` 上留了繁中註解說明理由。

## Task Commits

前端 repo `D:\end\workspace\vue\stock-v2`,分支 `feature/phase-04-manual-trade-creation`:

1. **Task 1:symbol typeahead 七態 + debounce + 競態 + 鍵盤**
   - `48dcdf3` (test) — RED:12 條測試
   - `d6ccbff` (feat) — GREEN
2. **Task 2:報價卡真實數字 + 走勢圖三態**
   - `0495de0` (test) — RED:9 條測試
   - `df41859` (feat) — GREEN
   - `3c49ab3` (fix) — Rule 2 補的 `volumeText` null 處理(見 Deviations)

本 SUMMARY 提交在後端 repo `feature/phase-04-trade-idempotency` 分支。**兩個 repo 都未 push。**

## Files Created/Modified

- `[FE] src/components/OrderTicket.vue` — 兩組 per-block 狀態機(symbol 下拉、走勢圖)、debounce 與競態、combobox 鍵盤、報價卡六格、走勢圖三態與 96px 版位。
- `[FE] src/components/OrderTicket.test.ts` — 13 條(04-09)→ **34 條**。新增 21 條全部為本 plan。

## Decisions Made

### 1. 競態機制:遞增 request id **與** AbortController 併用

plan 允許二選一,實作選擇兩者都用,理由不同:

| 機制 | 負責什麼 | 為什麼不能只有它 |
|------|---------|-----------------|
| 遞增 request id(`searchSeq`) | **正確性** —— 非最後一次的回應整包丟棄 | 單獨即足夠正確,但無法停止已在飛的請求 |
| `AbortController` | **禮貌** —— 取消被取代的請求、unmount 時中止 | 單獨不夠:abort 對已經送達伺服器的請求無效,舊回應仍可能先寫回 |

**`DOMException` 的處理方式(plan 明文要求記錄):** 不做 `error.name === 'AbortError'` 的型別判斷。abort 只可能發生在「已被新查詢取代」或「元件已卸載」的請求上,而這兩種情況 `seq !== searchSeq` 恆成立,所以 `catch` 開頭的那行 `if (seq !== searchSeq) return;` **順帶把 DOMException 吃掉了**。這比多寫一條 `instanceof DOMException` 判斷更難寫錯 —— 後者若漏了某條路徑就會把「已取消」渲染成錯誤態。

```ts
} catch (error) {
  // 已被新查詢取代的請求一律不寫回狀態 —— 這同時吃掉 AbortController 造成的
  // `DOMException`(abort 讓 fetch reject,但那是「已取消」不是「錯誤」,不得顯示錯誤態)。
  if (seq !== searchSeq) return;
  symbolState.value = { status: 'error', error: describeError(error) };
}
```

### 2. klines 參數 `interval=1h` / 48 小時 / `limit=48` 是 `[ASSUMED]` 值

plan 明文要求記錄:這組參數來自 `04-RESEARCH.md` A8 的 **`[ASSUMED]` 建議值,不是既有慣例**。前端在本 plan 之前對 klines 端點零消費,所以沒有任何前例可對齊。程式碼註解已標明可依實際資料密度調整,**不應被後續 plan 當成契約**。

`from` 用 `new Date(Date.now() - 48h).toISOString()` 的完整 ISO instant —— 後端 `from` 是 `@RequestParam Instant`,格式錯在 develop 上會回 **500** 而非 400。

### 3. `AssetDto` 的價格欄位**確實**可能為 null —— 已實讀後端確認

plan 要求「若本 plan 期間有實讀,記錄結論」。**已實讀,結論是會 null**:

```sql
-- stock-module-asset/.../repository/AssetRepository.java:27-29
select a.*, p.price latest_price, p.change, p.change_percent, p.volume_text, p.high, p.low
from assets a
left join asset_latest_prices p on p.asset_id = a.id
```

`left join` 意味著**任何在 `asset_latest_prices` 沒有對應列的資產,六個行情欄位全部是 SQL NULL**;row mapper(`:68`)用 `rs.getBigDecimal(...)` / `rs.getString("volume_text")` 直接取,null 原樣進 `AssetDto`。這不是理論風險 —— dev/demo 環境只要沒跑過行情匯入就會整批 null。

因此 RESEARCH A7 的 `[ASSUMED]` **可以升格為已查證**:`latestPrice` / `change` / `changePercent` / `high` / `low` / `volumeText` 六者皆可為 null。

### 4. 使用者打字時的精準命中**不關閉**下拉

`pickAsset` 加了 `{ close }` 參數。preset 解析成功 → 關閉(使用者沒在看下拉);打字打出精準命中 → **不關閉**(他還在輸入,把候選收走等於搶他的滑鼠)。04-09 的版本一律關閉,那會讓「打完 AAPL 想再看看別的」變成必須刪字才能回到清單。

### 5. idle 只列 6 筆,`truncated` 提示只在有關鍵字時出現

UI-SPEC §2 的 idle 是「第 0 頁前 6 筆」,而 `symbolMoreResults` 的文案寫死「僅顯示前 10 筆」。若 idle 也顯示這條提示,畫面會是「說 10 筆、列 6 筆」的自相矛盾。實作上請求一律 `size: 10`,idle 顯示時 `slice(0, 6)`,而 `symbolTruncated` 額外要求 `activeQuery !== ''`。

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] `volumeText` 為 null 時留空白格**

- **Found during:** Task 2 收尾時實讀後端 `AssetRepository`(為了回答 plan output 的第 3 點)
- **Issue:** `apiTypes.ts` 把 `volumeText` 宣告為 `string`(非 nullable),但後端是 `left join` + `rs.getString("volume_text")`,沒有行情列時就是 null。報價卡其他五格都會落到 `—`,唯獨這格會渲染成**空白**,使用者無法分辨「沒有資料」與「畫面壞了」。
- **Fix:** 模板改為 `{{ selected.volumeText || '—' }}`,並在旁註明後端的 `file:line` 依據。**沒有**動 `apiTypes.ts` 的型別宣告 —— 那會連帶影響 `marketApi.ts` 與其 12 條單測,屬於跨 plan 的契約調整(見下方 Next Phase Readiness)。
- **Verification:** `VITE_DATA_MODE=api npx vitest run src/components/OrderTicket.test.ts` 34/34 綠;`npm run build` exit 0。
- **Committed in:** `3c49ab3`

### 已知的 acceptance criteria 落差(誠實記錄,非自動修正)

**Task 1 的 `該檔不含 CRYPTO` 未達標,實測 2 處命中,判定為 grep 口徑過寬。**

```
644:const qtyStep = computed(() => (selected.value?.assetType === 'CRYPTO' ? 0.01 : 1));
701:  if (qty.value === 0) qty.value = asset.assetType === 'CRYPTO' ? 0.05 : 10;
```

兩處都是與**後端 `AssetType` enum 常數名**比較(`apiTypes.ts:108` 明文:「後端 AssetType enum 常數名:STOCK / CRYPTO / FX / BOND」),不是本地假資料集 `data.ts` 的 `CRYPTO` 陣列。該條 criteria 的意圖是「本地假資料已完全脫離」,而 `SYMBOLS` / `FX` / `BONDS` 三者實測皆為 **0**,`import ... from '../data'` 也只剩 `fmtNum` / `fmtPct` 兩個純格式化函式。逐字滿足這條 grep 只能靠「不再依 assetType 決定數量步進」,那是為了通過 grep 而弄壞行為。

**這與 04-09 的同型偏離是同一個根因**:acceptance 用的 grep 比它自己的禁止條文更寬。順帶處理:04-09 留下的一行註解字面上含有 `SYMBOLS / CRYPTO / FX / genSeries`,讓 grep 產生假警報(也會讓 Task 2 的「不含 `genSeries`」誤判),本次已改寫該註解,現在 `genSeries` 命中為 **0**。

---

**Total deviations:** 1 auto-fixed(Rule 2 缺少 null 處理)+ 1 誠實記錄的 criteria 落差
**Impact on plan:** 無範圍蔓延。`git log --name-only` 確認本 plan 只動了 2 個檔案,皆為 plan 的 `files_modified` 所列。

## TDD 誠實記錄(CLAUDE.md 硬約束)

**我親眼看到的 RED**:

| Task | RED 指令輸出 | 紅 / 綠 |
|------|-------------|--------|
| Task 1 | `VITE_DATA_MODE=api npx vitest run src/components/OrderTicket.test.ts` | **11 紅** / 1 綠(共 12 條新測試) |
| Task 2 | 同上 | **6 紅** / 4 綠(共 9 條新測試,含一次強化) |

**一寫就綠的測試,逐條說明(不假稱經歷過 RED)**:

| 測試 | 為什麼一寫就綠 |
|------|--------------|
| Test 10(不可交易標的不得讓送出鈕可用) | 04-09 骨架的 `searchAssets` 已做 `filter(tradeable)`,未選取即 `canSubmit === false`。本 plan 只是把這個不變量寫成測試 |
| Test 15(報價卡不發第二個請求) | `/market/{symbol}/latest` 從來沒被呼叫過。這是**防迴歸**測試,不是驅動實作的測試 |
| Test 20(走勢圖失敗不阻擋送出) | `canSubmit` 從未引用 klines(因為 klines 當時還不存在)。同樣是防迴歸 —— 它的價值在**未來**有人「順手」加進去時會紅 |
| Test 21(價格預填且可編輯) | 04-09 已實作,本 plan 用新 fixture 覆蓋 |

**一條初版假綠、已強化為真 RED 的測試**:Test 18(走勢圖 empty)初版只斷言「回空陣列時顯示 `quoteChartEmpty`」,而 04-09 骨架**寫死**了一個 `data-testid="ticket-quote-chart-empty"` 佔位,所以初版一寫就綠 —— 那是假綠。已補上第二段:「有 K 線資料時 `ticket-quote-chart-empty` 必須消失、且真的畫出走勢圖」,補完後轉紅,實作後轉綠。

## Issues Encountered

**`flushAsync` 的預設 6 輪排不乾淨長 microtask 鏈。** Task 2 的 GREEN 首次執行時,Test 16/18/19 仍紅但錯誤形態是「走勢圖永遠停在 loading」。原因是 `preset 解析 → response.json() → pickAsset → watch(selected) → listKlines → response.json()` 是一條比骨架階段更長的鏈,`flushAsync()` 的預設 6 輪 microtask + nextTick 排不完。把 `mountApiTicket` 內改為 `flushAsync(16)` 後全綠。

這是**測試工具的排乾深度**問題,不是實作競態 —— 確認方式:同一份實作在 `flushAsync(16)` 下 34/34 綠且完全穩定,且 Test 17(刻意讓 klines pending)仍正確停在 loading,證明狀態機本身沒有被「多等幾輪」掩蓋的 bug。

## 驗收指令與結果(全部為我實際執行的輸出)

| 指令 | 結果 |
|------|------|
| `VITE_DATA_MODE=api npx vitest run src/components/OrderTicket.test.ts` | **34/34 綠**(04-09 的 13 條 + 本 plan 的 21 條) |
| `npm test`(mock mode) | 35 files / **309 tests** 全綠(基準線 288 → +21) |
| `VITE_DATA_MODE=api npm test` | 35 files / **309 tests** 全綠 |
| `npm run build`(`vue-tsc --noEmit && vite build`) | exit 0 |

### acceptance criteria 逐條對帳

**Task 1**

| 條件 | 實測 |
|------|------|
| `role="combobox"` / `aria-autocomplete="list"` / `aria-activedescendant` / `role="listbox"` / `role="option"` | 各 1 命中 ✅ |
| 含 `setTimeout`(3)與 `clearTimeout`(2) | ✅ |
| **不含** `lodash` / `debounce'` | 各 0 ✅ |
| 含 `market.searchAssets` | 1 ✅ |
| 九個 typeahead testid | 全數命中 ✅ |
| **不含** `SYMBOLS` / `FX` / `BONDS` | 各 0 ✅ |
| **不含** `CRYPTO` | ❌ 2 命中 —— 見上方偏離記錄(後端 enum 常數名) |
| 聚焦測試 exit 0 | ✅ |

**Task 2**

| 條件 | 實測 |
|------|------|
| 含 `market.listKlines`(1)與 `closeSeries`(3) | ✅ |
| **不含** `genSeries` | 0 ✅ |
| 四個走勢圖 testid | 全數命中 ✅ |
| `canSubmit` 不引用 klines | ✅(人工檢視 + Test 20;程式碼旁有繁中註解說明理由) |
| 含 `interval: '1h'` 與 `limit: 48` | 各 1 ✅ |
| 走勢圖容器 `height: 96px` | ✅(error 態改用 `min-height: 96px`,見 Decisions) |
| `npm test` / API mode / `npm run build` | 全部 exit 0 ✅ |

### 自我驗證 grep(§Typography / §Spacing)

**字級**(`grep -nE 'font-(size|weight)'`):值域 `{12, 13, 16, 20}` × `{400, 600}`,**零表外違規**。

**間距**(`grep -nE '(padding|margin|gap)[^:]*:[^;]*[0-9]+px'`):本 plan 新增的 `.sym-truncated` / `.block-state` / `.block-error` / `.block-retry` / `.skeleton-row` / `.chart-loading` / `.chart-error` 全部為 4 的倍數。全檔唯一非 4 倍數仍是 `.step-dots { gap: 6px }`(§Spacing Exceptions 第 2 類,裝飾性 step dot)。

## Known Stubs

無。symbol 下拉與走勢圖皆為完整實作,無 TODO、無佔位。04-09 那個寫死的 `ticket-quote-chart-empty` 佔位**已被真實的三態機取代**。

## 誠實列出本 plan **未**涵蓋的事

- **走勢圖的視覺正確性(線畫得對不對)** —— `04-VALIDATION.md` §Manual-Only 明列為人工檢視項,元件測試斷言的是 `data` prop 而非 SVG path。**本 plan 沒有開過瀏覽器**,這一項未經人眼確認,需真實後端,屬 **Phase 5** 範圍。
- **真實 payload 與 fixture 的一致性**(`KlineDto` 的 string vs number)—— 屬 **Phase 5 / VER-03**。fixture 已依要求在上方註明後端 `file:line` 以降低漂移,但那是降低風險,**不是覆蓋**。
- **送出路徑的 key 生命週期 / 錯誤分派 / SELL 預檢** → 04-11(本 plan 完全沒碰)。
- **送出後三頁重讀與 fresh 高亮** → 04-12。

## User Setup Required

None —— 純前端,無外部服務、零新增 npm 依賴。

## Next Phase Readiness

**Ready for 04-11:**

- `describeError` / `BlockError` / `BlockState` 已在 `OrderTicket.vue` 內就位,04-11 的錯誤分派可以直接沿用同一份形狀,不需要再造一次。
- `canSubmit` 的組成已固定,04-11 只需在其上加 SELL 預檢條件。

**移交注意事項:**

1. **`apiTypes.ts` 的 `AssetDto.volumeText` 型別與後端不符**(宣告 `string`,實際可 null)。本 plan 用 `|| '—'` 在消費端擋住,但**根因未修**。建議在 04.1 或 Phase 5 一併把 `volumeText: string | null` 對齊,屆時要連 `marketApi.ts` 的 mock 投影與其單測一起改。
2. **klines 的三個參數是 `[ASSUMED]` 值**,不要當契約引用。
3. 前端 repo 仍在 `feature/phase-04-manual-trade-creation`,**未 push**;所有 FE plan 共用同一個工作樹,同 wave 排兩個 FE plan 會互相踩踏(04-06 SUMMARY 已警告過)。

---
*Phase: 04-manual-trade-creation-idempotency-post-trade-refetch*
*Completed: 2026-08-16*

## Self-Check: PASSED

- 5 個 commit hash(`48dcdf3` / `d6ccbff` / `0495de0` / `df41859` / `3c49ab3`)皆已於前端 repo `git log` 驗證存在。
- 2 個修改檔案皆存在且已提交;工作樹乾淨。
- 測試數已核對:`OrderTicket.test.ts` 的 `it(` 計數 34,`it.skip/todo` 0,與 `34 passed` 一致。
- 本 SUMMARY 的所有指令輸出均為本次實際執行所得;RED 的紅綠比例為實測值,未經美化。
- 未修改 `STATE.md` / `ROADMAP.md`;未觸碰任何後端原始碼;未執行 `git push`。
