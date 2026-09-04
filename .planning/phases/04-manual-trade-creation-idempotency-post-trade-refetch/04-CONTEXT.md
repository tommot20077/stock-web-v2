# Phase 4: Manual Trade Creation, Idempotency & Post-Trade Refetch - Context

**Gathered:** 2026-07-26
**Status:** Ready for planning

<domain>
## Phase Boundary

讓 API mode 的 order ticket 建立**一筆手動已成交交易**(`POST /api/v1/trades`)、後端以 `user_id + idempotency key` 唯一約束防重、成功後把 portfolio 三塊讀取(summary / holdings / trades)重讀成後端真相,並把 validation / oversell / permission / CSRF / network 錯誤以使用者可理解的方式呈現。

**⚠️ 本階段包含後端改動(共三處),不是純前端接線:**
1. `POST /api/v1/trades` 新增**必填** `Idempotency-Key` header 與 server-side 冪等邏輯(D-05 ~ D-08)。
2. 新 migration `V10`:`transactions.idempotency_key` 欄位 + partial unique index(D-08)。
3. `ErrorCode` 新增一個 409 code 給「同 key 不同 payload」(D-07)。

**⚠️ 第二個範圍認知:`OrderTicket.vue` 在 API mode 幾乎是重建而非接線。**
查證後確認該檔目前**沒有一項資料是真的**:symbol 清單來自本地 `data.ts` 的 `SYMBOLS/CRYPTO/FX`(:200, :209)、報價卡走勢圖是 `genSeries()`(:256-258)、fee 是 `Math.max(1, estTotal * 0.001)`(:253)、`cashAfter` 寫死 `124_580`(:254)、成交價是 `px * (1 + (Math.random() - 0.5) * 0.002)`(:373-374)、`orderId` 是亂數(:375),而且它直接 `import { useMockPortfolioStore }`(:202)——這正是 judgment §3 禁止的。四步 wizard 中的 `routing/match` 三階段(:242, :357-367)是 judgment §1 反例明文點名的東西。

**不在本階段:** pending order / cancel / partial fill / TIF / broker 撮合(PROJECT.md Out of Scope + judgment §1)、批次補登歷史交易、四條後端資料缺口(可用現金、日級損益、資產分類、watchlist API 化 —— Yuan 已決定用 `/gsd-phase` 在 Phase 4 之後插入新 phase 處理,見 Deferred)。

</domain>

<decisions>
## Implementation Decisions

### Ticket 欄位與資料來源

- **D-01:** **symbol 選單與報價卡全部改接 `GET /api/v1/assets?query=`。** 關鍵事實:`AssetDto` 單一回應就同時帶 `symbol / name / sector / latestPrice / change / changePercent / volumeText / high / low`(`AssetDto.java:9-24`),選單與報價卡不需要兩個端點。走勢圖接 `GET /api/v1/market/{symbol}/klines`(`MarketController.java:96`)。
  順帶解決一件事:symbol 必須是後端真存在且 `tradeable`,不會送出去才吃 `ASSET_NOT_FOUND`(`TradingService.resolveTradeableAsset:237`)。
  **這是 D-16「隱藏假資料」的例外分支,不是違反它** —— D-16 的例外判準本來就是「能由後端真實推導就用真實資料,隱藏只適用於推導不出來的內容」。報價卡的每一格後端都有。

- **D-02:** **fee 改為使用者手動輸入,預設 0。刪除 0.1% 估算公式。**
  理由不是美感問題:`fee` 會被 `HoldingCalculator` 算進 `avg_cost` 與 `realized_pnl`,而 `transactions` 是 append-only(V8 trigger 禁 UPDATE/DELETE),**寫錯的費用永久留在帳本裡且改不回來**。把前端發明的費率當預設值送出,等於使用者按一下就污染成本基礎。

