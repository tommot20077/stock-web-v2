---
phase: 05-cross-repo-browser-flow-verification-contract-hardening
plan: 16
subsystem: frontend-api-contract
tags: [big.js, decimal, money-format, cash-ledger, wire-string, vitest, playwright]

requires:
  - phase: 05-12
    provides: "D-25 Wave 1 閘門已過;Wave 2 金額字串化硬切(D-05 / D-07)"
provides:
  - "big.js 7.0.1(dependency)與 @types/big.js 7.0.0(devDependency),精確版本鎖定,package-lock 已提交"
  - "vue-app/src/services/decimal.ts:strict Big clone(strict=true、DP=20、RM=roundHalfUp);匯出 Decimal、parseDecimal、parseNullableDecimal、toWire(toFixed,一般記號)、toChartNumber、parseDecimalInput、numberToWire、isDecimal、ZERO,外加 ROUND_HALF_UP"
  - "moneyFormat 全部 fmt* 接受 Decimal | number | null | undefined;Decimal 走 toFixed(dp, HALF_UP) → Intl.NumberFormat 千分位(私有 fixedString),× 100 在 Decimal 內做"
  - "cashApi:CashEntryDto / CreateCashEntryRequest.amount 改 string;domain CashEntry / CreateCashEntryInput(amount: Decimal);toCashEntry(wire) mapper 與 toCreateCashEntryRequest 投影由 http 與 mock 共用"
  - "CashEntryDialog:parseDecimalInput 解析、Decimal 驗證(> 0、≤ 8 位、≤ 1e12)、沖銷預填 toWire;overBalance 交接點 parseDecimal(numberToWire(balance)),交給 05-17 刪除"
  - "layout e2e 現金 stub(portfolioStubs.ts CASH_ENTRIES 與 POST 回應)金額改字串"
affects: [05-17, 05-18, 05-19, 05-20, 05-21, 05-22, 05-23, 05-24, 05-25]

actuals:
  tokens: 19500
  tasks: 2
  commits: 2
commits: 2
plan_head_before: "frontend 9e920b8fad7f1f8459f8fbcd313b43b30796a7bc"
plan_head_after: "frontend abf255464c1af894090073ad5f5672f00bc1775b"

tech-stack:
  added: ["big.js@7.0.1", "@types/big.js@7.0.0"]
  patterns:
    - "wire 型別(*Dto / Create*Request)的 BigDecimal 欄位為 string;domain 型別以 Omit<Dto, 'amount'> & { amount: Decimal } 派生;mapper to<Domain>(wire) 放在 *Api.ts,http 與 mock 共用"
    - "domain 輸入 → wire 的逐欄投影抽成單一函式(toCreateCashEntryRequest),mock adapter 先投影成 wire 再驗證 / 比對 / 儲存,與 http 同一形狀"
    - "mock 帳本存 wire 形狀;回傳前先 structuredClone wire 再走 mapper(structuredClone 會丟掉 big.js 實例的 prototype,順序不能反)"
    - "測試比較 domain 物件時以 plain(entry) = { ...entry, amount: toWire(entry.amount) } 轉回 wire 形狀再 toEqual,不依賴 big.js 內部欄位"
    - "strict Decimal 下常數一律以字串建構(parseDecimal('100')),Decimal 方法收到 JS number 會丟錯"

key-files:
  created:
    - /home/user/stock-web-v2-front-end/vue-app/src/services/decimal.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/decimal.test.ts
  modified:
    - /home/user/stock-web-v2-front-end/vue-app/package.json
    - /home/user/stock-web-v2-front-end/vue-app/package-lock.json
    - /home/user/stock-web-v2-front-end/vue-app/tsconfig.json
    - /home/user/stock-web-v2-front-end/vue-app/src/moneyFormat.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/moneyFormat.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/cashApi.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/cashApi.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Cash.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Cash.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/components/CashEntryDialog.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/components/CashEntryDialog.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/e2e/support/portfolioStubs.ts

