---
status: testing
phase: 05-cross-repo-browser-flow-verification-contract-hardening
source: [05-VERIFICATION.md]
started: 2026-11-12T03:21:32Z
updated: 2026-11-14T13:25:35Z
---

## Current Test
<!-- OVERWRITE each test - shows where we are -->

number: 6
name: API mode 未登入進站 → UI 登入,不切頁,看 Overview
expected: |
  Yuan 決定此既有缺陷是否可以帶著 milestone 結案:目前 Overview 會停在 AUTH_REFRESH_TOKEN_INVALID 錯誤區塊,要切頁才會重讀(App.vue:297 的 watch 只重連報價串流);J0 以切頁繞過
awaiting: user response

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
result: [pending]

### 7. 分派 05-REVIEW-DISPOSITION.md 21 個 open finding,至少先處理 BE-CR-01(critical)
expected: BE-CR-01 給 initialCapital 加 @DecimalMax / @Digits 並補 400 測試,或明確 deferred 並寫明理由;FE-WR-01 / FE-WR-02 / FE-WR-04 決定修或延後
result: [pending]

### 8. 確認 judgment-tier prohibitions:05-01『journey 不把手動交易說成委託單』、05-04『不得放寬正式安全設定讓測試變綠』、05-16 ~ 05-23『API 路徑金額不經 JS number 運算』
expected: Yuan 認可下方的非權威判斷(皆 PASS)
result: [pending]

### 9. 05-01 test-tier prohibition『contract-journey 不得 page.route / route.fulfill』:目前 grep 為 0 次,但沒有自動守門防止回退
expected: Yuan 接受只靠現況 + code review,或補一條源碼守門(例如在 mockStoreBoundary 類測試掃描 contract-journey.spec.ts)
result: [pending]

### 10. 決定 T-05-12:.env.example:17 為 STOCK_JWT_ACCESS_TOKEN_TTL=PT30M 且沒有成對的 cookie TTL;dev profile 會讀 .env,照 README 複製後啟動會被 AccessTokenTtlConsistencyGuard 拒絕
expected: 修改 .env.example 為 PT15M(或補上成對的 STOCK_AUTH_COOKIE_ACCESS_TOKEN_TTL),或接受為已知的文件落差
result: [pending]

## Summary

total: 10
passed: 5
issues: 0
pending: 5
skipped: 0
blocked: 0

## Gaps

[none yet]

## Deferred Follow-Ups

- test: 4
  idea: "COVERAGE-AUDIT VER-01 #5:把 stock-module-market-data/target/failsafe-reports/ 加入 CI artifact 上傳路徑"
  deferred_at: 2026-11-14
- test: 4
  idea: "COVERAGE-AUDIT VER-01 #8:補 stock-start IT,把 Jacoco 下限從 0.87 / 0.64 拉回 0.88 / 0.66"
  deferred_at: 2026-11-14
