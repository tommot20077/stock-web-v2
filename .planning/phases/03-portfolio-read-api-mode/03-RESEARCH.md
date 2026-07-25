# Phase 3: Portfolio Read API Mode - Research

**Researched:** 2026-07-23
**Domain:** 跨 repo 整合(Vue 3 前端 API adapter + Spring Boot 4 後端查詢擴充 + PostgreSQL 排序/篩選/索引)
**Confidence:** HIGH(絕大多數結論來自本 session 直接讀取兩個 repo 的程式碼)

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Phase Boundary(節錄):**
- 讓 API mode 能從後端讀取 portfolio 三塊資料(summary / holdings / trade history)並映射到**現有** Overview、Positions、Trades 三個頁面,同時 mock mode 維持完全獨立運作。
- **本階段包含後端改動**:`GET /api/v1/trades` 需補篩選(交易類型、日期區間)與排序參數(D-05/D-06)。
- PORT-01 實為「取代合成假資料」而非接線:`Overview.vue` 四張 KPI 卡無一來自真實持倉。
- **不在本階段:** 交易建立(Phase 4)、Analytics/Alerts/Watchlist 等頁面(PORT-06,v2 deferred)、股利(DIV)交易類型、日級損益、可用現金、資產分類(皆已記 todo)。

**欄位映射與落差:**
- **D-01:** `sector` 本階段不處理(後端 `HoldingDto` 無此欄位是可接受的)。*(研究更正見 Open Questions Q1:sector 其實也出現在 `Positions.vue` 的 Sector breakdown 卡,決策結論不變,但 API mode 需隱藏該卡。)*
- **D-02:** 股利(DIV):API mode **隱藏**交易頁的「Dividend」篩選頁籤;mock mode 保留。
- **D-03:** 顯示 `HoldingDto.priceTime` 作為行情時間。

**計算歸屬(單一真相來源):**
- **D-04:** API mode 的 P&L / ROI / 市值 / 成本一律使用後端算好的值,前端不得重算後端已提供的數值。**例外:** 後端未提供、只能由後端值衍生的欄位(如 `weight = marketValue / totalMarketValue`)仍在前端計算,且必須衍生自後端值而非原始 qty×price。

**交易查詢:分頁 / 篩選 / 排序:**
- **D-05:** 後端 `GET /trades` 新增篩選參數(交易類型、日期區間)。`2026` chip 是硬編年份,實作改為動態當年度(或日期區間選擇器)。
- **D-06:** 後端 `GET /trades` 新增排序參數,三個排序鍵:`executedAt`、`total`(金額 = `quantity × price`,計算式排序)、`quantity`,皆需升降序;評估運算式索引,必要時依 flyway-convention 新增 migration。
- **D-07:** 預設排序 `executedAt` 降序,預設 `size` 20。
- **D-08:** 分頁 UI 採換頁按鈕(上一頁/下一頁 + 當前頁數),不用 append / infinite-scroll。
- **D-09:** `Overview.vue` 近期交易改為 `GET /trades?page=0&size=5`,不與交易頁共用跨頁面狀態。
- **D-10:** CSV 匯出範圍為當前篩選與排序條件下的**所有頁**(以相同 filter/sort 參數循環拉完);篩選語意必須保留。
- **D-14:** Overview KPI 卡在 API mode 只保留兩張:總資產 ← `totalMarketValue`、總報酬 ← `roi`;隱藏「今日損益」「可用現金」與資產配置 donut。mock mode 全部保留。`PortfolioSummaryDto` 其餘欄位呈現位置由 planner 決定(`Positions.vue` 彙總條是自然落點)。
- **D-15:** 篩選或排序條件變更時頁碼重置為第 0 頁;處理「請求頁碼 ≥ `totalPages`」的回退。

**錯誤與狀態呈現:**
- **D-11:** loading / empty / error / retry 各 view 內嵌,retry 只重試該區塊。
- **D-12:** trace id 只在錯誤狀態顯示(錯誤碼 + traceId)。
- **D-13:** 全域 `SessionBanner` 保留給 session / 認證類錯誤。

### Claude's Discretion

使用者對所有提問都給了明確選擇,無「你決定」項目。實作細節(元件拆分、loading 骨架樣式、換頁按鈕的具體版面)由 planner/executor 依現有 UI 慣例決定。

### Deferred Ideas (OUT OF SCOPE)

- 後端支援 DIV(股利)交易類型 — `.planning/todos/pending/2026-07-19-backend-dividend-trade-type.md`
- 後端支援日級損益(今日損益 KPI)— `.planning/todos/pending/2026-07-19-backend-daily-pnl.md`
- 後端支援可用現金 / 帳戶餘額模型 — `.planning/todos/pending/2026-07-19-backend-available-cash.md`
- 後端支援資產分類(產業別 / 資產類別)— `.planning/todos/pending/2026-07-19-backend-asset-classification.md`
- 多幣別呈現、空持倉初始引導、mock↔api 切換資料殘留(可選深入項目,未展開)
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| PORT-01 | API mode 讀取 portfolio summary 並映射到現有 overview UI | 後端 `GET /portfolio/summary` 已存在且回 7 欄 `PortfolioSummaryDto`(已查證);D-14 界定只接兩張 KPI;隱藏項清單見 Pitfall 8 與 Q1/Q4 |
| PORT-02 | API mode 讀取 holdings/positions 並映射到 positions UI | 後端 `GET /portfolio/holdings` 回 `List<HoldingDto>`(13 欄,不分頁);欄位映射表見 Code Examples;Positions 頁合成資料區塊盤點見 Pitfall 8 |
| PORT-03 | API mode 讀取 trade history 含分頁/排序 response mapping | `apiPaginatedRequest<T>` 已與後端 `PageResponse<T>` 同形,可直接用;`TradeDto → Trade` 映射見 Code Examples |
| PORT-04 | 保留 mock implementation;API mode 不直接讀寫 mock store | Service 三件組樣板(`backtestApi.ts`)+ `pageApiClients.ts` 註冊 + `api-adapter-wiring.test.ts` 的「mock factory 未被呼叫」斷言模式 |
| PORT-05 | loading/empty/error/retry 狀態 + request/trace id | `ApiClientError.requestId`(來自 `meta.traceId`)已存在;`SessionBanner.vue` 是錯誤呈現樣式參考;D-11/D-12/D-13 界定範圍 |
| PORT-08 | `GET /trades` 篩選(類型、日期區間)與排序(`executedAt`/金額/`quantity`)參數 | 後端擴充點在 `TradingController.listTrades` → `TradingService.listTrades` → `JdbcTradingRepository.listTransactions`;排序白名單 + 運算式索引方案見 Architecture Patterns 與 Code Examples;V9 migration |
</phase_requirements>

## Summary

