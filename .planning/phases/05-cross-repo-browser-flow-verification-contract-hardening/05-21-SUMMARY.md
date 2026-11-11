---
phase: 05-cross-repo-browser-flow-verification-contract-hardening
plan: 21
subsystem: frontend-api-contract
tags: [big.js, decimal, portfolio-activity, trades, csv, holding-event-dialog, wire-string, vitest]

requires:
  - phase: 05-20
    provides: "HoldingEventDialog.createReversal 的交接點 fromRow(parseDecimal(numberToWire(n)));mock holding event 儲存層已是 wire 字串;Dividend / ShareAdjustment domain"
  - phase: 05-19
    provides: "tradingApi 的 mock adapter 模式(先組 wire,再走共用 mapper);Trades.test 的 trade() helper"
  - phase: 05-16
    provides: "decimal.ts(parseDecimal / parseNullableDecimal / toWire / numberToWire / ZERO);DecimalFields;moneyFormat 接受 Decimal"
provides:
  - "apiTypes:PortfolioActivityDto.quantity / price / fee / amount / withholdingTax 改 string | null;ratioNew / ratioOld 維持 number"
  - "domain:PortfolioActivity = DecimalFields<PortfolioActivityDto, 'quantity' | 'price' | 'fee' | 'amount' | 'withholdingTax'>"
  - "mapper:export toPortfolioActivity(wire)(portfolioApi.ts),http 與 mock 共用;number 金額在邊界丟 TypeError"
  - "介面:PortfolioApi.listActivities 回 PaginatedResponse<PortfolioActivity>;PortfolioLiveMockData.holdingEvents 為 PortfolioActivity[]"
  - "mock:交易列以 numberToWire 組 wire,amount 以 Decimal times 計算;MockHoldingEvent 金額 / 預扣稅 / 股數改 wire 字串(儲存層原樣傳出);排序以 Decimal cmp(NULLS LAST);移除 structuredClone"
  - "Trades.vue:tradeTotal 回 Decimal(quantity.times(price));預扣稅行以 gt(ZERO) 判斷;交易數量欄與 CSV 數值欄以 toWire 輸出一般記號(plainNumber)"
  - "HoldingEventDialog:reversalOf / createReversal 改 PortfolioActivity;05-20 交接點 fromRow 已刪除,對話框不再 import numberToWire;reversalPreviewFor 收窄為 Decimal | null"
  - "e2e portfolioStubs:activities fixture 金額改 wire 字串"
affects: [05-22, 05-23, 05-24, 05-25]

actuals:
  tokens: 7600
  tasks: 2
  commits: 2
commits: 2
plan_head_before: "frontend bbcf7bd2a9bc3f453db2037d054b578e623afede"
plan_head_after: "frontend c334f82b633832bdae46153a0032e777df155eb1"

tech-stack:
  added: []
  patterns:
    - "活動列 mapper 放 portfolioApi,holdingEventApi 只產出 wire 形狀(MockHoldingEvent),避免 portfolioApi ↔ holdingEventApi 循環 import"
    - "頁面上不經格式化的數字(交易數量欄、CSV 數值欄)一律 toWire;String(Decimal) 在極小值會輸出 1e-8,csvCell 會把它當文字加公式前綴"
    - "mock 回傳 domain 時不能 structuredClone(會丟掉 big.js prototype);改由 mapper 每次建立新物件來隔離 mock 狀態"

key-files:
  created: []
  modified:
    - /home/user/stock-web-v2-front-end/vue-app/src/services/apiTypes.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/portfolioApi.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/portfolioApi.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Trades.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Trades.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/csvCell.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/holdingEventApi.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/holdingEventApi.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/components/HoldingEventDialog.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/components/HoldingEventDialog.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/e2e/support/portfolioStubs.ts

key-decisions:
  - "csvCell.ts 不需修改:PLAIN_NUMBER 已涵蓋 toWire 的一般記號(含負號、小數);以 csvCell.test 新案例證明(0.00000001、-12.50、123456789012.12345678 原樣;-1e-8 會被加前綴,提醒一律走 toWire)"
  - "交易數量欄原本是 {{ tr.quantity }}(number 直接渲染),改為 toWire 而不是 fmtQuantity:fmtQuantity 會加千分位,改變既有渲染;toWire 對一般值輸出與原本相同,極小值不再是 1e-8"
  - "createReversal 的現金股利 amount 保留 `?? ZERO` fallback:domain 型別是 Decimal | null(契約上 CASH_DIVIDEND 恆有值),缺值時送 0 讓後端以值域錯誤拒絕,不在前端猜測"
  - "mock 靜態交易列的 CSV(filteredTrades,data.ts 的 number)維持原樣:屬 mock-only 畫面資料(05-16 規約),不在 API 契約範圍"
  - "Trades 沒有「合計」列;plan 所說的合計只對應到 tradeTotal(單列 quantity × price),已改 Decimal"

