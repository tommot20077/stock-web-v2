<!-- generated-by: gsd-doc-writer -->
# 開發指南

> 本頁整理日常開發流程:建置、格式化、測試節奏、新增 migration 與跨模組 endpoint、分支與 commit、PR 與 CI。**規範以 `ai-docs/` 為準**,本頁只摘要並附連結;兩邊有出入時,以 `ai-docs/` 與程式碼為準。第一次啟動請先看 [`getting-started.md`](getting-started.md)。

## 本機開發環境

1. 準備 JDK 21。Docker 只有跑整合測試 / E2E(Testcontainers)時才需要。repo 已附 Maven Wrapper(`mvnw` / `mvnw.cmd`),不用另外裝 Maven。
2. clone 並建置全部模組:
   ```bash
   git clone https://github.com/tommot20077/stock-web-v2.git
   cd stock-web-v2
   ./mvnw -B install -DskipTests
   ```
3. 從範本建立 `.env`。`dev` profile 會以 `spring.config.import: optional:file:.env[.properties]` 讀取 repo 根的 `.env`,所以啟動指令要在 repo 根執行。`.env` 已列入 gitignore,**不要提交真值**:
   ```bash
   cp .env.example .env
   ```
   各變數的意義、必填與預設值見 [`docs/configuration/overview.md`](../configuration/overview.md)。
4. 打包並啟動(預設 profile 是 `dev`;API 在 port `11180`,Actuator 在 `11181`):
   ```bash
   ./mvnw -pl stock-start -am package -DskipTests
   java -jar stock-start/target/stock-start-0.0.1-SNAPSHOT.jar
   ```
   Windows 可直接跑 `scripts/run-dev.ps1`:它會載入 `.env`、預設 `SPRING_PROFILES_ACTIVE=dev`、打包,再執行 jar。

repo 用 `.gitattributes` 把所有文字檔統一成 LF(`* text=auto eol=lf`)。

### 和前端一起開發

前端是另一個 sibling repo 的 Vue 3 app。兩個 repo 的相對路徑**只在** [`ai-docs/judgment.md`](../../ai-docs/judgment.md) §8 定義,本頁一律稱「前端 repo 根」。前端 Vite dev server 會把 `/api`、`/ws` proxy 到 `localhost:8080`,而後端預設 port 是 `11180`;本機聯調時請用 `SERVER_PORT=8080` 啟動後端,或調整前端的 proxy 設定。

## 建置指令

| 指令 | 說明 |
|------|------|
| `./mvnw -B install -DskipTests` | 建置並安裝全部模組,不跑測試 |
| `./mvnw -pl stock-start -am package -DskipTests` | 只打包可執行 jar(`stock-start` 及其相依模組) |
| `./mvnw spotless:check` | 檢查格式(CI 的 Lint job 就是跑這個) |
| `./mvnw spotless:apply` | 自動修正格式 |
| `./mvnw test` | Unit + Web 層測試(surefire,不需 Docker) |
| `./mvnw -pl <module> -am test` | 只跑單一模組的測試,例如 `-pl stock-module-trading` |
| `./mvnw -pl stock-start -am verify` | 加跑 `*IT.java` 整合測試(failsafe + Testcontainers,需 Docker);同時產出 JaCoCo 報告 |
| `./mvnw -pl stock-start -am test -Pe2e` | 後端 E2E(`*E2E.java`) |
| `java scripts/GenerateJwtJwk.java [kid]` | 產生 JWT 簽章用的 EC JWK(單檔 Java script,`kid` 可省略) |

補充:

- Spotless 的 `check` goal 綁在 `validate` phase,所以**每次 Maven build(包括 `./mvnw test`)都會檢查格式**,格式不對就直接失敗。
- JaCoCo(0.8.13)只產報告,沒有覆蓋率門檻。
- 測試分層、命名與 Testcontainers 用法見 [`ai-docs/testing-standards.md`](../../ai-docs/testing-standards.md) 與 [`docs/testing/overview.md`](../testing/overview.md)。

## 程式風格

