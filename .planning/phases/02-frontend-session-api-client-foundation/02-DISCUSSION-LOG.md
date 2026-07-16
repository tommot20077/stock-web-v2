# Phase 2: Frontend Session & API Client Foundation - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-05-30
**Phase:** 2-Frontend Session & API Client Foundation
**Areas discussed:** Session state model, refresh retry behavior, CSRF bootstrap timing, API mode failure UX, runtime mode guard, auth UI scope

---

## Session State Model

| Option | Description | Selected |
|--------|-------------|----------|
| Minimal states | Track only authenticated vs anonymous. Simpler but cannot distinguish startup, refresh, and backend outage. | |
| Explicit lifecycle states | Track checking, authenticated, anonymous, refreshing, and error. Better for restore, refresh failure, and global UI. | ✓ |
| the agent's Discretion | Let the planner choose the internal state model. | |

**User's choice:** "你覺得呢" — accepted the recommended explicit lifecycle model.
**Notes:** The recommendation is to avoid confusing "still checking" with "logged out" and to make refresh/backend failures visible.

---

## Refresh Retry Behavior

| Option | Description | Selected |
|--------|-------------|----------|
| Safe requests only | Replay GET after refresh; unsafe requests fail and require user action. | |
| One replay for safe and unsafe requests | Replay once after successful refresh; unsafe replay must ensure CSRF first. | ✓ |
| the agent's Discretion | Let the planner decide per endpoint. | |

**User's choice:** "你覺得呢" — accepted the recommended one-refresh/one-replay model.
**Notes:** Refresh must be single-flight and bounded to avoid infinite loops. Unsafe replay is allowed only after CSRF is valid.

---

## CSRF Bootstrap Timing

| Option | Description | Selected |
|--------|-------------|----------|
| App startup bootstrap | Fetch `/api/v1/csrf` at API-mode startup, with unsafe-request fallback ensure. | ✓ |
| Lazy only | Fetch CSRF only before the first unsafe request. | |
| the agent's Discretion | Let the planner decide based on implementation cost. | |

**User's choice:** 預先抓.
**Notes:** Startup bootstrap gives earlier contract failure visibility. Unsafe requests still need fallback ensure to recover from cleared cookies.

---

## API Mode Failure UX

| Option | Description | Selected |
|--------|-------------|----------|
| Global session banner/toast | Use one app-level surface for auth/session/security failures. | ✓ |
| Page-local only | Let each page render its own auth/API failure state. | |
| Mixed without shared contract | Let pages decide independently. | |

**User's choice:** session banner/toast.
**Notes:** Pages may still show local domain errors, but 401, refresh failure, CSRF 403, backend outage, and invalid runtime mode should have one global session surface.

---

## Runtime Mode Guard

| Option | Description | Selected |
|--------|-------------|----------|
| Silent fallback | Keep current behavior where any non-`api` value becomes `mock`. | |
| Explicit invalid-mode failure | Allow unset local mock default, but fail fast for explicitly invalid values and API-mode integration runs. | ✓ |
| Strict explicit mode everywhere | Require `VITE_DATA_MODE` in all environments, including local development. | |

**User's choice:** Asked "甚麼意思"; after explanation, accepted the decision captured in CONTEXT.md.
**Notes:** The problem is a typo such as `VITE_DATA_MODE=ap1` silently running mock mode and hiding backend integration failure.

---

## Auth UI Scope

| Option | Description | Selected |
|--------|-------------|----------|
| No auth UI | Only build session/client internals and rely on tests or existing data. | |
| Login only | Build login/logout/session restore but leave registration out of Phase 2. | |
| Register and login | Build register, login, logout, and session restore so Phase 2 verifies the full browser auth contract. | ✓ |

**User's choice:** "還是要有完整的登入" and then "包含註冊".
**Notes:** The locked scope includes complete register/login/logout/session-restore UI flow inside the existing app shell.

---

## the agent's Discretion

- Exact component names, route placement, store/composable decomposition, and UI copy are left to the planner.
- Exact internal TypeScript type names are left to the planner.

## Deferred Ideas

- Portfolio API mode remains Phase 3.
- Manual trade creation and idempotency remain Phase 4.
- Full browser smoke verification remains Phase 5.
- Multi-device session management, password reset, email verification, and broker/order lifecycle remain out of scope.
