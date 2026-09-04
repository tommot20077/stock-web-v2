# develop 安全審查報告（2026-09-02）

- 後端：`origin/develop` @ 334cb34（PR #20 merge）
- 前端：`develop` @ a00fb29（PR #9 merge，含 fde9968）
- 方式：靜態親讀，read-only，未跑測試。判斷基準：`ai-docs/security.md`、`browser-auth-contract.md`、`redis-convention.md`、`event-conventions.md`、前端 `AGENTS.md` 鐵律 1–4。

## 總評

**評分：B**。**裁決：可接受留在 develop**——無 CRITICAL / HIGH；4 條 MEDIUM 全屬「文件承諾了但程式未做」或「部署設定未收緊」，對外公開部署前應處理 M-1 ~ M-4。

## Endpoint 權限表（實查 SecurityConfig + 各 Controller）

| Method | Path | URL 層（SecurityConfig） | 方法層 / 其他保護 |
|---|---|---|---|
| POST | /api/v1/auth/register | permitAll | rate limit register（IP，5/h） |
| POST | /api/v1/auth/login | permitAll | rate limit login（IP，10/min）+ 帳號 lockout 5 次/15 min |
| POST | /api/v1/auth/token | permitAll | rate limit login（IP） |
| POST | /api/v1/auth/refresh | permitAll | BrowserCsrfFilter（有 refresh cookie 時）+ rate limit refresh（user 或 IP，5/min）+ 重放偵測 |
| POST | /api/v1/auth/logout | permitAll | 有 refresh cookie → CSRF；否則需 Authentication principal（AuthController:186） |
| GET | /api/v1/csrf | permitAll | — |
| GET | /api/v1/me | authenticated | — |
| GET | /api/v1/assets | permitAll | size 夾在 1–100（AssetController:11）；**query 無長度上限、ILIKE 未跳脫**（L-7） |
| POST | /api/v1/trades | authenticated | `@PreAuthorize TRADE_EXECUTE`；`Idempotency-Key` 必填 1–128；Bean Validation 完整 |
| GET | /api/v1/trades | authenticated | `@PreAuthorize PORTFOLIO_VIEW`；size ≤100、page ≤MAX_PAGE；排序/方向 enum 白名單 |
| GET | /api/v1/portfolio/holdings, /summary | authenticated | `@PreAuthorize PORTFOLIO_VIEW` |
| POST | /api/v1/backtests/runs | authenticated | **無方法層**；query 以 user_id 綁定 |
| POST | /api/v1/backtests/strategies/validate | authenticated | **無方法層** |
| GET | /api/v1/backtests/runs/{runId}, /result, /runs | authenticated | **無方法層**；SQL `user_id = :userId and uuid = :uuid`（JdbcBacktestRepository:88）→ 非擁有者 404 ✓；size ≤100 |
| GET | /api/v1/market/{symbol}/latest, /latest, /{symbol}/klines | authenticated（比 security.md「公開」嚴） | **無方法層**；klines limit ≤5000（KlineQueryService:36,82） |
| POST | /api/v1/market/backfill | authenticated | **controller 內手寫 `requireAdmin`**（BackfillController:163）；區間 ≤90 天 |
| GET | /api/v1/market/backfill/{id} | authenticated | 同上手寫 requireAdmin |
| POST | /api/v1/market/ws/ticket | authenticated | — |
| POST | /api/admin/users/{uuid}/unlock | hasRole(ADMIN) | `@PreAuthorize hasRole('ADMIN')` ✓（雙層） |
| WS | /ws/v1/market | permitAll（HTTP 層） | 單次 ticket（getAndDelete，30s，SecureRandom）；global / IP / 帳號連線上限；10 msg/s；閒置 4003；每 5 min 核對 tokenVersion |
| GET | /actuator/health | permitAll（management port 11181） | show-details when_authorized |
| ANY | /v3/api-docs/**, /swagger-ui/** | permitAll | **所有 profile 預設開啟**（M-3） |
| GET | /test-only/error/** | — | `@Profile("test")` 才載入 ✓ |

## 發現

### MEDIUM

**M-1 一般 API 沒有任何 rate limit（security.md §15 承諾 100 req/min/user、公開 60 req/min/IP）**
- 證據：`grep resilience4j|@RateLimiter` 全 repo 零命中；`RateLimitService.enforce` 只在 `AuthController.java:66,79,87,116` 被呼叫。
- 情境：已登入使用者可無限速打 `POST /api/v1/trades`（每次一筆 INSERT + FOR UPDATE + cache 失效）或 `POST /backtests/runs`（CPU 密集）；匿名可無限速打公開的 `GET /api/v1/assets?query=…` ILIKE 全表掃描（配合 L-7）。
- 建議：以 Bucket4j 或 Resilience4j 在 filter 層加 per-user（已認證）/ per-IP（公開）桶；至少先把 `/api/v1/assets` 與寫入端點納入 `RateLimitService`。

**M-2 Client IP 只取 `getRemoteAddr()`，未處理反向代理**
- 證據：`ClientIpResolver.java:16-19`（註解自己承認需 `server.forward-headers-strategy=NATIVE` / RemoteIpValve）；`application*.yaml` 無 `forward-headers-strategy`，無 `ForwardedHeaderFilter` bean。
- 情境：K3s ingress 之後所有使用者共用同一個來源 IP → login 10/min、register 5/h、WS 每 IP 5 連線會把全站一起鎖死（可用性事故）；反之若日後草率信任 XFF，又變成任何人可偽造 IP 繞過限流。
- 建議：部署時設 `server.forward-headers-strategy: NATIVE` 並限定 `server.tomcat.remoteip.internal-proxies` 為 ingress 網段；寫進部署文件與 demo profile。

**M-3 Swagger UI / OpenAPI 在所有 profile 預設開啟且 permitAll**
- 證據：`application.yaml:46-50`、`application-demo.yaml:55-59`（`${STOCK_SWAGGER_UI_ENABLED:true}`）；`SecurityConfig.java:78-79` 放行 `/v3/api-docs/**`、`/swagger-ui/**`。security.md「Public Endpoints」明文 production 必須關閉。
- 情境：對外環境暴露完整 API 面（含 admin/backfill 端點形狀），降低攻擊者探索成本。
- 建議：demo/prod 預設 `false`（改成 `${STOCK_SWAGGER_UI_ENABLED:false}`），僅 dev 預設開；或把兩個路徑改為 `hasRole('ADMIN')`。

**M-4 方法層授權（security.md §1「兩層缺一不可」）覆蓋不完整；Backfill 的 ADMIN 檢查手寫在 controller**
- 證據：`BacktestController`（5 端點）、`MarketController`（3）、`WsTicketController`（1）零 `@PreAuthorize`；`BackfillController.java:163-169` 以 `requireAdmin(Authentication)` 手寫判斷，路徑 `/api/v1/market/backfill` 不在 `/api/admin/**` URL 規則下，URL 層只保證 `authenticated()`。另 `Permission` enum 沒有 backtest / market 的權限值；JWT filter 的 authorities 來自靜態 `Role.permissions()`（`SecurityConfig.java:240-247`），security.md §3 的 `user_permissions` GRANT/REVOKE 未落地。
- 情境：今天只有 USER/ADMIN 兩種角色且 USER 擁有全部權限，所以**目前無可利用的越權**；風險在演進：任何人重構 BackfillController 漏掉一行 `requireAdmin(...)`，一般使用者就能觸發 90 天 backfill 批次（外部 API 配額 + DB 寫入）。
- 建議：Backfill 改 `@PreAuthorize("hasRole('ADMIN')")` 並把路徑搬到 `/api/admin/market/backfill`（或在 SecurityConfig 加 URL 規則）；為 backtest / market 新增 `BACKTEST_RUN`、`MARKET_VIEW` 權限並補 `@PreAuthorize`；用 ArchUnit 鎖「所有 `@RestController` 方法必須有 `@PreAuthorize`」（doc §4 已承諾 ArchUnit）。

### LOW

**L-1 錯誤訊息回射使用者輸入，且 symbol 無長度上限**
- `TradingService.java:371`（`"Asset is not tradeable: " + symbol`）、`:379`、`MarketController.java:69`、`MarketLatestService.java:68`、`BackfillController.java`（回射 `interval` 與 `Idempotency-Key`）；`GlobalExceptionHandler.handleBusiness` 把 `getMessage()` 放進 `error.message`；前端 `AuthPanel.vue:55` 以 mustache 渲染 `message.message`（HTML escape，**無 XSS**）。`CreateTradeRequest.java:22`、`CreateBacktestRunRequest.java:12` 的 `symbol` 只有 `@NotBlank`。
- 建議：訊息改靜態、把 symbol 放進 `fields`；symbol 加 `@Size(max = 32)`。

**L-2 密碼欄位無上限長度**
- `RegisterRequest`（`@Pattern .{8,}`）、`LoginRequest`（`@NotBlank`）皆無 `@Size(max)`；BCrypt 對超長輸入（Tomcat 預設 body 2 MB）仍全量雜湊 → 每個請求都是 CPU 放大器，配合 M-1/M-2 更明顯。建議 `@Size(max = 128)`。

**L-3 帳號枚舉面（可接受的取捨，記錄）**
- `AuthService.java:96-101`：email 不存在時跳過 BCrypt（時間差）；`AUTH_ACCOUNT_LOCKED` 只會對存在的帳號出現；register 回 `DuplicateResourceException("email")`。若在意，未知 email 也跑一次 dummy BCrypt。

**L-4 WebSocket `setAllowedOriginPatterns("*")`**（`WsConfig.java:58`）
- CSWSH 已被「單次 30s ticket 需經 CORS 保護的已認證 REST 取得」實質擋住；仍建議改綁 `stock.cors.allowed-origins`，讓 WS 與 REST 同一份白名單。

**L-5 自訂 HTTP 安全標頭未設定**
- 無任何 `http.headers(...)` 客製；Spring 預設有 `X-Content-Type-Options`、`X-Frame-Options: DENY`、`Cache-Control: no-store`，但 security.md §19 要求的 `Referrer-Policy` 缺，HSTS 只在 app 端 TLS 時自動加（ingress 終結 TLS 時需在 ingress 補）。

**L-6 Cookie `secure` 預設 false、demo profile 未覆寫**
- `application.yaml:56-57`；程式沒有「非 dev profile 而 secure=false 就 fail-fast」的守衛（JWT key 有這種守衛，cookie 沒有）。建議比照 `JwtService.resolveKey` 在非 dev/test/e2e profile 強制 `secure=true`。

**L-7 `/api/v1/assets` ILIKE 未跳脫 `%`/`_`、query 無長度上限**（`AssetRepository.java:24,43`）
- 非注入（named param），但公開端點 + 使用者自帶萬用字元 + 無限流 = 廉價的全表掃描。建議 query 截到 64 字元並跳脫萬用字元。

**L-8 Logout 語意與契約不一致（程式較嚴）**
- `AuthService.java:50-53` 對 cookie logout 一律 `incrementTokenVersion` → 所有裝置的 access / refresh 全部失效；`browser-auth-contract.md` 寫「只撤銷目前瀏覽器 session」。安全上是好事，但文件要改，前端多裝置體驗要知道。

### INFO / 確認無問題

- 認證核心 ✓：ES256 + P-256 JWK，非 dev/test/e2e profile 缺 key 直接拒啟（`JwtService.java:123-130`）；claims 只有 sub/role/tokenVersion/exp；tokenVersion 與 Redis 逐字比對；access/refresh cookie `HttpOnly`（`BrowserAuthCookieService.java:35-36`）；refresh 為 `UUID.randomUUID()` 不透明 token、輪替 + 重放偵測（`RefreshTokenService.java:98-116`）；CSRF token `SecureRandom`、double-submit、bearer 豁免判定依 filter 設定的 request attribute（無法由客戶端偽造）。
- 注入 ✓：所有 SQL 走 `JdbcClient` named params；動態片段只來自常數欄位清單與 enum 白名單（`TradeSortKey`、`JdbcTradingRepository.java:272-275`、`JdbcBacktestRepository.java:188-190`）；Redis key 只拼 userId / UUID；Kafka `spring.json.trusted.packages` 限定 `common.event`，DLT 錯誤處理存在。
- 資訊外洩 ✓：catch-all 只回 `INTERNAL_ERROR` + traceId；`X-Trace-Id` 有長度與字元白名單（`TraceIdFilter.java:21-22`）；log 無 token/password/cookie；audit.log 格式固定不含敏感值；actuator 只開 health/info/metrics 且在獨立 port。
- 秘密 ✓：無任何 tracked 金鑰/密碼（只有 e2e 測試帳密常數）；`.env*` 已 ignore、只追蹤 `.env.example`；test resources 的 private-key/password 為空。
- 前端 ✓：`localStorage` 只存 UI tweaks（`useTweaks.ts:15`）；唯一 `v-html` 是寫死的 SVG 常數（`Analytics.vue:376-378`）；除 `apiClient.ts` 外零 `fetch(`；`credentials: 'include'` + `X-XSRF-TOKEN` 只在 apiClient；401 單次 refresh + 單次重送（`apiClient.ts:308-325`）；路徑參數皆 `encodeURIComponent`。
- 文件漂移（非漏洞）：security.md §3 GRANT/REVOKE、§5.7 PENDING_VERIFICATION 受限權限集未實作（程式直接拒絕非 ACTIVE，較嚴）；§「Public Endpoints」列 market-data 為公開，程式是 authenticated（較嚴）。
- 相依：Spring Boot 4.0.4、Vue 3.5.34、Vite 8.0.13、Vitest 4.1.6、TypeScript 6.0.3、Pinia 3.0.4——皆為近期版本；**未對照弱點資料庫**，不宣稱無 CVE。

## 殘留未確認

1. 未實跑：所有結論來自靜態閱讀；rate limit / lockout / CSRF 的行為以 IT（`BrowserAuthFlowIT`、`CorsIT`、`AuthPersistenceIT`）為準，本次未重跑。
2. 部署層（ingress 的 TLS/HSTS、NetworkPolicy、K3s secrets encryption）不在 repo 內，無法查證。
3. `user_permissions` 表是否存在於 migration 未逐一核對（僅確認 filter 未讀取它）。
4. 相依套件 CVE 未查（無網路查證）。
