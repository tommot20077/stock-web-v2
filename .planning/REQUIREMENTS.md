# Requirements: Stock Web V2

**Defined:** 2026-05-30  
**Core Value:** Users can safely sign in, inspect portfolio state, and record trades through one coherent frontend/backend flow.

## v1 Requirements

### Authentication

- [x] **AUTH-01**: 使用者可以用 email/password 註冊帳號，瀏覽器流程不需要讀取 refresh token。
- [x] **AUTH-02**: 使用者可以用 email/password 登入，後端會設定 browser-safe session cookies。
- [x] **AUTH-03**: 使用者重新整理 Vue app 後，可以透過 `/api/v1/me` 還原登入狀態。
- [x] **AUTH-04**: 使用者 session 過期時，Vue app 可以透過 `/api/v1/auth/refresh` 嘗試一次 session refresh。
- [x] **AUTH-05**: 使用者可以從 app 登出，後端會 revoke refresh/session state 並清除 auth cookies。
- [x] **AUTH-06**: 未登入請求會得到一致的 401 `ApiResponse` envelope，已登入但權限不足會得到一致的 403 envelope。
- [x] **AUTH-07**: 非瀏覽器 API client 仍可使用明確定義的 bearer-token path，不與 browser cookie path 混淆。

### Security

- [x] **SEC-01**: Browser auth 使用 httpOnly cookie 承載 access/refresh session，不把 refresh token 暴露給 Vue JavaScript。
- [x] **SEC-02**: Cookie-authenticated unsafe requests 必須通過 double-submit CSRF token 驗證。
- [x] **SEC-03**: 後端提供或設定可被 Vue 讀取的 CSRF token contract，並要求 unsafe methods 送出對應 header。
- [x] **SEC-04**: CORS 設定允許指定 Vue origin 使用 credentials，並拒絕未允許的 origin。
- [x] **SEC-05**: Backend security tests 覆蓋 cookie GET、cookie unsafe POST with/without CSRF、bearer request、401、403、refresh、logout。

### Frontend API Foundation

- [x] **FAPI-01**: Vue API mode 的所有 HTTP request 都經過 shared `apiClient.ts` transport boundary。
- [x] **FAPI-02**: Shared API client 在 API mode 預設送出 `credentials: "include"`。
- [x] **FAPI-03**: Shared API client 對 unsafe methods 加上 CSRF header，並可處理 CSRF 403 錯誤。
- [x] **FAPI-04**: Shared API client 統一解析 backend `ApiResponse<T>` envelope、錯誤 code、message、request/trace id。
- [x] **FAPI-05**: Shared API client 遇到 401 時最多嘗試一次 refresh/replay，避免無限 refresh loop。
- [x] **FAPI-06**: Vue auth store 不保存 access token 或 refresh token，只保存必要的 user/session UI state。
- [x] **FAPI-07**: API mode 錯誤或後端不可用時顯示錯誤/重試狀態，不靜默退回 mock data。
- [x] **FAPI-08**: Mock/API runtime mode 保留，但 integration/prod 類型環境不得因 invalid mode 靜默 fallback 到 mock。

### Portfolio Read

- [x] **PORT-01**: Vue API mode 可以從 backend 讀取 portfolio summary 並映射到現有 overview/portfolio UI。
- [x] **PORT-02**: Vue API mode 可以從 backend 讀取 holdings/positions 並映射到現有 positions UI。
- [x] **PORT-03**: Vue API mode 可以從 backend 讀取 trade history，包含分頁/排序需要的 API response mapping。
- [x] **PORT-04**: Portfolio API adapters 保留 mock implementation，API mode 不直接讀寫 mock portfolio store。
- [x] **PORT-05**: Portfolio read views 提供 loading、empty、error、retry 狀態，並保留 request/trace id 以利除錯。
- [x] **PORT-08**: `GET /api/v1/trades` 提供篩選（交易類型、日期區間）與排序（`executedAt` / 金額 `quantity × price` / `quantity`）參數，使前端在 server-side 分頁下的篩選與排序語意正確。
      *(2026-07-19 Phase 3 discuss 新增。理由：分頁導入後 client-side 篩選/排序只作用於當前頁，是正確性缺陷而非體驗問題。金額為計算值，後端需以 `quantity * price` 計算式排序並評估運算式索引。)*
