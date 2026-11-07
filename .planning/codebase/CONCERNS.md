---
last_mapped_commit: 5ca148d0e17ea7e2cafc957738dbfc70fe6dfa74
frontend_last_mapped_commit: 5e215a00510e25fddce7d02b6aa3bf7d0942748d
---

# Codebase Concerns

**Analysis Date:** 2026-11-06(初次分析 2026-05-30)

**Refresh:** 2026-11-06 targeted refresh (quick-261106-fbs Task 3) — 以程式核對並修正與現況衝突處,**不是** `/gsd-map-codebase` 全量重產(本次執行環境無法派 mapper agents;建議日後全量重產)。前端路徑以 Yuan 本機佈局 `../../vue/stock-v2/vue-app` 表示(cloud 環境為 `../stock-web-v2-front-end/vue-app`)。

## 2026-11-06 狀態總覽(targeted refresh)

以下各節保留 2026-05-30 的原始描述;本表記錄 2026-11-06 以程式核對的現況。**已解決**者不再是待辦,僅作歷史脈絡。

| 原始 concern | 現況 | 證據 |
|---|---|---|
| Bearer-token-only auth / Global CSRF disable with credentialed CORS / Cookie-auth fails without credentials | **已解決**(Phase 1 起):瀏覽器走 HttpOnly cookie + 雙提交 CSRF,前端 `credentials: 'include'` | `SecurityConfig`(`JwtAuthenticationFilter`、`BrowserCsrfFilter`)、`CsrfController`、`apiClient.ts`、`BrowserAuthFlowIT` |
| Refresh tokens returned in JSON | **部分解決**:瀏覽器 login / register / refresh 不回 token;只有非瀏覽器的 `POST /api/v1/auth/token` 回 bearer JSON(設計如此) | `AuthController`、`ai-docs/browser-auth-contract.md` |
| Duplicated frontend HTTP envelope parsing | **已解決**:信封解析只在 `apiClient.ts`(其他 service 不再自帶 `readJson` / `isApiFailure`) | `grep` 2026-11-06 |
| Frontend trading split between mock mutation and backend API | **已解決**:`OrderTicket` 經 `tradingApi`(mock / HTTP 同介面),payload 只含 `CreateTradeRequest` 欄位 | `vue-app/src/services/tradingApi.ts` |
| Market order UI vs backend manual trade | **部分**:payload 已符合「已成交手動紀錄」(無 order type / TIF);OrderTicket 仍保留 MKT / LMT 切換與 TIF 標籤(僅 UI),是否移除未在本次判斷 | `OrderTicket.vue`(`ordType`、`tif`) |
| Backtest custom strategy `new Function` in browser mock path | **仍存在**(mock path only) | `backtestApi.ts`、`StrategyEditor.vue`、`Backtest.vue` |
| WebSocket ticket query not URL-decoded | **仍存在**(後端簽發的 ticket 為 URL-safe,實務無影響) | `MarketHandshakeInterceptor#extractTicket` |
| WebSocket endpoint allows all origins | **已解決**:handler 註冊用 `stock.cors.allowed-origins` | `WsConfig#registerWebSocketHandlers`(`setAllowedOrigins`) |
| Trading API without idempotency | **已解決**:`Idempotency-Key` + `transactions.idempotency_key`(V11);現金 / 股利 / 股數調整同樣冪等 | `TradingService`、`TradingApiIT` |
| Trading permissions coarse | 仍為設計現況(Role → Permission 靜態對應;無 per-user override) | `Role.java`、`ai-docs/security.md` §3 |
| Full backend suite starts heavy containers | **部分解決**:IT 移到 failsafe(`./mvnw test` 不起容器);market-data IT 共用容器仍為 backlog | `stock-start/pom.xml`、todo `2026-09-27-it-shared-containers` |
| Backfill idempotency test fixed 5 s sleep | **仍存在** | `BackfillJobIT`(`Thread.sleep(5_000)`) |
| Portfolio summaries N latest-price reads on cache miss | **已解決**:`MarketDataFacade.findLatestPrices` / `findDailyQuotes` 批次;快取未命中 4 次批次往返(D-23 裁決) | `PortfolioQueryService`、`ai-docs/code-standards.md` §Facade Call Rules |
| Frontend API mode silently falls back to mock | **已解決**:無效 `VITE_DATA_MODE` 拋 `RuntimeDataModeError` | `runtimeDataMode.ts` |
| Trading pagination very large offsets | **已緩解**:`page` 夾限 0..10000、`size` 1..100 | `TradingService`(`MAX_PAGE`) |
| No backend portfolio read model beyond trades / holdings | **已解決**:summary(基準幣、今日損益、現金、配置、股利)、cash entries、dividends、share adjustments、activities | `ai-docs/trading-portfolio-contract.md` |
| No frontend auth client | **已解決**:`authApi` / `authSession` | `vue-app/src/services/authApi.ts` |
| No backend for AI access / broker keys / MCP | **仍存在**:後端無端點,前端 Preview | 前端 `docs/api-contracts/mock-to-real-contract.md` 檔首 |
| Test gaps: CSRF / cookie, idempotency, frontend HTTP auth, API mode vs real backend | **已補**:`BrowserAuthFlowIT`、`TradingApiIT` 冪等案例、`authSession` / `apiClient` 測試、CI browser E2E(Playwright 對真後端) | 各檔 |
| Spring Boot 4.0.4 / Springdoc 3.0.2 / Vue Router 5 / TypeScript 6 前緣版本 | 仍適用(版本未變;TypeScript 已為 6.0) | `pom.xml`、`vue-app/package.json` |
| 其餘(大型前端頁面、Redis 依賴、交易寫入合併 projection、前端 mock store 記憶體內、ingest 迴圈) | 本次未逐條重驗 | — |

