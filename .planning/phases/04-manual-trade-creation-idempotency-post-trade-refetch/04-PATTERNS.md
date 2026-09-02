# Phase 04: Manual Trade Creation, Idempotency & Post-Trade Refetch - Pattern Map

**Mapped:** 2026-07-26
**Files analyzed:** 27(backend 12 / frontend 15)
**Analogs found:** 25 / 27
**Repos:** `D:\end\workspace\java\stock-web-v2`(backend)、`D:\end\workspace\vue\stock-v2\vue-app`(frontend)

> **TDD 是硬性要求**(`CLAUDE.md`)。本文件的每一列**都**指派了 test analog;planner 必須把「先寫失敗測試」寫進每個 plan 的第一個 action。
> 路徑慣例:backend 以 repo root 為基準;frontend 以 `vue-app/` 為基準(標記 `[FE]`)。

---

## File Classification

### Backend(`stock-web-v2`)

| New/Modified File | Role | Data Flow | Closest Analog | Test Analog | Match |
|---|---|---|---|---|---|
| `stock-db-migration/src/main/resources/db/migration/V10__transactions_idempotency_key.sql` | migration | schema DDL | `V9__trading_query_indexes.sql` | `stock-start/src/test/java/.../TransactionsAppendOnlyIT.java` | exact |
| `stock-common/.../common/error/ErrorCode.java`(修改) | config/enum | — | 同檔 `TRADE_*` 區塊(`:40-44`) | `stock-start/.../ErrorHandlingIT.java` | exact |
| `stock-module-trading/.../domain/TradeTransaction.java`(修改,加 `idempotencyKey`) | model | — | 同檔既有 record 欄位 | `TradingServiceTest`(建構呼叫端會編譯失敗即紅燈) | exact |
| `stock-module-trading/.../repository/TradingRepository.java`(修改,新增查詢/insert 簽章) | repository interface | CRUD | 同檔既有 `insertTransaction` / `findHoldingForUpdate` | — (介面無測試) | exact |
| `stock-module-trading/.../repository/JdbcTradingRepository.java`(修改) | repository | CRUD | 同檔 `insertHoldingIfAbsent`(`:87-104`,ON CONFLICT DO NOTHING RETURNING) | `stock-start/.../TradingApiIT.java`(IT 層驗證) | **exact** |
| `stock-module-trading/.../service/TradingService.java`(修改 `createTrade`) | service | request-response + CRUD | 同檔 `createTrade`(`:60-104`)本身 | `stock-module-trading/src/test/.../service/TradingServiceTest.java` | exact |
| `stock-module-trading/.../service/TradePayloadMatcher`(新,或 `TradingService` private method) | domain/utility | transform(純函式比對) | `stock-module-trading/.../domain/HoldingCalculator.java` | `.../domain/HoldingCalculatorTest.java` | role-match |
| `stock-module-trading/.../api/TradingController.java`(修改,`@RequestHeader`) | controller | request-response | `stock-module-market-data/.../api/BackfillController.java:90`(**僅 header 慣例**;語意是反例) | `stock-module-trading/src/test/.../api/TradingControllerTest.java` | role-match |
| `stock-start/.../error/GlobalExceptionHandler.java`(選用:`MissingRequestHeaderException` handler) | middleware | request-response | 同檔 `handleValidation`(`:56-64`) | `stock-start/.../ErrorHandlingIT.java` | exact |
| `stock-start/src/test/java/.../TradingIdempotencyIT.java`(新) | test(IT) | request-response | `stock-start/src/test/java/.../TradingApiIT.java` | 自身 | exact |
| `stock-module-trading/src/test/.../service/TradingServiceTest.java`(修改) | test(unit) | — | 自身 | — | exact |
| `stock-module-trading/src/test/.../api/TradingControllerTest.java`(修改) | test(unit) | — | 自身 | — | exact |

### Frontend(`[FE]` = `vue-app/`)