patterns-established:
  - "活動列 domain 的消費者只拿 PortfolioActivity;頁面 / 元件 import PortfolioActivityDto 為 0(acceptance grep)"

requirements-completed: [VER-02]

coverage:
  - id: T1
    description: "http listActivities:wire 字串 → Decimal(0.00000001、-0.00000001、123456789012.12345678 逐字往返),ratioNew / ratioOld 維持 number,withholdingTax null → null;五個金額欄任一為 JSON number → toPortfolioActivity 與 listActivities 丟 TypeError"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/services/portfolioApi.test.ts#portfolioApi http adapter — listActivities(PORT-13)"
        status: pass
    human_judgment: false
  - id: T2
    description: "mock listActivities:交易列為 Decimal 且等於 data.ts 原值;靜態 DIV 實領 120 × 0.24 = 28.8(Decimal,number 會得 28.799999999999997);total 排序以 Decimal cmp、NULLS LAST;事件列合併一次"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/services/portfolioApi.test.ts#portfolioApi mock adapter — listActivities"
        status: pass
    human_judgment: false
  - id: T3
    description: "Trades:3 × 0.1 的 CSV total 為 0.3;123456.12345678 數量欄與 CSV 逐字;手續費 / 股利實領 0.00000001 與預扣稅 123456.12345678 以一般記號匯出,CSV 無科學記號、無公式前綴;既有渲染與 CSV 斷言不變"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/pages/Trades.test.ts#Trades — API mode CSV 全頁匯出(D-10)/ 05-21 / D-08"
        status: pass
      - kind: unit
        ref: "vue-app/src/csvCell.test.ts#05-21:Decimal 經 toWire 的金額字串(一般記號)原樣輸出"
        status: pass
    human_judgment: false
  - id: T4
    description: "mock 事件列:listMockHoldingEvents 金額 / 預扣稅 / 股數為 wire 字串且完整精度;live.holdingEvents 為 Decimal 且 toWire 與事件列相同"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/services/holdingEventApi.test.ts#holdingEventApi — mock 事件列為 wire 字串(05-21 / D-05 / D-08)"
        status: pass
    human_judgment: false
  - id: T5
    description: "HoldingEventDialog 沖銷:原列 Decimal 直接投影 —— 股利 120 / 30、配股 50(既有測試)與 123456789012.12345678 / 0.00000001 / 配股 0.00000001(新測試)逐字送出;摘要與預覽經 moneyFormat;源碼守門 numberToWire 0 處、不 import PortfolioActivityDto"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/components/HoldingEventDialog.test.ts(沖銷模式 / 05-20 兩個 describe / 05-21 describe)"
        status: pass
    human_judgment: false
  - id: T6
    description: "layout.spec(Trades 混合活動列 stub 改字串後版面不變)"
    requirement: VER-02
    verification:
      - kind: automated_ui
        ref: "npx playwright test e2e/tests/layout.spec.ts --trace off(101 passed)"
        status: pass
    human_judgment: false

duration: 20min
completed: 2026-11-11
status: complete
---

# Phase 5 Plan 21: 活動列字串契約與 Decimal 計算 Summary

**`PortfolioActivityDto` 的五個金額欄改成 wire 字串,新增的 `toPortfolioActivity` 把它轉成 Decimal domain,http 與 mock 都走這個 mapper。Trades 頁的單列總額、預扣稅判斷、數量欄與 CSV 匯出都改用 Decimal,匯出值以 `toWire` 輸出一般記號。HoldingEventDialog 的沖銷原列直接收 domain 型別,05-20 留下的 `numberToWire` 交接點已刪除**

## Performance

- **Duration:** 約 20 min
- **Started:** 2026-11-11T04:50Z
- **Completed:** 2026-11-11T05:11Z
- **Tasks:** 2/2
- **Files modified:** 11

## Accomplishments

