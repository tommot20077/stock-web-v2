# Roadmap: Stock Web V2

## Overview

本 milestone 先收斂瀏覽器安全認證合約與後端安全基礎，再建立 Vue API mode 的 session/API client transport 邊界，接著依序把 portfolio read 與 manual executed trade creation 接到後端，最後用跨 repo browser flow 驗證 cookie、CSRF、CORS、refresh、portfolio refetch 與 logout 在真實瀏覽器流程中一致運作。範圍明確排除 broker/order lifecycle、pending/cancel/partial fill、AI trading policy 與大型 UI redesign。

## Phases

**Phase Numbering:**

- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

- [x] **Phase 1: Browser Auth Contract & Backend Security Foundation** - 後端提供完整 cookie auth、CSRF、refresh/logout、401/403、bearer compatibility 與契約文件。
- [x] **Phase 2: Frontend Session & API Client Foundation** - Vue API mode 透過唯一 shared client 處理 credentials、CSRF、envelope、refresh retry、session restore 與 runtime mode。
- [x] **Phase 3: Portfolio Read API Mode** - Vue API mode 可以讀取 portfolio summary、holdings/positions、trade history 並呈現 loading/empty/error/retry 狀態。
- [x] **Phase 4: Manual Trade Creation, Idempotency & Post-Trade Refetch** - API mode order ticket 建立 manual executed trade，後端防重，成功後重新讀取 portfolio 狀態。
- [ ] **Phase 04.1: Backend Data Gap Backfill** *(INSERTED 2026-07-26，非緊急)* - 基準幣與匯率折算、手動現金帳、今日損益、資產配置與產業分組(2026-09-28 discuss 後範圍;股利與 watchlist 移至 04.2)。
- [ ] **Phase 04.2: Dividends, Share Adjustments & Watchlist** *(INSERTED 2026-09-28)* - 現金股利、配股、拆股 / 合股與多清單 watchlist 的後端 API 與前端 API mode。
- [ ] **Phase 5: Cross-Repo Browser Flow Verification & Contract Hardening** - Backend/frontend 測試與真實瀏覽器 smoke flow 驗證完整整合契約。

## Phase Details

### Phase 1: Browser Auth Contract & Backend Security Foundation

**Goal**: 後端 browser auth contract 可安全支援 cookie session，且不留下無 CSRF 的半套 cookie auth。
**Depends on**: Nothing (first phase)
**Requirements**: AUTH-01, AUTH-02, AUTH-05, AUTH-06, AUTH-07, SEC-01, SEC-02, SEC-03, SEC-04, SEC-05, VER-04
**Success Criteria** (what must be TRUE):

  1. 使用者註冊或登入後，瀏覽器收到 httpOnly auth cookies，Vue JavaScript 不需要也不能依賴 refresh token。
  2. Cookie-authenticated unsafe requests 缺少或送錯 CSRF token 時得到一致的 403 `ApiResponse` envelope，合法 CSRF request 才可進入業務處理。
  3. 使用者登出後，後端 revoke refresh/session state 並清除 auth cookies，後續 cookie request 會得到一致的 401 envelope。
  4. 指定 Vue origin 可以用 credentials 呼叫後端，未允許 origin 被拒絕，non-browser bearer-token path 仍有明確且隔離的支援。
  5. 契約文件描述 auth cookies、CSRF token/header、refresh/logout、401/403、portfolio/trading DTO 與 backend/frontend 驗證責任。

**Plans**:

  - **Wave 1:** `01-PLAN.md` — Security Contract, Error Codes, and Test Harness
  - **Wave 2 *(blocked on Wave 1 completion)*:** `02-PLAN.md` — Browser Cookie Login/Register and Cookie Authentication
  - **Wave 3 *(blocked on Wave 2 completion)*:** `03-PLAN.md` — CSRF Bootstrap and Cookie Unsafe Request Enforcement
  - **Wave 4 *(blocked on Wave 3 completion)*:** `04-PLAN.md` — Refresh Rotation and Logout Current-Session Revocation
  - **Wave 5 *(blocked on Wave 4 completion)*:** `05-PLAN.md` — Bearer Compatibility, Documentation Closeout, and Final Verification

