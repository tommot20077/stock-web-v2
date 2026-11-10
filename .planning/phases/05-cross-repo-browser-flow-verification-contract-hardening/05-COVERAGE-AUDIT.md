# Phase 05 Wave 1 覆蓋稽核表(05-12 Task 2)

> 依 D-01:SC1 / SC2 逐條對照現有測試,列出已涵蓋 / 缺口,只補缺口;本表是 Wave 1(05-01 ~ 05-11)的驗收證據。
> 證據全部來自 GitHub Actions(2026-11-10 收集)與各 plan SUMMARY 的 commit。沒有 CI 證據的項目一律標「缺口 / 待定」,不標通過。

## 證據來源(header)

| 項目 | 後端 `tommot20077/stock-web-v2` | 前端 `tommot20077/stock-web-v2-front-end` |
|------|------|------|
| PR | https://github.com/tommot20077/stock-web-v2/pull/55 | https://github.com/tommot20077/stock-web-v2-front-end/pull/22 |
| 分支 | `claude/epic-curie-lj8alo` | `claude/epic-curie-lj8alo` |
| head SHA | `ee4094c`(含 develop 合併 `f12d909`、docs `180a652`、Jacoco 調整 `ee4094c`) | `41469e1` |
| PR run id | **42741526488**(event `pull_request`)— https://github.com/tommot20077/stock-web-v2/actions/runs/42741526488 | **42648098145**(event `pull_request`)— https://github.com/tommot20077/stock-web-v2-front-end/actions/runs/42648098145 |
| 結論 | ALL GREEN:Lint job 129583473557、Unit Tests job 129583473178、Integration Tests job 129584199208、E2E Tests job 129586115603、Browser E2E Tests job 129586950716,皆 success;GitGuardian success | ALL GREEN:`frontend` job 129266720313(Node 20,lint / test / build)、`Browser E2E (real backend)` job 129268188684(約 4.7 分,< 30 分 timeout),皆 success |
| artifact | `unit-test-reports` 13729657168、`integration-test-reports` 13729926961、`e2e-test-reports` 13729139308、`browser-e2e-artifacts` 13730675012(四者經 `gh api .../artifacts/<id>` 核對 `workflow_run.id = 42741526488`、`head_sha = ee4094c`,下載檔大小一致) | `browser-e2e-artifacts` 13688593217 |
| 同 head 的 push run | 42741518476:Lint / Unit / Integration / E2E success;Browser E2E 依設計在 push 事件 skipped | (前端 workflow 只在 develop 的 push / PR 觸發,feature push 不跑) |

縮寫:**BE-PR** = 後端 run 42741526488;**FE-PR** = 前端 run 42648098145。報告檔路徑以 artifact 內相對路徑記錄。

本機閘門(2026-11-09,開 PR 前,僅作輔證):後端 `spotless:check` OK、`./mvnw -B test --fail-at-end` 993 tests 0 failures 0 errors;前端 lint 0 errors(40 個既有 warning)、`npm test` 1337 passed、build OK、`VITE_DATA_MODE=api` test 1337 passed + build OK、`layout.spec.ts` 101 passed、`playwright --list` 149 tests / 12 files。

---

## SC1 — 後端 Maven 覆蓋(VER-01)

以 05-RESEARCH §Q8 SC1 草稿為底。狀態欄:「已涵蓋」= Phase 5 前已有測試;「缺口 Gx 已補」= Wave 1 新增測試且 CI 綠;「Wave 2」= 尚未處理。