## Tech Debt

**Bearer-token-only auth contract conflicts with cookie-ready CORS/CSRF posture:**
- Issue: The backend disables CSRF globally and returns `accessToken` / `refreshToken` in JSON bodies while CORS allows credentials. There is no cookie writer, CSRF token endpoint, refresh-cookie rotation contract, or frontend credential mode.
- Files: `stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java`, `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java`, `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthResponse.java`, `../../vue/stock-v2/vue-app/src/services/apiClient.ts`
- Impact: Browser integration can work only by manually attaching bearer tokens from JavaScript storage or memory. If the integration moves refresh tokens into cookies, state-changing endpoints such as `POST /api/v1/trades`, `POST /api/v1/auth/logout`, backtest creation, backfill, and AI access writes have no CSRF defense.
- Fix approach: Choose one auth transport per browser mode. For cookie mode, issue refresh/access cookies with `HttpOnly`, `Secure`, and `SameSite` policy, add CSRF tokens for unsafe methods, configure `fetch(..., { credentials: 'include' })`, and keep bearer-token auth available only for non-browser API clients.

**Duplicated frontend HTTP envelope parsing:**
- Issue: `apiRequest`, `readJson`, `isApiFailure`, and `isPaginatedResponse` logic is duplicated across multiple frontend services instead of sharing one typed client path.
- Files: `../../vue/stock-v2/vue-app/src/services/apiClient.ts`, `../../vue/stock-v2/vue-app/src/services/backtestApi.ts`, `../../vue/stock-v2/vue-app/src/services/opsApi.ts`, `../../vue/stock-v2/vue-app/src/services/aiAccessApi.ts`
- Impact: API behavior can diverge silently across domains, especially for request IDs, error envelopes, auth headers, credentials, and pagination validation.
- Fix approach: Keep one shared API client in `../../vue/stock-v2/vue-app/src/services/apiClient.ts` with `request`, `paginatedRequest`, auth/credentials hooks, and schema guards. Domain clients should only build paths and map DTOs.