### Phase 2: Frontend Session & API Client Foundation

**Goal**: Vue API mode 具備一致的 session state 與 shared HTTP transport，後續 domain adapters 不再自行處理 auth/security 細節。
**Depends on**: Phase 1
**Requirements**: AUTH-03, AUTH-04, FAPI-01, FAPI-02, FAPI-03, FAPI-04, FAPI-05, FAPI-06, FAPI-07, FAPI-08
**Success Criteria** (what must be TRUE):

  1. 使用者重新整理 Vue app 後，app 會透過 `/api/v1/me` 還原 authenticated 或 anonymous state，而不保存 access token 或 refresh token。
  2. API mode 的所有 HTTP requests 都經過 shared `apiClient.ts`，預設送出 `credentials: "include"` 並統一解析 `ApiResponse<T>`、error code、message、request/trace id。
  3. Unsafe API mode requests 會自動帶 CSRF header，遇到 CSRF 403 時呈現可辨識錯誤而不是 generic failure。
  4. API client 遇到 401 時最多 refresh/replay 一次，失敗後停止 retry 並更新 session state，避免無限 refresh loop。
  5. Mock/API runtime mode 保留；API mode 後端不可用或 integration/prod invalid mode 時顯示錯誤/失敗狀態，不靜默退回 mock data。

**Plans**: 5 plans

  - **Wave 1:** `02-01-PLAN.md` — Shared transport cleanup, credentials default, trace id parsing, and paginated helper migration
  - **Wave 2 *(blocked on Wave 1 completion)*:** `02-02-PLAN.md` — CSRF bootstrap, unsafe headers, single-flight refresh, and one replay max
  - **Wave 3 *(blocked on Wave 2 completion)*:** `02-03-PLAN.md` — Browser auth API adapter and explicit non-token session state
  - **Wave 4 *(blocked on Wave 3 completion)*:** `02-04-PLAN.md` — App shell auth panel, session banner, header indicator, and i18n UI integration
  - **Wave 5 *(blocked on Wave 4 completion)*:** `02-05-PLAN.md` — Runtime mode hardening, API outage behavior, and final frontend verification

**UI hint**: yes

### Phase 3: Portfolio Read API Mode

**Goal**: 使用者在 API mode 可以從後端讀取 portfolio 狀態，且現有 mock mode 仍可獨立運作。
**Depends on**: Phase 2
**Requirements**: PORT-01, PORT-02, PORT-03, PORT-04, PORT-05, PORT-08
**Scope note**(2026-07-19 discuss 修訂): 本階段**包含後端 API 擴充** —— `GET /api/v1/trades` 需新增篩選(交易類型、日期區間)與排序(`executedAt` / 金額 `quantity × price` / `quantity`)參數。原因是 server-side 分頁一旦導入，client-side 的篩選與排序只會作用於當前頁，屬正確性缺陷。詳見 `phases/03-portfolio-read-api-mode/03-CONTEXT.md` 的 Phase Boundary 與 D-05/D-06。
**Success Criteria** (what must be TRUE):

  1. 使用者可以在現有 overview/portfolio UI 看到由 backend portfolio summary 映射出的資料。（註：現有 Overview KPI 為合成假資料，本項實為「取代」而非「接線」；「今日損益」「可用現金」「資產配置」後端無資料來源，API mode 隱藏並另記 todo — 見 03-CONTEXT.md D-14）
  2. 使用者可以在 positions UI 看到由 backend holdings/positions API 映射出的資料。
  3. 使用者可以在 trade history view 看到 backend trade history，包含分頁/排序 response mapping。
  4. Portfolio read views 在 API mode 顯示 loading、empty、error、retry 狀態，並保留 request/trace id 供除錯。
  5. Mock mode 仍使用 mock implementation；API mode 不直接讀寫 mock portfolio store。
  6. `GET /api/v1/trades` 支援篩選與排序參數，且前端篩選/排序在跨頁時語意正確（非只作用於當前頁）。