| New/Modified File | Role | Data Flow | Closest Analog | Test Analog | Match |
|---|---|---|---|---|---|
| `[FE] src/services/tradingApi.ts`(新) | service adapter | request-response(POST) | `src/services/opsApi.ts:142-165`(POST + `Idempotency-Key`)+ `src/services/portfolioApi.ts:140-202`(三件組 + `live`) | `src/services/portfolioApi.test.ts` | **exact** |
| `[FE] src/services/marketApi.ts`(新;RESEARCH 發現的漏項) | service adapter | request-response(GET,分頁 + 非分頁) | `src/services/portfolioApi.ts:180-198` | `src/services/portfolioApi.test.ts` | exact |
| `[FE] src/services/portfolioRevision.ts`(新) | store/singleton | event-driven(pub-sub) | `src/services/pageApiClients.ts:19,21-35,37-39` | `src/services/runtimeDataMode.test.ts`(模組級狀態 + reset 樣板) | role-match |
| `[FE] src/services/pageApiClients.ts`(修改) | config/wiring | — | 同檔 `:9-35` | `src/api-adapter-wiring.test.ts` | exact |
| `[FE] src/services/apiTypes.ts`(修改;`AssetDto` / `KlineDto` / `CreateTradeRequest`) | model/types | — | 同檔既有 `TradeDto` / `HoldingDto` | 由 adapter 測試間接覆蓋 | exact |
| `[FE] src/components/OrderTicket.vue`(重建) | component | request-response + form | `src/pages/Positions.vue:335-374`(四態機 + `describeError` + `if (live) return`) | `src/pages/Positions.test.ts` | role-match |
| `[FE] src/pages/Overview.vue`(修改,watch revision) | page component | event-driven | `src/pages/Positions.vue:351-374` | `src/pages/Overview.test.ts` | exact |
| `[FE] src/pages/Positions.vue`(修改,watch + `fresh`) | page component | event-driven | 同檔 `:255-265` TODO 接點 | `src/pages/Positions.test.ts` | exact |
| `[FE] src/pages/Trades.vue`(修改,watch + D-11) | page component | event-driven | 同檔 `applyQueryChange`(`:281-286`) | `src/pages/Trades.test.ts` | **exact** |
| `[FE] src/i18n.ts`(修改,新增 ~30 key) | config | — | 同檔扁平 `Record<Lang, Record<string,string>>` | `src/i18n.test.ts:6-33` | exact |
| `[FE] src/App.vue`(可能微調 overlay props) | app shell | — | 同檔 `:36,52-59` | `src/App.test.ts` | exact |
| `[FE] src/task4.test.ts`(修改;3 個 case 依賴 placing 假進度) | test | — | 自身 | — | exact |
| `[FE] src/services/tradingApi.test.ts`(新) | test | — | `src/services/portfolioApi.test.ts` | 自身 | exact |
| `[FE] src/services/marketApi.test.ts`(新) | test | — | `src/services/portfolioApi.test.ts` | 自身 | exact |
| `[FE] src/components/OrderTicket.test.ts`(新) | test(component) | — | `src/pages/Positions.test.ts` | 自身 | exact |

---

## Pattern Assignments — Backend

### `V10__transactions_idempotency_key.sql`(migration, DDL)

**Analog:** `stock-db-migration/src/main/resources/db/migration/V9__trading_query_indexes.sql`

慣例(逐條由 V9 觀察而來):檔名 `V{N}__{english_snake_case}.sql`、V10 是下一個空號、**不得修改已套用的 V7/V8/V9**(`ai-docs/flyway-convention.md:33`)、SQL 關鍵字大寫、**每一條 DDL 上方都要有繁中註解說明「為什麼」與取捨**。

V9 全檔的註解密度就是本專案的標準:

```sql
-- 交易查詢排序索引（對應決策 D-06 金額排序、D-07 預設排序改以 executed_at 為準）。
-- V7 既有索引全部建在 created_at（入帳時間）上，但 GET /api/v1/trades 的預設排序與日期
-- 篩選改以 executed_at（成交時間）為準，兩者在補登舊交易時會分歧，故需本組索引配套。

-- D-07：預設排序 executed_at DESC, id DESC 的配套索引（id 為 tie-breaker，一併納入避免額外排序）。
CREATE INDEX idx_transactions_user_executed ON transactions (user_id, executed_at DESC, id DESC);

-- 取捨說明：sort=quantity 刻意不建索引。per-user 交易筆數量級小⋯⋯
```

**Phase 4 必須額外遵守:**
- partial unique index **必須**帶 `WHERE idempotency_key IS NOT NULL`,否則 `ON CONFLICT` 推斷失敗(RESEARCH Pitfall 1 / 15)。
- **不得**使用 `CREATE INDEX CONCURRENTLY`(V9 註解有 1.5 小時死鎖的實測紀錄)。
- `ALTER TABLE ADD COLUMN` 不受 V8 append-only trigger 影響(trigger 只擋 row 層 UPDATE/DELETE/TRUNCATE)。

**Test analog:** `stock-start/src/test/java/dowob/xyz/stockwebv2/start/TransactionsAppendOnlyIT.java` — 直接注入 `JdbcClient` 對真 PG 下 SQL、繞過應用層驗證 DB 層強制:

```java
@DisplayName("transactions 表 append-only DB trigger")
class TransactionsAppendOnlyIT extends ContainerIT {
    @Autowired
    JdbcClient jdbcClient;   // 繞過應用層以驗證 DB 層強制

    @BeforeEach
    void seedTransaction() { /* 直接 INSERT INTO users(...) 種資料 */ }
}
```

V10 的紅燈:**先寫一個「同 user_id + 同 key 第二次 insert 拋 DataAccessException」的 IT**,再寫 migration。

---

### `ErrorCode.java`(config/enum)

**Analog:** 同檔 Trading 區塊(`:39-44`)。新 code 加在 `TRADE_CONFLICT` 之後、`INTERNAL_ERROR` 之前:

```java
    // Trading module error codes
    TRADE_UNSUPPORTED_TYPE(400, "Unsupported trade type"),
    TRADE_INVALID_QUANTITY(400, "Trade quantity must be greater than 0"),
    TRADE_INVALID_PRICE(400, "Trade price must be greater than 0"),
    TRADE_INSUFFICIENT_HOLDING(409, "Insufficient holding quantity"),
    TRADE_CONFLICT(409, "Holding changed during trade execution"),
    // ← TRADE_IDEMPOTENCY_KEY_REUSED(409, "...") 加在這裡

    INTERNAL_ERROR(500, "Internal server error");
```

`defaultMessage` 是**英文、無標點結尾、不含任何 ID / 使用者輸入**(`ai-docs/code-standards.md:79-84`)。UI-SPEC 已把 `TRADE_IDEMPOTENCY_KEY_REUSED` 寫死進前端 i18n 對照表 —— **命名一旦定案就不可再改**。

---

### `JdbcTradingRepository.java`(repository, CRUD)

