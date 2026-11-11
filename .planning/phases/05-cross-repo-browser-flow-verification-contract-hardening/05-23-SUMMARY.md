---
phase: 05-cross-repo-browser-flow-verification-contract-hardening
plan: 23
subsystem: frontend-api-contract
tags: [big.js, decimal, websocket, live-quotes, quote-stream, wire-string, vitest]

requires:
  - phase: 05-22
    provides: "useLiveQuotes.overlayTick 以 Decimal 疊加;唯一交接點 `parseDecimal(numberToWire(tick.price))`"
  - phase: 05-16
    provides: "decimal.ts(parseDecimal / toWire / numberToWire / isDecimal);Wave 2 前端共同規約"
  - phase: 05-14
    provides: "PriceTickEvent 刻意未改;WsBroadcastConsumer.buildTickData 本來就以 toPlainString() 送字串價格"
provides:
  - "型別:LiveTick.price: Decimal"
  - "quoteStream.parseTickPrice / parseTickData:WS tick 字串價格 → Decimal 的唯一轉換點;只接受非空字串,parseDecimal 失敗、JSON number、null、缺欄位一律丟棄 tick"
  - "createMockQuoteStream.step():random walk 維持 mock-only number,產出 tick 時 numberToWire → parseDecimal"
  - "testing/fakeQuoteStream.pushTick(symbol, price: number):簽章不變,內部轉 Decimal;Watchlist / Overview 測試呼叫端不需改"
  - "useLiveQuotes.overlayTick 直接用 tick.price;05-22 交接點與 numberToWire import 已刪除"
  - "前端 Wave 2 交接點全部歸零:src/pages、src/components、src/composables 非測試、非註解行引用 numberToWire = 0"
affects: [05-24, 05-25]

actuals:
  tokens: 4100
  tasks: 2
  commits: 2
commits: 2
plan_head_before: "frontend 1bf80c59b0aa1daa055a01b3183c21b9b1428236"
plan_head_after: "frontend 3b4345a8e65e586b36cfb21251697dac9ed05427"

tech-stack:
  added: []
  patterns:
    - "WS 與 REST 同一規則:wire 金額只接受字串,邊界以 parseDecimal 轉換;WS 側解析失敗不丟例外而是丟棄該 tick(串流不中斷),REST 側則丟 TypeError"
    - "測試替身的價格參數可維持 number(便利),在替身內部轉 Decimal;需要超過 number 精度時用字串(useLiveQuotes.test 的 fakeStream.pushTick 接受 number | string)"

key-files:
  created: []
  modified:
    - /home/user/stock-web-v2-front-end/vue-app/src/services/quoteStream.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/quoteStream.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/composables/useLiveQuotes.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/composables/useLiveQuotes.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/testing/fakeQuoteStream.ts

key-decisions:
  - "已核對後端(依 AGENTS.md,後端為準):stock-module-market-data WsBroadcastConsumer.buildTickData 送 `price: event.price().toPlainString()`(字串),Redis latest cache 用同一個 Map。plan 的「只接受字串」假設成立,沒有偏離"
  - "JSON number 價格丟棄,不容忍:契約是字串,而 number 在 JSON.parse 時就可能已經失真,收下會把錯誤數值帶進疊加計算"
  - "前後有空白的字串(' 187.45')也丟棄:big.js 不會 trim,後端 toPlainString 也不會產生空白"
  - "負數或 0 價格不額外擋(與 Phase 5 前 Number.isFinite 的行為相同);只把 number 轉換改成 Decimal 轉換,不在本 plan 新增業務驗證"
  - "useLiveQuotes.test 的本地 fakeStream.pushTick 改收 `number | string`:既有 number 呼叫不需改,大數精度案例走字串路徑(與後端 TICK 同形)"

patterns-established:
  - "LiveTick 構造點共 4 處(parseTickData、mock step、fakeQuoteStream、useLiveQuotes.test fakeStream),全部產出 Decimal"

requirements-completed: [VER-02]