**Frontend trading remains split between mock portfolio mutation and backend trading API:**
- Issue: The order ticket mutates local Pinia/mock store directly and does not use a service boundary that can point at backend `POST /api/v1/trades`.
- Files: `../../vue/stock-v2/vue-app/src/components/OrderTicket.vue`, `../../vue/stock-v2/vue-app/src/store.ts`, `../../vue/stock-v2/vue-app/src/stores/mockPortfolio.ts`, `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/TradingController.java`
- Impact: UI validation, order states, and fill simulations can drift from backend validation and persistence. Integration work must replace component-local execution rather than swapping a small API adapter.
- Fix approach: Introduce a `TradingApi` service with mock and HTTP implementations, then make `OrderTicket.vue` call the service and update stores from returned backend DTOs.

**Backtest custom strategy validation executes user JavaScript in the browser mock path:**
- Issue: Mock backtest validation compiles arbitrary strategy code with `new Function`.
- Files: `../../vue/stock-v2/vue-app/src/services/backtestApi.ts`, `../../vue/stock-v2/vue-app/src/pages/Backtest.vue`, `../../vue/stock-v2/vue-app/src/components/StrategyEditor.vue`
- Impact: A pasted strategy can execute in the app origin during mock mode. This is acceptable only for local demos and becomes a blocker if custom strategy editing is exposed in a shared environment without isolation.
- Fix approach: Validate custom strategies server-side or inside a sandboxed worker/iframe with a narrow message protocol. Keep browser-side syntax checks limited to parsing or static validation.

## Known Bugs

**Cookie-auth integration fails without explicit frontend credentials support:**
- Symptoms: Browser cookies are not sent by the shared client because `fetch(path, init)` receives no `credentials` option. Backend CORS permits credentials but the frontend never opts in.
- Files: `../../vue/stock-v2/vue-app/src/services/apiClient.ts`, `stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java`
- Trigger: Enable cookie-backed auth or refresh-token cookies and call any protected endpoint from the Vue app.
- Workaround: Use bearer tokens in `Authorization` headers until a cookie contract is added.

**WebSocket query ticket parsing does not URL-decode values:**
- Symptoms: `MarketHandshakeInterceptor.extractTicket` returns the raw substring after `ticket=`. URL-encoded characters are not decoded before Redis lookup.
- Files: `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/ws/MarketHandshakeInterceptor.java`, `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/ws/WsTicketService.java`
- Trigger: A client URL-encodes the ticket query parameter with any encoded byte sequence.
- Workaround: Current generated tickets are URL-safe Base64 without padding, so normal backend-issued tickets avoid the issue.

**Market order UI sends simulated fill price but backend records supplied request price:**
- Symptoms: The Vue order ticket distinguishes market and limit orders, but backend `CreateTradeRequest` has only `symbol`, `type`, `quantity`, `price`, `fee`, `note`, and `executedAt`; there is no order type, time-in-force, submitted price, fill price, or order status.
- Files: `../../vue/stock-v2/vue-app/src/components/OrderTicket.vue`, `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/CreateTradeRequest.java`, `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/TradingService.java`
- Trigger: Wire `OrderTicket.vue` directly to `POST /api/v1/trades` while preserving the current market/limit UI.
- Workaround: Treat backend trades as manual executed trades, not order placement, until the API adds order semantics.

## Security Considerations

**Global CSRF disable with credentialed CORS:**
- Risk: If browser credentials are used, a cross-site form or script can target unsafe endpoints unless CSRF tokens or strict SameSite cookies are enforced.
- Files: `stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java`, `stock-start/src/main/resources/application.yaml`
- Current mitigation: Protected endpoints require JWT bearer auth. Default CORS origin is `http://localhost:5173`, and credentials are allowed only for configured origins.
- Recommendations: Keep bearer auth for SPA calls or add CSRF for cookie auth before enabling cookies. Add tests for unsafe methods with and without CSRF tokens in `stock-start/src/test/java/dowob/xyz/stockwebv2/start/`.

