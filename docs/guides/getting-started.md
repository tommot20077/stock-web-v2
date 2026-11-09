<!-- generated-by: gsd-doc-writer -->
# 入門指南(Getting Started)

本文件帶你從零開始,在本機跑起 Stock Web V2 後端,接上前端,最後跑一次瀏覽器 E2E。

前端是另一個 sibling repo 裡的 Vue 3 SPA,下文稱為「前端 repo 根」,程式碼在它的 `vue-app/` 下。兩個 repo 之間的相對路徑只在 [`ai-docs/judgment.md`](../../ai-docs/judgment.md) §8 定義,本文件不另寫路徑,一律用 `<前端 repo 根>`、`<後端 repo 根>` 代稱。

環境變數與 profile 的完整說明在 [`docs/configuration/overview.md`](../configuration/overview.md),這裡只講第一次啟動需要的部分。

---

## 前置需求

| 工具 | 版本 | 什麼時候需要 |
|------|------|-------------|
| JDK | `21`(CI 用 Temurin 21) | 一律需要。Maven 由 repo 內的 Maven Wrapper 提供(Maven `3.9.14`),不必另外安裝 |
| Docker(含 compose v2) | — | 整合測試(`*IT.java`)、後端 E2E 的 Testcontainers,以及瀏覽器 E2E 的暫時基礎設施 |
| PostgreSQL(含 TimescaleDB extension)、Redis、Kafka | 測試用的映像版本為 `timescale/timescaledb:2.17.2-pg16`、`redis:7.4-alpine`、`confluentinc/cp-kafka:7.6.0` | 以 `dev` profile 啟動後端時,需要連得到這三套服務 |
| Node.js | `>= 20`(前端 CI 與 Browser E2E job 用 Node 20) | 跑前端或瀏覽器 E2E 時 |

repo 沒有 `.nvmrc` 或 `engines` 欄位,上表版本取自 CI workflow 與 `pom.xml`(`<java.version>21</java.version>`)。

---

## 安裝步驟

1. Clone 後端 repo:
   ```bash
   git clone https://github.com/tommot20077/stock-web-v2.git
   cd stock-web-v2
   ```
2. 編譯所有模組並安裝到本機 Maven repository(第一次會下載 Maven 本體與相依套件):
   ```bash
   ./mvnw -B install -DskipTests
   ```
   Windows 請改用 `mvnw.cmd`。
3. (要聯調或跑瀏覽器 E2E 時)依 [`ai-docs/judgment.md`](../../ai-docs/judgment.md) §8 的目錄配置 clone 前端 repo,再安裝前端相依套件:
   ```bash
   git clone https://github.com/tommot20077/stock-web-v2-front-end.git
   cd <前端 repo 根>/vue-app
   npm install
   ```

---

## 第一次執行

### 1. 最短驗證:跑單元測試

`./mvnw test` 只跑單元測試與 Web 層測試,不需要 Docker,也不需要任何外部服務。這是確認環境沒問題最快的方法:

```bash
./mvnw test
```

需要 Docker 的整合測試與後端 E2E 指令見 [`README.md`](../../README.md) 的「測試」一節,測試分層規範以 [`ai-docs/testing-standards.md`](../../ai-docs/testing-standards.md) 為準。

### 2. 以 `dev` profile 啟動後端

`dev` 是未指定 profile 時的預設值。它會讀取**啟動時工作目錄**下的 `.env`,而且 DB 與 Redis 的設定沒有預設值,所以必須先準備 `.env`,並且連得到這些服務。

1. 從範本建立 `.env`(已列入 gitignore,不要提交真值):
   ```bash
   cp .env.example .env
   ```
2. 編輯 `.env`,至少填入 `STOCK_DB_URL`、`STOCK_DB_USERNAME`、`STOCK_DB_PASSWORD`、`STOCK_REDIS_HOST`、`STOCK_REDIS_PORT`。
   - `.env.example` 沒有 Kafka 設定,未設時連 `localhost:9092`。行情 tick 經 Kafka 傳遞,Kafka 在別處時請加上 `STOCK_KAFKA_BOOTSTRAP_SERVERS`。
   - `STOCK_JWT_PRIVATE_KEY` 在 `dev` 可以留空,啟動時會產生暫時金鑰(每次重啟後,已發出的 access token 都會失效)。
   <!-- VERIFY: .env.example 的 DB / Redis 範例位址指向內網 K3s NodePort,外部開發者需自備對應服務 -->
