---
phase: 05-cross-repo-browser-flow-verification-contract-hardening
plan: 10
subsystem: ui
tags: [css-tokens, wcag, contrast, vue, vitest, playwright, red-up-green-down]

requires: []
provides:
  - "styles.css 固定錯誤色 token --danger / --on-danger(淺 #dc2626/#ffffff、深 #ef4444/#15171c,紅漲綠跌區塊不覆寫)"
  - "全部錯誤 / 破壞性樣式(19 個 .vue)改用 --danger,紅漲綠跌偏好下不變綠"
  - "漲跌膠囊 / 按鈕 / 深度條 / 指標底色改用 color-mix(var(--up|--dn)),隨偏好對調"
  - "contrastTokens.test.ts 三組源碼守門(--danger 固定錯誤色、錯誤樣式不得使用漲跌色、漲跌底色隨紅漲綠跌對調)"
affects: [frontend-ui, design-tokens, contrastTokens.test.ts]

actuals:
  tokens: 9200
  tasks: 3
  commits: 3
plan_head_before: ebd54db43ca74848603051674d2d03f8e344c411
plan_head_after: 82dfc18fc610c0d66b67db613fb9bcc61b83e502
commits: 3

tech-stack:
  added: []
  patterns:
    - "狀態語意固定色(--success / --danger)與漲跌色(--up / --dn)分離;紅漲綠跌區塊只對調漲跌色"
    - "selector 關鍵字(error|danger|invalid|fail|retry|logout)驅動的源碼掃描守門"
    - "半透明語意底色一律 color-mix(in oklch, var(--token) N%, transparent),不寫 rgba 字面"

key-files:
  created: []
  modified:
    - /home/user/stock-web-v2-front-end/vue-app/src/styles.css
    - /home/user/stock-web-v2-front-end/vue-app/src/contrastTokens.test.ts
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Ops.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/components/AuthPanel.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/components/CashEntryDialog.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/components/Header.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/components/HoldingEventDialog.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/components/OrderTicket.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/components/SessionBanner.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/components/WatchlistPicker.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/components/settings/SettingsAiAccess.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/components/settings/SettingsBrokers.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/components/settings/SettingsDataSources.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Alerts.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Analytics.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Backtest.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Cash.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Chart.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Overview.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Positions.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Settings.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Trades.vue
    - /home/user/stock-web-v2-front-end/vue-app/src/pages/Watchlist.vue

key-decisions:
  - "--danger 沿用 --dn 原值(淺 #dc2626、深 #ef4444),量測 --surface 4.83 / 4.76 皆 ≥ 4.5,不需調色;深色 --on-danger 用 #15171c(白字只有 3.76)"
  - "錯誤守門的漲跌色清單擴及 --neg / --btn-up-bg / --btn-dn-bg(同樣會被紅漲區塊對調),selector 關鍵字擴及 retry / logout"
  - "AuthPanel 登出鈕 .auth-submit.danger 由 --btn-dn-bg + 白字改 --danger + --on-danger,原 A2 斷言同步改寫"

patterns-established:
  - "失敗 / 錯誤 / 破壞性樣式只能用 --danger;--up / --dn 只留給漲跌語意(源碼守門強制)"
  - "漲跌底色以 color-mix 引用 --up / --dn,比例沿用原 alpha"

requirements-completed: [VER-02]

coverage:
  - id: D1
    description: "固定錯誤色 --danger / --on-danger 定義於兩主題、紅漲區塊不覆寫、對比度 ≥ 4.5;Ops 失敗燈改用 var(--danger)"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/contrastTokens.test.ts#--danger 固定錯誤色(D-28)"
        status: pass
    human_judgment: false
  - id: D2
    description: "所有錯誤 / 破壞性樣式(selector 含 error|danger|invalid|fail|retry|logout)不引用漲跌色、不寫死錯誤紅 rgba"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/contrastTokens.test.ts#錯誤樣式不得使用漲跌色(D-28)"
        status: pass
    human_judgment: false
  - id: D3
    description: "漲跌膠囊 / 按鈕 / 深度條 / 指標底色改為 color-mix(var(--up|--dn)),.pill.div 不動"
    requirement: VER-02
    verification:
      - kind: unit
        ref: "vue-app/src/contrastTokens.test.ts#漲跌底色隨紅漲綠跌對調(D-02c)"
        status: pass
      - kind: e2e
        ref: "cd vue-app && npx playwright test e2e/tests/layout.spec.ts (101 passed)"
        status: pass
    human_judgment: false
  - id: D4
    description: "紅漲綠跌偏好下錯誤樣式與漲跌底色的實際視覺觀感(色彩是否易讀、語意是否清楚)"
    verification: []
    human_judgment: true
    rationale: "源碼守門只證明 token 引用正確;color-mix 結果在兩主題 × 兩偏好下的視覺觀感需人眼確認"

