---
phase: 05-cross-repo-browser-flow-verification-contract-hardening
plan: 06
subsystem: testing
tags: [archunit, surefire, e2e, naming, ci, docs]
status: complete

requires:
  - phase: 05-03
    provides: "README / docs/testing/overview.md 的 port 與 Browser E2E 文件修訂(本 plan 只動不同行,已保留)"
  - phase: 05-05
    provides: "application-e2e.yaml 的 access-token-ttl PT15M(本 plan 只改 :15 註解,已保留)"
provides:
  - "E2eNamingRulesTest(ArchUnit,stock-start unit job):noClassesInE2ePackageEndWithTest、concreteE2eClassesEndWithE2E,皆 allowEmptyShould(false)"
  - "7 個需要容器的 E2E 改名:WsAuthFlowE2E、WsConnectionLimitE2E、WsSubscribeReceiveE2E、WsSubscriptionLimitE2E、WsTokenRevocationE2E、MarketRestApiE2E、BackfillAdminFlowE2E(移出 unit job,改由 -Pe2e 的 E2E job 執行)"
  - "3 個抽象基底改名:AbstractStockE2E、AbstractPortfolioE2E、AbstractWsE2E(14 個既有 *E2E 的 extends / import 同步)"
  - "./mvnw test 不再需要 Docker;文件不再記載 *E2ETest 已知例外"
affects: [05-12, backend-ci-unit-job, backend-ci-e2e-job, testing-docs]

actuals:
  tokens: 10400
  tasks: 2
  commits: 2
plan_head_before: 7f3c5766c3521bd97ed84c3d51fe3f515430ff83
plan_head_after: 1f3c30ad98e8286a02bafee640b2b6a348f6db36

tech-stack:
  added: []
  patterns:
    - "以 ArchUnit 守測試命名:@AnalyzeClasses(importOptions = OnlyIncludeTests) 掃 e2e 套件,後綴規則對齊 pom.xml 的 surefire include / exclude"

key-files:
  created:
    - /home/user/stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/E2eNamingRulesTest.java
  modified:
    - /home/user/stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/(7 個 *E2ETest → *E2E,git mv)
    - /home/user/stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/support/(3 個 Abstract*E2ETest → Abstract*E2E,git mv)
    - /home/user/stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/(14 個既有 *E2E:extends / import 同步)
    - /home/user/stock-web-v2/stock-start/src/test/resources/application-e2e.yaml
    - /home/user/stock-web-v2/docs/testing/overview.md
    - /home/user/stock-web-v2/README.md
    - /home/user/stock-web-v2/.planning/codebase/TESTING.md

key-decisions:
  - "規則一 noClasses().resideInAPackage(\"..start.e2e..\").should().haveSimpleNameEndingWith(\"Test\") 不排除 abstract(D-27);規則二只對 e2e 根套件的具體頂層類別要求 E2E 後綴,support/ 的 helper(AuthE2EHelper、DatabaseCleaner、StockE2EAssertions)不在範圍"
  - "stock-start/pom.xml 不修改:預設 surefire exclude **/*E2E.java、e2e profile include **/*E2E.java 已足夠;抽象基底符合 *E2E.java 但 surefire 不執行 abstract 類別"
  - "docs/testing/overview.md 以正面敘述取代已知例外段落(e2e 套件命名規則 + E2eNamingRulesTest 守門),並把守門加入測試分層表的「架構」列"

requirements-completed: [VER-01]

duration: ~25min
completed: 2026-11-09
---

# Phase 05 Plan 06: E2E 測試命名修正與 ArchUnit 守門 Summary

**7 個需要容器的 `*E2ETest` 與 3 個抽象基底以 `git mv` 改名為 `*E2E`,新增 `E2eNamingRulesTest`(ArchUnit,`allowEmptyShould(false)`、不豁免 abstract)守住 e2e 套件命名;`./mvnw -pl stock-start -am test` 在無 Docker 下全綠,unit job 不再跑任何 E2E。**

## Performance

- **Duration:** 約 25 分鐘
- **Completed:** 2026-11-09
- **Tasks:** 2
- **Files modified:** 29(10 組改名 + 14 個 extends 同步 + 1 個新守門測試 + 4 份文件 / 設定)

## Accomplishments

- `E2eNamingRulesTest` 先紅後綠:改名前規則一列出 10 個違規(7 具體 + 3 抽象)、規則二列出 7 個;改名後 2/2 綠。
- 10 個類別以 `git mv` 改名(保留歷史),以一次性 `sed -i` 整字替換 24 個 `.java` 的 class 宣告、`extends`、`import`、`{@link}`;`git grep 'E2ETest' -- '*.java'` 為空。
- 文件與設定註解只寫新名(4 檔 `E2ETest` 出現次數 = 0;`已知例外` = 0)。

## Task Commits

1. **Task 1(tracer):E2eNamingRulesTest(先紅)→ 改名 10 個類別與全部引用 → 守門轉綠** — `c7d6e22`(test)
2. **Task 2:本機證明 unit job 不再執行 E2E + 文件同步** — `1f3c30a`(docs)

## 驗證證據(本機,無 Docker)

