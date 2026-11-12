---
phase: 05-cross-repo-browser-flow-verification-contract-hardening
plan: 17
subsystem: frontend-api-contract
tags: [big.js, decimal, portfolio-summary, wire-string, vitest, playwright]

requires:
  - phase: 05-16
    provides: "decimal.ts(parseDecimal / parseNullableDecimal / toWire / toChartNumber / numberToWire / ZERO / ROUND_HALF_UP);moneyFormat 接受 Decimal;CashEntryDialog overBalance 交接點"
  - phase: 05-14
    provides: "後端 PortfolioSummaryDto / AllocationSliceDto / SectorAllocationDto 的 BigDecimal 欄位以 @DecimalString 輸出 JSON 字串;int 計數欄位維持 number"
provides:
  - "apiTypes:PortfolioSummaryDto(12 個 BigDecimal 欄位)、CashBalanceDto.amount、AllocationSliceDto.value / weight、SectorAllocationDto.value / weight / unrealizedPnl 改為 string(nullable 為 string | null);holdingCount / fxExcludedCount / dayPnlExcludedCount / SectorAllocationDto.holdingCount 維持 number"
  - "apiTypes:共用 mapped type DecimalFields<T, K>(wire string → Decimal,保留 nullable);domain PortfolioSummary / CashBalance / AllocationSlice / SectorAllocation"
  - "portfolioApi:toPortfolioSummary / toCashBalance / toAllocationSlice / toSectorAllocation(export),http 與 mock 共用;getSummary 回 domain;mock 以 numberToWire 組 wire 再走同一 mapper"
  - "Overview / Positions / Cash / cashBalanceView / CashEntryDialog 全部以 Decimal 判斷與計算,只有 moneyFormat 與 toChartNumber(Donut 切片、權重與產業 bar 寬)轉出"
  - "Positions.holdingWeight:Decimal div 後 round(8, HALF_UP);分子 marketValueInBase 是唯一的交接點 parseDecimal(numberToWire(...)),05-18 刪除"
  - "CashEntryDialog:balances 改為 CashBalance[],05-16 的 numberToWire 交接點已刪除"
  - "layout e2e stub(portfolioStubs.ts)summary / cashBalances / allocation / sectorAllocation 改字串"
affects: [05-18, 05-19, 05-24]

actuals:
  tokens: 28700
  tasks: 3
  commits: 4
commits: 4
plan_head_before: "frontend abf255464c1af894090073ad5f5672f00bc1775b"
plan_head_after: "frontend 42c225adbc21ac13b8c5965c6a8137fa822657b8"

tech-stack:
  added: []
  patterns:
    - "domain 型別一律由 wire DTO 以 DecimalFields<Dto, 'a' | 'b'> 派生;巢狀清單以 Omit + 交集換成 domain 陣列"
    - "mapper 在邊界補舊形狀缺漏的清單欄位(?? []),頁面不再各自寫容錯;純量金額欄位缺漏或仍為 number 一律在 parseDecimal 丟 TypeError"
    - "測試以 plainSummary(domain) 投影回 wire 形狀再 toEqual;注意 big.js 正規化尾零(\"1000.00000000\" → \"1000\")"
    - "元件比較 Decimal 一律 eq / lt / gt / gte / cmp;排序權重用 cmp,null 排最後"

key-files:
  created: []
  modified:
    - /home/user/stock-web-v2-front-end/vue-app/src/services/apiTypes.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/portfolioApi.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/portfolioApi.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Overview.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Overview.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/App.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Positions.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Positions.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Cash.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Cash.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/cashBalanceView.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/cashBalanceView.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/components/CashEntryDialog.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/components/CashEntryDialog.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/e2e/support/portfolioStubs.ts
    - /home/user/stock-web-v2-front-end/docs/LEARNINGS.md

