---
status: diagnosed
trigger: "G-05-9 (UAT Test 9): 補守門(回 issue) — 05-01 test-tier prohibition『contract-journey 不得 page.route / route.fulfill』目前 grep 0 次,但沒有自動守門防止回退"
created: 2026-11-14T13:37:00Z
updated: 2026-11-14T14:00:00Z
goal: find_root_cause_only
---

## Current Focus

bug_class: Bohrbug (deterministic process/coverage gap — not a runtime defect)
status: ROOT CAUSE CONFIRMED (find_root_cause_only — no fix applied)
reasoning_checkpoint:
  hypothesis: "J0 no-stub prohibition has no recurring enforcement because 05-01 satisfied `verification: test` with a one-shot plan-time grep, and every recurring FE gate (eslint src, vue-tsc src/**, vitest excluding e2e/**, Browser E2E) is structurally blind to stubbing in vue-app/e2e/"
  confirming_evidence:
    - "05-01-PLAN L31-33 status resolved/verification test; only check = acceptance grep L114, run once (SUMMARY L145)"
    - "Scratch injection of context.route+fulfill into J0: full vitest 67 files/1516 tests PASS, eslint src exit 0, 05-01 grep = 0"
    - "vite.config.ts test.exclude e2e/**; tsconfig include src/**; package.json lint = eslint src"
  falsification_test: "If any existing gate had gone red on the injected context.route, the hypothesis would be false — none did"
  fix_rationale: "(direction only) a persisted ?raw vitest guard in src/ puts e2e/tests/contract-journey.spec.ts (+ its imported helpers) inside the `npm test` gate that CI already runs"
  blind_spots: "in-page monkeypatching via page.evaluate(() => { window.fetch = ... }) is not caught by a call-shape regex; J0 legitimately uses page.evaluate so it cannot be banned wholesale"
  candidate_causes:
    - "process/planning: prohibition marked verification:test but enforced by one-off grep (confirmed)"
    - "config/tooling: e2e/ outside eslint/tsconfig/vitest scope (confirmed contributing)"
    - "code: existing ?raw guards glob only ./pages ./components ./composables (confirmed contributing)"
  and_gate: "yes — gap needs BOTH a one-shot (non-persisted) check AND gate scopes that exclude e2e/; either alone would have been caught/prevented"
next_action: return ROOT CAUSE FOUND to orchestrator (plan-phase --gaps handles the fix)

## Symptoms

expected: 有自動源碼守門斷言 vue-app/e2e/tests/contract-journey.spec.ts 不含 page.route / route.fulfill / context.route,防止 J0(真後端契約全程 journey)被 stub 弱化
actual: User reported "補守門(回 issue)". 05-01 test-tier prohibition says contract-journey must not use page.route / route.fulfill; grep is currently 0 hits but nothing automated prevents regression.
errors: none
reproduction: UAT Test 9 (05-UAT.md)
started: discovered during UAT 2026-11-14

## Eliminated

- hypothesis: J0 is already stubbed today (prohibition currently violated)
  evidence: raw grep of contract-journey.spec.ts and its only relative import (e2e/support/fixtures.ts) = 0 route/fulfill/routeFromHAR/routeWebSocket/addInitScript hits; probe guard green on current tree
  timestamp: 2026-11-14T13:41:00Z

- hypothesis: a config-level stub (playwright.config.ts globalSetup / project route / HAR replay) silently applies to J0
  evidence: playwright.config.ts has a single chromium project, no globalSetup, no route/HAR/serviceWorkers options
  timestamp: 2026-11-14T13:42:00Z

