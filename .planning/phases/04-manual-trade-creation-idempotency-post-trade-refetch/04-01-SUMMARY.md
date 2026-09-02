---
phase: 04-manual-trade-creation-idempotency-post-trade-refetch
plan: 01
subsystem: database
tags: [postgresql, flyway, partial-unique-index, idempotency, testcontainers, error-code]

# Dependency graph
requires:
  - phase: 03-portfolio-read-api-mode
    provides: V9 交易查詢索引與 transactions 現況 schema（本 plan 的 ALTER 基準）
provides:
  - "transactions.idempotency_key 欄位（VARCHAR(128)、nullable）"
  - "部分唯一索引 uk_transactions_user_idempotency：(user_id, idempotency_key) WHERE idempotency_key IS NOT NULL"
  - "ErrorCode.TRADE_IDEMPOTENCY_KEY_REUSED（409）—— 前端 i18n 對照表已寫死此字面"
  - "TransactionsIdempotencyIT：五條繞過應用層的 DB 層約束行為驗收"
affects: [04-02 冪等 repository/service, 04-03 controller 與 header 契約, 04-06 前端 tradingApi, 04-UI-SPEC 錯誤文案]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "以部分唯一索引（partial unique index）承載冪等約束，predicate 必須與應用層 ON CONFLICT 逐字相同"
    - "migration 的 TDD RED：先寫直接查 information_schema / pg_indexes 與實際 INSERT 的 IT，再寫 SQL"

key-files:
  created:
    - stock-db-migration/src/main/resources/db/migration/V11__transactions_idempotency_key.sql
    - stock-start/src/test/java/dowob/xyz/stockwebv2/start/TransactionsIdempotencyIT.java
  modified:
    - stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/ErrorCode.java
    - stock-common/src/test/java/dowob/xyz/stockwebv2/common/error/ErrorCodeTest.java

key-decisions:
  - "migration 版本號由計畫的 V10 順延為 V11：V10 已被 develop 的 d76f824 占用，已套用版本號不可重用"
  - "idempotency_key 長度採 VARCHAR(128)（DP-4 的 [ASSUMED] 判斷）：需 > 36（UUID）且必須有上限"
  - "唯一索引維度固定為 (user_id, idempotency_key) 兩欄，杜絕跨使用者 key 命中（T-04-02）"
  - "TRADE_IDEMPOTENCY_KEY_REUSED 的 defaultMessage 為靜態英文字串，絕不回射 idempotency key 值（T-04-03）"

patterns-established:
  - "部分索引的驗收必須直接斷言 pg_indexes.indexdef 含 WHERE —— 「多列 NULL 共存」在非部分索引下也會過，抓不到漏寫 predicate"
  - "IT 直接以 JdbcClient 對真實 PostgreSQL 下 SQL，繞過應用層以證明防護落在資料庫層"

requirements-completed: [TRAD-03]

# Metrics
duration: 55min
completed: 2026-07-29
---

# Phase 04 Plan 01: 冪等約束與 409 error code 基石 Summary

**以 PostgreSQL 部分唯一索引 `(user_id, idempotency_key) WHERE idempotency_key IS NOT NULL` 把交易冪等防護釘在資料庫層，並鎖定前端 i18n 依賴的 `TRADE_IDEMPOTENCY_KEY_REUSED`(409) 字面。**

## Performance

- **Duration:** 約 55 min
- **Started:** 2026-07-29T14:52Z
- **Completed:** 2026-07-29T15:47Z
- **Tasks:** 3/3
- **Files modified:** 4（2 新增 / 2 修改）

## Accomplishments

- **DB 層冪等約束成立且經行為驗證**：同 user 同 key 的第二次 INSERT 由資料庫直接拒絕（`DataIntegrityViolationException`），不同 user 用同一個 key 各自建立成功，既有 NULL key 交易列無限共存 —— 三條性質同時綠。
- **部分索引 predicate 已鎖定**：`pg_indexes.indexdef` 同時含 `UNIQUE` 與 `WHERE`，這是 04-02 的 `INSERT ... ON CONFLICT (user_id, idempotency_key) WHERE idempotency_key IS NOT NULL` 能推斷出此索引的前提。
- **V8 append-only 保證未被削弱**：`TransactionsAppendOnlyIT` 三條回歸測試在 V11 套用後仍全綠（DDL 不經過 row/statement 層 DML trigger）。
- **409 error code 字面定案**：`ErrorCode.TRADE_IDEMPOTENCY_KEY_REUSED` 與既有三個 409 code 語意分離、訊息兩兩不同，且不含任何格式化佔位或使用者輸入。