coverage:
  - id: T1
    description: "parseTickData:'187.45' → Decimal(toWire '187.45');'0.00000001'、'123456789012.12345679'、'9007199254740993.01' 逐字;'187.45000000' eq 187.45;JSON number 187.45、''、'abc'、' 187.45'、null、缺欄位 → 丟棄;既有 TICK 節流 / 丟棄 / SUB_ACK 測試改以 toWire 斷言"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/services/quoteStream.test.ts#TICK 價格字串 → Decimal(05-23 / D-08)"
        status: pass
    human_judgment: false
  - id: T2
    description: "mock stream:連續三步(random 1 / 0.123 / 0)的 LiveTick.price 為 Decimal,toWire 逐字等於 numberToWire(walk);既有訂閱 / 計時 / 非可交易代號測試綠"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/services/quoteStream.test.ts#createQuoteStream(mock) — 產出的 LiveTick.price 為 Decimal"
        status: pass
    human_judgment: false
  - id: T3
    description: "useLiveQuotes:row 123456789012.12345678 / change 0.00000001 + 字串 tick '123456789012.12345679' → change '0.00000002'、changePercent '0'、quoteSource ws;附 number 路徑失真的對照斷言;05-22 的 Decimal 疊加與 quoteSource / lastUpdatedAt 測試全綠"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/composables/useLiveQuotes.test.ts#WS 字串 tick 超過 number 精度(05-23)"
        status: pass
    human_judgment: false
  - id: T4
    description: "WS 建立邊界維持在 quoteStream.ts;layout.spec 不受影響"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/wsBoundary.test.ts"
        status: pass
      - kind: automated_ui
        ref: "npx playwright test e2e/tests/layout.spec.ts --trace off(101 passed)"
        status: pass
    human_judgment: false

duration: 9min
completed: 2026-11-11
status: complete
---

# Phase 5 Plan 23: WS 即時報價 LiveTick Decimal 化 Summary

**`LiveTick.price` 改為 Decimal。`parseTickData` 只接受後端 `WsBroadcastConsumer` 送的 `toPlainString()` 字串,用 `parseDecimal` 解析;number、空字串和無效字串的 tick 都會丟棄。mock / fake quote stream 在產出 tick 的地方轉成 Decimal。`useLiveQuotes.overlayTick` 直接使用 Decimal tick,05-22 留下的交接點已刪除,前端 Wave 2 的交接點至此全部歸零**

## Performance

- **Duration:** 約 9 min
- **Started:** 2026-11-11T05:39:58Z
- **Completed:** 2026-11-11T05:48:19Z
- **Tasks:** 2/2
- **Files modified:** 5(與 plan frontmatter 相同,沒有超出)

## Accomplishments

- `quoteStream.ts`:`LiveTick.price: Decimal`。新增 `parseTickPrice`(非空字串 → `parseDecimal`,失敗回 `null`),`parseTickData` 改用它,原本的 `Number(record.price)` / `Number.isFinite` 已刪除,JSDoc 註明這是唯一轉換點。`createMockQuoteStream.step()` 的漫步仍用 number,寫入 `ticks` 時轉換。
- `useLiveQuotes.ts`:`const price = tick.price;`,刪除 `numberToWire` import 和交接註解。
- `testing/fakeQuoteStream.ts`:`pushTick` 簽章不變,內部 `parseDecimal(numberToWire(price))`,JSDoc 已註明。
- 測試:quoteStream 5 個字串解析案例 + 1 個 mock 逐字案例,useLiveQuotes 1 個大數精度案例;既有斷言從 number 改為 `toWire` 比對字面。

## Task Commits(前端 repo `/home/user/stock-web-v2-front-end`,分支 `claude/epic-curie-lj8alo`)

1. **Task 1(tracer)**:`adf3359` `feat(05-23): WS tick 價格改為 Decimal 並移除疊加交接點`
2. **Task 2**:`3b4345a` `test(05-23): mock quote stream 產出 Decimal tick,補疊加精度案例`

完整 SHA:
- `adf3359b5f83003688784bc5e9a508218c92d6a2`
- `3b4345a8e65e586b36cfb21251697dac9ed05427`

plan 開始前 HEAD 為 `1bf80c59b0aa1daa055a01b3183c21b9b1428236`;`git rev-list --count 1bf80c5..HEAD` = 2。這兩個 commit 只存在於前端 repo 的歷史。

## Gate

