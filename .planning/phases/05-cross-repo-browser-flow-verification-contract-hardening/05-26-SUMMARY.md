---
phase: 05-cross-repo-browser-flow-verification-contract-hardening
plan: 26
subsystem: api
tags: [backtest, bean-validation, bigdecimal, dos, webmvc-test, spring-boot]

requires:
  - phase: 05-cross-repo-browser-flow-verification-contract-hardening
    provides: "D-07 金額走 JSON 字串(@DecimalString)與 ValidationBoundaryE2E D3 下界案例"
provides:
  - "CreateBacktestRunRequest.initialCapital 上限 1e12 與 6 位小數約束(@DecimalMax / @Digits)"
  - "BacktestService.validateCapital 縱深防禦(MAX_INITIAL_CAPITAL / MAX_INITIAL_CAPITAL_SCALE)"
  - "backtest 模組第一個 WebMvc slice(BacktestTestApplication + BacktestControllerWebMvcTest)"
  - "ValidationBoundaryE2E D3b 上界與位數案例(待 CI)"
affects: [05-28, 05-33, backtest]

actuals:
  tokens: 7500
  tasks: 2
  commits: 2
plan_head_before: c4b58bda84a85141796174bb7f3d29eeb7be158f
plan_head_after: 066f744cff18ade7cfa83132a08b0110513df021

tech-stack:
  added: []
  patterns:
    - "BigDecimal 範圍檢查先 compareTo(O(1) 對指數表示)再去尾零,且只在原始 scale 超限時才 stripTrailingZeros"
    - "WebMvc slice 以 @Timeout(10) 包住 DoS 回歸案例,回歸時以失敗呈現而非卡死"

key-files:
  created:
    - stock-module-backtest/src/test/java/dowob/xyz/stockwebv2/backtest/BacktestTestApplication.java
    - stock-module-backtest/src/test/java/dowob/xyz/stockwebv2/backtest/api/BacktestControllerWebMvcTest.java
  modified:
    - stock-module-backtest/pom.xml
    - stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/api/CreateBacktestRunRequest.java
    - stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/service/BacktestService.java
    - stock-module-backtest/src/test/java/dowob/xyz/stockwebv2/backtest/api/CreateBacktestRunRequestValidationTest.java
    - stock-module-backtest/src/test/java/dowob/xyz/stockwebv2/backtest/service/BacktestServiceTest.java
    - stock-module-backtest/src/test/java/dowob/xyz/stockwebv2/backtest/engine/DeterministicBacktestEngineTest.java
    - stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/ValidationBoundaryE2E.java

key-decisions:
  - "Hibernate Validator 9.0.1 的 @Digits 對 BigDecimal 用原始 scale(不去尾零),HTTP 層 \"100.00000000\" 回 400;保留此較嚴格行為(避免對超長尾零字串做 O(n²) 去零),前端 big.js 送出前已去尾零故正式路徑不受影響"
  - "service 層以去尾零後 scale 判斷(尾零不算),比 HTTP 層寬鬆,屬縱深防禦;engine.run 的 catch 不放寬"

patterns-established:
  - "回測模組 WebMvc slice:@WebMvcTest + 巢狀 OpenSecurityConfig + @MockitoBean service/AuditLogger,只證明 400 + service 零互動,信封由 E2E 負責"

requirements-completed: [VER-01]

coverage:
  - id: D1
    description: "initialCapital 指數字串 / 超過 6 位小數 / 超過 1e12 在 Bean Validation 被擋(validator 單元)"
    requirement: VER-01
    verification:
      - kind: unit
        ref: "stock-module-backtest/src/test/java/dowob/xyz/stockwebv2/backtest/api/CreateBacktestRunRequestValidationTest.java"
        status: pass
    human_judgment: false
  - id: D2
    description: "POST /api/v1/backtests/runs 界外 initialCapital 回 400 且 BacktestService 零互動;合法值到達 service 並回 200"
    requirement: VER-01
    verification:
      - kind: integration
        ref: "stock-module-backtest/src/test/java/dowob/xyz/stockwebv2/backtest/api/BacktestControllerWebMvcTest.java"
        status: pass
    human_judgment: false
  - id: D3
    description: "BacktestService.validateCapital 縱深防禦:界外值丟 BACKTEST_INVALID_INITIAL_CAPITAL(靜態訊息)且 engine 零呼叫;界內 100.00000000 / 1e12 成功"
    requirement: VER-01
    verification:
      - kind: unit
        ref: "stock-module-backtest/src/test/java/dowob/xyz/stockwebv2/backtest/service/BacktestServiceTest.java#outOfRangeCapitalIsRejectedBeforeEngine,boundaryCapitalWithinRangeIsAccepted"
        status: pass
    human_judgment: false
  - id: D4
    description: "引擎以 initialCapital 0.000001 與 1e12、FIVE_YEARS 跑完不拋 ArithmeticException"
    requirement: VER-01
    verification:
      - kind: unit
        ref: "stock-module-backtest/src/test/java/dowob/xyz/stockwebv2/backtest/engine/DeterministicBacktestEngineTest.java#boundaryInitialCapitalRunsWithoutArithmeticFailure"
        status: pass
    human_judgment: false
  - id: D5
    description: "真後端信封:界外 initialCapital → VALIDATION_FAILED + error.fields.initialCapital;1e12 與 0.000001 → succeeded(ValidationBoundaryE2E D3b)"
    requirement: VER-01
    verification:
      - kind: e2e
        ref: "stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/ValidationBoundaryE2E.java#InitialCapitalUpperBoundAndScale"
        status: unknown
    human_judgment: false

