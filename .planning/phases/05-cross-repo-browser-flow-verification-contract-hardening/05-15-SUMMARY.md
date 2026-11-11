---
phase: 05-cross-repo-browser-flow-verification-contract-hardening
plan: 15
subsystem: testing
tags: [bigdecimal, serialization, idempotency, integration-tests, e2e, contract, tracer]
status: complete

requires:
  - phase: 05-14
    provides: "全 API BigDecimal 欄位 @DecimalString + ApiDecimalStringRulesTest + 快取鍵 v4"
provides:
  - "TradingApiIT.stringAmountReplayReturnsExistingTrade / stringPrecisionTradeRoundTrips(字串金額冪等與精度 IT)"
  - "stock-start support/DecimalStringMatchers.decimalString(String):jsonPath 金額斷言用,要求 JSON 字串 + compareTo"
  - "stock-start 全部 IT / E2E 改用字串金額斷言(decimalValue() 歸零)"
  - "trading-portfolio-contract〈序列化格式〉字串契約(D-09)、快取鍵 v4、watchlist-contract / docs/api/reference.md 同步"
affects: [05-16, 05-25, frontend-apiTypes, ci-integration, ci-e2e]

actuals:
  tokens: 35000
  tasks: 3
  commits: 4
plan_head_before: 8349b91ae270416fc032e69434d3b8645a3d5cfd
plan_head_after: d81662a3a2d7a35f957d0fbb308adc29e08d2e35
commits: 4

tech-stack:
  added: []
  patterns:
    - "IT / E2E 金額斷言:scale 不是重點時用 jsonPath(expr, decimalString(\"995\"))(必為 String + compareTo);scale 是重點時用 equalTo(\"100.00000000\") 逐字比"
    - "JsonNode 讀金額用 asDecimal()(Jackson 3 對字串節點呼叫 decimalValue() 會丟 JsonNodeException)"

key-files:
  created:
    - /home/user/stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/support/DecimalStringMatchers.java
    - /home/user/stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/support/DecimalStringMatchersTest.java
  modified:
    - /home/user/stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/TradingApiIT.java
    - /home/user/stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/AssetApiIT.java
    - /home/user/stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/DividendApiIT.java
    - /home/user/stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/PortfolioActivityApiIT.java
    - /home/user/stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/PortfolioCashAllocationIT.java
    - /home/user/stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/PortfolioDayPnlIT.java
    - /home/user/stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/PortfolioDividendSummaryIT.java
    - /home/user/stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/ShareAdjustmentApiIT.java
    - /home/user/stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/CashLedgerE2E.java
    - /home/user/stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/DividendE2E.java
    - /home/user/stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/PortfolioActivityE2E.java
    - /home/user/stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/PortfolioBaseCurrencyE2E.java
    - /home/user/stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/PortfolioDayPnlE2E.java
    - /home/user/stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/ShareAdjustmentE2E.java
    - /home/user/stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/ValidationBoundaryE2E.java
    - /home/user/stock-web-v2/ai-docs/trading-portfolio-contract.md
    - /home/user/stock-web-v2/ai-docs/watchlist-contract.md
    - /home/user/stock-web-v2/docs/api/reference.md

key-decisions:
  - "既有計算欄位(損益、匯率折算、市值)的斷言用 decimalString(型別 + compareTo),不猜後端 scale;只有新案例中直接來自 NUMERIC(24,8) 回寫的值(交易 quantity / price / fee、持倉 totalQuantity)用逐字 equalTo"
  - "ValidationBoundaryE2E 的交易 body 改送字串,讓上限 / 小數位驗證走前端實際送的字串路徑;JSON number 相容性由其他仍送 number 的 IT 覆蓋(不拒收)"
  - "契約欄位表的 BigDecimal 型別全部改為 string(含比率);int 計數欄位(holdingCount / fxExcludedCount / dayPnlExcludedCount)與 Integer ratioNew / ratioOld 維持 number"
  - "變更紀錄中的歷史 v3 敘述保留(屬歷史);現行快取鍵段落改為 v4"

patterns-established:
  - "新 IT / E2E 對 API 金額欄位一律用 decimalString 或逐字字串,不得用 number 字面或 decimalValue()"

requirements-completed: [VER-01]