- **D-03:** **給「成交時間」(executedAt)日期時間欄位,預設現在。**
  後端省略此欄位即 `OffsetDateTime.now()`(`TradingService:68`),V9 index 的註解也早已預設「補登舊交易時 executed_at 與 created_at 會分歧」,所以後端已為補登留了空間。
  **本決策產生三個必須處理的後果**:(a) 驗證不可為未來時間;(b) 必須帶時區 offset(後端是 `OffsetDateTime`);(c) 補登的交易可能不在 Trades 頁當前的篩選/排序/頁碼範圍內 → 見 D-10。
  **另有一個正面副作用**:因為 executedAt 由前端明確送出,同一 key 重試時 payload 逐位元相同,D-07 的 payload 比對才有意義(若由後端帶 `now()`,每次重試都會假性不符)。
  **⚠️ 後果 (a) 的後端部分可能已經做完了 —— 但在另一個 branch 上。** draft PR #15(`fix/pr13-review-followups`,commit `d1bd9a1`)已在 `TradingService` 加入 `EXECUTED_AT_FUTURE_TOLERANCE = 5 分鐘` 與 `executedAt must not be in the future` 的 `VALIDATION_FAILED`,而且它的 javadoc 明文寫「補登舊交易是明確支援的情境,故**不設下界**」—— 與 D-03 的方向完全一致。planner **必須先確認 PR #15 是否已合併**:已合併則後端這項不用再做(只需前端欄位與 UI);未合併則 Phase 4 要自己補,且要避免與該 PR 產生衝突實作。詳見 `<code_context>` 的在途分支警告。

- **D-04:** **API mode 隱藏三組後端不支援的欄位:訂單類型(MKT/LMT)、TIF(DAY/GTC)、「交易後現金」。**
  前兩者是 judgment §1 反例明文點名(`把前端 mock 的 ordType/tif 塞進 API payload` / `UI 顯示「委託已送出,等待成交」`);第三者無後端來源(全 repo grep `available_cash|cash_balance|balance|wallet` **零命中**),照 D-14/D-16 隱藏。
  **連帶效果**:MKT 模式原本負責把報價自動填進 price 並鎖住輸入框(`OrderTicket.vue:80, 339-341`)。隱藏之後,price 一律**預填 `AssetDto.latestPrice` 但可編輯** —— 這正是「手動記錄已成交價格」的語意。
  mock mode 四樣全部保留不受影響。

- **D-09:** **送出流程收斂為「送出中 → 已記錄」兩態。** 移除 `routing/match` 三階段假進度、`Math.random()` slippage 與亂數 `orderId`。成功畫面顯示後端回傳的 `TradeDto`:trade id(UUID,除錯回報用)、type、quantity、**實際送出的 price**、fee、executedAt。不得出現「平均成交價」這種暗示撮合的欄位。

### Idempotency 契約(後端)

> judgment §5 已鎖定的部分不在此重述:防護在後端、`user_id + key` 唯一約束、duplicate 回既有交易、不重複更新 holdings、前端 guard 只是 UX。以下只記本次補上的契約 shape(§9 要求先問的部分)。

- **D-05:** **`Idempotency-Key` header 為必填,缺少回 400。** 不採現有 `BackfillController:90` 的 `required = false`。
  理由:選填等於防護可被繞過 —— 前端任何一條路徑忘了帶,連點就又會建重複交易,而那是 append-only 帳本改不回來的錯。目前**沒有任何真實 client 在呼叫 `POST /trades`**(Vue trading adapter 還不存在),遷移成本只有更新 `TradingControllerTest`。非瀏覽器 bearer client 也必須自產 UUID(不牴觸 AUTH-07:path 仍是明確定義的)。

- **D-06:** **header 名稱沿用 `Idempotency-Key`**,與 `BackfillController` 既有慣例一致(不新造名稱)。**但語意刻意不同**:Backfill 是 `tryAcquire` 失敗即回 409 拒絕;本階段必須**回既有交易**(§5 明文)。planner 請勿把 `BackfillIdempotencyService` 當實作樣板照抄 —— 它是同名不同語意的反例。

