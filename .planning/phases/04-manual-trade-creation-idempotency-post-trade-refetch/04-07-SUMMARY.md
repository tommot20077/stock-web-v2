---
phase: 04-manual-trade-creation-idempotency-post-trade-refetch
plan: 07
subsystem: frontend
tags: [vue, typescript, domain-adapter, idempotency, post-trade-refetch, vitest, mock-parity]

# Dependency graph
requires:
  - phase: 02-frontend-session-api-client-foundation
    provides: apiClient 的 apiRequest / ApiClientError / CSRF 注入 / 401 單飛 refresh + 一次 replay
  - phase: 04-manual-trade-creation-idempotency-post-trade-refetch
    plan: 01
    provides: ErrorCode.TRADE_IDEMPOTENCY_KEY_REUSED(409)的字面,本 plan 測試逐字消費
provides:
  - "TradingApi domain adapter 三件組(createHttpTradingApi / createMockTradingApi / createTradingApi)"
  - "CreateTradeRequest 契約型別(七欄,逐欄同形後端 CreateTradeRequest.java:18-28)"
  - "portfolioRevision:D-10 revision counter + D-13 apiLastFill + D-11 lastCreatedTradeId 三個唯讀訊號"
  - "notifyTradeCreated():交易成功後的唯一原子入口"
affects:
  - 04-08 pageApiClients 註冊 trading client
  - 04-09 OrderTicket 產生 idempotency key 並呼叫 createTrade
  - 04-10 三個 portfolio 頁 watch portfolioRevision 重讀
  - 04-11 Trades 頁 D-11 提示與 fresh 高亮

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "request 型別定義在 adapter 檔內(非 apiTypes.ts):它是 request 而非 response DTO,生命週期不同"
    - "payload 逐欄投影而非整包轉傳 —— TypeScript 的 excess property check 只對物件字面量生效,逐欄投影是執行期唯一真正的保證"
    - "模組級 readonly ref singleton + 顯式測試 reset(沿用 pageApiClients.ts 慣例,不用 Pinia)"
    - "mock adapter 丟出與 API mode 同形的 ApiClientError,讓消費端錯誤處理只有一條路徑"

key-files:
  created:
    - "[FE] src/services/tradingApi.ts"
    - "[FE] src/services/tradingApi.test.ts"
    - "[FE] src/services/portfolioRevision.ts"
    - "[FE] src/services/portfolioRevision.test.ts"
  modified: []

key-decisions:
  - "payload 改為逐欄投影(計畫原文為 json: request):TS excess property check 只擋物件字面量,呼叫端傳入更寬的表單狀態時整包轉傳會把多餘欄位送到後端 —— 這正是 TRAD-02 要防的事"
  - "mock 的 TradeDto id 用 'mock-0':executeOrder 是 trades.unshift,新交易永遠落在 index 0,與 portfolioApi.tradeDtoFrom 的 mock-{index} 合成 id 對得上"
  - "mock createTrade 刻意不接 idempotencyKey 參數:冪等防護落在後端唯一約束(judgment §5),收下參數會暗示它在 mock mode 有作用"
  - "LastFill 欄位名鎖定 { sym, type, qty, px },與 portfolioApi.ts:36 的 mock lastFill 逐字一致"
  - "三個訊號同一模組:一次成交同時產生三件事,分模組會讓「先 bump 還是先 set lastFill」變成跨模組協調問題"

patterns-established:
  - "adapter 不 catch、不重包:ApiClientError 的 code / status / requestId / fields 原樣往上拋(T-04-09)"
  - "說明性註解不得以某個字面開頭或含有被驗收條件禁止的字面 —— 註解會被機械檢查與編譯器一併讀到(本 plan 踩到兩次)"

requirements-completed: [TRAD-01, TRAD-02, TRAD-05]

# Metrics
duration: 20min
completed: 2026-07-30
---

# Phase 04 Plan 07: tradingApi 與 portfolioRevision Summary