| SC1 項目 | 測試檔#方法 | 狀態 | plan / commit | CI run id + 報告檔 |
|----------|-------------|------|---------------|--------------------|
| auth cookie 設定與 HttpOnly | `BrowserAuthFlowIT#registerSetsHttpOnlyAuthCookiesAndOmitsTokenBody`、`#loginSetsHttpOnlyAuthCookiesAndOmitsTokenBody` | 已涵蓋 | Phase 5 前既有 | BE-PR Integration job 129584199208;`integration-test-reports` → `stock-start/target/failsafe-reports/TEST-dowob.xyz.stockwebv2.start.BrowserAuthFlowIT.xml` tests=14 failures=0 errors=0 |
| cookie 屬性 SameSite / Path / Max-Age / Secure / Domain、XSRF 非 HttpOnly | `BrowserAuthCookieServiceTest`(4 個 `@Test`:access `Max-Age=900`、refresh `Max-Age=1209600`、`SameSite=Lax`、`Path=/`、secure / domain 依設定、`XSRF-TOKEN` 非 HttpOnly、`clearAuthCookies` `Max-Age=0`) | **缺口 G1 已補** | 05-04 / `c50e47a` | BE-PR Unit Tests job 129583473178;`unit-test-reports` → `stock-module-user/target/surefire-reports/TEST-dowob.xyz.stockwebv2.user.service.BrowserAuthCookieServiceTest.xml` tests=4 failures=0 errors=0 |
| cookie 認證 `/me` | `BrowserAuthFlowIT#meAcceptsAccessCookieWithoutBearerHeader` | 已涵蓋 | 既有 | BE-PR job 129584199208;`BrowserAuthFlowIT.xml`(14/0/0,testcase 存在) |
| CSRF bootstrap / 缺 header 403 信封 | `BrowserAuthFlowIT#csrfBootstrapSetsReadableXsrfCookie`、`#cookieLogoutWithoutCsrfHeaderReturnsCsrfErrorEnvelope`、`#refreshWithoutCsrfHeaderReturnsCsrfErrorEnvelope`;`WsTicketCookieCsrfIT`、`CashEntryApiIT`、`DividendApiIT`、`ShareAdjustmentApiIT`、`UserPreferencesIT`、`WatchlistApiIT` 的 CSRF 案例 | 已涵蓋 | 既有 | BE-PR job 129584199208;`stock-start/target/failsafe-reports/`:`WsTicketCookieCsrfIT.xml` 2/0/0、`CashEntryApiIT.xml` 32/0/0、`DividendApiIT.xml` 10/0/0、`ShareAdjustmentApiIT.xml` 13/0/0、`UserPreferencesIT.xml` 9/0/0、`WatchlistApiIT.xml` 22/0/0 |
| `POST /trades` cookie 認證無 CSRF → 403 | `TradingApiIT#cookieAuthenticatedTradeWithoutCsrfIsRejected`(403 `AUTH_CSRF_TOKEN_INVALID`;cookie GET `/trades` `totalElements=0`、`transactions` 0 列) | **缺口 G2 已補** | 05-04 / `f617f4d` | BE-PR job 129584199208;`TEST-dowob.xyz.stockwebv2.start.TradingApiIT.xml` tests=37 failures=0 errors=0(testcase 存在) |
| 有效 access cookie 下 login / register 需 CSRF(D-03 契約補述的證據) | `BrowserAuthFlowIT#loginWithValidAccessCookieRequiresCsrf`、`#registerWithValidAccessCookieRequiresCsrf`;反向 `#staleAccessCookieDoesNotBlockReLoginOnPublicEndpoint` | **缺口 G3 已補** | 05-04 / `f617f4d`(契約 05-05 / `d19557e`) | BE-PR job 129584199208;`BrowserAuthFlowIT.xml` 14/0/0(三個 testcase 皆存在) |
| CORS | `CorsIT#configuredFrontendOriginCanPreflightProtectedEndpoints`、`#configuredFrontendOriginCanPreflightUnsafeEndpointsWithCredentialHeaders`、`#unknownOriginDoesNotReceiveAllowOriginHeader` | 已涵蓋(非 preflight 回應的 `Access-Control-Allow-Credentials` 未測,列 VER-01 人工檢視) | 既有 | BE-PR job 129584199208;`CorsIT.xml` 3/0/0 |
| refresh rotation / replay | `BrowserAuthFlowIT#refreshWithMatchingCsrfRotatesAuthCookiesAndOmitsTokenBody`、`#replayedRefreshReturnsInvalidRefreshEnvelopeAndClearsCookies`、`#invalidRefreshReturnsInvalidRefreshEnvelopeAndClearsCookies`;`RefreshTokenRotationIT`(2 條) | 已涵蓋 | 既有 | BE-PR job 129584199208;`BrowserAuthFlowIT.xml` 14/0/0、`RefreshTokenRotationIT.xml` 2/0/0 |
| cookie 模式登出後舊 access cookie 失效 | `BrowserAuthFlowIT#cookieLogoutRevokesAccessCookieForMe`(舊 `stock_access` 打 `/me` → 401 `AUTH_INVALID_CREDENTIALS` + `meta.traceId`);bearer 版 `LogoutInvalidatesAccessTokenIT#accessTokenIsRejectedAfterLogout` | **缺口 G4 已補** | 05-04 / `f617f4d` | BE-PR job 129584199208;`BrowserAuthFlowIT.xml` 14/0/0、`LogoutInvalidatesAccessTokenIT.xml` 1/0/0;瀏覽器面另見 §SC3 J0 step 7 |
| 401 信封 | `TradingApiIT#tradingEndpointsRequireAuthentication`、`AuthFlowIT#meRejectsMalformedBearerTokenWithApiResponse`、`#logoutRequiresAuthentication` | 已涵蓋 | 既有 | BE-PR job 129584199208;`TradingApiIT.xml` 37/0/0、`AuthFlowIT.xml` 7/0/0 |
| 403 信封(方法層拒絕) | `MethodSecurityDenialIT#methodLevelDenialReturnsForbidden`(追加 `success=false`、`error.code=AUTH_FORBIDDEN`、`meta.traceId`) | **缺口 G5 已補** | 05-04 / `f617f4d` | BE-PR job 129584199208;`MethodSecurityDenialIT.xml` tests=1 failures=0 errors=0 |
| trading 冪等 | `TradingApiIT#concurrentSameKeyCreatesExactlyOneTrade`、`#rejectedTradeDoesNotBurnTheIdempotencyKey`、`#sameIdempotencyKeyReturnsExistingTradeAndAppliesHoldingOnce`、`#sameKeyWithDifferentPayloadIsRejectedAsReuse`、`#sameKeyWithOnlyNoteChangedReturnsExistingTrade`、`#sameKeyAcrossDifferentUsersCreatesSeparateTrades`、`#missingIdempotencyKeyHeaderReturnsFieldAwareValidationError`、`#blankIdempotencyKeyIsRejected`、`#oversizedIdempotencyKeyIsRejectedWithValidationError`;`TransactionsIdempotencyIT`(8);`TradePayloadMatcherTest`(13) | 已涵蓋(「字串金額重送 `"10"` vs `10.00000000` → 同一筆」列 Wave 2) | 既有 | BE-PR:Integration job 129584199208 `TradingApiIT.xml` 37/0/0、`TransactionsIdempotencyIT.xml` 8/0/0;Unit job 129583473178 `stock-module-trading/.../TEST-dowob.xyz.stockwebv2.trading.domain.TradePayloadMatcherTest.xml` 13/0/0 |
| (附)未來 `executedAt` 400 帶 `fields.executedAt`(D-02a 後端面) | `TradingApiIT#futureExecutedAtIsRejectedButBackfillIsAllowed`(`$.error.fields.executedAt = "must not be in the future"`);`TradingServiceTest#futureExecutedAtErrorNamesTheField`、`#futureExecutedAtWithinToleranceIsAccepted` | Wave 1 新增(D-02a) | 05-09 / `4255acb` | BE-PR:`TradingApiIT.xml` 37/0/0;Unit job `stock-module-trading/.../TEST-dowob.xyz.stockwebv2.trading.service.TradingServiceTest.xml` 51/0/0 |
| (附)access TTL 一致性守衛(D-22) | `AccessTokenTtlConsistencyGuardTest`(4) | Wave 1 新增 | 05-05 / `eb0bc29` | BE-PR Unit job 129583473178;`stock-module-user/.../TEST-dowob.xyz.stockwebv2.user.service.AccessTokenTtlConsistencyGuardTest.xml` 4/0/0 |
| BigDecimal 字串化(Q2:ArchUnit + 行為測試 + 精度 IT) | — | **缺口 G6 — Wave 2(05-13 ~ 05-15)** | 尚未執行(05-13 ~ 05-15 PLAN) | 無 CI 證據(待 Wave 2 PR) |

