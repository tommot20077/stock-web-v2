---
phase: 02-frontend-session-api-client-foundation
plan: 04
subsystem: frontend-auth-ui
tags: [vue, vitest, auth, session, i18n, app-shell]

requires:
  - phase: 02-frontend-session-api-client-foundation
    provides: shared apiClient credentials, CSRF, refresh/replay, auth API adapter, and token-free authSession state from plans 01-03
provides:
  - Shell-mounted auth panel for login, register, and logout states
  - Persistent session/security banner with safe diagnostics
  - API-mode App startup CSRF bootstrap and /me session restore
  - Header session indicator with stale identity suppression
affects: [portfolio-api-mode, trading-api-mode, cross-repo-browser-flow]

tech-stack:
  added: []
  patterns:
    - App shell owns API-mode session bootstrapping and global auth/session surfaces
    - Header receives safe session summary props and hides identity unless authenticated

key-files:
  created:
    - ../../vue/stock-v2/vue-app/src/components/AuthPanel.vue
    - ../../vue/stock-v2/vue-app/src/components/AuthPanel.test.ts
    - ../../vue/stock-v2/vue-app/src/components/SessionBanner.vue
    - ../../vue/stock-v2/vue-app/src/components/SessionBanner.test.ts
    - ../../vue/stock-v2/vue-app/src/App.test.ts
    - ../../vue/stock-v2/vue-app/src/components/Header.test.ts
    - .planning/phases/02-frontend-session-api-client-foundation/02-04-SUMMARY.md
  modified:
    - ../../vue/stock-v2/vue-app/src/App.vue
    - ../../vue/stock-v2/vue-app/src/components/Header.vue
    - ../../vue/stock-v2/vue-app/src/i18n.ts

key-decisions:
  - "API-mode session UI is mounted as a shell row below the existing 60px header instead of replacing the current product pages."
  - "Session diagnostics render only safe code/status/requestId fields; raw backend messages are not displayed in the banner."
  - "Header identity is derived only from authenticated session state and is hidden for anonymous, checking, refreshing, and error states."

patterns-established:
  - "AuthPanel emits typed login/register/logout requests while keeping password values inside inputs and emitted payloads only."
  - "SessionBanner maps session/security error codes to UI-SPEC copy and exposes retry/sign-in-again actions."
  - "App API-mode startup calls auth.csrf() before authSession.restore() while mock mode keeps existing pages without /me."

requirements-completed: [AUTH-03, AUTH-04, FAPI-07]

duration: 16min
completed: 2026-05-30
---

# Phase 02 Plan 04: Frontend Session & API Client Foundation Summary

**Vue API mode now surfaces browser session state through the existing app shell with auth panel, global session banner, and header identity controls.**

## Performance

- **Duration:** 16 min
- **Started:** 2026-05-30T16:28:40Z
- **Completed:** 2026-05-30T16:44:23Z
- **Tasks:** 2
- **Files modified:** 9 frontend files plus this summary

## Accomplishments

- Added `AuthPanel.vue` with UI-SPEC zh/en copy for signed-out, login, register, authenticated, and logout states.
- Added `SessionBanner.vue` for checking, refreshing, expired session, CSRF failure, backend outage, invalid runtime mode, and safe diagnostic details.
- Integrated API-mode session startup in `App.vue`: CSRF bootstrap runs before `/me` restore, while mock mode keeps existing pages and avoids `/api/v1/me`.
- Extended `Header.vue` with compact session status, authenticated identity, logout affordance, and stale identity suppression.

## Task Commits

Each task was committed atomically in the sibling frontend repository:

1. **Task 1 RED: Auth shell component tests** - `5e7e020` (`test`)
2. **Task 1 GREEN: Auth panel and session banner** - `7cf358d` (`feat`)
3. **Task 2 RED: App/Header session wiring tests** - `e25470b` (`test`)
4. **Task 2 GREEN: App shell and Header session integration** - `9e2f256` (`feat`)

## Files Created/Modified