**Analog:** 同檔 `insertHoldingIfAbsent`(`:86-104`)——這是全 repo 唯一的 `ON CONFLICT DO NOTHING ... RETURNING` 前例,而 Phase 4 的冪等 insert 是它的同構延伸:

```java
    @Override
    public Optional<Holding> insertHoldingIfAbsent(Holding holding) {
        // ON CONFLICT DO NOTHING 讓併發首次建倉不會拋唯一鍵例外（不會污染交易）；
        // 回傳空 Optional 表示已被他交易插入，呼叫端重讀後改走 update 併倉。
        return jdbcClient.sql("""
                insert into holdings (user_id, asset_id, ...)
                values (:userId, :assetId, ...)
                on conflict (user_id, asset_id) do nothing
                returning id, user_id, asset_id, ...
                """)
            .param("userId", holding.userId())
            .query(this::mapHolding)
            .optional();     // ← 零列時回 empty，不拋例外
    }
```

**既有 `insertTransaction`(`:34-70`)是要被改的那個方法** —— 它已是 `insert ... returning ... .single()`。Phase 4 要:(a) 加 `idempotency_key` 欄位與 `:idempotencyKey` 參數;(b) 加 `on conflict (user_id, idempotency_key) where idempotency_key is not null do nothing`;(c) `.single()` **改為 `.optional()`**(Pitfall 2:`DO NOTHING` 零列時 `.single()` 會拋)。

**必守的既有慣例(逐條可從本檔觀察):**
- SQL 以 text block 撰寫、全小寫關鍵字、**只用具名參數**,絕不字串串接。
- `TRANSACTION_COLUMNS`(`:23-26`)是共用欄位常數 —— 新欄位若要出現在查詢結果,加在這裡而非各處複製。
- row mapper 逐欄 `rs.getXxx`,`OffsetDateTime` 用 `rs.getObject(col, OffsetDateTime.class)`、UUID 同理。
- 新的「以 `(user_id, idempotency_key)` 查既有交易」方法照 `findHoldingForUpdate`(`:72-84`)的 `.query(...).optional()` 形狀寫,但**不要**加 `for update`。

**Anti-pattern:** 絕不對 `transactions` 用 `ON CONFLICT DO UPDATE`(觸發 V8 `trg_transactions_no_update` 直接炸)。

---

### `TradingService.createTrade`(service, request-response + CRUD)— 本階段技術核心

**Analog:** 它自己(`TradingService.java:60-104`)。要改的是**順序**,不是風格。現況逐字:

```java
    @Transactional
    public TradeDto createTrade(Long userId, CreateTradeRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "request is required");
        }
        AssetSummary asset = resolveTradeableAsset(request.symbol());
        TradeType type = TradeType.fromApiValue(request.type());
        BigDecimal fee = Objects.requireNonNullElse(request.fee(), BigDecimal.ZERO);
        OffsetDateTime executedAt = Objects.requireNonNullElseGet(request.executedAt(), OffsetDateTime::now);
        Optional<Holding> current = repository.findHoldingForUpdate(userId, asset.id());
        Holding next = switch (type) { ... };            // ← holdings 先被改
        ...
        TradeTransaction saved = repository.insertTransaction(new TradeTransaction(...));   // ← transaction 後 insert
        portfolioCache.invalidateAfterTrade(userId, asset.id());
        return mapper.toTradeDto(saved);
    }
```

**RESEARCH 的結論(Q1)是把 insert 提前到 holdings 之前**,以 `ON CONFLICT DO NOTHING RETURNING` 判斷 key 歸屬:有列 → 繼續改 holdings;零列 → 什麼都沒碰,重讀既有交易 → 比對 payload(D-07)→ 回傳。單一 `@Transactional` 即可。

**必守的既有慣例:**
- 錯誤一律 `throw new BusinessException(ErrorCode.X, "…")`(`:63, 80, 240, 248, 253`),controller 不做業務判斷。
- 錯誤訊息**刻意不回射使用者輸入**。樣板見 `parseTimestamp`(`:171-180`)的 javadoc:「錯誤訊息只說明期望格式、刻意不回射原始輸入值」。→ **絕不把 idempotency key 串進訊息**(反例:`BackfillController:105-106`)。
- 字串判空一律 `StringUtils.isBlank`(`:137, 252, 259`)。
- 每個非私有方法有完整繁中 javadoc:`<p>` 說明 + `@param` + `@return` + `@throws`(樣板 `:106-124`)。
- `@Transactional` 加在方法上;**禁止 self-invocation**(`this.someTransactionalMethod()` 不會產生交易)。

**Anti-patterns(RESEARCH Q1.2 / Pitfall 3 / 4):**
- 同一 `@Transactional` 內 catch 唯一約束例外後重讀 → PostgreSQL 該 tx 已中止。
- payload 比對用 `BigDecimal.equals` → scale 差異必然假性 409。用 `compareTo`。
- 時間比對用 `OffsetDateTime.equals` → offset 差異必然假性 409。用 `isEqual` / `toInstant()`。

**Test analog:** `stock-module-trading/src/test/java/.../service/TradingServiceTest.java` — 純 Mockito、無 Spring context、mock 全部協作者:

```java
@DisplayName("交易清單查詢參數解析")
class TradingServiceTest {
    @BeforeEach
    void setup() {
        repository = mock(TradingRepository.class);
        assetFacade = mock(AssetFacade.class);
        portfolioCache = mock(PortfolioCache.class);
        service = new TradingService(repository, assetFacade, portfolioCache, new TradingMapper());
    }
}
```