**建立交易送出生命週期的兩個 client 端原語:把交易送到後端(帶 D-05 必填 `Idempotency-Key`)的 `tradingApi`,以及成交後同時廣播「要重讀 / 哪一列是新的 / 新交易 id」三個訊號的 `portfolioRevision`。**

## Performance

- **Duration:** 約 20 min
- **Started:** 2026-07-30T02:20+08:00
- **Completed:** 2026-07-30T02:40+08:00
- **Tasks:** 2/2
- **Files modified:** 4(全部新增)

## Accomplishments

- **payload 的七欄契約被機械化鎖住**:sorted key 陣列的 `toEqual` 斷言(不是 `toMatchObject`)讓「多送一個欄位」立刻紅。額外加了一條「呼叫端傳更寬物件」的測試,證明逐欄投影在型別檢查放行時仍然守得住。
- **Q5.4 的免費紅利已實測驗證**(見下方專節):401 → refresh → replay 的三次 `fetch` 中,第 1 次與第 3 次的 `Idempotency-Key` **相同**,payload 也逐位元相同。
- **mock 與 API 的失敗形狀統一**:mock oversell 丟 `ApiClientError(409, 'TRADE_INSUFFICIENT_HOLDING')`,OrderTicket 的錯誤處理因此在兩個 mode 只有一條路徑。
- **三個訊號的原子性有直接斷言**:一次 `notifyTradeCreated` 之後三個值同時就位,不存在順序協調問題。
- **唯讀性在型別層與執行期雙重驗證**:三個 `@ts-expect-error` 在 `vue-tsc --noEmit` 下**被實際消耗**(若 `readonly()` 被拿掉會因 TS2578「未使用的指示詞」而紅),同時斷言 runtime 賦值不生效。
- **零新增 npm 依賴、零繞過 `apiClient`、`testSetup.ts` 一個位元組都沒動。**

## Task Commits

1. **Task 1:tradingApi.ts 三件組(HTTP + mock)**
   - `572f6a2` (test) — RED:`tradingApi.test.ts` 12 條
   - `1f84c71` (feat) — GREEN:`tradingApi.ts` 200 行
2. **Task 2:portfolioRevision.ts(D-10 + D-13 + D-11)**
   - `bf42f53` (test) — RED:`portfolioRevision.test.ts` 10 條
   - `382d7fb` (feat) — GREEN:`portfolioRevision.ts` 110 行

_兩個 task 均為 RED→GREEN 兩個 commit,皆無需 REFACTOR。全部 commit 在前端 repo `feature/phase-04-manual-trade-creation` 分支。_

## Files Created

- `[FE] src/services/tradingApi.ts`(200 行)— `CreateTradeRequest` / `TradingLiveMockData` / `TradingApi` + `createHttpTradingApi` / `createMockTradingApi` / `createTradingApi`。
- `[FE] src/services/tradingApi.test.ts`(322 行,12 條)— 5 個 describe:payload 契約(4)、Idempotency-Key(2)、錯誤上拋(2)、mock adapter(3)、factory(1)。
- `[FE] src/services/portfolioRevision.ts`(110 行)— `LastFill` + 三個唯讀 ref + `notifyTradeCreated` / `bumpPortfolioRevision` / `clearLastCreatedTrade` / `resetPortfolioRevisionForTests`。
- `[FE] src/services/portfolioRevision.test.ts`(171 行,10 條)— 6 個 describe。

## 1. Q5.4「401 replay 沿用同一 key」的實際驗證結果

**這是本 plan 最重要的安全驗證,結論:成立,且不需要任何額外程式碼。**

機制在 `apiClient.ts:319`:replay 用的是**同一份 `options`** 重新呼叫 `prepareRequestInit(options)`,而 `Idempotency-Key` 就在 `options.headers` 裡,所以自動沿用。