3. 在 repo 根目錄打包並啟動:
   ```bash
   ./mvnw -pl stock-start -am package -DskipTests
   java -jar stock-start/target/stock-start-0.0.1-SNAPSHOT.jar
   ```
   Windows 可直接執行 `scripts/run-dev.ps1`:它會載入 `.env`、在未設 `SPRING_PROFILES_ACTIVE` 時補上 `dev`、打包,再以 `java -jar` 啟動。
4. 確認服務已啟動。API 預設跑在 `11180`,Actuator 跑在 management port `11181`:
   ```bash
   curl http://localhost:11181/actuator/health
   ```

沒有外部 DB / Redis / Kafka 可用時,可以改走下一節的「路徑 B」,借用瀏覽器 E2E 的 compose 容器在本機起完整環境。

---

## 與前端聯調(API mode)

前端用 `VITE_DATA_MODE` 決定資料來源:未設時是 `mock`,設成 `api` 才會呼叫後端;其他值會直接拋出 `RuntimeDataModeError`(邏輯在前端 `vue-app/src/services/runtimeDataMode.ts`)。

前端沒有 base URL 設定。`vue-app/src/services/apiClient.ts` 一律送同源相對路徑,能不能連到後端完全取決於 `vue-app/vite.config.ts` 的 proxy。proxy 目標讀前端啟動時的 shell 環境變數 `VITE_API_PROXY_TARGET`(只在 Node 端讀取、不進 bundle、不讀 `.env` 檔),未設時是 `http://localhost:11180`:

| 項目 | Port | 說明 |
|------|------|------|
| Vite dev server(`npm run dev`) | `5173` | `/api`、`/ws` → `$VITE_API_PROXY_TARGET`(預設 `http://localhost:11180`) |
| Vite preview(`npm run preview`) | `4173` | 同一份 proxy 設定 |
| 後端 `dev` profile 預設 | `11180`(management `11181`) | 和 proxy 預設一致 |
| 後端 `e2e-browser` profile 預設 | `8080`(management 也是 `8080`) | 瀏覽器 E2E 由 Playwright 把 `VITE_API_PROXY_TARGET` 設為 `http://localhost:8080`;手動聯調時要自己設(見路徑 B) |

所以用 `dev` profile 聯調時,前後端都用預設值即可,後端不必另外設定 `SERVER_PORT`。後端改跑其他 port 時,以 `VITE_API_PROXY_TARGET=http://localhost:<port>` 啟動前端 dev / preview。

因為瀏覽器只看到 Vite 的 origin,REST 請求對瀏覽器來說是同源;WebSocket 的 origin 檢查則會比對 `STOCK_CORS_ALLOWED_ORIGINS`,`dev` 的預設值與 `.env.example` 都已包含 `http://localhost:5173`。`dev` 的 `STOCK_AUTH_COOKIE_SECURE` 預設為 `false`,auth cookie 可以走 `http://localhost`。

### 路徑 A:後端 `dev` profile + 前端 dev server

1. 在後端 repo 根,以預設 port `11180` 啟動後端(`.env` 依上一節準備好):
   ```bash
   java -jar stock-start/target/stock-start-0.0.1-SNAPSHOT.jar
   ```
   Management port 是 `.env` 裡的 `STOCK_MANAGEMENT_PORT`(範本值 `11181`)。
2. 在前端以 API mode 啟動 dev server:
   ```bash
   cd <前端 repo 根>/vue-app
   VITE_DATA_MODE=api npm run dev
   ```
3. 打開 `http://localhost:5173`,從畫面註冊一個帳號後登入。

### 路徑 B:借用瀏覽器 E2E 的本機環境

前端 repo 的 `e2e/run-e2e.sh` 加上 `E2E_ENV_ONLY=1` 時,只會把環境起好、不跑測試:用 `vue-app/e2e/docker-compose.yml` 起暫時的 TimescaleDB、Redis、Kafka,再以 `e2e-browser` profile 在 `8080` 啟動後端。這條路徑不需要 `.env`,也不需要外部服務。

```bash
cd <前端 repo 根>/vue-app
E2E_BACKEND_DIR="$(cd <後端 repo 根> && pwd)" E2E_ENV_ONLY=1 bash e2e/run-e2e.sh
VITE_API_PROXY_TARGET=http://localhost:8080 VITE_DATA_MODE=api npm run dev
```

`e2e-browser` 後端跑在 `8080`,和 proxy 預設的 `11180` 不同,所以這條路徑啟動 dev server 時要帶 `VITE_API_PROXY_TARGET=http://localhost:8080`。