慣例:類別級 `@DisplayName` + 繁中 javadoc 說明「這個測試鎖的是哪條契約」、每個 `@Test` 帶繁中 `@DisplayName`、`ArgumentCaptor` 攔截傳給 repository 的物件、`verifyNoInteractions` 證明「不該被呼叫的沒被呼叫」——**duplicate 進來時 `verifyNoInteractions(portfolioCache)` / `verify(repository, never()).updateHolding(any())` 就是「不重複更新 holdings」的直接斷言**(judgment §5)。

---

### `TradePayloadMatcher`(domain, transform 純函式)

**Analog:** `stock-module-trading/.../domain/HoldingCalculator.java` — 專案內唯一的「無狀態、無 I/O、可單測的領域計算類別」。它的 `applyBuy` / `applySell` 在違規時同樣 `throw new BusinessException(...)`(`:47, 66, 75`)。

**Test analog:** `stock-module-trading/src/test/.../domain/HoldingCalculatorTest.java` — 零 mock、純輸入輸出斷言。這是 D-07 比對邏輯最便宜的紅燈起點:先寫「scale 不同的 BigDecimal 視為相同」「offset 不同但瞬間相同的時間視為相同」兩條測試,必紅。

---

### `TradingController.createTrade`(controller, request-response)

**Analog(結構):** 同檔 `createTrade`(`:36-53`)。header 接收的語法慣例來自 `BackfillController:90`,但 **`required` 值不同(D-05 要 `required = true`)、語意不同(Backfill 回 409 拒絕,本階段回既有交易)——不要照抄它的 service**。

現況的 controller 契約(必須保留):

```java
    @PostMapping("/trades")
    @PreAuthorize("hasAuthority('TRADE_EXECUTE')")
    public ApiResponse<TradeDto> createTrade(
        @Valid @RequestBody CreateTradeRequest request,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        Long userId = authenticatedUserId(authentication);
        String ip = ClientIpResolver.resolve(servletRequest);
        try {
            TradeDto trade = tradingService.createTrade(userId, request);
            auditLogger.log(userId, "trade_create", "trade:" + trade.id(), "success", ip);
            return ApiResponse.success(trade, ApiMetaFactory.current());
        } catch (BusinessException exception) {
            auditLogger.log(userId, "trade_create", "trade", "failure:" + exception.errorCode().name(), ip);
            throw exception;
        }
    }
```

規則:回傳 `ApiResponse.success(dto, ApiMetaFactory.current())`、成功/失敗**都**要 `auditLogger.log`、例外重拋不吞、**controller 不做業務判斷**(key 的 blank 檢查應在 service,見 Pitfall 14:`Idempotency-Key: `(空值)會通過 `required = true`)。

**Test analog:** `TradingControllerTest.java`(全檔已讀)——mock `TradingService` + `AuditLogger`,`assertThatThrownBy` + `verify(auditLogger).log(eq(...), ...)`。加 header 參數後**該檔的 `controller.createTrade(...)` 呼叫端會編譯失敗 —— 那就是 D-05 的第一個紅燈**,不需要另造測試。同檔 `tradeRequest()` helper(`:52-54`)是建 `CreateTradeRequest` 的樣板。

---

### `GlobalExceptionHandler`(middleware, request-response)

**Analog:** 同檔 `handleValidation`(`:56-64`)——D-16 欄位級錯誤的產生處,也是新增 `MissingRequestHeaderException` handler 時要複製的形狀:

```java
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
            fields.put(error.getField(), error.getDefaultMessage())
        );
        ApiError error = ApiError.of(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), fields);
        return ResponseEntity.badRequest().body(ApiResponse.failure(error, ApiMetaFactory.current()));
    }
```

形狀:`@ExceptionHandler(X.class)` → `ApiError.of(code, message[, fields])` → `ResponseEntity.status(code.httpStatus()).body(ApiResponse.failure(error, ApiMetaFactory.current()))`。**絕不**把 exception 的原始 message 直接當 API message。

---

### `TradingIdempotencyIT`(test, IT)

**Analog:** `stock-start/src/test/java/dowob/xyz/stockwebv2/start/TradingApiIT.java`(全檔結構已讀)。

```java
@AutoConfigureMockMvc
class TradingApiIT extends ContainerIT {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void buyThenSellUpdatesHoldingsAndPortfolioSummary() throws Exception {
        AuthTokens tokens = register("trading-owner@example.com", "tradingowner", "Password1");

        String buyResponse = mockMvc.perform(post("/api/v1/trades")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + tokens.accessToken())
                .content(buyBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", equalTo(true)))
            .andExpect(jsonPath("$.data.id", notNullValue()))
            .andReturn().getResponse().getContentAsString();
        String buyId = objectMapper.readTree(buyResponse).get("data").get("id").asText();
        ...
    }
}
```

慣例:`extends ContainerIT`(已含 PG16 + Redis + Kafka + Flyway wiring)、`@AutoConfigureMockMvc`、`register(...)` helper 取 bearer token、body 用 text block、斷言走 `jsonPath("$.data...")` 與 `$.error.code`、常數化 fixture(`EXECUTED_AT_OLDEST` 等)並用繁中註解說明「為什麼刻意這樣設值」。

