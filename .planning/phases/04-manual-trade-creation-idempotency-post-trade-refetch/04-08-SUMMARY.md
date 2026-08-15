---
phase: 04-manual-trade-creation-idempotency-post-trade-refetch
plan: 08
subsystem: frontend
tags: [vue, i18n, adapter-wiring, runtime-clients, copywriting-contract, vitest]

# Dependency graph
requires:
  - phase: 04-manual-trade-creation-idempotency-post-trade-refetch
    provides: 04-06 的 createMarketApi、04-07 的 createTradingApi
provides:
  - "RuntimeApiClients 的 trading / market 註冊（元件經 getRuntimeApiClients() 取得,不 import mock store）"
  - "Phase 4 全部 zh/en 文案（含 15 條錯誤文案,權威來源 04-UI-SPEC §Copywriting Contract）"
  - "API mode 不靜默回退 mock 的防線測試（Phase 2 D-20 的延伸）"
affects: [04-09 OrderTicket 重建, 04-10 報價卡與走勢圖, 04-11 確認流程, 04-12 三頁 refetch]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "文案契約以測試落實:不只測 key 存在,還測「說了什麼」——每條送出後錯誤都必須含「交易未記錄」"
    - "同義字防線:測試明列必須複用的既有 key,阻止後續 plan 各自新增同義字"

key-files:
  created: []
  modified:
    - "[FE] src/services/pageApiClients.ts"
    - "[FE] src/api-adapter-wiring.test.ts"
    - "[FE] src/i18n.ts"
    - "[FE] src/i18n.test.ts"

key-decisions:
  - "closeTicket 為新 key:i18n.ts 內 close 零命中,而 cancel 已被 footer 的「取消」佔用;同一 dialog 兩個控件共用 label 會讓螢幕閱讀器連播兩個無法分辨的「取消」"
  - "欄位級錯誤的合法範圍靜態寫死（抄自 CreateTradeRequest 註解）,不從後端訊息解析——避免文案隨後端英文訊息漂移"
  - "底部錯誤依 error.code 分派,不假設錯誤出現順序"
  - "文案一次補齊而非每個 plan 零星新增:避免 i18n.ts 的檔案衝突與「只加了一邊語言」的漏網"

patterns-established:
  - "i18n 測試同時鎖住三件事:雙語存在性、t() 不等於 key 本身、以及文案的語意承諾（含/不含特定字串）"

requirements-completed: [TRAD-02, TRAD-06]

# Metrics
duration: 跨兩段（executor 約 3min + orchestrator 接手約 6min）
completed: 2026-08-15
---

# Phase 04 Plan 08: adapter 註冊與 Phase 4 全部文案 Summary

**把 04-06 / 04-07 的兩個 adapter 接進 runtime 客戶端工廠,並一次補齊 Phase 4 的 zh/en 文案 —— 後續四個前端 plan 的共同前提。**

## ⚠️ 本 SUMMARY 的撰寫情境（誠實揭露）

原 executor 在 Task 2 的 GREEN commit **之前**因 session 限額被中止(中止訊息停在「Now GREEN — adding the 42 keys to both languages」),留下已 commit 的 RED 測試與**未提交**的 `i18n.ts`(+104 行)。本 SUMMARY 由 orchestrator 接手完成:

- **Task 1 / Task 2 的 RED 失敗輸出我沒有親眼看到**,只能從 commit 順序推斷。下方「驗收證據」只列我**實際執行並看到輸出**的部分。
- `i18n.ts` 的文案是原 executor 寫的;我做的是驗證與提交(`4d3abcf`)。

## Performance

- **Started:** 2026-07-30T23:10+08:00（executor）
- **Completed:** 2026-08-15T14:34+08:00（orchestrator 接手;中間隔了兩週的中斷期）
- **Tasks:** 2/2
- **Files modified:** 4（全部為修改,無新增檔案）

## Accomplishments

- **adapter 接線完成且有不回退防線**:`getRuntimeApiClients().trading` / `.market` 可用;`api-adapter-wiring.test.ts` 斷言 API mode 下兩個 mock factory **一次都沒被呼叫**(`mockFactoryCalls.trading`)—— 這是 Phase 2 D-20「不靜默回退 mock」的延伸。
- **文案契約不只測存在,還測語意**:`i18n.test.ts` 的四條關鍵測試分別鎖住
  - 「除網路錯誤外,每條送出後錯誤都說『交易未記錄』」
  - 「絕不把重用的冪等鍵稱為『重複的請求』」(事實相反:什麼都沒建立)
  - 「網路結果未知時必須承諾『重試不會建立重複交易』」
  - 「Phase 4 字串裡不得出現券商/委託單生命週期詞彙」(這是記錄工具,不是下單系統)
- **同義字被測試擋住**:`loading` / `loadFailed` / `authRetry` / `authRequestId` / `cancel` / `fee` / `qty` / `price` / `notes` 等一律複用,有專測阻止後續 plan 各自新增同義 key。
- **雙語對稱**:每個 Phase 4 key 在 zh 與 en 兩邊都存在,且 `t()` 回傳值不等於 key 本身(避免「key 存在但沒翻譯」的假通過)。

## Task Commits

1. **Task 1:pageApiClients 註冊 trading / market + 不回退 mock 的防線**
   - `16ddb74` (test) — RED
   - `909ca55` (feat) — GREEN
