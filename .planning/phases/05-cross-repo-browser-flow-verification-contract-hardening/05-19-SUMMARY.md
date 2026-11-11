---
phase: 05-cross-repo-browser-flow-verification-contract-hardening
plan: 19
subsystem: frontend-api-contract
tags: [big.js, decimal, trades, order-ticket, wire-string, vitest, playwright]

requires:
  - phase: 05-18
    provides: "Holding domain(totalQuantity: Decimal);OrderTicket.sellableQty: Decimal | null;oversellError 的 numberToWire 交接點"
  - phase: 05-16
    provides: "decimal.ts(parseDecimal / parseDecimalInput / toWire / numberToWire / ZERO);moneyFormat 接受 Decimal;Wave 2 前端共同規約"
  - phase: 05-14
    provides: "後端 TradeDto / CreateTradeRequest 的 quantity / price / fee 以 @DecimalString 輸出 / 接受 JSON 字串"
provides:
  - "apiTypes:TradeDto 的 quantity / price / fee 改 string;domain TradeRecord = DecimalFields<TradeDto, 'quantity' | 'price' | 'fee'>"
  - "tradingApi:CreateTradeRequest 金額欄改 string;新增 CreateTradeInput(Decimal)、toCreateTradeRequest(toWire 逐欄投影)、export toTradeRecord、export toMockLastFill;createTrade(input, key): Promise<TradeRecord>;TradingLiveMockData 新增 heldQuantityOf(symbol): Decimal | null"
  - "portfolioRevision:LastFill.qty / px 為 Decimal,notifyTradeCreated(trade: TradeRecord);lastFill 改 shallowRef + shallowReadonly"
  - "portfolioApi:listTrades 回 PaginatedResponse<TradeRecord>(http 以 toTradeRecord 轉 items,mock 以 numberToWire 組 wire 再 map,排序用 Decimal cmp);PortfolioLiveMockData.lastFill 為 LastFill(getter 經 toMockLastFill)"
  - "OrderTicket:數量 / 價格 / 手續費為 type=text + inputmode=decimal 的文字輸入,parseAmountInput(一般記號、≤ 8 位小數)解析成 Decimal;估算、cashAfter、超賣、mock 預檢都用 Decimal;前端格式錯誤顯示在既有欄位錯誤節點;結果畫面顯示後端 TradeRecord;05-18 的 numberToWire 交接點已刪除"
  - "Overview 近期交易以 TradeRecord 顯示(股數 toWire、總額 times)"
affects: [05-21, 05-22, 05-24, 05-25]

actuals:
  tokens: 27000
  tasks: 3
  commits: 3
commits: 3
plan_head_before: "frontend 43ad1338f456ac2920997b923d8f7ad3980c539c"
plan_head_after: "frontend 057c71d39715985c194cba853576265a6466439e"

tech-stack:
  added: []
  patterns:
    - "表單金額輸入:ref<string> 存原始文字,computed 以 parseAmountInput 回 { empty | invalid | value };範圍(> 0 / ≥ 0)由各欄位 computed 判斷,送出時只傳已驗證的 Decimal"
    - "mock-only number 資料要和 Decimal 比較時,由 mock adapter 提供 Decimal getter(heldQuantityOf、toMockLastFill),元件不自己轉"
    - "放 Decimal 的模組級 ref 用 shallowRef + shallowReadonly:值整筆替換,big.js 實例不被包成深層 proxy"

key-files:
  created: []
  modified:
    - /home/user/stock-web-v2-front-end/vue-app/src/services/apiTypes.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/tradingApi.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/tradingApi.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/components/OrderTicket.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/components/OrderTicket.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/portfolioRevision.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/portfolioRevision.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/portfolioApi.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/portfolioApi.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Overview.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Overview.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Positions.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Trades.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/task4.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/e2e/support/portfolioStubs.ts