key-decisions:
  - "commit 邊界依型別閉包而不是依 task 檔案清單:getSummary 回傳型別一改,Overview / Positions / Cash / cashBalanceView / CashEntryDialog 在 vue-tsc 與執行期(strict Big 的 valueOf 會丟錯、mapper 拒收 number fixture)同時壞,沒有任何子集可以單獨 build。為了讓每個 commit 都 build 且全測試綠,型別切換與所有消費者在 c01af6e 一次完成;Task 2 / Task 3 的精度測試分別放在 67fe32c / 0c665a5"
  - "mapper 對缺漏的清單欄位(cashBalances / allocation / sectorAllocation / unconvertedCurrencies)補空清單,保住 Overview「舊形狀沒有 allocation」與 Positions T2.8「缺 sectorAllocation」兩個既有行為;頁面內的 ?? [] / ?? 0 容錯因此移除"
  - "Positions roi 卡新增區域 helper fmtRoiPct(Decimal):沿用既有 fmtPct 的輸出格式(非負帶 +、2 位、無千分位),捨入改 HALF_UP,渲染文字與既有測試相同;不改用 fmtRatioPct 是因為它對 0 不帶 + 號"
  - "Positions 權重排序拆出 Decimal 分支(cmp,null 排最後),不讓 Decimal 進原本 any 型別的 number 比較"
  - "權重 bar 寬 / 產業 bar 寬 / Donut 切片是視覺近似值,經 toChartNumber 轉出;百分比文字一律走 fmtWeightPct(Decimal)"

patterns-established:
  - "型別閉包交接點(05-17 → 05-18):Positions.holdingWeight 以 parseDecimal(numberToWire(holding.marketValueInBase)) 轉分子,JSDoc 註明 05-18 刪除;非註解行 numberToWire( 計數 = 1"

requirements-completed: [VER-02]

coverage:
  - id: S1
    description: "portfolioApi http getSummary:wire 字串 → Decimal,123456789012.12345678 / 9007199254740993.01 / 1e-8 往返不失真;dayPricePnl null → null;巢狀 cashBalances / allocation / sectorAllocation 皆為 Decimal;金額為 number 時丟 TypeError;mock 回 domain 且數值等於原計算"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/services/portfolioApi.test.ts"
        status: pass
    human_judgment: false
  - id: S2
    description: "Overview KPI 與資產配置渲染文字不變;超過 2^53 的總資產與配置金額原值顯示"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/pages/Overview.test.ts#KPI 與資產配置以 Decimal 呈現超過 double 精度的金額"
        status: pass
    human_judgment: false
  - id: S3
    description: "Positions 權重 Decimal 計算(0 分母 → —、round(8)、HALF_UP 0.15% → 0.2%)、標題列總市值超過 2^53 原值顯示、權重排序 null 排最後"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/pages/Positions.test.ts"
        status: pass
    human_judgment: false
  - id: S4
    description: "cashBalanceView / Cash 頁 / CashEntryDialog 以 Decimal 判斷正負與超額;05-16 交接點已刪除(源碼守門)"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/cashBalanceView.test.ts、src/pages/Cash.test.ts、src/components/CashEntryDialog.test.ts"
        status: pass
    human_judgment: false
  - id: S5
    description: "layout.spec 以字串 summary stub 通過"
    requirement: VER-02
    verification:
      - kind: automated_ui
        ref: "npx playwright test e2e/tests/layout.spec.ts --trace off(101 passed)"
        status: pass
    human_judgment: false

duration: 25min
completed: 2026-11-11
status: complete
---

# Phase 5 Plan 17: portfolio summary 字串契約與 Decimal 計算 Summary

**portfolio summary 型別閉包(summary、現金餘額、資產配置、產業配置)改成 wire 字串 → `toPortfolioSummary` → Decimal domain;Overview、Positions、Cash、cashBalanceView、CashEntryDialog 都用 Decimal 判斷與計算,只在 moneyFormat 和圖表 / bar 寬轉出。超過 2^53 的金額原值顯示,權重以 HALF_UP 捨入**

## Performance