---

## SC2 — 前端 Vitest / type-check / build(VER-02)

證據來源:FE-PR `frontend` job 129266720313 的 log(`npm run lint` → `✖ 40 problems (0 errors, 40 warnings)`;`npm test` → `Test Files 65 passed (65)`、`Tests 1337 passed (1337)`;`npm run build` = `vue-tsc --noEmit && vite build` → `✓ built`)。下表檔案測試數取自同一 log 的 Vitest 逐檔輸出。

| SC2 項目 | 測試檔#方法 | 狀態 | plan / commit | CI run id + 報告檔 |
|----------|-------------|------|---------------|--------------------|
| API client 既有行為(credentials、`meta.traceId`、CSRF bootstrap / header / 403、refresh 單飛 / replay、分頁信封、非 JSON 502、壞 JSON 200) | `src/services/apiClient.test.ts`(既有 28 條) | 已涵蓋 | 既有 | FE-PR job 129266720313 log:`✓ src/services/apiClient.test.ts (34 tests)` |
| `fetch` reject → `NETWORK_ERROR`(D-26)、AbortError 原樣上拋、CSRF bootstrap 階段、兩並行請求各自 `NETWORK_ERROR` 且不呼叫 refresh | `apiClient.test.ts#apiClient transport 網路錯誤正規化(D-26)`、`#gives each concurrent request its own NETWORK_ERROR and never calls auth/refresh (VER-02 concurrency)`;`baseCurrencyErrorKey.test.ts#transport 正規化的 NETWORK_ERROR(D-26)→ baseCurrencyErrNetwork…` | **缺口 F1 已補** | 05-02 / `be8a0d1` | FE-PR job 129266720313 log:`apiClient.test.ts (34 tests)`、`baseCurrencyErrorKey.test.ts (8 tests)` 綠;瀏覽器面見 §SC5 abort 6 列 |
| 5xx + 空 body(`application/json` → `INVALID_JSON_RESPONSE`;無 content-type → `HTTP_ERROR`、`requestId null`) | `apiClient.test.ts#apiClient 5xx 空 body(SC2 F2)` | **缺口 F2 已補** | 05-02 / `2465adb` | FE-PR job 129266720313 log:`apiClient.test.ts (34 tests)` 綠 |
| auth store(`authSession.ts`) | `src/services/authSession.test.ts`(7) | 已涵蓋 | 既有 | FE-PR job 129266720313 log:`authSession.test.ts (7 tests)` |
| runtime mode | `src/services/runtimeDataMode.test.ts`(3)、`src/api-adapter-wiring.test.ts`(17) | 已涵蓋 | 既有 | FE-PR job 129266720313 log:`runtimeDataMode.test.ts (3 tests)`、`api-adapter-wiring.test.ts (17 tests)` |
| portfolio adapters(現況) | `portfolioApi.test.ts`(31)、`cashApi.test.ts`(21)、`holdingEventApi.test.ts`(28)、`portfolioRevision.test.ts`(26) | 已涵蓋(現況) | 既有 | FE-PR job 129266720313 log:四檔皆 ✓,測試數如左 |
| portfolio adapters wire → domain mapper 與精度案例 | — | **缺口 F3 — Wave 2(05-13 ~ 05-15)** | 尚未執行 | 無 CI 證據(待 Wave 2 PR) |
| trading adapter | `src/services/tradingApi.test.ts`(13) | 已涵蓋(Wave 2 payload 改字串) | 既有 | FE-PR job 129266720313 log:`tradingApi.test.ts (13 tests)` |
| type-check / build | `npm run build` = `vue-tsc --noEmit && vite build` | 已涵蓋 | 既有 | FE-PR job 129266720313 log:`> vue-tsc --noEmit && vite build` → `✓ built in 1.31s` |
| proxy 設定(D-21:預設 11180、env 覆寫 dev / preview、`/ws` `ws === true`) | `src/viteConfig.test.ts`(2 → 4) | **缺口 F4 已補** | 05-03 / `6ad00ac` | FE-PR job 129266720313 log:`✓ src/viteConfig.test.ts (4 tests)`;執行面:兩邊 Browser E2E 經 preview proxy 指向 8080 跑完 149 tests(§SC3 / §SC5) |
| mock 邊界 | `src/mockStoreBoundary.test.ts`(4) | 已涵蓋 | 既有 | FE-PR job 129266720313 log:`mockStoreBoundary.test.ts (4 tests)` |
| (附)mock 哨兵守門(D-20) | `src/mockSentinels.test.ts#mock 哨兵守門(SC5 / D-20)`(4,含偵測規則自我驗證) | Wave 1 新增 | 05-02 / `2465adb` | FE-PR job 129266720313 log:`✓ src/mockSentinels.test.ts (4 tests)` |
| (附)`fields.executedAt` 綁到 `ticket-field-error-executedAt`(D-02a 前端面)、`useNow` 驅動未來判斷(D-02d) | `OrderTicket.test.ts#OrderTicket 成交時間 — 後端未來時間欄位錯誤綁定(Phase 5 D-02a / 05-09)`、`#OrderTicket 成交時間 — useNow 驅動上限與未來判斷(Phase 5 D-02d)`;`CashEntryDialog.test.ts#CashEntryDialog — useNow 驅動發生時間上限與未來判斷(Phase 5 D-02d)` | Wave 1 新增 | 05-11 / `884a460`、`6a5986c` | FE-PR job 129266720313 log:`OrderTicket.test.ts (85 tests)`、`CashEntryDialog.test.ts (52 tests)` |
| (附)`--danger` 固定錯誤色與漲跌底色對調(D-02c / D-28) | `contrastTokens.test.ts#--danger 固定錯誤色(D-28)`、`#錯誤樣式不得使用漲跌色(D-28)`、`#漲跌底色隨紅漲綠跌對調(D-02c)` | Wave 1 新增 | 05-10 / `53dfaf7`、`435a34a`、`82dfc18` | FE-PR job 129266720313 log:`✓ src/contrastTokens.test.ts (93 tests)` |