- `../../vue/stock-v2/vue-app/src/components/AuthPanel.vue` - Focused shell auth UI for anonymous, authenticated, login, register, and logout flows.
- `../../vue/stock-v2/vue-app/src/components/AuthPanel.test.ts` - Component coverage for zh/en copy, form labels/autocomplete, disabled states, typed emits, and password non-rendering.
- `../../vue/stock-v2/vue-app/src/components/SessionBanner.vue` - Global session/security banner with safe diagnostics and retry/sign-in actions.
- `../../vue/stock-v2/vue-app/src/components/SessionBanner.test.ts` - Coverage for state copy, `AUTH_CSRF_TOKEN_INVALID`, request diagnostics, action emits, and sensitive string redaction.
- `../../vue/stock-v2/vue-app/src/App.vue` - API-mode session bootstrapping and shell-mounted banner/auth panel integration.
- `../../vue/stock-v2/vue-app/src/App.test.ts` - App coverage for CSRF bootstrap, `/me` restore, expiry, backend outage, logout, and mock mode.
- `../../vue/stock-v2/vue-app/src/components/Header.vue` - Compact header session indicator and logout action.
- `../../vue/stock-v2/vue-app/src/components/Header.test.ts` - Header coverage for nav preservation, authenticated identity, and stale identity suppression.
- `../../vue/stock-v2/vue-app/src/i18n.ts` - UI-SPEC auth/session strings for zh and en.

## Decisions Made

- Mounted auth/session UI below the header so API-mode anonymous/error states have a primary shell anchor while existing pages remain mounted.
- Kept raw backend error messages out of `SessionBanner`; only safe `code`, HTTP `status`, and `requestId` render.
- Used Header props rather than importing session state directly into Header, keeping the shell composition in `App.vue`.

## Verification Evidence

- RED Task 1: `npm test -- src/components/AuthPanel.test.ts src/components/SessionBanner.test.ts` failed because `AuthPanel.vue` and `SessionBanner.vue` did not exist.
- GREEN Task 1: `npm test -- src/components/AuthPanel.test.ts src/components/SessionBanner.test.ts` passed, 2 files and 7 tests.
- RED Task 2: `npm test -- src/App.test.ts src/components/Header.test.ts` failed because App had no session bootstrap/UI and Header had no session indicator.
- GREEN / final focused gate: `npm test -- src/App.test.ts src/components/Header.test.ts src/components/AuthPanel.test.ts src/components/SessionBanner.test.ts` passed, 4 files and 14 tests.
- Acceptance scans confirmed UI-SPEC strings, stale identity assertions, mock-mode `/me` avoidance, and no package dependency changes.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

- App component tests needed a macrotask wait after mount so `onMounted` async session restore could settle before assertions. Production behavior was unchanged; the test helper local to `App.test.ts` handles this.
- `gsd-sdk` is installed through a Windows npm path that fails under this WSL workspace, so STATE/ROADMAP/REQUIREMENTS close-out updates were applied directly.

## Known Stubs

None. Stub scan matches were internal nullable refs (`toastTimer`, `ticketPreset`) and expected test-only sensitive-string fixtures used to prove redaction.

## Threat Flags

None. The security-relevant surfaces were already listed in the plan threat model: session metadata to UI, auth form inputs, stale identity hiding, and safe diagnostics.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

Plan 05 can tighten runtime mode validation and adapter wiring against the visible session/error surfaces added here. Phase 3 can consume the same App/Header session state when adding portfolio API-mode reads.

## Self-Check: PASSED

- Summary file exists at `.planning/phases/02-frontend-session-api-client-foundation/02-04-SUMMARY.md`.
- Frontend task commits exist: `5e7e020`, `7cf358d`, `e25470b`, `9e2f256`.
- Created frontend files exist: `AuthPanel.vue`, `AuthPanel.test.ts`, `SessionBanner.vue`, `SessionBanner.test.ts`, `App.test.ts`, and `Header.test.ts`.
- Required focused verification passed: `src/App.test.ts`, `src/components/Header.test.ts`, `src/components/AuthPanel.test.ts`, and `src/components/SessionBanner.test.ts`, 14 tests.

---
*Phase: 02-frontend-session-api-client-foundation*
*Completed: 2026-05-30*
