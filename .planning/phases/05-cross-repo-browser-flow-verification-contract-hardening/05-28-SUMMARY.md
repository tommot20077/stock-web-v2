---
phase: 05-cross-repo-browser-flow-verification-contract-hardening
plan: 28
subsystem: frontend
tags: [vue, big.js, decimal, backtest, input-validation, design-tokens, color-mix, vitest, source-guard]

requires:
  - phase: 05-24
    provides: 回測初始資金以 Decimal 送出、字串金額契約(parseDecimalInput / toWire / toChartNumber)
  - phase: 05-26
    provides: 後端 CreateBacktestRunRequest @DecimalMax("1000000000000") / @Digits(integer = 13, fraction = 6)(前端上限與其同值)
provides:
  - "decimal.ts parsePlainDecimalInput(text, { maxDecimals, max }) + PlainDecimalLimits:先檢一般十進位文法再建構 Decimal,只用比較判斷上限 / 位數"
  - "backtestApi.ts BACKTEST_CAPITAL_MAX(1e12)/ BACKTEST_CAPITAL_MAX_DECIMALS(6)"
  - "Backtest.vue run() 在 toChartNumber / createRun 之前驗證初始資金"
  - "mock createRun 對界外初始資金回 ApiClientError{400, VALIDATION_FAILED, fields.initialCapital}"
  - "Backtest 回撤 / 熱力圖、Analytics .harvest-btn 改用 var(--up) / var(--dn)(紅漲綠跌會對調)"
  - "contrastTokens.test.ts「漲跌色寫死字面全源碼守門」+ UPDOWN_RGBA_ALLOW"
affects: [05-verification, G-05-7, FE-WR-03, FE-WR-04]

actuals:
  tokens: 6340
  tasks: 3
  commits: 4
plan_head_before: 06ab9200d8c8d82bcad3045211ff27705182e22f
plan_head_after: a82fe746f4b4ac1321143694f1376871030b4a12
# plan_head_before / plan_head_after 是前端 repo(/home/user/stock-web-v2-front-end)的 commit;commits 依其量測。

tech-stack:
  added: []
  patterns:
    - "表單金額輸入:先以一般十進位正規式擋文法,再建構 Decimal,上限 / 位數只用 gt / round().eq() 比較,絕不在驗證前呼叫 toFixed"
    - "SVG 漲跌填色用 fill=var(--token) + fill-opacity;inline style / CSS 用 color-mix(in oklch, var(--up|--dn) N%, transparent)"
    - "源碼守門逐檔逐行掃描完整 .vue(不剝 style / 註解),具名白名單必須同時驗證不過期"

key-files:
  created: []
  modified:
    - /home/user/stock-web-v2-front-end/vue-app/src/services/decimal.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/decimal.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/backtestApi.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/backtestApi.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Backtest.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Backtest.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Analytics.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/contrastTokens.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/task7.test.ts

key-decisions:
  - "回測初始資金上限 1e12、最多 6 位小數(Yuan 2026-11-14,與後端 05-26 同值);常數放在 backtestApi.ts,Backtest.vue 直接 import 常數(既有頁面已有直接 import service 模組的先例,mockStoreBoundary 守門通過)"
  - "parsePlainDecimalInput 不判斷正值('0' 回 Decimal 0),由呼叫端決定;位數以數值判斷,尾零不算"
  - "mock 的 6 位精度案例改用 123456789012.123456;結果 mapper 的 8 位 equity curve 案例不動(結果欄位不受初始資金位數限制)"

patterns-established:
  - "parsePlainDecimalInput 是新的共用一般十進位輸入驗證;CashEntryDialog / OrderTicket / HoldingEventDialog 的手寫規則本 plan 未改"

requirements-completed: [VER-02]

coverage:
  - id: D1
    description: "parsePlainDecimalInput:接受一般十進位(含 .5 / 5. / 尾零),拒絕空白、指數記號、正負號、千分位、內部空白、超上限、超 6 位小數;1e999999999 迅速回 null"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/services/decimal.test.ts#parsePlainDecimalInput(05-28 / FE-WR-03)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Backtest 初始資金在 toChartNumber / createRun 前驗證;13 種不合法輸入皆不送出並顯示同一條欄位錯誤;123456789012.123456 / 1000000000000 / 0.000001 原樣送出"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/pages/Backtest.test.ts#Backtest 初始資金文字輸入 → Decimal(05-24 / D-08)"
        status: pass
      - kind: unit
        ref: "vue-app/src/task7.test.ts#Backtest rejects invalid initial capital without recording a run"
        status: pass
    human_judgment: false
  - id: D3
    description: "mock createRun 對 > 1e12 或超過 6 位小數回 ApiClientError{400, VALIDATION_FAILED, fields.initialCapital},且不建立 run;上限與最小刻度可建立"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/services/backtestApi.test.ts#mock createRun:initialCapital %s 超過上限 / 6 位小數 → VALIDATION_FAILED 400,不建立 run"
        status: pass
    human_judgment: false
  - id: D4
    description: "全 .vue 源碼漲跌 rgba 字面守門(自我驗證、範圍非空、0 違規、白名單不過期)與 Backtest 回撤 / heatColor / Analytics harvest 鈕正向斷言"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/contrastTokens.test.ts#漲跌色寫死字面全源碼守門(FE-WR-04 / D-28)"
        status: pass
      - kind: automated_ui
        ref: "npx playwright test e2e/tests/layout.spec.ts --trace off(101 passed)"
        status: pass
    human_judgment: false
  - id: D5
    description: "紅漲綠跌 × 深 / 淺主題下 Backtest 熱力圖、回撤填色與 Analytics harvest 鈕的實際顯示顏色"
    verification: []
    human_judgment: true
    rationale: "jsdom 不解析 color-mix / fill-opacity 的實際渲染結果,本 plan 未做瀏覽器截圖;以源碼守門 + 05-UAT Test 5 同類人工檢視為準"

