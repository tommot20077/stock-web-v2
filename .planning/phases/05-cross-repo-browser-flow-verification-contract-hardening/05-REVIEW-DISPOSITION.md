---
phase: 05
review: 05-REVIEW.md
titles: json
findings:
  - id: BE-CR-01
    severity: critical
    disposition: open
    title: "`CreateBacktestRunRequest.initialCapital` 沒有上限 / 位數限制,極端指數字串會讓回測引擎 `setScale` 卡死(已登入使用者即可 DoS)"
  - id: BE-WR-01
    severity: warning
    disposition: open
    title: "D-09 守門(`ApiDecimalStringRulesTest`)只掃 `*Dto` / `*Request` 類別的 raw-type `BigDecimal` 欄位,有多種寫法會繞過"
  - id: BE-WR-02
    severity: warning
    disposition: skipped
    title: "V20 以 `ADD CONSTRAINT ... CHECK` 驗證全表,只要有舊資料不符就會讓 Flyway 失敗、整個應用起不來"
  - id: BE-WR-03
    severity: warning
    disposition: deferred
    title: "Jacoco 棘輪下限直接設在實測值(無條件捨去),加上 market-data IT 亂序,`verify` 有 flaky 風險"
  - id: BE-IN-01
    severity: info
    disposition: deferred
    title: "`DecimalStringMatchers.decimalString` 接受科學記號字串,`asDecimal()` 改法也不檢查 wire 型別"
  - id: BE-IN-02
    severity: info
    disposition: deferred
    title: "`MarketDataContainers.ensureTopics` 遇到批次中有 `TopicExistsException` 時,可能吞掉其他 topic 的真正錯誤"
  - id: BE-IN-03
    severity: info
    disposition: deferred
    title: "`AccessTokenTtlConsistencyGuard` 比對的是正規化後的 cookie TTL,而且只守 access 那一對"
  - id: BE-IN-04
    severity: info
    disposition: deferred
    title: "`PortfolioCacheTest` 的名稱與說明還寫 v3,也沒有測「不讀 v3 鍵」"
  - id: BE-IN-05
    severity: info
    disposition: deferred
    title: "`KafkaConfigIT` 沒有執行任何 `KafkaConfig` 程式碼"
  - id: BE-IN-06
    severity: info
    disposition: deferred
    title: "模組 pom 的 Jacoco 註解寫「只可上調不可下調」,但 stock-start 的值已經下調"
  - id: BE-IN-07
    severity: info
    disposition: deferred
    title: "共用 Redis 的 database index 只靠註解分配,沒有任何機制守住"
  - id: BE-IN-08
    severity: info
    disposition: deferred
    title: "V20 的負向 IT 只斷言 `DataIntegrityViolationException`,沒有確認是哪個約束"
  - id: FE-WR-01
    severity: warning
    disposition: open
    title: "The non-`ApiClientError` fallback is still labelled `NETWORK_ERROR` and now hides contract or mapper failures, including after a successful write"
  - id: FE-WR-02
    severity: warning
    disposition: open
    title: "SC5 \"not masked by mock\" sentinels cannot fire for Overview (and Cash), so D-20 is not actually verified there"
  - id: FE-WR-03
    severity: warning
    disposition: open
    title: "Backtest initial capital accepts exponent notation, unbounded magnitude and more than 8 decimals"
  - id: FE-WR-04
    severity: warning
    disposition: open
    title: "D-28 is incomplete: hard-coded up/down `rgba` colours remain in in-scope pages and do not swap in red-up mode"
  - id: FE-IN-01
    severity: info
    disposition: deferred
    title: "Watchlist price formatting quietly moved from fixed `en-US` to the runtime default locale"
  - id: FE-IN-02
    severity: info
    disposition: deferred
    title: "Trades CSV `total` is the frontend product, not the backend's authoritative `amount`"
  - id: FE-IN-03
    severity: info
    disposition: deferred
    title: "Some real-backend E2E assertions are weaker than they look"
  - id: FE-IN-04
    severity: info
    disposition: deferred
    title: "`VITE_API_PROXY_TARGET` is named like a client variable and an empty value is not rejected"
  - id: FE-IN-05
    severity: info
    disposition: deferred
    title: "The \"never goes through JS number\" formatting depends on Intl.NumberFormat v3 with no fallback"
