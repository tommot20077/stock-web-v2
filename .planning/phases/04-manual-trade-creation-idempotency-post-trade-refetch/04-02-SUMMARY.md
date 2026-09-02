---
phase: 04-manual-trade-creation-idempotency-post-trade-refetch
plan: 02
subsystem: trading
tags: [postgresql, jdbcclient, on-conflict-do-nothing, partial-unique-index, idempotency, testcontainers, breaking-change]

# Dependency graph
requires:
  - phase: 04-manual-trade-creation-idempotency-post-trade-refetch
    plan: 01
    provides: V11 的 transactions.idempotency_key 欄位與部分唯一索引 uk_transactions_user_idempotency（ON CONFLICT 推斷的前提）
provides:
  - "TradeTransaction.idempotencyKey（record 第 13 個 component）"
  - "TradingRepository.insertTransactionIfAbsent(TradeTransaction) → Optional<TradeTransaction>：唯一的交易寫入路徑"
  - "TradingRepository.findByIdempotencyKey(Long, String) → Optional<TradeTransaction>：綁 user_id 的冪等鍵查詢"
  - "TransactionsIdempotencyIT 的 repository 層三條驗收（ON CONFLICT 推斷成立的證明）"
affects: [04-03 TradingService 冪等分支與 controller header 契約, 04-06 前端 tradingApi]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "部分唯一索引的 ON CONFLICT 必須連 WHERE predicate 一起逐字複製，欄位組合單獨相同不足以推斷"
    - "ON CONFLICT DO NOTHING + RETURNING 的 JdbcClient 收尾一律 .optional()，.single() 會把「冪等命中」誤報成系統錯誤"
    - "取代既有寫入方法而非並存新方法：只留一條寫入路徑，杜絕繞過冪等鍵"

key-files:
  created: []
  modified:
    - stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/domain/TradeTransaction.java
    - stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/repository/TradingRepository.java
    - stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/repository/JdbcTradingRepository.java
    - stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/TradingService.java
    - stock-module-trading/src/test/java/dowob/xyz/stockwebv2/trading/service/TradingServiceTest.java
    - stock-start/src/test/java/dowob/xyz/stockwebv2/start/TransactionsIdempotencyIT.java

key-decisions:
  - "insertTransaction 直接被 insertTransactionIfAbsent 取代而非並存：只留一條寫入路徑（judgment §6），代價是回傳型別變更的 breaking change"
  - "idempotencyKey 加在 record 最後一個 component：既有位置參數呼叫端的改動最小，diff 最好讀"
  - "insert 的 RETURNING 不 join assets，symbol 沿用呼叫端傳入值；findByIdempotencyKey 則 join assets 取回 symbol"
  - "TradingService 以 .orElseThrow(TRADE_CONFLICT) 過渡，冪等分支明確留給 04-03"

patterns-established:
  - "repository 層的冪等驗收要用真實 bean（@Autowired TradingRepository）而非直接下 SQL —— 要驗的正是 repository 自己組出來的那段 SQL"
  - "同一 key 的兩次呼叫必須各自帶新的 uuid，否則衝突可能撞在 uuid 唯一鍵上，證明不了冪等索引"

requirements-completed: [TRAD-03]

# Metrics
duration: 29min
completed: 2026-07-30
---

# Phase 04 Plan 02: 冪等 repository 層 Summary

**把 V11 的部分唯一索引接上資料存取層：交易寫入收斂成 `insertTransactionIfAbsent` 這唯一一條帶冪等鍵的路徑，衝突時回空 `Optional` 而非拋例外，並以真實 PostgreSQL 證明 `ON CONFLICT` 對部分索引的推斷成立。**

## Performance

- **Duration:** 約 29 min
- **Started:** 2026-07-30T02:19Z
- **Completed:** 2026-07-30T02:48Z
- **Tasks:** 2/2
- **Files modified:** 6（0 新增 / 6 修改）

## Accomplishments