- **D-07:** **同一 key 送不同 payload → 409 + 專用 error code(建議名 `TRADE_IDEMPOTENCY_KEY_REUSED`)。**
  若一律回既有交易,使用者改了數量再送、拿到舊交易卻看到「成功」,最終持倉與他的認知不符。比對成本幾乎為零:**直接比已存交易列的 `asset_id / type / quantity / price / fee / executed_at`,不需額外 fingerprint 欄位**(前提是 D-03 的 executedAt 明確送出)。

- **D-08:** **key 存在 `transactions` 新欄位,永久保留。** V10 migration:`idempotency_key VARCHAR` + `(user_id, idempotency_key)` partial unique index(`WHERE idempotency_key IS NOT NULL`)。
  不用獨立表 + 清理 job:清掉之後同一個 key 重送就會建出重複交易,那是真風險不是理論。不用 Redis:§5 明文要求「唯一約束」,而 Redis 指令與 DB transaction 非原子 —— Redis 鎖成功但 DB 回滾,那個 key 就被白白佔掉,使用者重試永遠失敗。
  `ALTER TABLE ADD COLUMN` 不受 V8 append-only trigger 影響(trigger 只擋 row 層 UPDATE/DELETE/TRUNCATE)。

### Post-trade refetch

- **D-10:** **用 shared revision counter 通知,已掛載的頁自行重讀。**
  關鍵架構事實:`App.vue:36` 用 `v-if="page === 'overview'"` 切頁,**非當前頁是卸載的**,下次 mount 本來就會重抓;而 OrderTicket 是全域 overlay,使用者可能在 Markets/Chart 頁下單(三個 portfolio 頁都沒掛載)。所以「一律打三個 domain 請求」在目前架構下是純粹的無效工(沒有 client-side cache 可放,沒掛載的頁沒人消費)。
  做法:成功後提升一個共用 revision;Overview / Positions / Trades `watch` 它,重跑各自**現有的** `loadSummary` / `loadHoldings` / `loadTrades`(`Overview.vue:232,241`、`Positions.vue:351,360`、`Trades.vue:255`)。與 D-11「各 view 自己處理 loading/error/retry」同一結構。

- **D-11:** **Trades 頁重讀時保留篩選與排序、頁碼重置為 0;若新交易不在結果集內則明確告知。**
  頁碼重置與 Phase 3 D-15 同一邏輯。因為 D-03 允許補登,新交易**不保證在第 0 頁**,甚至可能不符當前篩選(篩了 Buy 卻記一筆 Sell、或補登到去年)。此時顯示「已記錄,但不在目前的篩選/排序條件內」比讓使用者以為沒記錄成功好。文案由 planner 決定。

- **D-12:** **交易成功但 refetch 失敗時,兩件事分開呈現。** 成功畫面照 D-09 顯示 trade id;portfolio 區塊各自進 error + retry 態(Phase 3 D-11)。
  這條是防止最糟的失敗模式:使用者看到整體錯誤 → 以為交易沒成功 → 再送一次。冪等有保護,但他若重開 ticket(換了 key,見 D-14)或改了數量,就會真的建出第二筆。

- **D-13:** **接上 API mode 的「剛成交列 fresh 高亮」。** 用回傳的 `TradeDto` 產生 API mode 的 lastFill 等價物(symbol/type/qty/price)。Phase 3 已在 `Positions.vue:264` 與 `Trades.vue:109` 留下接點與註解(「Phase 4 引入 post-trade refetch 時再接」),本階段一併清掉那兩條 TODO 註解。

### 錯誤與重試

