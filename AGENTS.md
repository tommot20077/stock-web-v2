# gstack

Use the `/browse` skill from gstack for all web browsing. Never use `mcp__claude-in-chrome__*` tools.

Available gstack skills:

- `/office-hours`
- `/plan-ceo-review`
- `/plan-eng-review`
- `/plan-design-review`
- `/design-consultation`
- `/design-shotgun`
- `/design-html`
- `/review`
- `/ship`
- `/land-and-deploy`
- `/canary`
- `/benchmark`
- `/browse`
- `/connect-chrome`
- `/qa`
- `/qa-only`
- `/design-review`
- `/setup-browser-cookies`
- `/setup-deploy`
- `/setup-gbrain`
- `/retro`
- `/investigate`
- `/document-release`
- `/document-generate`
- `/codex`
- `/cso`
- `/autoplan`
- `/plan-devex-review`
- `/devex-review`
- `/careful`
- `/freeze`
- `/guard`
- `/unfreeze`
- `/gstack-upgrade`
- `/learn`

<!-- GSD:project-start source:PROJECT.md -->

## Project

**Stock Web V2**

Stock Web V2 is a modular stock trading web application with a Java/Spring Boot backend and a sibling Vue frontend. The backend already provides auth, assets, market data, WebSocket tickets, backtests, and trading/portfolio APIs; the next product step is to connect the Vue app to those APIs through a safe browser auth contract while preserving mock mode for demos and frontend development.

**Core Value:** Users can safely sign in, inspect portfolio state, and record trades through one coherent frontend/backend flow.

### Constraints

- **Tech stack**: Keep Java 21/Spring Boot backend modules and Vue 3/Vite frontend; do not introduce a new application framework for this milestone.
- **Repository layout**: Backend and frontend are sibling repositories, so cross-repo work must make file ownership and verification commands explicit.
- **Security**: Browser cookie auth must include CSRF protection before unsafe endpoints rely on cookies.
- **Compatibility**: Preserve mock mode for frontend demos and development while adding API mode.
- **API contract**: REST responses should continue using the common `ApiResponse<T>` envelope and existing backend error semantics.
- **Trading semantics**: Treat current backend trading API as executed manual trades, not live broker orders.
- **Verification**: Backend changes need Maven tests; frontend changes need type-check/build and focused Vitest coverage where affected.
- **AI institution**: Judgment rubrics, model dispatch, task briefs, and the maintenance protocol live in `ai-docs/judgment.md`, `ai-docs/model-dispatch.md`, `ai-docs/task-briefs.md`, `ai-docs/maintenance-protocol.md` — read on demand, do not inline them here.

<!-- GSD:project-end -->

<!-- GSD:stack-start source:codebase/STACK.md -->

## Technology Stack

## Languages

- Java 21 — 後端全部模組（`pom.xml` 的 `<java.version>21</java.version>`，`maven-compiler-plugin` 用 `<release>`）
- TypeScript 6（`^6.0.3`）— 前端 `vue-app/src/`（`vue-app/tsconfig.json`）
- SQL（PostgreSQL / TimescaleDB 方言）— Flyway migration `stock-db-migration/src/main/resources/db/migration/V*.sql`
- Vue SFC（`.vue`）— 前端元件
- Bash / PowerShell — `scripts/run-dev.ps1`、前端 `vue-app/e2e/run-e2e.sh`
- 單檔 Java script — `scripts/GenerateJwtJwk.java`（`java scripts/GenerateJwtJwk.java <kid>` 產生 JWK）

## Runtime

- 後端：JVM 21（CI 用 Temurin 21，`.github/workflows/ci.yml`）；Spring Boot 內嵌 Tomcat，`server.port` 預設 `11180`，management port `11181`（`stock-start/src/main/resources/application.yaml`）
- 前端：瀏覽器 SPA；開發 / 建置用 Node.js 20（前端 `.github/workflows/ci.yml`、後端 Browser E2E job）
- 時區：`spring.jackson.time-zone: Asia/Taipei`
- 後端：Maven（Maven Wrapper `mvnw` / `mvnw.cmd`）；多模組 reactor
- 前端：npm；Lockfile：`vue-app/package-lock.json` 存在（CI 用 `npm ci`）

## Frameworks