- **Duration:** 約 25 min
- **Started:** 2026-11-11T03:30:00Z
- **Completed:** 2026-11-11T03:54:18Z
- **Tasks:** 3/3
- **Files modified:** 16(程式 / 測試 15、LEARNINGS 1)

## Accomplishments

- `apiTypes.ts`:summary 閉包的 BigDecimal 欄位改為 `string`,整數計數維持 `number`(與後端 05-14 的 `@DecimalString` 標註逐欄對齊)。新增 `DecimalFields<T, K>`,05-18 之後的 domain 型別可以沿用。
- `portfolioApi.ts`:4 個 mapper 由 http 與 mock 共用。mock 先用 number 計算,再以 `numberToWire` 組成 wire DTO,最後走同一個 mapper,兩種 mode 都會經過字串 → Decimal 這條路。
- 頁面與元件:比較一律用 `eq` / `lt` / `gt` / `gte` / `cmp`。`Number.isFinite(summary.*)` 和對 summary 金額的算術運算都已移除。權重 = `marketValueInBase ÷ totalMarketValue`,以 Decimal 計算後 `round(8, HALF_UP)`。
- 05-16 在 CashEntryDialog 留下的 `numberToWire` 交接點已刪除。新的交接點只剩 Positions `holdingWeight` 一處(05-18 移除)。

## Task Commits(前端 repo `/home/user/stock-web-v2-front-end`,分支 `claude/epic-curie-lj8alo`)

1. **Task 1(tracer)+ 型別閉包切換**:`c01af6e` `feat(05-17): portfolio summary 改為字串契約並以 Decimal 對應`。內容包含 apiTypes、portfolioApi(+test)、Overview(+test)、App.test,以及 Task 2 / Task 3 的程式變更與 fixture 字串化(原因見 Deviations #1)。
2. **Task 2(Positions 精度證據)**:`67fe32c` `test(05-17): Positions 權重與總市值的 Decimal 精度案例`
3. **Task 3(現金餘額消費者精度證據)**:`0c665a5` `test(05-17): 現金餘額消費者的 Decimal 精度案例`
4. **LEARNINGS**:`42c225a` `docs(05-17): LEARNINGS 記錄 big.js 尾零正規化`

完整 SHA:
- `c01af6ea89f8b992cb5b51d7f66535af2abc72e0`
- `67fe32cc9177ae2fa3693285a60cadff893c9783`
- `0c665a5f40f969d4754f3b3cdf4c3fa1e6ae45c7`
- `42c225adbc21ac13b8c5965c6a8137fa822657b8`

plan 開始前 HEAD 為 `abf255464c1af894090073ad5f5672f00bc1775b`。這 4 個 commit 只在前端 repo 的歷史裡,不在本規劃 repo。

**Plan metadata:** 本 SUMMARY 未 commit,由 orchestrator 提交。

## Files Created/Modified

- `vue-app/src/services/apiTypes.ts`:wire 字串、`DecimalFields`、4 個 domain 型別
- `vue-app/src/services/portfolioApi.ts`(+ test):4 個 mapper、mock 改為先組 wire、`plainSummary` 測試投影、精度 / 漂移 / 舊形狀案例
- `vue-app/src/pages/Overview.vue`(+ test):domain 型別、Decimal 判斷、Donut 用 `toChartNumber`;fixture 字串化,補 2^53 案例
- `vue-app/src/App.test.ts`:summary fixture 改為完整字串形狀
- `vue-app/src/pages/Positions.vue`(+ test):標題列 null 判斷、KPI Decimal、`fmtRoiPct`、`holdingWeight` 交接點、權重 Decimal 排序、產業 bar `toChartNumber`;只把 summary fixture 字串化(HoldingDto fixture 不動)
- `vue-app/src/pages/Cash.vue`(+ test):`PortfolioSummary` 型別;fixture 字串化,補 2^53 / 極小負值案例
- `vue-app/src/cashBalanceView.ts`(+ test):參數改 `CashBalance[]`,用 Decimal 判斷
- `vue-app/src/components/CashEntryDialog.vue`(+ test):`balances: CashBalance[]`,刪除 05-16 交接點
- `vue-app/e2e/support/portfolioStubs.ts`:summary 閉包 stub 改字串
- `docs/LEARNINGS.md`:big.js 尾零正規化

## Decisions Made

見 frontmatter `key-decisions`。

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] commit 邊界改為依型別閉包(與 05-16 同一處理方式)**
- **Found during:** Task 1
- **Issue:** 計畫把 Overview 放在 Task 1、Positions 放在 Task 2、Cash / cashBalanceView / CashEntryDialog 放在 Task 3,並寫明「plan 結束時 build 綠」。但 `getSummary()` 的回傳型別一改,所有消費者會同時壞:vue-tsc 型別不符;執行期 strict Big 的 `valueOf` 遇到 `<` / `!==` 會丟錯;mapper 也會拒收 number fixture。Overview 本身就把 `summary.cashBalances` 交給 cashBalanceView 和 CashEntryDialog。按檔案分 commit 的話,前兩個 commit 會 build 紅、測試紅,違反 orchestrator「每個 commit 都要 build」的要求。
- **Fix:** 型別切換和全部消費者的程式變更(含 fixture 字串化與 layout stub)放在 `c01af6e`,這個 commit 本身通過 lint / 1378 tests / build。Task 2 與 Task 3 的精度測試分別以 `67fe32c`、`0c665a5` 提交,每個 commit 都 build 綠、全測試綠。
- **Files modified:** 15 個檔案,見 Files 段
- **Committed in:** `c01af6e`、`67fe32c`、`0c665a5`

