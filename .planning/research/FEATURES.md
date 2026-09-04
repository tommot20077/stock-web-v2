# Feature Landscape

**Domain:** 股票交易 Web App 的安全瀏覽器認證與核心投資組合/交易 API 整合  
**Researched:** 2026-05-30  
**Scope:** 下一個 milestone 僅聚焦 browser-safe auth、CSRF、API mode、portfolio/trading vertical slice。交易語意是「已成交手動交易紀錄」，不是券商下單系統。

## 研究結論

這個 milestone 的 v1 必須先證明一條安全、可測、可回復的端到端路徑：使用者能在 Vue 瀏覽器端註冊/登入，透過 httpOnly cookie session 與 CSRF token 呼叫受保護 API，讀取 portfolio summary、holdings/positions、trade history，並建立一筆已成交的 buy/sell trade。成功建立交易後，前端必須重新讀取後端狀態，而不是只改本地 mock store。

最重要的切分原則是：認證與 CSRF 是所有 API mode 的地基，portfolio/trading 整合是第一個高價值驗證場景。任何 broker order、pending order、partial fill、AI policy、watchlist/settings/alert 全面 API 化都會把 milestone 拉成另一個產品，應明確延後。

## Table Stakes

這些是 v1 缺一不可的功能。缺任一項，API mode 不是不能用，就是安全性或資料一致性不可靠。