duration: 9min
completed: 2026-11-09
status: complete
---

# Phase 5 Plan 10: 固定錯誤色 --danger 與漲跌底色對調 Summary

**新增不隨紅漲綠跌對調的 `--danger` / `--on-danger` token,19 個元件的錯誤 / 破壞性樣式改用它;漲跌膠囊 / 按鈕 / 深度條 / 指標底色由寫死 rgba 改為 `color-mix(in oklch, var(--up|--dn) N%, transparent)`,三組源碼守門測試防回歸。**

## Performance

- **Duration:** 約 9 min
- **Started:** 2026-11-09T14:46:31Z
- **Completed:** 2026-11-09T14:55Z
- **Tasks:** 3
- **Files modified:** 23(全在前端 repo)

## Accomplishments

- `styles.css`:`:root` `--danger: #dc2626; --on-danger: #ffffff;`,深色 `--danger: #ef4444; --on-danger: #15171c;`,紅漲兩個區塊不寫;檔頭註解記錄量測值(淺 surface 4.83 / bg 4.62,深 surface 4.76 / bg 5.16;surface2 上淺 4.35 / 深 4.42,錯誤文字不放在 surface2 底上)。
- `Ops.vue` 任務失敗燈 `'var(--dn)'` → `'var(--danger)'`。
- 錯誤 / 破壞性樣式全面改 `--danger`:AuthPanel、CashEntryDialog、Header(斷線燈、登出鈕)、HoldingEventDialog / OrderTicket(block-error、block-retry、field-error、form-error)、SessionBanner、WatchlistPicker、settings 三元件與 Settings、Alerts、Backtest、Overview、Watchlist,以及 Cash / Positions / Trades / Overview 的 `.block-retry`。錯誤紅 `rgba(220,38,38,α)` → `color-mix(in oklch, var(--danger) α%, transparent)`。
- 漲跌底色:Overview / Trades / Backtest 的 `.pill.*`、Trades `.kind-pill.*`、OrderTicket `.side-btn.*.active`、Chart `.depth.*-bg`、Analytics `.metric-delta.*` 改為 color-mix 引用 `--up` / `--dn`。`.pill.div`(`#a855f7`)維持(Trades 仍 2 處)。
- `contrastTokens.test.ts` 新增三組 describe(`--danger 固定錯誤色(D-28)`、`錯誤樣式不得使用漲跌色(D-28)` 含自我驗證、`漲跌底色隨紅漲綠跌對調(D-02c)`),測試數 86 → 93。

## Task Commits(前端 repo `/home/user/stock-web-v2-front-end`)

1. **Task 1(tracer):--danger token → Ops 失敗燈 → 守門測試**:`53dfaf7` fix(05-10): 新增固定錯誤色 --danger,Ops 失敗燈不隨紅漲綠跌變綠
2. **Task 2:全部錯誤 / 破壞性樣式改用 --danger + 源碼掃描守門**:`435a34a` fix(05-10): 錯誤與破壞性樣式改用 --danger
3. **Task 3:寫死的漲跌 rgba 底色改用會對調的 token + layout E2E**:`82dfc18` fix(05-10): 漲跌底色改用會隨紅漲綠跌對調的 token

TDD:每個 task 都先加守門測試並確認紅燈(Task 1:4 failed;Task 2:45 條違規;Task 3:14 條違規),再改實作轉綠。因 plan 指定每個 task 一個 commit,測試與實作同 commit。

