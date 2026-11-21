---
phase: 05-cross-repo-browser-flow-verification-contract-hardening
plan: 32
subsystem: testing
tags: [vitest, playwright, e2e, mock-sentinels, import-meta-glob, G-05-7, G-05-9, FE-WR-02]

requires:
  - phase: 05-cross-repo-browser-flow-verification-contract-hardening (05-01)
    provides: contract-journey.spec.ts(J0,SC3 / SC4 真後端證據)與「不得 page.route / route.fulfill」test-tier 禁止事項
  - phase: 05-cross-repo-browser-flow-verification-contract-hardening (D-20 / T-05-03)
    provides: e2e/support/mockSentinels.ts、src/mockSentinels.test.ts 防空轉守門、api-failure-states.spec.ts PAGES 表
provides:
  - "MOCK_SENTINELS.kpis = ['+$12,481', '$84,210', '18.4%'](Overview.vue mockKpis 字面值)"
  - "MOCK_SENTINELS.cashNotes = ['Initial deposit', 'Recorded by mistake'](cashApi mock 種子備註)"
  - "mockSentinels.test.ts:六類非空;kpis 在 mockKpis 區段且不在 apiKpis 區段(sliceSection 切不到時丟錯);cashNotes 在 resetMockCashLedgerForTests 後 createMockCashApi().listEntries() 的 note 中"
  - "api-failure-states.spec.ts:Overview sentinels + kpis、assertReal 驗第一張 KPI .kpi-s 以 holdingCount 1 開頭;Cash sentinels + cashNotes、assertReal 驗 cash-entries-row 總數 1"
  - "src/contractJourneyBoundary.test.ts:?raw glob 讀 e2e/**/*.ts,J0 import 閉包禁止 .route / .unroute / .unrouteAll / .routeFromHAR / .routeWebSocket / .fulfill / .abort / .fallback / .continue / .addInitScript 呼叫;J0 直接 import 白名單;非白名單套件與無法解析的相對 import 為違規"
affects: [05-33, G-05-7, G-05-9, FE-WR-02, VER-02, VER-03]

actuals:
  tokens: 5541
  tasks: 2
  commits: 2
plan_head_before: 615a8687d2461880202b0841dd6b447b4fb67ecd
plan_head_after: 203e52432221071007d93f1a6fc1954af109c065
# plan_head_before / plan_head_after 是前端 repo(/home/user/stock-web-v2-front-end)的 commit;commits 依其量測。
# tokens = chars/4 over `git diff plan_head_before..plan_head_after`(前端 repo)。

tech-stack:
  added: []
  patterns:
    - "e2e/ 的常駐守門只能走 src/*.test.ts + import.meta.glob('../e2e/**/*.ts', { query: '?raw' }):eslint / vue-tsc / vitest include 都看不到 e2e/"
    - "守門沿 entry 的相對 import BFS 掃整個閉包,不只掃 spec 本身(擋 fixtures 覆寫 page fixture 的繞過)"
    - "哨兵防空轉同時驗正反兩面:值仍在 mock 來源區段、且不在 API mode 會渲染的區段"

key-files:
  created:
    - /home/user/stock-web-v2-front-end/vue-app/src/contractJourneyBoundary.test.ts
  modified:
    - /home/user/stock-web-v2-front-end/vue-app/e2e/support/mockSentinels.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/mockSentinels.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/e2e/tests/api-failure-states.spec.ts

key-decisions:
  - "禁止呼叫偵測掃原始碼、不剝註解(decimalBoundary 的 stripComments 會誤吞含 /** 的 glob 字串);J0 / fixtures 現行註解命中 0"
  - "findForbiddenCalls 每處呼叫回報一筆(同一行 context.route + r.fulfill 回報 2 筆),格式 `檔案:行號: .名稱( | 該行內容`"
  - "繞過情境自我驗證全用合成 sources map(portfolioStubs 也是合成字串),不依賴真實 portfolioStubs.ts 存在或內容,避免 layout 支援檔重構時守門誤紅"
  - "import 抽取涵蓋 type-only import(保守:type import 也納入閉包與白名單);相對路徑解析候選為 原樣 / +.ts / .js→.ts / /index.ts"
  - "Cash 總列數斷言保留既有的 CASH_SEED_NOTE 過濾斷言,另加總數 1(兩條一起才能分辨「真值缺」與「混入 mock 列」)"