| Feature | 為什麼是必要 | 可驗收行為 | Complexity | Risk | Notes |
|---------|--------------|------------|------------|------|-------|
| Browser-safe login/register contract | 目前 token 由 JSON 回傳，瀏覽器安全目標要求不要把 refresh token 暴露給 JavaScript | 使用者可註冊/登入；後端設定 httpOnly cookie；回應仍使用既有 `ApiResponse<T>` envelope；前端不直接讀 refresh token | High | High | 需保留 bearer token 給非瀏覽器/API clients，但 browser mode 應走 cookie contract |
| Session restore via `/api/v1/me` | 重新整理頁面後必須知道目前是否已登入 | app 啟動時呼叫 `/me`；成功時填入 user/session state；401 時進入未登入狀態且不視為 fatal error | Medium | High | 是 protected route 與 API mode UX 的共同基礎 |
| Refresh endpoint and 401 handling | access token/cookie 過期時不能讓每個 domain client 各自處理 | 受保護 API 收到 401 後走單一路徑嘗試 refresh；refresh 失敗則清 session 並導向登入或顯示登入狀態 | High | High | 避免多個並行請求重複 refresh，可先用簡單 single-flight guard |
| Logout and server-side revocation | 登出必須清除 cookie 並撤銷 refresh/session 狀態 | 呼叫 logout 後 cookie 被清除；後續 `/me` 與 protected APIs 回 401；前端清掉 user state | Medium | High | logout 是 unsafe request，必須套用 CSRF |
| Double-submit CSRF token contract | Cookie-authenticated unsafe requests 若無 CSRF 防護會有跨站提交風險 | 前端可取得非 httpOnly CSRF token；unsafe methods 帶 `X-CSRF-Token`；缺失/錯誤 token 的 POST/PUT/PATCH/DELETE 被拒絕 | High | High | 最少需覆蓋 login/logout/refresh/trade creation；實作時需定義 token 取得、輪替與錯誤碼 |
| Shared frontend API client for credentials/envelope/errors | 現有前端 HTTP envelope parsing 分散，auth/CSRF/trace 行為會漂移 | 所有 API mode clients 透過同一 request helper；自動 `credentials: "include"`；統一解析 `ApiResponse<T>`、錯誤 envelope、request/trace id | Medium | High | 這是後續 portfolio/trading adapters 的前置工程 |
| Explicit mock/API runtime mode | 既有 Vue app 依賴 mock mode，API mode 必須 additive 且不誤切 | `VITE_DATA_MODE=api` 時使用 HTTP adapters；mock mode 保留；無效 mode 在整合環境不可靜默回 mock | Medium | Medium | 對 demo 與前端開發很重要，但整合驗證必須能強制 API mode |
| Frontend auth service/store | UI、route guard、domain clients 需要同一份 session 狀態 | 提供 `login/register/logout/restoreSession`；保存 user/session status；不保存 refresh token；暴露 loading/error states | Medium | Medium | 可放在 services/composable/store，需符合 Vue 既有模式 |
| Protected API mode navigation | 未登入者不應直接操作 portfolio/trading API mode | API mode 下 portfolio/trading 相關頁面或操作需要 session；未登入顯示登入入口或阻止提交 | Medium | Medium | 不必大改 router；可先在 App/page/service 邊界處理 |
| Portfolio summary API adapter | portfolio overview 是交易後狀態驗證的核心讀模型 | API mode 呼叫後端 summary endpoint；顯示總市值、損益、現金/成本等既有後端可提供欄位；錯誤狀態可見 | Medium | Medium | 不要求完整 analytics，只接既有 trading summary |
| Holdings/positions API adapter | 使用者必須能看到目前持倉，且交易後持倉更新 | API mode 呼叫 holdings/positions endpoint；空狀態、loading、error、資料格式 mapping 都可測 | Medium | Medium | 避免把 mock-only 欄位當成後端事實 |
| Trade history API adapter | 建立交易後要能回看交易紀錄 | API mode 呼叫 trade history/list endpoint；支援後端既有 pagination/sort 行為；錯誤 envelope 正確呈現 | Medium | Medium | 先使用 offset pagination；keyset 可延後 |
| Manual executed trade creation | milestone 目標是記錄一筆 buy/sell 成交，不是下單委託 | Order ticket 在 API mode 送出 `symbol/type/quantity/price/fee/note/executedAt`；成功後拿到後端 trade DTO | High | High | UI 文案與語意應改成「記錄成交/交易」或清楚限制，不要暗示 broker order placement |
| Post-trade refetch | 本地 optimistic mutation 會讓 portfolio 與後端資料不一致 | 交易建立成功後重新讀 summary、holdings、trade history；失敗時不改本地 portfolio | Medium | High | 可先不做複雜 cache invalidation；明確 refetch 最可靠 |
| Backend validation surfaced in UI | 後端會拒絕不可交易資產、超賣、非法價格/數量 | 後端 400/403/business error 可轉成 order ticket 的可讀錯誤；不吞錯、不顯示成功 | Medium | High | 使用既有 error catalog/envelope |
| Idempotency protection for trade submit | 雙擊、重試、timeout 會造成重複交易紀錄 | 前端每次 submit 產生 `Idempotency-Key` 或 client request id；後端同 user/key 重送回傳既有結果，不重複寫入 | High | High | Codebase concerns 已標為高風險；即使 v1 做簡化，也應列為核心安全功能而非 nice-to-have |
| Contract documentation before implementation | 後端與前端是 sibling repos，沒有明確 contract 容易互相猜 | planning/docs 中明確記錄 cookie、CSRF、refresh、error、portfolio/trading DTO 與 mode 行為 | Low | Medium | 是 phase 0/phase 1 的交付物，能降低跨 repo 返工 |

## Useful Differentiators

這些有價值，但只有在不拖垮安全與核心 vertical slice 時才納入。它們不應搶在 table stakes 之前。

| Feature | Value Proposition | Complexity | Risk | 建議 |
|---------|-------------------|------------|------|------|
| API mode diagnostics indicator | 使用者/開發者能立即知道目前是 mock 還是 API，避免誤判 demo 成功 | Low | Low | 可放在非敏感 diagnostics/status 區塊；不要暴露 token/cookie 細節 |
| Session expiry UX | access/refresh 過期時給出清楚提示，不讓使用者誤以為交易失敗 | Medium | Medium | 若 shared 401 handling 已完成，可加 toast/banner |
| Trade submit pending/duplicate guard | 在 idempotency 之外，前端阻止重複點擊送出 | Low | Medium | 應做；不能取代後端 idempotency |
| Last refreshed timestamp | portfolio/trading refetch 後顯示資料更新時間，提升使用者信任 | Low | Low | 適合接在 post-trade refetch 後 |
| Minimal audit metadata display | 交易紀錄顯示 created/executed time、note、request id 或 trace id，便於除錯 | Medium | Medium | 僅顯示既有後端資料；不要新增完整 audit log 系統 |
| Credentials/CSRF contract tests shared examples | 用少量範例測試固定跨 repo contract | Medium | Medium | 對後續 milestone 很有幫助；可納入驗證，不必變成大型 E2E framework |
| Graceful degraded state for backend unavailable | API mode backend 掛掉時，UI 清楚顯示不可用而不是回 mock | Medium | Medium | 尤其重要，因為 runtime mode 不能在整合環境靜默 fallback |