本階段是「整合 + 小幅後端查詢擴充」,不引入任何新外部套件。前端已有完整基礎設施:`apiClient.ts` 的信封/分頁解析已與後端 `ApiResponse<T>` / `PageResponse<T>` 對齊(2026-07-19 完成),`backtestApi.ts` 提供可直接照抄的 service 三件組樣板,`pageApiClients.ts` 依 mode 選實作。前端主要工作是:新增 `portfolioApi.ts`(mock + http 雙實作)、把 Overview/Positions/Trades 三頁從直接 import `useMockPortfolioStore` 切換為經 service、重建 KPI 資料來源、加分頁/篩選/排序 UI 與四態(loading/empty/error/retry)呈現。

後端唯一改動是 `GET /api/v1/trades` 的查詢擴充:新增 `type`、`dateFrom`/`dateTo` 篩選與 `sort`/`direction` 排序參數。現有實作(`JdbcTradingRepository.listTransactions`)已是「動態 WHERE 字串 + 具名參數」的 JdbcClient 模式,擴充路徑清晰。關鍵技術點有三:(1) ORDER BY 無法參數化,必須用 **Java 端白名單映射**到固定 SQL 片段(code-standards SQL injection 硬規則);(2) 金額排序是 `quantity * price` 計算式,PostgreSQL 支援運算式索引(語法需額外括號),需新增 **V9 migration**;(3) 現行預設排序是 `created_at desc`,D-07 要求改為 `executedAt desc`,而現有索引都建在 `created_at` 上——`executed_at` 排序需要配套索引,否則是全表排序。

風險最大的不是技術,而是「合成假資料的邊界」:研究查證發現 CONTEXT D-01 的查證有誤——`Positions.vue:119-140` 確實有 Sector breakdown 卡在用 `p.sector`;此外 Positions 頁還有權益曲線、時光機、Sharpe/年化/最大回撤等大片合成資料區塊,CONTEXT 只明確裁決了 Overview(D-14)。planner 必須依「後端沒有的就不露」這條已鎖定原則,把 Positions/Overview 的 API mode 隱藏清單完整列舉進 plan,否則 executor 會在中途遭遇範圍爆炸。

**Primary recommendation:** 前端照抄 `backtestApi.ts` 三件組 + `pageApiClients.ts` 註冊;後端在現有 JdbcClient 動態查詢上加白名單排序與篩選,配 V9 索引 migration;所有「後端無資料來源」的 UI 區塊在 API mode 一律隱藏(依 D-14 原則),並把完整隱藏清單寫死在 plan 裡。

## Project Constraints (from CLAUDE.md)

| 指令 | 來源 | 對本階段的意義 |
|------|------|----------------|
| **TDD 硬性要求**:先寫失敗測試再實作,Red → Green → Refactor | CLAUDE.md | 每個 task 的 action 都必須以測試先行;後端 filter/sort、前端 adapter/頁面切換皆同 |
| 稱呼 Yuan、以繁體中文回應、evidence-based | CLAUDE.md | 文件與回報語言 |
| Ask before acting:變更 API 契約 shape 前要停下來問 | CLAUDE.md + judgment §9 | D-05/D-06 已取得同意,新參數以 CONTEXT 為界;若實作中發現需要 CONTEXT 之外的契約變更,停下來問 |
| 元件不得 import mock store,一律經 `services/` interface | judgment §3 | 三頁現有的 `useMockPortfolioStore` import 必須移除 |
| API mode 驗證必須看到真實 network call | judgment §3 | 驗證不能只看畫面;`VITE_DATA_MODE=api npm test` 必跑 |
| 信封權威為後端 `ApiResponse<T>`;分頁為 `PageResponse<T>` | judgment §4 | 前端已對齊,不需新轉接層 |
| 高頻計算走 Redis 預計算、API 只讀 | judgment §7 | D-04 的依據;後端 read path 不需改 |
| 跨 repo 變更兩邊驗證都要跑 | judgment §8 | backend `./mvnw test` + frontend `npm test && npm run build`(含 api mode) |
| 測試方法名禁中文,用 `@DisplayName` 附繁中描述;pristine output | testing-standards.md | 後端新測試命名規則 |
| 三層測試:`*Test`(unit)/ `*ControllerTest`(web)/ `*IT`(integration),不可豁免(豁免只有 Yuan 能給) | testing-standards.md | filter/sort SQL 正確性需 IT 覆蓋(`TradingApiIT` 或新 IT) |
| **禁止 SQL 字串串接**;動態查詢只准 JdbcClient + 具名參數 | code-standards.md | ORDER BY 白名單映射是唯一合規做法 |
| 所有 production migration 集中在 `stock-db-migration`,連號、不改已套用的檔 | flyway-convention.md | 新索引 = `V9__*.sql`(現最高 V8) |
| Git commit 規範 | ai-docs/git-convention.md | executor 提交前讀 |
| GSD 生成檔(AGENTS.md 等)禁止手改 | CLAUDE.md / judgment §9 | — |

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| 篩選/排序/分頁(trades) | API / Backend(SQL) | Frontend(僅 UI 狀態:當前頁碼、選中 chip) | server-side 分頁下 client-side 篩選/排序只作用於當前頁,是正確性缺陷(D-05/D-06) |
| P&L / ROI / 市值 / 成本計算 | API / Backend(Redis 預計算 + DB) | — | judgment §7 + D-04;`realizedPnl` 前端根本算不出來 |
| 衍生顯示欄位(weight、total = qty×px 顯示) | Frontend | — | 後端無此欄位,只能由後端值衍生(D-04 例外) |
| 信封/分頁解析、CSRF、401 refresh | Frontend transport(`apiClient.ts`) | — | Phase 2 已完成的唯一邊界,不得繞過(judgment §10) |
| mock/api mode 切換 | Frontend service 層(`pageApiClients.ts`) | — | 元件不可知 mode;judgment §3 |
| loading/empty/error/retry 呈現 | Frontend view(各頁內嵌) | — | D-11;一個區塊失敗不拖垮整頁 |
| CSV 匯出 | Frontend(循環拉取 API) | API(提供 filter/sort 一致的分頁) | D-10;後端無匯出端點,以現有分頁 API 組裝 |
| holdings/summary 快取 | Backend(Redis, TTL 60s) | — | `PortfolioCache` 已存在,read path 不需改 |

## Standard Stack