- Spring Boot 4.0.4（`spring-boot-starter-parent`，root `pom.xml`）
- Spring Web MVC（`spring-boot-starter-webmvc`）— REST API（`/api/v1/**`、`/api/admin`）
- JDBC 持久層：手寫 `Jdbc*Repository` + `JdbcClient`（market-data 批次寫入用 `NamedParameterJdbcTemplate`）；非 JPA、也不是 Spring Data JDBC repository —— `spring-boot-starter-data-jdbc` 只提供 JDBC 基礎設施
- Spring Security + `spring-security-oauth2-jose` — JWT（EC P-256 JWK）簽發 / 驗證（`stock-infrastructure`）
- Spring WebSocket（`spring-boot-starter-websocket`）— 行情推播 `/ws/v1/market`（`stock-module-market-data/.../config/WsConfig.java`）
- Spring Kafka（`spring-kafka`、`spring-boot-kafka`）— 行情 tick / backfill 事件管線
- Spring Batch（`spring-boot-starter-batch`）— 歷史資料 backfill job（`spring.batch.job.enabled: false`，只由 `BackfillJobLauncher` 觸發）
- Spring Data Redis（`spring-boot-starter-data-redis`）
- Flyway（`spring-boot-starter-flyway` + `flyway-database-postgresql`）
- Vue `^3.5.34`
- Pinia `^3.0.4` — 狀態管理
- Vue Router `^5.0.7`
- HTTP：原生 `fetch`（無 axios、無 UI 框架）；WebSocket：原生 `WebSocket`，只在 `vue-app/src/services/quoteStream.ts` 建立
- JUnit 5 + `spring-boot-starter-test`、`spring-boot-starter-webmvc-test`、`spring-security-test`
- Testcontainers 1.21.4（BOM）— `timescale/timescaledb:2.17.2-pg16`、`redis:7.4-alpine`、`confluentinc/cp-kafka:7.6.0`
- `spring-kafka-test`、Awaitility、AssertJ
- H2（`stock-start` test scope）
- ArchUnit 1.3.0（`archunit-junit5`）— 模組邊界守門
- Surefire（unit）/ Failsafe（integration），`-Pe2e` profile 跑 E2E
- Vitest `^4.1.6`（jsdom `^29.1.1`，`vue-app/vite.config.ts` 的 `test` 區塊，`setupFiles: ./src/testSetup.ts`）
- Playwright `^1.61.1`（`vue-app/playwright.config.ts`、`vue-app/e2e/`）
- Spotless 2.44.5 — 只鎖 import 順序（`dowob,jakarta,org,com,java,\#`）、移除未用 import、空白衛生；綁 `validate` phase
- JaCoCo 0.8.13 — 只出報告、無門檻
- `spring-boot-maven-plugin`（`stock-start` 打包可執行 jar）
- springdoc-openapi 3.0.2（`springdoc-openapi-starter-webmvc-ui`），預設關閉
- 前端：Vite `^8.0.13` + `@vitejs/plugin-vue ^6.0.7`、`vue-tsc ^3.2.9`（`npm run build` = `vue-tsc --noEmit && vite build`）
- 前端 lint / format：ESLint `^10.9.1`（`vue-app/eslint.config.js`，`typescript-eslint`、`eslint-plugin-vue`、`eslint-config-prettier`）、Prettier `^3.9.6`（`vue-app/.prettierrc.json`）

## Key Dependencies

- `spring-security-oauth2-jose` — JWT 簽章；金鑰為 EC JWK（`stock.jwt.private-key`），PEM 需經 `JwkKeyConverter` 轉換
- `postgresql` JDBC driver + `flyway-database-postgresql` — 主資料庫
- `spring-kafka` — 行情 ingest → `PriceWriterConsumer` / `WsBroadcastConsumer`
- `jackson-databind` / `jackson-datatype-jsr310` — JSON 與時間型別（`stock-common`）
- `commons-lang3`（`stock-common`、`stock-module-trading`）
- `spring-boot-starter-actuator` + `micrometer-core` — health / metrics（`stock-start`、`stock-module-market-data`）
- `jakarta.servlet-api`（`stock-infrastructure`）

## Configuration

