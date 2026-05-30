# Phase 2: Frontend Session & API Client Foundation - Research

**Researched:** 2026-05-30 [VERIFIED: .planning/STATE.md]  
**Domain:** Vue 3 session state, shared HTTP transport, browser cookie auth, CSRF, refresh/replay, runtime-mode safety [VERIFIED: .planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md]  
**Confidence:** HIGH for local codebase and Phase 1 contract; MEDIUM for exact UI placement because that remains planner discretion [VERIFIED: .planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md]

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
## Implementation Decisions

### Session State Model
- **D-01:** Frontend session state must be explicit, not only `authenticated` / `anonymous`. Use states equivalent to `checking`, `authenticated`, `anonymous`, `refreshing`, and `error` so app boot, refresh attempts, backend outage, and session loss are distinguishable.
- **D-02:** Vue must not store access tokens or refresh tokens in local storage, session storage, Pinia, reactive state, or any JavaScript-readable persistence. Session state may store user/session UI metadata only.
- **D-03:** On app boot or refresh, API mode must call `/api/v1/me` to restore authenticated or anonymous state. Mock mode remains independent and must not require a backend session.

### Refresh and Replay
- **D-04:** `apiClient.ts` owns 401 handling and attempts at most one refresh/replay per original request.
- **D-05:** Refresh attempts must be single-flight so parallel 401 responses do not trigger parallel `/api/v1/auth/refresh` calls.
- **D-06:** Safe GET requests may be replayed after a successful refresh. Unsafe requests may also be replayed once, but only after the client confirms a valid CSRF token is available or successfully re-bootstraps CSRF.
- **D-07:** If refresh fails or the replay receives another 401, the client stops retrying, updates session state to anonymous/error as appropriate, and surfaces the problem through the global session UI.

### CSRF Bootstrap
- **D-08:** API mode should proactively bootstrap CSRF on app startup with `GET /api/v1/csrf`.
- **D-09:** Unsafe API-mode requests must still call an `ensureCsrfToken`-style guard before sending, so missing/expired/cleared readable CSRF cookies can recover without requiring a reload.
- **D-10:** The frontend follows the Phase 1 naming contract: readable cookie `XSRF-TOKEN` and request header `X-XSRF-TOKEN`.

### Auth and Session UI
- **D-11:** Phase 2 includes complete register, login, logout, and session-restore UI flow. This is required to verify the Phase 1 browser contract from the Vue app.
- **D-12:** Auth UI should fit into the existing app shell rather than introducing a marketing or landing page. It may be a focused auth view, modal, or route-level surface chosen by the planner based on existing Vue structure.
- **D-13:** Refresh failure returns the UI to a login/anonymous state and displays a user-safe session message. It must not leave stale authenticated UI visible.

### Error Presentation
- **D-14:** Auth/session/security failures use a global session banner/toast. Pages may still show local domain errors, but 401, refresh failure, CSRF 403, backend session outage, and invalid API-mode configuration should have one consistent global surface.
- **D-15:** User-facing errors should be concise, while developer details keep backend `error.code`, HTTP status, and trace/request id available for debugging.
- **D-16:** CSRF 403 must be distinguishable from generic authorization failure by using the backend error code, especially `AUTH_CSRF_TOKEN_INVALID`.

### Runtime Mode Guard
- **D-17:** Preserve mock and API modes. Unset `VITE_DATA_MODE` may remain local-development mock by default.
- **D-18:** If `VITE_DATA_MODE` is explicitly set to an invalid value, fail fast instead of silently falling back to mock.
- **D-19:** CI, integration, and production-like API-mode verification must require an explicit valid mode. Backend-integrated runs must not pass by accidentally using mock data.

### Shared Client Boundary
- **D-20:** `../vue/stock-v2/vue-app/src/services/apiClient.ts` is the single HTTP transport boundary for API mode credentials, CSRF, refresh/replay, envelope parsing, request/trace id handling, and malformed-response errors.
- **D-21:** Domain services such as backtest, ops, AI access, future portfolio, and future trading adapters should only build typed paths/payloads and call the shared client.
- **D-22:** The planner should preserve existing mock adapters and tests while tightening API-mode behavior.

### the agent's Discretion
- The exact auth UI placement, route names, component names, and store/composable decomposition are up to the planner, provided the flow covers register, login, logout, `/me` restore, refresh failure, and global session messaging.
- The exact internal TypeScript type names are up to the planner, provided token values are never stored and session states remain explicit.
- The exact user-facing copy is up to the planner, provided it is operational, short, and exposes error code/request id details where useful for debugging.