- [x] **PORT-09**: 使用者有後端儲存的基準幣（`users.base_currency`，預設 TWD），`/api/v1/me` 回傳 `baseCurrency`，可經 `PATCH /api/v1/me/preferences` 修改（cookie 路徑須 CSRF）；Overview 快速切換與 Settings 欄位都寫回後端並觸發重讀。（D-02、D-03）
- [x] **PORT-10**: summary 與 holdings 以基準幣折算：summary 金額（含 realized PnL）以 `baseCurrency` 表示，匯率直接 → 反向 → 經 USD 三角換算，換不到的部位不計入並回報 `fxExcludedCount` / `unconvertedCurrencies`；`HoldingDto` 帶原幣 `currency` 與 `marketValueInBase`；前端金額一律以 ISO 代碼後綴顯示、Positions 權重以基準幣市值計算。（D-04、D-05、D-21、D-22、D-23、D-30）
- [x] **PORT-11**: 今日價格損益：`HoldingDto` 單檔與 summary 總額，依各檔市場最近交易日（`TradingDay` / `findDailyQuotes`）、D-12 算法、D-16 分母；今日首次買入照算；計算所需價格缺失時回 `null` 並回報 `dayPnlExcludedCount`；只含價格損益、不含匯差與手續費。（D-12 ~ D-17、D-26）
- [x] **PORT-12**: 資產配置（`asset_type` + 正現金，基準幣）與產業分組（`assets.sector`，null = 未分類）由後端計算（summary `allocation` / `sectorAllocation`）；`HoldingDto` 帶 `assetType` / `sector`；資產屬性一律經 `AssetFacade`，trading 不得 SQL JOIN `assets` 取新屬性；Overview donut 與 Positions 產業分布在 API mode 顯示真實值。（D-18 ~ D-21）

### Cash Ledger

- [x] **CASH-01**: 使用者可依幣別記入金 / 出金（`POST /api/v1/portfolio/cash-entries`），分錄 append-only（DB trigger 保護）、`Idempotency-Key` 必填且冪等（同 key 不同 payload → 409 `CASH_IDEMPOTENCY_KEY_REUSED`）；入出金對話框沿用 Phase 4 key 生命週期與成功後重讀；現金帳是記帳不是券商帳戶。（D-01、D-06、D-09、D-11、D-30）
- [x] **CASH-02**: 可用現金依幣別即時推導（入出金 ± 全部交易現金流，依標的幣別），不存餘額欄位、允許為負；summary 回 `cash`（基準幣）、`cashBalances`、`totalEquity`；Overview 可用現金卡顯示並在任一幣別為負時提示。（D-07、D-08、D-10、D-22）
- [x] **CASH-03**: 入出金紀錄唯讀分頁列表（`GET /api/v1/portfolio/cash-entries`）與一鍵沖銷：沖銷分錄以 `reversal_of_id` 指回原分錄，DB 層擋下重複沖銷、沖銷沖銷分錄、金額 / 幣別 / 方向不一致；獨立「現金」頁顯示列表與「已沖銷」狀態。（D-24、D-25、D-29）

### Trading

