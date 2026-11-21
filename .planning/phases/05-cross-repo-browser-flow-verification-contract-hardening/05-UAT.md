---
status: diagnosed
phase: 05-cross-repo-browser-flow-verification-contract-hardening
source: [05-VERIFICATION.md]
started: 2026-11-12T03:21:32Z
updated: 2026-11-14T13:34:53.851Z
---

## Current Test

[testing complete]

## Tests

### 1. 決定 05-19 must-have『估算金額(數量 × 價格 + 手續費)』的字面偏離:目前 OrderTicket.vue:976 估算 = quantity.times(price),不含手續費
expected: Yuan 接受現況(加入下方建議的 override)或要求改為含手續費(要同時改既有 04.1-14 / 04.1-27 測試與 mock cashAfter 公式)
result: pass

### 2. 決定 05-20 must-have『淨額(金額 − 稅)以 Decimal』的字面偏離:HoldingEventDialog 未新增淨額提示與『稅 > 金額』檢查
expected: Yuan 接受現況(加入 override)或另定『稅前總額 = 實領 + 稅』之類的 UI 文案
result: pass

### 3. 決定 05-25 must-have『兩條鏈完成前,兩 repo 不推送 Wave 2 分支』的流程偏離:實際逐 plan 推送,期間後端分支 push run 為紅(42964232157 等)
expected: Yuan 接受為已發生的流程偏離(加入 override);PR 開啟時兩條鏈已完成、合併順序與 develop 綠燈皆已由 GitHub API 核實
result: pass

### 4. 逐項檢視 05-COVERAGE-AUDIT.md §『VER-01 unclassified edge 人工檢視』12 個未勾選項目(CORS 非 preflight 的 Allow-Credentials 未測、meta.traceId 只驗 exists、market-data failsafe 報告未上傳、D-29 只有單一 seed、Jacoco 下限下調、SC5 SERVICE_UNAVAILABLE 為測試自訂 code 等)
expected: 每項勾選 = 已檢視並接受現況,或排入後續工作
result: pass

### 5. 在深 / 淺主題 × 紅漲 / 綠漲四種組合下,看 --danger 錯誤框與 color-mix 漲跌膠囊(05-10 D-02c / D-28)
expected: 錯誤色在四種組合下都不會變綠,漲跌膠囊語意清楚、易讀
result: pass

### 6. API mode 未登入進站 → UI 登入,不切頁,看 Overview
expected: Yuan 決定此既有缺陷是否可以帶著 milestone 結案:目前 Overview 會停在 AUTH_REFRESH_TOKEN_INVALID 錯誤區塊,要切頁才會重讀(App.vue:297 的 watch 只重連報價串流);J0 以切頁繞過
result: pass
note: "Yuan 接受帶著此既有缺陷結案;仍記錄於 deferred-items.md"

### 7. 分派 05-REVIEW-DISPOSITION.md 21 個 open finding,至少先處理 BE-CR-01(critical)
expected: BE-CR-01 給 initialCapital 加 @DecimalMax / @Digits 並補 400 測試,或明確 deferred 並寫明理由;FE-WR-01 / FE-WR-02 / FE-WR-04 決定修或延後
result: issue
reported: "Yuan 裁定:BE-CR-01、FE-WR-03、FE-WR-04、FE-WR-01、FE-WR-02、BE-WR-01 本 phase 修正;BE-WR-02 不處理(skipped,待確認);BE-WR-03 與 13 個 info 延後(deferred)。原話:「FE-WR-01 FE-WR-02 BE-WR-01 BE-WR-02 不需要」→ 更正「FE-WR-01 FE-WR-02 BE-WR-01 這是本次要修正」"
severity: blocker

### 8. 確認 judgment-tier prohibitions:05-01『journey 不把手動交易說成委託單』、05-04『不得放寬正式安全設定讓測試變綠』、05-16 ~ 05-23『API 路徑金額不經 JS number 運算』
expected: Yuan 認可下方的非權威判斷(皆 PASS)
result: pass

### 9. 05-01 test-tier prohibition『contract-journey 不得 page.route / route.fulfill』:目前 grep 為 0 次,但沒有自動守門防止回退
expected: Yuan 接受只靠現況 + code review,或補一條源碼守門(例如在 mockStoreBoundary 類測試掃描 contract-journey.spec.ts)
result: issue
reported: "補守門(回 issue)"
severity: major