**WebSocket endpoint allows all origins at handler registration:**
- Risk: `WsConfig` uses `.setAllowedOriginPatterns("*")` even though REST CORS is origin-restricted. Ticket auth protects access, but wildcard origins weaken browser-origin policy and can conflict with deployment expectations.
- Files: `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/config/WsConfig.java`, `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/ws/MarketHandshakeInterceptor.java`
- Current mitigation: A short-lived one-time Redis ticket is required and token version is checked during handshake.
- Recommendations: Bind WebSocket allowed origins to the same `stock.cors.allowed-origins` property used by REST and add an origin rejection test in `stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/ws/`.

**Trading API records trades without idempotency or client order identifiers:**
- Risk: Double-clicks, retries, network timeouts, or frontend replay can create duplicate buy/sell transactions because each request always inserts a new UUID and transaction.
- Files: `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/TradingService.java`, `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/repository/JdbcTradingRepository.java`, `stock-db-migration/src/main/resources/db/migration/V7__trading_schema.sql`, `stock-start/src/test/java/dowob/xyz/stockwebv2/start/TradingApiIT.java`
- Current mitigation: Holding updates use `SELECT ... FOR UPDATE` and optimistic version checks, which protect concurrent balance updates but not duplicate submissions.
- Recommendations: Require an `Idempotency-Key` or `clientOrderId`, persist it with `user_id`, and return the existing trade for duplicate keys. Add retry/double-submit integration tests.

**Trading permissions are coarse and user accounts receive trade authority through role claims:**
- Risk: `@PreAuthorize("hasAuthority('TRADE_EXECUTE')")` protects trade creation, but there are no per-symbol, per-notional, per-day, environment, or human-confirmation controls in the backend.
- Files: `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/TradingController.java`, `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/CreateTradeRequest.java`, `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/TradingService.java`, `../../vue/stock-v2/vue-app/src/services/aiAccessApi.ts`
- Current mitigation: Asset must be active and tradeable, quantity/price/fee validation exists, and oversell is rejected.
- Recommendations: Add backend policy checks for trading limits before write operations. Do not rely on frontend AI access mock policies for real broker or AI order execution.

**Refresh tokens are bearer values stored in Redis and returned in JSON:**
- Risk: A refresh token exposed to JavaScript is vulnerable to XSS exfiltration and can be replayed until Redis TTL expiry unless revoked.
- Files: `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/service/RefreshTokenService.java`, `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java`, `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthResponse.java`
- Current mitigation: Refresh tokens are random UUIDs, stored server-side in Redis with TTL, indexed by user, and logout deletes the token.
- Recommendations: Store refresh tokens in `HttpOnly` cookies or rotate refresh tokens on use. Add device/session listing and revoke-all semantics if multiple devices are supported.

## Performance Bottlenecks

**Full backend test suite starts several heavy containers:**
- Problem: Integration tests share a static TimescaleDB, Redis, and Kafka set in `ContainerIT`, while market-data batch tests start their own container set.
- Files: `stock-start/src/test/java/dowob/xyz/stockwebv2/start/support/ContainerIT.java`, `stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/batch/BackfillJobIT.java`, `stock-start/pom.xml`, `pom.xml`
- Cause: `Startables.deepStart(postgres, redis, kafka).join()` is used for broad integration coverage, and failsafe runs `*IT` tests separately from surefire.
- Improvement path: Keep fast unit tests as the default gate, isolate container suites behind Maven profiles, enable Testcontainers reuse for local development where acceptable, and publish separate CI timings for unit, IT, and E2E phases.

**Backfill idempotency test includes a fixed 5 second sleep:**
- Problem: A fixed wait is used after duplicate message injection.
- Files: `stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/batch/BackfillJobIT.java`
- Cause: `Thread.sleep(5_000)` waits for potential duplicate consumer processing rather than waiting on an observable condition.
- Improvement path: Replace fixed sleep with Awaitility polling on Kafka/DB side effects or consumer offsets, with a short poll interval and bounded timeout.