key-decisions:
  - "估算金額維持 數量 × 價格(不含手續費),沒有照計畫寫的「+ 手續費」改。原因:既有測試已鎖住 review / ticket 的預估總額 = 10 × 945 = 9,450.00(手續費 20 不計入),後端 total 也定義為 quantity × price;mock cashAfter 依買賣方向另外處理手續費,估算若含手續費會重複計算。新測試用 3 × 123.45 = 370.35 驗證(見 Deviations #2)"
  - "前端格式檢查與 CashEntryDialog 相同:一般記號的非負數字面、≤ 8 位小數(尾零不算)。數量 / 價格格式錯誤顯示在既有的 ticket-field-error-quantity / -price 節點(沿用 tradeErrQuantity / tradeErrPrice 文案);0 與空白只停用送出、不顯示錯誤(與改文字輸入前相同);手續費留空視為 0 並送出 \"0\",格式錯誤沿用既有的表單底部 tradeErrFee"
  - "mock 同步預檢 sellPrecheckError 改讀 trading.live.heldQuantityOf(symbol)(mock adapter 內以 numberToWire → parseDecimal 轉換),元件只做 Decimal 比較;英文文案不變,task4.test 的超賣案例照舊通過"
  - "價格預填:AssetDto.latestPrice 要到 05-22 才轉 Decimal,本 plan 以 priceInputText(price) = toWire(parseDecimalInput(String(price)) ?? ZERO) 產生一般記號的預填文字(5e-7 → 0.0000005,不會被格式檢查擋下),缺價維持預填 0。這是 05-19 → 05-22 的交接點,不經 numberToWire"
  - "mock createTrade 先以 toCreateTradeRequest 組 wire,mock store(number)只在 adapter 內以 Number(wire) 取得;回傳結果用 wire 字串再走 toTradeRecord,所以 mock 結果保留輸入的完整精度"
  - "三處 LastFill 統一引用 portfolioRevision 的 LastFill 型別(不再各寫一份 inline 型別),JSDoc 保留三處同形說明"
  - "OrderTicket 的成功畫面 / review 股數以 toWire 顯示(一般記號、去尾零),渲染文字與改字串前的 number 相同(後端 \"7.00000000\" → 7)"

patterns-established:
  - "05-19 → 05-22 交接點:OrderTicket.priceInputText 把 number latestPrice 轉成預填文字;05-22 改為 toWire(asset.latestPrice) 後刪除 String() / parseDecimalInput 這一段"

requirements-completed: [VER-02]

coverage:
  - id: T1
    description: "tradingApi:createTrade body 為字串(\"3\" / \"123456.12345678\" / \"0\"),極小值 0.00000001 不出現科學記號;同 input 同 key 重送 body 逐位元相同;401 replay body 逐字相同;回應 wire → TradeRecord(9007199254740993.01 逐字往返,尾零 eq);回應金額為 number 丟 TypeError;mock 回傳保留完整精度;mock lastFill / heldQuantityOf 為 Decimal"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/services/tradingApi.test.ts#tradingApi — 金額字串契約(05-19 / D-05 / D-08)"
        status: pass
    human_judgment: false
  - id: T2
    description: "OrderTicket:三個欄位 type=text + inputmode=decimal;估算 3 × 123.45 = 370.35、0.1 × 3 = 0.30;送出 body 字串並保留 8 位小數,網路失敗後重試 body 與 key 相同;手續費留空送 \"0\";1e-9 / 9 位小數 / 負數 / 非數字 → 數量欄位錯誤且不可送出;價格 9 位小數 → 價格欄位錯誤;0 / 空白不顯示錯誤;10.00000001 對可賣 10 為超賣;review 顯示正規化數量,結果畫面顯示後端值;源碼守門 numberToWire = 0"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/components/OrderTicket.test.ts#OrderTicket 金額輸入 — 文字輸入 + Decimal(05-19)"
        status: pass
    human_judgment: false
  - id: T3
    description: "portfolioRevision:notifyTradeCreated 由 TradeRecord 推導 qty / px(0.00000001、9007199254740993.01 原樣),apiLastFill 回傳原始 Decimal;portfolioApi:http listTrades 字串 → Decimal、number 丟錯、尾零 eq;mock 總額排序以 Decimal(0.1 × 3 與 0.3 × 1 相等);mock live.lastFill 為 Decimal"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/services/portfolioRevision.test.ts、vue-app/src/services/portfolioApi.test.ts(05-19 describe)"
        status: pass
    human_judgment: false
  - id: T4
    description: "Overview 近期交易:尾零正規化後渲染文字不變(10 / 12.50 / 125)、8 位小數股數原值、9007199254740993 的總額精確;tbody 無金額算術運算子、頁面不 import TradeDto"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/pages/Overview.test.ts#05-19(D-08):近期交易以 TradeRecord 顯示"
        status: pass
    human_judgment: false
  - id: T5
    description: "layout.spec 以字串 trades stub 通過"
    requirement: VER-02
    verification:
      - kind: automated_ui
        ref: "npx playwright test e2e/tests/layout.spec.ts --trace off(101 passed)"
        status: pass
    human_judgment: false

duration: 30min
completed: 2026-11-11
status: complete
---

# Phase 5 Plan 19: 手動交易字串契約與 Decimal 計算 Summary

**手動交易的型別閉包改成 wire 字串 → `toTradeRecord` → Decimal domain:`createTrade` 以 `toWire` 逐欄投影送出字串,OrderTicket 的數量 / 價格 / 手續費改為文字輸入並以 Decimal 估算與比較,`LastFill`、交易分頁讀取、Overview 近期交易都改用 Decimal。05-18 留在 `oversellError` 的交接點已刪除**