**併發測試已有現成樣板** —— `TradingApiIT` 的 import 已含 `CountDownLatch` / `ExecutorService` / `Executors` / `Future` / `TimeUnit`,直接照抄該檔既有的併發區塊來測「兩個 request 同 key 只建一筆」。

**必要的 IT 覆蓋(RESEARCH B10~B13):** 缺 header → 400;同 key 重送 → 回同一 `data.id` 且 holdings 只變一次;併發同 key → 只建一筆;oversell 失敗 → key **未被燒掉**(同 key 修正後可再送);錯誤回應**不得**含 key 字串。

> **⚠️ 給 planner:`stock-module-trading` 目前沒有任何 `*IT`,只有 unit test。** IT 一律放 `stock-start`。若要在 module 內建 IT,需先補 `TradingTestApplication` 與 pom test deps(RESEARCH「Wave 0 Gaps」)——建議不要,沿用 `stock-start` 即可。

---

## Pattern Assignments — Frontend

### `[FE] src/services/tradingApi.ts`(service adapter, request-response)

**Analog A(三件組骨架 + `live` 契約):** `src/services/portfolioApi.ts:140-202`

```typescript
export function createMockPortfolioApi(): PortfolioApi {
  // 每個 getter 都重新呼叫 useMockPortfolioStore()：延遲解析是 reactivity 與
  // 跨測試隔離（testSetup 每個測試換 pinia）的關鍵，不可改成在此捕捉 store/陣列。
  const live: PortfolioLiveMockData = {
    get trades() { return useMockPortfolioStore().trades; },
    get lastFill() { return useMockPortfolioStore().lastFill; },
  };
  return { mode: 'mock', live, async getSummary() { ... } };
}

export function createHttpPortfolioApi(basePath = '/api/v1'): PortfolioApi {
  return {
    mode: 'api',
    getSummary: () => apiRequest<PortfolioSummaryDto>(`${basePath}/portfolio/summary`),
    listTrades: params => apiPaginatedRequest<TradeDto>(`${basePath}/trades${buildQueryString({...})}`),
  };
}

export function createPortfolioApi(mode: RuntimeDataMode, basePath = '/api/v1'): PortfolioApi {
  return mode === 'api' ? createHttpPortfolioApi(basePath) : createMockPortfolioApi();
}
```

介面 javadoc 也是契約的一部分(`portfolioApi.ts:39-46`),`tradingApi.ts` 應照抄這段語氣:

```typescript
/**
 * portfolio domain 的唯一消費介面。
 *
 * 頁面用法（judgment §3：元件永遠不 import mock store）：
 * - mock mode：經 `live` 取得 reactive 資料（Pinia reactivity 完整保留，含 lastFill 高亮）。
 * - API mode：`live` 為 undefined，改走 Promise 方法拿快照 + 明確 refetch。
 * - 分支判斷依據是 `api.live` 是否存在，不是 mode 字串。
 */
```

**Analog B(POST + 自訂 header — 唯一前例):** `src/services/opsApi.ts:146-154`

```typescript
    triggerJob: request => {
      const headers: Record<string, string> = {};
      if (request.idempotencyKey) headers['Idempotency-Key'] = request.idempotencyKey;
      return apiRequest<OpsJobDto>(`${basePath}/ops/jobs`, {
        method: 'POST',
        headers,
        json: { actionKey: request.actionKey, params: request.params },
      });
    },
```

`tradingApi.createTrade` **直接照這個形狀寫**,差別只有 key 是必填(不做 `if`)。不需要任何 transport 層擴充:CSRF、credentials、401 refresh + replay 都由 `apiClient` 處理,且 replay 用**同一份 options** 重打 → 自動沿用同一 key(D-14 的免費紅利)。

**mock 實作:** 委派 `useMockPortfolioStore().executeOrder()` 以保住 `lastFill`(judgment §3 只禁**元件** import mock store,`portfolioApi.ts:2` 本身就這樣做)。

**Test analog:** `src/services/portfolioApi.test.ts`

```typescript
function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}
beforeEach(() => { setActivePinia(createPinia()); });
afterEach(() => { vi.unstubAllGlobals(); });

describe('portfolioApi http adapter', () => {
  it('calls the contract endpoints and unwraps the ApiResponse envelope', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => { ... }));
  });
});
```

`tradingApi.test.ts` 必須額外斷言:**攔截到的 `RequestInit.headers` 含 `Idempotency-Key`**(從 `vi.stubGlobal('fetch', ...)` 的第二個參數讀)。

---

### `[FE] src/services/marketApi.ts`(service adapter, request-response)

**⚠️ 這是 CONTEXT.md 未列、由 RESEARCH 發現的必要新檔** —— 前端目前對 `api/v1/assets` / `klines` / `market/` **零命中**,`Markets.vue:129` 仍 `import { SYMBOLS, CRYPTO, FX, BONDS } from '../data'`。D-01 沒有這個 adapter 就做不到。planner **必須把它列進工作量**。

**Analog:** 同 `portfolioApi.ts` 三件組。要點:
- `searchAssets` 是**分頁端點** → 用 `apiPaginatedRequest`,不是 `apiRequest`(Pitfall 7)。
- `listKlines` 非分頁 → `apiRequest`;`KlineDto` 的 OHLCV 是 **JSON string**,`vue-tsc` 抓不到誤用(Pitfall 8)。
- `searchAssets` 需支援 `signal?: AbortSignal`——`ApiRequestOptions extends Omit<RequestInit,'body'>`(`apiClient.ts:32`)已支援,不需新機制。
- mock 實作從 `data.ts` 的 `SYMBOLS/CRYPTO/FX` 組 `AssetDto`、用 `genSeries()` 組 `KlineDto[]` → `OrderTicket.vue` 只剩一條資料路徑,不需要 `if (live)` 去讀 `data.ts`。

