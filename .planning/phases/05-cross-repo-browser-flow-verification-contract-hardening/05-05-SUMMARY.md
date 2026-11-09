---
phase: 05-cross-repo-browser-flow-verification-contract-hardening
plan: 05
subsystem: auth
tags: [jwt, auth-cookie, csrf, startup-guard, spring-boot, docs]

requires: []
provides:
  - "AccessTokenTtlConsistencyGuard:access cookie TTL ≠ JWT access TTL 時拒絕啟動(靜態訊息)"
  - "stock.jwt.access-token-ttl 預設 PT15M(application.yaml / application-demo.yaml / test / e2e profile)"
  - "browser-auth-contract.md:TTL 必須相等、有效 stock_access cookie 下 login / register 需 CSRF、Phase 5 測試名、變更紀錄"
  - "持久層用詞(手寫 Jdbc*Repository + JdbcClient)與 Vite proxy port(VITE_API_PROXY_TARGET,預設 11180)的後端文件更正"
affects: [05-04, 05-12, browser-auth-contract, configuration-docs]

actuals:
  tokens: 7653
  tasks: 3
  commits: 3
plan_head_before: 2d26315f3ff44c0d7911430dd82d62c3d24fa09f
plan_head_after: 51f69ede8d96a56b69eb573b3b10bf997eb71ca5

tech-stack:
  added: []
  patterns:
    - "設定一致性啟動守衛:@Component 建構子比對兩個 @ConfigurationProperties,不一致丟 IllegalStateException(靜態訊息、不回射值、無 profile 豁免)"

key-files:
  created:
    - stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/service/AccessTokenTtlConsistencyGuard.java
    - stock-module-user/src/test/java/dowob/xyz/stockwebv2/user/service/AccessTokenTtlConsistencyGuardTest.java
  modified:
    - stock-start/src/main/resources/application.yaml
    - stock-start/src/main/resources/application-demo.yaml
    - stock-start/src/test/resources/application-test.yaml
    - stock-start/src/test/resources/application-e2e.yaml
    - ai-docs/browser-auth-contract.md
    - docs/configuration/overview.md
    - ai-docs/architecture.md
    - AGENTS.md
    - .planning/codebase/STACK.md
    - .planning/codebase/ARCHITECTURE.md
    - .planning/codebase/INTEGRATIONS.md

key-decisions:
  - "D-22 守門方式選啟動守衛(AccessTokenTtlConsistencyGuard),比對 BrowserAuthCookieProperties 正規化後的 accessTokenTtl(),不設 profile 豁免"
  - "守衛例外訊息固定為 'STOCK_AUTH_COOKIE_ACCESS_TOKEN_TTL must equal STOCK_JWT_ACCESS_TOKEN_TTL',測試斷言不含 PT30M / PT15M / 30 / 15"

patterns-established:
  - "設定一致性守衛比照 BrowserAuthCookieSecureGuard:同 package、@Component、建構子檢查、純單元測試直接 new properties record"

requirements-completed: [VER-01]