### Core(全部為既有依賴,無新增)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Boot | 4.0.4(parent)| 後端框架 | 既有 [VERIFIED: pom.xml:10] |
| Java | 21 | 後端語言 | 既有 [VERIFIED: pom.xml `<java.version>`;本機 `java -version` 21.0.1] |
| Spring `JdbcClient` | 隨 Boot | 動態查詢(具名參數) | `JdbcTradingRepository` 既有模式 [VERIFIED: codebase] |
| PostgreSQL / TimescaleDB | timescale/timescaledb 2.17.2-pg16(IT 容器)| DB;運算式索引 | 既有 [VERIFIED: ContainerIT.java] |
| Flyway | 隨 Boot | Migration(V9 索引) | 既有;集中於 `stock-db-migration` [VERIFIED: codebase] |
| Vue | ^3.5.34 | 前端框架 | 既有 [VERIFIED: package.json] |
| Pinia | ^3.0.4 | mock store(僅 mock adapter 使用) | 既有 [VERIFIED: package.json] |
| TypeScript / Vite / Vitest | ^6.0.3 / ^8.0.13 / ^4.1.6 | build 與測試 | 既有 [VERIFIED: package.json] |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Testcontainers(PG+Redis+Kafka)| 既有 | 後端 IT | `ContainerIT` 基底類;Docker 已確認可用 |
| Mockito + JUnit 5 | 既有 | 後端 unit/web 測試 | `TradingControllerTest` 模式 |
| Apache Commons Lang3 | 既有 | null/blank 檢查 | code-standards 指定(`StringUtils.isBlank` 等) |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| JdbcClient 動態 WHERE + 白名單 ORDER BY | Spring Data JPA `Sort` / Specification | 本模組已全面採 JdbcClient,引 JPA 是不必要的架構偏移;維持一致性(code-standards「Consistency」) |
| 運算式索引 `(quantity * price)` | 新增 generated column `total` | generated column 需改表結構、動到 append-only 表的 DDL 面積更大;運算式索引達成同樣效果且改動最小 |
| 換頁按鈕 | infinite scroll / append | 已被 D-08 否決(page-number 位移/重複問題,mock 測試已驗證該行為) |

**Installation:** 無需安裝任何新套件。

**Version verification:** 本階段不新增套件,版本皆直接讀自 `pom.xml` 與 `package.json`(見上表),不涉及 registry 查詢。

## Package Legitimacy Audit

本階段**不安裝任何新外部套件**(前後端皆使用既有依賴)。

| Package | Registry | Age | Downloads | Source Repo | Verdict | Disposition |
|---------|----------|-----|-----------|-------------|---------|-------------|
| (無) | — | — | — | — | — | — |

**Packages removed due to [SLOP] verdict:** none
**Packages flagged as suspicious [SUS]:** none

## Architecture Patterns

### System Architecture Diagram

```
                         ┌─ mock mode ─────────────────────────────┐
                         │  createMockPortfolioApi()               │
                         │    └─ 讀寫 useMockPortfolioStore(Pinia)│
Overview.vue ─┐          └─────────────────────────────────────────┘
Positions.vue ┼─► getRuntimeApiClients().portfolio ──┤ (依 VITE_DATA_MODE 二選一)
Trades.vue   ─┘   (pageApiClients.ts,元件不可知 mode) │
                         ┌─ api mode ──────────────────────────────────────────────────┐
                         │  createHttpPortfolioApi(basePath)                           │
                         │    ├─ apiRequest<T>            (拆 ApiResponse.data)        │
                         │    └─ apiPaginatedRequest<T>   (拆 ApiResponse<PageResponse>)│
                         │         │  credentials:'include' + CSRF + 401 單次 refresh  │
                         └─────────┼───────────────────────────────────────────────────┘
                                   ▼  HTTP GET
        ┌──────────────────────────────────────────────────────────────────┐
        │ TradingController (@PreAuthorize PORTFOLIO_VIEW)                 │
        │   GET /portfolio/summary ──► TradingService.summary              │
        │   GET /portfolio/holdings ─► TradingService.listHoldings         │
        │   GET /trades ─────────────► TradingService.listTrades           │
        │        (新增: type, dateFrom, dateTo, sort, direction)           │
        └────────┬───────────────────────────────┬─────────────────────────┘
                 ▼ summary/holdings              ▼ trades
        PortfolioCache (Redis, TTL 60s)   JdbcTradingRepository.listTransactions
          miss 時→ Jdbc 計算後回寫          (動態 WHERE + 白名單 ORDER BY + limit/offset
                 │                          + 同 WHERE 的 count(*))
                 ▼                                │
        PostgreSQL: holdings / asset_latest_prices│ transactions (append-only, V8 trigger)
                                                  └ V9 新索引: (user_id, executed_at) 與
                                                    (user_id, (quantity*price)) 運算式索引
```

追主要用例:使用者開 Trades 頁(API mode)→ 頁面呼叫 `portfolio.listTrades({ type, dateFrom, dateTo, sort, direction, page, size })` → http adapter 組 query string → `apiPaginatedRequest` → 後端白名單驗證排序鍵 → SQL 回分頁結果 → 信封拆解 → 頁面渲染 + 換頁按鈕。

### Recommended Project Structure(僅列新增/修改)

```
後端 (stock-web-v2/)
├── stock-db-migration/src/main/resources/db/migration/
│   └── V9__trading_query_indexes.sql          # 新增 (executed_at 索引 + 運算式索引)
├── stock-module-trading/src/main/java/.../trading/
│   ├── api/TradingController.java             # 修改: listTrades 新增 @RequestParam
│   ├── service/TradingService.java            # 修改: 參數驗證/白名單解析
│   └── repository/JdbcTradingRepository.java  # 修改: 動態 WHERE + ORDER BY
└── stock-start/src/test/java/.../TradingApiIT.java  # 擴充: filter/sort IT

前端 (../../vue/stock-v2/vue-app/src/)
├── services/
│   ├── portfolioApi.ts          # 新增: 三件組 (mock/http/factory)
│   ├── portfolioApi.test.ts     # 新增
│   ├── apiTypes.ts              # 修改: TradeDto/HoldingDto/PortfolioSummaryDto TS 型別
│   └── pageApiClients.ts        # 修改: 註冊 portfolio client
├── pages/
│   ├── Overview.vue             # 修改: 移除 mock store import、KPI 重建、近期交易走 API
│   ├── Positions.vue            # 修改: 移除 mock store import、彙總改讀後端欄位、隱藏合成區塊
│   └── Trades.vue               # 修改: server-side 篩選/排序/分頁、CSV 循環匯出
└── i18n.ts                      # 修改: 新增分頁/狀態文案 (zh + en)
```

### Pattern 1: Service 三件組(既有慣例,直接照抄)

**What:** `createMockXxxApi()` / `createHttpXxxApi(basePath)` / `createXxxApi(mode, basePath)`,共用同一 interface,由 `pageApiClients.ts` 依 mode 建立並快取。
**When to use:** 本階段的 `portfolioApi.ts`。
**Example:** 見 `vue-app/src/services/backtestApi.ts:120-201`(mock 實作)、`:186-197`(http 實作,`apiRequest` + `apiPaginatedRequest` + `buildQueryString`)。[VERIFIED: codebase]

