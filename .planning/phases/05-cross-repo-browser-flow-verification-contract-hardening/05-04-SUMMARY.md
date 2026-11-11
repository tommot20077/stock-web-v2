---
phase: 05-cross-repo-browser-flow-verification-contract-hardening
plan: 04
subsystem: auth-testing
tags: [csrf, cookie, logout, method-security, envelope, VER-01]
status: complete
requires:
  - 05-05 寫入 browser-auth-contract.md §CSRF 補述與 §Verification 測試名稱
provides:
  - SC1 稽核缺口 G1 ~ G5 的測試證據(供 05-12 05-COVERAGE-AUDIT.md 標為「已補」)
affects:
  - 05-12(稽核表、CI Integration job run id 記錄)
tech-stack:
  added: []
  patterns:
    - "MockHttpServletResponse 讀 Set-Cookie header 驗 ResponseCookie 屬性"
    - "IT 以 stock_access cookie(無 Authorization)觸發 BrowserCsrfFilter"
key-files:
  created:
    - stock-module-user/src/test/java/dowob/xyz/stockwebv2/user/service/BrowserAuthCookieServiceTest.java
  modified:
    - stock-start/src/test/java/dowob/xyz/stockwebv2/start/BrowserAuthFlowIT.java
    - stock-start/src/test/java/dowob/xyz/stockwebv2/start/TradingApiIT.java
    - stock-start/src/test/java/dowob/xyz/stockwebv2/start/MethodSecurityDenialIT.java
decisions:
  - "G5 的 403 碼確認為 AUTH_FORBIDDEN:GlobalExceptionHandler 對 AccessDeniedException 原樣重拋,由 SecurityConfig.ApiSecurityErrorWriter.accessDeniedHandler 寫出"
  - "G4 的 401 碼沿用 LogoutInvalidatesAccessTokenIT 的 AUTH_INVALID_CREDENTIALS(token version 不符)"
  - "G2 以 cookie GET /trades totalElements=0 加 transactions 資料表列數=0 雙重確認未寫入,避免依賴 bearer 路徑"
metrics:
  duration: "約 15 分鐘"
  completed: 2026-11-09
estimate:
  tokens: 50000
  tasks: 2
actuals:
  tokens: 4758
  tasks: 2
  commits: 2
plan_head_before: babc355a9fa539d3b8def01c7eaa464356b55e6b
plan_head_after: f617f4d50d1dfb2bb18d09a5464ed17b47de0a29
---

# Phase 05 Plan 04: SC1 auth cookie / CSRF / 登出 / 403 信封缺口補測 Summary

新增 `BrowserAuthCookieServiceTest` 單元測試鎖住 Set-Cookie 屬性(G1),並在三個既有 IT 補上 cookie 認證缺 CSRF 的 403(trades、login、register)、cookie 登出後舊 access cookie 打 /me 的 401 信封,以及方法層拒絕的 403 信封(G2 ~ G5);正式程式未改動。

## 完成的任務

| Task | 名稱 | Commit | 檔案 |
|------|------|--------|------|
| 1(tracer) | G1 BrowserAuthCookieServiceTest | c50e47a | `stock-module-user/.../service/BrowserAuthCookieServiceTest.java`(新) |
| 2 | G2 ~ G5 IT 補測 | f617f4d | `BrowserAuthFlowIT.java`、`TradingApiIT.java`、`MethodSecurityDenialIT.java` |

### 缺口對照