- Spring profiles：`dev`（預設）、`demo`、`e2e-browser`（`stock-start/src/main/resources/application-*.yaml`）；測試用 `application-test.yaml`、`application-e2e.yaml`（`stock-start/src/test/resources/`）
- `dev` profile 以 `spring.config.import: optional:file:.env[.properties]` 讀 repo 根 `.env`；範本為 `.env.example`（存在，不得提交真值）
- 關鍵設定：`STOCK_DB_URL`、`STOCK_DB_USERNAME`（及密碼）、`STOCK_REDIS_HOST/PORT/DATABASE`、`STOCK_KAFKA_BOOTSTRAP_SERVERS`、`STOCK_JWT_PRIVATE_KEY`（非 dev/test/e2e profile 留空會拒絕啟動）、`STOCK_CORS_ALLOWED_ORIGINS`、`STOCK_AUTH_COOKIE_*`、`STOCK_FORWARD_HEADERS_STRATEGY`、`STOCK_OPENAPI_ENABLED`、`STOCK_SWAGGER_UI_ENABLED`、`SERVER_PORT`、`STOCK_MANAGEMENT_PORT`
- 前端：`VITE_DATA_MODE`（`mock` | `api`，未設為 mock；無效值 fail fast，邏輯在 `vue-app/src/services/runtimeDataMode.ts`）；無 base URL 設定，`vue-app/src/services/apiClient.ts` 一律送同源相對路徑
- 後端：root `pom.xml`、各模組 `*/pom.xml`
- 前端：`vue-app/vite.config.ts`（dev `5173` / preview `4173`，proxy `/api`、`/ws` → `$VITE_API_PROXY_TARGET`（預設 `http://localhost:11180`，與後端 `server.port` 預設一致；Browser E2E 由 Playwright 設為 8080））、`vue-app/tsconfig.json`、`vue-app/eslint.config.js`、`vue-app/playwright.config.ts`

## Platform Requirements

- JDK 21、Docker（Testcontainers 整合測試）、Node.js 20
- 可連線的 PostgreSQL（含 TimescaleDB extension）、Redis、Kafka（`.env.example` 指向內網 K3s NodePort）
- 部署於 K3s ingress 之後（`server.forward-headers-strategy: native` 的註解；`ai-docs/architecture.md`、`ai-docs/security.md`）；repo 內無 Dockerfile / K8s manifest
- 前端為靜態 SPA（`vue-app/dist/`），需與後端同源（或由反向代理轉發 `/api`、`/ws`）

<!-- GSD:stack-end -->

<!-- GSD:conventions-start source:CONVENTIONS.md -->

## Conventions

- **後端**(主 repo):本 repo 根,Java 21 / Spring Boot 4.0.4 多模組 Maven。
- **前端**:前端 repo 根(本機 `../../vue/stock-v2`、cloud `../stock-web-v2-front-end`,路徑對照見 `ai-docs/judgment.md` §8)。程式碼在 `vue-app/` 下;以下前端路徑皆相對於前端 repo 根。

## Naming Patterns

- 一個 public type 一個檔,檔名 = 類別名(PascalCase)。
- 套件根 `dowob.xyz.stockwebv2.<module>`,模組內固定分層子套件:`api/`(Controller、Request/Dto)、`service/`、`domain/`、`repository/`。範例:`stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/{api,service,domain,repository}/`。
- 字尾約定:
- 元件 / 頁面:PascalCase `.vue`(`vue-app/src/components/OrderTicket.vue`、`vue-app/src/pages/Trades.vue`);頁面允許單字命名(ESLint 已關 `vue/multi-word-component-names`)。
- service:camelCase + `Api` 字尾(`vue-app/src/services/tradingApi.ts`、`watchlistApi.ts`);transport 唯一檔 `apiClient.ts`;DTO 型別集中在 `apiTypes.ts`。
- composable:`use*` 前綴(`vue-app/src/composables/useLiveQuotes.ts`)。
- mock store:`mock*` 前綴(`vue-app/src/stores/mockPortfolio.ts`),只能被 service 的 mock adapter import。
- 純函式工具:camelCase(`vue-app/src/moneyFormat.ts`、`csvCell.ts`)。
- Java:camelCase 動詞開頭(`createTrade`、`listTrades`);record accessor 風格的 getter 不加 `get`(`BusinessException.errorCode()`、`fields()`)。
- TS:camelCase;adapter 工廠統一 `createHttpXxxApi` / `createMockXxxApi` / `createXxxApi`(`vue-app/src/services/tradingApi.ts`)。
- Java 常數 `UPPER_SNAKE_CASE`,數字用底線分隔(`MAX_PAGE = 10_000`);每個非顯然常數附 Javadoc 說明「為什麼是這個值」。
- TS 未使用變數 / 參數以 `_` 前綴豁免(ESLint `argsIgnorePattern: '^_'`)。
- Java:DTO / Request / 值物件優先用 `record`(main 原始碼約 77 個 public record);錯誤碼為 `enum ErrorCode`(`stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/ErrorCode.java`),每個常數帶 HTTP status 與靜態訊息,例:`VALIDATION_FAILED(400, "Validation failed")`。
- TS:`interface` 描述 API 形狀,欄位名逐字對應後端 record(`CreateTradeRequest` 對 `CreateTradeRequest.java`),並在 JSDoc 註明後端 `file:line` 出處;字串聯集取代 enum(`type: 'BUY' | 'SELL'`)。

