---
phase: 05-cross-repo-browser-flow-verification-contract-hardening
plan: 24
subsystem: frontend-api-contract
tags: [big.js, decimal, backtest, e2e, source-guard, wire-string, vitest, playwright]

requires:
  - phase: 05-23
    provides: "前端 Wave 2 交接點全部歸零(pages / components / composables 非測試行引用 numberToWire = 0)"
  - phase: 05-16
    provides: "decimal.ts(parseDecimal / parseNullableDecimal / parseDecimalInput / toWire / toChartNumber / numberToWire);DecimalFields;Wave 2 前端共同規約"
  - phase: 05-15
    provides: "後端 trading-portfolio-contract〈序列化格式〉:請求必須送字串,回應保留 scale"
provides:
  - "回測 wire 型別:BacktestRunRequest.initialCapital、BacktestRunDto.initialCapital / progress(string | null,後端 NUMERIC(6,5) nullable)、BacktestKpisDto 8 欄、EquityPointDto、MonthlyReturnDto.returnPct、DrawdownPointDto、BacktestTradeDto 4 欄 → string;int 欄維持 number"
  - "domain:BacktestRunInput、BacktestRunRecord、BacktestResult、BacktestKpis、EquityPoint、MonthlyReturn、DrawdownPoint、BacktestTrade"
  - "mapper:backtestApi.toBacktestRunRecord / toBacktestResult(http 與 mock 共用);createRun 收 Decimal,逐欄投影 + toWire"
  - "Backtest 頁:初始資金文字輸入(inputmode=decimal)+ parseDecimalInput;圖表軸標籤 toChartNumber"
  - "E2E:apiBuy(page, symbol, quantity: string, price: string, daysAgo?) 送 fee '0';decimalValue(wire, label) 先驗 JSON 字串再轉數值(只供斷言)"
  - "src/decimalBoundary.test.ts:pages / components / composables 禁用 numberToWire;props / state 宣告禁用 *Dto;含偵測規則自我驗證"
  - "Ops domain 別名 OpsAction / OpsJob / OpsLog(無 BigDecimal 欄,與 wire 同形)"
affects: [05-25]

actuals:
  tokens: 9947
  tasks: 3
  commits: 3
commits: 3
plan_head_before: "frontend 3b4345a8e65e586b36cfb21251697dac9ed05427"
plan_head_after: "frontend 5dc75118742f90ad359223b917bedbf6cb7a8259"

tech-stack:
  added: []
  patterns:
    - "回測與其他 domain 同一套:wire DTO 字串 → DecimalFields 派生 domain → 單一 mapper;mock adapter 先產 wire(number 計算經 numberToWire、scale 8)再過同一個 mapper"
    - "真後端 E2E 讀 BigDecimal:decimalValue 先斷言 typeof === 'string'(契約退回 number 時紅),再以 Number 比數值;scale 不確定的值不逐字比"
    - "源碼守門以 import.meta.glob ?raw 掃描,state / props 規則只看泛型參數與 *Props 型別本體,註解與 import 不算"

key-files:
  created:
    - /home/user/stock-web-v2-front-end/vue-app/src/decimalBoundary.test.ts
  modified:
    - /home/user/stock-web-v2-front-end/vue-app/src/services/apiTypes.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/backtestApi.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/backtestApi.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Backtest.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Backtest.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/task7.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/e2e/support/journeyApi.ts
    - /home/user/stock-web-v2-front-end/vue-app/e2e/tests/holding-event-journey.spec.ts
    - /home/user/stock-web-v2-front-end/vue-app/e2e/tests/cash-journey.spec.ts
    - /home/user/stock-web-v2-front-end/vue-app/e2e/tests/api-failure-states.spec.ts
    - /home/user/stock-web-v2-front-end/vue-app/e2e/tests/backtest.spec.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/components/OrderTicket.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Ops.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/services/opsApi.ts
    - /home/user/stock-web-v2-front-end/docs/api-contracts/mock-to-real-contract.md
    - /home/user/stock-web-v2-front-end/docs/LEARNINGS.md