- **`ON CONFLICT` 對部分索引的推斷已在真實 PostgreSQL 上證明成立**：Test 7 同 user 同 key 第二次呼叫回 `Optional.empty()`、不拋例外、`count(*)` 仍為 1。推斷失敗會直接拋 `there is no unique or exclusion constraint matching the ON CONFLICT specification`（不會靜默降級成一般 insert），因此這條綠燈就是 predicate 逐字對應 V11 的證明。
- **交易寫入收斂成單一路徑**：`insertTransaction` 已完全移除，全 repo 對該方法零命中。不留「沒有冪等保護的版本」——`transactions` 是 append-only 帳本，誤建的重複列永遠刪不掉。
- **跨使用者隔離在 repository 層成立**（T-04-02）：`findByIdempotencyKey` 的 WHERE 同時綁 `t.user_id`，Test 8 以另一個 userId 查同一 key 斷言 `Optional.empty()`。
- **V8 append-only 保證未被削弱**：`TransactionsAppendOnlyIT` 三條回歸仍全綠；實作全程未使用會就地改寫既有列的 `ON CONFLICT` 分支。
- **`TradingService` 流程零改動**：本 plan 只做因 record 變形與回傳型別變更而必須的呼叫端修正，語句順序完全未動（見下方 diff 佐證）。

## Task Commits

1. **Task 1: TradeTransaction 加 idempotencyKey component** — `40f3aac` (feat)
   - RED 與 GREEN 落在同一組檔案（RED 是「record 變形導致呼叫端編譯失敗」，沒有獨立的測試檔產出），故不切成兩個 commit——否則會留下一個編譯不過的 commit。RED 實際輸出見下方驗收證據。
2. **Task 2: repository 的 key 查詢與 ON CONFLICT 冪等 insert**
   - `eb313aa` (test) — RED：三條新測試，兩個方法不存在導致 `stock-start` testCompile 失敗
   - `a67d96d` (feat) — GREEN：`TransactionsIdempotencyIT` 8/8、`TransactionsAppendOnlyIT` 3/3

_兩個 task 均無需 REFACTOR。_

## Files Created/Modified

- `TradeTransaction.java`（修改）— record 末端新增 `String idempotencyKey`，component 總數 12 → 13。維持原檔無 javadoc 的簡單風格，未額外加註解（計畫明示「維持原樣即可」）。
- `TradingRepository.java`（修改）— 移除 `TradeTransaction insertTransaction(TradeTransaction)`；新增 `Optional<TradeTransaction> insertTransactionIfAbsent(TradeTransaction)` 與 `Optional<TradeTransaction> findByIdempotencyKey(Long, String)`，各配繁中 javadoc（語氣比照既有 `insertHoldingIfAbsent`），明寫「為何不加 FOR UPDATE」與「為何不保留無冪等保護的版本」。
- `JdbcTradingRepository.java`（修改）— 四處：`TRANSACTION_COLUMNS` 加 `t.idempotency_key`；`mapTransaction` 改為 `rs.getString("idempotency_key")`；`insertTransactionIfAbsent` 加 `on conflict (user_id, idempotency_key) where idempotency_key is not null do nothing`、`returning` 補欄位、收尾 `.single()` → `.optional()`；新增 `findByIdempotencyKey`（join assets、具名參數、`.optional()`、無 `for update`）。
- `TradingService.java`（修改）— 僅兩處：`new TradeTransaction(...)` 參數列多一個 `null`；`insertTransaction` → `insertTransactionIfAbsent(...).orElseThrow(TRADE_CONFLICT)`。**語句順序未動。**
- `TradingServiceTest.java`（修改）— 兩行 mock/verify 隨介面變更同步（見 Deviations #1）。
- `TransactionsIdempotencyIT.java`（修改）— 追加三條 `@Test` 與 `newTransaction` helper，改用 `@Autowired TradingRepository`；類別 javadoc 補上「後半段驗的是什麼」與「為何只能用真實 PG」，版本 1.0 → 1.1。

## 契約鎖定事項（04-03 必須遵守）

