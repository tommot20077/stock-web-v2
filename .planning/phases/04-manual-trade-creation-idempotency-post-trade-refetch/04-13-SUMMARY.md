---
phase: 04-manual-trade-creation-idempotency-post-trade-refetch
plan: 13
subsystem: verification
tags: [cross-repo-verification, judgment-8, over-claim-audit, requirement-traceability, testcontainers, concurrency-stability]

# Dependency graph
requires:
  - phase: 04-manual-trade-creation-idempotency-post-trade-refetch
    plan: 05
    provides: 後端 10 條冪等 IT 與凍結的前端契約
  - phase: 04-manual-trade-creation-idempotency-post-trade-refetch
    plan: 12
    provides: 前端三頁 post-trade refetch 與 fresh 高亮（最後一個實作 plan）
provides:
  - "judgment §8 跨 repo 四項驗證的同一份程式碼狀態實測證據"
  - "TRAD-01 ~ TRAD-06 的六列證據表（每列指名測試檔與測試名）"
  - "七項 out-of-scope 的 over-claim 稽核結論：零違規"
  - "D-01 ~ D-16 的 16 列覆蓋對照"
  - "併發冪等 IT 連跑 3 次的穩定性複驗（累計 7 次零偶發）"
affects: [Phase 5 VER-01/VER-02/VER-03, PR #20 收尾]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "收尾閘門的四項驗證必須在同一份乾淨工作樹下跑完，兩個 repo 各自綠不等於整體綠（judgment §8）"
    - "over-claim 稽核用 grep 對既有 SUMMARY 逐項掃描，而不是靠閱讀印象"

key-files:
  created:
    - .planning/phases/04-manual-trade-creation-idempotency-post-trade-refetch/04-13-SUMMARY.md
  modified: []

key-decisions:
  - "維持方案 A（ON CONFLICT DO NOTHING + 重讀），未切換方案 E：本次 3 次連跑加上 04-05 的 4 次，累計 7 次零偶發紅燈"
  - "04-10 的一句「鍵盤使用者現在可以完整選標的」判定為措辭偏寬而非 over-claim，並已導向 Task 2 第 6 步的瀏覽器確認"
  - "Validation Sign-Off 第 5 項（feedback latency < 60s）維持不勾，複述其為已接受取捨而非可修正缺口"

patterns-established:
  - "跨 repo phase 的收尾必須實查 git branch -r --contains 與 gh pr list，不可沿用 SUMMARY 記載的 push 狀態——本次就抓到三份 SUMMARY 的「未 push」記載與現實不符"

requirements-completed: [TRAD-01, TRAD-02, TRAD-03, TRAD-04, TRAD-05, TRAD-06]

# Metrics
duration: 約 95min（含 backend IT 全套 + 併發 IT 三次連跑的實際等待時間）
completed: 2026-08-16
---

# Phase 04 Plan 13: 跨 repo 收尾驗證與 over-claim 稽核（Task 1）

**四項驗證在同一份乾淨程式碼狀態下全綠、六條 TRAD 需求各有可指名的自動化證據、七項 out-of-scope 零 over-claim、D-01 ~ D-16 全覆蓋、Deferred 未被誤做 —— Phase 4 的自動化部分到此可被信任。**

> **本 SUMMARY 只涵蓋 Task 1。** Task 2（Yuan 的雙 mode 人工確認）是 `checkpoint:human-verify gate="blocking"`，
> **尚未執行，awaiting: Yuan**。本文件的任何一段都不構成該 checkpoint 的通過。

## Performance

- **Duration:** 約 95 min
- **Tasks:** 1/2（Task 2 為 blocking human checkpoint，未執行）
- **Files modified:** 1（僅本 SUMMARY；本 plan 依 objective **不寫任何程式碼**，實際確認零程式碼異動）

---

## A. 跨 repo 四項驗證（judgment §8 / `04-VALIDATION.md` §Sampling Rate 的 Phase gate）

### 前置：兩個 repo 的工作樹在跑測試前皆乾淨

| Repo | 指令 | 輸出 |
|------|------|------|
| 後端 `D:\end\workspace\java\stock-web-v2` | `git status --short` | `?? .planning/phases/04.1-backend-data-gap-backfill/` |
| 前端 `D:\end\workspace\vue\stock-v2` | `git status --short` | （空） |

後端唯一的輸出是 **Phase 04.1 的預留 placeholder 目錄**（2026-07-26 建立，與 Phase 4 無關，orchestrator 已交叉確認）。
除此之外零未追蹤、零修改。四項驗證全部在此狀態下執行，**跑完後再查一次，兩個 repo 的 `git status --short` 輸出完全相同** —— 測試過程未污染工作樹。

驗證時的程式碼狀態：
- 後端 `feature/phase-04-trade-idempotency` @ `90b5d7f`
- 前端 `feature/phase-04-manual-trade-creation` @ `51711b0`

### 四項結果

| # | 指令 | Exit | 測試數 | 耗時 |
|---|------|------|--------|------|
| 1 | `./mvnw test`（後端全 unit） | **0** | **492 tests, 0 failures, 0 errors, 0 skipped**（8 個有測試的模組） | 4:59 min |
| 2 | `./mvnw -pl stock-start -am verify`（後端全 IT） | **0** | **106 IT, 0 failures, 0 errors, 0 skipped**（20 個 IT 類別） | — |
| 3 | `cd ../../vue/stock-v2/vue-app && npm test && npm run build` | **0** / **0** | **35 files / 369 tests passed**；`vue-tsc --noEmit` + `vite build` 綠 | build 1.55s |
| 4 | `cd ../../vue/stock-v2/vue-app && VITE_DATA_MODE=api npm test` | **0** | **35 files / 369 tests passed** | — |

**四者皆 exit 0。**

#### 項目 1 的 Reactor 明細（`./mvnw test`）

```
[INFO] stock-web-v2 ....................................... SUCCESS [  0.006 s]
[INFO] stock-common ....................................... SUCCESS [  4.825 s]
[INFO] stock-db-migration ................................. SUCCESS [  0.047 s]
[INFO] stock-infrastructure ............................... SUCCESS [  5.142 s]
[INFO] stock-module-user .................................. SUCCESS [  5.272 s]
[INFO] stock-module-asset ................................. SUCCESS [ 12.844 s]
[INFO] stock-module-backtest .............................. SUCCESS [  3.068 s]
[INFO] stock-module-market-data ........................... SUCCESS [03:27 min]
[INFO] stock-module-trading ............................... SUCCESS [  9.195 s]
[INFO] stock-start ........................................ SUCCESS [ 51.240 s]
[INFO] BUILD SUCCESS
[INFO] Total time:  04:59 min
EXIT_UNIT=0
```

`stock-module-trading` 73 tests（含 `TradePayloadMatcherTest` 13、`Idempotency-Key header 綁定` 2、`HoldingCalculatorTest` 5）。

#### 項目 2 的 IT 明細（權威來源為 `stock-start/target/failsafe-reports/*.txt`，非 console）

計畫 `<action>` 點名必須涵蓋的五個 IT **全數在內且全綠**（粗體）：

| IT 類別 | Tests | Failures | Errors |
|---------|-------|----------|--------|
| **`TradingApiIT`** | **29** | 0 | 0 |
| **`TransactionsIdempotencyIT`** | **8** | 0 | 0 |
| **`TransactionsAppendOnlyIT`** | **3** | 0 | 0 |
| **`ErrorHandlingIT`** | **7** | 0 | 0 |
| **`BrowserAuthFlowIT`** | **11** | 0 | 0 |
| `FoundationMigrationIT` | 1 | 0 | 0 |
| `MethodSecurityDenialIT` | 1 | 0 | 0 |
| `AssetApiIT` | 3 | 0 | 0 |
| `AuditLoggingIT` | 3 | 0 | 0 |
| `AuthFlowIT` | 7 | 0 | 0 |
| `AuthPersistenceIT` | 3 | 0 | 0 |
| `AuthRateLimitAndLockoutIT` | 4 | 0 | 0 |
| `BacktestApiIT` | 8 | 0 | 0 |
| `BacktestPersistenceIT` | 4 | 0 | 0 |
| `CorsIT` | 3 | 0 | 0 |
| `FoundationSmokeIT` | 1 | 0 | 0 |
| `LoginAttemptLockoutIT` | 4 | 0 | 0 |
| `LogoutInvalidatesAccessTokenIT` | 1 | 0 | 0 |
| `RateLimitServiceIT` | 3 | 0 | 0 |
| `RefreshTokenRotationIT` | 2 | 0 | 0 |
| **合計** | **106** | **0** | **0** |

**Failures 與 Errors 分開判讀**（依交辦提醒）：兩者皆為 0 —— 沒有斷言失敗，也**沒有** Testcontainers 啟動類的 Error。

`EXIT_IT=0`。

#### 項目 3 / 4 的前端明細

```
===== FE1: npm test =====
 Test Files  35 passed (35)
      Tests  369 passed (369)
EXIT_FE_TEST=0

===== FE2: npm run build =====
 ✓ built in 1.55s
EXIT_FE_BUILD=0

===== FE3: VITE_DATA_MODE=api npm test =====
 Test Files  35 passed (35)
      Tests  369 passed (369)
EXIT_FE_API=0
```

**兩個 mode 的檔案數與測試數完全一致（35 / 369）**，與 04-12 SUMMARY 記載的基準線相符。
Phase 4 相關測試檔的 `it.skip` / `it.todo` 全數為 **0**（逐檔實測），沒有靠跳過測試取得綠燈。

---

## B. 併發 IT 的穩定性複驗（T-04-01 / RESEARCH Q1.8）

指令：`./mvnw -pl stock-start -am verify -Dit.test=TradingApiIT`，連跑 **3 次**。

```
===== TradingApiIT RUN 1 =====
RUN1_EXIT=0
[INFO] Tests run: 29, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 38.98 s -- in dowob.xyz.stockwebv2.start.TradingApiIT
===== TradingApiIT RUN 2 =====
RUN2_EXIT=0
[INFO] Tests run: 29, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 47.24 s -- in dowob.xyz.stockwebv2.start.TradingApiIT
===== TradingApiIT RUN 3 =====
RUN3_EXIT=0
[INFO] Tests run: 29, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 42.49 s -- in dowob.xyz.stockwebv2.start.TradingApiIT
ALL_RUNS_DONE
```

**三次皆 exit 0、29/29、零偶發紅燈。** 加上本次項目 2 的全套 IT 一次，以及 04-05 記載的四次，
`concurrentSameKeyCreatesExactlyOneTrade`（8 併發同 key）**累計 8 次連續綠燈**。

**處置結論：維持方案 A（`ON CONFLICT DO NOTHING` + 同 tx 重讀），未切換到方案 E（`pg_advisory_xact_lock`）。**
本次**沒有**調高 timeout、**沒有**加重試、**沒有**放寬斷言 —— 因為根本沒有紅燈需要處置。

**這幾次連跑證明的邊界（沿用 04-05 的誠實界定，不擴張）**：證明的是「在本專案的 Testcontainers PostgreSQL、8 併發、這組 payload 下沒有觀察到失敗」，
**不是** PostgreSQL 的普遍性保證。`04-RESEARCH.md` Q1.8 的文件層級 `[ASSUMED]` **沒有**變成 `[VERIFIED]`。
判準留給未來：若日後**偶發**紅燈，處置是切換方案 E，不是調參數遮掉（judgment §10）。

**非空洞性仍成立**：同檔的 `concurrentFirstBuysMergeWithoutUniqueViolation`（8 把**不同** key → 建 8 筆）
與 `concurrentSameKeyCreatesExactlyOneTrade`（**同一把** key → 建 1 筆）共用同一套 `CountDownLatch` + 8-thread 骨架，
兩者在本次三輪中都綠 —— 前者證明併發骨架真的讓 8 個請求各自寫入，後者才不是「併發根本沒發生所以只有 1 列」。

---

## C. TRAD-01 ~ TRAD-06 的證據對照表

依 `04-VALIDATION.md` §Coverage anchor 逐條填寫。**每一列都指名到具體測試檔與測試名**，
不使用「由 X plan 涵蓋」這類無法驗證的說法。「該次執行的實際結果」欄位一律取自上方 §A 的同一次執行。

| 需求 | 證明它的測試檔與測試名 | 執行指令 | 該次執行的實際結果 |
|------|----------------------|----------|-------------------|
| **TRAD-01**<br>API mode 建立 manual executed buy/sell trade | **後端** `TradingApiIT.buyThenSellUpdatesHoldingsAndPortfolioSummary`、`.sellRejectsOversell`、`.fullyClosedPositionStillCountsRealizedPnlInSummary`<br>**前端** `marketApi.test.ts` 全 12 條（`searchAssets` 走分頁信封、`listKlines` 走裸陣列、`closeSeries` 唯一轉換點）；`OrderTicket.test.ts` `Test 1`~`Test 12`（typeahead 七態）、`Test 13`（報價卡六格逐字等於 `AssetDto`，用刻意矛盾 fixture）、`Test 21`（價格預填 `latestPrice` 且可編輯） | `./mvnw -pl stock-start -am verify`<br>`FE$ VITE_DATA_MODE=api npm test` | IT 29/29 綠；FE 369/369 綠 |
| **TRAD-02**<br>payload 映射 `CreateTradeRequest`，不送 pending/cancel/TIF | **前端** `tradingApi.test.ts` `sends exactly the seven backend contract fields, nothing more`、`never leaks the mock-only order fields into the payload`、`projects the payload field-by-field so a wider caller object cannot smuggle extras`；`OrderTicket.test.ts` `Test 34（TRAD-02）payload 恰為七個合約欄位,executedAt 帶 offset`、`API mode 不渲染訂單類型 / TIF / 交易後現金(D-04)`、`mock mode 仍保留訂單類型 / TIF / 交易後現金(D-04)` | `FE$ npm test`<br>`FE$ VITE_DATA_MODE=api npm test` | 兩個 mode 各 369/369 綠 |
| **TRAD-03**<br>後端 server-side 冪等（**權威層 = backend IT**） | **DB 層** `TransactionsIdempotencyIT.idempotencyIndexIsPartialAndUnique`、`.sameUserSameKeyIsRejectedByDatabase`、`.multipleNullKeysCoexistForSameUser`、`.sameKeyAcrossDifferentUsersIsAllowed`<br>**repository 層** 同檔 `.insertTransactionIfAbsentReturnsRowForNewKey`、`.insertTransactionIfAbsentIsNoOpOnDuplicateKey`、`.findByIdempotencyKeyIsScopedToOwner`<br>**端到端** `TradingApiIT.sameIdempotencyKeyReturnsExistingTradeAndAppliesHoldingOnce`、`.sameKeyWithDifferentPayloadIsRejectedAsReuse`、`.sameKeyWithOnlyNoteChangedReturnsExistingTrade`、`.sameKeyAcrossDifferentUsersCreatesSeparateTrades`、`.concurrentSameKeyCreatesExactlyOneTrade`、`.rejectedTradeDoesNotBurnTheIdempotencyKey`<br>**純函式** `TradePayloadMatcherTest`（13 條）<br>**service 不變量** `TradingServiceTest` 的 8 處 `verify(repository, never())` | `./mvnw -pl stock-start -am verify`<br>`./mvnw -pl stock-start -am verify -Dit.test=TradingApiIT` ×3 | `TransactionsIdempotencyIT` 8/8、`TradingApiIT` 29/29；併發條目連跑 3 次皆綠 |
| **TRAD-04**<br>前端 duplicate-submit guard（不取代 server-side 冪等） | **前端** `OrderTicket.test.ts` `Test 22（TRAD-04）送出中送出鈕明確 disabled,標籤換成「記錄中…」`、`Test 23（TRAD-04）連按送出兩次只呼叫一次 createTrade`、`Test 24（§5 凍結清單）`、`Test 25`~`Test 29`（D-14 key 生命週期四條規則）、`Test 42（U-04 key 處置表）`；`task4.test.ts` `records only one trade when the submit button is clicked twice in a row`<br>**「不取代後端」的實質驗收** `OrderTicket.test.ts` `Test 48（judgment §5 的實質驗收）預檢通過仍可能被後端 409 拒絕` | `FE$ VITE_DATA_MODE=api npm test` | 369/369 綠（`OrderTicket.test.ts` 63/63） |
| **TRAD-05**<br>成功後重讀 summary / holdings / trades | **前端** `portfolioRevision.test.ts` 全 10 條（`produces all three signals in one call`、`resetPortfolioRevisionForTests restores 0 / null / null` 等）；`Overview.test.ts` / `Positions.test.ts` / `Trades.test.ts` 的 `Test 2`~`Test 8`（D-10 各自重讀、U-05 重讀不清空、U-06/D-12 失敗留舊值）、`Positions.test.ts` `Test 5（D-10 的核心論證）只重讀本頁自己的資料源`、`Trades.test.ts` `Test 11（D-11）重讀保留篩選與排序,但頁碼歸零`、`Positions.test.ts` `Test 10`（跨元件：refetch 失敗但 ticket 成功畫面不受影響） | `FE$ VITE_DATA_MODE=api npm test` | 369/369 綠（Overview 19 / Positions 33 / Trades 38） |
| **TRAD-06**<br>錯誤可理解呈現且保留 code / request id | **後端** `ErrorHandlingIT.missingRequiredHeaderReturnsValidationEnvelopeWithHeaderName`、`.missingRequiredHeaderResponseDoesNotEchoUserInput`、`.businessExceptionReturnsApiResponseAndTraceId`；`TradingApiIT.missingIdempotencyKeyHeaderReturnsFieldAwareValidationError`、`.blankIdempotencyKeyIsRejected`、`.oversizedIdempotencyKeyIsRejectedWithValidationError`、`.errorResponsesNeverEchoUserControlledInput`<br>**前端** `i18n.test.ts` 全 8 條（雙語存在性、`keeps broker and order lifecycle vocabulary out of every phase 4 string`、`never calls the reused idempotency key a duplicate request`、`promises no duplicate trade when the network outcome is unknown`）；`OrderTicket.test.ts` `Test 35`~`Test 43`（欄位級綁定、`Test 36` 英文 Bean Validation 值絕不進 DOM、`Test 39` 11 種 code 依 `error.code` 分派而非順序、`Test 40` 網路錯誤、`Test 43` 401 走全域 banner）；`Trades.test.ts` / `Positions.test.ts` `Test 8`（stale 診斷列帶 code + traceId 且不外洩 raw message） | `./mvnw -pl stock-start -am verify`<br>`FE$ VITE_DATA_MODE=api npm test` | `ErrorHandlingIT` 7/7、`TradingApiIT` 29/29；FE 369/369 綠 |

**六條需求全數有可指名的自動化證據，沒有任何一條靠「畫面看起來正常」宣稱覆蓋。**

---

## D. over-claim 稽核（本 task 最重要的一步）

方法：對**全部 12 份既有 `04-NN-SUMMARY.md`** 加上本 SUMMARY 草稿，
針對 `04-VALIDATION.md` §Explicitly Out of Scope 的七個項目逐一 grep（而非靠閱讀印象），
再逐條判讀每一個命中是「宣稱覆蓋」還是「明確排除的免責聲明」。

| # | Out-of-scope 項目 | 歸屬 | grep 命中 | 判讀 | 已確認未宣稱 |
|---|------------------|------|-----------|------|:---:|
| 1 | 真實瀏覽器完整流程（login → `/me` → portfolio reads → create trade → refetch → logout） | **VER-03 / Phase 5** | 04-05:151 | **免責聲明**（「MockMvc 是 servlet 層而非真實 HTTP。**Phase 5 / VER-03**」）；另有 04-09:151、04-10:244、04-11:318、04-12:347 五處各自主動寫明「**沒有開過瀏覽器**」 | ✅ |
| 2 | 真實 cookie / CSRF 瀏覽器行為（SameSite / Secure / HttpOnly 實際生效） | Phase 5 | 04-05:152、04-11:319 | **兩處皆為免責聲明**（04-11:319「jsdom 不實作 HttpOnly,測試裡的 `document.cookie` 是人工種下的。屬 **Phase 5 / VER-03**」） | ✅ |
| 3 | 前端 → 後端真實 network call 的證據（judgment §3） | Phase 5 | 04-11:320 | **免責聲明**（「全部 fixture 皆為 stub 的 `fetch`;judgment §3 要求的『API mode 必須看到真實 network call』**尚未滿足**」） | ✅ |
| 4 | 跨 repo 契約在真實 payload 上的一致性（`KlineDto` string vs number） | Phase 5 | 04-10:245 | **免責聲明**（「fixture 已註明後端 `file:line` 以降低漂移,但那是降低風險,**不是覆蓋**」）；04-06 的四處命中皆為型別宣告的事實敘述，非覆蓋宣稱 | ✅ |
| 5 | `executedAt` 未來時間驗證 | **draft PR #15**（DP-1 裁定為 (c)） | 04-01:90、04-02:131/133/340 | **全部為範圍排除記錄**。**已實查證實**：`TradingService.EXECUTED_AT_FUTURE_TOLERANCE` 在 Phase 4 merge-base 就已存在（3 處命中，Phase 4 後仍為 3 處，零改動）；`TradingApiIT.futureExecutedAtIsRejectedButBackfillIsAllowed` 同樣在 merge-base 就存在。**Phase 4 一個字都沒加，也沒有任何 SUMMARY 把它算成自己的成果** | ✅ |
| 6 | `TRADE_EXECUTE` 權限缺失 → 403 | 誠實標示「無獨立測試」 | 04-04:117、04-05:150 | **兩處皆正確**。04-05:150 逐字使用要求的措辭：「`Role.USER` 已含該權限，repo 內沒有缺少它的角色可用。**由 `@PreAuthorize("hasAuthority('TRADE_EXECUTE')")` 註解保證，無獨立測試**。不假裝覆蓋。」；04-04:117 主動聲明 `@WebMvcTest` 切片**不證明**授權語意（切片無 `@EnableMethodSecurity`） | ✅ |
| 7 | 走勢圖的視覺正確性 | 人工檢視 / Phase 5 | 04-10:244 | **免責聲明**（「元件測試斷言的是 `data` prop 而非 SVG path…**未經人眼確認**,需真實後端,屬 **Phase 5**」） | ✅ |

### 額外掃描：最危險的句型

`grep -nE "使用者(可以|已經可以|現在可以)"` 對 12 份 SUMMARY 只有 **1 處**命中：

- `04-10-SUMMARY.md:65` —— 「**鍵盤使用者現在可以完整選標的**」

**判定：措辭偏寬，但不構成 over-claim，無需更正 04-10 的結論。** 理由：
1. 它**不屬於**七個 out-of-scope 項目中的任何一個。
2. 它有指名證據：`OrderTicket.test.ts` `Test 11(a11y):combobox 屬性齊全,且鍵盤可移動 / 選取 / 關閉`（jsdom 實際 dispatch keydown 並斷言選取結果），且同 plan 已說明 04-09 骨架只綁 `@mousedown`、鍵盤路徑當時不存在。
3. **但**「jsdom 的 keydown 處理正確」與「真實瀏覽器中的鍵盤使用者體驗正確」不是同一件事。

**處置：不改寫既有 SUMMARY，改為明確導向人工確認** —— 本 plan Task 2 的乙段第 6 步（「用**鍵盤**（↓ / Enter）選一個標的。確認可以選得到」）**正是這一句的瀏覽器驗收**。
在 Yuan 完成該步驟之前，這句話的正確讀法是「元件層的鍵盤互動契約已被測試鎖住」。

### 稽核結論

**七項 out-of-scope 全數確認未被宣稱覆蓋，零 over-claim 需要更正。**

值得記錄的是：這不是僥倖。12 份 SUMMARY 中有 **5 份**主動寫了「誠實列出本 plan **未**涵蓋的事」或「誠實揭露」專節，
04-05 甚至把三條 out-of-scope 逐字抄進自己的 SUMMARY 當作反向清單。
`04-VALIDATION.md` 那句「plan 若在任何 task 的 verification 裡寫『使用者可以完整走完下單流程』，那是 over-claim」在執行期確實發揮了作用。

---

## E. D-01 ~ D-16 決策覆蓋對照（16 列）

| 決策 | 內容摘要 | 落實的 plan | 驗收證據（可指名） |
|------|---------|-------------|-------------------|
| **D-01** | symbol 選單與報價卡改接 `GET /assets`；走勢圖接 `/market/{symbol}/klines` | 04-06、04-10 | `marketApi.test.ts` 12 條（`searchAssets` 走 `apiPaginatedRequest`、`listKlines` 走 `apiRequest`）；`OrderTicket.test.ts` `Test 1`~`Test 12`、`Test 16`（`data` prop 是 `number[]` 不是字串）、`Test 10（D-01 硬規則）不可交易的標的不得讓送出鈕可用` |
| **D-02** | fee 改手動輸入預設 0，刪除 0.1% 估算公式 | 04-09 | `OrderTicket.test.ts` `兩個 mode 都有手續費欄位,預設 0 且常駐說明(D-02 / U-14)`；`estFee` 隨估算公式一併失去消費端（04-09 記錄） |
| **D-03** | 新增「成交時間」欄位，預設現在，帶時區 offset | 04-09、04-07 | `OrderTicket.test.ts` `兩個 mode 都有成交時間欄位,預設現在且不可晚於現在(D-03)`、`Test 34` 斷言 `executedAt` 帶 offset；`localTime.ts` 的 `toLocalIso` / `toLocalInputValue` |
| **D-04** | API mode 隱藏訂單類型 / TIF / 交易後現金；mock mode 四樣全保留 | 04-09 | `OrderTicket.test.ts` `API mode 不渲染訂單類型 / TIF / 交易後現金(D-04:隱藏,不留空版位)` **與** `mock mode 仍保留訂單類型 / TIF / 交易後現金(D-04:mock 四樣全部保留)` 兩條互為對照；實作以 `v-if="live"` 分支（本次實查 `OrderTicket.vue:451-457`、`:479-482`） |
| **D-05** | `Idempotency-Key` header **必填**，缺少回 400 | 04-03、04-04 | `TradingController.java:68` 的 `@RequestHeader("Idempotency-Key")` 不帶 `required = false`；`TradingApiIT.missingIdempotencyKeyHeaderReturnsFieldAwareValidationError`；`ErrorHandlingIT.missingRequiredHeaderReturnsValidationEnvelopeWithHeaderName` |
| **D-06** | header 名稱沿用 `Idempotency-Key`，但語意為「回既有交易」而非 409 拒絕 | 04-03、04-05 | `TradingApiIT.sameIdempotencyKeyReturnsExistingTradeAndAppliesHoldingOnce`（同 key 回**既有** `data.id`、HTTP 200）—— 與 `BackfillIdempotencyService` 的 409 拒絕語意明確分離 |
| **D-07** | 同 key 不同 payload → 409 `TRADE_IDEMPOTENCY_KEY_REUSED` | 04-01、04-03、04-05 | `ErrorCodeTest` 三條新測試（409 且與 `DUPLICATE_RESOURCE` / `TRADE_CONFLICT` 語意分離）；`TradePayloadMatcherTest` 13 條；`TradingApiIT.sameKeyWithDifferentPayloadIsRejectedAsReuse` 與 `.sameKeyWithOnlyNoteChangedReturnsExistingTrade`（note 不納入比對） |
| **D-08** | key 存 `transactions` 新欄位 + partial unique index，永久保留 | 04-01 | `V11__transactions_idempotency_key.sql`；`TransactionsIdempotencyIT.idempotencyIndexIsPartialAndUnique`（斷言 `pg_indexes.indexdef` 同時含 `UNIQUE` 與 `WHERE`）、`.multipleNullKeysCoexistForSameUser` |
| **D-09** | 送出流程收斂為「送出中 → 已記錄」兩態，成功畫面渲染後端 `TradeDto` | 04-09、04-11 | 04-09 的 U-16 九列逐行刪除（`grep` 對 `Math.random`/`placeStage`/`placeSteps`/`fillPx`/`orderId` 全 0）；`OrderTicket.test.ts` `Test 30（D-09）成功畫面只渲染後端 TradeDto,即使它與表單輸入矛盾`、`Test 31（D-09）交易編號完整顯示,且畫面沒有任何撮合語意` |
| **D-10** | shared revision counter，已掛載的頁自行重讀 | 04-07、04-12 | `portfolioRevision.test.ts` 10 條；`Positions.test.ts` `Test 2(D-10)`、`Test 5(D-10 的核心論證):只重讀本頁自己的資料源,不代替未掛載的頁發請求`；`Trades.test.ts` `Test 3(D-10)`；`Test 4(Pitfall 12)` ×3（mock mode 零網路） |
| **D-11** | Trades 重讀保留篩選排序、頁碼歸零；新交易不在結果集內時明確告知 | 04-12 | `Trades.test.ts` `Test 11(D-11)`、`Test 12(不得複製第二條重置邏輯)`、`Test 13/14(D-11)`、`Test 15(判定只能比 id)`、`Test 16(清除時機)` |
| **D-12** | 交易成功但 refetch 失敗時，兩件事分開呈現 | 04-12 | `Positions.test.ts` `Test 10:summary 重讀失敗時,ticket 仍顯示成功與交易編號,且沒有任何整體失敗`（**跨元件**：同一測試同時掛載 `Positions` 與 `OrderTicket`）、`Test 9(D-12)`；三頁 `Test 8(U-06 / D-12)` |
| **D-13** | API mode 的「剛成交列 fresh 高亮」 | 04-12 | `Positions.test.ts` `API mode 持倉列依 apiLastFill 帶 fresh 高亮(Phase 4 D-13,反轉 Phase 3 的鎖定)`（測試反轉已於 04-12 §1 完整交代，`git show f381188` 可證為改寫非刪增）；`Trades.test.ts` `Test 18/19(D-13 / U-12)`；兩頁 `Test 20(來源切換)` |
| **D-14** | key 送出時產生、重試沿用、改欄位換新 | 04-11 | `OrderTicket.test.ts` `Test 25(D-14 規則 1)`、`Test 26(D-14 規則 1)`、`Test 27(D-14 規則 2)`、`Test 28(D-14／D-07 互鎖):400 → 改數量 → 再送,成功建立而不是 409`、`Test 29(DP-11)`、`Test 42(U-04 key 處置表)`；斷言讀的是 HTTP `Idempotency-Key` header 而非函式參數 |
| **D-15** | SELL 載入持倉預檢並顯示「可賣數量」，後端 409 仍是最終權威 | 04-11 | `OrderTicket.test.ts` `Test 44`~`Test 49`，特別是 `Test 48(judgment §5 的實質驗收):預檢通過仍可能被後端 409 拒絕`、`Test 46(零持倉的文案)`、`Test 50(Phase 3 D-04 / judgment §7):預檢只碰 symbol 與 totalQuantity` |
| **D-16** | 欄位級錯誤綁輸入框，其餘顯示於 ticket 底部；Bean Validation 英文值不得顯示 | 04-08、04-11 | `i18n.test.ts` 8 條（含 `keeps broker and order lifecycle vocabulary out of every phase 4 string`）；`OrderTicket.test.ts` `Test 35(欄位級)`、`Test 36(D-16 最重要的 negative test):fields 的英文 value 絕不進入 DOM`、`Test 37/38`、`Test 39(依 code 分派,不依賴錯誤出現順序):11 種 code`、`Test 43(401 不在 ticket 顯示)` |

**16 條決策全部指得到至少一個已完成的 plan 與其驗收證據，無孤兒。**

---

## F. Deferred 未被誤做的複查

掃描範圍：兩個 repo 在本 Phase 實際變更的檔案（後端 21 個、前端 21 個，由 `git diff --name-only <merge-base> HEAD` 產生）。
指令：`grep -rniE "pending|partial fill|time.in.force|cashAfter|available_cash|watchlist|csv.import"`

### 後端

**命中 1 筆，全部無關**：

| 命中 | 判定 |
|------|------|
| `ai-docs/bug-reports/LESSONS.md:23` 的 `...watchlist` | **無關** —— 該行記錄的是 GSD `phase.insert` 工具把 slug 截斷的缺陷，`watchlist` 只是被截斷的目錄名字串 |

`pending` / `partial fill` / `time-in-force` / `cashAfter` / `available_cash` 在**全部後端生產與測試程式碼中零命中**。

### 前端

| 命中群 | 檔案 | 判定 |
|--------|------|------|
| `cashAfter` 的 i18n key 與模板渲染 | `i18n.ts:56,247`、`OrderTicket.vue:452,480,899` | **mock mode 既有保留（D-04 允許）** —— 實查 `OrderTicket.vue:451-457` 與 `:479-482`，兩處渲染皆由 `v-if="live"` 包住；`:899` 的 `124_580` 常數上方有繁中註解「mock mode 專屬的展示值(D-04:API mode 不渲染,後端沒有帳戶餘額模型)」。**API mode 不渲染、不留空版位**，由 `OrderTicket.test.ts` 的 D-04 兩條對照測試鎖住 |
| `tif: 'Time in force'`、`day` / `gtc` | `i18n.ts:250` | **mock mode 既有保留（D-04 允許）** —— 同上，review 步驟的訂單類型 / TIF 區塊由 `v-if="live"` 包住 |
| `cashAfter` 出現在 negative 斷言 | `OrderTicket.test.ts:191`、`tradingApi.test.ts:121,130,146` | **這正是防線本身** —— `not.toContain(t('en','cashAfter'))`（API mode）、`expect(body).not.toHaveProperty('cashAfter')`；`:146` 是刻意構造的「更寬呼叫端物件」fixture，用來證明逐欄投影擋得住 |
| `'Pending'` / `'Filled'` / `'Routing'` / `'Place order'` / `'Avg fill'` | `OrderTicket.test.ts:207` | **這正是防線本身** —— `const forbidden = [...]` 的禁用詞清單 |
| `pending` 作為 JS 變數名 | `OrderTicket.test.ts` ×7、`Overview.test.ts:208`、`Positions.test.ts:413`、`Trades.test.ts:523` | **無關** —— 全部是 `deferred<Response>()` 的 pending promise，測試用來卡住非同步以驗證 loading / refreshing 態，與「pending order」語意毫無關係 |
| `cancelPendingSearch()` | `OrderTicket.vue` ×6 | **無關** —— typeahead 的 debounce timer 取消，與「cancel order」無關 |
| `time-in-force` 出現在禁用詞陣列 | `i18n.test.ts:90` | **這正是防線本身** —— `FORBIDDEN_EN_TERMS` |
| `watchlist` | `i18n.ts:8,199`、`Overview.vue:108-111,248,424`、`Overview.test.ts`、`task4.test.ts:149` | **Phase 4 之前既有，本 Phase 未動** —— Overview 的 watchlist 卡片由 `data.ts` 的 `SYMBOLS.filter(s => s.star)` 產生，是 Phase 3 就存在的 mock 卡片；`Overview.test.ts:317` 明文測「Watchlist 與 News 卡在 API mode 照常渲染(**Phase Boundary**)」。watchlist API 化屬 PORT-06 (v2) / Phase 04.1，**本 Phase 沒有實作任何 watchlist API** |

**逐一判定結論：零違規。** 所有命中不是「mock mode 既有保留（D-04 明文允許）」、就是「negative 斷言防線本身」、或「同名不同義的變數命名」。

### 另行確認：Deferred 清單的其餘項目

| Deferred 項目 | 是否被誤做 | 依據 |
|--------------|-----------|------|
| pending order / cancel / partial fill / TIF | **否** | 上表；後端 `CreateTradeRequest` 仍為 7 欄，`tradingApi.test.ts` 的 sorted-key `toEqual` 鎖死 |
| 批次補登（CSV import） | **否** | `csv.import` 兩個 repo 零命中 |
| 可用現金 / 日級損益 / 資產分類 / watchlist 的 UI（屬 Phase 04.1） | **否** | `available_cash` 零命中；`cashAfter` 僅存於 mock 分支；`.planning/phases/04.1-backend-data-gap-backfill/` 目前是**空的 placeholder** |
| notifications API 化 | **否** | mock 通知由 `createMockTradingApi` 推送，API mode 不推（04-07 明文設計邊界） |
| 多幣別呈現 | **否** | 本 Phase 未觸碰任何彙總或換匯邏輯 |

---

## G. T-04-SC：零新增依賴的確認（plan `<threat_model>` 要求順帶確認）

| Repo | 指令 | 結果 |
|------|------|------|
| 後端 | `git diff <merge-base> HEAD -- '*pom.xml'` | **僅 `stock-module-trading/pom.xml` 新增兩項**：`spring-boot-starter-webmvc-test`、`spring-boot-starter-security-test`，**皆為 `<scope>test</scope>`、皆未指定版本（由 parent BOM 管理）、皆為 Spring Boot 官方 starter** —— 與 `04-RESEARCH.md` §Package Legitimacy Audit 及 04-04 的預期**逐字相符**，無第三項 |
| 前端 | `git diff --stat <merge-base> HEAD -- 'vue-app/package.json' 'vue-app/package-lock.json'` | **零輸出 —— `package.json` 與 lock 檔一個位元組都沒改** |

**結論：T-04-SC 的 accept 處置成立，無需人工套件合法性閘門。**

---

## H. `04-VALIDATION.md` §Validation Sign-Off 第 5 項的複述（plan 明文要求）

> - [ ] Feedback latency < 60s for unit/component layers

**此項刻意不勾，必須複述其理由，不得默默視為通過：**

僅對 unit / component 層成立（backend unit 實測 4:59 min 為**全部 10 個模組**，其中 `stock-module-trading` 僅 9.2s；frontend 兩個 mode 各約 40s）。
**6 個以 backend IT 為驗收的 task**（04-01-T2/T3、04-02-T2、04-04-T3、04-05-T1/T2）因 Testcontainers 需啟動 PostgreSQL + Redis + Kafka（~3-5 min）而超過 60s。

**這是 `04-VALIDATION.md` §Test Infrastructure 已載明並接受的取捨，不是可修正的缺口** ——
真實 PostgreSQL 對 TRAD-03 沒有替代方案（H2 / in-memory 無法對 partial index 推斷 `ON CONFLICT`，TRAD-03 的核心會變成未測）。
本次實測進一步佐證：全套 IT 一輪 + 三次 `TradingApiIT` 連跑，每輪都必須付一次容器啟動成本。

其餘六項 sign-off 維持成立；本次執行對「Both repos verified (judgment §8)」提供了實際證據（§A）。

---

## I. 兩個 repo 的收尾狀態（plan `<output>` 第 1 點：必須逐一實查，不得沿用 SUMMARY 記載）

**這一節抓到了與既有 SUMMARY 記載不符的事實，正是 plan 要求實查的原因。**

| 項目 | 後端 `stock-web-v2` | 前端 `stock-v2` |
|------|-------------------|----------------|
| 分支 | `feature/phase-04-trade-idempotency` | `feature/phase-04-manual-trade-creation` |
| 最後一個 commit | `90b5d7f docs(phase-04): 更新 wave 7 追蹤狀態（12/13）` | `51711b0 fix(04-12): 「新」標記只用水平內距，不撐高交易與持倉的列高` |
| 遠端分支是否存在 | **是** —— `origin/feature/phase-04-trade-idempotency` @ `e68a5ba` | **是** —— `origin/feature/phase-04-manual-trade-creation` @ `b5bd884` |
| upstream 設定 | 已設定 | 已設定 |
| `git branch -r --contains HEAD` | **空** —— 本地 HEAD 未包含在任何遠端分支 | **空** —— 同左 |
| 本地領先遠端 | **6 個 commit（全部是 docs）** | **15 個 commit（04-10 / 04-11 / 04-12 的完整 RED→GREEN）** |
| 是否已開 PR | **是 —— PR #20 OPEN**，標題「feat(04): 手動建立交易的冪等機制與交易後 refetch（進行中 5/13）」 | **否 —— 無任何 Phase 4 PR** |

### ⚠️ 與既有 SUMMARY 記載的三處不符（據實記錄，不修改既有檔案）

1. **04-10 / 04-11 / 04-12 SUMMARY 皆寫「兩個 repo 都未 push,也未開 PR」——「未開 PR」對後端不成立。**
   PR #20 早在 04-05 期間就已開啟（04-05 SUMMARY 自己提到「全綠後推送,CI 的 E2E job 仍紅」與「PR #20 的 CI 紅燈」，可交叉印證）。
   正確的說法是：**後端的程式碼全部已 push 且已在 PR #20 中；未 push 的 6 個 commit 全是 `.planning/` 文件**。
2. **前端 remote 分支存在但落後 15 個 commit。** 遠端停在 04-09 收尾附近；04-10 / 04-11 / 04-12 的全部實作**尚未 push**，且**沒有 PR**。
3. **PR #20 的標題仍寫「進行中 5/13」**，與實際的 13/13（Task 1 層級）不符，需在收尾時更新。

**本 plan 依交辦指示，未執行任何 `git push`、未開任何 PR、未更新 PR #20 的標題或內容。** 以上僅為狀態記錄，處置權在 Yuan。

**另記錄一項與 Phase 4 相關的既有 PR 狀態**：`draft PR #15`（`fix/pr13-review-followups`）**已 MERGED**。
因此 DP-1 (c) 排除的 `executedAt` 未來時間驗證**已存在於 develop 並已隨 merge-base 進入本分支**，
但那**不是 Phase 4 的成果**（見 §D 第 5 項的實查證據）。

---

## J. Phase 5（VER-01 ~ VER-03）將接手的完整清單（plan `<output>` 第 2 點）

### VER-01（Backend Maven tests 覆蓋 auth cookie、CSRF、CORS、refresh/logout、401/403 envelope、trading idempotency）

Phase 4 已交付 trading idempotency 那一半（`TransactionsIdempotencyIT` 8 + `TradingApiIT` 的 10 條冪等 IT），
其餘由 Phase 1 / Phase 2 的既有 IT 承接（`BrowserAuthFlowIT` 11、`CorsIT` 3、`ErrorHandlingIT` 7 等，本次全綠）。
**Phase 5 需做的是彙整與確認，不是重寫。**

### VER-02（Frontend Vitest / type-check / build 覆蓋 API client、auth store、runtime mode、portfolio adapters、trading adapter）

Phase 4 已交付 trading adapter（`tradingApi.test.ts` 12）、market adapter（`marketApi.test.ts` 12）、
runtime mode 註冊（`api-adapter-wiring.test.ts` 14）。**同樣是彙整而非重寫。**

### VER-03（Cross-repo browser smoke flow）—— Phase 5 的真正工作量所在

以下**全部**是 Phase 4 明確**未**涵蓋、必須由 VER-03 接手的項目：

| # | 項目 | 來源 |
|---|------|------|
| 1 | 真實瀏覽器完整流程 login → `/me` → portfolio reads → create trade → refetch → logout | VALIDATION §Out of Scope |
| 2 | 真實 cookie / CSRF 行為（SameSite / Secure / HttpOnly 實際生效） | 同上；04-11:319 |
| 3 | 前端 → 後端真實 network call 的證據（judgment §3 的硬要求，Phase 4 全部 stub `fetch`） | 同上；04-11:320 |
| 4 | 跨 repo 契約在真實 payload 上的一致性（`KlineDto` OHLCV 是 JSON **字串**） | 同上；04-10:245 |
| 5 | 走勢圖的視覺正確性（線畫得對不對） | VALIDATION §Manual-Only；04-10:244 |
| 6 | 版面比例 / 動畫觀感 / 文案在實際版位下的可讀性（雙 mode） | VALIDATION §Manual-Only；本 plan Task 2 的甲/乙/丙 14 步 |
| 7 | **`04-UI-SPEC.md` §Layout Contract「refresh 指示不得造成版面高度跳動」—— 明確未達成** | 04-12 §4；「更新中…」為流內列，出現/消失時下方內容位移約 28px。04-12 已評估並放棄絕對定位浮層與永久佔位兩個替代方案，需在瀏覽器量測後定版位 |
| 8 | 320px 下錯誤區塊 code 與 traceId 的換行（未實測） | 04-11 §5 |
| 9 | 送出鈕 `min-width: 136px` 是否真的消除版位跳動（依字寬估算，未量測） | 04-11 §5 / Deviations #1 |
| 10 | 「新」pill 的 `padding: 0 8px; line-height: 1.2` 是否真的不撐高列高（盒模型推算，未量測） | 04-12 Deviations #1 |
| 11 | `prefers-reduced-motion` 的實際生效（jsdom 不評估 media query） | 04-12 |
| 12 | `aria-busy` / `role="status"` 的螢幕閱讀器實際播報行為 | 04-12 |
| 13 | 鍵盤 combobox 在真實瀏覽器的可用性（見 §D 的措辭判定） | 本 plan；Task 2 乙段第 6 步 |

### 歸屬 Phase 04.1 / 其他

| 項目 | 歸屬 | 說明 |
|------|------|------|
| `apiTypes.ts` 的 `AssetDto.volumeText` 宣告為 `string` 但後端 `rs.getString` 可回 null | **Phase 04.1 / Phase 5** | 04-10 已在消費端用 `\|\| '—'` 擋住，**根因（型別宣告）未修**。修時要連 `marketApi.ts` 的 mock 投影與其 12 條單測一起改 |
| 可用現金 / 日級損益 / 資產分類 / watchlist API 化 | **Phase 04.1** | 目錄已建但為空 placeholder |
| PR #20 標題仍寫「進行中 5/13」；前端無 PR 且落後 15 個 commit | **收尾作業** | 見 §I |
| `TRADE_EXECUTE` 缺失 → 403 無獨立測試 | **維持 accept** | `Role.USER` 已含該權限，repo 內沒有缺少它的角色可構造。若日後新增權限較低的角色，可補 `MethodSecurityDenialIT` 樣式的測試 |

---

## K. 方案 E 是否曾切換（plan `<output>` 第 3 點）

**未曾切換。** 併發冪等維持 04-02 建立的方案 A（`insert ... on conflict (user_id, idempotency_key) where idempotency_key is not null do nothing` + 同 transaction 內重讀）。
理由與證據見 §B：本次 3 次連跑加上全套 IT 一輪、加上 04-05 的 4 次，累計 8 次連續綠燈，零偶發。
方案 E（`pg_advisory_xact_lock(hashtext(userId || key))`）的切換判準已寫進 `TradingApiIT` 的繁中註解，不只存在於 SUMMARY —— 留給未來真的出現偶發紅燈時使用。

---

## 已知缺口（本 plan 發現但依 objective 不修）

本 plan 的 objective 明文「**不寫任何程式碼**；若驗證發現缺口，處置是回報給 orchestrator 開 gap-closure」。
以下三項如實回報，**本 plan 一律未修**：

1. **前端 15 個 commit 未 push、無 PR**（§I）—— 屬收尾作業，處置權在 Yuan。
2. **PR #20 標題與實際進度不符**（§I）—— 同上。
3. **`04-VALIDATION.md` §Out of Scope 第 5 列「`executedAt` 未來時間驗證 → Owner: PR #15」的 Owner 欄已過期** ——
   PR #15 已 MERGED，該功能現已在 develop 上並有 `TradingApiIT.futureExecutedAtIsRejectedButBackfillIsAllowed` 覆蓋。
   **這不影響本次稽核的結論**（Phase 4 仍然沒有實作它、也沒有宣稱它），只是文件的 Owner 標註可在 Phase 5 順手更新。

**沒有發現任何實作缺陷。** 四項驗證全綠、併發穩定、六條需求證據齊備、零 over-claim、16 條決策全覆蓋、Deferred 未被誤做。

---

## Task 2：Yuan 的雙 mode 人工確認 —— **awaiting: Yuan**

**狀態：未執行。** 這是 `checkpoint:human-verify gate="blocking"`，**沒有任何 agent 可以代替 Yuan 執行、模擬或自行核可**。

自動化已經涵蓋：payload 形狀、header 契約、冪等不變量、錯誤分派、狀態機轉換、a11y 屬性存在性、key 生命週期 —— 上方 §C 已逐條指名證據。

**自動化涵蓋不到、必須由 Yuan 親眼確認的**：版面比例、動畫觀感、文案在實際版位下的可讀性，
以及 §J 表中第 5~13 項（走勢圖視覺、320px 換行、min-width 實效、pill 列高、reduced-motion、螢幕閱讀器、鍵盤 combobox）。

驗證步驟見 `04-13-PLAN.md` Task 2 的甲（mock mode 4 步）/ 乙（API mode 10 步）/ 丙（雙語 3 步），共 14 步。
Resume signal：輸入「approved」，或逐條描述哪一步與預期不符（請帶步驟編號）。

**Phase 4 在 Yuan 完成 Task 2 之前不得標記為完成。**

---

## Known Stubs

**無。** 本 plan 不產生任何程式碼。

對 Phase 4 整體的 stub 掃描結論：12 份 SUMMARY 中僅 04-02 曾記錄一個刻意的過渡 stub
（`TradingService.createTrade` 的 `.orElseThrow(TRADE_CONFLICT)`），**已由 04-03 的 insert-first 流程取代**（本次實查 `TradingService` 已無該過渡處置，且 `TradingServiceTest` 有 8 處 `verify(..., never())` 鎖住冪等命中不碰 holdings）。
04-09 留下的 `ticket-quote-chart-empty` 寫死佔位**已由 04-10 的三態機取代**。**Phase 4 目前無殘留 stub。**

## Threat Flags

無。本 plan 未新增任何網路端點、認證路徑、檔案存取或信任邊界的 schema 變更 —— 它只執行驗證與稽核。

## User Setup Required

Task 1：None（Docker 已就緒，Server 29.5.3）。
**Task 2：需要 Yuan 本人操作瀏覽器，且乙段需要後端在跑並已登入。**

---
*Phase: 04-manual-trade-creation-idempotency-post-trade-refetch*
*Task 1 completed: 2026-08-16 — Task 2 awaiting Yuan*