**Plans**: 5 plans

  - **Wave 1:** `03-01-PLAN.md` — 後端 `GET /trades` 篩選/排序參數、白名單 ORDER BY 與 V9 排序索引（PORT-08）
  - **Wave 1:** `03-02-PLAN.md` — 前端 portfolioApi 三件組、pageApiClients 註冊、DTO 型別與 i18n 地基（PORT-03/04）
  - **Wave 2 *(blocked on Wave 1 completion)*:** `03-03-PLAN.md` — Overview KPI 重建、D-14/D-16 隱藏清單與近期交易走 API（PORT-01/05）
  - **Wave 2 *(blocked on Wave 1 completion)*:** `03-04-PLAN.md` — Positions 後端欄位映射、weight/priceTime 衍生與合成區塊隱藏（PORT-02/05）
  - **Wave 2 *(blocked on Wave 1 completion)*:** `03-05-PLAN.md` — Trades server-side 篩選/排序/分頁、D-15 頁碼規則與全頁 CSV（PORT-03/05/08）

**UI hint**: yes

### Phase 4: Manual Trade Creation, Idempotency & Post-Trade Refetch

**Goal**: 使用者可以在 API mode 記錄 manual executed buy/sell trade，且 retry/double-click 不會造成重複交易或 stale portfolio state。
**Depends on**: Phase 3
**Requirements**: TRAD-01, TRAD-02, TRAD-03, TRAD-04, TRAD-05, TRAD-06
**Scope note**（2026-07-26 planning）: 本階段**橫跨兩個 git repository**（後端 `stock-web-v2` + sibling 前端 `vue/stock-v2`），且**包含後端改動三處**（V10 migration、必填 `Idempotency-Key` header 與 server-side 冪等、新 409 error code）。前端分支從 sibling repo 的 `develop @ a03e030` 開，後端分支從 `origin/develop` 開。DP-1 已裁定為 (c)：以 develop 為基準、只做冪等，`executedAt` 未來時間驗證與 `ApiTimeParser` **明確排除**在本階段範圍外（留給 draft PR #15）。
**Success Criteria** (what must be TRUE):

  1. 使用者在 API mode order ticket 送出的交易會明確建立 manual executed buy/sell trade，不傳送 pending order、cancel、time-in-force 等未支援欄位。
  2. 同一使用者以相同 idempotency key retry 或 double-click 時，後端不會重複建立交易或重複更新 holdings。
  3. Frontend trade submission 期間會阻擋重複送出，但 server-side idempotency 仍是最終保護。
  4. 交易成功後，Vue API mode 會重新讀取 portfolio summary、holdings/positions、trade history，畫面反映 backend truth。
  5. Trade validation、oversell、permission、CSRF、network 錯誤以使用者可理解的方式顯示，並保留 backend error code/request id。

**Plans**: 13 plans（8 waves；後端 5 + 前端 7 + 收尾閘門 1）。**2026-09-04 完成**：程式碼合併 develop（BE PR #20 / FE PR #9），`04-13` Task 1 稽核與 Task 2 人工檢查點皆通過（Yuan 走完 17 步回覆「都 ok」）。補跑證據：106 IT、Playwright 18/18、前端 379 tests × 三種模式。
Plans:

- [x] `04-01-PLAN.md` — 契約基石：`TRADE_IDEMPOTENCY_KEY_REUSED`(409) + V10 migration（`idempotency_key` 欄位 + partial unique index）+ DB 層行為 IT（wave 1，backend）
- [x] `04-06-PLAN.md` — `marketApi.ts` 三件組 + `AssetDto`/`KlineDto` 型別（D-01 的必要新 adapter，CONTEXT.md 未列）（wave 1，frontend）
- [x] `04-02-PLAN.md` — `TradeTransaction.idempotencyKey` + repository 的 key 查詢與 `ON CONFLICT DO NOTHING` 冪等 insert（wave 2，backend）
- [x] `04-07-PLAN.md` — `tradingApi.ts` 三件組 + `portfolioRevision.ts`（revision / lastFill / lastCreatedTradeId）（wave 2，frontend）
- [x] `04-03-PLAN.md` — `TradePayloadMatcher` 純函式 + `TradingService.createTrade` 改為 insert-first 冪等流程（wave 3，backend）
- [x] `04-08-PLAN.md` — `pageApiClients` 註冊 trading/market + 不回退 mock 的防線 + 42 個 i18n key（wave 3，frontend）
- [x] `04-04-PLAN.md` — 必填 `Idempotency-Key` header + `MissingRequestHeaderException` handler + `@WebMvcTest` 基礎設施（wave 4，backend）
- [x] `04-09-PLAN.md` — `OrderTicket.vue` 骨架重建：三步驟、假進度與亂數移除、fee/executedAt 欄位、D-04 隱藏、a11y（wave 4，frontend）
- [x] `04-05-PLAN.md` — `TradingApiIT` 端到端驗收：序列/併發冪等、跨使用者隔離、rollback 不燒 key、不回射 key（wave 5，backend）
- [x] `04-10-PLAN.md` — OrderTicket 真實資料：symbol typeahead 七態 + debounce/競態、報價卡、走勢圖三態（wave 5，frontend）
- [x] `04-11-PLAN.md` — OrderTicket 送出路徑：重複送出阻擋、D-14 key 生命週期、D-15 SELL 預檢、D-16 錯誤分派（wave 6，frontend）
- [x] `04-12-PLAN.md` — 三頁 post-trade 重讀、D-11 篩選保留與提示、D-12 分開呈現、D-13 fresh 高亮（含 DP-10 測試反轉）（wave 7，frontend）
- [x] `04-13-PLAN.md` — 收尾閘門：judgment §8 跨 repo 四項驗證 + 需求證據對照 + over-claim 稽核 + 人工確認（wave 8，checkpoint）

**UI hint**: yes

### Phase 04.1: Backend Data Gap Backfill (INSERTED)

**Goal**: 補齊四個「前端有 UI、後端無資料來源」的缺口，讓 Phase 5 的瀏覽器驗證能涵蓋完整畫面而非一堆隱藏區塊。
**Depends on**: Phase 4
**Requirements**: PORT-09, PORT-10, PORT-11, PORT-12, CASH-01, CASH-02, CASH-03, TRAD-10, ERR-01（2026-10-24 plan-phase 依 RESEARCH 提議註冊並擴充 D-24 / D-25 / D-27 / D-28；需求字面由 `04.1-01` Task 2 寫入 REQUIREMENTS.md）
**Insertion note**（2026-07-26）：**非緊急插入**。`(INSERTED)` 是 gsd 的結構標記，不代表急迫性。Yuan 於 Phase 4 discuss 時決定把四條後端資料缺口從 backlog 提前處理，排在 Phase 4 之後、Phase 5 之前，理由是 Phase 5 的跨 repo 瀏覽器驗證若在一堆 API-mode 隱藏區塊上跑，證明力會被削弱。
**Scope**（2026-07-26 查證的實際後端現況，四條成本差異很大）:

  1. **可用現金 / 帳戶餘額** — 後端**完全沒有**（`available_cash|cash_balance|balance|wallet` 全 repo 零命中）。新增領域模型，可能讓語意往「帳戶系統」偏移，需先確認是否符合 PROJECT.md 範圍（judgment §1）。**最貴的一條。**
  2. **日級損益** — 後端**沒有**。`market_prices` 有價格時序但無「當日持倉」維度，需日級持倉快照表或每日 job，並先定義「今日」（交易日/自然日、時區）。
  3. **資產分類（sector / assetClass）** — **後端其實有**：`assets.sector` 與 `assets.asset_type` 欄位存在且 V2 seed 有值，`AssetDto` 已回傳 `sector`。缺的只是 portfolio SQL 沒 JOIN、`HoldingDto` 無此欄位。成本接近「JOIN + DTO 欄位」，**最便宜的一條**（原 todo 檔描述為「新增領域模型」，過度悲觀，已於 04-CONTEXT.md 更正）。
  4. **watchlist API 化** — 後端**沒有**（整個 repo 只有 `Permission.WATCHLIST_MANAGE` 一個 enum 值，無表無 endpoint）。屬 PORT-06（原列 v2）。