- **D-14:** **Idempotency key 在「按下送出」時產生;該次嘗試的重試(自動或手動)沿用同一 key;使用者回到表單改過任何欄位後,下次送出換新 key。**
  兩條規則就蓋完所有情境,且**刻意避開與 D-07 的互鎖**:若採「一張 ticket 一個 key」,驗證失敗(400)後使用者改數量再送就會吃 409 `KEY_REUSED`,卡住且不知道要關掉 ticket 才能繼續。
  對應 §5:「timeout 重送沿用同一 key;新意圖才換 key」——「改過欄位」就是新意圖的可執行判準。

- **D-15:** **SELL 時載入該標的持倉做預檢,並顯示「可賣數量」。** 後端 409 `TRADE_INSUFFICIENT_HOLDING` 仍是最終權威(與 §5「前端 guard 不是防護」同一邏輯)。
  代價是多一次 `listHoldings()`。選這條而非「純靠後端」的理由:「可賣 N 股」是 mock 現在也沒有的真實有用資訊;而「只在已載入 holdings 時才預檢」會讓同一操作在 Positions 頁與 Markets 頁行為不同,比全部不預檢更難解釋。
  **注意**:預檢只比數量上限,**不重算成本或損益**(不牴觸 Phase 3 D-04)。

- **D-16:** **欄位級錯誤綁到對應輸入框,其餘顯示在 ticket 底部。**
  已查證後端**確實**回欄位級錯誤:`GlobalExceptionHandler:56-64` 把 `@Valid` binding 失敗填進 `ApiError.fields`(`Map<String,String>`),而 `CreateTradeRequest` 有 6 個驗證註解(`@NotBlank symbol`、`@NotBlank type`、quantity 的 `@DecimalMin/@DecimalMax/@Digits`、price 同、`@DecimalMin fee`、`@Size note`)。
  `fields` 的 key(symbol/quantity/price/fee/note)綁對應輸入框;`TRADE_INSUFFICIENT_HOLDING` / `ASSET_NOT_FOUND` / `TRADE_CONFLICT` / `TRADE_IDEMPOTENCY_KEY_REUSED` / CSRF 403 顯示在底部並帶 error code + traceId(Phase 3 D-12:只在錯誤狀態顯示 traceId)。
  **重要**:`fields` 的 value 是 Bean Validation 的英文預設訊息,**不得直接顯示** —— 前端依 field 名稱對應自己的 i18n 文案。
  session / 認證類錯誤(401、refresh 失敗)仍走 `SessionBanner`(Phase 3 D-13、Phase 2 D-14)。

### Claude's Discretion

以下由我依既有規範裁決,已有依據不需再問:

- **trading adapter 獨立成 `tradingApi.ts`,不併進 `portfolioApi.ts`。** 依據是 REQUIREMENTS.md **VER-02** 的字面:「覆蓋 API client、auth store、runtime mode、**portfolio adapters、trading adapter**」—— 需求本身就把兩者列為不同的 adapter。mock 實作可以 import `useMockPortfolioStore` 委派 `executeOrder()`(judgment §3 只禁**元件** import mock store;`portfolioApi.ts` 本身就是這樣做的),以保住 mock 的 `lastFill` 行為。需在 `pageApiClients.ts` 的 `RuntimeApiClients` 註冊 `trading`。
- **`OrderTicket.vue` 移除 `useMockPortfolioStore` 與 `useMockNotificationsStore` 的直接 import,改走 service interface**(judgment §3)。mock 通知的推送由 mock 實作負責,API mode 不推(notifications 未 API 化,屬 PORT-06 v2)。
- **key 產生用 `crypto.randomUUID()`**;前端 duplicate-submit guard 沿用現有 `placing` ref + button disabled 的雛形(`OrderTicket.vue:358, 181`),但依 §5 只當 UX。
- 元件拆分、loading 骨架、日期選擇器的具體版面、「不在篩選條件內」的文案 → 依現有 UI 慣例決定。
- 新 error code 的最終命名可由 planner 微調,但必須是 409 且與 `DUPLICATE_RESOURCE`(既有 409)語意區分開。

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### 判斷準則(最高優先)
- `ai-docs/judgment.md` §1 — 交易語義:`POST /api/v1/trades` 是**已成交紀錄不是下單系統**。payload 只允許 `CreateTradeRequest` 現有欄位;UI 文案與測試斷言不得出現 pending / cancel / routing / partial fill / TIF。**這是 D-04、D-09 的依據。**
- `ai-docs/judgment.md` §5 — **交易寫入必須 server-side 冪等**:`user_id + idempotency key` 唯一約束、duplicate 回既有交易、不重複更新 holdings;前端 debounce/disabled 只是 UX。**這是 D-05 ~ D-08、D-14 的依據。**
- `ai-docs/judgment.md` §3 — mock/api 雙模式:元件**不得** import mock store,一律經 `services/` 的 domain service interface;API mode 功能驗證必須看到真實 network call。
- `ai-docs/judgment.md` §4 — 信封權威為後端 `ApiResponse<T>`;分頁為 `ApiResponse<PageResponse<T>>`。
- `ai-docs/judgment.md` §7 — 高頻計算走 Redis 預計算、API 只讀(Phase 3 D-04 的依據,本階段的 refetch 不得前端重算)。
- `ai-docs/judgment.md` §8 — 跨 repo 變更兩邊驗證都要跑。
- `ai-docs/judgment.md` §9 — 變更 API 契約 shape 前要停下來問(D-05 ~ D-08 已於 2026-07-26 取得同意)。

