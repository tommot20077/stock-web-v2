---
phase: quick-261106-fbs
plan: 261106-fbs
type: execute
wave: 1
depends_on: []
mode: quick
autonomous: true
requirements:
  - QT-A1-cash-occurred-at-required-copy      # todo 2026-10-25-cash-occurred-at-empty-copy
  - QT-A2-contrast-text-tokens                # todo 2026-10-26-contrast-text-tokens
  - QT-A3-markets-header-640px                # 04.2-25 Deferred Issues / 04.2-26 G3
  - QT-B-top-movers-base-currency             # todo 2026-10-25-top-movers-base-currency
  - QT-C-0401-WR02-assets-bad-rows-and-check  # todo 2026-10-26-phase-04.1-review-followups
  - QT-C-0401-WR03-repeatable-read
  - QT-C-0401-IN01-IN05-info-cleanups
  - QT-C-0402-WR02-watchlist-symbol-case
  - QT-C-0402-WR04-event-date-floor
  - QT-D-full-docs-sweep                      # todo 2026-10-31-full-docs-sweep (含 04.2 G2)
repo_roots:
  backend: /home/user/stock-web-v2              # Task 1 與 Task 3[BE] 的路徑皆相對此根
  frontend: /home/user/stock-web-v2-front-end   # Task 2 與 Task 3[FE] 的路徑皆相對此根
branch: claude/epic-curie-lj8alo                # 兩個 repo 皆在此分支;PR 由 orchestrator 開
execution_topology:
  task_1: { repo: backend,  parallel_with: task_2 }
  task_2: { repo: frontend, parallel_with: task_1 }
  task_3: { repo: both,     depends_on: [task_1, task_2] }
files_modified:
  # ── backend(相對 repo_roots.backend)── Task 1
  - stock-common/src/main/java/dowob/xyz/stockwebv2/common/model/CurrencyCode.java
  - stock-db-migration/src/main/resources/db/migration/V19__assets_currency_check.sql
  - stock-module-asset/src/main/java/dowob/xyz/stockwebv2/asset/service/AssetFacadeImpl.java
  - stock-module-asset/src/test/java/dowob/xyz/stockwebv2/asset/service/AssetFacadeImplTest.java
  - stock-module-asset/src/test/java/dowob/xyz/stockwebv2/asset/service/AssetFacadeImplIT.java
  - stock-module-asset/src/main/java/dowob/xyz/stockwebv2/asset/service/WatchlistService.java
  - stock-module-asset/src/test/java/dowob/xyz/stockwebv2/asset/service/WatchlistServiceTest.java
  - stock-start/src/test/java/dowob/xyz/stockwebv2/start/WatchlistApiIT.java
  - stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/HoldingDto.java
  - stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/PortfolioQueryService.java
  - stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/PortfolioCache.java
  - stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/TradingService.java
  - stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/DividendService.java
  - stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/ShareAdjustmentService.java
  - stock-module-trading/src/test/java/dowob/xyz/stockwebv2/trading/service/PortfolioQueryServiceTest.java
  - stock-module-trading/src/test/java/dowob/xyz/stockwebv2/trading/service/TradingServiceTest.java
  - stock-module-trading/src/test/java/dowob/xyz/stockwebv2/trading/service/DividendServiceTest.java
  - stock-module-trading/src/test/java/dowob/xyz/stockwebv2/trading/service/ShareAdjustmentServiceTest.java
  - stock-start/src/test/java/dowob/xyz/stockwebv2/start/DividendApiIT.java
  - stock-start/src/test/java/dowob/xyz/stockwebv2/start/ShareAdjustmentApiIT.java
  - stock-start/src/test/java/dowob/xyz/stockwebv2/start/TradingApiIT.java
  - ai-docs/trading-portfolio-contract.md
  - ai-docs/watchlist-contract.md
  # ── frontend(相對 repo_roots.frontend)── Task 2
  - vue-app/src/i18n.ts
  - vue-app/src/i18n.test.ts
  - vue-app/src/components/CashEntryDialog.vue
  - vue-app/src/components/CashEntryDialog.test.ts
  - vue-app/src/styles.css
  - vue-app/src/useTweaks.ts
  - vue-app/src/contrastTokens.test.ts
  - vue-app/src/components/AuthPanel.vue
  - vue-app/src/components/Header.vue
  - vue-app/src/components/HoldingEventDialog.vue
  - vue-app/src/components/OrderTicket.vue
  - vue-app/src/components/OrderTicket.test.ts
  - vue-app/src/components/PrefixIndicator.vue
  - vue-app/src/components/StrategyEditor.vue
  - vue-app/src/components/settings/SettingsAiAccess.vue
  - vue-app/src/components/settings/SettingsBrokers.vue
  - vue-app/src/components/settings/SettingsDataSources.vue
  - vue-app/src/components/settings/SettingsNotificationPreferences.vue
  - vue-app/src/pages/Alerts.vue
  - vue-app/src/pages/Analytics.vue
  - vue-app/src/pages/Backtest.vue
  - vue-app/src/pages/Chart.vue
  - vue-app/src/pages/Markets.vue
  - vue-app/src/pages/Notifications.vue
  - vue-app/src/pages/Ops.vue
  - vue-app/src/pages/Overview.vue
  - vue-app/src/pages/Overview.test.ts
  - vue-app/src/pages/Positions.vue
  - vue-app/src/pages/Positions.test.ts
  - vue-app/src/pages/Cash.vue
  - vue-app/src/pages/Settings.vue
  - vue-app/src/pages/Trades.vue
  - vue-app/src/pages/Watchlist.vue
  - vue-app/src/cashBalanceView.ts
  - vue-app/src/cashBalanceView.test.ts
  - vue-app/src/baseCurrencyErrorKey.ts
  - vue-app/src/baseCurrencyErrorKey.test.ts
  - vue-app/src/services/apiTypes.ts
  - vue-app/src/services/portfolioApi.ts
  - vue-app/src/services/portfolioApi.test.ts
  - vue-app/e2e/tests/layout.spec.ts
  - vue-app/e2e/support/portfolioStubs.ts
  # ── docs(Task 3)── [BE] 相對 backend 根;[FE] 相對 frontend 根
  - ai-docs/                                                    # [BE] 全部(bug-reports/ 只驗 INDEX 連結)
  - .planning/codebase/                                         # [BE] 7 份
  - .planning/phases/04.1-backend-data-gap-backfill/04.1-UI-SPEC.md                 # [BE]
  - .planning/phases/04.1-backend-data-gap-backfill/04.1-REVIEW-DISPOSITION.md      # [BE]
  - .planning/phases/04.2-dividends-share-adjustments-watchlist/04.2-REVIEW-DISPOSITION.md  # [BE]
  - .planning/STATE.md                                          # [BE] 只動 ### Pending Todos 與 04.2 blocker 兩處
  - .planning/todos/                                            # [BE] 5 條移 completed/、1 條追加、1 條新建
  - docs/                                                       # [FE]
  - AGENTS.md                                                   # [FE] 前端的 AGENTS.md(後端 AGENTS.md 不動)

estimate:
  tokens: 380000
  raw_tokens: 380000
  tasks: 3
  confidence: low