### Deferred Ideas (OUT OF SCOPE)
## Deferred Ideas

- Portfolio summary, holdings/positions, and trade history API-mode wiring belongs to Phase 3.
- Manual executed trade creation, idempotency, and post-trade refetch belongs to Phase 4.
- Cross-repo browser smoke flow and full contract hardening belongs to Phase 5.
- Multi-device session management, password reset, email verification, broker order lifecycle, and AI/broker security policy remain out of scope for this milestone phase.
</user_constraints>

## Project Constraints (from AGENTS.md / CLAUDE.md)

- Address Yuan in Traditional Chinese for user-facing output. [VERIFIED: CLAUDE.md]
- Production code changes must follow TDD: write failing test, run red, implement minimum code, run green, refactor. [VERIFIED: CLAUDE.md]
- Do not modify production code without tests unless Yuan explicitly waives the rule. [VERIFIED: .planning/codebase/CONVENTIONS.md]
- Preserve Java 21/Spring Boot backend and Vue 3/Vite sibling frontend; do not introduce a new application framework. [VERIFIED: AGENTS.md]
- Browser cookie auth must include CSRF protection before unsafe endpoints rely on cookies. [VERIFIED: AGENTS.md]
- Preserve mock mode while adding API mode. [VERIFIED: AGENTS.md]
- REST responses must keep the common `ApiResponse<T>` envelope and backend error semantics. [VERIFIED: AGENTS.md]
- Backend verification uses Maven tests; frontend verification uses type-check/build and focused Vitest coverage. [VERIFIED: AGENTS.md]
- For web browsing, use the `/browse` gstack skill; this research used local repo files only. [VERIFIED: AGENTS.md]

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| AUTH-03 | Vue app refresh restores login state through `/api/v1/me`. [VERIFIED: .planning/REQUIREMENTS.md] | Use an API-mode session store/composable that calls `GET /api/v1/me` on app boot and transitions to `authenticated` or `anonymous`. [VERIFIED: .planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md] |
| AUTH-04 | Expired session can attempt one `/api/v1/auth/refresh`. [VERIFIED: .planning/REQUIREMENTS.md] | Put one refresh/replay attempt in `apiClient.ts`, single-flight parallel refreshes, and stop on failed refresh or second 401. [VERIFIED: .planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md] |
| FAPI-01 | All API-mode HTTP requests use shared `apiClient.ts`. [VERIFIED: .planning/REQUIREMENTS.md] | Remove duplicated paginated `fetch` helpers from `backtestApi.ts`, `opsApi.ts`, and `aiAccessApi.ts`; route them through shared client. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/backtestApi.ts; /mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/opsApi.ts; /mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/aiAccessApi.ts] |
| FAPI-02 | Shared API client defaults to `credentials: "include"` in API mode. [VERIFIED: .planning/REQUIREMENTS.md] | Extend `apiRequest` defaults because current `fetch(path, init)` does not set credentials. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/apiClient.ts] |
| FAPI-03 | Unsafe requests add CSRF header and handle CSRF 403. [VERIFIED: .planning/REQUIREMENTS.md] | Implement `GET /api/v1/csrf`, readable cookie `XSRF-TOKEN`, and header `X-XSRF-TOKEN` handling in shared client. [VERIFIED: ai-docs/browser-auth-contract.md] |
| FAPI-04 | Shared client parses `ApiResponse<T>`, error code/message, request/trace id. [VERIFIED: .planning/REQUIREMENTS.md] | Current client parses success/error envelopes but expects top-level `requestId`; Phase 1 backend uses `meta.traceId`, so parsing must accept `meta.traceId` and preserve backward-compatible request id where needed. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/apiClient.ts; stock-common/src/main/java/dowob/xyz/stockwebv2/common/api/ApiResponse.java; ai-docs/browser-auth-contract.md] |
| FAPI-05 | 401 refresh/replay is limited to one attempt. [VERIFIED: .planning/REQUIREMENTS.md] | Add per-request retry marker and shared refresh promise to avoid loops and refresh storms. [VERIFIED: .planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md] |
| FAPI-06 | Auth store stores no access/refresh tokens. [VERIFIED: .planning/REQUIREMENTS.md] | Store only session status, user metadata, expiry timestamps, last error, and flags; no token fields. [VERIFIED: ai-docs/browser-auth-contract.md] |
| FAPI-07 | API backend errors/outage show error/retry state, not mock fallback. [VERIFIED: .planning/REQUIREMENTS.md] | Preserve mock adapter selection, but make API mode failures surface through global session UI and local adapter errors. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/src/api-adapter-wiring.test.ts] |
| FAPI-08 | Runtime mode remains mock/api, invalid integration/prod mode cannot silently mock. [VERIFIED: .planning/REQUIREMENTS.md] | Change `normalizeRuntimeDataMode` because current unknown values return `mock`. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/runtimeDataMode.ts] |
</phase_requirements>

