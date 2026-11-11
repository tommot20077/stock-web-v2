---
phase: 05-cross-repo-browser-flow-verification-contract-hardening
plan: 02
subsystem: testing
tags: [vue, apiClient, fetch, network-error, playwright, page-route, e2e, vitest, mock-sentinels]

requires:
  - phase: 04.2
    provides: "e2e/support/journeyApi.ts(apiBuy / apiWrite / listWatchlists / addWatchlistItem)、fixtures.ts(uniqueAccount / loginViaApi)、六頁錯誤 / 重試 testid"
provides:
  - "apiClient.ts transportFetch:四個 fetch 呼叫點的單一入口;fetch reject(非 AbortError)→ ApiClientError{status: 0, code: 'NETWORK_ERROR', message: 'Network error', requestId: null};AbortError 原樣上拋(D-26)"
  - "baseCurrencyErrorKey:ApiClientError(NETWORK_ERROR) → baseCurrencyErrNetwork(與非 ApiClientError 同鍵)"
  - "apiClient 單元測試補齊 SC2 F1(fetch reject / AbortError / CSRF bootstrap 階段 / 兩並行請求不呼叫 refresh)與 F2(5xx 空 body:application/json → INVALID_JSON_RESPONSE;無 content-type → HTTP_ERROR)"
  - "e2e/support/mockSentinels.ts:MOCK_SENTINELS { symbols, prices, notes, watchlistNames }(純資料,Playwright 與 Vitest 共用)"
  - "src/mockSentinels.test.ts:哨兵仍存在於 mock 輸出的守門 + 偵測規則自我驗證"
  - "e2e/tests/api-failure-states.spec.ts:SC5 18 組矩陣(6 頁 × abort / envelope5xx / nonEnvelope502),@extended"
affects: [05-12, frontend-ci-browser-e2e, apiClient, Overview, Positions, Trades, Cash, Watchlist, Settings]

actuals:
  tokens: 9500
  tasks: 3
  commits: 4
plan_head_before: 6a5986c06c1d4dff7adcc17fb670477ffe013862
plan_head_after: 2171269
commits: 4

tech-stack:
  added: []
  patterns:
    - "transport 邊界正規化網路層錯誤:只有拿不到 Response 才是 NETWORK_ERROR;有 Response 的 401 仍走 refresh 流程"
    - "AbortError 以 error.name 判斷(不用 instanceof DOMException,jsdom / undici 跨 realm 會失準)"
    - "SC5 表格驅動:PAGES(match / testIds / seed / beforeRoute / enter / sentinelScope / sentinels / assertReal)× FAILURES(fail / expectedCode / expectedTraceId)雙層 for 產生獨立命名 test"
    - "page.route 旗標模式:failing 時只攔目標端點,其餘 route.continue() 直通真後端;切旗標後按重試看真值"
    - "哨兵斷言只對目標區塊 locator(allTextContents 合併),絕不對 body / main"

key-files:
  created:
    - /home/user/stock-web-v2-front-end/vue-app/e2e/support/mockSentinels.ts
    - /home/user/stock-web-v2-front-end/vue-app/e2e/tests/api-failure-states.spec.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/mockSentinels.test.ts
  modified:
    - /home/user/stock-web-v2-front-end/vue-app/src/services/apiClient.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/services/apiClient.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/baseCurrencyErrorKey.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/baseCurrencyErrorKey.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/components/HoldingEventDialog.test.ts

