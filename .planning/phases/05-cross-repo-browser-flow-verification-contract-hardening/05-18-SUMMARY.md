---
phase: 05-cross-repo-browser-flow-verification-contract-hardening
plan: 18
subsystem: frontend-api-contract
tags: [big.js, decimal, holdings, wire-string, vitest, playwright]

requires:
  - phase: 05-17
    provides: "DecimalFields<T, K>;portfolio summary domain;Positions.holdingWeight 回 Decimal | null 並以 cmp 排序;holdingWeight 的 numberToWire 交接點"
  - phase: 05-16
    provides: "decimal.ts(parseDecimal / parseNullableDecimal / toWire / numberToWire / ZERO / ROUND_HALF_UP);moneyFormat 接受 Decimal;型別閉包交接點規約"
  - phase: 05-13
    provides: "後端 HoldingDto 14 個 BigDecimal 欄位(含 dayPnlBasis)以 @DecimalString 輸出 JSON 字串"
provides:
  - "apiTypes:HoldingDto 13 個金額欄改 string(nullable 為 string | null),補 dayPnlBasis: string | null;domain Holding = DecimalFields<HoldingDto, HoldingDecimalKey>(14 欄)"
  - "portfolioApi:export toHolding(http 與 mock 共用);listHoldings 回 Holding[];mock holdingFrom 以 numberToWire 先組 wire"
  - "Positions:持倉表色彩 / roi 百分比 / 今日損益 class / Top movers 排序與色彩 / 表頭排序 / 權重全部以 Decimal 計算;05-17 的 holdingWeight 交接點已收回(Positions 非註解行 numberToWire = 0)"
  - "OrderTicket:sellableQty 為 Decimal | null(無持倉為 ZERO),sellableQtyText 以 fmtQuantity 顯示,oversellError 以 Decimal gt 比較;數量輸入的 parseDecimal(numberToWire(qty.value)) 是唯一交接點(05-19 刪除)"
  - "HoldingEventDialog:HoldingEventHolding.totalQuantity: Decimal | number,只經 fmtQuantity 顯示"
  - "layout e2e stub(portfolioStubs.ts)holdings 改字串並補 dayPnlBasis"
affects: [05-19, 05-24, 05-25]

actuals:
  tokens: 17750
  tasks: 2
  commits: 2
commits: 2
plan_head_before: "frontend 42c225adbc21ac13b8c5965c6a8137fa822657b8"
plan_head_after: "frontend 43ad1338f456ac2920997b923d8f7ad3980c539c"

tech-stack:
  added: []
  patterns:
    - "mock 與 API 共用的顯示列(Top movers MoverRow)先在各自路徑算好 up / pctText,模板只把金額交給 moneyFormat,不在模板對金額做比較運算"
    - "API 表格排序抽成 holdingSortValue(h, k): Decimal | null,統一以 cmp 比較、null 排最後;字串欄位(sym)單獨 localeCompare"
    - "股數欄維持既有 number 的顯示形狀時用 Decimal.toFixed()(一般記號、去尾零、無千分位);需要千分位的地方才用 fmtQuantity"

key-files:
  created: []
  modified:
    - /home/user/stock-web-v2-front-end/vue-app/src/services/apiTypes.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/portfolioApi.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/portfolioApi.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Positions.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Positions.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/components/OrderTicket.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/components/OrderTicket.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/components/HoldingEventDialog.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/components/HoldingEventDialog.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/types.ts
    - /home/user/stock-web-v2-front-end/vue-app/e2e/support/portfolioStubs.ts