## Research Question

What does the planner need to know to implement Phase 2 as a frontend session and API transport foundation without breaking mock mode, leaking tokens, duplicating auth/CSRF logic, or masking API-mode integration failures? [VERIFIED: user prompt; .planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md]

## Scope Summary

Phase 2 should be planned as frontend-first work in `/mnt/d/end/workspace/vue/stock-v2/vue-app`, with backend changes treated as out-of-scope unless implementation uncovers a mismatch against the already documented Phase 1 contract. [VERIFIED: .planning/ROADMAP.md; ai-docs/browser-auth-contract.md; stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java]

The backend already exposes the required Phase 2 browser-auth endpoints: `POST /api/v1/auth/register`, `POST /api/v1/auth/login`, `POST /api/v1/auth/refresh`, `POST /api/v1/auth/logout`, `GET /api/v1/csrf`, and `GET /api/v1/me`. [VERIFIED: stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java; stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/CsrfController.java]

The main deliverable is a shared transport/session boundary: all API-mode services use `apiClient.ts`; auth/session state lives in a small store/composable; `App.vue` hosts the session UI; mock adapters remain independent. [VERIFIED: .planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md; /mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/pageApiClients.ts; /mnt/d/end/workspace/vue/stock-v2/vue-app/src/App.vue]

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|--------------|----------------|-----------|
| Session restore from `/api/v1/me` | Browser / Client | API / Backend | Vue owns boot-time UI state; backend owns authoritative current-user response. [VERIFIED: ai-docs/browser-auth-contract.md] |
| Cookie authentication and refresh rotation | API / Backend | Browser / Client | Backend owns httpOnly cookies and refresh rotation; client only triggers refresh and never reads tokens. [VERIFIED: ai-docs/browser-auth-contract.md] |
| CSRF header on unsafe requests | Browser / Client | API / Backend | Backend issues readable `XSRF-TOKEN`; shared client must read it and send `X-XSRF-TOKEN`. [VERIFIED: ai-docs/browser-auth-contract.md] |
| Global session/security error UI | Browser / Client | — | Existing `App.vue` owns global overlays and toasts, making it the correct visible surface. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/src/App.vue] |
| Runtime mock/API selection | Browser / Client | — | Existing frontend mode selection lives in `runtimeDataMode.ts` and `pageApiClients.ts`. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/runtimeDataMode.ts; /mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/pageApiClients.ts] |
| Backend contract changes | API / Backend | Browser / Client | Backend contract is already implemented by Phase 1; Phase 2 should consume it rather than redefine it. [VERIFIED: stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java; ai-docs/browser-auth-contract.md] |

## Source Findings

### Phase 1 Contract

- Browser register/login/refresh responses return `BrowserSessionResponse` with `user`, `accessTokenExpiresAt`, and `refreshTokenExpiresAt`, not token bodies. [VERIFIED: stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/BrowserSessionResponse.java]
- `/api/v1/me` returns `MeResponse` containing `id`, `uuid`, `email`, `username`, `role`, and `status`. [VERIFIED: stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/MeResponse.java]
- `/api/v1/csrf` returns `{ cookieName: "XSRF-TOKEN", headerName: "X-XSRF-TOKEN" }` and sets the readable CSRF cookie. [VERIFIED: stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/CsrfController.java]
- Cookie-authenticated unsafe requests fail with `AUTH_CSRF_TOKEN_INVALID` when cookie/header are missing or mismatched. [VERIFIED: stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java; stock-start/src/test/java/dowob/xyz/stockwebv2/start/BrowserAuthFlowIT.java]
- Refresh is an unsafe `POST /api/v1/auth/refresh` and requires CSRF in browser cookie mode. [VERIFIED: ai-docs/browser-auth-contract.md; stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java]

### Existing Frontend Patterns