## Code Style

- Spotless `2.44.5`(根 `pom.xml`),綁 `validate` phase,每個 Maven build 都會 check;CI 另有獨立 `Lint` job 跑 `./mvnw -B spotless:check`。
- 規則:`removeUnusedImports`、`trimTrailingWhitespace`、`endWithNewline`、固定 import 順序。**沒有** google-java-format,縮排沿用既有風格:4 空白;多行參數清單每個參數一行、4 空白續行、`)` 獨立一行:
- 格式出錯時用 `./mvnw spotless:apply` 修正。
- Prettier(`vue-app/.prettierrc.json`):`singleQuote: true`、`semi: true`、`printWidth: 100`、`trailingComma: "all"`、`arrowParens: "always"`;執行 `npm run format`。2 空白縮排。
- SFC 一律 `<script setup lang="ts">`(`vue-app/src` 下 34/34 個 `.vue`)。
- ESLint 10 flat config `vue-app/eslint.config.js`:`@eslint/js` recommended + `typescript-eslint` recommended + `eslint-plugin-vue` `flat/essential` + `eslint-config-prettier`。
- 關鍵規則:`@typescript-eslint/no-explicit-any: warn`(ratchet,清零後改 error)、`vue/no-mutating-props: warn`(ratchet)、`@typescript-eslint/no-unused-vars: error`、`no-empty` 允許空 catch。
- 新程式碼不得新增 `any` 或 mutate prop 的 warning。

## Import Organization

## Error Handling

- 業務錯誤一律丟 `BusinessException(ErrorCode, staticMessage[, fields])`(`stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/BusinessException.java`);欄位驗證錯誤用子類 `FieldValidationException`。
- **訊息與 `fields` 必須是靜態字串,不得回射使用者輸入**(2026-09-02 安全審查 L-1);測試以 canary 值驗證(`TradingServiceTest` 的 `CANARY_KEY`)。
- 統一轉換在 `stock-start/.../error/GlobalExceptionHandler.java`;所有 `@ExceptionHandler` 必須回 `ResponseEntity<ApiResponse<Void>>` 並帶明確 status:
- 回應信封 `ApiResponse<T>`(`{success, data, error, meta}`),traceId 只在 `meta.traceId`;分頁為 `PageResponse<T>`。成功回傳:`ApiResponse.success(dto, ApiMetaFactory.current())`。
- Controller 不做業務判斷:參數原樣轉交 service,service 是「HTTP 原始字串 → 型別化查詢」唯一關卡(`TradingService#listTrades` → `TradeQuery`)。
- Controller 捕捉 `BusinessException` 只為寫稽核,之後**原樣 rethrow**(`TradingController#createTrade`)。
- 擁有權:非擁有者存取回 404(不是 403),查詢一律以 `user_id` 範圍限定(`ai-docs/security.md` §4)。
- null / empty 檢查:純 null 用 `Objects`(`requireNonNull`、`requireNonNullElse`);null+empty 用 `ObjectUtils`;字串用 `StringUtils.isBlank`。**禁用** `ObjectUtils.defaultIfNull`(deprecated)。
- Facade 呼叫:每請求跨模組往返預設 ≤ 3 次(例外條件見 `ai-docs/code-standards.md` Facade Call Rules)。
- 所有 HTTP 錯誤由 `vue-app/src/services/apiClient.ts` 轉成 `ApiClientError`(`status`、`code`、`requestId`、`fields`);元件只處理 `ApiClientError`,不自己 `fetch`、不解析信封。
- mock adapter 的失敗也必須丟 `ApiClientError`,讓 mock / API 兩種 mode 只有一條錯誤處理路徑(`tradingApi.ts` mock oversell)。
- 錯誤碼對使用者文案透過 i18n key 對應(`vue-app/src/i18n.ts`、`baseCurrencyErrorKey.ts`)。
- 未接後端的功能標示 Preview / Simulated,不得靜默 no-op(前端 `AGENTS.md` 鐵律 6)。
- `VITE_DATA_MODE` 無效值要 fail fast(`vue-app/src/services/runtimeDataMode.ts`)。

