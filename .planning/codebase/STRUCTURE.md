---
last_mapped_commit: adae04117fcfa99db40b8ca304fb5cb9c5e36ec9
last_mapped_at: 2026-11-07
---
# Codebase Structure

**Analysis Date:** 2026-11-07

> 兩個 sibling repo:後端(本 repo)與前端 repo 根(本機/cloud 路徑對照見 `ai-docs/judgment.md` §8)。前端路徑相對於前端 repo 根。

## Directory Layout

### 後端(本 repo)

```
stock-web-v2/
├── pom.xml                      # parent pom(Java 21、模組清單、Spotless/JaCoCo/ArchUnit/Testcontainers 版本)
├── mvnw, mvnw.cmd               # Maven wrapper
├── AGENTS.md                    # Agent 路由與專案約束
├── ai-docs/                     # 制度與契約(architecture、code-standards、browser-auth-contract、trading-portfolio-contract、watchlist-contract、judgment…)
├── docs/                        # plans/、reviews/、superpowers/ 歷史計畫與審查
├── scripts/                     # run-dev.ps1、GenerateJwtJwk.java
├── stock-common/                # L0 共用 API 信封、錯誤碼、enum、事件
├── stock-db-migration/          # L0 Flyway SQL(db/migration/V1..V19)
├── stock-infrastructure/        # L1 Facade 介面、security、web、redis、audit
├── stock-module-user/           # L2 auth、cookie/CSRF、使用者偏好、admin
├── stock-module-asset/          # L2 資產、觀察清單、FX
├── stock-module-trading/        # L2 交易、持倉、現金、股利、股數調整、portfolio
├── stock-module-backtest/       # L2 回測 run / engine
├── stock-module-market-data/    # L2 ingest、Kafka consumer、K 線、WS、backfill
├── stock-start/                 # L3 啟動、SecurityConfig、全域例外、整合測試
└── .planning/                   # GSD 規劃文件
```

每個 L2 模組內部套件:`dowob/xyz/stockwebv2/<module>/{api,service,domain,repository}`;market-data 另有 `batch/ config/ consumer/ ingest/ observability/ persistence/ provider/ ws/`,backtest 另有 `engine/`。

### 前端(前端 repo 根)

```
<前端 repo 根>/
├── AGENTS.md, CLAUDE.md
├── docs/                        # api-contracts/mock-to-real-contract.md、LEARNINGS.md、superpowers/
├── references/react-prototypes/ # 舊 React 視覺原型(僅參考)
├── .github/workflows/           # CI(Node 20:npm ci → lint → test → build)
└── vue-app/                     # 真正的專案根(npm 在此執行)
    ├── package.json, vite.config.ts, tsconfig.json, eslint.config.js, playwright.config.ts, index.html
    ├── e2e/                     # Playwright:tests/*.spec.ts、support/、docker-compose.yml、run-e2e.sh
    └── src/
        ├── main.ts, App.vue, router.ts, styles.css, i18n.ts, types.ts, data.ts
        ├── pages/               # 路由頁面(Overview、Markets、Watchlist、Positions、Backtest、Trades、Cash、Settings、Ops…)
        ├── components/          # 共用元件與對話框(OrderTicket、CashEntryDialog、HoldingEventDialog…);settings/ 子目錄
        ├── composables/         # useLiveQuotes、useQuoteRefresh、useNow、useAiAccessSettings
        ├── services/            # apiClient、apiTypes、runtimeDataMode、pageApiClients、*Api.ts、quoteStream、authSession
        ├── stores/              # mock 資料 store(mockPortfolio、mockNotifications、mockPreview)
        └── *.ts                 # 純函式 view/格式工具(moneyFormat、marketDate、cashBalanceView、csvCell…)
```

## Directory Purposes

**`stock-common/src/main/java/dowob/xyz/stockwebv2/common/`:**
- Purpose: 跨所有模組的型別
- Key files: `api/ApiResponse.java`、`api/PageResponse.java`、`error/ErrorCode.java`、`error/BusinessException.java`、`event/PriceTickEvent.java`

**`stock-db-migration/src/main/resources/db/migration/`:**
- Purpose: 唯一 schema 來源;規範見 `ai-docs/flyway-convention.md`
- Key files: `V7__trading_schema.sql`、`V8__transactions_append_only_trigger.sql`、`V5__market_data_continuous_aggregates.sql`

**`stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/`:**
- Purpose: Facade 介面與橫切設施
- Key files: `marketdata/MarketDataFacade.java`、`asset/AssetFacade.java`、`fx/FxRateFacade.java`、`user/UserPreferenceFacade.java`、`security/JwtService.java`、`web/TraceIdFilter.java`、`web/ApiMetaFactory.java`

**`stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/`:**
- Key files: `api/TradingController.java`、`api/CashLedgerController.java`、`api/CashDividendController.java`、`api/ShareAdjustmentController.java`、`api/PortfolioActivityController.java`、`service/TradingService.java`、`service/PortfolioQueryService.java`、`domain/HoldingCalculator.java`

**`stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/`:**
- Key files: `api/MarketController.java`、`api/WsTicketController.java`、`ws/MarketWebSocketHandler.java`、`consumer/PriceWriterConsumer.java`、`consumer/WsBroadcastConsumer.java`、`ingest/ScheduledIngestor.java`

**`stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/`:**
- Key files: `api/AuthController.java`、`api/CsrfController.java`、`service/BrowserAuthCookieService.java`、`service/RefreshTokenService.java`