- `apiClient.ts` already centralizes JSON serialization, `Accept`/`Content-Type` defaults, success envelope unwrapping, malformed JSON errors, malformed envelope errors, and `ApiClientError`. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/apiClient.ts]
- `apiClient.ts` currently does not set `credentials: "include"`, does not bootstrap CSRF, and does not implement 401 refresh/replay. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/apiClient.ts]
- `backtestApi.ts`, `opsApi.ts`, and `aiAccessApi.ts` duplicate paginated request/error parsing with direct `fetch`, so FAPI-01 needs a cleanup wave. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/backtestApi.ts; /mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/opsApi.ts; /mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/aiAccessApi.ts]
- `runtimeDataMode.ts` currently maps every value except `'api'` to `'mock'`, including invalid explicit values. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/runtimeDataMode.ts]
- `pageApiClients.ts` memoizes runtime clients by mode and base path and already exposes `resetRuntimeApiClientsForTests()`. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/pageApiClients.ts]
- `App.vue` owns global page rendering, toast state, command palette, order ticket, and tweaks panel, so it is the least disruptive place to mount session restore and global session messages. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/src/App.vue]
- `store.ts` is a mutable mock portfolio/trading/alert store and should not become session/auth state. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/src/store.ts]

### Environment and Tests

- The sibling frontend is at `/mnt/d/end/workspace/vue/stock-v2/vue-app`, not under the backend repository root. [VERIFIED: local filesystem search]
- Frontend scripts are `npm test`, `npm run build`, `npm run dev`, `npm run preview`, and `npm run test:watch`. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/package.json]
- `npm run build` runs `vue-tsc --noEmit && vite build`. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/package.json]
- Local Node is `v22.20.0`, npm is `10.9.3`, and `node_modules` is present. [VERIFIED: `node --version`; `npm --version`; filesystem check]
- Vitest uses jsdom through `vite.config.ts`. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/vite.config.ts]
- Existing frontend tests use `vi.stubEnv`, `vi.stubGlobal('fetch', ...)`, `mountWithPinia`, `cleanupMounted`, and `resetRuntimeApiClientsForTests()`. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/src/api-adapter-wiring.test.ts; /mnt/d/end/workspace/vue/stock-v2/vue-app/src/testUtils.ts]

## Recommended Implementation Shape

### Files to Add or Extend

| File | Recommendation |
|------|----------------|
| `src/services/apiClient.ts` | Extend into the only API-mode transport: credentials, CSRF guard, refresh/replay, paginated request helper, `meta.traceId` parsing, typed auth/security errors. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/apiClient.ts] |
| `src/services/apiClient.test.ts` | Add red/green tests for credentials, CSRF bootstrap/header, CSRF 403, single retry, failed refresh, parallel 401 single-flight, unsafe replay guard, malformed envelopes. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/apiClient.test.ts] |
| `src/services/authApi.ts` | Add typed browser auth adapter for `register`, `login`, `refresh`, `logout`, `me`, `csrf`; do not expose token fields. [VERIFIED: ai-docs/browser-auth-contract.md] |
| `src/services/authSession.ts` or `src/composables/useSession.ts` | Add explicit session state model: `checking`, `authenticated`, `anonymous`, `refreshing`, `error`; store user metadata and expiry timestamps only. [VERIFIED: .planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md] |
| `src/services/runtimeDataMode.ts` | Change explicit invalid values to throw/fail fast while preserving unset local default to `mock`. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/runtimeDataMode.ts] |
| `src/services/backtestApi.ts`, `opsApi.ts`, `aiAccessApi.ts` | Replace duplicated paginated direct fetch helpers with shared `apiClient` paginated helper. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/backtestApi.ts; /mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/opsApi.ts; /mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/aiAccessApi.ts] |
| `src/App.vue` | Initialize API-mode session, bootstrap CSRF, mount auth UI/global session message, and keep existing shell/page structure. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/src/App.vue] |
| `src/components/AuthPanel.vue` or equivalent | Implement focused register/login/logout flow inside product shell; no landing page. [VERIFIED: .planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md] |

### Transport Behavior

1. Default every API-mode shared-client request to `credentials: "include"` unless a test-only override is explicitly supplied. [VERIFIED: ai-docs/browser-auth-contract.md]
2. Define unsafe methods as `POST`, `PUT`, `PATCH`, and `DELETE`; call `ensureCsrfToken()` before sending unsafe browser-cookie requests. [VERIFIED: ai-docs/browser-auth-contract.md]
3. Implement `ensureCsrfToken()` as lazy/proactive: call `GET /api/v1/csrf` with credentials, read `document.cookie` for `XSRF-TOKEN`, and add `X-XSRF-TOKEN`. [VERIFIED: ai-docs/browser-auth-contract.md]
4. On 401, if the original request has not already retried, acquire a single shared refresh promise, refresh with CSRF, then replay the original request once. [VERIFIED: .planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md]
5. If refresh fails or replay returns another 401, clear authenticated session state and surface global session message. [VERIFIED: .planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md]
6. If an error envelope has `error.code === "AUTH_CSRF_TOKEN_INVALID"`, throw an `ApiClientError` preserving code/status/trace id so UI can render a CSRF-specific message. [VERIFIED: ai-docs/browser-auth-contract.md]
7. Parse request id from either legacy top-level `requestId` or Phase 1 `meta.traceId`; prefer `meta.traceId` for backend auth/security responses. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/apiClient.ts; ai-docs/browser-auth-contract.md]