**注意:** mock 實作與其他 domain 不同——portfolio 的 mock 資料權威是 `useMockPortfolioStore`(Phase 4 的 `executeOrder` 會寫它),所以 `createMockPortfolioApi()` 應**委派給該 store**(在 service 層 import store 是允許的;judgment §3 禁的是「元件」import),而非像 backtest 那樣自帶閉包狀態。詳見 Open Questions Q3。

### Pattern 2: ORDER BY 白名單映射(後端)

**What:** 排序鍵不可能參數化(`ORDER BY :col` 無效),唯一合規做法是把 API 參數值經 Java 白名單映射到**寫死的 SQL 片段**,未知值丟 `VALIDATION_FAILED`。
**When to use:** `listTrades` 的 `sort` / `direction` 參數。
**Example:** 見 Code Examples §2。CONTEXT specifics 已明確:排序欄位刻意收斂為三個,不做「所有欄位皆可排」。

### Pattern 3: 動態 WHERE 組裝(既有 JdbcClient 模式)

**What:** 以條件串接固定字串片段 + 具名參數(絕不內插使用者輸入),list 與 count 共用同一 WHERE。
**When to use:** 擴充 `listTransactions` 的 type / 日期區間篩選。
**Example:** 現有 `JdbcTradingRepository.listTransactions`(:128-150)已是此模式(`filterAsset` 分支)。擴充時把 WHERE 組裝抽成單一來源,避免 list/count 兩份 WHERE 漂移。[VERIFIED: codebase]

### Pattern 4: API-mode 測試三步(前端既有慣例)

**What:** `vi.stubEnv('VITE_DATA_MODE', 'api')` → `resetRuntimeApiClientsForTests()` → mock `fetch`;並以「mock factory 未被呼叫」斷言把靜默回退變成測試失敗。
**When to use:** `portfolioApi` 的 http 實作測試與三頁的 API mode wiring 測試。
**Example:** `vue-app/src/api-adapter-wiring.test.ts:18-33`(`vi.hoisted` factory spies + `vi.doUnmock`)、`testSetup.ts`(預設鎖 mock mode)。[VERIFIED: codebase]

### Anti-Patterns to Avoid

- **在 API mode 對當前頁資料做 client-side 篩選/排序**:這正是本階段要消滅的正確性缺陷;chips/排序點擊必須轉為 request 參數。
- **前端平行重算後端已提供的數值**(`totalCost = Σ qty*avg` 等):違反 D-04;`Positions.vue:237`、`:174` 這類計算在 API mode 必須改讀後端欄位。
- **在 adapter 裡做多 shape 兼容解析**:judgment §4 反例;信封只有一種權威。
- **繞過 `apiClient.ts` 直接 `fetch`**:judgment §10「需要繞過唯一邊界才能達成 → 設計錯了」。
- **API mode 顯示假資料**(寫死字串、`genSeries` 亂數):D-14 原則,後端沒有的就隱藏。
- **把 `ORDER BY` 排序鍵串進 SQL 字串**:code-standards 硬禁令。

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| 信封拆解 / 錯誤碼 / traceId | 自製 response parser | `apiRequest<T>` / `ApiClientError` | Phase 2 已處理 401 refresh、CSRF、fields 錯誤等邊角 |
| 分頁 response 解析 | 自製分頁轉接層 | `apiPaginatedRequest<T>` + `PaginatedResponse<T>` | 已與後端 `PageResponse<T>` 同形,CONTEXT 明言不需再寫 |
| Query string 組裝 | 手拼 `?a=1&b=2` | `buildQueryString(...)` | 已處理 null/undefined 過濾與 encode |
| CSV cell escaping | 重寫跳脫邏輯 | 沿用 `Trades.vue` 既有 `csvCell()` | 已處理引號/換行/逗號 |
| 排序時金額欄位 | 前端算 total 再排 | 後端 `quantity * price` 計算式排序 | client-side 排序只排當前頁(D-06) |
| 大小寫/空白容錯的 type 解析 | 新驗證邏輯 | `TradeType.fromApiValue(...)` | 既有,錯誤值丟 `TRADE_UNSUPPORTED_TYPE` |
| 分頁上限防護 | 新 clamp 邏輯 | `TradingService` 既有 `MAX_PAGE=10_000`、size clamp 1..100 | 已存在,擴充時沿用 |
| 錯誤診斷欄位呈現 | 新診斷元件語彙 | 參照 `SessionBanner.vue` 的 code/status/requestId 樣式 | D-12 與 Phase 2 慣例一致 |

**Key insight:** 這個 phase 的每一層「管線」都已存在——唯一真正的新輪子是後端的白名單排序與 V9 索引,以及各頁的狀態 UI。任何額外抽象(轉接層、共用分頁 store、通用動態排序框架)都是把簡單問題複雜化。

## Common Pitfalls

### Pitfall 1: ORDER BY 注入 / 非法排序鍵
**What goes wrong:** 把 `sort` 參數直接內插 SQL,或白名單漏驗 `direction`。
**Why it happens:** ORDER BY 無法用具名參數。
**How to avoid:** Java 端 `Map<String, String>`(或 enum)白名單,`direction` 只接受 `asc|desc`(不分大小寫),其餘丟 `BusinessException(VALIDATION_FAILED)` → 400。
**Warning signs:** SQL 字串裡出現任何直接來自 request 的變數。

### Pitfall 2: 排序缺 tie-breaker → 跨頁重複/遺漏列
**What goes wrong:** 只 `order by executed_at desc` 時,同一秒多筆交易在不同請求間順序不穩定,翻頁會看到重複或漏掉的列。
**Why it happens:** PostgreSQL 對相等鍵的順序不保證。
**How to avoid:** 每個排序皆補 `, t.id desc`(或與方向一致的 id 排序)作為決定性 tie-breaker——現有實作已是 `created_at desc, id desc`,沿用此慣例。
**Warning signs:** IT 中同 `executedAt` 的兩筆交易翻頁測試不穩定。

### Pitfall 3: count 與 list 的 WHERE 漂移
**What goes wrong:** 篩選加進 list query 卻忘了 count query(或反之),`totalPages` 與實際內容不一致,前端換頁按鈕壞掉。
**Why it happens:** 現有實作是兩個獨立 `StatementSpec`(:132-147)。
**How to avoid:** WHERE 片段與參數綁定抽成單一來源供兩個 query 共用;IT 同時斷言 `items` 與 `totalElements`。
**Warning signs:** 篩選後 `totalElements` 仍是全量。

