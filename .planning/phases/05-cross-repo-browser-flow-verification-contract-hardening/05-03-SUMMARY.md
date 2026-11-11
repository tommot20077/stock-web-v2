---
phase: 05-cross-repo-browser-flow-verification-contract-hardening
plan: 03
subsystem: ci
tags: [vite, proxy, playwright, github-actions, browser-e2e, docs]

requires: []
provides:
  - "VITE_API_PROXY_TARGET:vite dev / preview 的 /api、/ws proxy 目標(預設 http://localhost:11180),Playwright webServer 設為 http://localhost:${E2E_BACKEND_PORT ?? 8080}"
  - "viteConfig.test.ts F4 缺口:預設 11180、env 覆寫 dev 與 preview、/ws ws === true"
  - "run-e2e.sh / run-e2e.ps1 無參數跑全部 Playwright(@smoke + @extended)+ 4173 佔用檢查;npm script test:e2e = playwright test"
  - "後端 CI step 名稱 Run Browser E2E (all tags)"
  - "前端 CI browser-e2e job(Browser E2E (real backend)):同名後端分支 / develop fallback、github.token、上傳 browser-e2e-artifacts"
affects: [05-06, 05-12, frontend-ci, backend-ci, local-dev-docs]

actuals:
  tokens: 9330
  tasks: 3
  commits: 5
plan_head_before: "backend e46b883ee793f7e4c2d4e32b3aa03446bf22b9bc / frontend 2465adbe3ec266e6c9b19749cd524f699dc7e31f"
plan_head_after: "backend 304e39d(05-03 最後一個 task commit;HEAD 時為 3125659,含 05-02 摘要) / frontend 63afeb635ec0ce0c60343c6a92bbc77e7409d99e"

tech-stack:
  added: []
  patterns:
    - "Node 端 env 驅動的 Vite proxy:模組頂層讀 process.env,維持 defineConfig 物件形式(測試可直接 import 設定物件),以 vi.stubEnv + vi.resetModules + 動態 import 測 env 覆寫"
    - "未裝 @types/node 時,以模組範圍 `declare const process: { env: Record<string, string | undefined> }` 提供最小型別"

key-files:
  created: []
  modified:
    - /home/user/stock-web-v2-front-end/vue-app/vite.config.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/viteConfig.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/playwright.config.ts
    - /home/user/stock-web-v2-front-end/vue-app/e2e/run-e2e.sh
    - /home/user/stock-web-v2-front-end/vue-app/e2e/run-e2e.ps1
    - /home/user/stock-web-v2-front-end/vue-app/package.json
    - /home/user/stock-web-v2-front-end/vue-app/e2e/README.md
    - /home/user/stock-web-v2-front-end/.github/workflows/ci.yml
    - /home/user/stock-web-v2/.github/workflows/ci.yml
    - /home/user/stock-web-v2/README.md
    - /home/user/stock-web-v2/docs/guides/development.md
    - /home/user/stock-web-v2/docs/guides/getting-started.md
    - /home/user/stock-web-v2/docs/testing/overview.md

key-decisions:
  - "D-21 env 名稱定為 VITE_API_PROXY_TARGET,只讀 shell env(不用 loadEnv,避免改成函式形式破壞 viteConfig.test 的物件 import);/ws 用 http target + ws: true(RESEARCH A4,若 CI watchlist-live 斷線再改 ws://)"
  - "vite.config.ts 以模組範圍 declare const process 解決 vue-tsc 找不到 process(不新增 @types/node 依賴)"
  - "前端 browser-e2e job 額外沿用後端範本的 Maven wrapper 快取 + 下載重試 step,降低 Maven 本體下載 rate limit 造成的 flake"

patterns-established:
  - "跨 repo browser E2E job 雙向鏡像:各自以 git ls-remote 解析對方 repo 同名分支,fallback develop,重用同一支 run-e2e.sh(無參數 = 全部 tag)"

requirements-completed: [VER-02, VER-03]

