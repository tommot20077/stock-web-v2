---
phase: 05-cross-repo-browser-flow-verification-contract-hardening
reviewed: 2026-11-12T03:10:00Z
depth: standard
scope: cross-repo — stock-web-v2 (2d26315..87d5bdf) + stock-web-v2-front-end (ebd54db..06ab920)
files_reviewed: 193
files_reviewed_list:
  - stock-web-v2/.github/workflows/ci.yml
  - stock-web-v2/pom.xml
  - stock-web-v2/stock-common/pom.xml
  - stock-web-v2/stock-db-migration/src/main/resources/db/migration/V20__watchlists_name_blank_like_check.sql
  - stock-web-v2/stock-infrastructure/pom.xml
  - stock-web-v2/stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/web/json/DecimalString.java
  - stock-web-v2/stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/web/json/PlainDecimalStringSerializer.java
  - stock-web-v2/stock-infrastructure/src/test/java/dowob/xyz/stockwebv2/infrastructure/web/json/PlainDecimalStringSerializerTest.java
  - stock-web-v2/stock-module-asset/pom.xml
  - stock-web-v2/stock-module-asset/src/main/java/dowob/xyz/stockwebv2/asset/api/AssetDto.java
  - stock-web-v2/stock-module-asset/src/main/java/dowob/xyz/stockwebv2/asset/service/WatchlistService.java
  - stock-web-v2/stock-module-asset/src/test/java/dowob/xyz/stockwebv2/asset/service/AssetFacadeImplIT.java
  - stock-web-v2/stock-module-asset/src/test/java/dowob/xyz/stockwebv2/asset/service/FxRateFacadeImplIT.java
  - stock-web-v2/stock-module-backtest/pom.xml
  - stock-web-v2/stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/api/BacktestResultDto.java
  - stock-web-v2/stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/api/BacktestRunDto.java
  - stock-web-v2/stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/api/CreateBacktestRunRequest.java
  - stock-web-v2/stock-module-market-data/pom.xml
  - stock-web-v2/stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/api/KlineDto.java
  - stock-web-v2/stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/api/LatestPriceDto.java
  - stock-web-v2/stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/config/KafkaConfig.java
  - stock-web-v2/stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/consumer/PriceWriterConsumer.java
  - stock-web-v2/stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/consumer/WsBroadcastConsumer.java
  - stock-web-v2/stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/ingest/MarketDataIngestService.java
  - stock-web-v2/stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/api/BackfillIdempotencyServiceIT.java
  - stock-web-v2/stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/api/MarketControllerKlinesIT.java
  - stock-web-v2/stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/api/MarketControllerTest.java
  - stock-web-v2/stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/batch/BackfillJobIT.java
  - stock-web-v2/stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/config/KafkaConfigIT.java
  - stock-web-v2/stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/config/KafkaTopicPlaceholderTest.java
  - stock-web-v2/stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/config/KafkaTopicRetentionTest.java
  - stock-web-v2/stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/consumer/PriceWriterConsumerIT.java
  - stock-web-v2/stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/consumer/PriceWriterRetryIT.java
  - stock-web-v2/stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/ingest/MarketDataIngestServiceIT.java
  - stock-web-v2/stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/migration/MarketDataMigrationIT.java
  - stock-web-v2/stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/observability/MarketDataHealthIndicatorIT.java
  - stock-web-v2/stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/persistence/MarketPriceRepositoryIT.java
  - stock-web-v2/stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/support/MarketDataContainers.java
  - stock-web-v2/stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/ws/WsTicketServiceIT.java
  - stock-web-v2/stock-module-trading/pom.xml
  - stock-web-v2/stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/AllocationSliceDto.java
  - stock-web-v2/stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/CashBalanceDto.java
  - stock-web-v2/stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/CashEntryDto.java
  - stock-web-v2/stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/CreateCashEntryRequest.java
  - stock-web-v2/stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/CreateDividendRequest.java
  - stock-web-v2/stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/CreateShareAdjustmentRequest.java
  - stock-web-v2/stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/CreateTradeRequest.java
  - stock-web-v2/stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/DividendDto.java
  - stock-web-v2/stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/HoldingDto.java
  - stock-web-v2/stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/PortfolioActivityDto.java
  - stock-web-v2/stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/PortfolioSummaryDto.java
  - stock-web-v2/stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/SectorAllocationDto.java
  - stock-web-v2/stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/ShareAdjustmentDto.java
  - stock-web-v2/stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/TradeDto.java
  - stock-web-v2/stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/PortfolioCache.java
  - stock-web-v2/stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/TradingService.java
  - stock-web-v2/stock-module-trading/src/test/java/dowob/xyz/stockwebv2/trading/api/CashLedgerControllerWebMvcTest.java
  - stock-web-v2/stock-module-trading/src/test/java/dowob/xyz/stockwebv2/trading/api/PortfolioActivityControllerWebMvcTest.java
  - stock-web-v2/stock-module-trading/src/test/java/dowob/xyz/stockwebv2/trading/service/PortfolioCacheTest.java
  - stock-web-v2/stock-module-trading/src/test/java/dowob/xyz/stockwebv2/trading/service/TradingServiceTest.java
  - stock-web-v2/stock-module-user/pom.xml
  - stock-web-v2/stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/service/AccessTokenTtlConsistencyGuard.java
  - stock-web-v2/stock-module-user/src/test/java/dowob/xyz/stockwebv2/user/service/AccessTokenTtlConsistencyGuardTest.java
  - stock-web-v2/stock-module-user/src/test/java/dowob/xyz/stockwebv2/user/service/BrowserAuthCookieServiceTest.java
  - stock-web-v2/stock-start/pom.xml
  - stock-web-v2/stock-start/src/main/resources/application-demo.yaml
  - stock-web-v2/stock-start/src/main/resources/application.yaml
  - stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/ApiDecimalStringRulesTest.java
  - stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/AssetApiIT.java
  - stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/BrowserAuthFlowIT.java
  - stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/CashEntryApiIT.java
  - stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/DividendApiIT.java
  - stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/E2eNamingRulesTest.java
  - stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/MethodSecurityDenialIT.java
  - stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/PortfolioActivityApiIT.java
  - stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/PortfolioCashAllocationIT.java
  - stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/PortfolioDayPnlIT.java
  - stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/PortfolioDividendSummaryIT.java
  - stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/ShareAdjustmentApiIT.java
  - stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/TradingApiIT.java
  - stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/WatchlistApiIT.java
  - stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/AssetE2E.java
  - stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/AuthE2E.java
  - stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/BacktestE2E.java
  - stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/CashLedgerE2E.java
  - stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/DividendE2E.java
  - stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/FoundationFlowE2E.java
  - stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/PortfolioActivityE2E.java
  - stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/PortfolioBaseCurrencyE2E.java
  - stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/PortfolioDayPnlE2E.java
  - stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/ShareAdjustmentE2E.java
  - stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/SmokeE2E.java
  - stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/UserPreferencesE2E.java
  - stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/ValidationBoundaryE2E.java
  - stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/WatchlistE2E.java
  - stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/support/ContainerIT.java
  - stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/support/DecimalStringMatchers.java
  - stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/support/DecimalStringMatchersTest.java
  - stock-web-v2/stock-start/src/test/resources/application-e2e.yaml
  - stock-web-v2/stock-start/src/test/resources/application-test.yaml
  - stock-web-v2-front-end/.github/workflows/ci.yml
  - stock-web-v2-front-end/vue-app/e2e/run-e2e.ps1
  - stock-web-v2-front-end/vue-app/e2e/run-e2e.sh
  - stock-web-v2-front-end/vue-app/e2e/support/journeyApi.ts
  - stock-web-v2-front-end/vue-app/e2e/support/mockSentinels.ts
  - stock-web-v2-front-end/vue-app/e2e/support/portfolioStubs.ts
  - stock-web-v2-front-end/vue-app/e2e/tests/api-failure-states.spec.ts
  - stock-web-v2-front-end/vue-app/e2e/tests/backtest.spec.ts
  - stock-web-v2-front-end/vue-app/e2e/tests/cash-journey.spec.ts
  - stock-web-v2-front-end/vue-app/e2e/tests/contract-journey.spec.ts
  - stock-web-v2-front-end/vue-app/e2e/tests/holding-event-journey.spec.ts
  - stock-web-v2-front-end/vue-app/package.json
  - stock-web-v2-front-end/vue-app/playwright.config.ts
  - stock-web-v2-front-end/vue-app/src/App.test.ts
  - stock-web-v2-front-end/vue-app/src/baseCurrencyErrorKey.test.ts
  - stock-web-v2-front-end/vue-app/src/baseCurrencyErrorKey.ts
  - stock-web-v2-front-end/vue-app/src/cashBalanceView.test.ts
  - stock-web-v2-front-end/vue-app/src/cashBalanceView.ts
  - stock-web-v2-front-end/vue-app/src/components/AuthPanel.vue
  - stock-web-v2-front-end/vue-app/src/components/CashEntryDialog.test.ts
  - stock-web-v2-front-end/vue-app/src/components/CashEntryDialog.vue
  - stock-web-v2-front-end/vue-app/src/components/Header.vue
  - stock-web-v2-front-end/vue-app/src/components/HoldingEventDialog.test.ts
  - stock-web-v2-front-end/vue-app/src/components/HoldingEventDialog.vue
  - stock-web-v2-front-end/vue-app/src/components/OrderTicket.test.ts
  - stock-web-v2-front-end/vue-app/src/components/OrderTicket.vue
  - stock-web-v2-front-end/vue-app/src/components/SessionBanner.vue
  - stock-web-v2-front-end/vue-app/src/components/WatchlistPicker.test.ts
  - stock-web-v2-front-end/vue-app/src/components/WatchlistPicker.vue
  - stock-web-v2-front-end/vue-app/src/components/settings/SettingsAiAccess.vue
  - stock-web-v2-front-end/vue-app/src/components/settings/SettingsBrokers.vue
  - stock-web-v2-front-end/vue-app/src/components/settings/SettingsDataSources.vue
  - stock-web-v2-front-end/vue-app/src/composables/useLiveQuotes.test.ts
  - stock-web-v2-front-end/vue-app/src/composables/useLiveQuotes.ts
  - stock-web-v2-front-end/vue-app/src/composables/useQuoteRefresh.ts
  - stock-web-v2-front-end/vue-app/src/contrastTokens.test.ts
  - stock-web-v2-front-end/vue-app/src/csvCell.test.ts
  - stock-web-v2-front-end/vue-app/src/decimalBoundary.test.ts
  - stock-web-v2-front-end/vue-app/src/mockSentinels.test.ts
  - stock-web-v2-front-end/vue-app/src/moneyFormat.test.ts
  - stock-web-v2-front-end/vue-app/src/moneyFormat.ts
  - stock-web-v2-front-end/vue-app/src/pages/Alerts.vue
  - stock-web-v2-front-end/vue-app/src/pages/Analytics.vue
  - stock-web-v2-front-end/vue-app/src/pages/Backtest.test.ts
  - stock-web-v2-front-end/vue-app/src/pages/Backtest.vue
  - stock-web-v2-front-end/vue-app/src/pages/Cash.test.ts
  - stock-web-v2-front-end/vue-app/src/pages/Cash.vue
  - stock-web-v2-front-end/vue-app/src/pages/Chart.vue
  - stock-web-v2-front-end/vue-app/src/pages/Markets.vue
  - stock-web-v2-front-end/vue-app/src/pages/Ops.vue
  - stock-web-v2-front-end/vue-app/src/pages/Overview.test.ts
  - stock-web-v2-front-end/vue-app/src/pages/Overview.vue
  - stock-web-v2-front-end/vue-app/src/pages/Positions.test.ts
  - stock-web-v2-front-end/vue-app/src/pages/Positions.vue
  - stock-web-v2-front-end/vue-app/src/pages/Settings.vue
  - stock-web-v2-front-end/vue-app/src/pages/Trades.test.ts
  - stock-web-v2-front-end/vue-app/src/pages/Trades.vue
  - stock-web-v2-front-end/vue-app/src/pages/Watchlist.test.ts
  - stock-web-v2-front-end/vue-app/src/pages/Watchlist.vue
  - stock-web-v2-front-end/vue-app/src/services/apiClient.test.ts
  - stock-web-v2-front-end/vue-app/src/services/apiClient.ts
  - stock-web-v2-front-end/vue-app/src/services/apiTypes.ts
  - stock-web-v2-front-end/vue-app/src/services/backtestApi.test.ts
  - stock-web-v2-front-end/vue-app/src/services/backtestApi.ts
  - stock-web-v2-front-end/vue-app/src/services/cashApi.test.ts
  - stock-web-v2-front-end/vue-app/src/services/cashApi.ts
  - stock-web-v2-front-end/vue-app/src/services/decimal.test.ts
  - stock-web-v2-front-end/vue-app/src/services/decimal.ts
  - stock-web-v2-front-end/vue-app/src/services/holdingEventApi.test.ts
  - stock-web-v2-front-end/vue-app/src/services/holdingEventApi.ts
  - stock-web-v2-front-end/vue-app/src/services/marketApi.test.ts
  - stock-web-v2-front-end/vue-app/src/services/marketApi.ts
  - stock-web-v2-front-end/vue-app/src/services/opsApi.ts
  - stock-web-v2-front-end/vue-app/src/services/portfolioApi.test.ts
  - stock-web-v2-front-end/vue-app/src/services/portfolioApi.ts
  - stock-web-v2-front-end/vue-app/src/services/portfolioRevision.test.ts
  - stock-web-v2-front-end/vue-app/src/services/portfolioRevision.ts
  - stock-web-v2-front-end/vue-app/src/services/quoteStream.test.ts
  - stock-web-v2-front-end/vue-app/src/services/quoteStream.ts
  - stock-web-v2-front-end/vue-app/src/services/tradingApi.test.ts
  - stock-web-v2-front-end/vue-app/src/services/tradingApi.ts
  - stock-web-v2-front-end/vue-app/src/services/watchlistApi.test.ts
  - stock-web-v2-front-end/vue-app/src/services/watchlistApi.ts
  - stock-web-v2-front-end/vue-app/src/styles.css
  - stock-web-v2-front-end/vue-app/src/task4.test.ts
  - stock-web-v2-front-end/vue-app/src/task7.test.ts
  - stock-web-v2-front-end/vue-app/src/testing/fakeQuoteStream.ts
  - stock-web-v2-front-end/vue-app/src/testing/watchlistFetchDouble.ts
  - stock-web-v2-front-end/vue-app/src/types.ts
  - stock-web-v2-front-end/vue-app/src/viteConfig.test.ts
  - stock-web-v2-front-end/vue-app/tsconfig.json
  - stock-web-v2-front-end/vue-app/vite.config.ts