---

### `[FE] src/services/portfolioRevision.ts`(singleton, pub-sub)

**Analog:** `src/services/pageApiClients.ts:19,37-39` 的「模組級 singleton + 顯式測試 reset」:

```typescript
let clients: RuntimeApiClients | null = null;

export function getRuntimeApiClients(basePath = '/api/v1'): RuntimeApiClients { ... }

export function resetRuntimeApiClientsForTests() {
  clients = null;
}
```

**不用 Pinia:** `src/stores/` 底下全是 `mock*` 前綴的 mock 專用 store,把跨 mode 的協調狀態放進去會模糊 judgment §3 的界線。

**⚠️ 測試隔離:** `testSetup.ts` 刻意**不 import 任何 service module**(該檔 `:10-12` 註解明文說明:會搶在 `vi.mock` 生效前綁定真實實作)。→ `resetPortfolioRevisionForTests()` 必須在**各測試檔自己** `afterEach` 呼叫,**不得**加進 `testSetup.ts`。

---

### `[FE] src/services/pageApiClients.ts`(wiring)

**Analog:** 同檔全文(39 行)。新增 `trading` 與 `market` 兩個欄位到 `RuntimeApiClients`(`:9-17`)並在 factory(`:24-32`)註冊。

**Test analog:** `src/api-adapter-wiring.test.ts` — Phase 2 D-20 的「API mode 不得靜默回退 mock」防線:

```typescript
const mockFactoryCalls = vi.hoisted(() => ({
  auth: vi.fn(), aiAccess: vi.fn(), backtest: vi.fn(), ops: vi.fn(), portfolio: vi.fn(),
}));

afterEach(() => {
  cleanupMounted();
  mockFactoryCalls.portfolio.mockReset();
  vi.doUnmock('./services/portfolioApi');
});
```

新的 `trading` / `market` 必須同樣加進這個 hoisted 物件,並有「api mode 下 mock factory 未被呼叫」的斷言。

---

### `[FE] src/components/OrderTicket.vue`(component — 最大工作量)

**沒有 exact analog**(既有 `OrderTicket.vue` 幾乎全部要拆除)。**最接近的結構樣板是 `src/pages/Positions.vue`**,因為它是 Phase 3 已驗收的「四態 + API/mock 雙路徑」實作。

**四態機 + 錯誤診斷(`Positions.vue:336-368`):**

```typescript
// 模板不做型別窄化（vue-tsc 對模板內 union 窄化支援不穩），一律先投影成 computed。
const summaryLoading = computed(() => summaryState.value.status === 'loading');
const summaryError = computed(() => (summaryState.value.status === 'error' ? summaryState.value.error : null));
const summary = computed(() => (summaryState.value.status === 'loaded' ? summaryState.value.data : null));

// D-12：診斷資訊只在錯誤狀態出現，且只露 code / traceId（不外洩後端 message）。
function describeError(error: unknown): BlockError {
  if (error instanceof ApiClientError) return { code: error.code, traceId: error.requestId };
  return { code: 'UNKNOWN_ERROR', traceId: null };
}

async function loadSummary() {
  summaryState.value = { status: 'loading' };
  try {
    summaryState.value = { status: 'loaded', data: await api.getSummary() };
  } catch (error) {
    summaryState.value = { status: 'error', error: describeError(error) };
  }
}
```

OrderTicket 需要三份同構的獨立狀態:symbol typeahead、klines 走勢圖、SELL 預檢 holdings。**`describeError` 的形狀直接複製**,但 D-16 要求再多一層:`error instanceof ApiClientError && error.fields` → 綁欄位;否則依 `error.code` 分派到底部。

**mock/API 分支(`Positions.vue:369-374`):**

```typescript
onMounted(() => {
  // mock mode 完全走 live 委派，不打任何網路。
  if (live) return;
  void loadSummary();
  void loadHoldings();
});
```

分支依據永遠是 **`api.live` 是否存在**,不是 mode 字串。

**Test analog:** `src/pages/Positions.test.ts` — 本專案元件測試的最完整樣板:

```typescript
import Positions from './Positions.vue';
// 原始碼字面（Vite ?raw）：用來斷言 Positions 沒有 import mock store（PORT-04 / judgment §3）。
import positionsSource from './Positions.vue?raw';
import { resetRuntimeApiClientsForTests } from '../services/pageApiClients';
import { cleanupMounted, flushAsync, mountWithPinia } from '../testUtils';

/**
 * 「後端值 ≠ 前端算式」的關鍵 fixture。
 * qty×price = 1000 但後端 marketValue = 999⋯⋯任何一處回到前端重算，對應斷言就會紅。
 */
const TRUTH_HOLDING: HoldingDto = { ... };
```

**三個必抄的手法:**
1. **`?raw` 原始碼斷言** — `expect(orderTicketSource).not.toContain('useMockPortfolioStore')` 是 Discretion「移除直接 import」最直接的紅燈。
2. **「刻意矛盾」fixture** — 後端回傳值與任何前端算式都不同,任何前端重算立刻紅。用在 result 畫面必須顯示**後端 `TradeDto`** 而非表單值。
3. **`vi.stubEnv('VITE_DATA_MODE','api')`** — `testSetup.ts` 預設鎖 mock,api 測試各自覆蓋。