key-decisions:
  - "AbortError 判斷用 error.name === 'AbortError' 而非 instanceof DOMException(跨 realm 安全;plan 寫的是 instanceof,行為等價)"
  - "baseCurrencyErrorKey 調整提前併入 Task 1 的 fix commit:transportFetch 一上線,Overview / Settings 的網路失敗測試就會紅,同一個 commit 修才能讓每個 commit 都綠"
  - "settings 種子改用 EUR(plan 寫 TWD):TWD 是新帳號預設值(V14)也是 Settings 下拉初始值 BASE_CURRENCY_OPTIONS[0],斷言 TWD 分辨不出後端真值"
  - "landOnOverview 額外等 overview-watchlist-name 可見:確保 Overview 掛載時的 GET /watchlists 已完成才裝 route(watchlist 列的時序陷阱,對所有列一致套用)"
  - "Overview 的 sentinelScope = overview-summary-error.or(overview-kpi):錯誤態與放行後是不同 DOM 節點,沒有共同容器"
  - "SERVICE_UNAVAILABLE 是測試自訂的注入 code(不必存在於後端 ErrorCode),證明的是 apiClient 原樣帶出信封 code / meta.traceId"

patterns-established:
  - "新增依 microtask 輪數的假計時器測試(toFake: ['Date'])時,送出路徑一律 flushAsync(16)"

requirements-completed: [VER-02, VER-03]

coverage:
  - id: D1
    description: "apiClient transportFetch:fetch reject → NETWORK_ERROR(status 0、靜態訊息、無 traceId),AbortError 原樣上拋,CSRF bootstrap 階段同樣正規化"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/services/apiClient.test.ts#apiClient transport 網路錯誤正規化(D-26)"
        status: pass
    human_judgment: false
  - id: D2
    description: "VER-02 concurrency:兩個並行請求同時 fetch reject 各自得到 NETWORK_ERROR,不呼叫 /api/v1/auth/refresh;既有 refresh 單飛測試維持綠"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/services/apiClient.test.ts#gives each concurrent request its own NETWORK_ERROR and never calls auth/refresh (VER-02 concurrency)"
        status: pass
      - kind: unit
        ref: "vue-app/src/services/apiClient.test.ts#shares one refresh request across parallel 401 responses"
        status: pass
    human_judgment: false
  - id: D3
    description: "SC2 F2:503 + application/json + 空 body → INVALID_JSON_RESPONSE 不崩潰;503 無 content-type 空 body → HTTP_ERROR、requestId null"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/services/apiClient.test.ts#apiClient 5xx 空 body(SC2 F2)"
        status: pass
    human_judgment: false
  - id: D4
    description: "baseCurrencyErrorKey 對 ApiClientError(NETWORK_ERROR) 回 baseCurrencyErrNetwork;Overview / Settings 網路失敗元件測試維持綠"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/baseCurrencyErrorKey.test.ts#transport 正規化的 NETWORK_ERROR(D-26)→ baseCurrencyErrNetwork,與非 ApiClientError 同鍵"
        status: pass
      - kind: unit
        ref: "vue-app/src/pages/Overview.test.ts#網路失敗:顯示 baseCurrencyErrNetwork 並回原值"
        status: pass
      - kind: unit
        ref: "vue-app/src/pages/Settings.test.ts#錯誤分派:網路失敗(fetch reject) → baseCurrencyErrNetwork"
        status: pass
    human_judgment: false
  - id: D5
    description: "mock 哨兵守門:MOCK_SENTINELS 每個值仍存在於 data.ts 匯出與 mock store 初始狀態,含偵測規則自我驗證"
    requirement: VER-03
    verification:
      - kind: unit
        ref: "vue-app/src/mockSentinels.test.ts#mock 哨兵守門(SC5 / D-20)"
        status: pass
    human_judgment: false
  - id: D6
    description: "SC5 18 組真後端矩陣:六頁 × 三種失敗皆顯示錯誤 / code / 重試、trace-id 依種類顯示或不渲染、目標區塊無 mock 哨兵、放行重試後顯示後端種下的真值"
    requirement: VER-03
    verification:
      - kind: e2e
        ref: "cd vue-app && npx playwright test --list e2e/tests/api-failure-states.spec.ts → Total: 18 tests in 1 file"
        status: pass
      - kind: e2e
        ref: "CI Browser E2E(真後端)— 18 個 S5 test 執行結果由 05-12 記錄 run id"
        status: unknown
    human_judgment: true
    rationale: "本機無 Docker、無法起真後端;只驗了 --list 與以 layout stub 代替後端的錯誤態半段(見 Verification)。真後端放行後的 assertReal 與平行隔離必須由 CI Browser E2E 證明(05-12)"

