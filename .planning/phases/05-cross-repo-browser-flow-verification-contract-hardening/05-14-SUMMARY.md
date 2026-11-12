---
phase: 05-cross-repo-browser-flow-verification-contract-hardening
plan: 14
subsystem: api
tags: [jackson3, bigdecimal, serialization, archunit, redis-cache, contract, tracer]
status: complete

requires:
  - phase: 05-13
    provides: "@DecimalString / PlainDecimalStringSerializer(現金 slice 已證明)"
provides:
  - "全 API(trading / backtest / asset / market-data)DTO / Request 的 BigDecimal 欄位一律 @DecimalString(79 欄,17 檔)"
  - "ApiDecimalStringRulesTest:ArchUnit 規則 apiDtoBigDecimalFieldsAreDecimalStrings(allowEmptyShould(false))+ 4 個序列化行為樣本"
  - "Redis 鍵 portfolio:valuation:v4:{userId}:{assetId}、portfolio:summary:v4:{userId}:{baseCurrency}"
affects: [05-15, 05-16, trading-api, market-data-api, backtest-api, asset-api, frontend-apiTypes]

actuals:
  tokens: 14500
  tasks: 3
  commits: 3
plan_head_before: 34c2cd0d156397e42e39aa575dacc74d498529af
plan_head_after: 71d113973ae07c7ff061b19f50e896171e4eca04
commits: 3

tech-stack:
  added: []
  patterns:
    - "ArchUnit fields().that().haveRawType(BigDecimal).and().areDeclaredInClassesThat(<..api.. 且 *Dto/*Request>).should().beAnnotatedWith(DecimalString).allowEmptyShould(false)"
    - "同一測試類別同時有 @ArchTest(ArchUnit engine)與 @Test(Jupiter engine),兩個 engine 都會執行"
    - "WebMvc 驗 wire 型別要比對 body 字面:jsonPath(...).value(x) 會把實際值轉成期望型別再比,無法區分 \"120\" 與 120"

key-files:
  created:
    - /home/user/stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/ApiDecimalStringRulesTest.java
  modified:
    - /home/user/stock-web-v2/stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/PortfolioSummaryDto.java
    - /home/user/stock-web-v2/stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/HoldingDto.java
    - /home/user/stock-web-v2/stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/TradeDto.java
    - /home/user/stock-web-v2/stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/PortfolioActivityDto.java
    - /home/user/stock-web-v2/stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/DividendDto.java
    - /home/user/stock-web-v2/stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/ShareAdjustmentDto.java
    - /home/user/stock-web-v2/stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/AllocationSliceDto.java
    - /home/user/stock-web-v2/stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/SectorAllocationDto.java
    - /home/user/stock-web-v2/stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/CreateTradeRequest.java
    - /home/user/stock-web-v2/stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/CreateDividendRequest.java
    - /home/user/stock-web-v2/stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/CreateShareAdjustmentRequest.java
    - /home/user/stock-web-v2/stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/api/BacktestRunDto.java
    - /home/user/stock-web-v2/stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/api/BacktestResultDto.java
    - /home/user/stock-web-v2/stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/api/CreateBacktestRunRequest.java
    - /home/user/stock-web-v2/stock-module-asset/src/main/java/dowob/xyz/stockwebv2/asset/api/AssetDto.java
    - /home/user/stock-web-v2/stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/api/LatestPriceDto.java
    - /home/user/stock-web-v2/stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/api/KlineDto.java
    - /home/user/stock-web-v2/stock-module-trading/src/test/java/dowob/xyz/stockwebv2/trading/api/PortfolioActivityControllerWebMvcTest.java
    - /home/user/stock-web-v2/stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/api/MarketControllerTest.java
    - /home/user/stock-web-v2/stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/PortfolioCache.java
    - /home/user/stock-web-v2/stock-module-trading/src/test/java/dowob/xyz/stockwebv2/trading/service/PortfolioCacheTest.java
    - /home/user/stock-web-v2/ai-docs/code-standards.md
    - /home/user/stock-web-v2/ai-docs/redis-convention.md
    - /home/user/stock-web-v2/ai-docs/judgment.md