## Logging

- 稽核事件用專用 logger `LoggerFactory.getLogger("AUDIT")`,經 `AuditLogger.log(userId, action, target, result, ip)`(`dowob.xyz.stockwebv2.infrastructure.audit.AuditLogger`);成功與失敗(`"failure:" + errorCode.name()`)都要記。
- 不記錄 token、密碼、冪等鍵等敏感值;500 細節只進 log 並附 traceId。
- 測試輸出要乾淨(Pristine Output),除非測的是錯誤處理。

## Comments

- 註解、Javadoc、JSDoc 用**繁體中文**,識別字與技術術語用英文。
- 解釋「為什麼」與契約,引用決策 / 威脅編號與文件段落:`D-05`、`T-04-03`、`judgment §5`、`security.md §13`,以及跨 repo 的 `file:line`(前端 `CreateTradeRequest.java:18-28`)。
- 界線與反例要寫清楚(`TradingControllerWebMvcTest` 的「能力界線」段落)。
- public 類別必有 Javadoc,結尾帶 `@author Yuan` 與 `@version x.y`。
- public 方法寫 `@param`、`@return`、`@throws BusinessException <何時>`;段落以 `<p>...</p>`,強調用 `<strong>`,程式碼用 `{@code}` / `{@link}`。

## Function Design

## Module Design

- L2 業務模組(`stock-module-*`)之間禁止直接依賴,只透過 `stock-infrastructure` 的 Facade 介面互動;Controller 不得直接呼叫 Repository。由 `stock-start/src/test/java/dowob/xyz/stockwebv2/start/ArchitectureRulesTest.java` 強制。
- 每個 endpoint 方法必須有 `@PreAuthorize`(如 `hasAuthority('TRADE_EXECUTE')`)或在帶理由的 allow-list,由 `EndpointAuthorizationRulesTest.java` 強制。
- 共用型別(`ApiResponse`、`PageResponse`、`ErrorCode`、`CurrencyCode`)在 `stock-common`。
- `apiClient.ts` 是唯一 transport 邊界(credentials、CSRF `X-XSRF-TOKEN`、信封、401/403);token 只在 HttpOnly cookie,不得進 localStorage / sessionStorage / Pinia。
- 每個 domain service 匯出一個 interface(`TradingApi`)+ mock / http 兩個 adapter;頁面經 `vue-app/src/services/pageApiClients.ts` 的 `getRuntimeApiClients()` 取得,禁止 import mock store(`mockStoreBoundary.test.ts` 守護)。分支依據是 `api.live` 是否存在,不是 mode 字串。
- 無 barrel(`index.ts`)檔,直接 import 具體模組。

## Git

- Conventional Commits,繁中 subject;GSD 工作 scope 用 phase/plan id(`feat(02-05): ...`)或 quick id(`fix(quick-261106-fbs): ...`),詳見 `ai-docs/git-convention.md`。前端 PR 開向 `develop`。

<!-- GSD:conventions-end -->

<!-- GSD:architecture-start source:ARCHITECTURE.md -->

## Architecture

## System Overview

```text

```

## Component Responsibilities