### 10. 決定 T-05-12:.env.example:17 為 STOCK_JWT_ACCESS_TOKEN_TTL=PT30M 且沒有成對的 cookie TTL;dev profile 會讀 .env,照 README 複製後啟動會被 AccessTokenTtlConsistencyGuard 拒絕
expected: 修改 .env.example 為 PT15M(或補上成對的 STOCK_AUTH_COOKIE_ACCESS_TOKEN_TTL),或接受為已知的文件落差
result: issue
reported: "修正"
severity: major

## Summary

total: 10
passed: 7
issues: 3
pending: 0
skipped: 0
blocked: 0

## Gaps

- gap_id: G-05-7
  truth: "回測 initialCapital 前後端都有上限與位數限制(極端值回 400、不進引擎);Backtest / Analytics 漲跌色全走 token;非 ApiClientError 例外不再一律標成 NETWORK_ERROR;SC5 mock 哨兵在 Overview / Cash 能真正觸發;D-09 守門涵蓋 *Response / 參數化型別 / getter 等繞過寫法"
  status: failed
  reason: "User reported: Test 7 分派 code review finding,本 phase 修正:BE-CR-01(critical,initialCapital 無 @DecimalMax / @Digits,1e999999999 可讓引擎 setScale 卡死)、FE-WR-03(前端回測初始資金接受指數記號 / 無上限 / 超過 8 位小數)、FE-WR-04(Backtest.vue / Analytics.vue 寫死 rgba 漲跌色)、FE-WR-01(非 ApiClientError fallback 標 NETWORK_ERROR,遮蔽 contract / mapper 失敗)、FE-WR-02(SC5 哨兵在 Overview / Cash 無法觸發)、BE-WR-01(ApiDecimalStringRulesTest 只掃 *Dto / *Request raw-type 欄位)。細節見 05-REVIEW.md"
  severity: blocker
  test: 7
  root_cause: "6 項 finding 在現行 develop 全部仍存在(無部分修正),共通形態是約束 / 守門的涵蓋範圍小於缺陷類別:BE-CR-01 + FE-WR-03 同一欄位缺輸入上限 / 位數(後端 1e999999999 讓 setScale 卡死,>6 位小數經 money() 變 0 後 drawdownCurve 除以 0 → 500;前端 toChartNumber 在送出前呼叫 toFixed,輸入 1e999999999 會讓分頁本身卡死);FE-WR-04 守門只掃選擇器白名單內的 <style> 規則,看不到 template 屬性 / script 回傳字串 / .harvest-btn;FE-WR-02 哨兵只取自 data.ts 與 mock store,不含 Overview KPI 字面值與 cash 種子備註;BE-WR-01 只比對 Dto|Request 名稱後綴與 raw 欄位型別;FE-WR-01 寫入 adapter 在 apiRequest 外執行嚴格 mapper,mapper 丟 TypeError 後被 catch 分支改標 NETWORK_ERROR(D-26 後只有 apiClient.transportFetch 會產生 NETWORK_ERROR)"
  artifacts:
    - path: "stock-web-v2/stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/api/CreateBacktestRunRequest.java"
      issue: "initialCapital 只有 @DecimalMin,缺 @DecimalMax / @Digits"
    - path: "stock-web-v2/stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/service/BacktestService.java"
      issue: "validateCapital 只檢查 > 0;engine 在持久化前執行;只 catch IllegalArgumentException(ArithmeticException → 500)"
    - path: "stock-web-v2/stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/engine/DeterministicBacktestEngine.java"
      issue: "setScale / toPlainString 熱點(:51,:76-77,:184-189)與 peak = 0 除法(:98-106)"
    - path: "stock-web-v2/stock-start/src/test/java/dowob/xyz/stockwebv2/start/ApiDecimalStringRulesTest.java"
      issue: "predicate 只看 Dto|Request 後綴 + raw 欄位型別;無 method / 參數化型別規則與自我驗證"
    - path: "stock-web-v2-front-end/vue-app/src/pages/Backtest.vue"
      issue: "capital 無文法 / 上限 / 位數檢查,toChartNumber 先於驗證(:319-328);寫死 rgba 回撤填色(:141)與 heatColor(:368-374)"
    - path: "stock-web-v2-front-end/vue-app/src/pages/Analytics.vue"
      issue: ".harvest-btn 寫死 rgba(239,68,68,…)(:834-839)"
    - path: "stock-web-v2-front-end/vue-app/src/contrastTokens.test.ts"
      issue: "D-02c 守門只涵蓋白名單選擇器的 <style> 規則"
    - path: "stock-web-v2-front-end/vue-app/src/services/tradingApi.ts / cashApi.ts / holdingEventApi.ts"
      issue: "mapper 在 apiRequest 外執行,失敗不是 ApiClientError"
    - path: "stock-web-v2-front-end/vue-app/src/components/OrderTicket.vue, CashEntryDialog.vue, HoldingEventDialog.vue, WatchlistPicker.vue; src/pages/Watchlist.vue, Settings.vue, Overview.vue, Markets.vue, Chart.vue; src/baseCurrencyErrorKey.ts; src/App.vue(review 未列)"
      issue: "非 ApiClientError 分支一律標 NETWORK_ERROR"
    - path: "stock-web-v2-front-end/vue-app/e2e/tests/api-failure-states.spec.ts, e2e/support/mockSentinels.ts, src/mockSentinels.test.ts"
      issue: "Overview / Cash 哨兵與 assertReal 太弱,哨兵不含 KPI 與 cash 備註"
  missing:
    - "後端 initialCapital 加 @DecimalMax(\"1000000000000\") 與 @Digits(integer = 13, fraction = 6)(Yuan 2026-11-14 決定 fraction = 6;前端回測表單同為 6 位小數,Backtest.test.ts 既有 8 位小數案例改為 6 位),補 validator 單元測試與 400 + engine 未被呼叫的 WebMvc / E2E 案例"
    - "前端共用 plain-decimal 驗證 helper(文法 + 位數 + 上限,須在 toFixed / toChartNumber / toWire 之前),Backtest 使用;mock createRun 同步上限;Backtest.test.ts 補 1e5 / 1e400 / 1e999999999 / 超上限 / 位數過多案例"
    - "Backtest / Analytics 漲跌色改 token(fill-opacity 或 color-mix);contrastTokens.test.ts 新增掃描完整 .vue 原始碼的 rgba 漲跌字面值守門(含自我驗證,處理 Notifications.vue .alert)"
    - "apiClient.ts 新增 mapped 請求變體,mapper 失敗丟 ApiClientError{status, code: INVALID_API_RESPONSE, requestId};4 個寫入 adapter 改用;其餘非 ApiClientError 分支改 UNKNOWN_ERROR;OrderTicket 新增 tradeErrInvalidResponse 文案「伺服器已回應但內容無法解讀,這筆交易可能已記錄,請先到交易頁確認。」附 traceId,保留 idempotency key 允許重試(Yuan 2026-11-14 決定);App.vue messageFromStartupError 一併修正(Yuan 決定納入);補對應測試與 NETWORK_ERROR 只出現在 apiClient.ts 的源碼守門"
    - "MOCK_SENTINELS 新增 KPI 與 cash 備註類別並用於 Overview / Cash;mockSentinels.test.ts 驗證哨兵仍存在於 mock 輸出;強化 assertReal(KPI holdingCount = 1、cash 列數 = 1)"
    - "D-09 守門改以 controller 回傳型別為根(或至少擴大到 ..api.. 的 Dto|Request|Response|View 且排除 marketdata/api 的 service),加參數化型別與 BigDecimal 回傳方法規則及 fixture 自我驗證"
  debug_session: ".planning/debug/g-05-7-review-findings.md"