key-decisions:
  - "守門 predicate:resideInAPackage(\"dowob.xyz.stockwebv2..api..\") 且 simple name 以 Dto / Request 結尾(巢狀 record 的 simple name 如 BacktestKpisDto 也命中);無例外清單(D-06)"
  - "RESEARCH Assumption A1 已由紅燈證明成立:record component 上的 @DecimalString 傳播到 private field,ArchUnit 讀 bytecode 可見(05-13 已標的三個現金 DTO 未出現在違規清單),不需改用反射"
  - "LatestPriceDto / KlineDto 由舊的 toString 式 serializer 改為 @DecimalString,行情與交易統一一般記號"
  - "快取鍵兩把同時升 v4;trading-portfolio-contract.md 的快取段落依 05-15 plan 由 05-15 改(本 plan 不碰契約文件)"

patterns-established:
  - "新增 API DTO / Request 的 BigDecimal 欄位必須標 @DecimalString,否則 ApiDecimalStringRulesTest 紅"

requirements-completed: [VER-01]

coverage:
  - id: S1
    description: "ArchUnit:..api.. 的 *Dto / *Request BigDecimal 欄位全部標 @DecimalString;改動前紅(79 違規),改動後綠"
    requirement: VER-01
    verification:
      - kind: unit
        ref: "stock-start/src/test/java/dowob/xyz/stockwebv2/start/ApiDecimalStringRulesTest.java#apiDtoBigDecimalFieldsAreDecimalStrings"
        status: pass
    human_judgment: false
  - id: S2
    description: "序列化樣本:PortfolioSummaryDto ZERO.setScale(8) → \"0.00000000\"、holdingCount number;HoldingDto roi 字串;PortfolioActivityDto ratioNew number;CreateTradeRequest 字串反序列化精確"
    requirement: VER-01
    verification:
      - kind: unit
        ref: "stock-start/src/test/java/dowob/xyz/stockwebv2/start/ApiDecimalStringRulesTest.java"
        status: pass
    human_judgment: false
  - id: S3
    description: "WebMvc:活動列表金額為 JSON 字串;行情 latest 的 0.00000001 / 0.00000000 為一般記號(不出現 E-8)"
    requirement: VER-01
    verification:
      - kind: webmvc
        ref: "stock-module-trading/src/test/java/dowob/xyz/stockwebv2/trading/api/PortfolioActivityControllerWebMvcTest.java#responseCarriesAllContractFieldsIncludingNulls; stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/api/MarketControllerTest.java#latest_smallDecimalsUsePlainNotation"
        status: pass
    human_judgment: false
  - id: S4
    description: "PortfolioCache 鍵前綴 v4,失效鍵斷言同步"
    requirement: VER-01
    verification:
      - kind: unit
        ref: "stock-module-trading/src/test/java/dowob/xyz/stockwebv2/trading/service/PortfolioCacheTest.java"
        status: pass
    human_judgment: false

duration: 20min
completed: 2026-11-11
---

# Phase 5 Plan 14: 全 API BigDecimal 字串化 + ArchUnit 守門 Summary

**把 05-13 的 `@DecimalString` 擴到 17 個 trading / backtest / asset / market-data DTO / Request 的 79 個 `BigDecimal` 欄位,以 `ApiDecimalStringRulesTest`(ArchUnit,`allowEmptyShould(false)`)擋漏標,並把持倉 / summary 快取鍵升到 v4。**

## Performance

- **Duration:** 約 20 分鐘
- **Completed:** 2026-11-11
- **Tasks:** 3/3
- **Files:** 1 新增、24 修改

## Accomplishments

