---
phase: 05-cross-repo-browser-flow-verification-contract-hardening
plan: 13
subsystem: api
tags: [jackson3, bigdecimal, serialization, cash-ledger, contract, tracer]
status: complete

requires:
  - phase: 05-12
    provides: "Wave 1 合併、develop CI 綠(D-25 閘門)"
provides:
  - "dowob.xyz.stockwebv2.infrastructure.web.json.PlainDecimalStringSerializer(Jackson 3 StdSerializer<BigDecimal>,toPlainString 字串)"
  - "dowob.xyz.stockwebv2.infrastructure.web.json.@DecimalString(meta-annotation,FIELD/METHOD/PARAMETER/ANNOTATION_TYPE,RUNTIME)"
  - "stock-infrastructure 依賴 tools.jackson.core:jackson-databind(Boot BOM 管理版本)"
  - "現金 slice 字串契約:CashEntryDto / CreateCashEntryRequest / CashBalanceDto 的 amount 皆 @DecimalString"
affects: [05-14, 05-15, trading-api, portfolio-summary, frontend-apiTypes]

actuals:
  tokens: 6500
  tasks: 2
  commits: 2
plan_head_before: 2da8d098294a1506e8ff5cf003902c3235459672
plan_head_after: 922935798e1ddb99bb45ec8b83e0eedb838626be
commits: 2

tech-stack:
  added:
    - "tools.jackson.core:jackson-databind(stock-infrastructure;已是各 API 模組的傳遞依賴,非新第三方套件)"
  patterns:
    - "API DTO / Request 的 BigDecimal 欄位逐欄位標 @DecimalString(不用全域 JsonMapperBuilderCustomizer),annotation 跟 DTO 走,WebMvc slice / Redis 快取 / 正式 HTTP 一致"
    - "請求端沿用 Jackson 預設 string → BigDecimal,仍接受 JSON number(不拒收)"

key-files:
  created:
    - /home/user/stock-web-v2/stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/web/json/PlainDecimalStringSerializer.java
    - /home/user/stock-web-v2/stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/web/json/DecimalString.java
    - /home/user/stock-web-v2/stock-infrastructure/src/test/java/dowob/xyz/stockwebv2/infrastructure/web/json/PlainDecimalStringSerializerTest.java
  modified:
    - /home/user/stock-web-v2/stock-infrastructure/pom.xml
    - /home/user/stock-web-v2/stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/CashEntryDto.java
    - /home/user/stock-web-v2/stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/CreateCashEntryRequest.java
    - /home/user/stock-web-v2/stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/CashBalanceDto.java
    - /home/user/stock-web-v2/stock-module-trading/src/test/java/dowob/xyz/stockwebv2/trading/api/CashLedgerControllerWebMvcTest.java
    - /home/user/stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/CashEntryApiIT.java

key-decisions:
  - "逐欄位 @DecimalString + toPlainString serializer(RESEARCH §Q2 方案 C);不沿用 ToStringSerializer(ZERO.setScale(8) 會輸出 0E-8)"
  - "請求端不拒收 JSON number(CONTEXT Claude's Discretion);既有以 number 送 amount 的 IT 案例保留,兼作相容證據"
  - "CreateCashEntryRequest 的 1e12 上限保留,Javadoc 改寫理由(字串化後不再是 double 精度問題,而是業務上限 + 舊客戶端相容)"

patterns-established:
  - "BigDecimal API 欄位:`@DecimalString BigDecimal x`;JSON 輸出一般記號、保留 scale、null 維持 null"

requirements-completed: [VER-01]

coverage:
  - id: S1
    description: "PlainDecimalStringSerializer:0.00000000 / 0.00000001 / 123456789012.12345678 / 100.00000000 / 1E+3→1000 / null / 反序列化逐字"
    requirement: VER-01
    verification:
      - kind: unit
        ref: "stock-infrastructure/src/test/java/dowob/xyz/stockwebv2/infrastructure/web/json/PlainDecimalStringSerializerTest.java"
        status: pass
    human_judgment: false
  - id: S2
    description: "現金分錄 POST 回應 data.amount 為 JSON 字串、逐字等於 toPlainString、不出現 1E-8"
    requirement: VER-01
    verification:
      - kind: webmvc
        ref: "stock-module-trading/src/test/java/dowob/xyz/stockwebv2/trading/api/CashLedgerControllerWebMvcTest.java#responseAmountIsPlainJsonString,responseAmountNeverUsesScientificNotation"
        status: pass
    human_judgment: false
  - id: S3
    description: "精度回歸 IT:POST \"123456789012.12345678\" → 回應與 GET 列表逐字相同;同 key \"10\" / \"10.00000000\" 視為同一筆"
    requirement: VER-01
    verification:
      - kind: integration
        ref: "stock-start/src/test/java/dowob/xyz/stockwebv2/start/CashEntryApiIT.java#amountRoundTripsAsExactString,stringAmountReplayIsSameEntry"
        status: pending-ci
    human_judgment: false

duration: 25min
completed: 2026-11-11
---

# Phase 5 Plan 13: BigDecimal 字串化 tracer(現金 slice)Summary

**以 Jackson 3 `PlainDecimalStringSerializer`(`toPlainString()`)+ `@DecimalString` meta-annotation 建立 BigDecimal → JSON 字串機制,並在現金分錄 / 現金餘額三個 DTO 上端到端證明「字串請求 → BigDecimal → 原樣存讀 → 字串回應」。**

## Performance

- **Duration:** 約 25 分鐘
- **Completed:** 2026-11-11
- **Tasks:** 2/2
- **Files:** 3 新增、6 修改