**2. [Rule 1 - Bug] mapper 對舊形狀清單欄位的容錯**
- **Found during:** Task 1(Overview「舊形狀 summary(沒有 allocation 欄位)」測試紅)
- **Issue:** 原本由頁面處理 `s.allocation ?? []`。mapper 直接 `.map` 時,缺清單欄位會丟錯,整個 summary 進入錯誤態。
- **Fix:** `toPortfolioSummary` 對 4 個清單欄位補 `?? []`,並補 mapper 單元測試;頁面內的容錯移除。
- **Committed in:** `c01af6e`

**3. [Rule 1 - Bug] Positions 權重排序**
- **Found during:** 改 holdingWeight 回傳 Decimal 後,「權重表頭排序」測試紅
- **Issue:** 排序原本用 `Number.isFinite` 和 `va - vb`,Decimal 會被當成缺值。
- **Fix:** 權重排序拆出 Decimal `cmp` 分支,null 排最後。
- **Committed in:** `c01af6e`

**4. [計畫敘述修正] 「toWire 往返逐字」只對沒有尾零的值成立**
- big.js 會正規化尾零:`"0.00000000"` → `"0"`、`"1000.00000000"` → `"1000"`。測試改為「數值 eq 0、toWire 為 `"0"`」,沒有尾零的值(`123456789012.12345678`、`9007199254740993.01`、`0.00000001`)維持逐字斷言。已寫入 LEARNINGS(`42c225a`)。

**5. [計畫外檔案] `docs/LEARNINGS.md`**:依 AGENTS.md「踩坑一行進 LEARNINGS」新增一列。

---

**Total deviations:** 3 個自動修正(Rule 3 ×1、Rule 1 ×2),加 1 處計畫敘述修正與 1 個計畫外文件檔
**Impact on plan:** 功能範圍不變。frontmatter 列出的檔案都已修改,另外只多了 LEARNINGS.md。`api-adapter-wiring.test.ts` 不需要修改。

## Issues Encountered

