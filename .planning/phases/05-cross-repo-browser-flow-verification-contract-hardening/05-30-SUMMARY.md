---
phase: 05-cross-repo-browser-flow-verification-contract-hardening
plan: 30
subsystem: frontend
tags: [vue, error-normalization, idempotency, i18n, vitest, G-05-7, FE-WR-01]

requires:
  - phase: 05-cross-repo-browser-flow-verification-contract-hardening (D-26)
    provides: apiClient.transportFetch 把 fetch reject 正規化成 NETWORK_ERROR(status 0),網路錯誤碼的唯一來源
  - phase: 05-cross-repo-browser-flow-verification-contract-hardening (05-29)
    provides: apiRequestMapped;cashApi.createEntry / holdingEventApi.createDividend / createShareAdjustment 對 201 壞 body 丟 INVALID_API_RESPONSE(帶 status 與 traceId)
provides:
  - "baseCurrencyErrorKey:非 ApiClientError → baseCurrencyErrUnknown;只有 code 為 NETWORK_ERROR 的 ApiClientError 回 baseCurrencyErrNetwork"
  - "Settings diagnosticsOf(讀取 / 儲存)與 Overview 基準幣 diagnostics 的非 ApiClientError 分支 → UNKNOWN_ERROR"
  - "CashEntryDialog / HoldingEventDialog handleSubmitFailure 非 ApiClientError → UNKNOWN_ERROR(落到 cashErrUnknown / holdingEventErrUnknown,key 保留)"
  - "INVALID_API_RESPONSE 刻意不進兩個對話框的 SUBMIT_ERROR_COPY,落到既有「可能寫入也可能沒有,請先確認」文案並露 traceId"
affects: [05-31, G-05-7, FE-WR-01, VER-02]

actuals:
  tokens: 5119
  tasks: 2
  commits: 2
plan_head_before: 203e52432221071007d93f1a6fc1954af109c065
plan_head_after: 253fe16646e9a65d39a238226b921c7589edab4c
# plan_head_before / plan_head_after 是前端 repo(/home/user/stock-web-v2-front-end)的 commit;commits 依其量測。
# tokens = 前端 diff(203e524..253fe16)字元數 20477 / 4。

tech-stack:
  added: []
  patterns:
    - "非 ApiClientError 一律標 UNKNOWN_ERROR(結果不確定文案);網路文案只對 transportFetch 產生的 NETWORK_ERROR 顯示"
    - "測試非 ApiClientError 路徑:mount 後 vi.spyOn(getRuntimeApiClients().<domain>, '<method>').mockRejectedValue(new TypeError(...))"

key-files:
  created: []
  modified:
    - /home/user/stock-web-v2-front-end/vue-app/src/baseCurrencyErrorKey.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/baseCurrencyErrorKey.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Settings.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Settings.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Overview.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/components/CashEntryDialog.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/components/CashEntryDialog.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/components/HoldingEventDialog.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/components/HoldingEventDialog.test.ts

key-decisions:
  - "兩個對話框不新增 INVALID_API_RESPONSE 對照:既有 cashErrUnknown / holdingEventErrUnknown 已說「可能寫入也可能沒有,請先確認」,正確描述 201 但內容不合契約的情境;在 submitErrorMessage 上方註解寫明此刻意選擇"
  - "INVALID_API_RESPONSE 與 UNKNOWN_ERROR 的 key 處置走預設保留(與 05-29 OrderTicket 一致):可能已寫入,同一把 Idempotency-Key 重送命中後端冪等"
  - "baseCurrencyErrorKey 不新增文案:INVALID_API_RESPONSE 與非 ApiClientError 都走既有 baseCurrencyErrUnknown(基準幣未變更)"

patterns-established:
  - "新增依錯誤碼分派的 UI 時:非 ApiClientError → UNKNOWN_ERROR / *ErrUnknown,不要合成 NETWORK_ERROR(源碼守門由 05-31 加上)"

requirements-completed: [VER-02]

