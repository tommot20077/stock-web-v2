# Stock Web V2

## What This Is

Stock Web V2 is a modular stock trading web application with a Java/Spring Boot backend and a sibling Vue frontend. The backend already provides auth, assets, market data, WebSocket tickets, backtests, and trading/portfolio APIs; the next product step is to connect the Vue app to those APIs through a safe browser auth contract while preserving mock mode for demos and frontend development.

## Core Value

Users can safely sign in, inspect portfolio state, and record trades through one coherent frontend/backend flow.

## Requirements

### Validated

- ✓ Modular Spring Boot backend composed from feature modules for user/auth, assets, backtest, market data, and trading — existing
- ✓ Common REST response envelope, error catalog, trace IDs, and global exception handling — existing
- ✓ Email/password registration, login, bearer JWT authentication, refresh token persistence, `/api/v1/me`, and logout — existing
- ✓ Asset lookup and tradeability checks exposed through an asset module and cross-module `AssetFacade` — existing
- ✓ Market latest/K-line REST APIs and WebSocket market subscriptions protected by short-lived Redis tickets — existing
- ✓ Deterministic backtest API with strategy validation and persisted run/result records — existing
- ✓ Trading API for buy/sell transaction creation plus holdings, trade history, and portfolio summary — existing
- ✓ Vue 3 application shell with routes, portfolio UI, order ticket, mock portfolio state, and mock/API runtime mode conventions — existing
- ✓ Portfolio overview, positions/holdings, trade history, and order ticket wired to backend APIs in API mode — Phase 3 (read) + Phase 4 (order ticket)
- ✓ Post-trade refetch of portfolio summary, holdings/positions, and trade history so the UI reflects backend state — Phase 4

### Active

- [ ] Define and implement a browser-safe auth contract using httpOnly refresh/access cookies, `/auth/refresh`, `/me`, logout, and consistent 401/403 behavior.
- [ ] Add CSRF protection for cookie-authenticated unsafe browser requests using a double-submit token contract.
- [ ] Keep bearer-token support available for non-browser/API clients where appropriate, without weakening browser cookie safety.
- [ ] Update the Vue app to support real register, login, logout, session restore, and protected API calls while keeping mock/API dual mode.
- [ ] Add shared frontend API client behavior for credentials, CSRF headers, API envelopes, request IDs, 401/403 handling, and domain adapters.
- [ ] Document the backend/frontend contract in planning or docs before implementation so both repos can evolve against the same expectations. (auth/CSRF 已有 `ai-docs/browser-auth-contract.md`；**portfolio / trading DTO 兩個 repo 都還沒有契約文件** —— 2026-09-02 功能審查 M-4)

### Out of Scope

- Real broker integration — current backend trades are manual recorded transactions, not broker orders.
- Full order lifecycle with pending orders, partial fills, cancellations, time-in-force, and broker execution states — defer until the manual-trade vertical slice is stable.
- AI-assisted trading policy enforcement and broker credential APIs — the current frontend AI/broker settings are mock UX and need a separate security design.
- Complete API integration for alerts, notifications, analytics, settings, watchlists, and ops dashboards — defer after auth and core portfolio/trading API mode are reliable.
- Replacing the existing Vue visual shell — this milestone is integration-first, not a redesign.

## Context

The backend root is `/mnt/d/end/workspace/java/stock-web-v2`. It is a Java 21, Spring Boot 4.0.4 modular monolith using Maven, PostgreSQL/TimescaleDB migrations, Redis, Kafka, WebSocket, Spring Batch, and hand-written SQL through `JdbcClient`.

The frontend lives in the sibling repository `/mnt/d/end/workspace/vue/stock-v2/vue-app`. It is a Vue 3, Pinia, Vue Router, Vite, TypeScript app with existing mock portfolio behavior and runtime data-mode selection. API mode exists for some domains but auth, credentials, CSRF, and trading/portfolio adapters are not yet aligned with the backend.

Current backend auth is stateless bearer JWT from the browser perspective: register/login return tokens in JSON, CSRF is disabled, and the security filter reads `Authorization: Bearer ...`. CORS is already credential-ready for the Vue dev origin, so moving refresh tokens into cookies requires explicit CSRF protection and frontend `credentials: "include"` support.

The trading backend currently records executed trades and updates holdings transactionally. The current Vue order ticket models richer order-ticket behavior, so the near-term integration should treat backend trade creation as manual execution rather than a full order-management system.

The codebase has a dirty worktree with prior implementation changes. Planning commits must stage only `.planning` files unless a phase explicitly edits application code.

## Constraints

- **Tech stack**: Keep Java 21/Spring Boot backend modules and Vue 3/Vite frontend; do not introduce a new application framework for this milestone.
- **Repository layout**: Backend and frontend are sibling repositories, so cross-repo work must make file ownership and verification commands explicit.
- **Security**: Browser cookie auth must include CSRF protection before unsafe endpoints rely on cookies.
- **Compatibility**: Preserve mock mode for frontend demos and development while adding API mode.
- **API contract**: REST responses should continue using the common `ApiResponse<T>` envelope and existing backend error semantics.
- **Trading semantics**: Treat current backend trading API as executed manual trades, not live broker orders.
- **Verification**: Backend changes need Maven tests; frontend changes need type-check/build and focused Vitest coverage where affected.
- **AI institution**: Judgment rubrics, model dispatch, task briefs, and the maintenance protocol live in `ai-docs/judgment.md`, `ai-docs/model-dispatch.md`, `ai-docs/task-briefs.md`, `ai-docs/maintenance-protocol.md` — read on demand, do not inline them here.

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Use a safe vertical slice for the next milestone | Auth, CSRF, API client behavior, and portfolio/trading integration are tightly coupled and should be proven end-to-end before expanding scope | - Pending |
| Use httpOnly cookies plus `/auth/refresh` for browser sessions | Reduces refresh-token exposure to JavaScript and supports session restore across browser refresh | - Pending |
| Use double-submit CSRF token for unsafe cookie-authenticated browser requests | Required because credentialed browser requests otherwise make unsafe endpoints vulnerable to cross-site submission | - Pending |
| Keep mock/API dual mode in the Vue app | Existing frontend development depends on mock mode, and API mode should be additive | - Pending |
| Treat `POST /api/v1/trades` as manual executed trade creation | Existing backend contract has no order type, status, or broker lifecycle | - Pending |
| Store the cross-repo contract in the backend planning/docs area first | Backend owns auth/security semantics, and planning docs can reference frontend follow-up work clearly | - Pending |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `$gsd-transition`):
1. Requirements invalidated? -> Move to Out of Scope with reason
2. Requirements validated? -> Move to Validated with phase reference
3. New requirements emerged? -> Add to Active
4. Decisions to log? -> Add to Key Decisions
5. "What This Is" still accurate? -> Update if drifted

**After each milestone** (via `$gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check -- still the right priority?
3. Audit Out of Scope -- reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-05-30 after initialization*