coverage:
  - id: S1
    description: "同一 Idempotency-Key 先送 \"10\"/\"100\"、再送 \"10.00000000\"/\"100.00000000\" → 同一 id、交易一列、持倉 totalQuantity \"10.00000000\"(只套用一次);回應 fee 為 \"0.00000000\""
    requirement: VER-01
    verification:
      - kind: integration
        ref: "stock-start/src/test/java/dowob/xyz/stockwebv2/start/TradingApiIT.java#stringAmountReplayReturnsExistingTrade"
        status: pending-ci
    human_judgment: false
  - id: S2
    description: "字串價格 \"123456.12345678\" 在 POST 回應與 GET /trades 逐字相同"
    requirement: VER-01
    verification:
      - kind: integration
        ref: "stock-start/src/test/java/dowob/xyz/stockwebv2/start/TradingApiIT.java#stringPrecisionTradeRoundTrips"
        status: pending-ci
    human_judgment: false
  - id: S3
    description: "decimalString matcher 接受不同 scale 的字串、拒絕數值相等的 JSON number / 不同數值 / null,可用於 hasItem 過濾路徑"
    requirement: VER-01
    verification:
      - kind: unit
        ref: "stock-start/src/test/java/dowob/xyz/stockwebv2/start/support/DecimalStringMatchersTest.java"
        status: pass
    human_judgment: false
  - id: S4
    description: "stock-start IT / E2E 全部改為字串金額斷言;decimalValue() 在 stock-start 測試中為 0;test-compile 綠"
    requirement: VER-01
    verification:
      - kind: compile
        ref: "./mvnw -B -q -o -pl stock-start -am test-compile"
        status: pass
      - kind: integration
        ref: "CI Integration + E2E jobs(05-25)"
        status: pending-ci
    human_judgment: false
  - id: S5
    description: "契約文件改為字串契約:無 **JSON number** 金額規則、無「請以數值解析」、無 M-7;含 ApiDecimalStringRulesTest;快取鍵 v4"
    requirement: VER-01
    verification:
      - kind: doc-grep
        ref: "ai-docs/trading-portfolio-contract.md, docs/api/reference.md"
        status: pass
    human_judgment: false

duration: 35min
completed: 2026-11-11
---

# Phase 5 Plan 15: IT / E2E 字串金額遷移、交易字串冪等證據與字串契約文件 Summary

**`TradingApiIT` 新增兩個案例:字串金額冪等重送(`"10"` 與 `"10.00000000"` 視為同一筆,持倉只套用一次)和 15 位有效數字的價格精度回歸。stock-start 底下 15 個 IT / E2E 檔改成字串金額斷言,`decimalValue()` 從 175 處降到 0。`trading-portfolio-contract.md` 的序列化格式改寫為「BigDecimal 一律字串」(D-09),快取鍵改為 v4。**

## Performance

- **Duration:** 約 35 分鐘
- **Completed:** 2026-11-11
- **Tasks:** 3/3
- **Files:** 新增 2 個、修改 18 個

## Accomplishments

- 新增 `support/DecimalStringMatchers.decimalString(String)`,用在 jsonPath 斷言:實際值必須是 `String`,數值以 `compareTo` 比對。`jsonPath(...).value(120)` 會先把實際值轉型再比,分不出 `"120"` 和 `120`;改寫成 `equalTo("…")` 則每個計算欄位都得猜 scale。這個 matcher 同時避開這兩個問題。
- `TradingApiIT`:新增 `stringAmountReplayReturnsExistingTrade` 和 `stringPrecisionTradeRoundTrips`,並把 32 處 number 金額斷言(含 `hasItem`)改用 `decimalString`。仍以 JSON number 送出 body 的既有案例保留,作為「請求端不拒收 number」的證據。
- 9 個檔的 `decimalValue()` 一律改成 `asDecimal()`。`AssetApiIT`、`DividendApiIT`、`PortfolioActivityApiIT`、`ShareAdjustmentApiIT`、`CashLedgerE2E`、`DividendE2E` 的 number 斷言改為 `decimalString`。`ValidationBoundaryE2E` 的交易 body 改送字串金額。
- 契約文件:`trading-portfolio-contract.md` 的序列化格式節重寫(包含請求端、行情、機制與守門三段),欄位表內 51 列 BigDecimal 欄位的型別由 number 改為 string,選填金額註明送 `null`,快取段落改為 v4,並新增 2026-11-11 變更紀錄。`docs/api/reference.md` 的兩處說明、`watchlist-contract.md` 的報價欄位也一併更新。