| 檢查 | 結果 |
|------|------|
| 改名前 `./mvnw -B -pl stock-start -am test -Dtest=E2eNamingRulesTest -Dsurefire.failIfNoSpecifiedTests=false` | `Tests run: 2, Failures: 2` — 規則一 violated 10 times、規則二 violated 7 times(RED) |
| 改名後同一命令(先 `./mvnw -B -q clean`) | `Tests run: 2, Failures: 0, Errors: 0` / `BUILD SUCCESS`(GREEN) |
| `./mvnw -B -pl stock-start -am test`(先 clean) | `BUILD SUCCESS`,EXIT=0;10 模組 reactor 共 993 tests(common 106、infrastructure 42、user 32、asset 56、backtest 45、market-data 228、trading 464、stock-start 20),全部 0 failures / 0 errors |
| `ls stock-start/target/surefire-reports/ \| grep -E 'E2E(Test)?\.xml$'` | 0 檔(基準:7 個 `*E2ETest` 因 `NoClassDefFoundError` error);stock-start 只剩 ArchitectureRulesTest、E2eNamingRulesTest、EndpointAuthorizationRulesTest、InfraProfileDefaultsTest、OpenApiExposureDefaultsTest、GlobalExceptionHandlerTest |
| `./mvnw -B -pl stock-start help:effective-pom`(預設 / `-Pe2e`) | 預設 surefire `<exclude>**/*E2E.java</exclude>`;`-Pe2e` 為 `<include>**/*E2E.java</include>` + `<excludes combine.self="override"/>` |
| `stock-start/target/test-classes` 中 `*E2E.class`(不含內部類別)以 `javap` 判斷 abstract | 24 個:3 個 abstract(Surefire 不執行)、**21 個具體類別會在 `-Pe2e` 執行**(原 14 個 + 改名 7 個) |
| `./mvnw -B spotless:check` | `BUILD SUCCESS` |
| Acceptance greps | `git ls-files … grep -c "E2ETest.java"` = 0;新名檔 = 10;`allowEmptyShould(false)` = 3(2 處程式碼 + 1 處 Javadoc);`ABSTRACT` = 1 |

## 留給 05-12 由 CI 確認

- **E2E Tests job**(`./mvnw -B -pl stock-start -am test -Pe2e`,artifact `e2e-test-reports` = `stock-start/target/surefire-reports/`):應含 21 個 `TEST-dowob.xyz.stockwebv2.start.e2e.*E2E.xml`,其中 7 個新進:`WsAuthFlowE2E`、`WsConnectionLimitE2E`、`WsSubscribeReceiveE2E`、`WsSubscriptionLimitE2E`、`WsTokenRevocationE2E`、`MarketRestApiE2E`、`BackfillAdminFlowE2E`,全部 failures=0、errors=0。這 7 個過去在 Unit job(有 Docker)跑,現在首次與其他 14 個 E2E 共用同一 JVM 的 `ContainerIT` singleton 與 `application-e2e.yaml`(05-05 已把 access TTL 改 PT15M),本機無 Docker 無法驗證,須以 CI 結果為準。
- **Unit Tests job**(`./mvnw -B test`,artifact `unit-test-reports`):不得含任何 `*E2E.xml` / `*E2ETest.xml`;應含 `TEST-dowob.xyz.stockwebv2.start.E2eNamingRulesTest.xml`(2 tests,綠)。
- **Integration Tests job**(`verify`)的 surefire 階段同樣不應出現 E2E 報告。

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] WsSubscriptionLimitE2E Javadoc 宣稱「走 surefire,於 CI Unit Tests job 執行」**
- **Found during:** Task 1(sed 替換後 `git grep 'E2ETest'` 仍命中 1 行)
- **Issue:** 該行以 `{@code E2ETest}` 描述舊執行者,改名後內容已不正確,也會讓 Task 1 的 grep 驗證不為空。
- **Fix:** 改為「類別名以 `E2E` 結尾 → 由 `-Pe2e` profile 收進 CI E2E job 執行(D-23;需要 Docker)」。
- **Files modified:** stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/WsSubscriptionLimitE2E.java
- **Commit:** c7d6e22

**2. [Rule 2 - 文件完整性] 文件補上守門的正面說明**
- **Found during:** Task 2
- **Issue:** 只刪除「已知例外」段落會讓讀者不知道命名規則已被自動化守住。
- **Fix:** `docs/testing/overview.md` 的「Surefire 與 Failsafe 的分工」補一段 e2e 命名規則與 `E2eNamingRulesTest`,測試分層表「架構」列加入該測試;`.planning/codebase/TESTING.md` 的 E2E 數量由 14 更新為 21、結構樹補 `E2eNamingRulesTest.java`。
- **Commit:** 1f3c30a

## Issues Encountered

- 撰寫 Javadoc 時,`**/*E2E.java` 內含 `*/` 會提前結束註解;改以文字描述 `*E2E.java` 樣式,未寫入任何 `\uXXXX` 跳脫。

## Known Stubs

None。

## User Setup Required

None。

## Self-Check: PASSED

- FOUND: /home/user/stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/E2eNamingRulesTest.java
- FOUND: 10 個改名檔(`git ls-files` 計數 10)
- FOUND: c7d6e22、1f3c30a(皆為 HEAD 祖先)