**Scope update**(2026-09-28 discuss):第 1、2、3 條留在 04.1 並新增「基準幣與匯率折算」;第 4 條 watchlist 與 DIV 股利移至 **Phase 04.2**。決策見 `04.1-CONTEXT.md`。
**Success Criteria** (what must be TRUE)（每條都是「後端有資料 → API mode 顯示真實值」，不得是解除隱藏卻顯示 0）:

  1. 使用者在 Overview 快速切換或 Settings 欄位改基準幣後，後端儲存新值（`/api/v1/me` 回 `baseCurrency`），summary 與 holdings 以新基準幣重讀；USD 與 TWD 部位、已實現損益不再直接相加，Positions 權重以基準幣市值計算（PORT-09、PORT-10）。
  2. Overview 在 API mode 恰好顯示 4 張 KPI（總資產 `totalEquity` / 今日損益 `dayPricePnl` / 可用現金 `cash` / 總報酬率 `roi`），元件測試以刻意不同於 mock 的數值證明資料來自後端；缺前收或缺匯率時顯示 `—` 與「N 檔未計入」，不塞 0（PORT-10、PORT-11、CASH-02）。
  3. 使用者可記入金 / 出金（`Idempotency-Key` 冪等、append-only），可用現金依幣別即時推導、允許為負並提示；獨立「現金」頁顯示分頁紀錄並可一鍵沖銷，DB 層擋下重複沖銷 / 沖銷沖銷分錄 / 金額幣別不一致（CASH-01、CASH-02、CASH-03）。
  4. 資產配置 donut 與 Positions 產業分布在 API mode 以後端 `allocation` / `sectorAllocation` 渲染（`sector` null 顯示「未分類」）；持倉表顯示單檔今日損益（PORT-11、PORT-12）。
  5. Trades 頁、Overview 近期交易、OrderTicket review / result 的金額帶交易幣別（ISO 代碼後綴，不寫死 `$`）；格式錯誤的 JSON body 回 400 `VALIDATION_FAILED` 而非 500（TRAD-10、ERR-01）。
  6. 後端 unit + CI Integration job、前端 mock / API 雙模式測試 + build + lint、跨 repo 驗證（judgment §8）全綠，Yuan 完成瀏覽器走查。

**Plans**: 25 plans（7 waves；後端 14 + 前端 10 + 收尾閘門 1）。後端與前端在契約凍結後平行推進；本環境無 Docker，所有 `*IT` 以 CI Integration job 為證。2026-10-24 revision：依 plan-checker scope_sanity（每 plan < 15 檔）自 02 / 04 / 05 / 09 拆出 21 ~ 24，收尾閘門由 21 改號為 25。
Plans:

- [ ] `04.1-01-PLAN.md` — D-27 格式錯誤 JSON → 400（tracer：POST /trades）+ 需求註冊 + D-23 Facade 規則修訂（wave 1，backend）
- [ ] `04.1-02-PLAN.md` — PORT-09 後端：V14 `users.base_currency`、`/me` 回 `baseCurrency`、`PATCH /me/preferences`（wave 1，backend）
- [ ] `04.1-03-PLAN.md` — 參考資料 Facade：`AssetFacade.findProfiles` + `FxRateFacade` / `FxRates` 三角換算（wave 1，backend）
- [ ] `04.1-04-PLAN.md` — CASH-01 後端：V15 現金分錄表（append-only + 沖銷 DB 保護）+ `POST /portfolio/cash-entries` insert-first 寫入（wave 1，backend）
- [ ] `04.1-05-PLAN.md` — PORT-10 後端：summary / holdings 基準幣折算 + 快取 v2 鍵 + 契約 D-05（wave 3，backend）
- [ ] `04.1-06-PLAN.md` — CASH-03 後端：入出金分頁列表 + 一鍵沖銷（`reversalOfId`）+ PROJECT.md D-01（wave 3，backend）
- [ ] `04.1-07-PLAN.md` — PORT-11 後端：`DayPnlCalculator` 恆等式 + 單檔與總額今日損益（含今日賣光、D-26）（wave 4，backend）
- [ ] `04.1-08-PLAN.md` — TRAD-10 後端：`TradeDto.currency` 經 `AssetFacade`（wave 4，backend）
- [ ] `04.1-09-PLAN.md` — CASH-02 後端：summary `cash` / `cashBalances` / `totalEquity` + 現金分錄 commit 後失效（wave 5，backend）
- [ ] `04.1-10-PLAN.md` — PORT-12 後端：`allocation` / `sectorAllocation` + 契約檔收尾（wave 6，backend）
- [ ] `04.1-11-PLAN.md` — 前端：`moneyFormat` + Trades API 列幣別（tracer）+ 新 DTO 型別與 mock（wave 1，frontend）
- [ ] `04.1-12-PLAN.md` — 前端：`cashApi` / `profileApi` 三件組 + `pageApiClients` 註冊 + Phase 04.1 i18n 鍵（wave 1，frontend）
- [ ] `04.1-13-PLAN.md` — 前端：Settings 基準幣真欄位 + `MockDataNotice` 例外行（wave 2，frontend）
- [ ] `04.1-14-PLAN.md` — 前端：OrderTicket review / result 幣別 + Trades CSV `currency` 欄（wave 2，frontend）
- [ ] `04.1-15-PLAN.md` — 前端：`CashEntryDialog`（入金 / 出金 / 沖銷模式）+ `portfolioRevision` cause（wave 2，frontend）
- [ ] `04.1-16-PLAN.md` — 前端：Positions 今日損益欄、基準幣權重、幣別標籤、產業分布（wave 2，frontend）
- [ ] `04.1-17-PLAN.md` — 前端：「現金」頁 + 導覽 / CmdK / 捷徑登錄（wave 3，frontend）
- [ ] `04.1-18-PLAN.md` — 前端：Overview 4 KPI、基準幣切換、可用現金卡、資產配置、近期交易幣別（wave 4，frontend）
- [ ] `04.1-19-PLAN.md` — 前端：Playwright 窄螢幕版面回歸（UI-SPEC backstop）（wave 5，frontend）
- [ ] `04.1-20-PLAN.md` — 前端：Playwright 真後端旅程（基準幣持久化、入金 → 現金頁 → 沖銷、未入金買進的幣別與負現金）（wave 6，frontend）
- [ ] `04.1-21-PLAN.md` — PORT-09 後端：`UserPreferenceFacade` + `findBaseCurrency`（IT 讀回 PATCH 值）+ browser-auth 契約（wave 2，backend；自 02 拆出）
- [ ] `04.1-22-PLAN.md` — CASH-01 後端：入出金冪等 payload 比對 / 409 `CASH_IDEMPOTENCY_KEY_REUSED`、欄位驗證、稽核、header 綁定（wave 2，backend；自 04 拆出）
- [ ] `04.1-23-PLAN.md` — PORT-10 前置重構：summary / holdings 自 `TradingService` 抽出為 `PortfolioQueryService`（行為不變）（wave 1，backend；自 05 拆出）
- [ ] `04.1-24-PLAN.md` — CASH-02 計算核心：`CashFlow` + `CashBalanceCalculator` 純函式（wave 1，backend；自 09 拆出）
- [ ] `04.1-25-PLAN.md` — 收尾閘門：judgment §8 跨 repo 驗證 + 需求證據 + CI 證據 + Yuan 瀏覽器走查（wave 7，checkpoint；原 04.1-21）

