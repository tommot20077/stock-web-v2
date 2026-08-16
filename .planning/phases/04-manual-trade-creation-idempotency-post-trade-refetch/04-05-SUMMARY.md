---
phase: 04-manual-trade-creation-idempotency-post-trade-refetch
plan: 05
subsystem: backend
tags: [idempotency, integration-test, concurrency, testcontainers, postgresql, security]

# Dependency graph
requires:
  - phase: 04-manual-trade-creation-idempotency-post-trade-refetch
    provides: 04-01 的部分唯一索引、04-02 的 insertTransactionIfAbsent、04-03 的 insert-first 流程、04-04 的必填 header 與 400 envelope
provides:
  - "TRAD-03 的權威端到端驗收：真實 PostgreSQL 上的 10 條冪等 IT"
  - "RESEARCH Q1.8 的 [ASSUMED] 在本專案環境下的實測證據"
affects: [04-06~04-13 前端契約, Phase 5 跨 repo 瀏覽器驗證]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "併發 IT 斷言不變量（只有 1 列、id 全同）而非時序（誰先誰後）"
    - "同一併發骨架的兩個對照組：不同 key → 8 列；同一 key → 1 列"

key-files:
  created: []
  modified:
    - stock-start/src/test/java/dowob/xyz/stockwebv2/start/TradingApiIT.java

key-decisions:
  - "既有 fixture 交易的 key 一律用 UUID.randomUUID()：它們彼此獨立，共用 key 會被冪等機制合併"
  - "concurrentFirstBuysMergeWithoutUniqueViolation 改為每 thread 一把不同的 key —— 這條測的是持倉 upsert 競態，不是冪等"
  - "維持方案 A（ON CONFLICT DO NOTHING + 重讀），未切換到 RESEARCH Q1.7 的方案 E（pg_advisory_xact_lock）"

patterns-established:
  - "canary key 常數（LEAK-CANARY-12345）+ 對完整回應 body 做 doesNotContain，而非只驗 $.error.message"
  - "冪等 payload 一律明確送出 executedAt，避免後端補 now() 讓重試 payload 每次不同"

requirements-completed: [TRAD-01, TRAD-03, TRAD-06]

# Metrics
duration: 約 40min
completed: 2026-08-16
---

# Phase 04 Plan 05: 真實 PostgreSQL 上的冪等端到端驗收

**`TradingApiIT` 從 19 條擴充到 29 條；後端 Phase 4 在此收尾。**

## RED → GREEN 證據

### RED（本 plan 開工前的既有狀態）

04-04 讓 `Idempotency-Key` 成為必填後，Phase 3 寫的 `TradingApiIT` 全數缺 header：

```
[ERROR] Tests run: 19, Failures: 16, Errors: 0, Skipped: 0 -- in TradingApiIT
  buyThenSellUpdatesHoldingsAndPortfolioSummary:63   Status expected:<200> but was:<400>
  sellRejectsOversell:146                            Status expected:<409> but was:<400>
  ...（其餘 14 條皆為 seedThreeTrades → createTrade:524 的 200→400）
```

這與 PR #20 的 CI 紅燈同因、同數量。

### GREEN

```
Tests run: 29, Failures: 0, Errors: 0, Skipped: 0 -- in TradingApiIT   → BUILD SUCCESS
```

### ⚠️ 誠實揭露：10 條新測試「先跑後綠」，沒有經歷觀察到的 RED

新增的 10 條冪等 IT 在**第一次執行就全綠**。原因是它們驗收的生產程式碼在 04-03（service 層 insert-first 流程）與 04-04（header 契約與 envelope）已經完成，本 plan 只是補上端到端的證明層。plan §Task 2 明文允許這種情形，但要求標示為「先跑後綠」而非假裝經歷了 RED —— 此處照辦。

**非空洞性（non-vacuousness）的獨立佐證**：同一個檔案裡有一組天然對照——