### Session State Behavior

Use a singleton composable/store rather than `store.ts` for auth/session state. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/src/store.ts]

```ts
type SessionState =
  | { status: 'checking'; user: null; error: null }
  | { status: 'authenticated'; user: MeResponse; accessTokenExpiresAt?: string; refreshTokenExpiresAt?: string; error: null }
  | { status: 'anonymous'; user: null; error: SessionMessage | null }
  | { status: 'refreshing'; user: MeResponse | null; error: null }
  | { status: 'error'; user: null; error: SessionMessage };
```

This shape is recommended because it directly encodes the locked states and avoids any token fields. [VERIFIED: .planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md]

## Existing Patterns to Preserve

- Keep typed service adapters with `createMock*Api` and `createHttp*Api` factories; do not call raw `fetch` from pages or components. [VERIFIED: .planning/codebase/CONVENTIONS.md]
- Keep mock implementations independent and clone mutable mock state before returning it. [VERIFIED: .planning/codebase/CONVENTIONS.md; /mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/aiAccessApi.ts]
- Preserve `pageApiClients.ts` as the runtime service registry; update it only as needed for auth/session initialization or test resets. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/pageApiClients.ts]
- Keep auth UI inside `App.vue` shell or a shell-mounted component; do not replace the app with a marketing page or redesign. [VERIFIED: .planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md]
- Continue using co-located Vitest tests and `testUtils.ts` helpers for component/session UI tests. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/src/testUtils.ts]

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Token storage | JavaScript-readable access/refresh token cache | httpOnly backend cookies plus `/me` state restore | Tokens must not be readable by Vue JavaScript. [VERIFIED: ai-docs/browser-auth-contract.md] |
| Per-adapter auth handling | Refresh/CSRF code inside each domain adapter | Shared `apiClient.ts` hooks | FAPI-01 and D-20 require one transport boundary. [VERIFIED: .planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md] |
| Parallel refresh coordination | One refresh call per failed request | Single shared refresh promise | D-05 requires single-flight refresh. [VERIFIED: .planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md] |
| Silent mode fallback | Unknown `VITE_DATA_MODE` becomes mock | Explicit validation and fail-fast invalid mode | Current fallback can hide integration failure. [VERIFIED: .planning/REQUIREMENTS.md; /mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/runtimeDataMode.ts] |
| Session UI state | Reusing mock portfolio `store.ts` | Dedicated auth/session store or composable | `store.ts` owns mock positions/trades/alerts, not auth state. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/src/store.ts] |

## Required TDD Plan Inputs

Wave 1 should start with `apiClient.test.ts` red tests for `credentials: "include"`, success/error `meta.traceId` parsing, paginated shared helper, and no raw paginated direct fetch in HTTP adapters. [VERIFIED: CLAUDE.md; /mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/apiClient.test.ts]

Wave 2 should add red tests for CSRF bootstrap/header behavior: unsafe POST calls `/api/v1/csrf` when cookie missing, sends `X-XSRF-TOKEN` when cookie present, preserves caller headers, and surfaces `AUTH_CSRF_TOKEN_INVALID`. [VERIFIED: ai-docs/browser-auth-contract.md]

Wave 3 should add red tests for refresh/replay: one retry max, failed refresh stops, replay 401 stops, parallel 401s use one refresh call, unsafe replay only happens after CSRF is available. [VERIFIED: .planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md]

Wave 4 should add red tests for session state and auth UI: API-mode boot calls `/api/v1/me`; 200 becomes authenticated; 401 becomes anonymous; backend outage becomes error; login/register/logout update state; tokens are absent from state and persisted storage. [VERIFIED: .planning/REQUIREMENTS.md; ai-docs/browser-auth-contract.md]