must_haves:
  truths:
    - "[A1] CashEntryDialog 發生時間被清空時顯示專屬文案 cashErrOccurredAtRequired(zh「請填寫發生時間。」/ en「Occurred at is required.」);填未來時間與後端 fields.occurredAt 仍顯示 cashErrOccurredAt"
    - "[A2] 淺色主題上漲文字為 --up-text #15803d、圖表 / 點 / 底色仍為 --up #16a34a;深色主題 --up-text #16a34a、--dn #ef4444;所有 --accent 底的白字改為 #15171c(--on-accent);.quote-lab 用 --fg-dim;紅漲綠跌偏好(upGreen=false)下文字與圖表顏色仍正確對調;layout.spec 前後皆全綠、無回歸"
    - "[A3] Markets 頁在 320 / 375px 頁首搜尋框與 tabs / LIVE 列不再撐寬 .main;L12 在全部寬度斷言 documentElement 與 .main 皆無水平捲動(開啟選擇器前後)"
    - "[B] GET /api/v1/portfolio/holdings 每列帶 unrealizedPnlInBase(unrealizedPnl 以目前匯率折成 baseCurrency;與 marketValueInBase 同條件為 null);Positions Top movers 以 |unrealizedPnlInBase| 排序、金額為「數字 + 基準幣 ISO」、不再寫死 $、缺值持倉不進榜且顯示「N 檔未列入」提示"
    - "[C/04.1-WR02] assets 一列不支援的 asset_type / currency 不再讓 summary / holdings / trades 500:該列被略過並 WARN(只記 id);V19 新增 ck_assets_currency,DB 拒絕 CurrencyCode 以外的幣別"
    - "[C/04.1-WR03] TradingService#listTrades 與 PortfolioQueryService#summary 在 REPEATABLE_READ 唯讀交易內執行(反射測試鎖住)"
    - "[C/04.2-WR02,WR04] 觀察清單以小寫 symbol 加入成功;paidAt / effectiveDate 早於 1900-01-01 回 400 fields.paidAt / fields.effectiveDate,不再 500"
    - "[C/04.1-IN01..05] 死碼移除、Overview/Cash/Settings 重複邏輯抽共用、Overview 近期交易有序號守門、Positions sectorAllocation 缺值不炸頁、平倉候選缺資產屬性計入 dayPnlExcludedCount"
    - "[D] ai-docs / .planning/codebase / 前端 docs 與 AGENTS.md 與程式一致(含 04.2 G2 三處);無法判斷者列給 Yuan;5 條 todo 移 completed/ 並附 resolved / resolved_by / ## Resolution;04.1 WR-01 / WR-04 的延後去處有紀錄"
  artifacts:
    - path: "stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/HoldingDto.java"
      repo: backend
      provides: "unrealizedPnlInBase record component(緊接 marketValueInBase)"
      contains: "unrealizedPnlInBase"
    - path: "stock-db-migration/src/main/resources/db/migration/V19__assets_currency_check.sql"
      repo: backend
      provides: "ck_assets_currency CHECK(USD / TWD / EUR / JPY)"
      contains: "ck_assets_currency"
    - path: "vue-app/src/contrastTokens.test.ts"
      repo: frontend
      provides: "token 與 accent / quote-lab / up-text 規則的原始碼守門測試"
    - path: "vue-app/src/cashBalanceView.ts"
      repo: frontend
      provides: "Overview / Cash 共用的現金餘額檢視純函式"
    - path: "vue-app/src/baseCurrencyErrorKey.ts"
      repo: frontend
      provides: "Overview / Settings 共用的基準幣錯誤文案分派"
  key_links:
    - from: "backend HoldingDto.unrealizedPnlInBase(PortfolioQueryService#withBaseValuation 填值)"
      to: "frontend vue-app/src/services/apiTypes.ts HoldingDto.unrealizedPnlInBase → Positions.vue apiTopMovers"
      via: "GET /api/v1/portfolio/holdings JSON(欄位名與 null 語意見 <interfaces>)"
      pattern: "unrealizedPnlInBase"
    - from: "vue-app/src/useTweaks.ts(dataset.upColor)"
      to: "vue-app/src/styles.css :root[data-up-color=\"red\"] 區塊"
      via: "data attribute 取代 inline custom property,讓 styles.css 的深色主題 token 生效"
      pattern: "data-up-color"
    - from: "V19 ck_assets_currency"
      to: "stock-common CurrencyCode enum"
      via: "值域必須一致(另兩處:ck_users_base_currency、ck_cash_ledger_entries_currency)"
      pattern: "ck_assets_currency"
    - from: "WatchlistService#addItem"
      to: "WatchlistRepository#findActiveAssetIdBySymbol"
      via: "strip + toUpperCase(Locale.ROOT) 正規化後查詢"
      pattern: "Locale.ROOT"
---

<objective>
一個 quick task 收掉 Yuan 2026-11-06 核准的 A–D 四組小修(跨兩個 repo):
A 前端小修(入出金清空文案、對比度 token、Markets < 640px 溢出 + L12 收緊);
B 跨 repo:HoldingDto 新增 unrealizedPnlInBase,前端 Top movers 改以基準幣排序與顯示;
C 後端穩健性:04.1 WR-02 / WR-03 / 5 條 info、04.2 REVIEW WR-02 / WR-04;
D 最後做全文件掃描(涵蓋 A–C 的變更)並關閉對應 todo。

Purpose: 清掉 Phase 04.1 / 04.2 收尾留下的待辦與審查 finding,讓 Phase 5 的跨 repo 驗證在乾淨的契約與文件上開始。
Output: 後端與前端各自的 atomic commits(scope `quick-261106-fbs`,繁中 subject,TDD 的 RED / GREEN 分開 commit)、V19 migration、更新後的契約與文件、5 條 todo 移入 completed/。

**Out of scope(Yuan 延後):** 04.1 WR-01(匯率時間顯示 → todo fx-pnl-and-rate-history)、04.1 WR-04(字串金額 → Phase 5)。04.2 REVIEW 其他 finding(WR-01 / WR-03 / IN-*)不在本次範圍。

**Execution topology(orchestrator 以三個 executor 執行):**
- Task 1(後端 repo)與 Task 2(前端 repo)**平行**;兩者之間唯一的耦合是 `unrealizedPnlInBase` 欄位,其名稱、型別與 null 語意已在下方 `<interfaces>` 釘死,Task 2 不需等 Task 1。
- Task 3(文件 + todo 關閉)**依賴 Task 1 與 Task 2 皆完成並已 commit**(它要掃描兩者的變更)。

