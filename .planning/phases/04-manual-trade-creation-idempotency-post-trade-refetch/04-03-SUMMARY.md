---
phase: 04-manual-trade-creation-idempotency-post-trade-refetch
plan: 03
subsystem: backend
tags: [idempotency, insert-first, payload-matching, bigdecimal, timestamptz, mockito]

# Dependency graph
requires:
  - phase: 04-manual-trade-creation-idempotency-post-trade-refetch
    provides: 04-01 的 uk_transactions_user_idempotency 部分唯一索引與 TRADE_IDEMPOTENCY_KEY_REUSED；04-02 的 insertTransactionIfAbsent / findByIdempotencyKey
provides:
  - "TradePayloadMatcher：D-07 payload 比對的無狀態純函式（sameAmount / sameInstant / matches）"
  - "insert-first 的 TradingService.createTrade(userId, request, idempotencyKey)"
  - "POST /api/v1/trades 的必填 Idempotency-Key header"
affects: [04-04 controller 契約與錯誤映射, 04-05 併發 IT, 04-07 前端 tradingApi 的 header 送出, 04-13 雙 mode 收尾]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "insert-first：append-only 帳本的寫入先於就地改寫的狀態，讓重送在碰到 holdings 之前就被攔下"
    - "等值比較與型別精度分離：BigDecimal 用 compareTo（非 equals）、OffsetDateTime 用 isEqual（非 equals）"

key-files:
  created:
    - stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/domain/TradePayloadMatcher.java
    - stock-module-trading/src/test/java/dowob/xyz/stockwebv2/trading/domain/TradePayloadMatcherTest.java
  modified:
    - stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/TradingService.java
    - stock-module-trading/src/test/java/dowob/xyz/stockwebv2/trading/service/TradingServiceTest.java
    - stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/TradingController.java
    - stock-module-trading/src/test/java/dowob/xyz/stockwebv2/trading/api/TradingControllerTest.java

key-decisions:
  - "DP-2 方案 A：insert 先於 holdings 變更——持倉是就地改寫的狀態、交易是 append-only 帳本，先改持倉再靠唯一約束發現重送，重複的改動已經發生而帳本上改不回來"
  - "資產解析早於冪等快路徑：payload 比對維度是 assetId 而非標的代號（代號唯一性未經查證），代價是重送多付一次資產解析"
  - "DP-7：executedAt 截到微秒（TIMESTAMPTZ 精度），否則帶奈秒的客戶端每次合法重送都吃到假性 409"
  - "冪等鍵長度上限 128 由 service 層擋下（T-04-04），不讓它落到 DB 變成與冪等命中難以區分的 DataIntegrityViolationException"
  - "DP-8：快路徑命中不失效 portfolioCache——資料完全沒有變動"

patterns-established:
  - "冪等命中的驗收寫成 verify(..., never())：斷言「沒有發生的事」比斷言回傳值更貼近 judgment §5 的要求"
  - "訊息不外洩敏感值的測試用可辨識 canary（LEAK-CANARY-12345）+ hasMessageNotContaining"

requirements-completed: [TRAD-03, TRAD-06]

# Metrics
duration: 跨兩段（executor 約 5min + orchestrator 接手約 8min）
completed: 2026-08-15
---

# Phase 04 Plan 03: insert-first 冪等 createTrade Summary

**把 `createTrade` 從「先改 holdings 再 insert」翻轉成 insert-first,讓重送的請求在碰到持倉之前就被攔下 —— 這是本 Phase 的技術核心。**

## ⚠️ 本 SUMMARY 的撰寫情境（誠實揭露）

原 executor 在 Task 2 的 GREEN commit **之前**因 session 限額被中止,留下 worktree 內三個未提交的檔案(`TradingController` / `TradingService` / `TradingControllerTest`)。本 SUMMARY 由 orchestrator 接手完成:

- **Task 1 / Task 2 的 RED 失敗輸出我沒有親眼看到**,只能從 commit 順序推斷。下方「驗收證據」只列我**實際執行並看到輸出**的部分。
- 實作內容是原 executor 寫的;我做的是驗證、提交(`234a78d`)、併回主分支(`b3c8f5d`)與補寫本 SUMMARY。
- `./mvnw test` 一度因 Docker daemon 未執行而失敗,啟動 Docker Desktop 後重跑通過 —— 過程記錄於 Deviations #2。