### 規範
- `ai-docs/flyway-convention.md` — V10 migration 的命名與撰寫規則(D-08 需要)
- `ai-docs/security.md` §11 — `transactions` append-only 帳本的合規要求(V8 trigger 的來源)
- `ai-docs/code-standards.md` — 錯誤訊息安全規則(不得把使用者可控字串反射回應答)
- `ai-docs/testing-standards.md` — 層級要求(unit / web / IT 皆必要)
- `ai-docs/browser-auth-contract.md` — unsafe request 的 CSRF header 契約(`X-XSRF-TOKEN`)
- `../../vue/stock-v2/docs/api-contracts/mock-to-real-contract.md` — 信封與分頁契約(2026-07-19 已與後端對齊)

### 需求與前期脈絡
- `.planning/REQUIREMENTS.md` — TRAD-01 ~ TRAD-06(VER-02 是 trading adapter 獨立成檔的依據)
- `.planning/phases/03-portfolio-read-api-mode/03-CONTEXT.md` — D-04(計算歸屬)、D-08(換頁按鈕)、D-11~D-13(錯誤呈現)、D-14/D-16(隱藏合成資料的原則與例外判準)、D-15(頁碼重置)
- `.planning/phases/02-frontend-session-api-client-foundation/02-CONTEXT.md` — D-14(全域 session banner 邊界)、D-20(shared client 是唯一 transport 邊界)
- `.planning/codebase/ARCHITECTURE.md` — Trading and Portfolio Path、Frontend API Adapter Path、模組邊界與 anti-patterns
- `.planning/codebase/CONVENTIONS.md`、`.planning/codebase/TESTING.md`

### 後端待改檔案
- `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/service/TradingService.java:61-104` — `createTrade`,冪等邏輯的落點
- `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/TradingController.java:36-53` — `POST /trades`,header 接收處
- `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/CreateTradeRequest.java` — 現有 7 欄位與上限約束(2026-07-17 裁決)
- `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/repository/JdbcTradingRepository.java:35-70` — `insertTransaction`,已是 `insert ... returning`
- `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/domain/HoldingCalculator.java` — `applyBuy` / `applySell`(oversell 判定來源)
- `stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/ErrorCode.java:40-44` — 現有 TRADE_* codes,新 code 加在此
- `stock-start/src/main/java/dowob/xyz/stockwebv2/start/error/GlobalExceptionHandler.java:56-64` — 欄位級錯誤的產生處(D-16 依據)
- `stock-db-migration/src/main/resources/db/migration/V7__trading_schema.sql`、`V8__transactions_append_only_trigger.sql`、`V9__trading_query_indexes.sql` — 現有 schema、append-only trigger 與索引取捨
- `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/api/BackfillIdempotencyService.java` — **反例參考**:同名不同語意(回 409 拒絕而非回既有資源),勿照抄