- gap_id: G-05-9
  truth: "有自動源碼守門斷言 contract-journey.spec.ts 不含 page.route / route.fulfill / context.route,防止 J0 被 stub 弱化"
  status: failed
  reason: "User reported: 補守門(回 issue)"
  severity: major
  test: 9
  root_cause: "05-01 的禁止事項只靠執行當下一次性的 grep(05-01-PLAN.md L114)驗證,沒有沉澱成常駐測試;且前端所有閘門都看不到 vue-app/e2e/(lint = eslint src、tsconfig include 只有 src/**、vitest exclude e2e/**)。另外 grep 樣式過窄(漏 context.route / routeFromHAR / routeWebSocket / 非 route 命名的 callback),也不看 spec import 的 helper:共用的 e2e/support/fixtures.ts 覆寫 page fixture 即可 stub J0 而 spec grep 仍為 0(scratch 實測)"
  artifacts:
    - path: "stock-web-v2-front-end/vue-app/e2e/tests/contract-journey.spec.ts"
      issue: "要保護的檔案,目前只有 L14 註解記載規則"
    - path: "stock-web-v2-front-end/vue-app/e2e/support/fixtures.ts"
      issue: "J0 唯一 import 的 helper(10 個 spec 共用),fixture 覆寫可繞過只掃 spec 的守門"
    - path: "stock-web-v2-front-end/vue-app/e2e/support/portfolioStubs.ts"
      issue: "現成的 stub 函式庫,若被 J0 import 會 stub 全部 /api/v1"
    - path: "stock-web-v2-front-end/vue-app/vite.config.ts, tsconfig.json, package.json"
      issue: "閘門範圍不含 e2e/"
  missing:
    - "新增 vue-app/src/contractJourneyBoundary.test.ts:以 ?raw / import.meta.glob 讀 spec 與 e2e/support/*.ts(不可用 node:fs,未裝 @types/node),原始碼掃描(不剝註解)禁止 .route / .unroute / .routeFromHAR / .routeWebSocket / .fulfill / .abort / .fallback / .continue / .addInitScript 呼叫"
    - "涵蓋 import 的 helper:import 白名單({../support/fixtures, @playwright/test})及 / 或遞迴追蹤相對 import 並套用同一規則"
    - "掃描範圍非空、現行樹 0 違規、偵測規則自我驗證(合成字串)三類測試"
  debug_session: ".planning/debug/g-05-9-contract-journey-guard.md"