Wave 5 should add red tests for runtime mode guard and app wiring: unset mode defaults to mock for local development, explicit `api` works, explicit `mock` works, explicit invalid mode throws, API-mode backend failures show errors rather than mock data. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/runtimeDataMode.test.ts; /mnt/d/end/workspace/vue/stock-v2/vue-app/src/api-adapter-wiring.test.ts]

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | Vitest 4.1.6 with jsdom; Vue 3.5.34; Vite 8.0.13. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/package.json; /mnt/d/end/workspace/vue/stock-v2/vue-app/vite.config.ts] |
| Config file | `/mnt/d/end/workspace/vue/stock-v2/vue-app/vite.config.ts`. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/vite.config.ts] |
| Quick run command | `cd /mnt/d/end/workspace/vue/stock-v2/vue-app && npm test -- src/services/apiClient.test.ts src/services/runtimeDataMode.test.ts` [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/package.json] |
| Full frontend command | `cd /mnt/d/end/workspace/vue/stock-v2/vue-app && npm test && npm run build` [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/package.json] |
| Backend contract smoke if needed | `./mvnw -pl stock-start -am verify -Dspring-boot.repackage.skip=true --fail-at-end --no-transfer-progress` [VERIFIED: .planning/codebase/TESTING.md] |

### Phase Requirements to Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|--------------|
| AUTH-03 | Boot/session restore calls `/api/v1/me` and sets explicit state. [VERIFIED: .planning/REQUIREMENTS.md] | unit/component | `npm test -- src/services/authSession.test.ts src/App.test.ts` | ❌ Wave 0 |
| AUTH-04/FAPI-05 | 401 triggers at most one refresh/replay. [VERIFIED: .planning/REQUIREMENTS.md] | unit | `npm test -- src/services/apiClient.test.ts` | ✅ extend |
| FAPI-01 | HTTP adapters use shared client, including paginated requests. [VERIFIED: .planning/REQUIREMENTS.md] | unit | `npm test -- src/services/backtestApi.test.ts src/services/opsApi.test.ts src/services/aiAccessApi.test.ts` | ✅ extend |
| FAPI-02 | API requests include credentials by default. [VERIFIED: .planning/REQUIREMENTS.md] | unit | `npm test -- src/services/apiClient.test.ts` | ✅ extend |
| FAPI-03 | Unsafe requests attach CSRF and CSRF 403 is distinguishable. [VERIFIED: .planning/REQUIREMENTS.md] | unit | `npm test -- src/services/apiClient.test.ts` | ✅ extend |
| FAPI-04 | Success/error envelopes parse `meta.traceId` and legacy `requestId`. [VERIFIED: .planning/REQUIREMENTS.md] | unit | `npm test -- src/services/apiClient.test.ts` | ✅ extend |
| FAPI-06 | Session store contains no token values and does not write token storage. [VERIFIED: .planning/REQUIREMENTS.md] | unit | `npm test -- src/services/authSession.test.ts` | ❌ Wave 0 |
| FAPI-07 | API-mode outage renders error/retry, no mock fallback. [VERIFIED: .planning/REQUIREMENTS.md] | component/integration | `npm test -- src/api-adapter-wiring.test.ts src/App.test.ts` | ✅ extend / ❌ App test |
| FAPI-08 | Invalid explicit runtime mode fails fast. [VERIFIED: .planning/REQUIREMENTS.md] | unit | `npm test -- src/services/runtimeDataMode.test.ts` | ✅ extend |

### Sampling Rate

- Per task commit: run the focused test file changed by that task, usually `npm test -- src/services/apiClient.test.ts` or `npm test -- src/services/authSession.test.ts`. [VERIFIED: CLAUDE.md]
- Per wave merge: run `cd /mnt/d/end/workspace/vue/stock-v2/vue-app && npm test`. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/package.json]
- Phase gate: run `cd /mnt/d/end/workspace/vue/stock-v2/vue-app && npm test && npm run build`; run backend `stock-start` verify only if backend contract files are touched or a frontend test uncovers a backend mismatch. [VERIFIED: .planning/codebase/TESTING.md]

### Wave 0 Gaps

- [ ] `src/services/authApi.ts` and `src/services/authApi.test.ts` for browser auth endpoint wrappers. [VERIFIED: ai-docs/browser-auth-contract.md]
- [ ] `src/services/authSession.ts` or `src/composables/useSession.ts` plus tests for explicit state transitions and no token storage. [VERIFIED: .planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md]
- [ ] `src/App.test.ts` or equivalent shell test for boot restore, global session banner/toast, and auth UI placement. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/src/App.vue]
- [ ] Shared `apiPaginatedRequest` in `apiClient.ts` to eliminate duplicated direct `fetch`. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/backtestApi.ts; /mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/opsApi.ts; /mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/aiAccessApi.ts]