key-decisions:
  - "commit 邊界依型別閉包(與 05-16 / 05-17 相同):listHoldings 回傳型別一改,OrderTicket(sellableQty 與 qty 的 number 比較)、HoldingEventDialog(totalQuantity: number)、OrderTicket.test fixture 會同時在 vue-tsc 變紅;layout stub 若仍是 number,mapper 會丟錯讓 Positions 進錯誤態。所以 Task 2 的程式變更(OrderTicket / HoldingEventDialog 型別、fixture、stub)併入 8db5c94,Task 2 commit 43ad133 只放精度測試與 types.ts 註解"
  - "Positions 股數欄以 h.totalQuantity.toFixed() 顯示(一般記號、去尾零、無千分位),渲染文字與改字串前的 number 相同;不改 fmtQuantity 以免持倉表出現千分位這種計畫外的畫面變更"
  - "持倉列與 movers 的 roi 百分比改用 05-17 的 fmtRoiPct(Decimal × 100,HALF_UP,非負帶 +),輸出格式與原 fmtPct(x.roi * 100) 相同"
  - "OrderTicket 可賣數量文字依計畫改用 fmtQuantity:≥ 1000 股時會出現千分位(1,000),既有測試只用到 ≤ 100 股所以不受影響;這是計畫明定的格式,與 HoldingEventDialog 的目前持股一致"
  - "toHolding 對缺漏的 nullable 欄位(例如舊後端沒有 dayPnlBasis)視為 null;必填金額欄缺漏或仍為 number 一律在 parseDecimal 丟 TypeError"

patterns-established:
  - "型別閉包交接點(05-18 → 05-19):OrderTicket.oversellError 以 parseDecimal(numberToWire(qty.value)) 轉數量輸入,註解註明 05-19 刪除;OrderTicket 非註解行 numberToWire( 計數 = 1,由 OrderTicket.test 源碼守門"

requirements-completed: [VER-02]

coverage:
  - id: H1
    description: "portfolioApi http listHoldings:14 個金額欄 wire 字串 → Decimal,toWire 逐字往返(含 123456789012.12345678、9007199254740993.01);nullable 欄位 null → null;缺 dayPnlBasis → null;尾零 1000.00000000 / 0E-8 以 eq 比較;金額為 number(必填與 nullable)丟 TypeError;mock 回 domain 且數值等於原 mock 計算"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/services/portfolioApi.test.ts"
        status: pass
    human_judgment: false
  - id: H2
    description: "Positions:超過 double 精度的市值與 8 位小數股數原值顯示;double 無法區分的市值排序與 movers 絕對值排序正確;-1e-8 的今日損益 / 未實現損益為 dn、0 為 up;權重 999 / 4242 = 23.6%、null → —,源碼無 numberToWire;對話框收到 Decimal 股數並以 fmtQuantity 顯示"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/pages/Positions.test.ts#Positions — 持倉以 Decimal 計算(05-18 / D-08)"
        status: pass
    human_judgment: false
  - id: H3
    description: "OrderTicket:小數股數 12.5 原值顯示;等於可賣(10.00000000)不算超賣;9.99999999 超賣、10.00000001 通過;numberToWire 只在 oversellError 一處"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/components/OrderTicket.test.ts(05-18 案例)"
        status: pass
    human_judgment: false
  - id: H4
    description: "HoldingEventDialog:Decimal(含尾零)與 number 的目前持股顯示相同;超過 double 精度的 8 位小數股數原值顯示"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/components/HoldingEventDialog.test.ts#目前持股接受 Decimal 與 number(05-18)"
        status: pass
    human_judgment: false
  - id: H5
    description: "layout.spec 以字串 holdings stub 通過"
    requirement: VER-02
    verification:
      - kind: automated_ui
        ref: "npx playwright test e2e/tests/layout.spec.ts --trace off(101 passed)"
        status: pass
    human_judgment: false

duration: 15min
completed: 2026-11-11
status: complete
---

# Phase 5 Plan 18: holdings 字串契約與 Decimal 計算 Summary

**holdings 型別閉包改成 wire 字串 → `toHolding` → Decimal domain(14 欄,含新補的 `dayPnlBasis`)。Positions 的持倉表、排序、今日損益色彩、Top movers、權重全部用 Decimal 計算;OrderTicket 的可賣數量與超賣檢查改用 Decimal;HoldingEventDialog 接受 Decimal 股數。05-17 留在 `holdingWeight` 的交接點已收回**

## Performance

- **Duration:** 約 15 min
- **Started:** 2026-11-11T03:57:00Z
- **Completed:** 2026-11-11T04:11:38Z
- **Tasks:** 2/2
- **Files modified:** 11

## Accomplishments