測試(`reuses the SAME key on the 401 refresh replay`)的做法是攔截三次 `fetch`,只挑 URL 結尾為 `/trades` 的兩次,斷言:

```
tradeCallIndexes            → 長度 2(第 0 次與第 2 次;中間第 1 次是 /auth/refresh)
firstKey                    → 'key-1'
replayKey                   → 與 firstKey 相同
bodyAt(replay)              → toEqual(bodyAt(first))   ← payload 也逐位元相同
```

**為什麼 payload 相同這條也必須斷言:** 後端 D-07 的規則是「同 key 不同 payload → 409 `TRADE_IDEMPOTENCY_KEY_REUSED`」。若 replay 沿用了 key 卻送出不同 payload(例如 `executedAt` 由前端在送出時才 `now()` 產生),使用者會在 session 過期後莫名吃到 409。D-03 讓 `executedAt` 由呼叫端事先決定並放進 request 物件,這條才穩定 —— **D-03 與 D-07 是互相成立的,不要單獨改一邊。**

**安全意義(T-04-01):** 401 發生在回應路徑上時,refresh 前那一次交易**可能已經成功寫進帳本**。replay 若換一把新 key,後端會把它當成全新交易再建一筆,而 `transactions` 是 append-only(V8 trigger 禁 UPDATE/DELETE)—— 建錯改不回來。

## 2. mock 的 `sector` 反查解法

`mockPortfolio.executeOrder` 的參數需要 `sector`(與 `name`),但 `CreateTradeRequest` 刻意**不含**這兩者 —— 它必須與後端 `CreateTradeRequest.java` 逐欄同形(judgment §4)。

解法:**髒活留在 mock 裡,介面契約保持乾淨。**

```
MOCK_UNIVERSE = [...SYMBOLS, ...CRYPTO, ...FX]      ← BONDS 不納入(Bond 沒有 price,不可交易)
mockSymbolOf(symbol)   → 用 symbol 反查 data.ts
mockSectorOf(asset)    → asset?.sector ?? asset?.cat.toUpperCase() ?? 'OTHER'
name                   → asset?.name ?? request.symbol
```

`?? cat.toUpperCase()` 的 fallback 沿用 `OrderTicket.vue:384` 的既有寫法,因為 `data.ts` 的 CRYPTO / FX 條目**沒有** `sector` 欄位(只有 SYMBOLS 有)。測試以 `AAPL` 斷言 `sector: 'Tech'` 鎖住這條反查。

**這條的一般化規則:** adapter 介面的形狀由**後端契約**決定,不由 mock store 的實作需求決定。mock 需要的額外資料自己想辦法補齊。

## 3. 為什麼 `LastFill` 用 `{ sym, type, qty, px }` 而不是 `{ symbol, quantity, price }`

**這不是命名品味,是最小改動路徑。**

`portfolioApi.ts:36` 的 mock `PortfolioLiveMockData.lastFill` 形狀就是 `{ sym, type, qty, px }`(來自 `mockPortfolio.ts:28` 的 store 狀態)。Positions / Trades 兩頁的 fresh 高亮綁定表達式已經照這個形狀寫好了。

新的 `LastFill` 逐字一致,兩頁只需要加一行來源切換:

```ts
const effectiveLastFill = computed(() => live ? live.lastFill : apiLastFill.value)
```

**fresh class 的綁定表達式完全不用改,只是來源換了。** 若改用後端 DTO 的欄位名(`symbol`/`quantity`/`price`),兩頁的每一處綁定都要改寫,而那些是 Phase 3 已驗收的程式碼 —— 動它們等於把已驗收的東西重新拉進未驗收狀態,沒有任何收益。

`tradingApi.ts` 的 `TradingLiveMockData.lastFill` 也是同一形狀,**三處同形**。

## 4. `resetPortfolioRevisionForTests` 不得加進 `testSetup.ts`(給後續 plan 的 executor)

**規則:`resetPortfolioRevisionForTests()` 必須在「各測試檔自己」的 `afterEach` 呼叫,絕對不得加進 `testSetup.ts`。**