key-decisions:
  - "已核對後端(後端為準):BacktestRunDto / BacktestResultDto 的 BigDecimal 欄全標 @DecimalString,int 欄為 drawdownDays / tradeCount / year / month / bars,與 plan 一致。progress 在 V3 migration 是 nullable NUMERIC(6,5),wire 型別因此定為 `progress?: string | null`,domain 為 `Decimal | null`"
  - "Backtest 頁的 KPI / 熱力圖 / 交易明細原本就是頁內以 seed 計算的 Simulated 資料(未呼叫 getResult),本 plan 不改這個既有設計;經 toChartNumber 的只有初始資金 → 權益曲線軸標籤"
  - "mock 權益曲線以 Decimal 初始資金 times(numberToWire(係數)),初始資金本身不經 number;其餘 mock KPI 以 number 算後 numberToWire 並 round(8)"
  - "真後端 spec 一律用 decimalValue(先驗字串、再比數值)而不逐字比較:summary 的 dividendIncome / cash 等是換算或彙總結果,scale 不確定;totalQuantity 雖是 NUMERIC(24,8),攤薄後的值仍以數值比較較穩"
  - "守門的 Dto 規則掃到 Ops.vue 三個 ref 與 OrderTicket 的 klineState;依 plan「修元件不放寬規則」處理:Ops 加 domain 別名、OrderTicket state 改存 closeSeries 後的 number[]"
  - "backtest.spec D1 加一個只觀察不攔截的 waitForRequest,斷言 POST /backtests/runs 的 initialCapital 是字串 '50000' —— 讓 05-25 的 CI 直接證明新 wire 格式"

patterns-established:
  - "decimalValue(wire, label):真後端 E2E 讀 BigDecimal 的唯一寫法"
  - "domain 別名:沒有 BigDecimal 欄的 DTO 也給 domain 名稱(`type OpsAction = OpsActionDto`),元件只拿 domain 名稱"

requirements-completed: [VER-02]

coverage:
  - id: T1
    description: "backtestApi:createRun(parseDecimal('100000.5')) → body initialCapital '100000.5'(字串);逐欄投影不帶多餘欄位;toBacktestRunRecord progress 字串 / null / 缺欄;toBacktestResult BigDecimal 欄逐字、int 欄 number;wire number(initialCapital / progress / sharpe / equity.strategy / trade.pnl)丟 TypeError;mock initialCapital '0' / '-1' / '0E-8' 回 BACKTEST_INVALID_INITIAL_CAPITAL field error;mock 大數初始資金不失真"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/services/backtestApi.test.ts#backtest wire → domain mapper(05-24 / D-05 / D-08)"
        status: pass
    human_judgment: false
  - id: T2
    description: "Backtest 頁:輸入框 type=text、inputmode=decimal、預設 100000;'0' / '-1' / 'abc' / '' / 空白 → 既有欄位錯誤且不送出;' 200000 ' → createRun 收 Decimal(toWire '200000')、軸標籤 $400K;'123456789012.12345678' 原樣送出;D6 防抖仍綠"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/pages/Backtest.test.ts#Backtest 初始資金文字輸入 → Decimal(05-24 / D-08)"
        status: pass
    human_judgment: false
  - id: T3
    description: "源碼守門:numberToWire 與 props / state 的 *Dto 在 pages / components / composables 為 0;自我驗證 numberToWire(1)、ref<HoldingDto>、ref<BlockState<KlineDto[]>>、defineProps<{ row: TradeDto }>、interface RowProps、computed<() => AssetDto> 各 1 條,import / 註解 / domain / 函式參數 0 條;掃描檔數 > 0 且不含測試檔"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/decimalBoundary.test.ts"
        status: pass
    human_judgment: false
  - id: T4
    description: "真後端 E2E 依字串契約送出與斷言:apiBuy / 現金種子送字串;holding-event-journey、cash-journey 的 summary / holdings 金額經 decimalValue;backtest D1 斷言 initialCapital 字串。本機只能 --list(149 tests)與 tsc 型別檢查,執行證據在 05-25 CI"
    requirement: VER-03
    verification:
      - kind: automated_ui
        ref: "npx playwright test --list(Total: 149 tests in 12 files)"
        status: pass
      - kind: automated_ui
        ref: "05-25 CI:cash-journey、holding-event-journey、J0、18 個 S5、backtest D1"
        status: pending
    human_judgment: false

duration: 20min
completed: 2026-11-11
status: complete
---

