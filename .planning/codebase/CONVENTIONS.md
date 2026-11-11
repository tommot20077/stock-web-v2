---
last_mapped_commit: adae04117fcfa99db40b8ca304fb5cb9c5e36ec9
last_mapped_at: 2026-11-07
---
# Coding Conventions

**Analysis Date:** 2026-11-07

本文件涵蓋兩個 repo:
- **後端**(主 repo):本 repo 根,Java 21 / Spring Boot 4.0.4 多模組 Maven。
- **前端**:前端 repo 根(本機 `../../vue/stock-v2`、cloud `../stock-web-v2-front-end`,路徑對照見 `ai-docs/judgment.md` §8)。程式碼在 `vue-app/` 下;以下前端路徑皆相對於前端 repo 根。

權威規範文件:後端 `ai-docs/code-standards.md`、`ai-docs/testing-standards.md`、`ai-docs/git-convention.md`、`ai-docs/judgment.md`;前端 `AGENTS.md`(鐵律 1–6)、`docs/LEARNINGS.md`。本文件與它們衝突時以它們為準。

## Naming Patterns

**Files(後端):**
- 一個 public type 一個檔,檔名 = 類別名(PascalCase)。
- 套件根 `dowob.xyz.stockwebv2.<module>`,模組內固定分層子套件:`api/`(Controller、Request/Dto)、`service/`、`domain/`、`repository/`。範例:`stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/{api,service,domain,repository}/`。
- 字尾約定:
  - `*Controller`(`TradingController.java`)
  - `Create*Request`(輸入,`CreateTradeRequest.java`)、`*Dto`(輸出,`TradeDto.java`、`HoldingDto.java`)
  - `*Service`(`TradingService.java`)、`*QueryService`(唯讀查詢,`PortfolioQueryService.java`)
  - `*Repository` 介面 + `Jdbc*Repository` 實作(`TradingRepository.java` / `JdbcTradingRepository.java`)
  - `*Calculator`(純函式領域計算,`HoldingCalculator.java`)、`*PayloadMatcher`(冪等 payload 比對,`TradePayloadMatcher.java`)、`*SortKey`(排序白名單 enum)
  - 跨模組介面 `*Facade` 放在 `stock-infrastructure`(`dowob.xyz.stockwebv2.infrastructure.asset.AssetFacade`),實作 `*FacadeImpl` 在提供方模組。

**Files(前端):**
- 元件 / 頁面:PascalCase `.vue`(`vue-app/src/components/OrderTicket.vue`、`vue-app/src/pages/Trades.vue`);頁面允許單字命名(ESLint 已關 `vue/multi-word-component-names`)。
- service:camelCase + `Api` 字尾(`vue-app/src/services/tradingApi.ts`、`watchlistApi.ts`);transport 唯一檔 `apiClient.ts`;DTO 型別集中在 `apiTypes.ts`。
- composable:`use*` 前綴(`vue-app/src/composables/useLiveQuotes.ts`)。
- mock store:`mock*` 前綴(`vue-app/src/stores/mockPortfolio.ts`),只能被 service 的 mock adapter import。
- 純函式工具:camelCase(`vue-app/src/moneyFormat.ts`、`csvCell.ts`)。

**Functions:**
- Java:camelCase 動詞開頭(`createTrade`、`listTrades`);record accessor 風格的 getter 不加 `get`(`BusinessException.errorCode()`、`fields()`)。
- TS:camelCase;adapter 工廠統一 `createHttpXxxApi` / `createMockXxxApi` / `createXxxApi`(`vue-app/src/services/tradingApi.ts`)。

**Variables:**
- Java 常數 `UPPER_SNAKE_CASE`,數字用底線分隔(`MAX_PAGE = 10_000`);每個非顯然常數附 Javadoc 說明「為什麼是這個值」。
- TS 未使用變數 / 參數以 `_` 前綴豁免(ESLint `argsIgnorePattern: '^_'`)。

**Types:**
- Java:DTO / Request / 值物件優先用 `record`(main 原始碼約 77 個 public record);錯誤碼為 `enum ErrorCode`(`stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/ErrorCode.java`),每個常數帶 HTTP status 與靜態訊息,例:`VALIDATION_FAILED(400, "Validation failed")`。
- TS:`interface` 描述 API 形狀,欄位名逐字對應後端 record(`CreateTradeRequest` 對 `CreateTradeRequest.java`),並在 JSDoc 註明後端 `file:line` 出處;字串聯集取代 enum(`type: 'BUY' | 'SELL'`)。

## Code Style