**理由(`testSetup.ts:10-12` 明文):** 該檔刻意「不 import 任何 service module」。若在 setup 檔 import(例如為了呼叫 reset 而 import `portfolioRevision`),那次 import 會**搶在各測試檔的 `vi.mock` 生效前**就綁定真實實作,導致 mock 失效(前例:`Backtest.test.ts` D6)。

這條規則已寫進 `portfolioRevision.ts` 的檔頭 javadoc,後續 plan 的 executor 打開檔案就看得到。

**額外加了一條機械化防線:** `portfolioRevision.test.ts` 用 Vite 的 `?raw` 讀 `testSetup.ts` 原始碼,斷言它**不含** `portfolioRevision` 與 `resetPortfolioRevisionForTests` 兩個字串。若有人日後把 reset 加進 setup,這條測試會直接紅。

**為什麼需要 reset:** `vite.config.ts:26-27` 是 `pool: 'threads'` + `fileParallelism: false`,同一測試檔內的多個測試**共用**模組級狀態。`portfolioRevision.test.ts` 用一對相鄰測試(第一個 bump、第二個斷言看到 0)直接證明 reset 有效(Pitfall 13)。

## 驗收證據(我實際執行並看到的輸出)

### Task 1 RED — `npx vitest run src/services/tradingApi.test.ts`

```
 ❯ src/services/tradingApi.test.ts (0 test)

 FAIL  src/services/tradingApi.test.ts [ src/services/tradingApi.test.ts ]
Error: Failed to resolve import "./tradingApi" from "src/services/tradingApi.test.ts". Does the file exist?
  Plugin: vite:import-analysis
  File: D:/end/workspace/vue/stock-v2/vue-app/src/services/tradingApi.test.ts:8:7

 Test Files  1 failed (1)
      Tests  no tests
EXIT=1
```

失敗原因確認為「模組不存在」,不是語法錯 —— 這是 adapter 類新檔最便宜的合法 RED。

### Task 1 GREEN — 同一指令

```
 Test Files  1 passed (1)
      Tests  12 passed (12)
   Duration  3.20s
EXIT=0
```

### Task 2 RED — `npx vitest run src/services/portfolioRevision.test.ts`

```
 ❯ src/services/portfolioRevision.test.ts (0 test)

 FAIL  src/services/portfolioRevision.test.ts [ src/services/portfolioRevision.test.ts ]
Error: Failed to resolve import "./portfolioRevision" from "src/services/portfolioRevision.test.ts". Does the file exist?
  File: D:/end/workspace/vue/stock-v2/vue-app/src/services/portfolioRevision.test.ts:10:7

 Test Files  1 failed (1)
      Tests  no tests
EXIT=1
```

### Task 2 GREEN — 同一指令

```
 Test Files  1 passed (1)
      Tests  10 passed (10)
   Duration  3.31s
EXIT=0
```

### 全套回歸 — `npm test`

```
 Test Files  34 passed (34)
      Tests  267 passed (267)
   Duration  120.82s
EXIT=0
```

### API mode 全套回歸 — `VITE_DATA_MODE=api npm test`

```
 Test Files  34 passed (34)
      Tests  267 passed (267)
   Duration  110.07s
EXIT=0
```

(04-06 收尾時是 32 files / 245 tests;本 plan +2 files / +22 tests = 12 + 10,數字對得上。)

### 型別檢查 + build — `npm run build`(`vue-tsc --noEmit && vite build`)

```
vite v8.0.13 building client environment for production...
✓ 133 modules transformed.
dist/assets/index-_8zIP8u2.js   358.28 kB │ gzip: 120.13 kB
✓ built in 1.85s
EXIT=0
```

`vue-tsc --noEmit` 無輸出即通過。**特別注意:三個 `@ts-expect-error` 沒有觸發 TS2578,代表它們確實各自消耗了一個真實的型別錯誤 —— 這就是 `readonly()` 生效的證明。**