| Component | Responsibility | File |
|-----------|----------------|------|
| Application entry | `@SpringBootApplication(scanBasePackages="dowob.xyz.stockwebv2")` | `stock-start/src/main/java/dowob/xyz/stockwebv2/start/StockWebV2Application.java` |
| Security chain | JWT(header 或 access cookie)、自訂 `BrowserCsrfFilter`(double-submit)、路徑授權 | `stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java` |
| 全域錯誤 → envelope | Exception → `ApiResponse` failure | `stock-start/src/main/java/dowob/xyz/stockwebv2/start/error/GlobalExceptionHandler.java` |
| API envelope | `ApiResponse<T>{success,data,error,meta}`、`PageResponse<T>` | `stock-common/src/main/java/dowob/xyz/stockwebv2/common/api/` |
| 錯誤碼 / 例外 | `ErrorCode`、`BusinessException` 等 | `stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/` |
| 跨模組 Facade | `AssetFacade`、`FxRateFacade`、`MarketDataFacade`、`UserPreferenceFacade` | `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/{asset,fx,marketdata,user}/` |
| Auth / cookie | 登入、refresh rotation、browser cookie、CSRF bootstrap | `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/{api/AuthController,api/CsrfController,service/BrowserAuthCookieService}.java` |
| 交易 / 持倉 | 已成交手動紀錄、持倉計算、現金、股利、股數調整 | `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/` |
| 行情 | Ingest、Kafka consumer、K 線查詢、WS ticket、WS push | `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/` |
| 回測 | 決定性回測引擎、策略驗證 | `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/engine/DeterministicBacktestEngine.java` |
| 前端 transport 邊界 | credentials、CSRF header、信封解析、401 refresh 重試 | `vue-app/src/services/apiClient.ts` |
| 前端 service 組裝 | 依 `VITE_DATA_MODE` 建立各 domain API(singleton) | `vue-app/src/services/pageApiClients.ts`、`vue-app/src/services/runtimeDataMode.ts` |
| 前端即時報價 | WS ticket → 連線 → 訂閱、狀態機 | `vue-app/src/services/quoteStream.ts`、`vue-app/src/composables/useLiveQuotes.ts` |

## Pattern Overview

- 模組只能經 `stock-infrastructure` 的 Facade 介面互相呼叫,禁止跨模組直接存取 Repository/SQL(由 `stock-start/src/test/java/dowob/xyz/stockwebv2/start/ArchitectureRulesTest.java` ArchUnit 守護)。
- DDD-Lite 實為 transaction script:domain 是不可變 `record`,規則在純 calculator(`HoldingCalculator`、`CashBalanceCalculator`、`DayPnlCalculator`)與 application service。
- 資料存取用手寫 `Jdbc*Repository` + `JdbcClient`(market-data 批次寫入用 `NamedParameterJdbcTemplate`);非 JPA、非 Spring Data JDBC repository,`spring-boot-starter-data-jdbc` 只為 JDBC 基礎設施。
- 對外 ID 一律 UUID;所有 REST 回 `ApiResponse<T>`,traceId 只在 `meta.traceId`。
- 前端元件不得 `fetch`、不得 import mock store;mock 是明確模式,無效值 fail fast(`RuntimeDataModeError`)。

## Layers

- Purpose: 共用 DTO、enum、錯誤碼、事件
- Location: `stock-common/src/main/java/dowob/xyz/stockwebv2/common/`
- Contains: `api/`、`error/`、`event/PriceTickEvent`、`model/`(`AssetType`、`CurrencyCode`、`Role`…)、`time/ApiTimeParser`
- Depends on: 無(專案內)
- Used by: 所有模組
- Purpose: 集中 Flyway schema(`V1__`…`V19__`)
- Location: `stock-db-migration/src/main/resources/db/migration/`
- Used by: `stock-start` 啟動時
- Purpose: Facade 介面、安全(`JwtService` ES256)、rate limit、web 支援(`TraceIdFilter`、`ApiMetaFactory`、`AuthenticatedUserResolver`、`ClientIpResolver`)、`AuditLogger`、`RedisConfig`
- Location: `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/`
- Depends on: `stock-common`
- Used by: L2 模組、`stock-start`
- Purpose: 各領域 `api/`(Controller + DTO)→ `service/` → `domain/` → `repository/`
- Location: `stock-module-{user,asset,trading,backtest,market-data}/`
- Depends on: L0、L1(Facade 介面)
- Used by: `stock-start`;他模組僅透過 Facade 實作(如 `asset/service/AssetFacadeImpl.java`、`marketdata/api/MarketDataFacadeImpl.java`、`user/service/UserPreferenceFacadeImpl.java`)
- Purpose: 聚合啟動、SecurityConfig、全域例外、API rate limit interceptor、整合測試(`*IT.java`)
- Location: `stock-start/`
- pages(`vue-app/src/pages/*.vue`)→ components / composables → services(`vue-app/src/services/*Api.ts`)→ `apiClient.ts` / `quoteStream.ts` 或 mock stores(`vue-app/src/stores/mock*.ts`)