coverage:
  - id: D1
    description: "baseCurrencyErrorKey:非 ApiClientError(TypeError、字串)→ baseCurrencyErrUnknown;NETWORK_ERROR(status 0)→ baseCurrencyErrNetwork;INVALID_API_RESPONSE(200)→ baseCurrencyErrUnknown;其餘既有分派不變"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/baseCurrencyErrorKey.test.ts#非 ApiClientError(mapper / 程式錯誤)→ baseCurrencyErrUnknown / INVALID_API_RESPONSE → baseCurrencyErrUnknown / NETWORK_ERROR → baseCurrencyErrNetwork"
        status: pass
    human_judgment: false
  - id: D2
    description: "Settings 基準幣:updateBaseCurrency 以 plain TypeError reject → baseCurrencyErrUnknown 文案 + UNKNOWN_ERROR、不含網路文案;getPreferences 以 TypeError reject → loadFailed + UNKNOWN_ERROR、無 traceId;既有 fetch-TypeError 案例仍為網路文案"
    requirement: VER-02
    verification:
      - kind: automated_ui
        ref: "vue-app/src/pages/Settings.test.ts#updateBaseCurrency 以非 ApiClientError reject / getPreferences 以非 ApiClientError reject / 錯誤分派:網路失敗(fetch reject)"
        status: pass
    human_judgment: false
  - id: D3
    description: "Overview 基準幣儲存失敗 diagnostics 的非 ApiClientError 分支改為 UNKNOWN_ERROR(源碼已無 code: 'NETWORK_ERROR' 字面);Overview 既有測試全綠"
    requirement: VER-02
    verification:
      - kind: automated_ui
        ref: "vue-app/src/pages/Overview.test.ts(全檔)"
        status: pass
      - kind: other
        ref: "grep -cE \"code:[[:space:]]*.NETWORK_ERROR.\" src/pages/Settings.vue src/pages/Overview.vue = 0"
        status: pass
    human_judgment: false
  - id: D4
    description: "CashEntryDialog:201 但 amount 為 JSON number → cashErrUnknown + INVALID_API_RESPONSE + traceId(trace-cash-201)、不顯示 cashErrNetwork、不 emit recorded,重送沿用同一把 Idempotency-Key;cash.createEntry 以 TypeError reject → UNKNOWN_ERROR + cashErrUnknown、無 traceId"
    requirement: VER-02
    verification:
      - kind: automated_ui
        ref: "vue-app/src/components/CashEntryDialog.test.ts#回應不合契約與非 ApiClientError(05-30 FE-WR-01)"
        status: pass
    human_judgment: false
  - id: D5
    description: "HoldingEventDialog:股利 201 壞 body → holdingEventErrUnknown + INVALID_API_RESPONSE + traceId、key 保留;股數調整 201 缺 quantityDelta → 同上;createDividend 以 TypeError reject → UNKNOWN_ERROR + holdingEventErrUnknown;既有 fetch-TypeError 案例仍為網路文案"
    requirement: VER-02
    verification:
      - kind: automated_ui
        ref: "vue-app/src/components/HoldingEventDialog.test.ts#回應不合契約與非 ApiClientError(05-30 FE-WR-01)"
        status: pass
      - kind: automated_ui
        ref: "vue-app/src/components/HoldingEventDialog.test.ts#keepsTheKeyAfterANetworkFailureAndSaysRetryIsSafe"
        status: pass
    human_judgment: false

duration: 8min
completed: 2026-11-14
status: complete
---

# Phase 5 Plan 30: 基準幣與現金 / 股利對話框的非 ApiClientError 改標 UNKNOWN_ERROR Summary

**基準幣分派(`baseCurrencyErrorKey`、Settings、Overview)與 CashEntryDialog / HoldingEventDialog 不再把非 `ApiClientError` 說成網路失敗,改標 `UNKNOWN_ERROR` 並顯示既有的「結果不確定」文案;201 但內容不合契約(05-29 的 `INVALID_API_RESPONSE`)在兩個對話框顯示 Unknown 文案與 traceId,重送沿用同一把 Idempotency-Key。**

## Performance