### 字面檢查(驗收條件)

```
$ grep -nE "fetch\(|credentials|X-XSRF-TOKEN|Math\.random" src/services/tradingApi.ts
rc=1   ← 零命中

$ grep -c "Idempotency-Key" src/services/tradingApi.ts        → 2
$ grep -c "method: 'POST'"  src/services/tradingApi.ts        → 1
$ grep -c "readonly("       src/services/portfolioRevision.ts → 3
$ grep -nE "defineStore|pinia" src/services/portfolioRevision.ts
rc=1   ← 零命中(僅散文提到「Pinia」說明為何不用它)
```

### `testSetup.ts` 未被修改

```
$ git diff --name-only a03e030 HEAD
vue-app/src/services/apiTypes.ts        ← 04-06
vue-app/src/services/marketApi.test.ts  ← 04-06
vue-app/src/services/marketApi.ts       ← 04-06
vue-app/src/services/portfolioRevision.test.ts
vue-app/src/services/portfolioRevision.ts
vue-app/src/services/tradingApi.test.ts
vue-app/src/services/tradingApi.ts
```

`src/testSetup.ts` 不在清單內,一個位元組都沒被改動。

### 提交後工作樹乾淨

```
$ git status --porcelain
（空）
```

## must_haves 對帳(機械驗證)

| 項目 | 要求 | 實測 | 狀態 |
|------|------|------|------|
| `tradingApi.ts` exports | TradingApi / CreateTradeRequest / createTradingApi / createHttpTradingApi / createMockTradingApi | 五者皆在(另有 `TradingLiveMockData`) | PASS |
| `tradingApi.ts` contains | `'Idempotency-Key'` | 2 處命中 | PASS |
| key_link → `POST /api/v1/trades` | `apiRequest` + `headers['Idempotency-Key']` | `${basePath}/trades` + `method: 'POST'` 各 1 處 | PASS |
| `CreateTradeRequest` 欄位數 | 恰 7,不含 ordType/tif/orderId/cashAfter/slippage/sector | 7 欄,禁用欄位零命中 | PASS |
| `tradingApi.test.ts` 含 sorted key `toEqual` | 非 `toMatchObject` | `Object.keys(bodyAt(0)).sort()).toEqual(CONTRACT_FIELDS)` 2 處 | PASS |
| `tradingApi.test.ts` 測試數 | ≥ 10 | 12(0 skip) | PASS |
| `portfolioRevision.ts` exports | 8 個(含 LastFill) | 8 個全在 | PASS |
| `LastFill` 欄位名 | sym / type / qty / px | 逐字一致 | PASS |
| `portfolioRevision.ts` 含 `readonly(` | 3 次 | 3 | PASS |
| `portfolioRevision.test.ts` 測試數 | ≥ 6 | 10(0 skip) | PASS |
| key_link → 三個 portfolio 頁的 watch | `export const portfolioRevision` | 1 處命中 | PASS |
| `testSetup.ts` 未被修改 | 不在 `git diff --name-only` | 不在 | PASS |

## Success Criteria 對帳

| 條件 | 狀態 | 證據 |
|------|------|------|
| payload 恰為七欄,多一個就紅 | PASS | sorted key `toEqual` × 2 條(含「更寬呼叫端物件」那條) |
| `Idempotency-Key` 必帶,401 replay 沿用同一把 | PASS | `POSTs to /trades with the caller-supplied Idempotency-Key header` + `reuses the SAME key on the 401 refresh replay` 皆綠 |
| mock 與 API 的成功與失敗形狀一致 | PASS | mock 回 TradeDto 九欄;oversell 丟 `ApiClientError(409, TRADE_INSUFFICIENT_HOLDING)` |
| 一次 `notifyTradeCreated` 同時產生三個訊號 | PASS | `produces all three signals in one call` 綠 |
| 零新增 npm 依賴 | PASS | `package.json` 未異動(不在 `git diff --name-only`) |
| 零繞過 `apiClient` | PASS | `fetch(` / `credentials` / `X-XSRF-TOKEN` 字面零命中 |
| `testSetup.ts` 未被污染 | PASS | 不在 diff 清單;另有 `?raw` 測試防線 |