1. **`insertTransactionIfAbsent` 回傳空 `Optional` 是「冪等命中」，不是錯誤。** 04-03 必須把它接成「重讀既有交易 → 比對 payload → 回傳既有結果或 409」，而不是沿用目前的 `.orElseThrow`。
2. **`findByIdempotencyKey` 不持有任何鎖。** 它不加 `FOR UPDATE`（列可能還不存在，列鎖對不存在的列不生效）。併發保護完全由部分唯一索引承擔——04-03 不可假設這個查詢有互斥效果。
3. **`insertTransactionIfAbsent` 回傳的 `symbol` 來自呼叫端傳入值**（該述句沒有 join assets）；`findByIdempotencyKey` 回傳的 `symbol` 來自 join。兩條路徑都保證 `symbol()` 非 null，但來源不同。
4. **`ON CONFLICT` 的 predicate 不可動。** `where idempotency_key is not null` 少一個字就推斷失敗，且失敗方式是直接拋例外而非降級——會在 IT 立刻現形，但別在 code review 時「順手簡化」它。

## Decisions Made

- **取代而非並存**：`insertTransaction` 直接移除。並存會留下一條沒有冪等保護的寫入路徑，遲早有人用到；代價是回傳型別變更的 breaking change，但呼叫端只有一個（`TradingService:102`）。
- **`idempotencyKey` 加在 record 最後**：既有呼叫端全是位置參數，加在最後讓三處各只需補一個 `null`，diff 最小。
- **測試的每次 insert 都帶新的 `UUID.randomUUID()`**：若兩次呼叫共用同一個 uuid，第二次的衝突可能撞在 uuid 的唯一鍵上，Test 7 就證明不了冪等索引。這點寫進了 helper 的 javadoc。
- **Task 1 不拆 RED/GREEN 兩個 commit**：該 task 的 RED 是編譯失敗且沒有獨立測試檔產出，拆開會在歷史上留下一個編譯不過的 commit。RED 已實際執行並記錄輸出。

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - 阻斷性問題] `TradingServiceTest` 需同步修改，但不在計畫的 `files_modified` 內**

- **Found during:** Task 2（GREEN）
- **Issue:** 計畫的 `files_modified` 與 Task 2 的 `<files>` 都沒有列出 `TradingServiceTest.java`，但該檔 `:291` 與 `:301` 直接使用 `repository.insertTransaction(...)`。方法移除後 `stock-module-trading` 的 testCompile 必然失敗，驗收指令 `./mvnw -pl stock-module-trading -am test` 不可能綠。
- **Fix:** 兩行同步：`when(repository.insertTransaction(any(...))).thenAnswer(inv -> inv.getArgument(0))` → `when(repository.insertTransactionIfAbsent(any(...))).thenAnswer(inv -> Optional.of(inv.getArgument(0)))`；`verify(repository).insertTransaction(captor.capture())` → `verify(repository).insertTransactionIfAbsent(...)`。**測試意圖與斷言完全未改**（仍是「補登舊交易的 executedAt 被原樣傳給 repository」）。
- **Files modified:** `stock-module-trading/src/test/java/dowob/xyz/stockwebv2/trading/service/TradingServiceTest.java`
- **Verification:** `./mvnw -pl stock-module-trading -am test` → 48 tests, 0 failures, BUILD SUCCESS。
- **Committed in:** `a67d96d`

**2. [Rule 3 - 驗收條件字面衝突] javadoc 中的 `do update` 字面會讓 grep 驗收誤判**

- **Found during:** Task 2 驗收
- **Issue:** 驗收條件要求 `grep -in "do update" JdbcTradingRepository.java` 無輸出，但我寫的 javadoc 在說明「為何絕不可用 `DO UPDATE`」時出現該字面，導致字面檢查命中一筆——命中的是禁止該用法的警語本身，語意上完全相反。
- **Fix:** 改寫為「絕不可改用會就地改寫既有列的那個 `on conflict` 分支」，完整保留 V8 trigger 的因果說明但不出現該關鍵字。（與 04-01 Deviation #3 的 `CONCURRENTLY` 是同一類處置，沿用同一手法。）
- **Files modified:** `JdbcTradingRepository.java`
- **Verification:** `grep -i "do update"` → No matches found；改寫後重跑兩道驗收指令仍全綠。
- **Committed in:** `a67d96d`