**`stock-start/`:**
- Key files: `src/main/java/.../start/config/SecurityConfig.java`、`src/main/java/.../start/error/GlobalExceptionHandler.java`、`src/main/resources/application*.yaml`、`src/test/java/.../start/ArchitectureRulesTest.java`、`src/test/java/.../start/*IT.java`

**`vue-app/src/services/`(前端):**
- Purpose: 前後端唯一邊界
- Key files: `apiClient.ts`、`pageApiClients.ts`、`runtimeDataMode.ts`、`quoteStream.ts`、`apiTypes.ts`

## Key File Locations

**Entry Points:**
- `stock-start/src/main/java/dowob/xyz/stockwebv2/start/StockWebV2Application.java`: 後端 Spring Boot 入口
- `vue-app/src/main.ts`: 前端入口(Pinia + router 掛載 `App.vue`)

**Configuration:**
- `pom.xml`、各模組 `pom.xml`
- `stock-start/src/main/resources/application.yaml`(+ `application-dev.yaml`、`application-demo.yaml`、`application-e2e-browser.yaml`)、`logback-spring.xml`
- `vue-app/vite.config.ts`(dev proxy)、`vue-app/tsconfig.json`、`vue-app/eslint.config.js`、`vue-app/playwright.config.ts`

**Core Logic:**
- 後端:`stock-module-*/src/main/java/dowob/xyz/stockwebv2/<module>/service/`、`domain/`
- 前端:`vue-app/src/services/*Api.ts`、`vue-app/src/pages/`

**Testing:**
- 模組單元測試:`stock-module-*/src/test/java/...`(如 `trading/domain/`)
- 整合 / 架構測試:`stock-start/src/test/java/dowob/xyz/stockwebv2/start/`(`*IT.java`、`ArchitectureRulesTest.java`、`EndpointAuthorizationRulesTest.java`)
- 前端:`vue-app/src/**/*.test.ts`(co-located,Vitest);E2E `vue-app/e2e/tests/*.spec.ts`

## Naming Conventions

**Files(後端):**
- Controller:`XxxController.java`;Request DTO:`CreateXxxRequest.java` / `XxxRequest.java`;回應:`XxxDto.java` / `XxxResponse.java`
- Repository:介面 `XxxRepository.java` + 實作 `JdbcXxxRepository.java`
- Facade:介面 `XxxFacade.java`(L1)+ `XxxFacadeImpl.java`(擁有模組)
- 純規則:`XxxCalculator.java`、`XxxPayloadMatcher.java`
- 整合測試:`XxxIT.java`;單元測試:`XxxTest.java`
- Migration:`V{n}__snake_case_description.sql`

**Files(前端):**
- 頁面/元件:PascalCase `.vue`(`OrderTicket.vue`)
- service:camelCase `xxxApi.ts`;composable:`useXxx.ts`;mock store:`mockXxx.ts`
- 測試:同名 `.test.ts` 與原始檔並列

**Directories:**
- Maven 模組:`stock-<layer>` / `stock-module-<domain>`(kebab-case);Java package `dowob.xyz.stockwebv2.<module>`(market-data → `marketdata`)

## Where to Add New Code

**新後端 API(既有領域):**
- Controller/DTO:`stock-module-<domain>/src/main/java/dowob/xyz/stockwebv2/<module>/api/`
- 邏輯:`.../service/`;純規則:`.../domain/`;持久化:`.../repository/JdbcXxxRepository.java`
- Schema:`stock-db-migration/src/main/resources/db/migration/V20__....sql`(取下一個號碼)
- 授權規則:`stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java`
- Tests:單元在模組 `src/test/java`;整合 `stock-start/src/test/java/dowob/xyz/stockwebv2/start/XxxApiIT.java`

**跨模組需求:**
- 介面:`stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/<domain>/XxxFacade.java`
- 實作:擁有資料的模組 `service/XxxFacadeImpl.java`

**新錯誤碼 / 共用 enum:** `stock-common/src/main/java/dowob/xyz/stockwebv2/common/{error,model}/`

**新前端功能:**
- 頁面:`vue-app/src/pages/Xxx.vue`,並在 `vue-app/src/router.ts` `pageRoutes` 與 `vue-app/src/App.vue` 註冊
- API:`vue-app/src/services/xxxApi.ts`(`createXxxApi(mode, basePath)`,mock + api 實作),掛到 `vue-app/src/services/pageApiClients.ts`
- 型別:`vue-app/src/services/apiTypes.ts`;mock 資料:`vue-app/src/stores/`
- Tests:同目錄 `Xxx.test.ts`;E2E `vue-app/e2e/tests/xxx.spec.ts`

**Utilities:**
- 前端純函式:`vue-app/src/*.ts`(如 `moneyFormat.ts`);可重用狀態邏輯:`vue-app/src/composables/`

## Special Directories

**`target/`(後端各模組)、`vue-app/dist/`、`vue-app/node_modules/`、`vue-app/test-results/`、`vue-app/e2e/artifacts/`:**
- Purpose: 建置 / 測試產物
- Generated: Yes
- Committed: No

**`.planning/`:**
- Purpose: GSD 規劃與 codebase map
- Generated: 部分(由 GSD 工具)
- Committed: Yes

**`docs/superpowers/`(兩 repo):**
- Purpose: 2026-05 歷史計畫/設計,不隨程式維護
- Generated: No
- Committed: Yes

**`references/react-prototypes/`(前端 repo 根):**
- Purpose: 舊 UI 原型,非程式碼
- Committed: Yes

---

*Structure analysis: 2026-11-07*