| 缺口 | 測試 | 斷言重點 |
|------|------|----------|
| G1 | `BrowserAuthCookieServiceTest`(4 個 `@Test`) | access `Max-Age=900`、refresh `Max-Age=1209600`、HttpOnly、`SameSite=Lax`、`Path=/`;secure=false 不輸出 `Secure`、domain 空白不輸出 `Domain=`;secure=true + `" example.com "` → `Secure` 與 `Domain=example.com`;`XSRF-TOKEN` 43 字元 base64url、`Max-Age=900`、非 HttpOnly;`clearAuthCookies` 兩條 `Max-Age=0` 且值為空 |
| G2 | `TradingApiIT.cookieAuthenticatedTradeWithoutCsrfIsRejected` | cookie 認證、帶 `Idempotency-Key` 與合法 body、無 `X-XSRF-TOKEN` → 403 `AUTH_CSRF_TOKEN_INVALID` 信封;cookie GET `/trades` `totalElements=0`,`transactions` 列數 0 |
| G3 | `BrowserAuthFlowIT.loginWithValidAccessCookieRequiresCsrf`、`registerWithValidAccessCookieRequiresCsrf` | 有效 `stock_access` 下缺 CSRF → 403 `AUTH_CSRF_TOKEN_INVALID` + `meta.traceId`;帶相符 CSRF → 200。register 用同一個新 email 重送成功,順帶證明被拒請求未建帳號 |
| G4 | `BrowserAuthFlowIT.cookieLogoutRevokesAccessCookieForMe` | 登出前 /me 200 → cookie + CSRF 登出 200 → 舊 cookie 打 /me 401、`success=false`、`AUTH_INVALID_CREDENTIALS`、`meta.traceId` |
| G5 | `MethodSecurityDenialIT.methodLevelDenialReturnsForbidden`(擴充) | 追加 `success=false`、`error.code=AUTH_FORBIDDEN`、`meta.traceId` exists |

測試名稱與 05-05 寫進 `ai-docs/browser-auth-contract.md` §CSRF / §Verification 的名稱逐字一致,契約不需改。

## 驗證證據

- Task 1:`./mvnw -B -pl stock-module-user -am test -Dtest='BrowserAuthCookieServiceTest' -Dsurefire.failIfNoSpecifiedTests=false` → `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`,`BUILD SUCCESS`。
- Task 2:`./mvnw -B -q -pl stock-start -am test-compile` → exit 0、無 `COMPILATION ERROR`;`./mvnw -B spotless:check` → `BUILD SUCCESS`。
- Acceptance greps:BrowserAuthFlowIT 三個方法名命中 4 行(≥3)、TradingApiIT 命中 1、MethodSecurityDenialIT `meta.traceId` 命中 1;`git diff --stat -- stock-start/src/main stock-module-user/src/main` 為空。
- 本機 `docker info` 失敗(無 daemon),`*IT` 只能 test-compile;**IT 執行證據待 CI Integration job**(`TEST-dowob.xyz.stockwebv2.start.BrowserAuthFlowIT.xml`、`…TradingApiIT.xml`、`…MethodSecurityDenialIT.xml` failures=0、errors=0,由 05-12 記錄 run id)。

## Deviations from Plan

None - plan executed exactly as written.

補充說明(非偏離):
- G1 是對既有行為的補測,測試第一次執行即綠,沒有 RED 階段(本 plan 只補缺口,不改正式程式)。
- G3 register 的正向案例(帶 CSRF → 200)plan 只寫「同上」,這裡比照 login 一併補上,並刻意重用同一個新 email 以證明被拒請求沒有副作用。
- `meta.traceId` 由 `TraceIdFilter.currentTraceId()` 提供,MDC 缺值時回退常數,因此 `exists()` 只證明信封形狀,不證明 traceId 與 header 一致(既有 IT 同樣寫法)。

## Known Stubs

None.

## Threat Flags

None(只新增測試,沒有新的網路端點或信任邊界)。

## TDD Gate Compliance

Task 1 / Task 2 為補測型任務(test-only),正式程式不變,不存在 RED → GREEN 的 feat commit;兩個 commit 皆為 `test(05-04)`。

## Self-Check: PASSED

- FOUND: stock-module-user/src/test/java/dowob/xyz/stockwebv2/user/service/BrowserAuthCookieServiceTest.java
- FOUND: c50e47a
- FOUND: f617f4d