`src/testUtils.ts` 已提供 `mountWithPinia` / `flushAsync` / `clickButton` / `buttonByText` / `rowByText` / `cleanupMounted` —— **不要**在新測試檔自己重寫 mount helper(`task4.test.ts:13-28` 有一份 legacy 私有版本,那是要被淘汰的,不是樣板)。

---

### `[FE] src/pages/{Overview,Positions,Trades}.vue`(page component, event-driven)

三頁**目前都沒有任何 `watch(`** —— 這是新增。形狀統一:

```typescript
watch(portfolioRevision, () => {
  if (live) return;      // mock mode 走 Pinia reactivity，不打網路
  void loadSummary();
  void loadHoldings();
});
```

**Trades 頁已有現成入口(`Trades.vue:281-286`),D-11 直接呼叫它,不要另寫:**

```typescript
/** D-15：任何篩選或排序變更都經此入口，頁碼一律重置為 0 後再請求。 */
function applyQueryChange(mutate: () => void) {
  mutate();
  pageNo.value = 0;
  void loadTrades();
}
```

**D-13 的 `fresh` 接點(`Positions.vue:260-264` / `Trades.vue:109`)是要被清掉的 TODO 註解:**

```html
<!--
  API 路徑：每一格都是後端欄位（D-04）⋯⋯
  lastFill 在 API mode 無成交事件來源，故不綁 fresh class（Phase 4 引入 post-trade refetch 時再接）。
-->
```

**Test analog:** `src/pages/Trades.test.ts` / `Positions.test.ts` / `Overview.test.ts`(三檔皆存在)。D-11「新交易不在結果集內」的偵測要**比 `id` 是否在 `items` 內**,不得在前端重算篩選條件(Anti-pattern)。

---

### `[FE] src/i18n.ts`(config)

**Analog:** 扁平 `Record<Lang, Record<string,string>>`(`i18n.ts:4`),camelCase key,zh/en 兩邊都要加。

**Test analog:** `src/i18n.test.ts:6-33` — 直接複製這個結構,把 UI-SPEC 的新 key 列進陣列:

```typescript
const PORTFOLIO_STATE_KEYS = ['loading', 'loadFailed', 'noTrades', ...];

describe('i18n portfolio copy', () => {
  it('provides every portfolio state key in both languages', () => {
    for (const key of PORTFOLIO_STATE_KEYS) {
      expect(I18N.zh[key], `zh.${key}`).toBeTruthy();
      expect(I18N.en[key], `en.${key}`).toBeTruthy();
      // t() 找不到 key 時會回傳 key 本身，確保文案真的翻譯過
      expect(t('zh', key)).not.toBe(key);
      expect(t('en', key)).not.toBe(key);
    }
  });

  it('reuses authRetry for the portfolio retry action instead of adding a duplicate key', () => { ... });
});
```

第二個 case 鎖的慣例是「**不新增同義 key**」——UI-SPEC 已明文要求複用 `loading` / `loadFailed` / `authRetry` / `authRequestId`。新 key 清單見 `04-UI-SPEC.md` §Copywriting Contract。

---

## Shared Patterns

### S1. 後端錯誤:一律 `BusinessException` + `ErrorCode`
**Source:** `TradingService.java:63,80,240,248,253`;`GlobalExceptionHandler.java:49-54`
**Apply to:** 所有 backend service / domain 檔
```java
throw new BusinessException(ErrorCode.ASSET_NOT_FOUND, "Asset is not tradeable: " + symbol);
```
Controller 只做 audit + 重拋。訊息**不得**含 ID、SQL 片段或使用者可控字串(`ai-docs/code-standards.md:79-84`)——**反例是 `BackfillController:105-106` 把 idempotency key 串進訊息,勿照抄。**

### S2. 後端 SQL:`JdbcClient` text block + 具名參數
**Source:** `JdbcTradingRepository.java` 全檔
**Apply to:** V10 相關的所有 repository 改動。絕對禁止字串串接。

### S3. 後端測試分層
**Source:** `ai-docs/testing-standards.md`;實例 `TradingServiceTest`(unit, 全 mock)/ `TradingControllerTest`(unit, mock 協作者)/ `TradingApiIT extends ContainerIT`(真 PG16)
**Apply to:** 每一個後端改動。**H2 不支援 partial unique index 的 `ON CONFLICT` 推斷 → 冪等只能用 Testcontainers 測**。
慣例:類別級與方法級繁中 `@DisplayName`、AssertJ(`assertThat` / `assertThatThrownBy`)、Mockito `mock/verify/verifyNoInteractions`、`@author Yuan` javadoc。

### S4. 前端 domain adapter 三件組
**Source:** `portfolioApi.ts:47-54,140-202`;`opsApi.ts:142-165`
**Apply to:** `tradingApi.ts`、`marketApi.ts`
`interface XxxApi { mode; ...methods; live? }` + `createMockXxxApi()` / `createHttpXxxApi(basePath)` / `createXxxApi(mode, basePath)`。元件永遠透過 `getRuntimeApiClients().xxx` 取得,**不 import mock store**。

