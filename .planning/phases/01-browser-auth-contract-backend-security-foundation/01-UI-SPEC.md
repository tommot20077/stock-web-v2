---
phase: 01
slug: browser-auth-contract-backend-security-foundation
status: approved
shadcn_initialized: false
preset: existing-stock-v2-shell
created: 2026-05-30
---

# Phase 01 — UI Design Contract

> Visual and interaction contract for the browser auth backend contract. Phase 1 does not implement Vue UI, but it must document frontend-visible auth/session/error behavior so Phase 2 can build the UI without inventing security semantics.

---

## Design System

| Property | Value |
|----------|-------|
| Tool | none |
| Preset | existing Stock V2 app shell |
| Component library | none |
| Icon library | existing inline icons until an icon library is introduced in a later frontend phase |
| Font | `Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", system-ui, sans-serif` |

### Existing App Surface

- Frontend root: `/mnt/d/end/workspace/vue/stock-v2/vue-app`.
- Existing shell owner: `src/App.vue`.
- Existing global tokens: `src/styles.css`.
- Existing navigation: `src/components/Header.vue`.
- Existing transient feedback: `src/components/Toast.vue`.
- Existing data transport boundary: `src/services/apiClient.ts`.
- Existing language system: `src/i18n.ts`, with `zh` and `en`.

Phase 1 documentation must describe how these surfaces should react in Phase 2, but must not require production Vue edits during Phase 1.

---

## Spacing Scale

Declared values must stay aligned with the existing dense trading-tool UI. New Phase 2 UI additions should use multiples of 4px.

| Token | Value | Usage |
|-------|-------|-------|
| xs | 4px | Icon gaps, inline metadata gaps, compact status-dot spacing |
| sm | 8px | Button internal gaps, form row gaps, compact auth state rows |
| md | 16px | Default panel padding, login form field gaps, error-state body spacing |
| lg | 24px | Page/header horizontal padding, modal content padding |
| xl | 32px | Form group separation, session section breaks |
| 2xl | 48px | Dedicated auth page section spacing if Phase 2 adds one |
| 3xl | 64px | Avoid for normal app shell surfaces; only allowed for full-page empty/auth states |

Exceptions: existing `Header.vue` uses 22px horizontal padding and 60px height; keep those dimensions unless a later frontend phase intentionally revises the app shell.

---

## Typography

Typography must remain utilitarian and scan-friendly. Do not add marketing-sized hero text for auth/session surfaces.

| Role | Size | Weight | Line Height |
|------|------|--------|-------------|
| Body | 13px | 400 | 1.45 |
| Label | 12px | 500 | 1.35 |
| Heading | 16px | 600 | 1.35 |
| Display | 20px | 650 | 1.25 |

Constraints:

- Letter spacing must be `0`, except existing `.brand` styling may remain until explicitly refactored.
- Auth/security error text must fit in compact panels without truncating the error code or trace/request id.
- Session status labels should use sentence case in English and concise Traditional Chinese in `zh`.

---

## Color

Keep the existing palette from `src/styles.css`; do not introduce a new theme for auth.

| Role | Value | Usage |
|------|-------|-------|
| Dominant (60%) | `var(--bg)` / `#fafaf9` light, `#0c0d10` dark | App background |
| Secondary (30%) | `var(--surface)` / `var(--surface2)` | Header, panels, login/session surfaces, status rows |
| Accent (10%) | `var(--accent)` / default `#ff6600` | Primary action, active auth status dot, selected controls |
| Destructive | `var(--dn)` / default `#dc2626` | Logout, auth failure emphasis, invalid session warnings |

Accent reserved for:

- Primary auth action button.
- Active authenticated/session-valid status.
- Focus state on auth inputs.
- CSRF/session-ready status indicator.

Do not use accent for every link, every badge, or generic notification count. Security failures must use destructive color plus clear text, not color alone.

---

## Copywriting Contract

Phase 1 must document backend response semantics with user-facing copy guidance for Phase 2. Copy should be calm and operational, not celebratory.

| Element | Copy |
|---------|------|
| Primary CTA | `登入` / `Sign in` |
| Register CTA | `建立帳號` / `Create account` |
| Session restore loading | `正在確認登入狀態...` / `Checking session...` |
| Authenticated status | `已登入` / `Signed in` |
| Anonymous status | `尚未登入` / `Signed out` |
| CSRF bootstrap failure | `安全驗證初始化失敗，請重新整理後再試。` / `Security check could not start. Refresh and try again.` |
| Expired session error | `登入已過期，請重新登入。` / `Your session expired. Sign in again.` |
| CSRF 403 error | `安全驗證失敗，請重新整理頁面後再試。` / `Security check failed. Refresh and try again.` |
| Forbidden 403 error | `目前帳號沒有執行此操作的權限。` / `This account cannot perform that action.` |
| Backend unavailable | `暫時無法連線到後端，請稍後重試。` / `Cannot reach the backend. Try again later.` |
| Destructive confirmation | `登出：將結束目前瀏覽器工作階段。` / `Sign out: this ends the current browser session.` |