**Portfolio summaries recompute N holdings with N latest-price reads on cache miss:**
- Problem: `summary()` calls `listHoldings()`, and each uncached holding performs an individual `findLatestPrice(assetId)` query.
- Files: `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/TradingService.java`, `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/repository/JdbcTradingRepository.java`, `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/PortfolioCache.java`
- Cause: Latest prices are fetched per holding instead of in one join or batch query.
- Improvement path: Add a repository query that returns holdings joined with latest prices for a user, and calculate the summary from that result in one DB round trip.

**Large frontend pages and components concentrate many reactive paths:**
- Problem: Several single-file Vue components exceed 400 lines and include UI, data shaping, interaction state, and CSS together.
- Files: `../../vue/stock-v2/vue-app/src/pages/Analytics.vue`, `../../vue/stock-v2/vue-app/src/pages/Alerts.vue`, `../../vue/stock-v2/vue-app/src/pages/Positions.vue`, `../../vue/stock-v2/vue-app/src/components/OrderTicket.vue`, `../../vue/stock-v2/vue-app/src/pages/Notifications.vue`
- Cause: Page-level features are implemented as self-contained prototypes with local state and styles.
- Improvement path: Extract domain composables and API adapters before backend integration. Keep repeated table, filter, and command behavior in shared components.

## Fragile Areas

**Auth state depends on Redis availability for every bearer-authenticated request:**
- Files: `stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java`, `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/service/RefreshTokenService.java`
- Why fragile: JWT parsing succeeds only after a Redis `user:auth:{userId}` lookup verifies token version and status. Redis outages turn all protected requests into auth failures.
- Safe modification: Preserve the token-version revocation invariant, but add explicit operational handling for Redis outage behavior, metrics, and health checks. Add tests for degraded-mode responses.
- Test coverage: `stock-infrastructure/src/test/java/dowob/xyz/stockwebv2/infrastructure/security/JwtServiceTest.java` covers JWT behavior; end-to-end Redis outage behavior is not represented as a dedicated integration test.

**Trading writes combine ledger insert and holding projection update in one service method:**
- Files: `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/TradingService.java`, `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/domain/HoldingCalculator.java`, `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/repository/JdbcTradingRepository.java`
- Why fragile: Adding order lifecycle, broker fills, partial fills, cancellations, or idempotency changes both immutable transaction history and mutable holdings. Current tests cover buy/sell/oversell but not retries, duplicate requests, or concurrent same-user trades.
- Safe modification: Treat transactions as the ledger source of truth, add idempotency first, and keep holding projection updates transactional and locked per `user_id` / `asset_id`.
- Test coverage: `stock-start/src/test/java/dowob/xyz/stockwebv2/start/TradingApiIT.java` and `stock-module-trading/src/test/java/dowob/xyz/stockwebv2/trading/domain/HoldingCalculatorTest.java` cover core happy paths and oversell only.

**Frontend API mode is opt-in and silently falls back to mock:**
- Files: `../../vue/stock-v2/vue-app/src/services/runtimeDataMode.ts`, `../../vue/stock-v2/vue-app/src/services/backtestApi.ts`, `../../vue/stock-v2/vue-app/src/services/opsApi.ts`, `../../vue/stock-v2/vue-app/src/services/aiAccessApi.ts`, `../../vue/stock-v2/vue-app/src/api-adapter-wiring.test.ts`
- Why fragile: Any missing or mistyped `VITE_DATA_MODE` value becomes `mock`, which can hide failed backend integration in local demos and CI.
- Safe modification: Make API mode explicit for integration builds, fail startup for invalid values outside local development, and surface the active mode in a non-sensitive diagnostics panel.
- Test coverage: `../../vue/stock-v2/vue-app/src/services/runtimeDataMode.test.ts` covers normalization; there is no build-time guard that prevents accidental mock mode for backend-integrated environments.