## Anti-Features / Explicitly Deferred

這些不應進入本 milestone v1。若納入，會直接造成 broker/order-management scope creep 或安全設計不足。

| Anti-Feature | 為什麼避免 | Instead |
|--------------|------------|---------|
| 真實券商串接 | PROJECT.md 明確 out of scope；需要 broker credentials、法遵、錯誤復原、實際成交回報 | 保持手動成交交易紀錄 API |
| Pending orders / order status / cancellation | 現有後端沒有 order lifecycle，只有已成交 transaction 與 holdings update | UI 語意限制為「記錄成交」；order lifecycle 另開 milestone |
| Market/limit/time-in-force 真實委託語意 | 前端 ticket 目前比較像模擬下單，後端 `CreateTradeRequest` 不支援委託屬性 | v1 只傳成交價格；必要時把 market/limit UI 在 API mode 降級或隱藏 |
| Partial fills / broker fills / execution reports | 會改變交易資料模型、持倉投影與測試策略 | 等手動交易 vertical slice 穩定後再設計 |
| AI-assisted trading policy enforcement | PROJECT.md 明確指出 AI/broker settings 是 mock UX，需要獨立安全設計 | 本 milestone 不接 AI access/broker settings API mode |
| Broker credential storage | 高敏感資料，需要加密、rotation、access policy、audit | 延後到 broker integration/security milestone |
| Full settings/watchlists/alerts/notifications API mode | 會分散 auth + portfolio/trading 目標 | 保持 mock 或只顯示未整合狀態 |
| Complete analytics dashboard API integration | 不是本 milestone 的核心路徑，且後端缺對應 read model | 僅使用 portfolio summary/holdings/trades 支撐基本畫面 |
| Replacing Vue visual shell | PROJECT.md 指定 integration-first，不是 redesign | 僅做必要狀態/文案/錯誤處理調整 |
| Storing refresh token in localStorage/sessionStorage | 與 browser-safe auth 目標衝突，XSS 風險高 | refresh token 僅 httpOnly cookie；JS 不可讀 |
| Relying only on SameSite without CSRF token | SameSite 是防線之一，不應取代明確 CSRF contract，尤其 dev/proxy/CORS 變動時風險高 | unsafe browser requests 一律帶 CSRF header |
| Silent fallback from API mode to mock | 會掩蓋後端整合失敗，讓驗收失真 | API mode 失敗應顯示錯誤；mock mode 必須顯式啟用 |

## Feature Dependencies

```text
Auth contract documentation
  -> Backend cookie issuance + refresh/logout semantics
  -> CSRF token contract
  -> Frontend shared API client credentials/CSRF/401 handling
  -> Frontend auth service/session restore
  -> Protected API mode portfolio/trading adapters
  -> Manual executed trade submit
  -> Post-trade refetch

Shared API client
  -> Portfolio summary adapter
  -> Holdings/positions adapter
  -> Trade history adapter
  -> Trading API adapter

Idempotency design
  -> Trading API request contract
  -> Frontend submit key generation
  -> Duplicate submit/retry tests

Mock/API runtime mode hardening
  -> API mode verification
  -> Prevent accidental mock fallback in integration builds
```

## MVP Recommendation

優先順序應該是：

1. **Auth/CSRF contract and tests**  
   先定義 cookie、refresh、logout、`/me`、CSRF、401/403、error envelope。沒有這層，portfolio/trading API mode 不是不安全就是不穩。

2. **Shared frontend API client + auth service**  
   將 `credentials: "include"`、CSRF header、refresh retry、request/error envelope、session restore 集中。避免每個 domain client 各自實作不同安全行為。

3. **Portfolio/trading read adapters**  
   先接 summary、holdings、trade history，建立 API mode 的基本讀取與錯誤狀態。