## Performance

- **Started:** 2026-07-30T23:11+08:00（executor）
- **Completed:** 2026-08-15T14:35+08:00（orchestrator 接手;中間隔了兩週的中斷期）
- **Tasks:** 2/2
- **Files modified:** 6（2 新增 / 4 修改）

## Accomplishments

- **「冪等命中不碰 holdings」是被直接斷言的,不是被推論的**:`verify(repository, never()).findHoldingForUpdate(...)`、`.updateHolding(...)`、`.insertTransactionIfAbsent(...)` 與 `verifyNoInteractions(portfolioCache)` —— judgment §5 要的正是「沒有發生的事」。
- **執行順序翻轉並在 javadoc 留下理由**:`TradingService.createTrade` 的繁中 javadoc 明寫「交易寫入必須早於持倉變更」及其原因(帳本改不回來),plan 的人工檢視項通過。
- **D-07 的等值語意精確化**:`sameAmount` 用 `compareTo`(`BigDecimal.equals` 會因 scale 不同誤判)、`sameInstant` 用 `isEqual`(`OffsetDateTime.equals` 會因 offset 不同誤判)。只有 note 不同不算 payload 不一致。
- **假性 409 的根因被堵住(DP-7)**:`executedAt` 截到微秒對齊 `TIMESTAMPTZ`,否則帶奈秒精度的客戶端每次合法重送都會與讀回值差一個被捨去的尾數。
- **敏感值不外洩有專測**:三個例外路徑的訊息都以 `LEAK-CANARY-12345` 驗證 `hasMessageNotContaining`。

## Task Commits

1. **Task 1:TradePayloadMatcher 純函式(D-07 比對)**
   - `265ba7d` (test) — RED
   - `b637bf6` (feat) — GREEN
2. **Task 2:TradingService.createTrade 改為 insert-first 冪等流程**
   - `2450562` (test) — RED
   - `234a78d` (feat) — GREEN（由 orchestrator 接手提交,含 controller 的 header 接線）
3. `b3c8f5d` (merge) — 併回 worktree

## must_haves 對帳（機械驗證）

| 項目 | 要求 | 實測 | 狀態 |
|------|------|------|------|
| `TradePayloadMatcher` exports | sameAmount / sameInstant / matches | 三者皆在 | PASS |
| `TradingService` contains | `insertTransactionIfAbsent` | 2 處 | PASS |
| `TradingServiceTest` contains | `verify(repository, never())` | 8 處 | PASS |
| key_link → `insertTransactionIfAbsent` | insert 先於 holdings | 2 處,且 javadoc 說明順序理由 | PASS |
| key_link → `TradePayloadMatcher` | 冪等命中時比對 | 3 處 | PASS |

## 驗收證據（我實際執行並看到的輸出）

### plan 指定驗收 — `./mvnw -pl stock-module-trading -am test`（worktree 內,提交前）

```
[INFO] Tests run: 13, Failures: 0, Errors: 0 -- in 同 key 不同的 payload 比對
[INFO] Tests run: 67, Failures: 0, Errors: 0, Skipped: 0
[INFO] stock-module-trading ............................... SUCCESS [  7.697 s]
[INFO] BUILD SUCCESS
```

### 併回主分支後重驗 — 同一指令