- **Duration:** 約 8 min(實作與驗證;不含讀取協定檔)
- **Started:** 2026-11-14T17:04:22Z
- **Completed:** 2026-11-14T17:11:44Z
- **Tasks:** 2
- **Files modified:** 9(前端 repo)

## Accomplishments

- `baseCurrencyErrorKey.ts` 把原本合併的判斷拆成兩步:先 `!(error instanceof ApiClientError)` → `baseCurrencyErrUnknown`,再 `code === 'NETWORK_ERROR'` → `baseCurrencyErrNetwork`。註解改寫為:網路錯誤碼只由 `apiClient.transportFetch` 產生(D-26),非 ApiClientError 只可能是 mapper / 程式錯誤。
- `Settings.vue` 的 `diagnosticsOf`(讀取與儲存共用)和 `Overview.vue` 基準幣的 `diagnostics`,非 ApiClientError 分支都改成 `{ code: 'UNKNOWN_ERROR', traceId: null }`。
- `CashEntryDialog.vue` / `HoldingEventDialog.vue` 的 `handleSubmitFailure`,非 ApiClientError 分支改成 `{ code: 'UNKNOWN_ERROR', traceId: null, fields: null, status: 0 }`,旁註沿用 05-29 OrderTicket 的寫法。`SUBMIT_ERROR_COPY` 裡 `NETWORK_ERROR` 那行的註解改為「apiClient.transportFetch 合成(D-26),後端不會送」。`submitErrorMessage` 上方補註:`INVALID_API_RESPONSE` / `UNKNOWN_ERROR` 刻意落到既有的 `*ErrUnknown`。key 的處置沒有改(預設保留)。
- 測試:`baseCurrencyErrorKey.test.ts` 改寫兩條、新增一條;`Settings.test.ts` 新增 2 條(儲存、讀取);兩個對話框測試檔各新增一個 05-30 describe,`CashEntryDialog` 2 條、`HoldingEventDialog` 3 條。既有的 fetch-TypeError(網路)案例不動,仍然綠。

## Task Commits

全部在前端 repo `/home/user/stock-web-v2-front-end`,分支 `claude/epic-curie-lj8alo`:

1. **Task 1(tracer):基準幣非 ApiClientError → baseCurrencyErrUnknown / UNKNOWN_ERROR** - `ace3223`(fix)
2. **Task 2:CashEntryDialog / HoldingEventDialog 非 ApiClientError → UNKNOWN_ERROR + 201 壞 body 驗證** - `253fe16`(fix)

**Plan metadata:** 本 SUMMARY 在後端 repo 以 pathspec commit(docs(05-30))

## Files Created/Modified

- `vue-app/src/baseCurrencyErrorKey.ts`:拆開網路與非 ApiClientError 的判斷,更新 D-26 註解
- `vue-app/src/baseCurrencyErrorKey.test.ts`:非 ApiClientError → Unknown;NETWORK_ERROR → Network(拿掉「與非 ApiClientError 同鍵」的等值斷言);INVALID_API_RESPONSE → Unknown
- `vue-app/src/pages/Settings.vue`:`diagnosticsOf` 非 ApiClientError 改回傳 `UNKNOWN_ERROR`
- `vue-app/src/pages/Settings.test.ts`:`updateBaseCurrency` / `getPreferences` 以 TypeError reject 的案例;import `getRuntimeApiClients`
- `vue-app/src/pages/Overview.vue`:基準幣 `diagnostics` 非 ApiClientError 改回傳 `UNKNOWN_ERROR`
- `vue-app/src/components/CashEntryDialog.vue`:`handleSubmitFailure`、`SUBMIT_ERROR_COPY` 註解、`submitErrorMessage` JSDoc
- `vue-app/src/components/CashEntryDialog.test.ts`:201 number amount(含 key 保留)、`createEntry` TypeError
- `vue-app/src/components/HoldingEventDialog.vue`:`handleSubmitFailure`、`SUBMIT_ERROR_COPY` 註解、`submitErrorMessage` JSDoc
- `vue-app/src/components/HoldingEventDialog.test.ts`:股利 201 number amount(含 key 保留)、股數調整 201 缺 quantityDelta、`createDividend` TypeError

