# Requirements: Stock Web V2

**Defined:** 2026-05-30  
**Core Value:** Users can safely sign in, inspect portfolio state, and record trades through one coherent frontend/backend flow.

## v1 Requirements

### Authentication

- [ ] **AUTH-01**: 使用者可以用 email/password 註冊帳號，瀏覽器流程不需要讀取 refresh token。
- [ ] **AUTH-02**: 使用者可以用 email/password 登入，後端會設定 browser-safe session cookies。
- [ ] **AUTH-03**: 使用者重新整理 Vue app 後，可以透過 `/api/v1/me` 還原登入狀態。
- [ ] **AUTH-04**: 使用者 session 過期時，Vue app 可以透過 `/api/v1/auth/refresh` 嘗試一次 session refresh。
- [ ] **AUTH-05**: 使用者可以從 app 登出，後端會 revoke refresh/session state 並清除 auth cookies。
- [ ] **AUTH-06**: 未登入請求會得到一致的 401 `ApiResponse` envelope，已登入但權限不足會得到一致的 403 envelope。
- [ ] **AUTH-07**: 非瀏覽器 API client 仍可使用明確定義的 bearer-token path，不與 browser cookie path 混淆。

### Security

- [ ] **SEC-01**: Browser auth 使用 httpOnly cookie 承載 access/refresh session，不把 refresh token 暴露給 Vue JavaScript。
- [ ] **SEC-02**: Cookie-authenticated unsafe requests 必須通過 double-submit CSRF token 驗證。
- [ ] **SEC-03**: 後端提供或設定可被 Vue 讀取的 CSRF token contract，並要求 unsafe methods 送出對應 header。
- [ ] **SEC-04**: CORS 設定允許指定 Vue origin 使用 credentials，並拒絕未允許的 origin。
- [ ] **SEC-05**: Backend security tests 覆蓋 cookie GET、cookie unsafe POST with/without CSRF、bearer request、401、403、refresh、logout。

### Frontend API Foundation

- [ ] **FAPI-01**: Vue API mode 的所有 HTTP request 都經過 shared `apiClient.ts` transport boundary。
- [ ] **FAPI-02**: Shared API client 在 API mode 預設送出 `credentials: "include"`。
- [ ] **FAPI-03**: Shared API client 對 unsafe methods 加上 CSRF header，並可處理 CSRF 403 錯誤。
- [ ] **FAPI-04**: Shared API client 統一解析 backend `ApiResponse<T>` envelope、錯誤 code、message、request/trace id。
- [ ] **FAPI-05**: Shared API client 遇到 401 時最多嘗試一次 refresh/replay，避免無限 refresh loop。
- [ ] **FAPI-06**: Vue auth store 不保存 access token 或 refresh token，只保存必要的 user/session UI state。
- [ ] **FAPI-07**: API mode 錯誤或後端不可用時顯示錯誤/重試狀態，不靜默退回 mock data。
- [ ] **FAPI-08**: Mock/API runtime mode 保留，但 integration/prod 類型環境不得因 invalid mode 靜默 fallback 到 mock。

### Portfolio Read

- [ ] **PORT-01**: Vue API mode 可以從 backend 讀取 portfolio summary 並映射到現有 overview/portfolio UI。
- [ ] **PORT-02**: Vue API mode 可以從 backend 讀取 holdings/positions 並映射到現有 positions UI。
- [ ] **PORT-03**: Vue API mode 可以從 backend 讀取 trade history，包含分頁/排序需要的 API response mapping。
- [ ] **PORT-04**: Portfolio API adapters 保留 mock implementation，API mode 不直接讀寫 mock portfolio store。
- [ ] **PORT-05**: Portfolio read views 提供 loading、empty、error、retry 狀態，並保留 request/trace id 以利除錯。

### Trading

- [ ] **TRAD-01**: Vue order ticket 在 API mode 建立 manual executed buy/sell trade，而不是 broker order。
- [ ] **TRAD-02**: Trade creation request 明確映射到 backend `CreateTradeRequest` contract，避免傳送 pending order、cancel、time-in-force 等未支援欄位。
- [ ] **TRAD-03**: Backend trade creation 支援 server-side idempotency，避免同一使用者 retry/double-click 建立重複交易或重複更新 holdings。
- [ ] **TRAD-04**: Frontend trade submission 提供 duplicate-submit guard，但不取代 server-side idempotency。
- [ ] **TRAD-05**: Trade creation 成功後，Vue API mode 會重新讀取 portfolio summary、holdings/positions、trade history。
- [ ] **TRAD-06**: Trade validation、oversell、permission、CSRF、network 錯誤會以使用者可理解的方式顯示，並保留 backend error code/request id。

### Verification

- [ ] **VER-01**: Backend Maven tests 覆蓋 auth cookie、CSRF、CORS、refresh/logout、401/403 envelope、trading idempotency。
- [ ] **VER-02**: Frontend Vitest/type-check/build 覆蓋 API client、auth store、runtime mode、portfolio adapters、trading adapter。
- [ ] **VER-03**: Cross-repo browser smoke flow 可驗證 login -> `/me` -> portfolio reads -> create manual trade -> refetch -> logout。
- [ ] **VER-04**: Contract documentation 描述 auth cookies、CSRF header、refresh/logout、401/403、portfolio/trading DTO、驗證責任。

## v2 Requirements

### Expanded Portfolio

- **PORT-06**: Vue API mode 支援 analytics、alerts、notifications、watchlists、settings 等完整 portfolio-adjacent 頁面。
- **PORT-07**: Portfolio read models 支援更完整的績效分析、時間序列、風險與資產配置資料。

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
| AUTH-01 | Phase 1 | Pending |
| AUTH-02 | Phase 1 | Pending |
| AUTH-03 | Phase 2 | Pending |
| AUTH-04 | Phase 2 | Pending |
| AUTH-05 | Phase 1 | Pending |
| AUTH-06 | Phase 1 | Pending |
| AUTH-07 | Phase 1 | Pending |
| SEC-01 | Phase 1 | Pending |
| SEC-02 | Phase 1 | Pending |
| SEC-03 | Phase 1 | Pending |
| SEC-04 | Phase 1 | Pending |
| SEC-05 | Phase 1 | Pending |
| FAPI-01 | Phase 2 | Pending |
| FAPI-02 | Phase 2 | Pending |
| FAPI-03 | Phase 2 | Pending |
| FAPI-04 | Phase 2 | Pending |
| FAPI-05 | Phase 2 | Pending |
| FAPI-06 | Phase 2 | Pending |
| FAPI-07 | Phase 2 | Pending |
| FAPI-08 | Phase 2 | Pending |
| PORT-01 | Phase 3 | Pending |
| PORT-02 | Phase 3 | Pending |
| PORT-03 | Phase 3 | Pending |
| PORT-04 | Phase 3 | Pending |
| PORT-05 | Phase 3 | Pending |
| TRAD-01 | Phase 4 | Pending |
| TRAD-02 | Phase 4 | Pending |
| TRAD-03 | Phase 4 | Pending |
| TRAD-04 | Phase 4 | Pending |
| TRAD-05 | Phase 4 | Pending |
| TRAD-06 | Phase 4 | Pending |
| VER-01 | Phase 5 | Pending |
| VER-02 | Phase 5 | Pending |
| VER-03 | Phase 5 | Pending |
| VER-04 | Phase 1 | Pending |

**Coverage:**
- v1 requirements: 35 total
- Mapped to phases: 35
- Unmapped: 0

---
*Requirements defined: 2026-05-30*  
*Last updated: 2026-05-30 after initialization*
