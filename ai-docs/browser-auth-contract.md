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
| `POST` | `/api/v1/auth/logout` | Requires CSRF in browser cookie mode, signs the user out of **all devices**, clears auth cookies. |
| `GET` | `/api/v1/csrf` | Sets readable `XSRF-TOKEN` cookie and returns CSRF names. |
| `GET` | `/api/v1/me` | Reads `stock_access` cookie or bearer token and returns the current user. |
| `PATCH` | `/api/v1/me/preferences` | Updates the current user's base currency. Requires CSRF in browser cookie mode. See [User Preferences](#user-preferences). |

Browser register/login/refresh responses never include `data.accessToken` or `data.refreshToken`.

## Current User (`MeResponse`)

`GET /api/v1/me` returns `ApiResponse<MeResponse>`; the same `MeResponse` is embedded in the login / register / refresh responses (and in `data.user` of `POST /api/v1/auth/token`).

| Field | Type | Notes |
|-------|------|-------|
| `id` | number | User primary key. |
| `uuid` | string (UUID) | Public identifier. |
| `email` | string | Login email. |
| `username` | string | Display name. |
| `role` | string | Role enum name. |
| `status` | string | Account status enum name. |
| `baseCurrency` | `"TWD" \| "USD" \| "EUR" \| "JPY"` | The user's base (reporting) currency, serialized as the enum constant name. Defaults to `"TWD"` for every user (DB `DEFAULT 'TWD'`, `NOT NULL`, guarded by `ck_users_base_currency`). |

The Vue `authApi` projects `MeResponse` through a field whitelist, so the added `baseCurrency` field is dropped there and does not affect session state.

## User Preferences

`PATCH /api/v1/me/preferences` changes the current user's base currency.

| Item | Contract |
|------|----------|
| Permission | `PROFILE_EDIT` (`@PreAuthorize("hasAuthority('PROFILE_EDIT')")`). The target user is always the authenticated user; the body cannot name a user id. |
| CSRF | Cookie-authenticated requests must send `X-XSRF-TOKEN` (see [CSRF](#csrf)); bearer requests do not. |
| Idempotency | No `Idempotency-Key` required: setting a value is naturally idempotent. |
| Request body | `{"baseCurrency": "USD"}`. Value is trimmed and upper-cased before matching `TWD`, `USD`, `EUR`, `JPY`. |
| Success | HTTP 200, `ApiResponse<MeResponse>` with the updated `baseCurrency`. The change is visible immediately to `GET /api/v1/me` and to other modules through `UserPreferenceFacade` (no cache). |
| Audit | Event `preferences_update` on both paths: result `success` or `failure:<ERROR_CODE>`. |

Errors:

| HTTP | Code | Condition |
|------|------|-----------|
| 400 | `VALIDATION_FAILED` | `baseCurrency` missing or blank: `error.fields.baseCurrency = "must not be blank"`. |
| 400 | `VALIDATION_FAILED` | Unsupported currency: `error.fields.baseCurrency = "unsupported currency"`. The message is static and never echoes the submitted value. |
| 400 | `VALIDATION_FAILED` | `baseCurrency` longer than 10 characters (`@Size(max = 10)`; Bean Validation message in `error.fields.baseCurrency`). |
| 400 | `VALIDATION_FAILED` | Malformed JSON body (no `fields`). |
| 401 | `AUTH_INVALID_CREDENTIALS` / `AUTH_TOKEN_EXPIRED` | Unauthenticated or expired access token. |
| 403 | `AUTH_CSRF_TOKEN_INVALID` | Cookie-authenticated request without a matching `X-XSRF-TOKEN`. |
| 403 | `AUTH_FORBIDDEN` | Authenticated user lacks `PROFILE_EDIT`. |

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

`/api/v1/auth/logout` signs the user out of **all devices** (decided 2026-09-27, security review L-8): it increments the user's single `tokenVersion`, so every existing access token on every device/tab fails on its next request and every refresh token fails on its next rotation. It also clears `stock_access` and `stock_refresh` with max-age zero cookies. Frontend implication: another open tab or device will receive 401 and must return to the login screen. Logging out only the current device would require per-session revocation (session id / jti denylist) and is deferred, as are the multi-device session list and revoke-specific-device.

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

The portfolio and trading contract itself (endpoints, DTO fields, validation, error codes, number/time serialization) is documented in [trading-portfolio-contract.md](trading-portfolio-contract.md).

## Verification Responsibilities

Backend verification covers:

- `BrowserAuthFlowIT`: cookies, CSRF, refresh/logout, cookie-authenticated `/me`
- `AuthFlowIT`: bearer token endpoint and bearer logout compatibility
- `CorsIT`: credentialed Vue origin and rejected unknown origin
- `AuthPersistenceIT`: refresh Redis storage, revocation, and rotation

Frontend verification in later phases should cover `credentials: "include"`, CSRF bootstrap, one refresh retry, no token storage, and API-mode error rendering.
