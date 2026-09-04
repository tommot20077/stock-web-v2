# develop 全面審查(2026-09-02)

> 審查對象:後端 `develop` @ `334cb34`(PR #20 merge;之後只多了文件 PR #22)、前端 `develop` @ `a00fb29`(PR #9 merge)。
> 方式:四位獨立 reviewer 各審一個維度(安全 / 性能 / 架構 / 功能),主線逐條抽查 HIGH 與關鍵 MEDIUM 的 `file:line` 後才採信。
> 所有結論皆為**靜態閱讀**;本機當日無 Docker(BIOS 虛擬化關閉),IT / E2E 以 CI 為準。
> 各維度完整報告:[security.md](security.md)、[performance.md](performance.md)、[architecture.md](architecture.md)、[functionality.md](functionality.md);合併前的 PR review:[pr20-backend-review.md](pr20-backend-review.md)、[pr9-frontend-review.md](pr9-frontend-review.md)。
> 2026-09-04 追加:[04-13-task2-precheck.md](04-13-task2-precheck.md)——Docker 恢復後對人工檢查點 14 步做的 headless 功能預檢(含一條阻斷問題的修正與三點提醒)。

## 1. 總覽

| 維度 | 評分 | HIGH | MEDIUM | 一句話 |
|------|------|------|--------|--------|
| 安全 | **B** | 0 | 4 | 認證 / CSRF / JWT / SQL 參數化都扎實;缺口全是 `security.md` 承諾但未落地的運維面(限速、IP、Swagger、方法層授權) |
| 性能 | **B-** | 1 | 8 | 熱路徑 SQL / 索引正確;Kafka consumer 永不 ack 導致重啟重播,是唯一「一定會發作」的項目 |
| 架構 | **B-** | 1 | 11 | ArchUnit 守住 import 邊界;但 trading 用 SQL 直讀 asset 模組的死表,估值永遠是 seed 價 |
| 功能 | **94%**(34 / 36 需求有證據) | 0 | 4 | Phase 1–4 全部有實作與自動化證據;缺 VER-03 跨 repo 交易旅程、7 頁 API mode 仍走 mock 無標示 |

**裁決:develop 可作為下一階段(Phase 04.1 / Phase 5)的基準,不需回退。** 但下方 P0 兩條應在任何「對外部署」之前修掉。

## 2. 主線抽查紀錄(採信依據)

| 發現 | 抽查方式 | 結果 |
|------|----------|------|
| 架構 H-1:`asset_latest_prices` 無人更新 | grep main sources 的 `asset_latest_prices` | 只有 V2 seed 一處 INSERT;trading `JdbcTradingRepository.java:317` 讀它估值;Redis `market:latest:` 由 `WsBroadcastConsumer` 寫入但 trading 不讀 → **成立** |
| 性能 HIGH-1:consumer 永不 ack | 讀 `application.yaml:20-28`、grep `Acknowledgment` | `ack-mode: manual`、`enable-auto-commit: false`、`auto-offset-reset: earliest`;`PriceWriterConsumer`、`WsBroadcastConsumer` 皆無 `Acknowledgment` → **成立** |
| 安全 M-1:一般 API 零限速 | grep `rateLimitService.enforce` | 只有 `AuthController.java:66,79,87,116` 四處 → **成立** |
| 安全 M-4:方法層授權缺口 | 每個 `@RestController` 數 `@PreAuthorize` 與 mapping | Backtest 0/6、Market 0/4、WsTicket 0/2、Backfill 0/2;Trading 4/5、AdminUser 2/2 → **成立**(路徑層仍需登入,今日無越權) |
| 功能 M-1:API mode 頁面走 mock 無標示 | 每頁數 `../data` / store import 與 Preview 字樣 | Markets、Watchlist、Analytics、Notifications 有 import 無標示;Alerts、Settings 有標示 → **成立** |

## 3. 優先修正清單(跨維度合併)

### P0 — 對外部署前必修

| # | 項目 | 來源 | 證據 | 修法 |
|---|------|------|------|------|
| 1 | Kafka consumer 在 manual ack 下永不提交 offset,每次重啟從 earliest 重播整個 tick topic;topic 亦無 retention | 性能 HIGH-1 | `application.yaml:20-28`、`WsBroadcastConsumer.java:24-45`、`PriceWriterConsumer`、`KafkaConfig.java:8-35` | listener 加 `Acknowledgment` 並在處理完成後 `acknowledge()`(或改 `ack-mode: record`);topic 設 `retention.ms`;補一條 IT:重啟 consumer group 後不重播 |
| 2 | 持倉估值讀 `asset_latest_prices` 死表(只有 V2 seed),與「Kafka → trading → Redis」資料流脫節;同時違反 Facade-only(SQL 直 JOIN asset 模組的表) | 架構 H-1、性能 LOW | `JdbcTradingRepository.java:123,205,292,317`、`V2__foundation_seed_assets.sql:24` | 新增 `MarketDataFacade.latestPrices(assetIds)`(讀 `market:latest:`,miss 時查 `market_prices`),trading SQL 只碰自己的表;`asset_latest_prices` 廢棄;ArchUnit 加「模組 SQL 不得含他模組表名」 |

### P1 — 下一個 phase 內處理

| # | 項目 | 來源 | 證據 |
|---|------|------|------|
| 3 | 一般 API 零 rate limit(doc §15 承諾 100/min/user);`POST /trades`、`POST /backtests/runs`、公開 `GET /assets` 無限速 | 安全 M-1 | `RateLimitService.enforce` 只在 `AuthController` |
| 4 | Client IP 只取 `getRemoteAddr()`、無 `forward-headers-strategy`:ingress 後全站共用一 IP,限流 / lockout 會鎖所有人 | 安全 M-2 | `ClientIpResolver.java:16-19` |
| 5 | Swagger 所有 profile 預設開啟且 permitAll | 安全 M-3 | `application.yaml:46-50`、`SecurityConfig.java:78-79` |
| 6 | Backtest / Market / WsTicket / Backfill controller 零 `@PreAuthorize`;`user_permissions` GRANT / REVOKE 未落地 | 安全 M-4 | 見 §2 |
| 7 | `PortfolioCache` 在 COMMIT 前 DELETE,併發讀會把交易前資料寫回 Redis(TTL 60s)→ 成交後 refetch 可能髒讀 | 性能 MED-4 | `PortfolioCache.java:45-52`、`TradingService.createTrade` 末段 |
| 8 | holdings / summary 每持倉 1 GET + miss 1 DB + 1 SET 的 N+1;三頁 refetch 放大 | 性能 MED-5 | `JdbcTradingRepository.java:313-326` |
| 9 | Kafka 掛時 `/actuator/health` 卡約 60s(AdminClient 每次新建、close 等在途請求)→ k8s probe 連鎖重啟 | 性能 MED-2 | `MarketDataHealthIndicator.java:59-62`;`MarketDataHealthIndicatorIT` 71s |
| 10 | WS 廣播在單一 consumer 執行緒同步 send(10s timeout),慢客戶端 head-of-line blocking | 性能 MED-1 | `WsBroadcastConsumer.java:55-86`、`MarketWebSocketHandler.java:79-82` |
| 11 | API mode 下 Markets / Chart / Watchlist / Analytics / Alerts / Notifications / Settings 仍直讀 mock,無 Preview / Simulated 標示;watchlist 操作寫進 mock store(靜默 no-op,違反鐵律 3、6) | 功能 M-1 | `Markets.vue:129-137`、`Watchlist.vue:73-80` 等 |
| 12 | VER-03 未達:Playwright 無「建立交易 → refetch」旅程;04-13 Task 2 未執行;後端 CI browser-e2e 以「同名前端分支」對映,分支名不同時只打前端 develop | 功能 M-2 | `ci.yml:152-156`、`e2e/tests/` |
| 13 | audit.log 缺口:admin unlock、backfill(userId=null)、backtest runs | 功能 M-3 | `AdminUserController.java:38`、`BackfillJobListener.java:38,49` |
| 14 | portfolio / trading DTO 兩個 repo 都沒有契約文件(VER-04 部分);數值 wire 格式 number / string 混用,`@Digits(10,8)` 18 位有效數字超出 double | 功能 M-4、架構 M-7 | `apiTypes.ts:81-83`、`KlineDto` vs `TradeDto` |
| 15 | `latest?symbols=` 無上限、每 symbol 2–3 次往返(違反 Facade ≤ 3 / request) | 性能 MED-3 | `MarketController.java:80-83`、`MarketLatestService.java:80-110` |

### P2 — 技術債(排進 backlog)

| # | 項目 | 來源 |
|---|------|------|
| 16 | market-data 9 個 Testcontainers IT 掛在 surefire、各自起容器 → `./mvnw test` 4:59 中佔 3:27;無共用基底、無 `withReuse` | 性能 MED-6、架構 M-8 |
| 17 | `SecurityConfig` 369 行內含三個過濾器並依賴 L2 user 模組;`authenticatedUserId` 逐字重複 4 份;`GlobalExceptionHandler` 住在 stock-start 讓 `@WebMvcTest` 驗不到信封 | 架構 M-3、M-4 |
| 18 | 四個零實作抽象(EventPublisher / EventSubscriber / DomainEvent / SearchService)、`PriceTickEvent` 不符 event-conventions;market-data「可獨立部署」不成立 | 架構 M-1、M-2、M-5 |
| 19 | Redis key 與 redis-convention.md 漂移;`application-demo.yaml` 幾乎整份複製 `application.yaml` | 架構 M-6、M-9 |
| 20 | `OrderTicket.vue` 1612 行、`Positions.vue` 1042 行;formatter 住在 `data.ts` 逼 API 頁 import mock 模組;14 處 `?raw` 字面測試 | 架構 M-10、M-11、LOW |
| 21 | `ScheduledIngestor` 序列抓價無逾時、assetCache 永不刷新;`POST /backfill` 同步 JobLauncher 佔 HTTP 執行緒 | 性能 MED-7、MED-8 |
| 22 | 安全 LOW 8 條:錯誤訊息回射 symbol / interval、密碼無上限、帳號枚舉時間差、WS `allowedOriginPatterns("*")`、缺 `Referrer-Policy`、cookie `secure` 無 fail-fast、ILIKE 未跳脫、cookie logout 實為全裝置撤銷(程式較嚴,契約文件要改) | 安全 LOW |
| 23 | 文件漂移:architecture.md(Rich Domain / 獨立部署 / 資料流)、code-standards.md(引用不存在的 `SecurityUtils` 等)、event-conventions / redis-convention、CLAUDE.md 仍稱契約檔信封「過時」、REQUIREMENTS.md 勾選落後 | 架構 LOW、功能 LOW |
| 24 | Spotless + JaCoCo + CI lint(後端)、ESLint / Prettier(前端)——`archive/*` 分支與 todos 已有版本 | 架構技術債 #7 |

## 4. 已確認沒問題(不必再花時間)

- ES256 / JWK 且非 dev / test / e2e 缺 key 拒啟;claims 最小化;tokenVersion 逐字比對;HttpOnly cookie;refresh 輪替 + 重放偵測;CSRF double-submit 與 bearer 豁免無法偽造。
- 全部 SQL named params、排序 enum 白名單;Kafka trusted packages + DLT;catch-all 不外洩;log / audit 無敏感值;repo 無 tracked 秘密。
- 前端無 token 落地、`v-html` 僅靜態 SVG、`fetch` 只在 `apiClient.ts`、401 單次 refresh。
- 冪等下單:insert-first、`ON CONFLICT DO NOTHING` predicate 與 V11 索引逐字一致、rollback 不燒 key、跨使用者隔離、不回射 key;8 併發同 key 累計 8 次零偶發。
- 契約:`ApiResponse` / `PageResponse` / `TradeDto` / `HoldingDto` / `PortfolioSummaryDto` / `AssetDto` / `KlineDto`、`POST /trades` 7 欄 + header 前後端一致;i18n zh / en 各 176 key 零缺漏。
- ArchUnit 4 條 import 規則實查零違規;`listTransactions` / 冪等查詢的索引覆蓋正確。

## 5. 本次合併紀錄(供對照)

| PR | 內容 | review 修正 | 驗證 |
|----|------|-------------|------|
| 後端 #20 → `334cb34` | Phase 4 後端 5 plan + 收尾 | C-1 fee `@Digits`、K-1 `FieldValidationException` 讓 `fields['Idempotency-Key']` 指名 header、Q-1、S-1 | CI @ b3412a5 Unit / Integration / E2E / Browser E2E 全綠;本機 7 模組單元 418 tests 綠 |
| 前端 #9 → `a00fb29` | Phase 4 前端 7 plan | F-1 review 步驟 oversell 可見錯誤、F-2「新」標記三個清除時機、F-3、`flushAsync` Node 20 假紅 | Node 20 / 24 雙模式 377 / 377、build 綠;CI 綠 |
| 後端 #22、前端 #10 | 狀態回填與 LESSONS | — | 純文件 |

**仍開著的事**:`04-13` Task 2(Yuan 的雙 mode 14 步瀏覽器確認)未執行。

2026-09-04 更新:Yuan 已開啟 BIOS 虛擬化,Docker 恢復。補跑的驗證——後端 `./mvnw -pl stock-start -am verify` **106 IT 全綠**;Playwright browser E2E **18/18 全過**(後端 develop 對前端 develop)。另做了 headless 功能預檢並修掉一條會讓步驟乙走不下去的問題(dev server 缺 `/api` 代理,前端 PR #11),細節見 [04-13-task2-precheck.md](04-13-task2-precheck.md)。Task 2 本身仍需 Yuan 親自判斷版面、動畫與文案可讀性。

## 6. 殘留未確認

- 全部為靜態閱讀;HIGH-1 未做重啟實測;`asset_latest_prices` 是否有 repo 外(k3s job / DB trigger)在更新未查。
- 部署層(ingress TLS / HSTS / NetworkPolicy)不在 repo;`user_permissions` 表是否存在未核對;相依套件 CVE 未對照弱點資料庫。
- IT 耗時分布取自上次有 Docker 的 surefire 報告;Hikari / Lettuce 預設是否足夠需負載測試。
