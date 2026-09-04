---
phase: 4
slug: manual-trade-creation-idempotency-post-trade-refetch
status: draft
nyquist_compliant: true
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

**Status: 已於 planning 後回填（2026-07-26）。** 逐列對應 13 個 plan 的 `<task>` 區塊，
實數 **29** 個 task（各 plan 的 task 數依序為 3 / 2 / 2 / 3 / 2 / 2 / 2 / 2 / 3 / 2 / 2 / 2 / 2）。
本表是 `04-13` Task 1 步驟 C（需求證據對照）與步驟 D（over-claim 稽核）的基準。

表格閱讀約定：
- `FE$` 是縮寫，代表各 plan `<automated>` 中逐字寫出的前綴 `cd ../../vue/stock-v2/vue-app &&`。
- **Threat Ref** 取自該 plan `<threat_model>` 中確實落在該 task 的條目；`—` 表示該 task 沒有獨立威脅面（其正確性由同 plan 另一個 task 的威脅緩解引用）。
- **Status** 欄留給執行時逐格勾選；planning 階段一律 ⬜。

| Task ID | Plan | Wave | Requirement | Threat Ref | Test Type | Automated Command | Status |
|---------|------|------|-------------|------------|-----------|-------------------|--------|
| 04-01-T1 | 04-01 | 1 | TRAD-03 | T-04-03 | backend unit | `./mvnw -pl stock-common -am test -Dtest=ErrorCodeTest` | ⬜ |
| 04-01-T2 | 04-01 | 1 | TRAD-03 | T-04-01, T-04-02 | backend IT (RED) | `./mvnw -pl stock-start -am verify -Dit.test=TransactionsIdempotencyIT` | ⬜ |
| 04-01-T3 | 04-01 | 1 | TRAD-03 | T-04-01, T-04-04, T-04-08 | backend IT (GREEN) | `./mvnw -pl stock-start -am verify -Dit.test=TransactionsIdempotencyIT+TransactionsAppendOnlyIT+FoundationMigrationIT` | ⬜ |
| 04-02-T1 | 04-02 | 2 | TRAD-03 | — | backend unit | `./mvnw -pl stock-module-trading -am test` | ⬜ |
| 04-02-T2 | 04-02 | 2 | TRAD-03 | T-04-01, T-04-02, T-04-08, T-04-12 | backend IT | `./mvnw -pl stock-start -am verify -Dit.test=TransactionsIdempotencyIT+TransactionsAppendOnlyIT` | ⬜ |
| 04-03-T1 | 04-03 | 3 | TRAD-03 | — | backend unit | `./mvnw -pl stock-module-trading -am test -Dtest=TradePayloadMatcherTest` | ⬜ |
| 04-03-T2 | 04-03 | 3 | TRAD-03, TRAD-06 | T-04-01, T-04-02, T-04-03, T-04-04, T-04-05, T-04-07 | backend unit | `./mvnw -pl stock-module-trading -am test` | ⬜ |
| 04-04-T1 | 04-04 | 4 | TRAD-03, TRAD-06（Wave 0 前置） | T-04-SC | backend 測試基礎設施 | `./mvnw -pl stock-module-trading -am test` | ⬜ |
| 04-04-T2 | 04-04 | 4 | TRAD-03 | T-04-01, T-04-05, T-04-06 | backend web slice（`@WebMvcTest`） | `./mvnw -pl stock-module-trading -am test` | ⬜ |
| 04-04-T3 | 04-04 | 4 | TRAD-06 | T-04-03, T-04-09 | backend IT | `./mvnw -pl stock-start -am verify -Dit.test=ErrorHandlingIT` | ⬜ |
| 04-05-T1 | 04-05 | 5 | TRAD-01, TRAD-03 | T-04-02, T-04-04, T-04-09 | backend IT | `./mvnw -pl stock-start -am verify -Dit.test=TradingApiIT` | ⬜ |
| 04-05-T2 | 04-05 | 5 | TRAD-03, TRAD-06 | T-04-01, T-04-03, T-04-06, T-04-07, T-04-08 | backend IT（全套） | `./mvnw -pl stock-start -am verify` | ⬜ |
| 04-06-T1 | 04-06 | 1 | TRAD-01 | — | frontend type-check / build | `FE$ npm run build` | ⬜ |
| 04-06-T2 | 04-06 | 1 | TRAD-01 | T-04-11, T-04-12 | frontend unit | `FE$ npx vitest run src/services/marketApi.test.ts` | ⬜ |
| 04-07-T1 | 04-07 | 2 | TRAD-01, TRAD-02 | T-04-01, T-04-06, T-04-09, T-04-10 | frontend unit | `FE$ npx vitest run src/services/tradingApi.test.ts` | ⬜ |
| 04-07-T2 | 04-07 | 2 | TRAD-05 | — | frontend unit | `FE$ npx vitest run src/services/portfolioRevision.test.ts` | ⬜ |
| 04-08-T1 | 04-08 | 3 | TRAD-02 | T-04-09 | frontend unit（API mode） | `FE$ VITE_DATA_MODE=api npx vitest run src/api-adapter-wiring.test.ts` | ⬜ |
| 04-08-T2 | 04-08 | 3 | TRAD-06 | T-04-01, T-04-09 | frontend unit | `FE$ npx vitest run src/i18n.test.ts` | ⬜ |
| 04-09-T1 | 04-09 | 4 | TRAD-01, TRAD-02 | T-04-10 | frontend component (RED) | `FE$ npx vitest run src/components/OrderTicket.test.ts` | ⬜ |
| 04-09-T2 | 04-09 | 4 | TRAD-02 | T-04-09, T-04-10 | frontend component | `FE$ npx vitest run src/components/OrderTicket.test.ts` | ⬜ |
| 04-09-T3 | 04-09 | 4 | TRAD-02 | — | frontend component（回歸） | `FE$ npx vitest run src/task4.test.ts` | ⬜ |
| 04-10-T1 | 04-10 | 5 | TRAD-01 | T-04-11, T-04-12 | frontend component（API mode） | `FE$ VITE_DATA_MODE=api npx vitest run src/components/OrderTicket.test.ts` | ⬜ |
| 04-10-T2 | 04-10 | 5 | TRAD-01 | T-04-09 | frontend component（API mode） | `FE$ VITE_DATA_MODE=api npx vitest run src/components/OrderTicket.test.ts` | ⬜ |
| 04-11-T1 | 04-11 | 6 | TRAD-04 | T-04-01, T-04-03, T-04-04, T-04-10 | frontend component（API mode） | `FE$ VITE_DATA_MODE=api npx vitest run src/components/OrderTicket.test.ts` | ⬜ |
| 04-11-T2 | 04-11 | 6 | TRAD-02, TRAD-06 | T-04-06, T-04-07, T-04-09 | frontend component（API mode） | `FE$ VITE_DATA_MODE=api npx vitest run src/components/OrderTicket.test.ts` | ⬜ |
| 04-12-T1 | 04-12 | 7 | TRAD-05 | T-04-01, T-04-09 | frontend component（API mode） | `FE$ VITE_DATA_MODE=api npx vitest run src/pages/Overview.test.ts src/pages/Positions.test.ts src/pages/Trades.test.ts` | ⬜ |
| 04-12-T2 | 04-12 | 7 | TRAD-05, TRAD-06 | T-04-09 | frontend component（API mode） | `FE$ VITE_DATA_MODE=api npx vitest run src/pages/Trades.test.ts src/pages/Positions.test.ts` | ⬜ |
| 04-13-T1 | 04-13 | 8 | TRAD-01 ~ TRAD-06 | T-04-01, T-04-05, T-04-06, T-04-08, T-04-SC | 跨 repo 收尾閘門 | `./mvnw test && ./mvnw -pl stock-start -am verify` | ⬜ |
| 04-13-T2 | 04-13 | 8 | TRAD-01 ~ TRAD-06 | — | `checkpoint:human-verify` | 無 `<automated>`（刻意人工項，見 §Manual-Only Verifications） | ⬜ |