2. **Task 2:i18n 新增 Phase 4 全部文案（zh + en）**
   - `a9d3d52` (test) — RED
   - `4d3abcf` (feat) — GREEN（由 orchestrator 接手提交）

## must_haves 對帳（機械驗證）

| 項目 | 要求 | 實測 | 狀態 |
|------|------|------|------|
| `pageApiClients.ts` contains | `trading: createTradingApi(mode, basePath)` | 1 處 | PASS |
| `i18n.ts` contains | `tradeErrKeyReused` | 4 處（zh/en 定義 + 測試引用鏈） | PASS |
| `api-adapter-wiring.test.ts` contains | `mockFactoryCalls.trading` | 3 處 | PASS |
| key_link → `tradingApi.ts` | `createTradingApi` | 2 處 | PASS |
| key_link → `marketApi.ts` | `createMarketApi` | 2 處 | PASS |

## 驗收證據（我實際執行並看到的輸出）

### adapter wiring — `npx vitest run src/api-adapter-wiring.test.ts`

```
 Test Files  1 passed (1)
      Tests  14 passed (14)
```

### 文案契約 — `npx vitest run src/i18n.test.ts`

```
 Test Files  1 passed (1)
      Tests  8 passed (8)
```

### 全套回歸 — `npm test`

```
 Test Files  34 passed (34)
      Tests  275 passed (275)
   Duration  160.21s
```

（04-06 完成時是 32 files / 245 tests,本 plan 新增 2 個測試檔 / 30 條測試。）

### 型別檢查 + build — `npm run build`（`vue-tsc --noEmit && vite build`）

```
✓ 133 modules transformed.
dist/assets/index-ol1FkpKu.js   366.36 kB │ gzip: 122.95 kB
✓ built in 3.20s
```

### 提交後工作樹乾淨

```
$ git status --porcelain
（空）
```

## Deviations from Plan

**1. [執行中斷] 原 executor 在 Task 2 的 GREEN commit 前被 session 限額中止**

- **Found during:** Task 2 GREEN（i18n 寫入中途）
- **Issue:** `i18n.ts` 的 zh/en 兩塊都已寫完但未提交。中止訊息「adding the 42 keys to both languages」顯示它自認在收尾階段。
- **Fix:** orchestrator 驗證(聚焦測試 → 全套 → 型別檢查 → build)後提交 `4d3abcf`,並補寫本 SUMMARY。
- **Verification:** 見上方驗收證據;全部為我實際執行的輸出。
- **Committed in:** `4d3abcf`

**2. [記錄] commit 訊息說「42 keys」,實際新增行數為 104**

- 104 行含 zh/en 兩份的註解區塊(各約 8 行的四條硬規則說明)與兩份 key 定義。**我沒有逐一數過 key 的數量**,只確認測試要求的雙語對稱性通過。若要精確數字需另行統計;此處僅記錄我未查證這一點。

---

**Total deviations:** 2（1 執行中斷已補救 / 1 未查證的數字差異）
**Impact on plan:** 產出與 `must_haves` 逐條一致,無範圍蔓延。`git log --name-only` 確認只動了 plan 列出的 4 個檔案。

## Issues Encountered

- **原 executor 被 session 限額中止**(這是 Phase 4 執行期間的第三次)。環境限制,非程式問題。

## Known Stubs

無。adapter 註冊與文案皆為完整實作。

**但注意:** 文案已備齊但**尚無消費端** —— `OrderTicket.vue` 的重建在 04-09~04-11。因此目前這些 key 只有測試在讀,實際畫面上的呈現(換行、截斷、長字串溢出)**未經任何視覺驗證**。

## User Setup Required

None —— 純前端,無外部服務。

## Next Phase Readiness

**Ready for 04-09（OrderTicket 重建,wave 4）:**

- `getRuntimeApiClients().trading` / `.market` 已可用,元件不需要 import 任何 mock store。
- Phase 4 文案已全數就位,04-09~04-12 **不應再改 `i18n.ts`**(若真的缺,先確認不是既有 key 的同義字)。

**移交注意事項:**

1. **前端 repo 分支 `feature/phase-04-manual-trade-creation`,尚未 push。**
2. **前端 repo 不在 GSD 的 worktree 隔離範圍內**,所有前端 plan 共用同一個工作樹。目前 wave 編排每個 wave 最多一個 FE plan;**若日後有兩個 FE plan 同 wave,會互相踩踏。**
3. **`closeTicket` 與 `cancel` 是刻意分開的兩個 key**(無障礙考量),04-09 實作 dialog 時勿合併。
4. 文案的視覺呈現未驗證,04-09~04-11 實作時請留意長字串(尤其 `tradeErrConflict` / `tradeErrNetwork` 這類長句)在窄容器的換行。

---
*Phase: 04-manual-trade-creation-idempotency-post-trade-refetch*
*Completed: 2026-08-15*

## Self-Check: PASSED

- 4 個檔案與 4 個 commit hash 皆已於 repo 驗證存在。
- 「驗收證據」段落的輸出均為本次實際執行所得。
- **未驗證:** Task 1 / Task 2 的 RED 失敗輸出(原 executor 執行,我未親見);新增 key 的精確數量;文案的視覺呈現。
