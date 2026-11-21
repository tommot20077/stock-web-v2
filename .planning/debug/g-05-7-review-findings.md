---
status: diagnosed
trigger: "UAT Test 7 — Yuan decided these code-review findings must be fixed in this phase: BE-CR-01 (critical), FE-WR-03, FE-WR-04, FE-WR-01, FE-WR-02, BE-WR-01 (see 05-REVIEW.md)"
created: 2026-11-14T13:40:00Z
updated: 2026-11-14T14:50:00Z
goal: find_root_cause_only
symptoms_prefilled: true
---

## Current Focus

hypothesis: CONFIRMED — all 6 findings still present on backend e9919b8 / frontend 06ab920 (develop after Wave 2 merge); none partially fixed
bug_class: Bohrbug (deterministic static defects / guard-coverage gaps)
test: done (code re-read at current HEAD + scratch probes for BE-CR-01 / FE-WR-03 / validator)
expecting: n/a
next_action: return ROOT CAUSE FOUND to orchestrator (goal find_root_cause_only; no fix)
reasoning_checkpoint:
  hypothesis: "Each finding is a missing constraint or a guard whose scan surface is narrower than the defect class: (1) initialCapital lacks magnitude/scale bounds on both sides; (2) up/down literals live in template attr / script string / unlisted selector outside the <style>-selector-allowlist guard; (3) non-ApiClientError catch branches still assume 'no Response = network' although D-26 moved that to apiClient and write adapters map outside apiRequest; (4) SC5 sentinels are sourced only from data.ts/mock store; (5) D-09 ArchUnit predicate keys on Dto/Request suffix + raw field type."
  confirming_evidence:
    - "CreateBacktestRunRequest.java:17 unchanged; HV9 probe: 0 violations for 1e999999999; setScale cost superlinear (1e3000000 = 1.9 s)"
    - "Backtest.vue:319-323 only parseDecimalInput + lte(ZERO); big.js toFixed('1e999999999') OOMs node"
    - "Backtest.vue:141,372-373 / Analytics.vue:835-839 literals; contrastTokens.test.ts:647-690 scans only <style> rules with UPDOWN_SELECTOR allowlist"
    - "11 NETWORK_ERROR fallbacks + App.vue:236; tradingApi.ts:154 maps outside apiRequest"
    - "MOCK_SENTINELS lacks KPI/cash values; ApiDecimalStringRulesTest.java:64-83 predicate"
  falsification_test: "Any cited line already carrying the bound/token/UNKNOWN label/sentinel/broader predicate would falsify — none found"
  fix_rationale: "n/a (diagnose-only) — directions in Resolution"
  blind_spots: "E2E (real backend) not run locally; Jackson 3.1 acceptance of the '1e999999999' string taken from review measurement (BigDecimal parse itself verified); SVG presentation-attribute color-mix support not browser-tested"
  candidate_causes:
    - "code: missing Bean Validation / FE input grammar (BE-CR-01, FE-WR-03)"
    - "test/guard config: guard scan surface too narrow (FE-WR-04, FE-WR-02, BE-WR-01)"
    - "data/contract: mapper TypeError after 2xx relabelled NETWORK_ERROR (FE-WR-01)"
  and_gate: "yes for BE-CR-01: DoS needs (no @Digits/@DecimalMax) AND (engine.run before persistence calling setScale/toPlainString); for FE-WR-04 escape needs (literal outside <style>/allowlist) AND (guard only parses <style>)"

## Symptoms

expected: 回測 initialCapital 前後端都有上限與位數限制(極端值回 400、不進引擎);Backtest / Analytics 漲跌色全走 token;非 ApiClientError 例外不再一律標成 NETWORK_ERROR;SC5 mock 哨兵在 Overview / Cash 能真正觸發;D-09 守門涵蓋 *Response / 參數化型別 / getter 等繞過寫法
actual: UAT Test 7 — six code-review findings (BE-CR-01, FE-WR-03, FE-WR-04, FE-WR-01, FE-WR-02, BE-WR-01) judged must-fix this phase
errors: none (static review findings)
reproduction: Test 7 in UAT; write-ups in 05-REVIEW.md
started: discovered during code review, triaged during UAT 2026-11-14

## Eliminated

## Evidence