duration: 25min
completed: 2026-11-09
status: complete
---

# Phase 05 Plan 02: apiClient NETWORK_ERROR 正規化 + SC5 18 組失敗矩陣 Summary

**apiClient 以單一 transportFetch 把 fetch reject 正規化為 `ApiClientError(NETWORK_ERROR)`(AbortError 放行),補齊 SC2 F1 / F2 單元測試,並以 PAGES × FAILURES 表格驅動出 18 個真後端 Playwright test,證明六頁在 abort / 503 信封 / 502 HTML 下顯示錯誤與重試、不被 mock 回填、放行後顯示後端真值。**

## Performance

- **Duration:** 約 25 min
- **Started:** 2026-11-09T15:00Z(約)
- **Completed:** 2026-11-09T15:26Z
- **Tasks:** 3 / 3
- **Files:** 3 個新檔、5 個修改(前端 repo)

## Accomplishments

- `apiClient.ts`:新增模組私有 `transportFetch(path, init)`;`bootstrapCsrf`、`refreshSession`、`fetchWithSessionRecovery` 首發與重放四處改走它(`grep -cE "await fetch\(" = 1`,只剩 transportFetch 內一處)。訊息是靜態字串 `Network error`,不帶原例外訊息(T-05-04)。
- `baseCurrencyErrorKey.ts`:`!(error instanceof ApiClientError) || error.code === 'NETWORK_ERROR'` → `baseCurrencyErrNetwork`。
- `apiClient.test.ts`:+6 個案例(D-26 四案 + F2 兩案),32 → 34 → 全檔綠。
- `mockSentinels.ts` + `mockSentinels.test.ts`:哨兵集中管理與防空轉守門(4 個 test,含「偵測規則本身有效」)。
- `api-failure-states.spec.ts`:18 個 `S5 <page> × <kind>` test,名稱各不相同,tag `@extended`。

## Task Commits(前端 repo `/home/user/stock-web-v2-front-end`,分支 `claude/epic-curie-lj8alo`)

1. **Task 1(tracer)**
   - `be8a0d1` — `fix(05-02): apiClient 將網路中斷正規化為 NETWORK_ERROR`
   - `16f3289` — `test(05-02): SC5 失敗矩陣 tracer(positions × abort)`
2. **Task 2** — `2465adb` — `test(05-02): 補 apiClient 5xx 空 body 與 mock 哨兵守門測試`
3. **Task 3** — `2171269` — `test(05-02): SC5 失敗矩陣擴充為 18 組`

> commit 數量測:ledger `plan_head_before = 6a5986c` 到目前 HEAD 的 `git rev-list --count` = 7,其中 3 個是另一 lane 同時寫進前端 repo 的 05-03 commit(`6ad00ac`、`87eeff7`、`63afeb6`);本 plan 自己的 commit 為 `git log --grep='(05-02)'` = 4,`plan_head_after` 記本 plan 最後一個 commit `2171269`。

## Verification

