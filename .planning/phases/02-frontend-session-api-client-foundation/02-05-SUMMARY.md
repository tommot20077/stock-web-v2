---
phase: 02-frontend-session-api-client-foundation
plan: 05
subsystem: frontend-runtime-mode
tags: [vue, vitest, runtime-mode, fail-fast, api-outage, verification]

requires:
  - phase: 02-frontend-session-api-client-foundation
    provides: shared apiClient transport, authSession state, and App shell session/banner surfaces from plans 01-04
provides:
  - Strict runtime data mode validation that fails fast on invalid explicit VITE_DATA_MODE
  - App-level invalid runtime mode error surface that never renders mock content
  - API-mode outage surface proving no silent fallback to mock adapters
  - Final Phase 2 frontend suite and build verification gate
affects: [portfolio-api-mode, trading-api-mode, cross-repo-browser-flow]

tech-stack:
  added: []
  patterns:
    - Explicit-but-invalid runtime mode raises a typed error instead of defaulting to mock
    - API-mode transport failures surface as visible errors rather than mock data

key-files:
  created:
    - .planning/phases/02-frontend-session-api-client-foundation/02-05-SUMMARY.md
  modified:
    - ../../vue/stock-v2/vue-app/src/services/runtimeDataMode.ts
    - ../../vue/stock-v2/vue-app/src/services/runtimeDataMode.test.ts
    - ../../vue/stock-v2/vue-app/src/App.vue
    - ../../vue/stock-v2/vue-app/src/App.test.ts
    - ../../vue/stock-v2/vue-app/src/api-adapter-wiring.test.ts
    - ../../vue/stock-v2/vue-app/src/services/backtestApi.test.ts
    - ../../vue/stock-v2/vue-app/src/services/opsApi.test.ts
    - ../../vue/stock-v2/vue-app/src/services/aiAccessApi.test.ts
    - ../../vue/stock-v2/vue-app/vite.config.ts

key-decisions:
  - "Only absent or empty VITE_DATA_MODE defaults to mock; any explicit non-empty value other than mock/api throws RuntimeDataModeError."
  - "Invalid runtime mode is rendered as a configuration error surface in the App shell, and no network request is issued in that state."
  - "API-mode outages remain visible errors with a retry affordance; mock adapter factories are never invoked as a fallback."

patterns-established:
  - "Runtime mode is validated once at the boundary and surfaces as a typed error, not a silent default."
  - "API-mode wiring tests assert mock adapter factories are never called, making silent fallback a test failure."

requirements-completed: [D-17, D-18, D-19, FAPI-07, FAPI-08]

duration: 26min
completed: 2026-05-31
---

# Phase 02 Plan 05: Runtime Mode Hardening & Final Frontend Verification Summary

**Explicit-but-invalid `VITE_DATA_MODE` now fails fast with a visible configuration error, and API-mode outages surface as errors instead of silently falling back to mock data.**

> ⚠️ **本檔為回溯補寫(2026-07-19)。** 實作工作於 **2026-05-31 00:58–01:24** 完成並已進版控(下方 6 個 commit 為證),但當時的 session 在最後一個 commit 之後、產出本 summary 之前結束,因此 STATE.md 停在 `Completed 02-04-PLAN.md`。本檔依 commit 序列與今日重新驗證的結果如實重建;**原始的 RED/GREEN 測試輸出已無法重現**,verification 一節已註明證據來源。

## Performance

- **Duration:** 26 min
- **Started:** 2026-05-30T16:58:40Z (2026-05-31 00:58 +0800)
- **Completed:** 2026-05-30T17:24:20Z (2026-05-31 01:24 +0800)
- **Tasks:** 3
- **Files modified:** 9 frontend files plus this summary

## Accomplishments

- Tightened `normalizeRuntimeDataMode` so only absent/empty values default to `mock`; explicit `local`/`prod`/`invalid` now throw a typed `RuntimeDataModeError` carrying code `INVALID_RUNTIME_DATA_MODE`.
- Added App shell handling so an invalid runtime mode renders the UI-SPEC copy `資料模式設定無效，請修正 VITE_DATA_MODE。` with no mock portfolio/trading content and no network request.
- Added API-mode outage handling so a rejected fetch renders `暫時無法連線到後端，請稍後重試。` with a retry affordance instead of mock content.
- Extended adapter wiring coverage to prove API mode creates HTTP clients and never calls mock adapter factories.
- Aligned adapter test fixtures with the CSRF guard and stabilized the full frontend suite gate.

## Task Commits

Each task was committed atomically in the sibling frontend repository (`stock-web-v2-front-end`):

1. **Task 1 RED: invalid runtime mode coverage** - `6586a15` (`test`)
2. **Task 1 GREEN: fail fast on invalid runtime mode** - `54dcd17` (`feat`)
3. **Task 2 RED: API outage fallback regression coverage** - `6deb11f` (`test`)
4. **Task 2 GREEN: surface API-mode startup outages** - `4e18a2b` (`feat`)
5. **Task 3: align adapter fixtures with CSRF guard** - `04f2563` (`test`)
6. **Task 3: stabilize full frontend gate** - `a4f2dde` (`test`)