注意:

- `e2e-browser` 是測試用 profile:限流額度調高到 `10000`、帳號鎖定門檻調低為 `3` 次、JWT 與 access cookie 有效時間都是 `PT2H`,而且會開啟行情 mock ingest。它不代表正式環境的行為。
- `E2E_ENV_ONLY=1` 隱含 `E2E_KEEP=1`,腳本結束後容器與後端都會留著。用完後請自己清掉:停掉腳本印出的後端 pid,再執行
  ```bash
  docker compose -f e2e/docker-compose.yml down -v --remove-orphans
  ```

---

## 瀏覽器 E2E harness

瀏覽器 E2E 的流程是:真瀏覽器 → 真 HTTP → 真後端(`e2e-browser` profile)→ compose 暫時的 DB / Redis / Kafka。腳本與測試都在前端 repo 的 `vue-app/e2e/`,設計文件是本 repo 的 [`docs/plans/2026-07-16-browser-e2e-testing-design.md`](../plans/2026-07-16-browser-e2e-testing-design.md)。

第一次執行前,先在 `<前端 repo 根>/vue-app` 安裝 Playwright 的 Chromium:

```bash
npx playwright install chromium
```

執行(無參數時跑全部,`@smoke` 與 `@extended` 都會跑,和兩個 repo 的 CI 相同;本機想快速跑可以自己帶 `--grep @smoke`):

```bash
cd <前端 repo 根>/vue-app
E2E_BACKEND_DIR="$(cd <後端 repo 根> && pwd)" bash e2e/run-e2e.sh
E2E_BACKEND_DIR="$(cd <後端 repo 根> && pwd)" bash e2e/run-e2e.sh --grep @smoke
```

Windows PowerShell 改用 `e2e\run-e2e.ps1`。

`run-e2e.sh` 依序做這些事:

1. 檢查 `http://localhost:8080/actuator/health` 是否已有服務回應,以及 `http://localhost:4173/` 是否已有 `vite preview`;任一有回應就直接中止(本機 Playwright 會重用 4173 上既有的 preview,而它可能指向 `11180`)。
2. `docker compose up -d --wait` 起基礎設施(host port:PostgreSQL `15432`、Redis `16379`、Kafka `19092`,和 `application-e2e-browser.yaml` 的預設值一致)。
3. 在 `E2E_BACKEND_DIR` 執行 `./mvnw -pl stock-start -am package -DskipTests`。
4. 以 `SERVER_PORT=8080` 與 `--spring.profiles.active=e2e-browser` 執行 `stock-start/target/stock-start-*.jar`,stdout 寫到 `e2e/artifacts/backend.log`。
5. 最多等 90 秒,直到 health 回報 `UP`;逾時或後端行程退出時,印出 compose 與後端 log。
6. 執行 `npx playwright test`(帶入腳本參數;無參數 = 全部)。Playwright 的 `webServer` 會以 `VITE_DATA_MODE=api` 重新 build 前端,再用 `vite preview` 在 `4173` 提供服務(`VITE_DATA_MODE` 在 build 時就固定下來),並以 `VITE_API_PROXY_TARGET=http://localhost:${E2E_BACKEND_PORT:-8080}` 把 proxy 指到 E2E 後端。
7. 結束時停掉後端,並以 `docker compose down -v` 清掉容器。

### 環境變數

| 變數 | 預設 | 用途 |
|------|------|------|
| `E2E_BACKEND_DIR` | 對應 Yuan 本機目錄配置的相對路徑 | 後端 repo 根。目錄配置不同(例如 cloud 環境、CI)時一定要設定;相對路徑是從執行腳本的目錄算起,所以建議像上面那樣傳絕對路徑 |
| `E2E_BACKEND_PORT` | `8080` | 後端 port |
| `E2E_SKIP_BUILD` | `0` | 設成 `1` 會略過 `mvnw package`,必須已經有 jar |
| `E2E_KEEP` | `0` | 設成 `1` 時,結束後保留容器與後端(除錯用) |
| `E2E_ENV_ONLY` | `0` | 設成 `1` 時只起環境、不跑 Playwright,隱含 `E2E_KEEP=1` |

環境常駐時,可以直接在 `vue-app/` 下反覆跑測試:`npm run test:e2e`(等同 `playwright test`,跑全部;可再帶 `-- --grep @smoke`),或 `npx playwright test e2e/tests/auth.spec.ts` 只跑單一檔案。

