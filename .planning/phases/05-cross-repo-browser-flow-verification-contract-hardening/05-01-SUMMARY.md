---
phase: 05-cross-repo-browser-flow-verification-contract-hardening
plan: 01
subsystem: testing
tags: [playwright, e2e, browser-auth, cookie, csrf, idempotency, contract-journey, smoke]

requires:
  - phase: 04.2
    provides: "e2e/support/fixtures.ts(uniqueAccount / loginViaUi)、trade.spec.ts 的 OrderTicket helper、六頁 testid"
  - phase: 05-03
    provides: "playwright.config.ts webServer 以 VITE_API_PROXY_TARGET 指向 E2E 後端(預設 8080);run-e2e 無參數跑全部;前端 CI browser-e2e job"
provides:
  - "e2e/tests/contract-journey.spec.ts:單一 test `J0 登入 → /me → 讀持倉 → 記錄手動交易 → 三頁重讀 → 登出(契約全程) @smoke`,8 個 test.step,真後端、不攔截"
  - "檔內 helper:parseCookieHeader(header) → Map、assertContract(records, secrets) → 違規字串陣列(逐條 METHOD URL)、私有 openTicketFromTrades / pickSymbol / submitTrade(回傳 POST /trades Request)/ holdingCount / holdingQuantity"
  - "selectors.md 註記 J0 未新增 testid"
  - "LEARNINGS:UI 登入後已掛載頁面不重讀的 E2E 坑"
affects: [05-12, 05-24, frontend-ci-browser-e2e, backend-browser-e2e]

actuals:
  tokens: 4700
  tasks: 2
  commits: 3
plan_head_before: 63afeb635ec0ce0c60343c6a92bbc77e7409d99e
plan_head_after: 41469e151f39fcc6529c0531ecb74ba41fadd88d
commits: 3

tech-stack:
  added: []
  patterns:
    - "phase 以 page.on('response') 在登入 / 登出回應事件當下切換,不等 click / waitForResponse 的 await 鏈 —— 避免登出後的匿名請求被誤歸 authed"
    - "allHeaders() 先 .catch(() => null) 存 Promise,彙總時把取不到 headers 的請求列為違規(fail loud,不吞)"
    - "CSRF 比對一律用同一請求 cookie header 解析出的 XSRF-TOKEN,不用事後 context.cookies()"
    - "數量斷言用 toHaveText(/\\s3(?:\\.0+)?\\s/, { useInnerText: true }):innerText 以 tab 分隔儲存格,只配到獨立的數量格,且相容 Wave 2 金額字串化後的小數補零"

key-files:
  created:
    - /home/user/stock-web-v2-front-end/vue-app/e2e/tests/contract-journey.spec.ts
  modified:
    - /home/user/stock-web-v2-front-end/vue-app/e2e/support/selectors.md
    - /home/user/stock-web-v2-front-end/docs/LEARNINGS.md

key-decisions:
  - "phase 切換放在 page.on('response') handler(plan 寫「收到回應後才設 authed」,handler 是最精準的實作點),並在 step 內 expect(phase) 確認"
  - "交易前讀取順序改為 Positions → Overview(plan 寫 Overview → Positions):未登入時 Overview 已掛載並進入 401 錯誤態,UI 登入後不重讀,停在原頁看不到 overview-kpi;切頁重新掛載後才是登入後的真讀取"
  - "Overview 的 holdingCount 0 → 1 同時以「瀏覽器實際收到的 summary 回應」與 page.request 重讀雙重斷言;Positions 的數量 3 同時以 UI 列與 GET /portfolio/holdings 斷言"
  - "(c) 的 token 檢查同時涵蓋 stock_access 與 stock_refresh 的值(plan 只要求 access,refresh 一併檢查更嚴)"

patterns-established:
  - "真後端 journey 的彙總契約斷言:收集 → 結束時一次解析 → 違規逐條列 METHOD URL"

requirements-completed: [VER-03]