## Decisions Made

見 frontmatter `key-decisions`。重點:不新增 `INVALID_API_RESPONSE` 的專屬文案,沿用既有 Unknown 文案(plan 指定);key 保留。

## Deviations from Plan

None - plan executed exactly as written.

以下兩點是超出 plan 字面的補強,不算偏離,在此記錄:
- `Settings.test.ts` 多加了 `getPreferences` 讀取失敗的 `UNKNOWN_ERROR` 案例。`diagnosticsOf` 由讀取和儲存共用,plan 只要求儲存路徑。
- `HoldingEventDialog.test.ts` 多加了股數調整 201 缺 `quantityDelta` 的案例。plan 只要求股利路徑,但 commit subject 涵蓋股數調整。

## Issues Encountered

- TDD 紅燈:Task 1 有 3 條新案例先紅,Task 2 是 2 條非 ApiClientError 案例先紅,失敗原因都是畫面顯示了網路文案 / `NETWORK_ERROR`。Task 2 的三條「201 壞 body」案例一寫好就是綠的,因為 05-29 已經讓 adapter 丟 `INVALID_API_RESPONSE`,對話框也已經會落到 `*ErrUnknown`。這幾條屬於驗證 05-29 端到端行為,符合 plan 目標「以 05-29 的 mapped adapter 驗證」,不是缺陷。

## Verification(前端閘門)

| 命令 | 結果 |
|---|---|
| `npx vitest run src/baseCurrencyErrorKey.test.ts src/pages/Settings.test.ts src/pages/Overview.test.ts`(Task 1 verify / tracer gate) | 3 files / 140 tests 全過 |
| `npx vitest run src/components/CashEntryDialog.test.ts src/components/HoldingEventDialog.test.ts`(Task 2 verify) | 2 files / 157 tests 全過 |
| `npm run lint` | exit 0;0 errors / 38 warnings(都是既有的 `no-explicit-any`,本 plan 動到的檔案沒有新增) |
| `npm test` | 68 files / 1590 tests 全過 |
| `VITE_DATA_MODE=api npm test` | 68 files / 1590 tests 全過 |
| `npm run build`(含 vue-tsc) | exit 0,沒有 `error TS` |
| Playwright layout.spec | 未跑:本 plan 沒有改版面 / CSS |
| 真後端瀏覽器 E2E | 延到 CI(本機沒有 Docker) |

Acceptance criteria 全部重跑通過:
- T1:`instanceof ApiClientError) ||` = 0;Settings / Overview 的 `code: 'NETWORK_ERROR'` = 0;`baseCurrencyErrorKey.test.ts` 的 `baseCurrencyErrUnknown` = 11(≥ 7);`Settings.test.ts` 的 `UNKNOWN_ERROR` = 4(≥ 1)
- T2:兩個對話框的 `code: 'NETWORK_ERROR'` = 0;`CashEntryDialog.test.ts` 的 `INVALID_API_RESPONSE` = 3(≥ 1);`HoldingEventDialog.test.ts` 的 `INVALID_API_RESPONSE` = 5(≥ 1);兩檔 `UNKNOWN_ERROR` 合計 4(≥ 2)

## Known Stubs

None.

## User Setup Required

None - 不需外部服務設定。

## Next Phase Readiness

- 05-31 可以接手剩下的非 ApiClientError 分支(watchlist 系列頁、App.vue `messageFromStartupError`),以及「網路錯誤碼只在 apiClient.ts 產生」的源碼守門。本 plan 動過的 5 個產品檔已經沒有 `code: 'NETWORK_ERROR'` 字面,守門加上後不會被這些檔案擋下。
- 沒有 blocker。

## Self-Check: PASSED

- 9 個 modified 檔案都存在
- 前端 commit `ace3223`、`253fe16` 都在 HEAD 祖先鏈上(`git rev-list --count 203e524..HEAD` = 2)

---
*Phase: 05-cross-repo-browser-flow-verification-contract-hardening*
*Completed: 2026-11-14*