coverage:
  - id: D1
    description: "vite dev / preview 的 /api、/ws proxy 目標讀 VITE_API_PROXY_TARGET,未設為 http://localhost:11180,設定後兩個 server 皆改用該值,/ws ws === true(F4)"
    requirement: "VER-02"
    verification:
      - kind: unit
        ref: "cd vue-app && npx vitest run src/viteConfig.test.ts → 4 passed(RED:實作前 2 failed / 2 passed)"
        status: pass
      - kind: other
        ref: "vite loadConfigFromFile:無 env → 11180;VITE_API_PROXY_TARGET=http://localhost:8080 → dev 與 preview 皆 8080"
        status: pass
    human_judgment: false
  - id: D2
    description: "playwright.config.ts webServer env 帶 VITE_API_PROXY_TARGET=http://localhost:${E2E_BACKEND_PORT ?? 8080},設定可載入"
    requirement: "VER-03"
    verification:
      - kind: other
        ref: "npx playwright test --list e2e/tests/smoke.spec.ts → Total: 2 tests in 1 file"
        status: pass
      - kind: e2e
        ref: "真後端 browser E2E 在 CI 執行(本機無 Docker daemon),由 05-12 記錄"
        status: unknown
    human_judgment: true
    rationale: "run-e2e.sh 需 docker compose,本機無法執行;preview proxy 指向 8080 的端到端證據只能來自 CI"
  - id: D3
    description: "run-e2e.sh / .ps1 無參數跑全部、4173 佔用即中止;test:e2e = playwright test;後端 CI step 改名"
    requirement: "VER-03"
    verification:
      - kind: other
        ref: "bash -n e2e/run-e2e.sh;node 檢查 test:e2e === 'playwright test';非註解行 --grep @smoke(sh)= 0、@smoke(ps1)= 0;grep -c 4173 run-e2e.sh = 3;後端 YAML 解析 browser-e2e 有 'Run Browser E2E' 且無 @smoke 的 step;grep -c 'Run Browser E2E (@smoke)' = 0"
        status: pass
      - kind: other
        ref: "4173 檢查行為測試(scratch 副本改用 14173 避免干擾並行 executor):有 listener → exit 1 並印中止訊息;無 listener → 通過檢查進入 compose 步驟"
        status: pass
    human_judgment: false
  - id: D4
    description: "前端 CI 新增 browser-e2e job(needs: frontend、contents: read、timeout 30、ls-remote 解析後端分支、run-e2e.sh、上傳 artifacts、無 secrets)"
    requirement: "VER-03"
    verification:
      - kind: other
        ref: "python3 yaml.safe_load 檢查 needs == frontend、permissions.contents == read、有 run-e2e.sh step;grep -c browser-e2e = 2、ls-remote = 1、'secrets\\.' = 0"
        status: pass
      - kind: other
        ref: "前端 PR 的 browser-e2e job 出現並完成(05-12 記錄結論與 run id)"
        status: unknown
    human_judgment: true
    rationale: "GitHub Actions 只能在 push 後驗證;本機無 actionlint"
  - id: D5
    description: "後端 README / development / getting-started 的本機聯調 port 改為 11180 預設,E2E 8080 由 Playwright 設定"
    verification:
      - kind: other
        ref: "grep -h SERVER_PORT=8080 三份文件 | grep -vc e2e-browser = 0(baseline 5);三份文件 11180 合計 16(≥ 3)"
        status: pass
    human_judgment: false

duration: 10min
completed: 2026-11-09
status: complete
---

# Phase 05 Plan 03: Vite proxy 目標 env 化 + 兩 repo CI 跑全部 tag 的真後端 browser E2E Summary

**Vite dev / preview proxy 改讀 `VITE_API_PROXY_TARGET`(預設 `http://localhost:11180`,Playwright 設為 8080);`run-e2e` 無參數改跑全部 Playwright 並加 4173 佔用檢查;前端 CI 新增 `browser-e2e` job 以預設 `github.token` checkout 後端同名分支跑真後端 E2E。**

## Performance

- **Duration:** 約 10 min
- **Started:** 2026-11-09T15:21:14Z
- **Completed:** 2026-11-09T15:30Z(約)
- **Tasks:** 3 / 3
- **Files modified:** 13(前端 8、後端 5)

## Accomplishments