```
[INFO] Tests run: 13, Failures: 0, Errors: 0 -- in 同 key 不同的 payload 比對
[INFO] Tests run: 67, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

（合併未破壞任何測試。繁中類別名為 `@DisplayName`,Windows console codepage 造成的亂碼已於此還原。）

### plan 指定驗收 — `./mvnw test`（全模組 unit,Docker 啟動後）

```
[INFO] Tests run: 67, Failures: 0, Errors: 0, Skipped: 0   ← stock-module-trading
[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0   ← stock-start
[INFO] stock-web-v2 ....................................... SUCCESS [  0.007 s]
[INFO] stock-common ....................................... SUCCESS [ 10.543 s]
[INFO] stock-db-migration ................................. SUCCESS [  0.088 s]
[INFO] stock-infrastructure ............................... SUCCESS [  9.938 s]
[INFO] stock-module-user .................................. SUCCESS [  8.155 s]
[INFO] stock-module-asset ................................. SUCCESS [ 15.646 s]
[INFO] stock-module-backtest .............................. SUCCESS [  2.855 s]
[INFO] stock-module-market-data ........................... SUCCESS [04:13 min]
[INFO] stock-module-trading ............................... SUCCESS [  6.358 s]
[INFO] stock-start ........................................ SUCCESS [01:03 min]
[INFO] BUILD SUCCESS
=== EXIT: 0 ===
```

### breaking change 未波及下游 — `./mvnw test-compile -DskipTests`

```
=== EXIT: 0 ===
```

`createTrade` 簽章變更後全部 10 個模組仍可編譯(含測試碼)。

## Deviations from Plan

**1. [執行中斷] 原 executor 在 Task 2 的 GREEN commit 前被 session 限額中止**

- **Found during:** Task 2 GREEN
- **Issue:** 三個檔案改完未提交,worktree 未回收,SUMMARY 未產生。
- **Fix:** orchestrator 驗證後提交 `234a78d`,以 `--no-ff` 併回(`b3c8f5d`,比照 04-02 的 `c626a9a`),補寫本 SUMMARY。
- **Verification:** 見上方驗收證據。

**2. [環境] Docker daemon 未執行,一度阻擋全模組驗收（已解決）**

- **Found during:** 執行 `./mvnw test`
- **Issue:** `BUILD FAILURE`,失敗點在 `stock-module-asset` 的 `AssetFacadeImplIT`:`java.lang.IllegalStateException: Could not find a valid Docker environment`。該模組**不在本 plan 的 `files_modified` 內**(`git log -1` 顯示它最後由不相關的 `b445974` 改動),失敗原因是 Docker daemon 未執行而非斷言失敗。
- **Fix:** 啟動 Docker Desktop 後重跑,`docker info` → `29.5.3`,`./mvnw test` → `BUILD SUCCESS` / exit 0,10 個模組全綠。
- **Verification:** 見上方驗收證據。另跑 `./mvnw test-compile -DskipTests`(exit 0)確認 `createTrade` 的簽章變更未讓任何下游模組編譯失敗。

## Issues Encountered

- **Windows console codepage 讓繁中 `@DisplayName` 顯示為亂碼**(既有現象,不影響結果,以 surefire-reports 交叉確認)。
- **`Corrupted channel by directly writing to native stream in forked JVM` warning**:既有現象,build 仍 SUCCESS。

## Known Stubs

無。`TradePayloadMatcher` 與冪等版 `createTrade` 皆為完整實作。

## Next Phase Readiness

**Ready for 04-04（controller 契約與錯誤映射,wave 4）—— 但有前提:**

1. **`Idempotency-Key` header 已在 controller 落地**(`@RequestHeader("Idempotency-Key")`,必填)。04-04 若要處理 header 缺漏的錯誤映射,注意目前缺 header 會由 Spring 直接拋 `MissingRequestHeaderException`,**不是** `BusinessException`。
2. **`createTrade` 簽章已變**(breaking change)。已驗證全模組 `test-compile` 通過,無其他呼叫端需要修改。
3. **Docker 目前是就緒的**(29.5.3,由 orchestrator 啟動)。04-04 / 04-05 / 04-13 的 Testcontainers 驗收可以進行。

---
*Phase: 04-manual-trade-creation-idempotency-post-trade-refetch*
*Completed: 2026-08-15*

## Self-Check: PASSED

- 6 個檔案與 5 個 commit hash 皆已於 repo 驗證存在。
- 「驗收證據」段落的輸出均為本次實際執行所得。
- plan 的兩條自動驗收(`-pl stock-module-trading -am test`、`./mvnw test`)皆 exit 0;人工檢視項(javadoc 說明 insert 順序理由)已確認。
- **未驗證:** Task 1 / Task 2 的 RED 失敗輸出(原 executor 執行,我未親見)。
