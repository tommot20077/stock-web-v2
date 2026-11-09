---
phase: 05-cross-repo-browser-flow-verification-contract-hardening
plan: 09
subsystem: trading-validation / watchlist-db
tags: [error-envelope, field-validation, flyway, check-constraint, VER-01]
status: complete
requires:
  - 05-04(TradingApiIT 已有 G2 方法 cookieAuthenticatedTradeWithoutCsrfIsRejected 與 helper cookieLogin;本 plan 只動 futureExecutedAtIsRejectedButBackfillIsAllowed)
provides:
  - "POST /api/v1/trades 未來 executedAt → 400 VALIDATION_FAILED + error.fields.executedAt = \"must not be in the future\"(D-02a)"
  - "V20__watchlists_name_blank_like_check.sql:ck_watchlists_name_not_blank 以 Java isWhitespace ∪ isSpaceChar 字元集兜底(D-02b)"
affects:
  - 05-11(前端 OrderTicket ticket-field-error-executedAt 綁定測試,依賴本 plan 的 fields.executedAt)
  - 05-12(CI Integration job 證據:TradingApiIT、WatchlistApiIT 新斷言與 V20 套用)
tech-stack:
  added: []
  patterns:
    - "Postgres E-string \\uXXXX 字元集 + btrim(name, <set>) 做 Unicode 空白類 CHECK"
    - "IT 以 JdbcClient 繞過服務直接 insert,驗 DB 約束本身"
key-files:
  created:
    - stock-db-migration/src/main/resources/db/migration/V20__watchlists_name_blank_like_check.sql
  modified:
    - stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/TradingService.java
    - stock-module-trading/src/test/java/dowob/xyz/stockwebv2/trading/service/TradingServiceTest.java
    - stock-start/src/test/java/dowob/xyz/stockwebv2/start/TradingApiIT.java
    - ai-docs/trading-portfolio-contract.md
    - stock-start/src/test/java/dowob/xyz/stockwebv2/start/WatchlistApiIT.java
    - stock-module-asset/src/main/java/dowob/xyz/stockwebv2/asset/service/WatchlistService.java
    - ai-docs/watchlist-contract.md
    - ai-docs/flyway-convention.md
    - docs/guides/development.md
    - docs/architecture/overview.md
decisions:
  - "未來 executedAt 錯誤改丟 FieldValidationException(\"Invalid executedAt\", {executedAt: \"must not be in the future\"}),與 1900 下限、CashLedgerService occurredAt 同形;HTTP status / error.code / 5 分鐘容忍度不變"
  - "V20 字元集以 JDK 21.0.12 實際列舉 isWhitespace ∪ isSpaceChar 的 28 個 code point 為準(與 plan 列出的集合一致),以 E-string \\uXXXX 跳脫寫出,不用 locale 相依的 [[:space:]]"
metrics:
  duration: "約 25 分鐘"
  completed: 2026-11-09
estimate:
  tokens: 45000
  tasks: 2
actuals:
  tokens: 6999
  tasks: 2
  commits: 2
plan_head_before: f3920dfbaf8c04a5edd08d3ff59e44301244b1d3
plan_head_after: 7d3d29ce9d3289356344b3a0245589df7a06c561
---

# Phase 05 Plan 09: 交易未來時間 fields.executedAt + 觀察清單名稱 V20 DB CHECK Summary

`POST /api/v1/trades` 的「未來成交時間」400 改帶 `error.fields.executedAt = "must not be in the future"`(靜態字串、與 1900 下限同形),讓前端能把錯誤綁到成交時間欄位;新 migration V20 把 `ck_watchlists_name_not_blank` 擴充成與服務層 `normalizeName` 相同的 28 個 Unicode 空白類字元,只含 NBSP / 全形空白的名稱在 DB 層也被拒。

## 完成的任務

| Task | 名稱 | Commit | 檔案 |
|------|------|--------|------|
| 1(tracer) | executedAt 未來時間補 fields(service → 單元 → IT → 契約) | 4255acb | `TradingService.java`、`TradingServiceTest.java`、`TradingApiIT.java`、`trading-portfolio-contract.md` |
| 2 | V20 觀察清單名稱 DB CHECK + IT + 文件 | 7d3d29c | `V20__watchlists_name_blank_like_check.sql`(新)、`WatchlistApiIT.java`、`WatchlistService.java`、`watchlist-contract.md`、`flyway-convention.md`、`development.md`、`overview.md` |

### 變更重點