### 計畫敘述與現實不符（不影響產出，記錄供後續 plan 參考）

**3. Task 1 的驗收條件「`TradingService.java` 仍不含 `ApiTimeParser`、`EXECUTED_AT_FUTURE_TOLERANCE`」在執行前就已不成立**

該檔在本 plan 開工前（base commit `710f9f0`）就已含 `ApiTimeParser` import（`:6-7`）、`EXECUTED_AT_FUTURE_TOLERANCE` 常數（`:50`）與 `resolveExecutedAt` 方法（`:195`）——它們來自 develop，不是本 plan 加的。04-01 SUMMARY 的「附帶觀察」已預告過這件事（`stock-common` 出現「API 時間參數解析」8 條測試）。

**處置：本 plan 對這三者一個字都沒動**，既未新增也未移除。該驗收條件的真實意圖（DP-1 (c)「Phase 4 不做 executedAt 驗證與 time parser」）仍然成立，只是字面已過期。**04-03 的 planner 請直接把這條刪掉或改寫為「不得改動既有的 `resolveExecutedAt`」**，否則 executor 會再撞一次。

**4. 行號漂移（不影響執行）**

計畫寫 `TradingService` 的 `new TradeTransaction(...)` 在 `:88-101`，實際在 `:102-115`；`JdbcTradingRepository.insertHoldingIfAbsent` 計畫寫 `:86-104`，實際 `:96-114`。皆為 develop 演進造成的偏移，以符號定位即可，未造成任何阻礙。

**5. 已在交辦說明中預告、確認成立的兩點**

- migration 檔名確為 `V11__transactions_idempotency_key.sql`（非計畫文字的 V10）；索引名與 predicate 未變，故 `ON CONFLICT` 契約不受影響。
- failsafe 多類別參數確實必須用逗號：本 plan 全程使用 `-Dit.test=TransactionsIdempotencyIT,TransactionsAppendOnlyIT`，未嘗試計畫中的 `+` 寫法。

---

**Total deviations:** 2 auto-fixed（皆 Rule 3）+ 3 記錄性事項
**Impact on plan:** 無範圍蔓延。兩項 auto-fix 都是「不做就無法通過計畫自己的驗收指令」的阻斷性修正，且都未改動任何測試意圖或既有行為。`must_haves` 四條逐條成立（見下方對帳）。

## Issues Encountered

- **Maven 完整輸出過大**：`./mvnw -pl stock-start -am verify` 的單次輸出達 54.7MB（Spring Boot context 對每個 `@SpringBootTest` 重啟一次 + Testcontainers 日誌），超出工具的擷取上限而被截斷。**改以 `target/failsafe-reports/*.txt` 與 `failsafe-summary.xml` 作為結果權威**，本 SUMMARY 引用的即是這些檔案的原文。
- **繁中 `@DisplayName` 在 Windows console 呈現亂碼**（如 `API �ɶ��ѼƸѪR`）：既有的 codepage 問題，不影響測試結果，同樣以 failsafe/surefire 報告的類別全名交叉確認。
- **`Corrupted channel by directly writing to native stream in forked JVM` warning**：既有現象（Testcontainers 直寫 stdout），build 仍 SUCCESS。

## 驗收證據（實際指令輸出）

### Task 1 RED — `./mvnw -pl stock-module-trading -am test`

加入 component 後、修正呼叫端前：

```
[ERROR] .../trading/service/TradingService.java:[102,63] constructor TradeTransaction in record
  dowob.xyz.stockwebv2.trading.domain.TradeTransaction cannot be applied to given types;
  reason: actual and formal argument lists differ in length
[ERROR] .../trading/repository/JdbcTradingRepository.java:[65,36] constructor TradeTransaction ... differ in length
[ERROR] .../trading/repository/JdbcTradingRepository.java:[79,20] incompatible types:
  java.lang.Object cannot be converted to dowob.xyz.stockwebv2.trading.domain.TradeTransaction
[ERROR] .../trading/repository/JdbcTradingRepository.java:[279,16] constructor TradeTransaction ... differ in length
[INFO] stock-module-trading ............................... FAILURE [  3.157 s]
[INFO] BUILD FAILURE
```