findings:
  critical: 1
  warning: 7
  info: 13
  total: 21
status: issues_found
---

# Phase 05 Code Review(跨 repo)

兩個 gsd-code-reviewer 並行審查(standard depth),各自報告合併於此;finding ID 加 repo 前綴(`BE-` 後端、`FE-` 前端)避免撞號。審查範圍為 Phase 05 全部原始碼變更(Wave 1 + Wave 2,已合併進兩 repo develop)。

| Repo | Files | Critical | Warning | Info |
|------|-------|----------|---------|------|
| stock-web-v2(後端) | 100 | 1 | 3 | 8 |
| stock-web-v2-front-end(前端) | 93 | 0 | 4 | 5 |

---

# 後端 stock-web-v2

## Phase 5: Code Review Report(後端 stock-web-v2)

**Reviewed:** 2026-11-12T03:06:10Z
**Depth:** standard
**Files Reviewed:** 100
**Status:** issues_found

### Summary

範圍:`git diff 2d26315 origin/develop`(87d5bdf,合併後的 phase 結果)涵蓋的 100 個檔案,逐檔讀 diff 並對照周邊程式。主題:`@DecimalString` / `PlainDecimalStringSerializer` 字串化、ArchUnit 守門、快取鍵 v4、browser-auth 測試(CSRF / logout / TTL guard)、market-data 共用 Testcontainers、Kafka placeholder、V20 watchlist CHECK、IT / E2E 斷言改字串。