key-decisions:
  - "tsconfig lib 加 \"ES2023.Intl\";但 Intl.NumberFormat.format 的型別是 StringNumericLiteral(`${number}`),toFixed() 回傳 string 仍不相容 —— 在 moneyFormat.ts 單一 helper(formatDecimalString)以 `as Intl.StringNumericLiteral` 轉型並註明理由(RESEARCH A3 的後備方案)"
  - "decimal.ts 另匯出 ROUND_HALF_UP(= Big.roundHalfUp),讓 moneyFormat 與後續 plan 不必各自 import big.js;big.js 只在 decimal.ts 被 import"
  - "parseDecimal 對非字串先丟 TypeError(訊息指明 wire 契約);toWire 對非 Decimal 也丟 TypeError —— `(5).toFixed()` 同樣回字串,型別擋不住 as / any 混入的 number"
  - "CashEntryDialog 小數位數檢查採 value.round(8).eq(value)(有效小數位 ≤ 8,尾零不算,與 BigDecimal compareTo 一致);保留 AMOUNT_PATTERN 擋 big.js 也接受的 1e5 / -1 輸入"
  - "mock 冪等比對與沖銷鏡像比對都以 Decimal eq(compareTo 語意,'500' == '500.00000000'),對齊後端 CashEntryPayloadMatcher"
  - "Cash.vue amountText 的正負號仍由 type 決定(後端保證 amount > 0),amount 直接以 Decimal 交給 fmtMoney"
  - "fixedString 對 Decimal 負數的帶號規則與 fmtNum 相同(-0.001 於 2 位為 -0.00),讓 Decimal 與等值 number 輸出一致"

patterns-established:
  - "型別閉包交接點:CashEntryDialog.overBalance 以 parseDecimal(numberToWire(balance.amount)) 把 summary 的 number 餘額轉成 Decimal 比較;05-17 把 balances 轉 Decimal 時刪除並以 grep 驗證"

requirements-completed: [VER-02]

coverage:
  - id: D1
    description: "decimal.ts 往返精度 / 冪等 / strict / 0E-8 / 乘法精確"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/services/decimal.test.ts"
        status: pass
    human_judgment: false
  - id: D2
    description: "moneyFormat 接受 Decimal,123456789012.12345678 八位小數完整顯示、HALF_UP、與等值 number 同輸出"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/moneyFormat.test.ts#Decimal 輸入"
        status: pass
    human_judgment: false
  - id: D3
    description: "cashApi wire 字串 → Decimal mapper(http 與 mock 共用),wire 為 number 時大聲失敗;寫入以 toWire 送出字串"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/services/cashApi.test.ts"
        status: pass
    human_judgment: false
  - id: D4
    description: "Cash 頁以 Decimal 顯示超過 2^53 的金額(9,007,199,254,740,993.01 USD)"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/pages/Cash.test.ts#renders wire amount strings beyond double precision exactly"
        status: pass
    human_judgment: false
  - id: D5
    description: "CashEntryDialog 輸入 → Decimal → 請求字串;同 key 重試 payload 逐位元相同;沖銷極小金額不出現科學記號"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/components/CashEntryDialog.test.ts#金額以 Decimal 送出字串"
        status: pass
    human_judgment: false
  - id: D6
    description: "layout.spec 以字串現金 stub 本機通過"
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

# Phase 5 Plan 16: decimal 轉換層與現金 slice Summary

**引入 big.js 7.0.1 的 strict decimal 模組,現金分錄的讀寫兩個方向改為「wire 字串 ↔ Decimal」,全程不經 JS number:超過 2^53 的金額照樣精確顯示,`123456789012.12345678` 原樣送出**

## Performance

- **Duration:** 約 20 min
- **Started:** 2026-11-11T03:10:00Z
- **Completed:** 2026-11-11T03:30:08Z
- **Tasks:** 2/2
- **Files modified:** 14(新增 2、修改 12)

## Accomplishments

