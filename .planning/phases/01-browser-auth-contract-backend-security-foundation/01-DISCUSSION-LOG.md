# Phase 1: Browser Auth Contract & Backend Security Foundation - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-05-30
**Phase:** 1-Browser Auth Contract & Backend Security Foundation
**Areas discussed:** Cookie session model, Refresh/logout semantics, CSRF contract, Bearer compatibility and response contract

---

## Cookie Session Model

| Option | Description | Selected |
|--------|-------------|----------|
| Access + Refresh cookies | Put both access and refresh tokens in httpOnly cookies; Vue stores no token; backend reads access from cookie or bearer. | ✓ |
| Refresh cookie only | Put refresh token in httpOnly cookie and keep access token in frontend memory. | |
| Server session cookie | Use opaque server session id in Redis instead of JWT browser session. | |

**User's choice:** Access + Refresh cookies.
**Notes:** Yuan chose the browser-safe cookie model. Follow-up policy decisions: cookie `SameSite` / `Secure` / domain must be configurable per environment with `SameSite=Lax` as the default; Phase 1 should redefine base TTL policy without building multi-device session management. Locked policy values for planning are access TTL `PT15M`, refresh absolute TTL `P14D`, refresh rotation on every refresh, and no separate idle timeout in Phase 1.

---

## Refresh / Logout Semantics

| Option | Description | Selected |
|--------|-------------|----------|
| Revoke current session | Invalid/replayed refresh revokes only the current browser session, clears cookies, and returns 401. | ✓ |
| Revoke all user sessions | Invalid/replayed refresh logs out all user sessions. | |
| Return 401 only | Do not perform additional revocation beyond returning 401. | |

**User's choice:** Revoke current session.
**Notes:** Yuan also chose logout current session only. Full multi-device session management was requested initially but redirected to deferred v2 because it is outside Phase 1 requirements.

---

## CSRF Contract

| Option | Description | Selected |
|--------|-------------|----------|
| Refresh requires CSRF | `/auth/refresh` follows unsafe cookie-authenticated request rules and requires CSRF. | ✓ |
| Refresh bypasses CSRF but rotates token | Simpler frontend flow but allows cross-site refresh triggering. | |
| Dedicated refresh CSRF rule | Separate refresh-specific CSRF token or rule. | |

**User's choice:** Refresh requires CSRF.
**Notes:** Yuan asked what a good general architecture would do. The selected architecture is a public/safe CSRF bootstrap GET, readable `XSRF-TOKEN`, unsafe request header `X-XSRF-TOKEN`, and CSRF required for refresh/logout/all unsafe cookie-authenticated requests. Bearer-only non-browser requests may bypass CSRF.

### CSRF Naming and Failure Handling

| Option | Description | Selected |
|--------|-------------|----------|
| Spring convention | Use readable cookie `XSRF-TOKEN` and request header `X-XSRF-TOKEN`. | ✓ |
| Project custom names | Use project-branded cookie/header names. | |
| Header only no cookie | Return token in JSON and store it in frontend memory. | |

**User's choice:** Spring convention.
**Notes:** CSRF failures should return HTTP 403 `ApiResponse` with a CSRF-specific error code and trace/request id.

---

## Bearer Compatibility / Response Contract

| Option | Description | Selected |
|--------|-------------|----------|
| Browser mode omits tokens, API mode can return tokens | Browser responses set cookies and return user/session metadata; API token mode returns token JSON. | |
| Always omit tokens | Most secure but breaks existing token-body clients. | |
| Always return tokens and set cookies | Most compatible but weakens browser-safe goal. | |
| Separate endpoints | Browser cookie endpoints and non-browser bearer token issuance are separate contracts. | ✓ |

**User's choice:** Separate endpoints.
**Notes:** Yuan asked what is generally done for this situation. The selected architecture separates browser cookie auth endpoints (`/auth/register`, `/auth/login`, `/auth/refresh`, `/auth/logout`) from a non-browser bearer token endpoint such as `/auth/token`. Browser register/login body must not expose access or refresh tokens.

### Error Contract

| Option | Description | Selected |
|--------|-------------|----------|
| Stable error codes per auth/security case | Distinguish unauthenticated, expired token, invalid refresh, forbidden, and CSRF invalid without exposing internals. | ✓ |
| Status based only | Only guarantee HTTP 401/403. | |
| Very detailed codes | Expose fine-grained token/cookie/Redis/session state. | |

**User's choice:** Stable error codes per auth/security case.
**Notes:** Frontend Phase 2 needs stable routing semantics for 401/403 cases, but backend should avoid leaking sensitive internal session state.

---

## the agent's Discretion

- Exact Spring Security class decomposition.
- Exact cookie properties class/field names.
- Exact DTO class names for browser session metadata and non-browser token response.

## Deferred Ideas

- Complete multi-device session management: session list, revoke specific device, revoke all devices, and richer session metadata.
- Frontend API client/auth store implementation, portfolio adapters, and trading adapters.