- **Spotless 2.44.5**(設定在根 `pom.xml`)只管三件事:import 順序(`dowob,jakarta,org,com,java,\#`)、移除未使用的 import、空白衛生(去掉行尾空白、檔尾換行)。範圍是 `src/main/java/**` 與 `src/test/java/**`。
- **沒有** google-java-format 之類的格式化器。縮排沿用既有風格:4 個空白;多行參數清單每個參數一行。
- 註解與 Javadoc 一律用**繁體中文**,識別字與技術術語用英文。class、public method、member variable 都必須有 Javadoc;method 內可以用短的 `//` 說明「為什麼」,不要留下被註解掉的程式碼。
- 測試方法名用英文 `camelCase`(禁止中文),中文描述放在 `@DisplayName`。
- DTO / Request / 值物件優先用 `record`;業務錯誤一律丟 `BusinessException(ErrorCode, 靜態訊息)`,**訊息不得回射使用者輸入**。
- null / empty 檢查:純 null 用 `Objects`,null + empty 用 `ObjectUtils`,字串用 `StringUtils.isBlank`;不要用已 deprecated 的 `ObjectUtils.defaultIfNull`。
- SQL 一律用 `:namedParam` 綁定,禁止字串串接。

完整規則見 [`ai-docs/code-standards.md`](../../ai-docs/code-standards.md);命名與分層慣例的摘要見 [`.planning/codebase/CONVENTIONS.md`](../../.planning/codebase/CONVENTIONS.md)。

## 測試先行(TDD)

新功能與 bug 修正都採 TDD:**先寫會失敗的測試,親眼看到它變紅,再寫實作讓它變綠**。

- Unit、Web 層、整合測試都是必要的;E2E 只有 Yuan 能豁免([`ai-docs/testing-standards.md`](../../ai-docs/testing-standards.md))。
- 依層級命名:`*Test.java`(unit,Mockito)、`*ControllerWebMvcTest.java`(`@WebMvcTest`)、`*IT.java`(`@SpringBootTest` + Testcontainers,由 failsafe 執行)、`*E2E.java`(`-Pe2e`)。**需要容器的測試一定要以 `IT` 結尾**,否則會被 surefire 撿去跑,拖慢單元測試迴圈。
- stock-start 的整合測試共用 `stock-start/src/test/java/dowob/xyz/stockwebv2/start/support/ContainerIT.java` 的 singleton containers(TimescaleDB、Redis、Kafka)。
- 測試輸出要乾淨,除非測的就是錯誤處理。

宣稱完成前,要符合 [`ai-docs/judgment.md`](../../ai-docs/judgment.md) §8 的「真完成」條件:對應層級的測試全綠;跨 repo 的變更兩邊驗證都要跑(後端 `./mvnw test` 或 focused `-pl`,前端在前端 repo 根的 `vue-app/` 跑 `npm run lint && npm test && npm run build`);而且要有證據(測試輸出、log),不能只是「應該可以」。

## 新增 Flyway migration

所有 production migration **只能**放在 `stock-db-migration/src/main/resources/db/migration/`,其他模組不得自己放 `db/migration`。

1. 先看該目錄目前最大的版本號(目前最新是 `V19__assets_currency_check.sql`,所以下一個是 `V20`)。
2. 新增 `V{N}__{english_snake_case_description}.sql`:版本號連續、不跳號,版本與描述之間是兩個底線。
3. DDL 要和 module 的 model class 完全一致。
4. **已經套用到任何環境的 migration 絕對不能修改**,否則 Flyway checksum 驗證會失敗、應用起不來;要改就新增一支 migration。
5. TimescaleDB 的部分 DDL(例如 continuous aggregate)有特殊限制,寫之前先看 convention 的 TimescaleDB 段落。
6. 測試用 seed data 用 repeatable migration(`R__*.sql`),放在模組的 `src/test/resources/db/testdata/`。不要建立模組自己的 `src/test/resources/db/migration/`。

整合測試和 production 用的是同一套 migration:`stock-start` 以 compile scope 相依 `stock-db-migration`,有自己 IT 的模組(目前是 asset、market-data)則以 test scope 相依。因此繼承 `ContainerIT` 的整合測試啟動時,Flyway 會在 TimescaleDB container 上套用全部 migration,新 migration 有錯就會直接失敗。細則見 [`ai-docs/flyway-convention.md`](../../ai-docs/flyway-convention.md)。

## 新增跨模組 endpoint(透過 Facade)