## Performance

- **Duration:** 約 30 min
- **Completed:** 2026-11-11
- **Tasks:** 3/3
- **Files modified:** 15

## Accomplishments

- `apiTypes.ts` / `tradingApi.ts`:`TradeDto` 與 `CreateTradeRequest` 的金額欄改 string,對齊後端 `TradeDto.java` / `CreateTradeRequest.java` 的 `@DecimalString`。新增 domain `TradeRecord`、`CreateTradeInput`、`toTradeRecord`。http 與 mock 共用 `toCreateTradeRequest` 投影,所以同一份 input 每次產生的 body 逐位元相同(D-07 冪等比對)。
- `OrderTicket.vue`:三個金額欄位改 `type="text" inputmode="decimal"`,沿用既有 `data-testid`。`parseAmountInput` 只收一般記號、≤ 8 位小數。估算、mock cashAfter、`oversellError`、mock `sellPrecheckError` 都用 Decimal;成功畫面與 toast 顯示後端回傳的 `TradeRecord`。非註解行的 `numberToWire` 為 0。
- `portfolioRevision.ts`:`LastFill.qty` / `px` 為 Decimal,`notifyTradeCreated(trade: TradeRecord)`。`lastFill` 改 `shallowRef` + `shallowReadonly`,因為深層 `readonly` 會把 big.js 實例包成型別不相容的 proxy;直接賦值仍會被擋下。
- `portfolioApi.ts`:`listTrades` 回 `PaginatedResponse<TradeRecord>`;mock 排序改用 Decimal `cmp`;`live.lastFill` 經 `toMockLastFill`。
- `Overview.vue`:近期交易以 `TradeRecord` 顯示,股數用 `toWire`、總額用 `times`。

## Task Commits(前端 repo `/home/user/stock-web-v2-front-end`,分支 `claude/epic-curie-lj8alo`)

1. **Task 1(tracer)+ 型別閉包切換**:`0628a01` `feat(05-19): 手動交易請求與回應改為字串契約,OrderTicket 以 Decimal 估算`。除了 Task 1 的檔案,還包含 Task 2 / Task 3 的程式變更(portfolioRevision / portfolioApi / Overview.vue)、所有受影響測試的 fixture,以及 layout stub。原因見 Deviations #1。
2. **Task 2**:`c92bf11` `feat(05-19): LastFill 與交易分頁讀取改為 Decimal`。portfolioRevision / portfolioApi 的精度測試。
3. **Task 3**:`057c71d` `feat(05-19): Overview 近期交易改用 TradeRecord,交易 fixture 與 layout stub 改字串`。Overview 的精度案例與源碼守門。

完整 SHA:
- `0628a018278af6a310e6dd24164afd6e3a3d307e`
- `c92bf111eb05e3168b47bc11bb7f6ade45876eac`
- `057c71d39715985c194cba853576265a6466439e`

plan 開始前 HEAD 為 `43ad1338f456ac2920997b923d8f7ad3980c539c`;`git rev-list --count 43ad133..HEAD` = 3。這 3 個 commit 只存在於前端 repo 的歷史。

## Gate(最後一個 commit 的程式狀態)

- `npm run lint`:0 errors、38 warnings(與 baseline 相同)
- `npm test`:66 files / 1430 tests passed
- `VITE_DATA_MODE=api npm test`:66 files / 1430 tests passed
- `npm run build`(vue-tsc + vite):通過
- `npx playwright test e2e/tests/layout.spec.ts --trace off`:101 passed(在 0628a01 的 markup 與 stub 上執行;c92bf11 / 057c71d 只改測試檔)
- Tracer 閘門:Task 1 的 `<verify>`(tradingApi.test + OrderTicket.test)在擴展到 Task 2 前重跑並通過
- 驗收 grep:CreateTradeRequest `: number` 0、tradingApi `toTradeRecord` 5、tradingApi.test `"123456.12345678"` 1、OrderTicket 非註解 `numberToWire` 0、`v-model.number="qty|fee"` 0、三個 service 檔 `qty: number|px: number` 0、portfolioApi `PaginatedResponse<TradeDto>` 0、pages / components import `TradeDto` 0、apiTypes TradeDto `: number` 0

## Decisions Made

見 frontmatter `key-decisions`。

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] commit 邊界改為依型別閉包(與 05-16 ~ 05-18 相同)**
- **Found during:** Task 1
- **Issue:** `TradeDto` 一改字串,`portfolioRevision.notifyTradeCreated`、portfolioApi 的 mock `tradeDtoFrom` / 排序、`Overview.vue`,以及 Overview / Positions / Trades / portfolioRevision 測試的 fixture 都會在 vue-tsc 同時變紅。照 task 分 commit 的話,第一個 commit 不會 build。
- **Fix:** 型別切換與所有消費者的程式變更(含 fixture、stub)放在 `0628a01`,這個 commit 本身通過 lint / 1421 tests(兩種 mode)/ build / layout 101。Task 2 / Task 3 的 commit 只放精度測試與源碼守門。
- **Committed in:** `0628a01`、`c92bf11`、`057c71d`