已實際驗證(不只讀碼)的部分:
- V20 的 28 個字元與 JDK 21.0.12 `Character.isWhitespace || isSpaceChar` 全 code point 列舉**完全一致**。
- Jackson 3.1.0 預設 mapper 會把 `"1e999999999"`(字串)與 `1e999999999`(number)都收成 scale = -999999999 的 `BigDecimal`,`""` 收成 `null`。
- 所有 controller 回應型別逐一確認:目前每個 `BigDecimal` 欄位都有標 `@DecimalString`;WS 推播已用 `toPlainString()`,REST 與 WS 格式一致。
- 共用容器的隔離(唯一 DB 名、Redis index 1/2/3、唯一 topic / group、`flushDb`)沒有發現碰撞。

主要疑慮:(1) 回測 `initialCapital` 沒有上限 / `@Digits`,一個字串就能讓引擎的 `setScale` 卡死請求執行緒(這個漏洞在 phase 前就存在,但這次 phase 正好改了這一行,而且把字串輸入定為正式契約);(2) D-09 守門只看 `*Dto` / `*Request` 的 raw-type 欄位;(3) V20 對既有資料做全表驗證,遇到舊資料會讓 Flyway 失敗、應用起不來;(4) Jacoco 下限直接設在實測值,跟亂序 IT 搭在一起有 flaky 風險。

已知、不重報:`ci.yml:247` 的 `${{ github.head_ref || github.ref_name }}` 直接插值;`.env.example:17` 的 `PT30M` 沒有成對的 cookie TTL。

