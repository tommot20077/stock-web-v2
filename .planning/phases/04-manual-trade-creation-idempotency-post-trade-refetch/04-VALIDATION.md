---
phase: 4
slug: manual-trade-creation-idempotency-post-trade-refetch
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-07-26
---

# Phase 4 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> Derived from `04-RESEARCH.md` § Validation Architecture (SC-1 ~ SC-5 tables).
> The authoritative per-behaviour signal list lives there; this file is the
> execution-time sampling contract.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Backend framework** | JUnit 5 + Mockito + AssertJ + Testcontainers (`spring-boot-starter-test`, `stock-module-trading/pom.xml:49-50`) |
| **Backend config file** | `stock-start/src/test/resources/application-test.yaml`; container wiring `stock-start/src/test/java/.../support/ContainerIT.java:12-64` |
| **Backend quick run** | `./mvnw -pl stock-module-trading -am test` (PowerShell: `.\mvnw.cmd -pl stock-module-trading -am test`) |
| **Backend full suite** | `./mvnw test` |
| **Backend IT** | `./mvnw -pl stock-start -am verify` |
| **Frontend framework** | Vitest `^4.1.6` + jsdom (`vite.config.ts:20-28`); setup `src/testSetup.ts` |
| **Frontend quick run** | `cd ../../vue/stock-v2/vue-app && npx vitest run <changed test file>` |
| **Frontend full suite** | `cd ../../vue/stock-v2/vue-app && npm test && npm run build` |
| **Frontend API mode** | `VITE_DATA_MODE=api npm test` (PowerShell: `$env:VITE_DATA_MODE='api'; npm test`) |
| **Estimated runtime** | backend unit ~30s · backend IT ~3-5min (Testcontainers boots PG+Redis+Kafka) · frontend ~40s per mode |
| **⚠ Web-layer gap** | `stock-module-trading` has **no** `@WebMvcTest` infrastructure — see Wave 0 Requirements |
| **⚠ Hard prerequisite** | **Docker must be running.** All idempotency acceptance (concurrency, `ON CONFLICT` inference against a partial index) requires real PostgreSQL. There is **no fallback** — H2/in-memory cannot infer `ON CONFLICT` on a partial index, so the core of TRAD-03 would be untested. Confirm `docker info` before Wave 1. |

---

## Sampling Rate

- **After every task commit:** focused run for the touched module
  - backend trading → `./mvnw -pl stock-module-trading -am test`
  - backend common (`ErrorCode`) → `./mvnw -pl stock-common -am test`
  - frontend → `npx vitest run <changed test file>` (prefix `VITE_DATA_MODE=api` where the behaviour is API-mode-only)
- **After every plan wave:**
  - backend → `./mvnw test`
  - frontend → `npm test` **and** `VITE_DATA_MODE=api npm test` (judgment §3 — both modes are required, not one)
- **Before `/gsd-verify-work`:** all four must be green
  - `./mvnw test`
  - `./mvnw -pl stock-start -am verify` (including every new IT)
  - `cd ../../vue/stock-v2/vue-app && npm test && npm run build`
  - `cd ../../vue/stock-v2/vue-app && VITE_DATA_MODE=api npm test`
  - judgment §8 — cross-repo change means **both** repos verified
- **Max feedback latency:** ~40s for unit/component layers; backend IT is the long pole (~3-5min) and is therefore sampled per wave, not per task.

---

## Per-Task Verification Map

**Status: pending plan creation.** Task IDs do not exist until `gsd-planner` runs.
Populate this table during planning — one row per task, sourced from the
`04-RESEARCH.md` SC-1~SC-5 tables, which already give layer + command + signal
for every behaviour.

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| _TBD_ | _TBD_ | _TBD_ | TRAD-01..06 | see § Security Domain | see RESEARCH SC tables | unit / web / IT / component | see RESEARCH SC tables | ❌ W0 | ⬜ pending |

**Coverage anchor — every phase requirement must land in this table:**

| Req | Primary proving layer | Anchor test (from RESEARCH) |
|-----|----------------------|-----------------------------|
| TRAD-01 | Frontend unit + Backend IT | payload key-set assertion; `TradingApiIT` buy/sell creates one row |
| TRAD-02 | Frontend unit + component | payload has exactly the 7 contract fields; API mode hides MKT/LMT, TIF, Cash-after; mock mode still shows all four |
| TRAD-03 | **Backend IT** (authoritative) | same key twice → 1 row, holdings applied once, identical `data.id`; **8 concurrent same-key → all 200, 1 row, no 500**; same key + different payload → 409; different users same key → 2 rows |
| TRAD-04 | Frontend component | submit button `disabled` while in flight; double-click calls `createTrade` once; key lifecycle per D-14; **400 → edit field → resubmit succeeds with a NEW key (never 409)** |
| TRAD-05 | Frontend component | revision bump re-runs each mounted page's existing loader; Trades keeps filter/sort and resets page to 0; mock mode issues no network on bump |
| TRAD-06 | Frontend component + Backend IT | `fields` keys bind to inputs; **English Bean Validation values never appear in DOM**; dispatch by `error.code` not by error ordering; trade-succeeded-but-refetch-failed shown as two separate things (D-12) |