# Phase 5 Plan 24: 回測字串契約、E2E 字串金額與 decimal 邊界守門 Summary

**回測的 wire 型別改為字串並新增 Decimal domain 與 mapper,Backtest 頁初始資金改成文字輸入、以 Decimal 送出。真後端 E2E 的 helper 與 spec 改為送字串、讀字串(經 `decimalValue` 先驗型別再比數值)。新增 `decimalBoundary.test.ts`,把 pages / components / composables 不得引用 `numberToWire`、props / state 不得用 `*Dto` 兩條規則鎖在原始碼層級;守門掃到的 Ops 與 OrderTicket 違規已修正。前端 Wave 2 收尾完成**

## Performance

- **Duration:** 約 20 min
- **Started:** 2026-11-11T05:50:21Z
- **Completed:** 2026-11-11T06:09:45Z
- **Tasks:** 3/3
- **Files modified:** 17(plan frontmatter 的 13 個全部 + 4 個額外檔 task7.test / OrderTicket / Ops / opsApi,見 Deviations;`decimalBoundary.test.ts` 為新增)

## Accomplishments

- `apiTypes.ts`:回測 8 個 wire 型別依後端 `@DecimalString` 改 string(`BacktestKpisDto` 的 `: number` 只剩 `drawdownDays` / `tradeCount`);新增 `BacktestRunInput`、`BacktestRunRecord`、`BacktestResult` 與四個巢狀 domain,全部由 `DecimalFields` 派生。
- `backtestApi.ts`:`toBacktestRunRecord` / `toBacktestResult`(匯出,http 與 mock 共用);`createRun` 收 `BacktestRunInput`,以 `toBacktestRunRequest` 逐欄投影 + `toWire`;mock 驗證改 `parseDecimal(request.initialCapital).lte(ZERO)`,錯誤碼與 field 不變;mock 結果先產 wire 再 map。
- `Backtest.vue`:`<input type="text" inputmode="decimal" v-model="initialText" … data-testid="backtest-initial">`;`run()` 以 `parseDecimalInput` 驗證,請求帶 Decimal;`makeRunConfig` 的 number 只來自 `toChartNumber`(圖表軸標籤),已註明。
- E2E:`apiBuy` 簽名改字串、`fee: '0'`、JSDoc 註明契約;新增 `decimalValue`;holding-event-journey / cash-journey 的介面型別改 `string`,`toBe(0)`、`toBeCloseTo`、`toBeLessThan`、`totalQuantity 1050`、`avgCost 95.2381` 都經 `decimalValue`;api-failure-states 的 `BUY_SEED` 與現金種子改字串;backtest D1 觀察 POST body。
- `decimalBoundary.test.ts`:兩條規則 + 自我驗證(「偵測規則本身有效」)。
- 文件:`mock-to-real-contract.md` Backtest 一節加 D-05 說明,Create Run 請求 / 回應、Get Run、Get Result 範例的 BigDecimal 欄改字串(int 欄不動);`LEARNINGS.md` 加兩條(toString 科學記號 → `toFixed`;`Big.strict` 與字串 fixture),內容以 node 實測確認。

## Task Commits(前端 repo `/home/user/stock-web-v2-front-end`,分支 `claude/epic-curie-lj8alo`)

1. **Task 1(tracer)**:`c3e63ef` `feat(05-24): 回測金額改為字串契約,初始資金以 Decimal 送出`
2. **Task 2**:`be46754` `test(05-24): E2E 輔助函式與真後端 spec 改送 / 讀字串金額`
3. **Task 3**:`5dc7511` `test(05-24): decimal 邊界源碼守門,契約範例與 LEARNINGS 同步`

完整 SHA:
- `c3e63ef16451c7649447b2038e129107a3090406`
- `be4675423a57d8ce7bc2fb7dcfbbe6ef7ca9b58a`
- `5dc75118742f90ad359223b917bedbf6cb7a8259`

plan 開始前 HEAD 為 `3b4345a8e65e586b36cfb21251697dac9ed05427`;`git rev-list --count 3b4345a..HEAD` = 3。這三個 commit 只存在於前端 repo 的歷史。

## Gate

