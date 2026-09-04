---
phase: 04-manual-trade-creation-idempotency-post-trade-refetch
plan: 04
subsystem: backend
tags: [idempotency, http-contract, webmvc-slice, exception-handling, error-envelope]

# Dependency graph
requires:
  - phase: 04-manual-trade-creation-idempotency-post-trade-refetch
    provides: 04-03 的三參數 TradingService.createTrade 與 service 層 key 驗證（空白／128 字元上限）
provides:
  - "stock-module-trading 的 @WebMvcTest 基礎設施（TradingTestApplication + 兩個 test-scope 依賴）"
  - "GlobalExceptionHandler 的 MissingRequestHeaderException handler：400 + error.fields[headerName]"
  - "缺 Idempotency-Key 的 400 envelope 契約（前端可與 body 欄位錯誤區分）"
affects: [04-05 端到端冪等 IT, 04-07 前端 tradingApi 的錯誤分支, 04-13 雙 mode 收尾]

# Tech tracking
tech-stack:
  added:
    - "stock-module-trading test scope：spring-boot-starter-webmvc-test、spring-boot-starter-security-test（版本由 parent BOM 管理）"
  patterns:
    - "切片測試的能力界線寫進類別 javadoc：@WebMvcTest 證明綁定行為，envelope 的驗收留給 stock-start 的 IT"
    - "錯誤 envelope 的 fields 用靜態英文字串，不用 exception.getMessage()"

key-files:
  created:
    - stock-module-trading/src/test/java/dowob/xyz/stockwebv2/trading/TradingTestApplication.java
    - stock-module-trading/src/test/java/dowob/xyz/stockwebv2/trading/api/TradingControllerWebMvcTest.java
  modified:
    - stock-module-trading/pom.xml
    - stock-module-trading/src/test/java/dowob/xyz/stockwebv2/trading/api/TradingControllerTest.java
    - stock-start/src/main/java/dowob/xyz/stockwebv2/start/error/GlobalExceptionHandler.java
    - stock-start/src/test/java/dowob/xyz/stockwebv2/start/ErrorHandlingIT.java

key-decisions:
  - "DP-3：新 handler 插在 handleBusiness 與 handleValidation 之間，避開 PR #15 在 handleValidation 附近的 hunk"
  - "獨立 handler 而非沿用 catch-all：兩者都回 400 VALIDATION_FAILED，但 catch-all 路徑的 fields 是空的，前端無法區分「缺 header」與「body 欄位錯」"
  - "切片測試獨立成 TradingControllerWebMvcTest.java（偏離：plan 的 acceptance criteria 寫「TradingControllerTest.java 含 @WebMvcTest」）"

patterns-established:
  - "切片測試附對照組：只驗「缺 header → 400」無法排除 body/security/路徑造成的 400，補一條「帶 header → 200」才建立因果"
  - "audit 參數的洩漏驗證用 ArgumentCaptor 逐一檢查，而非 eq(預期值)——後者在新增參數時不會失敗"

requirements-completed: [TRAD-03, TRAD-06]

# Metrics
duration: 約 35min
completed: 2026-08-16
---

# Phase 04 Plan 04: 必填 Idempotency-Key 與可辨識的 400 envelope

**把冪等契約推到 HTTP 邊界：缺 `Idempotency-Key` 的請求回一個前端能程式化辨識的 400，而不是靜默建立交易。**

## 接手時的實際狀態（與 plan 假設的偏差）

Plan 04-04 假設 `TradingController` 尚未帶 header，Task 2 的 RED 是「把測試改成四參數版 → 編譯失敗」。**實際接手時，Task 2 的生產程式碼已經隨 04-03 一併落地**（`b3c8f5d` 併回 worktree 時包含了 `TradingController` 與 `TradingControllerTest` 的四參數遷移）。

因此本次實際執行的是三塊剩餘缺口：

| Task | 生產程式碼 | 測試 | 本次做了什麼 |
|------|-----------|------|-------------|
| Task 1 | — | 全缺 | pom 兩個 test 依賴 + `TradingTestApplication` |
| Task 2 | 04-03 已完成 | 只有既有 4 條遷移完的 case | 補 3 條單元測試 + `@WebMvcTest` 切片 |
| Task 3 | 全缺 | 全缺 | handler + 2 條 `ErrorHandlingIT` |

**D-05 的「遷移成本只有更新 TradingControllerTest」預估成立**：`grep -rn 'post("/api/v1/trades")'` 在本 repo 只命中測試檔，無任何生產端呼叫者需要改。真正的遷移成本落在 `TradingApiIT`（19 個 IT 有 16 個掛），那是 04-05 的範圍。

## RED → GREEN 證據

### Task 1 + Task 2（`./mvnw -pl stock-module-trading -am test`）

RED（先寫 `TradingControllerWebMvcTest`，此時依賴與錨點都不存在）：