---

## SC3 / SC4 — 契約全程 journey J0(VER-03)

- 測試:`vue-app/e2e/tests/contract-journey.spec.ts`,title `J0 登入 → /me → 讀持倉 → 記錄手動交易 → 三頁重讀 → 登出(契約全程) @smoke`,真後端、無 `page.route`。plan / commit:05-01 / `3b1c62c`、`39234d6`、`41469e1`。
- 結果:**passed(expected)**,兩邊皆是 —
  - BE-PR Browser E2E Tests job 129586950716(後端 `ee4094c` + 前端同名分支),`browser-e2e-artifacts` 13730675012 的 Playwright report:total 149 / expected 149 / unexpected 0 / flaky 0 / skipped 0。
  - FE-PR `Browser E2E (real backend)` job 129268188684(前端 `41469e1` + 後端同名分支 `81a467d`),`browser-e2e-artifacts` 13688593217:total 149 / expected 149 / unexpected 0 / flaky 0。
- Playwright 只在 8 個 `test.step` 全部通過時才判 passed,所以下列每個 step 的斷言都由這兩個 run 證明。

| # | J0 step(`test.step` 名稱) | 斷言 | SC | CI run id |
|---|------|------|----|-----------|
| 1 | 登入 | UI 登入,`POST /api/v1/auth/login` 200;`header-session-identity` 顯示註冊 email;phase 於 login 200 回應事件切為 authed | SC3 | BE-PR 42741526488 / job 129586950716;FE-PR 42648098145 / job 129268188684 |
| 2 | auth cookie 為 httpOnly 且 JS 讀不到 | `context.cookies()`:`stock_access` / `stock_refresh` 為 `httpOnly`、`sameSite Lax`、`path /`;`XSRF-TOKEN`(若存在)非 httpOnly;`document.cookie` 不含兩個 auth cookie;localStorage / sessionStorage 不含 token 值 | SC4 | 同上 |
| 3 | /me 回傳目前使用者 | `GET /api/v1/me` 200、`success === true`、`data.email` = 註冊帳號 email | SC3 | 同上 |
| 4 | 交易前讀 Overview 與 Positions | Positions holdings 200 → `positions-holdings-empty`、無 AAPL 列;Overview summary 200 → 瀏覽器收到 `holdingCount === 0`、`overview-kpi` 可見,`page.request` 重讀也是 0 | SC3 | 同上 |
| 5 | 以 OrderTicket 記錄手動交易(已成交紀錄,不是委託單) | AAPL × 3;`POST /api/v1/trades` 恰一個非空 `idempotency-key`;`x-xsrf-token` = 同一請求 cookie 的 `XSRF-TOKEN`;帶 `stock_access`;回應 ok、`ticket-result-trade-id` 為 UUID、`ticket-result-qty` = 3 | SC3 / SC4 | 同上 |
| 6 | 交易後三頁重讀到新值 | Trades `trades-fresh-badge` 可見、首列含 AAPL;Positions AAPL 1 列、數量格 3、`GET /portfolio/holdings` `totalQuantity === 3`;Overview `holdingCount === 1`、`overview-recent-trades` 含 AAPL | SC3 | 同上 |
| 7 | 登出後 /me 401(cookie jar 與舊 cookie 兩種) | 登出 200 後 `GET /api/v1/me` 401、`success === false`、`error.code` 與 `meta.traceId` 為字串;out-of-band context 帶舊 Cookie header 也是同樣 401 信封(伺服器端撤銷) | SC4 | 同上;後端面另見 §SC1 G4 |
| 8 | 全程 /api/v1 請求符合瀏覽器 auth 契約(D-12) | (a) authed 非公開端點都帶 `stock_access`;(b) 每個 unsafe 請求 `x-xsrf-token` = 同請求 `XSRF-TOKEN` cookie;(c) 無 `Authorization: Bearer`、token 不出現在 URL / body;違規逐條列 `METHOD URL` | SC4 | 同上 |

