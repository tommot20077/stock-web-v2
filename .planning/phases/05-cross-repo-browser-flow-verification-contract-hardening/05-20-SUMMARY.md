---
phase: 05-cross-repo-browser-flow-verification-contract-hardening
plan: 20
subsystem: frontend-api-contract
tags: [big.js, decimal, dividend, share-adjustment, holding-event-dialog, wire-string, vitest]

requires:
  - phase: 05-19
    provides: "toTradeRecord / toMockLastFill 的 mock adapter 模式(先組 wire 字串,再走共用 mapper);LastFill 為 shallowRef"
  - phase: 05-18
    provides: "HoldingEventHolding.totalQuantity: Decimal | number"
  - phase: 05-16
    provides: "decimal.ts(parseDecimal / parseDecimalInput / toWire / numberToWire / ZERO);DecimalFields;型別閉包交接點規約"
  - phase: 05-14
    provides: "後端 CreateDividendRequest(amount / withholdingTax)、CreateShareAdjustmentRequest(quantity)、DividendDto / ShareAdjustmentDto 以 @DecimalString 收送 JSON 字串"
provides:
  - "holdingEventApi:CreateDividendRequest.amount / withholdingTax、DividendDto.amount / withholdingTax、CreateShareAdjustmentRequest.quantity、ShareAdjustmentDto.quantityDelta 改 string(選填為 string | null);ratioNew / ratioOld 維持 number"
  - "domain:Dividend = DecimalFields<DividendDto, 'amount' | 'withholdingTax'>、ShareAdjustment = DecimalFields<ShareAdjustmentDto, 'quantityDelta'>;輸入 CreateDividendInput / CreateShareAdjustmentInput(金額 / 股數為 Decimal)"
  - "mapper:export toDividend / toShareAdjustment;內部 toCreateDividendRequest / toCreateShareAdjustmentRequest(toWire 逐欄投影,選填欄位送 null),http 與 mock 共用"
  - "介面:createDividend(input, key): Promise<Dividend>、createShareAdjustment(input, key): Promise<ShareAdjustment>"
  - "mock:儲存 wire 字串;值域、冪等、沖銷比對都用 Decimal(eq);只在寫 mock 持倉 / DIV 交易列(mock-only number)時轉 number;MockHoldingEvent 維持 number"
  - "HoldingEventDialog:金額 / 預扣稅 / 配股數量以 parseDecimalInput 解析,上下限以 Decimal 比較;emit('recorded') 與 RecordedEvent 為 Dividend | ShareAdjustment;不再 import wire DTO"
  - "型別閉包交接點(05-20 → 05-21):HoldingEventDialog.createReversal 內的 fromRow(parseDecimal(numberToWire(n))),對話框非註解行 numberToWire( 只有這一處"
affects: [05-21, 05-24, 05-25]

actuals:
  tokens: 20000
  tasks: 2
  commits: 2
commits: 2
plan_head_before: "frontend 057c71d39715985c194cba853576265a6466439e"
plan_head_after: "frontend bbcf7bd2a9bc3f453db2037d054b578e623afede"

tech-stack:
  added: []
  patterns:
    - "holding event 的 mock adapter 和 tradingApi 相同:先 toCreate*Request 組 wire,儲存 wire 字串,回傳前走 toDividend / toShareAdjustment。toDividend 每次都建立新物件,所以不再需要 structuredClone(structuredClone 會丟掉 big.js 的 prototype)"
    - "mock 冪等比對以 sameNullableDecimal(wire, Decimal) 做 compareTo 語意比對,兩邊都是 null 才算相同"

key-files:
  created: []
  modified:
    - /home/user/stock-web-v2-front-end/vue-app/src/services/holdingEventApi.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/holdingEventApi.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/components/HoldingEventDialog.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/components/HoldingEventDialog.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/portfolioRevision.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Positions.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Trades.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/portfolioApi.test.ts