### 前端待改檔案
- `../../vue/stock-v2/vue-app/src/components/OrderTicket.vue` — 本階段主戰場(見 Phase Boundary 的行號清單)
- `../../vue/stock-v2/vue-app/src/services/portfolioApi.ts` — 三件組樣板(`createMockXxxApi` / `createHttpXxxApi` / `createXxxApi`)與 `live` 消費契約
- `../../vue/stock-v2/vue-app/src/services/pageApiClients.ts` — 需註冊新的 `trading` client
- `../../vue/stock-v2/vue-app/src/services/apiClient.ts` — `ApiClientError`(含 `requestId` 來自 `meta.traceId`、`fields`)、CSRF header、401 refresh/replay 皆已就緒,勿另造轉接層
- `../../vue/stock-v2/vue-app/src/App.vue:36, 52-56, 104, 164, 170` — 頁面 `v-if` 切換與 OrderTicket overlay 掛載處(D-10 依據)
- `../../vue/stock-v2/vue-app/src/pages/Overview.vue:232,241`、`Positions.vue:351,360`、`Trades.vue:255` — 現有 load 函式,D-10 要 watch revision 重跑它們
- `../../vue/stock-v2/vue-app/src/pages/Positions.vue:264`、`Trades.vue:109` — Phase 3 留下的 fresh 高亮接點與 TODO 註解(D-13 要清掉)

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `apiClient.ts` — `apiRequest<T>` / `buildQueryString` / `ApiClientError`(含 `fields` 與 `requestId`)、CSRF header、單飛 refresh + 一次 replay 全部已就緒(Phase 2)。`POST /trades` 只要建 payload 與帶 header。
- `portfolioApi.ts` — 最接近的樣板:三件組結構、`live` 分支契約(`api.live` 是否存在,不是 mode 字串)、mock 委派 store 的寫法。
- `GET /api/v1/assets?query=` — **公開端點**(`AssetController` 無 `@PreAuthorize`),`AssetDto` 已含選單與報價卡需要的全部欄位。
- `GET /api/v1/market/{symbol}/latest`、`/market/latest?symbols=`、`/market/{symbol}/klines` — 走勢圖與批次報價的現成端點。
- `PortfolioCache.invalidateAfterTrade`(`PortfolioCache.java:45-52`)— **已經會同時刪除 holding 與 summary 兩個 key**,所以 refetch 拿得到新資料,不需要在本階段動快取失效邏輯。
- `Role.USER` 已包含 `TRADE_EXECUTE`(`Role.java:12-17`)—— 權限不是阻礙,一般使用者可建交易。
- `testSetup.ts` — 測試預設鎖 mock;需要 api 模式的測試自行 `vi.stubEnv('VITE_DATA_MODE','api')`。

### Established Patterns
- **Service 三件組**:`createMockXxxApi()` / `createHttpXxxApi(basePath)` / `createXxxApi(mode, basePath)`,介面同一份。
- **頁面取得 client**:`getRuntimeApiClients().xxx`,元件不直接 import mock store。
- **API mode 不回退 mock**:Phase 2 Plan 05 已用「mock factory 未被呼叫」的斷言把靜默回退變成測試失敗(`api-adapter-wiring.test.ts`),新的 trading adapter 需同樣覆蓋。
- **後端錯誤一律 `BusinessException(ErrorCode, message)`**,由 `GlobalExceptionHandler` 轉信封;controller 不做業務判斷。
- **TDD**:先寫失敗測試再實作(CLAUDE.md 硬性要求)。