- `src/services/decimal.ts` 是唯一的字串 ↔ decimal 轉換模組:strict Big clone 讓 number 混入 decimal 路徑時大聲失敗(T-05-27),wire 輸出一律 `toFixed()`(1e-8 輸出 `0.00000001`),RM 與後端 HALF_UP 一致。
- `moneyFormat` 八個格式化函式都接受 Decimal,透過 `toFixed(dp, HALF_UP)` 取得字串後交給 `Intl.NumberFormat`;既有 number 測試全部維持綠。
- 現金 slice 端到端:讀取方向 wire `CashEntryDto` → `toCashEntry` → `CashEntry` → `Cash.vue` → `fmtMoney`;寫入方向表單 → `parseDecimalInput` → `CreateCashEntryInput` → `toCreateCashEntryRequest`(`toWire`)→ JSON 字串。mock adapter 使用同一套 mapper 與投影。
- 精度案例覆蓋 decimal、moneyFormat、cashApi、Cash 頁、CashEntryDialog 五層;layout e2e stub 改為字串並全數通過。

## Task Commits(前端 repo `/home/user/stock-web-v2-front-end`,分支 `claude/epic-curie-lj8alo`)

1. **Task 1(tracer):big.js + decimal.ts + moneyFormat Decimal → cashApi mapper → Cash 頁顯示**:`981fc35` `feat(05-16): 引入 big.js 與 decimal 轉換模組,現金列表改為字串契約`
2. **Task 2:寫入方向,CashEntryDialog 輸入 → Decimal → toWire;layout stub 改字串**:`abf2554` `feat(05-16): 現金入出金寫入改為 Decimal → 字串`

完整 SHA:`981fc35c797a0e96f12545acbcbb532c363bd884` / `abf255464c1af894090073ad5f5672f00bc1775b`(plan 開始前 HEAD `9e920b8fad7f1f8459f8fbcd313b43b30796a7bc`)。這兩個 commit 只在前端 repo 的歷史裡,不在本規劃 repo。

**Plan metadata:** 本 SUMMARY 未 commit(由 orchestrator 在本 wave 結束後一起提交)。

## Files Created/Modified

- `vue-app/src/services/decimal.ts`:decimal 轉換模組(新增)
- `vue-app/src/services/decimal.test.ts`:往返 / 冪等 / strict / 捨入測試(新增)
- `vue-app/src/moneyFormat.ts`(+ test):Decimal 支援、私有 `fixedString`、檔頭註解更新
- `vue-app/tsconfig.json`:`lib` 加 `"ES2023.Intl"`
- `vue-app/src/services/cashApi.ts`(+ test):wire 字串、`CashEntry` / `CreateCashEntryInput`、`toCashEntry`、`toCreateCashEntryRequest`、mock wire 帳本與 compareTo 比對
- `vue-app/src/pages/Cash.vue`(+ test):domain `CashEntry`;fixture 改字串、補超過 2^53 金額的渲染案例
- `vue-app/src/components/CashEntryDialog.vue`(+ test):Decimal 輸入 / 驗證 / 沖銷預填 / overBalance 交接點;補 6 個 D-08 案例
- `vue-app/e2e/support/portfolioStubs.ts`:現金 stub 金額改字串
- `vue-app/package.json` / `package-lock.json`:big.js、@types/big.js(精確版本)

## Decisions Made