## Task Commits

1. **Task 1(tracer):TradingApiIT 字串金額冪等重送 + 精度回歸 + 交易 IT 字串斷言**:`618a424`(test)
2. **Task 2:其餘 IT / E2E 斷言窮舉遷移**:`47b6bfc`(test)
3. **Task 3:契約與 API 參考文件改為字串契約(D-09)**:`9077eec`(docs)
4. **補正:`DecimalStringMatchers` / 測試類別 Javadoc 補 `@author Yuan` / `@version 1.0`(code-standards)**:`d81662a`(style)

## TDD / Tracer 證據

- **IT 本身無法在本機 RED:** 容器裡沒有 Docker。字串化的實作已在 05-13 / 05-14 完成,所以 Task 1 的兩個 IT 是回歸鎖,執行證據要等 CI Integration job(05-25)。
- **matcher 的 RED 判別力(本機實跑):** `DecimalStringMatchersTest.rejectsJsonNumberEvenWhenNumericallyEqual` 證明,舊的 wire 格式(`{"amount":120.0}`)會讓 `decimalString("120")` 以 "was not a JSON string" 失敗。所以遷移後的斷言在後端退回 number 時會紅,不會靜默通過。
- **Tracer gate:** Task 1 commit 之後重跑 `<verify>`(`test-compile`)結果綠,接著才做 Task 2。

## Verification(本機實跑)

| 命令 | 結果 |
|------|------|
| `./mvnw -B -q -o -pl stock-start -am test-compile` | exit 0(Task 1、Task 2 各跑一次) |
| `./mvnw -B -o -pl stock-start -am test -Dtest='DecimalStringMatchersTest' -Dsurefire.failIfNoSpecifiedTests=false` | BUILD SUCCESS,5 tests,0 fail |
| `./mvnw -B -o -pl stock-start -am test --fail-at-end`(全部非 IT 單元 / WebMvc / ArchUnit) | BUILD SUCCESS:common 106、infrastructure 48、user 32、asset 56、backtest 45、market-data 229、trading 466、stock-start 30(上次 25,本次加 5),全部 0 fail / 0 error |
| `./mvnw -B -q -o spotless:check` | exit 0 |
| Task 2 verify:`git grep -c 'decimalValue()' -- stock-start/src/test` 加總 | 0 |
| Task 3 verify:兩份文件中 `**JSON number**` 與 `請以數值解析` 的出現次數 | 皆為 0 |
| Acceptance:`grep -c "M-7" ai-docs/trading-portfolio-contract.md` / `grep -c ApiDecimalStringRulesTest` | 0 / 2 |
| Acceptance:TradingApiIT 中 `stringAmountReplayReturnsExistingTrade\|stringPrecisionTradeRoundTrips` 出現次數 | 2 |

**本機沒跑的部分:** 所有 `*IT`、`*E2E`(Testcontainers 需要 Docker),只做了 test-compile。結果以 CI Integration 與 E2E job(05-25)為準。

## 窮舉檢查表

### 指令 1:`git grep -nE 'decimalValue\(\)|isNumber\(\)|numberValue\(\)|doubleValue\(\)' -- stock-start/src/test`

**遷移前:178 處命中**,其中 175 處是 `decimalValue()`,3 處是 `doubleValue()`。各檔分布:

| 檔案 | 命中數 | 處理 |
|------|------|------|
| PortfolioActivityApiIT | 2 | → `asDecimal()` |
| PortfolioCashAllocationIT | 26 | → `asDecimal()` |
| PortfolioDayPnlIT | 14 | → `asDecimal()` |
| PortfolioDividendSummaryIT | 17 | → `asDecimal()` |
| e2e/DividendE2E | 29 | → `asDecimal()` |
| e2e/PortfolioActivityE2E | 1 | → `asDecimal()` |
| e2e/PortfolioBaseCurrencyE2E | 50(47 處 decimalValue + 3 處 doubleValue) | decimalValue → `asDecimal()`;doubleValue 保留(見下) |
| e2e/PortfolioDayPnlE2E | 16 | → `asDecimal()` |
| e2e/ShareAdjustmentE2E | 23 | → `asDecimal()` |

