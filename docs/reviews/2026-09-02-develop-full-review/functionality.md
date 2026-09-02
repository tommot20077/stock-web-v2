# develop 功能維度審查(2026-09-02)

- 後端:`origin/develop` @ 334cb34(PR #20 merge commit)
- 前端:`feature/phase-04-manual-trade-creation` @ fde9968(含 F-1/F-2/F-3 與 flushAsync 修正;等同即將合併的 develop)
- 方法:靜態親讀程式碼與測試,未跑測試、未改檔。所有「✓」皆指名測試或 file:line。

## 0. 結論

- **milestone v1.0 需求完成度:34 / 36 有可指名證據(≈ 94%)**;未達:VER-03(跨 repo 瀏覽器交易流程)、VER-04 部分(portfolio/trading DTO 文件)。
- Phase 1–4 的 Success Criteria 全部有實作與自動化證據;Phase 04.1(四條後端資料缺口)與 Phase 5 尚未開始。
- **HIGH:0;MEDIUM:4;LOW:8。** 沒有會讓「註冊 → 登入 → 讀 portfolio → 記錄交易 → refetch → 登出」流程壞掉的缺陷。

## 1. 需求對照表(PROJECT.md Active × ROADMAP Phase 1–4)

| Req | 狀態 | 證據 |
|-----|------|------|
| AUTH-01 註冊不讀 refresh token | ✓ 實作+測試 | `BrowserAuthFlowIT.registerSetsHttpOnlyAuthCookiesAndOmitsTokenBody`;Playwright `auth.spec.ts` A1 |
| AUTH-02 登入設 cookie | ✓ | `BrowserAuthFlowIT.loginSetsHttpOnlyAuthCookiesAndOmitsTokenBody` |
| AUTH-03 `/me` 還原 | ✓ | `authSession.test.ts`;Playwright A2 |
| AUTH-04 refresh 一次 | ✓ | `apiClient.ts:308-325`(replay 一次、refresh path 不重試);`apiClient.test.ts`;`BrowserAuthFlowIT.refreshWithMatchingCsrfRotatesAuthCookiesAndOmitsTokenBody` |
| AUTH-05 登出撤銷+清 cookie | ✓ | `AuthController.java` logout(cookie 路徑 revoke + `authService.logout(owner)` + clear);`LogoutInvalidatesAccessTokenIT`;Playwright A3 |
| AUTH-06 401/403 信封一致 | ✓ | `AuthFlowIT.meRejectsMalformedBearerTokenWithApiResponse`;`MethodSecurityDenialIT.methodLevelDenialReturnsForbidden` |
| AUTH-07 bearer path 獨立 | ✓ | `AuthFlowIT.tokenEndpointReturnsBearerTokensAndDoesNotSetBrowserCookies` |
| SEC-01 httpOnly cookie | ✓ | `BrowserAuthFlowIT.assertAuthCookieHeaders` |
| SEC-02 double-submit CSRF | ✓(見備註) | `cookieLogoutWithoutCsrfHeaderReturnsCsrfErrorEnvelope`、`refreshWithoutCsrfHeaderReturnsCsrfErrorEnvelope`。**備註**:`POST /api/v1/trades` 的 cookie+CSRF 路徑沒有獨立 IT(`TradingApiIT` 全走 bearer),04-13 已誠實標為 accept,歸 Phase 5 |
| SEC-03 CSRF token 契約 | ✓ | `csrfBootstrapSetsReadableXsrfCookie`;`apiClient.ts:179`(bootstrap `GET /csrf`) |
| SEC-04 CORS | ✓ | `CorsIT` 3 條 |
| SEC-05 安全測試矩陣 | ✓ | `BrowserAuthFlowIT`(11)+`AuthFlowIT`(7)+`MethodSecurityDenialIT`+`CorsIT` |
| FAPI-01~07 | ✓ | `apiClient.test.ts`、`authSession.test.ts`、`api-adapter-wiring.test.ts`(transport 唯一邊界、credentials、CSRF header、信封解析、401 一次、無 token 儲存、不靜默退 mock) |
| FAPI-08 無效 mode fail fast | ✓ | `runtimeDataMode.test.ts`(`RuntimeDataModeError`);`App.vue:82`(REQUIREMENTS.md 仍未勾,狀態檔落後) |
| PORT-01/02/03/05 | ✓ | `Overview.test.ts`、`Positions.test.ts`、`Trades.test.ts`(loading/empty/error/retry/traceId 各有 case) |
| PORT-04 mock 保留、API 不碰 mock store | ✓(三頁) | 三頁以 `live` 分支;`api-adapter-wiring.test.ts`。**但其餘 7 頁仍直連 mock store,見 MEDIUM-1** |
| PORT-08 `/trades` 篩選排序 | ✓ | `TradingApiIT`:`typeFilterAppliesToItemsAndTotalElements`、`dateRangeFilterIsHalfOpen`、`sortByTotalBreaksTiesDeterministicallyById`、`invalidSortAndDirectionAreRejected` 等 12 條 |
| TRAD-01 manual executed trade | ✓ | `tradingApi.test.ts` payload;`TradingApiIT.buyThenSellUpdatesHoldingsAndPortfolioSummary` |
| TRAD-02 只送契約欄位 | ✓ | `OrderTicket.vue:1314` 附近的 payload 投影(7 欄);`OrderTicket.test.ts` payload key-set |
| TRAD-03 server-side 冪等 | ✓ | `TradingApiIT.concurrentSameKeyCreatesExactlyOneTrade`、`sameKeyWithDifferentPayloadIsRejectedAsReuse`、`rejectedTradeDoesNotBurnTheIdempotencyKey`;`TransactionsIdempotencyIT`(8) |
| TRAD-04 前端連點防護 | ✓ | `OrderTicket.vue` `submitting` 雙層守衛(`:disabled` + `submitTrade` 早退);`task4.test.ts` |
| TRAD-05 成交後三頁 refetch | ✓ | `portfolioRevision.ts`;`Overview/Positions/Trades.test.ts` post-trade refetch 系列 |
| TRAD-06 錯誤可理解且保留 code/traceId | ✓ | `OrderTicket.vue:1038-1053`(`SUBMIT_ERROR_COPY`)、`:1056-1061`(403 以 status 兜底)、`:1069-1077`(`FIELD_ERROR_COPY`);`ErrorHandlingIT`(traceId、header fields) |
| VER-01 Maven 覆蓋 | ✓ | 上列 IT 共 20 類 / 106 條 |
| VER-02 Vitest/型別/build | ✓ | 35 檔 377 tests;`vue-tsc` 於 build;CI `ci.yml` |
| VER-03 跨 repo 瀏覽器交易流程 | **✗ 未做** | Playwright 只有 `auth.spec.ts`(A1–A11)與 `backtest.spec.ts`(D1–D5);**沒有 create trade → refetch 的旅程**;04-13 Task 2 人工確認亦未執行 |
| VER-04 契約文件 | **部分** | `browser-auth-contract.md` 有 auth/CSRF/refresh/logout/401/403 與 Idempotency-Key;**portfolio/trading DTO(`HoldingDto`/`PortfolioSummaryDto`/`TradeDto`/`CreateTradeRequest`/`GET /trades` 參數)兩個 repo 都沒有文件**(前端 `mock-to-real-contract.md` 只寫 Backtest/Ops/AI Access) |

Phase 04.1 四條缺口(可用現金、日級損益、資產分類 JOIN、watchlist API)與 Phase 5 未開始,不計入本表。

## 2. 跨 repo 契約一致性

| 項目 | 結論 |
|------|------|
| `ApiResponse` / `ApiError` / `ApiMeta` | ✓ 同形(`apiTypes.ts:1-26` vs `stock-common/.../api/*.java`) |
| `PageResponse` ↔ `PaginatedResponse` | ✓ 五欄一致 |
| `TradeDto` | ✓ 九欄一致;`type` 後端 String、前端 `'BUY' \| 'SELL'`(後端 `TradeType` 只有兩值) |
| `HoldingDto` | ✓ 13 欄一致;`priceTime`/`lastUpdated` 可空 ✓ |
| `PortfolioSummaryDto` | ✓ 7 欄一致 |
| `AssetDto` | ✓ 14 欄;**`sector` DB 可空(`V1__foundation_schema.sql:27`)但前端型別 `string` 非可空** — 目前只有 mock 的 `Analytics.vue` 用 sector,無運行期影響(LOW-3) |
| `KlineDto` | ✓ 六欄皆字串(後端 `ToStringSerializer`);前端 `closeSeries` 為唯一轉換點 |
| `GET /assets` 參數 | ✓ `query/page/size`(`marketApi.ts:90-94` ↔ `AssetController.java:24-27`) |
| `GET /market/{symbol}/klines` 參數 | ✓ `interval/from/to/limit`(`marketApi.ts:101-106` ↔ `MarketController.java:119-124`) |
| `POST /trades` 請求 | ✓ 7 欄(`CreateTradeRequest`);`Idempotency-Key` header 必填;`fee` 現已鎖 8 位小數 |
| `GET /trades` 參數 | ✓ `symbol/type/dateFrom/dateTo/sort/direction/page/size`;前端只用 type/dateFrom/dateTo/sort/direction/page/size |
| error.code:後端會丟 vs 前端會認 | 前端 `SUBMIT_ERROR_COPY` 認 8 個後端 code + `NETWORK_ERROR`(前端合成);**`AUTH_FORBIDDEN`(後端 403 實際 code)不在表內,靠 `error.status === 403` 兜底(`OrderTicket.vue:1060`)→ 顯示正確**;表內 `ACCESS_DENIED`/`FORBIDDEN`/`AUTH_CSRF_TOKEN_MISSING` 是後端不存在的 code(死鍵,LOW-2)。`AUTH_RATE_LIMITED`(429)/`AUTH_ACCOUNT_LOCKED` 在 ticket 落到 `tradeErrUnknown`(但 login 路徑有專屬文案) |
| i18n | ✓ zh/en 各 176 key,零缺漏;24 個錯誤類文案雙語齊 |
| 數值序列化 | **不一致**:`KlineDto`/`LatestPriceDto` 以字串送 BigDecimal,`TradeDto`/`HoldingDto`/`PortfolioSummaryDto` 以 JSON number 送,前端型別 `number`。quantity 上限 10^9 × 8 位小數 = 最多 18 位有效數字,超過 double 的 15–17 位時前端顯示/比較可能失真(LOW-4) |
| WS 訊息 | 後端有 `WELCOME`/`SUB_ACK`/`ERROR`/`auth_expired` 與 subscribe/unsubscribe/pong(`MarketWebSocketHandler.java:380-418`、`ClientMessage.java`);**前端沒有任何 WebSocket client**(grep `new WebSocket|wsUrl|ws/ticket` 零命中)→ 後端 WS 推送目前無消費者(LOW-5) |
| `/me` | 後端 `MeResponse` 含內部 `Long id`,前端 `mapUser` **要求** `id: number`(`authApi.ts:44-51`)。architecture.md「外部 ID 一律 UUID」未被遵守(LOW-1) |

## 3. 使用者流程完整性(API mode)

| 步驟 | 後端 | 前端 | 自動化證據 | 備註 |
|------|------|------|-----------|------|
| 註冊 | `AuthController.register`(rate limit、audit、cookie) | `AuthPanel.vue` → `authApi.register` | `BrowserAuthFlowIT`、`AuthPanel.test.ts`、Playwright A1/A4/A5/A8 | — |
| 登入 | `AuthController.login` + lockout | `AuthPanel.vue` | `AuthRateLimitAndLockoutIT`、Playwright A7/A10 | — |
| `/me` 還原 | `AuthController.me` | `authSession.ts:124-163` | `authSession.test.ts`、Playwright A2 | — |
| Overview | `GET /portfolio/summary`、`GET /trades?size=5` | `Overview.vue`(D-14:只顯示 totalMarketValue/roi 兩張卡) | `Overview.test.ts` | **「今日損益」「可用現金」「資產配置 donut」「資產走勢圖」在 API mode 隱藏**(`Overview.vue:5,74,90`)— 04.1 缺口 |
| Positions | `GET /portfolio/holdings` | `Positions.vue` | `Positions.test.ts` | **sector breakdown / 走勢圖 / movers 隱藏**(`Positions.vue:144,169,192`)— 04.1 缺口(資產分類最便宜) |
| Trades | `GET /trades`(篩選/排序/分頁) | `Trades.vue`(年度 chip 半開區間 `:292-298`) | `Trades.test.ts`、`TradingApiIT` 12 條 | — |
| 開 ticket 搜標的 | `GET /assets?query=`(public) | `OrderTicket.vue` typeahead(250ms debounce、鍵盤) | `OrderTicket.test.ts`、`marketApi.test.ts`、`AssetApiIT` | — |
| 報價/走勢 | `AssetDto` 六格;`GET klines` | 報價卡 + `LineChart` 三態 | `OrderTicket.test.ts` | 走勢圖依賴 `market_prices` 有 backfill,否則「無走勢資料」但可送出 ✓ |
| 送出 BUY | `POST /trades`(冪等) | `submitTrade` 雙層守衛、key 生命週期 | `TradingApiIT` 10 條冪等、`OrderTicket.test.ts` | — |
| refetch | — | `notifyTradeCreated` → 三頁 watch | 三頁 test | fresh 高亮清除時機已依 UI-SPEC §9 修正(F-2) |
| 送 SELL / 超賣 | `applySell` → `TRADE_INSUFFICIENT_HOLDING`(409) | 可賣數量預檢 + review 步驟可見錯誤(F-1 修) | `TradingApiIT.sellRejectsOversell`、`OrderTicket.test.ts` | — |
| 登出 | `AuthController.logout` cookie 路徑 | `authSession.logout` → `POST /auth/logout`(apiClient 帶 CSRF) | `BrowserAuthFlowIT.cookieLogoutWithMatchingCsrfDoesNotFailWithCsrfError`、Playwright A3 | — |
| **其餘頁面** Markets / Watchlist / Chart / Alerts / Notifications / Analytics / Settings | 無對應後端 | **直接 import `../data` 與 `useMockPortfolioStore` / `useMockNotificationsStore`**,API mode 照樣渲染 mock 資料 | 無 | **MEDIUM-1** |

跨 repo 真實瀏覽器旅程(login → /me → reads → create trade → refetch → logout)**沒有任何自動化**(Playwright 只到 auth 與 backtest);後端 CI 的 browser-e2e 也因前後端分支名不同而只對前端 develop 跑。

## 4. 邊界與錯誤情境(逐條查證)

| 情境 | 結論 | 證據 |
|------|------|------|
| `executedAt` 未來時間 | ✓ 後端 5 分鐘容忍(`TradingService.java:54,303-311`);前端 `datetime-local :max`(`OrderTicket.vue:298`) | `TradingApiIT.futureExecutedAtIsRejectedButBackfillIsAllowed`;`maxExecutedAt` 在開啟時凍結(LOW-6) |
| `datetime-local` ↔ ISO 轉換 | ✓ `toLocalIso` 帶當地 offset、`toLocalInputValue` 去秒(`localTime.ts`);後端截到微秒(`TradingService.java:117`) | 無專屬單測(FE review F-6) |
| 年度 chip 時區 | ✓ `dateFrom/dateTo` 以本地年初帶 offset 送出,後端 `ApiTimeParser.parseRangeBound` 半開區間 | `TradingApiIT.dateRangeFilterIsHalfOpen`、`dateOnlyUpperBoundCoversTheWholeDay`;`Trades.test.ts:333-342`(以 2029 假系統時間鎖住不寫死年份) |
| BigDecimal 精度 | ✓ quantity/price/fee 皆 `@Digits(fraction=8)`;`HoldingCalculator` HALF_UP scale 8(`:11,29`);matcher 用 `compareTo` | `TradePayloadMatcherTest` 13 條 |
| SELL 超賣 | ✓ 後端 409 為權威;前端預檢 + review 步驟可見錯誤 | `sellRejectsOversell`;`OrderTicket.test.ts`(F-1 新增) |
| 同 symbol 大小寫 | ✓ `symbol.trim().toUpperCase(Locale.ROOT)`(`TradingService.java:386`);type 比對 `equalsIgnoreCase` | — |
| 分頁越界 | ✓ page clamp 到 `MAX_PAGE`、size 1..100(`TradingService.java:274-275`);非數字 → `VALIDATION_FAILED` | `AssetApiIT.publicAssetsClampsHugePageBeforeQuerying`、`BacktestApiIT.listRunsRejectsNonNumericPage...` |
| 空結果 | ✓ 三頁 empty state | `Overview/Positions/Trades.test.ts` |
| 401 → refresh → 重送 | ✓ 一次 replay,refresh path 與 login/register 不重試(`apiClient.ts:57,308`) | `apiClient.test.ts` |
| 離線 / 5xx | ✓ `NETWORK_ERROR` 合成碼 + 「重送不會重複建立」文案,key 保留 | `OrderTicket.test.ts` |
| 雙擊 | ✓ 雙層守衛 | `task4.test.ts` |
| 冪等鍵空白/過長/缺 header | ✓ 400 且 `fields['Idempotency-Key']`(本次 K-1 修正) | `TradingApiIT.blankIdempotencyKeyIsRejected`、`missingIdempotencyKeyHeaderReturnsFieldAwareValidationError`、`GlobalExceptionHandlerTest` |

## 5. mock mode 回歸

- 三個 API 化頁面皆以 `v-if="live"` 保留 mock 區塊(Overview 走勢圖/donut、Positions 圖表/movers/sector、Trades 排序箭頭);OrderTicket 在 mock mode 保留 MKT/LMT、TIF、交易後現金(D-04)— `OrderTicket.test.ts` 鎖住。
- 其餘 7 頁本來就是 mock,無回歸。
- LOW-7:mock 專用 SELL 預檢文案是英文硬字串未走 i18n(`OrderTicket.vue:990` 附近;develop 既有)。

## 6. 可觀測性與運維

- Actuator:`health,info,metrics` 於獨立 management port 11181、probes 開、`show-details: when_authorized`(`application.yaml:33-44`)。DB/Redis 由 Boot 自動指標;Kafka 由 `MarketDataHealthIndicator` 覆蓋;`FoundationSmokeIT.actuatorHealthAndOpenApiAreAvailable` 驗證。
- traceId:`TraceIdFilter` → `ApiMeta.traceId`;前端只從 `meta.traceId` 讀(`apiClient.ts:112-116`),錯誤畫面以「追蹤 ID」標籤顯示同一值 ✓;`ErrorHandlingIT` 三條 header 清洗。
- **audit.log 覆蓋缺口(MEDIUM-3)**:寫入端點中 register/login/refresh/logout/trade_create/ws_ticket 有事件;**`POST /api/admin/users/{uuid}/unlock`(`AdminUserController.java:38`)無 audit**;`POST /api/v1/market/backfill` 控制器無 audit,只有 `BackfillJobListener.java:38,49` 以 `userId=null` 記錄(無操作者);`POST /api/v1/backtests/runs` 無 audit。architecture.md §Audit Logging 要求「隨功能一起加,不回補」。

## 7. 文件與現況落差

| 文件 | 落差 |
|------|------|
| 前端 `docs/api-contracts/mock-to-real-contract.md` §Common API Conventions | 已於 2026-07-19 對齊後端(檔內明寫);**但後端 CLAUDE.md 與前端 AGENTS.md 鐵律 4 仍說「信封一節已過時」**— 路由層描述反而過時(LOW-8) |
| 同上 | 只涵蓋 Backtest / Ops / AI Access;**portfolio / trading / market / auth 契約完全沒有**(VER-04 部分未達) |
| `ai-docs/browser-auth-contract.md` | auth/CSRF/refresh/logout/401/403 完整;Idempotency-Key 與 400/409 本次補上;DTO 段只寫「由後端 API 擁有」 |
| 後端 root `README.md` | 不存在(archive 分支曾加,未合入) |
| `.planning/REQUIREMENTS.md` | AUTH-01/02/05/06/07、SEC-01~05、FAPI-08、TRAD-01~06、VER-01/02 實際已達卻未勾選;狀態檔落後 |
| `04-13-SUMMARY.md` / `04-13-PLAN.md` | 路徑 `D:\end\workspace` 已搬家 |

## 8. 發現清單

### MEDIUM
1. **API mode 下 7 頁渲染 mock 資料且無 Preview/Simulated 標示**(AGENTS.md 鐵律 3、6)— `Markets.vue:129-137`、`Chart.vue:155-163`、`Watchlist.vue:73-80`、`Analytics.vue:367`、`Alerts.vue:223-228`、`Notifications.vue:182-186`、`Settings.vue:192-210` 直接 import `../data` / mock store。影響:API mode 使用者在 11 頁中的 7 頁看到假市場/假持倉/假通知,watchlist 操作寫進 mock store(靜默 no-op);Phase 5 瀏覽器驗證的證明力被削弱。PROJECT.md Out of Scope 延後的是「API 整合」,不是「標示」。建議:Phase 04.1/5 前先在這些頁加 `Simulated` badge(`Backtest.vue:7` 已有樣式可複用),並把 Markets/Chart 的標的清單改走 `marketApi.searchAssets`。
2. **VER-03 未達:沒有跨 repo 的「建立交易 → refetch」瀏覽器旅程**— Playwright 僅 `auth.spec.ts` A1–A11、`backtest.spec.ts` D1–D5;04-13 Task 2 人工 14 步亦未執行(本機 BIOS 虛擬化關閉)。後端 CI browser-e2e 以「同名前端分支,否則 develop」解析(`ci.yml:152-156`),PR 期間永遠打不到 Phase 4 前端。建議:Phase 5 首個 plan 就寫 Playwright 旅程 B(login → ticket → BUY → Positions 高亮 → SELL 超賣 → logout),並讓 CI 以 PR body 或 env 指定前端分支。
3. **audit.log 缺口**:`POST /api/admin/users/{uuid}/unlock`(`AdminUserController.java:38`)、`POST /api/v1/market/backfill`(控制器層無操作者)、`POST /api/v1/backtests/runs` 三個寫入端點無 audit 事件;admin unlock 尤其該記(security.md §13)。
4. **VER-04 部分未達:portfolio / trading DTO 與 `GET /trades` 參數沒有契約文件**;前端契約檔只寫 Backtest/Ops/AI Access。建議在 `ai-docs/browser-auth-contract.md` 旁新增 `ai-docs/portfolio-trading-contract.md`(或擴寫前端契約檔),逐欄列 `TradeDto`/`HoldingDto`/`PortfolioSummaryDto`/`AssetDto`/`KlineDto`、`CreateTradeRequest` 與 header、`GET /trades` 參數與 error code。

### LOW
1. `MeResponse.id`(內部 Long)外露且前端 `mapUser` 強制要求(`authApi.ts:46`)— 違反 architecture.md「外部 ID 一律 UUID」;前端沒用到 `user.id`,可改為只認 `uuid`。
2. `SUBMIT_ERROR_COPY` 的 `ACCESS_DENIED`/`FORBIDDEN`/`AUTH_CSRF_TOKEN_MISSING` 是後端不存在的 code(`OrderTicket.vue:1049-1052`);403 靠 status 兜底才正確。建議改鍵為 `AUTH_FORBIDDEN` 並刪死鍵;`AUTH_RATE_LIMITED`/`AUTH_ACCOUNT_LOCKED` 可加文案。
3. `AssetDto.sector` DB 可空,前端型別 `string`(`apiTypes.ts`)。
4. BigDecimal 序列化不一致(kline 字串 / trade、holding 數字);18 位有效數字在前端 `number` 可能失真。建議 Phase 04.1 統一為字串或在前端以字串保存金額。
5. 前端沒有 WebSocket client;後端 WS 推送(ticket、訂閱、`auth_expired`)無消費者,Markets/Chart 即時資料仍為 mock。
6. `maxExecutedAt` 於 ticket 開啟時凍結(`OrderTicket.vue:1160`);後端 5 分鐘容忍可吸收,但 ticket 開超過 5 分鐘再送會被後端拒。
7. mock 專用 SELL 預檢英文硬字串未走 i18n。
8. 後端 CLAUDE.md / 前端 AGENTS.md 鐵律 4 仍稱前端契約檔信封一節「過時」,實際已對齊(文件自述 2026-07-19);`.planning/REQUIREMENTS.md` 勾選狀態落後(多數已達成項未勾)。

## 9. 前三缺口(依對 milestone 目標的影響排序)

1. **跨 repo 真實瀏覽器交易旅程零覆蓋**(MEDIUM-2)— 這是 milestone 核心價值「one coherent frontend/backend flow」唯一還沒被證明的一段。
2. **API mode 有 7 頁是未標示的 mock**(MEDIUM-1)— 影響 Phase 5 驗證的誠實度與使用者對「真資料」的信任;04.1 的 watchlist 缺口就在其中。
3. **portfolio/trading 契約無文件**(MEDIUM-4)— 兩個 repo 目前靠測試與 `apiTypes.ts` 對齊,一旦 04.1 改 DTO(加 sector/cash)沒有單一權威可查。

## 10. 殘留未確認

- 全部為靜態閱讀;未跑測試(依指示),引用的測試名以檔案內容為準。
- SEC-02 在 `POST /trades` 的 cookie+CSRF 路徑只有 `BrowserCsrfFilter` 的一般性 IT,沒有針對 trades 的 IT — 與 04-13 的 accept 一致,未新增證據。
- 前端 `Analytics.vue`/`Alerts.vue` 等 mock 頁的行為未逐頁走讀,只確認 import 來源與缺少標示。
- `BacktestRunDto` 前後端欄位同名同數(13 欄),但 `progress` 後端 BigDecimal 可空、前端 `progress?: number` — 未查後端何時為 null。