- timestamp: 2026-11-14T13:40:00Z
  checked: knowledge base (.planning/debug/knowledge-base.md)
  found: does not exist (debug dir newly created); no MemPalace
  implication: no known-pattern candidate

- timestamp: 2026-11-14T13:50:00Z
  checked: [BE-CR-01] stock-module-backtest/.../api/CreateBacktestRunRequest.java:17
  found: still `@NotNull @DecimalMin(value = "0.0", inclusive = false) @DecimalString BigDecimal initialCapital` — no @DecimalMax, no @Digits. BacktestService.validateCapital (:192-196) only checks `> 0`. createRun (:73-102) calls engine.run(input) BEFORE persistence. Engine seed() :51 `initialCapital.setScale(2, HALF_UP).toPlainString()`, equityCurve :76-77 money() -> setScale(6).
  implication: STILL PRESENT (unchanged since review)

- timestamp: 2026-11-14T13:52:00Z
  checked: scratch probe (scratchpad/becr/Probe*.java, JDK 21)
  found: new BigDecimal("1e999999999") -> scale=-999999999, precision=1, compareTo(0)>0 true (passes validateCapital); integer digits = 1e9 (would fail @Digits(integer=N)). setScale(2).toPlainString cost: 1e100000 74ms / 1e1000000 669ms (1 MB string) / 1e3000000 1941ms (3 MB) — superlinear; 1e999999999 = OOM/hang.
  implication: DoS mechanism confirmed empirically; @Digits integer bound alone rejects it in Bean Validation before the service/engine runs

- timestamp: 2026-11-14T13:54:00Z
  checked: [BE-CR-01 side-edge] engine drawdownCurve / V3 schema / GlobalExceptionHandler
  found: (a) tiny capital e.g. 0.0000001 -> money() setScale(6) = 0.000000 -> drawdownCurve divides by peak=0 -> ArithmeticException (service only catches IllegalArgumentException) -> catch-all 500; even if it got past, V3 initial_capital NUMERIC(24,6) + CHECK (initial_capital > 0) would reject. (b) V3 equity strategy_value NUMERIC(24,6); engine monthly return range -0.35%..+0.45%, max 60 months -> max growth ~1.31x, so a 1e12 cap leaves ample headroom under 1e18. (c) no DataIntegrityViolation handler -> 500.
  implication: fraction-digit cap (<=6, DB scale) is also needed, not just the upper bound

- timestamp: 2026-11-14T13:55:00Z
  checked: existing patterns + tests
  found: trading requests use `@DecimalMin("0.00000001") @DecimalMax("1000000000000") @Digits(integer = 13, fraction = 8) @DecimalString` (CreateCashEntryRequest:44-45, CreateDividendRequest:36-37); CreateBacktestRunRequestValidationTest only covers symbol length; ValidationBoundaryE2E D3 (:126-148) covers 0.01 lower bound (JSON number) only; trading upper-bound E2E pattern at ValidationBoundaryE2E:200-275 (10^15 / 10^17 / at-bound / bound+1 ulp)
  implication: mirror pattern; add validator-unit + WebMvc/E2E cases

- timestamp: 2026-11-14T14:00:00Z
  checked: [FE-WR-03] vue-app/src/pages/Backtest.vue:319-323, services/decimal.ts:75-83
  found: `const capital = parseDecimalInput(initialText.value); if (capital === null || capital.lte(ZERO)) {...}` — no plain-decimal pattern, no max, no scale check. parseDecimalInput = trim + big.js D(). Then `toChartNumber(capital)` (:328) and backtestApi toWire (backtestApi.ts:41).
  implication: STILL PRESENT (line drift 320-328 -> 319-328 only)

- timestamp: 2026-11-14T14:02:00Z
  checked: node probe with project big.js (strict)
  found: '1e5' ok; '1e400' -> toFixed len 401, Number() = Infinity; '0.000000000000000000001' accepted; '1e999999999' constructs cheaply (c=[1], e=999999999, .gt(1e12) works) but toFixed() -> node heap OOM after ~25s; '1e10000000'.toFixed() = 10 MB string / 1.2s.
  implication: worse than review stated — typing 1e999999999 freezes/OOMs the browser tab in toChartNumber BEFORE any request; the bound check must run before toChartNumber/toWire (comparisons are cheap)