Error surfaces must expose backend `error.code` and `meta.traceId` in developer-facing detail text or expandable details. User-facing headline must remain understandable without reading the code.

---

## Interaction Contract

### Session States

Phase 2 must represent these states explicitly:

| State | Trigger | Required UI behavior |
|-------|---------|----------------------|
| `unknown` | App boot before `/api/v1/me` resolves | Keep protected data surfaces in loading/skeleton state; do not show mock authenticated data in API mode |
| `anonymous` | `/api/v1/me` returns 401 and refresh fails or is unavailable | Show signed-out state and login/register entry point |
| `authenticated` | `/api/v1/me` succeeds or login/register succeeds | Show user identity and allow protected operations |
| `refreshing` | One-shot refresh after 401 | Disable duplicate refresh-triggered actions; keep existing page state visible but pending |
| `csrf_unavailable` | `GET /api/v1/csrf` fails in API mode | Disable unsafe actions and show CSRF bootstrap failure copy |

### Unsafe Actions

Unsafe API actions in Phase 2+ must:

- Use `credentials: "include"`.
- Ensure readable `XSRF-TOKEN` is available before the request.
- Send `X-XSRF-TOKEN`.
- Disable duplicate submission while a request is in flight.
- On `AUTH_CSRF_TOKEN_INVALID`, show CSRF 403 copy and offer retry/refresh path.

### Header and Status Placement

If Phase 2 adds visible session status:

- Place compact status near the existing header avatar area in `Header.vue`.
- Use a small text label plus status dot; do not add a large auth card to the main dashboard.
- Keep the avatar dimensions stable at 32px and avoid layout shift when status changes.
- Do not show raw tokens, cookie names, or refresh token values in UI.

### Toasts and Inline Errors

- Use toast only for short, non-blocking events like successful logout.
- Use inline error panels for login failure, session expired, CSRF failure, backend unavailable, and forbidden operations.
- Inline error panels must include retry/action affordance when the user can recover.

---

## Layout Contract

Phase 2 auth UI should fit into the existing app rather than introducing a landing page.

| Surface | Layout requirement |
|---------|--------------------|
| Login/register form | Centered or constrained panel, max width 360-420px, 16px field gaps, no nested cards |
| Header session indicator | Inline with avatar/search area, fixed height within existing 60px header |
| Error panel | Full-width within current content column or form panel, 8px radius max |
| API mode diagnostics | Small subdued row or details element; never a hero banner |
| Logout confirmation | Modal or compact confirmation panel with explicit destructive action |

Avoid oversized hero sections, decorative imagery, gradient backgrounds, or marketing copy. This is an operational trading app.

---

## Accessibility Contract

- Every auth input must have a visible label.
- Error text must not rely on color alone.
- Focus states must be visible on buttons, inputs, and retry controls.
- Loading states must expose text, not only spinners.
- Session status changes after login/logout/refresh failure should be announced through visible text; ARIA live behavior may be added in Phase 2 if practical.

---

## Security Display Contract

The UI must never display:

- Access token values.
- Refresh token values.
- `HttpOnly` auth cookie values.
- Raw `Set-Cookie` headers.
- Redis keys or internal session identifiers.

The UI may display:

- User email/username from `/api/v1/me`.
- Backend `error.code`.
- Backend `meta.traceId`.
- Safe session status labels.

For CSRF and auth errors, user copy must avoid implying that CSRF is optional. The recovery path is refresh/retry/sign-in, not "disable security".

---

## Registry Safety

| Registry | Blocks Used | Safety Gate |
|----------|-------------|-------------|
| shadcn official | none | not required |
| third-party registries | none | third-party registry use is out of scope |

No component registry adoption is authorized by Phase 1. Any future library introduction must happen in a dedicated frontend phase with tests and visual review.

---

## Checker Sign-Off

- [x] Dimension 1 Copywriting: PASS
- [x] Dimension 2 Visuals: PASS
- [x] Dimension 3 Color: PASS
- [x] Dimension 4 Typography: PASS
- [x] Dimension 5 Spacing: PASS
- [x] Dimension 6 Registry Safety: PASS

**Approval:** approved 2026-05-30

## UI-SPEC COMPLETE