---

## SC5 — 六頁 × 三種失敗(VER-03)

- 測試:`vue-app/e2e/tests/api-failure-states.spec.ts`,18 個 test,title 格式 `S5 <page> × <kind>:錯誤 / 重試可見、無 mock 哨兵、放行後顯示後端真值 @extended`。plan / commit:05-02 / `16f3289`(tracer)、`2171269`(擴充為 18 組);apiClient 正規化 `be8a0d1`(D-26)。
- 失敗種類:`abort` = `route.abort('failed')` → 預期 `NETWORK_ERROR`、不渲染 trace-id;`envelope5xx` = 503 `ApiResponse`(code `SERVICE_UNAVAILABLE`,測試自訂注入值)→ 預期 code 與 trace-id `e2e-sc5-<page>` 取自信封;`nonEnvelope502` = 502 `text/html` → 預期 `HTTP_ERROR`、不渲染 trace-id、畫面無 `Bad Gateway`。
- 每列共同斷言:錯誤區塊 / code / 重試鈕可見;目標區塊 locator 內無 mock 哨兵(`MOCK_SENTINELS`);改 `route.continue()` 放行後按重試,顯示後端種下的真值。
- 兩個 run 的 18 個 S5 test **全部 passed**:BE-PR Browser E2E job 129586950716(artifact 13730675012)、FE-PR Browser E2E job 129268188684(artifact 13688593217)。