patterns-established:
  - "新增 e2e helper 給 contract-journey 用時,helper 也落在守門閉包內,同樣不得出現攔截 / stub 呼叫形狀(註解也不行)"

requirements-completed: [VER-02, VER-03]

coverage:
  - id: D1
    description: "kpis / cashNotes 兩類新哨兵 + 防空轉測試(mockKpis 正向、apiKpis 反向、mock 現金帳本正向)"
    requirement: VER-03
    verification:
      - kind: unit
        ref: "vue-app/src/mockSentinels.test.ts"
        status: pass
    human_judgment: false
  - id: D2
    description: "api-failure-states Overview / Cash 案例使用新哨兵並驗後端值(holdingCount 1、現金列數 1)"
    requirement: VER-03
    verification:
      - kind: other
        ref: "npx playwright test --list e2e/tests/api-failure-states.spec.ts(18 tests,可解析)"
        status: pass
      - kind: e2e
        ref: "CI Browser E2E(real backend):S5 overview / cash × 3 failures(05-33 收集)"
        status: unknown
    human_judgment: false
  - id: D3
    description: "contractJourneyBoundary.test.ts:J0 與其 import 閉包禁止攔截 / stub,納入 npm test"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/contractJourneyBoundary.test.ts"
        status: pass
    human_judgment: false

duration: 8min
completed: 2026-11-14
status: complete
---

# Phase 05 Plan 32: SC5 哨兵補 KPI / 現金備註 + contract-journey 常駐禁 stub 守門 Summary

**SC5 哨兵新增 Overview mock KPI 與 cash mock 種子備註兩類(含正反向防空轉),api-failure-states Overview / Cash 的 assertReal 改驗後端值;新增 `?raw` vitest 守門,遞迴掃 contract-journey 的 import 閉包,禁止 route / fulfill / abort 等攔截呼叫,並禁止非白名單 import。**

## Performance

- **Duration:** 約 8 分鐘
- **Started:** 2026-11-14T16:53:59Z
- **Completed:** 2026-11-14T17:01:57Z
- **Tasks:** 2
- **Files modified:** 4(1 個新檔、3 個修改)

## Accomplishments

- G-05-7 / FE-WR-02:Overview 與 Cash 在 API mode 若回填 mock KPI 或 mock 現金列,SC5 的限定區塊 `not.toContainText` 現在真的會紅;`assertReal` 不再只驗「看得到」,改驗後端值:總資產副標以 holdingCount `1` 開頭、現金總列數 `1`。
- 哨兵防空轉:kpis 必須仍在 `Overview.vue` 的 `mockKpis` 區段、且不在 `apiKpis` 區段(若出現在 apiKpis,API mode 也會渲染,斷言會一直紅);cashNotes 必須仍在 mock 現金帳本的種子分錄裡。
- G-05-9:`src/contractJourneyBoundary.test.ts` 把 05-01「J0 不得 stub」從一次性 grep 變成 `npm test` 的常駐守門,也涵蓋 helper 繞過(J0 改 import 其他 helper、fixtures.ts 覆寫 page fixture)。

## Task Commits

1. **Task 1(tracer):KPI / 現金備註哨兵 → 防空轉 → api-failure-states 強化** - `c091f01`(test)
2. **Task 2:contractJourneyBoundary.test.ts** - `203e524`(test)

兩個 commit 都在前端 repo `/home/user/stock-web-v2-front-end`,分支 `claude/epic-curie-lj8alo`。

## TDD Evidence

- Task 1 RED:先改 `mockSentinels.test.ts`。新增的 3 個 it 失敗(`MOCK_SENTINELS.kpis` / `cashNotes` 是 undefined → `Cannot read properties of undefined (reading 'filter')`),結果是 3 failed | 3 passed。補上哨兵後 GREEN,6 passed。
- Task 2 RED:測試先寫,偵測函式以 stub 回傳空結果。4 failed | 1 passed;那 1 個是「閉包 0 違規」,stub 下本來就該綠。實作後 GREEN,5 passed。
- Tracer gate:Task 1 commit 後重跑 `<verify>`(vitest 6 passed、playwright --list 18 tests)通過,才繼續 Task 2。

