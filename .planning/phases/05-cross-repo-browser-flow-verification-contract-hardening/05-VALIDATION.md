---
phase: "05"
slug: "cross-repo-browser-flow-verification-contract-hardening"
# status lifecycle: draft (seeded by plan-phase) → validated (set by validate-phase §6)
# audit-milestone §5.5 distinguishes NOT-VALIDATED (draft) from PARTIAL (validated + nyquist_compliant: false) (#2117)
status: draft
nyquist_compliant: false
wave_0_complete: false
created: "2026-11-07"
---

# Phase 05 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> 來源:`05-RESEARCH.md` §Validation Architecture。本 cloud 環境沒有 Docker daemon:所有 IT / E2E / browser E2E 的驗收證據一律引用 CI job 結果與 artifact。

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | 後端:JUnit 5 + Spring Boot Test + Testcontainers 1.21.4 + ArchUnit 1.3.0(surefire `*Test` / failsafe `*IT` / `-Pe2e` `*E2E`);前端:Vitest ^4.1.6(jsdom)+ vue-tsc + Playwright 1.61.1 |
| **Config file** | `stock-start/pom.xml`(surefire / failsafe / `e2e` profile);前端 `vue-app/vite.config.ts`(`test` 區塊)、`vue-app/playwright.config.ts` |
| **Quick run command** | 後端:`./mvnw -B -pl <module> -am test -Dtest='<Class>' -Dsurefire.failIfNoSpecifiedTests=false`;前端:`cd vue-app && npx vitest run <path>` |
| **Full suite command** | 後端:`./mvnw -B spotless:check && ./mvnw -B test --fail-at-end`(IT / E2E 需 Docker → CI);前端:`cd vue-app && npm run lint && npm test && npm run build && VITE_DATA_MODE=api npm test && npm run build`;真後端:`bash e2e/run-e2e.sh`(CI browser-e2e job) |
| **Estimated runtime** | 後端 unit ~180s;前端完整閘門 ~120s;CI 全 job ~15–20 min |

---

## Sampling Rate

- **After every task commit:** 受影響模組的 quick run + `./mvnw -B spotless:check`(後端)/ `npm run lint`(前端)
- **After every plan wave:** 後端 `./mvnw -B test --fail-at-end`;前端完整閘門(mock + api 兩種 `npm test` + `npm run build`);push 後兩 repo CI 全綠(Unit / Integration / E2E / Browser E2E;前端 frontend + browser-e2e)
- **Before `/gsd-verify-work`:** 兩 repo `develop` CI 全綠(D-25:Wave 1 合併後先證明 develop 綠才開 Wave 2);`05-COVERAGE-AUDIT.md` 每列附測試名與 CI run id
- **Max feedback latency:** 300 seconds(本機 quick run);CI 證據另計

---

## Per-Task Verification Map

