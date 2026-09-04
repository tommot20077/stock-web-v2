---
phase: 03-portfolio-read-api-mode
plan: 01
subsystem: backend
tags: [java, spring-boot, jdbcclient, postgresql, flyway, trading, pagination, sql-injection]

# Dependency graph
requires:
  - phase: 02-browser-auth-api-mode
    provides: Bearer token 驗證與 ApiResponse 信封（IT 以 register + Bearer 建立使用者的既有模式）
provides:
  - GET /api/v1/trades 的 type / dateFrom / dateTo / sort / direction 五個查詢參數
  - TradeSortKey / SortDirection 排序白名單 enum（寫死 SQL 片段，ORDER BY 不可參數化下的合規做法）
  - TradeQuery 型別化查詢物件（service → repository 的唯一入口）
  - JdbcTradingRepository 的單一來源動態 WHERE（list 與 count 共用，totalElements 不再可能漂移）
  - 預設排序 executed_at DESC, id DESC（取代 created_at）
  - V9 索引：idx_transactions_user_executed、idx_transactions_user_amount
affects: [03-05 Trades 頁改寫（前端依本 plan 鎖定的契約表實作）]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "ORDER BY 白名單：使用者輸入只用於『挑選』enum 常數，SQL 片段永遠是寫死常數"
    - "動態 WHERE 單一來源：private 方法同時產出 WHERE 字串與具名參數 Map，list 與 count 共用"
    - "日期區間一律半開 [dateFrom, dateTo)，避免邊界重複計入"
    - "排序一律附加 id tie-breaker，確保分頁決定性"

key-files:
  created:
    - stock-db-migration/src/main/resources/db/migration/V9__trading_query_indexes.sql
    - stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/domain/TradeSortKey.java
    - stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/domain/SortDirection.java
    - stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/repository/TradeQuery.java
    - stock-module-trading/src/test/java/dowob/xyz/stockwebv2/trading/domain/TradeSortKeyTest.java
    - stock-module-trading/src/test/java/dowob/xyz/stockwebv2/trading/service/TradingServiceTest.java
  modified:
    - stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/repository/TradingRepository.java
    - stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/repository/JdbcTradingRepository.java
    - stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/TradingService.java
    - stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/TradingController.java
    - stock-start/src/test/java/dowob/xyz/stockwebv2/start/TradingApiIT.java

key-decisions:
  - "不引入 commons-lang3：code-standards 建議的 StringUtils 需要該依賴，但全專案未宣告此依賴，改沿用 TradeType 既有的 value == null || value.isBlank() 寫法（consistency 優先於外部標準）"
  - "sort=quantity 刻意不建索引：per-user 交易量小，三鍵全建索引寫入成本不成比例，取捨已寫進 V9 SQL 註解"
  - "count 查詢不 join assets：共用的 WHERE 只引用 t.* 欄位，因此 count 可省下 join"
  - "type 空白字串視為不篩選，而非非法值（TradeType.fromApiValue 對空白會丟 TRADE_UNSUPPORTED_TYPE，故 service 先行短路）"
  - "日期解析錯誤訊息只說明期望格式、不回射原始輸入（T-03-03）"

metrics:
  duration: ~75 min
  completed: 2026-07-25
  tasks: 3
  commits: 3
---

# Phase 3 Plan 01: 交易查詢篩選與排序 Summary

`GET /api/v1/trades` 新增 type / 日期區間篩選與 executedAt / total / quantity 三鍵白名單排序，預設排序由 `created_at` 改為 `executed_at DESC, id DESC`，並以 V9 索引配套；list 與 count 改用單一來源 WHERE，消除 `totalElements` 與 `items` 漂移。

## 完成內容

| Task | 內容 | Commit |
|------|------|--------|
| 1 | `TradeSortKey` / `SortDirection` 白名單 enum 與 `TradeQuery` record | `4b98759` |
| 2 | repository 動態 WHERE/ORDER 重構、service/controller 接線、V9 migration | `2f15c33` |
| 3 | `TradingApiIT` 擴充：filter / sort / 一致性 / negative / 使用者隔離 | `e276de5` |

## TDD 證據

