---
phase: 05-cross-repo-browser-flow-verification-contract-hardening
plan: 31
subsystem: frontend
tags: [vue, error-normalization, source-guard, watchlist, startup, vitest, G-05-7, FE-WR-01]

requires:
  - phase: 05-cross-repo-browser-flow-verification-contract-hardening (D-26)
    provides: apiClient.transportFetch 把 fetch reject 正規化成 NETWORK_ERROR(status 0),網路錯誤碼的唯一來源
  - phase: 05-cross-repo-browser-flow-verification-contract-hardening (05-29)
    provides: OrderTicket / mapped adapter 等非 ApiClientError 分支已改 UNKNOWN_ERROR
  - phase: 05-cross-repo-browser-flow-verification-contract-hardening (05-30)
    provides: 基準幣 / Settings / Overview / CashEntryDialog / HoldingEventDialog 已無 code 指定網路錯誤碼
provides:
  - "WatchlistPicker / Watchlist describeError、Watchlist toApiError、Markets / Chart 清單載入錯誤:非 ApiClientError → UNKNOWN_ERROR"
  - "src/startupErrorMessage.ts messageFromStartupError(error: unknown) => SessionMessage:ApiClientError 原樣投影,其他 → UNKNOWN_ERROR / status 0 / requestId null"
  - "src/networkErrorBoundary.test.ts:src 下非測試 .ts / .vue 只有 services/apiClient.ts 可以用 code 指定網路錯誤碼(常駐守門)"
affects: [G-05-7, FE-WR-01, VER-02, T-05-46]

actuals:
  tokens: 3974
  tasks: 2
  commits: 2
plan_head_before: 253fe16646e9a65d39a238226b921c7589edab4c
plan_head_after: 1454916288f96c6c28d23f817dc20a7792115731
# plan_head_before / plan_head_after 是前端 repo(/home/user/stock-web-v2-front-end)的 commit;commits 依其量測(git rev-list --count = 2)。
# tokens = 前端 diff(253fe16..1454916)字元數 15894 / 4。

tech-stack:
  added: []
  patterns:
    - "唯一產生點源碼守門:import.meta.glob(['./**/*.ts', './**/*.vue'], ?raw) 掃全 src 非測試檔,允許清單只有一個檔;含「掃描範圍非空」「允許檔自身命中(偵測不空轉)」「合成字串自我驗證」三道保險"
    - "App.vue 內的錯誤投影抽成純函式模組,行為以單元測試鎖住"

key-files:
  created:
    - /home/user/stock-web-v2-front-end/vue-app/src/startupErrorMessage.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/startupErrorMessage.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/networkErrorBoundary.test.ts
  modified:
    - /home/user/stock-web-v2-front-end/vue-app/src/components/WatchlistPicker.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/components/WatchlistPicker.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Watchlist.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Markets.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Chart.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/App.vue

key-decisions:
  - "守門正規式為 \\bcode\\s*:\\s*(['\"`])NETWORK_ERROR\\1:只抓 code 屬性被指定為字面值;ERROR_COPY 的 key、=== 判斷不算(消費端)"
  - "守門掃含註解的原始碼;ERROR_COPY 旁的說明改寫成文字敘述「網路錯誤碼由 apiClient.transportFetch 合成(D-26)」,不寫成物件字面值"
  - "非 Error 的啟動拋出值用靜態 'Unexpected startup error';SessionBanner 不渲染 message 本文,只顯示 code / status / requestId"
  - "Watchlist / Markets / Chart 合成的 ApiClientError message 改為 'Unexpected error'(畫面只露 code / requestId)"

patterns-established:
  - "新元件遇到非 ApiClientError 一律 UNKNOWN_ERROR;想合成網路錯誤碼會被 networkErrorBoundary.test.ts 擋下"

requirements-completed: [VER-02]

