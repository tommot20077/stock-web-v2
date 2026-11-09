---
last_mapped_commit: adae04117fcfa99db40b8ca304fb5cb9c5e36ec9
last_mapped_at: 2026-11-07
---
# Technology Stack

**Analysis Date:** 2026-11-07

> 本產品由兩個 repo 組成：後端（本 repo）與前端（「前端 repo 根」，程式碼在 `vue-app/`；本機 `../../vue/stock-v2` 與 cloud `../stock-web-v2-front-end` 的路徑對照見 `ai-docs/judgment.md` §8）。前端路徑一律相對於前端 repo 根撰寫。

## Languages

**Primary:**
- Java 21 — 後端全部模組（`pom.xml` 的 `<java.version>21</java.version>`，`maven-compiler-plugin` 用 `<release>`）
- TypeScript 6（`^6.0.3`）— 前端 `vue-app/src/`（`vue-app/tsconfig.json`）

**Secondary:**
- SQL（PostgreSQL / TimescaleDB 方言）— Flyway migration `stock-db-migration/src/main/resources/db/migration/V*.sql`
- Vue SFC（`.vue`）— 前端元件
- Bash / PowerShell — `scripts/run-dev.ps1`、前端 `vue-app/e2e/run-e2e.sh`
- 單檔 Java script — `scripts/GenerateJwtJwk.java`（`java scripts/GenerateJwtJwk.java <kid>` 產生 JWK）

## Runtime

**Environment:**
- 後端：JVM 21（CI 用 Temurin 21，`.github/workflows/ci.yml`）；Spring Boot 內嵌 Tomcat，`server.port` 預設 `11180`，management port `11181`（`stock-start/src/main/resources/application.yaml`）
- 前端：瀏覽器 SPA；開發 / 建置用 Node.js 20（前端 `.github/workflows/ci.yml`、後端 Browser E2E job）
- 時區：`spring.jackson.time-zone: Asia/Taipei`

**Package Manager:**
- 後端：Maven（Maven Wrapper `mvnw` / `mvnw.cmd`）；多模組 reactor
- 前端：npm；Lockfile：`vue-app/package-lock.json` 存在（CI 用 `npm ci`）

## Frameworks

**Core（後端）:**
- Spring Boot 4.0.4（`spring-boot-starter-parent`，root `pom.xml`）
- Spring Web MVC（`spring-boot-starter-webmvc`）— REST API（`/api/v1/**`、`/api/admin`）
- JDBC 持久層：手寫 `Jdbc*Repository` + `JdbcClient`（market-data 批次寫入用 `NamedParameterJdbcTemplate`）；非 JPA、也不是 Spring Data JDBC repository —— `spring-boot-starter-data-jdbc` 只提供 JDBC 基礎設施
- Spring Security + `spring-security-oauth2-jose` — JWT（EC P-256 JWK）簽發 / 驗證（`stock-infrastructure`）
- Spring WebSocket（`spring-boot-starter-websocket`）— 行情推播 `/ws/v1/market`（`stock-module-market-data/.../config/WsConfig.java`）
- Spring Kafka（`spring-kafka`、`spring-boot-kafka`）— 行情 tick / backfill 事件管線
- Spring Batch（`spring-boot-starter-batch`）— 歷史資料 backfill job（`spring.batch.job.enabled: false`，只由 `BackfillJobLauncher` 觸發）
- Spring Data Redis（`spring-boot-starter-data-redis`）
- Flyway（`spring-boot-starter-flyway` + `flyway-database-postgresql`）

**Core（前端，`vue-app/package.json`）:**
- Vue `^3.5.34`
- Pinia `^3.0.4` — 狀態管理
- Vue Router `^5.0.7`
- HTTP：原生 `fetch`（無 axios、無 UI 框架）；WebSocket：原生 `WebSocket`，只在 `vue-app/src/services/quoteStream.ts` 建立

**Testing（後端）:**
- JUnit 5 + `spring-boot-starter-test`、`spring-boot-starter-webmvc-test`、`spring-security-test`
- Testcontainers 1.21.4（BOM）— `timescale/timescaledb:2.17.2-pg16`、`redis:7.4-alpine`、`confluentinc/cp-kafka:7.6.0`
- `spring-kafka-test`、Awaitility、AssertJ
- H2（`stock-start` test scope）
- ArchUnit 1.3.0（`archunit-junit5`）— 模組邊界守門
- Surefire（unit）/ Failsafe（integration），`-Pe2e` profile 跑 E2E

