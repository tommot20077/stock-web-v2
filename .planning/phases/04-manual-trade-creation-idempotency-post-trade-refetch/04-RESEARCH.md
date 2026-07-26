# Phase 4: Manual Trade Creation, Idempotency & Post-Trade Refetch - Research

**Researched:** 2026-07-26
**Domain:** Spring Boot 3.x / Spring Framework 7.0.8 交易寫入冪等（PostgreSQL 唯一約束）+ Vue 3 order ticket API mode 重建
**Confidence:** HIGH（後端全部以 file:line 查證；前端全部實讀；PostgreSQL / Spring 語意有官方文件引用）

> **給 planner 的閱讀順序建議**:先讀「決策點」一節(在文件後半),再回頭讀對應的 Q 段落取證據。
> 本文件**不重新裁決** D-01 ~ D-16;只研究「怎麼實作」與「哪裡真的會爆」。

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

> 以下逐字取自 `04-CONTEXT.md` 的 `<decisions>` 區塊。planner 不得改動語意。

**Ticket 欄位與資料來源**

- **D-01:** **symbol 選單與報價卡全部改接 `GET /api/v1/assets?query=`。** 關鍵事實:`AssetDto` 單一回應就同時帶 `symbol / name / sector / latestPrice / change / changePercent / volumeText / high / low`(`AssetDto.java:9-24`),選單與報價卡不需要兩個端點。走勢圖接 `GET /api/v1/market/{symbol}/klines`(`MarketController.java:96`)。
  順帶解決一件事:symbol 必須是後端真存在且 `tradeable`,不會送出去才吃 `ASSET_NOT_FOUND`(`TradingService.resolveTradeableAsset:237`)。
  **這是 D-16「隱藏假資料」的例外分支,不是違反它** —— D-16 的例外判準本來就是「能由後端真實推導就用真實資料,隱藏只適用於推導不出來的內容」。報價卡的每一格後端都有。

- **D-02:** **fee 改為使用者手動輸入,預設 0。刪除 0.1% 估算公式。**
  理由不是美感問題:`fee` 會被 `HoldingCalculator` 算進 `avg_cost` 與 `realized_pnl`,而 `transactions` 是 append-only(V8 trigger 禁 UPDATE/DELETE),**寫錯的費用永久留在帳本裡且改不回來**。把前端發明的費率當預設值送出,等於使用者按一下就污染成本基礎。

- **D-03:** **給「成交時間」(executedAt)日期時間欄位,預設現在。**
  後端省略此欄位即 `OffsetDateTime.now()`(`TradingService:68`),V9 index 的註解也早已預設「補登舊交易時 executed_at 與 created_at 會分歧」,所以後端已為補登留了空間。
  **本決策產生三個必須處理的後果**:(a) 驗證不可為未來時間;(b) 必須帶時區 offset(後端是 `OffsetDateTime`);(c) 補登的交易可能不在 Trades 頁當前的篩選/排序/頁碼範圍內 → 見 D-10。
  **另有一個正面副作用**:因為 executedAt 由前端明確送出,同一 key 重試時 payload 逐位元相同,D-07 的 payload 比對才有意義(若由後端帶 `now()`,每次重試都會假性不符)。
  **⚠️ 後果 (a) 的後端部分可能已經做完了 —— 但在另一個 branch 上。** draft PR #15(`fix/pr13-review-followups`,commit `d1bd9a1`)已在 `TradingService` 加入 `EXECUTED_AT_FUTURE_TOLERANCE = 5 分鐘` 與 `executedAt must not be in the future` 的 `VALIDATION_FAILED`,而且它的 javadoc 明文寫「補登舊交易是明確支援的情境,故**不設下界**」—— 與 D-03 的方向完全一致。

- **D-04:** **API mode 隱藏三組後端不支援的欄位:訂單類型(MKT/LMT)、TIF(DAY/GTC)、「交易後現金」。**
  前兩者是 judgment §1 反例明文點名;第三者無後端來源(全 repo grep `available_cash|cash_balance|balance|wallet` **零命中**),照 D-14/D-16 隱藏。
  **連帶效果**:MKT 模式原本負責把報價自動填進 price 並鎖住輸入框(`OrderTicket.vue:80, 339-341`)。隱藏之後,price 一律**預填 `AssetDto.latestPrice` 但可編輯**。
  mock mode 四樣全部保留不受影響。

- **D-09:** **送出流程收斂為「送出中 → 已記錄」兩態。** 移除 `routing/match` 三階段假進度、`Math.random()` slippage 與亂數 `orderId`。成功畫面顯示後端回傳的 `TradeDto`:trade id(UUID,除錯回報用)、type、quantity、**實際送出的 price**、fee、executedAt。不得出現「平均成交價」這種暗示撮合的欄位。

**Idempotency 契約(後端)**

- **D-05:** **`Idempotency-Key` header 為必填,缺少回 400。** 不採現有 `BackfillController:90` 的 `required = false`。
  理由:選填等於防護可被繞過。目前**沒有任何真實 client 在呼叫 `POST /trades`**,遷移成本只有更新 `TradingControllerTest`。非瀏覽器 bearer client 也必須自產 UUID(不牴觸 AUTH-07)。

- **D-06:** **header 名稱沿用 `Idempotency-Key`**,與 `BackfillController` 既有慣例一致(不新造名稱)。**但語意刻意不同**:Backfill 是 `tryAcquire` 失敗即回 409 拒絕;本階段必須**回既有交易**(§5 明文)。planner 請勿把 `BackfillIdempotencyService` 當實作樣板照抄 —— 它是同名不同語意的反例。

- **D-07:** **同一 key 送不同 payload → 409 + 專用 error code(建議名 `TRADE_IDEMPOTENCY_KEY_REUSED`)。**
  比對成本幾乎為零:**直接比已存交易列的 `asset_id / type / quantity / price / fee / executed_at`,不需額外 fingerprint 欄位**(前提是 D-03 的 executedAt 明確送出)。

- **D-08:** **key 存在 `transactions` 新欄位,永久保留。** V10 migration:`idempotency_key VARCHAR` + `(user_id, idempotency_key)` partial unique index(`WHERE idempotency_key IS NOT NULL`)。
  不用獨立表 + 清理 job;不用 Redis:§5 明文要求「唯一約束」,而 Redis 指令與 DB transaction 非原子。
  `ALTER TABLE ADD COLUMN` 不受 V8 append-only trigger 影響(trigger 只擋 row 層 UPDATE/DELETE/TRUNCATE)。

**Post-trade refetch**

- **D-10:** **用 shared revision counter 通知,已掛載的頁自行重讀。**
  關鍵架構事實:`App.vue:36` 用 `v-if="page === 'overview'"` 切頁,**非當前頁是卸載的**;而 OrderTicket 是全域 overlay,使用者可能在 Markets/Chart 頁下單。所以「一律打三個 domain 請求」在目前架構下是純粹的無效工。
  做法:成功後提升一個共用 revision;Overview / Positions / Trades `watch` 它,重跑各自**現有的** `loadSummary` / `loadHoldings` / `loadTrades`。

- **D-11:** **Trades 頁重讀時保留篩選與排序、頁碼重置為 0;若新交易不在結果集內則明確告知。** 文案由 planner 決定。

- **D-12:** **交易成功但 refetch 失敗時,兩件事分開呈現。** 成功畫面照 D-09 顯示 trade id;portfolio 區塊各自進 error + retry 態(Phase 3 D-11)。

- **D-13:** **接上 API mode 的「剛成交列 fresh 高亮」。** 用回傳的 `TradeDto` 產生 API mode 的 lastFill 等價物(symbol/type/qty/price)。Phase 3 已在 `Positions.vue:264` 與 `Trades.vue:109` 留下接點與註解,本階段一併清掉那兩條 TODO 註解。

**錯誤與重試**

- **D-14:** **Idempotency key 在「按下送出」時產生;該次嘗試的重試(自動或手動)沿用同一 key;使用者回到表單改過任何欄位後,下次送出換新 key。**
  兩條規則就蓋完所有情境,且**刻意避開與 D-07 的互鎖**。對應 §5:「timeout 重送沿用同一 key;新意圖才換 key」——「改過欄位」就是新意圖的可執行判準。

- **D-15:** **SELL 時載入該標的持倉做預檢,並顯示「可賣數量」。** 後端 409 `TRADE_INSUFFICIENT_HOLDING` 仍是最終權威。
  **注意**:預檢只比數量上限,**不重算成本或損益**(不牴觸 Phase 3 D-04)。

- **D-16:** **欄位級錯誤綁到對應輸入框,其餘顯示在 ticket 底部。**
  `fields` 的 key(symbol/quantity/price/fee/note)綁對應輸入框;`TRADE_INSUFFICIENT_HOLDING` / `ASSET_NOT_FOUND` / `TRADE_CONFLICT` / `TRADE_IDEMPOTENCY_KEY_REUSED` / CSRF 403 顯示在底部並帶 error code + traceId。
  **重要**:`fields` 的 value 是 Bean Validation 的英文預設訊息,**不得直接顯示** —— 前端依 field 名稱對應自己的 i18n 文案。
  session / 認證類錯誤(401、refresh 失敗)仍走 `SessionBanner`。

### Claude's Discretion

- **trading adapter 獨立成 `tradingApi.ts`,不併進 `portfolioApi.ts`。** 依據 REQUIREMENTS.md **VER-02**。mock 實作可以 import `useMockPortfolioStore` 委派 `executeOrder()`(judgment §3 只禁**元件** import mock store),以保住 mock 的 `lastFill` 行為。需在 `pageApiClients.ts` 的 `RuntimeApiClients` 註冊 `trading`。
- **`OrderTicket.vue` 移除 `useMockPortfolioStore` 與 `useMockNotificationsStore` 的直接 import,改走 service interface**。mock 通知的推送由 mock 實作負責,API mode 不推。
- **key 產生用 `crypto.randomUUID()`**;前端 duplicate-submit guard 沿用現有 `placing` ref + button disabled 的雛形,但依 §5 只當 UX。
- 元件拆分、loading 骨架、日期選擇器的具體版面、「不在篩選條件內」的文案 → 依現有 UI 慣例決定。
- 新 error code 的最終命名可由 planner 微調,但必須是 409 且與 `DUPLICATE_RESOURCE`(既有 409)語意區分開。

### Deferred Ideas (OUT OF SCOPE)

- **四條後端資料缺口** → 已由 Yuan 排入 **Phase 04.1**(可用現金/帳戶餘額、日級損益、資產分類、watchlist API 化)。**本階段不做。**
- **批次補登歷史交易(bulk import / CSV)** — 不在本階段。
- **notifications API 化** — API mode 不推通知,屬 PORT-06(v2)。
- **多幣別呈現** — `assets.currency` 有值但 portfolio 彙總不分幣別;不在本階段。
- pending order / cancel / partial fill / TIF / broker 撮合 — PROJECT.md Out of Scope + judgment §1。

</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| **TRAD-01** | Vue order ticket 在 API mode 建立 manual executed buy/sell trade,而不是 broker order | Q5(`tradingApi.ts` 三件組 + `apiRequest` POST)、Q6(OrderTicket 重建面 + 移除 wizard/slippage/orderId)、D-01/D-09 |
| **TRAD-02** | Trade creation request 明確映射到後端 `CreateTradeRequest` contract,避免傳送 pending order / cancel / TIF | Q6(逐項對照 `CreateTradeRequest.java:18-28` 的 7 欄位)、D-04 隱藏 ordType/tif/cashAfter |
| **TRAD-03** | Backend trade creation 支援 server-side idempotency | **Q1**(交易邊界、insert-first + `ON CONFLICT DO NOTHING`、Spring 例外型別)、Q2(V10 partial unique index)、Q4(payload 比對) |
| **TRAD-04** | Frontend duplicate-submit guard,但不取代 server-side idempotency | Q6(`placing` ref 現況 + 送出鈕**目前沒有** `:disabled`)、Q5(D-14 key 生命週期 + 401 replay 自動沿用同 key) |
| **TRAD-05** | 交易成功後重新讀取 summary / holdings / trades | **Q7**(shared revision counter 放哪、三頁 `watch`、`applyQueryChange` 是 D-11 的現成入口) |
| **TRAD-06** | 錯誤以使用者可理解方式顯示,保留 backend error code / request id | **Q8**(`ApiError.fields` 契約 + `ApiClientError.fields/requestId` 已就緒)、Q3(缺 header 的實際 envelope) |
| VER-01(Phase 5) | 後端測試涵蓋 trading idempotency | Q10 提供 IT 清單;本階段就要寫,Phase 5 只是收斂驗證 |
| VER-02(Phase 5) | 前端測試涵蓋 trading adapter | Q10 提供 vitest 清單(含 mock-factory-not-called) |

</phase_requirements>

---

## Project Constraints (from CLAUDE.md)

| 指令 | 對本階段的具體含意 |
|------|-------------------|
| **TDD 是硬約束(Red → Green → Refactor)** | 每個工作區塊都必須先有紅燈測試。Q11 逐項給出「第一個失敗測試是什麼、用哪個指令跑」。Flyway migration 與 Vue 版面是 TDD 尷尾處,Q11 給出誠實替代品。 |
| 一律以繁體中文回應 | 本文件與所有 plan / summary / 註解用繁中;測試方法名用英文 camelCase + `@DisplayName("繁中")`(testing-standards.md:11-13)。 |
| Evidence-based | 本文件所有既有程式碼陳述皆帶 file:line;無法查證者標 `UNVERIFIED`。 |
| 驗證命令 | Backend `./mvnw test` / `./mvnw -pl <module> -am test` / `./mvnw -pl stock-start -am verify`;Frontend `cd ../../vue/stock-v2/vue-app && npm test && npm run build`,API mode 前綴 `VITE_DATA_MODE=api`。PowerShell 下用 `mvnw.cmd`、分步執行、`$env:VITE_DATA_MODE='api'`。 |
| 遇模糊先查 `ai-docs/judgment.md` §9 | 本階段的契約變更(D-05~D-08)**已於 2026-07-26 取得 Yuan 同意**,不需再問。Q0 的 PR #15 排序問題**需要問**(見決策點 DP-1)。 |
| 測試輸出保持乾淨(judgment §11) | 冪等衝突路徑若走「拋例外再接」,`GlobalExceptionHandler` 的 catch-all 會印 ERROR log → 污染測試輸出。這是 Q1 選型的一個實質理由。 |

---

## Summary

這個 phase 的技術風險**集中在後端一個方法**(`TradingService.createTrade`),其餘都是有前例可循的接線工作。

**後端**:`createTrade` 目前是單一 `@Transactional` 方法(`TradingService.java:60`),順序是「解析 asset → 讀/改 holdings → insert transaction」。要塞進冪等,最大的陷阱不是唯一約束本身,而是**衝突發生的時機晚於 holdings 已被改動**。CONTEXT.md 已正確指出 PostgreSQL 的約束違反會中止整個 transaction(官方文件證實:「`ROLLBACK TO` is the only way to regain control of a transaction block that was put in aborted state by the system due to an error」),所以「同一個 `@Transactional` 方法內 catch 例外再重讀」是**行不通的**。本研究找到一個比 CONTEXT.md 提示的「外層非交易方法 + 內層交易方法」更簡單、且完全避開中止交易問題的做法:**把 transaction insert 移到 holdings 之前,用 `insert ... on conflict (user_id, idempotency_key) where idempotency_key is not null do nothing returning ...`**。回傳有列 → 這個 key 是我的,繼續改 holdings;回傳零列 → key 是別人的,**什麼都沒碰**,重讀既有交易、比對 payload(D-07)、回傳。這樣單一 transaction 就夠,不需要第二個 bean、不需要 `REQUIRES_NEW`、不會有 Spring self-invocation 陷阱、不會有 ERROR log 噪音。代價是必須驗證「零列時既有列在同一 READ COMMITTED 交易內可見」——PostgreSQL 官方文件**沒有**明文保證 `DO NOTHING` 會等待併發交易 commit,所以必須用 IT 證明,並保留 `TRADE_CONFLICT`(既有 409)作為理論殘餘競態的優雅退路。細節與替代方案的完整比較見 Q1。

**Q0 的結論是明確的**:draft PR #15 未合併,`ApiTimeParser` 不在 develop 也不在 HEAD。但它與 Phase 4 的**實質衝突面極小**——`createTrade` 的改動集中在方法前 6 行(驗證順序重排 + `resolveExecutedAt`),而 Phase 4 要插入的冪等邏輯位置與它不重疊;`TradingController` 的 PR #15 改動**只有 javadoc**。真正的問題不是 merge conflict 而是**重複實作**:PR #15 已完成 D-03(a) 的未來時間驗證,Phase 4 若自己再做一次就違反 judgment §6 的精神。另外一個被低估的事實:PR #15 也修掉了 `MethodArgumentTypeMismatchException` → HTTP 500 的問題,而 Phase 4 是 `GET /market/{symbol}/klines` 的**第一個前端消費者**,該端點的 `from` 參數型別是 `Instant`(`MarketController.java:100`)——在 develop 上送錯格式會拿到 **500 + ERROR log**,而不是 400。建議見 DP-1。

**前端**:比 CONTEXT.md 預期的**順利**。`apiClient.ts` 的 `ApiRequestOptions extends Omit<RequestInit, 'body'>`(:32-34)已經支援自訂 header,而且 `opsApi.ts:146-154` 就是一個現成的「POST + `Idempotency-Key` header」樣板——不需要任何 transport 層擴充。更好的是 401 refresh 的 replay 路徑(`apiClient.ts:319`)會用**同一份 options** 重打,所以自動 replay 天然沿用同一 key,正好符合 D-14。`Trades.vue:282-286` 的 `applyQueryChange()` 已經是「保留篩選排序、頁碼歸零、重新請求」的現成入口,D-11 直接呼叫它即可。**唯一真正的重建工作在 `OrderTicket.vue`**,CONTEXT.md 列的行號我逐條查證,只有一處有實質偏移(見「行號漂移報告」):送出鈕(`:185`)**目前完全沒有 `:disabled`**,duplicate-submit 只靠函式內的 `placing.value` 早退(`:358`)——D-09 收斂成兩態之後必須明確補上 `:disabled`,否則 TRAD-04 只有一半。

**Q12 有一個必須更正的前提**:STATE.md 說 Phase 3 前端 commit 停在未 push 的 `feature/phase-03-portfolio-read` branch。**這已不成立** —— sibling repo 的 `develop` 上有 `a03e030 Merge pull request #8 from tommot20077/feature/phase-03-portfolio-read`,工作樹乾淨且與 `origin/develop` 同步。Phase 4 前端應從 `develop` 開分支。

**Primary recommendation:** 後端採「insert-first + `ON CONFLICT DO NOTHING RETURNING` + 同交易重讀 + `TRADE_CONFLICT` 退路」,並**先等 PR #15 合併**(或至少把 executedAt 驗證與 time parser 明確排除在 Phase 4 範圍外);前端 `tradingApi.ts` 照 `opsApi.ts:142-161` 的 header 樣板 + `portfolioApi.ts:140-202` 的三件組結構寫,revision counter 照 `pageApiClients.ts:19,37-39` 的「模組級 singleton + 測試 reset」慣例建一個小模組。

