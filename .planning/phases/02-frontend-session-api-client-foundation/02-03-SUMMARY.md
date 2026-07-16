---
phase: 02-frontend-session-api-client-foundation
plan: 03
subsystem: frontend-auth
tags: [vue, vitest, auth, session, api-client, csrf]

requires:
  - phase: 01-browser-auth-contract-backend-security-foundation
    provides: browser auth endpoints, httpOnly cookie contract, CSRF contract, and token-free browser session metadata
  - phase: 02-frontend-session-api-client-foundation
    provides: shared apiClient credentials, CSRF guard, refresh/replay, and session callback hooks from plans 01-02
provides:
  - Typed browser auth adapter for register, login, refresh, logout, csrf, and me endpoints
  - Explicit token-free Vue auth session state with checking, authenticated, anonymous, refreshing, and error statuses
  - Runtime API client registry entry for auth adapter consumption by session/app code
affects: [frontend-session-ui, app-shell-auth-wiring, portfolio-api-mode, trading-api-mode]

tech-stack:
  added: []
  patterns:
    - Auth adapters map backend envelopes into explicit safe metadata and drop unexpected token fields
    - Auth session state is a small Vue shallowRef module wired to shared apiClient refresh callbacks

key-files:
  created:
    - ../../vue/stock-v2/vue-app/src/services/authApi.ts
    - ../../vue/stock-v2/vue-app/src/services/authApi.test.ts
    - ../../vue/stock-v2/vue-app/src/services/authSession.ts
    - ../../vue/stock-v2/vue-app/src/services/authSession.test.ts
    - .planning/phases/02-frontend-session-api-client-foundation/02-03-SUMMARY.md
  modified:
    - ../../vue/stock-v2/vue-app/src/services/pageApiClients.ts

key-decisions:
  - "Session restore through /api/v1/me stores user metadata with null expiry timestamps because the backend /me contract does not return session expiry metadata."
  - "Auth adapter response mapping whitelists allowed fields so unexpected browser token fields are discarded before reaching session state."
  - "authSession.ts registers apiClient refresh callbacks directly, keeping refresh transport logic in apiClient while session owns user-visible state transitions."

patterns-established:
  - "createHttpAuthApi/createMockAuthApi/createAuthApi: auth service factory shape matching existing domain adapters."
  - "createAuthSession({ api, mode }): injectable session factory for focused tests and future App shell wiring."

requirements-completed: [AUTH-03, AUTH-04, FAPI-06]

duration: 30min
completed: 2026-05-31
---

# Phase 02 Plan 03: Frontend Session & API Client Foundation Summary

**Token-free browser auth adapter and explicit Vue session state wired to shared refresh callbacks.**

## Performance

- **Duration:** 30 min
- **Started:** 2026-05-30T15:54:00Z
- **Completed:** 2026-05-30T16:24:07Z
- **Tasks:** 2
- **Files modified:** 5 frontend files plus this summary

## Accomplishments

- Added `authApi.ts` with typed browser auth methods for `/auth/register`, `/auth/login`, `/auth/refresh`, `/auth/logout`, `/csrf`, and `/me`.
- Added `authSession.ts` with explicit `checking`, `authenticated`, `anonymous`, `refreshing`, and `error` session states that store only safe user/session metadata.
- Wired auth into `pageApiClients.ts` so future App shell integration can consume the same mock/API runtime registry.
- Covered token leakage controls with tests proving unexpected token fields, passwords, and storage writes do not enter serialized session state.

## Task Commits

Each task was committed atomically in the sibling frontend repository:

1. **Task 1 RED: Auth API adapter tests** - `16a9895` (`test`)
2. **Task 1 GREEN: Browser auth API adapter** - `ac59203` (`feat`)
3. **Task 2 RED: Auth session tests** - `e7d58e3` (`test`)
4. **Task 2 GREEN: Explicit auth session state** - `344e45d` (`feat`)

## Files Created/Modified

- `../../vue/stock-v2/vue-app/src/services/authApi.ts` - Defines browser auth request/response types plus HTTP/mock/factory adapters.
- `../../vue/stock-v2/vue-app/src/services/authApi.test.ts` - Covers endpoint paths, HTTP methods, token-field stripping, mock/API factory behavior, and `/auth/token` exclusion.
- `../../vue/stock-v2/vue-app/src/services/authSession.ts` - Defines token-free session state union, restore/login/register/logout actions, and apiClient refresh callbacks.
- `../../vue/stock-v2/vue-app/src/services/authSession.test.ts` - Covers restore transitions, 401 anonymous state, outage error details, refresh callback transitions, logout, mock mode, and no storage writes.
- `../../vue/stock-v2/vue-app/src/services/pageApiClients.ts` - Adds `auth` to the runtime API client registry.

## Decisions Made

- Treated `/api/v1/me` as user-only restore data, matching the backend contract, so restored sessions use `null` expiry metadata until login/register/refresh supplies expiry timestamps.
- Used whitelisted object mapping in `authApi.ts` rather than returning raw backend `data`, preventing unexpected token fields from entering consumers.
- Kept session state as a lightweight Vue `shallowRef` service module instead of introducing Pinia/session persistence.

## Verification Evidence

- RED Task 1: `npm test -- src/services/authApi.test.ts` failed because `./authApi` did not exist.
- GREEN Task 1: `npm test -- src/services/authApi.test.ts` passed, 1 file and 4 tests.
- RED Task 2: `npm test -- src/services/authSession.test.ts src/services/authApi.test.ts` failed because `./authSession` did not exist; `authApi.test.ts` still passed.
- GREEN Task 2: `npm test -- src/services/authSession.test.ts src/services/authApi.test.ts` passed, 2 files and 10 tests.
- Final focused gate: `npm test -- src/services/authSession.test.ts src/services/authApi.test.ts` passed, 2 files and 10 tests.
- Additional type/build check: `npm run build` passed (`vue-tsc --noEmit && vite build`).
- Acceptance scans: production `authApi.ts`, `authSession.ts`, and `pageApiClients.ts` contain no `/auth/token`, `localStorage`, or `sessionStorage` usage.

## Deviations from Plan

None - plan executed within the planned behavior and files.

## Issues Encountered

- The initial Task 2 test expected `/api/v1/me` restore to include expiry timestamps. While implementing, I aligned the test with the documented backend `/me` contract: restore stores user metadata and `null` expiry timestamps, while login/register/refresh store expiry metadata.

## Known Stubs

None. Stub scan matches were internal nullable state (`clients`, `currentSession`, session expiry fields after `/me`) and test helper defaults; they do not block the plan goal or render placeholder UI.

## Threat Flags

None. The new trust-boundary surfaces were already in the plan threat model: backend auth envelopes to Vue session state, and shared apiClient refresh callbacks to the session model.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

Plan 04 can mount this session module in the existing App shell, add login/register/logout UI, and display global session messages without duplicating auth endpoint or refresh callback logic.

## Self-Check: PASSED

- Summary file exists at `.planning/phases/02-frontend-session-api-client-foundation/02-03-SUMMARY.md`.
- Frontend task commits exist: `16a9895`, `ac59203`, `e7d58e3`, `344e45d`.
- Created frontend files exist: `authApi.ts`, `authApi.test.ts`, `authSession.ts`, `authSession.test.ts`.
- Required focused verification passed: `src/services/authSession.test.ts` and `src/services/authApi.test.ts`, 10 tests.
- Additional `npm run build` passed.

---
*Phase: 02-frontend-session-api-client-foundation*
*Completed: 2026-05-31*