### Critical Issues

#### BE-CR-01: `CreateBacktestRunRequest.initialCapital` 沒有上限 / 位數限制,極端指數字串會讓回測引擎 `setScale` 卡死(已登入使用者即可 DoS)

**File:** `stock-web-v2/stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/api/CreateBacktestRunRequest.java:17`(觸發點:`stock-web-v2/stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/engine/DeterministicBacktestEngine.java:51,76-77,189`)
**Issue:** 欄位只有 `@NotNull @DecimalMin(value = "0.0", inclusive = false)`,沒有 `@DecimalMax`,也沒有 `@Digits`(trading 的 Request 都有)。Jackson 3.1 預設接受 `{"initialCapital":"1e999999999"}`(實測得到 scale -999999999),Bean Validation 與 `BacktestService.validateCapital`(只檢查 `> 0`)都會放行。持久化之前就先跑 `engine.run(input)`(`BacktestService.java:101`),引擎第 51 行執行 `initialCapital.setScale(2, HALF_UP).toPlainString()`,`money()` 也執行 `setScale(6)`:實測 `1e7` 單次要 12.5 秒、產生 10 MB 字串,`1e9` 實際上就是 OOM 或永遠跑不完。`POST /api/v1/backtests` 只要已登入就能呼叫。這個漏洞在 phase 之前就存在,但這次 phase 在同一行加了 `@DecimalString`,Javadoc 也寫「`@DecimalMin` / `@Digits` 照常生效」,D-07 又把字串輸入定為正式路徑,所以這一行應該在這裡一起收斂。DB 欄位是 `NUMERIC(24,6)`(V3),上限本來就明確。
**Fix:**
```java
@NotNull
@DecimalMin(value = "0.0", inclusive = false)
@DecimalMax(value = "1000000000000")          // 依產品上限調整
@Digits(integer = 18, fraction = 6)           // 對齊 V3 initial_capital NUMERIC(24,6)
@DecimalString BigDecimal initialCapital,
```
另外補一個 WebMvc / IT 案例:`"initialCapital":"1e999999999"` 必須回 400,而且不能呼叫 engine。

### Warnings

#### BE-WR-01: D-09 守門(`ApiDecimalStringRulesTest`)只掃 `*Dto` / `*Request` 類別的 raw-type `BigDecimal` 欄位,有多種寫法會繞過
**File:** `stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/ApiDecimalStringRulesTest.java:66-86`
**Issue:** predicate 只看 simple name 結尾是 `Dto` 或 `Request`,而 controller 實際回傳的型別還有 `*Response`(`MeResponse`、`TokenResponse`、`BackfillTriggerResponse`、`BrowserSessionResponse`、`WsTicketResponse`、`CsrfResponse`)。規則又是 `fields().haveRawType(BigDecimal.class)`,所以下列三種情況都不會被抓到,而且都會靜默輸出 JSON number,也就是 T-05-24 要擋的情境:(a) `*Response` / `*View` 新增 `BigDecimal` 欄位;(b) `List<BigDecimal>`、`Map<String, BigDecimal>`、`Optional<BigDecimal>` 欄位(raw type 是 `List`,何況 `@DecimalString` 不含 `TYPE_USE`,根本標不上去);(c) Jackson 會序列化的 `BigDecimal` getter 或衍生屬性方法(不是 field)。目前碰巧沒有這種欄位,但守門的目的就是擋未來的回歸。
**Fix:** 以 controller 回傳型別為根往下走,或至少把 predicate 擴成 `..api..` 套件內所有 record / class;另外加一條規則禁止 API 型別裡出現參數化型別含 `BigDecimal` 的欄位或方法:
```java
fields().that().areDeclaredInClassesThat().resideInAPackage("dowob.xyz.stockwebv2..api..")
    .and().haveRawType(BigDecimal.class).should().beAnnotatedWith(DecimalString.class);
noFields().that().areDeclaredInClassesThat().resideInAPackage("dowob.xyz.stockwebv2..api..")
    .should(haveGenericTypeArgument(BigDecimal.class));   // 自訂 ArchCondition
methods().that().areDeclaredInClassesThat(API_TRANSFER_TYPES).and().haveRawReturnType(BigDecimal.class)
    .and().arePublic().should().beAnnotatedWith(DecimalString.class);
```

#### BE-WR-02: V20 以 `ADD CONSTRAINT ... CHECK` 驗證全表,只要有舊資料不符就會讓 Flyway 失敗、整個應用起不來
**File:** `stock-web-v2/stock-db-migration/src/main/resources/db/migration/V20__watchlists_name_blank_like_check.sql:29-33`
**Issue:** 檔頭自己寫明:quick-261107-1j4 之前服務層**允許**只含不換行空白的名稱,所以部署環境可能真的有這種列。Flyway 在啟動時套用 migration,`ADD CONSTRAINT` 掃到這種列就會失敗,`stock-start` 直接起不來。修復要靠人工照註解查詢、改資料、再重跑,等於部署當下的停機風險。CI 用全新 DB,測不到這個情境。
**Fix:** 用 `NOT VALID` 先只約束新寫入,同一個 migration 內把違規列改成安全名稱,再 `VALIDATE`;或者拆成兩步:
```sql
UPDATE watchlists
   SET name = 'Watchlist ' || left(id::text, 8)
 WHERE btrim(name, E'\u0009...　') = '';
ALTER TABLE watchlists DROP CONSTRAINT ck_watchlists_name_not_blank;
ALTER TABLE watchlists ADD CONSTRAINT ck_watchlists_name_not_blank
    CHECK (btrim(name, E'\u0009...　') <> '') NOT VALID;
ALTER TABLE watchlists VALIDATE CONSTRAINT ck_watchlists_name_not_blank;
```
(改名策略要同時避開 `(user_id, name)` 唯一鍵;如果不允許自動改名,至少用 `NOT VALID` 加上 runbook。)