coverage:
  - id: D1
    description: "J0 單一串連 journey(SC3):登入 → /me → Overview + Positions 讀取 → OrderTicket 手動交易 → Trades / Positions / Overview 三頁重讀 → 登出,@smoke"
    requirement: VER-03
    verification:
      - kind: e2e
        ref: "cd vue-app && npx playwright test --list e2e/tests/contract-journey.spec.ts → Total: 1 test in 1 file"
        status: pass
      - kind: e2e
        ref: "本機有狀態假後端演練(gitignored scratch,跑完已刪):J0 全程 passed(2.2s)"
        status: pass
      - kind: e2e
        ref: "CI Browser E2E(真後端)— J0 passed,由 05-12 記錄 run id"
        status: unknown
    human_judgment: true
    rationale: "本機無 Docker,無法起真後端;假後端只能證明選擇器、步驟順序與斷言邏輯正確,不能證明真後端行為(e2e-browser seed 的 AAPL、真 summary / holdings 計算、tokenVersion 撤銷)。真後端證據須由 CI 提供(05-12)"
  - id: D2
    description: "SC4 嵌入同一條 journey:stock_access / stock_refresh httpOnly + SameSite Lax + path /;document.cookie 與 web storage 不含 token;POST /trades 的 X-XSRF-TOKEN = 同請求 XSRF-TOKEN cookie;登出後 /me 401 失敗信封(cookie jar 與舊 Cookie header out-of-band 兩種)"
    requirement: VER-03
    verification:
      - kind: e2e
        ref: "假後端演練:各 step passed"
        status: pass
      - kind: e2e
        ref: "CI Browser E2E(真後端)— 由 05-12 記錄"
        status: unknown
    human_judgment: true
    rationale: "同 D1:cookie 屬性與伺服器端撤銷的權威證據必須來自真後端 e2e-browser profile"
  - id: D3
    description: "D-12 彙總斷言:(a) authed 非公開端點帶 stock_access (b) 每個 unsafe 請求 x-xsrf-token = 同請求 XSRF-TOKEN cookie (c) 無 Authorization: Bearer、token 不入 URL / body;違規逐條列 METHOD URL"
    requirement: VER-03
    verification:
      - kind: e2e
        ref: "假後端負向演練:注入 credentials:'omit' 的 authed GET、帶 Bearer 的 GET、無 CSRF 的 POST → 三條違規 (a)(b)(c) 皆被列出且 J0 失敗;移除注入後 J0 passed"
        status: pass
    human_judgment: false
  - id: D4
    description: "VER-03 idempotency:UI 送出的 POST /api/v1/trades 恰帶一個非空 Idempotency-Key(headersArray 計數);每次執行以 uniqueAccount 註冊新帳號,retries: 1 重跑不撞資料"
    requirement: VER-03
    verification:
      - kind: e2e
        ref: "假後端演練 passed;真後端由 CI(05-12)"
        status: pass
    human_judgment: false

duration: 25min
completed: 2026-11-09
status: complete
---

# Phase 05 Plan 01: 契約全程 journey(J0)Summary

**新增單一 Playwright test J0(@smoke、真後端、零攔截):同一個 session 內從 UI 登入走到登出,8 個 test.step 涵蓋 SC3 六段(登入 → /me → 讀取 → 手動交易 → 三頁重讀 → 登出)。SC4 的 cookie 屬性、CSRF 同請求比對、登出後兩種 401 也在這條流程裡斷言,最後再以 `page.on('request')` + `allHeaders()` 對全程 /api/v1 請求一次檢查 credentials / CSRF / 無 Bearer。**

## Performance

- **Duration:** 約 25 min
- **Started:** 2026-11-09T15:13Z(約)
- **Completed:** 2026-11-09T15:38Z
- **Tasks:** 2 / 2(另含 1 個 Rule 1 fix commit)
- **Files:** 1 個新檔、2 個修改(前端 repo)

## Accomplishments

- `contract-journey.spec.ts`:test.step 依序為「登入」「auth cookie 為 httpOnly 且 JS 讀不到」「/me 回傳目前使用者」「交易前讀 Overview 與 Positions」「以 OrderTicket 記錄手動交易(已成交紀錄,不是委託單)」「交易後三頁重讀到新值」「登出後 /me 401(cookie jar 與舊 cookie 兩種)」「全程 /api/v1 請求符合瀏覽器 auth 契約(D-12)」。
- 交易前:Positions 讀 holdings 200 → `positions-holdings-empty`、無 AAPL 列;Overview 讀 summary 200 → 瀏覽器收到 `holdingCount === 0`、`overview-kpi` 可見、page.request 重讀也是 0。
- 交易:AAPL × 3;`POST /api/v1/trades` 的 `headersArray()` 恰一個非空 `idempotency-key`;`x-xsrf-token` 等於同請求 cookie 的 `XSRF-TOKEN`,且帶 `stock_access`;回應 ok;`ticket-result-trade-id` 符合 UUID,`ticket-result-qty` 為 `3`。
- 重讀:Trades `trades-fresh-badge` 可見、第一列 `trades-row` 含 AAPL;Positions(等 holdings 200)AAPL 列 1 筆,innerText 中有獨立的數量格 `3`,`GET /portfolio/holdings` 的 AAPL `totalQuantity === 3`;Overview(等 summary 200)瀏覽器收到 `holdingCount === 1`、`overview-recent-trades` 含 AAPL、page.request 重讀也是 1。
- 登出:擷取舊 Cookie header → 點 `header-logout` 並等 logout 200 → `page.request.get('/api/v1/me')` 回 401 且 `success === false`、`error.code` 與 `meta.traceId` 為字串;out-of-band context 帶舊 Cookie header 也是同樣的 401 失敗信封(證明伺服器端撤銷)。
- 檔案中沒有 `page.route` / `route.fulfill`,也沒有 pending / cancel / routing 等委託單用語。