key-decisions:
  - "沒有新增「淨額 = 金額 − 稅」提示,也沒有新增「稅 > 金額」檢查。後端 CreateDividendRequest 的 javadoc 定義 amount 為「實領總額(扣稅後)」,所以金額本身就是淨額,再扣一次稅是錯的;對話框原本也沒有這兩個 UI。Decimal 化只套用在既有的比較(金額 > 0 且 ≤ 10^12、稅 ≥ 0 且 ≤ 10^12、股數 > 0 且 ≤ 10^9)。見 Deviations #2"
  - "HoldingEventHolding.totalQuantity 維持 Decimal | number,沒有收窄。Positions 的 mock 路徑(mockHoldingOf)傳的是 mock Position.qty(number);要收窄就得在 page 呼叫 numberToWire,違反 05-16 規約。對話框只把這個值交給 fmtQuantity 顯示,兩種型別的輸出相同"
  - "前端格式檢查維持原規則(一般記號的非負數字面、≤ 8 位小數),只把 Number 比較換成 Decimal。新規則下 1000000000000.00000001 會被擋下;原本 Number 會把它當成 10^12 放行"
  - "比例 ratioNew / ratioOld 是後端 Integer,對話框的 ratioSideValid / ratioReadText / 送出仍用 Number,符合 must_haves"
  - "mock 預扣稅未填時儲存 \"0\"(後端回 \"0.00000000\"),兩者 eq 相等"
  - "commit 依 dividend / share-adjustment 型別閉包切開,兩個 commit 各自都能 build;Task 1 一併改了共用的 decimalWithin(配股數量的格式檢查也走 Decimal),Task 2 再改股數的送出"

patterns-established:
  - "05-20 → 05-21 交接點:HoldingEventDialog.createReversal 的 fromRow(value: number | null) => parseDecimal(numberToWire(value))。05-21 把 PortfolioActivityDto 改成 Decimal 後,直接傳原列欄位並刪除 fromRow;HoldingEventDialog.test 的源碼守門(numberToWire 只在 createReversal 內)要改成斷言 0"

requirements-completed: [VER-02]

coverage:
  - id: T1
    description: "http createDividend:Decimal → toWire 字串 body(JSON 原文 \"amount\":\"123456789012.12345678\"),未填預扣稅送 null,極小值 0.00000001 不出現科學記號;回應字串 → Dividend,逐字往返;回應 amount / withholdingTax 為 number 丟 TypeError;多餘欄位不送出;401 replay body 相同"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/services/holdingEventApi.test.ts#holdingEventApi — 現金股利字串契約(05-20 / D-05 / D-08)"
        status: pass
    human_judgment: false
  - id: T2
    description: "mock 股利:完整精度往返;同 key 的 \"10\" 與 \"10.00000000\" 為同一筆、只寫一列 DIV;不同金額回 KEY_REUSED;值域以 Decimal 判斷;沖銷值以 Decimal 比對原列;listMockHoldingEvents 的輸出與原本相同(number)"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/services/holdingEventApi.test.ts(mock adapter describe 與 05-20 describe)"
        status: pass
    human_judgment: false
  - id: T3
    description: "股數調整:http 配股 quantity 送字串、拆股 quantity 送 null、比例為 number;回應 quantityDelta 字串 → Decimal(含負值),number 丟錯;mock 配股冪等以 Decimal eq、10^9 + 0.00000001 超出上限、沖銷配股以 Decimal 比對原 ΔQ"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/services/holdingEventApi.test.ts#holdingEventApi — 股數調整字串契約(05-20 / D-05 / D-08)"
        status: pass
    human_judgment: false
  - id: T4
    description: "HoldingEventDialog:金額 123456789012.12345678 與稅 0.00000001 逐字送出;10^12 + 0.00000001 金額 / 稅與 10^9 + 0.00000001 股數顯示欄位錯誤;配股 0.00000001 送字串;recorded payload 為 domain(Decimal);沖銷股利原列稅為 null 時送 null、金額 1234.56 送 \"1234.56\";沖銷配股 12.5 送 \"12.5\";沖銷拆股 quantity 送 null;源碼守門(金額 / 稅 / 股數不經 Number()、numberToWire 只在 createReversal、不 import wire DTO)"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/components/HoldingEventDialog.test.ts(05-20 兩個 describe)"
        status: pass
    human_judgment: false
  - id: T5
    description: "全套閘門與 layout.spec(對話框 markup 未變)"
    requirement: VER-02
    verification:
      - kind: automated_ui
        ref: "npx playwright test e2e/tests/layout.spec.ts --trace off(101 passed)"
        status: pass
    human_judgment: false

duration: 16min
completed: 2026-11-11
status: complete
---

# Phase 5 Plan 20: 股利與股數調整字串契約與 Decimal 計算 Summary

**股利 / 股數調整的請求與回應都改成 wire 字串,再經 `toDividend` / `toShareAdjustment` 轉成 Decimal domain。http 與 mock 共用 `toWire` 逐欄投影;mock 的值域、冪等、沖銷比對都改用 Decimal。HoldingEventDialog 以 Decimal 解析與比較金額 / 稅 / 股數,`recorded` 事件送出 domain 型別。沖銷時把原活動列的 number 轉成 Decimal,這個轉換只在 `createReversal` 一處,05-21 刪除**