## Threat Model 對帳

| Threat ID | Disposition | 落實情形 |
|-----------|-------------|----------|
| T-04-06 | accept(既有控制已覆蓋) | 未另造轉接層;CSRF 由 `apiClient.ts:220-223` 統一注入。字面檢查零命中即為機械證據 |
| T-04-01 | mitigate | `reuses the SAME key on the 401 refresh replay` 綠;另加斷言 replay payload 逐位元相同 |
| T-04-10 | mitigate | adapter **不自產 key**,`idempotencyKey` 是必填參數且**無預設值**;檔內無亂數產生器 |
| T-04-09 | mitigate | adapter 不 catch、不重包;409 / 400 兩條測試斷言 `code` / `status` / `requestId` / `fields` 原樣抵達 |
| —(payload 夾帶未支援欄位) | mitigate | sorted key `toEqual` + **逐欄投影**(比計畫原文更強,見 Deviations #1) |
| —(mock 與 API 錯誤形狀不一致) | mitigate | mock oversell 丟 `ApiClientError`,兩 mode 同形 |
| T-04-SC | accept | 零新增依賴;未裝 `uuid` / `date-fns` / `dayjs` |

**新增威脅面掃描:** 本 plan 未新增網路端點(消費的 `POST /api/v1/trades` 已在 threat register 內)、未新增認證路徑、未新增檔案存取。`portfolioRevision.ts` 是純記憶體內狀態,不跨信任邊界。**無新旗標。**

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - 補強關鍵正確性] payload 由「整包轉傳」改為「逐欄投影」**

- **Found during:** Task 1 實作
- **計畫原文:** `apiRequest<TradeDto>(..., { method: 'POST', headers, json: request })`
- **Issue:** TypeScript 的 excess property check **只對物件字面量生效**。呼叫端(04-09 的 OrderTicket)若把整包表單狀態當 `CreateTradeRequest` 傳進來 —— 只要結構相容就通過型別檢查 —— `json: request` 會把 `ordType` / `tif` / `cashAfter` 等多餘欄位原封不動送到後端。這正是 TRAD-02 與 threat register「payload 夾帶未支援欄位」要防的事,而型別系統在此處**擋不住**。
- **Fix:** 改為逐欄投影七個欄位;並新增一條測試(`projects the payload field-by-field so a wider caller object cannot smuggle extras`)用 `as CreateTradeRequest` 模擬更寬的呼叫端物件,斷言 payload 仍恰為七欄。
- **Files modified:** `[FE] src/services/tradingApi.ts`、`[FE] src/services/tradingApi.test.ts`
- **Verification:** 該條測試綠;若改回 `json: request` 它會立刻紅(多出 4 個 key)。
- **Committed in:** `1f84c71`(實作)/ `572f6a2`(測試,RED 階段就寫進去了)

**2. [Rule 3 - 驗收條件字面衝突] `tradingApi.ts` 的說明註解含被禁止的字面**

- **Found during:** Task 1 驗收
- **Issue:** 驗收條件要求該檔「不含 `fetch(`、`credentials`、`X-XSRF-TOKEN`、`Math.random`」,但初版註解在說明「為何不需要 transport 層擴充」與「為何不用亂數產生 id」時**正好出現這些關鍵字**,會讓字面檢查誤判。與 04-01 Deviation #3 的 `CONCURRENTLY` 是同一類問題。
- **Fix:** 改寫兩段註解為「CSRF header 注入、cookie 憑證附帶」與「絕不用亂數產生器合成 id 或成交價」,**保留完整取捨理由**(含 `OrderTicket.vue:373-375` 的交叉引用)但不出現被禁字面。並在註解中補一句說明「本檔刻意不出現任何 transport 層字面,好讓『未另造轉接層』可被字面檢查機械驗證」。
- **Verification:** `grep -nE "fetch\(|credentials|X-XSRF-TOKEN|Math\.random"` → 零命中;改寫後重跑 12 條測試仍全綠。
- **Committed in:** `1f84c71`

