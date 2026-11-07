<!-- generated-by: gsd-doc-writer -->
# 設定總覽(Configuration)

本文件說明 Stock Web V2 後端的執行期設定:Spring profile、環境變數、`application*.yaml` 結構、啟動時的必填檢查、預設值,以及各環境的覆寫方式。

所有執行期設定集中在 `stock-start` 模組:

| 檔案 | 用途 |
|------|------|
| `stock-start/src/main/resources/application.yaml` | 基礎設定,所有 profile 共用 |
| `stock-start/src/main/resources/application-dev.yaml` | `dev` profile(本機開發;**未指定 profile 時的預設值**) |
| `stock-start/src/main/resources/application-demo.yaml` | `demo` profile(展示 / 參考部署,全部值來自環境變數) |
| `stock-start/src/main/resources/application-e2e-browser.yaml` | `e2e-browser` profile(Playwright 瀏覽器 E2E,以 `java -jar` 外部啟動) |
| `stock-start/src/main/resources/logback-spring.xml` | Logback 設定(console + 稽核專用 `audit.log`) |
| `stock-start/src/test/resources/application-test.yaml` | `test` profile(單元 / 整合測試,H2 記憶體 DB) |
| `stock-start/src/test/resources/application-e2e.yaml` | `e2e` profile(後端 E2E,外部服務連線由 `DynamicPropertySource` 注入) |
| `.env.example` | 本機 `.env` 範本(`.env` 已 gitignore,不得提交真值) |

> 機密值(DB / Redis 密碼、JWT 簽章金鑰)一律經環境變數注入,本文件只列變數名稱,不列任何值。

---

## 環境變數

下表列出 `application*.yaml` 以 `${...}` 引用的所有環境變數。「預設值」欄為基礎設定 `application.yaml` 的值;profile 有不同預設時另註明。

### 基礎設施連線

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `STOCK_DB_URL` | **Required**(`dev`、`demo`) | `e2e-browser`:`jdbc:postgresql://localhost:15432/stock_e2e` | PostgreSQL(含 TimescaleDB)JDBC URL |
| `STOCK_DB_USERNAME` | **Required**(`dev`、`demo`) | `e2e-browser`:`stock` | DB 使用者名稱 |
| `STOCK_DB_PASSWORD` | **Required**(`dev`、`demo`) | `e2e-browser` 有測試用預設 | DB 密碼(機密) |
| `STOCK_REDIS_HOST` | **Required**(`dev`、`demo`) | `e2e-browser`:`localhost` | Redis 主機 |
| `STOCK_REDIS_PORT` | **Required**(`dev`、`demo`) | `e2e-browser`:`16379` | Redis 連接埠 |
| `STOCK_REDIS_DATABASE` | Optional | `1` | Redis DB index。依 `ai-docs/redis-convention.md`,stock-web-v2 固定使用 DB 1 |
| `STOCK_REDIS_PASSWORD` | Optional | 空字串 | Redis 密碼(機密);Redis 未設 `requirepass` 時留空 |
| `STOCK_KAFKA_BOOTSTRAP_SERVERS` | Optional | `localhost:9092`(`e2e-browser`:`localhost:19092`) | Kafka bootstrap servers |

