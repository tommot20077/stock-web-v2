---
phase: 05-cross-repo-browser-flow-verification-contract-hardening
plan: 27
subsystem: testing
tags: [archunit, decimal-string, jackson, env-template, jwt-ttl, gap-closure]

requires:
  - phase: 05-cross-repo-browser-flow-verification-contract-hardening
    provides: "D-09 ApiDecimalStringRulesTest(05-0x)、D-22 AccessTokenTtlConsistencyGuard(05-05)"
provides:
  - "D-09 守門擴大:API 傳輸型別 = ..api.. 內 Dto / Request / Response / View 字尾或 record"
  - "規則 2:禁止 List / Map / Optional 型別參數或陣列藏 BigDecimal(欄位與 public 零參數方法)"
  - "規則 3:public 零參數、回傳 BigDecimal、未 @JsonIgnore 的方法必須標 @DecimalString"
  - "test 原始碼 fixture ApiDecimalStringRuleFixtures + 同一 ArchRule 物件的自我驗證"
  - ".env.example 的 JWT access TTL = PT15M,附成對說明與 cookie TTL 註解範例"
  - "EnvExampleAccessTokenTtlTest:讀 repo 根範本 + application.yaml 預設的常駐等值守門"
affects: [05-33, VER-01, T-05-24, T-05-12]

actuals:
  tokens: 6975
  tasks: 3
  commits: 4
plan_head_before: b390355b31ee17293758f076797710e42bec4296
plan_head_after: 55b38c4

tech-stack:
  added: []
  patterns:
    - "ArchUnit 規則自我驗證:fixture 放 test 原始碼 ..archfixture.api..,以 ClassFileImporter#importClasses 匯入後用正式 @ArchTest 的同一個 ArchRule 物件 evaluate"
    - "allowEmptyShould(false) 下 evaluate 空選取會丟 AssertionError(不是回傳 hasViolation),自我驗證需搭配非空的合規 fixture"

key-files:
  created:
    - stock-start/src/test/java/dowob/xyz/stockwebv2/start/archfixture/api/ApiDecimalStringRuleFixtures.java
    - stock-start/src/test/java/dowob/xyz/stockwebv2/start/config/EnvExampleAccessTokenTtlTest.java
  modified:
    - stock-start/src/test/java/dowob/xyz/stockwebv2/start/ApiDecimalStringRulesTest.java
    - .env.example

key-decisions:
  - "規則 2 以 CompositeArchRule 合成欄位與方法兩條子規則;選取範圍是 API 傳輸型別的全部欄位 / 全部 public 零參數方法,由 condition 判斷型別,選取永不因合規而為空"
  - "規則 2 的陣列判斷以 getBaseComponentType() 展開 getAllInvolvedRawTypes(),涵蓋 BigDecimal[] 與 List<BigDecimal[]>"
  - "EnvExampleAccessTokenTtlTest 以 java.util.Properties 解析範本(與 .env[.properties] 匯入的 properties 語意一致,# / ! 為註解),不自寫 KEY=VALUE 解析"
  - "現行正式碼三條 D-09 規則皆綠,無需白名單或縮小 predicate"

patterns-established:
  - "守門規則必附會紅的 fixture 自我驗證,fixture 不加 Spring stereotype、名稱不以 Test / IT / E2E 結尾"
  - "repo 根設定範本(.env.example)由 stock-start 的 unit 測試以 Path.of(\"..\", ...) 讀取守門"

requirements-completed: [VER-01]