- `vite.config.ts`:模組頂層 `const proxyTarget = process.env.VITE_API_PROXY_TARGET ?? 'http://localhost:11180'`,`/api` 與 `/ws`(`ws: true`)共用,dev 5173 / preview 4173 共用;維持 `defineConfig({...})` 物件形式;檔頭註解說明預設來源、E2E 8080、只讀 shell env、不進 bundle。
- `viteConfig.test.ts`:新增 2 個案例(預設 11180、env 覆寫 8080,皆檢查 dev 與 preview 的 `/api`、`/ws` 與 `ws === true`),`afterEach` `vi.unstubAllEnvs()` + `vi.resetModules()`。
- `playwright.config.ts`:webServer `env.VITE_API_PROXY_TARGET = http://localhost:${E2E_BACKEND_PORT ?? '8080'}`。
- `run-e2e.sh` / `run-e2e.ps1`:移除無參數注入 `--grep @smoke`;8080 health 檢查旁新增 4173 檢查(有回應即中止,訊息提示 reuseExistingServer 可能重用指向 11180 的舊 preview)。`package.json` `test:e2e` = `playwright test`;`e2e/README.md` 同步。
- 後端 CI:step 改名 `Run Browser E2E (all tags)`,註解改為無參數 = 全部 tag。
- 前端 CI:新 job `browser-e2e`(`Browser E2E (real backend)`、`needs: frontend`、`timeout-minutes: 30`、`permissions: contents: read`),steps:checkout 前端到 `frontend/` → `Resolve backend ref (match frontend branch, else develop)`(`git ls-remote --exit-code --heads`)→ checkout `tommot20077/stock-web-v2` 到 `backend/` → setup-java 21 temurin(maven cache)→ Maven wrapper 快取 + 下載重試 → setup-node 20 → `npm ci` → `npx playwright install --with-deps chromium` → `Run Browser E2E (all tags)`(`bash e2e/run-e2e.sh`,`E2E_BACKEND_DIR=${{ github.workspace }}/backend`)→ `if: always()` 上傳 `browser-e2e-artifacts`。job 上方註解:後端 public、用預設 `github.token`、轉 private 時的處置。
- 後端文件:README、development、getting-started 改寫為 `$VITE_API_PROXY_TARGET` 預設 11180,本機聯調不必設 `SERVER_PORT`;getting-started 路徑 A 改用預設 port 啟動、路徑 B(e2e-browser 8080)dev server 改帶 `VITE_API_PROXY_TARGET=http://localhost:8080`、run-e2e 流程補 4173 檢查與 proxy env、疑難排解新增 4173 中止條目、CI 段補前端 `browser-e2e` job。

## Task Commits

| Task | Repo | Commit | Subject |
|------|------|--------|---------|
| 1(tracer) | 前端 | `6ad00ac` | feat(05-03): Vite 代理目標改讀 VITE_API_PROXY_TARGET,預設 11180 |
| 2 | 前端 | `87eeff7` | ci(05-03): run-e2e 無參數改跑全部 Playwright |
| 2 | 後端 | `cf1459a` | ci(05-03): Browser E2E step 改為全部 tag |
| 3 | 前端 | `63afeb6` | ci(05-03): 前端 CI 新增真後端 browser-e2e job |
| 3 | 後端 | `304e39d` | docs(05-03): 本機聯調 port 改為 11180 預設 |

`commits: 5` 只計 05-03 的 commit。ledger 區間的 `git rev-list --count` 為後端 3、前端 4,多出的 `3125659 docs(05-02)` 與 `2171269 test(05-02)` 是並行 executor 05-02 的 commit,與本 plan 交錯。

## Verification Evidence

- RED:新增測試、尚未實作時 `npx vitest run src/viteConfig.test.ts` → `Tests 2 failed | 2 passed (4)`(`expected 'ws://localhost:8080' to be 'http://localhost:8080'`)。
- GREEN:同指令 → `Tests 4 passed (4)`。
- tracer gate(端到端重跑):vitest 4 passed;`npx playwright test --list e2e/tests/smoke.spec.ts` → `Total: 2 tests in 1 file`;`vite.loadConfigFromFile` 無 env → dev / preview 皆 `http://localhost:11180`,`VITE_API_PROXY_TARGET=http://localhost:8080` → 皆 8080。
- `npx vue-tsc --noEmit` → exit 0(加 `declare const process` 前為 TS2591)。
- 前端標準閘門:`npm run lint` → 0 errors(40 warnings,皆為既有 `no-explicit-any` ratchet,本 plan 檔案 eslint 無輸出);`npm test` → `Test Files 65 passed (65)`、`Tests 1337 passed (1337)`;`npm run build` → `✓ built`。
- Task 2 / Task 3 的 `<verify>` 與 acceptance grep 全數 PASS(數值見 coverage D3 / D4 / D5)。
- 4173 檢查行為:scratch 副本(port 改 14173)有 listener → `ERROR: 4173 已有 vite preview…`、exit 1;無 listener → 進入 `啟動 compose infra…`。
- 兩份 ci.yml 均可被 PyYAML 解析;本機無 `actionlint`、無 Docker daemon,workflow 未實際執行。

## Decisions Made

- `VITE_API_PROXY_TARGET` 只讀 shell env;不改函式形式、不用 `loadEnv`(會破壞 `viteConfig.test.ts` 的物件 import)。
- `/ws` 維持 http target + `ws: true`(RESEARCH A4);若 CI 上 `watchlist-live.spec.ts` 因 WS 斷線失敗,改為 `target: proxyTarget.replace(/^http/, 'ws')`。
- 4173 檢查在 sh 版沿用既有 8080 檢查的 `BACKEND_PID=""; E2E_KEEP=1; exit 1` 寫法(中止時不做 compose down,因為尚未啟動任何東西)。

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] vue-tsc 找不到 `process`**
- **Found during:** Task 1
- **Issue:** 專案未安裝 `@types/node`,`tsconfig.json` 經 `src/viteConfig.test.ts` 的 import 型別檢查 `vite.config.ts`,`process.env` 觸發 TS2591,`npm run build` 會失敗。
- **Fix:** 在 `vite.config.ts` 加模組範圍 `declare const process: { env: Record<string, string | undefined> }`(只有型別、不產生程式碼),不新增依賴。
- **Files modified:** vue-app/vite.config.ts
- **Commit:** 6ad00ac