```
[ERROR] TradingControllerWebMvcTest.java:[9,58] package org.springframework.boot.webmvc.test.autoconfigure does not exist
[ERROR] TradingControllerWebMvcTest.java:[25,68] package org.springframework.security.test.web.servlet.request does not exist
[ERROR] TradingControllerWebMvcTest.java:[44,2] cannot find symbol  symbol: class WebMvcTest
[ERROR] Failed to execute goal ...:testCompile on project stock-module-trading
```

補上兩個依賴與 `TradingTestApplication` 後，切片啟動成功（`@SpringBootConfiguration` 找得到）。中間出現一次**我自己的測試缺陷**（stub 回 `null` 導致 `trade.id()` NPE，2 errors），改用具體 `TradeDto` 後：

```
GREEN: ./mvnw -q -pl stock-module-trading -am test → exit 0
```

### Task 3（`./mvnw -pl stock-start -am verify -Dit.test=ErrorHandlingIT`）

RED —— 現況已回 400 `VALIDATION_FAILED`（因為 `MissingRequestHeaderException` 實作 `ErrorResponse`，落到 catch-all 的 `handleErrorResponse` 分支），但 `fields` 是空的：

```
Body = {"success":false,"data":null,"error":{"code":"VALIDATION_FAILED","message":"Validation failed","fields":{}},...}
[ERROR] ErrorHandlingIT.missingRequiredHeaderReturnsValidationEnvelopeWithHeaderName:105
        No value at JSON path "$.error.fields['Idempotency-Key']"
Tests run: 7, Failures: 1, Errors: 0
```

**這正是新增 handler 的理由**：不加也有 400 與正確的 code，缺的是「哪一個 header」這個可程式化判讀的資訊。加入 handler 後 exit 0。

註：同批寫的「不回射使用者輸入」那條**先跑即綠**——catch-all 路徑本來就不回射。它的價值是回歸鎖，不是本次修好的缺陷，誠實標示。

### 全套回歸

```
./mvnw test → exit 0
```

## `@WebMvcTest` 切片的能力界線（必須被後續 plan 讀到）

`TradingControllerWebMvcTest` **證明**：
- 缺 `Idempotency-Key` 時 Spring 的參數綁定會失敗，請求在進入 `createTrade` 方法體之前就被擋下（回 400）。
- 帶上 header 時綁定成功、進得了業務邏輯（對照組，排除 body/security/路徑造成的 400）。

**不證明**：
- 回應信封的形狀（`error.code`、`error.fields`）。`GlobalExceptionHandler` 位於 `stock-start`，**不在切片內**；切片內的 400 是 Spring `DefaultHandlerExceptionResolver` 的預設行為。
- `@PreAuthorize("hasAuthority('TRADE_EXECUTE')")` 的授權語意。切片沒有 `@EnableMethodSecurity`，方法層授權不生效。

envelope 的權威驗收在 `ErrorHandlingIT`（本 plan Task 3）與 `TradingApiIT`（04-05）。

## DP-3：handler 的落點

新 handler 放在 `handleBusiness` 之後、`handleValidation` 之前（`GlobalExceptionHandler.java:75` vs `:83`）。理由是 draft PR #15 在 `handleValidation` 附近有 hunk，插在其前方可讓兩邊的改動不重疊。

驗證：`git diff` 對 `handleValidation` 方法本體顯示**零改動**（grep `getFieldErrors`/`BindingResult`/`handleValidation` 於 diff 的 +/- 行皆無命中）。

## 偏離記錄

1. **切片測試放在獨立檔案**。Plan 的 acceptance criteria 寫「`TradingControllerTest.java` ⋯含至少一個 `@WebMvcTest`」，實際建立為 `TradingControllerWebMvcTest.java`。理由：`TradingControllerTest` 是純 Mockito 單元測試，把 Spring 切片塞進同一個 class 需要 `@Nested` + 巢狀 context 的組合，比照 `BackfillControllerTest` 獨立成檔更貼近本 repo 既有形狀。criteria 的**意圖**（module 層要有 `@WebMvcTest` 切片證明綁定行為）已滿足。

2. **acceptance criteria「該檔不含 `required = false`」不可逐字套用**。`TradingController.java` 有 6 處 `required = false`，全部屬於 `listTrades` 的 `@RequestParam`，是 Phase 3 的既有程式碼。實際要守的是 `@RequestHeader("Idempotency-Key")` 不帶 `required = false`——已驗證（`TradingController.java:68`）。

## 驗收指令與結果

| 指令 | 結果 |
|------|------|
| `./mvnw -pl stock-module-trading -am test` | exit 0 |
| `./mvnw -pl stock-start -am verify -Dit.test=ErrorHandlingIT` | exit 0（7 tests） |
| `./mvnw test` | exit 0 |
| `find stock-module-trading/src/test -name "*IT.java"` | 無輸出（IT 未被誤放進 module） |
| `grep -c "auditLogger.log" TradingController.java` | 2（成功 + 失敗兩條路徑皆保留） |
| `grep -c "isBlank(idempotencyKey)" TradingController.java` | 0（業務判斷不在 controller） |