## Task Commits(前端 repo `/home/user/stock-web-v2-front-end`,分支 `claude/epic-curie-lj8alo`)

1. **Task 1(tracer)** — `3b1c62c` — `test(05-01): 新增契約全程 journey 骨架(登入 / cookie / 登出 401)`
2. **Task 2** — `39234d6` — `test(05-01): journey 補上持倉讀取、手動交易與三頁重讀`
3. **Rule 1 fix** — `41469e1` — `fix(05-01): J0 交易前讀取改為切頁重新掛載 Overview`(含 LEARNINGS 一行)

> `git rev-list --count 63afeb6..HEAD` = 3,全部是本 plan 的 commit(執行期間另一 lane 沒有 commit 進前端 repo)。

## Verification

| 檢查 | 結果 |
|------|------|
| `npx playwright test --list e2e/tests/contract-journey.spec.ts`(Task 1、Task 2、fix 後各跑一次) | `Total: 1 test in 1 file`,exit 0 |
| spec 型別檢查(`tsc --ignoreConfig --strict --noUncheckedIndexedAccess`,單檔) | spec 本身 0 錯誤(只剩 `fixtures.ts` 既有的 `Playwright` import / `process` 型別環境錯誤,與 05-02 相同) |
| `npx prettier --check e2e/tests/contract-journey.spec.ts` | clean |
| `npm run lint` | 0 errors(40 warnings 是既有的 ratchet) |
| `npm test` | 65 files / 1337 passed |
| `npm run build`(vue-tsc + vite) | exit 0 |
| **本機假後端演練**(scratch:Node 有狀態假後端 `:18080`,處理 cookie / CSRF / 撤銷 / 冪等;`vite build --outDir` 加上 `vite preview --port 4273`;跑完已刪除 `e2e/artifacts/scratch/`) | 第一次在「交易前讀 Overview」失敗(見 Deviation 1);修正後 **J0 passed(2.2s)**。phase 歸類實測正確:anon 7 筆(含初始 `/me` 401 → `POST /auth/refresh`)、authed 18 筆(全帶 `stock_access`)、loggedOut 1 筆 |
| **負向演練**(假後端,暫時注入違規請求) | `(a) 缺少 stock_access cookie:GET …/portfolio/summary`、`(c) 帶 Authorization: Bearer:GET …/me`、`(b) 缺少 x-xsrf-token:POST …/trades` 三條都被列出,J0 失敗;證明彙總斷言真的會擋 |

Acceptance greps:`allHeaders` = 3、`page.route|route.fulfill` = 0、`@smoke` = 1、`httpOnly` = 3、`x-xsrf-token` = 6、`test.step` = 8、`idempotency-key` = 3、`holdingCount` = 8、`/api/v1/portfolio/holdings` = 3、`pending|cancel`(-i)= 0、selectors.md `contract-journey` = 1。

Tracer gate:Task 1 commit 後重跑 `<verify>`(`--list` 1 test)通過,才進 Task 2。

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] 交易前在原頁斷言 `overview-kpi` 在 UI 登入後必失敗**
- **Found during:** Task 2 之後的假後端演練
- **Issue:** API mode 未登入時,`App.vue` 照樣掛載 Overview,它一進站就打 summary / trades / watchlists,收到 401 → refresh 失敗後停在錯誤態(`AUTH_REFRESH_TOKEN_INVALID`)。UI 登入後已掛載的頁面不會自動重讀,所以 plan 的「交易前:Overview `overview-kpi` 可見」在真後端也一定失敗。這是 app 本身的行為,與假後端無關。
- **Fix:** 交易前讀取改成先切 Positions、再回 Overview,兩頁都在登入後重新掛載,並以瀏覽器實際收到的 summary 回應斷言 `holdingCount === 0`。app 的 UX 缺陷屬範圍外,記入 `deferred-items.md`,並在前端 `docs/LEARNINGS.md` 補一行。
- **Files modified:** `vue-app/e2e/tests/contract-journey.spec.ts`、`docs/LEARNINGS.md`
- **Commit:** `41469e1`