見 frontmatter `key-decisions`。重點:`ES2023.Intl` 仍需在 `moneyFormat.ts` 單一 helper 做 `as Intl.StringNumericLiteral` 轉型;另匯出 `ROUND_HALF_UP`,讓 big.js 只在 `decimal.ts` 被 import。

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] 兩個 task 之間的型別閉包:cashApi 的寫入型別與對話框型別橋接移到 Task 1**
- **Found during:** Task 1
- **Issue:** Task 1 要求 `CreateCashEntryRequest.amount` / `CashEntryDto.amount` 改為 string,且 `amount: number` 歸零(含 `MockIdempotencyRecord`)。但 `CashEntryDialog.vue` 的 `reversalOf` prop、`recorded`、`emit('recorded')`、送出 payload 與 `resultSubText` 都綁這兩個型別,只做計畫中的 Task 1 檔案,vue-tsc 會紅,對話框測試在執行期也會壞。
- **Fix:** Task 1 一併完成 cashApi 的寫入型別(`CreateCashEntryInput`、`toCreateCashEntryRequest` 投影、mock compareTo 比對),並對 `CashEntryDialog.vue` / `CashEntryDialog.test.ts` 做最小型別橋接(prop / emit 改 `CashEntry`、`resultSubText` 改 `neg()`、payload 暫以 `parseDecimal(numberToWire(...))`、fixture 改字串)。Task 2 再把對話框改為 `parseDecimalInput`,並刪除 payload 的 `numberToWire` 暫時寫法,所以最終只剩 overBalance 這一個計畫內的交接點。
- **Files modified:** `src/services/cashApi.ts`、`src/components/CashEntryDialog.vue`、`src/components/CashEntryDialog.test.ts`
- **Verification:** 兩個 commit 各自都通過 `vue-tsc --noEmit`,全套測試也都綠。
- **Committed in:** `981fc35`

**2. [Rule 2 - Critical] `toWire` 執行期守衛**
- **Found during:** Task 1
- **Issue:** `(5).toFixed()` 也會回字串,經 `as` / `any` 混入的 number 可以靜默通過 `toWire`,進到 wire 路徑。
- **Fix:** `toWire` 遇到非 Decimal 丟 TypeError,並補測試。
- **Files modified:** `src/services/decimal.ts`、`src/services/decimal.test.ts`
- **Committed in:** `981fc35`

---

**Total deviations:** 2 auto-fixed(Rule 3 ×1、Rule 2 ×1)
**Impact on plan:** 範圍沒有擴大,兩個 commit 的分界從「依檔案」改為「依型別閉包」,每個 commit 都可以 build。

## Issues Encountered

- 用 regex 批次改測試 fixture 時,把「wire 仍是 number 必須丟錯」那個反例也改成了 `d('50000')`。big.js 有 `toJSON`,序列化後 wire 變回字串,測試因此沒有失敗,看起來像通過。已還原成 number 字面並確認會丟錯。提醒:**Decimal 進 `JSON.stringify` 會經 `toJSON` 變成 toString 字串**,極小值可能出現 `1e-8`;送出一律走 `toWire` 投影,不能把 Decimal 直接放進 `json`。

## Verification(最終 HEAD `abf2554`)

- `npm run lint`:0 errors(40 warnings,都是既有的 `no-explicit-any` ratchet,本 plan 的檔案沒有新增 warning)
- `npm test`:66 files / 1374 tests passed
- `VITE_DATA_MODE=api npm test`:66 files / 1374 tests passed
- `npm run build`(vue-tsc + vite build):通過
- `npx playwright test e2e/tests/layout.spec.ts --trace off`:101 passed
- Acceptance greps:`strict = true` ≥ 1;decimal.ts 中 `toString()` = 0;cashApi.ts 中 `amount: number` = 0;cashApi.test.ts 中 `123456789012.12345678` = 5;CashEntryDialog.vue 中 `Number(amount.value` = 0;cashApi.ts 中 `toWire` = 4;CashEntryDialog.test.ts 中 `"123456789012.12345678"` = 2;package.json 版本鎖定檢查通過

## Next Phase Readiness

- 05-17 要刪除 `CashEntryDialog.vue` overBalance 的 `parseDecimal(numberToWire(...))` 交接點,把 `props.balances` 改為 Decimal domain 型別。
- 後續 plan 照同一規約:wire `string` → `to<Domain>` mapper → domain `Decimal`;常數用字串建構;比率在 `div` 之後 `round(dp, ROUND_HALF_UP)`。

## Self-Check: PASSED

- FOUND: vue-app/src/services/decimal.ts、vue-app/src/services/decimal.test.ts
- FOUND: 981fc35、abf2554(前端 repo,都是 HEAD 的祖先)