duration: 7min
completed: 2026-11-14
status: complete
---

# Phase 05 Plan 26: 回測 initialCapital 上限與位數硬化 Summary

**`initialCapital` 加上 `@DecimalMax("1000000000000")` + `@Digits(integer = 13, fraction = 6)`,`"1e999999999"` 在 Bean Validation 就回 400、service / engine 不會被呼叫;`BacktestService` 以 O(1) 的比較做縱深檢查;界內極值引擎正常跑完(BE-CR-01 / T-05-37 關閉,E2E 信封證據待 CI)**

## Performance

- **Duration:** 約 7 分鐘(實作 + 驗證)
- **Started:** 2026-11-14T16:24:01Z
- **Completed:** 2026-11-14T16:30:20Z
- **Tasks:** 2/2
- **Files modified:** 9(2 新建、7 修改)

## Accomplishments

- `CreateBacktestRunRequest` 補 record Javadoc(繁中),寫清楚 1e12 / 13 位、6 位小數、為何必須在 Bean Validation 擋(指數字串讓引擎在持久化前 `setScale` 耗盡 CPU / heap)。
- backtest 模組第一個 WebMvc slice:`"1e999999999"`(`@Timeout(10)`)、`"123456789012.12345678"`、`"1000000000000.000001"` → 400 且 `verifyNoInteractions(backtestService)`;`"100000"` → service 收到 `createRun(eq(42L), any())` 一次、回 200。
- RED 階段實測確認:沒有 annotation 時,Jackson 會接受 `"1e999999999"` 並把請求送到 service(mock 回 null → `run.id()` NPE),所以拒絕確實發生在 **Bean Validation** 層、不是 Jackson 反序列化層;E2E 斷言照計畫維持 `VALIDATION_FAILED` + `error.fields.initialCapital`。
- `BacktestService.validateCapital`:保留原 null / `<= 0` 分支與預設訊息;新增 `compareTo(MAX_INITIAL_CAPITAL) > 0`,或「原始 scale > 6 且去尾零後 scale > 6」→ `BACKTEST_INVALID_INITIAL_CAPITAL`,訊息是靜態字串 `initialCapital must be at most 1000000000000 with at most 6 decimal places`(測試斷言訊息不含輸入)。`engine.run` 的 catch 不放寬。
- `ValidationBoundaryE2E` 新增 `@Nested` 「D3b initialCapital 上界與位數(BE-CR-01)」共 5 個案例,金額一律用 JSON 字串,每案例各自的 email / username。

## Task Commits

1. **Task 1(tracer):initialCapital 上限 / 位數約束 → validator 單元 → WebMvc slice** - `817083b` (fix)
2. **Task 2:BacktestService 縱深防禦 + 引擎邊界 + ValidationBoundaryE2E D3b** - `066f744` (fix)

Tracer feedback gate:非 auto 模式、`human_verify_mode` 預設 end-of-phase、`<verify>` 純自動 → 重跑 Task 1 驗證全綠後才展開 Task 2。

## Files Created/Modified

- `stock-module-backtest/pom.xml` - 加 test scope `spring-boot-starter-webmvc-test`、`spring-boot-starter-security-test`(無 version,BOM 管理;trading 模組已在用)
- `.../backtest/api/CreateBacktestRunRequest.java` - `@DecimalMax` / `@Digits` + 類別 Javadoc
- `.../backtest/BacktestTestApplication.java`(test,新建)- `@WebMvcTest` 的 `@SpringBootConfiguration` 錨點
- `.../backtest/api/BacktestControllerWebMvcTest.java`(test,新建)- 400 + service 零互動的 HTTP 層證據
- `.../backtest/api/CreateBacktestRunRequestValidationTest.java` - 上下界 / 位數的 parameterized 案例
- `.../backtest/service/BacktestService.java` - `MAX_INITIAL_CAPITAL`、`MAX_INITIAL_CAPITAL_SCALE`、靜態訊息常數與縱深檢查
- `.../backtest/service/BacktestServiceTest.java` - 界外值被擋、engine 零呼叫(計數 lambda);界內值成功
- `.../backtest/engine/DeterministicBacktestEngineTest.java` - 0.000001 / 1e12 × FIVE_YEARS 不拋例外;`inputWithCapital(capital, period)` overload
- `stock-start/.../e2e/ValidationBoundaryE2E.java` - D3b 五個案例

## 驗證結果(本機)