**3. [Rule 1 - Bug] 測試中的說明文字被 TypeScript 誤判為 `@ts-expect-error` 指示詞**

- **Found during:** Task 2 的 `npm run build`
- **Issue:** `npm run build` 報 `TS2578: Unused '@ts-expect-error' directive.`(`portfolioRevision.test.ts:98`)。原因是我在**說明**這三個指示詞的用途時,那行註解**以該指示詞字面開頭** —— TypeScript 不區分「散文提及」與「真正的指示詞」,把說明本身當成第 4 個指示詞,而它的下一行是另一行註解(沒有型別錯誤)→ 判定為未使用。
- **Fix:** 把說明改寫為「會驗下面三個**預期錯誤指示詞**」,並補一句警告:「說明文字不可以該指示詞開頭,否則 TypeScript 會把說明本身當成一個指示詞」。
- **Verification:** `npm run build` 由 EXIT=2 轉為 EXIT=0;三個真正的指示詞仍被消耗(無 TS2578),證明 `readonly()` 的型別保護真實有效。
- **Committed in:** `382d7fb`

---

**Total deviations:** 3 auto-fixed(1 × Rule 2 補強 / 1 × Rule 3 字面衝突 / 1 × Rule 1 bug)
**Impact on plan:** 無範圍蔓延。三項都是計畫撰寫時無法預見的執行期事實(TS 的 excess property check 邊界、字面檢查與註解的衝突、TS 對註解的解析規則),實質產出與 `must_haves` 逐條一致。唯一比計畫**更強**的是 payload 逐欄投影 —— 那是同方向的補強,不是偏離。

**Deviation #2 與 #3 的共同教訓(已列入 patterns-established):** 註解不是自由文本 —— 它會被機械化的字面檢查與 TypeScript 編譯器一併讀到。撰寫「為何不用 X」的說明時,必須避免讓 X 的字面出現。

## Issues Encountered

- **無環境層面問題。** 本次執行未遇到 session 限額、工具失敗或依賴問題。全套測試在 Windows 上約 110–120 秒(`environment` 佔 76–87 秒,是 jsdom 啟動成本,非本 plan 引入)。

## Known Stubs

**無。** `createHttpTradingApi` / `createMockTradingApi` / `portfolioRevision` 皆為完整實作,無 TODO、無 FIXME、無佔位、無硬編碼空值。

**但有兩個「刻意的設計邊界」需要後續 plan 知道,它們不是待補實作:**

1. **`createMockTradingApi().createTrade` 刻意不接 `idempotencyKey` 參數。** 冪等防護落在後端唯一約束(judgment §5),mock 沒有帳本也沒有並發。若日後有人想在 mock 模擬冪等行為,必須先做設計決策,而不是直接把參數加回來。
2. **mock 回傳的 `TradeDto.id` 固定為 `'mock-0'`。** 這是沿用 `portfolioApi.tradeDtoFrom` 的 `mock-{index}` 合成 id 慣例(store 是 `unshift`,新交易永遠在 index 0)。它在「同一個 session 建立第二筆交易」後不再指向原本那筆 —— 這是 mock 索引式 id 的既有性質(Phase 3 就存在),不是本 plan 引入的缺陷。API mode 用的是後端真 UUID,不受影響。

## User Setup Required

None —— 純前端、零新增依賴、無外部服務。

## Next Phase Readiness

**Ready for 04-08 / 04-09 / 04-10 / 04-11:**