- `apiTypes.ts`:`PortfolioActivityDto` 的 quantity / price / fee / amount / withholdingTax 改 `string | null`,新增 `PortfolioActivity`。比例欄是後端 Integer,維持 number。
- `portfolioApi.ts`:新增 `toPortfolioActivity`。http `listActivities` 把 `content` 逐列轉換;mock 交易列由 `data.ts` number 經 `numberToWire` 轉 wire,`amount` 改用 Decimal 相乘(原本 `trade.qty * trade.px` 走 JS 浮點);mock 事件列經 `mockEventActivity` 走同一個 mapper。排序比較器改為 Decimal `cmp`,NULLS LAST。mock 回傳拿掉 `structuredClone`,因為它會丟掉 big.js 的 prototype;mapper 每次都建立新物件,mock 狀態一樣不會被呼叫端改到(既有的 clone 測試仍通過)。
- `holdingEventApi.ts`:`MockHoldingEvent` 的金額欄改 wire 字串;`dividendToEvent` / `adjustmentToEvent` 直接傳儲存層字串,不再經 `Number()`。
- `Trades.vue`:只用 domain 型別;`tradeTotal` 回 Decimal;`hasWithholdingTax` 以 `gt(ZERO)` 判斷;`plainNumber`(toWire)用在交易數量欄與 CSV 五個數值欄。
- `HoldingEventDialog.vue`:`reversalOf` 與 `createReversal(original)` 改 `PortfolioActivity`,`fromRow` 與 `numberToWire` import 已刪除;原列 Decimal 直接放進 `Create*Input`;模板稅額 fallback 由 `?? 0` 改 `?? ZERO`;`reversalPreviewFor` 收窄為 `Decimal | null`。
- `e2e/support/portfolioStubs.ts`:activities fixture 改字串;交易列 `amount` 以 BigInt 精確乘積組成(e2e 不 import 產品碼的 big.js)。

## Task Commits(前端 repo `/home/user/stock-web-v2-front-end`,分支 `claude/epic-curie-lj8alo`)

1. **Task 1(tracer)**:`c1b68f4` `feat(05-21): 活動列讀取改為字串契約,CSV 輸出一般記號`。型別切換與所有消費者(apiTypes、portfolioApi、holdingEventApi、Trades.vue、HoldingEventDialog.vue)、因型別而壞的 fixture(五個測試檔)、http 精度 / number 丟錯 / CSV 精度 / csvCell 新測試。
2. **Task 2**:`c334f82` `feat(05-21): mock 事件列與沖銷原列改為 Decimal,layout stub 改字串`。mock 事件列字串與精度測試、沖銷原列精度測試、portfolioStubs 字串化。

完整 SHA:
- `c1b68f47ecbb5c613652ff829422e00e05ba00a8`
- `c334f82b633832bdae46153a0032e777df155eb1`

plan 開始前 HEAD 為 `bbcf7bd2a9bc3f453db2037d054b578e623afede`;`git rev-list --count bbcf7bd..HEAD` = 2。這兩個 commit 只存在於前端 repo 的歷史。

## Gate(最後一個 commit 的程式狀態)

- `npm run lint`:0 errors、38 warnings(與 baseline 相同)
- `npm test`:66 files / 1460 tests passed
- `VITE_DATA_MODE=api npm test`:66 files / 1460 tests passed
- `npm run build`(vue-tsc --noEmit + vite build):通過
- `npx playwright test e2e/tests/layout.spec.ts --trace off`:101 passed
- c1b68f4 本身也跑過 lint / 兩種 mode 的 1455 tests / build,全部通過
- Tracer 閘門:擴展到 Task 2 之前,Task 1 的 `<verify>`(portfolioApi.test + Trades.test + csvCell.test,139 tests)重跑並通過
- portfolioStubs.ts 另以 `tsc --strict` 單檔檢查通過(e2e 不在 vue-tsc 的 include 內)
- 驗收 grep:PortfolioActivityDto 區塊 `(quantity|price|fee|amount|withholdingTax): number` 0;`ratio(New|Old): number` 2;`export function toPortfolioActivity` 1;Trades.vue `row.quantity * row.price` 0;對話框非註解行 `numberToWire` 0;pages / components import `PortfolioActivityDto` 0;MockHoldingEvent 區塊 `amount: number` 0
- 禁止事項(judgment):Trades.vue / HoldingEventDialog.vue 剩下的 `Number(` 都是比例(Integer)解析,沒有對 domain 金額做 `Number()` 或算術運算子

## Decisions Made