- hypothesis: some existing gate (vitest/eslint/vue-tsc) would already catch a J0 stub regression
  evidence: scratch injection of context.route+fulfill → vitest 67 files/1516 tests PASS, eslint src exit 0; tsconfig include is src/** only
  timestamp: 2026-11-14T13:54:00Z

## Evidence

- timestamp: 2026-11-14T13:40:00Z
  checked: grep -nE "route|fulfill|routeFromHAR|unroute|HAR|abort\(|continue\(" vue-app/e2e/tests/contract-journey.spec.ts (399 lines); full read of spec
  found: 0 hits. Spec header L14 states「不攔截、不 stub 任何請求」as a comment only. Spec destructures `page`, `context`, `playwright`, `uniqueAccount`, `baseURL` fixtures (L175-181) — so `context.route(...)` / `page.context().route(...)` is a reachable vector. Spec legitimately uses `page.evaluate` (L108, L234 read document.cookie / storage) and `page.request` / `playwright.request.newContext` (real backend reads) — a guard must NOT forbid these. No `addInitScript` / `routeWebSocket` / `routeFromHAR` today.
  implication: Current state satisfies the prohibition; nothing but a comment documents it.

- timestamp: 2026-11-14T13:41:00Z
  checked: import graph of contract-journey.spec.ts and e2e/support/*.ts
  found: Spec imports only `../support/fixtures` (value: test, expect, loginViaUi) and `@playwright/test` (type-only). fixtures.ts imports only `@playwright/test`; it has 0 route/fulfill calls; its `test = base.extend<{uniqueAccount}>` fixture only registers an account via a separate APIRequestContext. portfolioStubs.ts DOES contain `page.route('**/api/v1/**')` (L446), `route.fulfill` (L415+, 12x) and `page.routeWebSocket` (L425) — imported today only by layout.spec.ts. journeyApi.ts / mockSentinels.ts: 0 route hits.
  implication: Helper-bypass risk is real but latent: (1) contract-journey importing portfolioStubs (installPortfolioStubs etc.) would stub every /api/v1 call while the spec file itself still greps clean; (2) fixtures.ts is shared by 10 specs — an `auto: true` fixture or a page/context override added there for another spec would silently apply to J0. A spec-file-only grep would miss both.

- timestamp: 2026-11-14T13:42:00Z
  checked: per-spec count of route/fulfill/routeWebSocket/addInitScript across e2e/tests/*.ts; playwright.config.ts
  found: layout.spec.ts 12, api-failure-states.spec.ts 3, trade.spec.ts 1 (stubs are legitimate there); contract-journey 0. playwright.config.ts: single chromium project, no globalSetup, no project-level route/HAR/serviceWorkers settings, testDir ./e2e/tests.
  implication: Stubbing is an accepted pattern elsewhere in the same suite (copy-paste vector into J0 is plausible); config layer is not currently a bypass path.

- timestamp: 2026-11-14T13:45:00Z
  checked: existing src source guards — mockStoreBoundary.test.ts, decimalBoundary.test.ts, wsBoundary.test.ts, contrastTokens.test.ts, mockSentinels.test.ts; grep for node:fs / readFileSync in src/**/*.test.ts
  found: All source guards read files via Vite `import.meta.glob(..., { query: '?raw', import: 'default', eager: true })` or `import x from './X.vue?raw'`, paths relative to the test file. Zero uses of node:fs/readFileSync in src tests. contrastTokens.test.ts L3-4 explicitly says「沿用 ... ?raw 手法,不走 node:fs」. Conventions: describe text in 繁中 with requirement id (e.g. `mock store 邊界守門(WATCH-03)`), header JSDoc citing decision ids, a「掃描範圍非空」it, and a「偵測規則本身有效(避免守門永遠綠)」self-test it feeding synthetic positive/negative strings to the detector. decimalBoundary has a reusable `stripComments` helper. mockSentinels.test.ts L3 already imports `../e2e/support/mockSentinels` — precedent for a src test reaching into e2e/.
  implication: The guard should live in vue-app/src/ as a `?raw`-based vitest file, mirroring mockStoreBoundary/decimalBoundary.

- timestamp: 2026-11-14T13:46:00Z
  checked: vite.config.ts test block, tsconfig.json, eslint.config.js, package.json scripts, node_modules/@types
  found: vitest uses default include + `exclude: [...configDefaults.exclude, 'e2e/**']` → a new src/*.test.ts is picked up; e2e specs are never executed by vitest. tsconfig include = src/**/*.ts + src/**/*.vue, types = vite/client + vitest/globals (vite/client declares `*?raw` as string). `@types/node` is NOT installed (only an optional peer in lock; vite.config.ts L16 notes it) → a `node:fs` import in a src test would fail `vue-tsc --noEmit` in `npm run build`. ESLint runs only on `src` (`eslint src`) with browser globals; e2e/ is neither linted nor type-checked except transitively.
  implication: node:fs is ruled out by the toolchain; `?raw` is the only zero-dependency way. e2e/ lies outside tsconfig/eslint scope, so the vitest guard is the only automated gate that could see contract-journey.spec.ts in `npm test` / CI.

- timestamp: 2026-11-14T13:50:00Z
  checked: origin of the prohibition — 05-01-PLAN.md L27-36, L106, L114; 05-01-SUMMARY.md L122/L145; 05-VERIFICATION.md L48-50, L118
  found: 05-01 frontmatter lists the prohibition as `status: resolved, verification: test`, but its only check is a plan-time acceptance criterion `grep -c "page.route\|route.fulfill" e2e/tests/contract-journey.spec.ts = 0`, executed once and recorded in SUMMARY (`page.route|route.fulfill = 0`). No task created a recurring test. Verifier flagged it `unverified-prohibition` (test-tier with no enforcement).
  implication: Root cause is a planning/verification-tier mismatch: "verification: test" was satisfied by a one-shot grep instead of a persisted guard.