**2. [Rule 1 - Robustness] phase 改在 response 事件當下切換**
- **Found during:** Task 1
- **Issue:** 如果等 `click` / `waitForResponse` 的 await 鏈回來才設 `phase = 'loggedOut'`,登出回應之後、await 回來之前發出的匿名請求會被歸成 authed,(a) 就會誤報。
- **Fix:** 用 `page.on('response')`,在 login / logout 200 回應事件發生時同步切換 phase;各 step 內再以 `expect(phase)` 確認。
- **Commit:** `3b1c62c`

**3. [Rule 2 - Robustness] `allHeaders()` rejection 先接住**
- **Found during:** Task 1
- **Issue:** 存起來的 Promise 如果 reject 而沒有 handler,會變成 unhandled rejection。
- **Fix:** 改存 `.catch(() => null)`,彙總時把取不到 headers 的請求列為違規(fail loud)。
- **Commit:** `3b1c62c`

**Total deviations:** 3 auto-fixed(1 × Rule 1 bug、1 × Rule 1 robustness、1 × Rule 2)。**Impact:** 沒有擴大範圍。另外多出一個檔案 `docs/LEARNINGS.md`,不在 plan 的 `files_modified` 內,依專案規範「踩坑一行進 LEARNINGS」加入。

## Known Stubs

None。

## Threat Flags

None。沒有新的端點或信任邊界。T-05-01 / T-05-02 已依 threat register 由 J0 的斷言緩解:httpOnly、document.cookie / storage、Bearer、URL / body 不含 token、CSRF 同請求比對。

## 與 05-03 並行的說明

- 沒有修改 05-03 的檔案(`playwright.config.ts`、`vite.config.ts`、`run-e2e.*`、`package.json`、CI yml)。J0 沿用現行 config 的 `baseURL: http://localhost:4173`、`retries: 1`、`workers: 4`、`fullyParallel`;webServer 以 `VITE_API_PROXY_TARGET=http://localhost:${E2E_BACKEND_PORT ?? 8080}` 代理到 E2E 後端。J0 只依賴這些現行設定。
- J0 是 `@smoke`,`run-e2e` 無參數(全部 tag)或 `--grep @smoke` 都會跑到。

## Left for CI / 05-12 要收集的證據

- **Test title(精確):** `J0 登入 → /me → 讀持倉 → 記錄手動交易 → 三頁重讀 → 登出(契約全程) @smoke`,檔案 `vue-app/e2e/tests/contract-journey.spec.ts`。可用 `--grep "J0 "` 篩選。
- **來源 job:** 後端 repo 的 Browser E2E job(以同名分支取前端),以及 05-03 之後前端 repo 的 browser-e2e job。兩者都要記 run id 與 J0 結果(passed / flaky)。
- **報告位置:** Playwright HTML report `vue-app/e2e/artifacts/playwright-report/`,失敗時的 trace / video / screenshot 在 `vue-app/e2e/artifacts/test-results/`(以 CI artifact 上傳的名稱為準)。
- **真後端才會暴露的風險點(若 J0 紅,先查這些):**
  1. `pickSymbol('AAP', 'AAPL')` 依賴 e2e-browser seed 的 AAPL 可搜尋且 tradeable(與 T1 相同前提)。
  2. `stock_access` / `stock_refresh` 的 `sameSite` / `path` 取 `BrowserAuthCookieProperties` 預設(`Lax`、`/`);如果 e2e-browser profile 覆寫了,cookie step 會紅。
  3. 登出 out-of-band 401 依賴 tokenVersion 撤銷(05-04 `cookieLogoutRevokesAccessCookieForMe` 的瀏覽器版)。
  4. 初始匿名 `GET /me` 401 會觸發 `POST /auth/refresh`(anon,仍帶 CSRF);若真後端對無 cookie 的 refresh 回非 401,只影響 UI 錯誤態,不影響 J0 斷言。
  5. 金額硬切(Wave 2 D-24)之後,`totalQuantity` 如果變成字串 `"3.00000000"`,UI 的 regex 和 `Number()` 轉換都相容;`holdingCount` 仍是整數。
- **範圍外待辦:** `deferred-items.md`(本 plan 新增,未 commit):UI 登入後已掛載頁面不重讀的 UX 缺陷。

## Self-Check: PASSED

- FOUND: /home/user/stock-web-v2-front-end/vue-app/e2e/tests/contract-journey.spec.ts
- FOUND: /home/user/stock-web-v2-front-end/vue-app/e2e/support/selectors.md(含 `contract-journey`)
- FOUND commits: 3b1c62c、39234d6、41469e1(都是 HEAD 的祖先)