### Pitfall 4: 預設排序從 `created_at` 改為 `executed_at` 的索引與行為漣漪
**What goes wrong:** 現行 SQL 是 `order by t.created_at desc, t.id desc`,兩個既有索引也建在 `created_at` 上(V7:20-21)。D-07 要求預設 `executedAt desc`——若不加索引,per-user 全量排序;若加了,還要注意 `executedAt` 可由使用者指定(`CreateTradeRequest.executedAt` 可回填過去日期),排序結果與 created_at 順序**可能不同**,既有 `TradingApiIT`(:64-68 斷言 `items[0].id`)之類的測試假設可能鬆動。
**How to avoid:** V9 新增 `(user_id, executed_at DESC, id DESC)` 索引;更新/檢查既有 IT 的順序斷言;文件化「顯示順序=成交時間序,非入帳序」。
**Warning signs:** 回填舊日期的交易出現在列表頂端或底部的位置與預期不符。

### Pitfall 5: 篩選變更後頁碼溢出(D-15)
**What goes wrong:** 第 5 頁切篩選,仍送 `page=4`,後端回空 `items` + `totalPages=1`,畫面顯示「沒有交易」。
**How to avoid:** 前端:篩選/排序變更一律 reset `page=0`;收到 response 後若 `page >= totalPages && totalPages > 0` 則以 `totalPages-1` 重新請求(或直接 reset 0)。這是鎖定決策,必須有對應測試。
**Warning signs:** 空列表但 `totalElements > 0`。

### Pitfall 6: mock 模式回歸——服務層切換破壞既有互動
**What goes wrong:** 三頁改走 service 後,mock mode 的既有行為(`lastFill` 高亮、`executeOrder` 後列表即時更新、時光機)失效,因為 service 回傳的是 snapshot 而非 reactive store 參照。
**Why it happens:** Promise-based service 天然失去 Pinia reactivity。
**How to avoid:** mock adapter 委派 store 並讓頁面在 mock mode 下仍取得 reactive 資料(可行做法:adapter 回傳 store 的陣列參照而非深拷貝),或在 CONTEXT 授權的「實作細節」範圍內由 planner 明確裁決 mock mode 的資料流。既有 154/154 雙模式測試必須保持綠。
**Warning signs:** mock mode 下 OrderTicket 成交後 Trades/Positions 不更新。

### Pitfall 7: 前端測試污染與模式洩漏
**What goes wrong:** API mode 測試忘了 `resetRuntimeApiClientsForTests()`,client 快取殘留造成跨測試污染;或忘了 `vi.stubEnv`,測到 mock 實作還以為測到 http。
**How to avoid:** 依 `testSetup.ts` + `api-adapter-wiring.test.ts` 既有模式;http 實作測試斷言真的呼叫了 `fetch`(或 mock factory 未被呼叫)。
**Warning signs:** 單跑綠、全跑紅(或反之)。

### Pitfall 8: 合成假資料邊界盤點不完整(範圍爆炸的主因)
**What goes wrong:** plan 只處理 D-14 列出的 Overview 隱藏項,executor 進到 Positions 才發現整頁一半是假資料,範圍中途膨脹。
**盤點結果(本研究逐行查證):**

| 頁面 | 區塊 | 資料來源 | API mode 建議 |
|------|------|---------|--------------|
| Overview | 總資產/總報酬 KPI | `genSeries` 亂數(:128-137) | 接 `totalMarketValue` / `roi`(D-14 鎖定) |
| Overview | 今日損益/可用現金 KPI | 寫死字串(:134-135) | 隱藏(D-14 鎖定) |
| Overview | 資產配置 donut | 寫死陣列 `alloc`(:139-142) | 隱藏(D-14 鎖定) |
| Overview | **資產走勢圖(LineChart)+ range 切換** | `genSeries(80, 1_000_000, ...)`(:128) | **CONTEXT 未裁決** → 依 D-14 原則建議隱藏(需日級歷史,已是 deferred todo);見 Q4 |
| Overview | 近期交易 | `portfolio.trades.slice(0,5)`(:95) | `GET /trades?page=0&size=5`(D-09 鎖定) |
| Overview | Watchlist / News 卡 | SYMBOLS/CRYPTO/NEWS 靜態資料 | 非 portfolio 範圍(PORT-06 v2),維持現狀不動 |
| Positions | 持倉表 | mock store | 接 holdings API(核心工作) |
| Positions | 彙總條 `pnlAbs`/`totalCost`(:79-83, :237)| 前端 qty×avg 計算 | 改讀 summary 後端欄位(D-04/D-14) |
| Positions | **權益曲線 + range 選擇器 + rangeCfg 假 KPI(Sharpe/年化/MaxDD)**(:228-296)| 寫死 `rangeCfg` + `genSeries` | **CONTEXT 未裁決** → 依 D-14 原則建議隱藏;見 Q4 |
| Positions | **時光機(scrubber)**(:378-444)| `priceAt` 偽隨機 | **CONTEXT 未裁決** → 依 D-14 原則建議 API mode 隱藏;見 Q4 |
| Positions | **Sector breakdown 卡**(:119-140, sectorStats :310-329)| `p.sector`(mock 才有)| **D-01 查證有誤——sector 確實在 Positions 頁**;HoldingDto 無 sector → API mode 隱藏該卡;見 Q1 |
| Positions | Top movers(:299-304)| 由 position pnl 排序 | 可保留:由後端 `unrealizedPnl` 衍生(符合 D-04 例外) |
| Positions | weight 欄(:376)| 前端 `qty*price/totalVal` | 保留:改以 `marketValue / totalMarketValue` 衍生(D-04 明文例外) |
| Trades | 篩選 chips(:65-79)| client-side filter | 轉 server 參數;Dividend 隱藏(D-02)、2026 → 動態當年度(D-05) |
| Trades | CSV 匯出(:83-107)| `filteredTrades` 當前陣列 | 以相同 filter/sort 循環拉全頁(D-10) |

**How to avoid:** plan 中為 Overview 與 Positions 各列一條「API mode 隱藏清單」task,逐項打勾。
**Warning signs:** executor 的 SUMMARY 出現「另發現 X 也是假資料」。

### Pitfall 9: BigDecimal → JSON number 的前端精度
**What goes wrong:** 後端 `NUMERIC(24,8)` 以 JSON number 序列化(IT 以 `equalTo(10.00000000)` 斷言數值,非字串)。前端 `JSON.parse` 得到 IEEE-754 double,理論上超過 2^53 或高小數位會失真。
**How to avoid:** 本專案金額量級(百萬美元、8 位小數)在 double 可表示範圍內,顯示用 `fmtNum` 格式化即可;**不要**在前端對這些值做再計算後回傳比較(D-04 本來就禁止重算)。測試中避免對長小數做嚴格字串比對。[ASSUMED — 見 Assumptions A2]
**Warning signs:** 畫面出現 `1179.0000000001` 之類的尾差。