| 檢查 | 結果 |
|------|------|
| `npx vitest run src/services/apiClient.test.ts`(verbose 含 NETWORK_ERROR 測試名) | 34 passed |
| `npx vitest run src/services/apiClient.test.ts src/baseCurrencyErrorKey.test.ts src/mockSentinels.test.ts` | 3 files / 46 passed |
| `npm run lint` | 0 errors(40 warnings 為既有 ratchet,本 plan 檔案 `npx eslint` 個別跑為 clean) |
| `npm test` | 65 files / 1337 passed |
| `npm run build`(vue-tsc + vite) | exit 0 |
| `VITE_DATA_MODE=api npm test` | 65 files / 1337 passed |
| `VITE_DATA_MODE=api npm run build` | exit 0 |
| `npx playwright test --list e2e/tests/api-failure-states.spec.ts` | `Total: 18 tests in 1 file` |
| spec 型別檢查(`tsc --strict --noUncheckedIndexedAccess`,單檔) | spec 本身 0 錯誤(僅 `fixtures.ts` 既有的 node 型別 / `Playwright` import 環境錯誤) |
| **本機 stub 演練**(gitignored `e2e/artifacts/scratch/`,跑完已刪除):同一 spec 改以 `portfolioStubs.mockPortfolioApi` 代替真後端、`route.continue()` → `route.fallback()`、略過 seed / assertReal | 18 組的**錯誤態**斷言全過(錯誤區塊、code `NETWORK_ERROR` / `SERVICE_UNAVAILABLE` / `HTTP_ERROR`、trace-id 顯示 `e2e-sc5-<page>` 或不渲染、重試鈕、畫面無 `Bad Gateway`、錯誤態區塊無哨兵),按重試後錯誤區塊消失也全過;9 個失敗**全部**在「放行後哨兵」那一行,原因是 layout stub fixture 本身含 NVDA / BTC / 2330.TW —— 這反過來證明哨兵偵測在真瀏覽器裡是活的 |

Acceptance greps:`transportFetch` = 5、`await fetch(` = 1、`NETWORK_ERROR`(apiClient.ts)= 4、`AbortError` = 6、`export const MOCK_SENTINELS` = 1、`route.continue` = 2、`NETWORK_ERROR`(baseCurrencyErrorKey.ts)= 2、`INVALID_JSON_RESPONSE`(apiClient.test.ts)= 3、`偵測規則` = 1、spec 內 `SERVICE_UNAVAILABLE` / `HTTP_ERROR` / `NETWORK_ERROR` 各 3、`header-session-identity` = 4、`locator('body')|getByRole('main')` 與 `not.toContainText` 同行 = 0。

Tracer gate:Task 1 commit 後重跑 `<verify>`(apiClient 單元測試 32 passed、`--list` 1 test),通過後才擴充。

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] HoldingEventDialog 拆股測試在假時鐘下 microtask 輪數不足**
- **Found during:** Task 1
- **Issue:** `recordsSplitAndTitlesResultFromBackendRatio` 用 `vi.useFakeTimers({ toFake: ['Date'] })`,`flushAsync` 只跑 microtask;transportFetch 多一層 `await` 後固定 6 輪不夠,`.result-ttl` 尚未渲染(連跑 3 次皆紅,確定非 flaky)。
- **Fix:** 依同檔既有 pattern(配股測試第 429-430 行)在 `submit()` 後補 `await flushAsync(16)`,附註解指向 LEARNINGS 2026-11-07。
- **Files modified:** `vue-app/src/components/HoldingEventDialog.test.ts`
- **Commit:** `be8a0d1`

**2. [Rule 3 - Blocking] baseCurrencyErrorKey 調整從 Task 2 提前到 Task 1 的 fix commit**
- **Found during:** Task 1
- **Issue:** transportFetch 上線後 `Overview.test.ts`「網路失敗:顯示 baseCurrencyErrNetwork」與 `Settings.test.ts`「錯誤分派:網路失敗」立刻紅(錯誤改以 ApiClientError 抵達,掉進 baseCurrencyErrUnknown)。
- **Fix:** 在同一個 fix commit 內完成 `baseCurrencyErrorKey.ts` 調整與其單元測試(先紅後綠),讓每個 commit 全綠;Task 2 commit 因此只含 F2 測試與哨兵守門。
- **Files modified:** `vue-app/src/baseCurrencyErrorKey.ts`、`vue-app/src/baseCurrencyErrorKey.test.ts`
- **Commit:** `be8a0d1`

**3. [Rule 1 - Bug] settings 列種子 TWD 會讓 assertReal 形同虛設**
- **Found during:** Task 3
- **Issue:** plan 寫 `PATCH /me/preferences { baseCurrency: 'TWD' }` 後斷言下拉為 `TWD`;但 TWD 是新帳號預設值,也是 Settings `ccySelected` 的初始值(`BASE_CURRENCY_OPTIONS[0]`),不論是否真的讀到後端都會通過。
- **Fix:** 種子改 `EUR`(`BASE_CURRENCY_SEED` 常數,註解寫明理由)。
- **Files modified:** `vue-app/e2e/tests/api-failure-states.spec.ts`
- **Commit:** `2171269`