## Task Commits

1. **Task 1: 新增 409 error code TRADE_IDEMPOTENCY_KEY_REUSED**
   - `a89e407` (test) — RED：三條斷言，enum 常數不存在導致編譯失敗
   - `c5b5fc3` (feat) — GREEN：常數加在 `TRADE_CONFLICT`(:44) 之後、`INTERNAL_ERROR`(:47) 之前
2. **Task 2: TransactionsIdempotencyIT** — `e0ddeaa` (test) — RED：2 failures + 3 errors
3. **Task 3: V11 migration** — `d51730b` (feat) — GREEN：Task 2 五條全綠

_Task 1 為 RED→GREEN 兩個 commit；三個 task 均無需 REFACTOR。_

## Files Created/Modified

- `stock-db-migration/src/main/resources/db/migration/V11__transactions_idempotency_key.sql`（新增）— `ALTER TABLE transactions ADD COLUMN idempotency_key VARCHAR(128)` + `CREATE UNIQUE INDEX uk_transactions_user_idempotency ON transactions (user_id, idempotency_key) WHERE idempotency_key IS NOT NULL`。註解密度比照 V9/V10：說明為何是欄位而非獨立表+清理 job、為何可對 append-only 表做 ALTER、為何欄位可為 NULL、為何採阻塞式建索引、以及 `WHERE` 是推斷的必要組成。
- `stock-start/src/test/java/dowob/xyz/stockwebv2/start/TransactionsIdempotencyIT.java`（新增）— 五條 `@Test`，`extends ContainerIT`，全部以 `@Autowired JdbcClient` 對 Testcontainers PostgreSQL 16（timescale/timescaledb:2.17.2-pg16）直接下 SQL。
- `stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/ErrorCode.java`（修改）— `:45` 新增 `TRADE_IDEMPOTENCY_KEY_REUSED(409, "Idempotency key was reused with a different trade payload")`。
- `stock-common/src/test/java/dowob/xyz/stockwebv2/common/error/ErrorCodeTest.java`（修改）— 新增三條測試並補上 `@DisplayName` import。

## 契約鎖定事項（後續 plan 必須遵守）

1. **`TRADE_IDEMPOTENCY_KEY_REUSED` 的字面已鎖定，不得更名。** `04-UI-SPEC.md` §Copywriting Contract 的 `tradeErrKeyReused` 對照表與前端 `error.code` 分派已寫死此字串；後端 enum 名稱與前端對照表 key 是同一份契約的兩端。
2. **索引名稱與 predicate 已鎖定。** 04-02 的 `ON CONFLICT (user_id, idempotency_key) WHERE idempotency_key IS NOT NULL` 必須**逐字**對應；省略 `WHERE` 會直接得到 `there is no unique or exclusion constraint matching the ON CONFLICT specification`。
3. **DP-1 (c) 的範圍排除仍然有效。** 本 plan **未做** `executedAt` 未來時間驗證、**未建** `ApiTimeParser`、**未動** `createTrade` 既有驗證順序 —— 那些屬 draft PR #15。後續 plan 不得「順手補上」。
   - **附帶觀察（給 04-02/04-03 的 planner）**：本次執行時，`stock-common` 的 surefire 輸出出現一組名為「API 時間參數解析」的 8 條測試，顯示 develop 上可能已存在 time parser 相關類別。實作 04-02/04-03 前請先 `grep` 確認，勿另造重複品（judgment §6）。
4. **`VARCHAR(128)` 是 DP-4 的 `[ASSUMED]` 判斷。** 既有慣例只有 `VARCHAR(10)`（type）/ `VARCHAR(500)`（note）可參考，128 是「必須 > 36 且必須有上限」推出的值，不是既有慣例的延伸。若日後 client 端 key 格式有定案，此值可再評估（但欄位已上線後不可縮小）。

## Decisions Made

- **版本號 V10 → V11**（見 Deviations #1）。
- **`insertTransactionWithNullKey` 以 SQL 字面 `NULL` 而非 named parameter 傳 null**：避免 PostgreSQL 對無型別 bind parameter 的推斷問題，且語意更直白。
- **測試資料以每次 `UUID.randomUUID()` 產生的 user 與 key 隔離**：`ContainerIT` 無 `@Transactional` 回滾（且 transactions 是 append-only，回滾以外無法清理），共用容器內的計數斷言必須自行 scope 到當次 seed 的 user / key。

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - 阻斷性問題] migration 版本號 V10 已被占用，順延為 V11**