| # | 頁 | 注入端點 | 失敗 | 預期 code / trace-id | 放行後真值 | BE-PR 42741526488 | FE-PR 42648098145 |
|---|----|---------|------|---------------------|-----------|------|------|
| 1 | overview | `GET /api/v1/portfolio/summary` | abort | `NETWORK_ERROR` / 無 | `overview-kpi` 可見、錯誤卡消失、無哨兵 | passed | passed |
| 2 | overview | 同上 | envelope5xx | `SERVICE_UNAVAILABLE` / `e2e-sc5-overview` | 同上 | passed | passed |
| 3 | overview | 同上 | nonEnvelope502 | `HTTP_ERROR` / 無 | 同上 | passed | passed |
| 4 | positions | `GET /api/v1/portfolio/holdings` | abort | `NETWORK_ERROR` / 無 | `positions-row` 含 AAPL、數量 3 | passed | passed |
| 5 | positions | 同上 | envelope5xx | `SERVICE_UNAVAILABLE` / `e2e-sc5-positions` | 同上 | passed | passed |
| 6 | positions | 同上 | nonEnvelope502 | `HTTP_ERROR` / 無 | 同上 | passed | passed |
| 7 | trades | `GET /api/v1/portfolio/activities` | abort | `NETWORK_ERROR` / 無 | `trades-row` 含 AAPL | passed | passed |
| 8 | trades | 同上 | envelope5xx | `SERVICE_UNAVAILABLE` / `e2e-sc5-trades` | 同上 | passed | passed |
| 9 | trades | 同上 | nonEnvelope502 | `HTTP_ERROR` / 無 | 同上 | passed | passed |
| 10 | cash | `GET /api/v1/portfolio/cash-entries` | abort | `NETWORK_ERROR` / 無 | `cash-entries-row` 含備註 `sc5-seed` 1 列 | passed | passed |
| 11 | cash | 同上 | envelope5xx | `SERVICE_UNAVAILABLE` / `e2e-sc5-cash` | 同上 | passed | passed |
| 12 | cash | 同上 | nonEnvelope502 | `HTTP_ERROR` / 無 | 同上 | passed | passed |
| 13 | watchlist | `GET /api/v1/watchlists` | abort | `NETWORK_ERROR` / 無 | `watchlist-row` 含 AAPL 1 列 | passed | passed |
| 14 | watchlist | 同上 | envelope5xx | `SERVICE_UNAVAILABLE` / `e2e-sc5-watchlist` | 同上 | passed | passed |
| 15 | watchlist | 同上 | nonEnvelope502 | `HTTP_ERROR` / 無 | 同上 | passed | passed |
| 16 | settings | `GET /api/v1/me` | abort | `NETWORK_ERROR` / 無 | `settings-base-currency` = `EUR`(種子刻意不用預設 TWD) | passed | passed |
| 17 | settings | 同上 | envelope5xx | `SERVICE_UNAVAILABLE` / `e2e-sc5-settings` | 同上 | passed | passed |
| 18 | settings | 同上 | nonEnvelope502 | `HTTP_ERROR` / 無 | 同上 | passed | passed |

---

## 一致性項目