| Task | RED | GREEN |
|------|-----|-------|
| 1 | `TradeSortKeyTest` 編譯失敗：`cannot find symbol: class TradeSortKey`、`package SortDirection does not exist` | `Tests run: 16, Failures: 0, Errors: 0` |
| 2 | `TradingServiceTest` 編譯失敗：`method listTrades in class TradingService cannot be applied to given types`、`TradeQuery cannot be converted to java.lang.Long` | 模組全跑 `Tests run: 34, Failures: 0, Errors: 0`（TradeSortKeyTest 16 + TradingServiceTest 12 + HoldingCalculatorTest 5 + TradingControllerTest 1） |
| 3 | 將 `orderByClause` 暫時改回舊的 `order by t.created_at desc, t.id desc` 後重跑：`Tests run: 2, Failures: 2`（`defaultSortUsesExecutedAtInsteadOfCreatedAt`、`sortByTotalBreaksTiesDeterministicallyById` 皆在 `$.data.items[n].id` 斷言失敗）→ 還原後全綠 | `TradingApiIT` `Tests run: 13, Failures: 0`；出場閘門 `./mvnw -pl stock-start -am verify` → `Tests run: 80, Failures: 0, Errors: 0`、`BUILD SUCCESS`、exit 0 |

Task 3 的 RED 為「事後反向驗證」而非「先寫測試再實作」——原因見下方〈偏離與風險〉。

## （1）因 created_at → executed_at 切換而更新的既有 IT 斷言

**沒有任何既有斷言需要更新。** `git diff --stat` 對 `TradingApiIT.java` 為 `226 insertions(+), 0 deletions(-)`，即純新增、零修改。

逐一檢查結果：

| 既有測試 | 是否受排序切換影響 | 理由 |
|----------|-------------------|------|
| `buyThenSellUpdatesHoldingsAndPortfolioSummary` | 否 | 斷言 `items[0].id` 時該使用者只有 1 筆交易，任何排序下結果相同；且未帶 `executedAt`，`executed_at` 由 service 填 `now()`，與 `created_at` 同序 |
| `fullyClosedPositionStillCountsRealizedPnlInSummary` | 否 | 只斷言 summary，未查交易清單 |
| `sellRejectsOversell` | 否 | 只斷言錯誤碼 |
| `concurrentFirstBuysMergeWithoutUniqueViolation` | 否 | 只斷言 holdings 總量 |
| `tradingEndpointsRequireAuthentication` | 否 | 未帶權杖，未進入查詢 |

其他 IT（`TransactionsAppendOnlyIT`、`AuditLoggingIT` 等）與交易清單排序無關，全數維持綠燈（80/80）。

## （2）交易查詢索引的最終定義（V9 + V10）

> **索引分佈於兩個 migration，這是修正 convention 違規的結果。** 本節原先描述的「單一個 V9」
> 一度成立，但那是因為 PR #15 直接修改了已隨 PR #13 合併的 V9，違反
> `ai-docs/flyway-convention.md`「Never modify a migration that has already been applied」。
> V9 的內容確實變了（+43/−4 行），因此它的 Flyway checksum 也必然改變
> —— 已套用舊 V9 的資料庫會在下次啟動 checksum mismatch 而失敗。
> 現已把 V9 還原成 PR #13 的原始內容（git blob 為 `a2530ba`，與原始逐位元相同，
> 故 checksum 必然回到原值，不需任何手動 `flyway repair`），
> PR #15 真正新增的兩道述句移入 `V10__trading_query_indexes_realign.sql`。
> 最終 schema 與原本的意圖完全相同。
>
> **關於本節先前引用的兩個數字（`1874006957` / `2297068974`）**：那是用
> `zlib.crc32(整個檔案的原始位元組)` 算出來的，**不是** Flyway 的 checksum 值，
> 先前誤標為「Flyway checksum 的計算方式」。反編譯 `flyway-core` 11.14.1 的
> `ChecksumCalculator` 可見它的實際做法是
> `BufferedReader.readLine()` → `BomFilter` → `String.getBytes()` → `CRC32.update()`
> 逐行累加，**行尾終止符已被 `readLine()` 剝除、不計入**。
> 兩者結論一致（內容變則 checksum 變），但拿上面的數字去比對
> `flyway_schema_history.checksum` 會對不上，故在此更正。
>
> 這個差異另有一個正面結論：**行尾（CRLF / LF）不影響 Flyway checksum**。
> 本 repo 的 `.gitattributes` 未涵蓋 `*.sql` 且 `core.autocrlf=true`，
> 一度令人擔心 Windows build 與 Linux build 會產生不同 checksum——依上述演算法，
> 這個顧慮不成立，`.gitattributes` 不需要為此調整。

`V9__trading_query_indexes.sql`（隨 PR #13，內容自此不可再改）：

```sql
-- D-07：預設排序 executed_at DESC, id DESC 的配套索引
CREATE INDEX idx_transactions_user_executed ON transactions (user_id, executed_at DESC, id DESC);

-- D-06：金額（數量 × 單價）排序的 PostgreSQL 運算式索引；運算式必須加括號
CREATE INDEX idx_transactions_user_amount ON transactions (user_id, (quantity * price) DESC, id DESC);
```