### S5. 前端 HTTP:`apiClient` 是唯一 transport 邊界
**Source:** `apiClient.ts`(`apiRequest` / `apiPaginatedRequest` / `buildQueryString` / `ApiClientError`)
**Apply to:** 所有前端網路呼叫。CSRF `X-XSRF-TOKEN`、credentials、401 單飛 refresh + 一次 replay 全部已就緒。**勿另造轉接層**(Phase 2 D-20)。

### S6. 前端 per-block 狀態機 + 診斷列
**Source:** `Positions.vue:336-368`;`Overview.vue:225-249`
**Apply to:** OrderTicket 的三個子區域、三個 portfolio 頁的 refetch
`{ status:'loading' } | { status:'loaded', data:T } | { status:'error', error:BlockError }`,`BlockError = { code, traceId }`;template 不做型別窄化,先投影成 `computed`;traceId 只在錯誤態出現。

### S7. 前端測試隔離
**Source:** `testSetup.ts`;`portfolioApi.test.ts:49-55`;`api-adapter-wiring.test.ts:16-35`
**Apply to:** 所有新前端測試
`testSetup` 預設鎖 mock → api 測試自行 `vi.stubEnv('VITE_DATA_MODE','api')`;`beforeEach(setActivePinia(createPinia()))`;`afterEach(vi.unstubAllGlobals())`;模組級 singleton 各測試檔自行 reset(**不得**加進 `testSetup.ts`)。

### S8. TDD 紅燈起點(每個工作區塊)
**Source:** `CLAUDE.md`(硬性)
最便宜的紅燈,按檔案類型:
| 檔案類型 | 第一個紅燈 |
|---|---|
| migration | `TransactionsAppendOnlyIT` 樣式的 `JdbcClient` 直下 SQL,斷言第二次 insert 拋 |
| domain 純函式 | `HoldingCalculatorTest` 樣式,零 mock 輸入輸出 |
| service | `TradingServiceTest` 樣式,`verify(repository, never()).updateHolding(any())` |
| controller | 改簽章 → 既有 `TradingControllerTest` 直接編譯失敗 |
| 端到端契約 | `TradingApiIT` 樣式的 MockMvc + `jsonPath("$.error.code")` |
| FE adapter | `portfolioApi.test.ts` 樣式的 `vi.stubGlobal('fetch', ...)` + header 斷言 |
| FE 元件 | `Positions.test.ts` 樣式的 `?raw` 原始碼斷言 + 矛盾 fixture |
| FE i18n | `i18n.test.ts` 樣式的 key 陣列迴圈 |

---

## No Analog Found

| File | Role | Data Flow | Reason |
|---|---|---|---|
| `[FE] src/services/marketApi.ts` 的 **klines mapping 與錯誤態** | service adapter | request-response | 前端從未消費 `GET /market/{symbol}/klines`(全 repo 零命中);Chart 頁仍未 API 化。**沒有前例可抄** —— 依 RESEARCH Q6.5 的端點契約 + S4/S5/S6 組合出來 |
| `[FE] OrderTicket` 的 **combobox typeahead + debounce + AbortController 競態** | component | request-response | 前端沒有任何 typeahead 前例;`CmdK.vue` 是本地資料過濾,非遠端查詢。依 UI-SPEC §2 的七態表格實作(250ms debounce、只採最後一次回應) |

兩者都應以 RESEARCH.md 的對應段落(Q6.4 / Q6.5 / Q6.6)為權威,而非「找一個不像的檔案照抄」。

---

## Metadata

**Analog search scope:**
- `stock-module-trading/`(main + test 全部 23 檔)
- `stock-db-migration/src/main/resources/db/migration/`(V1–V9)
- `stock-common/.../common/error/`、`stock-start/src/test/java/.../`(39 檔)
- `[FE] src/services/`(18 檔)、`src/pages/`、`src/components/`、`src/*.test.ts`

**Files read in full or targeted:** `TradingController.java`、`TradingService.java`、`JdbcTradingRepository.java:1-120`、`CreateTradeRequest.java`、`ErrorCode.java:25-60`、`GlobalExceptionHandler.java:40-75`、`V9__trading_query_indexes.sql`、`TradingControllerTest.java`、`TradingServiceTest.java:1-60`、`TradingApiIT.java:1-95`、`TransactionsAppendOnlyIT.java:1-45`、`flyway-convention.md:1-60`、`portfolioApi.ts`、`opsApi.ts:135-170`、`pageApiClients.ts`、`portfolioApi.test.ts:1-60`、`api-adapter-wiring.test.ts:1-70`、`Positions.vue:255-275,335-375`、`Trades.vue:245-295`、`i18n.test.ts`、`testSetup.ts`、`testUtils.ts`(exports)、`Positions.test.ts:1-45`、`task4.test.ts:1-30`

**Pattern extraction date:** 2026-07-26
**Upstream:** `04-CONTEXT.md`(D-01~D-16)、`04-RESEARCH.md`(Q0~Q12 / Pitfall 1~15)、`04-UI-SPEC.md` rev2

**⚠️ 兩件 planner 必須先處理的事(來自上游,本文件不重複論證):**
1. **draft PR #15 未合併** —— `ApiTimeParser` 不在 develop;`TradingService.createTrade` 的驗證順序會變。RESEARCH Q0 建議採 (c) 強化版:以 develop 為基準,並把 executedAt 驗證與 time parser **明確排除在 Phase 4 範圍外**(judgment §6:勿另造重複品)。
2. **`marketApi.ts` 是 CONTEXT.md 漏列的必要新檔** —— 不列進 plan 會嚴重低估工作量。