coverage:
  - id: D1
    description: "WatchlistPicker:addItem 以 plain TypeError reject → watchlistErrUnknown 文案 + UNKNOWN_ERROR、不顯示 watchlistErrNetwork / NETWORK_ERROR、emit retry(要求頁面重讀);既有 CASES(含 fetch 層 TypeError → 網路文案)全綠"
    requirement: VER-02
    verification:
      - kind: automated_ui
        ref: "vue-app/src/components/WatchlistPicker.test.ts#addItem 以非 ApiClientError reject → watchlistErrUnknown + UNKNOWN_ERROR、不顯示網路文案、要求頁面重讀"
        status: pass
      - kind: automated_ui
        ref: "vue-app/src/components/WatchlistPicker.test.ts#網路失敗 → watchlistErrNetwork"
        status: pass
    human_judgment: false
  - id: D2
    description: "Watchlist(toApiError、describeError)、Markets、Chart 的非 ApiClientError 分支改為 UNKNOWN_ERROR;四個頁面 / 元件測試檔全綠,源碼無 code 指定網路錯誤碼"
    requirement: VER-02
    verification:
      - kind: automated_ui
        ref: "vue-app/src/pages/Watchlist.test.ts、Markets.test.ts、Chart.test.ts(全檔,111 tests 與 WatchlistPicker 合計)"
        status: pass
      - kind: unit
        ref: "vue-app/src/networkErrorBoundary.test.ts#services/apiClient.ts 是唯一允許產生網路錯誤碼的地方"
        status: pass
    human_judgment: false
  - id: D3
    description: "startupErrorMessage.messageFromStartupError:ApiClientError(503 AUTH_REDIS_UNAVAILABLE、網路錯誤碼)原樣投影;TypeError → UNKNOWN_ERROR / message 原文 / status 0 / requestId null;非 Error → UNKNOWN_ERROR + 'Unexpected startup error';App.test.ts(含 fetch reject → backend unavailable)全綠"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/startupErrorMessage.test.ts(4 tests)"
        status: pass
      - kind: automated_ui
        ref: "vue-app/src/App.test.ts#API-mode rejected fetch renders backend unavailable retry surface"
        status: pass
    human_judgment: false
  - id: D4
    description: "networkErrorBoundary 源碼守門:掃描範圍 > 50 檔且含 apiClient.ts / App.vue / OrderTicket.vue、不含測試檔;apiClient.ts 自身命中 ≥ 1;除 apiClient.ts 外 0 違規;合成字串(單 / 雙引號、反引號)命中,ERROR_COPY key / === 判斷 / UNKNOWN_ERROR 不命中。TDD 紅燈時正確只報 ./App.vue"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/networkErrorBoundary.test.ts(4 tests)"
        status: pass
    human_judgment: false

duration: 7min
completed: 2026-11-14
status: complete
---

# Phase 5 Plan 31: 觀察清單 / App 啟動錯誤改標 UNKNOWN_ERROR + 網路錯誤碼唯一產生點源碼守門 Summary

**FE-WR-01 收尾:WatchlistPicker、Watchlist ×2、Markets、Chart 與 App 啟動錯誤不再把非 `ApiClientError` 說成網路失敗,一律標 `UNKNOWN_ERROR`;App 啟動錯誤投影抽成可測純函式 `startupErrorMessage.ts`;新增常駐源碼守門 `networkErrorBoundary.test.ts`,鎖定網路錯誤碼只能由 `services/apiClient.ts` 產生(D-26)。**

## Performance

- **Duration:** 約 7 min(實作與驗證;不含讀取協定檔)
- **Started:** 2026-11-14T17:13:46Z
- **Completed:** 2026-11-14T17:20:08Z
- **Tasks:** 2
- **Files:** 3 created、6 modified(前端 repo)

## Accomplishments

- `WatchlistPicker.vue` / `Watchlist.vue` 的 `describeError`:非 ApiClientError 改回 `{ code: 'UNKNOWN_ERROR', traceId: null, fields: null, status: 0 }`,落到 `watchlistErrUnknown`(「結果不確定,清單已重新整理」),並依既有 `needsReload` 規則請頁面重讀。
- `Watchlist.vue` `toApiError`、`Markets.vue` / `Chart.vue` 清單載入 catch:非 ApiClientError 改包成 `new ApiClientError({ status: 0, code: 'UNKNOWN_ERROR', message: 'Unexpected error' })`。
- 兩處 `ERROR_COPY`:CSRF MISSING 註解保留(去掉「/ 網路失敗」),網路錯誤碼那行另加「網路錯誤碼由 apiClient.transportFetch 合成(D-26),後端不會送」。
- `App.vue` 的 `messageFromStartupError` 搬到 `src/startupErrorMessage.ts`,非 ApiClientError → `UNKNOWN_ERROR`;App.vue 改 import,並移除變成未使用的 `ApiClientError` import。
- 新增 `networkErrorBoundary.test.ts`(4 tests)與 `startupErrorMessage.test.ts`(4 tests);`WatchlistPicker.test.ts` 新增 1 條非 ApiClientError 案例。
- 結果:`src` 下非測試檔以 `code:` 指定網路錯誤碼的只剩 `src/services/apiClient.ts`(規劃時 baseline 11 檔)。

## Task Commits

全部在前端 repo `/home/user/stock-web-v2-front-end`,分支 `claude/epic-curie-lj8alo`:

1. **Task 1(tracer):觀察清單系列非 ApiClientError → UNKNOWN_ERROR** - `0d13ece`(fix)
2. **Task 2:App 啟動錯誤抽成 startupErrorMessage.ts + networkErrorBoundary 源碼守門** - `1454916`(test)