業務模組(`stock-module-*`)之間**不能有 Maven 相依,也不能直接存取彼此的 repository / SQL**,只能透過 `stock-infrastructure` 裡的 Facade 介面溝通。這條規則由 `stock-start/src/test/java/dowob/xyz/stockwebv2/start/ArchitectureRulesTest.java`(ArchUnit)強制執行。

假設你在 `stock-module-trading` 需要另一個模組的資料,流程如下:

1. **確認有沒有現成的 Facade 方法**。介面在 `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/{asset,fx,marketdata,user}/`(`AssetFacade`、`FxRateFacade`、`MarketDataFacade`、`UserPreferenceFacade`)。
2. **需要新方法時**:在 `stock-infrastructure` 的 Facade 介面加方法,回傳型別也放在 `stock-infrastructure`(例如 `AssetProfile`、`LatestMarketPrice`);接著在擁有資料的模組實作,例如 `stock-module-asset/.../asset/service/AssetFacadeImpl.java`、`stock-module-market-data/.../marketdata/api/MarketDataFacadeImpl.java`。要一次查多筆時,一律提供批次方法(例如 `findProfiles(Collection)`),避免 N+1。
3. **Application Service 呼叫 Facade**。Controller 不得直接呼叫 Facade 或 repository(ArchUnit 會擋)。每個請求的跨模組往返預設 **≤ 3 次**;超過時必須同時滿足三個條件(全是批次方法、結果有 Redis 快取、呼叫點寫註解說明理由),見 [`ai-docs/code-standards.md`](../../ai-docs/code-standards.md) 的 Facade Call Rules。
4. **Controller** 放在模組的 `api/` 套件,寫法參考 `stock-module-trading/.../trading/api/TradingController.java`:
   - 每個 endpoint method 都必須有 `@PreAuthorize`(例如 `hasAuthority('TRADE_EXECUTE')`),或者在 `EndpointAuthorizationRulesTest` 的 allow-list 裡附上理由。
   - 呼叫者用 `AuthenticatedUserResolver.resolve(authentication)` 取得,再把 `userId` 往下傳;repository SQL 一律以 `user_id` 限定範圍,非擁有者存取會回 404(不是 403)。
   - 成功時回 `ApiResponse.success(dto, ApiMetaFactory.current())`;參數原樣交給 service,不在 controller 做業務判斷。
   - 寫入類操作要用 `AuditLogger` 記錄成功與失敗,catch 到 `BusinessException` 記完稽核後原樣 rethrow。
5. **錯誤碼**:新的業務錯誤加在 `stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/ErrorCode.java`(每個常數帶 HTTP status 與靜態訊息),轉換由 `stock-start/.../error/GlobalExceptionHandler.java` 統一處理。
6. **測試**:service 寫 unit test、controller 寫 `*ControllerWebMvcTest`(含權限)、在 `stock-start` 寫 `*IT` 驗證完整流程,並補一個「非擁有者回 404」的測試。

`/api/v1/**` 底下的新路徑預設就要求登入(`SecurityConfig` 的 `anyRequest().authenticated()`)。如果端點走 browser cookie 認證又是 unsafe method,會經過自訂的 `BrowserCsrfFilter`,契約見 [`ai-docs/browser-auth-contract.md`](../../ai-docs/browser-auth-contract.md)。

### 其他常見情境

- **新增 Redis key**:依 [`ai-docs/redis-convention.md`](../../ai-docs/redis-convention.md) 的 As Implemented 表格:前綴用擁有模組(`user:`、`market:`、`portfolio:`、`ws:`、`rl:`),cache key **必須設 TTL**(eviction 是 `volatile-lru`);快取的 DTO 形狀改變時要升 key 版本(例如 `portfolio:summary:v3:*`)。寫入後的快取失效一律放在 `afterCommit`。
- **事件 / Kafka**:目前只有行情資料走 Kafka(`market.price.tick.v1`、`market.price.backfill.v1`);`EventPublisher` 等介面是保留的擴充點,沒有實作。要新增事件前先讀 [`ai-docs/event-conventions.md`](../../ai-docs/event-conventions.md)。
- **影響前端的 API 變更**:回應一律包在 `ApiResponse<T>` 信封,分頁用 `PageResponse<T>`。交易與觀察清單的契約分別在 [`ai-docs/trading-portfolio-contract.md`](../../ai-docs/trading-portfolio-contract.md) 與 [`ai-docs/watchlist-contract.md`](../../ai-docs/watchlist-contract.md),改契約要同步更新文件與前端。