| 測試 | 8 條併發請求的 key | 結果 |
|------|-------------------|------|
| `concurrentFirstBuysMergeWithoutUniqueViolation` | 8 把**不同**的 key | 建立 8 筆交易，`totalQuantity` = 8 |
| `concurrentSameKeyCreatesExactlyOneTrade` | **同一把** key | 建立 1 筆交易，`totalQuantity` = 10（單次的量） |

兩條用**同一套** `CountDownLatch` + 8-thread 骨架，唯一差異是 key。前者證明這個併發骨架確實能讓 8 個請求各自寫入，後者才有意義——不是「併發根本沒發生所以只有 1 列」。

## Q1.8 的 `[ASSUMED]` 可升級為「本專案環境下實測成立」

`04-RESEARCH.md` Q1.8 記載的殘餘風險是：PostgreSQL 官方文件只對 `ON CONFLICT DO UPDATE` 給了原子性保證，「`DO NOTHING` 回零列後，衝突列在同一 READ COMMITTED 交易中可見」屬 `[ASSUMED]`。

**實測結果（Testcontainers 真實 PostgreSQL，Docker Server 29.5.3）：**

```
=== 首次驗證 ===  Tests run: 29, Failures: 0  (49.54 s)
=== RUN 1 ===     Tests run: 29, Failures: 0  (88.70 s)
=== RUN 2 ===     Tests run: 29, Failures: 0  (56.06 s)
=== RUN 3 ===     Tests run: 29, Failures: 0  (54.96 s)
```

**連跑四次、零偶發紅燈。** `concurrentSameKeyCreatesExactlyOneTrade` 每次都取得：8 個回應全部 200（零 500）、8 個 `data.id` 全部相同、`totalElements` 為 1、`totalQuantity` 為單次的量。

因此**未切換到方案 E**（`pg_advisory_xact_lock(hashtext(userId || key))`）。方案 A 保留。

**判準仍然有效，留給未來**：若這條測試日後**偶發**紅燈，處置是切換方案 E，**不是**調高 timeout、加重試、或放寬為「至多 2 列」。這條規則已寫進測試的繁中註解，不只存在於本 SUMMARY。

**這四次連跑證明的邊界**：它證明的是「在本專案的 Testcontainers PostgreSQL、8 併發、這個 payload 下沒有觀察到失敗」，不是 PostgreSQL 的普遍性保證。文件層級的 `[ASSUMED]` 沒有變成 `[VERIFIED]`，變的是「本專案環境下有實測證據」。

## 十條 IT 的覆蓋對照

| 測試 | 驗收的不變量 | 威脅 |
|------|------------|------|
| `sameIdempotencyKeyReturnsExistingTradeAndAppliesHoldingOnce` | 同 key 連送兩次 → 同一 `data.id`、帳本 1 列、持倉只套一次 | T-04-01 |
| `sameKeyWithDifferentPayloadIsRejectedAsReuse` | 同 key 不同 quantity → 409 `TRADE_IDEMPOTENCY_KEY_REUSED`，帳本仍 1 列 | D-07 |
| `sameKeyWithOnlyNoteChangedReturnsExistingTrade` | 同 key 只改 note → 200 回既有交易（note 不納入比對） | DP-6 |
| `sameKeyAcrossDifferentUsersCreatesSeparateTrades` | 兩 user 同一把 key → 兩個相異 id、各自 1 列 | T-04-02 |
| `missingIdempotencyKeyHeaderReturnsFieldAwareValidationError` | 缺 header → 400 + `error.fields['Idempotency-Key']`，帳本 0 列 | 04-04 的端到端驗收 |
| `blankIdempotencyKeyIsRejected` | 空白 key → 400 `VALIDATION_FAILED`，帳本 0 列 | Pitfall 14 |
| `oversizedIdempotencyKeyIsRejectedWithValidationError` | 129 字元 key → 400（非 500、非 DB 例外外洩） | T-04-04 |
| `concurrentSameKeyCreatesExactlyOneTrade` | 8 併發同 key → 全 200、1 列、id 全同、持倉單次 | T-04-01 / Q1.8 |
| `rejectedTradeDoesNotBurnTheIdempotencyKey` | oversell 409 後同一把 key 仍可建立合法交易 | T-04-07 |
| `errorResponsesNeverEchoUserControlledInput` | 409 / 空白 400 / 過長 400 的**完整 body** 皆不含 canary | T-04-03 |