**Plan metadata:** 本 SUMMARY 在後端 repo 以 pathspec commit(docs(05-31))

## Files Created/Modified

- `vue-app/src/startupErrorMessage.ts`(新):`messageFromStartupError(error: unknown): SessionMessage`,JSDoc 說明 D-26 與抽出理由
- `vue-app/src/startupErrorMessage.test.ts`(新):ApiClientError 投影 ×2、TypeError、非 Error 字串
- `vue-app/src/networkErrorBoundary.test.ts`(新):唯一產生點守門,檔頭 JSDoc 寫明規則、消費端不受限、掃註解的理由與已知盲點(變數間接組字串)
- `vue-app/src/components/WatchlistPicker.vue`:`describeError`、`ERROR_COPY` 註解
- `vue-app/src/components/WatchlistPicker.test.ts`:`addItem` 以 TypeError reject 的案例;import `getRuntimeApiClients`
- `vue-app/src/pages/Watchlist.vue`:`toApiError`、`describeError`、`ERROR_COPY` 註解
- `vue-app/src/pages/Markets.vue`、`vue-app/src/pages/Chart.vue`:清單載入錯誤非 ApiClientError 分支
- `vue-app/src/App.vue`:刪除本地函式,改 import `./startupErrorMessage`;移除未用 `ApiClientError` import(只動 `<script>`,未動 template / style)

## Decisions Made

見 frontmatter `key-decisions`。

## Deviations from Plan

None - plan executed exactly as written.

補充(不算偏離):
- App.vue 移除 `ApiClientError` import:函式搬走後這個 import 沒有其他使用者,留著會讓 ESLint `no-unused-vars`(error)失敗。
- 守門的自我驗證多加一條:對允許檔 `./services/apiClient.ts` 本身的合成字串回傳空陣列,確認允許清單有效。

## Issues Encountered

- TDD 紅燈:Task 1 新案例先紅(畫面顯示網路文案 + `NETWORK_ERROR`);Task 2 守門先紅且只回報 `./App.vue`(證明 05-29 / 05-30 / 本 plan Task 1 的檔案已乾淨),`startupErrorMessage.test.ts` 先紅(模組不存在)。實作後全綠。
- Tracer 閘門(end-of-phase、只有 automated verify):重跑 Task 1 verify 通過後才進 Task 2。

## Verification(前端閘門)

| 命令 | 結果 |
|---|---|
| `npx vitest run src/components/WatchlistPicker.test.ts src/pages/Watchlist.test.ts src/pages/Markets.test.ts src/pages/Chart.test.ts`(Task 1 / tracer gate) | 4 files / 111 tests 全過 |
| `npx vitest run src/networkErrorBoundary.test.ts src/startupErrorMessage.test.ts src/App.test.ts`(Task 2) | 3 files / 19 tests 全過 |
| `npm run lint` | exit 0;0 errors / 38 warnings(皆為既有 `no-explicit-any`,數量與 05-30 相同,本 plan 未新增) |
| `npm test` | 70 files / 1599 tests 全過 |
| `VITE_DATA_MODE=api npm test` | 70 files / 1599 tests 全過 |
| `npm run build`(含 vue-tsc) | exit 0,無 `error TS` |
| Playwright layout.spec | 未跑:App.vue 只改 `<script>`,未動 template / style,沒有版面 / CSS 變更 |
| 真後端瀏覽器 E2E | 延到 CI(本機沒有 Docker) |

Acceptance criteria 全部重跑通過:
- T1:四檔 `code:` 指定網路錯誤碼合計 = 0(baseline 5);`WatchlistPicker.test.ts` 的 `watchlistErrUnknown` = 4(≥ 3)
- T2:`grep -rlE "code:[[:space:]]*.NETWORK_ERROR." src ... | grep -v '\.test\.ts$'` 只輸出 `src/services/apiClient.ts`;`App.vue` 的 `startupErrorMessage` = 1(≥ 1);`export function messageFromStartupError` = 1(≥ 1)

## Known Stubs

None.

## User Setup Required

None - 不需外部服務設定。

## Next Phase Readiness

- FE-WR-01 全部完成:review 列出的 11 處 + App.vue 都不再把非 ApiClientError 標成網路錯誤,並有常駐守門防回歸(T-05-46 mitigate)。
- 沒有 blocker。

## Self-Check: PASSED

- 3 個 created、6 個 modified 檔案都存在
- 前端 commit `0d13ece`、`1454916` 都在 HEAD 祖先鏈上(`git rev-list --count 253fe16..HEAD` = 2)

---
*Phase: 05-cross-repo-browser-flow-verification-contract-hardening*
*Completed: 2026-11-14*
