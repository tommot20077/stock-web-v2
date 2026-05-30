# Phase 2: Frontend Session & API Client Foundation - Context

**Gathered:** 2026-05-30
**Status:** Ready for planning

<domain>
## Phase Boundary

This phase delivers the Vue API-mode session and HTTP transport foundation. The frontend must restore browser session state through `/api/v1/me`, use the Phase 1 browser cookie and CSRF contract through one shared `apiClient.ts` boundary, preserve mock mode, and provide enough register/login/logout UI to verify the browser auth flow end to end.

This phase does not wire portfolio read models, manual trade creation, broker/order lifecycle, AI/broker settings APIs, or large visual redesigns. Later domain adapters must consume the shared client and session state instead of re-implementing auth, CSRF, refresh, envelope parsing, or runtime-mode behavior.

</domain>

<decisions>
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

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Project and Phase Scope
- `.planning/PROJECT.md` — Core value, active requirements, project constraints, and out-of-scope boundaries.
- `.planning/REQUIREMENTS.md` — Phase 2 requirement IDs `AUTH-03`, `AUTH-04`, and `FAPI-01` through `FAPI-08`.
- `.planning/ROADMAP.md` — Phase 2 goal, dependency on Phase 1, success criteria, and UI hint.
- `.planning/STATE.md` — Current milestone state and Phase 2 pending todo.

### Upstream Auth Contract
- `.planning/phases/01-browser-auth-contract-backend-security-foundation/01-CONTEXT.md` — Locked browser cookie, CSRF, refresh/logout, bearer compatibility, and frontend-responsibility decisions from Phase 1.
- `ai-docs/browser-auth-contract.md` — Backend/frontend browser auth contract: endpoints, cookie names, CSRF names, refresh/logout behavior, error codes, and verification responsibilities.

### Codebase Maps
- `.planning/codebase/STRUCTURE.md` — Backend/frontend layout and where frontend integration code belongs.
- `.planning/codebase/CONVENTIONS.md` — Frontend adapter, test, naming, and API envelope conventions.
- `.planning/codebase/CONCERNS.md` — Known frontend API-mode, auth, CSRF, token storage, and runtime-mode risks.
- `.planning/codebase/TESTING.md` — Frontend Vitest/type-check/build expectations and backend/frontend integration testing guidance.
- `.planning/codebase/ARCHITECTURE.md` — Existing frontend API adapter path and backend auth request path.

### Frontend Source Areas
- `../vue/stock-v2/vue-app/src/services/apiClient.ts` — Current shared fetch/envelope boundary to extend for credentials, CSRF, refresh/replay, and session-aware errors.
- `../vue/stock-v2/vue-app/src/services/runtimeDataMode.ts` — Current runtime mode normalization that silently falls back to mock.
- `../vue/stock-v2/vue-app/src/services/pageApiClients.ts` — Current runtime service-client registry.
- `../vue/stock-v2/vue-app/src/services/apiTypes.ts` — API envelope and runtime-mode TypeScript contracts.
- `../vue/stock-v2/vue-app/src/services/apiClient.test.ts` — Existing shared client tests to extend.
- `../vue/stock-v2/vue-app/src/services/runtimeDataMode.test.ts` — Existing runtime mode tests to extend.
- `../vue/stock-v2/vue-app/src/api-adapter-wiring.test.ts` — Existing API/mock wiring tests that should remain green and gain auth/session assertions where appropriate.
- `../vue/stock-v2/vue-app/src/App.vue` — Current app shell and global overlay placement.
- `../vue/stock-v2/vue-app/src/router.ts` — Current hash route registry and route-transition context.
- `../vue/stock-v2/vue-app/src/store.ts` — Existing mock portfolio/trading store; Phase 2 must not treat it as API session state.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `apiClient.ts` already centralizes JSON body handling, `Accept`/`Content-Type` headers, `ApiResponse<T>` success envelopes, malformed JSON, malformed envelope, and `ApiClientError`.
- `runtimeDataMode.ts` already centralizes mode selection; it is the right place to replace silent invalid-mode fallback with explicit validation rules.
- `pageApiClients.ts` already centralizes mode-specific service creation for backtest, ops, and AI access.
- `App.vue` already owns global overlays such as `Toast`, command palette, order ticket, and settings tweaks; this is a natural integration point for global session banner/toast.
- Existing service tests cover API/mock wiring and error conversion patterns that should be extended instead of replaced.

### Established Patterns
- Frontend services expose typed adapters and keep raw fetch out of components.
- Mock and HTTP implementations live behind the same service interfaces.
- Existing tests use Vitest with `vi.stubEnv`, fetch mocks, and co-located service/component tests.
- The app shell currently owns page rendering, while `router.ts` is a transitional route registry.

### Integration Points
- API mode authenticated calls must use `credentials: "include"` and the Phase 1 browser cookie contract.
- CSRF bootstrap connects to `GET /api/v1/csrf`; unsafe requests connect to the `X-XSRF-TOKEN` header contract.
- Session restore connects to `GET /api/v1/me`.
- Refresh connects to `POST /api/v1/auth/refresh` and must coordinate with the shared client retry path.
- Register/login/logout UI connects to `/api/v1/auth/register`, `/api/v1/auth/login`, and `/api/v1/auth/logout`.

</code_context>

<specifics>
## Specific Ideas

- Build the session model as a small store or composable consumed by `apiClient.ts` and `App.vue`.
- Keep auth/session UI inside the existing product shell; avoid a landing-page treatment.
- Proactively fetch CSRF at API-mode app startup, but keep a lazy `ensureCsrfToken` fallback before unsafe requests.
- Use a global session banner/toast for session-wide auth/security failures; local pages can still display domain-specific errors.
- Include complete register and login because Phase 1 already provides browser register/login contracts and Phase 2 should verify new-user flow.

</specifics>

<deferred>
## Deferred Ideas

- Portfolio summary, holdings/positions, and trade history API-mode wiring belongs to Phase 3.
- Manual executed trade creation, idempotency, and post-trade refetch belongs to Phase 4.
- Cross-repo browser smoke flow and full contract hardening belongs to Phase 5.
- Multi-device session management, password reset, email verification, broker order lifecycle, and AI/broker security policy remain out of scope for this milestone phase.

</deferred>

---

*Phase: 2-Frontend Session & API Client Foundation*
*Context gathered: 2026-05-30*