| 命令 | 結果 |
|------|------|
| `mvn -B -o -pl stock-module-backtest -am test -Dtest='BacktestServiceTest,DeterministicBacktestEngineTest,CreateBacktestRunRequestValidationTest,BacktestControllerWebMvcTest' -Dsurefire.failIfNoSpecifiedTests=false` | 64 tests,0 failures,BUILD SUCCESS(Engine 18、Validation 13、WebMvc 4、Service 29) |
| `mvn -B -o -pl stock-module-backtest -am verify`(整個模組 + JaCoCo ratchet) | 68 tests,0 failures,`All coverage checks have been met` |
| `mvn -B -o -q -pl stock-start -am test-compile` | exit 0,無 COMPILATION ERROR |
| `mvn -B -o spotless:check` | BUILD SUCCESS |

註:本機以 `/opt/maven/bin/mvn -o` 代替計畫的 `./mvnw`(同一套 Maven reactor,離線使用既有 `~/.m2`)。

**僅 CI 可證(05-33 收集):** `ValidationBoundaryE2E` D3b 五個案例需 Docker(Testcontainers),本機**未執行**,只確認可編譯。

## Decisions Made

- HTTP 層的位數判斷沿用 Hibernate Validator `@Digits` 的原始 scale 語義(尾端零也算),見下方偏差 1。
- service 層依計畫以去尾零後 scale 判斷,並加一個「原始 scale 已超過 6 才去尾零」的短路,讓一般值完全不做 `stripTrailingZeros`。

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - 計畫假設錯誤] `"100.00000000"` 在 Bean Validation 層是違規,不是「無違規」**
- **Found during:** Task 1(GREEN 階段)
- **Issue:** 計畫 must_haves 與 behavior 寫「`"100.00000000"` 無違規」,但 Hibernate Validator 9.0.1 的 `DigitsValidatorForNumber` 遇到 `BigDecimal` 時直接用原始 `scale()`(只有非 BigDecimal 的 Number 才會 `stripTrailingZeros`,已用 `javap` 檢查 bytecode 確認),所以 `100.00000000`(scale 8)違反 `fraction = 6`。
- **Fix:** 不加自訂 validator,也不在 record compact constructor 先去尾零(那樣等於在驗證前對未受限長度的尾零字串做 O(n²) 去零,反而開一個新的 DoS 面)。改成照實測試:`"100.00000000"` 移到違規清單,另補 `"100.000000"`(剛好 6 位)到通過清單;Request 與測試 Javadoc 都寫明此語義。前端 05-28 的 `parsePlainDecimalInput` 以數值判斷(尾零不算),big.js `toWire` 送出前會去尾零,所以正式路徑不會出現這個差異。
- **Files modified:** `CreateBacktestRunRequest.java`、`CreateBacktestRunRequestValidationTest.java`
- **Verification:** validator 13 個案例全綠
- **Committed in:** `817083b`
- **待 Yuan 確認:** 若要 HTTP 層也「尾零不算」,需要自訂 constraint(而且要先限制長度才能去尾零);目前判斷不值得。service 層(`100.00000000` → 成功)維持計畫原樣。

**2. [Rule 2 - 補強] service 去尾零加短路**
- **Found during:** Task 2
- **Issue:** 計畫寫 `stripTrailingZeros().scale() > 6`;非 HTTP 呼叫端如果傳入有大量尾零的值,`stripTrailingZeros` 是逐位相除。
- **Fix:** 條件改成 `scale() > 6 && stripTrailingZeros().scale() > 6`,而且排在 `compareTo(MAX)` 之後;語義不變,一般值不做去零。
- **Committed in:** `066f744`

---

**Total deviations:** 2(1 個 Rule 1、1 個 Rule 2)
**Impact on plan:** 安全性只會更嚴、不會更鬆;唯一可見的差異是 HTTP 層拒絕帶有超過 6 位尾零的原始字串,前端不會送出這種字串。

## Issues Encountered

- 引擎邊界測試(0.000001 / 1e12)沒有經過紅燈階段,因為界內的值本來就不會讓引擎出錯;這兩個測試是回歸防護,計畫也沒有要求改引擎。

## Known Stubs

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- 05-28(前端)可以對齊相同的上限 / 位數;05-33 需要收集 CI E2E job 中 `ValidationBoundaryE2E$InitialCapitalUpperBoundAndScale` 的 5 個案例結果。

## Self-Check: PASSED

- FOUND: stock-module-backtest/src/test/java/dowob/xyz/stockwebv2/backtest/BacktestTestApplication.java
- FOUND: stock-module-backtest/src/test/java/dowob/xyz/stockwebv2/backtest/api/BacktestControllerWebMvcTest.java
- FOUND: commit 817083b、066f744(皆為 HEAD 祖先)
- acceptance grep:`@DecimalMax("1000000000000")` 2、`@Digits(integer = 13, fraction = 6)` 1、`spring-boot-starter-webmvc-test` 1、`verifyNoInteractions` 3、`MAX_INITIAL_CAPITAL` 7、E2E `1e999999999` 2、ServiceTest `1e999999999` 1