| 決策 | 內容 | 證據 |
|------|------|------|
| D-02a | 未來 `executedAt` → 400 帶 `fields.executedAt`,前端綁到欄位錯誤 | 後端 05-09 / `4255acb`:BE-PR Integration job 129584199208 `TradingApiIT.xml` 37/0/0(`futureExecutedAtIsRejectedButBackfillIsAllowed` 含 `fields.executedAt`);Unit job `TradingServiceTest.xml` 51/0/0。前端 05-11 / `884a460`:FE-PR job 129266720313 `OrderTicket.test.ts (85 tests)` |
| D-02b | 觀察清單名稱 DB CHECK 擴充到 Unicode 空白類(V20) | 05-09 / `7d3d29c`:BE-PR job 129584199208 `WatchlistApiIT.xml` 22/0/0,含 `dbRejectsNameMadeOnlyOfNoBreakSpaces`、`dbAcceptsNameWithInnerNoBreakSpace` → V20 已由 Flyway 在 Testcontainers 套用 |
| D-02c(D-28) | 錯誤樣式改固定色 `--danger`;漲跌底色隨紅漲綠跌對調 | 05-10 / `53dfaf7`、`435a34a`、`82dfc18`:FE-PR job 129266720313 `contrastTokens.test.ts (93 tests)`;本機 `layout.spec.ts` 101 passed(輔證)。視覺觀感未自動驗證 → 見 §VER-01 |
| D-02d | OrderTicket / CashEntryDialog 未來時間判斷改 `useNow` | 05-11 / `884a460`、`6a5986c`:FE-PR job 129266720313 `OrderTicket.test.ts (85 tests)`、`CashEntryDialog.test.ts (52 tests)` |
| D-03 | 文件一致性(`architecture.md` 持久層用詞、`browser-auth-contract.md` CSRF 補述、`application-demo.yaml` 檔頭、JwkKeyConverter 指令) | 05-05 / `d19557e`、`51f69ed`(acceptance grep 見 05-05-SUMMARY coverage D3 / D4)。屬文件變更,無 CI 斷言;CSRF 補述的行為證據為 §SC1 G3(BE-PR `BrowserAuthFlowIT.xml` 14/0/0) |
| D-04 / D-29 | market-data 11 個 IT 共用 TimescaleDB / Kafka / Redis 單例容器,failsafe 亂序 | 05-07 / `cad83c4`、`e90d406`;05-08 / `4a2ae1e`、`3ed5692`、`59500cc`。BE-PR Integration job 129584199208 log:第 1812 行 `Tests will run in random order. To reproduce ordering use flag -Dfailsafe.runOrder.random.seed=176983128627`;11 個 IT 全部 Failures 0 Errors 0(MarketControllerKlinesIT 4、MarketPriceRepositoryIT 13、KafkaConfigIT 1、PriceWriterRetryIT 1、MarketDataMigrationIT 6、BackfillIdempotencyServiceIT 5、WsTicketServiceIT 9、BackfillJobIT 3、MarketDataHealthIndicatorIT 2、PriceWriterConsumerIT 5、MarketDataIngestServiceIT 4);log 1830–4745 行內 `timescale/timescaledb:2.27.2-pg18`、`apache/kafka:4.2.0`、`redis:8.8.0-alpine` 各啟動 1 次。註:market-data failsafe-reports 未上傳為 artifact(CI 上傳路徑只含 stock-start),此列證據僅來自 job log。T-05-15(預設 topic / group 不變):BE-PR E2E job `WsSubscribeReceiveE2E` 綠 + Unit job `KafkaTopicPlaceholderTest.xml` 6/0/0 |
| D-14 | `run-e2e` 無參數跑全部 tag;CI step `Run Browser E2E (all tags)` | 05-03 / `87eeff7`(前端)、`cf1459a`(後端)。BE-PR Browser E2E job 129586950716:149 tests 全 expected,其中 @extended 27 個有執行;FE-PR job 129268188684 同為 149 / 149 |
| D-15 | 前端 CI 新增真後端 browser E2E,checkout 後端同名分支,只用預設 `github.token` | 05-03 / `63afeb6`。FE-PR `Browser E2E (real backend)` job 129268188684 success(約 4.7 分);`Resolve backend ref` log:「後端使用對應分支:claude/epic-curie-lj8alo」(checkout 到 `81a467d`,早於 develop 合併);只使用預設 `github.token` |
| D-21 | Vite proxy 目標 `VITE_API_PROXY_TARGET`,預設 11180,Playwright 設 8080 | 05-03 / `6ad00ac`:FE-PR job 129266720313 `viteConfig.test.ts (4 tests)`;兩邊 Browser E2E 經 preview proxy 跑完 149 tests。WS:`/ws` 用 `http://` target + `ws: true`,watchlist-live 等 WS 案例在 149 passed 之內,不需改 `ws://`(05-03 留待事項 3 結案) |
| D-22 | JWT access TTL 預設 PT15M,`AccessTokenTtlConsistencyGuard` 強制與 cookie TTL 相等 | 05-05 / `eb0bc29`:BE-PR Unit job 129583473178 `AccessTokenTtlConsistencyGuardTest.xml` 4/0/0、`BrowserAuthCookieSecureGuardTest.xml` 3/0/0;Integration job BUILD SUCCESS 且 E2E job 21 份報告全綠 → 所有帶守衛的 ApplicationContext 正常啟動 |
| D-23 / D-27 | 7 個 `*E2ETest` + 3 個抽象基底改名為 `*E2E`;ArchUnit 命名守門不豁免 abstract | 05-06 / `c7d6e22`、`1f3c30a`。BE-PR Unit job 129583473178:`unit-test-reports` 不含任何 `*E2E.xml` / `*E2ETest.xml`;`stock-start/target/surefire-reports/TEST-dowob.xyz.stockwebv2.start.E2eNamingRulesTest.xml` 2/0/0。E2E job 129586115603:`e2e-test-reports` 21 份 `TEST-dowob.xyz.stockwebv2.start.e2e.*E2E.xml` 全部 failures=0 errors=0,改名的 7 份 `WsAuthFlowE2E` 4、`WsConnectionLimitE2E` 1、`WsSubscribeReceiveE2E` 1、`WsSubscriptionLimitE2E` 1、`WsTokenRevocationE2E` 1、`MarketRestApiE2E` 4、`BackfillAdminFlowE2E` 3 皆綠 |

### 註記