`V10__trading_query_indexes_realign.sql`（補上 PR #15 的新增部分，述句皆可重複套用）：

```sql
-- D-05：symbol 篩選 + 預設排序的配套索引
CREATE INDEX IF NOT EXISTS idx_transactions_user_asset_executed
    ON transactions (user_id, asset_id, executed_at DESC, id DESC);

-- V7 的 (user_id, asset_id, created_at DESC, id DESC) 已無讀者
DROP INDEX IF EXISTS idx_transactions_user_asset_created;
```

- 三條索引皆以 `user_id` 開頭，對應恆存在的 `t.user_id = :userId` 條件。
- `id DESC` 納入索引尾端，讓 tie-breaker 不需額外排序步驟。
- **刻意不使用 `CONCURRENTLY`——這點實測過。** 一般 `CREATE INDEX` 確實會對 `transactions` 取 ACCESS EXCLUSIVE 鎖、在啟動期擋住 INSERT，直覺解法是改用 `CREATE INDEX CONCURRENTLY`；但在 Flyway 底下這會**直接死鎖**。Flyway 套用 migration 時另有一條連線持有 schema history 的交易並停在 `idle in transaction`，而 `CONCURRENTLY` 必須等待所有並行交易的 virtualxid 釋放，兩者互等。實測 `TradingApiIT`（Postgres 16 + Flyway 11）：V9 已被正確判定為 `[non-transactional]` 並在交易外執行，仍卡死逾 1.5 小時，`pg_stat_activity` 顯示 `Lock/virtualxid` 等待對象即 Flyway 自己的連線。在 migration 內用 `CONCURRENTLY`，是把「短暫鎖表」惡化成「啟動永遠不會完成」。
- **大表的正確做法是部署前手動建立**：先在線上以 `CREATE INDEX CONCURRENTLY` 建好（指令已完整寫入 V10 註解），**V10** 的述句都帶 `IF NOT EXISTS` / `IF EXISTS`，屆時該 migration 自然變成 no-op。現階段 `transactions` 資料量小，啟動期短暫鎖表可接受。**一個誠實的限制**：V9 建立的兩條索引用的是不帶 `IF NOT EXISTS` 的 `CREATE INDEX`，而 V9 已不可再改，所以這個手法對那兩條索引不適用——尚未套用 V9 的大表只能承受該次鎖表。今後新增索引的 migration 請一開始就寫 `IF NOT EXISTS`。
- **V7 的 `idx_transactions_user_asset_created` 一併移除**：本 phase 之後 `symbol` 篩選的排序改由 `idx_transactions_user_asset_executed` 承接，它已無任何讀者，留著只是讓每筆寫入多維護一份索引。同組的 `idx_transactions_user_created` 則**保留**——`sort=createdAt` 以它為配套索引。
- **`sort=quantity` 刻意不建索引**：per-user 交易筆數量級小，`user_id` 過濾後的排序成本可忽略；每鍵全建會讓每筆交易寫入都要維護更多份索引，寫入成本與收益不成比例。取捨已寫入 SQL 註解，日後單一使用者交易量顯著成長時再補建。
- Flyway 於每次 IT 啟動時套用 V1..V10，IT 全綠即證明兩者可乾淨套用、無 validate 錯誤。**但要注意這證明不了 checksum 相容性**：IT 用 Testcontainers，每次都是全新資料庫、沒有既存的 schema history 可比對，所以「修改已套用的 migration」這類問題對 CI 與 IT 完全隱形——這正是本節開頭那個違規躲過所有綠燈的原因。

## （3）最終 API 契約表（前端 Plan 05 依此實作）

`GET /api/v1/trades`