### 安全與認證

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `STOCK_JWT_PRIVATE_KEY` | **Required**(`dev`/`test`/`e2e`/`e2e-browser` 以外的 profile) | 空 | JWT 簽章金鑰(機密),格式為 **P-256 EC JWK 單行 JSON**,須含私鑰分量 `d`,建議固定 `kid`。**不是 PEM** |
| `STOCK_JWT_ACCESS_TOKEN_TTL` | Optional | `PT30M` | Access token(JWT)有效時間,ISO-8601 Duration |
| `STOCK_JWT_REFRESH_TOKEN_TTL` | Optional | `P14D` | Refresh token 有效時間 |
| `STOCK_CORS_ALLOWED_ORIGINS` | Optional | `http://localhost:5173`(`e2e-browser`:`http://localhost:4173,http://localhost:5173`) | 逗號分隔的前端 origin 白名單;REST CORS 與 WebSocket `/ws/v1/market` 共用 |
| `STOCK_AUTH_COOKIE_ACCESS_NAME` | Optional | `stock_access` | Access token cookie 名稱 |
| `STOCK_AUTH_COOKIE_REFRESH_NAME` | Optional | `stock_refresh` | Refresh token cookie 名稱 |
| `STOCK_AUTH_COOKIE_PATH` | Optional | `/` | Auth cookie path |
| `STOCK_AUTH_COOKIE_SAME_SITE` | Optional | `Lax` | Auth cookie SameSite;設為 `None` 時必須同時 `secure=true` |
| `STOCK_AUTH_COOKIE_SECURE` | 非本機 profile 必須為 `true` | `false`(`demo`:`true`) | Auth cookie 是否只經 HTTPS 傳送 |
| `STOCK_AUTH_COOKIE_DOMAIN` | Optional | 空(不設 Domain 屬性) | Auth cookie domain |
| `STOCK_AUTH_COOKIE_ACCESS_TOKEN_TTL` | Optional | `PT15M` | Access cookie 的有效時間(與 JWT TTL 是兩個獨立設定) |
| `STOCK_AUTH_COOKIE_REFRESH_TOKEN_TTL` | Optional | `P14D` | Refresh cookie 的有效時間 |
| `STOCK_FORWARD_HEADERS_STRATEGY` | Optional | `native` | 對應 `server.forward-headers-strategy`,決定是否採信 `X-Forwarded-For`(影響以 IP 為鍵的限流與鎖定,見下文) |
| `STOCK_LOCKOUT_THRESHOLD` | Optional(僅 `e2e-browser`) | `3` | E2E 用的帳號鎖定門檻 |
| `STOCK_LOCKOUT_DURATION` | Optional(僅 `e2e-browser`) | `PT2M` | E2E 用的鎖定持續時間 |

### 伺服器與維運

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `SERVER_PORT` | Optional | `11180`(`e2e-browser`:`8080`) | HTTP 服務埠 |
| `STOCK_MANAGEMENT_PORT` | Optional | `11181`(`e2e-browser`:`8080`) | Actuator management port |
| `STOCK_OPENAPI_ENABLED` | Optional | `false` | 是否開啟 `/v3/api-docs`。依 `ai-docs/security.md`,正式環境必須關閉 |
| `STOCK_SWAGGER_UI_ENABLED` | Optional | `false` | 是否開啟 Swagger UI |
| `SPRING_PROFILES_ACTIVE` | Optional | 未設時為 `dev`(`spring.profiles.default`) | 啟用的 Spring profile |

---

## 設定檔格式

設定採 Spring Boot YAML,依 profile 疊加:先載入 `application.yaml`,再以 `application-{profile}.yaml` 覆寫同名鍵。以下為各頂層鍵的用途。

### `application.yaml`(基礎設定)

| 鍵 | 說明 |
|----|------|
| `spring.application.name` | `stock-web-v2` |
| `spring.profiles.default` | `dev`——未指定 profile 時以 `dev` 啟動 |
| `spring.jackson.time-zone` | `Asia/Taipei` |
| `spring.batch.job.enabled` | `false`。backfill Job 需要 Job Parameters,只能由 `BackfillJobLauncher` 帶參數觸發,不可讓 Boot 啟動時自動執行 |
| `spring.batch.jdbc.initialize-schema` | `never`。Batch metadata 表由 Flyway migration 建立 |
| `spring.kafka.*` | Bootstrap servers、idempotent producer(`acks: all`)、JSON serializer / deserializer、consumer 關閉 Kafka client 自動 commit(`enable-auto-commit: false`,改由 listener container 依 ack mode commit)、trusted package `dowob.xyz.stockwebv2.common.event`。刻意不設全域 `ack-mode`,各 listener 以 `containerFactory` 指定 |
| `server.port`、`server.forward-headers-strategy` | HTTP 埠與 proxy header 策略 |
| `management.*` | 獨立 management port;只暴露 `health,info,metrics`;health probes 開啟;`show-details: when_authorized` |
| `springdoc.*` | OpenAPI / Swagger UI 開關,預設皆關閉 |
| `stock.auth.cookie.*` | 瀏覽器 auth cookie 屬性(綁定 `BrowserAuthCookieProperties`) |
| `stock.cors.allowed-origins` | CORS / WebSocket origin 白名單 |
| `stock.jwt.*` | JWT 簽章金鑰與 TTL(綁定 `JwtProperties`) |