- **develop 合併(PR #54 映像版本)**:`f12d909` `chore(05-12): 同步 develop(測試中介軟體版本對齊與 Jacoco 棘輪)` 把 develop 合進本分支;BE-PR Integration job 啟動的容器映像(`timescale/timescaledb:2.27.2-pg18`、`apache/kafka:4.2.0`、`redis:8.8.0-alpine`)即依 develop PR #54 的版本。文件對齊見 `180a652`。
- **stock-start Jacoco 下限調整(Yuan 核准)**:第一次 PR run 42649422483(head `180a652`)只因 stock-start ratchet 失敗(line 0.87 < 0.88、branch 0.64 < 0.66),原因是 05-06 把 7 個 E2E 移出 `verify`。Yuan 於 2026-11-10 核准把 stock-start 下限調為 0.87 / 0.64(`ee4094c`,`testing-standards.md` 表格已註記)。BE-PR 42741526488 Integration job 顯示所有模組「All coverage checks have been met」。

---

## develop

> 待 Yuan 合併 Wave 1 PR 組(後端 PR #55、前端 PR #22)後,於 05-12 Task 3 補上:develop 上兩 repo 的 CI run id、各 job 結論與 merge commit SHA(D-25 閘門)。
> 目前狀態:**待定(尚無 develop CI 證據)**。

---

## VER-01 unclassified edge 人工檢視

以下項目沒有被歸入 SC1 / SC2 任一列,或只有部分自動證據,留給 Yuan 在 05-12 Task 3 勾選(勾選 = 已檢視並接受現況或已排入後續處理)。

- [ ] **紅漲綠跌視覺觀感**(05-10 coverage D4,`human_judgment: true`):源碼守門只證明 token 引用正確;`--danger` 錯誤框與 `color-mix` 漲跌膠囊在深 / 淺主題 × 紅漲 / 綠漲四種組合下是否易讀、語意是否清楚,需人眼確認。證據:05-10-SUMMARY coverage D4(`verification: []`)。
- [ ] **未登入時掛載頁面、登入後不重讀的 UX 缺陷**(`deferred-items.md`,05-01 發現):API mode 未登入時 Overview 進站就打 summary / trades / watchlists 並停在 `AUTH_REFRESH_TOKEN_INVALID`,UI 登入後已掛載頁面不重讀;J0 目前以切頁繞過。證據:`deferred-items.md`、前端 `41469e1`。
- [ ] **CORS 實際(非 preflight)回應的 `Access-Control-Allow-Credentials`** 未測(05-RESEARCH §Q8 標為選做)。證據:BE-PR `CorsIT.xml` 只有 3 個 preflight / origin 案例。
- [ ] **`meta.traceId` 只驗 `exists()`**:05-04 新增的 403 / 401 信封斷言只證明信封形狀,不證明 traceId 與 response header 一致(既有 IT 同寫法)。證據:05-04-SUMMARY 補充說明。
- [ ] **market-data failsafe 報告未上傳 artifact**:D-04 / D-29 的 11 個 IT 結果只能從 Integration job 129584199208 的 log 讀到;是否要把 `stock-module-market-data/target/failsafe-reports/` 加入 CI 上傳路徑。
- [ ] **D-29 亂序只有單一 seed 的 CI 證據**(`176983128627`);05-08 建議的「兩次不同 seed 皆綠」尚未有第二次 run。
- [ ] **前端 Browser E2E 的後端版本**:FE-PR job 129268188684 checkout 的後端是 `81a467d`(develop 合併前),非 PR #55 head `ee4094c`;`ee4094c` 的跨 repo 證據來自 BE-PR Browser E2E job 129586950716。develop 合併後(Task 3)兩邊應再對齊一次。
- [ ] **stock-start Jacoco 下限由 0.88 / 0.66 降為 0.87 / 0.64**(`ee4094c`,Yuan 已核准):是否另排工作把 stock-start 的 IT 覆蓋率補回原棘輪。
- [ ] **SC5 envelope5xx 的 `SERVICE_UNAVAILABLE` 是測試自訂 code**,不存在於後端 `ErrorCode`;證明的是 apiClient 原樣帶出信封 code / `meta.traceId`,不是後端真實 503 的形狀。證據:`api-failure-states.spec.ts` FAILURES 註解、05-02-SUMMARY key-decisions。
- [ ] **D-03 純文件變更無自動守門**(`architecture.md`、`application-demo.yaml` 檔頭、JwkKeyConverter 註解):僅有 05-05 的 acceptance grep,無 CI 斷言。
- [ ] **前端 lint 40 個既有 warning**(`no-explicit-any` ratchet):FE-PR job 129266720313 `✖ 40 problems (0 errors, 40 warnings)`,Wave 1 未新增也未減少。
- [ ] **`.planning/codebase/CONCERNS.md:153` 仍寫「`run-e2e.sh` 預設只跑 `@smoke`」**(05-03-SUMMARY 備註):codebase map 快照過期,留待下次 map-codebase。