失敗時的 artifacts(已 gitignore)都在 `vue-app/e2e/artifacts/`:`backend.log`(畫面上的 `traceId` 可以在這裡對到)、`playwright-report/`、`test-results/`(失敗案例的 trace / screenshot / video)。

後端 CI(`.github/workflows/ci.yml`)只有在對 `develop` / `main` 開 PR,或 push 到 `develop` 時,才會跑 Browser E2E job。它會 checkout 與後端分支同名的前端分支(不存在時改用 `develop`),再以 `E2E_BACKEND_DIR` 指向後端 checkout 執行 `bash e2e/run-e2e.sh`(無參數,全部 tag)。前端 CI 的 `browser-e2e` job 方向相反:checkout 與前端分支同名的後端分支(不存在時改用 `develop`),同樣執行 `run-e2e.sh`,所以前端單獨的 PR 也有真後端證據。

---

## 常見問題

- **前端畫面顯示「暫時無法連線到後端」**:先執行 `curl http://localhost:5173/api/v1/csrf`。如果回的是 HTML,表示 Vite proxy 沒有生效(例如前端版本太舊、dev server 沒有 proxy),請求被 SPA fallback 吃掉了,不是後端的問題。如果是連線被拒,通常是後端沒有跑在 proxy 目標的 port:預設是 `11180`;後端跑在其他 port(例如路徑 B 的 `8080`)時,前端 dev server 要以 `VITE_API_PROXY_TARGET=http://localhost:<port>` 啟動。
- **改了 `VITE_DATA_MODE` 卻沒有反應**:這個值在 dev server 啟動或 `npm run build` 時就固定了,改完要重啟 dev server 或重新 build。值只能是 `mock` 或 `api`,打錯字會直接拋 `RuntimeDataModeError`。
- **`dev` profile 啟動時因 placeholder 無法解析而失敗**:`.env` 不在工作目錄下,或缺 DB / Redis 變數。`dev` 以 `optional:file:.env[.properties]` 讀取**啟動時工作目錄**下的 `.env`,請在 repo 根目錄執行 `java -jar`。必填項目的完整清單見 [`docs/configuration/overview.md`](../configuration/overview.md) 的「必填與選填設定」。
- **用 `dev` / `e2e-browser` 以外的 profile 啟動時,出現 `STOCK_JWT_PRIVATE_KEY must be configured ...` 或 `STOCK_AUTH_COOKIE_SECURE must be true ...`**:非本機 profile 必須提供 JWT 金鑰,而且 cookie 必須設成 secure。用 `java scripts/GenerateJwtJwk.java <kid>` 產生金鑰,格式是 P-256 EC JWK 單行 JSON,不是 PEM。
- **`run-e2e.sh` 一開始就中止,訊息是 port `8080` 已有服務回應 health**:前一次以 `E2E_ENV_ONLY=1` / `E2E_KEEP=1` 起的後端還在跑(或你手動把後端開在 `8080`)。先停掉它再重跑。
- **`run-e2e.sh` 一開始就中止,訊息是 `4173 已有 vite preview`**:本機有一個 `npm run preview`(或前一次 Playwright 留下的 preview)還在跑。Playwright 在本機會重用它,而它可能指向 `11180`,所以腳本先中止。停掉它再重跑。
- **`run-e2e.sh` 找不到 `stock-start` jar**:`E2E_BACKEND_DIR` 指錯位置,或設了 `E2E_SKIP_BUILD=1` 但還沒打包過。請確認路徑指向後端 repo 根。
- **整合測試或後端 E2E 卡住、或回報找不到 Docker**:Testcontainers 需要正在執行的 Docker daemon。只想快速驗證時,改跑不需要 Docker 的 `./mvnw test`。

---

## 下一步

- [`docs/guides/development.md`](development.md):本機開發、建置指令、程式風格
- [`docs/testing/overview.md`](../testing/overview.md):測試分層與執行方式
- [`docs/configuration/overview.md`](../configuration/overview.md):所有環境變數與 Spring profile(`dev` / `demo` / `e2e-browser`)
- [`docs/architecture/overview.md`](../architecture/overview.md):模組架構與資料流
- [`ai-docs/browser-auth-contract.md`](../../ai-docs/browser-auth-contract.md):瀏覽器 cookie 與 CSRF 契約
- 前端 repo 根的 `AGENTS.md`:前端驗證命令(`npm run lint && npm test && npm run build`)與前端規範