## Performance

- **Duration:** 約 16 min
- **Started:** 2026-11-11T04:36:50Z
- **Completed:** 2026-11-11T04:52:06Z
- **Tasks:** 2/2
- **Files modified:** 8

## Accomplishments

- `holdingEventApi.ts`:四個 wire 欄位改 string,對齊後端 `@DecimalString`。新增 `Dividend`、`ShareAdjustment`、`CreateDividendInput`、`CreateShareAdjustmentInput`、`toDividend`、`toShareAdjustment`。選填的 `withholdingTax` / `quantity` 未填時送 `null`,不送空字串。
- mock adapter:先組 wire,儲存字串,回傳前走同一個 mapper。冪等與沖銷比對用 Decimal `eq`,所以同 key 的 `"10"` 與 `"10.00000000"` 視為同一筆。持股數 / DIV 交易列 px 只在寫進 mock store 時轉成 number。拆股的 ΔQ 由 mock 持倉(number)算出後,以 `numberToWire` 存成字串。`MockHoldingEvent` 欄位型別不變(05-21)。
- `HoldingEventDialog.vue`:新增 `parseAmountText`(格式 + 位數檢查 → `parseDecimalInput`)、`decimalWithin`(Decimal 上下限)、`requireDecimalText`(送出用,沒通過自檢就丟錯,不會送出 0)。`emit('recorded', event: Dividend | ShareAdjustment)`;`reversalPreviewFor` 改為接受 `Decimal | number`(原列仍是 number,結果是 Decimal)。
- `portfolioRevision.ts`:`notifyHoldingEventCreated` 的 JSDoc 改指 domain 型別(只讀 `id`)。

## Task Commits(前端 repo `/home/user/stock-web-v2-front-end`,分支 `claude/epic-curie-lj8alo`)

1. **Task 1(tracer)**:`87682d6` `feat(05-20): 現金股利請求與回應改為字串契約`。股利型別與 mapper、mock 股利、對話框現金股利路徑、createReversal 股利分支、共用的 Decimal 格式檢查、股利 fixture(含 Positions.test / Trades.test / portfolioApi.test)。
2. **Task 2**:`bbcf7bd` `feat(05-20): 股數調整與 mock 股利事件改為字串契約`。股數調整型別與 mapper、mock 股數調整、對話框配股送出與 createReversal 股數調整分支、recorded / RecordedEvent 改 domain、portfolioRevision JSDoc、股數調整 fixture 與精度測試。

完整 SHA:
- `87682d6dd943bf92015db5182982d13bad7d65f3`
- `bbcf7bd2a9bc3f453db2037d054b578e623afede`

plan 開始前 HEAD 為 `057c71d39715985c194cba853576265a6466439e`;`git rev-list --count 057c71d..HEAD` = 2。這兩個 commit 只存在於前端 repo 的歷史。

## Gate(最後一個 commit 的程式狀態)

- `npm run lint`:0 errors、38 warnings(與 baseline 相同)
- `npm test`:66 files / 1451 tests passed
- `VITE_DATA_MODE=api npm test`:66 files / 1451 tests passed
- `npm run build`(vue-tsc --noEmit + vite build):通過
- `npx playwright test e2e/tests/layout.spec.ts --trace off`:101 passed
- 87682d6 本身也跑過 lint / 兩種 mode 的 1441 tests / build,全部通過
- Tracer 閘門:擴展到 Task 2 之前,Task 1 的 `<verify>`(holdingEventApi.test + HoldingEventDialog.test,121 tests)重跑並通過
- 驗收 grep:DividendDto 區塊 `(amount|withholdingTax): number` 0;`toDividend` 8;holdingEventApi.test `"123456789012.12345678"` 1;ShareAdjustmentDto 區塊 `quantityDelta: number` 0;對話框 import `DividendDto|ShareAdjustmentDto` 0;對話框非註解行 `numberToWire(` 1,createReversal 內 1

## Decisions Made

見 frontmatter `key-decisions`。

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] commit 邊界依型別閉包,並改了 frontmatter 以外的測試檔**
- **Found during:** Task 1
- **Issue:** `DividendDto` / `createDividend` 簽章一改,Positions.test、Trades.test 的 wire fixture,以及 portfolioApi.test、Trades.test 呼叫 mock `createDividend` 時傳的 number 參數,都會在 vue-tsc 變紅。
- **Fix:** 股利閉包的所有消費者都放在 87682d6,股數調整閉包放在 bbcf7bd,兩個 commit 都能 build。`src/services/portfolioApi.test.ts` 不在 frontmatter 內:mock `createDividend` / `createShareAdjustment` 的參數改成 `parseDecimal(...)`,活動列的斷言維持 number(05-21)。
- **Committed in:** `87682d6`、`bbcf7bd`