| 參數 | 型態 | 預設 | 語意 | 非法值行為 |
|------|------|------|------|-----------|
| `symbol` | string | 無（不篩） | 標的代號，前後空白會被 trim、轉大寫 | 查無此標的 → **404** `ASSET_NOT_FOUND` |
| `type` | string | 無（不篩） | `BUY` / `SELL`，大小寫不敏感；**空白字串等同不帶** | 400 `TRADE_UNSUPPORTED_TYPE` |
| `dateFrom` | ISO-8601 日期或時間戳 | 無 | `executed_at >= dateFrom`（含） | 400 `VALIDATION_FAILED`，訊息 `dateFrom must be an ISO-8601 date or timestamp` |
| `dateTo` | ISO-8601 日期或時間戳 | 無 | `executed_at < dateTo`（**不含**，半開區間 `[dateFrom, dateTo)`） | 400 `VALIDATION_FAILED`，訊息 `dateTo must be an ISO-8601 date or timestamp` |
| `sort` | string | `executedAt` | 白名單 `executedAt` / `createdAt` / `total` / `quantity`，大小寫不敏感；`total` 定義為 `quantity × price`（**不含 fee**） | 400 `VALIDATION_FAILED`，訊息 `sort must be one of executedAt, createdAt, total, quantity` |
| `direction` | string | `desc` | `asc` / `desc`，大小寫不敏感 | 400 `VALIDATION_FAILED`，訊息 `direction must be asc or desc` |
| `page` | int | `0` | 夾限 0..10000（超出不報錯，直接夾限） | 非數字 → 400 `VALIDATION_FAILED`，訊息 `page must be a number` |
| `size` | int | `20` | 夾限 1..100（超出不報錯，直接夾限） | 非數字 → 400 `VALIDATION_FAILED`，訊息 `size must be a number` |

`symbol` 查無標的是 **404 不是 400**：`ErrorCode.ASSET_NOT_FOUND` 宣告為 `ASSET_NOT_FOUND(404, "Asset not found")`，本表先前誤植為 400。依此表實作的前端若以狀態碼 400 分支處理此情形，需改為 404。

### 日期參數的傳送與接收格式

接收端（`ApiTimeParser.parseRangeBound`）接受三種形式，一律正規化成 `OffsetDateTime`：

| 客戶端送出 | 解析結果 | 說明 |
|-----------|---------|------|
| `2026-01-01T00:00:00Z` | 該瞬間 | **建議格式**；JS `new Date().toISOString()` 的原生輸出 |
| `2026-01-01T00:00:00%2B08:00` | 該瞬間 | 帶偏移量，`+` **必須**編碼成 `%2B`（見下） |
| `2026-01-01T00:00:00` | 補 UTC | 未帶偏移量時以 UTC 為基準 |
| `2026-01-01` | 整個當日 | 作為 `dateFrom` 取當日 00:00Z；作為 `dateTo` 取**隔日** 00:00Z |

- **`+` 必須百分比編碼成 `%2B`。** Servlet 對 query string 採 `application/x-www-form-urlencoded` 解碼規則，會把裸的 `+` 解成空白，`2026-01-01T00:00:00+08:00` 抵達服務層時會變成 `2026-01-01T00:00:00 08:00`。`encodeURIComponent()`、`URLSearchParams` 與 axios 的預設序列化都會正確編碼；手動字串拼接 URL 則不會。服務層雖已還原此種破壞（空白一律還原成 `+`），客戶端仍應正確編碼。
- **純日期的上界涵蓋當天整日。** `dateTo=2026-01-31` 代表「到 01-31 這天結束為止」，等價於 `2026-02-01T00:00:00Z` 的排除上界；同一個邊界寫成時間戳 `2026-01-31T00:00:00Z` 則是嚴格小於，01-31 當天不會被納入。日期選擇器請直接送純日期。
- **`dateFrom` 晚於 `dateTo` 回 400** `VALIDATION_FAILED`，訊息 `dateFrom must not be after dateTo`；兩者相等是合法的退化區間，回傳空頁。
- **`GET /api/v1/market/{symbol}/klines` 的 `from` / `to` 套用完全相同的三種形式與 `%2B` 規則。** 這兩個參數原先宣告成型別化的 `Instant`，只接受帶偏移量的完整時間戳，且未編碼的 `+` 會在 Spring 型別轉換前就被 servlet 解成空白而遭拒；改走同一個 `ApiTimeParser` 後兩個模組不再有兩套答案。唯一的差異是 **`to` 的空值語意**：trading 的 `dateTo` 省略代表不設上界，klines 的 `to` 省略代表**取當前時間**（`KlineQueryService` 既有行為，未變動）；另外 klines 要求 `to` 嚴格晚於 `from`，不接受 trading 允許的相等退化區間。

補充契約細節：