**4. [Rule 1 - Robustness] AbortError 判斷改用 name 而非 `instanceof DOMException`**
- **Found during:** Task 1
- **Issue:** jsdom 與 Node(undici)各有自己的 DOMException 建構子,跨 realm 時 `instanceof` 可能誤判,把使用者取消誤報成 NETWORK_ERROR。
- **Fix:** `isAbortError(error)` 以 `isRecord(error) && error.name === 'AbortError'` 判斷;行為與 plan 的意圖等價。
- **Commit:** `be8a0d1`

**5. [Rule 2 - Robustness] landOnOverview 對所有列都等 Overview 的觀察清單載完**
- **Found during:** Task 3
- **Issue:** plan 只要求 watchlist 列「先等 Overview 載完」;但 overview-kpi 只代表 summary 完成,Overview 掛載時的 `GET /watchlists` 可能仍在途中。
- **Fix:** `landOnOverview` 依序等 `header-session-identity`、`overview-kpi`、`overview-watchlist-name`,六列共用。

**Total deviations:** 5 auto-fixed(2 × Rule 3、2 × Rule 1、1 × Rule 2)。**Impact:** 無範圍擴張;都是讓 commit 保持全綠或讓斷言具鑑別力。

## Known Stubs

None —— 本 plan 沒有新增 UI 或資料來源;各頁 `describeError` 對非 ApiClientError 的 `UNKNOWN_ERROR` fallback 依 plan 保留為防禦。

## Threat Flags

None —— 沒有新的網路端點、auth 路徑或信任邊界;T-05-03 / T-05-04 依 threat register 已緩解(哨兵斷言 + 守門測試;transportFetch 靜態訊息 + 502 E2E 斷言畫面不含 `Bad Gateway`)。

## Issues Encountered / Left for CI

- **真後端執行證據待 CI(05-12):** 18 個 `S5 …` test 只做了 `--list` 與 stub 演練;放行後 `assertReal`(AAPL 3 股、`sc5-seed` 入金、觀察清單 AAPL、Settings `EUR`)、真後端 seed 寫入、以及 `fullyParallel` / `workers: 4` 下的隔離,都要等 CI Browser E2E 跑出結果。特別留意:settings 列的 `GET /me` 若在 nav 之後被其他元件再次呼叫,會在失敗階段被攔(研究已評估 session bootstrap 只發生一次)。
- **Node 20(CI)microtask 敏感度:** 本機只有 Node 22。transportFetch 多一層 await;真計時器下 `flushAsync` 有 macrotask hop 不受影響,但其他用假計時器的送出路徑測試在 Node 20 可能較緊(LEARNINGS 2026-09-02 / 2026-11-07)。若 CI 單元測試紅,先看是否為假時鐘 + 固定輪數。
- **另一 lane 的檔案:** 執行期間前端 repo 出現 `playwright.config.ts`、`vite.config.ts`、`viteConfig.test.ts`、`package.json` 的 05-03 變更與 commit,本 plan 未觸碰也未 stage。

## Next Phase Readiness

- 05-12 可直接在 CI Browser E2E 中以 `--grep "S5 "` 找到 18 個 test 並記錄 run id。
- `NETWORK_ERROR` 已是六頁一致的網路中斷診斷碼,後續 UI 文案若要對它分流,可沿用 `baseCurrencyErrorKey` 的寫法。

## Self-Check: PASSED

- FOUND: vue-app/e2e/support/mockSentinels.ts
- FOUND: vue-app/e2e/tests/api-failure-states.spec.ts
- FOUND: vue-app/src/mockSentinels.test.ts
- FOUND commits: be8a0d1、16f3289、2465adb、2171269(皆為 HEAD 祖先)