- **Found during:** Task 3（V10 migration）
- **Issue:** 計畫寫「V10 已確認為下一個空號；draft PR #15 不含 V10，其他 branch 也沒有」，但實際上 `stock-db-migration/.../V10__trading_query_indexes_realign.sql` 已存在，來自 **`origin/develop` 的 commit `d76f824`**（「還原被 PR #15 改動的 V9,新增 V10 補上差異」）—— 該 commit 在 Phase 4 planning 之後才進 develop。沿用 V10 會產生重複版本號，Flyway 啟動即失敗。
- **Fix:** 檔名改為 `V11__transactions_idempotency_key.sql`，並在檔頭以註解記錄順延原因。**索引名稱 `uk_transactions_user_idempotency`、欄位名、predicate 一律不變**，故 04-02 的 `ON CONFLICT` 契約完全不受影響。
- **Files modified:** `stock-db-migration/src/main/resources/db/migration/V11__transactions_idempotency_key.sql`
- **Verification:** `git log -1 -- .../V10__trading_query_indexes_realign.sql` → `d76f824`，且 `git branch -a --contains d76f824` 含 `origin/develop`；跨全部 branch 的 `git ls-tree` 確認 V11 為真正的空號。IT 全綠證明 Flyway 成功套用 V1→V11。
- **Committed in:** `d51730b`
- **⚠️ 後續影響:** `04-02-PLAN.md:123`、`04-PATTERNS.md:19,56`、`04-RESEARCH.md:507` 仍寫 `V10__transactions_idempotency_key.sql`。**那些引用應讀作 V11**；檔名本身不是契約（索引名才是），但 04-02 的 executor 若照字面找檔案會找不到。

**2. [Rule 3 - 阻斷性問題] 計畫的 failsafe 指令分隔符 `+` 無效，改為逗號**

- **Found during:** Task 3 驗收
- **Issue:** 計畫寫 `-Dit.test=TransactionsIdempotencyIT+TransactionsAppendOnlyIT+FoundationMigrationIT`，實跑得到 `No tests matching pattern "...+...+..." were executed!` 並讓 build 失敗 —— failsafe 的多類別分隔符是逗號，不是 `+`。
- **Fix:** 改用 `-Dit.test=TransactionsIdempotencyIT,TransactionsAppendOnlyIT,FoundationMigrationIT`。
- **Files modified:** 無（僅指令）
- **Verification:** 改用逗號後 `Tests run: 9, Failures: 0, Errors: 0`、`BUILD SUCCESS`、`EXIT=0`。
- **Committed in:** N/A（無檔案異動；後續 plan 的驗收指令請用逗號）

**3. [Rule 3 - 驗收條件字面衝突] V11 註解原本含 `CONCURRENTLY` 字面**

- **Found during:** Task 3 驗收
- **Issue:** 驗收條件要求該檔「不含 `CONCURRENTLY`」，但初版檔頭在說明「為何不用非阻塞建索引」時出現該關鍵字兩次，會讓字面檢查誤判。
- **Fix:** 改寫該段為「刻意採阻塞式，不用 PostgreSQL 的非阻塞建索引模式」，保留完整取捨理由（含 V10 檔頭的 1.5 小時死鎖實測交叉引用）但不出現該關鍵字。
- **Files modified:** `V11__transactions_idempotency_key.sql`
- **Verification:** `grep -c "CONCURRENTLY" V11__...sql` → `0`；改寫後重跑驗收指令仍 `BUILD SUCCESS`。
- **Committed in:** `d51730b`

---

**Total deviations:** 3 auto-fixed（全為 Rule 3 阻斷性問題）
**Impact on plan:** 無範圍蔓延。三項都是計畫撰寫時的環境事實已改變（V10 被占用）或指令/字面瑕疵，實質產出與計畫的 `must_haves` 逐條一致；唯一對後續 plan 有影響的是 migration 檔名（索引名稱不變，故契約不變）。

## Issues Encountered

- **Maven 輸出中的繁中 `@DisplayName` 在 Windows console 呈現為亂碼**（`transactions �����䪺��Ʈw�h����`）。屬 console codepage 問題，不影響測試結果；以 `target/failsafe-reports/*.txt` 的類別全名報告交叉確認。
- **`Corrupted channel by directly writing to native stream in forked JVM` warning**：既有現象（Testcontainers 直寫 stdout），不影響結果，build 仍 SUCCESS。

## 驗收證據（實際指令輸出）

### Task 1 RED — `./mvnw -pl stock-common -am test -Dtest=ErrorCodeTest`