遷移前 `isNumber()` / `numberValue()` 皆為 0 處。

**遷移後:3 處,都不是 JSON 讀取:**
```
e2e/PortfolioBaseCurrencyE2E.java:116  weightSum(summary.get("allocation")).doubleValue()
e2e/PortfolioBaseCurrencyE2E.java:120  weightSum(summary.get("sectorAllocation")).doubleValue()
e2e/PortfolioBaseCurrencyE2E.java:168  weightSum(usd.get("allocation")).doubleValue()
```
`weightSum` 是測試內的 helper,回傳 `BigDecimal`(內部已改用 `asDecimal()` 加總),所以這裡呼叫的是 `BigDecimal.doubleValue()`,不是 `JsonNode`。

### 指令 2:plan 的 jsonPath 金額欄位 regex

**遷移前:63 處命中**(AssetApiIT 8、CashEntryApiIT 5、DividendApiIT 4、PortfolioActivityApiIT 13、ShareAdjustmentApiIT 12、TradingApiIT 16、BacktestE2E 1、CashLedgerE2E 3、DividendE2E 1)。

**regex 漏掉、改用另一條 grep 補出的命中**(欄位名大寫開頭,例如 `totalQuantity`、`marketValue`;或用 `hasItem(...)` 包住):TradingApiIT 的 `totalQuantity` / `marketValueInBase` / `totalMarketValue` 共 11 處、`hasItem(<number>)` 5 處,ShareAdjustmentApiIT 的 `totalQuantity` 5 處。補查指令是 `git grep -nE 'jsonPath\(' | grep -E '(equalTo|value|...)\(\s*-?[0-9]'` 和 `(contains|hasItems?|...)\([^)]*[0-9]+\.[0-9]`。以上全部已改。

**遷移後:31 處命中,逐條說明為非 number 金額斷言:**

| 位置 | 為何保留 |
|------|------|
| AssetApiIT:64 `volumeText equalTo("3.0K")` | 字串欄位 |
| AssetApiIT:72-73、PortfolioActivityApiIT:82/94/182/183、ShareAdjustmentApiIT:92/93 `.value(nullValue())` | 斷言為 null |
| CashEntryApiIT:83/111/126/132/149 | 05-13 已改為逐字字串 |
| PortfolioActivityApiIT:83/84、ShareAdjustmentApiIT:124/125 `ratioNew` / `ratioOld` | `Integer`,維持 number |
| TradingApiIT:1021-1028/1063/1064/1071 | 本 plan 新案例,逐字字串 |
| BacktestE2E:59 `equityCurve.length()` | 陣列長度 |

另外確認過:`asInt()` / `asLong()` 只用在 `holdingCount`、`fxExcludedCount`、`dayPnlExcludedCount`、`ratioNew` / `ratioOld`、`totalElements` / `totalPages`、`jobExecutionId`、WS 上限、`id`,全部是整數欄位。`WatchlistApiIT:149-150` 是兩個 `AssetDto` 字串節點直接比較,同一個 serializer,不受影響。JSON 中沒有任何 `jsonPath(...).isNumber()`。

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - 正確性] 新增 frontmatter 以外的測試 support 檔**
- **Found during:** Task 1
- **Issue:** 如果把 number 斷言機械地換成 `equalTo("<scale 8 字串>")`,就得猜每個計算欄位的 scale(乘法和匯率折算之後不保證是 8),在本機跑不了 IT 的情況下,CI 很可能大量誤紅。plan 的替代寫法 `isA(String.class)` 加 compareTo,每處都要拆成兩條斷言。
- **Fix:** 新增 `support/DecimalStringMatchers` 與 `DecimalStringMatchersTest`(5 個單元測試,本機綠)。
- **Commit:** `618a424`

**2. [Rule 1 - 窮舉] frontmatter 未列的兩個 IT 也有 number 金額斷言**
- **Found during:** Task 2 的窮舉 grep
- **Issue:** `DividendApiIT`(4 處 `equalTo(120.00000000)` 之類)和 `ShareAdjustmentApiIT`(12 處 `quantityDelta` / `totalQuantity` / `avgCost`)不在 plan 的 files_modified 裡。欄位字串化後,這些 Hamcrest `equalTo(Double)` 比對 `String` 一定會失敗。
- **Fix:** 改為 `decimalString`。
- **Commit:** `47b6bfc`