**Frontend AI access and broker controls are mock-only for high-risk workflows:**
- Files: `../../vue/stock-v2/vue-app/src/services/aiAccessApi.ts`, `../../vue/stock-v2/vue-app/src/components/settings/SettingsBrokers.vue`, `../../vue/stock-v2/vue-app/src/components/settings/SettingsAiAccess.vue`, `../../vue/stock-v2/vue-app/src/composables/useAiAccessSettings.ts`
- Why fragile: The UI models live trading keys, HITL modes, MCP endpoints, and audit logs, but the backend has no matching API module or policy enforcement path.
- Safe modification: Define backend DTOs and enforcement before enabling API mode for these pages. Treat the mock as UX-only data, not as a security model.
- Test coverage: `../../vue/stock-v2/vue-app/src/services/aiAccessApi.test.ts`, `../../vue/stock-v2/vue-app/src/components/settings/SettingsBrokers.test.ts`, and `../../vue/stock-v2/vue-app/src/components/settings/SettingsAiAccess.test.ts` cover mock behavior only.

**WebSocket auth relies on Redis ticket and token-version data with permissive origin config:**
- Files: `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/ws/WsTicketService.java`, `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/ws/MarketHandshakeInterceptor.java`, `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/config/WsConfig.java`
- Why fragile: The ticket flow is strong, but origin policy, ticket extraction, Redis availability, and token-version semantics must remain aligned. Small changes can make all WebSocket clients fail or broaden access.
- Safe modification: Keep ticket issuance/consumption isolated in `WsTicketService`, use shared origin config, and extend tests when changing handshake request parsing or Redis keys.
- Test coverage: `stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/ws/WsTicketServiceIT.java`, `stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/ws/MarketHandshakeInterceptorTest.java`, and `stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/WsAuthFlowE2ETest.java` cover ticket behavior and auth flow.

## Scaling Limits

**Trading pagination allows very large offsets:**
- Current capacity: `TradingService` clamps `page` to `10_000` and `size` to `100`, allowing offsets up to 1,000,000 rows per user.
- Limit: Offset pagination degrades as a user's transaction history grows.
- Scaling path: Add keyset pagination on `(created_at, id)` using the existing `idx_transactions_user_created` and `idx_transactions_user_asset_created` indexes.

**Market ingest loops over every active tradeable asset each scheduled tick:**
- Current capacity: `ScheduledIngestor` fetches all active tradeable assets and emits ticks on a schedule.
- Limit: Per-second full-list polling scales linearly with asset count and provider latency.
- Scaling path: Partition symbols, add provider-specific rate limiting/backoff, and move high-volume providers to streaming ingestion.

**Frontend mock stores are in-memory only:**
- Current capacity: Mock data survives within a browser session and is reset by reload.
- Limit: API integration cannot depend on mock state semantics for persistence, concurrency, or multi-device behavior.
- Scaling path: Keep mock stores only as local demo fixtures and move durable behavior through backend APIs.

## Dependencies at Risk

**Spring Boot 4.0.4 and Springdoc 3.0.2 are early major-version dependencies:**
- Risk: Framework behavior and ecosystem compatibility can change quickly across early major releases.
- Impact: Security, test, OpenAPI, and Jackson behavior changes can break generated API docs or test setup.
- Migration plan: Pin versions intentionally in `pom.xml`, monitor Spring Boot and springdoc release notes, and keep dependency update PRs small with full `mvn verify` coverage.

**Vue Router 5 and TypeScript 6 are forward-edge frontend dependencies:**
- Risk: Ecosystem tooling and examples may lag these versions.
- Impact: Router API assumptions and type-checking behavior can shift under app code and tests.
- Migration plan: Keep `package-lock.json` committed, update frontend dependencies in isolated PRs, and require `npm run build` plus `npm test` before integration merges.

## Missing Critical Features