open: 6
total: 21
---

# Phase 05: Code Review Disposition

| Finding | Severity | Disposition | Source |
|---------|----------|-------------|--------|
| BE-CR-01 | critical | open | 05-REVIEW.md;UAT Test 7(2026-11-14):本 phase gap-closure 修正 |
| BE-WR-01 | warning | open | 05-REVIEW.md;UAT Test 7(2026-11-14):本 phase gap-closure 修正 |
| BE-WR-02 | warning | skipped | 05-REVIEW.md;Yuan 於 UAT Test 7(2026-11-14)裁定不需要處理 |
| BE-WR-03 | warning | deferred | 05-REVIEW.md;UAT Test 7(2026-11-14):與 Jacoco 下限 follow-up(UAT Test 4 #8)一併處理 |
| BE-IN-01 | info | deferred | 05-REVIEW.md;UAT Test 7(2026-11-14):info 級,延後至後續整理 |
| BE-IN-02 | info | deferred | 05-REVIEW.md;UAT Test 7(2026-11-14):info 級,延後至後續整理 |
| BE-IN-03 | info | deferred | 05-REVIEW.md;UAT Test 7(2026-11-14):info 級,延後至後續整理 |
| BE-IN-04 | info | deferred | 05-REVIEW.md;UAT Test 7(2026-11-14):info 級,延後至後續整理 |
| BE-IN-05 | info | deferred | 05-REVIEW.md;UAT Test 7(2026-11-14):info 級,延後至後續整理 |
| BE-IN-06 | info | deferred | 05-REVIEW.md;UAT Test 7(2026-11-14):info 級,延後至後續整理 |
| BE-IN-07 | info | deferred | 05-REVIEW.md;UAT Test 7(2026-11-14):info 級,延後至後續整理 |
| BE-IN-08 | info | deferred | 05-REVIEW.md;UAT Test 7(2026-11-14):info 級,延後至後續整理 |
| FE-WR-01 | warning | open | 05-REVIEW.md;UAT Test 7(2026-11-14):本 phase gap-closure 修正 |
| FE-WR-02 | warning | open | 05-REVIEW.md;UAT Test 7(2026-11-14):本 phase gap-closure 修正 |
| FE-WR-03 | warning | open | 05-REVIEW.md;UAT Test 7(2026-11-14):本 phase gap-closure 修正 |
| FE-WR-04 | warning | open | 05-REVIEW.md;UAT Test 7(2026-11-14):本 phase gap-closure 修正 |
| FE-IN-01 | info | deferred | 05-REVIEW.md;UAT Test 7(2026-11-14):info 級,延後至後續整理 |
| FE-IN-02 | info | deferred | 05-REVIEW.md;UAT Test 7(2026-11-14):info 級,延後至後續整理 |
| FE-IN-03 | info | deferred | 05-REVIEW.md;UAT Test 7(2026-11-14):info 級,延後至後續整理 |
| FE-IN-04 | info | deferred | 05-REVIEW.md;UAT Test 7(2026-11-14):info 級,延後至後續整理 |
| FE-IN-05 | info | deferred | 05-REVIEW.md;UAT Test 7(2026-11-14):info 級,延後至後續整理 |

Dispositions: `open` (recorded, not yet triaged), `fixed`, `skipped`, `deferred`.
Set `deferred` by hand and put the reason in the Source cell; both are preserved.

> 跨 repo 合併 review:ID 前綴 `BE-` = stock-web-v2、`FE-` = stock-web-v2-front-end。由 orchestrator 依 code-review-disposition 的輸出格式產生(內建解析器只認無前綴的 `### CR-01:` 標題)。