- [x] **TRAD-01**: Vue order ticket 在 API mode 建立 manual executed buy/sell trade，而不是 broker order。
- [x] **TRAD-02**: Trade creation request 明確映射到 backend `CreateTradeRequest` contract，避免傳送 pending order、cancel、time-in-force 等未支援欄位。
- [x] **TRAD-03**: Backend trade creation 支援 server-side idempotency，避免同一使用者 retry/double-click 建立重複交易或重複更新 holdings。
- [x] **TRAD-04**: Frontend trade submission 提供 duplicate-submit guard，但不取代 server-side idempotency。
- [x] **TRAD-05**: Trade creation 成功後，Vue API mode 會重新讀取 portfolio summary、holdings/positions、trade history。
- [x] **TRAD-06**: Trade validation、oversell、permission、CSRF、network 錯誤會以使用者可理解的方式顯示，並保留 backend error code/request id。
- [x] **TRAD-10**: `TradeDto` 帶標的交易幣別 `currency`（經 `AssetFacade`，不 SQL JOIN `assets`）；Trades 頁、Overview 近期交易、OrderTicket review / result 以 ISO 代碼顯示金額，Trades CSV 加 `currency` 欄。（D-28、D-30）

### Error Handling

- [x] **ERR-01**: 格式錯誤的 request body（`HttpMessageNotReadableException`）回 400 `VALIDATION_FAILED`（靜態訊息、不回射輸入），不再落入 catch-all 500。（D-27）

### Verification

- [ ] **VER-01**: Backend Maven tests 覆蓋 auth cookie、CSRF、CORS、refresh/logout、401/403 envelope、trading idempotency。
- [ ] **VER-02**: Frontend Vitest/type-check/build 覆蓋 API client、auth store、runtime mode、portfolio adapters、trading adapter。
- [ ] **VER-03**: Cross-repo browser smoke flow 可驗證 login -> `/me` -> portfolio reads -> create manual trade -> refetch -> logout。
- [x] **VER-04**: Contract documentation 描述 auth cookies、CSRF header、refresh/logout、401/403、portfolio/trading DTO、驗證責任。

## v2 Requirements

### Expanded Portfolio

- **PORT-06**: Vue API mode 支援 analytics、alerts、notifications、watchlists、settings 等完整 portfolio-adjacent 頁面。
- **PORT-07**: Portfolio read models 支援更完整的績效分析、時間序列、風險與資產配置資料。*(資產配置部分已由 PORT-12 於 Phase 04.1 提前提供)*

### Trading Evolution

- **TRAD-07**: 系統支援 pending orders、order status、cancel、partial fills、time-in-force。
- **TRAD-08**: 系統支援真實 broker integration、broker credentials、實際成交回報與法遵要求。
- **TRAD-09**: 系統支援 AI-assisted trading policy enforcement、human-in-the-loop approval 與 broker/AI audit trails。

### Account Management

- **AUTH-08**: 使用者可以管理多裝置 session、查看 active sessions、revoke specific device。
- **AUTH-09**: 使用者可以重設密碼、驗證 email、或使用其他登入方式。

## Out of Scope

| Feature | Reason |
|---------|--------|
| Real broker integration | 本 milestone 只記錄 manual executed trades；broker 串接需要獨立安全、法遵、credential 與 order lifecycle 設計。 |
| Pending orders / cancellations / partial fills / time-in-force | 現有 backend contract 是交易紀錄，不是 order management system。 |
| AI trading policy and broker credential APIs | 目前前端 AI/broker settings 是 mock UX；真實 policy enforcement 需要獨立 backend design。 |
| Full API mode for alerts, notifications, analytics, settings, watchlists, and ops | 先完成 auth + core portfolio/trading vertical slice，再擴張其他頁面。 |
| Large Vue redesign | 本 milestone 是 integration-first，只做必要狀態、錯誤、文案與 wiring。 |
| localStorage/sessionStorage refresh token storage | refresh token 不應暴露給 browser JavaScript。 |
| Cookie auth without CSRF | Credentialed browser unsafe requests 必須有 CSRF 防護。 |
| Silent API-mode fallback to mock data | 會掩蓋整合失敗，不符合驗收需求。 |

## Traceability

Roadmap creation will map each v1 requirement to exactly one phase.