coverage:
  - id: D1
    description: "D-09 規則 1 涵蓋 *Response / *View / 無字尾 record 的 raw BigDecimal 欄位,service 類別不在範圍"
    requirement: VER-01
    verification:
      - kind: unit
        ref: "stock-start/src/test/java/dowob/xyz/stockwebv2/start/ApiDecimalStringRulesTest.java#selfCheckUnannotatedResponseFieldIsViolation / selfCheckUnannotatedViewFieldIsViolation / selfCheckUnannotatedSuffixlessRecordFieldIsViolation / selfCheckCompliantResponseAndOutOfScopeServiceHaveNoViolation"
        status: pass
      - kind: unit
        ref: "ApiDecimalStringRulesTest @ArchTest apiTransferBigDecimalFieldsAreDecimalStrings(正式碼)"
        status: pass
    human_judgment: false
  - id: D2
    description: "D-09 規則 2 禁止 List / Map / Optional / 陣列藏 BigDecimal;規則 3 要求 BigDecimal getter 標 @DecimalString(@JsonIgnore 豁免、record accessor 由傳播滿足)"
    requirement: VER-01
    verification:
      - kind: unit
        ref: "ApiDecimalStringRulesTest#selfCheckParameterizedAndArrayBigDecimalAreViolations / selfCheckCompliantResponseDoesNotViolateParameterizedRule / selfCheckUnannotatedBigDecimalGetterIsViolation / selfCheckIgnoredGetterAndAnnotatedRecordAccessorAreNotViolations / selfCheckFieldRuleAloneMissesParameterizedTypesAndGetters"
        status: pass
      - kind: unit
        ref: "ApiDecimalStringRulesTest @ArchTest apiTransferTypesDoNotHideBigDecimalInParameterizedOrArrayTypes + apiTransferBigDecimalAccessorsAreDecimalStrings(正式碼)"
        status: pass
    human_judgment: false
  - id: D3
    description: ".env.example 的 JWT access TTL 對齊 PT15M 並註明須與 cookie TTL 成對;EnvExampleAccessTokenTtlTest 常駐守門(含合成輸入自我驗證)"
    requirement: VER-01
    verification:
      - kind: unit
        ref: "stock-start/src/test/java/dowob/xyz/stockwebv2/start/config/EnvExampleAccessTokenTtlTest.java#envExampleAccessTokenTtlsArePaired + 3 selfCheck*"
        status: pass
      - kind: other
        ref: "grep -c PT30M .env.example = 0;grep -c '^STOCK_JWT_ACCESS_TOKEN_TTL=PT15M$' = 1;STOCK_AUTH_COOKIE_ACCESS_TOKEN_TTL = 2;STOCK_JWT_REFRESH_TOKEN_TTL=P14D = 1"
        status: pass
    human_judgment: false

duration: 8min
completed: 2026-11-14
status: complete
---

# Phase 05 Plan 27: D-09 BigDecimal 守門擴大 + .env.example TTL 成對 Summary

**D-09 ArchUnit 守門由 1 條擴為 3 條(Response / View / record 欄位標註、禁止 List / Map / Optional / 陣列藏 BigDecimal、BigDecimal getter 標註),每條都有用同一 ArchRule 物件評估 test fixture 的會紅自我驗證;`.env.example` 的 JWT access TTL 改為 PT15M 並由新的 EnvExampleAccessTokenTtlTest 常駐守門。**

## Performance

- **Duration:** 約 8 分鐘(執行階段;不含讀取協議文件)
- **Started:** 2026-11-14T16:32:59Z
- **Completed:** 2026-11-14T16:40:12Z
- **Tasks:** 3 / 3
- **Files modified:** 4(2 新增、2 修改)

## Accomplishments

- **G-05-7 / BE-WR-01**:`API_TRANSFER_TYPES` 擴大為 `..api..` 內 `Dto` / `Request` / `Response` / `View` 字尾或 `isRecord()`;新增規則 2(參數化型別 / 陣列禁止,欄位 + public 零參數非 static 方法)與規則 3(BigDecimal getter 標註,`@JsonIgnore` 豁免)。三條皆 `allowEmptyShould(false)`,現行正式碼全綠——沒有實際違規,證實是純回歸守門缺口。
- 新增 test 原始碼 fixture 持有類別 `ApiDecimalStringRuleFixtures`(11 個巢狀型別,無 Spring stereotype、名稱不以 Test / IT / E2E 結尾);9 個 `selfCheck*` 測試證明每條規則對違規 fixture 會紅、對合規 fixture 不紅、service 形態類別不在範圍內,以及規則 1 單獨看不到參數化型別與 getter(規則 2 / 3 不是重複守門)。
- **G-05-10 / T-05-12**:`.env.example` 的 `STOCK_JWT_ACCESS_TOKEN_TTL=PT15M`,上方兩行繁中註解說明 D-22 成對要求,下方加註解掉的 `# STOCK_AUTH_COOKIE_ACCESS_TOKEN_TTL=PT15M` 範例;`STOCK_JWT_REFRESH_TOKEN_TTL=P14D` 未動。
- `EnvExampleAccessTokenTtlTest` 讀 `../.env.example`(找不到直接失敗、不 skip)與 `application.yaml` 的 placeholder 預設,以 `Duration#equals` 斷言兩個有效 access TTL 相等;修改範本前紅(expected 15M but was 30M)、修改後綠。

