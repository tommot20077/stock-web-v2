# Browser Auth Contract

## Scope

Phase 1 splits browser cookie auth from non-browser bearer auth. Browser endpoints use `HttpOnly` cookies and CSRF. Non-browser clients use `/api/v1/auth/token` for bearer JSON.

Vue must not store access tokens or refresh tokens in local storage, session storage, Pinia, or JavaScript-readable state.

## Browser Endpoints

| Method | Path | Behavior |
|--------|------|----------|
| `POST` | `/api/v1/auth/register` | Creates the user, sets auth cookies, returns user/session metadata only. |
| `POST` | `/api/v1/auth/login` | Verifies credentials, sets auth cookies, returns user/session metadata only. |
| `POST` | `/api/v1/auth/refresh` | Requires CSRF, rotates refresh token, sets new auth cookies. |
| `POST` | `/api/v1/auth/logout` | Requires CSRF in browser cookie mode, revokes current browser session, clears auth cookies. |
| `GET` | `/api/v1/csrf` | Sets readable `XSRF-TOKEN` cookie and returns CSRF names. |
| `GET` | `/api/v1/me` | Reads `stock_access` cookie or bearer token and returns the current user. |

Browser register/login/refresh responses never include `data.accessToken` or `data.refreshToken`.

## Auth Cookies

Default properties:

| Property | Default |
|----------|---------|
| `stock.auth.cookie.access-name` | `stock_access` |
| `stock.auth.cookie.refresh-name` | `stock_refresh` |
| `stock.auth.cookie.path` | `/` |
| `stock.auth.cookie.same-site` | `Lax` |
| `stock.auth.cookie.secure` | `false` |
| `stock.auth.cookie.domain` | blank |
| `stock.auth.cookie.access-token-ttl` | `PT15M` |
| `stock.auth.cookie.refresh-token-ttl` | `P14D` |

`stock_access` and `stock_refresh` are `HttpOnly`. `SameSite=None` requires `secure=true`.

## CSRF

Browser unsafe requests must use:

- `credentials: "include"`
- readable cookie `XSRF-TOKEN`
- request header `X-XSRF-TOKEN` with the same value

`POST`, `PUT`, `PATCH`, and `DELETE` requests authenticated by browser cookies require CSRF. Bearer-token clients do not use CSRF as their protection mechanism.

CSRF failure returns HTTP 403:

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "AUTH_CSRF_TOKEN_INVALID",
    "message": "CSRF token invalid",
    "fields": {}
  },
  "meta": {
    "traceId": "...",
    "timestamp": "..."
  }
}
```

## Refresh And Logout

`/api/v1/auth/refresh` rotates on every successful call:

- validates the presented `stock_refresh`
- validates token version against Redis auth state
- deletes the old `user:refresh:{token}`
- issues a new refresh token and access token
- sets new `stock_access` and `stock_refresh` cookies

Invalid, missing-state, or replayed refresh returns HTTP 401 `AUTH_REFRESH_TOKEN_INVALID` and clears auth cookies.

`/api/v1/auth/logout` revokes only the current browser session in Phase 1. It clears `stock_access` and `stock_refresh` with max-age zero cookies. Multi-device session list and revoke-specific-device are deferred.

## Bearer Token Endpoint

Non-browser clients use:

`POST /api/v1/auth/token`

Request body matches `LoginRequest`:

```json
{
  "email": "user@example.com",
  "password": "Password1"
}
```

Successful response includes `data.accessToken`, `data.refreshToken`, and `data.user`. This endpoint does not set browser auth cookies.

Bearer clients send:

`Authorization: Bearer <accessToken>`

Bearer unsafe requests bypass browser CSRF.

## Error Codes

Frontend should route these stable codes:

| HTTP | Code | Meaning |
|------|------|---------|
| 401 | `AUTH_INVALID_CREDENTIALS` | Missing, malformed, or invalid credentials. |
| 401 | `AUTH_TOKEN_EXPIRED` | Access token expired. |
| 401 | `AUTH_REFRESH_TOKEN_INVALID` | Refresh token missing, invalid, replayed, or missing server state. |
| 403 | `AUTH_FORBIDDEN` | Authenticated user lacks permission or status is not allowed. |
| 403 | `AUTH_CSRF_TOKEN_INVALID` | CSRF cookie/header missing or mismatched. |
| 503 | `AUTH_REDIS_UNAVAILABLE` | Auth state unavailable; fail closed. |
| 400 | `VALIDATION_FAILED` | Body field or required header invalid; `error.fields` names the field or header (for example `Idempotency-Key`). |
| 409 | `TRADE_IDEMPOTENCY_KEY_REUSED` | Same `Idempotency-Key` reused with a different trade payload (trading, Phase 4). |

All auth/security failures use `ApiResponse` and include `meta.traceId`.

## Frontend Responsibilities

In API mode, Vue must:

- call APIs with `credentials: "include"`
- call `GET /api/v1/csrf` before unsafe browser requests
- send `X-XSRF-TOKEN`
- call `/api/v1/me` on boot to resolve session state
- attempt one refresh after a 401 when appropriate
- avoid storing access or refresh tokens anywhere JavaScript can read
- display user-safe auth errors while exposing `error.code` and `meta.traceId` in details
- send a per-attempt `Idempotency-Key` header (1–128 chars, not blank) on `POST /api/v1/trades`; reuse it when retrying the same payload, rotate it after the user edits the form

Portfolio and trading DTOs remain owned by their backend APIs. Phase 1 only defines how authenticated browser requests reach those APIs; Phase 2+ frontend adapters should continue using typed service clients and the common `ApiResponse<T>` envelope.

## Verification Responsibilities

Backend verification covers:

- `BrowserAuthFlowIT`: cookies, CSRF, refresh/logout, cookie-authenticated `/me`
- `AuthFlowIT`: bearer token endpoint and bearer logout compatibility
- `CorsIT`: credentialed Vue origin and rejected unknown origin
- `AuthPersistenceIT`: refresh Redis storage, revocation, and rotation

Frontend verification in later phases should cover `credentials: "include"`, CSRF bootstrap, one refresh retry, no token storage, and API-mode error rendering.