**2. [Rule 1 - Bug(文件)] getting-started 路徑 B 在新預設下會連錯 port**
- **Found during:** Task 3
- **Issue:** 路徑 B 以 e2e-browser profile 把後端開在 8080 後執行 `VITE_DATA_MODE=api npm run dev`,proxy 預設改為 11180 後會連不到後端。
- **Fix:** 指令改為 `VITE_API_PROXY_TARGET=http://localhost:8080 VITE_DATA_MODE=api npm run dev` 並加說明;路徑 A 改用預設 11180 啟動後端(不再 `SERVER_PORT=8080`)。
- **Files modified:** docs/guides/getting-started.md
- **Commit:** 304e39d

**3. [Rule 1 - Bug(文件)] `docs/testing/overview.md` 仍寫「預設只跑 @smoke」、「@extended 不在 CI 執行」**
- **Found during:** Task 3(grep 全 repo 的 `@smoke` 預設敘述)
- **Issue:** 不在 plan 檔案清單,但 D-14 改動後 3 處敘述變成錯的(:141、:151、:298),另「前端 repo 自己的 CI」段未提 browser-e2e。
- **Fix:** 只改這幾行(05-06 負責該檔的 :25-75、:200-215,沒有重疊;05-06 在 wave 2,不與本 plan 並行)。
- **Files modified:** docs/testing/overview.md
- **Commit:** 304e39d

**4. [Rule 2 - Robustness] 前端 browser-e2e job 加 Maven wrapper 快取 + 下載重試**
- **Found during:** Task 3
- **Issue:** plan 的 step 清單沒有這兩步,但 `run-e2e.sh` 會執行 `./mvnw`,後端範本已為 Maven 本體下載 rate limit 加了快取與重試。
- **Fix:** 鏡像後端範本的 `Cache Maven wrapper distribution`、`Download Maven distribution (retry on rate limit)`(路徑改為 `backend/`);setup-java 加 `cache-dependency-path: backend/**/pom.xml`。
- **Files modified:** .github/workflows/ci.yml(前端)
- **Commit:** 63afeb6

其他:Task 1 對 `viteConfig.test.ts` 執行 Prettier,既有第二個測試的一行被重新折行(baseline 本來就不符 Prettier,純格式)。

## Issues Encountered

- 本機無 Docker daemon、無 actionlint、無 pwsh:`run-e2e.sh` 全流程、`run-e2e.ps1`、兩份 workflow 都無法在本機執行;ps1 只做了 diff 檢視(BOM 保留)。
- `.planning/codebase/CONCERNS.md:153` 仍寫「`run-e2e.sh` 預設只跑 `@smoke`」:是 codebase map 快照,不在本 plan 範圍,留給下次 map-codebase 更新。

## 留給 CI / 05-12 確認

1. 前端 PR(向 develop)出現 `Browser E2E (real backend)` job 並完成,`Resolve backend ref` log 顯示「後端使用對應分支:claude/epic-curie-lj8alo」(或 fallback develop),記錄結論與 run id。
2. 兩邊 Browser E2E 的 Playwright 輸出包含 `@extended` 案例(不再只有 @smoke),step 名稱為 `Run Browser E2E (all tags)`;總時間在 30 分鐘 timeout 內。
3. `watchlist-live.spec.ts` 等 WS 案例在 preview proxy(`http://` target + `ws: true`)下為綠;若斷線,依 RESEARCH A4 把 `/ws` target 改為 `ws://`。
4. 前端 CI 註:前端 workflow 只在 `push: [develop]` / `pull_request: [develop]` 觸發,feature 分支 push 不會跑。

## Next Phase Readiness

- 05-06 修改 `docs/testing/overview.md` 時,本 plan 已改的 :141、:151、:298 與「前端 repo 自己的 CI」段落須保留。
- 05-12 依上列清單記錄兩 repo CI 證據。

## Self-Check: PASSED

- FOUND:前端 8 個檔案與後端 5 個檔案皆存在且含預期內容(VITE_API_PROXY_TARGET、browser-e2e、Run Browser E2E (all tags)、11180)
- FOUND commits:前端 6ad00ac、87eeff7、63afeb6;後端 cf1459a、304e39d(皆為各自 HEAD 的祖先)