**路徑規則(#4767):** 每個 task 的 `<files>`、`<action>`、`<verify>` 路徑皆相對該 task 所在 repo 的根(Task 1 = backend 根、Task 2 = frontend 根;Task 3 以 `[BE]` / `[FE]` 標示),`<automated>` 指令假設 cwd 在該 repo 根。

**Quick-mode 結構說明:** orchestrator 指定單一 plan 三個 task(每 repo 一個 executor),因此不另立 tracer task;改為每個 task 內先做最薄的端到端切片(Task 1 先做 unrealizedPnlInBase,Task 2 先做 apiTypes + Top movers),再擴展其餘項目。每一子項目各自 RED → GREEN commit,context 中斷可依 commit 接續。
</objective>

<execution_context>
@~/.claude/gsd-core/workflows/execute-plan.md
@~/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@/home/user/stock-web-v2/.planning/STATE.md
@/home/user/stock-web-v2/AGENTS.md
@/home/user/stock-web-v2-front-end/AGENTS.md
@/home/user/stock-web-v2/.planning/todos/pending/2026-10-25-cash-occurred-at-empty-copy.md
@/home/user/stock-web-v2/.planning/todos/pending/2026-10-25-top-movers-base-currency.md
@/home/user/stock-web-v2/.planning/todos/pending/2026-10-26-contrast-text-tokens.md
@/home/user/stock-web-v2/.planning/todos/pending/2026-10-26-phase-04.1-review-followups.md
@/home/user/stock-web-v2/.planning/todos/pending/2026-10-31-full-docs-sweep.md

<interfaces>
<!-- 跨 repo 契約(Task 1 實作、Task 2 依此平行開發;兩邊都不得偏離) -->

後端 `HoldingDto`(record)新增 component,位置**緊接在 `marketValueInBase` 之後**:
- Java:`BigDecimal unrealizedPnlInBase`
- JSON:`"unrealizedPnlInBase": number | null`
- 值:`unrealizedPnl`(原幣)以**目前匯率**折成 `baseCurrency`,走與 `marketValueInBase` 完全相同的路徑(`PortfolioQueryService#withBaseValuation` → `FxRates.convert`,結果 scale 8、HALF_UP)。
- null 條件:與 `marketValueInBase` **同條件**——`currency` 為 null(缺資產屬性)或換不到匯率;語意同 summary 的 `fxExcludedCount`(換不到匯率者不計入任何基準幣總額 / 排行)。
- 不寫入持倉快取:與 `marketValueInBase` 一樣於回應時補上;快取鍵維持 `portfolio:valuation:v2`,summary 不變、`portfolio:summary:v3` 不變。
- 只出現在 `GET /api/v1/portfolio/holdings`。

前端 `vue-app/src/services/apiTypes.ts` 的 `HoldingDto` 對應新增(緊接 `marketValueInBase`):
- `unrealizedPnlInBase: number | null;`(附註解:基準幣未實現損益,缺匯率時為 null,該列不進基準幣排行)

既有可沿用的前端 helper(`vue-app/src/moneyFormat.ts`):
- `fmtSignedMoney(value, code, dp): string` —— 帶號「數字 + NBSP + ISO」;null / 非有限 → `—`
- `minorUnits(code): number`
既有的 Positions 基準幣金額慣例:`fmtSignedMoney(x, base, 0)`(sector rows 已如此)。

既有後端模式(Task 1 照抄):
- `FxRateFacadeImpl#toFxRate`:壞列 `log.warn("… id={}", id)` + `Optional.empty()`,只記 id。
- `CashLedgerServiceTest#listEntriesRunsInARepeatableReadReadOnlyTransaction`:以 `getMethod(...).getAnnotation(Transactional.class)` 斷言 `readOnly()` 與 `isolation() == Isolation.REPEATABLE_READ`。
- `V14__users_base_currency.sql`:CHECK 約束 + 中文標頭註解(值域與 `CurrencyCode` 同步的說明)。
</interfaces>
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1 [backend repo — 路徑相對 /home/user/stock-web-v2]:B 後端 unrealizedPnlInBase + C 後端穩健性(04.1 WR-02 / WR-03 / IN-01 / IN-05、04.2 WR-02 / WR-04)</name>
  <files>
    stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/HoldingDto.java,
    stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/PortfolioQueryService.java,
    stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/PortfolioCache.java,
    stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/TradingService.java,
    stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/DividendService.java,
    stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/ShareAdjustmentService.java,
    stock-module-trading/src/test/java/dowob/xyz/stockwebv2/trading/service/PortfolioQueryServiceTest.java,
    stock-module-trading/src/test/java/dowob/xyz/stockwebv2/trading/service/TradingServiceTest.java,
    stock-module-trading/src/test/java/dowob/xyz/stockwebv2/trading/service/DividendServiceTest.java,
    stock-module-trading/src/test/java/dowob/xyz/stockwebv2/trading/service/ShareAdjustmentServiceTest.java,
    stock-module-asset/src/main/java/dowob/xyz/stockwebv2/asset/service/AssetFacadeImpl.java,
    stock-module-asset/src/test/java/dowob/xyz/stockwebv2/asset/service/AssetFacadeImplTest.java,
    stock-module-asset/src/test/java/dowob/xyz/stockwebv2/asset/service/AssetFacadeImplIT.java,
    stock-module-asset/src/main/java/dowob/xyz/stockwebv2/asset/service/WatchlistService.java,
    stock-module-asset/src/test/java/dowob/xyz/stockwebv2/asset/service/WatchlistServiceTest.java,
    stock-common/src/main/java/dowob/xyz/stockwebv2/common/model/CurrencyCode.java,
    stock-db-migration/src/main/resources/db/migration/V19__assets_currency_check.sql,
    stock-start/src/test/java/dowob/xyz/stockwebv2/start/TradingApiIT.java,
    stock-start/src/test/java/dowob/xyz/stockwebv2/start/WatchlistApiIT.java,
    stock-start/src/test/java/dowob/xyz/stockwebv2/start/DividendApiIT.java,
    stock-start/src/test/java/dowob/xyz/stockwebv2/start/ShareAdjustmentApiIT.java,
    ai-docs/trading-portfolio-contract.md,
    ai-docs/watchlist-contract.md
  </files>
  <behavior>
    - T1.1 listHoldings:USD 持倉 unrealizedPnl 100、USD→TWD 32.418、基準幣 TWD → unrealizedPnlInBase = 3241.80000000;換不到匯率 → unrealizedPnlInBase 與 marketValueInBase 同為 null;快取命中的原幣持倉依本次基準幣重新折算,快取內容的 unrealizedPnlInBase 維持 null
    - T1.2 AssetFacadeImpl 壞列:asset_type 或 currency 不在 enum 的列被略過(回傳 map 不含該 id、不拋例外),其餘列照常;單元測試以純函式(或 mock ResultSet)驗證,IT 另驗真 DB 路徑
    - T1.3 V19:插入 currency 'HKD' 的 assets 列 → DataIntegrityViolationException(IT,CI 跑)
    - T1.4 反射:TradingService#listTrades 與 PortfolioQueryService#summary 的 @Transactional 為 readOnly 且 isolation == REPEATABLE_READ
    - T1.5 WatchlistService#addItem(" aapl ") → repository 以 "AAPL" 查詢;IT:POST {"symbol":"aapl"} → 200 且清單含 AAPL
    - T1.6 DividendService:paidAt 1899-12-31T23:59:59Z 與 -10000-01-01T00:00:00Z → FieldValidationException,fields 含 paidAt;1900-01-01T00:00:00Z 不被下限擋(邊界含)
    - T1.7 ShareAdjustmentService:effectiveDate 1899-12-31 → fields.effectiveDate,且在任何 Facade 呼叫前擋下;1900-01-01 不被下限擋
    - T1.8 IN-05:今天賣光的平倉候選有成交與行情但查無資產屬性 → dayPnlExcludedCount +1(唯一候選時 dayPricePnl 為 null);持有中且查無屬性的持倉只計入 fxExcludedCount,dayPnlExcludedCount 不重複計
  </behavior>
  <action>
**前置:** 確認在分支 `claude/epic-curie-lj8alo`、工作樹乾淨。鐵律:強制 TDD——每個子項先寫測試、跑出 RED 並 commit(`test(quick-261106-fbs): …(RED)`),再寫最少實作跑 GREEN 並 commit(`fix|feat(quick-261106-fbs): …(GREEN)`);繁中 subject;commit 結尾附 Co-Authored-By / Claude-Session 兩行(見 orchestrator 給的 attribution)。本機無 Docker:IT / E2E 只寫、只 test-compile,由 CI 跑;SUMMARY 不得宣稱 IT 通過。

**1a(端到端切片,先做)— B 後端 unrealizedPnlInBase(依 `<interfaces>` 契約):**
- `HoldingDto`:在 `marketValueInBase` 之後新增 `BigDecimal unrealizedPnlInBase`;Javadoc 補 `@param`(基準幣未實現損益;換不到匯率時為 null;不寫入持倉快取);`withBaseValuation` 改為接收 `(String baseCurrency, BigDecimal valueInBase, BigDecimal unrealizedPnlInBase)` 並帶入新欄位;類別 Javadoc 的「基準幣欄位不寫入快取」一句把新欄位一併列入;`@version` 遞增。
- `PortfolioQueryService#withBaseValuation`(private,約 365 行):以同一個 `parseCurrency(holding.currency())` 結果,用 `rates.convert(holding.unrealizedPnl(), currency, base)` 算出 unrealizedPnlInBase;`calculateAndCacheHolding` 的 canonical constructor 呼叫對新位置傳 null(快取只存原幣)。`listHoldings` Javadoc 補一句新欄位。
- 測試:`PortfolioQueryServiceTest` 既有 4 處 `new HoldingDto(` 補參數;於約 557 / 577 / 596 行那三個 marketValueInBase 測試旁新增 T1.1 的斷言(或新測試)。`TradingApiIT` 在既有 `marketValueInBase` jsonPath 斷言旁加 `unrealizedPnlInBase`(CI 跑)。
- 契約:`ai-docs/trading-portfolio-contract.md` holdings 表在 `marketValueInBase` 列之後新增 `unrealizedPnlInBase` 列(`number | null`;換不到匯率時 null,與 `marketValueInBase` 同條件、語意同 `fxExcludedCount`;跨標的比較 / 排行請用此欄);第 ~132 行引言補「跨標的比較損益請用 `unrealizedPnlInBase`」;〈快取與一致性〉的持倉鍵說明把新欄位列為回應時補上;〈變更紀錄〉加 2026-11-06(quick-261106-fbs)條目。

**1b — 04.1 WR-02 資產壞列容錯 + V19:**
- `AssetFacadeImpl`:仿 `FxRateFacadeImpl` 加 SLF4J logger;把 `findProfiles` 的映射改為「先讀原始字串、再以 try/catch(IllegalArgumentException)轉 enum」,失敗時 `log.warn` **只記 id**(不記 symbol / currency / asset_type 原值,避免 log injection)並略過,只收集成功的列。建議抽成 package-private 純函式(例如接收原始欄位值的 record → `Optional<AssetProfile>`),讓 `AssetFacadeImplTest`(新檔,純單元)可本機 RED → GREEN。`findAllTradeable` / `findBySymbol` 共用的 `map` 也套同樣防護(同一類失敗:一列壞 asset_type 會讓 market-data ingestor 整批失敗);`findBySymbol` 遇壞列回 empty。
- `AssetFacadeImplIT`(CI 跑):新增「asset_type 不在 enum 的列被 findProfiles 略過、其餘照常」與「插入 currency 'HKD' 被 ck_assets_currency 拒絕」兩案;插入的暫存列以獨特 symbol、`tradeable=false` 建立並於測試結束刪除,不得影響既有 `hasSize(11)` 等斷言。
- 新檔 `stock-db-migration/src/main/resources/db/migration/V19__assets_currency_check.sql`:中文標頭註解(仿 V14:值域與 `CurrencyCode` 同步、新增幣別需同時改 enum 與三個 CHECK——`ck_users_base_currency`、`ck_cash_ledger_entries_currency`、`ck_assets_currency`;若在某環境套用失敗,先以 `select id, currency from assets where currency not in (...)` 找出違規列;V1~V18 不得修改;不使用 CONCURRENTLY)+ 一行 `ALTER TABLE assets ADD CONSTRAINT ck_assets_currency CHECK (currency IN ('USD', 'TWD', 'EUR', 'JPY'));`。規劃時已驗證:V2 種子 19 列幣別皆為 USD / TWD / EUR / JPY;`WatchlistApiIT#createTempAssets` 用 'USD';repo 內沒有其他寫 assets 的 SQL / 測試(實作時再 grep 一次 `into assets` 確認)。此 migration 一經套用到共用環境只能以新 migration 改(reversibility: costly,Yuan 已裁決採用,不另設 checkpoint)。
- `CurrencyCode`:Javadoc 註明值域與上述三個 DB CHECK 必須同步(只改註解)。

**1c — 04.1 WR-03 隔離層級:**
- `TradingService#listTrades`:`@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)`,Javadoc 比照 `CashLedgerService#listEntries` 說明 `readOnly` 不改隔離層級、READ COMMITTED 下 count 與 list 各取快照會漂移。`TradingServiceTest` 加反射測試(仿 `CashLedgerServiceTest` 約 712 行)。
- `PortfolioQueryService#summary`:同樣改 REPEATABLE_READ(todo 2026-10-31 更新明列 PortfolioQueryService 為 WR-03 剩餘一半;唯讀交易在 PostgreSQL REPEATABLE READ 下不會有序列化失敗),Javadoc 補一句多道述句共用同一快照;`PortfolioQueryServiceTest` 加反射測試。若實作時發現 summary 會在外層讀寫交易內被呼叫,改為不動並於 SUMMARY 記錄理由。

**1d — 04.1 IN-01 / IN-05:**
- IN-01:刪除 `PortfolioQueryService` 未被呼叫的私有加總 helper(檔尾 `roi` 之前那個)與 `PortfolioCache` 的單鍵讀取方法 `readHolding`(只保留批次 `readHoldings`);spotless 的 removeUnusedImports 會在 test 時檢查殘留 import。刪除前 grep 確認 src/main 與 src/test 皆無呼叫。
- IN-05:`dayTotals` 的平倉候選迴圈中,查無資產屬性的候選要計入 `excluded`(進 `dayPnlExcludedCount`);持有中持倉查無屬性維持只計入 `fxExcludedCount`(契約第 ~185 行:換不到匯率者不在 dayPnlExcludedCount 內,避免重複計)。做法自選(例如 `DayAccumulator.add` 增加「此候選是否已計入 fxExcludedCount」參數),同步更新 `DayAccumulator#add` 的 Javadoc;契約 `dayPnlExcludedCount` 列補「今天賣光但查無資產屬性的平倉候選也計入」。

**1e — 04.2 REVIEW WR-02 / WR-04:**
- WR-02:`WatchlistService#addItem` 的 symbol 正規化改為 strip 後 `toUpperCase(Locale.ROOT)`(與 `DividendService` / `ShareAdjustmentService` / `ActivityQueryService` 一致);方法 Javadoc 的 `@param symbol` 改為「前後空白去除、大小寫不敏感」。`WatchlistServiceTest` 加 T1.5 單元案例,`WatchlistApiIT` 加小寫 symbol 案例(CI)。`ai-docs/watchlist-contract.md` 第 ~55 行 symbol 規則補「大小寫不敏感(後端以 `toUpperCase(Locale.ROOT)` 正規化)」,〈變更紀錄〉加條目。
- WR-04:`DividendService` 新增常數下限 1900-01-01T00:00:00Z,在 `validateNotInFuture` 旁(資產解析之前)檢查 `requestedPaidAt` 早於下限 → `FieldValidationException("Invalid paidAt", Map.of("paidAt", "must not be before 1900-01-01"))`;`ShareAdjustmentService` 在 `validateShape` 之後、`resolveTradeableAsset` 之前檢查 `request.effectiveDate()` 早於 `LocalDate` 1900-01-01 → `fields.effectiveDate` 同訊息(沿用 `EFFECTIVE_DATE_FIELD` 常數)。訊息為靜態英文,不回射輸入。單元測試 T1.6 / T1.7;`DividendApiIT` / `ShareAdjustmentApiIT` 各加一個極端日期(如 `-10000-01-01T00:00:00Z` / `1800-01-01`)→ 400 + `fields.*` 的案例(CI)。契約:`paidAt` body 列(~362 行)與錯誤表(~397 行)、〈生效時間與時序規則〉(~436 行)補「早於 1900-01-01 → 400」。**不要**順手擴到 cash ledger 的 occurredAt 或 trade 的 executedAt;若發現同類缺口,只在 SUMMARY 列為給 Yuan 的後續候選。

**收尾:** 跑完下方 verify 全部指令;SUMMARY 記錄每個子項的 RED / GREEN commit、測試數、grep 閘門前後值,並列出:(1) IT / E2E 待 CI 確認的新增案例清單;(2) 前端 HoldingEventDialog 對 `fields.paidAt` 顯示的是「不可晚於現在」文案,遇到下限錯誤會不貼切——列為給 Yuan 的後續觀察(不在本 task 修)。
  </action>
  <verify>
    <automated>./mvnw -q -o test -pl stock-common,stock-db-migration,stock-module-asset,stock-module-trading -am -Dtest='AssetFacadeImplTest,WatchlistServiceTest,TradingServiceTest,PortfolioQueryServiceTest,PortfolioCacheTest,DividendServiceTest,ShareAdjustmentServiceTest,CashLedgerServiceTest' -Dsurefire.failIfNoSpecifiedTests=false</automated>
    <automated>./mvnw -o -B test -Dtest='!*IT,!*E2E*' -Dsurefire.failIfNoSpecifiedTests=false</automated>
    <automated>./mvnw -q -o -pl stock-start -am test-compile</automated>
    <automated>./mvnw -q -o spotless:check</automated>
    <automated>test "$(grep -c 'unrealizedPnlInBase' stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/HoldingDto.java)" -ge 2   # baseline 0</automated>
    <automated>grep -q 'ck_assets_currency' stock-db-migration/src/main/resources/db/migration/V19__assets_currency_check.sql && test "$(ls stock-db-migration/src/main/resources/db/migration/ | grep -c '^V19__')" -ge 1   # baseline 0</automated>
    <automated>test "$(grep -c 'isolation = Isolation.REPEATABLE_READ' stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/TradingService.java)" -ge 1 && test "$(grep -c 'isolation = Isolation.REPEATABLE_READ' stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/PortfolioQueryService.java)" -ge 1   # baseline 0 / 0</automated>
    <automated>test "$(grep -c 'private BigDecimal sum(' stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/PortfolioQueryService.java)" -eq 0 && test "$(grep -c 'readHolding(' stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/PortfolioCache.java)" -eq 0   # baseline 1 / 1</automated>
    <automated>test "$(grep -c 'toUpperCase(Locale.ROOT)' stock-module-asset/src/main/java/dowob/xyz/stockwebv2/asset/service/WatchlistService.java)" -ge 1   # baseline 0</automated>
    <automated>grep -q 'unrealizedPnlInBase' ai-docs/trading-portfolio-contract.md && grep -q '1900-01-01' ai-docs/trading-portfolio-contract.md   # baseline 0 / 0</automated>
  </verify>
  <done>
    - 指定測試類別與全 reactor 單元測試(`!*IT,!*E2E*`)全綠、stock-start test-compile 通過、spotless:check 通過
    - 所有 grep 閘門達標(前值見各行註解)
    - 每個子項(1a–1e)都有 RED 與 GREEN 兩個 commit,scope `quick-261106-fbs`
    - 契約檔(trading-portfolio / watchlist)與程式行為一致,〈變更紀錄〉有 2026-11-06 條目
    - SUMMARY 列出待 CI 驗證的 IT / E2E 案例與給 Yuan 的後續觀察
  </done>
</task>

<task type="auto" tdd="true">
  <name>Task 2 [frontend repo — 路徑相對 /home/user/stock-web-v2-front-end]:A 前端小修(A1 / A2 / A3)+ B 前端 Top movers + C 前端 info(04.1 IN-02 / IN-03 / IN-04)</name>
  <files>
    vue-app/src/services/apiTypes.ts, vue-app/src/services/portfolioApi.ts, vue-app/src/services/portfolioApi.test.ts,
    vue-app/src/pages/Positions.vue, vue-app/src/pages/Positions.test.ts, vue-app/src/components/OrderTicket.test.ts,
    vue-app/e2e/support/portfolioStubs.ts,
    vue-app/src/i18n.ts, vue-app/src/i18n.test.ts,
    vue-app/src/components/CashEntryDialog.vue, vue-app/src/components/CashEntryDialog.test.ts,
    vue-app/src/styles.css, vue-app/src/useTweaks.ts, vue-app/src/contrastTokens.test.ts,
    vue-app/src/components/AuthPanel.vue, vue-app/src/components/Header.vue, vue-app/src/components/HoldingEventDialog.vue,
    vue-app/src/components/OrderTicket.vue, vue-app/src/components/PrefixIndicator.vue, vue-app/src/components/StrategyEditor.vue,
    vue-app/src/components/settings/SettingsAiAccess.vue, vue-app/src/components/settings/SettingsBrokers.vue,
    vue-app/src/components/settings/SettingsDataSources.vue, vue-app/src/components/settings/SettingsNotificationPreferences.vue,
    vue-app/src/pages/Alerts.vue, vue-app/src/pages/Analytics.vue, vue-app/src/pages/Backtest.vue, vue-app/src/pages/Chart.vue,
    vue-app/src/pages/Markets.vue, vue-app/src/pages/Notifications.vue, vue-app/src/pages/Ops.vue, vue-app/src/pages/Overview.vue,
    vue-app/src/pages/Settings.vue, vue-app/src/pages/Trades.vue, vue-app/src/pages/Watchlist.vue, vue-app/src/pages/Cash.vue,
    vue-app/src/pages/Overview.test.ts,
    vue-app/src/cashBalanceView.ts, vue-app/src/cashBalanceView.test.ts,
    vue-app/src/baseCurrencyErrorKey.ts, vue-app/src/baseCurrencyErrorKey.test.ts,
    vue-app/e2e/tests/layout.spec.ts
  </files>
  <behavior>
    - T2.1 Positions(API mode):三檔持倉 unrealizedPnlInBase = 500 / -1200 / null、baseCurrency TWD → 榜上只有兩檔,順序 -1200 那檔在前;金額文字為 `-1,200 TWD` 形式(fmtSignedMoney,0 位小數);提示 `positions-movers-fx-excluded` 顯示 `1 …`;任何 mover 文字不含 `$`;全部缺值時卡片仍顯示並只有提示
    - T2.2 Positions(mock mode):mover 金額以 USD 代碼呈現(MOCK_POSITION_CURRENCY),不含 `$`
    - T2.3 CashEntryDialog:清空發生時間並 blur → 錯誤文字等於 t(lang,'cashErrOccurredAtRequired');填 2999 年 → 仍為 cashErrOccurredAt;後端回 fields.occurredAt → cashErrOccurredAt
    - T2.4 contrastTokens:styles.css `:root` 有 `--up: #16a34a`、`--up-text: #15803d`、`--on-accent: #15171c`;深色區塊有 `--up-text: #16a34a`、`--dn: #ef4444`;紅漲區塊存在;所有 .vue 的 style 規則凡 background 為 var(--accent) 且宣告 color 者,color 皆為 var(--on-accent);`.quote-lab` 規則 color 為 var(--fg-dim);.vue style 區塊中沒有以 var(--up) 作為文字 color 的宣告
    - T2.5 useTweaks:set('upGreen', false) 後 documentElement.dataset.upColor === 'red' 且 documentElement.style 上沒有 --up / --dn inline 值;reset 後為 'green'
    - T2.6 cashBalanceView / baseCurrencyErrorKey 純函式:排序(TWD/USD/EUR/JPY,未知殿後、濾掉 0)、負值幣別、未折算幣別、zh 用「、」en 用「, 」、入金預設幣別(第一個負值否則基準幣);錯誤分派與原 Overview / Settings 行為逐案相同(含 401 → null)
    - T2.7 Overview:先發的 listTrades 較晚回來時不得覆蓋較新的結果(loadRecentTrades 與 refreshRecentTrades 皆然)
    - T2.8 Positions:summary 缺 sectorAllocation 時頁面不炸、產業卡為空狀態
  </behavior>
  <action>
**前置:** 分支 `claude/epic-curie-lj8alo`、工作樹乾淨;`npm` 一律在 `vue-app/` 下跑。前端鐵律(AGENTS.md):不碰 `vue-app/src/services/apiClient.ts`、不新增任何 `fetch`、元件不 import mock store、token 不入 JS 可讀處。強制 TDD:每子項 RED commit → GREEN commit,scope `quick-261106-fbs`,繁中 subject,附 attribution 兩行。
**先記錄 layout 基準線:** 動任何程式前跑一次 `cd vue-app && npx playwright test e2e/tests/layout.spec.ts`(page.route stub,不需後端;PLAYWRIGHT_BROWSERS_PATH 已設),把通過數(預期 97)記進 SUMMARY。

**2a(端到端切片,先做)— B 前端 Top movers(依 `<interfaces>` 契約,不等 Task 1):**
- `apiTypes.ts` `HoldingDto` 在 `marketValueInBase` 後加 `unrealizedPnlInBase: number | null`(附註解)。`portfolioApi.ts` mock adapter(約 169 行)補該欄位(mock 單一幣別,值同 unrealizedPnl,與 marketValueInBase 的做法一致);修正因型別必填而紅的 fixtures:`portfolioApi.test.ts`、`Positions.test.ts`、`OrderTicket.test.ts`;`e2e/support/portfolioStubs.ts` 的 `holding()` 預設補值,`marketValueInBase: null` 的列(SAP.DE、7203.T)同為 null。
- `Positions.vue`:`apiTopMovers` 先濾掉 `unrealizedPnlInBase == null`,以其絕對值排序取前 4,`pnl` 取 unrealizedPnlInBase、`pct` 維持 `roi * 100`,並帶出該列 `baseCurrency`;新增 computed 計算被排除檔數。模板金額改用 `fmtSignedMoney(m.pnl, m.ccy, 0)`(mock mode 的 ccy 用既有 `MOCK_POSITION_CURRENCY`),移除寫死的美元符號與舊的手刻正負號 + fmtNum 組合;卡片 `v-if` 改為「mock、或有 mover、或有被排除者」;被排除數 > 0 時在卡內顯示 `data-testid="positions-movers-fx-excluded"` 提示,文案新 i18n 鍵 `topMoversFxExcluded`(zh `{n} 檔缺少換算成 {base} 的匯率,未列入排行。`/ en `{n} holdings have no exchange rate to {base} and are not ranked.`),`{base}` 取 summary 或持倉的 baseCurrency。更新模板上方註解(改述以 unrealizedPnlInBase 排序)。
- 更新既有測試「Top movers 依後端 unrealizedPnl 絕對值排序取前 4」為新語意,加 T2.1 / T2.2。

**2b — A1 入出金清空文案:**
- `i18n.ts` zh / en 各加 `cashErrOccurredAtRequired`(zh `請填寫發生時間。`/ en `Occurred at is required.`),放在 `cashErrOccurredAt` 旁;`i18n.test.ts` 把新鍵加入 `PHASE41_KEYS`(並把「81 個」註解改為實際數),另為 `topMoversFxExcluded` 建一個本 quick task 的鍵清單測試(兩語皆有、佔位符 `{n}` `{base}` zh / en 一致)。
- `CashEntryDialog.vue`:`fieldErrorText('occurredAt')` 在「本地自檢失敗且輸入為空」時回 `cashErrOccurredAtRequired`;本地未來時間與後端 `fields.occurredAt` 維持 `cashErrOccurredAt`(`FIELD_ERROR_COPY` 不改)。`CashEntryDialog.test.ts` 加 T2.3。

**2c — A2 對比度 token(Yuan 於對比度樣式頁裁定的值,不得更動):**
- `styles.css`:`:root` 新增 `--up-text: #15803d` 與 `--on-accent: #15171c`(`--up` 維持 #16a34a、`--accent` 維持 #ff6600);深色區塊新增 `--up-text: #16a34a`、`--dn: #ef4444`;新增紅漲綠跌區塊 `:root[data-up-color="red"]`(淺色:`--up` / `--up-text` 為 #dc2626、`--dn` 為 #15803d)與 `:root[data-theme="dark"][data-up-color="red"]`(`--up` / `--up-text` 為 #ef4444、`--dn` 為 #16a34a)——`--dn` 兼作文字色,所以紅漲模式下取綠色的文字安全值。
- `useTweaks.ts`:**根因**——目前 watcher 以 inline custom property 寫 `--up` / `--dn`,inline 優先權高於任何選擇器,會讓 styles.css 的深色 `--dn` 與新 token 失效。改為設定 `document.documentElement.dataset.upColor = v.upGreen ? 'green' : 'red'`,並以 `removeProperty` 清掉這兩個 inline 值;`--accent` 的 inline 設定不變。測試 T2.5。
- 文字改用 `--up-text`:逐一檢視 `var(--up)` 的所有用法(規劃時基準:.vue style 區塊中 `color: var(--up)` 宣告 30 處、模板 / TS 內 `'var(--up)'` 字串 19 處)。規則:作為**文字顏色**(含 pill / chip / 漲跌數字 / KPI / `.up` 類別 / `.live.on` 文字 / Overview `toneColor`、OrderTicket `changeColor` 這類回傳給文字 `color` 的 helper)→ `var(--up-text)`;作為 background、border、box-shadow、圓點、圖表填色或以 `color` 驅動 SVG `currentColor` 的走勢圖容器(例如 Overview 約 400 行的 70×24 sparkline 容器、Analytics waterfall 的 bar 色)→ 維持 `var(--up)`。每一處的歸類寫進 SUMMARY 表格;`Positions.test.ts` 約 1321 行、`Overview.test.ts` 約 234 行斷言文字 style 的地方改斷言 `var(--up-text)`(約 236 行的否定斷言維持)。
- accent 底白字:所有 style 規則中 background 為 `var(--accent)` 且文字色為白(#fff / white)者,文字色改 `var(--on-accent)`。規劃時以規則掃描找到 22 條:AuthPanel `.auth-submit`、CashEntryDialog / HoldingEventDialog / OrderTicket / StrategyEditor / SettingsNotificationPreferences / Alerts / Backtest / Ops / Overview / Positions / Settings / Trades 的 `.btn-accent`、Header `.logo`、OrderTicket `.sym-tag`、PrefixIndicator `kbd`、SettingsAiAccess / SettingsBrokers / SettingsDataSources `.banner-icon`、Analytics `.apply-btn`、Markets `.quick-order:hover`、Notifications `.btn-ghost.active`(實作時以 T2.4 的掃描測試為準,掃到幾條改幾條)。`.btn-accent.buy`(background 改 `--up`)會繼承深色字:預設綠漲下 5.44:1 通過;紅漲模式下約 3.7:1——不在 Yuan 裁定範圍,列入 SUMMARY 給 Yuan,不自行處理。

**2c-addendum — orchestrator 補充(Yuan 2026-11-06 追加裁定,優先於上段對 `.btn-accent.buy` 的處理):**
- **買賣方向按鈕(OrderTicket `.btn-accent.buy` / `.sell`,以及任何以 `--up` / `--dn` 為底色、白字的按鈕):** 改用與文字 token 分離的按鈕底色 token,兩種主題同值:`--btn-up-bg: #15803d`(白字 5.02:1)、`--btn-dn-bg: #dc2626`(白字 4.83:1),文字維持白色 `#fff`(**不**套 `--on-accent`)。理由:Yuan 選「綠底改 #15803d」(白字);深色主題 `--dn` 改 `#ef4444` 後白字只有 3.76:1,故賣出鈕不可沿用 `--dn`。紅漲綠跌偏好(upGreen=false)下,這兩個按鈕底色 token 必須與 `--up` / `--dn` 一同對調(買進 = 上漲色)。加掃描 / 元件測試鎖住:買進 / 賣出鈕底色取自 `--btn-up-bg` / `--btn-dn-bg`、文字為白。
- **「記錄交易」按鈕尺寸(OrderTicket footer 的 `.btn-accent`):** `min-height` 44px → **36px**(與同列 `.btn-ghost` 同高)、`padding` 8px 24px → **8px 16px**;`.btn-submit` 的 `min-width: 136px` 保留(避免「記錄中…」標籤切換跳動),並更新該段 CSS 註解的寬度推算。只改 OrderTicket;其他對話框(CashEntryDialog / HoldingEventDialog …)的 `.btn-accent` 高度不動,於 SUMMARY 列出供 Yuan 決定是否統一。layout.spec 需涵蓋 320px footer 不換行。
- `.quote-lab`(CashEntryDialog、OrderTicket、HoldingEventDialog 三處)文字色改 `var(--fg-dim)`;OrderTicket 與 `.qm-l` 共用同一條規則,一併改(同為標籤角色)。
- 新檔 `vue-app/src/contrastTokens.test.ts` 實作 T2.4 / T2.5:以 Vite `?raw` 讀 `styles.css`,以 `import.meta.glob('./**/*.vue', { query: '?raw', import: 'default', eager: true })` 掃 style 區塊(沿用 OrderTicket.test.ts 的 `?raw` 手法);若需要允許清單(以 color 驅動 SVG 的容器),寫在測試內並逐條附理由。
- 觀察但不修:accent 色文字(例如 `.tab.active .tab-count`)、其他使用 mute token 的提示字等仍不足 AA 者,列進 SUMMARY 給 Yuan。

**2d — A3 Markets < 640px 與 L12 收緊:**
- `Markets.vue`(scoped style):頁首 `.row-between` 允許換行並加 gap;搜尋框寬度改為不超過容器(`min(280px, 100%)`,focus 的 320px 同理),`.search-wrap` 可縮;tabs 列改為列內水平捲動(`overflow-x: auto`,可參考 Watchlist.vue 已通過 L11 的 tabs 寫法),LIVE 鈕不被壓縮。先用 Playwright 在 320 / 375(zh / en)量測找出所有撐寬 `.main` 的元素(04.2-25 量到 320px zh 439px、en 591px),逐一修到 `.main` 不再水平捲動;只改 Markets.vue,若表格也撐寬則讓表格在卡內捲動(比照 L5 的作法)。
- `e2e/tests/layout.spec.ts` L12:移除 `< 640px` 的例外分支與 deferred annotation,所有寬度一律 `expectNoHorizontalScroll`;並在開啟選擇器**之前**也斷言 `.main` 不水平捲動;刪掉註解中「既有骨架撐寬 .main 的例外」段落,改述目前保證。

**2e — C 前端 info:**
- IN-02:新檔 `vue-app/src/cashBalanceView.ts`(純函式:依 BASE_CURRENCY_OPTIONS 排序並濾 0 的餘額、餘額清單文字(沿用 fmtMoneyAmount)、負值幣別、未折算幣別、依語言串接幣別碼、入金預設幣別)與 `vue-app/src/baseCurrencyErrorKey.ts`(原 Overview `currencyErrorKey` / Settings `saveErrorKey` 的分派);Overview.vue、Cash.vue 改用前者(computed 只包一層呼叫,Cash 的 mock / 無 summary 回 TWD 規則保留在頁面),Overview.vue、Settings.vue 改用後者;各自新增 `*.test.ts`(T2.6)。CashEntryDialog 的錯誤對照表不動。
- IN-03:Overview 加 `tradesSeq` 序號守門(與 `summarySeq` 同模式),`loadRecentTrades` 與 `refreshRecentTrades` 皆只套用最新請求;`Overview.test.ts` 加 T2.7。
- IN-04:Positions `sectorRows` 的兩處 sectorAllocation 存取改為先 `?? []` 再 filter;`Positions.test.ts` 加 T2.8。

**收尾:** 跑完下方 verify 全部指令,layout.spec 通過數不得少於基準線(L12 收緊後仍全過);SUMMARY 附 2c 的 `var(--up)` 歸類表、accent 規則清單、給 Yuan 的觀察清單,以及 HoldingEventDialog 清空入帳時間同樣沿用「不可晚於現在」文案的觀察(不在本 task 修)。
  </action>
  <verify>
    <automated>cd vue-app && npx vitest run src/contrastTokens.test.ts src/components/CashEntryDialog.test.ts src/i18n.test.ts src/pages/Positions.test.ts src/pages/Overview.test.ts src/cashBalanceView.test.ts src/baseCurrencyErrorKey.test.ts src/services/portfolioApi.test.ts</automated>
    <automated>cd vue-app && npm test && VITE_DATA_MODE=api npm test && npm run build && npm run lint</automated>
    <automated>cd vue-app && npx playwright test e2e/tests/layout.spec.ts</automated>
    <automated>test "$(grep -c 'cashErrOccurredAtRequired' vue-app/src/i18n.ts)" -ge 2 && test "$(grep -c 'unrealizedPnlInBase' vue-app/src/services/apiTypes.ts)" -ge 1   # baseline 0 / 0</automated>
    <automated>test "$(grep -rnE 'background: *var\(--accent\); *color: *(#fff|white)' vue-app/src --include=*.vue | wc -l)" -eq 0   # baseline 21(同一行者;跨行者由 contrastTokens.test.ts 守)</automated>
    <automated>test "$(grep -cE "setProperty\('--(up|dn)'" vue-app/src/useTweaks.ts)" -eq 0 && grep -q 'upColor' vue-app/src/useTweaks.ts   # baseline 2 / 0</automated>
    <automated>test "$(grep -c 'fmtNum(Math.abs(m.pnl)' vue-app/src/pages/Positions.vue)" -eq 0 && test "$(grep -c 'sectorAllocation.filter' vue-app/src/pages/Positions.vue)" -eq 0 && test "$(grep -c 'tradesSeq' vue-app/src/pages/Overview.vue)" -ge 2   # baseline 1 / 2 / 0</automated>
    <automated>test "$(grep -c "type: 'deferred'" vue-app/e2e/tests/layout.spec.ts)" -eq 0   # baseline 1</automated>
    <automated>test -z "$(git diff --name-only b4ff61e..HEAD -- vue-app/src/services/apiClient.ts)"   # 鐵律 1:Transport 邊界不得被動到;b4ff61e = 規劃時前端 HEAD</automated>
  </verify>
  <done>
    - vitest(mock 與 api 兩模式)全綠、build(含 vue-tsc)與 lint 通過
    - layout.spec 全綠,通過數 ≥ 動工前基準線;L12 在 320 / 375 / 768 / 1280 × zh / en 皆斷言 .main 無水平捲動
    - 所有 grep 閘門達標(前值見各行註解);apiClient.ts 未被修改
    - 每個子項(2a–2e)有 RED / GREEN commit,scope `quick-261106-fbs`
    - SUMMARY 含 layout 前後通過數、var(--up) 歸類表、accent 規則清單、給 Yuan 的觀察清單
  </done>
</task>

<task type="auto">
  <name>Task 3 [both repos — [BE] 路徑相對 /home/user/stock-web-v2、[FE] 路徑相對 /home/user/stock-web-v2-front-end;automated 的 cwd 為 backend 根](依賴 Task 1 + Task 2):D 全文件掃描 + 審查處置 + todo 關閉</name>
  <files>
    [BE] ai-docs/*.md(含 bug-reports/INDEX.md 的連結有效性;其餘 bug-reports/*.md 為歷史紀錄不改寫),
    [BE] .planning/codebase/ARCHITECTURE.md, [BE] .planning/codebase/CONCERNS.md, [BE] .planning/codebase/CONVENTIONS.md,
    [BE] .planning/codebase/INTEGRATIONS.md, [BE] .planning/codebase/STACK.md, [BE] .planning/codebase/STRUCTURE.md,
    [BE] .planning/codebase/TESTING.md,
    [BE] .planning/phases/04.1-backend-data-gap-backfill/04.1-UI-SPEC.md,
    [BE] .planning/phases/04.1-backend-data-gap-backfill/04.1-REVIEW-DISPOSITION.md,
    [BE] .planning/phases/04.2-dividends-share-adjustments-watchlist/04.2-REVIEW-DISPOSITION.md,
    [BE] .planning/STATE.md,
    [BE] .planning/todos/pending/ → .planning/todos/completed/(5 條)、.planning/todos/pending/2026-09-28-fx-pnl-and-rate-history.md(追加)、.planning/todos/pending/2026-11-06-cash-amount-string-serialization.md(新建),
    [FE] AGENTS.md, [FE] docs/LEARNINGS.md, [FE] docs/api-contracts/mock-to-real-contract.md, [FE] docs/superpowers/**(僅必要時加「歷史文件」標示)
  </files>
  <precondition>Task 1 與 Task 2 皆已完成:`git log --oneline --grep 'quick-261106-fbs' | head` 在 backend 根與 `../stock-web-v2-front-end` 皆列出 GREEN commit,且兩個 repo 工作樹乾淨;未滿足即停止並回報 orchestrator。</precondition>
  <action>
**原則:** 程式碼是事實來源;以 grep 驗證文件中的可查證主張(路徑、類別 / 方法名、端點與權限、設定鍵與值、上限數字、migration 版號、Redis 鍵、錯誤碼、指令),錯就以程式為準修文件;無法判斷者不猜,列入 SUMMARY 的「給 Yuan 裁決」清單(每條附文件位置、程式證據、可選方案)。只改文件,不改任何程式或測試。開工時記下兩個 repo 的 `git rev-parse HEAD`(下稱 T3_BASE_BE / T3_BASE_FE)供收尾檢查。每組文件一個 commit:`docs(quick-261106-fbs): …`(繁中 subject,附 attribution 兩行),以便 context 中斷時依 commit 接續。

**3a — 04.2 G2 三處(先做):**
- [BE] `ai-docs/watchlist-contract.md` 第 ~9 行的「Phase 04.2 凍結契約」提示:改寫為已實作(04.2-11 / 04.2-12 已合入),目前程式碼是事實來源、偏離時以程式為準回頭修本檔;移除「實作前程式不存在」的過時敘述。〈上限與併發保證〉表的清單數與項目數兩列,把「鎖 → count → insert」補明:count 必須是**取得列鎖之後另起的查詢**(READ COMMITTED 下鎖查詢本身的快照在等鎖前取得,看不到前一個持鎖交易剛 commit 的列;見 `WatchlistService#create` 與 `#addItem` 的註解)。
- [BE] `ai-docs/trading-portfolio-contract.md` 第 ~167 行 04.2 D-01 語意變更提示的日期改為 2026-11-01(裁決日,見 04.2-26-SUMMARY G2);同檔 ~175、~176、~556 行同一語意變更的「〔04.2〕2026-10-31 起」字樣若指同一裁決,一併對齊;若判斷為「生效 / 實作日」而非裁決日,保留並列給 Yuan。

**3b — ai-docs 其餘逐份掃描:** 依序處理 architecture、browser-auth-contract、security、trading-portfolio-contract(含 Task 1 的新增是否一致)、watchlist-contract、redis-convention(持倉鍵說明需反映 unrealizedPnlInBase 為回應時補值、不入快取)、flyway-convention(規劃時已見一處漂移:「選號前查看 stock-start 的 migrations」實際目錄為 `stock-db-migration/src/main/resources/db/migration/`;最高版號現為 V19)、event-conventions、code-standards、testing-standards、git-convention、judgment、task-briefs、maintenance-protocol、model-dispatch、harness-diagnosis。`letter-to-future-sessions.md` 與 `bug-reports/*.md` 屬敘事 / 歷史紀錄:只修失效的路徑連結,不改寫內容。

**3c — `.planning/codebase/` 重產([BE]):** 7 份最後分析於 2026-05-30。優先使用 `gsd-map-codebase` skill(由 mapper agents 平行重產,保住本 executor 的 context);若本執行環境無法呼叫 skill,改為逐份針對性更新:frontmatter `last_mapped_commit` 與 Analysis Date 更新為現況,修正模組清單、migration V1–V19、主要端點與服務、測試指令與數量等與程式衝突處,仍來不及驗證的段落列給 Yuan。

**3d — 前端文件([FE]):**
- `AGENTS.md`(上限約 120 行):規劃時已見的漂移種子——`mock-to-real-contract.md` 標示的行數(寫 872,實際約 933)、後端 `ai-docs/judgment.md` / `task-briefs.md` 「合併後才存在」(現已存在)、CI 步驟(實際為 `npm ci → lint → test → build`)、鐵律 4 對 Common API Conventions 一節「過時草案」的描述(該節現已改寫為後端 `ApiResponse` 信封,逐句核對後更新鐵律 4 的措辭,鐵律本身「信封權威是後端」不變)。逐條核對其餘敘述(技術棧版本對 `vue-app/package.json`、驗證命令、按需路徑是否存在)。
- `docs/api-contracts/mock-to-real-contract.md`:以後端 controllers(`@*Mapping` 與 `@PreAuthorize`)與 `ErrorCode` 核對 Backtest / Ops / AI-Access 端點、權限與錯誤碼;不一致處以後端為準修正或列給 Yuan。
- `docs/LEARNINGS.md`:核對格式;`docs/superpowers/plans|specs/` 是歷史計畫,不改寫,若檔首沒有「歷史文件、不隨程式維護」標示則加一行。

**3e — 審查處置與 UI-SPEC([BE]):**
- `04.1-UI-SPEC.md` Copywriting 對照第 ~379 行(`occurredAt` 列)後加一列:清空發生時間 → `請填寫發生時間。` / `Occurred at is required.` / `cashErrOccurredAtRequired`(與 Task 2 的 i18n 實際值逐字核對)。
- `04.1-REVIEW-DISPOSITION.md`:WR-02、WR-03、IN-01 ~ IN-05 → `fixed`(附 quick-261106-fbs 與對應 commit);WR-01 → deferred 並指向 todo fx-pnl-and-rate-history;WR-04 → deferred 並指向 Phase 5 與新 todo。
- `04.2-REVIEW-DISPOSITION.md`:frontmatter 與表格的 WR-02、WR-04 → `fixed`(附 quick-261106-fbs 與 commit);其他列不動。
- `.planning/STATE.md` 只用 scoped Edit 改兩處:`### Pending Todos` 的數量與分類(本次關閉 5 條、新增 1 條,盤點日 2026-11-06),以及 Blockers 中 04.2 那一條(WR-02 / WR-04、G2、Markets < 640px 已由本 quick task 處理)。其他段落(含 orchestrator 會更新的 quick task 紀錄)不動。

**3f — todo 關閉([BE],慣例同 commit db7fbd8):** 以 `git mv` 把下列 5 條從 `pending/` 移到 `completed/`,frontmatter 加 `resolved: 2026-11-06` 與 `resolved_by: quick-261106-fbs(後端 / 前端 commit 範圍;PR 待 orchestrator 開)`,檔尾加 `## Resolution`(實際做法、偏離原建議之處、證據指向 SUMMARY):`2026-10-25-cash-occurred-at-empty-copy.md`、`2026-10-25-top-movers-base-currency.md`、`2026-10-26-contrast-text-tokens.md`、`2026-10-26-phase-04.1-review-followups.md`(Resolution 明列 WR-01 / WR-04 依 Yuan 裁決延後及其去處)、`2026-10-31-full-docs-sweep.md`(Resolution 附「給 Yuan 裁決」清單的摘要或連結)。另外:在 `pending/2026-09-28-fx-pnl-and-rate-history.md` 追加一節「併入 04.1 WR-01(基準幣總額未標示匯率時間)」;新建 `pending/2026-11-06-cash-amount-string-serialization.md`(frontmatter 仿既有 todo,area portfolio,target Phase 5;內容摘錄 04.1 WR-04 問題與方向,source 指向 04.1-REVIEW.md 與本 quick task)。

**收尾:** 跑下方 verify;SUMMARY 列出每份文件的改動摘要、「給 Yuan 裁決」清單、以及本 task 只動了 `.md`(含 todo)的證明。
  </action>
  <verify>
    <automated>test "$(grep -c '程式碼尚不存在' ai-docs/watchlist-contract.md)" -eq 0 && test "$(grep -c '2026-10-31 語意變更' ai-docs/trading-portfolio-contract.md)" -eq 0   # baseline 1 / 1</automated>
    <automated>test "$(ls .planning/todos/pending/ | grep -cE '2026-10-25-cash-occurred-at-empty-copy|2026-10-25-top-movers-base-currency|2026-10-26-contrast-text-tokens|2026-10-26-phase-04.1-review-followups|2026-10-31-full-docs-sweep')" -eq 0   # baseline 5</automated>
    <automated>for f in 2026-10-25-cash-occurred-at-empty-copy 2026-10-25-top-movers-base-currency 2026-10-26-contrast-text-tokens 2026-10-26-phase-04.1-review-followups 2026-10-31-full-docs-sweep; do grep -q '^resolved: 2026-11-06' .planning/todos/completed/$f.md && grep -q '^resolved_by:' .planning/todos/completed/$f.md && grep -q '^## Resolution' .planning/todos/completed/$f.md || { echo "MISSING $f"; exit 1; }; done</automated>
    <automated>test -f .planning/todos/pending/2026-11-06-cash-amount-string-serialization.md && grep -q 'WR-01' .planning/todos/pending/2026-09-28-fx-pnl-and-rate-history.md</automated>
    <automated>test "$(grep -l 'Analysis Date:\*\* 2026-05-30' .planning/codebase/*.md | wc -l)" -eq 0   # baseline 7</automated>
    <automated>grep -q 'cashErrOccurredAtRequired' .planning/phases/04.1-backend-data-gap-backfill/04.1-UI-SPEC.md && test "$(grep -A2 -E 'id: WR-0(2|4)$' .planning/phases/04.2-dividends-share-adjustments-watchlist/04.2-REVIEW-DISPOSITION.md | grep -c 'disposition: fixed')" -ge 2   # baseline 0 / 0</automated>
    <automated>test "$(wc -l < ../stock-web-v2-front-end/AGENTS.md)" -le 120</automated>
    <automated>BE_FILES=$(git log --format= --name-only --grep '^docs(quick-261106-fbs)') && FE_FILES=$(git -C ../stock-web-v2-front-end log --format= --name-only --grep '^docs(quick-261106-fbs)') && test -z "$(printf '%s\n%s\n' "$BE_FILES" "$FE_FILES" | grep -vE '\.md$|^$')"   # Task 3 的 docs commit 只含 .md;收尾時另以 T3_BASE_BE / T3_BASE_FE 做 git diff 複核並寫入 SUMMARY</automated>
  </verify>
  <done>
    - G2 三處修正;ai-docs、.planning/codebase、前端 AGENTS.md 與 docs 與程式一致,無法判斷者列給 Yuan
    - 04.1 / 04.2 審查處置與 04.1 UI-SPEC 已同步;STATE 只動兩處
    - 5 條 todo 移入 completed/ 且具 resolved / resolved_by / ## Resolution;WR-01 併入 fx-pnl-and-rate-history、WR-04 有新 todo(Phase 5)
    - 本 task 的 commit 只含 .md 檔;所有 grep 閘門達標
  </done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| browser → REST API | 不可信的 body 欄位(`symbol`、`paidAt`、`effectiveDate`)進入 watchlist / dividends / share-adjustments 服務 |
| DB(assets 表)→ application | 以 migration / 直接改庫寫入的資產列被 enum 映射;壞列曾讓整個 portfolio 讀取路徑 500 |
| API JSON → frontend 顯示 | 前端只呈現後端算好的基準幣值(不在前端換匯) |
| server log | WARN 訊息內容不得夾帶 DB 原始字串 |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-fbs-01 | Denial of Service | `AssetFacadeImpl` 映射(summary / holdings / trades 熱路徑) | medium | mitigate | 壞 asset_type / currency 列以 try/catch 略過並 WARN,不讓 IllegalArgumentException 逸出;V19 `ck_assets_currency` 作第二道防線(Task 1 1b) |
| T-fbs-02 | Information Disclosure / log injection | `AssetFacadeImpl` WARN log | low | mitigate | 只記數字 id,不記 symbol / currency / asset_type 原值(比照 FxRateFacadeImpl) |
| T-fbs-03 | Denial of Service / error disclosure | `POST /portfolio/dividends`、`POST /portfolio/share-adjustments` | medium | mitigate | 1900-01-01 下限於任何 DB 綁定與 Facade 呼叫前檢查,回 400 `fields.paidAt` / `fields.effectiveDate`(靜態英文訊息、不回射輸入),取代 catch-all 500(Task 1 1e) |
| T-fbs-04 | Tampering | `WatchlistService#addItem` symbol | low | mitigate | `toUpperCase(Locale.ROOT)` 正規化(避免 locale 相依轉換),查詢維持具名參數;授權與 `(uuid, user_id)` 列鎖不變 |
| T-fbs-05 | Tampering | V19 migration(共用環境單向門) | medium | mitigate | 規劃時已驗證種子與測試資料皆合法;migration 註解附違規列診斷查詢;一經套用只能以新 migration 調整(Yuan 已裁決採 CHECK) |
| T-fbs-06 | Information Disclosure | `HoldingDto.unrealizedPnlInBase` | low | accept | 端點仍為 `PORTFOLIO_VIEW` 且只回登入者自己的持倉;新欄位由使用者已可見的資料衍生 |
| T-fbs-07 | Tampering(錯誤排名) | Positions Top movers | low | mitigate | 前端不換匯,只用後端 `unrealizedPnlInBase`;缺值持倉排除並顯示可見提示,不靜默錯排(前端鐵律 6 精神) |
| T-fbs-08 | Elevation of Privilege / token exposure | 前端 transport 邊界 | high | mitigate | Task 2 不得修改 `vue-app/src/services/apiClient.ts`、不新增 fetch、不碰 token 儲存;verify 以 git diff 檢查 apiClient.ts 未變動,`mockStoreBoundary` / `api-adapter-wiring` 測試在 `npm test` 內持續守門 |
| T-fbs-09 | Repudiation | 文件與程式漂移 | low | mitigate | Task 3 以程式為準修文件,無法判斷者列給 Yuan,審查處置檔記錄每條 finding 的去向 |
| T-fbs-SC | Tampering | npm / maven 安裝 | low | accept | 本 task 不新增任何套件或依賴;若 executor 發現需要新套件,停止並回報(不得自行安裝) |
</threat_model>

<verification>
- 後端:`./mvnw -o -B test -Dtest='!*IT,!*E2E*' -Dsurefire.failIfNoSpecifiedTests=false` 全綠、`./mvnw -q -o -pl stock-start -am test-compile` 與 `./mvnw -q -o spotless:check` 通過;新增的 IT / E2E 案例待 PR 的 CI 確認(本機無 Docker)。
- 前端(`vue-app/`):`npm test`、`VITE_DATA_MODE=api npm test`、`npm run build`、`npm run lint` 全綠;`npx playwright test e2e/tests/layout.spec.ts` 動工前後皆全綠且通過數不減。
- 跨 repo:後端 JSON 與前端型別都有 `unrealizedPnlInBase`(名稱、位置、null 語意同 `<interfaces>`)。
- 文件:Task 3 的 grep 閘門全數達標,5 條 todo 已關閉。
</verification>

<success_criteria>
- 9 條 must_haves truths 全部可觀察成立
- 兩個 repo 在 `claude/epic-curie-lj8alo` 上各有一組乾淨的 `quick-261106-fbs` commits(程式 RED / GREEN 分開、文件獨立),可由 orchestrator 各開一個 PR 到 develop
- SUMMARY 含:各 task 的驗證輸出摘要、layout 前後通過數、待 CI 的 IT / E2E 清單、給 Yuan 的觀察與裁決清單
</success_criteria>

<output>
完成後建立 `/home/user/stock-web-v2/.planning/quick/261106-fbs-small-fixes-batch-a-markets-640px-b-top-/261106-fbs-SUMMARY.md`(三個 executor 各自負責的段落可分節;以 orchestrator 的 quick workflow 為準)。
</output>