**2. [計畫文字與既有契約衝突] 估算金額不含手續費**
- **Found during:** Task 1
- **Issue:** 計畫 must_haves / behavior 寫「估算金額(數量 × 價格 + 手續費)」,例子是 3 / 123.45 / 0.1 → 370.45。但 `OrderTicket.test.ts` 既有的 04.1-14 / 04.1-27 案例鎖住 review 與 ticket 的預估總額 = 10 × 945 = 9,450.00(手續費 20 不計入),後端 total 也定義為 quantity × price。mock `cashAfter` 依買賣方向另外處理手續費,估算若含手續費會重複計算。
- **Fix:** 維持既有語意,只把計算改成 Decimal。新測試驗證 3 × 123.45 = 370.35,以及手續費輸入 0.1 不影響估算。如果 Yuan 想改成含手續費,要一起改既有測試與 cashAfter 公式。
- **Committed in:** `0628a01`

**3. [Rule 3 - Blocking] frontmatter 以外的測試檔**
- `src/task4.test.ts`:兩個案例用 `input[type="number"]` 選取數量欄,改用 `[data-testid="ticket-qty"]`(行為斷言不變)。
- `src/pages/Overview.test.ts` 的 TradeDto fixture 改字串(在 frontmatter 內,一併說明)。

**4. [Rule 2] mock 預檢改由 adapter 提供 Decimal 持股數**
- **Issue:** 交接說明提到 `sellPrecheckError` 拿數量和 mock 持倉的 number 比較。數量變成 Decimal 後,元件要嘛把 Decimal 轉回 number,要嘛自己用 `numberToWire`,兩者都違反共同規約。
- **Fix:** `TradingLiveMockData.heldQuantityOf(symbol)` 在 mock adapter 內轉換,元件只做 Decimal 比較。
- **Committed in:** `0628a01`

---

**Total deviations:** 3 個自動修正(Rule 3 ×2、Rule 2 ×1),外加 1 項與計畫文字不同的語意決定(#2)

## Deferred / Out of Scope

- `vue-app/e2e/support/journeyApi.ts:51` 的 `apiBuy` 對真後端送 `quantity` / `price` / `fee` 為 JSON number;`e2e/tests/api-failure-states.spec.ts:135` 的 `BUY_SEED` 也是 number。後端 `@DecimalString` 是否還接受 number 輸入,本 plan 沒有驗證;這些是真後端 E2E,不在本 plan 的閘門內,留給 05-24 / 05-25。
- 05-18 交接提到的 `holding-event-journey.spec.ts:30/:160`,依約由 05-24 處理,這次沒動。

## Threat Flags

沒有新增安全面。T-05-29 已緩解:送出金額以 `toWire` 逐欄投影(一般記號,無科學記號);同 input 同 key 的 body 逐位元相同有測試;文字輸入經 `parseDecimalInput` 加上格式與位數檢查。

## Known Stubs

無。

## Next Phase Readiness(交接)

- **05-21(活動列)**:`Trades.test.ts` 的 `trade()` helper 目前以 `Number(tr.quantity)` 等填 `PortfolioActivityDto`,`amount` 用 Decimal `times` 算完再轉 number。活動列字串化時,改成直接傳字串,並以 `toWire(parseDecimal(q).times(parseDecimal(p)))` 算 amount。
- **05-22(AssetDto)**:`OrderTicket.priceInputText(price: number | null)` 是 05-19 → 05-22 的交接點,`latestPrice` 轉 Decimal 後改成 `toWire(asset.latestPrice)`(缺價維持 `'0'`)。價格欄已是文字輸入。
- **05-24(源碼守門)**:pages / components 的 `numberToWire` 目前為 0;`tradingApi.ts` 的 `toMockLastFill` / `heldQuantityOf` 與 `portfolioApi.tradeDtoFrom` 屬 mock adapter,是允許的用法。
- 可以 import 共用 mapper:`toTradeRecord`、`toMockLastFill`(皆在 `tradingApi.ts`)。

## Self-Check: PASSED

- FOUND:frontmatter key-files 的 15 個檔案皆已修改(`git diff --stat 43ad133..HEAD`:15 files)
- FOUND:`0628a01`、`c92bf11`、`057c71d`(前端 repo,都是 HEAD 的祖先;`git rev-list --count 43ad133..HEAD` = 3)
- 規劃 repo 只 commit 本 SUMMARY;STATE.md / ROADMAP.md 未動;沒有 push