## Accomplishments

- `stock-infrastructure` 新增 `web/json` 套件:`PlainDecimalStringSerializer`(`gen.writeString(value.toPlainString())`)與 `@DecimalString`(`@JacksonAnnotationsInside` + `@JsonSerialize(using = ...)`),繁中 Javadoc 說明 D-05 / D-06 / D-07 / D-09 與不用 `toString()` 的理由。
- `pom.xml` 加 `tools.jackson.core:jackson-databind`(無版本,Boot BOM 管理)。
- `CashEntryDto.amount`、`CreateCashEntryRequest.amount`、`CashBalanceDto.amount` 標 `@DecimalString`;`CashEntryDto` 類別 Javadoc 的「以 JSON number 輸出」改寫為字串契約(檔內不再出現舊 serializer 類名)。
- `CashLedgerControllerWebMvcTest` 新增兩個回應格式斷言;`CashEntryApiIT` 改兩處 number 斷言為字串並新增兩個 IT。

## Task Commits

1. **Task 1(tracer):PlainDecimalStringSerializer + @DecimalString → CashEntryDto → WebMvc** — `a50c67b` (feat)
2. **Task 2:Request / Balance 標註 + CashEntryApiIT 精度回歸與冪等重送** — `9229357` (test)

## TDD / Tracer 證據

- **RED(Task 1 單元):** 先寫 `PlainDecimalStringSerializerTest`,執行 → `COMPILATION ERROR`(`PlainDecimalStringSerializer` / `DecimalString` 不存在、`tools.jackson.databind.json` 不在 stock-infrastructure classpath)。
- **GREEN:** 加依賴與兩個類別後 6/6 綠。
- **RED(Task 1 WebMvc):** 暫時移除 `CashEntryDto` 上的 `@DecimalString` 重跑 → `responseAmountIsPlainJsonString`、`responseAmountNeverUsesScientificNotation` 2 failures;還原後 8/8 綠。
- **Tracer gate:** 還原後重跑 `<verify>`(見下)全綠,才進 Task 2。

## Verification(本機實跑)

| 命令 | 結果 |
|------|------|
| `./mvnw -B -pl stock-module-trading -am test -Dtest='PlainDecimalStringSerializerTest,CashLedgerControllerWebMvcTest' -Dsurefire.failIfNoSpecifiedTests=false` | BUILD SUCCESS;6 + 8 tests,0 fail |
| `./mvnw -B -o -pl stock-infrastructure,stock-module-trading -am test`(全部單元 / WebMvc) | BUILD SUCCESS;stock-common 106、stock-infrastructure 48、stock-module-trading 466,0 fail |
| `./mvnw -B -o -pl stock-start -am test -Dtest='*RulesTest'`(ArchUnit 守門) | BUILD SUCCESS;8 tests,0 fail |
| `./mvnw -B -q -pl stock-start -am test-compile` | exit 0 |
| `./mvnw -B spotless:check` | BUILD SUCCESS |

**未在本機執行:** `CashEntryApiIT`(Testcontainers,容器內無 Docker)— 只做 test-compile;IT 結果以 CI Integration job 為準(05-25)。

## Deviations from Plan

None - plan executed exactly as written.(Task 2 的 commit 依 plan 指定用 `test(05-13)`,內含兩個 DTO 的一行 annotation 主程式變更。)

## 預期中的過渡破壞(交給 05-15,非本 plan 範圍)

`CashEntryDto` / `CashBalanceDto` 字串化後,下列 IT / E2E 以 `JsonNode.decimalValue()` 或 `jsonPath(..., equalTo(<number>))` 讀現金金額的地方在 Docker CI 會失敗,直到 05-15(wave 6,「IT / E2E 對金額的 jsonPath number 斷言改為字串、decimalValue() 改為 asDecimal()」)修正:

- `stock-start/.../PortfolioCashAllocationIT.java`(`balanceOf(...).get("amount").decimalValue()` 多處)
- `stock-start/.../PortfolioDividendSummaryIT.java`(`balanceOf(...).get("amount").decimalValue()` 4 處)
- `stock-start/.../e2e/CashLedgerE2E.java`(`$.data.amount` number 斷言 3 處:約 :63、:84、:280)
- `stock-start/.../e2e/support/AbstractPortfolioE2E.java` 的 `balanceOf` 呼叫端(`PortfolioBaseCurrencyE2E` 等)

本機 test-compile 不受影響(皆為執行期斷言);Wave 2 為硬切(D-07),CI 證據點在 05-25。

## Threat Flags

None — 未新增 endpoint / auth 路徑 / schema;只改既有 DTO 的 JSON 表示。T-05-22(金額精度)以 `@DecimalString` + 精度 IT 緩解;T-05-23(壞數字字串的 400)沿用 `GlobalExceptionHandler` 靜態訊息,本 plan 未改。

## Known Stubs

None.

## Next Phase Readiness

- 05-14 可直接用 `@DecimalString` 擴到其餘 trading / backtest / asset / market-data DTO,並加 ArchUnit 守門(`fields().that().haveRawType(BigDecimal.class)... beAnnotatedWith(DecimalString.class)`)。
- 前端 05-16 起的 `apiTypes` 型別變更需與本 plan 同分支合併(D-07 硬切)。

## Self-Check: PASSED

- FOUND: PlainDecimalStringSerializer.java、DecimalString.java、PlainDecimalStringSerializerTest.java
- FOUND: a50c67b、9229357(皆為 HEAD 祖先)