## Task Commits

1. **Task 1(tracer):D-09 API 傳輸型別擴大到 Response / View / record + fixture 自我驗證骨架** — `049e504`(test)
2. **Task 2:參數化型別 / 陣列規則 + BigDecimal getter 規則 + 其餘 fixture** — `130d304`(test)
3. **Task 3:.env.example access TTL 對齊 PT15M + EnvExampleAccessTokenTtlTest** — `55b38c4`(fix)

**Plan metadata:** 本 SUMMARY 的 docs commit。

_`actuals.commits: 4` 為 `git rev-list --count b390355..HEAD` 的量測值;其中 `5e34a4d docs(05-28)` 是並行的前端 lane 寫入後端 `.planning/` 的 SUMMARY commit,不屬本 plan。本 plan 自有 commit 為上列 3 個 + SUMMARY commit。_

## TDD 紀錄

- **Task 1 RED**:先寫 4 個 selfCheck 測試、保留舊 predicate → 4 個失敗(舊 predicate 選不到 Response / View / 無字尾 record,`allowEmptyShould(false)` 下 `evaluate` 丟 `failed to check any classes`)。**GREEN**:擴大 predicate 後 9/9 綠。
- **Task 2**:規則 2 / 3 是新規則物件,自我驗證測試與規則同 commit;RED 證據以 `selfCheckFieldRuleAloneMissesParameterizedTypesAndGetters` 常駐保存(規則 1 對 `ListAmountDto` + `GetterResponse` 選取為空)。
- **Task 3 RED**:測試先寫、範本未改 → `envExampleAccessTokenTtlsArePaired` 失敗(expected 15M but was 30M),3 個合成輸入自我驗證綠。**GREEN**:改範本後 4/4 綠。
- **Tracer 閘門**:`human_verify_mode` 預設 end-of-phase、tracer `<verify>` 只有 `<automated>`,重跑通過後進入 Task 2。

## Verification(本機)

| 命令 | 結果 |
|------|------|
| `mvn -o -B -pl stock-start -am test -Dtest='ApiDecimalStringRulesTest,ArchitectureRulesTest,E2eNamingRulesTest,EnvExampleAccessTokenTtlTest,InfraProfileDefaultsTest,AccessTokenTtlConsistencyGuardTest' -Dsurefire.failIfNoSpecifiedTests=false`(plan `<verification>`) | BUILD SUCCESS;stock-start 29 tests / 0 failures(ApiDecimalStringRulesTest 13 @Test + 3 @ArchTest、ArchitectureRulesTest 4、E2eNamingRulesTest 2、InfraProfileDefaultsTest 3、EnvExampleAccessTokenTtlTest 4),stock-module-user AccessTokenTtlConsistencyGuardTest 4 / 0 |
| `mvn -o -B spotless:check` | 綠(每個 task 後皆跑) |
| `mvn -o -B test`(全後端 Surefire unit,含所有 IT 的 test-compile) | BUILD SUCCESS;1050 tests / 0 failures(common 106、infrastructure 48、asset 56、backtest 68、market-data 229、trading 466、user 32、start 45) |
| Task 1 / 2 / 3 acceptance grep | 全部 PASS(ClassFileImporter 2、isRecord() 1、getAllInvolvedRawTypes 1、`@ArchTest` 5(3 個註解 + 2 處 Javadoc)、四個參數化 fixture 4;.env.example PT30M 0 / PT15M 行 1 / cookie TTL 2 / refresh P14D 1) |