- **D-02(a)**:`TradingService.resolveExecutedAt` 未來分支 `BusinessException(VALIDATION_FAILED, "executedAt must not be in the future")` → `FieldValidationException("Invalid executedAt", Map.of("executedAt", "must not be in the future"))`;方法 Javadoc 刪除「無 fields,維持既有形狀」,`@throws` 改為兩種情況都丟 `FieldValidationException`。
  - `TradingServiceTest.futureExecutedAtErrorKeepsNoFields` → `futureExecutedAtErrorNamesTheField`(now + 6 分鐘;`fields` 恰為 `{executedAt: "must not be in the future"}`;canary:訊息與 fields 值不含請求時間字串;Facade / repository / cache 零互動)。
  - 新增 `futureExecutedAtWithinToleranceIsAccepted`(now + 4 分鐘照常入帳;先前沒有容忍度的正向測試)。
  - `TradingApiIT.futureExecutedAtIsRejectedButBackfillIsAllowed` 加 `jsonPath("$.error.fields.executedAt", equalTo("must not be in the future"))`。
  - 契約錯誤表 :89 與變更紀錄 2026-11-09 條目。
- **D-02(b)**:V20 `DROP CONSTRAINT` + `ADD CONSTRAINT ck_watchlists_name_not_blank CHECK (btrim(name, E'\u0009…\u3000') <> '')`;檔頭比照 V19:出處、為何、字元集與 Java 的對應(含同步義務)、UTF8 前提(Assumption A7)與 `chr(160)` 備案、「套用失敗時」查詢、V1~V19 不得修改。
  - `WatchlistApiIT.dbRejectsNameMadeOnlyOfNoBreakSpaces`(兩個 U+00A0、單一 U+3000 → `DataIntegrityViolationException`,該使用者 0 列)、`dbAcceptsNameWithInnerNoBreakSpace`(`科技\u00A0股` insert 成功且原樣存回)。
  - `WatchlistService.normalizeName` Javadoc 與 `watchlist-contract.md`(上限表 DB 欄、變更紀錄)改為「V20 以同一字元集兜底」。

## 驗證證據

- Task 1 RED:改測試後 `./mvnw -B -pl stock-module-trading -am test -Dtest='TradingServiceTest' -Dsurefire.failIfNoSpecifiedTests=false` → `Tests run: 51, Failures: 1`(`futureExecutedAtErrorNamesTheField:397`),`BUILD FAILURE`。
- Task 1 GREEN:改 service 後同命令 → `Tests run: 51, Failures: 0, Errors: 0, Skipped: 0`,`BUILD SUCCESS`。
- `./mvnw -B -q -pl stock-start -am test-compile` → exit 0(Task 1、Task 2 各跑一次);`./mvnw -B -q spotless:check` → exit 0(兩次)。
- `./mvnw -B -pl stock-module-asset -am test -Dtest='WatchlistServiceTest' …` → `Tests run: 33, Failures: 0`(Javadoc 變更後回歸)。
- 字元集:以 JDK 21.0.12 掃描全部 code point,`isWhitespace || isSpaceChar` 恰為 28 個(U+0009–000D、U+001C–001F、U+0020、U+00A0、U+1680、U+2000–200A、U+2028、U+2029、U+202F、U+205F、U+3000),與 V20 E-string 逐一相同。
- **本機 PostgreSQL 16(UTF8)額外驗證**(非 CI 替代):
  - psql:V18 形式 CHECK 下 `E'\u00A0\u00A0'` 可寫入(證明缺口);套用 V20 後 NBSP×2、U+3000、U+2007+U+202F+TAB+空白、ASCII 空白、U+205F+U+1680+U+2028+U+2029+U+001C+U+000B 五組全被 `ck_watchlists_name_not_blank` 拒絕;`科技\u00A0股`、`\u00A0科技股\u3000` 可寫入。
  - 「套用失敗時」查詢在有 NBSP-only 舊列的表上精準找出該列,且 V20 在該狀態下以 `is violated by some row` 失敗(交易內 rollback)。
  - Flyway 11.14.1(專案同版)+ postgresql 42.7.10:以 filesystem location 套用「V1 精簡表 + V20 原檔」→ `Successfully applied 2 migrations`;JDBC 直接 insert NBSP×2 / U+3000 → SQLState `23514`,`科技\u00A0股` 成功 —— 與 IT 預期的 `DataIntegrityViolationException` 對應。