**威脅覆蓋**：`T-04-01` ~ `T-04-12` 與 `T-04-SC` 全數在上表出現至少一次；13 個 plan 各自都有 `<threat_model>`。
`T-04-06`（CSRF）與 `T-04-05`（`TRADE_EXECUTE`）的處置是 **accept**（既有控制已覆蓋 / 誠實標示無獨立測試），不是 mitigate —— `04-13` 步驟 D 會強制這兩條不得被寫成「已覆蓋」。

**Coverage anchor — every phase requirement must land in this table:**

| Req | Primary proving layer | Anchor test (from RESEARCH) |
|-----|----------------------|-----------------------------|
| TRAD-01 | Frontend unit + Backend IT | payload key-set assertion; `TradingApiIT` buy/sell creates one row |
| TRAD-02 | Frontend unit + component | payload has exactly the 7 contract fields; API mode hides MKT/LMT, TIF, Cash-after; mock mode still shows all four |
| TRAD-03 | **Backend IT** (authoritative) | same key twice → 1 row, holdings applied once, identical `data.id`; **8 concurrent same-key → all 200, 1 row, no 500**; same key + different payload → 409; different users same key → 2 rows |
| TRAD-04 | Frontend component | submit button `disabled` while in flight; double-click calls `createTrade` once; key lifecycle per D-14; **400 → edit field → resubmit succeeds with a NEW key (never 409)** |
| TRAD-05 | Frontend component | revision bump re-runs each mounted page's existing loader; Trades keeps filter/sort and resets page to 0; mock mode issues no network on bump |
| TRAD-06 | Frontend component + Backend IT | `fields` keys bind to inputs; **English Bean Validation values never appear in DOM**; dispatch by `error.code` not by error ordering; trade-succeeded-but-refetch-failed shown as two separate things (D-12) |