## Threat Model

| Threat | STRIDE | Planning Control |
|--------|--------|------------------|
| Access/refresh token leakage through JS state/storage | Information Disclosure | Never model token fields; add tests that state/storage do not contain `accessToken` or `refreshToken`. [VERIFIED: ai-docs/browser-auth-contract.md] |
| CSRF on cookie-authenticated unsafe requests | Tampering | Shared client must call `/api/v1/csrf` and attach `X-XSRF-TOKEN`; tests must cover missing and present cookie paths. [VERIFIED: ai-docs/browser-auth-contract.md] |
| Infinite refresh loop | Denial of Service | Per-request retry marker and stop after failed refresh or replay 401. [VERIFIED: .planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md] |
| Parallel 401 refresh storm | Denial of Service | Single-flight refresh promise shared across pending 401 handlers. [VERIFIED: .planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md] |
| Unsafe replay without CSRF after refresh | Tampering | Re-run CSRF guard before replaying unsafe methods. [VERIFIED: .planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md] |
| Stale authenticated UI after session loss | Spoofing | Session failure transitions to anonymous/error and global message hides auth-only affordances. [VERIFIED: .planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md] |
| Invalid API-mode config masked by mock data | Repudiation / Integrity | Explicit invalid `VITE_DATA_MODE` fails fast; API-mode failures render errors. [VERIFIED: .planning/REQUIREMENTS.md] |

## Common Pitfalls

- **Parsing only top-level `requestId`:** Phase 1 backend auth/security envelopes use `meta.traceId`; frontend should preserve trace id for debugging. [VERIFIED: ai-docs/browser-auth-contract.md; stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java]
- **Bootstrapping CSRF only once:** A user can clear cookies or receive a rotated/expired readable CSRF cookie, so unsafe requests still need a lazy guard. [VERIFIED: .planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md]
- **Refreshing refresh requests:** The client must not recursively refresh when `/api/v1/auth/refresh` itself returns 401. [VERIFIED: .planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md]
- **Moving auth into domain adapters:** Domain clients should only build typed paths/payloads; auth, credentials, CSRF, refresh, and envelope parsing stay in `apiClient.ts`. [VERIFIED: .planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md]
- **Using `store.ts` for auth:** That store mutates mock portfolio/trading/alerts and should not carry session security state. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/src/store.ts]
- **Assuming mock mode covers integration:** Mock mode intentionally avoids backend session requirements, so API-mode tests must explicitly set `VITE_DATA_MODE=api`. [VERIFIED: .planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md]

## Planning Recommendations

1. Plan Wave 1 as shared transport cleanup: extend `apiClient.ts`, add `apiPaginatedRequest`, parse `meta.traceId`, default credentials, and migrate existing paginated helpers out of domain adapters. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/apiClient.ts; /mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/backtestApi.ts]
2. Plan Wave 2 as CSRF and refresh core: add CSRF bootstrap/guard, single-flight refresh, one replay max, unsafe replay guard, and tests for all edge cases. [VERIFIED: ai-docs/browser-auth-contract.md; .planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md]
3. Plan Wave 3 as auth/session API: add browser auth adapter and explicit session store/composable with boot restore, login/register/logout, and no token persistence. [VERIFIED: stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java; stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/BrowserSessionResponse.java]
4. Plan Wave 4 as app shell UI integration: mount auth UI and global session message in `App.vue`; keep layout minimal and product-shell-native. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/src/App.vue; .planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md]
5. Plan Wave 5 as runtime-mode hardening and final verification: invalid explicit mode fails, API-mode outage surfaces, all frontend tests/build pass, backend verify only if contract mismatch is found. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/runtimeDataMode.ts; .planning/codebase/TESTING.md]

## State of the Art