**Testing（前端）:**
- Vitest `^4.1.6`（jsdom `^29.1.1`，`vue-app/vite.config.ts` 的 `test` 區塊，`setupFiles: ./src/testSetup.ts`）
- Playwright `^1.61.1`（`vue-app/playwright.config.ts`、`vue-app/e2e/`）

**Build/Dev:**
- Spotless 2.44.5 — 只鎖 import 順序（`dowob,jakarta,org,com,java,\#`）、移除未用 import、空白衛生；綁 `validate` phase
- JaCoCo 0.8.13 — 只出報告、無門檻
- `spring-boot-maven-plugin`（`stock-start` 打包可執行 jar）
- springdoc-openapi 3.0.2（`springdoc-openapi-starter-webmvc-ui`），預設關閉
- 前端：Vite `^8.0.13` + `@vitejs/plugin-vue ^6.0.7`、`vue-tsc ^3.2.9`（`npm run build` = `vue-tsc --noEmit && vite build`）
- 前端 lint / format：ESLint `^10.9.1`（`vue-app/eslint.config.js`，`typescript-eslint`、`eslint-plugin-vue`、`eslint-config-prettier`）、Prettier `^3.9.6`（`vue-app/.prettierrc.json`）

## Key Dependencies

**Critical:**
- `spring-security-oauth2-jose` — JWT 簽章；金鑰為 EC JWK（`stock.jwt.private-key`），PEM 需經 `JwkKeyConverter` 轉換
- `postgresql` JDBC driver + `flyway-database-postgresql` — 主資料庫
- `spring-kafka` — 行情 ingest → `PriceWriterConsumer` / `WsBroadcastConsumer`
- `jackson-databind` / `jackson-datatype-jsr310` — JSON 與時間型別（`stock-common`）
- `commons-lang3`（`stock-common`、`stock-module-trading`）

**Infrastructure:**
- `spring-boot-starter-actuator` + `micrometer-core` — health / metrics（`stock-start`、`stock-module-market-data`）
- `jakarta.servlet-api`（`stock-infrastructure`）

## Configuration

**Environment:**
- Spring profiles：`dev`（預設）、`demo`、`e2e-browser`（`stock-start/src/main/resources/application-*.yaml`）；測試用 `application-test.yaml`、`application-e2e.yaml`（`stock-start/src/test/resources/`）
- `dev` profile 以 `spring.config.import: optional:file:.env[.properties]` 讀 repo 根 `.env`；範本為 `.env.example`（存在，不得提交真值）
- 關鍵設定：`STOCK_DB_URL`、`STOCK_DB_USERNAME`（及密碼）、`STOCK_REDIS_HOST/PORT/DATABASE`、`STOCK_KAFKA_BOOTSTRAP_SERVERS`、`STOCK_JWT_PRIVATE_KEY`（非 dev/test/e2e profile 留空會拒絕啟動）、`STOCK_CORS_ALLOWED_ORIGINS`、`STOCK_AUTH_COOKIE_*`、`STOCK_FORWARD_HEADERS_STRATEGY`、`STOCK_OPENAPI_ENABLED`、`STOCK_SWAGGER_UI_ENABLED`、`SERVER_PORT`、`STOCK_MANAGEMENT_PORT`
- 前端：`VITE_DATA_MODE`（`mock` | `api`，未設為 mock；無效值 fail fast，邏輯在 `vue-app/src/services/runtimeDataMode.ts`）；無 base URL 設定，`vue-app/src/services/apiClient.ts` 一律送同源相對路徑

**Build:**
- 後端：root `pom.xml`、各模組 `*/pom.xml`
- 前端：`vue-app/vite.config.ts`（dev `5173` / preview `4173`，proxy `/api`、`/ws` → `$VITE_API_PROXY_TARGET`（預設 `http://localhost:11180`，與後端 `server.port` 預設一致；Browser E2E 由 Playwright 設為 8080））、`vue-app/tsconfig.json`、`vue-app/eslint.config.js`、`vue-app/playwright.config.ts`

## Platform Requirements

**Development:**
- JDK 21、Docker（Testcontainers 整合測試）、Node.js 20
- 可連線的 PostgreSQL（含 TimescaleDB extension）、Redis、Kafka（`.env.example` 指向內網 K3s NodePort）

**Production:**
- 部署於 K3s ingress 之後（`server.forward-headers-strategy: native` 的註解；`ai-docs/architecture.md`、`ai-docs/security.md`）；repo 內無 Dockerfile / K8s manifest
- 前端為靜態 SPA（`vue-app/dist/`），需與後端同源（或由反向代理轉發 `/api`、`/ws`）

---

*Stack analysis: 2026-11-07*