**3. [Rule 2 - 文件一致] watchlist-contract.md 的報價欄位型別**
- **Found during:** Task 3,掃描其他文件時發現
- **Issue:** `latestPrice` / `change` / `changePercent` / `high` / `low` 仍寫 `number \| null`,但 `WatchlistDto` 用的是 `AssetDto`,05-14 已經改成字串。
- **Fix:** 型別改為 `string \| null`,並新增一條變更紀錄。
- **Commit:** `9077eec`

### 範圍說明

- frontmatter 列了 `ValidationBoundaryE2E`,但它沒有任何回應金額斷言(只有 `fields.*` 的 `exists()`)。本 plan 把它的交易 body 改成送字串(`fee` 送 `"0"`),上限 / 小數位驗證因此改走契約的字串路徑。超過上限的值改用字串送出,同樣會在 `@DecimalMax` / `@Digits` 擋下回 400。
- `"" → null` 的說法(契約「選填金額不要送 `""`」)已用 Jackson 3.1.0 預設 `JsonMapper` 實測:`{"fee":""}` 反序列化結果為 `fee=null`。
- `docs/reviews/2026-09-02-*` 裡的 M-7 / JSON number 敘述是歷史審查紀錄,保留不改。契約變更紀錄中舊條目提到的 `v3` 屬於歷史,同樣保留;只有現行的快取鍵段落(〈快取與一致性〉兩處)改為 v4。

## CI 殘餘風險(05-25 要看)

- `stringAmountReplayReturnsExistingTrade` 對首次回應斷言 `$.data.fee == "0.00000000"`。依據是 `TradingService.createTrade` 以 `insertTransactionIfAbsent` 的 `RETURNING` 列組 DTO(`TradingService.java:193`,已讀碼確認),而 PgJDBC 讀 `NUMERIC(24,8)` 的 0 回傳 scale 8。這點只讀碼確認、沒有實跑;萬一 CI 回 `"0"`,改用 `decimalString("0")` 即可。持倉 `totalQuantity == "10.00000000"` 依賴持倉列 scale 8 經 Redis v4 快取後保持不變,同屬逐字比對,風險相同。
- `asDecimal()` 對 `NullNode` 不會像 `decimalValue()` 一樣丟例外。如果某個 E2E 原本靠「欄位為 null 時丟例外」來失敗,現在會改讀成預設值。grep 檢查過,這些檔對可為 null 的欄位都是先用 `isNull()` 判斷,沒有找到這種依賴,但沒辦法以實跑證明。
- `ValidationBoundaryE2E` 改送字串之後,`"1000000000000000"` 等超限值依然走 Bean Validation。如果某個字串值在 Jackson 層就失敗(例如超過 StreamReadConstraints 的數字長度上限,目前的值遠小於上限),錯誤會變成 `VALIDATION_FAILED` 但不帶 `fields`,`fields.quantity` 的斷言就會紅。

## Threat Flags

None。沒有新增 endpoint、auth 路徑或 schema。T-05-26(用不同 scale 的字串金額做冪等竄改)由 `stringAmountReplayReturnsExistingTrade` 鎖住 `compareTo` 語意,持倉只套用一次(CI 待證)。

## Known Stubs

None.

## Next Phase Readiness

- 後端 Wave 2(05-13 ~ 05-15)完成:wire 格式、守門、IT / E2E 斷言、契約文件都已對齊字串契約。CI Integration / E2E job 的綠燈是 05-25 的閘門證據。
- 前端 05-16 起:`apiTypes` 依新的 `trading-portfolio-contract`〈序列化格式〉處理。請求端送字串,選填金額送 `null`。

## Self-Check: PASSED

- FOUND: stock-start/src/test/java/dowob/xyz/stockwebv2/start/support/DecimalStringMatchers.java
- FOUND: stock-start/src/test/java/dowob/xyz/stockwebv2/start/support/DecimalStringMatchersTest.java
- FOUND: 618a424、47b6bfc、9077eec、d81662a(皆為 HEAD 祖先)
