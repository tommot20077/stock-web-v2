---
phase: 04-manual-trade-creation-idempotency-post-trade-refetch
plan: 09
subsystem: frontend
tags: [order-ticket, mock-decoupling, a11y, ui-spec, i18n-cleanup, vue]

# Dependency graph
requires:
  - phase: 04-manual-trade-creation-idempotency-post-trade-refetch
    provides: 04-06 marketApi、04-07 tradingApi 三件組、04-08 的 adapter wiring 與 42 個 i18n key
provides:
  - "三步驟（ticket → review → result）的 OrderTicket 骨架，零 mock store 直接依賴"
  - "D-02 手續費與 D-03 成交時間欄位，含常駐說明與 a11y 關聯"
  - "OrderTicket.test.ts —— 此前不存在的專屬測試檔（13 條骨架契約）"
  - "services/localTime.ts —— toLocalIso / toLocalInputValue 共用模組"
affects: [04-10 typeahead 七態, 04-11 key 生命週期與錯誤分派, 04-12 送出後重讀]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "?raw 原始碼斷言鎖「這段程式碼不存在」——行為測試在 mock mode 抓不到 mock store 依賴"
    - "分支依據是 api.trading.live 的存在性，不是 VITE_DATA_MODE 字串"

key-files:
  created:
    - "[FE] src/components/OrderTicket.test.ts"
    - "[FE] src/services/localTime.ts"
  modified:
    - "[FE] src/components/OrderTicket.vue"
    - "[FE] src/pages/Trades.vue"
    - "[FE] src/task4.test.ts"
    - "[FE] src/i18n.ts"

key-decisions:
  - "toLocalIso 抽成共用模組而非複製：這個轉換的錯法（UTC 位移）會直接寫進 append-only 帳本"
  - "另立 toLocalInputValue：datetime-local 不接受 offset 也不接受秒，塞 toLocalIso 的輸出會被瀏覽器判為無效值"
  - "連點防護改為雙層（:disabled 視覺層 + submitTrade 的 JS 守衛），兩層都寫進測試"
  - "保留 i18n key `filled`（依 plan 明文），儘管 grep 顯示它其實也已無消費端"

patterns-established:
  - "測試遷移的判準：被保護的不變量是否改變。不變量不變 → 純實作細節遷移；不變量的保證機制改變 → 必須在測試名反映新意圖"

requirements-completed: [TRAD-01, TRAD-02]

# Metrics
duration: 約 30min（承接前一 session 未提交的 Task 2 進度）
completed: 2026-08-16
---

# Phase 04 Plan 09: OrderTicket 骨架重建

**把一個「沒有一項資料是真的」的四步驟下單 wizard，換成三步驟的交易記錄表單。**

## 接手時的狀態

前一個 session 已完成並提交 Task 1（`d728f94 test(04-09): 先寫 OrderTicket 骨架契約的失敗測試(RED)`），Task 2 的實作留在工作樹**未提交**（`OrderTicket.vue`、`Trades.vue` 已改，`services/localTime.ts` 未追蹤）。

本 session 的第一件事是**驗證**這份繼承來的工作而非直接信任它：13 條測試綠、逐條跑 Task 2 的 acceptance grep（結果見下方表格），確認無誤後才提交為 `e933640`。

## U-16 九列逐項對照