---

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| 重複提交防護(冪等) | **API / Backend**(`TradingService` + PostgreSQL 唯一約束) | Browser(disabled button,純 UX) | judgment §5 明文:防護在後端,前端 debounce 只是 UX。DB 唯一約束是唯一能抗併發的層。 |
| payload 一致性比對(D-07) | **API / Backend**(`TradingService`) | — | 需要讀已存交易列;前端不可能知道別人送過什麼。 |
| executedAt 未來時間驗證 | **API / Backend**(`TradingService.resolveExecutedAt`,PR #15 已實作) | Browser(input `max` 屬性,UX) | 後端是權威;前端只是不讓使用者浪費一次來回。 |
| symbol 可交易性判定 | **API / Backend**(`resolveTradeableAsset:237-243`) | Browser(選單只列 `tradeable: true`) | `AssetDto.tradeable`(:17)讓前端能先篩,但 `ASSET_NOT_FOUND` 仍是權威。 |
| 手續費(fee)決定 | **Browser**(使用者輸入,D-02) | Backend(`@DecimalMin(0.0)` 驗證) | 手動記錄已成交交易,fee 是事實輸入而非計算結果。後端只驗範圍。 |
| 成本基礎 / 已實現損益計算 | **API / Backend**(`HoldingCalculator`) | — | judgment §7 + Phase 3 D-04:前端絕不重算。 |
| 可賣數量預檢(D-15) | **Browser**(讀 `listHoldings()` 比數量) | Backend(`TRADE_INSUFFICIENT_HOLDING` 為最終權威) | 前端只做「數量上限」比較,不重算成本。 |
| 報價卡數字 / 走勢圖 | **API / Backend**(`AssetDto`、`KlineDto`) | Browser(僅格式化) | D-01/D-16 例外分支:後端有真資料就用真資料。 |
| post-trade refetch 協調 | **Browser**(shared revision counter + 各頁 `watch`) | — | 純 client-side 狀態協調;後端已由 `PortfolioCache.invalidateAfterTrade` 保證讀到新值。 |
| 快取失效 | **API / Backend**(`PortfolioCache.invalidateAfterTrade:45-52`) | — | **本階段不需改動**;已同時刪 holding 與 summary 兩個 key(CONTEXT.md 查證)。 |
| 錯誤文案 i18n | **Browser**(`src/i18n.ts`) | Backend(只給 `code` 與 `fields` 的 key) | `fields` 的 value 是英文 Bean Validation 預設訊息,D-16 明文禁止直接顯示。 |

---

## Q0 — PR #15 衝突面(最高優先)

### 事實確認(orchestrator 已驗,本研究再證)

| 事實 | 證據 |
|------|------|
| PR #15 是 **OPEN + draft**,未合併 | orchestrator `gh pr view 15`;本研究 `gh pr diff 15 --name-only` 成功回應(未合併的 PR 才有 diff) |
| `ApiTimeParser` 不在 develop、不在 HEAD | orchestrator `git ls-tree`;本研究 `git diff origin/develop...origin/fix/pr13-review-followups` 顯示它是 **新增檔** |
| 本地存在 `fix/pr13-review-followups-rebased` 與 `pr15-merge` 兩個 branch | `git branch -a`(2026-07-26) |
| PR #15 共 9 個 commit | `gh pr view 15 --json commits` |

### PR #15 完整改檔清單(`gh pr diff 15 --name-only`)

```
.planning/phases/03-portfolio-read-api-mode/03-01-SUMMARY.md
stock-common/pom.xml
stock-common/src/main/java/dowob/xyz/stockwebv2/common/time/ApiTimeParser.java          ← 新增
stock-common/src/test/java/dowob/xyz/stockwebv2/common/time/ApiTimeParserTest.java      ← 新增
stock-db-migration/src/main/resources/db/migration/V9__trading_query_indexes.sql        ← ⚠️ 改既有 migration
stock-module-trading/pom.xml
stock-module-trading/.../api/TradingController.java
stock-module-trading/.../domain/ApiValueParser.java
stock-module-trading/.../domain/SortDirection.java
stock-module-trading/.../domain/TradeSortKey.java
stock-module-trading/.../domain/TradeType.java
stock-module-trading/.../repository/JdbcTradingRepository.java
stock-module-trading/.../service/TradingService.java                                    ← ⚠️ Phase 4 主戰場
stock-module-trading/src/test/.../api/TradingControllerTest.java                        ← ⚠️ D-05 要改這支
stock-module-trading/src/test/.../service/TradingServiceTest.java                       ← ⚠️ Phase 4 要加測試
stock-start/.../start/error/GlobalExceptionHandler.java                                 ← ⚠️ Q3 相關
stock-start/src/test/.../TradingApiIT.java                                              ← ⚠️ Phase 4 要加 IT
stock-start/src/test/.../error/GlobalExceptionHandlerTest.java
```

### 衝突面量化:Phase 4 也要改的檔案 × PR #15 也改的檔案

| 檔案 | Phase 4 需要改? | PR #15 改了什麼 | 衝突嚴重度 |
|------|----------------|----------------|-----------|
| `TradingService.java` | **是**(冪等邏輯) | 重排 `createTrade` 前 6 行驗證順序、新增 `resolveExecutedAt`、`listTrades` 改用 `ApiTimeParser` 並加 `@Transactional(readOnly=true)`、刪 `parseTimestamp` | **中**。改動區域**部分重疊**(見下方逐行分析) |
| `TradingController.java` | **是**(加 `@RequestHeader`) | **只有 javadoc**(class-level + 兩個方法的 javadoc)。方法簽章、`@PostMapping` 內容**逐字未變** | **低**。git 三方合併幾乎必然自動成功 |
| `JdbcTradingRepository.java` | **是**(新增 key 查詢 + insert 帶 key) | **只有 javadoc**(class-level + `listTransactions` + `buildFilter`) | **低**。Phase 4 動 `insertTransaction`(:35-70)與新增方法;PR #15 完全沒動 `insertTransaction` |
| `TradeTransaction.java`(record) | **是**(加 `idempotencyKey` 第 13 個 component) | 未改 | **無** |
| `ErrorCode.java` | **是**(加 409 code) | 未改 | **無** |
| `GlobalExceptionHandler.java` | **可能**(Q3:是否補 `MissingRequestHeaderException` handler) | 新增 `handleTypeMismatch`(插在 `handleValidation` 之後、`handleUnexpected` 之前) | **中**。若 Phase 4 也在同一位置插新 handler,同一 hunk 衝突 |
| `TradingControllerTest.java` | **是**(D-05 明文:遷移成本只有更新這支) | 未改內容(在 diff 清單但屬 PR13 review 的既有覆蓋補齊) | **中** |
| `TradingApiIT.java` | **是**(冪等 IT) | 新增查詢參數三層覆蓋的多個測試 | **中**(檔尾附加,通常可自動合併) |
| `V9__trading_query_indexes.sql` | **否**(Phase 4 建 V10) | **改了既有 migration 內容**(加 `IF NOT EXISTS`、加一個索引、`DROP INDEX idx_transactions_user_asset_created`) | **無直接衝突**,但見下方 Flyway checksum 警告 |
| `stock-common/pom.xml`、`stock-module-trading/pom.xml` | 否 | 只加 `commons-lang3` 顯式宣告與註解 | **無** |

### `createTrade` 逐行:HEAD 順序 vs PR #15 後的順序

**HEAD**(`TradingService.java:60-104`,已實讀):

```
60  @Transactional
61  createTrade(userId, request)
62-64   null 檢查 → VALIDATION_FAILED
65      resolveTradeableAsset(request.symbol())        ← DB I/O(第一件事)
66      TradeType.fromApiValue(request.type())
67      fee = requireNonNullElse(request.fee(), ZERO)
68      executedAt = requireNonNullElseGet(request.executedAt(), OffsetDateTime::now)
69      repository.findHoldingForUpdate(userId, asset.id())   ← DB I/O + FOR UPDATE
70-87   holdings 計算 + insert/update(含 on-conflict 重讀併倉路徑 :78-84)
88-101  repository.insertTransaction(...)               ← DB I/O
102     portfolioCache.invalidateAfterTrade(...)
103     return mapper.toTradeDto(saved)
```

**PR #15 後**(`git diff origin/develop...origin/fix/pr13-review-followups`,已實讀 diff):

```
@Transactional
createTrade(userId, request)
    null 檢查
    now = OffsetDateTime.now()                          ← 新增:單一時間基準
    TradeType.fromApiValue(request.type())              ← 零 I/O,移到最前
    fee = requireNonNullElse(...)                       ← 零 I/O
    executedAt = resolveExecutedAt(request.executedAt(), now)   ← 新增,零 I/O,擋未來時間
    resolveTradeableAsset(request.symbol())             ← DB I/O,移到最後
    findHoldingForUpdate(...)
    holdings 計算(所有 OffsetDateTime.now() 換成 now)
    insertTransaction(...)
    invalidateAfterTrade / return
```

**Phase 4 的冪等查詢應該插在哪裡?** 兩個版本的答案一致:**零 I/O 驗證之後、`resolveTradeableAsset` 之前**。理由(CONTEXT.md 已述):key 查詢是 I/O,但比資產查詢更該先做——duplicate 應該連資產查詢都不必付。

在 HEAD 上,這個位置在 `:65` 之前;在 PR #15 上,在 `resolveExecutedAt` 之後。**這兩個插入點的上下文文字完全不同**,所以若 Phase 4 先在 develop 上實作、PR #15 後合併,git 會在這個 hunk 報衝突,而且**衝突內容涉及驗證順序的語意**——不是機械式的空白差異,需要人工判斷。

### PR #15 引入的、Phase 4 否則會重複建造的東西

| 項目 | PR #15 的實作 | Phase 4 若不知情會怎樣 |
|------|--------------|----------------------|
| `executedAt` 未來時間驗證 | `EXECUTED_AT_FUTURE_TOLERANCE = Duration.ofMinutes(5)` + `resolveExecutedAt()` 私有方法 + `VALIDATION_FAILED "executedAt must not be in the future"`;javadoc 明文「刻意不設下界:補登舊交易是本帳本明確支援的情境」 | **重複實作 D-03(a)**,而且兩份可能容忍度不同(例如一份 0 分鐘一份 5 分鐘)→ 合併後行為不確定 |
| `ApiTimeParser`(`stock-common/.../common/time/`)+ `ApiTimeParserTest` | 新類別,處理純日期 / 帶偏移量 / servlet 把裸 `+` 解成空白的還原 | **另造一個重複的時間解析器**,直接違反 judgment §6 的精神(「先 grep 確認,不存在才建,勿另造重複品」)。注意:在 develop 上 grep 是**找不到**它的 |
| `MethodArgumentTypeMismatchException` → 400 handler | `GlobalExceptionHandler.handleTypeMismatch`,填 `fields: {paramName: "invalid format"}` | 見下方「被低估的第三個影響」 |
| 錯誤優先序改變 | 「type 打錯 + symbol 不存在」由 `ASSET_NOT_FOUND` 改回 `TRADE_UNSUPPORTED_TYPE` | 若 Phase 4 的前端測試對錯誤**順序**寫死斷言,PR #15 合併後會紅。**D-16 的實作必須以 `error.code` 為準、不假設優先序** |

### 被低估的第三個影響:klines 端點在 develop 上會回 500

Phase 4 是 `GET /api/v1/market/{symbol}/klines` 的**第一個前端消費者**(CONTEXT.md `<specifics>` 明文)。查證該端點簽章:

```java
// MarketController.java:96-105
@GetMapping("/{symbol}/klines")
public ApiResponse<List<KlineDto>> klines(
    @PathVariable("symbol") String symbol,
    @RequestParam("interval") String intervalCode,
    @RequestParam("from") Instant from,                              // 必填,型別 Instant
    @RequestParam(value = "to", required = false) Instant to,
    @RequestParam(value = "limit", required = false) Integer limit
)
```

`from` 宣告為 `Instant` 且**必填**。若前端送 `from=2026-01-01`(無偏移量)或送裸的 `+08:00`,Spring 會拋 `MethodArgumentTypeMismatchException`。查證該例外的繼承鏈:

```
MethodArgumentTypeMismatchException extends org.springframework.beans.TypeMismatchException
TypeMismatchException             extends org.springframework.beans.PropertyAccessException
```
（`javap -cp spring-web-7.0.8.jar` / `spring-beans-7.0.8.jar`,2026-07-26 實跑）

**它沒有實作 `org.springframework.web.ErrorResponse`。** 因此在 develop 的 `GlobalExceptionHandler` 中會落到 `handleUnexpected`(:66-75),`instanceof ErrorResponse` 為 false → **HTTP 500 + `log.error` ERROR 級 log**。PR #15 的 `handleTypeMismatch` 就是修這個。

對 Phase 4 的實務影響:前端開發 klines 走勢圖時,一個純粹的參數格式錯誤會表現成「後端掛了」,而且測試輸出會被 ERROR log 污染(違反 judgment §11 的 pristine output)。

### Flyway checksum 警告(順帶發現,planner 需知)

PR #15 **修改了已存在的 `V9__trading_query_indexes.sql` 內容**(從 `CREATE INDEX` 改成 `CREATE INDEX IF NOT EXISTS`、新增 `idx_transactions_user_asset_executed`、`DROP INDEX IF EXISTS idx_transactions_user_asset_created`)。

`ai-docs/flyway-convention.md:33` 明文:「**Never modify** a migration that has already been applied to any environment (Flyway checksum validation will fail)」,:162 再列為 Prohibited。

在 CI / Testcontainers 環境下每次都是全新 DB,所以測試會綠。但**任何已套用過 V9 的長期環境(dev / demo 資料庫)在 PR #15 合併後啟動會 checksum 失敗**。這不是 Phase 4 的責任,但它是「PR #15 合併」這個動作的隱含成本,planner 若選擇「等它合併」需要知道這條。

### Q0 建議:採 **(c) 的強化版** —— 以 develop 為基準,但把 executedAt 驗證與 time parser 明確排除在 Phase 4 範圍外

| 選項 | 評估 |
|------|------|
| **(a) 等 PR #15 合併** | 技術上最乾淨,零重複實作、零衝突。但 PR #15 是 **draft**,還帶著上述 Flyway checksum 債,合併時程不由 Phase 4 控制。若無限期等待,Phase 4 就被一個 draft PR 綁住。**若 Yuan 願意在近期把 PR #15 推進到 merge,這是最佳選項。** |
| **(b) 以 develop 為基準,自己補 executedAt 驗證** | **不建議。** 會產生兩份未來時間驗證與(可能)兩個 time parser,直接踩 judgment §6,且合併時的衝突內容涉及語意判斷。 |
| **(c) 以 develop 為基準,Phase 4 只做冪等** | **建議。** Phase 4 的後端範圍收斂為:V10 migration、`TradeTransaction` 加欄位、`JdbcTradingRepository` 的 key 查詢與帶 key insert、`TradingService` 的冪等分支、`ErrorCode` 新 409 code、`TradingController` 的 `@RequestHeader`。**不動** `resolveExecutedAt`、**不建** time parser、**不改**驗證順序。前端照 D-03 送 `executedAt`(帶完整 offset,`Date.toISOString()` 即符合),並在 input 上加 `max` 屬性做 UX 層防護;「送未來時間會被後端擋」這條驗收留給 PR #15,Phase 4 的 SUMMARY 明確記錄此依賴。 |

**採 (c) 時 planner 必須額外做的三件事:**

1. **在 04-SUMMARY.md 明確記錄「executedAt 未來時間驗證由 PR #15 提供,Phase 4 不實作」**,並列出 PR #15 的 branch 名與 commit(`fix/pr13-review-followups`, `d1bd9a1`)。
2. **前端 D-16 的錯誤處理實作與測試一律以 `error.code` 分派,絕不對錯誤出現順序寫死斷言**(PR #15 commit 訊息自己就提醒了這件事)。
3. **klines 消費端必須從第一天就送格式正確的 `from`**(完整 ISO instant,`+` 需 `%2B` 編碼——`buildQueryString` 用 `encodeURIComponent` 已自動處理,見 `apiClient.ts:69-74`),並在 adapter 測試裡鎖住這個格式,避免踩到 develop 上的 500。

---

## Q1 — PostgreSQL 冪等 insert / 重讀語意(最高技術風險)

### Q1.1 `createTrade` 今天的交易邊界是什麼?

| 事實 | 證據 |
|------|------|
| `createTrade` 標了 `@Transactional`(Spring 的 `org.springframework.transaction.annotation.Transactional`) | `TradingService.java:60`;import 在 `:24` |
| `listTrades` / `listHoldings` / `summary` 在 HEAD **沒有** `@Transactional`(PR #15 才加 `readOnly=true` 到 `listTrades`) | `TradingService.java:125, 182, 188` 無註解 |
| `JdbcTradingRepository` **沒有**任何 `@Transactional` | 全檔實讀(291 行),只有 `@Repository`(:21) |
| 專案**沒有**任何 `TransactionTemplate` 使用 | `grep -rn "TransactionTemplate"` 無命中(排除 worktrees) |
| repository 用 `org.springframework.jdbc.core.simple.JdbcClient`(Spring 6.1+ 的 fluent API),**不是** `NamedParameterJdbcTemplate` 也不是 `JdbcTemplate` | `JdbcTradingRepository.java:10`(import)、`:28`(欄位)、全檔所有查詢都是 `jdbcClient.sql(...)` |
| 交易隔離等級未設定 → PostgreSQL 預設 **READ COMMITTED** | `stock-start/src/main/resources/application.yaml` 全檔無 `isolation`;`grep -rn "isolation\|Isolation"` 在 yaml 中零命中 |

**結論:`createTrade` 的整個方法體就是一個 transaction。** 因此任何「衝突後重讀」的程式碼若寫在這個方法**裡面**,都落在同一個(已中止的)交易內。

### Q1.2 為什麼「同一 `@Transactional` 方法內 catch 例外再重讀」行不通

PostgreSQL 官方文件(`postgresql.org/docs/16/tutorial-transactions.html`)明文:

> "`ROLLBACK TO` is the only way to regain control of a transaction block that was put in aborted state by the system due to an error, short of rolling it back completely and starting again."

也就是說,唯一約束違反(SQLSTATE `23505`)之後,該連線上的交易進入 aborted state,**後續任何述句都會被拒絕**,直到 `ROLLBACK` 或 `ROLLBACK TO SAVEPOINT`。所以:

```java
// ❌ 這樣寫一定壞
@Transactional
public TradeDto createTrade(...) {
    ...
    try {
        return repository.insertTransaction(txn);
    } catch (DuplicateKeyException e) {
        // 這行會拋 "current transaction is aborted, commands ignored until end of transaction block"
        return repository.findByIdempotencyKey(userId, key).orElseThrow();
    }
}
```

**額外一層問題**:即使重讀能成功,Spring 的 `@Transactional` 在偵測到 `RuntimeException` 穿過任何交易 proxy 時會把交易標為 rollback-only;而且 holdings 的改動也還在同一個交易裡,如果最終 commit 成功,holdings 就被**雙重套用**了。

### Q1.3 為什麼現有 `insertHoldingIfAbsent` 的重讀路徑不能照抄

`TradingService.java:78-84` 的重讀之所以安全,是因為 `insertHoldingIfAbsent` 用的是 `on conflict (user_id, asset_id) do nothing`(`JdbcTradingRepository.java:93`)——**`ON CONFLICT` 不是錯誤,交易不會中止**,`.optional()`(:103)拿到空 Optional 而不是例外。CONTEXT.md 說「不要照抄」是對的:如果 Phase 4 依賴的是**裸的唯一約束違反**,語意完全不同。

但反過來看,這正好提示了正確答案:**把 transactions 的 insert 也改成 `ON CONFLICT DO NOTHING`,那就從頭到尾不會有中止交易的問題。**

### Q1.4 Spring 把 PostgreSQL `23505` 翻譯成哪個例外型別?

**答案:`org.springframework.dao.DuplicateKeyException`(繼承 `DataIntegrityViolationException`)。**

證據分三層:

1. **專案內既有實證**(最強):`JdbcUserRepository.java:86` 對 `users` 的 `insert` 包了 `catch (DataIntegrityViolationException exception)`,並在 `:104-123` 用約束名(`uk_users_email` / `uk_users_username`)分派成 `DuplicateResourceException`。而 `ErrorHandlingIT` 有一個綠燈測試斷言重複註冊 → `409 DUPLICATE_RESOURCE`(`ErrorHandlingIT.java:35-38`)。**所以在這個確切的 stack(PgJDBC + `JdbcClient` + Spring 7.0.8)上,PostgreSQL 唯一約束違反確實被翻譯成 `DataIntegrityViolationException` 家族。**
2. **Spring 類別存在性驗證**:`SQLStateSQLExceptionTranslator` 有 `private static final Set<String> DATA_INTEGRITY_VIOLATION_CODES` 與 `static boolean indicatesDuplicateKey(String, int)`(`javap -p -cp spring-jdbc-7.0.8.jar`,2026-07-26 實跑)。SQLSTATE class code `23` 落在前者,`23505` 落在後者 → `DuplicateKeyException`。
3. PgJDBC 拋的是 `org.postgresql.util.PSQLException`(**不是** `SQLIntegrityConstraintViolationException`),所以 `SQLExceptionSubclassTranslator` 的 subclass 分支不會命中,會 fallback 到 `SQLStateSQLExceptionTranslator` 的 SQLSTATE 分支。`[ASSUMED]` — PgJDBC 的例外型別未在本 session 直接 javap 驗證,但結論不受影響:`DuplicateKeyException` 是 `DataIntegrityViolationException` 的子類,**catch 後者一定接得到**。

> **給 planner 的操作結論**:若採「catch 例外」路線,一律 catch `DataIntegrityViolationException`(與 `JdbcUserRepository:86` 一致),不要只 catch `DuplicateKeyException`。

### Q1.5 `(user_id, idempotency_key)` 查詢的具體程式碼形狀

依 `JdbcTradingRepository` 現有風格(`JdbcClient` + 具名參數 + text block + `TRANSACTION_COLUMNS` 常數 + `.optional()`):

```java
// JdbcTradingRepository — 與 :129-146 的 listTransactions 共用 TRANSACTION_COLUMNS(:23-26)
// 注意 TRANSACTION_COLUMNS 用 t.* 前綴且含 a.symbol,所以必須保留 join assets（見 PR #15 加的
// buildFilter 不變量註解:共用 WHERE 只能引用 t.*，但 SELECT 清單本身需要 a.symbol）。
@Override
public Optional<TradeTransaction> findByIdempotencyKey(Long userId, String idempotencyKey) {
    return jdbcClient.sql("select " + TRANSACTION_COLUMNS + """
            from transactions t
            join assets a on a.id = t.asset_id
            where t.user_id = :userId and t.idempotency_key = :idempotencyKey
            """)
        .param("userId", userId)
        .param("idempotencyKey", idempotencyKey)
        .query(this::mapTransaction)          // 既有 mapper，:245-260
        .optional();
}
```

`mapTransaction`(:245-260)**需要擴充**以讀出新的 `idempotency_key` 欄位(因為 `TradeTransaction` record 會多一個 component)。同樣 `TRANSACTION_COLUMNS`(:23-26)也要加 `t.idempotency_key`。

**`insertTransaction` 的 ON CONFLICT 版本**(改 `:35-70`):

```java
@Override
public Optional<TradeTransaction> insertTransactionIfAbsent(TradeTransaction transaction) {
    return jdbcClient.sql("""
            insert into transactions (
                uuid, user_id, asset_id, type, quantity, price, fee, note, executed_at, idempotency_key
            )
            values (
                coalesce(:uuid, uuid_generate_v4()), :userId, :assetId, :type,
                :quantity, :price, :fee, :note, :executedAt, :idempotencyKey
            )
            on conflict (user_id, idempotency_key) where idempotency_key is not null do nothing
            returning id, uuid, user_id, asset_id, type, quantity, price, fee, note,
                      executed_at, created_at, idempotency_key
            """)
        // ... params 同 :46-54 再加 idempotencyKey
        .query(/* row mapper */)
        .optional();      // ⚠️ 必須從 .single() 改成 .optional()
}
```

**兩個必須注意的細節:**

1. **`.single()` 必須改成 `.optional()`。** 現有 `insertTransaction` 結尾是 `.single()`(:69),在 `ON CONFLICT DO NOTHING` 且發生衝突時回傳零列,`.single()` 會拋 `EmptyResultDataAccessException`(`IncorrectResultSizeDataAccessException` 家族)。`insertHoldingIfAbsent` 就是用 `.optional()`(:103),照它做。
2. **partial unique index 的 `ON CONFLICT` 推斷必須帶 `WHERE index_predicate`。** PostgreSQL 官方文件(`postgresql.org/docs/16/sql-insert.html`)明文:

   > "When performing inference, it consists of one or more `index_column_name` columns and/or `index_expression` expressions, and an optional `index_predicate`. All `table_name` unique indexes that, without regard to order, contain exactly the `conflict_target`-specified columns/expressions are inferred (chosen) as arbiter indexes. **If an `index_predicate` is specified, it must, as a further requirement for inference, satisfy arbiter indexes.**"
   >
   > "**`index_predicate`** — Used to allow inference of partial unique indexes. Any indexes that satisfy the predicate (which need not actually be partial indexes) can be inferred."

   省略 `where idempotency_key is not null` 時,partial unique index 無法被推斷,PostgreSQL 會直接報 `there is no unique or exclusion constraint matching the ON CONFLICT specification`。**這是一個「寫錯就 100% 炸、而且訊息不直觀」的陷阱,planner 必須把它寫進 plan 的驗收條件。**

### Q1.6 `SELECT ... FOR UPDATE` 還是 `ON CONFLICT DO NOTHING`?—— 誠實評估

`SELECT ... FOR UPDATE` 在這裡**沒用**:`FOR UPDATE` 只鎖已存在的列,對「這個 key 還不存在」的情境不加任何鎖(這正是 `TradingApiIT.concurrentFirstBuysMergeWithoutUniqueViolation` 的註解在 `:180-181` 指出的同一個問題:「`findHoldingForUpdate` 對不存在的 row 不加鎖」)。所以 `FOR UPDATE` 排除。

`ON CONFLICT DO NOTHING` **是關鍵簡化**,但它單獨還不夠 —— 因為它解決的是「交易不會中止」,沒解決「holdings 已經被改了」。**必須配合把 insert 移到 holdings 之前**。

### Q1.7 四個候選方案的完整比較

假設要達成的三個不變量:
- **I1** duplicate 進來時,holdings **完全不被改動**。
- **I2** 併發同 key 兩個 request 都通過前置查詢時,**不能回 500**。
- **I3** `transactions` 表最終只有一列。

| 方案 | 機制 | 滿足 I1/I2/I3? | 評估 |
|------|------|---------------|------|
| **A. insert-first + `ON CONFLICT DO NOTHING RETURNING`**(**建議**) | 順序改為:零 I/O 驗證 → key 查詢(快路徑)→ `resolveTradeableAsset` → **insert transaction with ON CONFLICT** → 有列則改 holdings / 零列則重讀既有交易 + D-07 比對 + 回傳 | **是**(零列時 holdings 一行都沒動) | 單一 `@Transactional`、無 proxy 陷阱、無中止交易、無 ERROR log 噪音、程式碼最短。**風險見 Q1.8** |
| **B. 外層非交易方法 + 內層交易方法 + catch `DataIntegrityViolationException`**(CONTEXT.md 提示的路線) | 外層 `createTrade`(無 `@Transactional`)呼叫 `createTradeTransactional`;內層衝突 → 例外穿出 → Spring rollback(holdings 一併回滾)→ 外層 catch → 在新交易重讀 | **是** | 正確,但有一個**必踩的 Spring 陷阱**:`this.createTradeTransactional(...)` 是 self-invocation,Spring 官方文件明文「self-invocation ... **does not lead to an actual transaction at runtime** even if the invoked method is marked with `@Transactional`」(docs.spring.io, declarative/annotations)。必須拆成**兩個 bean** 或注入 self proxy。額外成本:多一個類別、ERROR log 噪音(catch-all 前的 log 由 Spring 交易層產生)、以及「內層 rollback 後外層還能不能重讀」需要另一條連線。 |
| **C. `@Transactional(propagation = REQUIRES_NEW)` 做重讀** | 外層交易已中止,內層取新連線重讀 | **不可靠** | Spring 文件:REQUIRES_NEW「**acquires its own resources such as a new database connection**」,所以重讀本身能成功。但**外層交易仍在 aborted state**,其後的 commit 對 PostgreSQL 而言等於 ROLLBACK,Spring 卻認為 commit 成功了。語意錯誤且脆弱。文件另警告連線池耗盡與死鎖風險。**不建議。** |
| **D. `@Transactional(propagation = NESTED)`(JDBC savepoint)** | Spring 文件:NESTED「uses a **single physical transaction with multiple savepoints**... typically mapped onto JDBC savepoints... works only with JDBC resource transactions... supported by `DataSourceTransactionManager`」。PostgreSQL 文件亦證實 `ROLLBACK TO` 可從 aborted state 復原 | **可行但複雜** | 技術上成立(本專案就是 `DataSourceTransactionManager`),但 savepoint 必須包住 **holdings + insert 整段**才能滿足 I1,否則 rollback to savepoint 不會撤銷 holdings。比 A 複雜且更難讀。 |
| **E. `pg_advisory_xact_lock(hashtext(userId || key))` 前置** | 交易一開始就對 (user, key) 取 advisory lock,把併發同 key 序列化 → 前置查詢變成權威,I2 的路徑永不觸發 | **是** | **也是好答案**,單一交易、零特殊機制。代價:多一次 round trip;`hashtext` 是 64-bit 空間內的雜湊,碰撞只造成無害的多餘序列化。缺點是它把「正確性」建立在一個不可見的鎖上,讀者不容易看出來,且不保護「繞過應用層的直接 SQL 寫入」。**可作為 A 的補強或備案。** |

### Q1.8 方案 A 的殘餘風險(必須誠實面對)

方案 A 的正確性依賴一個假設:**`ON CONFLICT DO NOTHING` 回傳零列之後,那筆衝突的列在同一個 READ COMMITTED 交易的下一道 `SELECT` 中可見。**

- 若併發的另一個交易**已 commit**:READ COMMITTED 下每道述句取新快照,所以可見。✅
- 若併發的另一個交易**尚未 commit**:PostgreSQL 需要「等對方結束」才能決定要不要 DO NOTHING。實作上 `INSERT ... ON CONFLICT` 使用 speculative insertion 並在偵測到未提交的衝突列時等待對方的 xid;等到之後若對方 commit → DO NOTHING(零列,且該列已可見),若對方 abort → 正常插入。

**但 PostgreSQL 官方文件並未明文保證 `DO NOTHING` 的等待行為。** 我實際查了 `sql-insert.html`,只找到針對 `DO UPDATE` 的保證:

> "`ON CONFLICT DO UPDATE` guarantees an atomic `INSERT` or `UPDATE` outcome; provided there is no independent error, one of those two outcomes is guaranteed, even under high concurrency."

`DO NOTHING` 沒有等價的句子。所以「零列 ⇒ 該列已可見」在本 session 是 `[ASSUMED]`,不是 `[VERIFIED]`。

**兩條必須寫進 plan 的緩解措施:**

1. **IT 必須實測。** 沿用 `TradingApiIT.concurrentFirstBuysMergeWithoutUniqueViolation`(`:150-191`)的 `CountDownLatch` + `ExecutorService` 樣板,改成 8 條 thread 用**同一個 `Idempotency-Key`** 同時 POST,斷言:(i) 全部 200;(ii) `transactions` 只有 1 列;(iii) holdings 只套用一次;(iv) 8 個回應的 `data.id` 全部相同。這條 IT 就是「零列 ⇒ 可見」的直接證明,也同時是 TRAD-03 的驗收。
2. **重讀落空時回 `TRADE_CONFLICT`,不是 500。** 若真的出現理論殘餘競態(零列但重讀不到),丟 `BusinessException(ErrorCode.TRADE_CONFLICT, ...)` → 409。這個 code 已存在(`ErrorCode.java:44`,"Holding changed during trade execution"),語意上「併發衝突,請重試」是合理的,而且既有 `updateHolding`(`JdbcTradingRepository.java:126`)與 `createTrade`(`TradingService.java:80`)都已用同一個 code 處理同類情境。**這一條讓「文件沒保證」從 500 風險降級為可重試的 409。**

若 Yuan / planner 不接受這個殘餘風險,**方案 E(advisory lock)是零殘餘風險的替代品**,成本只有一次 round trip。見 DP-2。

### Q1.9 V8 append-only trigger 會不會擋到?

**不會。** 逐字查證 `V8__transactions_append_only_trigger.sql`:

```sql
CREATE TRIGGER trg_transactions_no_update
    BEFORE UPDATE ON transactions FOR EACH ROW EXECUTE FUNCTION prevent_transactions_mutation();   -- :16-18
CREATE TRIGGER trg_transactions_no_delete
    BEFORE DELETE ON transactions FOR EACH ROW EXECUTE FUNCTION prevent_transactions_mutation();   -- :20-22
CREATE TRIGGER trg_transactions_no_truncate
    BEFORE TRUNCATE ON transactions FOR EACH STATEMENT EXECUTE FUNCTION prevent_transactions_mutation();  -- :24-26
```

| 動作 | 會不會觸發 trigger? |
|------|-------------------|
| `INSERT` | **不會** — 沒有 `BEFORE INSERT` trigger。`TransactionsAppendOnlyIT.insertOnTransactionsStillSucceeds`(:87-107)已有綠燈證明。 |
| `INSERT ... ON CONFLICT DO NOTHING` | **不會** — DO NOTHING 不執行 UPDATE。 |
| `INSERT ... ON CONFLICT DO **UPDATE**` | **會炸** ⚠️ — 這會觸發 `trg_transactions_no_update`。**planner 絕對不可以用 `DO UPDATE` 版本的 upsert。** 這是一個很容易誤用的陷阱:一般 upsert 教學都示範 `DO UPDATE`。 |
| `ALTER TABLE transactions ADD COLUMN` | **不會** — trigger 是 row/statement 層 DML trigger,不是 event trigger。CONTEXT.md D-08 的判斷正確。 |
| `CREATE UNIQUE INDEX ... ON transactions` | **不會** — 同上。 |

---

## Q2 — Flyway V10 migration 形狀

### V10 是不是下一個空號?

**是。** 證據:

| 檢查 | 結果 |
|------|------|
| `ls stock-db-migration/src/main/resources/db/migration/`(HEAD) | `V1__foundation_schema.sql` … `V9__trading_query_indexes.sql`,**最高為 V9,無 V10** |
| PR #15 的改檔清單是否含 V10? | **否** — 只改 `V9__trading_query_indexes.sql`(見 Q0 清單) |
| 其他 branch 是否有 V10? | `git log --all --diff-filter=A -- 'stock-db-migration/.../V1*'` 只回一個 commit(`3019f26 feat: add foundation database migrations`,即 V1),**沒有任何 branch 新增過 V10~V19** |
| 是否有其他 open PR 佔用 V10? | 只有 PR #15 是 open,且它不含 V10 |

**檔名:`V10__transactions_idempotency_key.sql`**
- 符合 `V{N}__{description}.sql` 格式(`flyway-convention.md:39-45`)
- description 用英文 snake_case ✅
- 放在 `stock-db-migration/src/main/resources/db/migration/` ✅(`flyway-convention.md:5-7` 硬規則:其他模組不得放 migration)

### DDL 內容與 `VARCHAR(n)` 長度建議

`transactions` 表現況(`V7__trading_schema.sql:1-19`,實讀):

```sql
CREATE TABLE transactions (
    id BIGSERIAL PRIMARY KEY,
    uuid UUID NOT NULL DEFAULT uuid_generate_v4(),
    user_id BIGINT NOT NULL REFERENCES users(id),
    asset_id BIGINT NOT NULL REFERENCES assets(id),
    type VARCHAR(10) NOT NULL,
    quantity NUMERIC(24, 8) NOT NULL,
    price NUMERIC(24, 8) NOT NULL,
    fee NUMERIC(24, 8) NOT NULL DEFAULT 0,
    note VARCHAR(500),
    executed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_transactions_uuid UNIQUE (uuid),
    CONSTRAINT ck_transactions_type CHECK (type IN ('BUY', 'SELL')),
    CONSTRAINT ck_transactions_quantity_positive CHECK (quantity > 0),
    CONSTRAINT ck_transactions_price_positive CHECK (price > 0),
    CONSTRAINT ck_transactions_fee_non_negative CHECK (fee >= 0)
);
```

建議 DDL:

```sql
-- Phase 4 / D-08:交易建立的 server-side 冪等鍵(judgment §5 要求「唯一約束」)。
--
-- 為什麼是欄位而不是獨立表 + 清理 job:清掉之後同一個 key 重送就會建出重複交易，而
-- transactions 是 append-only（V8 trigger），改不回來。key 與交易同壽命是刻意的。
--
-- 為什麼可以對 append-only 表做 ALTER：V8 的三個 trigger 都是 row/statement 層 DML
-- trigger（BEFORE UPDATE / DELETE / TRUNCATE），DDL 不經過它們。
--
-- 為什麼 NULL 可以存在：V10 之前的既有交易沒有 key，且欄位無法回填（append-only）。
-- 因此用 partial unique index，只約束「有 key」的列。
ALTER TABLE transactions ADD COLUMN idempotency_key VARCHAR(128);

-- WHERE 子句是 partial unique index 的必要組成，不是最佳化：
--   1. 它讓所有既有 NULL 列不進入索引，否則多列 NULL 也不會互相衝突但索引白白變大；
--   2. 更關鍵的是，應用層的 INSERT ... ON CONFLICT 必須以同一個 predicate 才能推斷出
--      這個索引（PostgreSQL: "index_predicate ... Used to allow inference of partial
--      unique indexes"）。索引與 SQL 的 WHERE 必須逐字對應。
CREATE UNIQUE INDEX uk_transactions_user_idempotency
    ON transactions (user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
```

**長度建議:`VARCHAR(128)`。** 理由:
- 瀏覽器 client 用 `crypto.randomUUID()` → 36 字元(D 的 Discretion 已鎖)。
- 但 D-05 明文允許「非瀏覽器 bearer client 自產 UUID」,而**自產**意味著格式不受我們控制:常見的 idempotency key 慣例包含 `<prefix>-<uuid>`、base64 的 32-byte 隨機值(44 字元)、或 ULID(26 字元)。128 給了充裕餘裕而成本近乎零(PostgreSQL 的 `varchar(n)` 是 varlena,實際佔用依內容長度,`n` 只是上限檢查)。
- **不要用 `VARCHAR(36)`**:那會讓任何非 UUID 格式的合法 client 在 DB 層炸 `value too long for type character varying(36)`——而那是一個 `DataIntegrityViolationException`,會被冪等的 catch 邏輯誤判或變成 500。
- **不要用無界 `TEXT`**:允許使用者送任意長字串進帳本(且該欄位進唯一索引,PostgreSQL B-tree 單筆 entry 上限約 2704 bytes),是 DoS 面。上限 + 應用層驗證(見 Q3.3)是正確組合。

`[ASSUMED]` — 128 這個具體數字是我的判斷,不是專案既有慣例。既有慣例可參考:`type VARCHAR(10)`、`note VARCHAR(500)`(V7),`users.email/username` 長度未在本研究查證。planner 可自行調整,但**必須 > 36 且有上限**。

### Flyway 設定是否需要額外處理?

| 檢查項 | 結論 | 證據 |
|--------|------|------|
| 是否需要 `-- non-transactional` 標記? | **不需要。** `ALTER TABLE ADD COLUMN` 與(非 CONCURRENTLY 的)`CREATE UNIQUE INDEX` 都是 transactional DDL | `flyway-convention.md:152` 只針對 TimescaleDB DDL;V7/V8 也都沒有標記 |
| 是否需要 `CREATE INDEX CONCURRENTLY`? | **絕對不要。** PR #15 在 V9 留下了非常明確的實測紀錄:「在 Flyway 底下這會直接死鎖 …… 實測(TradingApiIT,Postgres 16 + Flyway 11):V9 已被正確判定為 `[non-transactional]` 並在交易外執行,但仍卡死逾 1.5 小時」 | PR #15 diff of `V9__trading_query_indexes.sql`(逐字引用) |
| 是否有 `schema.sql` 需要同步維護? | **沒有。** `flyway-convention.md:81` 明文「Per-module test schemas are ELIMINATED」;`grep` 確認 `stock-start/src/test/resources/` 只有 `application-test.yaml` 與 `application-e2e.yaml` | `find stock-start/src/test -type f` |
| Flyway checksum / validate 設定 | 測試環境每次全新 DB,無 checksum 問題。`ContainerIT.registerContainerProperties`(:51-63)設 `spring.flyway.enabled=true`、`locations=classpath:db/migration`、`mixed=true` | `ContainerIT.java:58-60` |
| Testcontainers 映像 | `timescale/timescaledb:2.17.2-pg16`(即 PostgreSQL 16) | `ContainerIT.java:15-21` |

### 既有 migration 怎麼被測到的?

**有兩個現成樣板:**

1. **`FoundationMigrationIT`**(`stock-start/src/test/java/.../FoundationMigrationIT.java`)—— migration 套用後的 schema 斷言。**這是 V10 的測試落點。**
2. **`TransactionsAppendOnlyIT`**(全檔實讀,109 行)—— 用 `@Autowired JdbcClient` 直接下 SQL 驗證 DB 層強制。它的 `seedTransaction`(:42-67)示範了「插 user → 查 AAPL asset id → 插 transaction」的最小 fixture,**Phase 4 的「V10 索引存在」與「同 key 第二次 insert 被擋」的低層測試可直接照抄這個樣板**。

V10 的 migration 測試建議斷言(不是「schema 長什麼樣」而是「約束真的生效」):

```sql
-- 1) 欄位存在且可為 NULL
select column_name, is_nullable, character_maximum_length
from information_schema.columns
where table_name = 'transactions' and column_name = 'idempotency_key';

-- 2) partial unique index 存在，且 predicate 正確（indexdef 含 WHERE）
select indexdef from pg_indexes
where tablename = 'transactions' and indexname = 'uk_transactions_user_idempotency';

-- 3) 行為驗證（比 schema 斷言強）：同一 user 同一 key 插第二次 → DataIntegrityViolationException
-- 4) 行為驗證：兩列 idempotency_key = NULL 可共存（不被 partial index 擋）
-- 5) 行為驗證：不同 user 同一 key 可共存
```

第 3~5 條是**真正的驗收**(schema 斷言容易寫成套套邏輯)。第 4 條特別重要:如果誤寫成非 partial 的 unique index,PostgreSQL 對多個 NULL 本來也不會衝突(NULL 不等於 NULL),所以第 4 條**不會**抓到那個錯;能抓到的是第 2 條的 `indexdef` 含 `WHERE`,以及應用層 `ON CONFLICT ... where ...` 能否成功推斷(那會在 IT 直接炸)。

---

## Q3 — `Idempotency-Key` 作為 Spring MVC 必填 header

### Q3.1 Spring 對缺少 `@RequestHeader(required = true)` 的預設行為

**拋 `org.springframework.web.bind.MissingRequestHeaderException`。** 繼承鏈(`javap -cp spring-web-7.0.8.jar`,2026-07-26 實跑):

```
MissingRequestHeaderException
  extends MissingRequestValueException
    extends ServletRequestBindingException
      extends jakarta.servlet.ServletException
      implements org.springframework.web.ErrorResponse     ← 關鍵
        // getStatusCode() : HttpStatusCode → 400
        // getBody()       : ProblemDetail
```

### Q3.2 `GlobalExceptionHandler` 已經正確處理了嗎?—— **是,不需要新 handler**

追蹤 develop 上的 `GlobalExceptionHandler.java`(全檔 98 行實讀):

1. 沒有 `@ExceptionHandler(MissingRequestHeaderException.class)`,也沒有 `@ExceptionHandler(ServletRequestBindingException.class)`。
2. 落到 catch-all `handleUnexpected(Exception)`(`:66-75`)。
3. **第一行就是** `if (exception instanceof ErrorResponse errorResponse) { return handleErrorResponse(errorResponse); }`(`:68-70`)—— `MissingRequestHeaderException` 命中此分支,**不會**印 ERROR log(`log.error` 在 `:71`,在 return 之後)。
4. `handleErrorResponse`(`:77-81`)→ `codeForStatus(400)`(`:87-89`)→ `ErrorCode.VALIDATION_FAILED`。
5. 回應:`ResponseEntity.status(400).body(ApiResponse.failure(ApiError.of(VALIDATION_FAILED, "Validation failed"), meta))`。
6. `ApiError` 的 compact constructor(`ApiError.java:9-11`)把 null fields 轉成 `Map.of()`,所以 JSON 會有 `"fields": {}`(**不是** null)。

**結論:D-05 的「缺 header → 400」開箱即可,envelope 已正確,且不污染測試輸出。**

實際 envelope:

```json
{
  "success": false,
  "data": null,
  "error": { "code": "VALIDATION_FAILED", "message": "Validation failed", "fields": {} },
  "meta": { "traceId": "..." }
}
```

### Q3.3 但**建議**加一個 handler —— 理由與 PR #15 完全同構

上面的 envelope 有一個實務缺點:`message` 是通用的 `"Validation failed"`,`fields` 是空的,**前端無法區分「缺 `Idempotency-Key` header」與「body 某欄位驗證失敗」**。而 D-16 要求「欄位級錯誤綁輸入框,其餘顯示在底部」——缺 header 這個錯誤既不是欄位錯誤(不該綁輸入框),也需要能被前端辨識為「程式 bug 而非使用者輸入問題」。

PR #15 為了完全相同的理由加了 `handleTypeMismatch`,填 `fields: {paramName: "invalid format"}`。**建議 Phase 4 比照:**

```java
/**
 * 必填 HTTP header 缺漏。
 *
 * <p>{@link MissingRequestHeaderException} 已實作 {@link ErrorResponse}，落到 catch-all
 * 也會得到 400 VALIDATION_FAILED，但 fields 為空、message 為通用字串，客戶端無法區分
 * 「缺 header」與「body 欄位驗證失敗」。POST /trades 的 Idempotency-Key（D-05）缺漏代表
 * 客戶端實作漏了一條路徑，是需要被明確指認的錯誤。</p>
 *
 * <p>只回報 header 名稱、不回射任何使用者傳入的值（code-standards 錯誤訊息安全規則）。</p>
 */
@ExceptionHandler(MissingRequestHeaderException.class)
public ResponseEntity<ApiResponse<Void>> handleMissingHeader(MissingRequestHeaderException exception) {
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put(exception.getHeaderName(), "required header is missing");
    ApiError error = ApiError.of(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), fields);
    return ResponseEntity.badRequest().body(ApiResponse.failure(error, ApiMetaFactory.current()));
}
```

`getHeaderName()` 存在(`javap` 顯示 `public final java.lang.String getHeaderName()`),回傳的是**我們自己宣告的** header 名稱 `"Idempotency-Key"`,不是使用者輸入,所以不違反「不回射使用者可控字串」。

**⚠️ 若採此建議,與 PR #15 在 `GlobalExceptionHandler` 的同一個 hunk 位置競爭(見 Q0 衝突表)。** 緩解:把新 handler 插在 `handleValidation`(:56-64)**之前**,而 PR #15 的 `handleTypeMismatch` 插在它**之後**,兩者就不同 hunk。這是一個 planner 可以直接照做的具體措施。

### Q3.4 key 是否該做格式驗證?用哪個 error code?

**建議做,但只做長度與字元集,不做 UUID 格式驗證。**

| 驗證 | 建議 | 理由 |
|------|------|------|
| 非空白 | **做** | `@RequestHeader(required = true)` 只擋「header 不存在」,**不擋 `Idempotency-Key: `(空值)**。空字串會通過綁定,然後被存進 DB 並進索引 —— 所有送空 key 的 request 會互相衝突,行為極難理解。用 `StringUtils.isBlank`(專案慣例,`code-standards` 要求)在 service 層擋,丟 `VALIDATION_FAILED`。 |
| 長度上限 | **做**(建議 ≤ 128,與 DDL 一致) | 不擋就是把 DB 的 `value too long` 例外(`DataIntegrityViolationException`)當成流程的一部分 —— 而冪等邏輯正在 catch 同一個例外家族,會誤判。應用層先擋。 |
| 字元集 | **建議** ASCII 可列印字元(或更嚴:`[A-Za-z0-9._:-]`) | 帳本欄位,不需要接受任意 Unicode。但**注意**:若拒絕,錯誤訊息**絕不可回射該 key**(`code-standards.md:82`:「never include internal IDs, SQL fragments...」;而且 key 是使用者可控字串)。**這裡有一個反例可循**:`BackfillController:105-106` 把 idempotency key 直接串進錯誤訊息 —— **不要照抄**。 |
| UUID 格式 | **不做** | D-05 明文允許非瀏覽器 client 自產 key;強制 UUID 會排除合法 client。 |

**error code:一律用既有的 `ErrorCode.VALIDATION_FAILED`(400)。** 不需要新 code —— 這是輸入格式問題,和 body 欄位驗證同類。

### Q3.5 既有 409 codes 盤點 —— 新 code 該叫什麼、放哪裡

`ErrorCode.java` 全檔實讀,**所有** 409:

| Code | 行 | httpStatus | defaultMessage | 語意 |
|------|-----|-----------|----------------|------|
| `DUPLICATE_RESOURCE` | **:14** | 409 | "Duplicate resource" | 通用「資源已存在」(user email/username 註冊撞號用,`JdbcUserRepository:116,119`) |
| `BACKFILL_ALREADY_RUNNING` | :21 | 409 | "Backfill job already running for this key" | Backfill 的 idempotency **拒絕**語意(D-06 明文的反例) |
| `BACKTEST_RESULT_NOT_READY` | :37 | 409 | "Backtest result is not ready" | 狀態未就緒 |
| `TRADE_INSUFFICIENT_HOLDING` | **:43** | 409 | "Insufficient holding quantity" | oversell(Q9 的權威來源) |
| `TRADE_CONFLICT` | **:44** | 409 | "Holding changed during trade execution" | 樂觀鎖 / 併發衝突(Q1.8 的退路) |

> CONTEXT.md 說「`ErrorCode.java:40-44` 現有 TRADE_* codes」—— **驗證通過**:`:40 TRADE_UNSUPPORTED_TYPE`、`:41 TRADE_INVALID_QUANTITY`、`:42 TRADE_INVALID_PRICE`、`:43 TRADE_INSUFFICIENT_HOLDING`、`:44 TRADE_CONFLICT`。✅ 無漂移。

**建議:採 D-07 的建議名 `TRADE_IDEMPOTENCY_KEY_REUSED`,插在 `:44` 之後(即 `TRADE_CONFLICT` 之下、`INTERNAL_ERROR` 之上),放在 `// Trading module error codes` 區塊內。**

```java
    // Trading module error codes
    TRADE_UNSUPPORTED_TYPE(400, "Unsupported trade type"),
    TRADE_INVALID_QUANTITY(400, "Trade quantity must be greater than 0"),
    TRADE_INVALID_PRICE(400, "Trade price must be greater than 0"),
    TRADE_INSUFFICIENT_HOLDING(409, "Insufficient holding quantity"),
    TRADE_CONFLICT(409, "Holding changed during trade execution"),
    TRADE_IDEMPOTENCY_KEY_REUSED(409, "Idempotency key was already used with a different trade payload"),
```

**與其他 409 的語意區分(這是 D-07 的明文要求):**

| 相對於 | 差異 |
|--------|------|
| `DUPLICATE_RESOURCE` | 那是「你要建的東西已經存在」;這是「你的 key 已經存在,**但綁的是另一筆內容**」。前者重送同一份 payload 也永遠 409;後者重送**同一份** payload 會 200 回既有交易。**行為完全相反**,不能共用 code。 |
| `BACKFILL_ALREADY_RUNNING` | 那是「同 key 一律拒絕」(D-06 明文的反例語意);這是「同 key + 同 payload 回既有資源,只有 payload 不同才拒絕」。 |
| `TRADE_CONFLICT` | 那是「重試就可能成功」的暫時性併發衝突;這是「重試永遠不會成功,除非換 key 或改回原 payload」的確定性錯誤。前端的處置也不同(前者可自動重試,後者必須提示使用者)。 |

`defaultMessage` 刻意寫得長一點,因為前端 i18n 只會用 `code`,而這個 message 主要是給後端 log 與 API 探索者看的。

---

## Q4 — D-07 payload 比對的精度陷阱

### 要比的 6 個欄位在兩端的實際型別

| 欄位 | request 側(Jackson 反序列化 `CreateTradeRequest`) | DB 讀回側(`mapTransaction`) |
|------|--------------------------------------------------|---------------------------|
| `asset_id` | 不在 request 裡 —— 需先 `resolveAsset(request.symbol()).id()` 才能比 | `rs.getLong("asset_id")`(`JdbcTradingRepository.java:249`)→ `Long` |
| `type` | `String`(`CreateTradeRequest.java:20`)→ `TradeType.fromApiValue(...)` | `TradeType.valueOf(rs.getString("type"))`(`:252`)→ enum |
| `quantity` | `BigDecimal`(`:22`),scale 取決於 JSON 字面 | `rs.getBigDecimal("quantity")`(`:253`),PostgreSQL `NUMERIC(24,8)` → **scale 固定 8** |
| `price` | `BigDecimal`(`:24`) | 同上,scale 8 |
| `fee` | `BigDecimal`(`:25`),**可能為 null** → `TradingService:67` 補 `BigDecimal.ZERO`(scale **0**) | 同上,scale 8(V7 有 `DEFAULT 0`) |
| `executed_at` | `OffsetDateTime`(`:27`) | `rs.getObject("executed_at", OffsetDateTime.class)`(`:257`),PostgreSQL `TIMESTAMPTZ` |

### 陷阱 1:`BigDecimal.equals` 會因 scale 差異而假性不符 —— **必須用 `compareTo`**

`BigDecimal.equals` 比較 unscaled value **與 scale**。所以:

| request JSON | 反序列化後 | DB 讀回 | `equals`? | `compareTo == 0`? |
|--------------|-----------|---------|-----------|-------------------|
| `"quantity": 10` | `10`(scale 0) | `10.00000000`(scale 8) | **false** ❌ | **true** ✅ |
| `"quantity": 10.5` | `10.5`(scale 1) | `10.50000000`(scale 8) | **false** ❌ | **true** ✅ |
| `"fee"` 省略 | `BigDecimal.ZERO`(scale 0) | `0.00000000`(scale 8) | **false** ❌ | **true** ✅ |

**這不是理論風險:`TradingApiIT` 現有的 request body 全都是整數字面**(例如 `{"symbol":"AAPL","type":"BUY","quantity":10,"price":100,"fee":5}`,`TradingApiIT.java:413-416` 的 `buyBody()`),所以**每一次**重試都會被 `equals` 判成 payload 不同 → 誤回 409。**用 `equals` 的實作在第一個 IT 就會紅**,這算是一個好消息(TDD 會抓到),但 planner 必須把 `compareTo` 寫進 plan 的實作規範,不要靠試錯。

**建議實作(明確、可單元測試、null-safe):**

```java
/**
 * 比較兩個金額/數量是否等值。
 *
 * <p>刻意用 {@link BigDecimal#compareTo} 而非 {@code equals}：{@code equals} 連 scale 一起比，
 * 而 request 的 JSON 字面（{@code 10}，scale 0）與 PostgreSQL NUMERIC(24,8) 讀回值
 * （{@code 10.00000000}，scale 8）scale 必然不同，用 equals 會讓每一次合法重試都被誤判為
 * 「同 key 不同 payload」。</p>
 */
private static boolean sameAmount(BigDecimal left, BigDecimal right) {
    if (left == null || right == null) {
        return left == right;
    }
    return left.compareTo(right) == 0;
}
```

### 陷阱 2:`OffsetDateTime.equals` 會因 offset 差異而假性不符 —— **必須用 `isEqual` 或 `toInstant()`**

`OffsetDateTime.equals` 要求 local date-time **與 offset 都相同**;`isEqual` 只比 instant。這裡有**兩個獨立的 offset 變異來源**:

**來源 A — Jackson 的 context timezone。** `stock-start/src/main/resources/application.yaml` 第 6-7 行:

```yaml
  jackson:
    time-zone: Asia/Taipei
```

Jackson 的 `DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE` 預設為 **true**,會把帶偏移量的輸入調整到 context timezone。所以送 `"executedAt": "2026-01-10T00:00:00Z"`,反序列化出的 `OffsetDateTime` 的 offset 很可能是 **`+08:00`**(local time `2026-01-10T08:00`),而不是 `Z`。`[ASSUMED]` — 我沒有在本 session 實際跑一次反序列化來確認 `spring.jackson.time-zone` 對 `OffsetDateTime` 的具體作用;但 `spring.jackson.time-zone` 確實會設到 `ObjectMapper` 的 TimeZone 上,而 `ADJUST_DATES_TO_CONTEXT_TIME_ZONE` 預設開啟,兩者組合的效果就是上述。

**來源 B — PgJDBC 讀回 `timestamptz` 的 offset。** `rs.getObject("executed_at", OffsetDateTime.class)` 回傳的 offset 由驅動決定(通常是 UTC),**與 request 側的 offset 沒有任何理由一致**。

兩個來源任一成立,`equals` 就必然 false。**同一個 instant 的兩個 `OffsetDateTime`,offset 不同時 `equals` 回 false 是 `java.time` 的明文行為,不是 bug。**

**建議實作:**

```java
/**
 * 比較兩個成交時間是否指向同一時刻。
 *
 * <p>刻意不用 {@code equals}：OffsetDateTime.equals 連偏移量一起比，而
 * (a) Jackson 依 spring.jackson.time-zone=Asia/Taipei 會把請求的偏移量調成 +08:00，
 * (b) JDBC 讀回 timestamptz 的偏移量由驅動決定。兩者不可能一致，用 equals 會讓每一次
 * 合法重試都被誤判為「同 key 不同 payload」。</p>
 *
 * <p>另外把兩側都截到微秒：PostgreSQL timestamptz 的精度是微秒，Java OffsetDateTime 是
 * 奈秒。若客戶端送了奈秒精度，寫入時被壓成微秒，讀回來就不會等於原值。瀏覽器的
 * Date.toISOString() 只有毫秒精度，實務上不會踩到，但非瀏覽器 client 可能會。</p>
 */
private static boolean sameInstant(OffsetDateTime left, OffsetDateTime right) {
    if (left == null || right == null) {
        return left == right;
    }
    return left.toInstant().truncatedTo(ChronoUnit.MICROS)
        .equals(right.toInstant().truncatedTo(ChronoUnit.MICROS));
}
```

**誠實的殘餘限制**:PostgreSQL 對超出微秒精度的輸入是**四捨五入**而非截斷。因此若 client 送 `.1234565`(奈秒級),PostgreSQL 存成 `.123457`,而 `truncatedTo(MICROS)` 把 request 側算成 `.123456` → 仍不相等。這個窗口只有奈秒精度的 client 會踩到,且後果是「回 409 要求換 key」而非資料錯誤 —— 可接受。若要完全消除,唯一乾淨的做法是後端在寫入前就把 `executedAt` 正規化到微秒(`truncatedTo(MICROS)`)再存,那樣讀回值與比對基準就逐位元一致。**這是一個更好的做法,建議 planner 採用**(成本一行,寫在 `insertTransaction` 之前)。

### 陷阱 3:`asset_id` 要比,但 request 只有 `symbol`

D-07 明文說比 `asset_id`。實作上:先 `resolveAsset(request.symbol()).id()` 拿到 `Long`,再與既有列的 `assetId` 比。

**這帶來一個順序後果**:若冪等查詢排在 `resolveTradeableAsset` 之前(Q0 建議的位置),則 D-07 比對時**還沒**解析 asset。兩個選擇:

- **(i) 在冪等命中的分支內才解析 asset**(只為了比對)。多一次 DB 查詢,但只在「key 已存在」時付。
- **(ii) 直接比 `symbol`** —— 既有列有 `symbol`(`TradeTransaction.symbol`,由 `join assets a` 帶出,`JdbcTradingRepository.java:251`)。`symbol` 需先做 `normalizeSymbol`(trim + uppercase,`TradingService:251-256`)才能比,避免 `"aapl"` vs `"AAPL"` 假性不符。

**建議 (ii)**:成本更低,且 `symbol → asset_id` 是一對一(`assets.symbol` 有唯一約束 `[ASSUMED]` — 未在本研究查證 V1 的 assets DDL),語意等價。但**必須先 normalize**,否則會誤判。

### 陷阱 4:`note` 要不要比?

**建議:不比。**

D-07 的 rationale 逐字是:「若一律回既有交易,使用者改了數量再送、拿到舊交易卻看到『成功』,**最終持倉與他的認知不符**」。這個判準很精確 —— 保護目標是**持倉正確性**。

| 欄位 | 影響持倉? | 該比? |
|------|----------|-------|
| `asset_id` / `symbol` | 是(改標的 → 改到別的持倉) | ✅ |
| `type` | 是(BUY/SELL 方向相反) | ✅ |
| `quantity` | 是 | ✅ |
| `price` | 是(進 `avg_cost` / `realized_pnl`) | ✅ |
| `fee` | 是(D-02 明文:進 `avg_cost` 與 `realized_pnl`) | ✅ |
| `executed_at` | 不影響持倉數值,但影響帳本時序與 Trades 頁篩選,且 D-03 的副作用讓它成為 payload 穩定性的一部分 | ✅(D-07 明列) |
| `note` | **否** —— 純備註,不進任何計算 | ❌ |

**而且 `note` 不比有一個實質好處**:它是唯一一個「使用者可能在 timeout 重試前手滑改了一個字」而不影響任何帳務意義的欄位。若比 `note`,那種情況會吃 409 `KEY_REUSED`,使用者完全無法理解為什麼「改個備註就不能重試」。

D-07 列的 6 個欄位(`asset_id / type / quantity / price / fee / executed_at`)**本身就已經排除了 `note`** —— 這不是我的新增判斷,是 D-07 的字面。此處只是把理由寫清楚,讓 planner 不會「順手也比 note」。

### 完整比對函式的建議形狀

```java
/**
 * 判斷既有交易是否與本次請求的 payload 相符（D-07）。
 *
 * <p>比對範圍刻意只含影響持倉與帳本語意的欄位（symbol / type / quantity / price / fee /
 * executedAt），<strong>不含 note</strong>：note 不進任何計算，把它納入比對只會讓
 * 「timeout 重試前順手改了備註」的使用者吃到無法理解的 409。</p>
 */
private boolean matchesExistingTrade(TradeTransaction existing, CreateTradeRequest request,
                                     TradeType type, BigDecimal fee, OffsetDateTime executedAt) {
    return existing.symbol().equals(normalizeSymbol(request.symbol()))
        && existing.type() == type
        && sameAmount(existing.quantity(), request.quantity())
        && sameAmount(existing.price(), request.price())
        && sameAmount(existing.fee(), fee)
        && sameInstant(existing.executedAt(), executedAt);
}
```

`sameAmount` / `sameInstant` / `matchesExistingTrade` 都是**純函式** → 適合 `TradingServiceTest` 的純單元測試,是 Q11 的第一批紅燈測試落點。

---

## Q5 — 前端 `tradingApi.ts` 三件組

### Q5.1 `portfolioApi.ts` 樣板的確切結構

`portfolioApi.ts` 全檔實讀(203 行)。三件組:

| 元素 | 行 | 說明 |
|------|-----|------|
| `interface PortfolioApi` | `:47-54` | 唯一消費介面。`mode: RuntimeDataMode` 必填;Promise 方法;`live?` 選填 |
| `interface PortfolioLiveMockData` | `:33-37` | mock 專屬 reactive 視窗。**三個屬性都是 getter** —— `:29-32` 的註解明文:「絕不在 factory 時捕捉陣列參照」 |
| `createMockPortfolioApi()` | `:140-178` | 回 `{ mode: 'mock', live, ...async methods }`;每個 getter 都重新呼叫 `useMockPortfolioStore()`(`:141-142` 註解說明這是 reactivity 與跨測試隔離的關鍵) |
| `createHttpPortfolioApi(basePath = '/api/v1')` | `:180-198` | 回 `{ mode: 'api', ...apiRequest 呼叫 }`,**無 `live`** |
| `createPortfolioApi(mode, basePath = '/api/v1')` | `:200-202` | `return mode === 'api' ? createHttpPortfolioApi(basePath) : createMockPortfolioApi();` |

### Q5.2 `api.live` 消費契約 —— CONTEXT.md 的說法確認

`portfolioApi.ts:42-46` 逐字:

> ```
>  * 頁面用法(judgment §3:元件永遠不 import mock store):
>  * - mock mode:經 `live` 取得 reactive 資料(Pinia reactivity 完整保留,含 lastFill 高亮)。
>  * - API mode:`live` 為 undefined,改走 Promise 方法拿快照 + 明確 refetch。
>  * - 分支判斷依據是 `api.live` 是否存在,不是 mode 字串。
> ```

三個頁面的實際用法逐一驗證:

| 頁面 | 取得 client | 取得 live | 分支判斷 |
|------|-----------|----------|---------|
| Overview | `Overview.vue:201` `const api = getRuntimeApiClients().portfolio;` | `:202` `const live = api.live;` | `:251-...` `onMounted(() => { if (live) return; ... })` |
| Positions | `Positions.vue:315` | `:316` | `:369-374` `onMounted(() => { if (live) return; void loadSummary(); void loadHoldings(); })` |
| Trades | `Trades.vue:157` | `:158` | `:289-291`(`selectChip`)、`:300`(`toggleSort`)、`onMounted` |

✅ CONTEXT.md 的描述完全正確。`api-adapter-wiring.test.ts:222-235` 也有一個測試明確鎖住「`live` 只在 mock mode 存在」。

### Q5.3 `apiRequest` 能不能帶自訂 header?—— **能,而且已有現成樣板**

**已就緒,不需要任何 transport 層擴充。** 證據鏈:

1. `ApiRequestOptions extends Omit<RequestInit, 'body'>`(`apiClient.ts:32-34`)—— 所以 `headers` 是合法選項(來自 `RequestInit`)。
2. `buildRequestInit`(`:161-176`)第一行 `const { json, headers: headersInit, ...requestOptions } = options;`,然後 `const headers = new Headers(headersInit);`(`:163`)—— 呼叫端傳入的 headers 被完整保留,只在缺 `Accept` / `Content-Type` 時補上(`:164-166`, `:170-172`)。
3. `prepareRequestInit`(`:218-226`)對 unsafe method **再**建一次 `new Headers(init.headers)` 並 `set(CSRF_HEADER_NAME, ...)`(`:221-223`)—— 自訂 header 一併保留,CSRF 自動注入。

**現成樣板:`opsApi.ts:146-154`**(逐字):

```typescript
    triggerJob: request => {
      const headers: Record<string, string> = {};
      if (request.idempotencyKey) headers['Idempotency-Key'] = request.idempotencyKey;
      return apiRequest<OpsJobDto>(`${basePath}/ops/jobs`, {
        method: 'POST',
        headers,
        json: { actionKey: request.actionKey, params: request.params },
      });
    },
```

**這正是 `tradingApi.createTrade` 需要的形狀**,差別只在 D-05 讓 key 必填,所以不需要 `if`。

### Q5.4 D-14 的一個「免費」正面發現:401 replay 自動沿用同一 key

`fetchWithSessionRecovery`(`apiClient.ts:299-326`)的 replay 路徑:

```typescript
  const replayInit = await prepareRequestInit(options);      // :319 — 同一份 options
  const replayResponse = await fetch(path, replayInit);      // :320
```

replay 用的是**呼叫端傳進來的同一份 `options` 物件**,所以 `headers['Idempotency-Key']` 逐字相同。

**含意:「access token 過期 → 自動 refresh → 自動 replay POST /trades」這條路徑天然滿足 D-14「該次嘗試的重試沿用同一 key」,且如果 refresh 之前那次 POST 其實已經在後端成功了(只是回應丟了),replay 會命中冪等並回既有交易 —— 完全正確。** planner 應該把這條路徑寫成一個明確的測試(見 Q10),因為它是「冪等真的有用」最貼近真實的證明。

### Q5.5 建議的 `tradingApi.ts` 介面形狀

```typescript
import { apiRequest } from './apiClient';
import { useMockPortfolioStore } from '../stores/mockPortfolio';
import { useMockNotificationsStore } from '../stores/mockNotifications';
import type { RuntimeDataMode, TradeDto } from './apiTypes';

/** POST /api/v1/trades 的 body，與後端 CreateTradeRequest 逐欄同形（judgment §4）。 */
export interface CreateTradeRequest {
  symbol: string;
  type: 'BUY' | 'SELL';
  quantity: number;
  price: number;
  /** D-02：使用者手動輸入，預設 0。前端不發明費率。 */
  fee: number;
  note: string | null;
  /** D-03：ISO-8601 含 offset。Date.prototype.toISOString() 即符合（毫秒精度 + Z）。 */
  executedAt: string;
}

/** mock mode 專屬：保留 executeOrder 的 lastFill 行為（Q5.6）。 */
export interface TradingLiveMockData {
  readonly lastFill: { sym: string; type: 'BUY' | 'SELL'; qty: number; px: number } | null;
}

export interface TradingApi {
  mode: RuntimeDataMode;
  /**
   * 建立一筆已成交交易。
   * @param idempotencyKey D-05：必填。同一次送出嘗試的重試必須沿用同一個 key（D-14）。
   */
  createTrade(request: CreateTradeRequest, idempotencyKey: string): Promise<TradeDto>;
  /** 僅 mock 實作提供，與 portfolioApi 的 live 契約同構（分支判斷看存在性，不看 mode 字串）。 */
  live?: TradingLiveMockData;
}

export function createMockTradingApi(): TradingApi { /* 委派 store.executeOrder + pushNotification */ }
export function createHttpTradingApi(basePath = '/api/v1'): TradingApi {
  return {
    mode: 'api',
    createTrade: (request, idempotencyKey) => apiRequest<TradeDto>(`${basePath}/trades`, {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      json: request,
    }),
  };
}
export function createTradingApi(mode: RuntimeDataMode, basePath = '/api/v1'): TradingApi {
  return mode === 'api' ? createHttpTradingApi(basePath) : createMockTradingApi();
}
```

**注意 `apiRequest<TradeDto>` 而非 `apiPaginatedRequest`** —— `POST /trades` 回 `ApiResponse<TradeDto>`(`TradingController.java:38`),非分頁。

### Q5.6 mock 實作如何委派 `executeOrder()` 並保住 `lastFill`

`OrderTicket.vue:377-386` 現有呼叫(逐字):

```typescript
  const filled = portfolio.executeOrder({
    sym: selected.value.sym,
    name: selected.value.name,
    side: side.value,
    qty: qty.value,
    px: fillPx.value,
    fee: +estFee.value.toFixed(2),
    sector: selected.value.sector ?? selected.value.cat.toUpperCase(),
    note: tif.value === 'GTC' ? 'GTC' : '',
  });
  if (!filled) { /* 失敗處理 */ }
```

`createMockTradingApi().createTrade` 需要:
1. 呼叫 `useMockPortfolioStore().executeOrder({...})`(**延遲解析**,不在 factory 時捕捉 store —— 照 `portfolioApi.ts:141-142` 的註解)。
2. `executeOrder` 回 falsy(oversell)時,**丟一個與 API mode 同形的錯誤**,讓 OrderTicket 的錯誤處理只有一條路徑。建議 `throw new ApiClientError({ status: 409, code: 'TRADE_INSUFFICIENT_HOLDING', message: '...', requestId: null })` —— `ApiClientError` 是 exported class(`apiClient.ts:3`),mock 用它完全合理,而且讓 D-16 的錯誤呈現在兩個 mode 下行為一致。
3. 推 mock 通知(把 `OrderTicket.vue:393-399` 的 `notifications.pushNotification` 搬進來)。
4. 合成一個 `TradeDto` 回傳(id 用 `mock-${...}`,照 `portfolioApi.ts:98-113` 的 `tradeDtoFrom` 慣例)。
5. `live.lastFill` 用 getter 讀 `useMockPortfolioStore().lastFill`(照 `portfolioApi.ts:150-152`)。

**注意 `sector` 與 `cat`**:mock 的 `Sym` type 有 `cat` 與 `sector?`;API mode 的 `AssetDto` 有 `sector` 但**沒有 `cat`**。`executeOrder` 需要 `sector`,所以 mock 實作要能拿到它 —— 這是為什麼 mock 的 `createTrade` 簽章可能需要比 API 版多知道一點(或在 mock 內從 `data.ts` 反查)。**這是 planner 需要設計的一個小介面問題**:建議讓 `CreateTradeRequest` 保持與後端逐欄同形(不加 `sector`),mock 實作自己從 `data.ts` 用 symbol 反查 sector。這樣介面契約乾淨,mock 的髒活留在 mock 裡。

### Q5.7 `pageApiClients.ts` 註冊

`pageApiClients.ts` 全檔 39 行,改動極小:

```typescript
import { createTradingApi, type TradingApi } from './tradingApi';   // 加在 :5 附近（字母序在 portfolioApi 之後）

interface RuntimeApiClients {
  mode: RuntimeDataMode;
  basePath: string;
  auth: AuthApi;
  aiAccess: AiAccessApi;
  backtest: BacktestApi;
  ops: OpsApi;
  portfolio: PortfolioApi;
  trading: TradingApi;              // 新增（:17 之後）
}

// getRuntimeApiClients 的 clients 物件（:24-32）加一行：
      trading: createTradingApi(mode, basePath),
```

`RuntimeApiClients` **沒有** export(`:9` 是 `interface` 而非 `export interface`)—— 所以型別只在本檔內用,新增欄位不會產生外部型別破壞。快取邏輯(`:23`)以 `mode` + `basePath` 為 key,無需改動;`resetRuntimeApiClientsForTests()`(`:37-39`)亦無需改動。

### Q5.8 `api-adapter-wiring.test.ts` 的「mock factory 未被呼叫」樣板

`api-adapter-wiring.test.ts` 全檔實讀(272 行)。關鍵測試是 `:166-220` `'API mode creates HTTP clients and never calls mock adapter factories'`。樣板由四塊組成:

**(1) `vi.hoisted` 的 spy 集合(`:16-22`)**
```typescript
const mockFactoryCalls = vi.hoisted(() => ({
  auth: vi.fn(), aiAccess: vi.fn(), backtest: vi.fn(), ops: vi.fn(), portfolio: vi.fn(),
  // ← 加 trading: vi.fn(),
}));
```

**(2) `afterEach` 的 reset + `doUnmock`(`:24-36`)** — 每個 domain 各一行 `mockReset()` 與 `vi.doUnmock('./services/xxxApi')`。**加 trading 兩行。**

**(3) 測試內的 `vi.doMock` 攔截 mock factory(`:198-204` 樣板)**
```typescript
    vi.doMock('./services/tradingApi', async importOriginal => {
      const actual = await importOriginal<typeof import('./services/tradingApi')>();
      return { ...actual, createMockTradingApi: mockFactoryCalls.trading };
    });
```

**(4) 雙重斷言(`:209-219`)**
```typescript
    expect(clients.trading.mode).toBe('api');
    expect(mockFactoryCalls.trading).not.toHaveBeenCalled();
```

另外需比照 `:222-235` 加一個 `live` 存在性測試(mock mode 有 `live`、API mode `live` 為 `undefined`),以及比照 `:237-253` 加一個「API mode 失敗保留 status code 與 traceId」測試。

**⚠️ 這個檔案有一個必須遵守的順序約束**:`vi.resetModules()` 必須在 `vi.doMock` **之前**(`:167`),且 `getRuntimeApiClients` 必須用 **動態 `await import()`**(`:206`)而非頂層 import —— 否則 mock 不生效。`testSetup.ts` 的註解也明文警告同一件事(「此檔刻意『不 import 任何 service module』…… 會搶在各測試檔的 vi.mock 生效前就綁定真實實作」)。

---

## Q6 — `OrderTicket.vue` 重建面

### Q6.1 行號逐條查證(CONTEXT.md 的清單 vs 實檔)

`OrderTicket.vue` 全檔實讀(556 行)。逐條核對:

| CONTEXT.md 的陳述 | 宣稱行號 | 實際 | 判定 |
|-------------------|---------|------|------|
| symbol 清單來自本地 `data.ts` 的 `SYMBOLS/CRYPTO/FX` | `:200`, `:209` | `:200` `import { SYMBOLS, CRYPTO, FX, fmtNum, fmtPct, genSeries } from '../data';`;`:209` `const ALL: Sym[] = [...SYMBOLS, ...CRYPTO, ...FX];` | ✅ **準確** |
| 報價卡走勢圖是 `genSeries()` | `:256-258` | `:256-258` `const quoteSeries = computed(() => selected.value ? genSeries(40, selected.value.price * 0.985, 0.012, selected.value.sym.charCodeAt(0)) : []);` | ✅ **準確** |
| fee 是 `Math.max(1, estTotal * 0.001)` | `:253` | `:253` `const estFee = computed(() => Math.max(1, estTotal.value * 0.001));` | ✅ **準確** |
| `cashAfter` 寫死 `124_580` | `:254` | `:254` `const cashAfter = computed(() => 124_580 - (side.value === 'BUY' ? ... ));` | ✅ **準確** |
| 成交價是 `px * (1 + (Math.random() - 0.5) * 0.002)` | `:373-374` | `:373` `const slip = (Math.random() - 0.5) * 0.002;`;`:374` `fillPx.value = +(px.value * (1 + slip)).toFixed(2);` | ✅ **準確** |
| `orderId` 是亂數 | `:375` | `:375` `orderId.value = String(Math.floor(Math.random() * 90_000_000) + 10_000_000);` | ✅ **準確** |
| 直接 `import { useMockPortfolioStore }` | `:202` | `:202` `import { useMockPortfolioStore } from '../stores/mockPortfolio';`(另 `:201` 是 `useMockNotificationsStore`) | ✅ **準確** |
| 四步 wizard 的 `routing/match` 三階段 | `:242`, `:357-367` | `:242` `const placeSteps = computed(() => [t(props.lang,'placing'), t(props.lang,'routingMatch'), t(props.lang,'filled')]);`;`:357-367` `async function placeOrder()` 的 `await wait(420) / wait(640) / wait(380)` 三段假進度 | ✅ **準確** |
| MKT price-lock | `:80`, `:339-341` | `:80` `:disabled="ordType === 'MKT'"`;`:339-341` `watch(ordType, (v) => { if (v === 'MKT' && selected.value) px.value = +selected.value.price.toFixed(2); });` | ✅ **準確** |
| `placing` ref | `:358` | `placing` **宣告在 `:240`**;`:358` 是 `if (placing.value \|\| step.value === 'placing') return;`(函式內早退 guard) | ⚠️ **輕微漂移** — `:358` 是使用處而非宣告處。無實質影響。 |
| disabled button | `:181` | `:181` `<button class="btn-accent" :disabled="!canSubmit" @click="step = 'review'">{{ t(lang,'review') }} →</button>` | ⚠️ **重要語意漂移** — 見 Q6.2 |

**結論:12 條中 10 條逐字準確,1 條輕微漂移,1 條有重要語意含意。CONTEXT.md 的 code archaeology 品質很高,planner 可以信任其餘陳述。**

### Q6.2 ⚠️ 重要發現:送出鈕目前**沒有** `:disabled`

`:181` 的 `:disabled="!canSubmit"` 在 **Review 按鈕**(step 1 → step 2 的推進鈕),不是送出鈕。

真正的送出鈕在 `:185`:

```html
<button :class="['btn-accent', side.toLowerCase()]" @click="placeOrder">{{ t(lang, 'placeOrder') }}</button>
```

**完全沒有 `:disabled`。** duplicate-submit 目前只靠 `placeOrder()` 內第一行的 `if (placing.value || step.value === 'placing') return;`(`:358`),再加上 `step.value = 'placing'`(`:361`)之後 `v-else-if="step === 'review'"`(`:183`)的 footer 整塊被 `v-else-if="step === 'placing'"` 取代 —— 也就是**按鈕從 DOM 消失**,所以連點在現況下確實擋住了。

**但 D-09 把流程收斂成「送出中 → 已記錄」兩態之後,這個「按鈕消失」的機制可能不再成立**(取決於 planner 怎麼設計兩態的 footer)。TRAD-04 明文要求「Frontend trade submission 提供 duplicate-submit guard」,所以:

**planner 必須在送出鈕上明確加 `:disabled="submitting"`,不能依賴「按鈕會消失」這個副作用。** 並且要有一個元件測試斷言「送出中時送出鈕 disabled」與「連按兩次只呼叫 `createTrade` 一次」。

### Q6.3 逐項對照:每塊合成資料 → 取代它的決策

| 現有實作(file:line) | 取代方案 | 依據 |
|---------------------|---------|------|
| `ALL = [...SYMBOLS,...CRYPTO,...FX]`(`:209`)、`filtered` computed(`:244-248`)、`onSymInput` 的本地 exact match(`:308`) | 改接 `GET /api/v1/assets?query=`,typeahead。選單只列 `tradeable: true` | **D-01** |
| 報價卡的 `selected.price/chg/chgPct/low/high/vol`(`:99-119`) | 改讀 `AssetDto.latestPrice / change / changePercent / low / high / volumeText` | **D-01**(D-16 例外分支) |
| `selected.cat.toUpperCase()` 當 sym-tag(`:29`) | `AssetDto` **沒有 `cat`**。改用 `assetType`(enum → 字串,如 `STOCK`/`CRYPTO`)或 `sector`(`AssetDto.java:13,16`) | D-01 |
| `quoteSeries = genSeries(...)`(`:256-258`) | 改接 `GET /api/v1/market/{symbol}/klines`;見 Q6.5 | **D-01** |
| `estFee = Math.max(1, estTotal * 0.001)`(`:253`) | 刪除公式,改成使用者輸入欄位,預設 **0** | **D-02** |
| `cashAfter`(`:254`)+ Review 畫面的 `cashAfter` 列(`:150`)+ i18n `cashAfter` key | API mode **隱藏**(mock mode 保留) | **D-04**(後端零命中) |
| `ordType` ref(`:232`)+ 選擇器(`:63-67`)+ Review 顯示(`:145`)+ price 鎖(`:80`)+ `watch(ordType)`(`:339-341`) | API mode **隱藏**;price 一律預填 `latestPrice` 但**可編輯** | **D-04** |
| `tif` ref(`:233`)+ 選擇器(`:86-90`)+ `note: tif === 'GTC' ? 'GTC' : ''`(`:385`) | API mode **隱藏**;`note` 改為使用者輸入(對應 `CreateTradeRequest.note`,`@Size(max=500)`) | **D-04** + judgment §1 |
| `placeSteps` 三階段(`:242`)+ `wait(420)/wait(640)/wait(380)`(`:363-367`)+ Placing 畫面(`:155-163`)+ i18n `routingMatch` | 收斂為「送出中」單一 loading 態 | **D-09** + judgment §1 反例 |
| `slip = Math.random()...` / `fillPx`(`:373-374`)+ Filled 畫面的 `avgFillPx`(`:171`) | 刪除。成功畫面顯示**實際送出的 price** 與後端回傳的 `TradeDto`。**不得**出現「平均成交價」 | **D-09** 明文 |
| `orderId = Math.random()...`(`:375`)+ Filled 畫面的 `orderId`(`:172`) | 顯示後端 `TradeDto.id`(UUID,除錯回報用) | **D-09** |
| `import { useMockPortfolioStore }`(`:202`)+ `portfolio.executeOrder(...)`(`:377`)+ `portfolio.positions.find(...)`(`:265`) | 全部改走 `getRuntimeApiClients().trading` / `.portfolio` | **judgment §3** + Discretion |
| `import { useMockNotificationsStore }`(`:201`)+ `notifications.pushNotification(...)`(`:393-399`) | 搬進 `createMockTradingApi()`;API mode 不推 | **Discretion** |
| `sellValidationError` 讀 mock store(`:263-269`) | 改讀 `portfolio.listHoldings()`,顯示「可賣數量」 | **D-15** |
| `step: 'ticket' \| 'review' \| 'placing' \| 'filled'`(`:212`)+ `stepIdx`(`:215`)+ 4 個 step dots(`:8`) | D-09 只說「送出流程」收斂為兩態。**ticket → review 這一步是否保留由 planner 決定**(D-09 未禁止 review 步驟,它禁的是 routing/match 假進度) | D-09;planner 裁量 |
| `preset`(`:206`,由 `App.vue:162-165 openTicket` 傳入,來源是 Overview/Markets/Positions/Chart/Analytics 的 `@order` 事件) | API mode 下必須用 `GET /assets?query=<sym>` 解析,不能用 `ALL.find(...)`(`:329`) | D-01 |
| `qtyStep` 依 `cat === 'crypto'`(`:250`)、`pickSym` 的預設 qty(`:285`) | `AssetDto.assetType` 可替代 `cat`;預設數量是純 UX,planner 裁量 | — |

### Q6.4 `GET /api/v1/assets?query=` 是**分頁的** ⚠️(CONTEXT.md 未提及)

`AssetController.java` 全檔實讀(33 行):

```java
@RestController
@RequestMapping("/api/v1/assets")          // :12-13
public class AssetController {
    @GetMapping                            // :23
    public ApiResponse<PageResponse<AssetDto>> search(     // :24  ← PageResponse！
        @RequestParam(defaultValue = "") String query,      // :25
        @RequestParam(defaultValue = "0") int page,         // :26
        @RequestParam(defaultValue = "20") int size         // :27
    ) {
        int safePage = Math.min(Math.max(0, page), MAX_PAGE);   // :29, MAX_PAGE = 10_000 (:15)
        int safeSize = Math.min(Math.max(1, size), 100);        // :30
        ...
    }
}
```

**含意:**
1. 回應是 `ApiResponse<PageResponse<AssetDto>>` → 前端必須用 **`apiPaginatedRequest<AssetDto>`**(`apiClient.ts:352-372`),**不是** `apiRequest`。若用 `apiRequest`,`isApiSuccess` 會通過但拿到的是 `{items, page, size, ...}` 而不是 `AssetDto[]` → 型別對不上,執行期壞。
2. `query` 預設 `""`,所以空 query 也合法(回第一頁)—— 正好對應 `OrderTicket.vue:246` `if (!q) return ALL.slice(0, 6);` 的行為。
3. `size` 上限 100;typeahead 建議 `size: 10` 或 `20`(現況 `filtered` 是 `.slice(0, 6)`,`:246-247`)。
4. **公開端點確認**:整個檔案**沒有** `@PreAuthorize`。✅ CONTEXT.md 的說法正確。這代表選單即使在未登入狀態也能查(雖然實際上 ticket 只在登入後開得起來)。
5. **`page` / `size` 是 `int` 型別參數** → 送非數字會拋 `MethodArgumentTypeMismatchException` → **在 develop 上是 500**(見 Q0)。前端只送數字,不會踩到,但這是為什麼 Q0 的 (c) 選項需要「adapter 測試鎖住參數格式」。

### `AssetDto` 完整 JSON shape

`AssetDto.java:9-24` 全檔實讀 —— **CONTEXT.md 的 `:9-24` 引用完全準確** ✅:

```typescript
/** 對應後端 stock-module-asset 的 AssetDto。逐欄同形，勿增刪（judgment §4）。 */
export interface AssetDto {
  uuid: string;                 // Java UUID → JSON string
  symbol: string;
  name: string;
  /** Java enum AssetType → JSON string（V2 seed 值見 Phase 04.1 scope：STOCK/CRYPTO/... 具體 enum 常數未於本研究查證） */
  assetType: string;
  market: string;
  /** Java enum CurrencyCode → JSON string（USD / TWD） */
  currency: string;
  sector: string;
  /** D-01：選單只列 tradeable === true，避免送出才吃 ASSET_NOT_FOUND */
  tradeable: boolean;
  /** Java BigDecimal → JSON number（AssetDto 沒有 ToStringSerializer，與 KlineDto 不同！） */
  latestPrice: number;
  change: number;
  changePercent: number;
  volumeText: string;
  high: number;
  low: number;
}
```

`[ASSUMED]` — `AssetType` / `CurrencyCode` 的具體 enum 常數名未在本研究讀取(`stock-common/.../common/model/`)。planner 若需要在前端做 assetType 分類顯示,必須先讀那兩個 enum。**也可能為 null**:`latestPrice/change/changePercent/high/low` 是 `BigDecimal`(不是 primitive),若某個 asset 沒有價格資料就會是 `null` → 前端型別應為 `number | null` 並處理空態。`[ASSUMED]` — 未查證 `AssetQueryService.search` 是否保證非 null。**這是 planner 需要在實作時 grep 確認的一點**(建議讀 `AssetQueryService`)。

**⚠️ 注意 `AssetDto` 的 BigDecimal 是 JSON number,而 `KlineDto` 的是 JSON string** —— 兩個端點不一致,前端必須分別處理。見 Q6.5。

### Q6.5 klines 端點:參數、回應、mapping、狀態

**簽章**(`MarketController.java:96-105`,實讀):

| 參數 | 型別 | 必填 | 說明 |
|------|------|------|------|
| `symbol` | `@PathVariable String` | 是 | javadoc 明文「**大小寫敏感**」 |
| `interval` | `@RequestParam String` | **是** | `1m` / `5m` / `15m` / `1h` / `1d`;非法值 → 400 `KLINE_INTERVAL_INVALID`(`:110-112`) |
| `from` | `@RequestParam Instant` | **是** | ⚠️ 必須是完整 ISO instant(`2026-01-01T00:00:00Z`)。格式錯 → `MethodArgumentTypeMismatchException` → **develop 上是 500** |
| `to` | `@RequestParam Instant`,`required=false` | 否 | javadoc:未指定時預設當前時間 |
| `limit` | `@RequestParam Integer`,`required=false` | 否 | javadoc:預設 500,**最大 5000** |

**回應**:`ApiResponse<List<KlineDto>>` —— **非分頁**,用 `apiRequest<KlineDto[]>`。

**`KlineDto` 的關鍵陷阱**(`KlineDto.java` 全檔實讀):

```java
public record KlineDto(
    @JsonProperty("bucket") Instant bucket,
    @JsonSerialize(using = ToStringSerializer.class) @JsonProperty("open")   BigDecimal open,
    @JsonSerialize(using = ToStringSerializer.class) @JsonProperty("high")   BigDecimal high,
    @JsonSerialize(using = ToStringSerializer.class) @JsonProperty("low")    BigDecimal low,
    @JsonSerialize(using = ToStringSerializer.class) @JsonProperty("close")  BigDecimal close,
    @JsonSerialize(using = ToStringSerializer.class) @JsonProperty("volume") BigDecimal volume
) {}
```

class-level javadoc 明文:「BigDecimal 欄位序列化為 JSON **字串**,避免浮點精度損失」。

**所以 OHLCV 五欄在 JSON 裡都是 string**,前端 TypeScript 型別必須是 `string`,取值必須 `Number(k.close)`。**這與同一 phase 要用的 `AssetDto`(number)相反** —— 這是最容易寫錯的一點,而且錯了不會噴型別錯誤(TS 若宣告成 `number` 但實際收到 string,`vue-tsc` 檢查不到執行期資料),會表現成走勢圖畫成 `NaN` 或整條線壓平。**planner 必須在 adapter 測試裡用 string fixture 鎖住這個轉換。**

**Mapping 到 `LineChart`**:`LineChart.vue:17-18` 的 props 是:

```typescript
const props = withDefaults(defineProps<{
  data: number[];
  ...
```

且 `:32,37-38` 直接對 `props.data` 做 min/max 與線性映射。所以:

```typescript
// klines → LineChart 的 data
const quoteSeries = computed<number[]>(() => klines.value.map(k => Number(k.close)));
```

**建議的 klines 呼叫參數**(取代 `genSeries(40, ...)` 的 40 點走勢):

```typescript
// genSeries 產 40 點；用 1h interval 回看 48 小時可得可比的密度，且 1h 是 continuous
// aggregate view 之一（MarketController javadoc:80「依 interval 參數路由至對應的 view」）。
const from = new Date(Date.now() - 48 * 60 * 60 * 1000).toISOString();
const klines = await trading /* 或 market */ .listKlines(symbol, { interval: '1h', from, limit: 48 });
```

`[ASSUMED]` — `1h` + 48 小時是我的建議,不是既有慣例(Chart 頁尚未 API 化,無前例可抄,CONTEXT.md `<specifics>` 已明言)。planner 可自行調整。

**必須設計的三個狀態(沒有前例可抄)**:

| 狀態 | 觸發 | 建議呈現 |
|------|------|---------|
| loading | 選定 symbol 後、klines 回來前 | 報價卡的數字(來自 `AssetDto`,已經有了)先顯示,**走勢圖區域**顯示骨架。**關鍵**:走勢圖失敗不可拖垮報價卡 —— 兩者來源不同端點 |
| empty | `data: []`(該 symbol 在該區間無 K 線資料) | 走勢圖區域顯示「無走勢資料」。**這是很可能發生的**:market_prices 需要 backfill 過的資料才有值,demo/dev 環境未必有 |
| error | 400 `KLINE_INTERVAL_INVALID` / 404 `ASSET_NOT_FOUND` / 5xx / network | 走勢圖區域顯示錯誤 + 可重試。**不得**因走勢圖錯誤就阻擋送出 —— 走勢圖是輔助資訊,不是交易前提 |

**這三態是 Phase 4 需要新設計的唯一 UI 決策**(其餘都能抄 Phase 3 的 `BlockError` / `describeError` 樣板,見 `Positions.vue:345-349`)。

### Q6.6 typeahead 需要 debounce 嗎?

**需要。** 理由與建議:

- 現況 `onSymInput`(`:304-317`)在**每次 `@input`** 觸發,做本地陣列 filter —— 零成本。改成 HTTP 之後,逐字打 `AAPL` 會發 4 個請求。
- `GET /assets` 沒有 rate limit(`AssetController` 無相關註解),但 `stock.security.rate-limit.enabled` 是全域開關(`ContainerIT.java:39` 在測試中關掉它),production 下的行為未查證 `[ASSUMED]`。
- **更重要的是競態**:4 個請求的回應順序不保證。若不處理,`AAPL` 的結果可能被 `AAP` 的結果覆蓋。
- **建議**:250-300ms debounce **加上** 「只採用最後一次請求的結果」(用一個遞增的 request id 或 `AbortController`)。debounce 單獨不夠 —— 慢網路下兩個 debounce 後的請求仍可能亂序返回。
- `[ASSUMED]` — 前端 repo 目前**沒有** debounce utility(未 grep 到 `debounce`)。planner 需自行實作或用 `setTimeout` + `clearTimeout`;不建議為此引入新套件(見 Package Legitimacy Audit)。
- `AbortController` 搭配 `apiRequest` 是可行的:`ApiRequestOptions extends Omit<RequestInit,'body'>` 包含 `signal`(`apiClient.ts:32`),而 `buildRequestInit` 的 `...requestOptions`(`:168`)會把 `signal` 原樣傳給 `fetch`。**這是一個「已就緒但沒人用過」的能力**,planner 若用它需要注意 abort 會讓 `fetch` reject 成 `DOMException`(不是 `ApiClientError`),錯誤處理要多一條判斷。

---

## Q7 — D-10 shared revision counter

### Q7.1 `v-if` page-unmount 架構確認

`App.vue:35-48` 實讀:

```html
    <main v-if="showMainContent" class="main">
      <Overview   v-if="page === 'overview'"        :lang="tweaks.lang" @order="openTicket" @navigate="page = $event" />   <!-- :36 -->
      <Markets    v-else-if="page === 'markets'"    ... />
      <Chart      v-else-if="page === 'chart'"      ... />
      <Positions  v-else-if="page === 'positions'"  :lang="tweaks.lang" @order="openTicket" />                             <!-- :39 -->
      <Analytics  v-else-if="page === 'analytics'"  ... />
      <Trades     v-else-if="page === 'trades'"     :lang="tweaks.lang" @order="openTicket" />                             <!-- :41 -->
      ...
    </main>
```

✅ **`v-if` / `v-else-if` 鏈確認,無 `<KeepAlive>`。非當前頁確實是卸載的。**

`OrderTicket` 的掛載位置(`:52-59`):

```html
    <OrderTicket
      :open="ticketOpen"
      :lang="tweaks.lang"
      :preset="ticketPreset"
      @close="ticketOpen = false"
      @navigate="onTicketNav"
      @toast="showToast"
    />
```

它是 `<main>` 的**兄弟節點**,在 `</main>`(`:48`)之後 —— ✅ **確認為全域 overlay,與當前頁無關**。而且 `OrderTicket.vue:2` 是 `<Teleport to="body">`,內部 `v-if="open"`(`:3`)。

**⚠️ 行號漂移**:CONTEXT.md 說 `App.vue:52-56`,實際 OrderTicket 區塊是 **`:52-59`**(8 行,含 3 個 prop + 3 個 event)。其餘引用皆準確:`:36` ✅、`:104` `const ticketOpen = ref(false);` ✅、`:164` 在 `openTicket`(`:162-165`)內 ✅、`:170` `function onTicketNav(p: 'positions') { page.value = p; ticketOpen.value = false; }` ✅。

**D-10 的推論因此完全成立**:使用者可能在 Markets / Chart / Analytics / Settings 任何頁按 `@order` 開 ticket,此時三個 portfolio 頁**全部未掛載**。無條件打三個請求是純浪費(無 client-side cache 可放,無消費者)。

### Q7.2 revision counter 該放哪裡?—— 引用既有前例,不發明新 pattern

**專案內的共享狀態前例盤點:**

| 前例 | 檔案 | pattern |
|------|------|---------|
| **`pageApiClients.ts`** | `:19` `let clients: RuntimeApiClients \| null = null;`;`:21-35` getter;`:37-39` `resetRuntimeApiClientsForTests()` | **模組級 singleton + 明確測試 reset** |
| `apiClient.ts` | `:62-63` `let refreshPromise: Promise<void> \| null = null;` / `let sessionHandlers: ApiClientSessionHandlers = {};`;`:65-67` `configureApiClientSessionHandlers()` | 模組級可變狀態 + 設定函式 |
| `store.ts` | `:2` `import { reactive } from 'vue';`;`:29` `export const store: Store = reactive({...})` | 模組級 `reactive` singleton(**legacy mock store**) |
| `stores/mockPortfolio.ts` 等 | Pinia `defineStore` | **全部是 mock 專用**(檔名 `mock*` 前綴),不是通用狀態容器 |
| `composables/useAiAccessSettings.ts` | `:1` `import { computed, reactive, ref } from 'vue'` | **factory function**(每次呼叫產生新實例),**不是** singleton — 不適合 revision counter |

**`composables/` 目前只有一個檔案**(`useAiAccessSettings.ts` + 其 test),所以沒有「composable 一定是 singleton 還是 factory」的既定慣例。

**建議:比照 `pageApiClients.ts` 的模組級 singleton + 測試 reset,新建 `src/services/portfolioRevision.ts`。**

```typescript
import { readonly, ref, type Ref } from 'vue';

/**
 * Portfolio 資料版本號（D-10）。
 *
 * 為什麼是「通知」而不是「代打三個請求」：App.vue:36-48 用 v-if 切頁，非當前頁是卸載的；
 * 而 OrderTicket 是全域 overlay（App.vue:52-59），使用者可能在 Markets/Chart 頁下單，
 * 此時三個 portfolio 頁全部未掛載。無條件打三個 domain 請求沒有任何消費者，是純粹的無效工。
 *
 * 為什麼是模組級 singleton 而不是 Pinia store：stores/ 底下目前全是 mock 專用 store
 * （mockPortfolio / mockNotifications / mockPreview），把跨 mode 的協調狀態放進去會模糊
 * 「mock store 是 mock mode 專屬」這條界線（judgment §3）。模組級 singleton + 顯式
 * 測試 reset 是 pageApiClients.ts:19,37-39 與 apiClient.ts:62-67 已經在用的慣例。
 */
const revision = ref(0);

/** 唯讀版本號；三個 portfolio 頁 watch 它。 */
export const portfolioRevision: Readonly<Ref<number>> = readonly(revision);

/** 交易建立成功後呼叫，通知已掛載的 portfolio 頁重讀。 */
export function bumpPortfolioRevision(): void {
  revision.value += 1;
}

/** 測試隔離用（比照 resetRuntimeApiClientsForTests）。 */
export function resetPortfolioRevisionForTests(): void {
  revision.value = 0;
}
```

**⚠️ 一個必須注意的測試隔離問題**:`testSetup.ts` 目前**只**做 `vi.stubEnv` / `vi.unstubAllEnvs`,**沒有** reset 任何模組級 singleton(連 `resetRuntimeApiClientsForTests` 都沒呼叫 —— 註解 `:10-12` 明文解釋為什麼刻意不 import service module)。而 `vite.config.ts:26-27` 設了 `pool: 'threads'` + `fileParallelism: false`,所以**同一個測試檔內**的多個測試共用模組狀態。planner 必須在需要的測試檔自行 `afterEach(() => resetPortfolioRevisionForTests())`,**不要**加進 `testSetup.ts`(會踩到 `:10-12` 警告的 mock 失效問題)。

### Q7.3 三個頁面怎麼 watch

三頁都**沒有現存的 `watch(`**(`grep -n "watch(" src/pages/{Overview,Positions,Trades}.vue` 零命中),所以這是新增。

| 頁面 | 現有 load 函式(已查證) | 建議 watch |
|------|---------------------|-----------|
| **Overview** | `:232` `async function loadSummary()`;`:241` `async function loadRecentTrades()`;`:251` `onMounted(() => { if (live) return; ... })` | `watch(portfolioRevision, () => { if (live) return; void loadSummary(); void loadRecentTrades(); });` |
| **Positions** | `:351` `async function loadSummary()`;`:360` `async function loadHoldings()`;`:369-374` `onMounted(...)` | `watch(portfolioRevision, () => { if (live) return; void loadSummary(); void loadHoldings(); });` |
| **Trades** | `:255` `async function loadTrades(allowOverflowFallback = true)`;`:282-286` `applyQueryChange(mutate)` | **見 Q7.4** |

> CONTEXT.md 的行號引用 `Overview.vue:232,241`、`Positions.vue:351,360`、`Trades.vue:255` —— **全部逐字準確** ✅

**`if (live) return;` 是必要的**:mock mode 完全走 `live` reactive 委派、不打網路(三頁的 `onMounted` 註解都明文寫了)。mock mode 下 `executeOrder` 直接改 store,Pinia reactivity 自動更新畫面,**不需要也不應該**觸發 refetch。

### Q7.4 D-11:Trades 頁的重讀 —— **已有現成入口**

`Trades.vue:281-286` 逐字:

```typescript
/** D-15:任何篩選或排序變更都經此入口,頁碼一律重置為 0 後再請求。 */
function applyQueryChange(mutate: () => void) {
  mutate();
  pageNo.value = 0;
  void loadTrades();
}
```

**這正好就是 D-11 要的三件事**:保留篩選(`activeFilter` 不動)、保留排序(`sortKey`/`sortDir` 不動)、頁碼歸零、重新請求。

```typescript
// D-11:保留篩選與排序、頁碼重置為 0（與 Phase 3 D-15 同一入口，不另造第二條重置邏輯）。
watch(portfolioRevision, () => {
  if (live) return;
  applyQueryChange(() => {});     // 不改任何查詢條件，只重置頁碼並重讀
});
```

`applyQueryChange(() => {})` 傳空 mutate 是刻意的 —— **重用既有的單一重置入口,而不是複製 `pageNo.value = 0; void loadTrades();` 兩行**。這符合 `:281` 註解宣示的「任何篩選或排序變更都經此入口」精神。

**現有的篩選 / 排序 / 分頁狀態(全部要保留)**:

| 狀態 | 行 |
|------|-----|
| `activeFilter`(chip:`Buy` / `Sell` / `YEAR_CHIP` / 全部)→ `filterParams()` 轉成 `type` / `dateFrom` / `dateTo` | `:232-243` |
| `sortKey` | `:179` |
| `sortDir` | `:180` |
| `pageNo` | `:181` |
| `queryParams()` = `{ ...filterParams(), sort, direction }` | `:245-247` |

### Q7.5 「新交易不在結果集內」怎麼偵測?

D-11 要求「若新交易不在結果集內則明確告知」。因為 D-03 允許補登,新交易可能:
- 不符當前篩選(篩了 Buy 卻記一筆 Sell)
- 不在第 0 頁(補登到去年,而排序是 `executedAt DESC`)

**偵測方法:refetch 完成後,檢查回傳的 `items` 是否含有剛建立那筆 trade 的 `id`。**

```typescript
// OrderTicket 成功後把剛建立的 trade 存進一個共享的「最近成交」狀態（見 Q7.6 的 lastFill），
// Trades 頁 refetch 完成後比對：
const justCreatedId = lastCreatedTradeId.value;          // 來自共享狀態
const inResultSet = justCreatedId === null
  || apiTrades.value.some(t => t.id === justCreatedId);
// inResultSet === false → 顯示「已記錄，但不在目前的篩選/排序條件內」
```

**為什麼比 `id` 而不是重算篩選條件?** 因為篩選與排序都在**後端**執行(`Trades.vue:107` 的註解:「整份列表就是後端當前頁的回應,前端不再做任何本地篩選或排序」)。前端重算「這筆是否符合條件」等於在前端複製一份後端的篩選邏輯 —— 那是 Phase 3 D-04 / judgment §7 明確反對的事,而且第一個邊界情況(半開區間、時區)就會不一致。**比 `id` 是唯一不需要複製後端邏輯的方法。**

`justCreatedId` 應在顯示過提示、或使用者變更篩選/排序、或下次開 ticket 時清空,否則提示會一直掛著。planner 需決定清空時機。

### Q7.6 D-13:API mode 的 `lastFill` 等價物

**現有 mock 路徑**(全部查證):

| 頁面 | mock lastFill 來源 | fresh class 綁定 |
|------|-------------------|-----------------|
| Positions | `:320` `const mockLastFill = computed(() => (live ? live.lastFill : null));` | `:235` `:class="{ fresh: mockLastFill && p.sym === mockLastFill.sym, scrubbed: scrubDays > 0 }"` |
| Trades | `:162` `const mockLastFill = computed(() => (live ? live.lastFill : null));` | `:94` `:class="{ fresh: i === 0 && mockLastFill && tr.sym === mockLastFill.sym }"` |

CSS 動畫兩邊都是 `tbody tr.fresh { animation: highlight 1.6s ease-out; }`(`Positions.vue:762`、`Trades.vue:462`)。

**要清掉的兩條 TODO 註解**(D-13 明文,逐字確認):

- `Positions.vue:264` — `lastFill 在 API mode 無成交事件來源,故不綁 fresh class(Phase 4 引入 post-trade refetch 時再接)。`
- `Trades.vue:109` — `lastFill 在 API mode 無成交事件來源,故不綁 fresh(Phase 4 接 post-trade refetch)。`

✅ 兩處行號皆準確。

**建議:把 lastFill 等價物放進同一個 `portfolioRevision.ts` 模組**,因為它與 revision 是同一個生命週期(一次成交同時產生「要重讀」與「哪一列是新的」兩個訊號),分成兩個模組會讓「先 bump 還是先 set lastFill」變成一個需要協調的順序問題。

```typescript
/** D-13：API mode 的 lastFill 等價物，形狀與 mock 的 PortfolioLiveMockData.lastFill 一致，
 *  讓兩個 mode 的 fresh 高亮綁定可以共用同一段模板表達式。 */
export interface LastFill {
  sym: string;
  type: 'BUY' | 'SELL';
  qty: number;
  px: number;
}

const lastFill = ref<LastFill | null>(null);
const lastCreatedTradeId = ref<string | null>(null);

export const apiLastFill: Readonly<Ref<LastFill | null>> = readonly(lastFill);
export const lastCreatedTradeId_ = readonly(lastCreatedTradeId);

/** 交易建立成功後由 OrderTicket 呼叫：同時記錄 lastFill 與提升 revision（單一原子動作）。 */
export function notifyTradeCreated(trade: TradeDto): void {
  lastFill.value = { sym: trade.symbol, type: trade.type, qty: trade.quantity, px: trade.price };
  lastCreatedTradeId.value = trade.id;
  revision.value += 1;
}
```

**注意 `LastFill` 的形狀刻意與 `PortfolioLiveMockData.lastFill`(`portfolioApi.ts:36`)逐字一致**:`{ sym, type, qty, px }`。這樣兩頁的模板可以寫成 `const effectiveLastFill = computed(() => live ? live.lastFill : apiLastFill.value);`,**fresh class 的綁定表達式完全不用改**,只是來源換了。這是最小改動路徑。

---

## Q8 — D-16 欄位級錯誤綁定

### Q8.1 後端確實回欄位級錯誤 —— 逐行確認

`GlobalExceptionHandler.java:56-64`(實讀,**CONTEXT.md 的 `:56-64` 引用準確** ✅):

```java
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> fields = new LinkedHashMap<>();                       // :58
        exception.getBindingResult().getFieldErrors().forEach(error ->
            fields.put(error.getField(), error.getDefaultMessage())               // :60  ← key = field, value = 英文訊息
        );
        ApiError error = ApiError.of(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), fields);  // :62
        return ResponseEntity.badRequest().body(ApiResponse.failure(error, ApiMetaFactory.current()));
    }
```

`ApiError`(全檔實讀):

```java
public record ApiError(String code, String message, Map<String, String> fields) {    // :7
    public ApiError {
        fields = fields == null ? Map.of() : Map.copyOf(fields);                      // :10  ← 永遠不會是 null
    }
    ...
}
```

✅ `fields` 是 `Map<String,String>`,且 **JSON 永遠有 `"fields"` key**(至少是 `{}`)。

### Q8.2 `apiClient.ts` 已暴露 `fields` 與 `requestId` —— 確認

`ApiClientError`(`apiClient.ts:3-30`):

| 屬性 | 行 | 來源 |
|------|-----|------|
| `status: number` | `:4` | `response.status`(`:239`) |
| `code: string` | `:5` | `payload.error.code`(`:240`) |
| `requestId: string \| null` | `:6` | `requestIdFrom(payload)`(`:242`)→ `meta.traceId`(`:113-117`;`:112` 註解:「後端 ApiResponse 只在 meta.traceId 帶追蹤 id;沒有其他來源」) |
| `fields: Record<string,string> \| null` | `:10`(`:9` 註解引用 browser-auth-contract.md) | `fieldsFrom(payload.error)`(`:243`)→ `:228-234`,過濾非 string value,**空 map 時回 `null`** |

⚠️ **一個實作細節**:`fieldsFrom`(`:233`)`return entries.length ? Object.fromEntries(entries) : null;` —— 後端送 `"fields": {}` 時前端拿到的是 **`null`**,不是 `{}`。所以前端判斷要寫 `error.fields?.quantity`,不要寫 `Object.keys(error.fields).length`(會在 null 上炸)。

### Q8.3 `CreateTradeRequest` 的 6 個受驗欄位與確切的 `fields` key

`CreateTradeRequest.java` 全檔實讀:

```java
public record CreateTradeRequest(
    @NotBlank String symbol,                                                          // :19
    @NotBlank String type,                                                            // :20
    @NotNull @DecimalMin(value = "0.00000001") @DecimalMax(value = "1000000000")
    @Digits(integer = 10, fraction = 8) BigDecimal quantity,                          // :21-22
    @NotNull @DecimalMin(value = "0.00000001") @DecimalMax(value = "1000000")
    @Digits(integer = 7, fraction = 8) BigDecimal price,                              // :23-24
    @DecimalMin(value = "0.0") BigDecimal fee,                                        // :25
    @Size(max = 500) String note,                                                     // :26
    OffsetDateTime executedAt                                                          // :27  ← 無任何驗證註解！
) {}
```

`error.getField()`(`GlobalExceptionHandler:60`)對 record 回傳的是 **Java property 名**,即 record component 名。所以確切的 key:

| `fields` key | 可能的驗證失敗 | 綁到哪個輸入框 |
|-------------|--------------|--------------|
| `symbol` | `@NotBlank` | symbol 輸入框 |
| `type` | `@NotBlank` | BUY/SELL 切換(理論上前端不可能送空,但仍需綁,否則後端改了會沒地方顯示) |
| `quantity` | `@NotNull` / `@DecimalMin 0.00000001` / `@DecimalMax 1000000000` / `@Digits(10,8)` | 數量輸入框 |
| `price` | `@NotNull` / `@DecimalMin 0.00000001` / `@DecimalMax 1000000` / `@Digits(7,8)` | 價格輸入框 |
| `fee` | `@DecimalMin 0.0`(**注意:沒有 `@NotNull`,可省略**) | 手續費輸入框(D-02 新增) |
| `note` | `@Size(max=500)` | 備註輸入框 |

> CONTEXT.md 說「`CreateTradeRequest` 有 6 個驗證註解」—— 更精確地說是 **6 個帶驗證註解的欄位、共 11 個註解**。CONTEXT.md 列的 `fields` key 清單(`symbol/quantity/price/fee/note`)**漏了 `type`** —— 補上。

### Q8.4 ⚠️ `executedAt` 沒有 Bean Validation 註解 —— 它的錯誤**不會**進 `fields`

`executedAt`(`:27`)完全沒有驗證註解。含意:

| 錯誤情境 | 後端行為 | envelope |
|---------|---------|---------|
| `executedAt` 格式無法反序列化(例如 `"2026-01-01"` 無時間/offset) | Jackson 反序列化失敗 → `HttpMessageNotReadableException`(實作 `ErrorResponse`,狀態 400)→ 落 `handleUnexpected` 的 `ErrorResponse` 分支 → `codeForStatus(400)` → `VALIDATION_FAILED`,**`fields` 為空** | `{code: "VALIDATION_FAILED", fields: {}}` |
| `executedAt` 是未來時間(PR #15 的驗證) | `BusinessException(VALIDATION_FAILED, "executedAt must not be in the future")` → `handleBusiness`(`:49-54`)→ `ApiError.of(code, message)`(**兩參數版,無 fields**) | `{code: "VALIDATION_FAILED", message: "executedAt must not be in the future", fields: {}}` |

**所以 D-16 的「executedAt 未來時間」錯誤無法用 `fields` 綁到日期欄位。** 兩個選擇:

- **(i) 顯示在 ticket 底部**(D-16 的預設分支:「其餘顯示在 ticket 底部」)。最簡單,且完全符合 D-16 字面。
- **(ii) 前端自己在送出前擋掉未來時間**(input 的 `max` 屬性 + 送出前檢查),讓這個後端錯誤在正常操作下永不出現。

**建議兩者都做**:(ii) 作為 UX(使用者立即知道),(i) 作為 fail-safe(時鐘偏移、或使用者繞過前端)。這也讓 Q0 選項 (c)(後端驗證由 PR #15 提供)在 Phase 4 期間仍有可用的使用者體驗。

### Q8.5 硬規則複述:`fields` 的 value **不得顯示**

D-16 明文:「`fields` 的 value 是 Bean Validation 的英文預設訊息,**不得直接顯示** —— 前端依 field 名稱對應自己的 i18n 文案。」

實際的 value 長什麼樣(Hibernate Validator 預設訊息):

| 註解 | `getDefaultMessage()` |
|------|----------------------|
| `@NotBlank` | `"must not be blank"` |
| `@NotNull` | `"must not be null"` |
| `@DecimalMin("0.00000001")` | `"must be greater than or equal to 0.00000001"` |
| `@DecimalMax("1000000")` | `"must be less than or equal to 1000000"` |
| `@Digits(integer=7, fraction=8)` | `"numeric value out of bounds (<7 digits>.<8 digits> expected)"` |
| `@Size(max=500)` | `"size must be between 0 and 500"` |

`[ASSUMED]` — 這些是 Hibernate Validator 的標準預設訊息,未在本 session 實跑確認。但**這正是為什麼不能顯示**:它們是英文的、技術性的、且**版本可能變動**。

**正確做法:只用 `fields` 的 **key** 判斷「哪個欄位錯了」,文案完全由前端 i18n 提供。**

```typescript
// ✅ 正確
const fieldErrorKeys = computed(() => Object.keys(submitError.value?.fields ?? {}));
// 模板：<div v-if="fieldErrorKeys.includes('quantity')" class="field-error">{{ t(lang, 'tradeErrQuantity') }}</div>

// ❌ 錯誤（違反 D-16）
// <div>{{ submitError.fields.quantity }}</div>
```

**代價與 planner 需要接受的事**:前端 i18n 的文案是「這個欄位有問題」的**通用**文字,無法區分「太小」與「小數位太多」(兩者都是 `quantity` key)。若要更精確,唯一乾淨的做法是後端改用專屬 `ErrorCode`(如既有的 `TRADE_INVALID_QUANTITY`,`ErrorCode.java:41`)—— **但那是 API 契約變更,judgment §9 要求先問**,且不在 D-01~D-16 範圍內。**建議 Phase 4 接受通用文案**,並在文案中提示合法範圍(例如「數量必須大於 0,最多 8 位小數」——範圍是前端從 `CreateTradeRequest` 的約束抄下來的靜態知識,不是從錯誤訊息解析的)。

### Q8.6 前端 i18n 目錄位置與新 key 命名慣例

| 事實 | 證據 |
|------|------|
| 目錄檔案 | `src/i18n.ts`(283 行) |
| 結構 | `export const I18N: Record<Lang, Record<string, string>>`(`i18n.ts:4`)—— **扁平**的 key→字串 map,**沒有** nesting |
| 語言 | `zh`(`:5` 起)與 `en`(`:144` 起)。**兩邊都必須加**,否則 `t()` 會回 key 本身或 undefined |
| 命名慣例 | 扁平 **camelCase**,語意縮寫。例:`estTotal`、`estFee`、`cashAfter`、`avgFillPx`、`orderId`、`placeOrder`、`newOrder`、`orderType`、`noTrades`、`selectSymbol`、`dayRange` |
| 存取 | `t(lang, 'key')`(`OrderTicket.vue:199` import;全檔大量使用) |
| 有測試 | `src/i18n.test.ts` 存在 —— planner 應檢查它是否斷言「zh 與 en 的 key 集合相同」(若有,新增 key 漏掉一邊會直接紅燈,這是好事) |

**要刪除的 key**(D-04/D-09 隱藏的東西,若 mock mode 仍要用就**不能刪**):

| key | 行 | 處置 |
|-----|-----|------|
| `cashAfter` | `:194`(en) | **保留** — mock mode 的 Review 畫面仍顯示(D-04:「mock mode 四樣全部保留」) |
| `orderType` / `market` / `limit` | `:193`(en)、`:55`(zh) | **保留** — 同上 |
| `tif` / `day` / `gtc` | 未在 grep 輸出中定位,但存在(`OrderTicket.vue:86-89` 使用) | **保留** — 同上 |
| `routingMatch` | 未定位(`OrderTicket.vue:242` 使用) | **可刪** — D-09 移除三階段假進度,mock mode 也不再有 routing 階段。⚠️ 但若 planner 選擇「mock mode 保留四步 wizard」,則保留 |
| `avgFillPx` | `:196`(en)、`:58`(zh) | **可刪** — D-09 明文「不得出現『平均成交價』這種暗示撮合的欄位」。⚠️ 同上,mock mode 若保留則保留 |

**新增的 key(建議清單,planner 可調整命名):**

```
// D-02 / D-03 新欄位標籤
tradeExecutedAt    成交時間 / Executed at
tradeFee           手續費 / Fee              （既有 `fee` key 已存在，可直接複用）
tradeNote          備註 / Note               （既有 `notes` key 已存在，可複用）

// D-09 兩態
submitting         送出中… / Submitting…
tradeRecorded      交易已記錄 / Trade recorded
tradeId            交易編號 / Trade ID

// D-16 欄位級錯誤（key 對應 fields 的 key）
tradeErrSymbol     請選擇有效的標的 / Select a valid symbol
tradeErrType       交易類型無效 / Invalid trade type
tradeErrQuantity   數量必須大於 0，最多 8 位小數 / Quantity must be greater than 0, up to 8 decimals
tradeErrPrice      價格必須大於 0，最多 8 位小數 / Price must be greater than 0, up to 8 decimals
tradeErrFee        手續費不可為負 / Fee must not be negative
tradeErrNote       備註最多 500 字 / Note must be at most 500 characters
tradeErrExecutedAt 成交時間不可為未來時間 / Executed at must not be in the future

// D-16 ticket 底部錯誤（依 error.code 分派）
tradeErrOversell        持倉不足，無法賣出此數量 / Insufficient holding to sell this quantity
tradeErrAssetNotFound   標的不存在或不可交易 / Asset not found or not tradeable
tradeErrConflict        持倉在交易期間被變更，請重試 / Holding changed during trade, please retry
tradeErrKeyReused       此次送出的內容已變更，請重新送出 / Submission content changed, please submit again
tradeErrCsrf            安全驗證失敗，請重新載入頁面 / Security check failed, please reload
tradeErrNetwork         無法連線，請檢查網路後重試 / Cannot reach the server, please retry
tradeErrUnknown         發生未預期的錯誤 / An unexpected error occurred

// D-11
tradeNotInCurrentView   已記錄，但不在目前的篩選/排序條件內 / Recorded, but outside the current filter/sort view

// D-15
sellableQty        可賣數量 / Sellable qty

// Q6.5 klines 三態
quoteChartEmpty    無走勢資料 / No chart data
quoteChartError    走勢資料載入失敗 / Failed to load chart data
```

`[ASSUMED]` — 具體文案由 planner 依 Discretion 決定(CONTEXT.md 明文:「『不在篩選條件內』的文案 → 依現有 UI 慣例決定」)。上面是形狀示範,不是規定。

**⚠️ 一個必須注意的一致性要求**:`tradeErrKeyReused` 的文案不能說「重複的請求」—— 那會讓使用者以為交易被重複建立了。D-07 的語意是「同一個 key 但內容變了」,對使用者的正確指示是「請重新送出」(因為 D-14 會給新 key)。

---

## Q9 — D-15 SELL 預檢

### Q9.1 前端怎麼取得該標的持倉

**有現成方法可直接重用:`portfolioApi.listHoldings()`。**

```typescript
// portfolioApi.ts:50（interface）
  listHoldings(): Promise<HoldingDto[]>;
// portfolioApi.ts:184（HTTP 實作）
    listHoldings: () => apiRequest<HoldingDto[]>(`${basePath}/portfolio/holdings`),
// portfolioApi.ts:161-163（mock 實作）
    async listHoldings() {
      return useMockPortfolioStore().positions.map(holdingFrom);
    },
```

✅ CONTEXT.md 的「可與 Positions 頁共用同一 adapter 方法」正確 —— `Positions.vue:360-367` 的 `loadHoldings()` 用的就是同一個方法。

**回傳是「全部持倉的陣列」,不是 per-symbol** —— 後端 `GET /api/v1/portfolio/holdings` 回 `ApiResponse<List<HoldingDto>>`(`TradingController.java:83-85`),**沒有** symbol 篩選參數。所以前端必須自行 filter:

```typescript
const holding = holdings.find(h => h.symbol === symbol);
const sellableQty = holding?.totalQuantity ?? 0;
```

**⚠️ 一個必須知道的後端過濾行為**:`listHoldings` 的 SQL 有 `where h.user_id = :userId and h.total_quantity > 0`(`JdbcTradingRepository.java:210`)。所以**已平倉的部位(`total_quantity = 0`)根本不在回應裡**。

含意:`find(...)` 回 `undefined` 有**兩種**意義 ——「從未持有」與「曾持有但已全數賣出」。兩者對 D-15 的處置相同(可賣 0),所以不影響正確性,但文案不該說「您未持有此標的」而該說「可賣數量:0」——後者兩種情況都成立。

**另一個含意**:`HoldingDto` 有一堆 D-15 **不該用**的欄位(`avgCost`、`costBasis`、`marketPrice`、`marketValue`、`realizedPnl`、`unrealizedPnl`、`roi`,見 `apiTypes.ts:57-73`)。D-15 明文「預檢只比數量上限,**不重算成本或損益**」。**planner 的實作只能碰 `totalQuantity` 與 `symbol` 兩個欄位**,絕不可用回傳的 `avgCost` 去算「賣出後的損益預估」—— 那會踩 Phase 3 D-04 與 judgment §7。

### Q9.2 何時載入?—— 一個 planner 需要決定的取捨

CONTEXT.md 已明確拒絕「只在已載入 holdings 時才預檢」(理由:「會讓同一操作在 Positions 頁與 Markets 頁行為不同」)。所以 ticket 必須**自己**載入。剩下的問題是**時機**:

| 選項 | 評估 |
|------|------|
| ticket 開啟時就載入全部 holdings | 一次請求,之後切換 symbol / BUY↔SELL 都不用再打。但 BUY-only 的使用者也付這個成本 |
| 切到 SELL 時才載入 | 只有 SELL 才付。但 D-15 要顯示「可賣數量」,所以切到 SELL 那一刻會有短暫 loading |
| 選定 symbol 且 side === SELL 時載入 | 同上,但更晚 |

**建議:切到 SELL 時載入一次並在該 ticket 生命週期內快取。** 理由:(a) 只有 SELL 需要;(b) 一次拿全部 holdings 之後,使用者換 symbol 不需要再打;(c) `HoldingDto[]` 對單一使用者是小陣列(`holdingCount` 在 `PortfolioSummaryDto` 裡,通常個位數到數十)。

**⚠️ 一個 stale 風險**:若使用者在同一個 ticket 內完成一筆 SELL、然後不關 ticket 又要賣同一標的,快取的 `totalQuantity` 就過期了。**建議在 `notifyTradeCreated`(Q7.6)之後把 ticket 內的 holdings 快取失效** —— 這正好也是 revision counter 的一個消費者。planner 可以直接讓 ticket 也 `watch(portfolioRevision)`。

### Q9.3 後端 oversell 錯誤的權威來源

**`HoldingCalculator.applySell`(`HoldingCalculator.java:46-48`,逐字):**

```java
        if (existing == null || existing.totalQuantity().compareTo(quantity) < 0) {
            throw new BusinessException(ErrorCode.TRADE_INSUFFICIENT_HOLDING, ErrorCode.TRADE_INSUFFICIENT_HOLDING.defaultMessage());
        }
```

| 項目 | 值 |
|------|-----|
| ErrorCode | `ErrorCode.TRADE_INSUFFICIENT_HOLDING` |
| HTTP status | **409**(`ErrorCode.java:43`) |
| message | `"Insufficient holding quantity"`(`defaultMessage()`,**不含任何數量資訊**) |
| 觸發條件 | `existing == null`(從未持有)**或** `totalQuantity < quantity` |
| 呼叫處 | `TradingService.java:72` `case SELL -> calculator.applySell(current.orElse(null), ...)` |
| 現有 IT 覆蓋 | `TradingApiIT.sellRejectsOversell`(`:136-148`):`{"symbol":"AAPL","type":"SELL","quantity":1,...}` 在零持倉下 → `isConflict()` + `$.error.code == "TRADE_INSUFFICIENT_HOLDING"` ✅ |

**注意 message 刻意不含實際可賣數量**(符合 `code-standards.md:82`「never include internal IDs」的精神)。所以前端**無法**從錯誤回應得知「實際可賣多少」—— 這正是 D-15 需要前端主動載入 holdings 的第二個理由(除了 UX)。

**`applySell` 順帶發現的兩個相關驗證**(planner 應知,因為它們也會產生前端要處理的錯誤):

| 條件 | ErrorCode | status | 行 |
|------|-----------|--------|-----|
| `quantity == null` 或 `<= 0` | `TRADE_INVALID_QUANTITY` | 400 | `:43` → `:64-68` |
| `price == null` 或 `<= 0` | `TRADE_INVALID_PRICE` | 400 | `:44` → `:64-68` |
| `fee < 0` | `VALIDATION_FAILED`,message `"fee must be greater than or equal to 0"` | 400 | `:70-78` |

**這三個 code 在 `CreateTradeRequest` 的 Bean Validation 之後才會被觸及**,所以正常情況下前端不會看到它們(`@DecimalMin("0.00000001")` 先擋)。但它們**不會**進 `fields`(走 `handleBusiness` 而非 `handleValidation`)。D-16 的實作應把它們納入「依 code 分派到底部」的清單,即使實務上罕見。

### Q9.4 D-15 的完整實作形狀建議

```typescript
// SELL 預檢：只比數量上限，不重算成本或損益（D-15 明文 / Phase 3 D-04 / judgment §7）。
const sellableQty = computed<number | null>(() => {
  if (side.value !== 'SELL') return null;
  if (holdingsState.value.status !== 'loaded') return null;
  const symbol = selected.value?.symbol;
  if (!symbol) return null;
  // 後端 listHoldings 的 SQL 有 total_quantity > 0（JdbcTradingRepository:210），
  // 所以「找不到」同時涵蓋「從未持有」與「已全數平倉」，兩者可賣數量都是 0。
  return holdingsState.value.data.find(h => h.symbol === symbol)?.totalQuantity ?? 0;
});

const sellPrecheckError = computed<string | null>(() => {
  if (sellableQty.value === null) return null;              // 尚未載入或非 SELL → 不預檢
  if (qty.value <= 0) return null;                          // 交給既有的 canSubmit 判斷
  if (qty.value > sellableQty.value) return t(props.lang, 'tradeErrOversell');
  return null;
});
```

**必須保留的關鍵行為**:預檢**不可**取代後端。即使 `sellPrecheckError` 為 null(前端認為可以賣),送出後仍可能吃 409 `TRADE_INSUFFICIENT_HOLDING`(併發情境:另一個 tab 剛賣掉了)。**D-16 的底部錯誤區必須能顯示這個 code,而且測試必須覆蓋「前端預檢通過但後端拒絕」這條路徑** —— 這才是 judgment §5「前端 guard 不是防護」的實質驗收。

---

## Standard Stack

**本階段不引入任何新的第三方套件。** 全部用既有能力。

### 後端(既有,版本已鎖於 parent BOM)

| 元件 | 版本 | 用途 | 為何是這個 |
|------|------|------|-----------|
| `spring-jdbc` `JdbcClient` | 7.0.8 `[VERIFIED: 本機 ~/.m2 jar]` | `(user_id, idempotency_key)` 查詢與帶 key 的 insert | `JdbcTradingRepository` 全檔都用它(`:10,28`);引入 `JdbcTemplate`/`NamedParameterJdbcTemplate` 會製造第二種風格 |
| `spring-tx` `@Transactional` | 7.0.8 | 交易邊界 | `createTrade` 已有(`:60`) |
| `spring-web` `@RequestHeader` | 7.0.8 | 接 `Idempotency-Key` | `BackfillController:90` 已有慣例(但 `required` 值不同,見 D-05) |
| Flyway | 11 `[CITED: PR #15 的 V9 註解「Postgres 16 + Flyway 11」]` | V10 migration | `flyway-convention.md` 硬規則 |
| PostgreSQL(TimescaleDB) | `timescale/timescaledb:2.17.2-pg16` = **PG 16** `[VERIFIED: ContainerIT.java:16]` | partial unique index + `ON CONFLICT` | judgment §5 要求「唯一約束」 |
| Testcontainers + JUnit 5 + AssertJ | 由 BOM 管理 | IT | `ContainerIT` 已有(`:12-64`) |
| `commons-lang3` `StringUtils` | 由 BOM 管理 | key 的 `isBlank` 檢查 | `code-standards` 要求字串判空一律用 `StringUtils`;`TradingService:22` 已 import |

### 前端(既有)

| 元件 | 版本 | 用途 | 為何是這個 |
|------|------|------|-----------|
| Vue | `^3.5.34` `[VERIFIED: package.json]` | 元件 + `ref`/`computed`/`watch`/`readonly` | 專案本體 |
| Vitest | `^4.1.6` `[VERIFIED: package.json]` | 單元/元件測試 | `npm test` = `vitest run`;`vite.config.ts:20-28` 已設 jsdom + `testSetup.ts` |
| `crypto.randomUUID()` | Web Crypto API(瀏覽器內建) | idempotency key 產生 | **Discretion 已鎖**。⚠️ 見下方 jsdom 注意事項 |
| `AbortController` / `signal` | Web 標準 | typeahead 競態處理(選用) | `ApiRequestOptions` 已支援(`apiClient.ts:32`) |
| 既有 `apiRequest` / `apiPaginatedRequest` / `ApiClientError` / `buildQueryString` | — | 全部 HTTP | Phase 2 D-20:shared client 是**唯一** transport 邊界。canonical ref 明文「勿另造轉接層」 |

### ⚠️ `crypto.randomUUID()` 在 jsdom 下的注意事項

`crypto.randomUUID()` 在 **secure context** 才可用(HTTPS / localhost)。jsdom 的 `crypto` 實作依 Node 版本而異:

- Node 19+ 提供全域 `crypto`(Web Crypto),`crypto.randomUUID()` 可用。
- jsdom 環境下 `globalThis.crypto` 是否被 jsdom 覆寫成不完整實作,`[ASSUMED]` — 未在本 session 實測。

**planner 的處置建議**:第一個測試就直接呼叫它;若在 jsdom 下 undefined,用 `vi.stubGlobal('crypto', { randomUUID: () => 'fixed-uuid' })` 處理。**而且測試本來就該 stub 它** —— 隨機 UUID 讓斷言無法寫「送出的 header 是 X」。所以無論是否可用,測試都會 stub,實際風險僅限於 production build(瀏覽器一定有)。

`[ASSUMED]` — 若 Node 版本過舊導致 production 也有問題,fallback 是 `URL.createObjectURL` 取 UUID 或手寫 `crypto.getRandomValues` 組 v4。**不要為此引入 `uuid` npm 套件**(見下節)。

---

## Package Legitimacy Audit

**本階段不安裝任何新的 npm / Maven 套件,因此無需執行 package legitimacy gate。**

| Package | Registry | Verdict | Disposition |
|---------|----------|---------|-------------|
| （無） | — | — | 本階段零新增依賴 |

**Packages removed due to [SLOP] verdict:** none
**Packages flagged as suspicious [SUS]:** none

**為什麼不需要新套件 —— 三個「可能被誤加」的候選與其既有替代品:**

| 可能被誤加的套件 | 為什麼不需要 |
|-----------------|-------------|
| `uuid`(npm) | `crypto.randomUUID()` 是瀏覽器內建 Web Crypto API,零依賴。Discretion 已明文鎖定用它。 |
| `lodash.debounce` / `debounce`(npm) | typeahead debounce 用 `setTimeout` + `clearTimeout` 十行以內完成。前端 repo 目前的 `package.json` dependencies 極簡,為一個 debounce 引入 lodash 生態不成比例。 |
| 任何日期套件(`date-fns` / `dayjs`) | D-03 的 `executedAt` 只需要 `new Date(...).toISOString()`(產生帶 `Z` 的 ISO-8601,毫秒精度)。`Trades.vue:218-226` 已有現成的 `toLocalIso(date)` helper 處理「帶本地 offset」的情形 —— **若 planner 需要送本地 offset 而非 UZ,直接重用它,不要新造也不要裝套件。** |

**⚠️ planner 若真的需要新套件,必須先跑 legitimacy gate**(`gsd-tools query package-legitimacy check` 目前在本環境 **不可用** —— `gsd-tools: command not found`,見 Open Questions Q-3)。替代驗證:`npm view <pkg> version`、`npm view <pkg> scripts.postinstall`、確認 GitHub repo 存在與週下載量。

---

## Architecture Patterns

### 後端資料流(冪等版 `createTrade`)

```
POST /api/v1/trades
  Header: Idempotency-Key（D-05 必填）+ X-XSRF-TOKEN（browser-auth-contract）
  Body:   CreateTradeRequest{symbol,type,quantity,price,fee,note,executedAt}
    │
    ▼
[SecurityFilterChain]  CSRF 驗證 → 403 AUTH_CSRF_TOKEN_INVALID
  Cookie/Bearer 認證   → 401 AUTH_INVALID_CREDENTIALS
    │
    ▼
[@PreAuthorize("hasAuthority('TRADE_EXECUTE')")]  ← TradingController:37
  拒絕 → AccessDeniedException → GlobalExceptionHandler:35-38 re-throw → Spring Security 403
  （Role.USER 已含 TRADE_EXECUTE，一般使用者可通過 — CONTEXT.md 查證 Role.java:12-17）
    │
    ▼
[@Valid @RequestBody]  ← TradingController:39
  失敗 → MethodArgumentNotValidException → handleValidation(:56-64) → 400 + fields{}
    │
[@RequestHeader(value="Idempotency-Key")]  ← 新增，required=true
  缺漏 → MissingRequestHeaderException（implements ErrorResponse）→ 400 VALIDATION_FAILED
    │
    ▼
TradingController.createTrade  ← :36-53（僅取 userId + 稽核，不做業務判斷）
    │
    ▼
TradingService.createTrade  @Transactional  ← :60
    │
    ├─(1) 零 I/O 驗證：null 檢查 / TradeType.fromApiValue / fee 補 0
    │     ＋ idempotencyKey 的 isBlank + 長度檢查（Q3.4）→ VALIDATION_FAILED
    │     ＋（PR #15 後）resolveExecutedAt 擋未來時間
    │
    ├─(2) 快路徑：findByIdempotencyKey(userId, key)
    │        ├─ 命中 ＋ payload 相符 ──▶ 直接回既有 TradeDto（holdings 一行都沒碰）✅ judgment §5
    │        └─ 命中 ＋ payload 不符 ──▶ 409 TRADE_IDEMPOTENCY_KEY_REUSED（D-07）
    │
    ├─(3) resolveTradeableAsset(symbol)  ← DB I/O，:237-243
    │        └─ 不存在/不可交易 ──▶ 404 ASSET_NOT_FOUND
    │
    ├─(4) ⚠️ insertTransactionIfAbsent(... ON CONFLICT DO NOTHING RETURNING)
    │        ├─ 回傳有列 ──▶ 這個 key 是我的，繼續 (5)
    │        └─ 回傳零列 ──▶ 併發同 key。重讀既有交易：
    │              ├─ 讀到 ＋ payload 相符 ──▶ 回既有 TradeDto（holdings 未碰）✅
    │              ├─ 讀到 ＋ payload 不符 ──▶ 409 TRADE_IDEMPOTENCY_KEY_REUSED
    │              └─ 讀不到（理論殘餘競態）──▶ 409 TRADE_CONFLICT（不是 500！Q1.8）
    │
    ├─(5) findHoldingForUpdate → HoldingCalculator.applyBuy/applySell
    │        └─ SELL 且持倉不足 ──▶ 409 TRADE_INSUFFICIENT_HOLDING（HoldingCalculator:46-48）
    │           ⚠️ 整個 tx 回滾，含 (4) 插入的 transaction 列。key 未被燒掉，使用者改數量可重送
    │
    ├─(6) insertHoldingIfAbsent / updateHolding（既有邏輯，:74-87 不動）
    │        └─ 版本衝突 ──▶ 409 TRADE_CONFLICT（JdbcTradingRepository:126）
    │
    ├─(7) portfolioCache.invalidateAfterTrade(userId, assetId)  ← :102
    │        （已同時刪 holding + summary 兩個 key，本階段不動）
    │
    └─(8) return mapper.toTradeDto(saved)  → 200 ApiResponse<TradeDto>
```

**⚠️ 步驟 (4) 移到 (5)(6) 之前是本研究的核心建議**(Q1.7 方案 A)。CONTEXT.md 的提示是「holdings → insert」再處理衝突;本建議是「insert → holdings」,讓衝突在**任何副作用之前**就被偵測到。planner 若採 CONTEXT.md 的原順序,必須走 Q1.7 的方案 B(外層/內層兩個 bean)。見 DP-2。

### 前端資料流(OrderTicket API mode)

```
使用者在任何頁按 @order（Overview/Markets/Chart/Positions/Analytics）
    │  App.vue:162-165 openTicket(preset) → ticketOpen = true
    ▼
OrderTicket（全域 overlay，App.vue:52-59；Teleport to body，OrderTicket.vue:2）
    │
    ├─ symbol typeahead ──▶ getRuntimeApiClients().asset?（或 market）
    │                        GET /api/v1/assets?query=&page=0&size=10
    │                        ⚠️ apiPaginatedRequest（Q6.4：是分頁端點）
    │                        debounce 250-300ms ＋ 只採最後一次結果（Q6.6）
    │                        只列 tradeable === true（D-01）
    │
    ├─ 選定 symbol ──┬──▶ 報價卡數字直接來自 AssetDto（D-01，無需第二次請求）
    │                │
    │                └──▶ GET /market/{symbol}/klines?interval=1h&from=...&limit=48
    │                     ⚠️ OHLCV 是 JSON string（KlineDto ToStringSerializer）→ Number(k.close)
    │                     LineChart :data 需要 number[]（LineChart.vue:17-18）
    │                     三態：loading / empty / error（Q6.5，走勢圖失敗不阻擋送出）
    │
    ├─ price 預填 AssetDto.latestPrice 但可編輯（D-04 連帶效果）
    ├─ fee 使用者輸入，預設 0（D-02）
    ├─ executedAt 預設現在，可改；input max = 現在（D-03 + Q8.4）
    ├─ note 使用者輸入（取代原本的 tif === 'GTC' ? 'GTC' : ''）
    │
    ├─ side === 'SELL' ──▶ portfolio.listHoldings() → find(symbol).totalQuantity
    │                       顯示「可賣數量」；只比數量，不算成本（D-15）
    │
    ▼
按下送出
    │  ⚠️ key 在此刻產生：idempotencyKey = crypto.randomUUID()（D-14）
    │  ⚠️ submitting = true；送出鈕必須 :disabled（Q6.2 — 目前沒有！）
    ▼
trading.createTrade(request, idempotencyKey)
    │  apiRequest 自動注入：credentials:'include' + X-XSRF-TOKEN（apiClient.ts:220-223）
    │  401 → 單飛 refresh + 一次 replay，replay 沿用同一份 options
    │        ⇒ 同一個 Idempotency-Key（Q5.4）✅ 正好符合 D-14
    ▼
  ┌── 成功 ──────────────────────────────────────────────────┐
  │  notifyTradeCreated(trade)  ← 同時：set lastFill（D-13）  │
  │                                    set lastCreatedTradeId │
  │                                    bump revision（D-10）  │
  │  顯示「已記錄」＋ TradeDto: id/type/quantity/price/fee/    │
  │       executedAt（D-09；不得有「平均成交價」）             │
  │  ⚠️ 成功畫面與 refetch 結果分開呈現（D-12）               │
  └───────────────────────────────────────────────────────────┘
        │
        ▼  watch(portfolioRevision) — 只有「已掛載」的頁會反應（D-10）
  ┌─────────────┬──────────────┬────────────────────────────────┐
  │ Overview    │ Positions    │ Trades                          │
  │ loadSummary │ loadSummary  │ applyQueryChange(() => {})       │
  │ loadRecent  │ loadHoldings │  → 保留篩選排序、pageNo=0、重讀  │
  │  Trades     │              │  → 比對 lastCreatedTradeId 是否  │
  │             │              │    在 items 內 → D-11 提示       │
  │ 各自 loading/error/retry（Phase 3 D-11 / D-12）              │
  └─────────────┴──────────────┴────────────────────────────────┘
        │
        └─▶ fresh 高亮：effectiveLastFill = live ? live.lastFill : apiLastFill（D-13）
             Positions.vue:235 / Trades.vue:94 的綁定表達式不用改，只換來源

  ┌── 失敗 ──────────────────────────────────────────────────────────┐
  │  ApiClientError（apiClient.ts:3-30）                              │
  │    ├─ error.fields 有 key ──▶ 綁對應輸入框（D-16）                │
  │    │   ⚠️ 只用 key，value 是英文預設訊息，絕不顯示                 │
  │    │   ⚠️ fieldsFrom 空 map 時回 null，用 ?. 存取（Q8.2）          │
  │    └─ 依 error.code 分派到 ticket 底部（D-16）                     │
  │        ⚠️ 一律以 code 判斷，絕不假設錯誤出現順序（Q0/PR #15 警告）  │
  │        底部同時顯示 error.code + requestId（meta.traceId）         │
  │    401 / refresh 失敗 ──▶ 不在 ticket 顯示，走 SessionBanner       │
  │                            （Phase 3 D-13 / Phase 2 D-14）        │
  │  submitting = false；idempotencyKey 保留（同次嘗試重試沿用）       │
  │  ⚠️ 使用者改動任何欄位 ──▶ 清掉 key，下次送出產新 key（D-14）      │
  └───────────────────────────────────────────────────────────────────┘
```

### Pattern 1:三件組 domain service adapter

**What:** `createMockXxxApi()` / `createHttpXxxApi(basePath)` / `createXxxApi(mode, basePath)` + 單一 `interface XxxApi { mode; ...methods; live? }`。
**When:** 每一個新的前端 domain adapter。
**Why:** judgment §3(元件不 import mock store)+ Phase 2 D-20(shared client 是唯一 transport 邊界)。
**Source:** `portfolioApi.ts:47-54, 140-202`(最完整,含 `live`);`opsApi.ts:142-165`(含自訂 header);`aiAccessApi.ts` / `backtestApi.ts` / `authApi.ts` 同構。

### Pattern 2:POST + 自訂 header

**Source:** `opsApi.ts:146-154`(逐字見 Q5.3)。
**Why:** `apiClient.ts` 已處理 CSRF、credentials、401 refresh/replay;傳 `headers` 物件即可,零額外機制。

### Pattern 3:模組級 singleton + 顯式測試 reset

**Source:** `pageApiClients.ts:19, 21-35, 37-39`;`apiClient.ts:62-67`。
**When:** 跨頁共享的協調狀態(D-10 的 revision counter)。
**Why not Pinia:** `stores/` 目前全是 `mock*` 前綴的 mock 專用 store;把跨 mode 的協調狀態放進去會模糊 judgment §3 的界線。

### Pattern 4:per-block loading / error / retry 狀態機

**Source:** `Positions.vue:340-367`(`summaryState` / `holdingsState` + `describeError` + `BlockError`);`Overview.vue:225-249` 同構。
**Shape:** `{ status: 'loading' } | { status: 'loaded', data: T } | { status: 'error', error: BlockError }`,`BlockError = { code, traceId }`。
**Why:** Phase 3 D-11(各區塊各自載入重試)+ D-12(只在錯誤態露 code/traceId)。D-12 的「成功與 refetch 失敗分開呈現」直接落在這個結構上。

### Pattern 5:後端錯誤一律 `BusinessException(ErrorCode, message)`

**Source:** `TradingService` 全檔(`:63, 80, 240, 248, 253`);`HoldingCalculator:47, 66, 75`。
**Why:** `GlobalExceptionHandler.handleBusiness(:49-54)` 轉信封;controller 不做業務判斷(`TradingController` 的 class javadoc,PR #15 版本明文)。
**⚠️ 訊息安全:** `code-standards.md:79-84` —— message 不得含 ID / SQL 片段 / 使用者可控字串。**反例:`BackfillController:105-106` 把 idempotency key 串進訊息 —— 不要照抄。**

### Anti-Patterns to Avoid

- **`INSERT ... ON CONFLICT DO UPDATE` 對 `transactions`** — 會觸發 V8 的 `trg_transactions_no_update`,直接炸。只能用 `DO NOTHING`。(Q1.9)
- **`ON CONFLICT (user_id, idempotency_key)` 省略 `WHERE idempotency_key IS NOT NULL`** — partial unique index 無法被推斷,PostgreSQL 報 `no unique or exclusion constraint matching the ON CONFLICT specification`。(Q1.5)
- **在同一個 `@Transactional` 方法內 catch 唯一約束例外後重讀** — PostgreSQL 交易已中止。(Q1.2)
- **`this.someTransactionalMethod()` self-invocation** — Spring 官方文件明文「does not lead to an actual transaction at runtime」。(Q1.7 方案 B)
- **`BigDecimal.equals` 比金額** — scale 差異必然假性不符。用 `compareTo`。(Q4)
- **`OffsetDateTime.equals` 比時間** — offset 差異必然假性不符。用 `isEqual` / `toInstant()`。(Q4)
- **`apiRequest` 打 `GET /assets`** — 那是分頁端點,要用 `apiPaginatedRequest`。(Q6.4)
- **把 `KlineDto` 的 OHLCV 當 number 用** — 它們是 JSON string,`vue-tsc` 抓不到。(Q6.5)
- **顯示 `error.fields` 的 value** — 英文 Bean Validation 預設訊息,D-16 明文禁止。(Q8.5)
- **對錯誤出現順序寫死斷言** — PR #15 改了驗證順序,`ASSET_NOT_FOUND` ↔ `TRADE_UNSUPPORTED_TYPE` 的優先序會變。一律以 `error.code` 分派。(Q0)
- **在前端重算「這筆交易是否符合當前篩選」** — 複製後端邏輯,踩 Phase 3 D-04 / judgment §7。比 `id` 是否在 `items` 內。(Q7.5)
- **用 `HoldingDto.avgCost` 算「賣出損益預估」** — D-15 明文只比數量,不重算成本。(Q9.1)
- **修改已套用的 migration** — `flyway-convention.md:33, 162`。Phase 4 建 V10,**不要**動 V7/V8/V9。(Q2)
- **`CREATE INDEX CONCURRENTLY` 在 migration 內** — PR #15 的 V9 註解有 1.5 小時死鎖的實測紀錄。(Q2)

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| 併發同 key 的重複交易防護 | 應用層 `synchronized` / `ConcurrentHashMap` / 前端 debounce | **PostgreSQL partial unique index**(V10) | judgment §5 明文要求「唯一約束」。應用層鎖不跨 JVM 實例;前端擋不住第二個 tab |
| 冪等狀態儲存 | 獨立表 + TTL 清理 job;Redis SETNX | `transactions.idempotency_key` 欄位 | D-08 明文:清掉後同 key 重送會建重複交易(append-only 改不回來);Redis 與 DB tx 非原子,鎖成功但 DB 回滾就永久卡死該 key |
| 唯一約束衝突 → 例外 → 重讀 | 自己接 `SQLException` 判 `23505` | `ON CONFLICT DO NOTHING` + `.optional()`;或 catch `DataIntegrityViolationException`(照 `JdbcUserRepository:86`) | Spring 已翻譯例外;裸 SQLState 判斷會綁死驅動 |
| SQL 動態組裝 | 字串串接 | `JdbcClient` 具名參數 + enum 白名單 SQL 片段 | `code-standards`「絕對禁止串接 SQL」;`JdbcTradingRepository` class javadoc(PR #15 版本)明文說明這個不變量 |
| HTTP transport(CSRF / credentials / 401 refresh / replay) | 新的 fetch wrapper | `apiRequest` / `apiPaginatedRequest`(`apiClient.ts`) | Phase 2 D-20 明文「shared client 是唯一 transport 邊界」;canonical ref「勿另造轉接層」。而且 401 replay 已天然滿足 D-14 |
| 錯誤 envelope 解析 | 自己解 `{success,data,error,meta}` | `ApiClientError`(`apiClient.ts:3-30`)已含 `status/code/message/requestId/fields` | judgment §4:信封權威在後端;重複解析必然漂移 |
| UUID 產生 | `Math.random()` 拼字串;裝 `uuid` 套件 | `crypto.randomUUID()` | 瀏覽器內建;Discretion 已鎖。`Math.random()` 不是密碼學隨機,碰撞機率不可忽略,而碰撞會導致**別人的交易被當成你的重試回傳** —— 這是資料洩漏 |
| 時間格式化 / offset 處理 | 自己組 ISO 字串;裝 `date-fns` | `Date.toISOString()`;或既有 `Trades.vue:218-226` 的 `toLocalIso` | 已有 helper;PR #15 的 `ApiTimeParser` 在後端側處理解析 |
| debounce | 裝 `lodash.debounce` | `setTimeout` + `clearTimeout`(十行內) | 為十行程式碼引入生態不成比例 |
| 分頁 envelope 解析 | 自己解 `{items,page,size,totalElements,totalPages}` | `apiPaginatedRequest`(`:352-372`,含 `isPageResponse` 型別守衛) | 同上 |
| 測試用 PostgreSQL | 手動起 Docker / H2 / 記憶體 DB | `ContainerIT`(`:12-64`,已含 PG16+Redis+Kafka + Flyway wiring) | `testing-standards.md:36-56`;H2 不支援 partial unique index 的 `ON CONFLICT` 推斷 → 測了等於沒測 |

**Key insight:** 這個 phase 幾乎所有「基礎設施」都已就緒 —— 唯一需要真正新造的東西是**一條 SQL 的正確形狀**(partial unique index + 對應的 `ON CONFLICT` 推斷)與**一個 12 行的 revision counter 模組**。任何超出這個範圍的「新機制」都應該先問「既有的哪個檔案已經在做這件事」。這正是 judgment §6 的精神(「先 grep 確認,不存在才建」)在本階段的具體應用 —— 而 Q0 的 `ApiTimeParser` 事件就是這條規則失效時的真實代價。

---

## ⚠️ 未列入 CONTEXT.md 的範圍發現:前端**沒有** asset / market adapter

這是本研究最重要的**範圍**發現(其餘發現都是實作細節)。

| 檢查 | 結果 |
|------|------|
| `ls src/services/ \| grep -i "asset\|market"` | **零命中** |
| `grep -rn "api/v1/assets\|klines\|market/" src/` | **整個前端零命中** |
| `Markets.vue` 怎麼取資料? | `:129` `import { SYMBOLS, CRYPTO, FX, BONDS, fmtNum, fmtPct } from '../data';` —— **純本地假資料** |
| `RuntimeApiClients` 現有欄位 | `auth / aiAccess / backtest / ops / portfolio`(`pageApiClients.ts:12-16`)—— **沒有 asset,沒有 market** |

**含意:D-01(symbol 選單 + 報價卡 + 走勢圖全接真後端)需要建立的不是一個新 adapter,而是兩個:**

1. `tradingApi.ts` —— CONTEXT.md 已明文(Discretion + VER-02)
2. **`assetApi.ts`(或 `marketApi.ts`)—— CONTEXT.md 未明文,但 D-01 沒有它就做不到**

CONTEXT.md 的 `<code_context>` 只寫「`pageApiClients.ts` 的 `RuntimeApiClients` 需新增 `trading` 欄位」,漏了 asset/market。這不是 CONTEXT.md 的錯 —— `<canonical_refs>` 已把兩個端點列為「Integration Points」,只是沒把「需要新 adapter」這一步寫出來。

**建議形狀(合併成一個 adapter,不是兩個):**

```typescript
// src/services/marketApi.ts
export interface MarketApi {
  mode: RuntimeDataMode;
  /** GET /api/v1/assets?query=&page=&size= — ⚠️ 分頁端點（AssetController:24） */
  searchAssets(params: { query: string; page?: number; size?: number }, signal?: AbortSignal)
    : Promise<PaginatedResponse<AssetDto>>;
  /** GET /api/v1/market/{symbol}/klines — 非分頁；OHLCV 為 JSON string */
  listKlines(symbol: string, params: { interval: KlineInterval; from: string; to?: string; limit?: number })
    : Promise<KlineDto[]>;
}
```

**為什麼合併成一個 `marketApi`?** 兩個端點雖在不同後端模組(`stock-module-asset` vs `stock-module-market-data`),但對前端是同一個消費情境(「查標的與它的行情」),而且未來 Markets 頁與 Chart 頁 API 化(PORT-06 v2)會用同一組。分成兩個 adapter 會讓 `RuntimeApiClients` 多兩個欄位卻沒有語意收益。`[ASSUMED]` — 這是我的判斷,不是既有慣例;planner 可自行決定,但**必須在 plan 裡明確承認「本階段要建兩個(或一個雙端點的)新 adapter」,不能只列 `tradingApi`,否則 plan 的工作量估計會低估**。

**mock 實作怎麼做?** `createMockMarketApi()` 從 `data.ts` 的 `SYMBOLS/CRYPTO/FX` 組出 `AssetDto` 形狀,`listKlines` 用既有 `genSeries()` 組 `KlineDto[]`。**這樣 mock mode 的 OrderTicket 行為完全不變**(D-04 明文「mock mode 四樣全部保留不受影響」),而且 `OrderTicket.vue` 只剩一條資料路徑,不需要 `if (live)` 分支去讀 `data.ts`。這是把 judgment §3 落實得最乾淨的做法。

---

## Common Pitfalls

### Pitfall 1:partial unique index 的 `ON CONFLICT` 推斷失敗

**What goes wrong:** `INSERT ... ON CONFLICT (user_id, idempotency_key) DO NOTHING` 直接報 `ERROR: there is no unique or exclusion constraint matching the ON CONFLICT specification`(SQLSTATE `42P10`)。
**Why:** partial unique index 必須在 `ON CONFLICT` 的 conflict_target 上重述 predicate 才能被推斷(PostgreSQL 官方文件明文,見 Q1.5)。
**How to avoid:** SQL 寫 `on conflict (user_id, idempotency_key) where idempotency_key is not null do nothing`,**且 `WHERE` 必須與 V10 的索引 predicate 逐字一致**。
**Warning signs:** IT 的第一個「同 key 兩次」測試就會炸,錯誤訊息含 `42P10` 或 `matching the ON CONFLICT specification`。**好消息:這個錯誤不會靜默,一定在第一次 IT 就抓到。**

### Pitfall 2:`.single()` 在 `DO NOTHING` 零列時拋例外

**What goes wrong:** `EmptyResultDataAccessException`(`IncorrectResultSizeDataAccessException` 家族)→ 落 catch-all → **500**。
**Why:** 現有 `insertTransaction` 結尾是 `.single()`(`JdbcTradingRepository.java:69`);`ON CONFLICT DO NOTHING` 衝突時 `RETURNING` 回零列(PostgreSQL 官方文件:「Only rows that were successfully inserted or updated will be returned」)。
**How to avoid:** 改成 `.optional()`,照 `insertHoldingIfAbsent`(`:103`)。
**Warning signs:** 併發 IT 出現 500 而不是 200。

### Pitfall 3:`BigDecimal.equals` 讓每次合法重試都吃 409

**What goes wrong:** 使用者 timeout 重試,送逐位元相同的 payload,卻拿到 409 `TRADE_IDEMPOTENCY_KEY_REUSED`。
**Why:** request 的 `quantity: 10`(scale 0)vs DB 讀回 `10.00000000`(scale 8),`equals` 為 false。`TradingApiIT` 現有的所有 request body 都是整數字面(`:413-416`),**每一個**都會踩到。
**How to avoid:** `compareTo(...) == 0`,包成 `sameAmount` 純函式(Q4)。
**Warning signs:** 「同 key 同 payload 應回既有交易」的 IT 紅燈,回 409 而非 200。

### Pitfall 4:`OffsetDateTime.equals` 讓每次合法重試都吃 409(第二個獨立來源)

**What goes wrong:** 同上,但即使修好了 `BigDecimal` 還是紅。
**Why:** 兩個獨立的 offset 變異來源 —— (a) `spring.jackson.time-zone: Asia/Taipei`(`application.yaml:6-7`)+ `ADJUST_DATES_TO_CONTEXT_TIME_ZONE` 預設 true 會把請求的 offset 調成 `+08:00`;(b) PgJDBC 讀回 `timestamptz` 的 offset 由驅動決定。
**How to avoid:** `toInstant().truncatedTo(MICROS)` 比較;更好的是**寫入前就把 `executedAt` 正規化到微秒**(Q4)。
**Warning signs:** 修完 `BigDecimal` 之後同一個 IT 還是紅,而且 diff 出來的 `executedAt` 看起來「一樣」(同一時刻,不同 offset 字面)。**這個症狀最容易被誤判為「已經一樣了怎麼還不過」而浪費大量時間。**

### Pitfall 5:`ON CONFLICT DO UPDATE` 撞上 V8 append-only trigger

**What goes wrong:** `RAISE EXCEPTION 'transactions is append-only: UPDATE is not permitted'`(SQLSTATE `restrict_violation`)。
**Why:** V8 有 `BEFORE UPDATE ... FOR EACH ROW` trigger(`:16-18`);`DO UPDATE` 執行的就是 UPDATE。
**How to avoid:** **只用 `DO NOTHING`。** 這個陷阱特別容易踩,因為一般 upsert 教學都示範 `DO UPDATE`。
**Warning signs:** 錯誤訊息含 `transactions is append-only`。

### Pitfall 6:Spring self-invocation 讓 `@Transactional` 靜默失效

**What goes wrong:** 「外層非交易 + 內層交易」的實作完全沒有交易 —— holdings 的改動與 transaction insert 各自 autocommit,衝突時 holdings **不會回滾**,直接資料損毀。
**Why:** Spring 官方文件:「self-invocation ... does not lead to an actual transaction at runtime even if the invoked method is marked with `@Transactional`」。
**How to avoid:** 若採 Q1.7 方案 B,必須拆兩個 bean 或用 `TransactionTemplate`。**若採方案 A(建議),根本不需要兩層方法,這個陷阱自動消失。**
**Warning signs:** 極隱蔽 —— 單筆測試全綠,只有併發或錯誤路徑的 IT 才會露出「holdings 被改了但 transaction 沒建」的不一致。**這是不採方案 B 的最強理由。**

### Pitfall 7:`GET /assets` 用 `apiRequest` 而非 `apiPaginatedRequest`

**What goes wrong:** `isApiSuccess` 通過(有 `data` key),但 `data` 是 `{items,page,size,...}` 而不是 `AssetDto[]` → `filtered.map(...)` 在物件上炸,或 selector 永遠空。
**Why:** `AssetController:24` 回 `ApiResponse<PageResponse<AssetDto>>`。CONTEXT.md 未提及它是分頁端點。
**How to avoid:** 用 `apiPaginatedRequest<AssetDto>`,取 `.items`。
**Warning signs:** typeahead 永遠沒有結果,但 network 顯示 200。

### Pitfall 8:`KlineDto` 的 OHLCV 是 JSON string,`vue-tsc` 抓不到

**What goes wrong:** 走勢圖畫成一條平線或消失。
**Why:** `KlineDto` 五個 BigDecimal 欄位都有 `@JsonSerialize(using = ToStringSerializer.class)` → JSON string。若前端型別宣告成 `number`,TypeScript 編譯期無從得知,執行期 `Math.min(...strings)` 會產生 `NaN`。
**How to avoid:** 型別宣告成 `string`,取值 `Number(k.close)`。**adapter 測試的 fixture 必須用 string**(`{"close": "218.40000000"}`),不是 number —— 否則測試綠燈而 production 壞。
**Warning signs:** 測試全綠但實際畫面走勢圖是平的。**這是「測試 fixture 與真實 payload 不同」的典型案例。**

### Pitfall 9:`develop` 上 klines 的參數錯誤回 500 而非 400

**What goes wrong:** 前端開發時送 `from=2026-01-01`(無 offset),拿到 500 + 後端 ERROR log,誤判「後端掛了」。
**Why:** `from` 型別是 `Instant`(`MarketController.java:100`);`MethodArgumentTypeMismatchException` 不實作 `ErrorResponse`(`javap` 已驗)→ 落 `handleUnexpected` 的 catch-all → 500。**PR #15 修了這個,但 PR #15 未合併。**
**How to avoid:** 前端一律送 `Date.toISOString()`(帶 `Z`);adapter 測試鎖住格式。
**Warning signs:** 500 + `Unexpected exception while handling request` ERROR log。

### Pitfall 10:`fieldsFrom` 空 map 回 `null` 而非 `{}`

**What goes wrong:** `Object.keys(error.fields).length` 在 `null` 上拋 `TypeError`。
**Why:** `apiClient.ts:233` `return entries.length ? Object.fromEntries(entries) : null;` —— 後端送 `"fields": {}` 時前端拿到 `null`。而後端**很多**錯誤都送空 `fields`(所有走 `handleBusiness` 的 `BusinessException`,以及 `MissingRequestHeaderException`)。
**How to avoid:** 一律用 `error.fields?.quantity` / `Object.keys(error.fields ?? {})`。
**Warning signs:** ticket 在收到 409 `TRADE_INSUFFICIENT_HOLDING` 時整個 crash 而不是顯示錯誤。

### Pitfall 11:D-14/D-07 互鎖 —— 400 之後改欄位再送吃 409

**What goes wrong:** 使用者送出 → 400 驗證失敗 → 改數量 → 再送 → **409 `TRADE_IDEMPOTENCY_KEY_REUSED`**,而且他不知道要關掉 ticket 才能繼續。
**Why:** 若採「一張 ticket 一個 key」。CONTEXT.md `<specifics>` 明文說這是**設計時發現的實際互鎖,不是理論風險**。
**How to avoid:** D-14 的兩條規則 —— key 在「按下送出」時產生;使用者改過任何欄位後,下次送出**換新** key。
**Warning signs:** 這條路徑**必須有明確測試**(CONTEXT.md 明文要求):`400 → 改欄位 → 再送 → 應 200 建立成功,不得 409`。
**⚠️ 實作細節:「改過任何欄位」的偵測。** 建議用一個 `dirtySinceSubmit` flag:送出時設 false,任何欄位的 `watch` / `@input` 設 true。送出時 `if (dirtySinceSubmit || !currentKey) currentKey = crypto.randomUUID();`。**不要**用「深比較整個 form 物件」—— 那會讓「改了又改回來」被判定為未變更,而使用者的心智模型是「我動過了」。

### Pitfall 12:mock mode 被 revision counter 觸發網路請求

**What goes wrong:** mock mode 的測試突然嘗試 fetch 相對 URL,在 jsdom + undici 下丟 `Failed to parse URL`。
**Why:** `watch(portfolioRevision, () => { void loadSummary(); })` 忘了 `if (live) return;`。三頁的 `onMounted` 都有這個 guard(`Overview.vue:251-...`、`Positions.vue:369-374`),`watch` 也必須有。
**How to avoid:** 每個 `watch` 第一行 `if (live) return;`。
**Warning signs:** `testSetup.ts:5-7` 的註解已經預告了這個確切的症狀。

### Pitfall 13:modules 級 singleton 跨測試污染

**What goes wrong:** 測試 A bump 了 revision,測試 B 的斷言看到非 0 的初值,或 `lastFill` 殘留。
**Why:** `vite.config.ts:26-27` `pool: 'threads'` + `fileParallelism: false`;`testSetup.ts` **不** reset 任何模組狀態(`:10-12` 明文解釋為何刻意不 import service module)。
**How to avoid:** 在需要的測試檔自行 `afterEach(() => resetPortfolioRevisionForTests())`。**不要**加進 `testSetup.ts`。
**Warning signs:** 單獨跑綠、整檔跑紅(或反之)。

### Pitfall 14:`Idempotency-Key: `(空值)通過 `required = true`

**What goes wrong:** 所有送空 key 的請求互相「冪等命中」,使用者的第二筆不同交易被當成第一筆的重試而回既有交易。**這是靜默資料錯誤,不是錯誤訊息。**
**Why:** `@RequestHeader(required = true)` 只擋 header **不存在**;`Idempotency-Key: ` 會綁成空字串。
**How to avoid:** service 層 `StringUtils.isBlank(key)` → `VALIDATION_FAILED`(Q3.4)。
**Warning signs:** 需要一個**明確的 negative test**(`judgment §11`:安全相關變更至少要有一個 negative test)。這個 pitfall 若不寫測試,永遠不會被發現。

### Pitfall 15:V10 的 unique index 誤寫成非 partial

**What goes wrong:** 看起來能用(既有的 NULL 列因為「NULL 不等於 NULL」也不衝突),但 `ON CONFLICT ... WHERE ...` 無法推斷該索引 → Pitfall 1。
**Why:** 容易以為「反正 NULL 不衝突,partial 只是最佳化」。
**How to avoid:** migration 測試斷言 `pg_indexes.indexdef` **含 `WHERE`**(Q2 的第 2 條)。單純的「兩列 NULL 可共存」測試**抓不到**這個錯。
**Warning signs:** migration 測試綠但應用層 IT 炸 `42P10`。

---

## Runtime State Inventory

> 本階段不是 rename / refactor / 字串替換 phase,但**含一個 schema migration**,所以仍逐項檢查「檔案都改完後,還有哪些執行期狀態不同步」。

| Category | Items Found | Action Required |
|----------|-------------|-----------------|
| **Stored data** | `transactions` 表新增 `idempotency_key` 欄位。既有列**無法回填**(V8 trigger 禁 UPDATE),必然為 NULL → **這正是 partial unique index 存在的理由**,不是缺陷。`holdings` / `assets` / `users` 無需改動。 | **code edit + DDL only** — 明確**不需要** data migration(且技術上不可能) |
| **Live service config** | **None** — 無 n8n / Datadog / Cloudflare / Tailscale 類外部服務。本專案的所有設定都在 `application*.yaml`(git 內)。已 grep `stock-start/src/main/resources/*.yaml` 確認無 idempotency / trading 相關 runtime 設定需改。 | none |
| **OS-registered state** | **None** — 無 Windows Task Scheduler / pm2 / systemd 註冊項。已確認專案以 `mvnw` / `vite` 啟動,無 OS 層註冊。 | none |
| **Secrets / env vars** | **None** — `Idempotency-Key` 是 HTTP header 而非設定;不新增任何 env var 或 secret。前端不需要新的 `VITE_*`。 | none |
| **Build artifacts / installed packages** | **None** — 不新增任何 Maven / npm 依賴(見 Package Legitimacy Audit),所以無 `egg-info` / `node_modules` / Docker image tag 類殘留。**唯一需注意**:`TradeTransaction` record 加第 13 個 component 會讓所有呼叫端需重新編譯 —— `./mvnw test` 自然處理。呼叫端已盤點:`JdbcTradingRepository.java:55, 246`、`TradingService.java:88`(共 3 處,`grep -rn "new TradeTransaction("` 驗證,排除 worktrees)。 | none(重新編譯即可) |
| **⚠️ 額外項:Flyway schema history** | **已套用 V9 的長期環境**(dev / demo 資料庫,若存在)。Phase 4 的 V10 是**新增**,無 checksum 問題。**但 PR #15 修改了已存在的 V9,那個 PR 合併時會在已套用 V9 的環境炸 checksum。** | Phase 4 **無** action;但 DP-1 選 (a)「等 PR #15 合併」時需知道這個成本 |
| **⚠️ 額外項:Redis PortfolioCache** | `PortfolioCache.invalidateAfterTrade`(`:45-52`)已同時刪 holding + summary 兩個 key(CONTEXT.md 查證)。冪等命中的路徑**不建立新交易,所以也不需要失效快取** —— 但呼叫它是無害的。 | **建議在冪等命中的快路徑上「不」呼叫 `invalidateAfterTrade`**:資料沒變,失效只是讓下一次讀重算。這是一個小最佳化,planner 可自行決定 |

**Nothing found in the four standard categories:** 已逐項確認(見上表),不是未檢查。

---

## Validation Architecture

> `.planning/config.json` 的 `workflow.nyquist_validation` 為 **`true`** `[VERIFIED: config.json]` → 本節必要。

### Test Framework

| Property | Value |
|----------|-------|
| **Backend framework** | JUnit 5 + Mockito + AssertJ + Testcontainers;`spring-boot-starter-test`(`stock-module-trading/pom.xml:49-50`) |
| Backend config file | `stock-start/src/test/resources/application-test.yaml`;容器 wiring 在 `stock-start/src/test/java/.../support/ContainerIT.java:12-64` |
| Backend quick run | `./mvnw -pl stock-module-trading -am test`(PowerShell:`.\mvnw.cmd -pl stock-module-trading -am test`) |
| Backend full suite | `./mvnw test` |
| Backend IT | `./mvnw -pl stock-start -am verify` |
| **Frontend framework** | Vitest `^4.1.6` + jsdom(`vite.config.ts:20-28`);setup `src/testSetup.ts` |
| Frontend quick run | `cd ../../vue/stock-v2/vue-app && npx vitest run src/services/tradingApi.test.ts` |
| Frontend full suite | `cd ../../vue/stock-v2/vue-app && npm test && npm run build` |
| Frontend API mode | `VITE_DATA_MODE=api npm test`(PowerShell:`$env:VITE_DATA_MODE='api'; npm test`) |
| **⚠️ Web-layer gap** | `stock-module-trading` **沒有** `@WebMvcTest` 基礎設施 — 見下方 Wave 0 Gaps |

### Success Criterion → Observable Signal → Layer

ROADMAP Phase 4 的 5 條 Success Criteria 逐條對應:

#### SC-1 —「送出的交易明確建立 manual executed buy/sell trade,不傳送 pending order / cancel / TIF 等未支援欄位」

| Req | Behavior | Layer | Automated Command | Signal |
|-----|----------|-------|-------------------|--------|
| TRAD-01 | `tradingApi.createTrade` 打 `POST /api/v1/trades`,body 只有 7 個合約欄位 | Frontend unit | `npx vitest run src/services/tradingApi.test.ts` | stub `fetch`,斷言 `JSON.parse(init.body)` 的 key 集合**恰為** `['symbol','type','quantity','price','fee','note','executedAt']`(⚠️ 用 `toEqual` 比 sorted key 陣列,不是 `toMatchObject` —— 後者不會抓到多送的欄位) |
| TRAD-02 | payload **不含** `ordType` / `tif` / `orderId` / `cashAfter` / `slippage` | Frontend unit | 同上 | 同上的 key 集合斷言即涵蓋。**額外**加一條 `expect(body).not.toHaveProperty('ordType')` 等,讓失敗訊息更明確 |
| TRAD-02 | API mode 的 ticket UI **不顯示** MKT/LMT、DAY/GTC、Cash after | Frontend component | `VITE_DATA_MODE=api npx vitest run src/components/OrderTicket.test.ts` | 掛載後 `expect(document.body.textContent).not.toContain('Cash after')` 等(en locale) |
| TRAD-02 | mock mode 四樣**仍然**顯示(D-04 明文) | Frontend component | `npx vitest run src/components/OrderTicket.test.ts` | 反向斷言 `toContain('Cash after')` |
| TRAD-01 | UI 文案不含 pending / routing / partial fill(judgment §1) | Frontend component | 同上 | `not.toContain('Routing')` / `not.toContain('Avg fill')` |
| TRAD-01 | 真實建立成功後 DB 有一列 | Backend IT | `./mvnw -pl stock-start -am verify -Dit.test=TradingApiIT` | 既有 `buyThenSellUpdatesHoldingsAndPortfolioSummary`(`:55-105`)已覆蓋;**只需加 `Idempotency-Key` header** |

#### SC-2 —「同一使用者以相同 idempotency key retry 或 double-click 時,後端不會重複建立交易或重複更新 holdings」

| Req | Behavior | Layer | Automated Command | Signal |
|-----|----------|-------|-------------------|--------|
| TRAD-03 | 缺 `Idempotency-Key` → 400 `VALIDATION_FAILED` envelope | **Backend IT** | `./mvnw -pl stock-start -am verify -Dit.test=TradingApiIT` | `status().isBadRequest()` + `$.error.code == "VALIDATION_FAILED"`。**必須在 stock-start**:`GlobalExceptionHandler` 在該模組 |
| TRAD-03 | 缺 header 時 `fields` 指出 header 名(若採 Q3.3 建議) | Backend IT | 同上 | `$.error.fields['Idempotency-Key']` 存在 |
| TRAD-03 | **空白** `Idempotency-Key` → 400(Pitfall 14 的 negative test,judgment §11 要求) | Backend unit | `./mvnw -pl stock-module-trading -am test -Dtest=TradingServiceTest` | `assertThatThrownBy(() -> service.createTrade(1L, req, "  "))` → `BusinessException` with `VALIDATION_FAILED` |
| TRAD-03 | key 超長 → 400(不是 DB 例外) | Backend unit | 同上 | 同形 |
| TRAD-03 | **同 key 兩次序列送出 → `transactions` 只有 1 列,holdings 只套用一次,兩次回應的 `data.id` 相同** | **Backend IT** | `./mvnw -pl stock-start -am verify -Dit.test=TradingApiIT` | 兩次 POST 都 200;`GET /portfolio/holdings` 的 `totalQuantity` == 單次的量(**不是** 2×);`GET /trades` 的 `totalElements == 1`;`data.id` 逐字相同 |
| TRAD-03 | **併發 8 條同 key → 全部 200,只有 1 列,無 500** | **Backend IT**(最關鍵) | 同上 | 照 `concurrentFirstBuysMergeWithoutUniqueViolation`(`:149-191`)的 `CountDownLatch`+`ExecutorService` 樣板;斷言 8 個 status 全 200、`totalElements == 1`、8 個 `data.id` 全相同、`totalQuantity` 為單次量。**這條 IT 就是 Q1.8 殘餘風險的直接證明** |
| TRAD-03 | 同 key + **不同 payload** → 409 `TRADE_IDEMPOTENCY_KEY_REUSED` | **Backend IT** | 同上 | `status().isConflict()` + `$.error.code` |
| TRAD-03 | payload 比對:整數 vs scale-8 小數視為相同(Pitfall 3) | **Backend unit** | `./mvnw -pl stock-module-trading -am test` | `sameAmount(new BigDecimal("10"), new BigDecimal("10.00000000"))` 為 true |
| TRAD-03 | payload 比對:同 instant 不同 offset 視為相同(Pitfall 4) | **Backend unit** | 同上 | `sameInstant(OffsetDateTime.parse("2026-01-10T00:00:00Z"), OffsetDateTime.parse("2026-01-10T08:00+08:00"))` 為 true |
| TRAD-03 | payload 比對:`note` 不同**不**觸發 409(Q4 陷阱 4) | Backend IT | IT | 同 key、只改 `note` → 200 回既有交易 |
| TRAD-03 | 冪等命中時**完全不碰 holdings** | Backend unit | `./mvnw -pl stock-module-trading -am test` | mock repository:`verify(repository, never()).findHoldingForUpdate(any(), any())` / `never()).updateHolding(any())`。**這是 judgment §5「不重複更新 holdings」最直接的斷言** |
| TRAD-03 | 不同 user 同一 key **各自**建立(partial index 的 `user_id` 維度) | Backend IT | IT | 兩個 register 出來的 user,同一 key → 兩列 |
| TRAD-03 | 既有的 NULL key 列可共存 | Backend IT | `TransactionsAppendOnlyIT` 樣板或新 migration IT | 直接 SQL 插兩列 `idempotency_key = NULL` → 成功 |
| TRAD-03 | **V10 的 index 是 partial(`indexdef` 含 `WHERE`)**(Pitfall 15) | **Backend IT** | `./mvnw -pl stock-start -am verify` | `select indexdef from pg_indexes where indexname='uk_transactions_user_idempotency'` → 字串含 `WHERE` |
| TRAD-03 | V10 對 V8 append-only trigger 無害 | Backend IT | 同上 | `TransactionsAppendOnlyIT` 的三個既有測試在 V10 之後仍綠(**回歸保護,不需新測試**) |
| TRAD-03 | SELL 導致 oversell 時整個 tx 回滾,**key 未被燒掉**(可改數量重送) | **Backend IT** | IT | 同 key SELL 超量 → 409;改小數量、**換新 key** → 200。⚠️ 若不換 key 會吃 409 `KEY_REUSED`?**不會** —— oversell 時 tx 回滾,那一列從未 commit,所以同 key 也可以重送。**這條需要 IT 明確證明**,因為它是「rollback 是否真的把 insert 也撤掉」的驗收 |

#### SC-3 —「Frontend trade submission 期間會阻擋重複送出,但 server-side idempotency 仍是最終保護」

| Req | Behavior | Layer | Automated Command | Signal |
|-----|----------|-------|-------------------|--------|
| TRAD-04 | 送出中送出鈕 `disabled`(Q6.2:**目前沒有**) | Frontend component | `VITE_DATA_MODE=api npx vitest run src/components/OrderTicket.test.ts` | 用未 resolve 的 promise stub `createTrade`,點送出後 `expect(submitBtn.disabled).toBe(true)` |
| TRAD-04 | 連按兩次只呼叫 `createTrade` **一次** | Frontend component | 同上 | `expect(createTradeSpy).toHaveBeenCalledTimes(1)` |
| TRAD-04 | key 在「按下送出」時產生(D-14 規則 1) | Frontend component | 同上 | stub `crypto.randomUUID` 回遞增值;斷言開 ticket 時**未**呼叫,按送出時才呼叫一次 |
| TRAD-04 | 同次嘗試的手動重試沿用**同一** key(D-14 規則 1) | Frontend component | 同上 | 第一次失敗(網路錯)→ 按重試 → `createTrade` 第 2 次呼叫的 key 與第 1 次**相同** |
| TRAD-04 | **改過欄位後送出換新 key**(D-14 規則 2) | Frontend component | 同上 | 失敗 → 改 quantity → 送出 → key **不同** |
| TRAD-04 | **D-14/D-07 互鎖路徑**(CONTEXT.md 明文要求) | Frontend component | 同上 | stub:第 1 次回 400 `VALIDATION_FAILED` `fields:{quantity:...}` → 改 quantity → 第 2 次 stub 回 200 → 斷言(a) 第 2 次的 key ≠ 第 1 次,(b) 最終狀態是「已記錄」而**不是** 409。**這是 Pitfall 11 的驗收** |
| TRAD-04 | 401 refresh + replay 沿用同一 key(Q5.4) | Frontend unit | `npx vitest run src/services/apiClient.test.ts` 或 `tradingApi.test.ts` | stub `fetch`:第 1 次 401、refresh 200、replay 200;斷言 replay 的 `init.headers` 的 `Idempotency-Key` 與第 1 次相同 |

#### SC-4 —「交易成功後重新讀取 summary / holdings / trades,畫面反映 backend truth」

| Req | Behavior | Layer | Automated Command | Signal |
|-----|----------|-------|-------------------|--------|
| TRAD-05 | `notifyTradeCreated` 提升 revision | Frontend unit | `npx vitest run src/services/portfolioRevision.test.ts` | `portfolioRevision.value` 由 0 → 1;`apiLastFill.value` 為 `{sym,type,qty,px}` |
| TRAD-05 | Overview 在 revision 變化時重跑 `loadSummary` + `loadRecentTrades` | Frontend component | `VITE_DATA_MODE=api npx vitest run src/pages/Overview.test.ts` | 計數 `fetch` 呼叫;`bumpPortfolioRevision()` + `flushAsync()` 後 `/portfolio/summary` 與 `/trades` 各多一次 |
| TRAD-05 | Positions 同上(summary + holdings) | Frontend component | `VITE_DATA_MODE=api npx vitest run src/pages/Positions.test.ts` | 同形 |
| TRAD-05 | Trades 重讀時**保留篩選與排序、頁碼歸零**(D-11) | Frontend component | `VITE_DATA_MODE=api npx vitest run src/pages/Trades.test.ts` | 先選 Sell chip + 切排序 + 翻到第 2 頁 → bump → 斷言新請求的 query string **仍含** `type=SELL` 與該 sort/direction,但 `page=0` |
| TRAD-05 | **mock mode 不因 revision 打任何網路**(Pitfall 12) | Frontend component | `npx vitest run src/pages/Positions.test.ts`(mock 預設) | `vi.stubGlobal('fetch', spy)`;bump 後 `expect(spy).not.toHaveBeenCalled()` |
| TRAD-05 | 未掛載的頁不會被觸發(D-10 的核心論證) | Frontend component | `VITE_DATA_MODE=api` | 只掛 Positions,bump → 只有 holdings/summary 的請求,**沒有** `/trades` |
| TRAD-05 | 新交易不在結果集內 → 顯示 D-11 提示 | Frontend component | Trades test | stub refetch 回不含 `lastCreatedTradeId` 的 items → 斷言提示文案出現;含它 → 不出現 |
| TRAD-05 / D-13 | fresh 高亮在 API mode 生效 | Frontend component | `VITE_DATA_MODE=api` | refetch 後含 `lastFill.sym` 的那列有 `fresh` class。⚠️ Phase 3 有一個測試 `test(positions): 鎖定 API mode 持倉列不帶 lastFill 高亮`(sibling repo commit `587e84e`)—— **這個測試會因 D-13 而必須反轉,planner 必須明確處理它,不能只是刪掉**(judgment §10:「開始想『改測試來遷就實作』→ 幾乎必然方向錯」—— 這裡是**例外**,因為 D-13 是新的使用者決策,測試意圖本身變了。SUMMARY 必須記錄這個反轉與理由) |
| TRAD-05 | 冪等命中時 `PortfolioCache` 仍讓 refetch 讀到正確資料 | Backend IT | `./mvnw -pl stock-start -am verify` | 同 key 第二次之後 `GET /portfolio/summary` 的值仍等於單次交易的結果 |

#### SC-5 —「validation / oversell / permission / CSRF / network 錯誤以使用者可理解方式顯示,保留 backend error code / request id」

| Req | Behavior | Layer | Automated Command | Signal |
|-----|----------|-------|-------------------|--------|
| TRAD-06 | `fields` 的 key 綁對應輸入框(D-16) | Frontend component | `VITE_DATA_MODE=api npx vitest run src/components/OrderTicket.test.ts` | stub 400 + `fields:{quantity:'must be greater...'}` → quantity 輸入框附近出現前端 i18n 文案 |
| TRAD-06 | **`fields` 的英文 value 絕不出現在 DOM**(D-16 硬規則) | Frontend component | 同上 | `expect(document.body.textContent).not.toContain('must be greater than or equal to')`。**這是 D-16 最重要的 negative test** |
| TRAD-06 | 多個 `fields` key 同時綁定 | Frontend component | 同上 | stub `fields:{quantity:..., price:...}` → 兩個輸入框都有錯誤 |
| TRAD-06 | `fields` 為 `null` 時不 crash(Pitfall 10) | Frontend component | 同上 | stub 409 無 `fields` → 底部顯示錯誤,無 exception |
| TRAD-06 | 依 `error.code` 分派到底部:`TRADE_INSUFFICIENT_HOLDING` / `ASSET_NOT_FOUND` / `TRADE_CONFLICT` / `TRADE_IDEMPOTENCY_KEY_REUSED` / `AUTH_CSRF_TOKEN_INVALID` | Frontend component | 同上(參數化) | 每個 code 顯示對應的前端 i18n 文案 + `error.code` 字串 + `requestId` |
| TRAD-06 | 錯誤態才顯示 traceId(Phase 3 D-12) | Frontend component | 同上 | 成功態 `not.toContain(traceId)` |
| TRAD-06 | network 錯誤(fetch reject)有可理解訊息 | Frontend component | 同上 | `vi.stubGlobal('fetch', () => Promise.reject(new TypeError('fail')))` → 顯示 `tradeErrNetwork`,**不顯示** raw Error message |
| TRAD-06 | 401 / refresh 失敗**不**在 ticket 顯示,走 SessionBanner | Frontend component | `VITE_DATA_MODE=api npx vitest run src/App.test.ts` | ticket 內無錯誤;`onRefreshFailed` handler 被呼叫 |
| TRAD-06 | **前端預檢通過但後端拒絕**(judgment §5 的實質驗收) | Frontend component | OrderTicket test | holdings stub 回 `totalQuantity: 100`、送 qty 10(預檢通過)→ `createTrade` stub 回 409 `TRADE_INSUFFICIENT_HOLDING` → 底部顯示錯誤 |
| TRAD-06 / D-12 | **交易成功但 refetch 失敗 → 兩件事分開呈現** | Frontend component | `VITE_DATA_MODE=api`,App 或整合 test | `createTrade` 200 + 後續 `/portfolio/summary` 503 → 斷言(a) ticket 顯示「已記錄」+ trade id,(b) portfolio 區塊進 error+retry 態,(c) **ticket 上沒有任何整體失敗訊息**。**這是 D-12 明文要防的最糟失敗模式** |
| TRAD-06 | oversell 預檢顯示「可賣數量」(D-15) | Frontend component | 同上 | 切到 SELL → 顯示 `sellableQty` 數字;qty 超過 → 顯示預檢錯誤且送出鈕不可按 |
| TRAD-06 | 缺 `TRADE_EXECUTE` 權限 → 403 | Backend IT | `./mvnw -pl stock-start -am verify` | `MethodSecurityDenialIT` 樣板已存在;`Role.USER` 已含該權限,所以需要一個無此權限的角色。⚠️ 若沒有這樣的角色,此條**不可驗證** → 應在 plan 中誠實標為「由 `@PreAuthorize` 註解保證,無獨立測試」,不可假裝覆蓋 |
| TRAD-06 | CSRF header 缺漏 → 403(browser-auth-contract) | Backend IT | `BrowserAuthFlowIT` 樣板已存在 | 已由 Phase 1 覆蓋;Phase 4 只需確認 `POST /trades` 也在 unsafe method 清單內 |
| TRAD-06 | 錯誤訊息不回射 idempotency key(`code-standards.md:82`) | Backend IT | `./mvnw -pl stock-start -am verify` | 送一個可辨識的 key(如 `LEAK-CANARY-12345`)觸發 409 → `expect(response body).not.toContain("LEAK-CANARY")`。**這是避免重蹈 `BackfillController:105` 的 negative test** |

### Sampling Rate

- **Per task commit:** 該 task 對應模組的 focused run
  - 後端 trading:`./mvnw -pl stock-module-trading -am test`
  - 後端 common(ErrorCode):`./mvnw -pl stock-common -am test`
  - 前端:`npx vitest run <改動的 test 檔>`(需 API mode 者加 `VITE_DATA_MODE=api`)
- **Per wave merge:**
  - 後端:`./mvnw test`(全 unit)
  - 前端:`npm test` **且** `VITE_DATA_MODE=api npm test`(judgment §3:兩個 mode 都要跑)
- **Phase gate(交給 `/gsd-verify-work` 前):**
  - `./mvnw test` 綠
  - `./mvnw -pl stock-start -am verify` 綠(**含所有新 IT**)
  - `cd ../../vue/stock-v2/vue-app && npm test && npm run build` 綠
  - `cd ../../vue/stock-v2/vue-app && VITE_DATA_MODE=api npm test` 綠
  - judgment §8 第 2 條:**跨 repo 兩邊都跑過**

### Wave 0 Gaps

實作前必須先建立的測試基礎設施:

- [ ] **`stock-module-trading/src/test/java/dowob/xyz/stockwebv2/trading/TradingTestApplication.java`** — `@SpringBootApplication` 錨點,供 `@WebMvcTest` 找 `@SpringBootConfiguration`。照 `stock-module-market-data/src/test/java/.../MarketDataTestApplication.java`(全檔 15 行,已實讀)。
  **⚠️ 為什麼需要**:`testing-standards.md:33` 要求 web 層用 `@WebMvcTest`,但 `TradingControllerTest` 目前是**純 Mockito 單元測試**(`:32-67` 實讀,無 `@WebMvcTest`、無 MockMvc)。`stock-module-trading` 內**零** `@WebMvcTest`(`grep -rn "@WebMvcTest"` 只在 market-data 命中)。
- [ ] **`stock-module-trading/pom.xml` 加兩個 test-scope 依賴** — `spring-boot-starter-webmvc-test` 與 `spring-boot-starter-security-test`。
  **證據**:market-data 的 pom 有這兩個(`:103-104`, `:108-109`);trading 的 pom **只有** `spring-boot-starter-test`(`:49-50`)。`spring-security-test` 在整個 repo 只出現在 `stock-start/pom.xml:106`。
- [ ] **`TradingControllerTest` 的 `@WebMvcTest` 版本 + `TestExceptionHandler` + `TestSecurityConfig`** — 照 `BackfillControllerTest:56-62` 的 `@Import({AdminOnlySecurityConfig.class, TestExceptionHandler.class})`。
  **⚠️ 重要限制**:`GlobalExceptionHandler` 在 `stock-start`,**不在** `@WebMvcTest` 切片範圍內(`BackfillControllerTest` 的 javadoc 明文說了這件事)。所以**「缺 header → 400 envelope」的真正驗收只能在 `stock-start` 的 IT**;module 層的 `@WebMvcTest` 只能證明「`@RequestHeader` 綁定失敗會拋 `MissingRequestHeaderException`」。**plan 必須把這兩層分清楚,不可把 module 層測試當成 envelope 的證明。**
- [ ] **`src/services/tradingApi.test.ts`**(新檔)— 照 `src/services/opsApi.test.ts`(已存在,`:29`、`:118` 有 idempotencyKey 的斷言樣板)
- [ ] **`src/services/marketApi.test.ts`**(新檔,若採前述的 asset/market adapter 建議)
- [ ] **`src/services/portfolioRevision.test.ts`**(新檔)
- [ ] **`src/components/OrderTicket.test.ts`**(新檔)— ⚠️ 目前**沒有** OrderTicket 的專屬測試檔(`ls src/components/` 未查證有無,但 `src/` 頂層的 `task4~task8.test.ts` 是遺留的分批測試檔)。planner 應建專屬檔而非續加進 `taskN.test.ts`。
- [ ] **`src/api-adapter-wiring.test.ts` 擴充** — 加 `trading`(與 `market`)到 `mockFactoryCalls`(`:16-22`)、`afterEach` 的 reset/doUnmock(`:24-36`)、以及 `:166-220` 的三處斷言。**不是新檔,是改既有檔。**
- [ ] **`src/i18n.ts` 新增 key**(zh + en 兩邊)— 並先確認 `src/i18n.test.ts` 是否有「兩語言 key 集合相同」的斷言(若有,漏一邊會直接紅)
- [ ] **`stock-start` 新增或擴充 migration IT** — V10 的 partial index `indexdef` 含 `WHERE` 斷言。落點:擴充既有 `FoundationMigrationIT`,或新建 `TransactionsIdempotencyIT`(照 `TransactionsAppendOnlyIT` 的 `@Autowired JdbcClient` + 直接 SQL 樣板)

*(若上述皆已就緒:不適用 —— 本階段確實需要新建上述基礎設施,尤其是後端 web 層與前端 OrderTicket 測試檔。)*

### 明確**不能**在本階段證明、屬於 Phase 5 的事

judgment §8 與 ROADMAP Phase 5 明確劃線。以下**不得**在 Phase 4 的 plan 中宣稱覆蓋:

| 項目 | 為什麼不在 Phase 4 | 歸屬 |
|------|------------------|------|
| **真實瀏覽器的完整流程**(login → `/me` → portfolio reads → create trade → refetch → logout) | 需要真實瀏覽器 + 真實 backend 同時跑;Vitest 是 jsdom + stubbed fetch,`MockMvc` 是 servlet 層而非真實 HTTP | **VER-03 / Phase 5** |
| **真實 cookie / CSRF 在瀏覽器中的行為**(SameSite、Secure、HttpOnly 的實際生效) | jsdom 的 `document.cookie` 不實作 HttpOnly;`MockMvc` 不實作瀏覽器 cookie jar | **Phase 5**(`BrowserAuthFlowIT` 已在後端側覆蓋一部分) |
| **前端 → 後端的真實 network call 證據**(judgment §3:「API mode 的功能驗證必須看到真實 network calls / backend logs」) | Phase 4 的前端測試全部 stub `fetch`。**「畫面看起來正常」不算證據**是 judgment §3 的反例 | **Phase 5** |
| **跨 repo 契約在真實 payload 上的一致性**(例如 `KlineDto` 的 string vs number —— Pitfall 8) | Phase 4 的 fixture 是人手寫的,可能與真實 payload 不同 | **Phase 5**;但 Phase 4 應在 fixture 註解裡引用後端的 file:line,降低漂移 |
| **jsdom 之外的 `crypto.randomUUID()` 行為** | 測試會 stub 它 | **Phase 5** |
| 走勢圖的視覺正確性(線畫得對不對) | 元件測試斷言行為不斷言像素 | **人工檢視 / Phase 5** |
| PR #15 的 executedAt 未來時間驗證(若採 DP-1 選項 c) | 不在 Phase 4 範圍 | **PR #15** |

**plan 若在任何 task 的 verification 裡寫「使用者可以完整走完下單流程」,那是 over-claim** —— Phase 4 能證明的是「每一段行為在其對應層級正確」,端到端串接是 Phase 5 的工作。

---

## Q11 — TDD 排序(每個工作區塊的第一個紅燈)

CLAUDE.md 硬約束:先寫失敗測試。逐區塊給出「第一個紅燈是什麼、用哪個指令跑、為什麼它會紅」。

### 後端

| # | 工作區塊 | 第一個失敗測試 | 指令 | 為什麼會紅(RED) |
|---|---------|--------------|------|-----------------|
| B1 | `ErrorCode` 新 409 code | `ErrorCodeTest`(或直接在 `TradingServiceTest` 內)斷言 `ErrorCode.TRADE_IDEMPOTENCY_KEY_REUSED.httpStatus() == 409` | `./mvnw -pl stock-common -am test` | enum 常數不存在 → **編譯失敗**。⚠️ 編譯失敗是合法的 RED(測試無法通過),但 planner 應知這不會產生「測試紅燈」報告而是 build error |
| B2 | payload 比對純函式 | `TradingServiceTest`:`sameAmountTreatsDifferentScaleAsEqual` / `sameInstantTreatsDifferentOffsetAsEqual` | `./mvnw -pl stock-module-trading -am test -Dtest=TradingServiceTest` | 方法不存在 → 編譯失敗。**⚠️ 若這兩個是 private,測試無法直接呼叫。** 建議做成 package-private static(或抽成 `TradePayloadComparator` 小類別),讓純函式可直接單測 —— 這是最容易寫、最快回饋的一批 |
| B3 | `TradeTransaction` 加 `idempotencyKey` | 沿用 B2 的測試 + 一個 record 建構測試 | 同上 | record component 不存在 → 編譯失敗。⚠️ **會連帶讓 3 個既有呼叫端編譯失敗**(`JdbcTradingRepository:55, 246`、`TradingService:88`)。這是 refactor 步驟,建議與 B2 分開成獨立 task |
| B4 | V10 migration | **見「TDD 尷尬處」** | — | — |
| B5 | repository 的 key 查詢與帶 key insert | ⚠️ **無法純單測**(需真實 PostgreSQL 的 `ON CONFLICT` + partial index)。第一個紅燈是 **IT**:`TransactionsIdempotencyIT.sameUserSameKeyInsertedTwiceKeepsOneRow` | `./mvnw -pl stock-start -am verify -Dit.test=TransactionsIdempotencyIT` | 欄位/索引/方法不存在。**這是本階段唯一「單元測試無法先行」的區塊** |
| B6 | `TradingService` 冪等分支(快路徑) | `TradingServiceTest.duplicateKeyReturnsExistingTradeWithoutTouchingHoldings`:mock repository 的 `findByIdempotencyKey` 回既有交易,`verify(repository, never()).findHoldingForUpdate(any(), any())` | `./mvnw -pl stock-module-trading -am test` | 方法簽章不接受 key 參數 → 編譯失敗;之後是 `never()` 斷言失敗。**這條測試就是 judgment §5「不重複更新 holdings」的直接驗收,價值最高** |
| B7 | D-07 的 409 | `TradingServiceTest.sameKeyDifferentPayloadThrowsKeyReused` | 同上 | 不拋 `BusinessException` |
| B8 | key 的 blank / 長度驗證 | `TradingServiceTest.blankIdempotencyKeyIsRejected`(Pitfall 14 的 negative test) | 同上 | 不拋例外 |
| B9 | `TradingController` 的 `@RequestHeader` | `TradingControllerTest`(既有純單元測試)先加一條:`controller.createTrade(req, "key-1", auth, servletRequest)` 把 key 往下傳。**⚠️ 這會讓既有的 `rejectedTradeEmitsFailureAudit`(`:55-66`)編譯失敗** —— 這正是 D-05 明文說的「遷移成本只有更新 `TradingControllerTest`」 | `./mvnw -pl stock-module-trading -am test` | 簽章不符 → 編譯失敗 |
| B10 | 缺 header → 400 **envelope** | `TradingApiIT.createTradeWithoutIdempotencyKeyReturnsBadRequest` | `./mvnw -pl stock-start -am verify -Dit.test=TradingApiIT` | 現況會 200 建立成功(header 還不存在) |
| B11 | 併發同 key | `TradingApiIT.concurrentSameKeyCreatesExactlyOneTrade` | 同上 | 現況會建 8 列 |
| B12 | oversell 回滾不燒 key | `TradingApiIT.rejectedSellDoesNotConsumeIdempotencyKey` | 同上 | — |
| B13 | 錯誤訊息不回射 key | `TradingApiIT.errorResponseDoesNotEchoIdempotencyKey` | 同上 | — |
| B14 | `MissingRequestHeaderException` handler(若採 Q3.3) | `GlobalExceptionHandlerTest`(既有檔) | `./mvnw -pl stock-start -am test` | `fields` 為空 |

### 前端

| # | 工作區塊 | 第一個失敗測試 | 指令 | 為什麼會紅 |
|---|---------|--------------|------|-----------|
| F1 | `portfolioRevision.ts` | `portfolioRevision.test.ts`:`bumpPortfolioRevision` 讓 `portfolioRevision.value` 加 1;`notifyTradeCreated(trade)` 同時設 `apiLastFill` 與 `lastCreatedTradeId` 並 bump | `npx vitest run src/services/portfolioRevision.test.ts` | 模組不存在 → import 失敗。**這是最小、最快的第一步,建議作為 Wave 1 的第一個 task** |
| F2 | `tradingApi.ts` HTTP 實作 | `tradingApi.test.ts`:stub `fetch`,`createTrade(req, 'key-1')` → 斷言 `init.method === 'POST'`、header 含 `Idempotency-Key: key-1`、body 的 key 集合恰為 7 個 | `npx vitest run src/services/tradingApi.test.ts` | 模組不存在 |
| F3 | `tradingApi.ts` mock 實作 | 同檔:`createMockTradingApi().createTrade(...)` 委派 `executeOrder`、oversell 時丟 `ApiClientError` with code `TRADE_INSUFFICIENT_HOLDING`、`live.lastFill` 反映 store | 同上 | 同上 |
| F4 | `marketApi.ts` | `marketApi.test.ts`:`searchAssets` 走 `apiPaginatedRequest`(斷言取到 `.items`);`listKlines` 的 fixture **OHLCV 用 string**,斷言 mapping 後是 `number[]` | `npx vitest run src/services/marketApi.test.ts` | 模組不存在。⚠️ **fixture 必須用 string**,見 Pitfall 8 |
| F5 | `pageApiClients.ts` 註冊 | `api-adapter-wiring.test.ts`:`clients.trading.mode === 'api'` + `mockFactoryCalls.trading` 未被呼叫 | `VITE_DATA_MODE=api npx vitest run src/api-adapter-wiring.test.ts` | `clients.trading` 為 `undefined` → 型別錯誤 + 執行期失敗 |
| F6 | i18n 新 key | `i18n.test.ts`(若有 key 集合一致性斷言)或新測試斷言 `t('zh','tradeRecorded') !== 'tradeRecorded'` | `npx vitest run src/i18n.test.ts` | key 不存在 |
| F7 | OrderTicket:payload 形狀 | `OrderTicket.test.ts`(API mode):填表 → 送出 → 斷言 `createTradeSpy` 收到的 body key 集合 | `VITE_DATA_MODE=api npx vitest run src/components/OrderTicket.test.ts` | 目前會呼叫 mock store 而非 adapter |
| F8 | OrderTicket:duplicate-submit guard | 同檔:連按兩次 → `toHaveBeenCalledTimes(1)`;送出中送出鈕 `disabled === true` | 同上 | ⚠️ 送出鈕目前**沒有** `:disabled`(Q6.2) |
| F9 | OrderTicket:D-14 key 生命週期 | 同檔:三條(產生時機 / 重試沿用 / 改欄位換新) | 同上 | — |
| F10 | **OrderTicket:D-14/D-07 互鎖** | 同檔:400 → 改欄位 → 再送 → 200 且 key 不同 | 同上 | **CONTEXT.md 明文要求的路徑** |
| F11 | OrderTicket:D-16 欄位級錯誤 | 同檔:`fields` key 綁輸入框 + **英文 value 不出現在 DOM** | 同上 | — |
| F12 | OrderTicket:D-04 隱藏 | 同檔(兩個 mode 各一):API mode 不含 MKT/LMT/DAY/GTC/Cash after;mock mode 仍含 | `npx vitest run` 與 `VITE_DATA_MODE=api npx vitest run` | — |
| F13 | OrderTicket:D-15 SELL 預檢 | 同檔:切 SELL 顯示可賣數量;超量時預檢錯誤 + 送出鈕不可按;**預檢通過但後端 409 仍正確顯示** | `VITE_DATA_MODE=api ...` | — |
| F14 | 三頁 watch revision | `Overview/Positions/Trades.test.ts`:bump 後各自的請求數增加;**mock mode 零 fetch** | 兩個 mode 都跑 | — |
| F15 | D-11 Trades 保留篩選排序、頁碼歸零 | `Trades.test.ts`:設 chip + sort + page=2 → bump → 新請求含 `type=SELL` 且 `page=0` | `VITE_DATA_MODE=api ...` | — |
| F16 | D-13 fresh 高亮 | `Positions/Trades.test.ts` | 同上 | ⚠️ **會與 Phase 3 的既有測試 `587e84e test(positions): 鎖定 API mode 持倉列不帶 lastFill 高亮` 直接矛盾** —— 見 SC-4 表格的說明 |
| F17 | D-12 成功 + refetch 失敗分開呈現 | App 或整合測試 | `VITE_DATA_MODE=api ...` | — |

### TDD 尷尬處與誠實替代品

| 區塊 | 為什麼 TDD 尷尬 | 誠實替代品 |
|------|---------------|-----------|
| **V10 Flyway migration** | 無法對「還不存在的 SQL 檔」寫單元測試;Flyway 在 Spring context 啟動時套用,所以「測試先紅」意味著 **context 啟動失敗**而不是斷言失敗 | **先寫 IT,讓它因「欄位不存在」而紅。** 具體:`TransactionsIdempotencyIT` 用 `@Autowired JdbcClient` 查 `information_schema.columns` 斷言欄位存在 → 紅(零列);查 `pg_indexes.indexdef` 斷言含 `WHERE` → 紅;直接 SQL 插兩次同 key → 紅(不拋例外)。**這是真正的 RED,不是「先寫 SQL 再補測試」。** 這三條斷言比「schema 長什麼樣」的斷言更有價值,因為它們驗證的是**約束真的生效** |
| **`ON CONFLICT` 推斷正確性** | 無法在單元層驗證(H2 / 記憶體 DB 不支援 partial index 推斷) | IT 唯一路徑。**如果 planner 想加快回饋迴圈**,可以先寫一個只跑 SQL 的極小 IT(不啟 MockMvc、不註冊 user),類似 `TransactionsAppendOnlyIT` 的形狀 —— 它比 `TradingApiIT` 快得多,適合 RED→GREEN 迭代 |
| **Vue 視覺版面**(D-09 兩態畫面、日期選擇器版面、loading 骨架) | 元件測試斷言 DOM 而非像素;「版面好不好看」不可測 | **斷言行為,不斷言版面。** 具體:斷言「送出中時 x 元素存在且送出鈕 disabled」而非「spinner 在正中間」;斷言「成功後畫面含 trade id 文字」而非「勾勾是綠色 64px」。**版面交給人工檢視**,並在 plan 的 verification 誠實寫「人工檢視」而非假裝有自動化 |
| **走勢圖畫得對不對** | `LineChart` 產生 SVG path,斷言 path 字串是脆弱測試 | 斷言 **mapping**:`klines → number[]` 的轉換函式是純函式,直接單測(`Number("218.40000000") === 218.4`);斷言 `LineChart` 收到的 `data` prop 是預期的 `number[]`。**不斷言 SVG** |
| **併發行為** | 天生非決定性 | `CountDownLatch` + `ExecutorService` 樣板(`TradingApiIT:150-191` 已有前例)讓它盡可能決定性;斷言**不變量**(「只有 1 列」)而非時序。⚠️ 併發測試偶發紅燈時,**不要調高 timeout 就算了** —— judgment §10:「同一個測試修了兩輪還是紅 → 停」 |
| **`crypto.randomUUID()`** | 隨機值無法斷言 | 一律 `vi.stubGlobal('crypto', { randomUUID: () => `key-${++n}` })`。這同時解決了 jsdom 可用性的不確定性 |
| **Q1.8 的殘餘風險**(「零列 ⇒ 可見」) | PostgreSQL 文件沒保證 | 併發 IT 是唯一證據。**若該 IT 偶發性紅,那就是殘餘風險真的發生了 → 改採方案 E(advisory lock)**,不要靠重試遮掉。這條判準應寫進 plan |

### 建議的 Wave 切分(依賴順序)

```
Wave 1（可並行，互不依賴）
  ├─ B1  ErrorCode 新 409 code                          [backend, 極小]
  ├─ B4  V10 migration + 三條行為 IT                     [backend, 中]
  └─ F1  portfolioRevision.ts + 測試                     [frontend, 極小]

Wave 2（依賴 Wave 1）
  ├─ B3  TradeTransaction 加欄位（含 3 個呼叫端修正）      [backend, 小]
  ├─ B2  payload 比對純函式 + 單測                        [backend, 小]
  ├─ F2/F3  tradingApi.ts 三件組 + 測試                   [frontend, 中]
  └─ F4  marketApi.ts（assets + klines）+ 測試            [frontend, 中]

Wave 3（依賴 Wave 2）
  ├─ B5  repository 的 key 查詢 + ON CONFLICT insert      [backend, 中]
  ├─ Wave-0 gap: TradingTestApplication + pom test deps   [backend, 小]
  └─ F5/F6  pageApiClients 註冊 + i18n key                [frontend, 小]

Wave 4（依賴 Wave 3）
  ├─ B6/B7/B8  TradingService 冪等分支 + D-07 + key 驗證   [backend, 大 — 核心]
  ├─ B9  TradingController @RequestHeader（+ 既有測試遷移） [backend, 小]
  └─ B14 MissingRequestHeaderException handler（選用）     [backend, 極小]

Wave 5（依賴 Wave 4）
  └─ B10~B13  TradingApiIT 四組 IT（缺 header / 併發 /     [backend, 大 — 驗收]
              oversell 不燒 key / 不回射 key）

Wave 6（依賴 Wave 2 的前端 + Wave 4 的後端契約確定）
  └─ F7~F13  OrderTicket 重建（payload / guard / D-14 /   [frontend, 最大]
             D-16 / D-04 / D-15）

Wave 7（依賴 Wave 6）
  ├─ F14/F15  三頁 watch + D-11                           [frontend, 中]
  ├─ F16      D-13 fresh 高亮（含既有測試反轉）            [frontend, 小]
  └─ F17      D-12 成功 + refetch 失敗分開呈現             [frontend, 中]
```

**⚠️ Wave 6 的前端可以在 Wave 4/5 完成前開始**(用 stub 的 `createTrade`),因為前端測試全部 stub `fetch`。但**契約必須先定**(payload 形狀、error code 名稱)—— 那在 Wave 1(B1)與 Wave 4 的設計階段確定。planner 若要並行,應在 Wave 1 就把 `TRADE_IDEMPOTENCY_KEY_REUSED` 這個字串鎖定。

---

## Q12 — 跨 repo 協調

### Sibling repo 現況(2026-07-26 實查)

```
cd /d/end/workspace/vue/stock-v2
git status --short --branch  → ## develop...origin/develop        （工作樹乾淨，與 remote 同步）
git branch -a                → * develop
                                docs/governance-thin-router
                                feature/phase-03-portfolio-read
                                remotes/origin/claude/fullstack-review-architecture-q5nvfj
                                remotes/origin/develop
                                remotes/origin/feature/phase-03-portfolio-read
git log --oneline -8         → a03e030 Merge pull request #8 from tommot20077/feature/phase-03-portfolio-read
                                c4abd7e test(trades): 以偽造系統年度鎖定年度 chip 不得寫死 2026
                                ef1fb35 feat(trades): API mode 改走 server-side 篩選/排序/分頁,CSV 匯出涵蓋全頁
                                587e84e test(positions): 鎖定 API mode 持倉列不帶 lastFill 高亮
                                40a4f2b feat(positions): API mode 改走 portfolio service,持倉與彙總一律讀後端欄位
                                309598f feat(overview): API mode 改走 portfolio service,隱藏無後端來源的合成區塊
                                157f7d8 feat(03-02): pageApiClients 註冊 portfolio client 並補齊頁面共用文案
                                4bdb229 feat(03-02): 新增 portfolio service 三件組與後端 DTO 型別
git log --oneline origin/develop..HEAD  → （空）
```

### ⚠️ 必須更正 STATE.md 的前提

**STATE.md 記載「Phase 3 前端 commits 停在 `feature/phase-03-portfolio-read`,未 push」—— 這已不成立。**

證據:
1. `a03e030 Merge pull request #8 from tommot20077/feature/phase-03-portfolio-read` 在 **`develop`** 上。
2. `origin/develop..HEAD` 為空 → 本地 develop 與 remote **完全同步**。
3. `remotes/origin/feature/phase-03-portfolio-read` 存在 → 該 branch **已 push**(STATE.md 說未 push 也不成立)。
4. 工作樹乾淨(`git status --short` 無輸出)。

**結論:Phase 3 的前端工作已完整 merge 進 sibling repo 的 `develop`。**

### Phase 4 前端的分支建議

| 問題 | 答案 |
|------|------|
| 從哪個 branch 開? | **`develop`**(不是 `feature/phase-03-portfolio-read`)。Phase 3 的所有 commit 都已在 develop 上 |
| Phase 3 的前端成果是否可用? | **是,全部可用** — `portfolioApi.ts`、`pageApiClients.ts`、`Overview/Positions/Trades.vue` 的 API mode 路徑、`describeError`/`BlockError` 樣板、`api-adapter-wiring.test.ts`、`applyQueryChange` 全在 develop 上 |
| 有無未 merge 的前置依賴? | **無** |
| 需要注意的 branch | `docs/governance-thin-router`(本地,未 push,與 Phase 4 無關)、`origin/claude/fullstack-review-architecture-q5nvfj`(remote,內容未查證,`[ASSUMED]` 與 Phase 4 無關) |

**建議 branch 名**:`.planning/config.json` 的 `git.branching_strategy` 是 **`"none"`**,`phase_branch_template` 是 `gsd/phase-{phase}-{slug}`。既有慣例(後端 repo 用 `docs/`、`fix/`、`feature/` 前綴;sibling repo 用 `feature/phase-03-portfolio-read`)顯示 sibling repo 用 `feature/phase-NN-slug`。**建議 `feature/phase-04-manual-trade-creation`**,與 Phase 3 的命名一致。

### 後端 repo 現況

```
Current branch: docs/lessons-verification-traps  （= develop + 一個純文件 commit）
Recent: 3ea000e docs(lessons): 記錄 GSD phase.insert 與 state.add-roadmap-evolution 兩個工具缺陷
        263e072 docs(todos): 四條缺口排入 Phase 04.1,並更正資產分類的後端現況
        3068367 docs(roadmap): 插入 Phase 04.1 後端資料缺口補齊
Untracked: .planning/phases/04.1-backend-data-gap-backfill/
```

**建議**:後端從 `develop` 開新 branch(不要在 `docs/lessons-verification-traps` 上疊實作 commit —— 那個 branch 的語意是純文件)。若採 DP-1 的 (a)「等 PR #15」,則從 PR #15 合併後的 `develop` 開。

### judgment §8 的驗證清單(Phase 4 收尾必跑)

```bash
# Backend
./mvnw test                             # 全 unit
./mvnw -pl stock-start -am verify       # 全 IT（含新的冪等 IT）

# Frontend（cd 到 sibling repo）
cd ../../vue/stock-v2/vue-app
npm test                                # mock mode
npm run build                           # vue-tsc --noEmit && vite build
VITE_DATA_MODE=api npm test             # API mode（judgment §3 要求）
```

PowerShell 版本:
```powershell
.\mvnw.cmd test
.\mvnw.cmd -pl stock-start -am verify
cd ..\..\vue\stock-v2\vue-app
npm test
npm run build
$env:VITE_DATA_MODE='api'; npm test; Remove-Item Env:\VITE_DATA_MODE
```

**⚠️ 一個容易漏的點**:`npm run build` 是 `vue-tsc --noEmit && vite build`(`package.json` scripts)。新增 `RuntimeApiClients.trading` 欄位、`AssetDto` / `KlineDto` 型別、`TradingApi` interface 都會被 `vue-tsc` 檢查。**型別錯誤只在 `npm run build` 出現,`npm test` 不會抓到**(Vitest 用 esbuild transpile,不做型別檢查)。所以「`npm test` 綠」不代表「型別正確」。

---

## 行號漂移報告

CONTEXT.md 引用的所有行號逐條核對結果:

### 後端(全部準確)

| CONTEXT.md 引用 | 判定 | 實際 |
|----------------|------|------|
| `TradingService.java:61-104` `createTrade` | ✅ | `@Transactional` 在 `:60`,方法 `:61-104` |
| `TradingService:68` executedAt 預設 now | ✅ | `:68` `Objects.requireNonNullElseGet(request.executedAt(), OffsetDateTime::now)` |
| `TradingService:78-84` insertHoldingIfAbsent 重讀路徑 | ✅ | `:78-84` |
| `TradingService.resolveTradeableAsset:237` | ✅ | `:237-243` |
| `TradingController.java:36-53` `POST /trades` | ✅ | `:36-53` |
| `CreateTradeRequest.java` 7 欄位 | ✅ | `:18-28`,7 個 record component |
| `JdbcTradingRepository.java:35-70` `insertTransaction` | ✅ | `:35-70`,確為 `insert ... returning` |
| `HoldingCalculator` `applyBuy`/`applySell` | ✅ | `:13-40` / `:42-62`;oversell 在 `:46-48` |
| `ErrorCode.java:40-44` TRADE_* codes | ✅ | 5 個,逐字符合 |
| `GlobalExceptionHandler.java:56-64` 欄位級錯誤 | ✅ | `:56-64` `handleValidation` |
| `BackfillController:90` `required = false` | ✅ | `:90` `@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey` |
| `AssetDto.java:9-24` | ✅ | `:9-24`,14 個 component,含 CONTEXT.md 列的全部欄位 |
| `MarketController.java:96` klines | ✅ | `:96` `@GetMapping("/{symbol}/klines")` |
| `PortfolioCache.java:45-52` `invalidateAfterTrade` | **UNVERIFIED** | 未讀該檔(CONTEXT.md 已查證,本研究採信;不影響本階段結論,因為 D-10 不改快取邏輯) |
| `Role.java:12-17` `Role.USER` 含 `TRADE_EXECUTE` | **UNVERIFIED** | 未讀該檔(CONTEXT.md 已查證,本研究採信) |
| V7/V8/V9 檔名 | ✅ | 三檔皆存在,V8 trigger 逐字符合 CONTEXT.md 描述 |

### 前端

| CONTEXT.md 引用 | 判定 | 實際 |
|----------------|------|------|
| `OrderTicket.vue:200, 209` symbol 來源 | ✅ | 逐字符合 |
| `OrderTicket.vue:256-258` genSeries | ✅ | 逐字符合 |
| `OrderTicket.vue:253` fee 公式 | ✅ | 逐字符合 |
| `OrderTicket.vue:254` cashAfter 124_580 | ✅ | 逐字符合 |
| `OrderTicket.vue:373-374` slippage | ✅ | 逐字符合 |
| `OrderTicket.vue:375` 亂數 orderId | ✅ | 逐字符合 |
| `OrderTicket.vue:202` mock store import | ✅ | 逐字符合(`:201` 是 notifications) |
| `OrderTicket.vue:242, 357-367` routing/match | ✅ | 逐字符合 |
| `OrderTicket.vue:80, 339-341` MKT price lock | ✅ | 逐字符合 |
| `OrderTicket.vue:358` `placing` ref | ⚠️ **輕微** | 宣告在 `:240`;`:358` 是使用處(函式內 guard) |
| `OrderTicket.vue:181` disabled button | ⚠️ **重要語意** | `:181` 是 **Review** 鈕的 `:disabled="!canSubmit"`;真正的送出鈕在 `:185` 且**無 `:disabled`**(Q6.2) |
| `App.vue:36` `v-if="page === 'overview'"` | ✅ | 逐字符合 |
| `App.vue:52-56` OrderTicket | ⚠️ **輕微** | 實際是 **`:52-59`**(8 行) |
| `App.vue:104` `ticketOpen` | ✅ | `const ticketOpen = ref(false);` |
| `App.vue:164` | ✅ | 在 `openTicket`(`:162-165`)內 |
| `App.vue:170` `onTicketNav` | ✅ | 逐字符合 |
| `Overview.vue:232, 241` | ✅ | `loadSummary` / `loadRecentTrades` |
| `Positions.vue:351, 360` | ✅ | `loadSummary` / `loadHoldings` |
| `Positions.vue:264` TODO 註解 | ✅ | 逐字符合 |
| `Trades.vue:255` `loadTrades` | ✅ | 逐字符合 |
| `Trades.vue:109` TODO 註解 | ✅ | 逐字符合 |
| `portfolioApi.ts` 三件組 + `live` 契約 | ✅ | `:47-54`(interface)、`:140-178`/`:180-198`/`:200-202`(三件組)、`:42-46`(live 契約註解) |
| `pageApiClients.ts` `RuntimeApiClients` | ✅ | `:9-17` |
| `apiClient.ts` `ApiClientError` 含 `requestId`/`fields` | ✅ | `:3-30`;`requestId` 來自 `meta.traceId`(`:112-117`) |
| `testSetup.ts` 預設鎖 mock | ✅ | `:13-15` `vi.stubEnv('VITE_DATA_MODE', 'mock')` |

**統計:31 條可驗證引用中,28 條逐字準確、2 條輕微漂移(±3 行 / 宣告 vs 使用處)、1 條有重要語意含意(`:181`)、2 條未驗證(採信 CONTEXT.md)。CONTEXT.md 的 code archaeology 可信度高。**

### CONTEXT.md 的三處**遺漏**(非錯誤,但 planner 必須知道)

1. **`GET /api/v1/assets` 是分頁端點** —— CONTEXT.md 未提;必須用 `apiPaginatedRequest`(Q6.4)
2. **前端完全沒有 asset / market adapter** —— 需新建第二個 adapter,不只 `tradingApi`(見專節)
3. **`fields` 的 key 清單漏了 `type`** —— CONTEXT.md 列 `symbol/quantity/price/fee/note`,實際還有 `type`(Q8.3)

---

## 給 planner 的決策點

| # | 決策 | 我的建議 | 理由 | 需要問 Yuan? |
|---|------|---------|------|-------------|
| **DP-1** | PR #15 的處理:(a) 等它合併 / (b) 自己補 executedAt 驗證 / (c) 以 develop 為基準但範圍收斂成「只做冪等」 | **(c),但若 Yuan 能在近期推進 PR #15 到 merge 則優先 (a)** | (b) 會產生兩份未來時間驗證 + 兩個 time parser,直接踩 judgment §6。(c) 零重複實作,衝突面已量化為「`TradingService` 的一個 hunk + `GlobalExceptionHandler` 的一個位置」,兩者都有具體緩解措施。(a) 最乾淨但 PR #15 是 draft 且帶 V9 checksum 債 | **✅ 要問** — 這牽涉「一個 draft PR 的合併時程」,是 Yuan 的排程決定,不是技術決定。judgment §9 未直接涵蓋,但 model-dispatch 精神是「時程類決定歸 Yuan」 |
| **DP-2** | 冪等的交易機制:方案 A(insert-first + `ON CONFLICT DO NOTHING`)/ B(外層+內層兩個 bean)/ E(advisory lock) | **A**,並在「零列後重讀落空」時回 `TRADE_CONFLICT` 而非 500 | A 是單一交易、零 proxy 陷阱、零 ERROR log 噪音、程式碼最短。B 有 self-invocation 靜默失效的高風險(Pitfall 6),而且那種失效**只在錯誤路徑露出**。E 是零殘餘風險的備案,若 A 的併發 IT 偶發紅就切換 | **⚠️ 應知會** — A 的順序(insert 在 holdings **之前**)**與 CONTEXT.md `<code_context>` 給的步驟順序不同**(CONTEXT.md 是 holdings → insert → 衝突處理)。兩者滿足同一組不變量,但 planner 不應在不知情下偏離 CONTEXT.md 的指示。建議在 plan 中明確記錄這個偏離與理由,並在 SUMMARY 交代 |
| **DP-3** | 是否加 `MissingRequestHeaderException` handler | **加**,但插在 `handleValidation` **之前**(避開 PR #15 的 hunk) | 不加也能得到 400 `VALIDATION_FAILED`(Q3.2 已驗證),但 `fields` 空、message 通用,前端無法區分「缺 header」與「body 欄位錯」。PR #15 為完全相同的理由加了 `handleTypeMismatch` —— 有前例 | 否 |
| **DP-4** | `idempotency_key` 的 `VARCHAR(n)` 長度 | **128** | UUID 是 36,但 D-05 允許非瀏覽器 client 自產(可能是 `prefix-uuid` / base64 / ULID)。36 會讓合法 client 在 DB 層炸,而那個例外正好落在冪等的 catch 範圍內 → 誤判。`TEXT` 無界則是 DoS 面 + B-tree entry 上限 | 否(`[ASSUMED]`,planner 可調,但必須 > 36 且有上限) |
| **DP-5** | asset / market adapter:合併成一個 `marketApi.ts` / 拆成 `assetApi.ts` + `marketApi.ts` / 塞進 `tradingApi.ts` | **合併成一個 `marketApi.ts`** | 對前端是同一消費情境;未來 Markets/Chart API 化(PORT-06 v2)會用同一組。**絕不塞進 `tradingApi.ts`** —— VER-02 把 portfolio 與 trading 列為不同 adapter 的邏輯同樣適用 | ⚠️ **應知會** — CONTEXT.md 只寫「註冊 `trading`」,漏了這個。這是**工作量估計的實質變動**,plan 必須明列 |
| **DP-6** | `note` 是否納入 D-07 payload 比對 | **不納入** | D-07 明列的 6 欄本身就沒有 `note`。理由:`note` 不進任何計算,納入會讓「timeout 重試前順手改了備註」的使用者吃到無法理解的 409 | 否(D-07 字面已決定) |
| **DP-7** | `executedAt` 是否在寫入前正規化到微秒 | **正規化**(`truncatedTo(MICROS)`) | 成本一行。不做的話,奈秒精度的 client 會因 PostgreSQL 的四捨五入而永遠 payload 不符(Q4 的殘餘限制)。正規化後讀回值與比對基準逐位元一致 | 否 |
| **DP-8** | 冪等命中的快路徑是否呼叫 `PortfolioCache.invalidateAfterTrade` | **不呼叫** | 資料沒變,失效只是讓下次讀重算。無害但無益 | 否 |
| **DP-9** | ticket 的 `review` 步驟是否保留 | **planner 裁量** | D-09 收斂的是「**送出流程**」的 routing/match 假進度,不是 ticket → review 這一步。保留 review 對「不可撤銷的帳本寫入」是合理的確認關卡 | 否(Discretion 明文:「元件拆分…依現有 UI 慣例決定」) |
| **DP-10** | D-13 與 Phase 3 既有測試 `587e84e test(positions): 鎖定 API mode 持倉列不帶 lastFill 高亮` 的矛盾 | **明確反轉該測試,並在 SUMMARY 記錄理由** | 這是 judgment §10「改測試遷就實作」的**合法例外** —— D-13 是新的使用者決策,測試意圖本身變了,不是實作遷就。但必須**明確處理**(改寫斷言 + 更新測試名 + SUMMARY 交代),**不可默默刪掉** | ⚠️ **應知會** — 改動非自己產生的測試,judgment §9 第 2 條(「刪除或覆寫非自己產生的檔案」)邊緣;建議 plan 明列此 task |
| **DP-11** | 「改過任何欄位」(D-14 規則 2)的偵測方式 | **`dirtySinceSubmit` boolean flag**,任何欄位 `@input`/`watch` 設 true,送出時設 false | 不要深比較 form 物件 —— 「改了又改回來」會被判未變更,但使用者的心智模型是「我動過了」,他期待新 key | 否 |
| **DP-12** | typeahead 的競態處理 | **debounce 250-300ms + 只採最後一次結果**(遞增 request id 或 `AbortController`) | debounce 單獨不夠 —— 慢網路下兩個 debounce 後的請求仍可能亂序返回,`AAP` 的結果覆蓋 `AAPL` 的 | 否 |
| **DP-13** | 是否為 `stock-module-trading` 建立 `@WebMvcTest` 基礎設施(TestApplication + 2 個 pom test 依賴) | **建立** | `testing-standards.md:33` 明文要求 web 層用 `@WebMvcTest`;現況 `TradingControllerTest` 是純 Mockito(不符規範)。**但要清楚它的極限**:`GlobalExceptionHandler` 在 stock-start,envelope 的真正驗收只能在 IT | 否(規範已要求) |
| **DP-14** | 新 error code 的最終命名 | **`TRADE_IDEMPOTENCY_KEY_REUSED`**(D-07 的建議名) | 語意精確、與 `DUPLICATE_RESOURCE` / `BACKFILL_ALREADY_RUNNING` / `TRADE_CONFLICT` 三者都區分得開(Q3.5 的對照表)。Discretion 允許微調,但沒有更好的候選 | 否(Discretion 已授權) |

**必須問 Yuan 的只有 DP-1。** DP-2 / DP-5 / DP-10 是「應知會 + plan 明列」,不是「停下來問」—— 它們都在 Discretion 範圍或有明確規範依據,但都偏離了 CONTEXT.md 的字面,所以必須讓 Yuan 在 plan review 時看得到。

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Java + Maven wrapper | 後端全部 | ✓ | `mvnw` / `mvnw.cmd` 在 repo 根;Spring Framework **7.0.8**(`~/.m2` jar 實查) | — |
| **Docker** | 所有 IT(Testcontainers) | **UNVERIFIED** | — | **無 fallback** — 冪等的核心驗收(併發、`ON CONFLICT` 推斷、partial index)**必須**真實 PostgreSQL。H2/記憶體 DB 不支援 partial index 的 `ON CONFLICT` 推斷 → 測了等於沒測。**planner 必須在第一個 task 前確認 `docker info` 可用** |
| PostgreSQL 16(via TimescaleDB image) | IT | ✓(映像已在 `ContainerIT:16` 指定) | `timescale/timescaledb:2.17.2-pg16` | — |
| Redis 7.4 | IT(`PortfolioCache`) | ✓(`ContainerIT:23`) | `redis:7.4-alpine` | — |
| Kafka | IT(共用容器) | ✓(`ContainerIT:26-27`) | `confluentinc/cp-kafka:7.6.0` | 本階段不需要,但 `ContainerIT` 會啟它 → IT 啟動成本較高 |
| Node + npm | 前端全部 | **UNVERIFIED**(未實跑 `node --version`) | Vitest `^4.1.6`、Vue `^3.5.34`(`package.json` 實讀) | 無 fallback |
| `crypto.randomUUID()` in jsdom | 前端測試 | **UNVERIFIED** | — | ✓ 測試一律 stub(見 Standard Stack 註記);production(瀏覽器)必有 |
| `gh` CLI | Q0 的 PR 查詢 | ✓(本研究實跑 `gh pr diff 15`) | — | — |
| **`gsd-tools`** | GSD 的 research-plan / package-legitimacy / commit seam | **✗** | `gsd-tools: command not found`(本 session 實跑) | 已用直接工具替代(`javap`、`WebFetch` 官方文件、`git`、`gh`)。**影響**:package legitimacy gate 無法自動跑(但本階段零新增依賴,見 Package Legitimacy Audit);RESEARCH.md 的 git commit 需人工執行 |

**Missing dependencies with no fallback:**
- **Docker** — 若不可用,**Phase 4 的核心驗收(TRAD-03)完全無法證明**。這是 plan 的第一個 blocker,必須在 Wave 1 之前確認。

**Missing dependencies with fallback:**
- `gsd-tools` — 已用直接工具替代;`commit_docs: true` 需人工 `git add`/`git commit`
- jsdom 的 `crypto.randomUUID` — 測試 stub

---

## Security Domain

`.planning/config.json` 無 `security_enforcement` 鍵 → **視為啟用**。

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control(本專案的具體實作) |
|---------------|---------|-----------------------------------|
| **V2 Authentication** | yes | 既有 —— cookie(HttpOnly)/ bearer 雙路徑(`browser-auth-contract.md`);`TradingController.authenticatedUserId(:93-102)` 從 `Authentication` 取 userId,**不信任任何請求欄位**。⚠️ **Phase 4 不得改動任何認證語意**(judgment §9 第 1 條) |
| **V3 Session Management** | yes | 既有 —— 單飛 refresh + 一次 replay(`apiClient.ts:271-297, 299-326`);token 不落 JS(judgment §2)。**Phase 4 的新發現**:401 replay 會沿用同一 `Idempotency-Key`(Q5.4),這是**正確且必要**的 —— 若 replay 換 key,refresh 前那次可能已成功的交易會被重複建立 |
| **V4 Access Control** | yes | `@PreAuthorize("hasAuthority('TRADE_EXECUTE')")`(`TradingController:37`);`AccessDeniedException` 必須 re-throw(`GlobalExceptionHandler:35-38`,`security.md §7`)。**ownership**:`createTrade` 的 `userId` 一律來自 `Authentication`,`idempotency_key` 的唯一約束是 `(user_id, key)` —— **user_id 維度是必要的隔離**,少了它 A 使用者的 key 會命中 B 使用者的交易(**跨使用者資料洩漏**)。⚠️ **必須有 IT 證明「不同 user 同一 key 各自建立」**(已列在 SC-2) |
| **V5 Input Validation** | yes | Bean Validation(`CreateTradeRequest:19-27`,11 個註解)+ service 層白名單(`TradeType.fromApiValue`)+ **新增:`Idempotency-Key` 的 blank/長度/字元集驗證**(Q3.4)。`Digits(integer=10, fraction=8)` / `DecimalMax` 的上限是 2026-07-17 裁決,防 `NUMERIC(24,8)` overflow(`CreateTradeRequest:13-17` javadoc) |
| **V6 Cryptography** | yes(小面積) | `crypto.randomUUID()` 是 Web Crypto(CSPRNG)。**絕不用 `Math.random()`** —— 它不是密碼學隨機,碰撞會導致**別人的交易被當成你的重試回傳**(跨使用者資料洩漏)。⚠️ 但 `(user_id, key)` 的 user_id 維度使碰撞的影響限縮在同一使用者內 —— **這是 D-08 選擇 `(user_id, key)` 而非單獨 `key` 的一個隱含安全好處** |
| **V7 Error Handling & Logging** | yes | `GlobalExceptionHandler` 統一 envelope;`code-standards.md:79-84` 的訊息安全規則。**⚠️ Phase 4 的具體要求**:錯誤訊息**不得回射 idempotency key**(使用者可控字串)。**反例在 repo 內**:`BackfillController:105-106` 把 key 串進訊息 —— 不要照抄。**已列為 IT**(SC-5 的最後一條) |
| **V8 Data Protection** | yes | `transactions` append-only(V8 trigger,`security.md §11`)。`idempotency_key` 永久保留(D-08)—— **它是使用者可控字串且永久存進帳本**,所以字元集/長度驗證不只是穩定性問題也是資料衛生問題 |
| **V13 API & Web Service** | yes | CSRF:`apiClient.ts:220-223` 對所有 unsafe method 自動注入 `X-XSRF-TOKEN`(`browser-auth-contract.md`)。`POST /trades` 是 unsafe → 自動涵蓋(`UNSAFE_METHODS` 含 `POST`,`:60`) |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation(本階段的具體落點) |
|---------|--------|--------------------------------------|
| **重複提交造成重複帳本寫入** | **Tampering / Repudiation** | **本階段的核心工作**:`(user_id, idempotency_key)` partial unique index(V10)。judgment §5 明文要求「唯一約束」 |
| **跨使用者 idempotency key 碰撞** | **Information Disclosure** | 唯一約束的 **`user_id` 維度**。若誤寫成單欄 `unique(idempotency_key)`,A 的 key 會命中 B 的交易並把 B 的 `TradeDto` 回給 A。**IT 必須覆蓋「不同 user 同一 key」** |
| SQL injection | Tampering | `JdbcClient` 具名參數;排序片段來自 enum 白名單常數(`JdbcTradingRepository` class javadoc,PR #15 版本明文)。`idempotency_key` 一律走 `.param(...)`,**絕不串接** |
| CSRF(未經授權的交易建立) | Tampering / Spoofing | `X-XSRF-TOKEN`,`apiClient.ts:220-223` 自動;後端 `SecurityFilterChain`(Phase 1) |
| 越權建立他人交易 | Elevation of Privilege | `userId` 一律來自 `Authentication`(`TradingController:93-102`);`CreateTradeRequest` **沒有** userId 欄位 —— 這是正確的設計,不可新增 |
| oversell(超賣) | Tampering | `HoldingCalculator.applySell:46-48`(後端權威);D-15 的前端預檢**只是 UX** |
| 錯誤訊息洩漏使用者可控字串 | Information Disclosure | `code-standards.md:79-84`;**IT 用 canary key 驗證**(SC-5) |
| 帳本篡改 / 刪除 | Tampering / Repudiation | V8 append-only trigger(三個 trigger);`TransactionsAppendOnlyIT` 三個測試在 V10 之後必須仍綠(回歸保護) |
| 過長 idempotency key 造成 DB 層錯誤 / 索引膨脹 | Denial of Service | `VARCHAR(128)` 上限 + 應用層長度驗證(Q3.4)。**兩層都要** —— 只靠 DB 會讓 `DataIntegrityViolationException` 落進冪等的 catch 範圍而誤判 |
| 未經速率限制的 typeahead 請求 | Denial of Service | debounce + 只採最後結果(DP-12);`GET /assets` 是公開端點,`[ASSUMED]` production 的全域 rate limit 行為未查證。**若 planner 擔心,可在 plan 中提出「`/assets` 是否需要 rate limit」給 Phase 04.1 或 v2** |
| Redis 不可用時降級放行 | Elevation of Privilege | **禁止**(judgment §2:fail-closed,503 `AUTH_REDIS_UNAVAILABLE`)。本階段不改認證邏輯,但 `PortfolioCache` 的 Redis 不可用時**不可**讓交易建立失敗(它只是快取失效)。`[ASSUMED]` — `PortfolioCache.invalidateAfterTrade` 在 Redis 不可用時的行為未查證。**建議 planner 讀一次該檔**,確認它不會讓一筆已成功的交易因快取失效失敗而回滾 |

### Negative tests required(judgment §11:安全相關變更至少要有一個 negative test)

- [ ] 缺 `Idempotency-Key` → 400(不是靜默通過)
- [ ] **空白** `Idempotency-Key` → 400(Pitfall 14 —— 不寫這條永遠不會被發現)
- [ ] 過長 `Idempotency-Key` → 400(不是 500)
- [ ] **不同 user 同一 key → 各自建立**(跨使用者隔離)
- [ ] 錯誤回應**不含** canary key 字串
- [ ] `fields` 的英文 value **不出現在** DOM(D-16)
- [ ] 前端預檢通過但後端 409 → 仍正確拒絕(judgment §5)
- [ ] V10 之後 `transactions` 的 UPDATE / DELETE 仍被擋(V8 回歸)

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| **A1** | `ON CONFLICT DO NOTHING` 回零列後,衝突列在同一 READ COMMITTED 交易中可見(PostgreSQL 會等併發 xid) | **Q1.8** | **最高風險項。** 若不成立,併發同 key 會有一方重讀不到 → 回 409 `TRADE_CONFLICT`(**不是 500**,因為已設退路)。使用者體驗是「偶發需要重試」而非資料錯誤。**緩解:併發 IT 是直接證明;若偶發紅則切換到方案 E(advisory lock)** |
| **A2** | PgJDBC 拋 `PSQLException`(非 `SQLIntegrityConstraintViolationException`),因此走 `SQLStateSQLExceptionTranslator` 的 SQLSTATE 分支 | Q1.4 | **低** — 結論不受影響:`DuplicateKeyException` 是 `DataIntegrityViolationException` 的子類,catch 後者一定接得到。而且 `JdbcUserRepository:86` + `ErrorHandlingIT` 綠燈已是在同一 stack 上的實證 |
| **A3** | `spring.jackson.time-zone: Asia/Taipei` + `ADJUST_DATES_TO_CONTEXT_TIME_ZONE`(預設 true)會把請求的 `OffsetDateTime` offset 調成 `+08:00` | **Q4 / Pitfall 4** | **低** — 即使不成立,PgJDBC 讀回的 offset 仍是第二個獨立變異來源,所以「必須用 `isEqual`/`toInstant()`」的結論不變。**實際上只要有任一個來源成立,結論就成立** |
| **A4** | Hibernate Validator 的預設訊息字面(`"must not be blank"` 等) | Q8.5 | **極低** — 論點是「不可顯示」,具體字面不影響。且測試斷言是 `not.toContain(...)`,寫得寬鬆即可 |
| **A5** | `idempotency_key VARCHAR(128)` 是合適長度 | Q2 / DP-4 | **低** — 太短會讓合法 client 炸;太長是 DoS 面。128 有充裕餘裕。**planner 可調,但必須 > 36 且有上限** |
| **A6** | `AssetType` / `CurrencyCode` 的具體 enum 常數名未查證 | Q6.4 | **中** — 若前端要做 assetType 分類顯示(取代 mock 的 `cat`),planner **必須先讀** `stock-common/.../common/model/AssetType.java`。否則會猜錯字面 |
| **A7** | `AssetDto` 的 `latestPrice/change/changePercent/high/low` 可能為 `null`(BigDecimal 非 primitive) | Q6.4 | **中** — 若真的可能為 null 而前端型別宣告成 `number`,報價卡會顯示 `NaN`。**planner 應讀 `AssetQueryService.search` 確認**。保守做法:型別 `number \| null` 並處理空態(反正 D-16 的隱藏原則也支持這樣) |
| **A8** | klines 用 `interval=1h` + 回看 48 小時 + `limit=48` 是合適參數 | Q6.5 | **低** — 純 UX 選擇,無正確性風險。Chart 頁未 API 化,無前例可抄(CONTEXT.md `<specifics>` 明言) |
| **A9** | jsdom 下 `crypto.randomUUID()` 的可用性 | Standard Stack | **極低** — 測試一律 stub;production 是瀏覽器必有 |
| **A10** | 合併成一個 `marketApi.ts`(而非拆兩個)是較好的設計 | DP-5 | **低** — 純結構選擇。planner 可自行決定,但**必須承認需要新 adapter** |
| **A11** | `assets.symbol` 有唯一約束(讓「比 symbol」等價於「比 asset_id」) | Q4 陷阱 3 | **中** — 若不成立,同一 symbol 可能對應多個 asset_id,`symbol` 比對會漏。**緩解:改成比 `asset_id`(Q4 的選項 i),成本只是冪等命中分支多一次查詢。planner 若不確定就選 (i)** |
| **A12** | `PortfolioCache.invalidateAfterTrade` 在 Redis 不可用時不會讓交易回滾 | Security Domain | **中** — 若它會拋例外,`createTrade` 的最後一步(`TradingService:102`)會讓已成功的交易回滾。**planner 應讀 `PortfolioCache.java` 確認**。這不是 Phase 4 引入的問題,但 Phase 4 會讓它更容易被觸發(refetch 依賴快取失效) |
| **A13** | `PortfolioCache.java:45-52` 與 `Role.java:12-17` 的內容(採信 CONTEXT.md,本研究未讀) | 行號漂移報告 | **低** — 都不影響 Phase 4 的實作決策(不改快取邏輯;權限只需 `TRADE_EXECUTE` 存在) |
| **A14** | production 的 `/assets` rate limit 行為 | Q6.6 / Security Domain | **低** — debounce 已是好公民行為;若真有問題會在 Phase 5 的真實流程露出 |
| **A15** | `spring-boot-starter-webmvc-test` / `spring-boot-starter-security-test` 的 artifactId 在 trading 模組可用(BOM 已管理版本) | Wave 0 Gaps | **低** — market-data 的 pom 已在用同樣的 artifactId(`:103-104, 108-109`),同一個 parent BOM |

---

## Open Questions

1. **PR #15 的合併時程**
   - What we know:未合併、是 draft、mergeable、9 個 commit、改了 Phase 4 主戰場的同一個方法、順帶修了 klines 的 500 問題、修改了已套用的 V9(checksum 債)。
   - What's unclear:Yuan 打算什麼時候推進它。
   - Recommendation:**DP-1 選 (c)**(以 develop 為基準,範圍收斂成只做冪等),但把這個問題明確提給 Yuan。若 Yuan 說「這週就 merge」,改採 (a)。

2. **A1 的殘餘風險是否可接受**
   - What we know:PostgreSQL 官方文件只對 `DO UPDATE` 給了原子性保證,`DO NOTHING` 的等待行為未明文。
   - What's unclear:實際行為(需要 IT 實測)。
   - Recommendation:先做,用併發 IT 驗證,並保留 `TRADE_CONFLICT` 退路。若 IT 偶發紅 → 切方案 E(advisory lock)。**這個判準應寫進 plan,讓執行者知道紅燈時該換路而不是加 timeout。**

3. **`gsd-tools` 在本環境不可用**
   - What we know:`gsd-tools: command not found`(本 session 實跑)。因此 research-plan seam、classify-confidence seam、package-legitimacy seam、commit seam 全部無法使用。
   - What's unclear:是安裝問題還是路徑問題。
   - Recommendation:本研究已用直接工具替代(`javap` 驗 Spring 類別繼承鏈、`WebFetch` 取 PostgreSQL / Spring 官方文件、`git` / `gh` 查 branch 與 PR)。**影響**:(a) 本階段零新增依賴,package legitimacy gate 不影響;(b) `commit_docs: true` 但 commit seam 不可用 → RESEARCH.md 需人工 commit。**建議告知 Yuan 這個工具問題**,它會影響後續所有 GSD 階段。

4. **`AssetDto` 的 BigDecimal 欄位是否可能為 null(A7)**
   - Recommendation:planner 在實作 F4 前先讀 `stock-module-asset/.../service/AssetQueryService.java`。保守做法(型別 `number | null`)無論答案是什麼都正確。

5. **`assets.symbol` 是否有唯一約束(A11)**
   - Recommendation:planner 在實作 B6 前 grep `V1__foundation_schema.sql` 的 assets DDL。**若不確定就比 `asset_id`**(Q4 選項 i),成本只是多一次查詢。

6. **`PortfolioCache` 在 Redis 不可用時的行為(A12)**
   - Recommendation:planner 讀一次 `PortfolioCache.java`。若它會拋例外,**這是一個既有 bug 而非 Phase 4 引入的**,應寫成 lesson 或 todo,不要在 Phase 4 順手改(judgment §9:範圍外的改動先問)。

7. **D-16 的通用文案是否足夠(Q8.5 的代價)**
   - What we know:`fields` 只給 key,所以「quantity 太小」與「quantity 小數位太多」共用同一段文案。
   - What's unclear:Yuan 是否接受。
   - Recommendation:**Phase 4 接受通用文案**,文案中靜態提示合法範圍。若要更精確需要後端改用專屬 `ErrorCode` —— 那是 API 契約變更(judgment §9 要求先問)且不在 D-01~D-16 範圍。**若 planner 認為這是使用者體驗的實質缺口,應提給 Yuan 而非自行擴大範圍。**

---

## Sources

### Primary(HIGH confidence — 本 session 直接讀取的專案原始碼)

後端(全檔或指定區段實讀):
- `stock-module-trading/.../service/TradingService.java`(275 行,全檔)
- `stock-module-trading/.../repository/JdbcTradingRepository.java`(291 行,全檔)
- `stock-module-trading/.../repository/TradingRepository.java`(全檔)
- `stock-module-trading/.../api/TradingController.java`(112 行,全檔)
- `stock-module-trading/.../api/CreateTradeRequest.java`(全檔)
- `stock-module-trading/.../domain/HoldingCalculator.java`(80 行,全檔)
- `stock-module-trading/.../domain/TradeTransaction.java`(全檔)
- `stock-common/.../error/ErrorCode.java`(64 行,全檔)
- `stock-common/.../api/ApiError.java`(全檔)
- `stock-start/.../error/GlobalExceptionHandler.java`(98 行,全檔)
- `stock-module-asset/.../api/AssetController.java`(33 行,全檔)
- `stock-module-asset/.../api/AssetDto.java`(全檔)
- `stock-module-market-data/.../api/KlineDto.java`(全檔)
- `stock-module-market-data/.../api/MarketController.java`(`:60-115`)
- `stock-module-market-data/.../api/BackfillController.java`(`:80-108`)
- `stock-module-market-data/.../api/BackfillControllerTest.java`(`:1-90`)
- `stock-module-market-data/.../MarketDataTestApplication.java`(全檔)
- `stock-module-user/.../repository/JdbcUserRepository.java`(`:55-124`)
- `stock-db-migration/.../V7__trading_schema.sql`(`:1-40`)、`V8__transactions_append_only_trigger.sql`(全檔)
- `stock-start/src/test/.../support/ContainerIT.java`(64 行,全檔)
- `stock-start/src/test/.../TransactionsAppendOnlyIT.java`(109 行,全檔)
- `stock-start/src/test/.../TradingApiIT.java`(`:1-200`, `:375-440`)
- `stock-module-trading/src/test/.../api/TradingControllerTest.java`(`:1-80`)
- `stock-start/src/main/resources/application.yaml`(`:1-60`)
- `stock-module-trading/pom.xml`、`stock-module-market-data/pom.xml`(依賴區段)

前端(全檔或指定區段實讀):
- `src/components/OrderTicket.vue`(556 行,全檔)
- `src/services/apiClient.ts`(373 行,全檔)
- `src/services/portfolioApi.ts`(203 行,全檔)
- `src/services/pageApiClients.ts`(39 行,全檔)
- `src/services/apiTypes.ts`(310 行,全檔)
- `src/services/opsApi.ts`(`:130-166`)
- `src/api-adapter-wiring.test.ts`(272 行,全檔)
- `src/testSetup.ts`(全檔)、`vite.config.ts`(全檔)、`package.json`(scripts + 關鍵版本)
- `src/App.vue`(`:30-60`, `:95-115`, `:155-180`)
- `src/pages/Overview.vue`(`:183-250`)、`Positions.vue`(`:255-275`, `:315-375`)、`Trades.vue`(`:94-120`, `:157-200`, `:218-320`)
- `src/i18n.ts`(`:1-50` + grep)、`src/store.ts`(`:1-30`)、`src/composables/useAiAccessSettings.ts`(`:1-28`)
- `src/components/LineChart.vue`(props 區段)

規範與計畫:
- `CLAUDE.md`(全檔)
- `ai-docs/judgment.md`(101 行,全檔)
- `ai-docs/testing-standards.md`(85 行,全檔)
- `ai-docs/flyway-convention.md`(165 行,全檔)
- `ai-docs/code-standards.md`(`:75-85` 錯誤訊息安全規則)
- `.planning/phases/04-.../04-CONTEXT.md`(237 行,全檔)
- `.planning/REQUIREMENTS.md`(TRAD/VER 區段)、`.planning/ROADMAP.md`(Phase 4 / 04.1 區段)、`.planning/config.json`(全檔)

工具實跑:
- `gh pr diff 15 --name-only`、`gh pr view 15 --json commits`
- `git diff origin/develop...origin/fix/pr13-review-followups -- <各檔>`
- `git branch -a`、`git log --all --diff-filter=A`
- `git -C /d/end/workspace/vue/stock-v2 status/branch/log`
- `javap -cp spring-web-7.0.8.jar org.springframework.web.bind.MissingRequestHeaderException`
- `javap -cp spring-web-7.0.8.jar org.springframework.web.bind.ServletRequestBindingException`
- `javap -cp spring-web-7.0.8.jar org.springframework.web.method.annotation.MethodArgumentTypeMismatchException`
- `javap -cp spring-beans-7.0.8.jar org.springframework.beans.TypeMismatchException`
- `javap -p -cp spring-jdbc-7.0.8.jar org.springframework.jdbc.support.SQLStateSQLExceptionTranslator`
- `javap -p -cp spring-jdbc-7.0.8.jar org.springframework.jdbc.support.SQLExceptionSubclassTranslator`
- 多組 `grep` / `find` / `ls`(逐項見各 Q 段落的證據欄)

### Secondary(MEDIUM-HIGH confidence — 官方文件,本 session 實際抓取並逐字引用)

- `postgresql.org/docs/16/sql-insert.html` — `ON CONFLICT` 的 `conflict_target` 語法、partial unique index 的 `index_predicate` 推斷規則、`RETURNING` 只回成功插入/更新的列、`DO UPDATE` 的原子性保證(且 `DO NOTHING` **無**等價保證)
- `postgresql.org/docs/16/tutorial-transactions.html` — SAVEPOINT / `ROLLBACK TO` 語意;「`ROLLBACK TO` is the only way to regain control of a transaction block that was put in aborted state by the system due to an error」
- `docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html` — self-invocation 在 proxy mode 下**不會**產生交易;方法可見性規則
- `docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html` — `REQUIRES_NEW` 使用獨立物理交易與新連線(含連線池耗盡警告);`NESTED` 使用單一物理交易 + savepoint,僅支援 JDBC 資源交易 / `DataSourceTransactionManager`

### Tertiary(LOW confidence — 已在 Assumptions Log 逐條標記)

- Hibernate Validator 預設訊息字面(A4)
- PgJDBC 的具體例外型別(A2)
- Jackson `ADJUST_DATES_TO_CONTEXT_TIME_ZONE` 與 `spring.jackson.time-zone` 的互動細節(A3)
- jsdom 的 `crypto` 實作完整度(A9)
- `PortfolioCache.java` / `Role.java` 的內容(A13,採信 CONTEXT.md)

---

## Metadata

**Confidence breakdown:**

| Area | Level | Reason |
|------|-------|--------|
| PR #15 衝突面(Q0) | **HIGH** | 逐檔 diff 實跑;`createTrade` 的 HEAD 與 PR 版本逐行對照;例外繼承鏈以 `javap` 驗證 |
| 冪等交易語意(Q1) | **HIGH**(機制)/ **MEDIUM**(A1 殘餘風險) | 交易邊界、Spring 例外型別、Postgres 中止語意皆有 file:line 或官方文件引用。唯一 MEDIUM 是「`DO NOTHING` 零列後可見性」—— 官方文件無保證,已標 A1 並設 `TRADE_CONFLICT` 退路 + IT 驗證 |
| V10 migration(Q2) | **HIGH** | V10 為空號經四種方式確認;partial index 推斷規則有官方文件逐字引用;既有 migration 測試樣板實讀 |
| 必填 header(Q3) | **HIGH** | 繼承鏈 `javap` 驗證;`GlobalExceptionHandler` 全檔追蹤到 `codeForStatus(400)`;所有 409 code 逐一盤點 |
| payload 比對(Q4) | **HIGH** | scale 陷阱有 V7 DDL + 現有 IT fixture 為證;offset 陷阱有兩個獨立來源(其中 A3 標為 assumed,但單一來源即足以成立) |
| 前端 adapter(Q5) | **HIGH** | `apiClient.ts` / `portfolioApi.ts` / `opsApi.ts` / `pageApiClients.ts` / `api-adapter-wiring.test.ts` 全檔實讀;`opsApi.ts:146-154` 是逐字可抄的樣板 |
| OrderTicket 重建面(Q6) | **HIGH** | 全檔 556 行實讀;12 條行號逐條核對;`AssetController` / `MarketController` / `KlineDto` / `LineChart` props 皆實讀。**發現「前端無 asset/market adapter」的範圍缺口** |
| revision counter(Q7) | **HIGH** | `App.vue` v-if 鏈實讀;三頁 load 函式與 `applyQueryChange` 實讀;共享狀態前例四種盤點 |
| 欄位級錯誤(Q8) | **HIGH** | `GlobalExceptionHandler:56-64` + `ApiError` + `ApiClientError` + `fieldsFrom` 全鏈實讀;`CreateTradeRequest` 逐欄註解實讀;i18n 結構與慣例實讀 |
| SELL 預檢(Q9) | **HIGH** | `HoldingCalculator:42-62` 全讀;`listHoldings` 的 `total_quantity > 0` 過濾行為實讀;現有 oversell IT 實讀 |
| Validation Architecture(Q10) | **HIGH** | 五條 SC 逐條對應到具體指令與斷言;Wave 0 gap 以 pom 依賴與 `grep @WebMvcTest` 實證;Phase 5 邊界依 ROADMAP + judgment §8 劃定 |
| TDD 排序(Q11) | **HIGH** | 每個紅燈都對應到一個已驗證存在或不存在的符號;尷尬處給了具體替代品而非「不適用」 |
| 跨 repo(Q12) | **HIGH** | sibling repo 的 status/branch/log 實跑;**發現 STATE.md 的前提已過期** |

**三個最重要的發現(planner 若只能記三件事):**
1. **前端完全沒有 asset / market adapter** —— D-01 需要新建**兩個** adapter,不只 `tradingApi.ts`。這是工作量估計的實質變動。
2. **`ON CONFLICT DO NOTHING` + insert-first 可以完全避開 CONTEXT.md 警告的「中止交易」問題** —— 但它偏離 CONTEXT.md 給的步驟順序,需明確記錄(DP-2)。
3. **`BigDecimal.equals` 與 `OffsetDateTime.equals` 會讓每一次合法重試都誤回 409** —— 兩個獨立陷阱,且第二個的症狀(「看起來一樣但不相等」)極易浪費大量除錯時間。

**Research date:** 2026-07-26
**Valid until:** 2026-08-09(14 天)—— 比一般的 30 天短,因為:(a) PR #15 一旦合併,Q0 的整個分析與 `TradingService` 的所有行號都會變;(b) sibling repo 的 `develop` 是活躍分支,前端行號可能漂移。**若 PR #15 合併,Q0 / Q1.7 的插入點分析需重新驗證(其餘結論不受影響)。**

---

*Phase: 4-manual-trade-creation-idempotency-post-trade-refetch*
*Researched: 2026-07-26*