## Files Created/Modified

- `vue-app/e2e/support/mockSentinels.ts`:新增 `kpis`、`cashNotes`,檔頭 JSDoc 補兩類的來源與選值理由。不選 `+1.04%` / `8.4%`:較可能和真值巧合,而且 `8.4%` 是 `18.4%` 的子字串。
- `vue-app/src/mockSentinels.test.ts`:`sliceSection` 用來切 Overview.vue 的 mockKpis / apiKpis 區段,切不到時丟出明確錯誤;新增 kpis / cashNotes 兩個 it;非空檢查改為六類;「偵測規則本身有效」補 sliceSection 的失敗路徑。
- `vue-app/e2e/tests/api-failure-states.spec.ts`:只動 Overview、Cash 兩個 PageCase,其他案例不變。
- `vue-app/src/contractJourneyBoundary.test.ts`:新守門,共 5 個 it:掃描範圍非空、J0 直接 import 白名單、閉包 0 違規、偵測自我驗證、繞過情境自我驗證。

## Verification

| Command(在 `vue-app/` 下) | Result |
|---|---|
| `npx vitest run src/mockSentinels.test.ts` | 6 passed |
| `npx vitest run src/contractJourneyBoundary.test.ts` | 5 passed |
| `npm run lint` | exit 0,0 errors / 38 warnings(都是既有警告;兩個新 / 改的測試檔單獨跑 eslint 為 0 問題) |
| `npm test` | 68 files / 1582 tests passed |
| `VITE_DATA_MODE=api npm test` | 68 files / 1582 tests passed |
| `npm run build` | exit 0,0 個 `error TS`(只有既有的 chunk size 警告) |
| `npx playwright test --list e2e/tests/api-failure-states.spec.ts` | 可解析,共 18 tests |
| `npx playwright test e2e/tests/layout.spec.ts --trace off` | 101 passed |

Acceptance grep:`kpis:` / `cashNotes:` 在 mockSentinels.ts 各 1 處;`MOCK_SENTINELS.kpis`、`MOCK_SENTINELS.cashNotes` 在 api-failure-states.spec.ts 各 1 處;`createMockCashApi` 在 mockSentinels.test.ts 有 2 處;`contract-journey.spec.ts` 在新守門中有 2 處;`node:` 為 0 處;受保護檔(contract-journey.spec.ts、fixtures.ts)沒有 `(05-32)` 的 commit。

**只有 CI 能證明(延到 05-33 收集):** `api-failure-states.spec.ts` 的 S5 overview / cash 案例要真後端(e2e-browser profile)。本機沒有後端 / Docker,所以**沒跑過**這些案例,只用 `--list` 證明 spec 可解析。

## Decisions Made

見 frontmatter `key-decisions`。

## Deviations from Plan

None - plan executed exactly as written.

有一處是實作細節的選擇,不改變 plan 的規格:繞過情境 (a) 的 portfolioStubs 用合成字串,沒有讀真實檔(plan 寫「以合成 sources map 呼叫同一個閉包函式」,與這個做法一致)。另外多加了情境 (c):無法解析的相對 import 與非白名單套件 `msw`,補強「無法解析即違規」規則的自我驗證。

## Issues Encountered

None.

## Known Stubs

None.

## Threat Flags

None:只新增 / 修改測試與 E2E 支援檔,沒有新的執行期網路、auth 或檔案存取面。T-05-47 與 T-05-48 依 threat register 處理完畢。

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

05-33 要從兩個 repo 的 CI Browser E2E job 收集 S5 overview / cash(× abort / envelope5xx / nonEnvelope502)的 passed 證據。

## Self-Check: PASSED

- FOUND: /home/user/stock-web-v2-front-end/vue-app/src/contractJourneyBoundary.test.ts
- FOUND: /home/user/stock-web-v2-front-end/vue-app/e2e/support/mockSentinels.ts(含 cashNotes)
- FOUND: c091f01、203e524(front-end HEAD 的祖先)
