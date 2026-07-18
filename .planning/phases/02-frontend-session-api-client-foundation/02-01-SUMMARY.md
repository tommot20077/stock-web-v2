---
phase: 02-frontend-session-api-client-foundation
plan: 01
subsystem: frontend-api
tags: [vue, vitest, api-client, credentials, pagination]

requires:
  - phase: 01-browser-auth-contract-backend-security-foundation
    provides: browser cookie auth contract, ApiResponse envelopes, and meta.traceId contract
provides:
  - Shared Vue API client credentials default and paginated envelope helper
  - API-mode backtest, ops, and AI access paginated adapters routed through shared client
  - Focused Vitest coverage for credentials, trace id preservation, and paginated errors
affects: [frontend-session-api-client-foundation, future-csrf-refresh, portfolio-api-mode]

tech-stack:
  added: []
  patterns:
    - Shared apiClient transport owns credentials, envelope parsing, trace ids, and paginated responses
    - Domain adapters build typed paths and delegate HTTP behavior to apiClient

key-files:
  created:
    - .planning/phases/02-frontend-session-api-client-foundation/02-01-SUMMARY.md
  modified:
    - ../../vue/stock-v2/vue-app/src/services/apiClient.ts
    - ../../vue/stock-v2/vue-app/src/services/apiClient.test.ts
    - ../../vue/stock-v2/vue-app/src/services/backtestApi.ts
    - ../../vue/stock-v2/vue-app/src/services/backtestApi.test.ts
    - ../../vue/stock-v2/vue-app/src/services/opsApi.ts
    - ../../vue/stock-v2/vue-app/src/services/opsApi.test.ts
    - ../../vue/stock-v2/vue-app/src/services/aiAccessApi.ts
    - ../../vue/stock-v2/vue-app/src/services/aiAccessApi.test.ts

key-decisions:
  - "Paginated API-mode requests now use the same shared apiClient request initializer as standard requests."
  - "meta.traceId is preferred over legacy requestId when extracting ApiClientError.requestId."
  - "Malformed success envelopes preserve available trace/request ids; malformed error envelopes without valid error bodies still fall back to HTTP_ERROR."

patterns-established:
  - "apiPaginatedRequest<T>: shared helper validates { data, page } paginated envelopes and throws ApiClientError on backend errors."
  - "Adapter migration: backtest, ops, and AI access keep mock logic local while API-mode paginated calls import apiPaginatedRequest."

requirements-completed: [FAPI-01, FAPI-02, FAPI-04]

duration: 22min
completed: 2026-05-30
---

# Phase 02 Plan 01: Frontend Session & API Client Foundation Summary

**Shared Vue API transport now defaults to browser credentials, preserves backend trace ids, and owns paginated response parsing for existing API-mode adapters.**

## Performance

- **Duration:** 22 min
- **Started:** 2026-05-30T15:21:00Z
- **Completed:** 2026-05-30T15:42:59Z
- **Tasks:** 2
- **Files modified:** 8 frontend files plus this summary

## Accomplishments

- Extended `apiClient.ts` so shared requests default to `credentials: "include"` while preserving explicit caller overrides.
- Added `apiPaginatedRequest<T>` with shared JSON parsing, ApiClientError behavior, paginated envelope validation, and `meta.traceId` / legacy `requestId` extraction.
- Migrated backtest, ops, and AI access paginated HTTP adapters away from duplicated direct fetch helpers.
- Preserved mock-mode adapter behavior and clone-safety coverage.

## Task Commits

Each task was committed atomically in the sibling frontend repository:

1. **Task 1 RED: Extend shared transport contract tests** - `5fef7dd` (`test`)
2. **Task 1 GREEN: Implement shared transport contract** - `37827f9` (`feat`)
3. **Task 2 RED: Paginated adapter shared-client tests** - `9ae22fa` (`test`)
4. **Task 2 GREEN: Route paginated adapters through shared client** - `3132215` (`feat`)

## Files Created/Modified