失敗處恰為計畫預測的三個呼叫端（`:79` 是 `:65` 那個 lambda 的連鎖推斷失敗），且同一次 run 中 `stock-common` 83 tests / `stock-infrastructure` 21 tests 全 SUCCESS——證明 RED 的成因是 record 變形，不是環境問題。

### Task 1 GREEN — 同一指令

```
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0  -- in 交易服務覆蓋（HoldingCalculatorTest 等）
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0  -- in HoldingCalculatorTest
[INFO] Tests run: 16, Failures: 0, Errors: 0, Skipped: 0 -- in 排序白名單解析
[INFO] Tests run: 23, Failures: 0, Errors: 0, Skipped: 0 -- in 交易清單查詢參數解析
[INFO] Results:
[INFO] Tests run: 48, Failures: 0, Errors: 0, Skipped: 0
[INFO] stock-module-trading ............................... SUCCESS [ 10.101 s]
[INFO] BUILD SUCCESS
```

`TradingService` 的 diff 恰為一個 `null`，無任何語句順序改動：

```
@@ -111,6 +111,7 @@ public class TradingService {
             cleanNote(request.note()),
             executedAt,
+            null,
             null
         ));
```

### Task 2 RED — `./mvnw -pl stock-start -am test-compile -DskipTests`

```
[ERROR] COMPILATION ERROR :
[ERROR] .../TransactionsIdempotencyIT.java:[175,64] cannot find symbol
  symbol:   method insertTransactionIfAbsent(dowob.xyz.stockwebv2.trading.domain.TradeTransaction)
  location: variable tradingRepository of type dowob.xyz.stockwebv2.trading.repository.TradingRepository
[ERROR] .../TransactionsIdempotencyIT.java:[206,61] cannot find symbol ... insertTransactionIfAbsent
[ERROR] .../TransactionsIdempotencyIT.java:[208,62] cannot find symbol ... insertTransactionIfAbsent
[ERROR] .../TransactionsIdempotencyIT.java:[240,26] cannot find symbol ... insertTransactionIfAbsent
[ERROR] .../TransactionsIdempotencyIT.java:[242,61] cannot find symbol
  symbol:   method findByIdempotencyKey(java.lang.Long,java.lang.String)
[ERROR] .../TransactionsIdempotencyIT.java:[254,37] cannot find symbol ... findByIdempotencyKey
[ERROR] Failed to execute goal ...maven-compiler-plugin:3.14.1:testCompile on project stock-start
```

失敗成因**只有**兩個尚不存在的方法，沒有任何其他錯誤——合法 RED。

### Task 2 GREEN — `./mvnw -pl stock-start -am verify -Dit.test=TransactionsIdempotencyIT,TransactionsAppendOnlyIT`

`stock-start/target/failsafe-reports/` 原文：

```
Test set: dowob.xyz.stockwebv2.start.TransactionsIdempotencyIT
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.393 s

Test set: dowob.xyz.stockwebv2.start.TransactionsAppendOnlyIT
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 34.34 s
```

```xml
<failsafe-summary ... timeout="false">
    <completed>11</completed>
    <errors>0</errors>
    <failures>0</failures>
    <skipped>0</skipped>
    <flakes>0</flakes>
</failsafe-summary>
```

8 = 04-01 的五條 DB 層 + 本 plan 的三條 repository 層；3 = `TransactionsAppendOnlyIT` 回歸。Flyway 於同一次 run 中 `Successfully applied 11 migrations to schema "public", now at version v11`。

### 字面驗收