| # | 原位置 | 內容 | 處置 |
|---|--------|------|------|
| 1 | `:373` | `const slip = (Math.random() - 0.5) * 0.002;` | **刪除** |
| 2 | `:374` | `fillPx.value = ...` | **刪除**（連同 `fillPx` ref） |
| 3 | `:375` | `orderId.value = String(Math.floor(Math.random() * 90_000_000) + 10_000_000);` | **刪除**（連同 `orderId` ref）；result 改渲染後端回傳的 `TradeDto` |
| 4 | `:358` | `if (placing.value \|\| step.value === 'placing') return;` | **改守衛條件** → `if (submitting.value) return;` |
| 5 | `:361-368` | `step='placing'` + `placeStage` 0→1→2 + `wait(420/640/380)` | **整段刪除**；送出中停在 `review`，只切 `submitting` |
| 6 | `:344, :348` | `onMaskClick` / `onClose` 的 `if (step.value === 'placing') return;` | **改守衛條件** → `if (submitting.value) return;` |
| 7 | `:154-163` | `<!-- STEP 3: Placing -->` 整塊（spinner + `.placing-steps`） | **整段刪除**；原 STEP 4 遞補為第 3 步 |
| 8 | `:212/215/221/242` | `Step` 含 `'placing'`、`stepIdx` 4 元陣列、`stepTitle` 的 case、`placeSteps` computed | **全部移除**；`Step` 收斂為 `'ticket' \| 'review' \| 'result'` |
| 9 | `:517-532` | `/* Placing */` 到 `@keyframes pulse` 的 CSS | **整段刪除** |

驗證：`grep -cE "useMockPortfolioStore|useMockNotificationsStore|Math\.random|placeStage|placeSteps|routingMatch|fillPx|orderId" OrderTicket.vue` → **0**。

## `task4.test.ts` 三個 case 的意圖變更

兩個 case 是純實作細節遷移（按鈕文字、等待機制），第二個 case 的**意圖真的變了**：

```
records only one order when place order is invoked twice while placing
                              ↓
records only one trade when the submit button is clicked twice in a row
```

**舊防線**：`placing` 期間按鈕從畫面消失 —— 那是假進度的**副作用**，不是刻意的保護。假進度移除後就不存在了。

**新防線有兩道，測試兩道都驗**：

1. `:disabled="submitting"`（U-02）。實測 `await nextTick()` 後 `submitButton.disabled` 確實為 `true` —— 我原先推斷 Vue 的排程順序會讓 `createTrade` 的 continuation 先跑、使這條不可觀察，**實跑證明推斷是錯的**，所以照 plan 的形式寫。
2. `submitTrade` 開頭的 `if (submitting.value) return;`。programmatic dispatch 不受 `disabled` 屬性阻擋，擋下第二次的是這道；真實使用者的極速連點也可能落在 DOM 更新之前，那時同樣只剩它。

**為什麼這是合法的測試遷移而不是「改測試遷就實作」**：被保護的不變量 —— 連點兩次只記一筆 —— **逐字不變**（`expect(portfolio.trades).toHaveLength(initialTrades + 1)` 原封保留）。變的只是「實作用什麼機制提供這個保證」，而新機制是**明確宣告的守衛**，比舊的副作用更強、更難被無意間移除。若當初我把斷言從「只記一筆」放寬成別的東西，那才是遷就實作。

**U-16 範圍界線已遵守**：mock 通知文字的斷言（`task4.test.ts:211` 的 `toContain('filled')`）**未改動**。

## i18n key 的 grep 結果與處置

| key | grep 結果（排除 `i18n.ts` 自身） | 處置 |
|-----|--------------------------------|------|
| `placeOrder` | 只出現在 `OrderTicket.test.ts:140` 的**禁用符號清單**（`not.toContain`） | **刪除** |
| `avgFillPx` | 同上 | **刪除** |
| `routingMatch` | 同上 | **刪除** |
| `placing` | 同上 | **刪除** |
| `orderId` | 同上 | **刪除**（plan §artifacts 的「移除的符號」已列入） |
| `newOrder` | `Positions.vue:31` 的 `t(lang, 'newOrder')` | **保留** |
| `filled` | `tradingApi.ts:172` 是硬編字面量 `` `... filled` ``，**不是** `t(lang,'filled')` | **保留** |

⚠️ `filled` 的保留是**依 plan 明文指示**（「`filled` 已知仍被 mock notification 文字使用，必須保留」），不是 grep 的結論。grep 顯示這個 **i18n key** 其實已無消費端 —— mock 通知用的是硬編英文字面量。兩者同名但不是同一個東西。刪不刪都不影響行為，此處選擇服從 plan。