```
[ERROR] .../ErrorCodeTest.java:[171,29] cannot find symbol
  symbol:   variable TRADE_IDEMPOTENCY_KEY_REUSED
  location: class dowob.xyz.stockwebv2.common.error.ErrorCode
[ERROR] .../ErrorCodeTest.java:[177,29] cannot find symbol
[ERROR] .../ErrorCodeTest.java:[191,26] cannot find symbol
[ERROR] Failed to execute goal ...maven-compiler-plugin:3.14.1:testCompile ... Compilation failure
```

（enum 常數不存在造成的編譯失敗，即 `04-RESEARCH.md` Q11 B1 註記的合法 RED。）

### Task 1 GREEN — 同一指令

```
[INFO] Tests run: 25, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.593 s -- in dowob.xyz.stockwebv2.common.error.ErrorCodeTest
[INFO] Tests run: 25, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

enum 位置與訊息安全的字面檢查：

```
44:    TRADE_CONFLICT(409, "Holding changed during trade execution"),
45:    TRADE_IDEMPOTENCY_KEY_REUSED(409, "Idempotency key was reused with a different trade payload"),
47:    INTERNAL_ERROR(500, "Internal server error");
--- grep "String.format|%s|+ key|+ idempotency" → NONE
```

### Task 2 RED — `./mvnw -pl stock-start -am verify -Dit.test=TransactionsIdempotencyIT`

```
[ERROR] Failures:
[ERROR]   TransactionsIdempotencyIT.idempotencyIndexIsPartialAndUnique:86
Expected size: 1 but was: 0 in: []
[ERROR]   TransactionsIdempotencyIT.idempotencyKeyColumnExistsAsNullableVarchar128:64
Expected size: 1 but was: 0 in: []
[ERROR] Errors:
[ERROR]   TransactionsIdempotencyIT.multipleNullKeysCoexistForSameUser:113->insertTransactionWithNullKey:209
    ? BadSqlGrammar PreparedStatementCallback; bad SQL grammar [INSERT INTO transactions(... idempotency_key) ...]
[ERROR]   TransactionsIdempotencyIT.sameKeyAcrossDifferentUsersIsAllowed:137->insertTransactionWithKey:193
    ? BadSqlGrammar ...
[ERROR]   TransactionsIdempotencyIT.sameUserSameKeyIsRejectedByDatabase:100->insertTransactionWithKey:193
    ? BadSqlGrammar ...
[ERROR] Tests run: 5, Failures: 2, Errors: 3, Skipped: 0
[INFO] stock-start ........................................ FAILURE [02:49 min]
[INFO] BUILD FAILURE
```

失敗原因確認為「欄位與索引不存在」（查詢回 0 列 / `column does not exist`），**不是**編譯錯或容器啟不起來 —— 同一次 run 中 `stock-common` … `stock-module-trading` 全數 SUCCESS，容器亦成功啟動。

### Task 3 GREEN — `./mvnw -pl stock-start -am verify -Dit.test=TransactionsIdempotencyIT,TransactionsAppendOnlyIT,FoundationMigrationIT`

```
EXIT=0
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 46.72 s -- in dowob.xyz.stockwebv2.start.FoundationMigrationIT
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.389 s -- in transactions 表 append-only DB trigger
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.398 s -- in transactions 冪等鍵的資料庫層約束
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

（繁中類別名為 `@DisplayName`，console 原始輸出因 codepage 呈現亂碼，此處以 `target/failsafe-reports/*.txt` 的正確對應還原：`TransactionsAppendOnlyIT` 3/3、`TransactionsIdempotencyIT` 5/5、`FoundationMigrationIT` 1/1。）

Reactor 全綠（同一次 run，含 `stock-common` 83 tests、`ErrorCodeTest` 25/25）：

```
[INFO] stock-web-v2 ....................................... SUCCESS [  0.008 s]
[INFO] stock-common ....................................... SUCCESS [  8.049 s]
[INFO] stock-db-migration ................................. SUCCESS [  0.162 s]
[INFO] stock-infrastructure ............................... SUCCESS [  6.213 s]
[INFO] stock-module-user .................................. SUCCESS [  7.782 s]
[INFO] stock-module-asset ................................. SUCCESS [ 16.154 s]
[INFO] stock-module-backtest .............................. SUCCESS [  3.625 s]
[INFO] stock-module-market-data ........................... SUCCESS [04:07 min]
[INFO] stock-module-trading ............................... SUCCESS [  6.837 s]
[INFO] stock-start ........................................ SUCCESS [02:09 min]
[INFO] Total time:  07:06 min
```

### migration 未污染既有檔案