另補一條在既有成功路徑上的斷言：`$.data.idempotencyKey` `doesNotExist()`（T-04-09，key 不得從成功回應漏出）。

## 既有測試的遷移

- 12 處 `post("/api/v1/trades")` 中 11 處帶了 `Idempotency-Key`；差額 1 正是 `missingIdempotencyKeyHeaderReturnsFieldAwareValidationError` 刻意不帶。
- `createTrade` helper 改為每次呼叫產生一把 `UUID.randomUUID()` 的 key —— fixture 交易彼此獨立，共用 key 會被冪等合併，fixture 就湊不齊。
- **`concurrentFirstBuysMergeWithoutUniqueViolation` 改為每 thread 一把不同的 key**。這是本次遷移最容易寫錯的一處：它斷言 `totalQuantity` 為 8，若沿用同一把 key，冪等機制會把 8 筆合併成 1 筆而讓斷言變成 1，測到的就不再是原本的「持倉 upsert 競態」。
- `tradingEndpointsRequireAuthentication` 也補上 header，讓它專測「未帶憑證即 401」，而不是被缺 header 的 400 搶先攔下。

## 誠實標示：未覆蓋的項目

- **`TRADE_EXECUTE` 權限缺失 → 403**：`Role.USER` 已含該權限，repo 內沒有缺少它的角色可用。**由 `@PreAuthorize("hasAuthority('TRADE_EXECUTE')")` 註解保證，無獨立測試**。不假裝覆蓋。
- **真實瀏覽器的完整流程**（login → `/me` → portfolio reads → create trade → refetch → logout）：MockMvc 是 servlet 層而非真實 HTTP。**Phase 5 / VER-03**。
- **真實 cookie / CSRF 在瀏覽器中的行為**（SameSite / Secure / HttpOnly 實際生效）：**Phase 5**。

## 驗收指令與結果

| 指令 | 結果 |
|------|------|
| `./mvnw -pl stock-start -am verify -Dit.test=TradingApiIT` ×4 | 全部 exit 0，29/29，零偶發 |
| `./mvnw -pl stock-start -am verify`（全部 IT） | exit 0 — 106 IT + 324 unit，BUILD SUCCESS |
| `./mvnw test` | exit 0 |

全 IT 回歸涵蓋 `TransactionsAppendOnlyIT`(3)、`TransactionsIdempotencyIT`(8)、`FoundationMigrationIT`(1)、`ErrorHandlingIT`(7)、`BrowserAuthFlowIT`(11) 皆綠。

## 後端 Phase 4 在此收尾 —— 前端契約已凍結

以下對前端（`../../vue/stock-v2`）的契約自本 plan 起可視為凍結：

- **請求 header**：`Idempotency-Key`，**必填**，1–128 字元且不得全為空白。
- **錯誤碼**：
  - 缺 / 空白 / 過長 key → HTTP 400，`error.code = VALIDATION_FAILED`；缺 header 時 `error.fields['Idempotency-Key']` 存在。
  - 同 key 不同 payload → HTTP 409，`error.code = TRADE_IDEMPOTENCY_KEY_REUSED`。
- **payload 比對的 7 個欄位**：assetId(由 symbol 解析)、type、quantity、price、fee、executedAt 納入比對；**note 不納入**。
- **成功回應**：`data` 不含 `idempotencyKey` 欄位；同 key 重送回傳**既有**交易（`data.id` 相同），HTTP 200。