- 最終 commit(5dc7511):`npm run lint` 0 errors / 38 warnings(與 baseline 相同);`npm test` 67 files / 1516 tests passed;`VITE_DATA_MODE=api npm test` 67 files / 1516 tests passed;`npm run build`(vue-tsc + vite)通過;`npx playwright test e2e/tests/layout.spec.ts --trace off` 101 passed;`npx playwright test --list` Total: 149 tests in 12 files
- c3e63ef:lint 0 errors / 38 warnings、兩種 mode 各 1512 tests passed、build 通過、layout.spec 101 passed
- be46754:只動 e2e(不在 vue-tsc / vitest 範圍);`--list` 149;以臨時 tsconfig(scratchpad)對 `e2e/**/*.ts` 跑 `tsc --noEmit`,本 plan 動到的 5 個檔 0 錯誤(剩下的錯誤只在未動的 `e2e/support/fixtures.ts`:缺 `@types/node` 與 `Playwright` 型別匯入,既有狀況)
- Tracer 閘門:Task 1 的 `<verify>`(backtestApi.test + Backtest.test,23 tests)通過後才進 Task 2
- 驗收 grep:`BacktestKpisDto` 區塊 `: number` = 2;`v-model.number="initial"` = 0;`apiBuy(` 帶數字參數 = 0;`quantity: string` in journeyApi = 1;`amount: number` in cash-journey + holding-event-journey = 0;pages / components / composables 非測試檔 `numberToWire` = 0;`偵測規則` = 1;LEARNINGS `toFixed` ≥ 1
- 禁止事項:API 路徑上的回測金額沒有經過 JS number。Backtest.vue 剩下的 number 運算是頁內 Simulated 圖表 / KPI(seed 計算,既有)與 `toChartNumber` 後的軸標籤

## Decisions Made

見 frontmatter `key-decisions`。

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] `task7.test.ts` 以 `input[type="number"]` 找初始資金輸入框**
- **Found during:** Task 1(全套測試)
- **Issue:** 輸入框改成 text 後,`Backtest rejects invalid initial capital` 找不到元素而紅。
- **Fix:** selector 改 `[data-testid="backtest-initial"]`,斷言不變。
- **Files modified:** `vue-app/src/task7.test.ts`(不在 plan 檔案清單)
- **Commit:** `c3e63ef`

**2. [Rule 2 - 守門要求] 守門掃到既有違規,依 plan「修正元件而非放寬規則」修正**
- **Found during:** Task 3
- **Issue:** `Ops.vue` 的 `ref<OpsActionDto[]>` / `ref<OpsLogDto[]>` / `ref<OpsJobDto | null>`,以及 `OrderTicket.vue` 的 `ref<BlockState<KlineDto[]>>`。
- **Fix:** `apiTypes.ts` 加 `OpsAction` / `OpsJob` / `OpsLog` domain 別名(三個 DTO 都沒有 BigDecimal 欄,註解說明日後加欄時改 `DecimalFields`),`opsApi.ts` 介面與 `Ops.vue` 改用 domain 名稱;`OrderTicket.vue` 的 `klineState` 改存 `closeSeries(klines)` 的 `number[]`,`closeSeries` 仍是唯一轉換點,行為不變(OrderTicket.test 全綠)。
- **Files modified:** `vue-app/src/services/apiTypes.ts`、`vue-app/src/services/opsApi.ts`、`vue-app/src/pages/Ops.vue`、`vue-app/src/components/OrderTicket.vue`(後三個不在 plan 檔案清單)
- **Commit:** `5dc7511`

### 計畫文字與既有程式不同

- 守門規則 2 的範圍依 plan behavior 解讀為「props / ref 等 state 宣告中的 `*Dto`」,不是任何 `\w+Dto` 字樣:`useAiAccessSettings.ts` 的 mapper 函式參數(`endpointFromDto(endpoint: McpEndpointDto)`)、`import type`、註解(Positions / Trades / OrderTicket 等提到 `HoldingDto` 的註解)都不算違規。例外清單維持空。
- 介面中的行號(`apiTypes.ts:296-381`、`Backtest.vue:41` 等)與實際位置略有差距,依名稱定位。
- `backtest.spec.ts` 不依賴 number 輸入特性(本來就用 `fill`),沒有要調整的地方;改為在 D1 加上請求 body 的字串斷言。
- Backtest 頁的軸標籤是 `v-for="(g, i) in 5"`(g 從 1 起),第一格是 initial × 2.0,測試以此為準。