## Data Flow

### Primary Request Path(前端 → 寫入交易)

### 行情 ingest 與推播

### 歷史回補

- 後端無 session:stateful JWT,Redis 存 token version(`user:auth:{id}`);portfolio 讀取有 `trading/service/PortfolioCache.java`。
- 前端:Pinia(`vue-app/src/main.ts`),mock 資料在 `vue-app/src/stores/`;token 只在 HttpOnly cookie,不進 JS 可讀儲存。

## Key Abstractions

- Purpose: 同步跨模組查詢
- Examples: `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/marketdata/MarketDataFacade.java`、`.../asset/AssetFacade.java`
- Pattern: 介面在 L1、實作在擁有模組;只能由 Application Service 呼叫,Controller 不可
- Examples: `stock-common/src/main/java/dowob/xyz/stockwebv2/common/api/ApiResponse.java`;前端對應型別 `vue-app/src/services/apiTypes.ts`
- Examples: `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/provider/`
- Pattern: 可插拔行情源(Strategy)
- Examples: `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/engine/BacktestEngine.java`
- Examples: `vue-app/src/services/{tradingApi,portfolioApi,cashApi,marketApi,watchlistApi,backtestApi,authApi}.ts`
- Pattern: `createXxxApi(mode, basePath)` 回傳 interface,mock / api 兩實作

## Entry Points

- Location: `stock-start/src/main/java/dowob/xyz/stockwebv2/start/StockWebV2Application.java`
- Config: `stock-start/src/main/resources/application.yaml`(+ `-dev`、`-demo`、`-e2e-browser` profiles)
- Dev script: `scripts/run-dev.ps1`
- `vue-app/index.html` → `vue-app/src/main.ts` → `vue-app/src/App.vue`
- `vue-app/src/router.ts`:routes 的 component 為 `render: () => null`,實際頁面由 `App.vue` 依 route name 切換(import 於 `App.vue:87-99`)
- Dev proxy:`vue-app/vite.config.ts`(`backendProxy`)

## Architectural Constraints

- **Threading:** 後端 servlet thread-per-request;ingest 平行呼叫 provider 並有 per-call timeout;WS 每 session 有獨立 send queue(`SessionSendDispatcher`、`WsSendConfig`)。前端單執行緒。
- **Global state:** 前端 `pageApiClients.ts` 模組級 `clients` singleton(含單一 `QuoteStream` 連線);`apiClient.ts` 模組級 session handlers(`configureApiClientSessionHandlers`)。
- **Circular imports:** 未偵測到;L2 模組間無 Maven 依賴,ArchUnit 守護。
- **單一可執行 app:** market-data 可用 `market-data.ingestor.enabled` 等開關停用,但非獨立部署。
- **CSRF:** Spring 內建 CSRF 關閉(`SecurityConfig.java:69`),改由自訂 `BrowserCsrfFilter` 對 cookie 認證的 unsafe 請求強制 double-submit。
- **交易語義:** `POST /api/v1/trades` 是已成交紀錄,非委託單。

## Anti-Patterns

### 跨模組直接存取 Repository

### Controller 直接呼叫 Facade

### 元件內自行 fetch 或 import mock store

## Error Handling

- 欄位錯誤 `FieldValidationException` → `error.fields`,前端 `fieldsFrom()` 解析
- 401(非 login/refresh 端點)→ refresh 一次再重放;仍 401 → session handler 通知 UI(`SessionBanner.vue`)
- 429 → `RateLimitExceededException`

## Cross-Cutting Concerns

<!-- GSD:architecture-end -->

<!-- GSD:skills-start source:skills/ -->
## Project Skills