```
$ grep -i "do update" .../JdbcTradingRepository.java
No matches found

$ grep -n "Optional<TradeTransaction> \(findByIdempotencyKey\|insertTransactionIfAbsent\)(\|insertTransaction(" .../TradingRepository.java
25:    Optional<TradeTransaction> insertTransactionIfAbsent(TradeTransaction transaction);
40:    Optional<TradeTransaction> findByIdempotencyKey(Long userId, String idempotencyKey);

$ grep -rn "insertTransaction(" --glob *.java .
No matches found        # 舊方法在全 repo 零殘留

$ grep -n "on conflict\|for update\|idempotency_key" .../JdbcTradingRepository.java
35:        t.fee, t.note, t.executed_at, t.created_at, t.idempotency_key      # TRANSACTION_COLUMNS
66:                    uuid, ..., executed_at, idempotency_key                # insert 欄位列
72:                on conflict (user_id, idempotency_key) where idempotency_key is not null do nothing
74:                          executed_at, created_at, idempotency_key         # returning
99:                rs.getString("idempotency_key")                            # insert row mapper
123:                where t.user_id = :userId and t.idempotency_key = :idempotencyKey
137:                for update                                                # findHoldingForUpdate（既有，非本次新增）
152:                on conflict (user_id, asset_id) do nothing                # insertHoldingIfAbsent（既有）
341:            rs.getString("idempotency_key")                               # mapTransaction
```

`findByIdempotencyKey` 的 SQL（`:120-125`）不含 `for update`；`:137` 屬既有的 `findHoldingForUpdate`，非本次新增。

### 未污染其他檔案

```
$ git diff --diff-filter=D --name-only 710f9f0 HEAD
（無輸出 —— 本 plan 零檔案刪除）

$ git status --short
（無輸出 —— 無未追蹤檔案殘留）
```

本 plan 未觸碰 sibling 前端 repo，亦未修改 `STATE.md` / `ROADMAP.md`（worktree 模式，由 orchestrator 集中更新）。

## must_haves 對帳

| 條件 | 狀態 | 證據 |
|------|------|------|
| repository 可用 (user_id, idempotency_key) 查出既有交易，且不加 FOR UPDATE | PASS | `findByIdempotencyKey` 綠（Test 8）；SQL `:120-125` 無 `for update` |
| 帶 key 的 insert 衝突時回空 Optional，不拋例外、不中止交易 | PASS | Test 7 綠：`second` 為 empty、無例外、count = 1 |
| 帶 key 的 insert 走 ON CONFLICT DO NOTHING（絕非 DO UPDATE） | PASS | `:72` 為 `do nothing`；全檔 `do update` 零命中；`TransactionsAppendOnlyIT` 3/3 |
| TradeTransaction 帶得動 idempotencyKey，查詢結果也讀得回 | PASS | Test 6（insert RETURNING 讀回）+ Test 8（查詢讀回）皆綠 |

## Success Criteria 對帳

| 條件 | 狀態 | 證據 |
|------|------|------|
| 只有一條交易寫入路徑，且帶得動 idempotency key | PASS | 全 repo `insertTransaction(` 零命中；`insertTransactionIfAbsent` 綁 `:idempotencyKey` |
| ON CONFLICT 推斷在真實 PG 上成功 | PASS | Test 7 綠（推斷失敗會直接拋例外，不會降級） |
| 衝突時回空 Optional、不拋例外、不中止交易 | PASS | Test 7 的 `second` 斷言 + 其後的 `count(*)` 查詢在同一測試內成功執行 |
| 跨使用者查詢隔離在 repository 層成立 | PASS | Test 8：別的 userId 查同一 key 回 `Optional.empty()` |

## Threat Model 對帳

| Threat ID | Disposition | 落實情形 |
|-----------|-------------|----------|
| T-04-01 | mitigate | `on conflict (user_id, idempotency_key) where idempotency_key is not null do nothing` + `.optional()`；Test 7 直接證明第二次呼叫不建列（count 仍為 1） |
| T-04-02 | mitigate | `findByIdempotencyKey` 的 WHERE 同時綁 `t.user_id`；Test 8 以別的 userId 斷言 `Optional.empty()` |
| T-04-08 | mitigate | 全檔 `do update` 零命中（含註解）；`TransactionsAppendOnlyIT` 3/3 回歸綠 |
| T-04-12 | mitigate | 兩段新 SQL 皆 text block + 全小寫關鍵字 + 具名參數（`:idempotencyKey` / `:userId`），零字串串接。`findByIdempotencyKey` 唯一的 `+` 是拼接寫死常數 `TRANSACTION_COLUMNS`，與 `listTransactions` 既有作法一致，不含任何請求輸入 |
| T-04-SC | accept | 零新增依賴（`pom.xml` 未異動） |