---

## Wave 0 Requirements

Test infrastructure that must exist before implementation tasks can go RED:

- [ ] **`docker info` succeeds** — hard gate; without it TRAD-03 cannot be proven at all
- [ ] `stock-module-trading/src/test/java/.../TradingTestApplication.java` — `@SpringBootApplication` anchor for `@WebMvcTest`; copy `stock-module-market-data/src/test/java/.../MarketDataTestApplication.java` (15 lines)
- [ ] `stock-module-trading/pom.xml` — add test-scope `spring-boot-starter-webmvc-test` + `spring-boot-starter-security-test` (market-data pom already has both at `:103-104, :108-109`; trading has only `spring-boot-starter-test`)
- [ ] `TradingControllerTest` `@WebMvcTest` variant + `TestExceptionHandler` + `TestSecurityConfig` — copy `BackfillControllerTest:56-62`
- [ ] `src/services/tradingApi.test.ts` (new) — copy `src/services/opsApi.test.ts` (`:29`, `:118` already assert an idempotency key)
- [ ] `src/services/marketApi.test.ts` (new) — required by D-01; **no asset/market adapter exists today**
- [ ] `src/services/portfolioRevision.test.ts` (new)
- [ ] `src/components/OrderTicket.test.ts` (new) — no dedicated OrderTicket test file exists; do **not** append to the legacy `taskN.test.ts` files
- [ ] `src/api-adapter-wiring.test.ts` — **extend, not create**: add `trading` (and `market`) to `mockFactoryCalls` (`:16-22`), the `afterEach` reset/doUnmock (`:24-36`), and the three assertion sites (`:166-220`)
- [ ] `src/i18n.ts` — new keys in **both** zh and en; check whether `src/i18n.test.ts` asserts key-set parity across locales (if it does, a one-sided addition goes red immediately)
- [ ] `stock-start` migration IT — assert V10's index is **partial** (`select indexdef from pg_indexes where indexname=...` contains `WHERE`); extend `FoundationMigrationIT` or add `TransactionsIdempotencyIT` following `TransactionsAppendOnlyIT`

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Sparkline visual correctness (klines renders a sensible line) | TRAD-01 (D-01) | Component tests assert behaviour, not pixels | Open the ticket in API mode against a running backend, pick a seeded symbol, confirm the chart shape matches `GET /market/{symbol}/klines` data |
| `TRADE_EXECUTE` permission denial → 403 | TRAD-06 | `Role.USER` already includes `TRADE_EXECUTE`, so no role exists that lacks it. **If no such role can be constructed, mark as "guaranteed by `@PreAuthorize`, no independent test" — do not claim coverage.** | `MethodSecurityDenialIT` pattern, only if a permission-less role is available |

---

## Explicitly Out of Scope for Phase 4 (belongs to Phase 5 / VER-03)

Plans **must not** claim coverage of these. Writing "user can complete the full
trade flow" in any task verification is an over-claim.

| Item | Why not Phase 4 | Owner |
|------|-----------------|-------|
| Real browser flow (login → `/me` → portfolio reads → create trade → refetch → logout) | Needs a real browser + real backend together; Vitest is jsdom + stubbed `fetch`, MockMvc is servlet-layer not real HTTP | **VER-03 / Phase 5** |
| Real cookie/CSRF browser behaviour (SameSite, Secure, HttpOnly actually taking effect) | jsdom does not implement HttpOnly; MockMvc has no browser cookie jar | Phase 5 |
| Evidence of real frontend→backend network calls (judgment §3) | All Phase 4 frontend tests stub `fetch`; "the screen looks right" is explicitly not evidence | Phase 5 |
| Cross-repo contract fidelity against real payloads (e.g. `KlineDto` OHLCV is a JSON **string**, not a number) | Phase 4 fixtures are hand-written | Phase 5 — but Phase 4 fixtures should cite the backend `file:line` to limit drift |
| `executedAt` future-time rejection | Provided by draft PR #15, not by Phase 4 (pending DP-1) | PR #15 |

---

## Validation Sign-Off

- [ ] All tasks have an `<automated>` verify or a Wave 0 dependency
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references (incl. the Docker gate)
- [ ] No watch-mode flags
- [ ] Feedback latency < 60s for unit/component layers
- [ ] Both repos verified (judgment §8)
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
