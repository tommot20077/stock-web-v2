# 後端 PR #20 code review（`origin/develop...3c42cb2`，排除 `.planning/`）

審查範圍：21 檔 / +1811 −39。Main code：`ErrorCode`、`V11` migration、`TradingController`、`TradePayloadMatcher`、`TradeTransaction`、`JdbcTradingRepository`、`TradingRepository`、`TradingService`、`GlobalExceptionHandler`。Tests：`ErrorCodeTest`、`TradingControllerTest`、`TradingControllerWebMvcTest`、`TradingTestApplication`、`TradePayloadMatcherTest`、`TradingServiceTest`、`ErrorHandlingIT`、`TradingApiIT`、`TransactionsIdempotencyIT`、`ValidationBoundaryE2E`。

## 裁決：**MERGE**（0 BLOCKER / 0 MAJOR / 3 MINOR / 4 NIT）

---

## 1. 正確性 — 無 BLOCKER/MAJOR；1 MINOR

**流程確認（親讀 `TradingService.java:104-167`）**
- insert-first：`insertTransactionIfAbsent` (L124) 在 `findHoldingForUpdate` (L146) 之前；重送請求（快路徑 L119-123、併發重讀 L139-145）都不會進入持倉分支。✔
- 回滾不燒 key：`BusinessException extends RuntimeException`（`stock-common/.../BusinessException.java:5`），`@Transactional` 預設對 RuntimeException 回滾；SELL 持倉不足在 insert 之後拋出 → 整筆回滾。`TradingApiIT#rejectedTradeDoesNotBurnTheIdempotencyKey` 實測。✔
- 併發：兩個同 key 交易都通過 L119 的空查詢後同時 insert，後者在唯一索引上等待前者 commit；DO NOTHING 回零列 → L142 重讀（READ COMMITTED 每述句新快照，看得到已 commit 列）→ 比對回傳。若前者 rollback，後者的 insert 直接成功。等待期間後者不持有任何鎖，無死鎖環。重讀仍落空 → `TRADE_CONFLICT` 409 而非 NPE (L142-143)。✔
- 跨使用者：`findByIdempotencyKey` 同綁 `user_id`（`JdbcTradingRepository.java:130-132`）；索引 `(user_id, idempotency_key)`。✔
- `TradePayloadMatcher.matches` (L84-97)：六欄 assetId/type/quantity/price/fee/executedAt，不含 note，與凍結契約一致。✔
- BigDecimal 用 `compareTo` (L42)、時間用 `toInstant().equals` (L61)，皆對 scale / offset 免疫；`executedAt` 服務層先 `truncatedTo(MICROS)` (`TradingService.java:117`) 對齊 TIMESTAMPTZ 精度。✔
- 成功回應 `data` 不含 `idempotencyKey`：`TradeDto` 記錄無此欄位，`TradingMapper.toTradeDto` 未映射；`TradingApiIT` 斷言 `$.data.idempotencyKey` doesNotExist。✔

**[MINOR] C-1 `fee` 缺 `@Digits` / `@DecimalMax`，>8 位小數的 fee 合法重試會吃假性 409**
- `CreateTradeRequest.java:25`：`@DecimalMin("0.0") BigDecimal fee` — 與 quantity/price 不同，沒有 `@Digits(fraction = 8)`。
- 情境：client 送 `fee: 1.123456789`（9 位小數）→ 通過驗證 → PostgreSQL `NUMERIC(24,8)` 靜默四捨五入為 `1.12345679` 寫入 → 逾時重送同 payload → `sameAmount(1.12345679, 1.123456789)` 為 false（`TradePayloadMatcher.java:45`）→ 409 `TRADE_IDEMPOTENCY_KEY_REUSED`，而 D-07 的本意是「合法重試不得假性 409」。
- 同一欄位也沒有 `@DecimalMax`，`fee: 1e17` 會超出 NUMERIC(24,8) 的 16 位整數位 → DB 例外 → 500（此部分為 develop 既有，不是本 PR 新增；但假性 409 的互動是本 PR 新產生的）。
- 瀏覽器前端送的 fee 由 UI 控制，實務觸發機率低；bearer client 可觸發。修法一行：`@Digits(integer = 16, fraction = 8)`。**不阻擋合併**，建議記 todo 或順手補。

**NIT C-2** `TradingController.java:76`：冪等命中（替換既有交易）仍寫入 `trade_create / success` 稽核事件，同一筆交易在 audit.log 會出現多次「建立成功」。不是錯誤，但稽核語意上「replay」與「create」不可分。可在 service 回傳值標記或改寫 target。

**NIT C-3** `TradingService.java:118`：資產解析在冪等快路徑之前，若標的在原交易後被下架（`tradeable=false`），同 key 重送會得到標的不可交易的 4xx 而非既有交易。與 Javadoc L88-91 說明一致，屬有意取捨，記錄即可。