## Files Created/Modified

- `src/services/runtimeDataMode.ts` - `RuntimeDataModeError` plus strict normalization; only absent/empty defaults to `mock`.
- `src/services/runtimeDataMode.test.ts` - Coverage for absent/empty defaulting, explicit `mock`/`api`, and typed throw on `local`/`prod`/`invalid`.
- `src/App.vue` - Invalid runtime mode configuration surface and API-mode startup outage surface.
- `src/App.test.ts` - Invalid-mode assertions (exact zh copy, `INVALID_RUNTIME_DATA_MODE`, no mock content, `fetch` never called) and API-mode rejected-fetch outage assertions.
- `src/api-adapter-wiring.test.ts` - API mode creates HTTP clients and never calls mock adapter factories; adapter failures preserve status/trace id.
- `src/services/backtestApi.test.ts`, `src/services/opsApi.test.ts`, `src/services/aiAccessApi.test.ts` - Fixtures aligned with the CSRF guard.
- `vite.config.ts` - Suite stabilization for the full frontend gate.

## Decisions Made

- Treated an explicit invalid mode as a configuration fault rather than a recoverable state: fail fast and show the operator what to fix.
- Kept the invalid-mode surface strictly offline (no `fetch`) so a misconfigured build cannot leak requests to an unintended backend.
- Asserted the *absence* of mock adapter factory calls, making silent mock fallback a hard test failure rather than a reviewer-spotted regression.

## Verification Evidence

> 證據來源說明:原始 2026-05-31 執行時的 RED/GREEN 輸出未被保存,無法重現。以下為(a)commit 序列證明 TDD 順序,(b)2026-07-19 重新執行的驗證結果證明驗收條件目前成立。

- **TDD 序列(commit 證據):** Task 1 與 Task 2 皆為先 `test(...)` 後 `feat(...)`,時間戳依序為 00:58:40 → 01:00:12 → 01:02:42 → 01:04:31。
- **驗收條件現況(2026-07-19 重驗):**
  - `App.test.ts` → `invalid explicit runtime mode renders a configuration error without mock content`:斷言精確文案 `資料模式設定無效，請修正 VITE_DATA_MODE。`、`INVALID_RUNTIME_DATA_MODE`、不含 `總資產`/`最近交易`、`fetch` 未被呼叫。
  - `App.test.ts` → `API-mode rejected fetch renders backend unavailable retry surface without mock portfolio content`:斷言 `暫時無法連線到後端，請稍後重試。` 與 `session-retry`,且不含 mock 內容。
  - `runtimeDataMode.test.ts` → 未設定/空值回 `mock`;`local`/`prod`/`invalid` 丟 `RuntimeDataModeError`。
  - `api-adapter-wiring.test.ts` → `API mode creates HTTP clients and never calls mock adapter factories`。
  - **完整套件:** `npm test` 154/154、`VITE_DATA_MODE=api npm test` 154/154(兩模式一致)、`npm run build` 綠(vue-tsc + vite build)。

## Deviations from Plan

None in scope. Task 3 的「最終驗證」除了跑套件外,另包含兩個 commit 修正既有 fixture 與套件穩定性(`04f2563`、`a4f2dde`),以讓整體 gate 能綠。

## Issues Encountered

- 執行 session 在最後一個 commit(`a4f2dde`,01:24)之後結束,未產出本 summary,導致 STATE.md 停留在 `Completed 02-04-PLAN.md`、Phase 02 長期顯示為 in_progress。本檔即為該缺口的補正。

## Known Stubs

None.

## Threat Flags

None new. 相關面向(runtime mode 誤設導致對非預期後端發請求)已由「invalid mode 不發任何 fetch」的斷言涵蓋。

## User Setup Required

None.

## Next Phase Readiness

Phase 2 的共用傳輸/session/runtime-mode 基礎已完備並經驗證,Phase 3(Portfolio Read API Mode)可直接在其上新增 portfolio/holdings/positions 的 API-mode 讀取。

**後續變更備註:** `a4f2dde` 當時加入的 `vite.config.ts` `maxWorkers: 1` 已於 2026-07-19 被 `pool: 'threads'` + `fileParallelism: false` 取代(保留測試檔序列化的穩定性,改用更輕量的 threads pool)。

## Self-Check: PASSED

- Summary file exists at `.planning/phases/02-frontend-session-api-client-foundation/02-05-SUMMARY.md`.
- Frontend task commits exist and are reachable on `develop`: `6586a15`, `54dcd17`, `6deb11f`, `4e18a2b`, `04f2563`, `a4f2dde`.
- Acceptance criteria re-verified on 2026-07-19 against the current tree (see Verification Evidence).
- Full frontend gate green in both data modes plus production build.

---
*Phase: 02-frontend-session-api-client-foundation*
*Completed: 2026-05-31 (summary written retroactively 2026-07-19)*