### Pitfall 10: 日期區間參數的時區語意
**What goes wrong:** `executed_at` 是 `TIMESTAMPTZ`,前端「當年度」chip 若以本地時區算年界、後端以 UTC 解讀,跨年邊界的交易會被錯誤納入/排除。
**How to avoid:** 參數定為 ISO-8601 含 offset 的時間(`OffsetDateTime`,與 `CreateTradeRequest.executedAt` 同慣例),由前端算好邊界帶 offset 送出;半開區間 `[dateFrom, dateTo)` 語意寫進契約文件。[ASSUMED — 見 Assumptions A1,planner 需確認]
**Warning signs:** 12/31 深夜或 1/1 凌晨的交易在年度篩選中歸屬錯誤。

### Pitfall 11: Flyway V9 的紀律
**What goes wrong:** 把索引 SQL 放進 trading module 的 resources、或修改 V7。
**How to avoid:** 新檔 `stock-db-migration/src/main/resources/db/migration/V9__*.sql`;絕不動 V1..V8(checksum 會炸);運算式索引寫法見 Code Examples §1(表達式必須加括號)。
**Warning signs:** Flyway validate 失敗、模組目錄出現 `db/migration`。

## Code Examples

### 1. V9 migration:排序配套索引(PostgreSQL)

```sql
-- Source: postgresql.org/docs/current/indexes-expressional.html(運算式需額外括號)
-- V9__trading_query_indexes.sql
-- D-07 預設排序 executed_at desc(現有索引只覆蓋 created_at)
CREATE INDEX idx_transactions_user_executed
    ON transactions (user_id, executed_at DESC, id DESC);

-- D-06 金額(quantity * price)排序;運算式索引,寫入時多一次計算(transactions 為
-- append-only 低頻寫入,成本可接受)
CREATE INDEX idx_transactions_user_amount
    ON transactions (user_id, (quantity * price) DESC, id DESC);
```

註:`quantity` 排序不另建索引——三鍵全建索引的寫入成本與收益不成比例,per-user 交易量目前極小;`quantity` 排序先走 `(user_id, ...)` 既有索引過濾後排序即可,若未來量大再補。planner 可依 CONTEXT「評估運算式索引」的授權裁量。另外:帶 `type`/日期篩選時 PostgreSQL 未必選用排序索引(組合條件下 planner 自行取捨)——索引是保險,不是保證。

### 2. 後端排序白名單(Java,示意)

```java
// Source: 專案 code-standards.md「動態查詢只准 JdbcClient + 具名參數;禁止串接」
// ORDER BY 無法參數化 → API 值映射到寫死片段,未知值 → 400
private static final Map<String, String> SORT_COLUMNS = Map.of(
    "executedAt", "t.executed_at",
    "total",      "(t.quantity * t.price)",
    "quantity",   "t.quantity"
);

static String orderByClause(String sort, String direction) {
    String column = SORT_COLUMNS.get(sort == null ? "executedAt" : sort);
    if (column == null) {
        throw new BusinessException(ErrorCode.VALIDATION_FAILED, "unsupported sort key");
    }
    String dir = switch (direction == null ? "desc" : direction.toLowerCase(Locale.ROOT)) {
        case "asc" -> "asc";
        case "desc" -> "desc";
        default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED, "direction must be asc or desc");
    };
    return "order by " + column + " " + dir + ", t.id " + dir + " ";  // tie-breaker 必加
}
```

篩選參數同理:`type` 經 `TradeType.fromApiValue()`(既有,錯誤丟 `TRADE_UNSUPPORTED_TYPE`);`dateFrom`/`dateTo` 綁具名參數 `t.executed_at >= :dateFrom and t.executed_at < :dateTo`。WHERE 組裝供 list 與 count 共用(Pitfall 3)。Controller 沿用現有手動解析風格(`parseQueryInt` 慣例,`@RequestParam(required = false)`)。

### 3. 前端 service 三件組骨架(照 `backtestApi.ts` 模式)

```typescript
// Source: vue-app/src/services/backtestApi.ts(既有樣板)
export interface TradeListParams {
  type?: 'BUY' | 'SELL';
  dateFrom?: string;         // ISO-8601 with offset
  dateTo?: string;
  sort?: 'executedAt' | 'total' | 'quantity';
  direction?: 'asc' | 'desc';
  page?: number;
  size?: number;
}

export interface PortfolioApi {
  mode: RuntimeDataMode;
  getSummary(): Promise<PortfolioSummaryDto>;
  listHoldings(): Promise<HoldingDto[]>;
  listTrades(params?: TradeListParams): Promise<PaginatedResponse<TradeDto>>;
}

export function createHttpPortfolioApi(basePath = '/api/v1'): PortfolioApi {
  return {
    mode: 'api',
    getSummary: () => apiRequest(`${basePath}/portfolio/summary`),
    listHoldings: () => apiRequest(`${basePath}/portfolio/holdings`),
    listTrades: params => apiPaginatedRequest<TradeDto>(
      `${basePath}/trades${buildQueryString({
        type: params?.type,
        dateFrom: params?.dateFrom,
        dateTo: params?.dateTo,
        sort: params?.sort,
        direction: params?.direction,
        page: params?.page ?? 0,
        size: params?.size ?? 20,
      })}`,
    ),
  };
}
// createMockPortfolioApi(): 委派 useMockPortfolioStore(見 Open Questions Q3)
// createPortfolioApi(mode, basePath): mode === 'api' ? http : mock
// 並在 pageApiClients.ts 的 RuntimeApiClients 加入 portfolio 欄位
```

### 4. 後端 DTO → 前端欄位映射(查證自兩邊程式碼)

| 後端(已查證) | 前端現有欄位 | 備註 |
|---------------|-------------|------|
| `TradeDto.executedAt`(OffsetDateTime ISO 字串)| `Trade.d`('YYYY-MM-DD')| 顯示時取日期部分;排序/篩選语意以 executedAt 為準 |
| `TradeDto.symbol / type / quantity / price / fee / note` | `sym / type / qty / px / fee / note` | type 值域 BUY/SELL 相容;DIV 永不出現(D-02) |
| `TradeDto.id`(uuid)| (無)| 可作 `:key`,取代現行多欄拼接 key |
| `HoldingDto.symbol / assetName / totalQuantity / avgCost / marketPrice` | `Position.sym / name / qty / avg / price` | `sector` 無對應(Q1) |
| `HoldingDto.costBasis / marketValue / unrealizedPnl / roi / realizedPnl` | (現由前端 qty×price 算)| D-04:改讀後端值 |
| `HoldingDto.priceTime` | (新增顯示)| D-03 鎖定 |
| `PortfolioSummaryDto.totalMarketValue / roi` | Overview KPI 兩張 | D-14 |
| `PortfolioSummaryDto.totalCostBasis / realizedPnl / unrealizedPnl / totalPnl / holdingCount` | Positions 彙總條(planner 決定)| D-14 |

## State of the Art