- `TradingApi` 三件組已就位,04-08 可直接在 `pageApiClients.ts` 的 `RuntimeApiClients` 註冊 `trading`(記得同時把 `trading` 加進 `api-adapter-wiring.test.ts` 的 hoisted `mockFactoryCalls`,並補一條「api mode 下 mock factory 未被呼叫」的斷言)。
- `portfolioRevision` 三個訊號已就位,04-10 的三頁 `watch` 可直接消費。

**移交注意事項(依重要性排序):**

1. **key 由呼叫端產生,adapter 不自產。** 04-09 的 OrderTicket 必須用 `crypto.randomUUID()`(內建 Web Crypto,CSPRNG;**不得**裝 `uuid` 套件),並遵守 D-14 的生命週期:按下送出時產生 → 該次嘗試的重試沿用 → 使用者改過任何欄位後換新 key。**這條規則屬 UI 層,adapter 幫不了忙。**
2. **`executedAt` 必須在建立 request 物件時就決定,不可在送出瞬間 `now()`。** 否則同一次嘗試的重試會送出不同 payload,吃到 409 `TRADE_IDEMPOTENCY_KEY_REUSED`(D-03 與 D-07 綁在一起)。日期用內建 `Date.prototype.toISOString()` 或既有 `Trades.vue:218-226` 的 `toLocalIso` helper,**不得**裝 `date-fns` / `dayjs`。
3. **`resetPortfolioRevisionForTests()` 必須在各測試檔自己的 `afterEach` 呼叫,不得加進 `testSetup.ts`。** 已有 `?raw` 測試防線會擋住違規。
4. **兩頁的 fresh 高亮建議寫成 `computed(() => live ? live.lastFill : apiLastFill.value)`。** `LastFill` 與 mock 逐字同形就是為了這個 —— 綁定表達式不用改。
5. **本 plan 未動 `pageApiClients.ts`、未動 `OrderTicket.vue`、未動任何 page。** `OrderTicket.vue:377-386` 與 `:393-399` 的兩段程式碼雖然已經**複製**進 mock adapter,但原處**尚未移除** —— 那屬 04-09 的重建工作。
6. **前端 repo 分支 `feature/phase-04-manual-trade-creation` 仍未 push**,目前領先 `origin/develop @ a03e030` 共 **7 個 commit**(04-06 三個 + 04-07 四個)。
7. **前端 repo 不在 GSD 的 worktree 隔離範圍內**(`sub_repos` 為空),所有前端 plan 共用同一個工作樹。目前每個 wave 最多一個 FE plan 所以安全;**若日後有人把兩個 FE plan 排進同一 wave,會直接互相踩踏**(此警告由 04-06 提出,本 plan 再次確認仍然成立)。

---
*Phase: 04-manual-trade-creation-idempotency-post-trade-refetch*
*Completed: 2026-07-30*

## Self-Check: PASSED

- **4 個檔案**皆已於前端 repo 驗證存在(`wc -l` 全部有輸出:200 / 110 / 322 / 171 行)。
- **4 個 commit hash** 皆已於 `git log` 驗證存在:`572f6a2`(test)、`1f84c71`(feat)、`bf42f53`(test)、`382d7fb`(feat)。
- **所有「驗收證據」段落的輸出均為本次實際執行所得**,包含兩次 RED、兩次 GREEN、兩次全套回歸、一次 build、以及全部字面檢查。**本 plan 沒有任何「未親眼看到」的宣稱**(這是 04-06 SUMMARY 明確記錄的瑕疵,本次已避免)。
- **測試數已核對:** `grep -cE "^\s*it\("` → 12 與 10;`it.skip|it.todo` → 0 與 0;與 `12 passed` / `10 passed` 一致。全套 267 = 245(04-06 收尾)+ 22。
- **TDD 順序已核對:** `git log --oneline` 顯示 `test(...)` 在 `feat(...)` 之前,兩個 task 皆然,且兩次 RED 的失敗輸出均已貼出。