`application.yaml` 本身**不含** datasource 與 Redis 連線設定,這些由各 profile 檔提供。

### Profile 檔重點

- **`application-dev.yaml`**:以 `spring.config.import: optional:file:.env[.properties]` 讀取**工作目錄**下的 `.env`(檔案不存在時略過);設定 datasource、Redis、Flyway(`classpath:db/migration`);`dowob.xyz.stockwebv2` logger 設為 `DEBUG`。
- **`application-demo.yaml`**:檔首註解列出所有環境變數;datasource 與 Redis 全由環境變數提供;`stock.auth.cookie.secure` 預設改為 `true`。
- **`application-e2e-browser.yaml`**:所有連線都有指向本機 compose 暫時容器的預設值;Flyway `mixed: true`;開啟 market-data scheduling / ingestor / mock(畫面需要活資料);限流保持啟用但各限額調高為 `10000`,僅 lockout 門檻調低;JWT 與 access cookie TTL 皆為 `PT2H`。

### 其他可設定的 `stock.*` 與 `market-data.*` 屬性

以下屬性未出現在主設定檔,但程式碼有綁定,可依需要在 profile 檔或以環境變數覆寫(Spring relaxed binding,例如 `stock.security.rate-limit.login.limit` 對應 `STOCK_SECURITY_RATE_LIMIT_LOGIN_LIMIT`)。

**`stock.security.rate-limit.*`**(`stock-infrastructure/.../security/RateLimitProperties.java`)

| 屬性 | 預設值 | 說明 |
|------|--------|------|
| `enabled` | `true` | 整體開關(限流 + 帳號鎖定) |
| `login.limit` / `login.window` | `10` / `PT1M` | 登入端點,每 IP |
| `register.limit` / `register.window` | `5` / `PT1H` | 註冊端點,每 IP |
| `refresh.limit` / `refresh.window` | `5` / `PT1M` | 換發 token 端點,每使用者 |
| `api.limit` / `api.window` | `100` / `PT1M` | 已登入 API,每使用者 |
| `public-api.limit` / `public-api.window` | `60` / `PT1M` | 公開端點,每 IP |
| `lockout.threshold` / `lockout.duration` | `5` / `PT15M` | 連續登入失敗鎖定 |

**`stock.security.websocket.*`**(`stock-module-market-data/.../config/WebSocketLimitProperties.java`)

| 屬性 | 預設值 | 說明 |
|------|--------|------|
| `max-connections-global` | `1000` | 全域連線上限,超過時 handshake 回 503 |
| `max-connections-per-ip` | `5` | 每 IP 上限,超過時 handshake 回 429 |
| `max-connections-per-account` | `2` | 每帳號上限,超過時驅逐最舊連線(close code 4002) |

**`market-data.*`**(`stock-module-market-data`)

| 屬性 | 預設值 | 說明 |
|------|--------|------|
| `market-data.mock.enabled` | `true` | 啟用 `MockDataProvider` 與 `MockOnlyProviderRegistry`(目前唯一的 provider 實作) |
| `market-data.scheduling.enabled` | `true` | 啟用 `@Scheduled` 支援(`SchedulingConfig`) |
| `market-data.ingestor.enabled` | `true` | 啟用 `ScheduledIngestor` |
| `market-data.ingestor.fixed-delay-ms` | `1000` | 行情抓取間隔(毫秒) |
| `market-data.ingestor.asset-refresh-ms` | `60000` | 可交易資產清單刷新間隔(毫秒) |
| `market-data.ingestor.fetch-threads` | `8` | 抓取執行緒池大小 |
| `market-data.ingestor.fetch-timeout` | `PT2S` | 每次 provider 呼叫逾時 |
| `market-data.ws.send-threads` | `4` | WebSocket 送出執行緒池大小 |
| `market-data.ws.max-pending-per-session` | `256` | 每個 WebSocket session 待送訊息上限 |