| Old Approach(本專案現狀) | Current Approach(本階段目標) | When Changed | Impact |
|--------------|------------------|--------------|--------|
| 三頁直接 import `useMockPortfolioStore` | 一律經 `getRuntimeApiClients().portfolio` | Phase 2 已為 Backtest/Ops/Settings 建立此模式 | PORT-04 達成;judgment §3 合規 |
| client-side 篩選/排序全陣列 | server-side 篩選/排序 + 分頁 | 本階段(D-05/D-06) | 跨頁語意正確 |
| 前端 cursor 分頁抽象 | page-number `PageResponse` 同形 | 2026-07-19 契約對齊(已完成) | 不需轉接層 |
| `{data, requestId}` 草案信封 | `ApiResponse<T>` + `meta.traceId` | 2026-07-19(已完成) | `ApiClientError.requestId` 即 traceId |

**Deprecated/outdated:**
- 前端 `docs/api-contracts/mock-to-real-contract.md` 的信封一節已過時——權威裁決是 judgment §4(整份其餘部分 2026-07-19 已對齊)。
- `Trades.vue` 的 `'2026'` 硬編年份 chip:本階段以動態當年度(或日期區間)取代(D-05)。

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | 日期區間參數採 ISO-8601 含 offset(`OffsetDateTime`),半開區間 `[dateFrom, dateTo)`,年界由前端以使用者本地時區計算 [ASSUMED] | Pitfall 10 / Code Examples | 跨年邊界交易歸屬錯誤;契約文件與 IT 斷言需依最終裁決調整(屬 API 契約細節,planner 應在 plan 中定死,若認定為 shape 變更層級則依 judgment §9 confirm) |
| A2 | `NUMERIC(24,8)` → JSON number → JS double 的精度損失在本專案量級可忽略(顯示用途)[ASSUMED] | Pitfall 9 | 極大金額或高精度需求時顯示失真;屆時需改字串序列化(契約變更,超出本階段) |
| A3 | 日期篩選與預設排序皆以 `executed_at`(成交時間)為準,而非 `created_at`(入帳時間)[ASSUMED,由 D-07「executedAt」與 UI 顯示交易日推斷] | Pitfall 4 | 若 Yuan 意指入帳序,索引與 SQL 需改回 created_at;兩者在回填舊日期交易時順序不同 |
| A4 | 對 Positions/Overview 的 CONTEXT 未裁決合成區塊(權益曲線、時光機、Sharpe 等)套用 D-14「後端沒有的就不露」原則於 API mode 隱藏 [ASSUMED — 原則已鎖定,套用範圍是推斷] | Pitfall 8 / Q4 | 若 Yuan 想保留部分區塊(例如以「即將推出」樣式呈現),UI 工作量與驗收條件不同 |

## Open Questions

1. **D-01 的查證更正:`sector` 其實也在 Positions 頁**
   - What we know: `Positions.vue:119-140` 有 Sector breakdown 卡,`sectorStats`(:310-329)以 `p.sector || 'Other'` 分群;D-01 聲稱「sector 不在 Positions 頁」與程式碼不符。
   - What's unclear: 無——決策結論(後端不加 sector)不受影響。
   - Recommendation: API mode 隱藏 Sector breakdown 卡(全部持倉會歸到 'Other',等同假資料);mock mode 保留。planner 把此項列入隱藏清單,並在 plan 註明是 D-01 的證據更正而非翻案。
2. **既有 IT 順序斷言在預設排序改為 `executed_at` 後是否鬆動**
   - What we know: `TradingApiIT:64-68` 斷言 `items[0].id`;測試中 executedAt 皆為 now(),順序通常不變。
   - What's unclear: 是否有測試依賴 created_at 序的隱含假設。
   - Recommendation: 改動後全量跑 `./mvnw -pl stock-start -am verify`,失敗即為 Pitfall 4 的實證。
3. **mock adapter 的 reactivity 設計(`lastFill` 高亮、Phase 4 `executeOrder` 後更新)**
   - What we know: 三頁現直接吃 Pinia reactivity;service 化後 Promise 回傳天然失去 reactivity;`lastFill` 目前直接讀 store(`Trades.vue:37`、`Positions.vue:161`)。
   - What's unclear: mock mode 下頁面資料流——adapter 回 store 陣列參照(保 reactivity)vs. 頁面自管 state + 事件後 refetch(與 API mode 對稱)。
   - Recommendation: 屬 CONTEXT 授權的實作細節;建議 planner 選「頁面自管 state、雙模式同一資料流,mock adapter 委派 store 取數」,`lastFill` 高亮在 mock mode 可經 adapter 額外暴露或降級移除——但必須保住既有 154 個雙模式測試綠。
4. **Overview 資產走勢圖與 Positions 權益曲線/時光機/假 KPI 的 API mode 處置**
   - What we know: 全部是 `genSeries`/`rangeCfg` 合成資料(Pitfall 8 盤點);後端無時間序列資料源(daily-pnl 是 deferred todo)。
   - What's unclear: CONTEXT 只裁決了 Overview 的 KPI/donut,未提及這些圖表區塊。
   - Recommendation: 依 D-14 原則在 API mode 隱藏(A4);plan 中列成明確 task 並在 SUMMARY 記錄,若 Yuan 有異議可在 plan review 時翻。
5. **holdings 大清單(不分頁)的 loading 設計**
   - What we know: CONTEXT specifics 已指出風險:`GET /portfolio/holdings` 無上界,本階段不改後端。
   - Recommendation: loading 骨架 + 一次渲染;SUMMARY 記錄實測持倉筆數上限(CONTEXT 要求),供未來分頁決策。

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Java | 後端 build/test(需 21) | ✓ | 21.0.1 LTS | — |
| Maven wrapper | `./mvnw` / `mvnw.cmd` | ✓ | repo 內建 | — |
| Docker | Testcontainers IT(PG+Redis+Kafka) | ✓ | `docker info` OK | 無 fallback;IT 必須 Docker |
| Node.js | 前端 build/test | ✓ | v24.18.0 | — |
| npm | 前端 scripts | ✓ | 11.16.0 | — |
| 前端 scripts | `npm test`(vitest run)、`npm run build`(vue-tsc + vite build) | ✓ | 已查證 package.json:6-13 | — |
| PostgreSQL/Redis 本機服務 | 不需要(IT 走 Testcontainers) | n/a | — | — |

**Missing dependencies with no fallback:** 無。
**Missing dependencies with fallback:** 無。

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework(後端) | JUnit 5 + Mockito + Spring Boot Test + Testcontainers(Maven Surefire/Failsafe) |
| Framework(前端) | Vitest ^4.1.6(`testSetup.ts` 預設鎖 mock mode) |
| Config file | 後端:各模組 pom.xml;前端:`vue-app/vite.config.*` + `src/testSetup.ts` |
| Quick run command | 後端:`./mvnw -pl stock-module-trading -am test`;前端:`cd ../../vue/stock-v2/vue-app && npm test` |
| Full suite command | 後端:`./mvnw test`(IT:`./mvnw -pl stock-start -am verify`);前端:`npm test && npm run build` + `VITE_DATA_MODE=api npm test` |