- 最終 commit(3b4345a):`npm run lint` 0 errors / 38 warnings(與 baseline 相同);`npm test` 66 files / 1497 tests passed;`VITE_DATA_MODE=api npm test` 66 files / 1497 tests passed;`npm run build`(vue-tsc + vite)通過;`npx playwright test e2e/tests/layout.spec.ts --trace off` 101 passed
- adf3359 本身:lint 0 errors / 38 warnings、兩種 mode 各 1495 tests passed、build 通過
- Tracer 閘門:Task 1 的 `<verify>`(quoteStream.test + useLiveQuotes.test + wsBoundary.test,57 tests)通過後才進 Task 2
- 驗收 grep:`LiveTick` 區塊 `price: number` = 0;useLiveQuotes.ts 非註解行 `numberToWire` = 0;quoteStream.ts `Number(record.price)` = 0;useLiveQuotes.test.ts `"123456789012.12345679"` = 1
- 額外:`src/pages` / `src/components` / `src/composables` 非測試、非註解行引用 `numberToWire` = 0
- 禁止事項(judgment):API 路徑上的 tick 價格沒有經過 JS number。quoteStream.ts 剩下的 number 運算只在 mock random walk(mock-only 資料,符合 D-08 / 共同規約)

## Decisions Made

見 frontmatter `key-decisions`。後端實際送的格式已核對(`WsBroadcastConsumer.java` buildTickData:`data.put("price", event.price().toPlainString())`),與 plan 假設相同。

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Task 1 一併涵蓋 mock stream 的轉換與 useLiveQuotes.test 的 fakeStream**
- **Found during:** Task 1
- **Issue:** `LiveTick.price` 改成 Decimal 後,`createMockQuoteStream.step()`(plan 放在 Task 2)和 `useLiveQuotes.test.ts` 本地 `fakeStream().pushTick`(plan 放在 Task 2 的檔案清單)都在構造 `LiveTick`,不一起改的話 Task 1 的 commit 無法通過 vue-tsc。
- **Fix:** adf3359 包含 mock `step()` 的 `numberToWire → parseDecimal` 轉換、`fakeStream.pushTick` 改收 `number | string`,以及 quoteStream.test 既有 mock 斷言改成可通過的最小寫法(`Number(toWire(...))` 搭配 `toBeCloseTo`)。3b4345a 只新增 mock 逐字案例與大數精度案例。兩個 commit 都能 build,兩種 mode 的測試都綠。做法與 05-19 / 05-21 / 05-22 相同。
- **Committed in:** `adf3359`、`3b4345a`

### 計畫文字與既有程式不同

- 介面中的行號(`quoteStream.ts:105-115` 等)與實際位置略有差距,依名稱定位,不影響內容。
- 後端格式與 plan 假設相同,不構成偏離。

---

**Total deviations:** 1 個自動修正(Rule 3 commit 邊界)

## Deferred / Out of Scope

- `volume` 仍不帶進 `LiveTick`(P-W5,既有設計),未變更。
- 真後端 E2E 與源碼守門依約由 05-24 處理。

## Threat Flags

沒有新增安全面。T-05-31 已緩解:WS tick 只接受字串價格,`parseDecimal` 失敗、JSON number、null、缺欄位一律丟棄(quoteStream.test 有測試);疊加以 Decimal 計算(useLiveQuotes.test 大數案例)。

## Known Stubs

無。

## Next Phase Readiness(交接)

- 前端 Wave 2 交接點已全部收回:`src/pages`、`src/components`、`src/composables` 非測試、非註解行引用 `numberToWire` 為 0。05-24 的源碼守門可以把這三個目錄一起納入。非測試檔中引用 `numberToWire` 的只剩:`decimal.ts`(定義)、services 內的 mock adapter(marketApi / watchlistApi / portfolioApi / tradingApi / holdingEventApi / quoteStream mock)、`testing/fakeQuoteStream.ts`,以及 `types.ts` 的一行註解。
- `LiveTick` 構造點共 4 處:`quoteStream.parseTickData`、`createMockQuoteStream.step`、`testing/fakeQuoteStream.pushTick`、`useLiveQuotes.test.ts` 的本地 `fakeStream.pushTick`。
- big.js 是 strict mode,`eq` / `cmp` 傳 number 會丟 `TypeError: [big.js] Invalid value`;測試比較請用字串或 `toWire`。
- WS 側解析失敗是丟棄 tick(串流不中斷),REST 側(`toAsset` 等)則丟 TypeError。守門測試不要把兩者混為一談。

## Self-Check: PASSED

- FOUND:frontmatter key-files 的 5 個檔案都已修改(`git diff --name-only 1bf80c5..HEAD`:5 files)
- FOUND:`adf3359`、`3b4345a`(前端 repo,都是 HEAD 的祖先;`git rev-list --count 1bf80c5..HEAD` = 2)
- 規劃 repo 只 commit 本 SUMMARY;STATE.md / ROADMAP.md 未動;沒有 push