**需求 → task 反查**（上表逐列彙整，`04-13` 步驟 C 可直接引用）：

| Req | 證明它的 task |
|-----|---------------|
| TRAD-01 | 04-05-T1、04-06-T1、04-06-T2、04-07-T1、04-09-T1、04-10-T1、04-10-T2 |
| TRAD-02 | 04-07-T1、04-08-T1、04-09-T1、04-09-T2、04-09-T3、04-11-T2 |
| TRAD-03 | 04-01-T1/T2/T3、04-02-T1/T2、04-03-T1/T2、04-04-T1/T2、04-05-T1/T2 |
| TRAD-04 | 04-11-T1 |
| TRAD-05 | 04-07-T2、04-12-T1、04-12-T2 |
| TRAD-06 | 04-03-T2、04-04-T3、04-05-T2、04-08-T2、04-11-T2、04-12-T2 |

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

**擁有 plan（planning 後回填；上方勾選欄仍留給執行時）** —— 依據為各 plan 的 `files_modified` 與 task `<files>`：

| Wave 0 項目 | 擁有 task | 依據 |
|-------------|-----------|------|
| `docker info` | 執行時閘門，**Wave 1 之前** | 04-01-T2 是第一個需要真實 PostgreSQL 的 task；未通過則不得開始 |
| `TradingTestApplication.java` | 04-04-T1 | 04-04 `files_modified` 含該檔 |
| `stock-module-trading/pom.xml` 兩個 test-scope 依賴 | 04-04-T1 | 04-04 `files_modified` 含 pom.xml；`T-04-SC` 已判定為官方 starter、免人工閘門 |
| `TradingControllerTest` + `TestExceptionHandler` + `TestSecurityConfig` | 04-04-T1（骨架）／04-04-T2（斷言） | 04-04 `files_modified` 含 `TradingControllerTest.java` |
| `src/services/tradingApi.test.ts` | 04-07-T1 | 04-07 `files_modified` |
| `src/services/marketApi.test.ts` | 04-06-T2 | 04-06 `files_modified` |
| `src/services/portfolioRevision.test.ts` | 04-07-T2 | 04-07 `files_modified` |
| `src/components/OrderTicket.test.ts` | 04-09-T1 | 04-09 `files_modified`；且該 task 就是 RED |
| `src/api-adapter-wiring.test.ts`（擴充） | 04-08-T1 | 04-08 `files_modified` |
| `src/i18n.ts` 雙語 + parity | 04-08-T2 | 04-08 `files_modified` 含 `i18n.ts` 與 `i18n.test.ts` |
| migration IT：partial index 斷言 | 04-01-T2 | `TransactionsIdempotencyIT` 的 Test 2 斷言 `indexdef` 同時含 `UNIQUE` 與 `WHERE` |