duration: 10min
completed: 2026-11-14
status: complete
---

# Phase 5 Plan 28: 回測初始資金上限驗證與漲跌色 token 化 Summary

**新增共用 `parsePlainDecimalInput`,先檢一般十進位文法再建構 Decimal,所以 `1e999999999` 不會再讓 Backtest 分頁 OOM。回測初始資金的上限(1e12)與 6 位小數跟後端 `@DecimalMax` / `@Digits` 同值,mock 也同步;Backtest 回撤與熱力圖、Analytics harvest 鈕改用 `var(--up)` / `var(--dn)`,並新增掃描完整 `.vue` 原始碼的漲跌 rgba 守門。**

## Performance

- **Duration:** 10 min
- **Started:** 2026-11-14T16:23:20Z
- **Completed:** 2026-11-14T16:33:43Z
- **Tasks:** 3
- **Files modified:** 9

## Accomplishments

- FE-WR-03:回測初始資金的驗證移到 `toChartNumber` / `toWire` 之前,而且驗證本身不呼叫 `toFixed`。RED 階段原本的 Backtest 測試 worker 在 `1e999999999` 案例 `ERR_WORKER_OUT_OF_MEMORY`,重現了缺陷;修正後 60 個測試在 4 秒內全綠。
- mock adapter 與真後端對界外初始資金回同形 400(`VALIDATION_FAILED` + `fields.initialCapital`),維持鐵律 3。
- FE-WR-04:三處寫死的漲跌 rgba(Backtest 3 行、Analytics 3 行)改為會隨紅漲綠跌對調的 token。新守門在修正前列出的正好是這 6 行,修正後 0 違規;Notifications 的兩行通知類別色以具名白名單處理,並檢查白名單沒有過期。

## Task Commits

前端 repo `/home/user/stock-web-v2-front-end`,分支 `claude/epic-curie-lj8alo`:

1. **Task 1(tracer):共用 parsePlainDecimalInput → Backtest 初始資金驗證** - `f010d79`(fix)
2. **Task 2:mock createRun 同步後端上限與位數** - `488e470`(fix)
3. **偏差修正:task7 錯誤文案斷言同步** - `47e008d`(test)
4. **Task 3:漲跌色改 token + 全源碼 rgba 守門** - `a82fe74`(fix)

Tracer 回饋閘門:interactive、`human_verify_mode` 預設 end-of-phase、tracer `<verify>` 只有 automated,所以重跑 verify(60 passed)後直接進入展開 task,沒有 checkpoint。

## Files Created/Modified

- `vue-app/src/services/decimal.ts`:`PLAIN_DECIMAL_PATTERN`、`PlainDecimalLimits`、`parsePlainDecimalInput`(附繁中 JSDoc,說明為何拒絕指數記號)
- `vue-app/src/services/decimal.test.ts`:parsePlainDecimalInput 的接受 / 拒絕表格與 1e999999999 快速結束案例
- `vue-app/src/services/backtestApi.ts`:`BACKTEST_CAPITAL_MAX`、`BACKTEST_CAPITAL_MAX_DECIMALS`;mock createRun 上限 / 位數檢查
- `vue-app/src/services/backtestApi.test.ts`:mock 界外 400 / 不建立 run / 上限可建立;精度案例改 6 位小數
- `vue-app/src/pages/Backtest.vue`:`run()` 改用 parsePlainDecimalInput 與新欄位錯誤文案;回撤 `fill="var(--dn)" fill-opacity="0.18"`;`heatColor` 改 color-mix
- `vue-app/src/pages/Backtest.test.ts`:13 種不合法輸入、6 位小數原樣送出、上限 / 最小刻度可送出
- `vue-app/src/pages/Analytics.vue`:`.harvest-btn` / `:hover` 改 `color-mix(in oklch, var(--dn) N%, transparent)`
- `vue-app/src/contrastTokens.test.ts`:「漲跌色寫死字面全源碼守門(FE-WR-04 / D-28)」describe 與 `UPDOWN_RGBA_ALLOW`
- `vue-app/src/task7.test.ts`:錯誤文案斷言改為新文案