Plan 的 verify 寫 `./mvnw`;本機以 `/opt/maven/bin/mvn -o`(離線)執行同樣的 goal 與參數,語意相同。

**Deferred to CI(05-33 收集)**:無 Docker daemon,Testcontainers 整合測試(Failsafe `*IT`)與後端 E2E 無法本機跑;本 plan 未改正式碼,這些測試只經 `mvn test` 的 test-compile 確認可編譯。CI Unit job 報告應含 `ApiDecimalStringRulesTest`、`EnvExampleAccessTokenTtlTest` 且綠。

## Files Created/Modified

- `stock-start/src/test/java/dowob/xyz/stockwebv2/start/ApiDecimalStringRulesTest.java` — predicate 擴大、規則 2 / 3、`violates()` helper、9 個 selfCheck 測試、類別 Javadoc 更新(三條規則 / 自我驗證 / 不涵蓋)
- `stock-start/src/test/java/dowob/xyz/stockwebv2/start/archfixture/api/ApiDecimalStringRuleFixtures.java` — D-09 違規 / 合規 fixture(只供自我驗證,刻意違規,不是 API)
- `stock-start/src/test/java/dowob/xyz/stockwebv2/start/config/EnvExampleAccessTokenTtlTest.java` — 範本 TTL 成對常駐守門
- `.env.example` — JWT access TTL = PT15M + D-22 成對說明 + cookie TTL 註解範例

## Decisions Made

- 規則 2 用 `CompositeArchRule` 把欄位與方法兩條子規則合成一個 `@ArchTest`,選取廣(全部欄位 / 全部 public 零參數非 static 方法)、條件窄(型別檢查),所以自我驗證只匯入合規 fixture 也不會空選取。
- 規則 2 對方法**不**豁免 `@JsonIgnore`(照 plan truth 字面,較嚴);規則 3 才有 `@JsonIgnore` 豁免。現行正式碼沒有受影響的成員。
- 範本解析用 `java.util.Properties`,與 `optional:file:.env[.properties]` 的 properties 語意一致;空值(`KEY=`)會讓 `Duration.parse` 失敗而紅,不靜默通過。

## Deviations from Plan

None - plan executed exactly as written.(唯一的執行差異是以 `/opt/maven/bin/mvn -o` 取代 `./mvnw`,屬環境替代,不是計畫偏差。)

## Issues Encountered

- ArchUnit 1.3.0 在 `allowEmptyShould(false)` 下對空選取的 `evaluate()` 是丟 `AssertionError`,不是回傳 `hasViolation() == true`。這反而讓自我驗證不可能因空選取而假綠;`IgnoredGetterResponse` 的合規案例因此與 `CompliantResponse` 一起匯入(plan 已預見此情形)。

## Known Stubs

None.

## Threat Flags

None — 只動測試碼與設定範本,沒有新的網路端點、auth 路徑或 schema 變更。T-05-39 / T-05-40 依 threat register 完成 mitigate。

## User Setup Required

None。已有本機 `.env` 且仍是舊 JWT TTL 的開發者,需自行把 `STOCK_JWT_ACCESS_TOKEN_TTL` 改為 PT15M(或同時設定 `STOCK_AUTH_COOKIE_ACCESS_TOKEN_TTL` 為同值);`.env` 不在版控內,本 plan 無法代改。

## Next Phase Readiness

- G-05-7(BE-WR-01)與 G-05-10 已關閉;05-33 收集 CI Unit job 證據即可。
- 無 blocker。

---
*Phase: 05-cross-repo-browser-flow-verification-contract-hardening*
*Completed: 2026-11-14*

## Self-Check: PASSED

- FOUND: stock-start/src/test/java/dowob/xyz/stockwebv2/start/archfixture/api/ApiDecimalStringRuleFixtures.java
- FOUND: stock-start/src/test/java/dowob/xyz/stockwebv2/start/config/EnvExampleAccessTokenTtlTest.java
- FOUND: commits 049e504、130d304、55b38c4(皆為 HEAD 祖先)