## 分支慣例

- 預設整合分支是 `develop`;`main` 是穩定分支。
- 沒有書面的分支命名規則。從合併紀錄看,慣用 `<type>/<短描述>`,type 與 commit type 一致,例如 `fix/disable-batch-job-autorun`、`docs/align-ai-docs`、`chore/it-to-failsafe`。

## Commit 規範

依 [`ai-docs/git-convention.md`](../../ai-docs/git-convention.md):

- 採 **Conventional Commits v1.0.0**,格式是 `<type>(<scope>): <繁體中文 subject>`。
- type:`feat` / `fix` / `docs` / `style` / `refactor` / `perf` / `test` / `build` / `ci` / `chore`。
- **scope**:有規劃的工作用 phase / plan id(例如 `feat(04.2-12)`、`docs(04.2)`)或 quick id(例如 `fix(quick-261106-fbs)`);只有不屬於規劃工作的變更,才用模組(`user`、`asset`、`trading`、`market-data`、`common`、`infrastructure`)或分層(`controller`、`service`、`security`…)當 scope。前端 repo 用同一套規則。
- subject 用繁體中文、祈使語氣、結尾不加句號,最多 72 字元。
- Breaking change 在 type 後加 `!`,並在 footer 寫 `BREAKING CHANGE: <說明>`。
- 保持 commit 小而完整(atomic)。

## PR 流程

- 一般功能的 PR 開向 **`develop`**;hotfix 開向 `main`。
- 開 PR 前,先在本機跑過 `./mvnw spotless:check` 與 `./mvnw test`;有動到整合流程就加跑 `./mvnw -pl stock-start -am verify`(需 Docker)。
- `ai-docs/*.md` 的變更需要 Yuan 審核([`ai-docs/maintenance-protocol.md`](../../ai-docs/maintenance-protocol.md))。
- 跨 repo 的功能:前端也開同名分支。Browser E2E job 會優先 checkout 前端同名分支,找不到才退回前端的 `develop`。

### CI 跑什麼

`.github/workflows/ci.yml` 在**所有分支的 push**、對 `main` / `develop` 的 **PR**,以及手動觸發(`workflow_dispatch`)時執行,環境是 Temurin JDK 21。同一個 ref 的新執行會取消舊的。各 job 依序執行:

| Job | 指令 | 說明 |
|-----|------|------|
| Lint | `./mvnw -B spotless:check` | 搶在耗時的測試之前先回報格式問題 |
| Unit Tests | `./mvnw -B test --fail-at-end` | Unit + Web 層(surefire) |
| Integration Tests(needs: unit) | `./mvnw -B -pl stock-start -am verify -Dspring-boot.repackage.skip=true --fail-at-end` | `*IT.java`(failsafe + Testcontainers) |
| E2E Tests(needs: integration) | `./mvnw -B -pl stock-start -am test -Pe2e` | 後端 `*E2E.java` |
| Browser E2E Tests(needs: e2e) | 前端 `vue-app/` 下 `npm ci`、安裝 Playwright Chromium,再跑 `bash e2e/run-e2e.sh` | 跨 repo 的 Playwright `@smoke`;**只在 PR 或 `develop` push 時跑** |

每個 job 都會發布 JUnit 測試報告,並把 surefire / failsafe 報告上傳成 artifact(保留 7 天)。

## 延伸閱讀

- [`ai-docs/code-standards.md`](../../ai-docs/code-standards.md):程式規範(權威)
- [`ai-docs/testing-standards.md`](../../ai-docs/testing-standards.md):測試分層與命名(權威)
- [`ai-docs/git-convention.md`](../../ai-docs/git-convention.md):Commit 規範(權威)
- [`ai-docs/flyway-convention.md`](../../ai-docs/flyway-convention.md):migration 規則
- [`ai-docs/redis-convention.md`](../../ai-docs/redis-convention.md):Redis key 與 TTL
- [`ai-docs/event-conventions.md`](../../ai-docs/event-conventions.md):事件與 Kafka
- [`ai-docs/security.md`](../../ai-docs/security.md):安全規範
- [`docs/architecture/overview.md`](../architecture/overview.md):架構與資料流
- [`docs/configuration/overview.md`](../configuration/overview.md):環境變數與 profiles