### Integration Points
- `POST /api/v1/trades` + `Idempotency-Key` header ← 新的 `tradingApi.createTrade()`
- `GET /api/v1/assets` / `/market/{symbol}/klines` ← OrderTicket 的選單與報價卡
- `GET /api/v1/portfolio/holdings` ← D-15 的 SELL 預檢(可與 Positions 頁共用同一 adapter 方法)
- shared revision counter ← OrderTicket 成功後提升,三個 portfolio 頁 watch(D-10)
- `pageApiClients.ts` 的 `RuntimeApiClients` 需新增 `trading` 欄位

### ⚠️ 在途分支風險:draft PR #15 正在改同一個方法(2026-07-26 查證)

本 CONTEXT 的所有行號都對應 **HEAD**(`docs/lessons-verification-traps` = `develop` + 一個純文件 commit)。但 **draft PR #15**(`fix/pr13-review-followups`,「PR #13 code review 的 15 項發現修正」)**尚未合併進 develop**,而它動的正是 Phase 4 的主戰場:

- **重排 `createTrade` 的驗證順序**:改為「零 I/O 的 type / fee / executedAt 檢查先行,需查 DB 的 `resolveTradeableAsset` 最後」,與 `listTrades` 一致。HEAD 目前是 symbol 解析先跑。
- **新增 executedAt 未來時間驗證**(見 D-03 的補充)。
- **新增 `stock-common/.../common/time/ApiTimeParser.java`**(HEAD 沒有這個類別;目前只存在於 worktree `.claude/worktrees/pr15-fix/`)。
- **副作用是錯誤優先序改變**:「type 打錯 + symbol 不存在」由 `ASSET_NOT_FOUND` 改回 `TRADE_UNSUPPORTED_TYPE`。該 commit 訊息明確提醒「前端以 `error.code` 為準、不要假設優先序」—— **這正是 D-16 錯誤顯示要遵守的事**:不要對錯誤出現順序寫死假設。

**planner 的第一件事應是確認 PR #15 的狀態**,並據此決定:
- 已合併 → 以合併後的 `createTrade` 為基礎,冪等查詢插在「零 I/O 驗證之後、symbol 解析之前」(key 查詢是 I/O,但比資產查詢更該先做:duplicate 應該連資產查詢都不必付)。
- 未合併 → 兩種做法都有風險。優先建議等它合併,或明確以 develop 為基準並在 SUMMARY 記錄衝突風險。**不要**在不知情的狀況下重複實作 executedAt 驗證或另造一個 time parser(judgment §6 的精神:先 grep 確認,不存在才建,勿另造重複品)。

### ⚠️ 給 planner 的實作順序陷阱(冪等)
現有 `createTrade`(`TradingService:61-104`)的順序是「**先改 holdings,再 insert transaction**」。若冪等只依賴 insert 的 `on conflict`,duplicate 進來時 holdings 已經被動過才發現衝突。正確順序:
1. 先以 `(user_id, idempotency_key)` 查既有交易 → 有就直接回它,**完全不碰 holdings**。
2. 沒有才走現有流程(holdings → insert)。
3. 唯一約束衝突(併發連點,兩個 request 都通過步驟 1)必須落回「重讀既有交易並回傳」,不能變成 500。
   **注意 PostgreSQL 的行為**:同一 transaction 內約束違反後該 tx 已中止,重讀**必須在新的 transaction** 進行 —— 這與現有 `insertHoldingIfAbsent` 的重讀路徑(:78-84,那是 `on conflict do nothing` 不會中止 tx)不是同一回事,不要照抄。

</code_context>

<specifics>
## Specific Ideas