- timestamp: 2026-11-14T14:04:00Z
  checked: coupling BE-CR-01 <-> FE-WR-03
  found: existing test Backtest.test.ts:135-143 sends '123456789012.12345678' (8 decimals) and asserts it is sent verbatim; DB scale is 6 (V3 NUMERIC(24,6)); frontend money forms use MAX_DECIMALS=8 + 1e12 max (CashEntryDialog:463-466, OrderTicket:919-920, HoldingEventDialog:1168-1172). Mock adapter createMockBacktestApi (backtestApi.ts:231) only checks <= 0.
  implication: backend fraction limit and frontend limit must be decided together; if backend uses fraction=6 (DB scale), the 8-decimal FE test must change; mock should mirror the backend bound

- timestamp: 2026-11-14T14:10:00Z
  checked: [FE-WR-04] grep rgba(22,163,74 / 220,38,38 / 239,68,68 across vue-app/src
  found: Backtest.vue:141 `<path :d="ddPath" fill="rgba(220,38,38,0.18)" stroke="var(--dn)" .../>`; Backtest.vue:368-374 heatColor returns `rgba(22, 163, 74, a)` / `rgba(220, 38, 38, a)` bound via `:style="{ background: heatColor(c) }"` (:126); Analytics.vue:834-839 `.harvest-btn { background: rgba(239,68,68,0.1); color: var(--dn); border: 1px solid rgba(239,68,68,0.25) } .harvest-btn:hover { background: rgba(239,68,68,0.18) }`. Others: Notifications.vue:445,514 rgba(239,68,68) on `.alert` (alert category, not up/down), Analytics.vue:551 '#ef4444' correlation heat (not up/down).
  implication: STILL PRESENT exactly as reviewed (no drift)

- timestamp: 2026-11-14T14:12:00Z
  checked: why the existing D-02c/D-28 guard missed them — contrastTokens.test.ts:613-690
  found: D-28 guard filters by ERROR_SELECTOR and only flags rgba(220,38,38 in error selectors; D-02c guard uses selector allowlist UPDOWN_SELECTOR = /.(pill|kind-pill).(buy|sell)|.side-btn.(buy|sell)|.depth.(up|dn)-bg|.metric-delta.(good|bad)/ and only inspects parsed <style> rules (vueRules). An SVG presentation attribute in <template>, a script-returned inline :style string, and the unlisted `.harvest-btn` selector are all outside its scan surface.
  implication: root cause = migration done per selector-allowlist + guard scans only <style> rules; a literal-based scan over template+script+style (templateAndScriptOf helper exists) is needed

- timestamp: 2026-11-14T14:20:00Z
  checked: [FE-WR-01] grep NETWORK_ERROR fallbacks for non-ApiClientError in vue-app/src (excl. tests/i18n)
  found: still present at OrderTicket.vue:1454-1457, CashEntryDialog.vue:679-681, HoldingEventDialog.vue:1577-1584, WatchlistPicker.vue:228-231, Watchlist.vue:376-379 (toApiError) and :464-467 (describeError), Settings.vue:350-353 (diagnosticsOf), Overview.vue:678-680, Markets.vue:204-206, Chart.vue:221-223, baseCurrencyErrorKey.ts:13; plus App.vue:225-240 messageFromStartupError (not in review list, same pattern). Positions.vue:519, Trades.vue:457, Cash.vue:275, Overview.vue:510, OrderTicket.vue:738, HoldingEventDialog.vue:852 already use UNKNOWN_ERROR.
  implication: STILL PRESENT (11 sites in review list + App.vue); line numbers barely drifted

- timestamp: 2026-11-14T14:22:00Z
  checked: apiClient.ts transportFetch :184-198, apiRequest :366-384, write-path adapters
  found: transportFetch turns every non-Abort fetch reject into ApiClientError{status:0, code:'NETWORK_ERROR'}; AbortError is rethrown raw (only search paths use AbortController). apiRequest already throws ApiClientError{status: response.status, code:'INVALID_API_RESPONSE', requestId: requestIdFrom(payload)} for bad envelopes (:373-379, :400-406; also authApi.ts:54,75, profileApi.ts:43). Write adapters map OUTSIDE apiRequest: tradingApi.ts:154 `toTradeRecord(await apiRequest(...))`, cashApi.ts:125 toCashEntry, holdingEventApi.ts:183/188 toDividend/toShareAdjustment; toTradeRecord (:75-82) calls parseDecimal (strict, TypeError on non-string/undefined).
  implication: a post-201 mapper TypeError reaches components as a non-ApiClientError and is relabelled NETWORK_ERROR -> tradeErrNetwork / cashErrNetwork; the boundary has no step that converts mapper failures

- timestamp: 2026-11-14T14:24:00Z
  checked: i18n copy contract (i18n.ts:144-147, 194-195, 282-283, 433, 444-445, 288-289)
  found: hard rule: every post-submit trade error except tradeErrNetwork must say "交易未記錄"; tradeErrUnknown = '發生未預期的錯誤，交易未記錄。' cashErrUnknown / holdingEventErrUnknown / watchlistErrUnknown already say "may or may not have been saved, check page X"; baseCurrencyErrUnknown says "基準幣未變更".
  implication: simply relabelling to UNKNOWN_ERROR makes OrderTicket claim "nothing recorded" when the 201 trade WAS recorded — a trade-specific INVALID_API_RESPONSE copy (UI-SPEC decision) is needed; cash/holding/watchlist Unknown copy already fits

- timestamp: 2026-11-14T14:25:00Z
  checked: tests that would be affected
  found: component tests throw TypeError inside the fetch stub (OrderTicket.test.ts:1184,1584,2186; CashEntryDialog.test.ts:495; HoldingEventDialog.test.ts:767; WatchlistPicker.test.ts:345; Overview.test.ts:1002; Settings.test.ts:402) -> still become NETWORK_ERROR via transportFetch (remain valid). baseCurrencyErrorKey.test.ts:14-16,22 directly assert non-ApiClientError -> baseCurrencyErrNetwork (must change).
  implication: blast radius in tests is small; new tests needed for 201-with-number-body

- timestamp: 2026-11-14T14:35:00Z
  checked: [FE-WR-02] e2e/tests/api-failure-states.spec.ts:150-240, e2e/support/mockSentinels.ts:22-27, src/mockSentinels.test.ts, Overview.vue:765-770/842, cashApi.ts:161-180
  found: Overview case (:166-171) scope = overview-summary-error | overview-kpi, sentinels = symbols + PRICE_SENTINELS; assertReal only checks KPI visible + error count 0. Mock KPI literals ('+$12,481', '$84,210', '8.4%', 'annualized 18.4%', '+1.04%') live inline in Overview.vue mockKpis and are NOT in MOCK_SENTINELS. Cash case (:236-240) sentinels = symbols + prices + trade notes; mock cash notes 'Initial deposit' / 'Recorded by mistake' (cashApi.ts:164,172, seedEntries not exported) are NOT sentinels; assertReal counts only rows filtered by 'sc5-seed'. MOCK_SENTINELS has 4 categories (symbols/prices/notes/watchlistNames); mockSentinels.test.ts haystack = data.ts + mock store state only (no Overview KPIs, no cash seed).
  implication: STILL PRESENT; sentinel set is derived from data.ts/mockPortfolio only, so mock values defined in a component (mockKpis) or in a service mock adapter (cashApi seed) can never trip the assertion. API-mode Overview total-assets KPI subtitle = `${holdingCount} positions` (Overview.vue:796-801) -> deterministic '1' for the AAPL seed, usable in assertReal.

- timestamp: 2026-11-14T14:40:00Z
  checked: [BE-WR-01] stock-start/src/test/java/.../ApiDecimalStringRulesTest.java:64-83
  found: API_TRANSFER_TYPES = ..api.. AND simpleName endsWith Dto|Request; rule = fields().haveRawType(BigDecimal).areDeclaredInClassesThat(API_TRANSFER_TYPES).should().beAnnotatedWith(DecimalString).allowEmptyShould(false). No method rule, no generic-type rule, no self-test fixture.
  implication: STILL PRESENT (line drift 66-86 -> 64-83)

- timestamp: 2026-11-14T14:42:00Z
  checked: current exposure — controller payload types (grep ApiResponse<...> in *Controller.java), *Response types, generic BigDecimal, BigDecimal getters in api packages
  found: payload types include *Response: MeResponse, TokenResponse, BrowserSessionResponse, WsTicketResponse, EmptyResponse, CsrfController.CsrfResponse (nested), BackfillController.BackfillTriggerResponse (nested), AuthResponse in user/api. None currently has BigDecimal; no `<...BigDecimal...>` generic in any api file; no `public BigDecimal x()` non-record getter. All Dto payload types reside in ..api... marketdata/api also holds services (MarketDataFacadeImpl, MarketLatestService, KlineQueryService) that use BigDecimal locals only (no fields) — a naive "everything in ..api.." predicate would cover them too.
  implication: pure regression-guard gap (no live violation today); predicate should be rooted at controller return types or extended to Response/View + records, plus generic + method rules. ArchUnit 1.3.0 jar has JavaField.getType() (JavaType), JavaParameterizedType.getActualTypeArguments(), JavaType.traverseSignature — generic detection feasible.

- timestamp: 2026-11-14T14:45:00Z
  checked: Bean Validation probe (Hibernate Validator 9.0.1 from ~/.m2, scratchpad/becr/ValProbe.java)
  found: current constraints: 0 violations for 1e999999999 / 0.0000001 / 123456789012.12345678. Proposed `@DecimalMax("1000000000000") @Digits(integer = 13, fraction = 6)`: 1e999999999 -> 2 violations (fast), 0.0000001 -> 1, 123456789012.12345678 -> 1 (8 decimals > 6), 123456789012.123456 -> 0, 1000000000000 -> 0, 1000000000000.000001 -> 1, 1E+12 -> 0.
  implication: fix direction verified to reject DoS input in validation layer; confirms FE test value 123456789012.12345678 would start failing if backend fraction=6

## Resolution

root_cause: |
  All six findings are still present on current develop (none partially fixed). Shared shape: a constraint or guard narrower than the defect class.
  - BE-CR-01: CreateBacktestRunRequest.initialCapital (:17) has only @NotNull @DecimalMin(0, exclusive); BacktestService.validateCapital checks only > 0; engine.run runs before persistence and calls setScale(2).toPlainString (:51) / setScale(6) (:184-189) -> exponent strings like "1e999999999" (scale -999999999) cause CPU/heap exhaustion; sub-6-decimal tiny values cause ArithmeticException (divide by zero peak) -> 500.
  - FE-WR-03: Backtest.vue run() (:319-323) validates with parseDecimalInput + lte(ZERO) only; big.js accepts exponent / unbounded / >8 decimals; toChartNumber (toFixed) runs before submit, so 1e999999999 freezes/OOMs the tab, 1e400 -> Infinity axis and 401-digit wire string.
  - FE-WR-04: Backtest.vue:141 (SVG fill attr), :368-374 (heatColor inline style string), Analytics.vue:834-839 (.harvest-btn) keep rgba literals; contrastTokens D-02c guard parses only <style> rules matching a selector allowlist, so template attrs, script strings and unlisted selectors escape.
  - FE-WR-01: 11 catch branches (+ App.vue:236) map any non-ApiClientError to NETWORK_ERROR although D-26 made transportFetch the only NETWORK_ERROR source; write adapters (tradingApi:154, cashApi:125, holdingEventApi:183/188) run strict mappers outside apiRequest, so a post-2xx mapper TypeError surfaces as tradeErrNetwork/cashErrNetwork (retry loop with retained key). Naively relabelling to UNKNOWN_ERROR would show tradeErrUnknown "交易未記錄" — also false for a recorded trade.
  - FE-WR-02: MOCK_SENTINELS (and its anti-vacuity test) only harvest data.ts + mockPortfolio store; Overview mockKpis literals and cashApi seedEntries notes are not sentinels, so Overview/Cash sentinel checks cannot fail; assertReal checks presence only.
  - BE-WR-01: ApiDecimalStringRulesTest predicate = ..api.. + Dto|Request suffix and raw field type BigDecimal; misses *Response (7 live payload types), parameterized BigDecimal (List/Map/Optional), and getter/derived methods. No live violation today — pure guard gap.
fix: (not applied — goal find_root_cause_only)
verification: (n/a)
files_changed: []