- `ApiDecimalStringRulesTest`(`stock-start`):規則 `apiDtoBigDecimalFieldsAreDecimalStrings` 加 4 個以 `JsonMapper.builder().build()` 跑的序列化行為樣本;類別 Javadoc 說明 D-05 / D-06 / D-09、為什麼不用全域 Jackson 設定,也寫明不涵蓋 `PriceTickEvent`。
- 79 個欄位的標註由守門紅燈的違規清單逐項對完(script 標註數 72 + 行情 7 = 79,與違規數一致)。`LatestPriceDto` / `KlineDto` 移除舊 serializer 與相關 import;`DividendDto` 的 Javadoc「金額以 JSON number 輸出」改成字串描述。
- `PortfolioActivityControllerWebMvcTest` 的金額斷言改為字串,另加 body 字面比對;`MarketControllerTest` 新增一般記號回歸測試。
- `PortfolioCache` 的 v3 前綴改為 v4(Javadoc 版本沿革加一條、`@version 4.2`);`PortfolioCacheTest` 與三份 ai-docs 同步。

## Task Commits

1. **Task 1(tracer):ApiDecimalStringRulesTest → 17 個 DTO / Request 標註 → 規則綠**:`d5a694f`(feat)
2. **Task 2:行為樣本 + 模組 unit / WebMvc 測試修正**:`ec879e8`(test)
3. **Task 3:PortfolioCache 鍵前綴升 v4 + 快取文件同步**:`71d1139`(refactor)

## TDD / Tracer 證據

- **RED:** 新增規則後執行 `ApiDecimalStringRulesTest` → `was violated (79 times)`,清單與 RESEARCH §Q2 一致(AssetDto 5、BacktestKpisDto 8、BacktestTradeDto 4、EquityPointDto 2、MonthlyReturnDto 1、DrawdownPointDto 1、BacktestRunDto 2、CreateBacktestRunRequest 1、KlineDto 5、LatestPriceDto 2、trading 48)。05-13 已標的 `CashEntryDto` / `CashBalanceDto` / `CreateCashEntryRequest` 不在清單上,代表 record component 上的 annotation 有傳播到 field(A1 成立)。
- **GREEN:** 標註後同一條命令 `Tests run: 1, Failures: 0`,BUILD SUCCESS。
- **Tracer gate:** 在標註後的 commit 上重跑 `<verify>`,結果綠,之後才做 Task 2。
- Task 2 是在實作完成後補上的回歸樣本,沒有獨立的 RED 階段(對象是 Task 1 已經改好的行為)。

## Verification(本機實跑)

| 命令 | 結果 |
|------|------|
| `./mvnw -B -o -pl stock-start -am test -Dtest='ApiDecimalStringRulesTest' -Dsurefire.failIfNoSpecifiedTests=false` | BUILD SUCCESS(ArchUnit 1 + Jupiter 4) |
| `./mvnw -B -o -pl stock-module-trading,stock-module-market-data,stock-module-backtest,stock-module-asset,stock-start -am test --fail-at-end` | BUILD SUCCESS;common 106、infrastructure 48、user 32、asset 56、backtest 45、market-data 229、trading 466、stock-start 25,全部 0 fail / 0 error |
| `./mvnw -B -o -pl stock-module-trading -am test -Dtest='*PortfolioCache*' -Dsurefire.failIfNoSpecifiedTests=false`(先跑 v3 grep 檢查 = 0) | BUILD SUCCESS;13 tests |
| `./mvnw -B -q -o -pl stock-start -am test-compile` | exit 0 |
| `./mvnw -B -o -q spotless:check` | exit 0 |

**Acceptance:**`ToStringSerializer` 在 api DTO 中出現 0 次;`PriceTickEvent` 沒有 diff;PortfolioCache 中 v4 前綴出現 2 次;ApiDecimalStringRulesTest 中 `"0.00000000"` 出現 5 次;v3 鍵引用(模組 src 與三份文件)為 0。