#### BE-WR-03: Jacoco 棘輪下限直接設在實測值(無條件捨去),加上 market-data IT 亂序,`verify` 有 flaky 風險
**File:** `stock-web-v2/pom.xml:126-153`、`stock-web-v2/stock-module-market-data/pom.xml:12-17,185-188`、`stock-web-v2/stock-start/pom.xml:13-17`
**Issue:** 各模組下限是 2026-11-09 實測值捨去到小數第二位,餘裕介於 0 到 0.0099。stock-start 已經碰到一次:0.87 / 0.64 是在 0.88 / 0.66 失敗後**下調**而來(`ee4094c`)。market-data 的 line 0.90 是 unit 加 IT 合併的 `jacoco.exec`,而 IT 現在固定 `<runOrder>random</runOrder>`,內容也含時間相依的重試 / DLT / async consumer 路徑,覆蓋率會隨執行順序與時序小幅浮動,可能偶發低於下限、讓 CI 紅燈。另外,下限是含 IT 的實測值,本機或 CI 用 `./mvnw verify -DskipITs`(或沒有 Docker)時,`jacoco.exec` 只有 unit 資料,check 照樣執行,很可能因覆蓋率不足失敗,而不是被略過。
**Fix:** 下限至少保留 1 到 2 個百分點的緩衝(例如 market-data line 0.88);`check` execution 加 `<skip>${skipITs}</skip>`,或把下限只套在 IT 有跑的 profile;改動後在 CI 用多個 `-Dfailsafe.runOrder.random.seed` 各跑一次,確認覆蓋率穩定。

### Info

#### BE-IN-01: `DecimalStringMatchers.decimalString` 接受科學記號字串,`asDecimal()` 改法也不檢查 wire 型別
**File:** `stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/support/DecimalStringMatchers.java:38-56`;`PortfolioCashAllocationIT`、`PortfolioDividendSummaryIT`、`PortfolioDayPnlIT`、`DividendE2E`、`PortfolioBaseCurrencyE2E`、`PortfolioDayPnlE2E`、`ShareAdjustmentE2E`、`PortfolioActivityE2E` 的 `.asDecimal()`
**Issue:** matcher 用 `new BigDecimal(text)` 比數值,`"1E+3"`、`"0E-8"` 都會通過,但這正是這次 phase 要消滅的格式(`toString()` 回歸)。Jackson 3 的 `asDecimal()` 對 StringNode 與 NumberNode 都會成功,所以這些 IT / E2E 原本用 `decimalValue()` 隱含的型別檢查不見了。目前只有 `ApiDecimalStringRulesTest` 的 unit 樣本守住格式。
**Fix:** matcher 加一條格式檢查:`text.matches("-?\\d+(\\.\\d+)?")`,不符就報 "not plain notation";summary / holdings 這類 IT 至少挑一兩個欄位改用 `decimalString`,或另加 `isTextual()` 斷言。

#### BE-IN-02: `MarketDataContainers.ensureTopics` 遇到批次中有 `TopicExistsException` 時,可能吞掉其他 topic 的真正錯誤
**File:** `stock-web-v2/stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/support/MarketDataContainers.java:148-157`
**Issue:** `createTopics(...).all().get()` 只會拋出第一個失敗的 future。同一批若有 A 已存在、B 因其他原因失敗(設定錯誤、replication factor 等),而先被看到的是 A 的 `TopicExistsException`,方法會當作成功回傳,錯誤要等到後續斷言或 timeout 才浮現,很難追查。
**Fix:** 逐一檢查 `result.values()`,每個 future 個別 `get()`,只忽略 `TopicExistsException`。

#### BE-IN-03: `AccessTokenTtlConsistencyGuard` 比對的是正規化後的 cookie TTL,而且只守 access 那一對
**File:** `stock-web-v2/stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/service/AccessTokenTtlConsistencyGuard.java:36-41`
**Issue:** `BrowserAuthCookieProperties` 會把 `PT0S` 或負值靜默改成 15 分鐘(`BrowserAuthCookieProperties.java:38-40`)。操作者若把 `STOCK_AUTH_COOKIE_ACCESS_TOKEN_TTL` 設錯成 `PT0S`、JWT 為 `PT15M`,guard 會放行,錯誤設定被吞掉。refresh cookie(P14D)與 JWT refresh TTL 也沒有同樣的一致性檢查,同類不一致可能讓 refresh cookie 比 refresh token 先過期或晚過期。此外,`e2e-browser` / `test` profile 是在 yaml 裡寫死的值,錯誤訊息卻只指名環境變數,在這些 profile 下會誤導。
**Fix:** cookie TTL 非正值時直接拒絕啟動,不要正規化;視需要加上 refresh 一對的檢查;訊息補上 property 名稱(`stock.auth.cookie.access-token-ttl` / `stock.jwt.access-token-ttl`)。

#### BE-IN-04: `PortfolioCacheTest` 的名稱與說明還寫 v3,也沒有測「不讀 v3 鍵」
**File:** `stock-web-v2/stock-module-trading/src/test/java/dowob/xyz/stockwebv2/trading/service/PortfolioCacheTest.java:142-143`(以及 legacy v2 系列案例)
**Issue:** `@DisplayName("...與全部 v3 summary 鍵...")` 與方法名 `tradeInvalidationDeletesV3ValuationKeyAndAllV3SummaryKeys` 實際斷言的是 v4。鍵升到 v4 的目的是「部署瞬間不混讀 v3」(`PortfolioCache.java:37-39`),但 legacy 案例只驗證 v2 不會被讀,v3 這條沒有測試鎖住。
**Fix:** 名稱改成 V4;另加一個案例:`portfolio:summary:v3:*` 有值、v4 為 null 時回 empty,而且從未 `get` v3。