## 2. 安全 — 無發現（1 NIT 文件落差）

- 三種失敗（空白、超長、payload 不符）的訊息皆為靜態字串（`TradingService.java:184,187,214-217`），不回射 key；`TradingServiceTest#idempotencyKeyIsNeverEchoedInErrorMessages`、`TradingApiIT#errorResponsesNeverEchoUserControlledInput` 以 canary 對整個 body 斷言。✔
- `handleMissingRequestHeader`（`GlobalExceptionHandler.java:75-81`）`fields` 只放 `exception.getHeaderName()` → 靜態字串；不用 `exception.getMessage()`。✔
- `@PreAuthorize("hasAuthority('TRADE_EXECUTE')")` 保留（`TradingController.java:63`）。✔
- 三個 main 檔皆無 SLF4J logger；唯一輸出面是 `AuditLogger`，參數為 `"trade:" + trade.id()` 與 error code 名，不含 key（`TradingControllerTest` 兩條 canary 測試覆蓋）。✔
- 長度上限：`MAX_IDEMPOTENCY_KEY_LENGTH = 128`（`TradingService.java:59`）與 `VARCHAR(128)` 一致；Java `length()` 以 UTF-16 unit 計數，對 BMP 外字元比 PG 的字元計數更嚴，不會有「Java 放行、DB 拒絕」的縫隙。✔
- CORS：`SecurityConfig.java:128` `setAllowedHeaders("*")`，瀏覽器 preflight 對自訂 `Idempotency-Key` 會放行。✔

**NIT S-1** `ai-docs/security.md:158` 仍寫 `allowedHeaders: Authorization, Content-Type`，與程式碼 `*` 不一致（develop 既有落差）。本 PR 新增了瀏覽器必送的自訂 header，若日後有人「照文件」收緊 CORS，`POST /trades` 會在 preflight 被擋。建議把 `Idempotency-Key` 補進 security.md §8 與 `browser-auth-contract.md`（後者目前完全沒提到 Idempotency-Key，grep 零命中）。

## 3. 契約 — 無發現

- `ErrorCode.java:45`：`TRADE_IDEMPOTENCY_KEY_REUSED(409, ...)`，字面與 status 由 `ErrorCodeTest` 三條鎖定。✔
- 所有回應走 `ApiResponse.success/failure` + `ApiMetaFactory.current()`（judgment §4 信封）。✔
- 缺 header → 400 `VALIDATION_FAILED` + `fields['Idempotency-Key']`（`ErrorHandlingIT`、`TradingApiIT` 各一條）。✔

**[MINOR] K-1 `Idempotency-Key:`（有 header 但值為空/全空白）→ 400 但 `fields` 為空**
- Spring `@RequestHeader(required=true)` 只在 header **不存在**時拋 `MissingRequestHeaderException`；值為 `""` 會原樣進 service（`TradingControllerTest#blankIdempotencyKeyIsForwardedInsteadOfRejectedAtController` 即刻意鎖此行為），由 `TradingService.java:183-185` 丟 `BusinessException(VALIDATION_FAILED, ...)` → 走 `handleBusiness` → **無 `fields`**。
- 後果：D-16「前端靠 `fields` 分辨缺 header 與 body 欄位錯」在「空值 header」這個變體上失效，前端會把它歸為 body 錯。`TradingApiIT#blankIdempotencyKeyIsRejected` 只斷言 code，未斷言 fields，所以綠燈不代表契約完整。
- 前端自己產 key（UUID），實務不會送空值；bearer client 才會踩到。**不阻擋合併**；若要補，在 service 丟含 `fields` 的例外或在 controller 用 `BindException` 語意處理。

## 4. Migration — 無發現

- 只新增 `V11__transactions_idempotency_key.sql`；`V1`–`V10` 在本 diff 中零改動（flyway-convention L33「Never modify an applied migration」✔）。
- 部分唯一索引 `(user_id, idempotency_key) WHERE idempotency_key IS NOT NULL`（V11 L54-56）與 `JdbcTradingRepository.java:71` 的 `on conflict (user_id, idempotency_key) where idempotency_key is not null` 逐字對應；`TransactionsIdempotencyIT#insertTransactionIfAbsentIsNoOpOnDuplicateKey` 在真實 PG 上證明推斷成功。✔
- V8 三個 trigger 只攔 UPDATE/DELETE/TRUNCATE（`V8__transactions_append_only_trigger.sql:16-26`），ALTER/CREATE INDEX 為 DDL 不受影響；DO NOTHING 不產生 UPDATE。✔
- 未用 CONCURRENTLY 有 V10 實測理由（V11 檔頭 L27-31）。✔

## 5. 測試品質 — 無發現

