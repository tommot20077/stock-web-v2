<!-- generated-by: gsd-doc-writer -->
# Stock Web V2(後端)

[![CI](https://github.com/tommot20077/stock-web-v2/actions/workflows/ci.yml/badge.svg)](https://github.com/tommot20077/stock-web-v2/actions/workflows/ci.yml)

Stock Web V2 是股票交易 Web 應用的後端。它以 Java 21 / Spring Boot 4 寫成,採多模組 Maven 的 modular monolith 架構,提供 REST(`/api/v1/**`)與 WebSocket(`/ws/v1/market`)API,涵蓋登入驗證、資產與觀察清單、行情、回測,以及交易 / 持倉 / 現金紀錄。

前端是另一個 sibling repo 裡的 Vue 3 SPA,稱為「前端 repo 根」。兩個 repo 的相對路徑只在 [`ai-docs/judgment.md`](ai-docs/judgment.md) §8 定義,其他地方一律指回該節。

> **交易語義**:`POST /api/v1/trades` 用來記錄「已成交」的手動交易,不是委託單。本系統沒有 pending / cancel / routing 這類委託生命週期。

## 模組一覽

| 模組 | 職責 |
|------|------|
| `stock-common` | 共用型別:`ApiResponse<T>` / `PageResponse<T>` 信封、`ErrorCode`、事件、enum |
| `stock-db-migration` | 集中管理的 Flyway migration(`db/migration/V*.sql`) |
| `stock-infrastructure` | 跨模組 Facade 介面、JWT(ES256)、rate limit、trace id、稽核 logger、Redis 設定 |
| `stock-module-user` | 註冊 / 登入、browser cookie 與 CSRF、refresh rotation、個人偏好、管理員解鎖 |
| `stock-module-asset` | 資產查詢、觀察清單 |
| `stock-module-market-data` | 行情 ingest(Kafka)、K 線與最新報價查詢、歷史回補(Spring Batch)、WebSocket 推播 |
| `stock-module-trading` | 已成交交易紀錄、持倉 / 摘要計算、現金帳、股利、股數調整 |
| `stock-module-backtest` | 決定性回測引擎、策略驗證 |
| `stock-start` | 啟動模組:`SecurityConfig`、全域例外處理、整合 / E2E 測試 |

業務模組(`stock-module-*`)之間不能直接互相依賴,只能透過 `stock-infrastructure` 的 Facade 介面溝通。這條規則由 ArchUnit(`ArchitectureRulesTest`)強制執行。完整架構見 [`docs/architecture/overview.md`](docs/architecture/overview.md),權威來源是 [`ai-docs/architecture.md`](ai-docs/architecture.md)。

## 安裝

需求:

- JDK 21
- Docker(整合 / E2E 測試的 Testcontainers 需要;`./mvnw test` 的 Unit + Web 層不需要)
- 一套連得到的 PostgreSQL(含 TimescaleDB extension)、Redis 與 Kafka

repo 已附 Maven Wrapper,不必另外安裝 Maven:

```bash
git clone https://github.com/tommot20077/stock-web-v2.git
cd stock-web-v2
./mvnw -B install -DskipTests
```

Windows 請改用 `mvnw.cmd`。

## 快速開始

1. 從範本建立本機設定檔(`.env` 已列入 gitignore,不要提交真值):
   ```bash
   cp .env.example .env
   ```
2. 編輯 `.env`,填入 `STOCK_DB_URL`、`STOCK_DB_USERNAME` / `STOCK_DB_PASSWORD`、`STOCK_REDIS_*`。`.env.example` 裡沒有 Kafka 設定,預設會連 `localhost:9092`;要連別的位置,請加上 `STOCK_KAFKA_BOOTSTRAP_SERVERS`。在 `dev` profile 下 `STOCK_JWT_PRIVATE_KEY` 可以留空,啟動時會產生暫時金鑰。
   <!-- VERIFY: .env.example 中的 DB / Redis 位址(10.0.0.214 NodePort)為內網 K3s 環境,外部開發者需自備對應服務 -->
3. 打包並啟動。請在 repo 根目錄下執行,因為 `dev` profile 會以相對路徑讀取 `.env`:
   ```bash
   ./mvnw -pl stock-start -am package -DskipTests
   java -jar stock-start/target/stock-start-0.0.1-SNAPSHOT.jar
   ```
   Windows 可以直接跑 `scripts/run-dev.ps1`。它會載入 `.env`、打包,再執行 jar。
4. 確認服務已啟動。API 預設跑在 port `11180`,Actuator 跑在 management port `11181`:
   ```bash
   curl http://localhost:11181/actuator/health
   ```

和前端本機聯調時:前端 Vite 代理把 `/api` 與 `/ws` 轉到 `$VITE_API_PROXY_TARGET`,預設 `http://localhost:11180`(與後端 dev 預設一致),本機聯調不再需要 `SERVER_PORT=8080`;要指向其他 port 時,以 `VITE_API_PROXY_TARGET` 啟動前端 dev / preview。瀏覽器 E2E(`run-e2e.sh`)由 Playwright 設為 `8080`,對應 `e2e-browser` profile。設定細節見 [`docs/guides/getting-started.md`](docs/guides/getting-started.md) 與 [`docs/configuration/overview.md`](docs/configuration/overview.md)。

## 使用範例

所有 REST 回應都包在 `ApiResponse<T>` 信封裡(`{success, data, error, meta}`),追蹤 id 只放在 `meta.traceId`。

**1. 搜尋資產(公開端點,不需登入;回傳 `PageResponse<AssetDto>`)**

```bash
curl 'http://localhost:11180/api/v1/assets?query=AAPL&page=0&size=20'
```

**2. 非瀏覽器 client:取得 bearer token 後呼叫受保護端點**

```bash
# 回傳 data.accessToken / data.refreshToken / data.user
curl -X POST http://localhost:11180/api/v1/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"email":"user@example.com","password":"Passw0rd!"}'

curl http://localhost:11180/api/v1/portfolio/holdings \
  -H 'Authorization: Bearer <accessToken>'
```

用 bearer token 送出的 unsafe 請求不需要 CSRF header。

**3. 瀏覽器 client:cookie + CSRF**

瀏覽器流程用 `POST /api/v1/auth/login` 取得 HttpOnly auth cookie,再用 `GET /api/v1/csrf` 取得可讀的 `XSRF-TOKEN` cookie。之後每個以 cookie 驗證的 `POST` / `PUT` / `PATCH` / `DELETE` 請求,都要在 `X-XSRF-TOKEN` header 帶上同一個值,否則回 `403 AUTH_CSRF_TOKEN_INVALID`。token 不會出現在 JS 讀得到的地方。完整契約見 [`ai-docs/browser-auth-contract.md`](ai-docs/browser-auth-contract.md)。

端點總覽見 [`docs/api/reference.md`](docs/api/reference.md)。交易 / 持倉 / 現金的契約見 [`ai-docs/trading-portfolio-contract.md`](ai-docs/trading-portfolio-contract.md),觀察清單見 [`ai-docs/watchlist-contract.md`](ai-docs/watchlist-contract.md)。

## 測試

```bash
./mvnw -B spotless:check                                   # 格式檢查(import 順序、空白衛生)
./mvnw test                                                # Unit + Web 層(不需 Docker)
./mvnw -pl stock-start -am verify                          # 加上 *IT.java 整合測試(Testcontainers,需 Docker)
./mvnw -pl stock-start -am test -Pe2e                      # 後端 E2E(*E2E.java)
```

CI(`.github/workflows/ci.yml`)依序跑 Lint → Unit → Integration → E2E。對 `develop` / `main` 的 PR 與 `develop` 的 push 還會跨 repo 跑 Browser E2E(Playwright)。測試分層與命名規範以 [`ai-docs/testing-standards.md`](ai-docs/testing-standards.md) 為準,說明見 [`docs/testing/overview.md`](docs/testing/overview.md)。

## 文件導覽

| 文件 | 內容 |
|------|------|
| [`docs/guides/getting-started.md`](docs/guides/getting-started.md) | 環境需求與第一次啟動 |
| [`docs/guides/development.md`](docs/guides/development.md) | 本機開發、建置指令、程式風格 |
| [`docs/architecture/overview.md`](docs/architecture/overview.md) | 架構與資料流 |
| [`docs/configuration/overview.md`](docs/configuration/overview.md) | 環境變數、Spring profiles(`dev` / `demo` / `e2e-browser`) |
| [`docs/api/reference.md`](docs/api/reference.md) | REST / WebSocket 端點參考 |
| [`docs/testing/overview.md`](docs/testing/overview.md) | 測試分層與執行方式 |
| [`ai-docs/security.md`](ai-docs/security.md) | 安全規範(權威) |
| [`ai-docs/code-standards.md`](ai-docs/code-standards.md) | 程式碼規範(權威) |
| [`ai-docs/git-convention.md`](ai-docs/git-convention.md) | Commit 規範:Conventional Commits,subject 用繁體中文 |

## 授權

本 repo 目前沒有附 LICENSE 檔。