#### BE-IN-05: `KafkaConfigIT` 沒有執行任何 `KafkaConfig` 程式碼
**File:** `stock-web-v2/stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/config/KafkaConfigIT.java:31-53`
**Issue:** 測試自己用 `TopicBuilder` 建出 3 partitions 的 topic,再斷言 partition 數是 3,沒有呼叫 `KafkaConfig#priceTickTopic` 等 bean 方法。改用共用容器之後,正式常數只剩前綴的用途。若 `KafkaConfig` 改成 6 partitions,這個 IT 仍然綠燈。這個問題在 phase 前就存在,只是這次又改到它。
**Fix:** 用 `new KafkaConfig().priceTickTopic(uniqueName(...))` 等回傳的 `NewTopic` 去建立 topic,再 describe 斷言。

#### BE-IN-06: 模組 pom 的 Jacoco 註解寫「只可上調不可下調」,但 stock-start 的值已經下調
**File:** `stock-web-v2/stock-start/pom.xml:13`
**Issue:** 註解寫「2026-11-09 實測……只可上調不可下調」,但 0.87 / 0.64 是 2026-11-10 經 Yuan 核准下調的結果(`ee4094c`)。註解與實際歷史不符,會誤導後續維護者。
**Fix:** 改成「2026-11-10 依 D-23 下調(Yuan 核准,見 testing-standards.md);之後只可上調」。

#### BE-IN-07: 共用 Redis 的 database index 只靠註解分配,沒有任何機制守住
**File:** `stock-web-v2/stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/batch/BackfillJobIT.java:116`、`api/BackfillIdempotencyServiceIT.java:27`、`ws/WsTicketServiceIT.java:39`
**Issue:** index 1 / 2 / 3 分散寫在三個類別的常數與註解裡。新增 IT 時若重用同一個 index,另一個類別的 `flushDb()` 會清掉它的資料,在亂序執行下變成偶發失敗。
**Fix:** 在 `MarketDataContainers` 集中定義 `REDIS_DB_WS_TICKET = 1` 等常數,或提供 `allocateRedisDatabase(String owner)` 並檢查重複。

#### BE-IN-08: V20 的負向 IT 只斷言 `DataIntegrityViolationException`,沒有確認是哪個約束
**File:** `stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/WatchlistApiIT.java:216-219`
**Issue:** 任何 NOT NULL、唯一鍵、FK 違反都會讓斷言通過。正向案例證明了其他欄位有預設值,但若之後 schema 變動,負向案例可能變成「因別的原因失敗而通過」。
**Fix:** `.hasMessageContaining("ck_watchlists_name_not_blank")`,或斷言 root cause 的 `PSQLException#getServerErrorMessage().getConstraint()`。

---

_Reviewed: 2026-11-12T03:06:10Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_

---

# 前端 stock-web-v2-front-end

## Phase 05: Code Review Report (frontend, stock-web-v2-front-end)

**Reviewed:** 2026-11-12
**Depth:** standard
**Files Reviewed:** 93 (diff `ebd54db..origin/develop` = 06ab920, judged in surrounding code)
**Status:** issues_found

### Summary

I reviewed the phase's frontend changes: the big.js strict Decimal domain (`services/decimal.ts`, the per-type mappers in `portfolioApi` / `tradingApi` / `cashApi` / `holdingEventApi` / `marketApi` / `watchlistApi` / `backtestApi`, and Decimal arithmetic in pages and components), `apiClient` `transportFetch` NETWORK_ERROR normalisation, the SC5 failure matrix with mock sentinels, contract journey J0, the real-backend E2E helpers, the `decimalBoundary` source guard, the `--danger` token migration and the new CI browser-e2e job.

The core Decimal boundary is solid:
- Wire mappers parse every BigDecimal field. Request projections go through `toWire` (so JSON bodies never contain `1e-8`).
- `moneyFormat` formats Decimal through `toFixed(dp, HALF_UP)` and then string-input `Intl.NumberFormat`.
- I checked the sorting, comparisons and the live-quote overlay. All of them stay in Decimal.
- Vue deep-reactive proxies around big.js instances work: `instanceof` and the big.js copy constructor both go through the proxy.

There are no blockers. The main concern (FE-WR-01): D-26 changed what the leftover `: 'NETWORK_ERROR'` fallbacks catch. Each of these fallbacks handles a thrown value that is not an `ApiClientError`. Before this phase, that meant a fetch reject. Now it means a strict-mapper `TypeError` or a programming error. In the three write dialogs this misreports a write that actually succeeded as a network error. The SC5 "not masked by mock" assertion is vacuous for Overview and Cash (FE-WR-02). Backtest's capital input skips the validation every other money form applies (FE-WR-03). D-28 still has hard-coded up/down `rgba` colours in two in-scope pages (FE-WR-04).

Known items not re-reported: `ci.yml:53` interpolates `head_ref` into `run:`. The OrderTicket estimate excludes the fee. big.js normalises trailing zeros.

### Narrative Findings (AI reviewer)

### Warnings

#### FE-WR-01: The non-`ApiClientError` fallback is still labelled `NETWORK_ERROR` and now hides contract or mapper failures, including after a successful write

**File:**
- `stock-web-v2-front-end/vue-app/src/components/OrderTicket.vue:1454-1457`
- `stock-web-v2-front-end/vue-app/src/components/CashEntryDialog.vue:681`
- `stock-web-v2-front-end/vue-app/src/components/HoldingEventDialog.vue:1584`
- `stock-web-v2-front-end/vue-app/src/components/WatchlistPicker.vue:231`
- `stock-web-v2-front-end/vue-app/src/pages/Watchlist.vue:379,467`
- `stock-web-v2-front-end/vue-app/src/pages/Settings.vue:352`
- `stock-web-v2-front-end/vue-app/src/pages/Overview.vue:680`
- `stock-web-v2-front-end/vue-app/src/pages/Markets.vue:206`
- `stock-web-v2-front-end/vue-app/src/pages/Chart.vue:223`
- `stock-web-v2-front-end/vue-app/src/baseCurrencyErrorKey.ts:13`