**UI hint**: yes（受影響畫面：Overview KPI / 基準幣切換 / 可用現金 / 資產配置 donut / 近期交易、Positions 今日損益與 sector breakdown、Settings 基準幣、新「現金」頁、Trades 與 OrderTicket 幣別）

### Phase 04.2: Dividends, Share Adjustments & Watchlist (INSERTED)

**Goal**: 補齊「股利與股數調整」(現金股利、配股、拆股 / 合股)與 Watchlist(多個具名清單)的後端資料與前端 API mode,讓 Trades 的股利頁籤、Chart / Markets / Watchlist 頁不再依賴 mock。
**Depends on**: Phase 04.1(現金帳與今日損益是股利入帳與拆股前收調整的基礎)
**Requirements**: TBD(`/gsd-plan-phase 04.2` 時定)
**Insertion note**(2026-09-28):04.1 discuss 時範圍擴大(現金帳、基準幣、股利三種事件、多清單 watchlist),Yuan 決定拆成 04.1 / 04.2;決策見 `.planning/phases/04.2-dividends-share-adjustments-watchlist/04.2-CONTEXT.md`。
**Plans**: TBD
**UI hint**: yes(Positions「股利與股數調整」對話框、Trades 頁籤、Chart / Markets 星號選清單、Watchlist 頁)

### Phase 5: Cross-Repo Browser Flow Verification & Contract Hardening

**Goal**: Backend 與 sibling Vue repo 的自動測試和真實瀏覽器流程共同證明整合契約可用。
**Depends on**: Phase 4
**Requirements**: VER-01, VER-02, VER-03
**Success Criteria** (what must be TRUE):

  1. Backend Maven tests 覆蓋 auth cookie、CSRF、CORS、refresh/logout、401/403 envelope 與 trading idempotency。
  2. Frontend Vitest/type-check/build 覆蓋 API client、auth store、runtime mode、portfolio adapters 與 trading adapter。
  3. Cross-repo browser smoke flow 可以完成 login -> `/me` -> portfolio reads -> create manual trade -> refetch -> logout。
  4. Browser verification 可確認 auth cookies 為 httpOnly、CSRF token/header 正確、protected requests 帶 credentials、logout 後 `/me` 回 401。
  5. API mode integration failure 會顯示錯誤/重試狀態，不會被 mock data 掩蓋。

**Plans**: TBD
**UI hint**: yes

## Progress

**Execution Order:**
Phases execute in numeric order: 1 -> 2 -> 3 -> 4 -> 4.1 -> 4.2 -> 5

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Browser Auth Contract & Backend Security Foundation | 5/5 | Completed | 2026-05-30 |
| 2. Frontend Session & API Client Foundation | 5/5 | Completed | 2026-05-31 |
| 3. Portfolio Read API Mode | 5/5 | Completed | 2026-07-26 |
| 4. Manual Trade Creation, Idempotency & Post-Trade Refetch | 13/13 | Completed | 2026-09-04 |
| 04.1 Backend Data Gap Backfill (INSERTED) | 0/25 | Planned | - |
| 04.2 Dividends, Share Adjustments & Watchlist (INSERTED) | 0/TBD | Context gathered | - |
| 5. Cross-Repo Browser Flow Verification & Contract Hardening | 0/TBD | Not started | - |