4. **Manual executed trade creation + idempotency + post-trade refetch**  
   最後接 order ticket，但語意必須是「建立已成交交易紀錄」。成功後重新讀 portfolio/trading 狀態，以後端為 truth。

Defer:

- **Broker/order lifecycle:** 等手動交易紀錄穩定後再設計資料模型與 API。
- **AI/broker settings API mode:** 需要獨立安全與 policy enforcement 設計。
- **Analytics/alerts/watchlists/settings 全面整合:** 不屬於此 vertical slice。
- **大型 UI redesign:** 只做必要整合與文案修正。

## Phase Fit

| Phase Topic | 應納入 | 不應納入 | 主要風險 |
|-------------|--------|----------|----------|
| Auth contract | cookie、refresh、logout、`/me`、401/403、bearer compatibility | 多裝置 session 管理完整 UI | cookie/CSRF 行為不一致 |
| CSRF | double-submit token、unsafe method enforcement、前端 header | 複雜 WAF/security product | 漏掉 logout/trade/backtest 等 unsafe endpoint |
| Frontend API foundation | shared client、auth service、mock/API mode hardening | 大規模 state management 重寫 | domain clients 重複錯誤處理 |
| Portfolio read | summary、holdings、trade history | full analytics/watchlists/alerts | DTO mapping 與 mock 欄位混淆 |
| Trading write | manual executed buy/sell、idempotency、refetch | pending/cancel/partial fill/broker order | 重複 submit、UI 語意誤導 |

## Acceptance-Oriented Feature List

這些條目可直接轉成 requirements 或 UAT：

- 未登入使用者在 API mode 進入 portfolio/trading 操作時，不會呼叫需要 session 的 write API，並看見登入入口或未登入狀態。
- 登入成功後，瀏覽器收到 httpOnly auth cookie；JavaScript 不可讀 refresh token。
- app refresh 後，前端透過 `/me` 還原 session；若 session 無效，進入明確未登入狀態。
- 任一 unsafe cookie-authenticated request 未帶正確 CSRF token 時，後端拒絕且回傳一致錯誤 envelope。
- shared API client 對所有 API mode request 設定 `credentials: "include"`，並統一解析 success/error envelope。
- portfolio summary、holdings、trade history 在 API mode 從後端讀取，不再依賴 mock store 作為 truth。
- 建立 buy/sell trade 時，前端送出後端支援的手動成交欄位，不送 pending order/status/time-in-force。
- trade submit 成功後，前端重新讀取 summary、holdings、trade history。
- trade submit 失敗時，前端不修改本地持倉，並呈現後端 validation/business error。
- 重複點擊或網路重試同一筆 submit 不會產生兩筆交易紀錄。
- `VITE_DATA_MODE=api` 的整合驗證不允許靜默 fallback 到 mock。

## Complexity and Risk Summary

| Area | Complexity | Risk | Reason |
|------|------------|------|--------|
| Login/session/cookie auth | High | High | 涉及後端安全 config、cookie 屬性、refresh lifecycle、前端 session restore |
| CSRF | High | High | 一旦漏掉 unsafe endpoints，cookie auth 會變成安全缺陷 |
| Shared API client | Medium | High | 若沒有集中處理，所有 domain API 都會產生不一致行為 |
| Portfolio reads | Medium | Medium | 後端已存在 API，但前端 mock 欄位與後端 DTO 需對齊 |
| Manual trade creation | High | High | 持倉更新、錯誤處理、idempotency、UI 語意都容易出錯 |
| Mock/API coexistence | Medium | Medium | mock 對開發重要，但整合驗證不能被 mock 掩蓋 |

## Sources

- `.planning/PROJECT.md` — milestone 範圍、active requirements、out-of-scope、key decisions。
- `.planning/codebase/ARCHITECTURE.md` — backend modules、auth path、trading/portfolio path、frontend adapter pattern。
- `.planning/codebase/STRUCTURE.md` — 檔案位置、frontend services pattern、where to add new code。
- `.planning/codebase/CONCERNS.md` — cookie/CSRF、frontend API client duplication、trading idempotency、mock/API mode、scope creep 風險。