## Decisions Made

- 上限與位數常數放在 `backtestApi.ts`,由 `Backtest.vue` 直接 import(其他頁面已經直接 import service 模組;`mockStoreBoundary.test.ts` 通過)。
- `parsePlainDecimalInput` 不判斷正值,由呼叫端以 `lte(ZERO)` 處理,錯誤文案也由呼叫端決定。
- 結果 mapper 的 8 位小數 equity curve 案例保留;只改 mock 精度案例的初始資金(該值現在會被 mock 上限拒絕)。

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] task7.test.ts 仍斷言舊錯誤文案**
- **Found during:** Task 3 跑全套閘門時
- **Issue:** Task 1 依計畫把欄位錯誤文案改成新的長文案,`src/task7.test.ts` 的「Backtest rejects invalid initial capital without recording a run」仍斷言 `Initial capital must be greater than 0`,而這個檔案不在計畫的 files 清單內
- **Fix:** 斷言改為新文案前綴 `Initial capital must be a plain number greater than 0`
- **Files modified:** vue-app/src/task7.test.ts
- **Verification:** `npx vitest run src/task7.test.ts` 5 passed;全套 1560 passed
- **Committed in:** `47e008d`(獨立 test commit)

**2. [Rule 1 - Bug] backtestApi.test.ts 的 mock 精度案例使用 8 位小數初始資金**
- **Found during:** Task 2
- **Issue:** 既有「mock 結果為 domain Decimal…」案例以 `123456789012.12345678` 建立 run,mock 同步 6 位小數上限後會被拒絕
- **Fix:** 該案例的初始資金改為 `123456789012.123456`(3 處),精度註解同步;結果 mapper 的 8 位 equity curve 案例不動
- **Files modified:** vue-app/src/services/backtestApi.test.ts(本來就在 Task 2 的 files 內)
- **Verification:** backtestApi.test.ts 18 passed
- **Committed in:** `488e470`

---

**Total deviations:** 2 auto-fixed(2 × Rule 1,皆為 Task 1 / Task 2 依計畫修改後直接連帶的測試)
**Impact on plan:** 沒有擴大範圍;正式程式碼完全依計畫。

## Issues Encountered

- RED 階段 Backtest.test.ts 的 vitest worker 因 `1e999999999` 進到 `toFixed` 而 heap OOM。這正是要修的缺陷,實作後就消失。

## Verification

前端閘門(皆在 `vue-app/` 下執行):

| 命令 | 結果 |
|------|------|
| `npm run lint` | exit 0,0 errors / 38 warnings(都是既有的 `no-explicit-any` ratchet,本 plan 沒有新增) |
| `npm test` | 67 files / 1560 tests passed |
| `VITE_DATA_MODE=api npm test` | 67 files / 1560 tests passed |
| `npm run build`(vue-tsc + vite) | exit 0,0 `error TS` |
| `npx playwright test e2e/tests/layout.spec.ts --trace off` | 101 passed |

Acceptance criteria:Task 1(1 / 3 / 2 / 1,皆 ≥ 1)、Task 2(3 ≥ 3、1 ≥ 1)、Task 3(rgba 在 Backtest 0、Analytics 0;fill-opacity 1;UPDOWN_RGBA_ALLOW 4 ≥ 2),全部 PASS。

**留給 CI / 人工:**
- 真後端瀏覽器 E2E(`e2e/tests/backtest.spec.ts` 等)需要 Docker,本機無法執行,留給 CI。已確認 backtest.spec 的 D4 案例沒有依賴錯誤文案,只用 `0` / `-500` / `50000`,不受新上限影響。
- 紅漲綠跌 × 深 / 淺主題的實際顏色沒有做瀏覽器截圖驗證(jsdom 不解析 color-mix),以源碼守門與 05-UAT Test 5 同類人工檢視為準(coverage D5)。

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- FE-WR-03 / FE-WR-04 的前端部分已關閉;G-05-7 其餘 finding 由其他 plan 處理。
- `parsePlainDecimalInput` 可供 CashEntryDialog / OrderTicket / HoldingEventDialog 日後收斂手寫規則(本 plan 未改)。

## Self-Check: PASSED

- 9 個修改檔皆存在;前端 commits `f010d79`、`488e470`、`47e008d`、`a82fe74` 都是 HEAD 的祖先(`git rev-list --count 06ab920..HEAD` = 4)。

---
*Phase: 05-cross-repo-browser-flow-verification-contract-hardening*
*Completed: 2026-11-14*