**Issue:** After D-26, `apiClient.transportFetch` (`apiClient.ts:186-198`) turns every fetch reject into `ApiClientError{status:0, code:'NETWORK_ERROR'}`. A thrown value that is not an `ApiClientError` can therefore no longer be a network failure. It is now either a `TypeError` from a strict mapper (`parseDecimal` refuses JSON numbers, as designed in T-05-27) or a programming error. These call sites still treat such a value as `NETWORK_ERROR`.

Concrete path:
1. `tradingApi.createHttpTradingApi.createTrade` runs `toTradeRecord(await apiRequest(...))`.
2. The backend answers 201 but sends a BigDecimal field as a number (version skew under the D-07 hard cut) or omits a field.
3. `toTradeRecord` throws `TypeError` after the trade was recorded.
4. `OrderTicket.handleSubmitFailure` displays `tradeErrNetwork`: "無法連線到伺服器，這筆交易可能尚未記錄。直接再送出一次不會建立重複交易。"
5. The idempotency key is kept. A retry replays as 201 and fails the same way, so the user is stuck in a loop.

The same happens in `CashEntryDialog` (`toCashEntry`) and `HoldingEventDialog` (`toDividend` / `toShareAdjustment`). Positions, Trades and Cash fall back to `UNKNOWN_ERROR` instead (`Positions.vue:519`, `Trades.vue:457`, `Cash.vue:275`). The same mapper failure therefore shows different diagnostics on different pages. That undoes the "one consistent diagnostic code" goal of D-26, and the phase's "fail loudly at the boundary" design ends up reported as a connectivity problem.

**Fix:** Convert mapper failures into an envelope-level error at the service boundary. Then make every leftover fallback stop claiming it was a network failure:
```ts
// services/apiClient.ts (export) — reuse in every http adapter
export async function apiRequestMapped<W, D>(path: string, map: (w: W) => D, options?: ApiRequestOptions): Promise<D> {
  const data = await apiRequest<W>(path, options);
  try { return map(data); } catch {
    throw new ApiClientError({ status: 200, code: 'INVALID_API_RESPONSE', message: 'Response did not match the contract', requestId: null });
  }
}
// components / pages
: { code: 'UNKNOWN_ERROR', traceId: null, fields: null, status: 0 };   // not NETWORK_ERROR
// baseCurrencyErrorKey.ts
if (!(error instanceof ApiClientError)) return 'baseCurrencyErrUnknown';
if (error.code === 'NETWORK_ERROR') return 'baseCurrencyErrNetwork';
```
Add a unit test where the 201 body has `quantity: 3` (a JSON number) and assert that the ticket does not show `tradeErrNetwork`.

#### FE-WR-02: SC5 "not masked by mock" sentinels cannot fire for Overview (and Cash), so D-20 is not actually verified there

**File:**
- `stock-web-v2-front-end/vue-app/e2e/tests/api-failure-states.spec.ts:164-167,234-237`
- `stock-web-v2-front-end/vue-app/e2e/support/mockSentinels.ts:28-33`

**Issue:** The Overview case scopes the sentinel check to `overview-summary-error` or `overview-kpi`, with `sentinels: [...symbols, ...PRICE_SENTINELS]`. The KPI cards never show a symbol or a position price. In mock mode they show `'+$12,481'`, `'$84,210'`, `'8.4%'`, `'18.4%'` and `fmtNum(last, 0)` (`Overview.vue:766-771`), and none of these are sentinels. If the API-mode summary block ever fell back to `mockKpis`, both sentinel assertions would still pass. D-20 explicitly names "mock KPI 數字" as a sentinel. The Cash case has the same gap: its mock ledger notes (`'Initial deposit'`, `'Recorded by mistake'`, from `cashApi.seedEntries`) are not sentinels, and the trade notes checked there never appear in cash rows. The `assertReal` check for Cash (`sc5-seed` count = 1) would not catch a mixed real-plus-mock list either.

**Fix:** Add mock-only KPI and cash values to `MOCK_SENTINELS`, and extend `mockSentinels.test.ts` so it checks that they still exist in mock output:
```ts
kpis: ['+$12,481', '$84,210', '18.4%'],
cashNotes: ['Initial deposit', 'Recorded by mistake'],
```
Then use `sentinels: [...MOCK_SENTINELS.kpis, ...]` for Overview and `[...MOCK_SENTINELS.cashNotes, ...]` for Cash. Also assert one backend value in Overview `assertReal` (for example the holding-count or market-value KPI that the AAPL 3 @ 123.45 seed produces).

#### FE-WR-03: Backtest initial capital accepts exponent notation, unbounded magnitude and more than 8 decimals

**File:** `stock-web-v2-front-end/vue-app/src/pages/Backtest.vue:320-328`

**Issue:** The `type="number"` input was replaced with a text input that is validated only by `parseDecimalInput(...)` and `> 0`. `parseDecimalInput` hands the text straight to big.js, which accepts `1e5`, `1e400` and `0.000000000000000000001`. The other three money forms (OrderTicket `DECIMAL_INPUT_PATTERN` plus `MAX_DECIMALS`, CashEntryDialog `AMOUNT_PATTERN` plus `AMOUNT_MAX`, HoldingEventDialog `DECIMAL_PATTERN` plus `MONEY_MAX`) all reject these.

For `1e400`:
- `toWire(capital)` sends a 401-digit `initialCapital` string to the backend.
- `toChartNumber(capital)` returns `Infinity`, which `makeRunConfig` uses for chart axis labels.

Values with more than 8 decimals are only caught by the backend.

**Fix:** Apply the same plain-decimal grammar, scale and upper bound before submit. A shared helper would avoid a fourth hand-written copy:
```ts
const CAPITAL_PATTERN = /^(\d+\.?\d*|\.\d+)$/;
const text = initialText.value.trim();
const capital = CAPITAL_PATTERN.test(text) ? parseDecimalInput(text) : null;
if (capital === null || capital.lte(ZERO) || !capital.round(8).eq(capital) || capital.gt(MONEY_MAX)) { /* show error */ }
```

#### FE-WR-04: D-28 is incomplete: hard-coded up/down `rgba` colours remain in in-scope pages and do not swap in red-up mode