| Skill | Description | Path |
|-------|-------------|------|
| architecture-review-discussion | "Facilitate multi-role architecture review discussions using an Agent Team. Spawns specialized agents (architects, security lead, CTO) that explore the codebase, evaluate designs, ask each other questions, and resolve disputes through multiple rounds. Use when Yuan wants a team-based architecture review, design critique, or cross-functional discussion." | `.claude/skills/architecture-review-discussion/SKILL.md` |
| brainstorming | "You MUST use this before any creative work - creating features, building components, adding functionality, or modifying behavior. Explores user intent, requirements and design before implementation." | `.claude/skills/brainstorming/SKILL.md` |
| finishing-a-development-branch | Use when implementation is complete, all tests pass, and you need to decide how to integrate the work - guides completion of development work by presenting structured options for merge, PR, or cleanup | `.claude/skills/finishing-a-development-branch/SKILL.md` |
| git-action | Stage, commit, and push changes to the current feature or hotfix branch. Use after completing a logical unit of work to create a well-formed commit following Conventional Commits with Traditional Chinese subject. | `.claude/skills/git-action/SKILL.md` |
| github-review | Fetch all comments and review discussions from an open GitHub PR, discuss each item with Yuan to decide what to implement, then automatically hand off to /review-implementing for approved items. | `.claude/skills/github-review/SKILL.md` |
| open-pr | Open a Pull Request from the current feature or hotfix branch to the correct target branch (develop for features, main for hotfixes). Run once per feature after all commits are pushed. | `.claude/skills/open-pr/SKILL.md` |
| post-bug | Record a post-mortem report after fixing a bug — analyses the fix commit, discusses root cause and category with Yuan, generates a structured report in ai-docs/bug-reports/, and updates the index. | `.claude/skills/post-bug/SKILL.md` |
| review-bugs | Periodically review accumulated bug reports in ai-docs/bug-reports/ — analyse patterns, identify hotspots, suggest improvements to code standards and review checklists, and update guidelines with Yuan's approval. | `.claude/skills/review-bugs/SKILL.md` |
| review-implementing | Process and implement code review feedback systematically. Use when user provides reviewer comments, PR feedback, code review notes, or asks to implement suggestions from reviews. | `.claude/skills/review-implementing/SKILL.md` |
| software-architecture | Guide for stock-web-v2 Java Spring Boot modular monolith architecture. Use this skill when designing new features, reviewing code structure, or making architectural decisions. | `.claude/skills/software-architecture/SKILL.md` |
| subagent-driven-development | Use when executing implementation plans with independent tasks — dispatches fresh subagent per task with TDD + code review between tasks | `.claude/skills/subagent-driven-development/SKILL.md` |
| test-driven-development | Use when implementing any feature or bugfix, before writing implementation code | `.claude/skills/test-driven-development/SKILL.md` |
| test-fixing | Run tests and systematically fix all failing tests using smart error grouping. Use when user asks to fix failing tests, mentions test failures, runs test suite and failures occur, or requests to make tests pass. | `.claude/skills/test-fixing/SKILL.md` |
| using-git-worktrees | Use when starting feature work that needs isolation from current workspace or before executing implementation plans - creates isolated git worktrees with smart directory selection and safety verification | `.claude/skills/using-git-worktrees/SKILL.md` |
| writing-plans | Convert an approved brainstorming design into a concrete implementation plan with ordered tasks. Use after brainstorming design is approved, before dispatching implementation work. | `.claude/skills/writing-plans/SKILL.md` |
<!-- GSD:skills-end -->

<!-- GSD:workflow-start source:GSD defaults -->

## GSD Workflow Enforcement

Before using Edit, Write, or other file-changing tools, start work through a GSD command so planning artifacts and execution context stay in sync.

Use these entry points:
- `/gsd-fast` for a trivial task inline, with no subagents and no PLAN.md
- `/gsd-quick` for small fixes, doc updates, and ad-hoc tasks
- `/gsd-debug` for investigation and bug fixing
- `/gsd-execute-phase` for planned phase work

Do not make direct repo edits outside a GSD workflow unless the user explicitly asks to bypass it.
<!-- GSD:workflow-end -->

<!-- GSD:profile-start -->

## Developer Profile

> Profile not yet configured. Run `/gsd-profile-user` to generate your developer profile.
> This section is managed by `generate-claude-profile` -- do not edit manually.
<!-- GSD:profile-end -->