- `apiTypes.ts`:`HoldingDto` 的 BigDecimal 欄位改為 `string`(與後端 `HoldingDto.java` 的 `@DecimalString` 逐欄對齊),補上前端原本缺的 `dayPnlBasis`;以 `DecimalFields` 派生 domain `Holding`。
- `portfolioApi.ts`:`toHolding` 由 http 與 mock 共用。mock 先用 number 計算,再以 `numberToWire` 組成 wire,最後走同一個 mapper。
- `Positions.vue`:`hasBasePnl` 只判 null;movers 以 `abs().cmp()` 排序,色彩用 `gte(ZERO)`;表頭排序統一由 `holdingSortValue` 回 Decimal 再 `cmp`,null 排最後;`dayPnlClass` 用 `gte`;權重分子直接用 domain `marketValueInBase`。`numberToWire` import 已移除,原本 `let va: any, vb: any` 也一起刪掉(lint warning 40 → 38)。
- `OrderTicket.vue`:`sellableQty: Decimal | null`、`fmtQuantity` 顯示、`gt` 比較;數量輸入仍是 `v-model.number`,轉換只在 `oversellError` 一處(05-19 交接點)。測試 50 的「不得引用損益欄位」字面守門仍通過。
- `HoldingEventDialog.vue`:`totalQuantity: Decimal | number`,只經 `fmtQuantity` 顯示。

## Task Commits(前端 repo `/home/user/stock-web-v2-front-end`,分支 `claude/epic-curie-lj8alo`)

1. **Task 1(tracer)+ 型別閉包切換**:`8db5c94` `feat(05-18): 持倉改為字串契約,Positions 以 Decimal 計算`。除 Task 1 檔案外,還包含 OrderTicket / HoldingEventDialog 的型別變更、OrderTicket.test fixture 字串化與 layout stub(原因見 Deviations #1)。
2. **Task 2(其餘消費者的精度證據)**:`43ad133` `feat(05-18): 可賣數量與持股數改用 Decimal,layout stub 改字串`。內容是 OrderTicket / HoldingEventDialog 的精度測試、OrderTicket 源碼守門與 types.ts 註解。

完整 SHA:
- `8db5c94226498a90c323e025f477992d57a0510a`
- `43ad1338f456ac2920997b923d8f7ad3980c539c`

plan 開始前 HEAD 為 `42c225adbc21ac13b8c5965c6a8137fa822657b8`。這 2 個 commit 只存在於前端 repo 的歷史。

## Gate(最後一個 commit 上執行)

- `npm run lint`:0 errors、38 warnings(baseline 40;移除 Positions 排序的兩個 `any`)
- `npm test`:66 files / 1401 tests passed
- `VITE_DATA_MODE=api npm test`:66 files / 1401 tests passed
- `npm run build`(vue-tsc + vite):通過
- `npx playwright test e2e/tests/layout.spec.ts --trace off`:101 passed(在 8db5c94 的 stub 上執行;43ad133 沒有改 markup 或 stub)
- Tracer 閘門:Task 1 的 `<verify>`(portfolioApi.test + Positions.test,125 passed)在擴展到 Task 2 前重新執行並通過
- 驗收 grep:`toHolding` export 1、apiTypes `dayPnlBasis` 3、HoldingDto 區塊 `: number` 0、Positions 非註解 `numberToWire` 0、pages/components import `HoldingDto` 0、OrderTicket 非註解 `numberToWire(` 1、HoldingEventDialog `totalQuantity: number;` 0

## Decisions Made

見 frontmatter `key-decisions`。

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] commit 邊界改為依型別閉包(與 05-16 / 05-17 相同)**
- **Found during:** Task 1
- **Issue:** 計畫把 OrderTicket / HoldingEventDialog / layout stub 放在 Task 2。但 `listHoldings()` 回 `Holding[]` 後,OrderTicket 的 `match.totalQuantity`(Decimal)指派給 `number | null`、`qty.value > sellableQty.value` 都會在 vue-tsc 失敗;Positions 的 `apiHoldingOf` 傳 Decimal 給 `totalQuantity: number` 也會失敗。number 形狀的 layout stub 會讓 mapper 丟錯。按 task 分 commit 的話第一個 commit 不會 build。
- **Fix:** 型別切換與所有消費者的程式變更(含 fixture、stub)放在 `8db5c94`,這個 commit 本身通過 lint / 1395 tests(兩種 mode)/ build / layout 101。Task 2 的精度測試放在 `43ad133`。
- **Committed in:** `8db5c94`、`43ad133`