### 計畫文字與既有契約衝突

**2. 沒有新增「淨額(金額 − 稅)」與「稅 > 金額」**
- **Issue:** must_haves / behavior 寫「金額 120、稅 30 → 淨額提示 90」「稅 > 金額 → 既有欄位錯誤」。但對話框沒有淨額提示,也沒有這條欄位錯誤。後端 `CreateDividendRequest` 的 `@param amount` 是「實領總額(扣稅後、標的幣別)」,金額已經是淨額,金額 − 稅會把稅扣兩次。
- **Fix:** 沒有新增這兩個 UI。對話框裡實際存在的金額比較(上下限、> 0 / ≥ 0)全部改用 Decimal,並以 10^12 + 0.00000001 的測試證明 Decimal 比較有效。如果 Yuan 要加「稅前總額 = 實領 + 稅」之類的提示,需要另外定 UI 文案。

### 不需要變更的檔案

- `e2e/support/portfolioStubs.ts`:dividends / share-adjustments 的 POST stub 只回 503(送出中狀態測試),沒有 DTO fixture。stub 中有 CASH_DIVIDEND / STOCK_DIVIDEND 的 activity 列,它們屬於 `PortfolioActivityDto`,由 05-21 處理。本 plan 沒有修改這個檔案。

---

**Total deviations:** 1 個自動修正(Rule 3),外加 1 項與計畫文字不同的語意決定(#2)

## Deferred / Out of Scope

- `vue-app/e2e/tests/holding-event-journey.spec.ts`:對真後端讀寫股利 / 股數調整的數字斷言,依約由 05-24 處理,這次沒動。
- `e2e/support/journeyApi.ts` 沒有股利 / 股數調整的 helper(只有 `apiBuy`,05-19 已記錄);真後端股利 / 配股請求都在 holding-event-journey.spec 內,同樣交給 05-24。

## Threat Flags

沒有新增安全面。T-05-30 已緩解:送出值以 `toWire` 逐欄投影(一般記號);選填欄位送 `null`;往返精度、number 回應丟錯都有測試;沖銷值逐欄取自原列,後端會再驗證。

## Known Stubs

無。

## Next Phase Readiness(交接)

- **05-21(活動列)**:
  - 刪除 `HoldingEventDialog.createReversal` 的 `fromRow`,改為直接傳原列的 Decimal 欄位;`amount: fromRow(original.amount) ?? ZERO` 的 fallback 視 domain 是否 nullable 再決定要不要保留。同時把 HoldingEventDialog.test「numberToWire 只在 createReversal」的源碼守門改成斷言 0,並移除對話框的 `numberToWire` import。
  - `holdingEventApi.ts` 的 `MockHoldingEvent` 與 `PortfolioActivityDto` 一起字串化:`dividendToEvent` / `adjustmentToEvent` 目前以 `Number(entry.amount)` / `Number(entry.quantityDelta)` 產生 number,屆時直接傳 wire 字串即可(儲存層已經是字串)。
  - `reversalPreviewFor` 已接受 `Decimal | number`;活動列轉 Decimal 後可以收窄成 `Decimal | null`。
  - `holdingEventApi.test.ts` 的 05-20 mock 沖銷測試以 `toEqual([50, 50])` 斷言 `listMockHoldingEvents()` 的 quantity,05-21 要改成字串 / Decimal。
- **HoldingEventHolding.totalQuantity** 仍是 `Decimal | number`(mock 路徑傳 number)。如果之後要收窄,可以由 mock portfolio adapter 提供 Decimal getter(同 05-19 的 `heldQuantityOf`),Positions 不需要自己轉換。
- 可以 import 共用 mapper:`toDividend`、`toShareAdjustment`(皆在 `holdingEventApi.ts`)。

## Self-Check: PASSED

- FOUND:frontmatter key-files 的 8 個檔案都已修改(`git diff --stat 057c71d..HEAD`:8 files)
- FOUND:`87682d6`、`bbcf7bd`(前端 repo,都是 HEAD 的祖先;`git rev-list --count 057c71d..HEAD` = 2)
- 規劃 repo 只 commit 本 SUMMARY;STATE.md / ROADMAP.md 未動;沒有 push