**本機沒跑的部分:**`*IT` / `*E2E` 需要 Testcontainers,容器內沒有 Docker,只做了 test-compile,以 CI 結果為準。

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Task 1 的第一個 commit 漏掉 17 個 DTO 檔,已 amend**
- **Found during:** Task 2 開始前看 `git diff --stat`
- **Issue:** 我用 `git diff --name-only -- 'stock-module-*/src/main/java'` 組 staging 清單。git pathspec 裡帶 `*` 時要比對完整路徑,沒有 `/**` 結尾就一個檔都選不到,所以 `be97e33` 只收進了測試檔(這個 commit 上規則是紅的)。
- **Fix:** 改用明確路徑 stage 這 17 個檔,`git commit --amend --no-edit`。當時 HEAD 仍是自己剛建的 commit,還沒 push,中間也沒有別人的 commit。最後的 Task 1 commit 是 `d5a694f`(18 個檔)。
- **附帶:** plan acceptance 的 `git grep -l "@DecimalString" -- 'stock-module-*/src/main/java' | wc -l` 是同一個 pathspec 問題,永遠回 0。改用 `'stock-module-*/src/main/java/**'` 實測為 20(≥ 20,符合)。

**2. [範圍說明] 規劃時列為「已知受影響」的兩個測試,改動前其實沒有紅**
- `MarketControllerTest` 本來就斷言字串(`"150.50"`),而舊 serializer 對這些值的輸出和一般記號相同。`PortfolioActivityControllerWebMvcTest` 的 `.value(120)` 也會過,原因是 jsonPath 會先把字串 `"120"` 轉成期望的型別再比對。兩個檔都照 plan 改了斷言,並各自補上能證明 wire 型別的檢查:活動測試比對 body 字面;行情測試新增 `latest_smallDecimalsUsePlainNotation`,確認輸出不出現 `E-8`。其他模組測試沒有因為改字串而失敗,沒有改到 frontmatter 以外的測試檔。`PortfolioCacheTest` 的鍵斷言是 Task 3 依 plan 說明同步的。

## 預期中的過渡破壞(交給 05-15,不在本 plan 範圍)

DTO 全面字串化之後,`stock-start` 底下用 `JsonNode.decimalValue()` / `isNumber()` 讀金額的 IT / E2E 在 Docker CI 會失敗,直到 05-15 遷移完成(test-compile 不受影響)。以 grep 找到的檔案如下:`PortfolioActivityApiIT`、`PortfolioCashAllocationIT`、`PortfolioDayPnlIT`、`PortfolioDividendSummaryIT`、`DividendE2E`、`PortfolioActivityE2E`、`PortfolioBaseCurrencyE2E`、`PortfolioDayPnlE2E`、`ShareAdjustmentE2E`。jsonPath 的 `value(<number>)` 斷言有寬鬆轉型,多數仍會過;真正會失敗的範圍以 CI 為準。

另外 `ai-docs/trading-portfolio-contract.md:561` 和 `:594`/`:598`(變更紀錄)仍寫 `portfolio:valuation:v3` / `summary:v3`。05-15 Task 的 action 已明確要求「快取段落若記載 v3 改為 v4」,所以本 plan 沒動。`docs/guides/development.md:110` 是用 `portfolio:summary:v3:*` 當「升版」的舉例,不是現行鍵表,保留。

## Threat Flags

None。沒有新增 endpoint、auth 路徑或 schema。T-05-24(漏標)由 ArchUnit 規則 + `allowEmptyShould(false)` + 行為樣本緩解;T-05-25(新舊快取混讀)由 v4 前綴緩解,舊鍵 60 秒過期。

## Known Stubs

None.

## Next Phase Readiness

- 05-15:IT / E2E 的金額斷言改為字串、`decimalValue()` 改為字串解析;契約文件(含快取鍵 v4)。
- 前端 05-16 起的 `apiTypes` 要把所有 BigDecimal 欄位(含比例、回測指標、行情)當字串處理,並與本 plan 同分支合併(D-07 硬切)。

## Self-Check: PASSED

- FOUND: stock-start/src/test/java/dowob/xyz/stockwebv2/start/ApiDecimalStringRulesTest.java
- FOUND: d5a694f、ec879e8、71d1139(皆為 HEAD 祖先)