- 所有排序一律附加 `, t.id {direction}` 作為決定性 tie-breaker；`sort=total&direction=desc` 下金額相同者，**後插入者（id 較大）在前**。
- 日期篩選與**預設**排序以 `executed_at`（成交時間）為準，**非** `created_at`（入帳時間）。補登舊交易時兩者會分歧。`executed_at` 由提交者填寫，`created_at` 由資料庫產生並受 V8 append-only trigger 保護；需要防竄改順序時請用 `sort=createdAt`。
- `executedAt` 建立交易時**不得為未來時間**（容忍 5 分鐘時鐘偏移），否則 400 `VALIDATION_FAILED`；**不設下界**，補登舊交易是明確支援的情境。
- **多個參數同時非法時的錯誤優先序**：`GET /trades` 與 `POST /trades` 都是零 I/O 的白名單比對與時間檢查先行、需查資料庫的 `symbol` 解析最後。因此「`type` 打錯 + `symbol` 也不存在」回的是 `TRADE_UNSUPPORTED_TYPE`（非 `ASSET_NOT_FOUND`），且不會為必定被拒的請求付一次資產查詢。前端若要顯示單一錯誤，請以回應的 `error.code` 為準，不要假設優先序。
- `totalElements` 與 `items` 套用完全相同的 WHERE（單一來源），且 `listTrades` 標記為 `@Transactional(readOnly = true)` 讓 count 與 list 讀到同一個快照，因此帶篩選或併發寫入時兩者都一致。
- 篩選/排序的任何組合都不會跨使用者洩漏（`t.user_id = :userId` 恆在）。
- 回應信封與分頁結構不變：`$.data.items[]` / `$.data.page` / `$.data.size` / `$.data.totalElements` / `$.data.totalPages`。
- `TradeDto.id` 是 **uuid 字串**（非數字主鍵），排序 tie-breaker 用的是資料庫數字 id，兩者不同。

## Deviations from Plan

### 1. [Rule 3 - 阻擋問題] 未引入 commons-lang3

- **發現於**：Task 1
- **問題**：`ai-docs/code-standards.md` 建議以 `StringUtils.isBlank` 做空值檢查，我依此撰寫後發現全專案 `pom.xml` 皆未宣告 `commons-lang3`。
- **處置**：不新增依賴（新增套件屬於需人工確認的範疇），改沿用同 package `TradeType.fromApiValue` 既有的 `value == null || value.isBlank()` 寫法。code-standards「Consistency: Follow existing local style over external standards」支持此選擇。
- **影響檔案**：`TradeSortKey.java`、`SortDirection.java`
- **Commit**：`4b98759`

### 2. Task 3 的 TDD 順序為「事後反向驗證」

- **實況**：Task 3 的 IT 在 Task 2 實作已存在的前提下撰寫，首次執行即 13/13 綠，未先觀察到自然的 RED。
- **原因**：新參數在 Task 2 之前連 controller 都不存在，IT 會停在「參數被忽略」而非有意義的紅燈；plan 本身也將此 task 標為「Red/**確認** test」。
- **補救**：為避免寫出「永遠會綠」的假斷言，我把 `orderByClause` 暫時改回舊的 `order by t.created_at desc, t.id desc` 重跑，確認 `defaultSortUsesExecutedAtInsteadOfCreatedAt` 與 `sortByTotalBreaksTiesDeterministicallyById` 兩個測試確實轉紅（`Tests run: 2, Failures: 2`），再還原。此為 Task 3 的 RED 證據。
- **殘留風險**：其餘 6 個新 IT 方法（日期半開區間、type 篩選一致性、negative、使用者隔離）僅有綠燈證據，未逐一做反向驗證。

## Known Stubs

無。

## Threat Flags

無新增未列於 `<threat_model>` 的安全面。三項 `mitigate` 處置皆已落實：

| Threat ID | 落實方式 | 測試證據 |
|-----------|---------|---------|
| T-03-01（ORDER BY 竄改） | 排序鍵/方向經 enum 白名單映射寫死片段；service 層擋非法值 | `TradeSortKeyTest` 注入字串 negative（5 個 `@ValueSource` 值含 `executed_at; drop table transactions`）、IT `invalidSortAndDirectionAreRejected` |
| T-03-02（跨使用者洩漏） | WHERE 恆含 `t.user_id = :userId` | IT `filtersNeverLeakOtherUsersTrades`：5 種篩選排序組合皆斷言回應不含他人交易 id |
| T-03-03（錯誤訊息回射） | 訊息只述期望格式，不含原始輸入與 SQL 片段 | `TradeSortKeyTest.rejectionMessageDoesNotEchoInput`、`TradingServiceTest.malformedDateFromIsRejected`（`hasMessageNotContaining`） |

## Self-Check: PASSED

- 6 個新建檔案全部存在於工作樹。
- 3 個 commit 皆可由 `git log` 查得：`4b98759`、`2f15c33`、`e276de5`。
- 出場閘門 `./mvnw -pl stock-start -am verify` exit 0、`BUILD SUCCESS`、`Tests run: 80, Failures: 0, Errors: 0`。
- V1..V8 未被修改（`git diff --name-only -- stock-db-migration/` 於提交前為空，僅 V9 為新增檔）。