```
$ git status --short stock-db-migration/src/main/resources/db/migration/
?? stock-db-migration/src/main/resources/db/migration/V11__transactions_idempotency_key.sql

$ grep -c "CONCURRENTLY" .../V11__transactions_idempotency_key.sql
0
```

V7 / V8 / V9 / V10 皆未出現在 `git status`，一個位元組都沒被改動。

## Success Criteria 對帳

| 條件 | 狀態 | 證據 |
|------|------|------|
| `TRADE_IDEMPOTENCY_KEY_REUSED` 為 409 且與 `DUPLICATE_RESOURCE` / `TRADE_CONFLICT` 語意區分 | PASS | `ErrorCodeTest` 三條新測試綠（25/25） |
| 同 user 同 key 第二次 insert 被 DB 拒絕 | PASS | `sameUserSameKeyIsRejectedByDatabase` 綠（`DataIntegrityViolationException`） |
| 不同 user 同 key 各自成功 | PASS | `sameKeyAcrossDifferentUsersIsAllowed` 綠（count = 2） |
| 多列 NULL key 共存 | PASS | `multipleNullKeysCoexistForSameUser` 綠（count = 2） |
| `pg_indexes.indexdef` 含 `WHERE`（partial 已生效） | PASS | `idempotencyIndexIsPartialAndUnique` 綠（同時含 `UNIQUE` 與 `WHERE`） |
| V8 append-only 三條回歸仍綠 | PASS | `TransactionsAppendOnlyIT` 3/3 |
| V7/V8/V9 未被改動 | PASS | `git status --short` 僅列出新增的 V11 |

## Threat Model 對帳

| Threat ID | Disposition | 落實情形 |
|-----------|-------------|----------|
| T-04-01 | mitigate | `uk_transactions_user_idempotency` 部分唯一索引已建立並經行為驗證 |
| T-04-02 | mitigate | 索引為 `(user_id, idempotency_key)` 兩欄；跨使用者測試（Test 5）為直接驗收 |
| T-04-03 | mitigate | `defaultMessage` 為靜態英文字串，檔案內 `String.format` / `%s` / 字串串接皆零命中 |
| T-04-04 | mitigate | `VARCHAR(128)` 上限已生效（IT 斷言 `character_maximum_length = 128`）；應用層第二層長度驗證留給 04-03 |
| T-04-08 | mitigate | `TransactionsAppendOnlyIT` 三條回歸在 V11 套用後仍綠 |
| T-04-SC | accept | 本 plan 零新增依賴（`pom.xml` 未異動） |

**新增威脅面掃描：** 本 plan 未新增網路端點、認證路徑或檔案存取；schema 變更（新增 nullable 欄位 + 索引）已在既有 threat register 內。無新旗標。

## Known Stubs

無。本 plan 的三個產出皆為完整實作，無佔位、無 TODO、無硬編碼空值。

## User Setup Required

None —— 無外部服務設定。IT 依賴的 Docker 已在執行環境可用（server 29.5.3），Testcontainers 自動拉取 `timescale/timescaledb:2.17.2-pg16` / `redis:7.4-alpine` / `confluentinc/cp-kafka:7.6.0`。

## Next Phase Readiness

**Ready for 04-02（冪等 repository / service 層）：**

- `ON CONFLICT` 可推斷的索引已就位，predicate 為 `WHERE idempotency_key IS NOT NULL`。
- 409 error code 已存在，04-03 可直接 `throw new BusinessException(ErrorCode.TRADE_IDEMPOTENCY_KEY_REUSED, ...)`。

**移交給 04-02 的注意事項：**

1. **migration 檔名是 V11 不是 V10**（04-02-PLAN.md:123 的 read_first 路徑需自行修正）。
2. **`04-CONTEXT.md` `<code_context>` 的實作順序陷阱仍然有效**：必須「先查既有交易 → 有就回它、完全不碰 holdings」，唯一約束衝突（併發）的重讀**必須在新的 transaction** 進行（PostgreSQL 在約束違反後該 tx 已中止），不可照抄 `insertHoldingIfAbsent` 的重讀路徑。
3. **`BackfillIdempotencyService` 是同名不同語意的反例**（回 409 拒絕 vs. 回既有資源），勿照抄。
4. 本 repo 目前分支為 `feature/phase-04-trade-idempotency`，**尚未 push**。

---
*Phase: 04-manual-trade-creation-idempotency-post-trade-refetch*
*Completed: 2026-07-29*

## Self-Check: PASSED

所有宣稱的產出檔案與 commit hash 皆已於 repo 中驗證存在（4 檔案 / 5 commit）。