11 項全部有擁有 task，無孤兒。

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Sparkline visual correctness (klines renders a sensible line) | TRAD-01 (D-01) | Component tests assert behaviour, not pixels | Open the ticket in API mode against a running backend, pick a seeded symbol, confirm the chart shape matches `GET /market/{symbol}/klines` data |
| `TRADE_EXECUTE` permission denial → 403 | TRAD-06 | `Role.USER` already includes `TRADE_EXECUTE`, so no role exists that lacks it. **If no such role can be constructed, mark as "guaranteed by `@PreAuthorize`, no independent test" — do not claim coverage.** | `MethodSecurityDenialIT` pattern, only if a permission-less role is available |
| 版面比例／動畫觀感／文案在實際版位下的可讀性（雙 mode） | TRAD-01 ~ TRAD-06 | jsdom 沒有版面與動畫；`04-UI-SPEC.md` 已誠實標為人工項 | `04-13-T2`（`checkpoint:human-verify`）的甲／乙／丙三段 14 步 |

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

- [x] All tasks have an `<automated>` verify or a Wave 0 dependency — 29 個 task 中 **28 個**帶 `<automated>`；唯一例外 `04-13-T2` 是 `checkpoint:human-verify`，屬 §Manual-Only 的刻意人工項
- [x] Sampling continuity: no 3 consecutive tasks without automated verify — 全 Phase 僅 1 個無 `<automated>` 的 task，且位於最後一個 wave 的最末
- [x] Wave 0 covers all MISSING references (incl. the Docker gate) — 11 項各有擁有 task（見 §Wave 0 Requirements 的擁有 plan 表）
- [x] No watch-mode flags — 前端一律 `npx vitest run`（不是 `vitest`），後端一律 `mvnw test` / `mvnw verify`
- [ ] Feedback latency < 60s for unit/component layers — **僅對 unit/component 層成立**（backend unit ~30s、frontend ~40s）。6 個以 backend IT 為驗收的 task（04-01-T2/T3、04-02-T2、04-04-T3、04-05-T1/T2）因 Testcontainers 啟動需 ~3-5min 而超過 60s。這是 §Test Infrastructure 已載明並接受的取捨（真實 PostgreSQL 對 TRAD-03 無替代方案），**不是可修正的缺口**，故此項據實不勾。
- [x] Both repos verified (judgment §8) — `04-13-T1` 步驟 A 明列四項指令，acceptance criteria 要求四者皆 exit 0 且在同一份乾淨工作樹下執行
- [x] `nyquist_compliant: true` set in frontmatter — 已於本次回填設定

**Approval:** plan-time validation **通過**（2026-07-26 回填）—— 13 個 plan / 29 個 task 全數入表，六項 sign-off 成立，Wave 0 十一項各有擁有 task。

**執行時尚未放行**：`wave_0_complete` 仍為 `false`。必須在 Wave 1 開始前實測 `docker info` 成功才可翻為 `true`；在此之前不得啟動 `04-01-T2`（第一個需要真實 PostgreSQL 的 task）。
