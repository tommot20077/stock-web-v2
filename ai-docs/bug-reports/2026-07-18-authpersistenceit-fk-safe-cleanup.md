---
id: BUG-2026-001
date: 2026-07-18
commit: bdd24fd
modules: [stock-start]
category: [Careless Oversight]
severity: MEDIUM
impact: code-review-caught
---

# Bug Review: AuthPersistenceIT 清理改 FK-safe,修復共用容器下 transactions FK 阻擋

## Summary

| Item | Detail |
|------|--------|
| ID | BUG-2026-001 |
| Date | 2026-07-18 |
| Commit | `bdd24fd` |
| Modules | stock-start(整合測試) |
| Category | Careless Oversight |
| Severity | MEDIUM |
| Impact | code-review-caught(CI 攔截) |

## Symptoms

PR #5 / #6 / #7(security-remediation batch-1/2/3 堆疊 PR)的 **Integration Tests** 全數失敗,
`AuthPersistenceIT` 3 個測試皆拋 `DataIntegrityViolationException`:

```
SQL [DELETE FROM users]; ERROR: update or delete on table "users" violates
foreign key constraint "transactions_user_id_fkey" on table "transactions"
  Detail: Key (id)=(18) is still referenced from table "transactions".
	at ...AuthPersistenceIT.cleanUserData(AuthPersistenceIT.java:50)
```

Unit Tests 綠、E2E 因 Integration 失敗被 skip。base 分支 `feature/market-data-module`
的 Integration Tests(PR #4 docs)為綠,顯示回歸由 batch 引入。

## Root Cause Analysis

共用容器 IT 的測試隔離缺陷:

- `ContainerIT` 讓所有 IT 共用同一個 Postgres 容器。
- batch-1 新增的 `TransactionsAppendOnlyIT`(commit `7af8b35`)在 `@BeforeEach` 每次
  INSERT 一名 user 與一筆 `transactions`,且**無 `@AfterEach` 清理**。
- `transactions` 為 append-only 帳本(Flyway V8 trigger 禁止 DELETE),其 `user_id`
  以 FK `transactions_user_id_fkey` 參照 `users`,這些殘留列在容器生命週期內**永遠刪不掉**。
- `AuthPersistenceIT.cleanUserData()` 沿用早於 append-only trigger 的 naive 全表
  `DELETE FROM users`。當它在共用容器中執行、而其他測試已種下 transactions 列時,即撞 FK 而失敗。

專案**早已存在**正確做法:e2e 的 `DatabaseCleaner.cleanUserData()` 以
`SET LOCAL session_replication_role='replica'` 停用 user triggers 與 FK 檢查後 FK-safe
依序刪除。新增 `TransactionsAppendOnlyIT` 時未同步把 `AuthPersistenceIT` 的清理對齊此
已知 pattern —— 屬粗心疏漏(known pattern 未套用),而非知識缺口。

### Before Fix

```java
@AfterEach
void cleanUserData() {
    jdbcTemplate.execute("DELETE FROM users");
    try (RedisConnection connection = redisConnectionFactory.getConnection()) {
        connection.serverCommands().flushDb();
    }
}
```

### After Fix

```java
@AfterEach
void cleanUserData() {
    // transactions 為 append-only 帳本(V8 trigger 禁止 DELETE)且以 FK 參照 users;
    // 共用容器下其他 IT 會種下 transactions 列,故不能直接 DELETE FROM users。
    // 改在單一交易內以 session_replication_role='replica' 暫時停用 user triggers 與 FK
    // 檢查後 FK-safe 依序刪除(對齊 e2e 的 DatabaseCleaner)。
    jdbcTemplate.execute("""
        BEGIN;
        SET LOCAL session_replication_role = 'replica';
        DELETE FROM transactions;
        DELETE FROM holdings;
        DELETE FROM backtest_runs;
        DELETE FROM users;
        COMMIT;
        """);
    try (RedisConnection connection = redisConnectionFactory.getConnection()) {
        connection.serverCommands().flushDb();
    }
}
```

## Affected Files

- `stock-start/src/test/java/dowob/xyz/stockwebv2/start/AuthPersistenceIT.java`(+14 / −1)

## Timeline

| Event | Time / Commit |
|-------|--------------|
| 潛在引入 | `7af8b35` — feat(trading): 新增 transactions append-only DB trigger(同時新增 `TransactionsAppendOnlyIT`,開始在共用容器種下不可刪的 transactions 列) |
| 發現 | 2026-07-18,review PR #5/#6/#7 時由 CI Integration Tests 紅燈發現 |
| 修復 | `bdd24fd`(batch-1),前向 merge 至 batch-2 `fee24c6` / batch-3 `98069f2` |

## Preventive Measures

| Measure | Status |
|---------|--------|
| 共用容器 IT 一律禁用 naive 全表 DELETE,清理統一走 FK-safe(session_replication_role='replica')pattern | TODO |
| 將 e2e `DatabaseCleaner` 的清理邏輯抽為共用測試工具,供 e2e 與非-e2e IT 共用,避免各自複製 | TODO |
| 新增會種下 append-only / FK 參照資料的 IT 時,檢查既有清理是否涵蓋 | TODO |

## Lesson Learned

共用容器下新增「會留下不可刪資料」的測試時,必須同步把所有既有清理對齊已知的 FK-safe pattern —— 正確做法已存在(DatabaseCleaner),漏套即成回歸。