見 frontmatter `key-decisions`。

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] commit 邊界依型別閉包重切,Task 1 涵蓋了 plan 原本放在 Task 2 的原始碼檔**
- **Found during:** Task 1
- **Issue:** `PortfolioActivityDto` 一改成字串,`portfolioApi` 的 `listMockHoldingEvents().map(...)`(MockHoldingEvent 仍是 number)就會型別錯誤;`listActivities` 改回 domain 後,Trades 傳給 `HoldingEventDialog` 的 `reversalOf` 也會不符。Task 1 若照 plan 切,commit 無法 build。
- **Fix:** Task 1(c1b68f4)包含型別切換與所有消費者:`holdingEventApi.ts` 的 MockHoldingEvent 字串化、`HoldingEventDialog.vue` 的 `reversalOf` / `createReversal` 改型別並刪除 `fromRow`,以及因型別變更而壞的 `holdingEventApi.test.ts`、`HoldingEventDialog.test.ts` fixture 與源碼守門。Task 2(c334f82)只留新增的精度測試與 layout stub。兩個 commit 都能 build、兩種 mode 的測試都綠。
- **Files modified:** 同 frontmatter key-files;沒有超出 plan frontmatter 的檔案(`csvCell.ts` 不需修改,見 key-decisions)
- **Committed in:** `c1b68f4`、`c334f82`

**2. [Rule 1 - Bug] mock `listActivities` 移除 `structuredClone`**
- **Found during:** Task 1
- **Issue:** 回傳值改成含 Decimal 的 domain 後,`structuredClone` 會把 big.js 實例變成普通物件(沒有 `times` / `toFixed`),頁面在 mock mode 會壞。
- **Fix:** 拿掉 `structuredClone`,mapper 每次呼叫都建立新物件。「改回傳值不影響 mock 狀態」的既有測試(portfolioApi.test / holdingEventApi.test)改用字串 / Decimal 後仍通過。
- **Committed in:** `c1b68f4`

### 計畫文字與既有程式不同

- `csvCell.ts` 沒有修改,plan 已預期這一點(「預期不需改,以測試證明」)。
- plan 說「合計」改 Decimal,但 Trades 頁沒有合計列,只有單列 `tradeTotal`,已改 Decimal。
- 版面測試(layout.spec)在 c1b68f4 與 c334f82 之間會因 stub 仍是 number 而失敗(page 經 `parseDecimal` 丟錯);plan 本來就把 stub 放在 Task 2,所以照做。最終 commit 101 passed。

---

**Total deviations:** 2 個自動修正(Rule 3 commit 邊界、Rule 1 structuredClone)

## Deferred / Out of Scope

- `HoldingEventHolding.totalQuantity` 仍是 `Decimal | number`:Positions 的 mock 路徑傳 `Position.qty`(number)。這屬於持倉 / mock adapter 範圍,不在本 plan 的活動列閉包內,沒有改。之後可由 mock portfolio adapter 提供 Decimal getter(同 05-19 的 `heldQuantityOf`),Positions 不需自己轉。
- 真後端 E2E:`e2e/tests/holding-event-journey.spec.ts`、`e2e/tests/api-failure-states.spec.ts`(`BUY_SEED` quantity / price、cash 種子 amount 仍是 number)依約由 05-24 處理,沒動。
- mock 靜態交易列(`filteredTrades`)的 CSV 仍以 `tr.qty * tr.px`(number)輸出:屬 mock-only 畫面資料。

## Threat Flags

沒有新增安全面。T-05-35 已緩解:活動列數值欄一律經 `toWire`(`toFixed` 一般記號)輸出,`csvCell` 的 `PLAIN_NUMBER` 判斷不變,非純數字仍加公式前綴;新增 `0.00000001` / `-12.50` / `123456789012.12345678` / `-1e-8` 案例,以及 Trades 匯出「無科學記號、無 `'`」的整檔斷言。

## Known Stubs

無。

## Next Phase Readiness(交接)

- 活動列閉包已完成:頁面 / 元件只拿 `PortfolioActivity`;mapper `toPortfolioActivity` 可從 `portfolioApi.ts` import。
- `numberToWire` 在 `src/pages` / `src/components` 的非註解行引用:HoldingEventDialog 0;05-24 的源碼守門可以把對話框納入。
- `MockHoldingEvent` 是 wire 形狀(字串);新的 mock 事件來源也要在 portfolioApi 經 `toPortfolioActivity` 轉換,不要在 holdingEventApi 內 import portfolioApi(會循環)。
- 回傳含 Decimal 的 mock 資料時不要用 `structuredClone`(會丟 prototype);用 mapper 重新建立物件。
- 05-22 → 05-23 的交接點(useLiveQuotes.overlayTick)與本 plan 無關,沒動。

## Self-Check: PASSED

- FOUND:frontmatter key-files 的 11 個檔案都已修改(`git diff --stat bbcf7bd..HEAD`:11 files)
- FOUND:`c1b68f4`、`c334f82`(前端 repo,都是 HEAD 的祖先;`git rev-list --count bbcf7bd..HEAD` = 2)
- 規劃 repo 只 commit 本 SUMMARY;STATE.md / ROADMAP.md 未動;沒有 push