## Verification

- `npx vitest run src/contrastTokens.test.ts` → 93 passed(含 4 條名稱帶 `--danger` 的測試)。
- `npm run lint` → 0 errors(40 warnings 皆為既有 script 端 `no-explicit-any`,本 plan 只改 `<style>`、Ops 一個色彩字串與測試檔)。
- `npm test` → 64 files / 1319 tests passed;`VITE_DATA_MODE=api npm test` → 1319 passed。
- `npm run build`(含 vue-tsc)→ built OK。
- `npx playwright test e2e/tests/layout.spec.ts` → 101 passed(1.9m)。
- Acceptance:`--danger:` 於 styles.css 2 行;Ops.vue `var(--dn)` 0;錯誤紅 rgba 0.10 殘留(排除 side-btn)0;含 `var(--danger)` 的元件 / 頁面檔 19(≥ 14);`.pill/.kind-pill` rgba 0;`.side-btn.*.active` rgba 0;Trades `#a855f7` 2(= baseline)。

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - 缺漏的正確性] 守門的漲跌色清單擴及 `--neg` / `--btn-up-bg` / `--btn-dn-bg`**
- **Found during:** Task 2
- **Issue:** 計畫的守門只查 `var(--dn)` / `var(--up)`,但 `--neg: var(--dn)` 與 `--btn-*-bg` 同樣會被紅漲區塊對調。`Alerts.vue .form-error` 用 `--neg`、`AuthPanel .auth-submit.danger`(登出鈕)用 `--btn-dn-bg`,紅漲模式下會變綠,正是 D-28 要防的。
- **Fix:** 守門 regex 加入這三個 token;Alerts `.form-error` 改 `--danger`;AuthPanel 登出鈕改 `background: var(--danger); color: var(--on-danger)`(深色 `--danger` 配白字只有 3.76,所以用 `--on-danger`),並把原 A2 測試「`.auth-submit.danger` 用 `--btn-dn-bg` + 白字」改寫成新斷言(SettingsBrokers 警示圖示的原斷言保留)。
- **Files modified:** Alerts.vue、AuthPanel.vue、contrastTokens.test.ts
- **Commit:** 435a34a

**2. [Rule 2 - 缺漏的正確性] 守門 selector 關鍵字擴及 `retry|logout`**
- **Found during:** Task 2
- **Issue:** 計畫 interfaces 提到「相鄰 retry 鈕」與 `Header.vue:207`(`.session-logout`),但這些 selector 不含 error/danger/invalid/fail,原 regex 抓不到。
- **Fix:** regex 改 `/error|danger|invalid|fail|retry|logout/i`,一併修正 HoldingEventDialog / OrderTicket / Cash / Positions / Trades / Overview 的 `.block-retry`、Settings `.ccy-retry`、Header `.session-logout`。
- **Files modified:** 新增 Cash.vue、Positions.vue(計畫 files 清單外);Trades.vue 在 Task 2 也有一行 `.block-retry` 變更
- **Commit:** 435a34a

---

**Total deviations:** 2 auto-fixed(皆 Rule 2)。**Impact:** 檔案數比計畫多 3 個(Alerts、Cash、Positions),全部只改 `<style>` 色彩 token;守門比計畫更嚴,不放寬任何規則。

## Issues Encountered

None。

## Known Stubs

None。

## Threat Flags

None(只改 CSS token 與測試;T-05-19 已以 `--danger` + 源碼守門 mitigate)。

## Next Phase Readiness

- 無阻擋。CI(前端 `.github/workflows/`)會再跑 lint → test → build;layout E2E 已在本機跑過。
- 建議 verify-work 時以人眼看一次紅漲綠跌 × 深 / 淺主題下的錯誤框與漲跌膠囊(coverage D4)。

## Self-Check: PASSED

- 3 個 commit(53dfaf7、435a34a、82dfc18)皆在 `claude/epic-curie-lj8alo` HEAD 歷史中;`git rev-list --count ebd54db..HEAD` = 3。
- key-files 全部存在且於上述 commit 中修改。