| Old Approach | Current Phase Approach | Impact |
|--------------|------------------------|--------|
| Bearer tokens in browser JS response/state | httpOnly cookies plus `/me` session restore | Reduces token exfiltration surface and aligns with Phase 1 browser contract. [VERIFIED: ai-docs/browser-auth-contract.md] |
| Per-adapter fetch/error parsing | One shared `apiClient.ts` transport | Prevents auth/CSRF/envelope drift across domain adapters. [VERIFIED: .planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md] |
| Unknown runtime mode becomes mock | Invalid explicit runtime mode fails fast | Prevents backend integration failures being hidden by mock data. [VERIFIED: .planning/REQUIREMENTS.md] |
| No session state model | Explicit `checking/authenticated/anonymous/refreshing/error` state | Makes backend outage, refresh, session loss, and anonymous state distinguishable. [VERIFIED: .planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md] |

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|-------------|-----------|---------|----------|
| Node.js | Vite/Vitest/frontend build | Yes | `v22.20.0` | None needed. [VERIFIED: `node --version`] |
| npm | Frontend scripts | Yes | `10.9.3` | None needed. [VERIFIED: `npm --version`] |
| Frontend dependencies | Vitest/build | Yes | `node_modules` present | Run `npm install` only if missing in another workspace. [VERIFIED: filesystem check] |
| Maven wrapper | Backend contract verification if needed | Yes | Maven wrapper documented as 3.9.14 | Use `./mvnw` from backend root. [VERIFIED: .planning/codebase/STACK.md] |

**Missing dependencies with no fallback:** None found for research/planning. [VERIFIED: local environment checks]

**Missing dependencies with fallback:** None found for research/planning. [VERIFIED: local environment checks]

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `App.vue` shell integration will be sufficient for auth UI without adding route guards. [ASSUMED] | Recommended Implementation Shape | Planner may need a small route-level auth surface if shell-only UX becomes awkward. |

## Open Questions

1. **Should auth UI be a modal, shell panel, or route entry?** [ASSUMED]
   - What we know: It must fit the existing app shell and cover register/login/logout/session restore. [VERIFIED: .planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md]
   - What's unclear: Exact placement was intentionally left to planner discretion. [VERIFIED: .planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md]
   - Recommendation: Use a shell-mounted focused auth panel first; add route naming only if tests show navigation needs it. [ASSUMED]

2. **Should runtime mode validation depend on an extra env such as integration/prod flag?** [ASSUMED]
   - What we know: Unset may remain mock for local development, but explicit invalid mode must fail fast. [VERIFIED: .planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md]
   - What's unclear: The frontend currently has no `engines`, `.nvmrc`, or documented environment classifier in `package.json`. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/package.json]
   - Recommendation: Make `normalizeRuntimeDataMode` throw for any non-empty value other than `mock` or `api`; keep only `undefined`/empty as local mock default. [VERIFIED: .planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md]

## Sources

### Primary (HIGH confidence)

- `.planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md` - locked Phase 2 implementation decisions and deferred scope.  
- `.planning/REQUIREMENTS.md` - Phase 2 requirement IDs and acceptance semantics.  
- `.planning/ROADMAP.md` - Phase 2 goal, dependency, and success criteria.  
- `.planning/STATE.md` - current milestone state and Phase 2 readiness.  
- `.planning/phases/01-browser-auth-contract-backend-security-foundation/01-CONTEXT.md` - upstream locked browser-auth contract.  
- `ai-docs/browser-auth-contract.md` - browser endpoint, cookie, CSRF, refresh/logout, and frontend responsibility contract.  
- `/mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/apiClient.ts` - current shared client behavior.  
- `/mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/runtimeDataMode.ts` - current mode fallback behavior.  
- `/mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/pageApiClients.ts` - current mock/API service registry.  
- `/mnt/d/end/workspace/vue/stock-v2/vue-app/src/App.vue` - current shell/global overlay owner.  
- `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java` and `CsrfController.java` - implemented backend endpoints.  
- `stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java` - cookie auth and CSRF enforcement.

### Secondary (MEDIUM confidence)

- `.planning/codebase/STRUCTURE.md`, `CONVENTIONS.md`, `CONCERNS.md`, `TESTING.md`, `ARCHITECTURE.md` - generated codebase maps verified against sampled source files.  

### Tertiary (LOW confidence)

- None; no web search or unverified external package recommendation was used.

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - Vue/Vite/Vitest versions and scripts were read from `package.json` and `vite.config.ts`. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/package.json; /mnt/d/end/workspace/vue/stock-v2/vue-app/vite.config.ts]
- Architecture: HIGH - frontend and backend integration points were checked in local source files. [VERIFIED: /mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/apiClient.ts; stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java]
- Pitfalls: HIGH - risks are locked in Phase 2 context or present in current code. [VERIFIED: .planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md; /mnt/d/end/workspace/vue/stock-v2/vue-app/src/services/runtimeDataMode.ts]
- UI placement: MEDIUM - exact auth UI placement is intentionally discretionary. [VERIFIED: .planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md]

**Research date:** 2026-05-30 [VERIFIED: current_date]  
**Valid until:** 2026-06-29, or earlier if frontend runtime/client files change. [ASSUMED]

## RESEARCH COMPLETE