**Formatting(後端):**
- Spotless `2.44.5`(根 `pom.xml`),綁 `validate` phase,每個 Maven build 都會 check;CI 另有獨立 `Lint` job 跑 `./mvnw -B spotless:check`。
- 規則:`removeUnusedImports`、`trimTrailingWhitespace`、`endWithNewline`、固定 import 順序。**沒有** google-java-format,縮排沿用既有風格:4 空白;多行參數清單每個參數一行、4 空白續行、`)` 獨立一行:
  ```java
  public ApiResponse<TradeDto> createTrade(
      @Valid @RequestBody CreateTradeRequest request,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      Authentication authentication,
      HttpServletRequest servletRequest
  ) {
  ```
- 格式出錯時用 `./mvnw spotless:apply` 修正。

**Formatting(前端):**
- Prettier(`vue-app/.prettierrc.json`):`singleQuote: true`、`semi: true`、`printWidth: 100`、`trailingComma: "all"`、`arrowParens: "always"`;執行 `npm run format`。2 空白縮排。
- SFC 一律 `<script setup lang="ts">`(`vue-app/src` 下 34/34 個 `.vue`)。

**Linting(前端):**
- ESLint 10 flat config `vue-app/eslint.config.js`:`@eslint/js` recommended + `typescript-eslint` recommended + `eslint-plugin-vue` `flat/essential` + `eslint-config-prettier`。
- 關鍵規則:`@typescript-eslint/no-explicit-any: warn`(ratchet,清零後改 error)、`vue/no-mutating-props: warn`(ratchet)、`@typescript-eslint/no-unused-vars: error`、`no-empty` 允許空 catch。
- 新程式碼不得新增 `any` 或 mutate prop 的 warning。

## Import Organization

**後端順序(Spotless 強制 `dowob,jakarta,org,com,java,\#`),群組間空一行:**
1. `dowob.xyz.stockwebv2.*`(專案內)
2. `jakarta.*`
3. `org.*`(Spring、JUnit、Mockito、Apache Commons)
4. `com.*`
5. `java.*`
6. `import static ...`(最後)

範例見 `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/TradingService.java` 開頭。不使用 wildcard import。

**前端順序(慣例,未以 lint 強制):**
1. 第三方(`vue`、`pinia`、`vitest`)
2. 相對路徑模組(`./apiClient`、`../data`)
3. `import type { ... }` 型別匯入獨立寫

**Path Aliases:** 前端未設定 alias,一律相對路徑。

## Error Handling

**後端:**
- 業務錯誤一律丟 `BusinessException(ErrorCode, staticMessage[, fields])`(`stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/BusinessException.java`);欄位驗證錯誤用子類 `FieldValidationException`。
- **訊息與 `fields` 必須是靜態字串,不得回射使用者輸入**(2026-09-02 安全審查 L-1);測試以 canary 值驗證(`TradingServiceTest` 的 `CANARY_KEY`)。
- 統一轉換在 `stock-start/.../error/GlobalExceptionHandler.java`;所有 `@ExceptionHandler` 必須回 `ResponseEntity<ApiResponse<Void>>` 並帶明確 status:
  ```java
  return ResponseEntity.status(code.httpStatus()).body(ApiResponse.failure(error, ApiMetaFactory.current()));
  ```
  禁止直接回 `ApiResponse.failure(...)`(會變 HTTP 200)。不存在 `SystemException`,未預期錯誤落到 catch-all 500 `INTERNAL_ERROR`。
- 回應信封 `ApiResponse<T>`(`{success, data, error, meta}`),traceId 只在 `meta.traceId`;分頁為 `PageResponse<T>`。成功回傳:`ApiResponse.success(dto, ApiMetaFactory.current())`。
- Controller 不做業務判斷:參數原樣轉交 service,service 是「HTTP 原始字串 → 型別化查詢」唯一關卡(`TradingService#listTrades` → `TradeQuery`)。
- Controller 捕捉 `BusinessException` 只為寫稽核,之後**原樣 rethrow**(`TradingController#createTrade`)。
- 擁有權:非擁有者存取回 404(不是 403),查詢一律以 `user_id` 範圍限定(`ai-docs/security.md` §4)。
- null / empty 檢查:純 null 用 `Objects`(`requireNonNull`、`requireNonNullElse`);null+empty 用 `ObjectUtils`;字串用 `StringUtils.isBlank`。**禁用** `ObjectUtils.defaultIfNull`(deprecated)。
- Facade 呼叫:每請求跨模組往返預設 ≤ 3 次(例外條件見 `ai-docs/code-standards.md` Facade Call Rules)。

**前端:**
- 所有 HTTP 錯誤由 `vue-app/src/services/apiClient.ts` 轉成 `ApiClientError`(`status`、`code`、`requestId`、`fields`);元件只處理 `ApiClientError`,不自己 `fetch`、不解析信封。
- mock adapter 的失敗也必須丟 `ApiClientError`,讓 mock / API 兩種 mode 只有一條錯誤處理路徑(`tradingApi.ts` mock oversell)。
- 錯誤碼對使用者文案透過 i18n key 對應(`vue-app/src/i18n.ts`、`baseCurrencyErrorKey.ts`)。
- 未接後端的功能標示 Preview / Simulated,不得靜默 no-op(前端 `AGENTS.md` 鐵律 6)。
- `VITE_DATA_MODE` 無效值要 fail fast(`vue-app/src/services/runtimeDataMode.ts`)。