coverage:
  - id: D1
    description: "AccessTokenTtlConsistencyGuard 在兩個 access TTL 不相等時拒絕啟動,訊息只指名兩個環境變數"
    requirement: "VER-01"
    verification:
      - kind: unit
        ref: "stock-module-user/src/test/java/dowob/xyz/stockwebv2/user/service/AccessTokenTtlConsistencyGuardTest.java (4 cases)"
        status: pass
      - kind: unit
        ref: "./mvnw -B -pl stock-module-user -am test -Dtest='AccessTokenTtlConsistencyGuardTest,BrowserAuthCookieSecureGuardTest' -Dsurefire.failIfNoSpecifiedTests=false"
        status: pass
    human_judgment: false
  - id: D2
    description: "四份 yaml 的 JWT access TTL 對齊 PT15M,所有 IT / E2E context 仍能啟動"
    requirement: "VER-01"
    verification:
      - kind: other
        ref: "grep -rh PT30M stock-start/src/main/resources stock-start/src/test/resources | grep -v '^\\s*#' | wc -l == 0"
        status: pass
      - kind: integration
        ref: "CI Integration / E2E jobs(本機無 Docker daemon,由 05-12 記錄)"
        status: unknown
    human_judgment: true
    rationale: "Testcontainers IT / E2E 本機無法執行(docker info 失敗),context 啟動證據只能來自 CI(05-12)"
  - id: D3
    description: "browser-auth-contract.md / configuration overview / yaml 註解反映 TTL 規則、有效 cookie 下 login 需 CSRF、JwkKeyConverter 正確指令、demo 檔頭 STOCK_AUTH_COOKIE_SECURE"
    verification:
      - kind: other
        ref: "grep -c AccessTokenTtlConsistencyGuard ai-docs/browser-auth-contract.md (3); grep -c loader.main application.yaml (2); grep -c STOCK_AUTH_COOKIE_SECURE application-demo.yaml (2); grep -c loginWithValidAccessCookieRequiresCsrf (2); PT30M / 兩個獨立設定 in overview.md (0 / 0)"
        status: pass
    human_judgment: false
  - id: D4
    description: "architecture.md / AGENTS.md / codebase 摘要的持久層用詞與 Vite proxy port 更正"
    verification:
      - kind: other
        ref: "grep -c 'Spring Data JDBC' ai-docs/architecture.md == 1; SERVER_PORT=8080 in AGENTS.md+STACK.md == 0; 11180 in AGENTS.md (2) / STACK.md (2); 'Aggregate Root Boundaries (Spring Data JDBC)' == 0"
        status: pass
    human_judgment: false

duration: 12min
completed: 2026-11-09
status: complete
---

# Phase 05 Plan 05: Access token TTL 對齊與契約 / 設定文件一致性 Summary

**JWT access TTL 預設由 PT30M 改為 PT15M 並以 `AccessTokenTtlConsistencyGuard` 啟動守衛強制與 cookie TTL 相等;契約補「有效 `stock_access` cookie 下 login / register 需 CSRF」,並更正 JwkKeyConverter 指令、持久層用詞與 Vite proxy port 敘述。**

## Performance

- **Duration:** 約 12 min
- **Started:** 2026-11-09T14:39Z(約)
- **Completed:** 2026-11-09T14:51Z
- **Tasks:** 3 / 3
- **Files modified:** 13(新增 2、修改 11)

## Accomplishments

- `AccessTokenTtlConsistencyGuard`(`stock-module-user`,`@Component`):`BrowserAuthCookieProperties.accessTokenTtl()`(正規化後)≠ `JwtProperties.accessTokenTtl()` 時丟 `IllegalStateException("STOCK_AUTH_COOKIE_ACCESS_TOKEN_TTL must equal STOCK_JWT_ACCESS_TOKEN_TTL")`;無 profile 豁免。4 個單元測試(TDD:先紅 — 編譯失敗 cannot find symbol,後綠)。
- `application.yaml`、`application-demo.yaml`(含檔頭註解)、`application-test.yaml`、`application-e2e.yaml` 的 JWT access TTL 全部改 `PT15M`,與守衛同一個 commit(Pitfall 2)。`application-e2e-browser.yaml` 兩者皆 PT2H,未改。
- `browser-auth-contract.md`:端點表 login / register 加註;Auth Cookies 新增「Access token TTL must match」小節(理由、守衛、無豁免);CSRF 節補有效 / 失效 cookie 行為與 `apiClient` 行為不變;Verification 節補 `BrowserAuthCookieServiceTest`、`AccessTokenTtlConsistencyGuardTest`、`BrowserAuthFlowIT.*`、`TradingApiIT.cookieAuthenticatedTradeWithoutCsrfIsRejected`、`MethodSecurityDenialIT`;新增 Change Log 2026-11 條目。
- `docs/configuration/overview.md`:JWT TTL 預設 PT15M、兩 TTL 必須相等、啟動失敗表新增守衛列。
- `application-demo.yaml` 檔頭補 `STOCK_AUTH_COOKIE_SECURE`;`application.yaml` JwkKeyConverter 註解改為 `PropertiesLauncher` + `-Dloader.main`(與 Javadoc / `JwtService` 訊息一致)。
- `ai-docs/architecture.md`、`AGENTS.md`、`.planning/codebase/{STACK,ARCHITECTURE,INTEGRATIONS}.md`:持久層改述為手寫 `Jdbc*Repository` + `JdbcClient`(market-data 批次寫入 `NamedParameterJdbcTemplate`);Vite proxy 改為 `$VITE_API_PROXY_TARGET`(預設 `http://localhost:11180`),刪除 `SERVER_PORT=8080` 注意事項。