- `../../vue/stock-v2/vue-app/src/services/apiClient.ts` - Added shared credentials default, trace id extraction, reusable request initializer, and `apiPaginatedRequest`.
- `../../vue/stock-v2/vue-app/src/services/apiClient.test.ts` - Added coverage for credentials, `meta.traceId`, legacy `requestId`, paginated success, backend errors, and malformed paginated payloads.
- `../../vue/stock-v2/vue-app/src/services/backtestApi.ts` - Removed duplicated paginated fetch/envelope helpers and imported `apiPaginatedRequest`.
- `../../vue/stock-v2/vue-app/src/services/backtestApi.test.ts` - Added shared paginated adapter behavior coverage and aligned error expectations to the shared client contract.
- `../../vue/stock-v2/vue-app/src/services/opsApi.ts` - Removed duplicated paginated fetch/envelope helpers and imported `apiPaginatedRequest`.
- `../../vue/stock-v2/vue-app/src/services/opsApi.test.ts` - Added shared paginated adapter behavior coverage and request id preservation assertion.
- `../../vue/stock-v2/vue-app/src/services/aiAccessApi.ts` - Removed duplicated paginated fetch/envelope helpers and imported `apiPaginatedRequest`.
- `../../vue/stock-v2/vue-app/src/services/aiAccessApi.test.ts` - Added shared paginated adapter behavior coverage.

## Decisions Made

- Used `credentials: "include"` in the shared request initializer so standard and paginated API-mode requests inherit the same browser-cookie behavior.
- Kept explicit caller-provided `credentials` honored for test-only or special non-cookie paths.
- Treated valid error envelopes as typed backend errors even when legacy top-level `requestId` is absent or malformed, while preserving trace ids when available.

## Verification Evidence

- RED Task 1: `npm test -- src/services/apiClient.test.ts` failed with 5 expected failures for missing credentials default, missing `meta.traceId` extraction, and missing `apiPaginatedRequest`.
- GREEN Task 1: `npm test -- src/services/apiClient.test.ts` passed, 13 tests.
- RED Task 2: `npm test -- src/services/backtestApi.test.ts src/services/opsApi.test.ts src/services/aiAccessApi.test.ts` failed with 6 expected failures for local paginated fetch helpers lacking shared credentials and trace parsing.
- GREEN / wave gate: `npm test -- src/services/apiClient.test.ts src/services/backtestApi.test.ts src/services/opsApi.test.ts src/services/aiAccessApi.test.ts` passed, 4 files and 41 tests.
- Acceptance grep: `rg "async function apiPaginatedRequest|function isApiFailure|function isPaginatedResponse|function readJson" src/services/backtestApi.ts src/services/opsApi.ts src/services/aiAccessApi.ts` returned no production duplicates.
- Token storage scan: `rg "localStorage|sessionStorage|accessToken|refreshToken" src/services/apiClient.ts src/services/apiClient.test.ts` returned no matches.

## Deviations from Plan

None - plan executed within the planned files and behavior.

## Issues Encountered

- Vitest initially could not start because `node_modules` was missing the existing lockfile-listed Linux Rolldown native binding. Running `npm install` in the sibling frontend repo restored lockfile dependencies, changed no tracked files, and allowed the required tests to run.
- One Vitest run hit a worker startup timeout. A rerun produced normal assertion output, and the final required verification passed.

## Known Stubs

None. Stub scan only matched existing mock adapter in-memory arrays, default parameter objects, and null state values that are intentional test/mock state, not UI-facing placeholders.

## Threat Flags

None. The security-relevant surface was already in the plan threat model: shared browser credentials, trace id parsing, and removal of per-adapter paginated fetch paths.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

Plan 02 can add CSRF bootstrap/header handling and refresh/replay behavior in one shared `apiClient.ts` boundary. Existing paginated domain adapters will inherit those changes through `apiPaginatedRequest`.

## Self-Check: PASSED

- Summary file exists at `.planning/phases/02-frontend-session-api-client-foundation/02-01-SUMMARY.md`.
- Frontend task commits exist: `5fef7dd`, `37827f9`, `9ae22fa`, `3132215`.
- Required focused verification passed: 4 service test files, 41 tests.

---
*Phase: 02-frontend-session-api-client-foundation*
*Completed: 2026-05-30*