**2. [Rule 1 - Bug] Top movers 的模板比較**
- **Found during:** Task 1
- **Issue:** movers 模板用 `m.pnl >= 0` 決定色彩,`fmtPct(m.pct)` 顯示百分比。改成 Decimal 後,strict big.js 的 `valueOf` 遇到 `>=` 會丟錯。
- **Fix:** 新增 `MoverRow`(`pnl: Decimal | number`、`up`、`pctText`)。mock 與 API 兩條路徑各自算好再交給模板,mock 的輸出與原本相同。
- **Committed in:** `8db5c94`

**3. [計畫外的測試範圍] Positions 精度案例放在新的 describe 區塊**
- 計畫 `<behavior>` 列的權重 999 / 4242、null → —、`apiHoldingOf` 傳 Decimal 都有案例,另外補了 double 精度排序、movers、-1e-8 色彩。既有斷言(例如 `fmtPct(0.0117 * 100)`)渲染文字不變,保留原樣。

---

**Total deviations:** 2 個自動修正(Rule 3 ×1、Rule 1 ×1),外加 1 項測試範圍說明

## Deferred / Out of Scope

- `vue-app/e2e/tests/holding-event-journey.spec.ts:160`(`expect(aapl?.totalQuantity).toBe(1050)`)與 `:30` 的 `totalQuantity: number` 型別,是對真後端讀 holdings 的 E2E,後端 05-13 改字串後就會失敗。本 plan 的閘門不跑這個檔案,05-24 / 05-25 的計畫已把它列入範圍,這次沒有修改。`contract-journey.spec.ts:170` 已經用 `Number(...)` 讀值,不受影響。

## Threat Flags

無新增的安全面。T-05-34(可賣數量 / 超賣比較精度)已緩解:`sellableQty` 是 Decimal,以 `gt` 比較,小數股數與 1e-8 邊界有測試;後端 409 `TRADE_INSUFFICIENT_HOLDING` 仍是最終權威。

## Known Stubs

無。

## Next Phase Readiness(05-19 交接)

- **要刪的交接點**:`OrderTicket.vue` `oversellError` 中的 `parseDecimal(numberToWire(qty.value))`(連同前一行的 `Number.isFinite(qty.value)` 守衛)。數量改成文字輸入 + `parseDecimalInput` 後,直接用 `requested.gt(sellableQty.value)`;刪除後 OrderTicket 非註解行 `numberToWire(` 應為 0,同時更新 `OrderTicket.test.ts` 的「05-18:數量輸入的 number → Decimal 轉換只在 oversellError 一處」源碼守門(改為斷言 0)。
- 改文字輸入時,mock 預檢 `sellPrecheckError`(讀 mock `positions` 的 number)也會受 `qty.value` 型別影響,需要一併處理。
- 05-18 新增的 OrderTicket 超賣案例以 preset qty = 10 為前提;改文字輸入後,`requireInput('ticket-qty').value` 仍應為 `'10'`。
- Positions 已沒有 number → Decimal 的轉換;05-24 擴大到 pages / components 的 `numberToWire` 源碼守門,目前只剩 OrderTicket 這一處會命中。

## Self-Check: PASSED

- FOUND:apiTypes.ts、portfolioApi.ts(+test)、Positions.vue(+test)、OrderTicket.vue(+test)、HoldingEventDialog.vue(+test)、types.ts、e2e/support/portfolioStubs.ts(皆已修改)
- FOUND:`8db5c94`、`43ad133`(前端 repo,都是 HEAD 的祖先;`git rev-list --count 42c225a..HEAD` = 2)
- 規劃 repo 只 commit 本 SUMMARY;STATE.md / ROADMAP.md 未動;沒有 push