---

**Total deviations:** 2 個自動修正(Rule 3 測試 selector、Rule 2 守門違規)

## 只有 CI 能證明的真後端 E2E 變更(05-25)

本機沒有 Docker,以下只做了 `--list` 與型別檢查,依後端契約(`ai-docs/trading-portfolio-contract.md`〈序列化格式〉、`BacktestRunDto.java` / `BacktestResultDto.java`)寫成:

1. `e2e/support/journeyApi.ts`:`apiBuy` 送 `quantity` / `price` 字串、`fee: '0'`;新增 `decimalValue`(非字串即 fail)。
2. `e2e/tests/holding-event-journey.spec.ts`:J1 `dividendIncome` / `dividendTaxWithheld` / USD 現金、J2 `totalQuantity`(數值 1050)/ `avgCost`(≈95.2381)都經 `decimalValue`;`apiBuy` 呼叫改字串。
3. `e2e/tests/cash-journey.spec.ts`:C2 USD 現金 = 0、C3 USD 現金 < 0 與 `totalEquity ≈ totalMarketValue + cash` 經 `decimalValue`。
4. `e2e/tests/api-failure-states.spec.ts`:S5 `BUY_SEED` `{ quantity: '3', price: '123.45' }`、現金種子 `amount: '1234.5'`。
5. `e2e/tests/backtest.spec.ts`:D1 `waitForRequest` 斷言 POST `/api/v1/backtests/runs` 的 `initialCapital === '50000'`。

風險點:如果後端某個 summary 欄位在空帳號時回 `null` 而不是 `"0.00000000"`,`decimalValue` 會紅(J1 的 `before.dividendIncome`、C2 的 USD 現金列不存在時已用 `?? 0` 分支處理)。前端 `PortfolioSummaryDto.dividendIncome` 型別是非 null `string`,與後端一致。

## Deferred / Out of Scope

- 05-19 的 OrderTicket 預估金額不含手續費,等待 Yuan 裁決;本 plan 未變更行為。
- Backtest 頁仍以 seed 在頁內計算 KPI / 圖表(Simulated 標示既有),未改成消費 `getResult`;不在本 plan 範圍。
- `e2e/support/fixtures.ts` 在嚴格 tsc 下有既有型別錯誤(缺 `@types/node`、`Playwright` 型別匯入),Playwright 自己的轉譯不受影響;未處理。

## Threat Flags

沒有新增安全面。T-05-32 已緩解:mock-to-real-contract 的 Backtest 範例已改字串並註明 D-05;`decimalBoundary.test.ts` 防止元件回到 wire 型別或 `numberToWire`。

## Known Stubs

無新增 stub。

## Next Phase Readiness(交接)

- 前端 Wave 2 domain 全部走字串契約 + Decimal;`decimalBoundary.test.ts` 是 05-16 ~ 05-23 交接點的最終回歸守門。日後新增元件若需要 wire 型別,先在 service 加 domain 型別(沒有 BigDecimal 欄就用別名),不要把 `*Dto` 放進 props / state。
- 05-25 需要在兩個 repo 的 CI 確認:cash-journey、holding-event-journey、J0、18 個 S5、backtest D1 全部 passed(上方清單)。
- 非測試檔中引用 `numberToWire` 的只剩:`decimal.ts`(定義)、services 的 mock adapter(marketApi / watchlistApi / portfolioApi / tradingApi / holdingEventApi / quoteStream mock / backtestApi mock)、`testing/fakeQuoteStream.ts`、`types.ts` 一行註解。
- WS 壞 tick 是丟棄、REST mapper 是丟 TypeError,守門測試不涉及這兩者的差異。

## Self-Check: PASSED

- FOUND:frontmatter key-files 的 17 個檔案(`git diff --name-only 3b4345a..HEAD` = 17 files,含新增的 `decimalBoundary.test.ts`)
- FOUND:`c3e63ef`、`be46754`、`5dc7511`(前端 repo,都是 HEAD 的祖先;`git rev-list --count 3b4345a..HEAD` = 3)
- 規劃 repo 只 commit 本 SUMMARY;STATE.md / ROADMAP.md 未動;沒有 push