## Task Commits

1. **Task 1(tracer):AccessTokenTtlConsistencyGuard + 四份 yaml 對齊 PT15M** — `eb0bc29` (feat)
2. **Task 2:契約與設定文件** — `d19557e` (docs)
3. **Task 3:持久層用詞 + Vite port 敘述** — `51f69ed` (docs)

## Verification Evidence

- `./mvnw -B -pl stock-module-user -am test -Dtest='AccessTokenTtlConsistencyGuardTest,BrowserAuthCookieSecureGuardTest' -Dsurefire.failIfNoSpecifiedTests=false` → `AccessTokenTtlConsistencyGuardTest` Tests run: 4、`BrowserAuthCookieSecureGuardTest` Tests run: 3,Failures 0 / Errors 0,BUILD SUCCESS。
- RED 證據:實作前同指令 → `COMPILATION ERROR ... cannot find symbol class AccessTokenTtlConsistencyGuard`。
- 非註解 `PT30M` 於 profile yaml:0(baseline 4)。
- `./mvnw -B -q spotless:check` → exit 0;`./mvnw -B -q -pl stock-start -am test-compile` → exit 0。
- Task 2 / Task 3 的 verify 與 acceptance grep 全數 PASS(數值見 coverage D3 / D4)。
- 宣稱的實作事實已查證:`SecurityConfig.BrowserCsrfFilter` 以 `JwtAuthenticationFilter` 設的 `AUTH_TRANSPORT_ATTRIBUTE == "cookie"` 判斷是否強制 CSRF;main 原始碼無 Spring Data repository 介面、13 個檔案使用 `JdbcClient`、`MarketPriceRepository` 使用 `NamedParameterJdbcTemplate`。

## Decisions Made

- 守門方式選啟動守衛而非測試(D-22 交由 planner 選,plan 指定守衛);比對正規化後的 cookie TTL,cookie 未設定時視為 15 分鐘。
- 除 plan 指定外,在 `application.yaml` 的 `stock.jwt.access-token-ttl` 上方加一行註解指明必須相等(與守衛同義,幫助部署者)。

## Deviations from Plan

None - plan executed exactly as written.(Task 2 額外在 `application.yaml` JWT TTL 上方加一行說明註解、契約 Auth Cookies 節補一段 demo 預設 `secure=true` 的說明,皆在 Task 2 檔案範圍內,屬文件補強。)

## Issues Encountered

- 本機無 Docker daemon(`docker info` 失敗):Testcontainers IT / E2E 無法執行;stock-start 無 surefire 層的 `@SpringBootTest`,因此「所有 context 帶守衛仍能啟動」的證據只能由 CI Integration / E2E job 提供(05-12 記錄)。已確認 java 測試中沒有任何 `DynamicPropertySource` / `@TestPropertySource` 覆寫兩個 access TTL。
- 契約引用的 05-04 測試名(`loginWithValidAccessCookieRequiresCsrf`、`registerWithValidAccessCookieRequiresCsrf`、`cookieLogoutRevokesAccessCookieForMe`、`cookieAuthenticatedTradeWithoutCsrfIsRejected`、`BrowserAuthCookieServiceTest`)在本 plan 執行時尚未存在於程式碼(05-04 尚未執行),名稱取自 05-04-PLAN。05-04 落地時須沿用同名,否則契約需同步。

## Next Phase Readiness

- 05-04 須以契約中列出的測試名實作;05-12 須記錄 CI Integration / E2E 沒有 `AccessTokenTtlConsistencyGuard` 的 ApplicationContext failure。

## Self-Check: PASSED

- FOUND: stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/service/AccessTokenTtlConsistencyGuard.java
- FOUND: stock-module-user/src/test/java/dowob/xyz/stockwebv2/user/service/AccessTokenTtlConsistencyGuardTest.java
- FOUND commits: eb0bc29、d19557e、51f69ed(皆為 HEAD 祖先)