- 命名：全部英文 camelCase + `@DisplayName` 繁中（`ErrorHandlingIT` 新增兩條沿用該檔既有的無 DisplayName 風格）。✔
- `TransactionsIdempotencyIT`：前五條繞過 service 直下 SQL 驗 DB 約束（含直接斷言 `pg_indexes.indexdef` 含 UNIQUE+WHERE，是唯一能抓到「誤建非部分索引」的方式，理由寫在 L27-31）；後三條用真實 repository bean 驗 ON CONFLICT 推斷與 DO NOTHING 語意。每條都有 `as(...)` 斷言。✔
- 併發測試非假測試：`TradingApiIT#concurrentSameKeyCreatesExactlyOneTrade` 用 `CountDownLatch` 同步 8 執行緒後斷言「8 個 id 全同 + totalElements=1 + totalQuantity=10（非 80）」。若持倉重複套用，第三條斷言會紅。既有的 8 執行緒首次建倉測試改為每執行緒不同 key（L191-197 註解說明原因），避免被冪等機制吞掉。✔
- `sameIdempotencyKeyReturnsExistingTradeAndAppliesHoldingOnce` 三重斷言（id 相同 / 帳本 1 列 / 持倉 10 非 20）。✔
- `TradingTestApplication`（`stock-module-trading/src/test/.../TradingTestApplication.java`）：library 模組沒有 `@SpringBootConfiguration`，`@WebMvcTest` 找不到錨點會直接失敗；放在 test scope、stock-start 不依賴 trading 的 test-jar，不會外溢。切片以 `OpenSecurityConfig` 放行並關 CSRF，且註明 envelope 驗收在 stock-start 的 IT（L28-36），能力界線誠實。✔
- pom 只新增兩個 Spring Boot 官方 test starter（`stock-module-trading/pom.xml:63-72`），與 T-04-SC 相符。✔

## 6. code-standards — 2 NIT

- 判空：`StringUtils.isBlank`（`TradingService.java:183`）、`Objects.requireNonNullElse`（L114）、`Objects.equals`（`TradePayloadMatcher.java:91`）。✔
- Javadoc：新類別 `TradePayloadMatcher`、`TradingTestApplication`、`TradingControllerWebMvcTest` 皆有描述/@author/@version；新 public 方法皆有 @param/@return。✔

**NIT Q-1** `GlobalExceptionHandler.java:17-18`：`import org.springframework.web.bind.MissingRequestHeaderException;` 重複兩行。JLS §7.5.1 對相同型別的重複 single-type import 視為忽略，**可編譯**，但 IDE/lint 會警告。刪一行。

**NIT Q-2** main code 新增 5 處 `//` 單行註解（`TradingService.java:111-112,115-116,121,140-141,152-154`），code-standards「No Single-line Comments」條目不符；不過該檔 develop 版本 L111-112、L152-154 已是 `//` 風格，屬「Consistency: follow existing local style」的既有慣例，不要求本 PR 處理。

## 7. 合併風險（3c42cb2）— 無發現

- `git diff --stat origin/develop HEAD -- stock-infrastructure stock-start/src/test/resources stock-start/src/main/resources stock-module-user` 為**空**：JWT/JWK 重構的所有檔案（`JwkKeyConverter`、`application-test.yaml` 的 `jwt:` 段、`application-e2e.yaml`）與 develop 完全相同，merge 未產生語意分歧。
- merge 只帶入 develop 的 7 檔（+450 −247），全部在 infrastructure/user/docs，與 trading 模組零交集；Phase 4 的 `TradingApiIT` 透過既有 `register()` helper 取 token，不硬編 JWT fixture（testing-standards「Forbidden to hardcode JWT token fixtures」✔）。
- 唯一舊 API 呼叫點 `insertTransaction(` 全 repo 零殘留（grep 排除 target）。

---

## 殘留風險 / 未確認事項

1. **未跑測試**（依指示）：以上「✔」對測試的判斷是讀碼結論；「492 unit / 106 IT 全綠」引用自 04-13-SUMMARY 與 STATE.md 的 2026-08-16 基準線，merge 後（3c42cb2）尚無本機或 CI 綠燈證據。
2. **READ COMMITTED 下 DO NOTHING 後重讀可見性**：由 RESEARCH Q1.8 `[ASSUMED]` 升為 IT 實測（累計 7 次連跑），但本質仍是機率性驗證；若日後偶發，處置是切 advisory lock 方案 E（已寫入測試註解），不得調參數。
3. **CI 的 Browser E2E 會 fallback 到前端 `develop`**（後端分支名 `feature/phase-04-trade-idempotency` ≠ 前端 `feature/phase-04-manual-trade-creation`），所以 PR #20 的 browser-e2e 沒有覆蓋 Phase 4 前端；不影響後端本身正確性，但「跨 repo 整合綠」不能從此 PR 的 CI 推得。
4. `ValidationBoundaryE2E` 掛在 `-Pe2e` profile，`./mvnw test` 與 `-am verify` 都不跑（LESSONS 2026-08-16 條目）；本 PR 已補 11 處 header，但需 CI 的 E2E job 綠才算確認。