**`stock.audit.log-path`**(`logback-spring.xml`)

| 屬性 | 預設值 | 說明 |
|------|--------|------|
| `stock.audit.log-path` | `logs` | 稽核日誌目錄(相對於工作目錄)。`AUDIT` logger 寫入 `audit.log`,按日切割、保留 90 天,同時輸出到 console |

最小可用的 `demo` 部署環境變數範例(值以佔位符表示):

```bash
SPRING_PROFILES_ACTIVE=demo
STOCK_DB_URL=jdbc:postgresql://<db-host>:<port>/<database>
STOCK_DB_USERNAME=<username>
STOCK_DB_PASSWORD=<from secret store>
STOCK_REDIS_HOST=<redis-host>
STOCK_REDIS_PORT=<redis-port>
STOCK_KAFKA_BOOTSTRAP_SERVERS=<kafka-host>:<port>
STOCK_JWT_PRIVATE_KEY=<P-256 EC JWK, from secret store>
STOCK_CORS_ALLOWED_ORIGINS=https://<frontend-origin>
```

---

## 必填與選填設定

### 啟動時會失敗的情況

| 條件 | 結果 / 錯誤訊息 | 檢查位置 |
|------|----------------|----------|
| `dev` / `demo` 未提供 `STOCK_DB_URL`、`STOCK_DB_USERNAME`、`STOCK_DB_PASSWORD`、`STOCK_REDIS_HOST`、`STOCK_REDIS_PORT` | placeholder 無預設值,Spring 無法解析而啟動失敗 | `application-dev.yaml`、`application-demo.yaml` |
| `STOCK_JWT_PRIVATE_KEY` 空白,且 active profile 不含 `dev`/`test`/`e2e`/`e2e-browser` | `STOCK_JWT_PRIVATE_KEY must be configured outside dev/test/e2e/e2e-browser profiles` | `JwtService` |
| `STOCK_JWT_PRIVATE_KEY` 仍是 PEM(以 `-----BEGIN` 開頭) | 拒絕啟動並附上轉換指令 | `JwtService` |
| JWK 不是合法 JSON / `kty` 不是 EC / 缺私鑰分量 / 曲線不是 P-256 | `must be a valid EC JWK (JSON)`、`must be an EC JWK for ES256`、`must include EC private key material`、`must use the P-256 curve for ES256` | `JwtService` |
| `stock.jwt.access-token-ttl` 或 `refresh-token-ttl` 為 null、零或負值 | `... must not be null` / `... must be positive` | `JwtProperties` |
| `STOCK_AUTH_COOKIE_SECURE=false`,且 active profile 不含 `dev`/`test`/`e2e`/`e2e-browser` | `STOCK_AUTH_COOKIE_SECURE must be true outside dev/test/e2e/e2e-browser profiles` | `BrowserAuthCookieSecureGuard` |
| `STOCK_AUTH_COOKIE_SAME_SITE=None` 但 `secure=false` | `SameSite=None auth cookies require secure=true` | `BrowserAuthCookieProperties` |

### JWT 簽章金鑰