**No backend API for frontend portfolio/trading read model beyond trades and holdings:**
- Problem: The frontend has pages for positions, analytics, alerts, notifications, settings, AI access, brokers, watchlists, and ops, but backend modules currently cover assets, auth, backtest, market data, trading, and backfill/admin flows.
- Blocks: Full API mode for `../../vue/stock-v2/vue-app/src/pages/Positions.vue`, `../../vue/stock-v2/vue-app/src/pages/Analytics.vue`, `../../vue/stock-v2/vue-app/src/pages/Alerts.vue`, `../../vue/stock-v2/vue-app/src/pages/Notifications.vue`, `../../vue/stock-v2/vue-app/src/pages/Settings.vue`, and `../../vue/stock-v2/vue-app/src/pages/Ops.vue`.

**No frontend auth client or protected-route model:**
- Problem: The Vue app has service adapters but no shared auth service, token storage strategy, refresh handling, or route guard.
- Blocks: Calling protected backend endpoints from `../../vue/stock-v2/vue-app/src/services/backtestApi.ts`, `../../vue/stock-v2/vue-app/src/services/opsApi.ts`, `../../vue/stock-v2/vue-app/src/services/aiAccessApi.ts`, and a future trading API adapter.

**No backend implementation for AI access, broker keys, or MCP endpoint policy:**
- Problem: The frontend models API keys, provider permissions, HITL modes, risk limits, MCP endpoints, agents, and audit logs; no matching backend module exists.
- Blocks: Secure AI trading or broker integration from `../../vue/stock-v2/vue-app/src/services/aiAccessApi.ts` and settings components.

## Test Coverage Gaps

**CSRF and cookie-auth flows:**
- What's not tested: Cookie issuance, `SameSite` behavior, `HttpOnly` cookies, credentialed cross-origin fetch, CSRF success/failure, and refresh-token cookie rotation.
- Files: `stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java`, `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java`, `../../vue/stock-v2/vue-app/src/services/apiClient.ts`
- Risk: Browser auth integration can pass bearer-token tests while failing or being unsafe in cookie mode.
- Priority: High

**Trading idempotency and concurrent duplicate submissions:**
- What's not tested: Replayed `POST /api/v1/trades`, client retry after timeout, concurrent buys/sells for the same holding, and duplicate prevention by client key.
- Files: `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/TradingService.java`, `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/repository/JdbcTradingRepository.java`, `stock-start/src/test/java/dowob/xyz/stockwebv2/start/TradingApiIT.java`
- Risk: Duplicate trades and incorrect holdings can occur under network retries or parallel submissions.
- Priority: High

**Frontend HTTP auth behavior:**
- What's not tested: Authorization header injection, refresh handling, `credentials: include`, API base URL configuration, and protected endpoint redirects or error handling.
- Files: `../../vue/stock-v2/vue-app/src/services/apiClient.ts`, `../../vue/stock-v2/vue-app/src/services/apiClient.test.ts`, `../../vue/stock-v2/vue-app/src/api-adapter-wiring.test.ts`
- Risk: Switching from mock to API mode surfaces auth failures late in integration.
- Priority: High

**Full-suite duration and noisy container tests:**
- What's not tested: No committed timing budget, no per-suite duration report, and no test profile document that separates unit, integration, E2E, and frontend suites.
- Files: `pom.xml`, `stock-start/pom.xml`, `stock-start/src/test/java/dowob/xyz/stockwebv2/start/support/ContainerIT.java`, `stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/batch/BackfillJobIT.java`, `../../vue/stock-v2/vue-app/package.json`
- Risk: Developers avoid full verification or CI becomes hard to diagnose as container suites expand.
- Priority: Medium

**Frontend API mode against real backend:**
- What's not tested: The Vue app in `VITE_DATA_MODE=api` against the Spring backend for auth, CORS, backtest, market data, trading, and settings paths.
- Files: `../../vue/stock-v2/vue-app/src/services/runtimeDataMode.ts`, `../../vue/stock-v2/vue-app/src/api-adapter-wiring.test.ts`, `stock-start/src/test/java/dowob/xyz/stockwebv2/start/`
- Risk: Mock adapters stay green while backend contracts are missing, differently shaped, or unauthorized.
- Priority: High

---

*Concerns audit: 2026-05-30;targeted refresh 2026-11-06*