(PowerShell 下:`mvnw.cmd`、分步執行、`$env:VITE_DATA_MODE='api'`。)

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| PORT-01 | summary 映射 Overview 兩張 KPI;無資料項隱藏 | unit(component) | `npx vitest run src/pages/Overview*` (檔名依 plan) | ❌ Wave 0 |
| PORT-02 | holdings 映射 Positions 表;後端欄位取代前端計算 | unit(component) | `npx vitest run src/pages/Positions*` | ❌ Wave 0 |
| PORT-03 | trades 分頁/排序 response mapping;換頁按鈕 | unit(component + service) | `npx vitest run src/services/portfolioApi.test.ts src/pages/Trades*` | ❌ Wave 0 |
| PORT-04 | API mode 不觸 mock store(factory 未被呼叫斷言) | unit(wiring) | `npx vitest run src/api-adapter-wiring.test.ts` | ✅ 既有檔擴充 |
| PORT-05 | loading/empty/error/retry + traceId 顯示 | unit(component) | 各頁測試檔內 error-state cases | ❌ Wave 0 |
| PORT-08 | filter/sort 參數:SQL 正確性、白名單 400、分頁一致 | unit + IT | `./mvnw -pl stock-module-trading -am test` + `./mvnw -pl stock-start -am verify -Dit.test=TradingApiIT`(依實際 failsafe 設定調整) | ❌ Wave 0(unit)/ ✅ TradingApiIT 擴充 |
| D-15 | 篩選變更 reset page、頁碼溢出回退 | unit(component) | Trades 頁測試 cases | ❌ Wave 0 |
| D-10 | CSV 匯出循環拉全頁且保留篩選 | unit(component) | Trades 頁測試 cases | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** 後端 task:`./mvnw -pl stock-module-trading -am test`;前端 task:`npm test`
- **Per wave merge:** 兩邊 full:`./mvnw test` + `npm test && npm run build`(跨 repo 變更兩邊都跑,judgment §8)
- **Phase gate:** `./mvnw -pl stock-start -am verify`(IT)+ `VITE_DATA_MODE=api npm test` + `npm run build` 全綠後才 `/gsd-verify-work`

### Wave 0 Gaps
- [ ] 後端:`TradingService` / repository 排序白名單與篩選的失敗測試(TDD Red)——放 `stock-module-trading/src/test/java/...`
- [ ] 後端:`TradingApiIT` 擴充 filter/sort/分頁一致性 cases(含 400 negative test:非法 sort key / direction / type)
- [ ] 前端:`src/services/portfolioApi.test.ts`(mock 與 http 實作,http 需 fetch mock + query string 斷言)
- [ ] 前端:三頁的 component 測試檔(API mode 四態、D-15、D-10、隱藏清單斷言)
- [ ] 前端:`api-adapter-wiring.test.ts` 加 portfolio wiring case(mock factory 未被呼叫)
- [ ] Framework install:無(全部既有)

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | yes(沿用) | 端點已有 `@PreAuthorize("hasAuthority('PORTFOLIO_VIEW')")`;本階段不改 auth |
| V3 Session Management | yes(沿用) | cookie session + `apiClient` 401 單次 refresh(Phase 2 已建) |
| V4 Access Control | yes | user 隔離靠 `t.user_id = :userId`(query 內建);新 WHERE 片段**必須保留 userId 條件**——IT 需維持「A 看不到 B 的交易」保證 |
| V5 Input Validation | yes | 新參數全部後端驗證:sort/direction 白名單、type 走 `TradeType.fromApiValue`、page/size 走既有 clamp、日期走 OffsetDateTime 解析失敗 → 400 `VALIDATION_FAILED` |
| V6 Cryptography | no | 本階段無密碼學面 |

### Known Threat Patterns for Spring JdbcClient + 動態查詢

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| ORDER BY / 篩選欄位注入 | Tampering | 白名單映射到寫死 SQL 片段;值一律具名參數(code-standards 硬規則);至少一個 negative test(judgment §11) |
| 越權讀他人 portfolio | Information Disclosure | 所有查詢以 authenticated userId 為必要條件;錯誤訊息不含資源 ID(code-standards) |
| 分頁 DoS(超大 size/page) | DoS | 既有 clamp(size ≤ 100、page ≤ 10_000)沿用;CSV 循環匯出用 size=100 減少請求數 |
| 錯誤訊息洩漏內部細節 | Information Disclosure | `BusinessException` 訊息不含 SQL 片段/內部 ID;traceId 只在前端錯誤狀態顯示(D-12) |

註:本階段皆為 GET(safe method),不觸 CSRF 面;`apiClient` 既有行為已涵蓋。

## Sources

### Primary (HIGH confidence)
- 後端 codebase 直接查證:`TradingController.java`、`TradingService.java`、`JdbcTradingRepository.java`、`TradeDto/HoldingDto/PortfolioSummaryDto.java`、`TradingMapper.java`、`TradeType.java`、`PortfolioCache.java`、`PageResponse.java`、`V7__trading_schema.sql`、`V8`(append-only trigger 存在)、`TradingApiIT.java`、`ContainerIT.java`、`pom.xml`
- 前端 codebase 直接查證:`apiClient.ts`、`apiTypes.ts`、`backtestApi.ts`、`pageApiClients.ts`、`Overview.vue`、`Positions.vue`、`Trades.vue`、`mockPortfolio.ts`、`types.ts`、`i18n.ts`、`api-adapter-wiring.test.ts`、`testSetup.ts`、`package.json`
- 專案規範:`CLAUDE.md`、`ai-docs/judgment.md`、`ai-docs/code-standards.md`、`ai-docs/testing-standards.md`、`ai-docs/flyway-convention.md`
- 環境探測:`java -version`、`docker info`、`node --version`、`npm --version`

### Secondary (MEDIUM confidence)
- [CITED: postgresql.org/docs/current/indexes-expressional.html] — 運算式索引支援、括號語法、寫入維護成本(WebFetch 官方文件確認;digest 已存 research-store,key `4dd3b1e8…`)

### Tertiary (LOW confidence)
- 無(未使用純 WebSearch 來源)

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — 無新套件,版本全數讀自 lockfile/pom
- Architecture: HIGH — 樣板(`backtestApi.ts`)與後端擴充點皆為既有程式碼,逐行查證
- Pitfalls: HIGH(codebase 部分)/ MEDIUM(A1–A4 假設項,已列 Assumptions Log)

**Research date:** 2026-07-23
**Valid until:** 2026-08-22(整合型研究,依 codebase 現狀;若 main 上 trading module 或前端 services 有大改需重驗)