- **報價卡不必隱藏,是因為後端真的有這些數字** —— 這是 D-16 例外判準的第一次正式套用(Phase 3 四次都落在「隱藏」那一邊)。判準複述:能由後端真實推導 → 用真實資料;推導不出來 → 隱藏。`AssetDto` 有 latestPrice/change/changePercent/high/low/volumeText,所以走「真實資料」分支;`cashAfter` 後端零命中,所以走「隱藏」分支。同一張 ticket 上兩個分支並存是正確的,不是不一致。
- **D-03(補登)與 D-07(payload 比對)是互相成立的**:因為 executedAt 由前端送,重試的 payload 才逐位元穩定。planner 若之後想「簡化」成讓後端帶 `now()`,D-07 會立刻失效變成每次重試都假性不符 —— 兩條決策綁在一起,不要單獨改一邊。
- **D-14 與 D-07 的互鎖是設計時發現的**,不是理論風險:「一張 ticket 一個 key」+「同 key 不同 payload 回 409」會讓驗證失敗後修正欄位的使用者卡死。測試應明確覆蓋這條路徑(400 → 改欄位 → 再送 → 應成功建立而非 409)。
- 走勢圖接 klines 是 Yuan 明確選擇「連走勢圖一起接真資料」的結果(選項另有「走勢圖隱藏」)。Chart 頁本身仍未 API 化(PORT-06 v2),所以本階段是 klines 端點的**第一個**前端消費者,mapping 與錯誤態沒有前例可抄。

</specifics>

<deferred>
## Deferred Ideas

- **四條後端資料缺口 → Yuan 決定插入新 phase(排在 Phase 4 之後、Phase 5 之前)。** 用 `/gsd-phase` 執行,不手改 ROADMAP.md。四條的**實際**後端現況(2026-07-26 查證,比原 todo 檔記載更精確):
  - **可用現金 / 帳戶餘額** — 後端**完全沒有**。`available_cash|cash_balance|balance|wallet` 全 repo(排除 target)零命中。是新增領域模型,且可能讓語意往「帳戶系統」偏移,需先確認是否符合 PROJECT.md 範圍(judgment §1)。
  - **日級損益** — 後端**沒有**。`market_prices` 有價格時序但無「當日持倉」維度,需日級持倉快照表或每日 job,並先定義「今日」(交易日/自然日、時區)。
  - **資產分類** — **原 todo 描述過度悲觀,需更正**:`assets.sector` 與 `assets.asset_type` 欄位**存在且 V2 seed 有值**(`'Tech'`/`'Auto'`/`'Retail'`/`'Crypto'`),`AssetDto:16` 也已回傳 `sector`。缺的只是 portfolio 的 SQL 沒 JOIN 帶出來、`HoldingDto` 沒這個欄位。成本接近「JOIN + DTO 欄位」而非「新領域模型」。受益者是 Phase 3 的頁面(Positions sector 卡、Overview donut),等於回頭補 Phase 3。
  - **watchlist API 化** — 後端**沒有**。整個 repo 只有 `Permission.WATCHLIST_MANAGE` 一個 enum 值,無表無 endpoint。屬 PORT-06(v2)。
- **批次補登歷史交易(bulk import)** — D-03 只給單筆 ticket 的日期欄位;一次匯入多筆歷史交易是不同使用情境(CSV 匯入 + 批次冪等),不在本階段。
- **notifications API 化** — OrderTicket 目前會推 mock 通知;API mode 不推。屬 PORT-06(v2)。
- **多幣別呈現** — `assets.currency` 有值(USD/TWD),但 portfolio 彙總目前不分幣別;Phase 3 討論尾聲已列為可選深入項目,仍未展開。交易建立會讓這個問題更明顯(記一筆 TWD 交易到以 USD 彙總的 portfolio),但修正它需要先定義換匯來源,不在本階段。

### Reviewed Todos (not folded)

四條皆**檢視後不折入 Phase 4**,改以新 phase 承接(理由見上)。與本階段唯一的交集是 `OrderTicket.vue` 的 `cashAfter` 寫死值 —— 依 D-04 隱藏即可解決,不需要先有後端帳戶模型。

</deferred>

---

*Phase: 4-manual-trade-creation-idempotency-post-trade-refetch*
*Context gathered: 2026-07-26*