- 產生新金鑰(只需 JDK):`java scripts/GenerateJwtJwk.java <kid>`(未給 `kid` 時預設 `stock-signing-yyyyMMdd`)。輸出為一行含私鑰的 JSON,請比照密碼處理,不要進 shell history、log 或 commit。
- 沿用舊 PEM 金鑰:使用 `JwkKeyConverter`,用法見 `stock-infrastructure/.../security/JwkKeyConverter.java` 的 javadoc(fat jar 須經 `PropertiesLauncher` 指定 `-Dloader.main`)。
- 不要用線上工具產生或轉換簽章金鑰。
- `dev`/`test`/`e2e`/`e2e-browser` 留空時,啟動會產生暫時金鑰並記錄 warning;每次重啟後既有 access token 失效。
- 換鑰只會讓 access token 失效;refresh token 是 Redis 中的不透明值,不受影響。需強制全部重新登入時,另須撤銷 refresh token(見 `ai-docs/security.md` 的 Key Rotation Strategy)。

### 一定要注意的選填設定

- **`STOCK_CORS_ALLOWED_ORIGINS`**:禁止設為 `*`(CORS 開了 `allowCredentials`)。值為空時,REST 沒有允許的跨來源,WebSocket 則維持 Spring 預設的同源限制。
- **`STOCK_FORWARD_HEADERS_STRATEGY`**:預設 `native` 交給 Tomcat `RemoteIpValve`,只有直接連線端屬於內網位址(10/8、172.16/12、192.168/16、127/8 等)時才採信 `X-Forwarded-For`。若 ingress 不在這些網段,須以 `server.tomcat.remoteip.internal-proxies` 補上,否則以 IP 為鍵的登入限流與鎖定會把所有使用者視為同一來源。<!-- VERIFY: 正式環境 K3s ingress 的實際來源網段是否落在預設內網範圍 -->
- **`STOCK_OPENAPI_ENABLED` / `STOCK_SWAGGER_UI_ENABLED`**:所有執行期 profile(含 `dev`)預設關閉。因為忘了指定 profile 的部署會以 `dev` 啟動,`dev` 刻意不開;本機需要時請明確設為 `true`。只有測試用的 `e2e` profile 會開啟。

---

## 預設值

| 設定 | 預設值 | 定義位置 |
|------|--------|----------|
| Spring profile | `dev` | `application.yaml` `spring.profiles.default` |
| `server.port` | `11180` | `application.yaml` |
| `management.server.port` | `11181` | `application.yaml` |
| Actuator 暴露端點 | `health,info,metrics` | `application.yaml` |
| `spring.jackson.time-zone` | `Asia/Taipei` | `application.yaml` |
| `spring.kafka.bootstrap-servers` | `localhost:9092` | `application.yaml` |
| `spring.data.redis.database` | `1` | 各 profile 檔 |
| `stock.jwt.access-token-ttl` | `PT30M` | `application.yaml` |
| `stock.jwt.refresh-token-ttl` | `P14D` | `application.yaml` |
| `stock.auth.cookie.access-name` / `refresh-name` | `stock_access` / `stock_refresh` | `application.yaml`;空白時 `BrowserAuthCookieProperties` 也會補同樣的值 |
| `stock.auth.cookie.path` / `same-site` | `/` / `Lax` | 同上 |
| `stock.auth.cookie.secure` | `false`(`demo`:`true`) | `application.yaml` / `application-demo.yaml` |
| `stock.auth.cookie.access-token-ttl` | `PT15M` | `application.yaml`;null 或非正值時 `BrowserAuthCookieProperties` 補 15 分鐘 |
| `stock.auth.cookie.refresh-token-ttl` | `P14D` | `application.yaml`;null 或非正值時補 14 天 |
| `stock.cors.allowed-origins` | `http://localhost:5173` | `application.yaml` |
| `server.forward-headers-strategy` | `native` | `application.yaml` |
| `springdoc.api-docs.enabled` / `swagger-ui.enabled` | `false` / `false` | `application.yaml` |
| 限流 / 鎖定 / WebSocket 上限 / market-data | 見上方「其他可設定的屬性」各表 | 對應的 properties 類別或 `@Value` 預設 |
| 稽核日誌目錄 | `logs` | `logback-spring.xml` |

---

## 各環境覆寫

### 本機開發(`dev`)