**File:**
- `stock-web-v2-front-end/vue-app/src/pages/Backtest.vue:141,372-373`
- `stock-web-v2-front-end/vue-app/src/pages/Analytics.vue:835-839`

**Issue:** D-28 says hard-coded `rgba` literals such as `.pill.buy` must be replaced with tokens that swap. The phase migrated `.pill.*` and `.metric-delta.*` in these same files but left three spots:
- **Backtest drawdown path:** `fill="rgba(220,38,38,0.18)"` with `stroke="var(--dn)"`. In `data-up-color="red"` mode the stroke turns green but the fill stays red.
- **Backtest monthly-return heatmap (`:372-373`):** gains are hard-coded green and losses red, so it shows the opposite of every other up/down colour when red-up is active.
- **Analytics `.harvest-btn`:** keeps a red `rgba` background and border around `color: var(--dn)`.

**Fix:**
```html
<path :d="ddPath" fill="color-mix(in oklch, var(--dn) 18%, transparent)" stroke="var(--dn)" ... />
```
```ts
return c >= 0
  ? `color-mix(in oklch, var(--up) ${Math.round((0.15 + a * 0.5) * 100)}%, transparent)`
  : `color-mix(in oklch, var(--dn) ${Math.round((0.15 + a * 0.5) * 100)}%, transparent)`;
```
```css
.harvest-btn { background: color-mix(in oklch, var(--dn) 10%, transparent); border: 1px solid color-mix(in oklch, var(--dn) 25%, transparent); }
.harvest-btn:hover { background: color-mix(in oklch, var(--dn) 18%, transparent); }
```
Extend `contrastTokens.test.ts` (or a source guard) to reject `rgba(22,163,74` / `rgba(220,38,38` / `rgba(239,68,68` in `src/pages` and `src/components`.

### Info

#### FE-IN-01: Watchlist price formatting quietly moved from fixed `en-US` to the runtime default locale

**File:**
- `stock-web-v2-front-end/vue-app/src/pages/Watchlist.vue:923`
- `stock-web-v2-front-end/vue-app/src/pages/Overview.vue:965`

**Issue:** `fixed()` / `wlFixed()` used to call `toLocaleString('en-US', ...)`. They now call `fmtMoneyAmount`, whose `fixedFormatter` uses `new Intl.NumberFormat(undefined, …)`. In a browser with a de-DE or fr-FR locale, watchlist prices now render as `1.234,56` or `1 234,56`. Meanwhile quantities (`QTY_FORMAT`) stay pinned to `en-US`, and comments elsewhere say display is "與改字串前相同" (unchanged from before).

**Fix:** Either pin `fixedFormatter` to `'en-US'` to match `QTY_FORMAT`, or give the watchlist an en-US Decimal formatter. Document whichever you choose.

#### FE-IN-02: Trades CSV `total` is the frontend product, not the backend's authoritative `amount`

**File:** `stock-web-v2-front-end/vue-app/src/pages/Trades.vue:990` (`tradeTotal`, `:815-818`)

**Issue:** For BUY/SELL rows the CSV writes `toWire(quantity.times(price))`. That product can have up to 16 decimals (scale 8 × scale 8). The backend's `PortfolioActivityDto.amount` for the same row is rounded to scale 8, so exported totals can differ from the server value in the low digits.

**Fix:** Use `row.amount ?? tradeTotal(row)` for the CSV `total` column (and optionally for display too), so the file reflects the backend value.

#### FE-IN-03: Some real-backend E2E assertions are weaker than they look

**File:**
- `stock-web-v2-front-end/vue-app/e2e/tests/contract-journey.spec.ts:170`
- `stock-web-v2-front-end/vue-app/e2e/tests/api-failure-states.spec.ts:191`

**Issue:**
- `holdingQuantity` uses `Number(holding.totalQuantity)` without first checking that the wire value is a string. `journeyApi.decimalValue` exists for exactly this, so a regression back to a JSON number would pass J0.
- The SC5 positions `assertReal` uses `toContainText('3')`, which also matches the price `123.45` in the same row, so the quantity is never really checked.

**Fix:** Use `decimalValue(holding.totalQuantity, 'totalQuantity')`. In SC5, match the quantity cell itself (for example `toHaveText(/\s3(?:\.0+)?\s/, { useInnerText: true })`, as J0 already does).

#### FE-IN-04: `VITE_API_PROXY_TARGET` is named like a client variable and an empty value is not rejected

**File:** `stock-web-v2-front-end/vue-app/vite.config.ts:19`

**Issue:** Two problems:
- **Misleading name:** the `VITE_` prefix suggests a `.env` entry works. A value in `.env.local` would reach `import.meta.env` but not this Node-side proxy, which reads `process.env` only.
- **Empty value:** `VITE_API_PROXY_TARGET=` (empty string) gets past `??` and produces proxy target `''`.

**Fix:** Use `process.env.VITE_API_PROXY_TARGET || 'http://localhost:11180'`. Either rename the variable to a non-`VITE_` name (for example `API_PROXY_TARGET`) or note in the getting-started docs that `.env` files are ignored for it.

#### FE-IN-05: The "never goes through JS number" formatting depends on Intl.NumberFormat v3 with no fallback

**File:** `stock-web-v2-front-end/vue-app/src/moneyFormat.ts:57-59`

**Issue:** `formatter.format(digits as Intl.StringNumericLiteral)` only keeps full precision where string input is supported (Chrome 106+, Safari 15.4+, Firefox 116+). Older engines convert the string with `ToNumber` without any error, so values with 16 or more significant digits get rounded on screen. The `as` cast hides this from the type checker.

**Fix:** Either document the minimum browser versions, or add a pure-string grouping fallback (split integer and fraction, insert separators) when feature detection (`new Intl.NumberFormat('en', { maximumFractionDigits: 2 }).format('12345678901234567.89' as never) !== '12,345,678,901,234,567.89'`) fails.

---

_Reviewed: 2026-11-12_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
