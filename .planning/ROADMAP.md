# Roadmap: Stock Web V2

## Overview

本 milestone 先收斂瀏覽器安全認證合約與後端安全基礎，再建立 Vue API mode 的 session/API client transport 邊界，接著依序把 portfolio read 與 manual executed trade creation 接到後端，最後用跨 repo browser flow 驗證 cookie、CSRF、CORS、refresh、portfolio refetch 與 logout 在真實瀏覽器流程中一致運作。範圍明確排除 broker/order lifecycle、pending/cancel/partial fill、AI trading policy 與大型 UI redesign。

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

- [x] **Phase 1: Browser Auth Contract & Backend Security Foundation** - 後端提供完整 cookie auth、CSRF、refresh/logout、401/403、bearer compatibility 與契約文件。
- [ ] **Phase 2: Frontend Session & API Client Foundation** - Vue API mode 透過唯一 shared client 處理 credentials、CSRF、envelope、refresh retry、session restore 與 runtime mode。
- [ ] **Phase 3: Portfolio Read API Mode** - Vue API mode 可以讀取 portfolio summary、holdings/positions、trade history 並呈現 loading/empty/error/retry 狀態。
- [ ] **Phase 4: Manual Trade Creation, Idempotency & Post-Trade Refetch** - API mode order ticket 建立 manual executed trade，後端防重，成功後重新讀取 portfolio 狀態。
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
**Requirements**: PORT-01, PORT-02, PORT-03, PORT-04, PORT-05
**Success Criteria** (what must be TRUE):
  1. 使用者可以在現有 overview/portfolio UI 看到由 backend portfolio summary 映射出的資料。
  2. 使用者可以在 positions UI 看到由 backend holdings/positions API 映射出的資料。
  3. 使用者可以在 trade history view 看到 backend trade history，包含分頁/排序 response mapping。
  4. Portfolio read views 在 API mode 顯示 loading、empty、error、retry 狀態，並保留 request/trace id 供除錯。
  5. Mock mode 仍使用 mock implementation；API mode 不直接讀寫 mock portfolio store。
**Plans**: TBD
**UI hint**: yes

### Phase 4: Manual Trade Creation, Idempotency & Post-Trade Refetch
**Goal**: 使用者可以在 API mode 記錄 manual executed buy/sell trade，且 retry/double-click 不會造成重複交易或 stale portfolio state。
**Depends on**: Phase 3
**Requirements**: TRAD-01, TRAD-02, TRAD-03, TRAD-04, TRAD-05, TRAD-06
**Success Criteria** (what must be TRUE):
  1. 使用者在 API mode order ticket 送出的交易會明確建立 manual executed buy/sell trade，不傳送 pending order、cancel、time-in-force 等未支援欄位。
  2. 同一使用者以相同 idempotency key retry 或 double-click 時，後端不會重複建立交易或重複更新 holdings。
  3. Frontend trade submission 期間會阻擋重複送出，但 server-side idempotency 仍是最終保護。
  4. 交易成功後，Vue API mode 會重新讀取 portfolio summary、holdings/positions、trade history，畫面反映 backend truth。
  5. Trade validation、oversell、permission、CSRF、network 錯誤以使用者可理解的方式顯示，並保留 backend error code/request id。
**Plans**: TBD
**UI hint**: yes

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
Phases execute in numeric order: 1 -> 2 -> 3 -> 4 -> 5

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Browser Auth Contract & Backend Security Foundation | 5/5 | Completed | 2026-05-30 |
| 2. Frontend Session & API Client Foundation | 2/5 | In Progress | - |
| 3. Portfolio Read API Mode | 0/TBD | Not started | - |
| 4. Manual Trade Creation, Idempotency & Post-Trade Refetch | 0/TBD | Not started | - |
| 5. Cross-Repo Browser Flow Verification & Contract Hardening | 0/TBD | Not started | - |