- 第一次用 regex 把 Positions.test 的金額轉字串時,必須限定在 summary fixture 的區塊內。`HoldingDto` 也有 `unrealizedPnl` / `roi` / `dayPricePnl` 同名欄位,05-18 才會轉。最後只改 `TRUTH_SUMMARY`、`summaryWith` 本體、`summaryWith(...)` 呼叫行、`SECTOR_SUMMARY` 與 `sectorAllocation: [{` 行,並以 diff 確認持倉 fixture 沒有變動。
- CashEntryDialog 的金額上限是 1e12,所以「超過 double 有效位數的餘額」案例改用 `999999999999.99999998`:它與 `...99` 經 number 都會變成 1e12。

## Verification(最終 HEAD `42c225a`;程式碼與 `0c665a5` 相同)

- `npm run lint`:0 errors(40 warnings,都是既有的 `no-explicit-any`,與 05-16 相同,沒有新增)
- `npm test`:66 files / 1384 tests passed
- `VITE_DATA_MODE=api npm test`:66 files / 1384 tests passed
- `npm run build`(vue-tsc + vite build):通過
- `npx playwright test e2e/tests/layout.spec.ts --trace off`:101 passed
- 中間 commit:`c01af6e` 通過 lint / 1378 tests / build;`67fe32c` 通過 Positions 80 tests + vue-tsc
- Acceptance greps:
  - `export function toPortfolioSummary` = 1
  - PortfolioSummaryDto 中 `(totalMarketValue|cash|totalEquity|roi): number` = 0
  - CashBalanceDto 中 `amount: number` = 0
  - Positions 中 `Number.isFinite(...totalMarketValue)` = 0
  - Positions 中 `.(marketValueInBase|totalMarketValue) [-+*/] ` = 0
  - Positions 非註解行 `numberToWire(` = 1
  - Positions 中 `toChartNumber` = 4
  - CashEntryDialog 非註解行 `numberToWire` = 0
  - pages / components / cashBalanceView import `CashBalanceDto|PortfolioSummaryDto` = 0
- 禁止事項(judgment):頁面 / 元件對 summary domain 金額沒有 `Number()` 或算術運算子。Overview 唯一的 `Number`-類轉換是 Donut 用的 `toChartNumber`;Positions 剩下的 number 算術都是 HoldingDto 欄位(05-18 範圍)和 mock 路徑。

## Next Phase Readiness(05-18 交接)

- **要刪的交接點**:`Positions.vue` `holdingWeight` 中的 `parseDecimal(numberToWire(holding.marketValueInBase))`。HoldingDto 轉 Decimal 後,直接以 `holding.marketValueInBase.div(s.totalMarketValue).round(8, ROUND_HALF_UP)` 計算。刪除後 `grep -v 註解 | grep -c "numberToWire(" src/pages/Positions.vue` 應為 0,`numberToWire` 也要從 Positions 的 import 移除。
- `holdingWeight` 已回傳 `Decimal | null`,權重排序已用 `cmp`,05-18 不需要再改。
- Positions 其餘持倉運算(`apiTopMovers`、`hasBasePnl`、`dayPnlClass`、表格排序的 `va - vb`、`x.roi * 100`)仍是 number,屬 05-18。
- 可沿用 `DecimalFields<HoldingDto, ...>` 派生 `Holding` domain;mapper 放在 portfolioApi(`toHolding`),mock `holdingFrom` 改為先組 wire。
- Positions.test 的 `holding()` / `TRUTH_HOLDING` fixture 仍是 number;轉字串時注意 summary fixture 已是字串,斷言 `fmtPct(0.0117 * 100)` 等需要一起調整。
- 測試比較 domain 時注意尾零正規化(LEARNINGS 2026-11-11)。

## Self-Check: PASSED

- FOUND: vue-app/src/services/apiTypes.ts、vue-app/src/services/portfolioApi.ts、vue-app/e2e/support/portfolioStubs.ts(皆已修改)
- FOUND: c01af6e、67fe32c、0c665a5、42c225a(前端 repo,都是 HEAD 的祖先)
- 規劃 repo 沒有任何 git 寫入;STATE.md / ROADMAP.md 未動