**新增威脅面掃描：** 本 plan 未新增網路端點、認證路徑或檔案存取；唯一的新查詢面（以 key 查交易）已由 T-04-02 涵蓋並在測試中驗收。無新旗標。

## Known Stubs

**`TradingService.createTrade` 的 `.orElseThrow(TRADE_CONFLICT)` 是刻意的過渡處置，不是完成品。**

- **檔案：** `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/TradingService.java:115`
- **現況：** `insertTransactionIfAbsent` 回空 `Optional` 時直接拋 `TRADE_CONFLICT`。由於目前 `idempotencyKey` 一律傳 `null`（不落在部分索引範圍內），這條分支在執行期**不可能**被觸發——它只是讓型別成立的最小處置。
- **為何刻意：** 計畫（`<objective>` 與 `<action>`）明文把冪等分支劃給 04-03；本 plan 的界線是「repository 層可用」，不含 service 流程重排。
- **由誰解決：** **04-03** 會把它換成「查既有交易 → 有就回它、完全不碰 holdings；insert 回空則重讀 → 比對 payload → 回既有結果或 `TRADE_IDEMPOTENCY_KEY_REUSED`(409)」。

除此之外無其他 stub：兩個 repository 方法都是完整實作，無 TODO、無硬編碼空值。

## User Setup Required

None —— 無外部服務設定。IT 依賴的 Docker 已在執行環境可用（server 29.5.3），Testcontainers 自動拉取 `timescale/timescaledb:2.17.2-pg16` / `redis:7.4-alpine` / `confluentinc/cp-kafka:7.6.0`。

## Next Phase Readiness

**Ready for 04-03（TradingService 冪等分支 + controller header 契約）：**

- `findByIdempotencyKey` 與 `insertTransactionIfAbsent` 兩個方法都已存在並經真實 PG 驗證，04-03 的失敗一定是流程問題而不是 SQL 問題——這正是本 plan 拆分的目的。
- `TradeTransaction` 已帶得動 key，service 只需在建構時填入即可。
- `ErrorCode.TRADE_IDEMPOTENCY_KEY_REUSED`(409) 由 04-01 就位，可直接使用。

**移交給 04-03 的注意事項：**

1. **`.orElseThrow(TRADE_CONFLICT)` 必須被換掉**（見 Known Stubs），不可留著。
2. **併發退路不可照抄 `insertHoldingIfAbsent` 的重讀路徑。** 本 plan 的 `DO NOTHING` 不會中止交易，所以「insert 回空 → 同一 tx 內 `findByIdempotencyKey` 重讀」是安全的；但若 04-03 改用會拋唯一約束例外的寫法，PostgreSQL 該 tx 即已中止，重讀必失敗（04-CONTEXT 的實作順序陷阱）。
3. **`findByIdempotencyKey` 不持有鎖**，別把它當互斥手段。
4. **Task 1 驗收條件的字面已過期**（見 Deviations #3）：`ApiTimeParser` / `EXECUTED_AT_FUTURE_TOLERANCE` 早已在 develop 的 `TradingService` 內，04-03 的 plan 若沿用該條字面會誤導 executor。
5. 本 plan 在 worktree 分支 `worktree-agent-a4062345dbe467d61` 上完成三個 commit，**未修改 `STATE.md` / `ROADMAP.md`**，待 orchestrator 合併後集中更新。

---
*Phase: 04-manual-trade-creation-idempotency-post-trade-refetch*
*Completed: 2026-07-30*

## Self-Check: PASSED

本 SUMMARY 宣稱的 6 個異動檔案皆存在於 repo，4 個 commit hash（`40f3aac` / `eb313aa` / `a67d96d` / `b0003c8`）皆已於
`git log` 驗證存在，且各 commit 的檔案清單與上方「Task Commits」逐條相符。工作區乾淨，無未追蹤檔案、無檔案刪除。