- Acceptance greps:TradingService 舊 `BusinessException(... "executedAt must not be in the future")` = 0;`futureExecutedAtErrorKeepsNoFields` = 0;TradingApiIT `error.fields.executedAt", equalTo("must not be in the future")` = 1;契約「無 `error.fields`（既有形狀）」= 0;V20 檔 `ck_watchlists_name_not_blank` = 3(≥2)、`u00A0` = 2 行;`git diff --stat` 對 V1~V19 為空;WatchlistService「未同步擴充」= 0;`ls … | grep -c "^V20__"` = 1。

### 留給 05-12 的 CI 證據(本機無 Docker,`*IT` 只能 test-compile)

- CI Integration job 的 Flyway 在**所有** Testcontainers context 套用 V20 成功(無 `FlywayException` / `Migration V20__watchlists_name_blank_like_check.sql failed`)。
- `TEST-dowob.xyz.stockwebv2.start.TradingApiIT.xml`:`futureExecutedAtIsRejectedButBackfillIsAllowed` passed(failures=0、errors=0)。
- `TEST-dowob.xyz.stockwebv2.start.WatchlistApiIT.xml`:`dbRejectsNameMadeOnlyOfNoBreakSpaces`、`dbAcceptsNameWithInnerNoBreakSpace` passed,且既有 `nameRulesAreEnforced` 等方法不回歸。
- 記錄 run id。

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] 寫檔工具把 `\uXXXX` 跳脫轉成實際不可見字元**
- **Found during:** Task 2
- **Issue:** 以 Write / Edit 工具寫入 V20 與 WatchlistApiIT 時,` ` 之後的跳脫序列被轉成字面 NBSP / 全形空白等不可見字元(SQL 語意仍正確,但不可讀、acceptance grep `u00A0` 失敗、Java 測試字串不可見)。
- **Fix:** 以 python 明確寫出反斜線跳脫重建 V20 兩行字元集,並把 WatchlistApiIT 的字面 U+00A0 / U+3000 換回 `\u00A0` / `\u3000`;再以腳本確認兩檔(及其他改動檔)無殘留不可見空白類字元,並重跑 PG16 驗證。
- **Files modified:** `V20__watchlists_name_blank_like_check.sql`、`WatchlistApiIT.java`
- **Commit:** 7d3d29c(commit 前已修正)

**2. [Rule 2 - 文件正確性] 同步「最新 migration 版本」提示**
- **Found during:** Task 2
- **Issue:** `ai-docs/flyway-convention.md`(:43、:47)、`docs/guides/development.md`(:80)、`docs/architecture/overview.md`(:49)寫「最新 V19、下一個 V20」,V20 落地後會誤導下一個 migration 撞號。
- **Fix:** 改為最新 V20、下一個 V21。`AGENTS.md` 的 `V1__…V19__` 屬 GSD 生成區塊(來源 ARCHITECTURE.md),未手改。
- **Commit:** 7d3d29c

### 補充說明(非偏離)

- Task 1 額外新增 `futureExecutedAtWithinToleranceIsAccepted` 對應 plan `<behavior>` 第二條(先前沒有 4 分鐘容忍度的正向單元測試)。
- `TradingApiIT.futureExecutedAtIsRejectedButBackfillIsAllowed` 的 `@DisplayName` 補上「fields.executedAt 指名欄位(Phase 5 D-02a)」。
- `trading-portfolio-contract.md` 2026-11-07 的歷史變更紀錄(「仍無 `fields`」)保留原文,由新條目覆蓋說明。
- plan ledger 基準 `f3920df` 是本 plan 開始動 commit 時的 HEAD(其間另一 lane 有 `docs(05-07)`、`docs(05-11)` 兩個 SUMMARY commit,都在基準之前);`f3920df..HEAD` 只含本 plan 兩個 commit。

## Known Stubs

None.

## Threat Flags

None(沒有新端點或信任邊界;T-05-17 以 canary 斷言、T-05-18 以 V20 + IT 直接 insert 緩解,皆在 plan threat model 內)。

## TDD Gate Compliance

- Task 1:RED(改 `TradingServiceTest` 後 1 failure)→ GREEN(改 `TradingService` 後 51/51)已觀察;依任務 commit 粒度合併為單一 `fix(05-09)` commit(plan 指定的 commit 訊息)。
- Task 2:DB 約束的 RED/GREEN 需 Testcontainers,本機以 PG16 psql 觀察到 V18 下 NBSP-only 可寫入(RED 等價)→ V20 後被拒(GREEN 等價);正式 IT 執行證據待 05-12 CI。

## Self-Check: PASSED

- FOUND: stock-db-migration/src/main/resources/db/migration/V20__watchlists_name_blank_like_check.sql
- FOUND: 4255acb
- FOUND: 7d3d29c