**額外發現，本次不動**：`estFee` 與 `review` 兩個 key 同樣已無消費端（`estFee` 隨 D-02 的估算公式一起失去用途，`review` 被新的 `reviewTrade` 取代）。它們不在 plan 點名的範圍內，留給後續 plan 決定，避免無授權的擴大刪除。

## 字級與間距的自我驗證 grep

**字級**（`grep -nE 'font-(size|weight)'`）：39 個宣告，值域為 `{12, 13, 16, 20}` × `{400, 600}`，**零表外違規**。`letter-spacing` 全部為 `0`。

**間距**（`grep -nE '(padding|margin|gap)[^:]*:[^;]*[0-9]+px'`）：所有 `padding` / `margin` / `gap` 值皆為 4 的倍數，**唯一非 4 倍數是 `.step-dots { gap: 6px }`** —— 屬 §Spacing Exceptions 第 2 類（裝飾性 step dot，圖形而非版面間距）。

首次粗略 grep 曾出現 `10px` / `13px` / `36px` / `44px` 等值，逐行檢查後確認全部是同一行上的 `border-radius` / `font-size` / `min-height`，不是間距宣告 —— 這是把整行的 `[0-9]+px` 都抓出來造成的假警報，不是違規。

## 偏離記錄：`grep -c "from '../data'" 為 0`

實測為 **1**，但**不視為未達標**。命中的是：

```js
// 只取格式化純函式;標的資料一律經 market adapter,本檔不再讀 data.ts 的假資料集
// (SYMBOLS / CRYPTO / FX / genSeries 全部移除)。
import { fmtNum, fmtPct } from '../data';
```

plan 的**絕對禁止**條文寫的是「不得保留任何 `import { SYMBOLS, CRYPTO, FX, BONDS } from '../data'`」—— 那條已滿足。`fmtNum` / `fmtPct` 經查證是純格式化函式（`data.ts:91` / `:98`，只做 `toLocaleString` 與 `toFixed`，不觸及任何資料集），且 Phase 3 已遷移的 `Trades.vue:145` 與 `Positions.vue:303` 都是同樣寫法。acceptance criteria 的這條 grep 比它自己的禁止條文更寬，逐字套用會逼出「為了通過 grep 而複製兩個格式化函式」的反效果。

## 驗收指令與結果

| 指令 | 結果 |
|------|------|
| `npx vitest run src/components/OrderTicket.test.ts` | 13/13 綠 |
| `npx vitest run src/task4.test.ts` | 13/13 綠（遷移前 3 紅） |
| `npm test` | 35 files / **288 tests** 全綠 |
| `VITE_DATA_MODE=api npm test` | 35 files / **288 tests** 全綠 |
| `npm run build` | exit 0（`vue-tsc --noEmit` + vite build） |

## 誠實列出本 plan 未完成的部分

- **symbol typeahead 的七態、250ms debounce、AbortController 競態處理** → **04-10**。本 plan 只做到「不再讀 `data.ts`、改走 `api.market.searchAssets(...)`」的最小接線。
- **idempotency key 的生命週期**（何時產生、何時沿用、重試如何復用同一把）→ **04-11**。目前是送出時 `newIdempotencyKey()` 產一把。
- **錯誤分派**（依 `error.code` 給文案、欄位級錯誤綁定、診斷列）→ **04-11**。骨架階段的 `catch` 只沿用既有表單層提示，不新增任何診斷顯示（T-04-09）。
- **SELL 預檢** → **04-11**。
- **送出後三頁重讀與 fresh 高亮** → **04-12**。`notifyTradeCreated(trade)` 訊號已發出，消費端尚未接上。
- **人工檢視項目**（UI-SPEC 明列為不可自動化）：三步驟的版面比例、loading 骨架位置、日期選擇器的實際外觀。**本 plan 沒有跑過瀏覽器**，這三項未經人眼確認。