- timestamp: 2026-11-14T13:52:00Z
  checked: SCRATCH COPY experiment (scratchpad/fe-probe, repo untouched; `git status` clean) — probe src/contractJourneyNoStub.test.ts using `import x from '../e2e/tests/contract-journey.spec.ts?raw'` + `import.meta.glob('../e2e/support/*.ts', {query:'?raw', import:'default', eager:true})`
  found: On current tree: probe 3/3 pass; `npx vue-tsc --noEmit` exit 0; `npx eslint <probe>` exit 0; glob keys include `../e2e/support/fixtures.ts` and `portfolioStubs.ts`; the ?raw import of a .spec.ts does not register Playwright tests.
  implication: A ?raw guard in src/ reading ../e2e/** is viable under the current vitest/tsconfig/eslint setup with zero config changes.

- timestamp: 2026-11-14T13:54:00Z
  checked: Negative control 1 (scratch) — injected `await context.route('**/api/v1/portfolio/summary', (r) => r.fulfill({ json: ... }))` into J0
  found: Probe RED (`.route(`, `.fulfill(`). Without the probe: full existing suite `npx vitest run` = 67 files / 1516 tests PASSED; `npx eslint src` exit 0; and the 05-01 acceptance grep `grep -c "page.route\|route.fulfill"` = **0** (pattern misses `context.route` and callbacks not named `route`).
  implication: DIRECT proof that no existing gate catches a J0 stub regression, and that the original 05-01 grep pattern itself is too narrow to serve as the guard spec.

- timestamp: 2026-11-14T13:56:00Z
  checked: Negative control 2 (scratch) — spec untouched; added a `page` fixture override in e2e/support/fixtures.ts that calls `page.route('**/api/v1/**', ...)`
  found: Spec-file grep = 0 (a spec-only guard would stay green); probe that follows the spec's relative imports went RED on `../e2e/support/fixtures.ts: .route(`.
  implication: Helper-bypass risk confirmed as real: guard must scan the spec's transitive relative imports (today: only fixtures.ts) or enforce an import allowlist.

- timestamp: 2026-11-14T13:57:00Z
  checked: reuse of decimalBoundary.test.ts `stripComments` on route-style code (node one-off)
  found: Input with `page.route('**/api/v1/**', (r) => r.fulfill({}))` + `context.route(...abort())` + a later `/** doc */` → hits drop 4 → 1 after stripping: the `/\*[\s\S]*?\*\//` regex treats `/**'` inside the glob string as a block-comment opener and swallows code up to the next `*/`.
  implication: The new guard must scan RAW source (no comment stripping); current J0 raw source already has 0 hits, so no false positives from its comments.

- timestamp: 2026-11-14T13:58:00Z
  checked: backend analog — stock-start/src/test/java/.../E2eNamingRulesTest.java; grep Mockito/MockitoBean in start/e2e package; backend .github/workflows/ci.yml Browser E2E job
  found: E2eNamingRulesTest is ArchUnit naming-only (D-23/D-27: no *Test in e2e package; concrete e2e classes end with E2E; allowEmptyShould(false)). Backend e2e package uses 0 Mockito/MockitoBean but no rule forbids it (parallel latent gap, out of scope). Backend Browser E2E job (ci.yml L227-312) runs frontend `e2e/run-e2e.sh` (Playwright), not vitest — so a vitest guard fires only in frontend CI / local `npm test`.
  implication: No backend guard to mirror for J0; frontend is the correct and sufficient home. `allowEmptyShould(false)` maps to the frontend「掃描範圍非空」it.

## Resolution

root_cause: "05-01 declared the J0 no-stub prohibition `verification: test, status: resolved` but implemented it only as a one-shot plan-time acceptance grep (`grep -c \"page.route\\|route.fulfill\" = 0`) that was never persisted as a test; AND every recurring frontend gate is structurally blind to vue-app/e2e/ (eslint src only, tsconfig src/** only, vitest excludes e2e/**, existing ?raw boundary guards glob only ./pages ./components ./composables, Browser E2E stays green when stubbed). Secondary: the 05-01 grep pattern is itself too narrow (misses context.route / page.context().route / routeFromHAR / routeWebSocket / addInitScript / callbacks not named `route`) and ignores imported helpers (e2e/support/fixtures.ts is shared by 10 specs)."
fix: (n/a — find_root_cause_only)
verification: "Root cause verified by scratch-copy negative controls (repo untouched): injected stub passes all existing gates; probe ?raw guard catches spec-level and helper-level injections; probe passes vue-tsc + eslint on current tree."
files_changed: []