| Requirement | Phase | Status |
|-------------|-------|--------|
| AUTH-01 | Phase 1 | Complete |
| AUTH-02 | Phase 1 | Complete |
| AUTH-03 | Phase 2 | Complete |
| AUTH-04 | Phase 2 | Complete |
| AUTH-05 | Phase 1 | Complete |
| AUTH-06 | Phase 1 | Complete |
| AUTH-07 | Phase 1 | Complete |
| SEC-01 | Phase 1 | Complete |
| SEC-02 | Phase 1 | Complete |
| SEC-03 | Phase 1 | Complete |
| SEC-04 | Phase 1 | Complete |
| SEC-05 | Phase 1 | Complete |
| FAPI-01 | Phase 2 | Complete |
| FAPI-02 | Phase 2 | Complete |
| FAPI-03 | Phase 2 | Complete |
| FAPI-04 | Phase 2 | Complete |
| FAPI-05 | Phase 2 | Complete |
| FAPI-06 | Phase 2 | Complete |
| FAPI-07 | Phase 2 | Complete |
| FAPI-08 | Phase 2 | Complete |
| PORT-01 | Phase 3 | Complete |
| PORT-02 | Phase 3 | Complete |
| PORT-03 | Phase 3 | Complete |
| PORT-04 | Phase 3 | Complete |
| PORT-05 | Phase 3 | Complete |
| PORT-08 | Phase 3 | Complete |
| TRAD-01 | Phase 4 | Complete |
| TRAD-02 | Phase 4 | Complete |
| TRAD-03 | Phase 4 | Complete |
| TRAD-04 | Phase 4 | Complete |
| TRAD-05 | Phase 4 | Complete |
| TRAD-06 | Phase 4 | Complete |
| VER-01 | Phase 5 | Pending |
| VER-02 | Phase 5 | Pending |
| VER-03 | Phase 5 | Pending |
| VER-04 | Phase 1 | Complete |
| PORT-09 | Phase 04.1 | Complete |
| PORT-10 | Phase 04.1 | Complete |
| PORT-11 | Phase 04.1 | Complete |
| PORT-12 | Phase 04.1 | Complete |
| CASH-01 | Phase 04.1 | Complete |
| CASH-02 | Phase 04.1 | Complete |
| CASH-03 | Phase 04.1 | Complete |
| TRAD-10 | Phase 04.1 | Complete |
| ERR-01 | Phase 04.1 | Complete |

2026-09-27 狀態回填(依測試證據,非依 phase 完成宣稱):
- AUTH-01/02/05/06/07、SEC-01~05:`BrowserAuthFlowIT`、`AuthFlowIT`、`CorsIT`、`RefreshTokenRotationIT`、`LogoutInvalidatesAccessTokenIT`、`MethodSecurityDenialIT`(stock-start)。
- TRAD-01~06:後端 `TradingApiIT`、`TransactionsIdempotencyIT`;前端 `OrderTicket.test.ts`(Test 22 送出中 disabled、Test 25-32 冪等 key、Test 39-40 錯誤碼 / traceId、Test 47 超賣);瀏覽器 `e2e/tests/trade.spec.ts` T1-T3。Phase 4 的 04-13 Task 2 已於 2026-09-04 由 Yuan 親自走完 17 步通過。
- VER-04:`ai-docs/browser-auth-contract.md` + `ai-docs/trading-portfolio-contract.md`。
- 未勾:VER-01~03(Phase 5 範圍)。

2026-10-23 狀態回填:
- FAPI-08:前端 `runtimeDataMode.test.ts`(明確但無效的 `VITE_DATA_MODE` 丟 `RuntimeDataModeError`)、`App.vue` 錯誤畫面不發 fetch(02-05 決策);9/2 功能審查亦判 ✓。

**Coverage:**
- v1 requirements: 45 total
- Mapped to phases: 45
- Unmapped: 0

---
*Requirements defined: 2026-05-30*  
*Last updated: 2026-10-24 Phase 04.1 plan-phase 註冊九條需求*