## Logging

**Framework(後端):** SLF4J,`private static final Logger log = LoggerFactory.getLogger(Xxx.class);`(不使用 Lombok,repo 無 Lombok 依賴)。
- 稽核事件用專用 logger `LoggerFactory.getLogger("AUDIT")`,經 `AuditLogger.log(userId, action, target, result, ip)`(`dowob.xyz.stockwebv2.infrastructure.audit.AuditLogger`);成功與失敗(`"failure:" + errorCode.name()`)都要記。
- 不記錄 token、密碼、冪等鍵等敏感值;500 細節只進 log 並附 traceId。
- 測試輸出要乾淨(Pristine Output),除非測的是錯誤處理。

**Framework(前端):** 無 logging 框架;正式程式碼不留 `console.log`。

## Comments

**When to Comment:**
- 註解、Javadoc、JSDoc 用**繁體中文**,識別字與技術術語用英文。
- 解釋「為什麼」與契約,引用決策 / 威脅編號與文件段落:`D-05`、`T-04-03`、`judgment §5`、`security.md §13`,以及跨 repo 的 `file:line`(前端 `CreateTradeRequest.java:18-28`)。
- 界線與反例要寫清楚(`TradingControllerWebMvcTest` 的「能力界線」段落)。

**Javadoc(後端):**
- public 類別必有 Javadoc,結尾帶 `@author Yuan` 與 `@version x.y`。
- public 方法寫 `@param`、`@return`、`@throws BusinessException <何時>`;段落以 `<p>...</p>`,強調用 `<strong>`,程式碼用 `{@code}` / `{@link}`。

**JSDoc(前端):** interface 與 export 函式用 `/** ... */`,重要欄位行內 `/** D-02:... */`;測試檔首以區塊註解列出本檔鎖住的契約清單(`vue-app/src/services/tradingApi.test.ts`)。

## Function Design

**Size:** 小而單一責任;複雜計算抽成 `domain/*Calculator` 純函式(後端)或 `src/*.ts` 純函式(前端,如 `chartTvTheme.ts`)以便單元測試。

**Parameters:** Java controller 參數每行一個;userId 一律由 `AuthenticatedUserResolver.resolve(authentication)` 取得,IP 由 `ClientIpResolver.resolve(request)`。前端 `apiRequest<T>(path, options)` 以 `json` 選項傳 body。

**Return Values:** Java 查詢回 `Optional` 而非 null;集合回不可變副本(`Map.copyOf`)。金額一律 `BigDecimal`(DB `NUMERIC(24,8)`,比較用 `compareTo`/scale 正規化);時間 `OffsetDateTime`,API 傳含 offset 的 ISO-8601。

## Module Design

**Exports / 邊界(後端):**
- L2 業務模組(`stock-module-*`)之間禁止直接依賴,只透過 `stock-infrastructure` 的 Facade 介面互動;Controller 不得直接呼叫 Repository。由 `stock-start/src/test/java/dowob/xyz/stockwebv2/start/ArchitectureRulesTest.java` 強制。
- 每個 endpoint 方法必須有 `@PreAuthorize`(如 `hasAuthority('TRADE_EXECUTE')`)或在帶理由的 allow-list,由 `EndpointAuthorizationRulesTest.java` 強制。
- 共用型別(`ApiResponse`、`PageResponse`、`ErrorCode`、`CurrencyCode`)在 `stock-common`。

**Exports / 邊界(前端):**
- `apiClient.ts` 是唯一 transport 邊界(credentials、CSRF `X-XSRF-TOKEN`、信封、401/403);token 只在 HttpOnly cookie,不得進 localStorage / sessionStorage / Pinia。
- 每個 domain service 匯出一個 interface(`TradingApi`)+ mock / http 兩個 adapter;頁面經 `vue-app/src/services/pageApiClients.ts` 的 `getRuntimeApiClients()` 取得,禁止 import mock store(`mockStoreBoundary.test.ts` 守護)。分支依據是 `api.live` 是否存在,不是 mode 字串。
- 無 barrel(`index.ts`)檔,直接 import 具體模組。

## Git

- Conventional Commits,繁中 subject;GSD 工作 scope 用 phase/plan id(`feat(02-05): ...`)或 quick id(`fix(quick-261106-fbs): ...`),詳見 `ai-docs/git-convention.md`。前端 PR 開向 `develop`。

---

*Convention analysis: 2026-11-07*