> 2026-11-07 plan-phase 已依 22 份 PLAN 填入 task / plan / wave / threat;Status 由 executor 與 05-12 / 05-22 gate 依 CI 證據更新。

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 05-04-T1 | 05-04 | 1 | VER-01 | T-05-07 | auth cookie HttpOnly / SameSite / Path(G1) | unit | `./mvnw -pl stock-module-user -am test -Dtest=BrowserAuthCookieServiceTest -Dsurefire.failIfNoSpecifiedTests=false` | ❌ W0 | ⬜ pending |
| 05-04-T2 | 05-04 | 1 | VER-01 | T-05-07, T-05-08 | cookie 無 CSRF 的 POST /trades → 403;login 帶有效 cookie 需 CSRF;cookie 登出後 /me 401(G2–G4) | IT (CI) | `./mvnw -B -pl stock-start -am verify -Dit.test='BrowserAuthFlowIT,TradingApiIT' -Dspring-boot.repackage.skip=true` | ✅ 檔 / 方法 ❌ | ⬜ pending |
| 05-04-T2 | 05-04 | 1 | VER-01 | T-05-09 | method-security 403 失敗信封(G5) | IT (CI) | `-Dit.test=MethodSecurityDenialIT` | ✅ 檔 / 斷言 ❌ | ⬜ pending |
| 05-05-T1 | 05-05 | 1 | VER-01 | T-05-10 | access token TTL == cookie TTL 守衛 | unit | `./mvnw -pl stock-module-user -am test -Dtest=AccessTokenTtlConsistencyGuardTest -Dsurefire.failIfNoSpecifiedTests=false` | ❌ W0 | ⬜ pending |
| 05-06-T1 | 05-06 | 2 | VER-01 | T-05-13 | `start/e2e` 無 `*Test` 具體類別 | unit (ArchUnit) | `./mvnw -pl stock-start -am test -Dtest=E2eNamingRulesTest -Dsurefire.failIfNoSpecifiedTests=false` | ❌ W0 | ⬜ pending |
| 05-06-T2 / 05-12-T2 | 05-06, 05-12 | 2–3 | VER-01 | T-05-13 | 7 個改名 `*E2E` 在 E2E job 綠 | E2E (CI) | `./mvnw -B -pl stock-start -am test -Pe2e` | ✅ 改名 | ⬜ pending |
| 05-09-T1 | 05-09 | 2 | VER-01 | T-05-17 | executedAt 未來 → `fields.executedAt` | unit + IT | `./mvnw -pl stock-module-trading -am test -Dtest=TradingServiceTest -Dsurefire.failIfNoSpecifiedTests=false` | ✅ 需改 | ⬜ pending |
| 05-09-T2 | 05-09 | 2 | VER-01 | T-05-18 | 觀察清單名稱 NBSP CHECK(新 migration) | IT (CI) | `-Dit.test=WatchlistApiIT` | ✅ 案例 ❌ | ⬜ pending |
| 05-07-T1/T2, 05-08-T1~T3 | 05-07, 05-08 | 1–2 | VER-01 | T-05-14, T-05-16 | 共用容器後 market-data IT 全綠(亂序) | IT (CI) | `./mvnw -B -pl stock-module-market-data -am verify -Dfailsafe.runOrder=random` | ✅ 需改 | ⬜ pending |
| 05-14-T1/T2 | 05-14 | 5 | VER-01 | T-05-24 | API DTO BigDecimal 一律 plain-string 序列化 | unit (ArchUnit + 行為) | `./mvnw -pl stock-start -am test -Dtest=ApiDecimalStringRulesTest -Dsurefire.failIfNoSpecifiedTests=false` | ❌ W2 | ⬜ pending |
| 05-13-T2 | 05-13 | 4 | VER-01 | T-05-22 | 精度回歸 `123456789012.12345678` 原樣存讀 | IT (CI) | `-Dit.test=CashEntryApiIT` | ✅ 檔 / 案例 ❌ | ⬜ pending |
| 05-02-T1/T2 | 05-02 | 1 | VER-02 | T-05-04 | fetch reject / 5xx 空 body 不崩潰 | unit | `cd vue-app && npx vitest run src/services/apiClient.test.ts` | ✅ 案例 ❌ | ⬜ pending |
| 05-03-T1 | 05-03 | 1 | VER-02 | T-05-06 | proxy target 讀 env,預設 11180 | unit | `cd vue-app && npx vitest run src/viteConfig.test.ts` | ✅ 案例 ❌ | ⬜ pending |
| 05-16-T1 | 05-16 | 4 | VER-02 | T-05-27 | decimal 轉換與精度 | unit | `cd vue-app && npx vitest run src/services/decimal.test.ts` | ❌ W2 | ⬜ pending |
| 05-11-T1/T2 | 05-11 | 2 | VER-02 | T-05-20 | useNow 未來時間判斷 | component | `cd vue-app && npx vitest run src/components/CashEntryDialog.test.ts src/components/OrderTicket.test.ts` | ✅ 案例 ❌ | ⬜ pending |
| 05-10-T1~T3 | 05-10 | 1 | VER-02 | T-05-19 | 紅漲綠跌下失敗色不變綠 / 無寫死 rgba | unit + layout | `cd vue-app && npx vitest run src/contrastTokens.test.ts && npx playwright test e2e/tests/layout.spec.ts` | ✅ 案例 ❌ | ⬜ pending |
| all | all | all | VER-02 | — | type-check / build | build | `cd vue-app && npm run build` | ✅ | ⬜ pending |
| 05-01-T1/T2 | 05-01 | 1 | VER-03 | T-05-01, T-05-02 | journey login → /me → reads → trade → refetch → logout + SC4 斷言 | browser E2E (CI) | `cd vue-app && bash e2e/run-e2e.sh e2e/tests/contract-journey.spec.ts` | ❌ W1 | ⬜ pending |
| 05-02-T3 | 05-02 | 1 | VER-03 | T-05-03 | SC5 18 組失敗矩陣不被 mock 掩蓋 | browser E2E (CI) | `cd vue-app && bash e2e/run-e2e.sh e2e/tests/api-failure-states.spec.ts` | ❌ W1 | ⬜ pending |
| 05-03-T2 | 05-03 | 1 | VER-03 | — | `run-e2e.sh` 無參數跑全部 | browser E2E (CI) | `cd vue-app && bash e2e/run-e2e.sh` | ✅ 改腳本 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `stock-module-user/src/test/java/dowob/xyz/stockwebv2/user/service/BrowserAuthCookieServiceTest.java` — G1
- [ ] `stock-module-user/src/test/java/dowob/xyz/stockwebv2/user/service/AccessTokenTtlConsistencyGuardTest.java` — D-22
- [ ] `stock-start/src/test/java/dowob/xyz/stockwebv2/start/E2eNamingRulesTest.java` — D-23
- [ ] `stock-start/src/test/java/dowob/xyz/stockwebv2/start/ApiDecimalStringRulesTest.java` — D-09(Wave 2 起頭先寫紅燈)
- [ ] `stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/support/MarketDataContainers.java` — D-04
- [ ] `vue-app/e2e/tests/contract-journey.spec.ts`、`vue-app/e2e/tests/api-failure-states.spec.ts`、`vue-app/e2e/support/mockSentinels.ts` — VER-03
- [ ] `vue-app/src/services/decimal.ts` + `decimal.test.ts` — D-08(Wave 2)
- [ ] Framework install:`npm install big.js@7.0.1 && npm install -D @types/big.js@7.0.0`(Wave 2)
- [ ] `.planning/phases/05-cross-repo-browser-flow-verification-contract-hardening/05-COVERAGE-AUDIT.md` — D-01 驗收證據

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| IT / E2E / browser E2E 實跑結果 | VER-01 / VER-03 | cloud 環境無 Docker daemon | 讀兩 repo CI run 的 job 結論與 artifact(`e2e-test-reports`、Playwright report),run id 記入 `05-COVERAGE-AUDIT.md` |
| Wave 1 合併後 develop 綠才開 Wave 2(D-25) | VER-01 / VER-02 / VER-03 | 需要 Yuan 合併 PR | Yuan 合併 Wave 1 PR 後確認兩 repo develop CI 綠 |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 300s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
