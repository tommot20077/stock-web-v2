---
phase: 02-frontend-session-api-client-foundation
plan: 02
subsystem: frontend-api
tags: [vue, vitest, api-client, csrf, refresh]

requires:
  - phase: 01-browser-auth-contract-backend-security-foundation
    provides: browser CSRF endpoint, refresh endpoint, auth error codes, and ApiResponse meta.traceId contract
  - phase: 02-frontend-session-api-client-foundation
    provides: shared apiClient credentials/envelope/paginated transport from plan 01
provides:
  - Shared Vue CSRF bootstrap helper for GET /api/v1/csrf
  - Unsafe request CSRF guard using XSRF-TOKEN and X-XSRF-TOKEN
  - One-shot single-flight refresh and bounded replay for 401 responses
  - Safe session callbacks for refreshing and refresh/replay failure metadata
affects: [frontend-session-store, auth-ui, portfolio-api-mode, trading-api-mode]

tech-stack:
  added: []
  patterns:
    - apiClient owns CSRF bootstrap/header behavior for all unsafe shared-client requests
    - apiClient owns single-flight refresh and one replay max before surfacing typed ApiClientError

key-files:
  created:
    - .planning/phases/02-frontend-session-api-client-foundation/02-02-SUMMARY.md
  modified:
    - ../../vue/stock-v2/vue-app/src/services/apiClient.ts
    - ../../vue/stock-v2/vue-app/src/services/apiClient.test.ts

key-decisions:
  - "CSRF bootstrap and unsafe request header logic live only in apiClient.ts, not in domain adapters."
  - "Refresh uses a module-level promise for single-flight coordination and exposes only safe error metadata to session callbacks."
  - "Refresh endpoint requests are excluded from recursive refresh handling."

patterns-established:
  - "bootstrapCsrf()/ensureCsrfToken(): shared helpers for app startup and lazy unsafe-request CSRF recovery."
  - "configureApiClientSessionHandlers(): callback hook for future session store integration without importing UI/session state into apiClient."
  - "fetchWithSessionRecovery(): bounded 401 recovery path shared by standard and paginated requests."

requirements-completed: [AUTH-04, FAPI-03, FAPI-05]

duration: 17min
completed: 2026-05-30
---

# Phase 02 Plan 02: Frontend Session & API Client Foundation Summary

**Shared Vue API transport now bootstraps CSRF, protects unsafe cookie-authenticated requests, and recovers 401s with one single-flight refresh plus one bounded replay.**

## Performance

- **Duration:** 17 min
- **Started:** 2026-05-30T15:52:35Z
- **Completed:** 2026-05-30T16:09:34Z
- **Tasks:** 2
- **Files modified:** 2 frontend files plus this summary

## Accomplishments

- Added exported `bootstrapCsrf()` and `ensureCsrfToken()` helpers using `GET /api/v1/csrf`, `credentials: "include"`, readable `XSRF-TOKEN`, and request header `X-XSRF-TOKEN`.
- Updated `apiRequest` and `apiPaginatedRequest` so `POST`, `PUT`, `PATCH`, and `DELETE` ensure CSRF before fetch while `GET` remains header-free.
- Added single-flight refresh handling for first 401 responses, with exactly one replay of the original request and no recursive refresh for `/api/v1/auth/refresh`.
- Added `configureApiClientSessionHandlers()` so future session state can observe refresh progress/failure using safe `{ status, code, message, requestId }` metadata only.

## Task Commits

Each task was committed atomically in the sibling frontend repository:

1. **Task 1 RED: CSRF shared-client tests** - `8e47ff9` (`test`)
2. **Task 1 GREEN: CSRF bootstrap and unsafe guard** - `580c5f1` (`feat`)
3. **Task 2 RED: Refresh/replay tests** - `a78d81f` (`test`)
4. **Task 2 GREEN: Single-flight refresh/replay** - `25cad91` (`feat`)

## Files Created/Modified

- `../../vue/stock-v2/vue-app/src/services/apiClient.ts` - Added CSRF helpers, unsafe method guard, single-flight refresh promise, bounded replay logic, refresh endpoint recursion exclusion, and safe session callbacks.
- `../../vue/stock-v2/vue-app/src/services/apiClient.test.ts` - Added focused Vitest coverage for CSRF bootstrap/header behavior, CSRF 403 typed errors, one-shot refresh/replay, parallel 401 single-flight, refresh failure callback metadata, replay 401 stop behavior, refresh endpoint recursion exclusion, and unsafe replay CSRF re-guard.
- `.planning/phases/02-frontend-session-api-client-foundation/02-02-SUMMARY.md` - Execution evidence and close-out record.

## Decisions Made

- Kept CSRF names hard-bound to the Phase 1 backend contract: readable cookie `XSRF-TOKEN` and header `X-XSRF-TOKEN`.
- Used a module-level refresh promise instead of per-request refresh calls so parallel 401s share one `/api/v1/auth/refresh`.
- Routed both standard and paginated requests through the same recovery path so future domain adapters inherit the same auth semantics.

## Verification Evidence

- RED Task 1: `npm test -- src/services/apiClient.test.ts` failed with 4 expected failures for missing `bootstrapCsrf`, missing `ensureCsrfToken`, and unsafe requests missing `X-XSRF-TOKEN`.
- GREEN Task 1: `npm test -- src/services/apiClient.test.ts` passed, 1 file and 19 tests, after one Vitest worker startup timeout rerun.
- RED Task 2: `npm test -- src/services/apiClient.test.ts` failed with expected failures for missing `configureApiClientSessionHandlers` and missing 401 refresh/replay behavior.
- GREEN Task 2: `npm test -- src/services/apiClient.test.ts` passed, 1 file and 25 tests.
- Final focused gate: `npm test -- src/services/apiClient.test.ts` passed, 1 file and 25 tests.
- Token storage scan: `rg "localStorage|sessionStorage|accessToken|refreshToken" src/services/apiClient.ts src/services/apiClient.test.ts` returned no matches.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

- Vitest hit the known worker startup timeout once during Task 1 GREEN verification. Rerunning the same focused command produced normal test execution and passed.
- Existing unsafe request tests needed explicit CSRF cookie setup after the client correctly began guarding unsafe methods. The assertions remained focused on JSON/header behavior.

## Known Stubs

None. Stub scan matches were internal nullable state (`refreshPromise`, `requestId`, pagination cursor) and empty/default request option objects used by production helpers and tests, not UI-facing placeholders or disconnected data sources.

## Threat Flags

None. The security-relevant surfaces added here were already listed in the plan threat model: CSRF guard, refresh single-flight, stale-session failure callback, and safe metadata payloads.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

Plan 03 can wire explicit frontend session state to `configureApiClientSessionHandlers()` and proactively call `bootstrapCsrf()` on API-mode app startup without duplicating CSRF or refresh behavior outside `apiClient.ts`.

## Self-Check: PASSED

- Summary file exists at `.planning/phases/02-frontend-session-api-client-foundation/02-02-SUMMARY.md`.
- Frontend task commits exist: `8e47ff9`, `580c5f1`, `a78d81f`, `25cad91`.
- Required focused verification passed: `src/services/apiClient.test.ts`, 25 tests.
- Token storage scan returned no matches in `apiClient.ts` or `apiClient.test.ts`.

---
*Phase: 02-frontend-session-api-client-foundation*
*Completed: 2026-05-30*