- gap_id: G-05-10
  truth: "照 README 複製 .env.example 為 .env 後,dev profile 可直接啟動(JWT access TTL 與 cookie access TTL 一致,不被 AccessTokenTtlConsistencyGuard 拒絕)"
  status: failed
  reason: "User reported: 修正(.env.example:17 STOCK_JWT_ACCESS_TOKEN_TTL=PT30M 改為 PT15M,對齊 application.yaml 兩個預設值)"
  severity: major
  test: 10
  root_cause: ".env.example:17 仍為 STOCK_JWT_ACCESS_TOKEN_TTL=PT30M(2026-05-16 86fd897 加入時 yaml 預設也是 PT30M),且無成對的 STOCK_AUTH_COOKIE_ACCESS_TOKEN_TTL。05-05(eb0bc29)把四個 yaml 的 JWT 預設改為 PT15M 並加上無 profile 豁免的 AccessTokenTtlConsistencyGuard,但漏改 repo 根的 .env.example(05-05-PLAN.md L80 位置清單與 L115 grep 都只看 stock-start/src)。dev 為預設 profile 且 application-dev.yaml 以 optional:file:.env 匯入,複製範例後 JWT = PT30M、cookie = PT15M,guard 拋 IllegalStateException 拒絕啟動(Binder 實測重現)"
  artifacts:
    - path: "stock-web-v2/.env.example"
      issue: "第 17 行 PT30M,無成對 cookie TTL;唯一漂移的檔案"
    - path: "stock-web-v2/stock-start/src/main/resources/application-dev.yaml"
      issue: "第 3 行匯入 .env(注入路徑,本身正確)"
    - path: "stock-web-v2/stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/service/AccessTokenTtlConsistencyGuard.java"
      issue: "第 38 行 Duration 相等檢查(行為正確,D-22)"
  missing:
    - ".env.example:17 改為 STOCK_JWT_ACCESS_TOKEN_TTL=PT15M,旁加註解(或註解掉的 STOCK_AUTH_COOKIE_ACCESS_TOKEN_TTL=PT15M)說明兩者必須成對設定(D-22)"
    - "(選做)stock-start 靜態單元測試讀 repo 根 .env.example,斷言有效的 JWT / cookie access TTL 相等(比照 InfraProfileDefaultsTest 的 Files.readString)"
  debug_session: ".planning/debug/g-05-10-env-example-ttl.md"

## Deferred Follow-Ups

- test: 4
  idea: "COVERAGE-AUDIT VER-01 #5:把 stock-module-market-data/target/failsafe-reports/ 加入 CI artifact 上傳路徑"
  deferred_at: 2026-11-14
- test: 4
  idea: "COVERAGE-AUDIT VER-01 #8:補 stock-start IT,把 Jacoco 下限從 0.87 / 0.64 拉回 0.88 / 0.66"
  deferred_at: 2026-11-14