1. 複製範本:`cp .env.example .env`,填入 DB 與 Redis 帳密。`.env` 與 `.env.*`(`.env.example` 除外)都已 gitignore。
2. `dev` profile 以 `optional:file:.env[.properties]` 讀取**啟動時工作目錄**下的 `.env`,因此請從 repo 根目錄啟動。
3. Windows 可使用 `scripts/run-dev.ps1`:它把 `.env` 載入行程環境變數、`SPRING_PROFILES_ACTIVE` 未設時補 `dev`,再以 `mvnw -pl stock-start -am package -DskipTests` 打包並 `java -jar` 啟動。
4. `STOCK_JWT_PRIVATE_KEY` 可留空(自動產生暫時金鑰)。

`.env.example` 的 DB / Redis 範例值指向內網 K3s NodePort 主機。<!-- VERIFY: .env.example 所列內網 DB / Redis 主機與 NodePort 目前是否仍可連線 -->`STOCK_KAFKA_BOOTSTRAP_SERVERS` 不在範本中,未設時使用 `localhost:9092`。

### 展示 / 參考部署(`demo`)

- 設定 `SPRING_PROFILES_ACTIVE=demo`,所有值由環境變數提供(變數清單見 `application-demo.yaml` 檔首註解)。
- 必須提供 `STOCK_JWT_PRIVATE_KEY`;`STOCK_AUTH_COOKIE_SECURE` 預設為 `true`,不可改成 `false`。
- 依 `ai-docs/security.md`,JWT 私鑰存放在 K8s Secret 並以環境變數注入,K3s 須啟用 `--secrets-encryption`。<!-- VERIFY: 部署環境的 K8s Secret 名稱與 K3s secrets-encryption 是否已啟用 -->
- management port 不應經 K3s Service 對外暴露。<!-- VERIFY: K3s Service / ingress 實際只暴露 HTTP 埠而未暴露 management port -->

repo 內沒有 `prod` profile 檔,也沒有 Dockerfile 或 K8s manifest。若以其他 profile 名稱部署正式環境,同樣會套用非本機 profile 的啟動守衛:必須提供 `STOCK_JWT_PRIVATE_KEY`,並把 `STOCK_AUTH_COOKIE_SECURE` 設為 `true`(基礎預設是 `false`,不設會拒絕啟動)。<!-- VERIFY: 正式環境實際使用的 Spring profile 名稱與環境變數注入方式 -->

### 瀏覽器 E2E(`e2e-browser`)

- 由前端 repo 的 `e2e/run-e2e.sh` 啟動:先 compose 起暫時的基礎設施容器,再以 `java -jar` 搭配 `e2e-browser` profile 啟動後端(port `8080`),等 `/actuator/health` 回報 UP。
- 連線預設值對應 compose 容器的本機埠(PostgreSQL `15432`、Redis `16379`、Kafka `19092`),一般不需另外設定;要測試鎖定行為時可調整 `STOCK_LOCKOUT_THRESHOLD` / `STOCK_LOCKOUT_DURATION`。

### 測試(`test`、`e2e`)

- **`test`**:datasource 為 H2 記憶體 DB(`MODE=PostgreSQL`),Flyway 停用,排除 Redis、Spring Security 與 Kafka 的 auto-configuration;market-data scheduling / ingestor / mock 全部關閉;JWT 金鑰留空(自動產生)。
- **`e2e`**:外部服務(PostgreSQL / Redis / Kafka)的連線由測試的 `DynamicPropertySource` 注入;market-data mock 保持啟用,但關閉 scheduling / ingestor,避免背景 tick 干擾 WebSocket 測試;明確開啟 OpenAPI 與 Swagger UI 供 smoke 測試驗證 `/v3/api-docs`。

### 日誌層級

- `dev` 將 `dowob.xyz.stockwebv2` 設為 `DEBUG`;其他 profile 沿用 root `INFO`。
- 可用 Spring Boot 標準方式覆寫,例如環境變數 `LOGGING_LEVEL_DOWOB_XYZ_STOCKWEBV2=INFO`。`logback-spring.xml` 沿用 Boot 預設的 console appender,因此 `logging.level.*` 照常生效。
