# Phase 5: Cross-Repo Browser Flow Verification & Contract Hardening - Research

**Researched:** 2026-11-07
**Domain:** 跨 repo 驗證(Maven / Vitest / Playwright 真後端)、JSON 金額契約(Jackson 3 BigDecimal 字串化 + 前端 decimal)、CI、Testcontainers 共用、設定一致性
**Confidence:** HIGH(程式碼現況與 Jackson / big.js 行為皆在本 session 以工具實測);MEDIUM(CI 新 job 與共用容器的時序風險需 CI 實跑才能定案)

<user_constraints>
## User Constraints (from CONTEXT.md)

> 本節是 research 當下的 CONTEXT 摘錄。research 之後 Yuan 另有補充裁決 D-26 ~ D-29 與「D-15 補充」(見 CONTEXT.md〈Research 後補充裁決〉與本檔 `## Open Questions (RESOLVED)`);兩者衝突時以 CONTEXT.md 為準。

### Locked Decisions

### 已裁決範圍(來源:scope-decisions todo,本次 discuss 不重議,列出以便 planner 對照)
- **D-01:** SC1 / SC2:逐條對照現有測試,列出已涵蓋 / 缺口,**只補缺口**。稽核表要留下來作為驗收證據(格式見 Claude's Discretion)。
- **D-02:** quick-261107-1j4 的 4 條觀察:(a) 交易 `executedAt` 晚於現在時補 `fields.executedAt`;(b) 觀察清單名稱的 DB CHECK 擴充到不換行空白(新 migration);(c) 紅漲綠跌模式下,Ops 失敗狀態燈不得變綠(失敗 / 錯誤另立固定色),寫死的 `rgba` 字面色(如 `.pill.buy`)改用會跟著對調的 token;(d) CashEntryDialog / OrderTicket 的「未來時間」判斷改用 `useNow`。
- **D-03:** 文件更新時發現的一致性問題:`ai-docs/architecture.md` 的「Spring Data JDBC」用詞更正;`browser-auth-contract.md` 補「已帶有效 `stock_access` cookie 時 login / register 要求 CSRF」;`application-demo.yaml` 檔頭補 `STOCK_AUTH_COOKIE_SECURE`;`application.yaml` 的 `JwkKeyConverter` 指令註解改成與 javadoc / `JwtService` 一致(`PropertiesLauncher` + `-Dloader.main`)。Port、TTL、`*E2ETest` 三項見 D-21 ~ D-23。
- **D-04:** `it-shared-containers`(market-data IT 共用 Testcontainers,方案 B)併入本 phase;todo 檔列的風險(共用 DB 被清表、共用 Kafka 殘留訊息)必須處理。

### 金額字串化的邊界與切換
- **D-05:** **全 API 一致**:所有 API DTO / Request 的 `BigDecimal` 欄位一律序列化為 JSON 字串。範圍包括 trading 的全部 DTO / Request(交易、股利、股數調整、持倉、summary、allocation、現金)、backtest(`BacktestResultDto`、`BacktestRunDto`、`CreateBacktestRunRequest`)與 `AssetDto`,和已是字串的行情(`LatestPriceDto`、`KlineDto`、`PriceTickEvent` 用 `ToStringSerializer`)對齊。— **Reversibility:** one-way — 改變已發布的 HTTP 契約(`trading-portfolio-contract.md`、前端 `apiTypes`、mock adapter、E2E 輔助函式都依賴欄位型別)。
- **D-06:** **判斷依據是 Java 型別,不是語意**:後端是 `BigDecimal` 的一律字串,包括 roi、weight、allocation 百分比、回測指標等比率欄位;`double` / `int` / `long` 欄位維持數字。沒有例外清單。
- **D-07:** **硬切**:不做過渡旗標,也不做雙格式並存;前後端以同名分支、同一組 PR 一起合併。請求端靠 Jackson 把 JSON 字串轉成 `BigDecimal`,`@DecimalMin` / `@Digits` 照常生效。— **Reversibility:** costly — 前後端必須同步部署,單邊回滾會壞。
- **D-08:** **前端涉及金額的計算一律用 decimal**:service 層負責字串 ↔ decimal 轉換(已裁決);domain 型別直接持有 decimal 物件,元件內的估算、小計、權重也用 decimal,只有格式化顯示(`moneyFormat`、圖表輸入、CSV 輸出)才轉成字串或 number。decimal 套件的選型與授權確認在 research 階段做。
- **D-09:** **自動測試守門 + 契約文件**:後端加一個測試,掃描 API DTO 的 `BigDecimal` 欄位,確認都序列化成字串(ArchUnit 或序列化反射測試,由 planner 選);前端 `apiTypes` 的這些欄位型別改為 `string`。`ai-docs/trading-portfolio-contract.md` 寫明「BigDecimal 欄位一律字串」;`CashEntryDto` 現有的「不加 ToStringSerializer」註解要改掉。

### SC3 / SC4:串連 journey
- **D-10:** **新寫 `vue-app/e2e/tests/contract-journey.spec.ts`**:單一 test 從登入走到登出,以 `test.step` 分段(登入 → `/me` → portfolio reads → 建立手動交易 → 重讀 → 登出),標 `@smoke`。既有 `auth.spec.ts` A1–A11、`trade.spec.ts` T1–T3 保留,繼續負責邊界情境。
- **D-11:** **SC4 斷言嵌在同一條 journey**:登入後用 `context.cookies()` 斷言 auth cookie 為 `httpOnly`;建立交易時確認 `X-XSRF-TOKEN` 與 `XSRF-TOKEN` cookie 相符;登出後直接打 `GET /api/v1/me`,確認回 401 失敗信封。
- **D-12:** **全程監聽 `/api/v1` 請求**:以 `page.on('request')` 收集整條 journey 的 API 請求,結束時一次斷言:(a) 每個受保護請求都帶 auth cookie;(b) 每個 unsafe 請求(POST / PATCH / PUT / DELETE)都帶正確的 `X-XSRF-TOKEN`;(c) 沒有任何請求帶 `Authorization: Bearer`,也沒有 token 出現在 body 或 URL。
- **D-13:** **portfolio reads = Overview + Positions + Trades**:交易前讀 Overview KPI 與 Positions;下單後確認三頁都重讀到新值(持股、成交列、summary 變化)。Cash 已由 `cash-journey.spec.ts` 覆蓋,不重複。

### CI
- **D-14:** **`run-e2e.sh` 沒帶參數時跑全部**(拿掉預設的 `--grep @smoke`);`npm run test:e2e` 同步改掉 `--grep @smoke`。本機想快速跑可以自己帶 `--grep @smoke`。後端 CI 的 step 名稱「Run Browser E2E (@smoke)」與註解一併更新。
- **D-15:** **兩邊 CI 都跑真後端 browser E2E**:前端 `.github/workflows/ci.yml` 新增 browser-e2e job,checkout 後端同名分支(不存在時用 `develop`),重用 `run-e2e.sh`(仿照後端 CI 的 Browser E2E job,只是方向相反)。若後端 repo 是 private,需要 Yuan 在前端 repo 設一個唯讀 secret(fine-grained PAT 或 deploy key);planner 要把這一步列成 Yuan 的人工步驟(checkpoint),不能假設已存在。（已被 D-15 補充推翻:後端 repo 為 public,用預設 `github.token` 即可,不設 PAT / deploy key checkpoint）

### SC5:API mode 失敗狀態
- **D-16:** **用 `page.route` 注入失敗,重試時放行**:在真後端 E2E 中對目標端點先注入失敗,斷言錯誤與重試 UI;接著改用 `route.continue()` 放行到真後端,按重試後應看到後端真值。不採用「真的停掉後端」。
- **D-17:** **覆蓋所有接真 API 的頁面**:Overview、Positions、Trades、Cash、Watchlist、Settings,每頁選一個主要讀取端點。
- **D-18:** **三種失敗都要**:(a) 網路中斷(`route.abort()`,fetch reject);(b) 5xx 後端失敗信封(500 / 503 的 `ApiResponse`,帶 `meta.traceId`);(c) 非信封回應(代理回 502 HTML 或空白 body,確認 `apiClient` 不會解析崩潰)。
- **D-19:** **全矩陣 18 組**(3 種失敗 × 6 頁),表格驅動生成(Yuan 選擇多於建議的分層方案)。
- **D-20:** **「不被 mock 掩蓋」的斷言**:錯誤區塊、`error.code` / `traceId` 診斷列、重試按鈕都可見;**並且**頁面上不得出現 mock 資料的特徵值(mock KPI 數字、mock 持股代號等哨兵);放行重試後顯示後端真值。

### 設定對齊
- **D-21:** **Vite 代理 port**:`vite.config.ts` 的 proxy target(dev 與 preview、`/api` 與 WS)改讀環境變數(名稱由 planner 定,例如 `VITE_API_PROXY_TARGET`),預設 `http://localhost:11180`,和後端 `application.yaml` 預設一致;`run-e2e.sh` / Playwright 設成 8080,對應 `e2e-browser` profile。前端 getting-started 文件同步更新,本機 dev 不再需要 `SERVER_PORT=8080`。
- **D-22:** **access token TTL 兩者都對齊 `PT15M`**:`STOCK_JWT_ACCESS_TOKEN_TTL` 預設由 `PT30M` 改為 `PT15M`(`application.yaml` 與 `application-demo.yaml`,含檔頭註解),和 cookie 的 `PT15M` 一致,外洩的 cookie 最多有效 15 分鐘;bearer 客戶端也變成 15 分鐘(已有 refresh)。`browser-auth-contract.md` 寫明兩者必須相等,並由啟動檢查或測試守住(方式由 planner 選)。
- **D-23:** **7 個 `*E2ETest` 改名為 `*E2E.java`**(`Ws*E2ETest` ×5、`MarketRestApiE2ETest`、`BackfillAdminFlowE2ETest`):移出 unit job,由 `-Pe2e` 的 E2E job 執行;另加一個命名守門(ArchUnit 或等效測試),擋住 `start/e2e` 套件內出現 `*Test` 結尾的類別。改名後要確認 CI 的 E2E job 實際跑到這 7 個,而且是綠的。

### Plan 順序與 PR 切法
- **D-24:** **先建安全網,再做金額字串化**:
  - **Wave 1(安全網)**:SC1 / SC2 稽核補缺、contract journey(D-10 ~ D-13)、SC5 矩陣(D-16 ~ D-20)、CI 調整(D-14、D-15)。
  - **Wave 2(契約硬切)**:金額字串化(D-05 ~ D-09),由 Wave 1 的測試證明流程沒壞。Wave 2 會改到 `journeyApi.ts` 等 E2E 輔助函式的請求格式,這是預期內的變更。
  - 小型修補(D-02)、文件與設定一致性(D-03、D-21 ~ D-23)、`it-shared-containers`(D-04)可以和兩個 wave 平行,由 planner 安排。
- **D-25:** **每個 wave 一組 PR**:Wave 1 先開一組前後端同名分支 PR,合進 `develop`、在 develop 上證明是綠的;之後才開 Wave 2 那組。平行的小項目由 planner 決定歸到哪一組。

### Claude's Discretion
- SC1 / SC2 稽核表的格式與存放位置(建議放 phase 目錄,如 `05-COVERAGE-AUDIT.md`,逐條列 SC → 測試檔 / 方法 → 已涵蓋 / 缺口 → 補上的測試)。
- decimal 套件選型(research 比較 bundle size、API、授權、TS 型別)。
- BigDecimal 字串化的後端實作方式(逐欄位 `@JsonSerialize(using = ToStringSerializer.class)`,或全域 Jackson 設定只套用在 API DTO);以 D-09 的守門測試能驗證為準。
- SC5 的 mock 哨兵值怎麼選、怎麼集中管理。
- 環境變數名稱(D-21)、TTL 守門方式(D-22)、命名守門方式(D-23)。
- 平行小項目要歸到 Wave 1 或 Wave 2 的哪一組 PR。

### Deferred Ideas (OUT OF SCOPE)
- **DB 計數**(分頁 `count(*)`、觀察清單 100 檔上限 count)— Yuan 2026-11-07 裁決維持現狀,不排工作。

#### Reviewed Todos (not folded)
- `2026-09-28-account-level-return.md` — 帳戶層級報酬率:新 milestone 的第一個 phase(Yuan 2026-11-07 第 1 題)。
- `2026-09-28-fx-pnl-and-rate-history.md` — 匯率歷史:新 milestone「真實行情與參考資料來源」主題。
- `2026-09-28-kline-adjusted-prices.md` — K 線還原:同上主題。
- `2026-09-28-asset-master-management.md` — 資產主檔:同上主題。
- `2026-07-19-backend-asset-classification.md` — 剩 Analytics 產業分布,隨 Analytics API 化處理。
- `2026-11-07-account-and-security-hardening.md` — 帳號與安全強化:backlog。
- `2026-11-07-ops-and-ai-access-backend.md` — Ops / AI Access 後端:backlog。
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| VER-01 | Backend Maven tests 覆蓋 auth cookie、CSRF、CORS、refresh/logout、401/403 envelope、trading idempotency。 | §Q8 SC1 覆蓋矩陣(已涵蓋 / 缺口 6 條);§Q2 BigDecimal 守門測試;§Q5 命名守門;§Q6 TTL 守門 |
| VER-02 | Frontend Vitest/type-check/build 覆蓋 API client、auth store、runtime mode、portfolio adapters、trading adapter。 | §Q8 SC2 覆蓋矩陣(缺口 4 條,含 fetch reject / 5xx 空 body);§Q1 decimal 轉換層測試;§Q7 viteConfig 測試 |
| VER-03 | Cross-repo browser smoke flow 可驗證 login -> `/me` -> portfolio reads -> create manual trade -> refetch -> logout。 | §Q9 SC5 矩陣端點 / testid 表;§Pattern 2 contract-journey 骨架;§Q4 前端 CI browser-e2e job;§Q7 run-e2e / Playwright |
</phase_requirements>

## Project Constraints (from CLAUDE.md / AGENTS.md)

前端(`/home/user/stock-web-v2-front-end/AGENTS.md`)鐵律 —— 與後端 CONTEXT 決策同等權威:
1. **Transport 唯一邊界是 `vue-app/src/services/apiClient.ts`**:credentials、CSRF header、信封解析、401/403 全在這;domain service 與元件禁止自己 `fetch`。→ SC5 的「網路中斷」正規化若要做,只能做在 `apiClient.ts`。
2. **Token 不落 JS 可讀處**:只在 HttpOnly cookie。→ SC4 以 `context.cookies()` 的 `httpOnly` 與 `document.cookie` 不含 `stock_access` 雙重證明。
3. **mock 是明確模式,不是 fallback**;元件不得 import mock store。→ SC5 的 mock 哨兵斷言正是驗證這條。
4. **信封權威是後端 `ApiResponse<T>`**(`meta.traceId`)。
5. **交易語義**:`POST /api/v1/trades` 是已成交手動紀錄,不是委託單。
6. **可見的控制不得靜默 no-op**。
- 驗證命令(宣稱完成前必跑):`cd vue-app && npm run lint && npm test && npm run build`;涉及 API mode 加跑 `VITE_DATA_MODE=api npm test && npm run build`;動到版面 / CSS 加跑 `npx playwright test e2e/tests/layout.spec.ts`。
- Commit:Conventional Commits、繁中 subject,GSD scope 用 plan id(`feat(05-03): ...`);PR 開向 `develop`。
- 踩坑一行寫進前端 `docs/LEARNINGS.md`。

後端(`/home/user/stock-web-v2/CLAUDE.md` + `AGENTS.md`):
- Java 21 / Spring Boot 4.0.4,不引入新框架;DTO 優先 `record`;錯誤訊息與 `fields` 必須是靜態字串,不得回射使用者輸入;所有 handler 回 `ApiResponse`;L2 模組間只經 Facade(ArchUnit 守護);public 類別 Javadoc 帶 `@author Yuan` / `@version`;註解繁中。
- 測試後綴決定執行者:`*Test` → surefire、`*IT` → failsafe、`*E2E` → `-Pe2e`(`ai-docs/testing-standards.md:32-37`)。
- Spotless 綁 `validate`:每個 Maven build 都會 check import 順序(`dowob,jakarta,org,com,java,\#`)。
- AGENTS.md 列出的 `.claude/skills/*` 在此 cloud checkout **不存在**(`ls /home/user/stock-web-v2/.claude/skills` → No such file),本研究未載入任何 project skill。
- GSD Workflow Enforcement:檔案變更須經 GSD 指令(本 phase 走 `/gsd-execute-phase`)。

## Summary

本 phase 有三條主線,研究結論各自明確:

**(1) 驗證安全網(Wave 1)**。後端 SC1 大部分已涵蓋(`BrowserAuthFlowIT` 11 條、`CorsIT` 3 條、`TradingApiIT` 冪等 7 條、`TransactionsIdempotencyIT` 8 條、CSRF 403 於 6 個 IT),實測缺口集中在 6 處:cookie 屬性(SameSite / Path / Max-Age)未斷言、`BrowserAuthCookieService` 無單元測試、cookie 模式登出後舊 access cookie 打 `/me` 未測(只測了 bearer)、`POST /trades` 的 cookie 無 CSRF → 403 未測、「已帶有效 access cookie 時 login 需要 CSRF」未測(D-03 要寫進契約)、method-security 403 只斷言 status 不斷言信封。前端 SC2 缺口:`apiClient` 對 `fetch` reject(TypeError)完全沒有測試也沒有處理,5xx + 空 JSON body 未測。真後端 E2E 方面,現有 130 條 Playwright(121 `@smoke` + 9 `@extended`),新 journey + 18 組 SC5 後約 149 條。

**(2) 金額字串化(Wave 2)**。實測發現一個 CONTEXT 沒有預見、但會直接決定實作方式的陷阱:**`ToStringSerializer` 會輸出科學記號** —— `BigDecimal.ZERO.setScale(8)` 序列化成 `"0E-8"`、`0.00000001` 成 `"1E-8"`(本 session 以 Jackson 3.1.0 實測)。後端 summary 的 `cash` / `dividendIncome` 初值就是 `ZERO.setScale(MONEY_SCALE)`(`PortfolioQueryService.java:581,642-643`),所以這不是邊角案例。建議:在 `stock-infrastructure` 新增一個以 `toPlainString()` 輸出的 Jackson 3 serializer 與 `@DecimalString` meta-annotation,逐欄位標註(含已用 `ToStringSerializer` 的 `LatestPriceDto` / `KlineDto`),**不要**用全域 Jackson 設定(理由:模組的 `@WebMvcTest` slice 拿不到 stock-start 的 customizer,測試與正式環境會分歧)。前端選 **big.js 7.0.1 + @types/big.js 7.0.0**:3.0 KB gzip(decimal.js 12.9 KB)、加減乘**完全精確**(decimal.js 預設 20 位有效數字會把 `123456789012.12345678 × 1000000.12345678` 截成 `…097.36`)、`Big.strict = true` 可在執行期禁止以 JS number 建構,正好守住「service 層才做字串 ↔ decimal」。顯示端可直接把字串餵給 `Intl.NumberFormat.format()`(實測精確保留 20 位有效數字,number 路徑會變 `…012.12346`)。

**(3) 一致性修補與 CI**。後端 repo 已確認為 **public**(`gh api repos/tommot20077/stock-web-v2` → `"private":false`),D-15 不需要 PAT / deploy key 的人工 checkpoint。TTL 守門若做成啟動檢查,**`application-test.yaml:28` 與 `application-e2e.yaml:4` 目前設 `PT30M`,會讓所有 IT / E2E 啟動失敗**,必須同 PR 改成 `PT15M`。共用容器(D-04)的 Kafka 殘留訊息風險是真的:`MarketDataIngestServiceIT.pollOne` 從 earliest 取第一筆並斷言 key = `"42"`,`PriceWriterRetryIT` 會往同一個 `TOPIC_PRICE_TICK` 寫 key `"1"`;另外 5 個類別各自 `admin.createTopics` 同名 topic,共用 broker 後第二次會丟 `TopicExistsException`。

**Primary recommendation:** Wave 1 先補 6+4 個測試缺口、新增 contract-journey 與 18 組 SC5、前端 CI 加真後端 job(不需 secret);Wave 2 以 `@DecimalString`(`toPlainString`)+ ArchUnit 欄位規則 + 序列化行為測試硬切後端,前端以 big.js(strict)建立單一 `decimal.ts` 與 wire→domain mapper。

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| BigDecimal → JSON 字串 | API / Backend(DTO 欄位 annotation) | — | 契約權威在後端(judgment §4);annotation 跟著 DTO 走,module slice 測試與正式環境一致 |
| JSON 字串 → BigDecimal(請求) | API / Backend(Jackson 預設 coercion + Bean Validation) | — | 已實測 Jackson 3 預設接受字串;`@DecimalMin`/`@Digits` 對轉換後的 `BigDecimal` 生效 |
| 字串 ↔ decimal 轉換 | Browser / Client — service 層(`services/*Api.ts` + 新 `decimal.ts`) | — | D-08 已裁決;元件只拿 domain 型別 |
| 金額顯示 / 圖表 / CSV | Browser / Client — `moneyFormat.ts`、chart 元件、`csvCell.ts` | — | D-08:唯一轉 string / number 的出口 |
| 網路錯誤正規化 | Browser / Client — `apiClient.ts` | 各頁 `describeError` | 鐵律 1:transport 邊界唯一在 apiClient |
| auth cookie / CSRF | API / Backend(`BrowserAuthCookieService`、`BrowserCsrfFilter`) | Browser(apiClient 送 header) | 安全控制必在伺服器端;前端只負責帶 header |
| TTL 一致性 | API / Backend 啟動守衛 | 設定檔 | 比照既有 `BrowserAuthCookieSecureGuard` 模式 |
| 觀察清單名稱空白約束 | Database / Storage(V20 CHECK) | API(`WatchlistService.normalizeName`) | DB 兜底,服務層既有 |
| proxy target | 前端 dev / preview server(Vite) | Playwright webServer env | 前端無 base-url,連線完全靠 proxy |
| 跨 repo browser E2E | CI(兩 repo 的 GitHub Actions) | `run-e2e.sh` | D-15 |

## Research Findings by Question

### Q1 (D-08) 前端 decimal 套件選型與轉換面

**套件比較**(本 session 以 esbuild 0.x `--bundle --minify` 打包最小 import + `gzip -9` 實測;npm metadata 以 `npm view` 取得):

| 套件 | 版本 | License | min / gzip | TS 型別 | 加減乘精確? | 捨入控制 | 最後發佈 |
|------|------|---------|-----------|---------|-------------|---------|---------|
| **big.js** | 7.0.1 | MIT | 6.9 KB / **3.0 KB** | `@types/big.js` 7.0.0(DefinitelyTyped) | **是**(只有 div/sqrt 受 `DP` 限制) | `Big.RM` 全域 + `toFixed(dp, rm)` / `round(dp, rm)` 逐次指定 | 2025-04 [VERIFIED: npm registry] |
| bignumber.js | 11.1.5 | MIT | 20.2 KB / 8.7 KB | 內建 `.d.ts` | 是(div 受 `DECIMAL_PLACES`) | `ROUNDING_MODE` + 逐次 | 2026-07 [VERIFIED: npm registry] |
| decimal.js-light | 2.5.1 | MIT | 13.8 KB / 6.0 KB | 內建 | **否**(`precision` 有效位數,預設 20) | 有 | 2020-09 [VERIFIED: npm registry] |
| decimal.js | 10.6.0 | MIT | 32.3 KB / 12.9 KB | 內建 | **否**(`precision` 預設 20 有效位數) | 有 | 2025-07 [VERIFIED: npm registry] |

實測證據(`node` 22 執行,本 session):
```
big mul exact: 123456804253701097.3561429365279684
decimal.js mul default precision 20: 123456804253701097.36
strict number ctor throws: [big.js] Invalid value
strict toNumber throws: [big.js] Imprecise conversion
toFixed(2, roundHalfUp=1): 2.35  halfEven=2: 2.34
big toString 1e-8: 1e-8 | toFixed(): 0.00000001 | 0E-8 parse: 0
Intl format string: 123,456,789,012.12345678
Intl format number: 123,456,789,012.12346
JSON precision: 123456789012.12346
```

**建議:big.js 7.0.1 + @types/big.js 7.0.0**。理由:
- 語意與 Java `BigDecimal` 最接近(加減乘不設精度上限),後端 `NUMERIC(24,8)` 的值域在 20+ 位有效數字,decimal.js 系預設 20 位會無聲截斷乘積。
- `Big.strict = true`:建構子拒收 JS number(`[big.js] Invalid value`),等於執行期守住 D-08「number 不得進入 decimal 世界」;`toNumber()` 在失真時丟錯,迫使圖表轉換寫成明確的 `Number(d.toFixed())`。
- 最小(3.0 KB gzip);同作者(MikeMcl)維護四個套件中最精簡者。
- 已以前端 `tsconfig.json` 等價設定(`moduleResolution: Bundler`、`strict`、`isolatedModules`)用 TS 6.0.3 型別檢查通過 `import Big from 'big.js'`、`Big()` clone、`toFixed(8, Big.roundHalfEven)`、`InstanceType<typeof D>`:`PROJECT_LIKE_TYPECHECK_OK` [VERIFIED: 本 session tsc 實測]。`@types/big.js` 是 `export = Big`;Bundler 解析下 `allowSyntheticDefaultImports` 預設開啟,故 default import 可用。

**big.js 必須知道的 3 個陷阱**:
1. `toString()` 在指數 ≤ -7 或 ≥ 21 時輸出科學記號(`1e-8`)。**送回後端一律用 `toFixed()`(無參數 = 完整精度、一般記號)**,不要用 `toString()` / `String(x)`。
2. `div` 的結果位數由 `Big.DP`(預設 20)決定;權重 / 比率計算要明確 `round(dp, rm)`。
3. `@types/big.js` 是 devDependency,`big.js` 是 dependency。

**轉換面清單(要改的前端檔)**:
- 型別:`src/services/apiTypes.ts`(84 個 `: number` 中屬 BigDecimal 者改 `string`:`PortfolioSummaryDto` 12 欄、`PortfolioActivityDto` 5 欄(`ratioNew`/`ratioOld` 是 Java `Integer`,**維持 number**)、`CashBalanceDto.amount`、`AllocationSliceDto.value/weight`、`SectorAllocationDto.value/weight/unrealizedPnl`、`HoldingDto` 14 欄、`TradeDto.quantity/price/fee`、`AssetDto.latestPrice/change/changePercent/high/low`、`BacktestRunRequest/BacktestRunDto.initialCapital`、`BacktestRunDto.progress`、`BacktestKpisDto` 8 欄(`drawdownDays`/`tradeCount` 是 int → number)、`EquityPointDto`、`MonthlyReturnDto.returnPct`、`DrawdownPointDto.drawdownPct`、`BacktestTradeDto.entry/exit/pnl/pnlPct`(`bars` int)) [CITED: `vue-app/src/services/apiTypes.ts` 與後端各 DTO,本 session 逐檔比對];`cashApi.ts:21,39,109`(`amount`)、`holdingEventApi.ts:16,18,33-34,53,70,167,169-170`(股利 / 股數調整);`HoldingDto.dayPnlBasis` 後端有、前端型別缺(未被消費)。
- Service(wire → domain mapping 放這):`portfolioApi.ts`、`tradingApi.ts`、`cashApi.ts`、`holdingEventApi.ts`、`marketApi.ts`(`AssetDto`)、`backtestApi.ts`、`watchlistApi.ts`(引用 AssetDto 報價)、`portfolioRevision.ts`。
- 計算點(grep `.<field> [-+*/<>]`,102 處候選,依檔):`pages/Positions.vue` 14、`pages/Analytics.vue` 5(mock-only 頁)、`pages/Overview.vue` 3、`cashBalanceView.ts` 2、`components/HoldingEventDialog.vue` 2、`StrategyEditor.vue` 2、`pages/Chart.vue` 2、`pages/Trades.vue` 2、`services/portfolioApi.ts` 2、`cashApi.ts` 1、`holdingEventApi.ts` 1、`pages/Markets.vue` 1。
- 表單輸入驗證以 `Number(text)` 判斷金額(`CashEntryDialog.vue:456` 附近、`OrderTicket.vue`、`HoldingEventDialog.vue`)→ 改 `new Big(text)` try/catch + 比較。
- 顯示出口:`moneyFormat.ts`(`fmtMoney`、`fmtSignedMoney`、`fmtMoneyAmount`、`fmtRatioPct`、`fmtWeightPct`、`fmtQuantity`、`fmtSignedQuantity`,全部參數型別目前是 `number | null | undefined`)、`components/LineChart.vue`、`Donut.vue`、`Bars.vue`、`csvCell.ts`(`Trades.vue` 匯出)。
- Mock:`stores/mockPortfolio.ts`、`src/data.ts`(`POSITIONS` / `TRADES` 是 number)、各 service 的 `createMock*Api`。**建議 mock adapter 產出 wire 形狀(字串),再走與 HTTP 同一個 mapper** —— 一條轉換路徑,兩種 mode 都被測到(呼應既有「mock / API 同一條錯誤路徑」慣例)。
- E2E:`e2e/support/journeyApi.ts:51-60`(`apiBuy` 送 `quantity`/`price` number、`fee: 0`)、`e2e/support/portfolioStubs.ts`(layout.spec 的回應 stub,數字欄位要改字串,否則 strict 模式在 mapper 丟錯)。
- 測試:22 個 Vitest 檔帶數字 fixture(`App.test.ts`、`cashBalanceView.test.ts`、`CashEntryDialog/HoldingEventDialog/OrderTicket.test.ts`、`useLiveQuotes.test.ts`、6 個 page test、9 個 service test、`task4.test.ts`)—— Wave 2 前端是全 phase 工作量最大的一塊,planner 應拆成 2–3 個 plan。

### Q2 (D-05/06/09) 後端 BigDecimal 欄位清單、實作方式、守門

**完整清單**(本 session 以 grep + 逐檔讀取 record 定義):

| 模組 / DTO | BigDecimal 欄位 |
|-----------|----------------|
| trading `PortfolioSummaryDto` | totalMarketValue, totalCostBasis, realizedPnl, unrealizedPnl, totalPnl, roi, dayPricePnl, dayPricePnlRatio, cash, totalEquity, dividendIncome, dividendTaxWithheld |
| trading `HoldingDto` | totalQuantity, avgCost, costBasis, marketPrice, marketValue, realizedPnl, unrealizedPnl, roi, marketValueInBase, unrealizedPnlInBase, previousClose, dayPricePnl, dayPricePnlRatio, dayPnlBasis |
| trading `TradeDto` | quantity, price, fee |
| trading `PortfolioActivityDto` | quantity, price, fee, amount, withholdingTax(`ratioNew`/`ratioOld` 為 `Integer`) |
| trading `CashEntryDto` / `CashBalanceDto` | amount / amount |
| trading `DividendDto` | amount, withholdingTax |
| trading `ShareAdjustmentDto` | quantityDelta |
| trading `AllocationSliceDto` / `SectorAllocationDto` | value, weight / value, weight, unrealizedPnl |
| trading Requests | `CreateTradeRequest`(quantity, price, fee)、`CreateCashEntryRequest`(amount)、`CreateDividendRequest`(amount, withholdingTax)、`CreateShareAdjustmentRequest`(quantity) |
| backtest | `BacktestRunDto`(initialCapital, progress)、`BacktestResultDto.BacktestKpisDto`(8 欄)、`EquityPointDto`(strategy, benchmark)、`MonthlyReturnDto`(returnPct)、`DrawdownPointDto`(drawdownPct)、`BacktestTradeDto`(entry, exit, pnl, pnlPct)、`CreateBacktestRunRequest`(initialCapital) |
| asset `AssetDto` | latestPrice, change, changePercent, high, low |
| market-data(已字串) | `LatestPriceDto`(price, volume)、`KlineDto`(open, high, low, close, volume)—皆 `ToStringSerializer` |
| common `PriceTickEvent` | price, volume —— **Kafka 事件,不是 HTTP DTO**;用的是 **Jackson 2** 的 `com.fasterxml.jackson.databind.annotation.JsonSerialize`,**不要動** |

API package 內沒有 `double`/`float` 欄位,也沒有 `List<BigDecimal>` / `Map<…, BigDecimal>`(grep 無結果)。

**實作方式比較與建議**

關鍵實測(Jackson databind 3.1.0,本 session):
```
0.00000000            ToStringSer={"v":"0E-8"}   ShapeSTRING={"v":"0E-8"}   ShapeSTRING+WRITE_BIGDECIMAL_AS_PLAIN={"v":"0.00000000"}
0.00000001            ToStringSer={"v":"1E-8"}   ...                         {"v":"0.00000001"}
123456789012.12345678 全部 = "123456789012.12345678"
```
後端產生 scale-8 零值的地方:`PortfolioQueryService.java:581`(`BigDecimal cash = BigDecimal.ZERO.setScale(MONEY_SCALE);`)、`:642-643`(`income`/`tax`)[CITED: grep -n 輸出]。契約檔 `ai-docs/trading-portfolio-contract.md:30` 也已經記錄「值為 0 且 scale 8 時 JSON 形式可能是 `0E-8`」。字串化後若沿用 `ToStringSerializer`,前端會收到 `"0E-8"` 字串 —— big.js 能解析(實測 `new Big('0E-8').toFixed()` → `0`),但任何 regex / `includes('.')` 類的字串邏輯、CSV 原樣輸出都會出錯。

| 方案 | 優點 | 缺點 |
|------|------|------|
| A. 逐欄位 `@JsonSerialize(using = ToStringSerializer.class)` | 與現有行情 DTO 寫法一致 | **輸出 `0E-8` / `1E-8`**;約 90 個欄位重複長 annotation |
| B. 全域 `JsonMapperBuilderCustomizer`(config override `BigDecimal` → `Shape.STRING` + `WRITE_BIGDECIMAL_AS_PLAIN`) | 一處設定、未來新 DTO 自動涵蓋 | 定義在 stock-start 時,**模組的 `@WebMvcTest` slice(如 `PortfolioActivityControllerWebMvcTest`)拿不到** → slice 測試仍是 number、正式是 string,測試與正式分歧;另外也會改到 `PortfolioCache`(Redis)、`MarketLatestService` 等所有走 Boot mapper 的 JSON;「只套用在 API DTO」在 Jackson 沒有自然的界線 |
| **C. 共用 `PlainDecimalStringSerializer`(`gen.writeString(value.toPlainString())`)+ `@DecimalString` meta-annotation(`@JacksonAnnotationsInside @JsonSerialize(using=…)`),逐欄位標註** | 一般記號、保留 scale(`"100.00000000"`);annotation 跟 DTO 走 → slice 測試、Redis 快取、正式 HTTP 全部一致;null 維持 `null`;短 | 要標約 90 欄位(機械性);需要守門防漏標 |

**建議方案 C**。已在本 session 以 Jackson 3.1.0 實測 meta-annotation 在 record component 上可用:
```
{"amount":"0.00000000","nullable":null,"count":3}
Req[amount=123456789012.12345678]
```
注意:`@DecimalString` 的 `@Target` 不能用在 type-use(`List<@DecimalString BigDecimal>` 編譯失敗,實測);目前沒有這種欄位,不影響。

**放哪個模組**:serializer 必須是 **Jackson 3**(`tools.jackson.databind.ser.std.StdSerializer`)。`stock-common` 目前只有 Jackson 2(`com.fasterxml.jackson.core:jackson-databind`,給 Kafka 事件用);`stock-infrastructure` 目前沒有任何 Jackson 依賴;所有 API 模組(asset / backtest / trading / market-data / user)都經 `spring-boot-starter-webmvc` 帶 Jackson 3。建議放 `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/web/json/`,並在 `stock-infrastructure/pom.xml` 加 `tools.jackson.core:jackson-databind`(版本由 Boot BOM 管理;本機 `.m2` 為 3.1.0)[CITED: 各模組 pom.xml artifactId 清單,本 session grep]。`jackson-annotations` 仍是 `com.fasterxml.jackson.annotation`(2.21),`@JacksonAnnotationsInside` 從這裡 import。

**請求端**:Jackson 3 預設把 JSON 字串轉 `BigDecimal`(實測 `{"v":"123456789012.12345678"}` → 精確)、也接受 JSON number(也精確,因 Jackson 以文字解析 BigDecimal)。邊界:
- `""` → `null`(實測)→ 必填欄位由 `@NotNull` 擋成 `VALIDATION_FAILED`;選填欄位(`fee`、`withholdingTax`)會被當成「未提供」套預設 → 前端選填欄位**送 `null`,不要送 `""`**。
- `"abc"` → `InvalidFormatException` → 既有 `GlobalExceptionHandler` 的 `HttpMessageNotReadableException` handler 回 400 `VALIDATION_FAILED`、靜態訊息、無 `fields`(`GlobalExceptionHandler.java:144-156` 註解明載不帶 Jackson 訊息,避免回射輸入)[CITED]。
- `" 1.5 "` → `1.5`(前後空白被容忍,實測);`"1e3"` → `1E+3`,`@Digits(integer=13, fraction=8)` 以 precision-scale 計算整數位,仍正確。
- **D-07「不做雙格式並存」的解讀**:後端請求端「同時接受 string 與 number」是 Jackson 預設、且不損精度;要真的拒收 number 需自訂 deserializer。建議**不拒收**(前端 `apiTypes` 型別 + big.js strict 已在編譯期與執行期守住),列為 Open Question 讓 Yuan 確認（RESOLVED:CONTEXT「Claude's Discretion」採 Jackson 預設,接受 JSON number）。

**冪等 `*PayloadMatcher` 影響:無**。`TradePayloadMatcher.sameAmount` 用 `compareTo`(`10` 等於 `10.00000000`),`DividendPayloadMatcher` / `ShareAdjustmentPayloadMatcher` / `CashEntryPayloadMatcher` 沿用;比對發生在 Jackson 已把字串轉成 `BigDecimal` 之後 [CITED: `TradePayloadMatcher.java` `sameAmount` Javadoc「絕不可改用 equals」]。

**`PortfolioCache`(Redis)影響**:`PortfolioCache` 以注入的 Boot `ObjectMapper` 序列化 `HoldingDto` / `PortfolioSummaryDto` 存 Redis(TTL 60 秒,鍵 `portfolio:valuation:v3:` / `portfolio:summary:v3:`)。方案 C 下快取 JSON 的金額會變字串;新舊程式互讀都可(Jackson 預設 string↔BigDecimal coercion,已實測)。依該類別 Javadoc 的既有慣例(JSON 形狀變更就換版本段),**建議兩個前綴同步升為 `v4`**,成本一行、部署瞬間零疑慮 [CITED: `PortfolioCache.java` 類別 Javadoc 版本沿革]。

**Kafka / 事件不受影響**:`application.yaml:26` 的 `value-serializer: org.springframework.kafka.support.serializer.JsonSerializer` 使用 spring-kafka 自己的 mapper;`PriceTickEvent` 標的是 Jackson 2 annotation;`WsBroadcastConsumer.java:188-210` 送 WS 前已手動 `toPlainString()`。方案 C 只碰 HTTP DTO,不會誤改事件格式。

**守門測試(D-09)建議 = ArchUnit 欄位規則 + 一個序列化行為測試**,兩者都放 `stock-start/src/test/java/dowob/xyz/stockwebv2/start/`(該模組測試 classpath 涵蓋全部模組,`ArchitectureRulesTest` 已這樣用):
1. ArchUnit:`fields().that().haveRawType(BigDecimal.class).and().areDeclaredInClassesThat(<位於 dowob.xyz.stockwebv2..api.. 且 simpleName 以 Dto 或 Request 結尾,含巢狀 record 如 BacktestResultDto$BacktestKpisDto>).should().beAnnotatedWith(DecimalString.class)`。record component 上的 annotation 若 `@Target` 含 `FIELD` 會傳播到 private field,ArchUnit 讀 bytecode 看得到(RUNTIME retention)。用 `.allowEmptyShould(false)` 防止 predicate 打錯而空轉綠燈。[ASSUMED: ArchUnit 1.3.0 對 record 欄位 annotation 的可見性 —— 需在 Wave 2 第一個 task 以紅燈測試證明]
2. 行為測試(注入 Boot 的 `JsonMapper`,`@SpringBootTest` 不需要,`@JsonTest` 或直接 `JsonMapper.builder().build()` 即可,因為 annotation 不依賴全域設定):建 `CashEntryDto(amount = BigDecimal.ZERO.setScale(8))`、`PortfolioSummaryDto` 等樣本,斷言 `"0.00000000"` 是字串、非科學記號,並反序列化 `Create*Request` 的字串金額。
3. 精度回歸 IT(Specific Ideas 原例):`CashEntryApiIT` 新增 POST `{"amount":"123456789012.12345678"}` → GET 列表回 `"123456789012.12345678"`。

**既有後端測試的破壞面**:`jsonPath(...).value(<number>)` 對金額欄位 24 處、7 個檔(`MarketControllerTest`、`PortfolioActivityControllerWebMvcTest`、`PortfolioActivityApiIT`、`AssetApiIT`、`CashEntryApiIT`、`ValidationBoundaryE2E`、`CashLedgerE2E`);以 `JsonNode.decimalValue()` 讀金額的 9 個檔(`PortfolioDayPnlIT`、`PortfolioDividendSummaryIT`、`PortfolioCashAllocationIT`、`PortfolioActivityApiIT`、`ShareAdjustmentE2E`、`PortfolioDayPnlE2E`、`PortfolioBaseCurrencyE2E`、`DividendE2E`、`PortfolioActivityE2E`)。**好消息**:Jackson 3 對 TextNode 呼叫 `decimalValue()` 會丟 `JsonNodeException`(實測),測試會大聲失敗而不是靜默讀成 0;機械修法是改 `asDecimal()`(實測 `"12.5"` → `12.5`)。另有 118 處以文字 JSON 送 number 的請求 body —— 因請求端仍接受 number,**不必全改**,但精度與字串路徑至少要有專門案例。

### Q3 (D-04) market-data 共用 Testcontainers

**現況**(本 session 逐檔 grep):market-data 有 11 個 `*IT`,各自 `@Testcontainers` + `@Container`(per-class 生命週期):

| 容器 | 使用的 IT(啟動次數) |
|------|-------------------|
| Kafka `cp-kafka:7.6.0` | KafkaConfigIT、PriceWriterRetryIT、MarketDataHealthIndicatorIT、MarketDataIngestServiceIT、BackfillJobIT(5) |
| TimescaleDB `2.17.2-pg16` | MarketPriceRepositoryIT、PriceWriterConsumerIT、MarketControllerKlinesIT、MarketDataMigrationIT、BackfillJobIT(5) |
| Redis `7.4-alpine` | BackfillIdempotencyServiceIT、WsTicketServiceIT、BackfillJobIT(3) |

`stock-start` 的 `ContainerIT` 已是 singleton 模式範本:`static final` 容器 + `static { Startables.deepStart(postgres, redis, kafka).join(); }`、**不加** `@Container`/`@Testcontainers`(那兩個會在每個類別結束時停容器),由 Ryuk 在 JVM 結束時回收 [CITED: `ContainerIT.java`]。failsafe 預設 `forkCount=1, reuseForks=true`,同模組的 IT 共用同一 JVM,singleton 才會生效。

**建議做法**:`stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/support/MarketDataContainers.java`,用 **initialization-on-demand holder**(`KafkaHolder` / `PostgresHolder` / `RedisHolder` 三個 nested static class),只用 Redis 的類別不會連帶啟動 Kafka。

**風險與處置(todo 列的兩條 + 本研究新發現兩條)**:

| 風險 | 證據 | 處置 |
|------|------|------|
| 共用 DB 被清表 | `MarketPriceRepositoryIT:91`、`PriceWriterConsumerIT:85` 都 `DELETE FROM market_prices`;Klines IT 斷言筆數 | **每個類別獨立 database**:helper `freshDatabase(String name)` 以 admin 連線 `CREATE DATABASE md_<class>`,回傳該 DB 的 JDBC URL,再跑既有的 `Flyway.configure()…migrate()`。V1 / V4 migration 自帶 `CREATE EXTENSION`(grep 證實),timescale image 已 preload 擴充,新 DB 可直接建 hypertable。`MarketDataMigrationIT` 本來就需要乾淨 DB,正好吻合 |
| 共用 Kafka 殘留訊息 | `MarketDataIngestServiceIT` 用隨機 group + `earliest`,`pollOne` 取**第一筆**並斷言 `rec.key() == "42"`;`PriceWriterRetryIT:203` 往同 topic 寫 key `"1"` | 斷言改成「poll 到符合預期 key / 唯一標記的那一筆」(過濾),測試資料用每次唯一的 assetId(如 `ThreadLocalRandom` 9xxxxx);consumer group 一律 `"<class>-" + UUID` |
| **(新)同名 topic 重複建立** | KafkaConfigIT:38-46、PriceWriterRetryIT:92-96、MarketDataIngestServiceIT:87-89 都 `admin.createTopics(TOPIC_PRICE_TICK…)`,共用 broker 後第二次會 `TopicExistsException` | helper `ensureTopics(admin, NewTopic…)`:吞掉 `TopicExistsException`;partition 數三處都是 3,所以 `KafkaConfigIT` 斷言 `hasSize(3)` 仍成立 —— 但要在 helper 註明「新增 topic 時 partition 數必須一致」 |
| **(新)BackfillJobIT 的 Spring listener 讀到別人的 tick** | `BackfillJobIT:75` 設 `spring.kafka.consumer.auto-offset-reset=earliest`,其 context 的 `PriceWriterConsumer` 會消費殘留 tick 寫進自己的 DB;它斷言 `findRange(1L, from, to).hasSize(1440)`(:189, :216);`PriceWriterRetryIT` 的 key 是 `"1"` | 給 BackfillJobIT 獨立 database(上一條已涵蓋)+ 其他 IT 改用不會撞 assetId 1 的測試 id;或 **保守方案:BackfillJobIT 保留自己的 Kafka**(仍省下 4 次 Kafka 啟動中的 3 次)。建議先做保守方案,CI 綠後再評估（已被 D-29 推翻:BackfillJobIT 一併共用 Kafka,以唯一 topic / consumer group 隔離）|

另:failsafe 預設 `runOrder=filesystem`,順序相依的問題可能潛伏;驗證時至少本機 / CI 各跑一次 `-Dfailsafe.runOrder=random`(或 `reversealphabetical`)以暴露殘留依賴 [ASSUMED: failsafe 參數名稱沿用 surefire 的 `runOrder`]。

### Q4 (D-15) 前端 CI browser-e2e job

- **後端 repo 是 public**:`gh api repos/tommot20077/stock-web-v2 --jq '{private, visibility, default_branch}'` → `{"default_branch":"develop","private":false,"visibility":"public"}`;前端同為 public [VERIFIED: GitHub REST API,本 session]。→ **不需要 PAT / deploy key**;`actions/checkout@v4` 帶 `repository:` 跨 repo 讀 public repo 用預設 `github.token` 即可(後端 CI 反向讀前端已是此做法,`ci.yml` 註解「前端為 public repo…無需 PAT / deploy key」)。建議 planner 仍在 plan 中寫一行「若後端改 private,需加 secret」作為風險註記,但**不必列 checkpoint**。
- 後端 CI 的同名分支解析(可直接鏡像):
  ```bash
  CANDIDATE="${{ github.head_ref || github.ref_name }}"
  git ls-remote --exit-code --heads "$REPO" "$CANDIDATE"  # 有 → 用;無 → develop
  ```
- 前端 CI 觸發只有 `push: [develop]` 與 `pull_request: [develop]`(後端是全分支 push)。新 job 建議 `needs: frontend`、`timeout-minutes: 30`、`permissions: contents: read`、加 `concurrency`。
- 步驟:checkout 前端到 `frontend/`、解析後端 ref、checkout 後端到 `backend/`、setup-java 21(temurin、`cache: maven`)、setup-node 20(`cache-dependency-path: frontend/vue-app/package-lock.json`)、`npm ci`、`npx playwright install --with-deps chromium`、`E2E_BACKEND_DIR=$GITHUB_WORKSPACE/backend bash e2e/run-e2e.sh`、`if: always()` 上傳 `frontend/vue-app/e2e/artifacts/`。ubuntu-latest runner 內建 Docker + compose v2(後端 CI 已在用)。
- 耗時參考:後端 develop 最新 CI(run 42348849621)Browser E2E job 07:02:44 → 07:06:17 ≈ 3.6 分鐘(只跑 @smoke 121 條)[VERIFIED: GitHub REST API]。全跑多 9 條 @extended + 新增約 19 條,預估仍在 30 分鐘 timeout 內。
- **Wave 2 硬切的 CI 時序陷阱**:D-25 每 wave 一組 PR;Wave 2 若後端先合進 develop,前端 develop 的 push CI(checkout 後端 develop)會在前端 PR 合併前紅。兩邊 PR 要緊接著合併,合完各自 re-run develop CI;或先合前端(前端改成能吃字串前,舊後端仍送 number → 前端 strict mapper 會紅,所以順序反過來也會紅)。**結論:Wave 2 兩個 PR 必須在同一時間窗合併並接受 develop CI 短暫紅一輪**,plan 要寫明。（plan-phase 已定案:05-25 `<merge_order>` 宣告「前端 PR 先、後端 PR 緊接」—— 過渡期是新前端對舊後端,strict mapper 大聲失敗(fail-closed),只有前端合併時那一次 develop run 預期為紅;兩邊合併後重跑）

### Q5 (D-23) 7 個 `*E2ETest` 與命名守門

- 7 個類別:`BackfillAdminFlowE2ETest`、`MarketRestApiE2ETest`(extends `AbstractStockE2ETest`)、`WsAuthFlowE2ETest`、`WsConnectionLimitE2ETest`、`WsSubscribeReceiveE2ETest`、`WsSubscriptionLimitE2ETest`、`WsTokenRevocationE2ETest`(extends `AbstractWsE2ETest`)[CITED: grep `^class` 輸出]。
- `stock-start/pom.xml:160-201`:預設 surefire `<exclude>**/*E2E.java</exclude>`;failsafe 預設 includes;`e2e` profile 下 surefire `<include>**/*E2E.java</include>` + `<excludes combine.self="override"/>` [VERIFIED: stock-start/pom.xml:160-201,Read]。CI E2E job 指令 `./mvnw -B -pl stock-start -am test -Pe2e`。
- 改名後(檔名 + 類別名,`git mv`)它們自動被 e2e profile include、被預設 surefire exclude;不需改 pom。
- **守門的關鍵細節**:`e2e/support/` 下的 `AbstractStockE2ETest`、`AbstractPortfolioE2ETest`、`AbstractWsE2ETest` **本身就以 `Test` 結尾**(abstract,surefire 不會執行抽象類別)。若照字面「擋住 `start/e2e` 套件內出現 `*Test` 結尾的類別」,這三個會紅。兩個選項:(a) 規則排除 abstract 類別(建議,零連帶修改);(b) 把三個 base 改名(例如 `AbstractStockE2ESupport`),連帶改 16+ 個 E2E 類別的 `extends`。（已被 D-27 推翻:採 (b) 改名三個 base,守門不豁免 abstract）
- 建議實作(ArchUnit 1.3.0 已在 classpath;下方範例的 `.doNotHaveModifier(JavaModifier.ABSTRACT)` 一行已被 D-27 推翻,實作時必須刪除):新檔 `stock-start/src/test/java/dowob/xyz/stockwebv2/start/E2eNamingRulesTest.java`(放在 `e2e` 套件**外**、以 `Test` 結尾 → 由 unit job 執行、不需容器):
  ```java
  @AnalyzeClasses(packages = "dowob.xyz.stockwebv2.start.e2e",
                  importOptions = ImportOption.OnlyIncludeTests.class)
  class E2eNamingRulesTest {
      @ArchTest
      static final ArchRule concreteE2eClassesMustNotEndWithTest = noClasses()
          .that().resideInAPackage("dowob.xyz.stockwebv2.start.e2e..")
          .and().areTopLevelClasses()
          .and().doNotHaveModifier(JavaModifier.ABSTRACT)
          .should().haveSimpleNameEndingWith("Test")
          .allowEmptyShould(false);
  }
  ```
  可再加一條正向規則:`e2e` 套件**根層**(不含 `support`)的具體類別必須 `haveSimpleNameEndingWith("E2E")`。[ASSUMED: `ImportOption.OnlyIncludeTests` 以路徑含 `/test-classes/` 判斷,Maven 預設成立]
- 驗收「CI 的 E2E job 實際跑到這 7 個」:E2E job 有 `dorny/test-reporter` 與上傳 `stock-start/target/surefire-reports/`;plan 的驗證步驟寫成「下載 `e2e-test-reports` artifact,確認存在 `TEST-dowob.xyz.stockwebv2.start.e2e.WsAuthFlowE2E.xml` 等 7 檔且 failures=0」,並確認 Unit job 的 report **不再**含它們。本 cloud 環境沒有 Docker daemon,本機無法跑。
- 文件同步:`docs/testing/overview.md:63-70`「已知例外」整段刪除 / 改寫。

### Q6 (D-22) access token TTL

- 設定位置:`application.yaml:75`(`access-token-ttl: ${STOCK_AUTH_COOKIE_ACCESS_TOKEN_TTL:PT15M}`)、`:85`(`access-token-ttl: ${STOCK_JWT_ACCESS_TOKEN_TTL:PT30M}`)[VERIFIED: application.yaml:66-86,Read];`application-demo.yaml:17`(檔頭註解「Defaults to PT30M」)與 `:70`;`application-e2e-browser.yaml` 兩者皆 `PT2H`(已相等);**`stock-start/src/test/resources/application-test.yaml:28` 與 `application-e2e.yaml:4` 為 `PT30M`**(cookie 未覆寫 → 取主檔 PT15M → 不相等)[CITED: grep 輸出]。
- 消費端:`JwtProperties`(`stock-infrastructure`,`accessTokenTtl`)、`BrowserAuthCookieProperties`(`stock-module-user`,null/非正值時預設 15 分鐘)、`AuthController.java:180` 以 **cookie TTL** 計算回應的 `expiresAt`、`BrowserAuthCookieService.addCsrfCookie` 的 XSRF cookie 也用 access TTL。
- **建議:啟動守衛**,完全比照既有 `BrowserAuthCookieSecureGuard`(`stock-module-user/.../service/`,`@Component`,建構子檢查、丟 `IllegalStateException`,有 3 條單元測試)。新類別 `AccessTokenTtlConsistencyGuard(BrowserAuthCookieProperties, JwtProperties)`:`!cookie.accessTokenTtl().equals(jwt.accessTokenTtl())` → 拒絕啟動,訊息指名兩個環境變數。`stock-module-user` 已依賴 `stock-infrastructure`,可讀 `JwtProperties`。優點:所有 profile(含部署環境以環境變數覆寫時)都被守住;純測試守門只能檢查 repo 內的 yaml。
- **必須同 PR 修改**:`application-test.yaml:28`、`application-e2e.yaml:4` 改 `PT15M`,否則守衛會讓所有 `*IT` / `*E2E` context 起不來。`LogoutInvalidatesAccessTokenIT` 等測試不依賴 30 分鐘長度(未見以 TTL 計時的斷言)[ASSUMED: 未逐一檢查所有 IT 對 TTL 的時間依賴]。

### Q7 (D-21 / D-14) Vite proxy、Playwright、run-e2e、scripts

- 現況:`vite.config.ts:7-13` `backendProxy = { '/api': 'http://localhost:8080', '/ws': { target: 'ws://localhost:8080', ws: true } }`,dev(5173)與 preview(4173)共用 [VERIFIED: vite.config.ts:1-25,Read];`playwright.config.ts:28-35` webServer `npm run build && npm run preview`、`reuseExistingServer: !process.env.CI`、`env: { VITE_DATA_MODE: 'api' }`;`run-e2e.sh:102-103` 無參數時 `set -- --grep @smoke`;`run-e2e.ps1:110-111` 同;`package.json` `"test:e2e": "playwright test --grep @smoke"` [CITED]。
- **建議實作**:維持 `defineConfig({...})` **物件形式**,在模組頂層讀 `process.env`:
  ```ts
  const proxyTarget = process.env.VITE_API_PROXY_TARGET ?? 'http://localhost:11180';
  const backendProxy = { '/api': proxyTarget, '/ws': { target: proxyTarget, ws: true } };
  ```
  理由:(1) `src/viteConfig.test.ts` 直接 `import config from '../vite.config'` 並把它當物件讀 `server.proxy` / `preview.proxy`,改成 `defineConfig(({ mode }) => …)` 函式形式會讓該測試壞掉;(2) http-proxy 對 `ws: true` 的 `http://` target 會自行升級 WebSocket,不需另一個 `ws://` 變數 [ASSUMED: http-proxy ws 升級行為,建議以 `watchlist-live.spec.ts`(WS 報價)在真後端 E2E 中驗證]。
  - 變數名:`VITE_API_PROXY_TARGET` 只在 `vite.config.ts`(Node 端)被讀,不會進 bundle(Vite 只替換 client 程式中**被引用**的 `import.meta.env.VITE_*`)。注意 `process.env` 只看 shell 環境,不讀 `.env` 檔;若希望 `.env.local` 也可設,需改用 `loadEnv` —— 但那需要函式形式,與上一點衝突。建議只支援 shell env,文件寫明。
  - `viteConfig.test.ts` 新增:預設為 `http://localhost:11180`;`vi.stubEnv('VITE_API_PROXY_TARGET', 'http://localhost:8080')` + `vi.resetModules()` + 動態 `import('../vite.config')` 時兩個 server 都改用 8080、`/ws.ws === true`。
- `playwright.config.ts` webServer `env` 加 `VITE_API_PROXY_TARGET: \`http://localhost:${process.env.E2E_BACKEND_PORT ?? '8080'}\``。`run-e2e.sh` / `.ps1` 已 export `E2E_BACKEND_PORT` 預設 8080,可沿用。
- **陷阱**:`reuseExistingServer: !process.env.CI` —— 本機若已有一個沒帶 env 的 `npm run preview`(現在會指向 11180)在 4173,Playwright 會直接重用,E2E 打到錯的後端。文件與 `run-e2e.sh` 開頭的 port 檢查應加上 4173 的檢查或在 README 註明。
- D-14:刪 `run-e2e.sh:102-104` 與 `run-e2e.ps1:110-112` 的預設分支(註解 `:3`、`:109` 一併改),`package.json` `test:e2e` 改 `playwright test`;後端 `ci.yml` step「Run Browser E2E (@smoke)」改名、註解「(預設只跑 @smoke)」改寫。
- 文件要同步的位置(grep `8080` / `SERVER_PORT=8080`):後端 `README.md:65`、`AGENTS.md:134-135`(由 `.planning/codebase/STACK.md` 產生,一併改來源)、`docs/guides/development.md:31`、`docs/guides/getting-started.md:93-106, 186`、`docs/configuration/overview.md:64-65`(e2e-browser 註記維持)、前端 `vue-app/e2e/README.md`、`vite.config.ts` 檔頭註解。**前端 repo 沒有 getting-started 文件**(`docs/` 只有 `LEARNINGS.md`、`api-contracts/`、`superpowers/`),D-21 說的「前端 getting-started」實際是後端 `docs/guides/getting-started.md`。

### Q8 (D-01) SC1 / SC2 覆蓋矩陣(草稿)

**SC1(後端 Maven)**

| SC1 項目 | 現有測試(檔 / 方法) | 狀態 |
|----------|---------------------|------|
| auth cookie 設定與 HttpOnly | `BrowserAuthFlowIT.registerSetsHttpOnlyAuthCookiesAndOmitsTokenBody`、`loginSetsHttpOnlyAuthCookiesAndOmitsTokenBody`(`assertAuthCookieHeaders` 只檢查 `name=` 與 `HttpOnly`) | 部分 |
| cookie 屬性 SameSite / Path / Max-Age / Secure | 無;`BrowserAuthCookieService` 無單元測試(只有 `BrowserAuthCookieSecureGuardTest`) | **缺口 G1**:`BrowserAuthCookieServiceTest`(Max-Age = access TTL 900、`SameSite=Lax`、`Path=/`、`secure` 依設定、`domain` 空白不輸出、XSRF cookie **非** HttpOnly) |
| cookie 認證 `/me` | `meAcceptsAccessCookieWithoutBearerHeader` | 已涵蓋 |
| CSRF bootstrap / 缺 header 403 信封 | `csrfBootstrapSetsReadableXsrfCookie`、`cookieLogoutWithoutCsrfHeaderReturnsCsrfErrorEnvelope`、`refreshWithoutCsrfHeaderReturnsCsrfErrorEnvelope`、`WsTicketCookieCsrfIT`、`CashEntryApiIT:357`、`DividendApiIT:125`、`ShareAdjustmentApiIT:173`、`UserPreferencesIT:139`、`WatchlistApiIT:292-295` | 已涵蓋 |
| `POST /trades` cookie 無 CSRF → 403 | `TradingApiIT` 全用 bearer | **缺口 G2** |
| 已帶有效 access cookie 時 login / register 需 CSRF(`BrowserCsrfFilter`:`cookieAuthenticated` 由 JWT filter 設定,permitAll 端點也適用) | `staleAccessCookieDoesNotBlockReLoginOnPublicEndpoint` 只測**失效** cookie 不擋登入 | **缺口 G3**(D-03 契約補述的證據) |
| CORS | `CorsIT` 3 條(preflight GET、unsafe + credentials headers、未知 origin 403) | 已涵蓋(實際非 preflight 回應的 `Access-Control-Allow-Credentials` 未測,選做) |
| refresh rotation / replay | `refreshWithMatchingCsrfRotatesAuthCookiesAndOmitsTokenBody`、`replayedRefresh…`、`invalidRefresh…`、`RefreshTokenRotationIT` 2 條 | 已涵蓋 |
| logout 後 access 失效 | `LogoutInvalidatesAccessTokenIT.accessTokenIsRejectedAfterLogout`(**bearer**) | 部分 → **缺口 G4**:cookie 模式登出後以舊 `stock_access` cookie 打 `GET /me` → 401 信封(對應 SC4 的後端面) |
| 401 信封 | `TradingApiIT.tradingEndpointsRequireAuthentication`(`AUTH_INVALID_CREDENTIALS`)、`AuthFlowIT.meRejectsMalformedBearerTokenWithApiResponse`、`logoutRequiresAuthentication` | 已涵蓋 |
| 403 信封 | CSRF 403(上列)有信封;`MethodSecurityDenialIT.methodLevelDenialReturnsForbidden` 只斷言 status | **缺口 G5**:補 `$.success=false`、`$.error.code`、`$.meta.traceId` |
| trading 冪等 | `TradingApiIT`:`concurrentSameKeyCreatesExactlyOneTrade`、`rejectedTradeDoesNotBurnTheIdempotencyKey`、`sameIdempotencyKeyReturnsExistingTradeAndAppliesHoldingOnce`、`sameKeyWithDifferentPayloadIsRejectedAsReuse`、`sameKeyWithOnlyNoteChangedReturnsExistingTrade`、`sameKeyAcrossDifferentUsersCreatesSeparateTrades`、`missing/blank/oversizedIdempotencyKey…`;`TransactionsIdempotencyIT` 8 條;`TradePayloadMatcherTest` 等 4 個 matcher 單元測試 | 已涵蓋(Wave 2 補一條「字串金額重送 `"10"` vs 既有 `10.00000000` → 同一筆」) |
| (Wave 2) BigDecimal 字串化 | — | **缺口 G6**:Q2 的 ArchUnit + 行為測試 + 精度 IT |

**SC2(前端 Vitest / type-check / build)**

| SC2 項目 | 現有測試 | 狀態 |
|----------|---------|------|
| API client | `services/apiClient.test.ts` 28 條(credentials、traceId 只取 `meta.traceId`、CSRF bootstrap / header / 403、refresh 單飛 / replay / 失敗、分頁信封、非 JSON 502、壞 JSON 200) | 部分 → **缺口 F1**:`fetch` reject(TypeError)目前**完全未處理也未測**(`apiClient.ts:305` 直接 `await fetch`,例外原樣上拋);**缺口 F2**:5xx + `Content-Type: application/json` + 空 body → 目前回 `INVALID_JSON_RESPONSE`(status 502),未測 |
| auth store(`services/authSession.ts`) | `authSession.test.ts` 7 條(checking→authenticated、401→anonymous、outage→error、refresh 回呼、不存 token、mock mode) | 已涵蓋 |
| runtime mode | `runtimeDataMode.test.ts` 3 條(預設 mock、接受 api/mock、無效值 typed error)、`api-adapter-wiring.test.ts` 17 條(API mode 不呼叫 mock factory) | 已涵蓋 |
| portfolio adapters | `portfolioApi.test.ts` 31、`cashApi.test.ts` 21、`holdingEventApi.test.ts` 28、`portfolioRevision.test.ts` | 已涵蓋(Wave 2 每個加 wire→domain mapper 與精度案例,**缺口 F3**) |
| trading adapter | `tradingApi.test.ts` 13(7 欄位 payload、Idempotency-Key 與 refresh replay 同 key、錯誤原樣上拋、mock oversell 同形錯誤) | 已涵蓋(Wave 2 payload 改字串) |
| type-check / build | `npm run build` = `vue-tsc --noEmit && vite build` | 已涵蓋 |
| proxy 設定 | `viteConfig.test.ts` 2 條 | **缺口 F4**(D-21:預設 11180 + env 覆寫) |
| mock 邊界 | `mockStoreBoundary.test.ts` 4 條 | 已涵蓋 |

稽核表建議存為 `.planning/phases/05-…/05-COVERAGE-AUDIT.md`,欄位:SC 項目 → 測試檔#方法 → 狀態 → 補上的測試(commit)→ 證據(CI run id)。

### Q9 (D-16 ~ D-20) SC5 六頁端點、testid、apiClient 失敗行為

**apiClient 對三種失敗的實際行為**(讀 `apiClient.ts`,本 session):
- (a) `route.abort()` → `fetch` reject `TypeError`:**apiClient 不攔**,原樣上拋。各頁處理不一致:`Overview.vue:506-508`、`Positions.vue:515-517`、`Trades.vue:455`、`Cash.vue:274` 的 `describeError` 對非 `ApiClientError` 回 `{ code: 'UNKNOWN_ERROR', traceId: null }`;`Watchlist.vue:375-377, 463-465` 與 `Settings.vue:350-352` 回 `NETWORK_ERROR`。不會崩潰,但 4 頁會顯示 `UNKNOWN_ERROR`。
- (b) 5xx `ApiResponse`:`readJson` 依 `Content-Type: application/json` 解析 → `errorFromResponse` → `ApiClientError{status, code, requestId = meta.traceId, fields}`。
- (c) 502 HTML(`text/html`):`readJson:146` 回 `null` → `HTTP_ERROR`、`requestId: null`(`:248`);空 body 且無 JSON content-type → 同上 `HTTP_ERROR`;空 body **但** `application/json` → `response.json()` 丟錯 → `INVALID_JSON_RESPONSE`(`:154`)。三者都是 `ApiClientError`,不會崩潰。

| 頁 | 主要讀取端點(建議注入目標) | 錯誤 / 重試 testid | 備註 |
|----|---------------------------|-------------------|------|
| Overview | `GET /api/v1/portfolio/summary`(`portfolioApi.ts:362`) | `overview-summary-error`、`-error-code`、`-trace-id`(僅 traceId 存在時渲染,`Overview.vue:48`)、`-retry` | Positions 也讀 summary,predicate 用 pathname 精確比對即可 |
| Positions | `GET /api/v1/portfolio/holdings`(`:363`) | `positions-holdings-error`、`-error-code`、`-trace-id`、`-retry` | |
| Trades | `GET /api/v1/portfolio/activities?…`(`:376`,分頁 → `apiPaginatedRequest`) | `trades-error`、`trades-error-code`、`trades-error-trace-id`、`trades-retry` | 有 query string,用 `url => new URL(url).pathname === '/api/v1/portfolio/activities'` |
| Cash | `GET /api/v1/portfolio/cash-entries?…`(`cashApi.ts:95`) | `cash-entries-error`、`-error-code`、`-trace-id`、`-retry` | 同路徑有 POST,predicate 加 `method === 'GET'` |
| Watchlist | `GET /api/v1/watchlists`(`watchlistApi.ts:47`) | `watchlist-error`、`watchlist-error-code`、`watchlist-trace-id`、`watchlist-retry` | **Overview 掛載時也會 `watchlist.list()`**(`Overview.vue:926`):先在 Overview 完成載入,再裝 route,再點 `nav-watchlist` |
| Settings | `GET /api/v1/me`(`profileApi.ts:53` `getPreferences`) | `settings-base-currency-error`、`-error-code`、`-trace-id`、`-retry` | **`/me` 也是 session bootstrap**(`authApi.ts:111`):必須等 `header-session-identity` 可見之後才裝 route,再點 nav 進 Settings;注入 5xx / abort 不會觸發 refresh(apiClient 只對 401 refresh) |

**SC5 對 (a) 網路中斷的建議**:在 `apiClient.ts` 把 `fetch` 的 reject 正規化為 `ApiClientError({ status: 0, code: 'NETWORK_ERROR', message: 'Network error' })`(`AbortError` 原樣 rethrow,`OrderTicket.vue:793` / `HoldingEventDialog.vue:868` 的搜尋用 `AbortController`)。這符合鐵律 1,讓 6 頁都顯示 `NETWORK_ERROR`。連帶:`baseCurrencyErrorKey.ts` 目前以「非 `ApiClientError`」判斷網路錯誤(`if (!(error instanceof ApiClientError)) return 'baseCurrencyErrNetwork'`),要改成同時接受 `code === 'NETWORK_ERROR'`;`OrderTicket` / `CashEntryDialog` / `HoldingEventDialog` / `WatchlistPicker` 的 `handleSubmitFailure` 走 `instanceof` 分支後 code 仍是 `NETWORK_ERROR`,行為不變;沒有任何既有測試斷言 `UNKNOWN_ERROR`(grep 為空)。**此為行為變更,列 Open Question**;不做的話,SC5 (a) 的 4 頁斷言 `UNKNOWN_ERROR`。（RESOLVED → D-26 採納:六頁一律 `NETWORK_ERROR`）

**mock 哨兵候選**(`src/data.ts:35-50`、`stores/mockPortfolio.ts:5-9`):持股 `NVDA`、`2330.TW`、`BTC`、`ETH`(名稱 `NVIDIA Corp.`、`台積電`、`Bitcoin`)、價格 `1142.83`、`67842.40`、`3482.18`、交易備註 `加碼`、`停利`、`季度配息`、`DCA`、mock 觀察清單 `核心持倉`、`AI 概念`、`Crypto`。
- **陷阱**:API mode 仍有合法的 Preview / 示範區塊(例:Overview 新聞卡「示範」,見 `layout.spec.ts` L16 名稱),且真後端 seed 的資產清單可能含 `NVDA`。因此**哨兵斷言要限定在目標區塊的 locator 內**(例如 `positions-table`、`overview-kpi`、`watchlist-page`),不能對整頁 `body` 做 `not.toContainText`。
- 集中管理:`e2e/support/mockSentinels.ts` 匯出 `{ symbols, prices, notes, watchlistNames }` 常數;另加一條 Vitest(在 `src/` 下)斷言這些值仍存在於 `src/data.ts` / mock store 的輸出,防止 mock 資料改了而哨兵空轉(呼應既有「守門要有自我驗證」慣例,如 `mockStoreBoundary.test.ts` 的「偵測規則本身有效」)。
- 「放行後顯示後端真值」:先用 `journeyApi.apiBuy` 種一筆決定性資料(例:`AAPL` 3 股 @ `123.45`),retry 後斷言目標區塊出現 `AAPL` / `3` / `123.45`(Overview 則斷言 KPI 區塊不再是錯誤態且無哨兵)。

### Q10 (D-02) 四條小修補的確切位置

**(a) `executedAt` 晚於現在補 `fields`** —— `TradingService.java:388-390`:
```java
if (requested.isAfter(now.plus(EXECUTED_AT_FUTURE_TOLERANCE))) {
    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "executedAt must not be in the future");
}
```
[VERIFIED: TradingService.java:384-395,Read] 改成比照 `CashLedgerService.java:338-339` 的 `throw new FieldValidationException("Invalid occurredAt", Map.of("occurredAt", "must not be in the future"));` [VERIFIED: CashLedgerService.java:334-341,Read] → `new FieldValidationException("Invalid executedAt", Map.of("executedAt", "must not be in the future"))`。連帶:`TradingServiceTest.java:391` `futureExecutedAtErrorKeepsNoFields` 要翻轉(改名並斷言 fields)、`:312` 檢查;`TradingApiIT.futureExecutedAtIsRejectedButBackfillIsAllowed`(:609)補 `$.error.fields.executedAt`;`ai-docs/trading-portfolio-contract.md:89`(「無 `error.fields`(既有形狀)」)與 `:595` 變更紀錄;方法 Javadoc `:375`、`:381`。前端 `OrderTicket` 已有 `ticket-field-error-executedAt` testid,確認 `fields.executedAt` 會綁到該欄。

**(b) 觀察清單名稱 DB CHECK** —— 現況 `V18__watchlists.sql:34` `CONSTRAINT ck_watchlists_name_not_blank CHECK (btrim(name) <> '')` [VERIFIED: V18__watchlists.sql:22-35,Read];最新 migration 是 `V19__assets_currency_check.sql` → **新檔 `V20__watchlists_name_blank_like_check.sql`**。服務層規則(`WatchlistService.normalizeName`):`strip()` 後所有 code point 皆 `Character.isWhitespace || Character.isSpaceChar` 即視為空白。對應字元集(Java `isWhitespace` ∪ `isSpaceChar`):`\t \n \u000B \f \r \u001C-\u001F`、空白、`\u00A0`、`\u1680`、`\u2000-\u200A`、`\u2028`、`\u2029`、`\u202F`、`\u205F`、`\u3000`。寫法建議用 `btrim(name, <字元集>) <> ''`(沿用原 CHECK 形式,不依賴 locale 的 `[[:space:]]`),E-string 內 `\uXXXX` 需資料庫為 UTF8 [ASSUMED: 部署 DB 編碼為 UTF8]。步驟:`ALTER TABLE watchlists DROP CONSTRAINT ck_watchlists_name_not_blank; ALTER TABLE watchlists ADD CONSTRAINT ck_watchlists_name_not_blank CHECK (…)`;檔頭比照 V19 寫「套用失敗時」的查詢(找出只含這類字元的既有列)。測試:`WatchlistApiIT` 以 `JdbcClient` 直接 insert `'\u00A0\u00A0'` 名稱 → 預期 `DataIntegrityViolationException`;一般名稱中間含 NBSP 仍可。同步更新 `WatchlistService.normalizeName` Javadoc(「DB 的 btrim CHECK 未同步擴充」那句)。

**(c) 紅漲綠跌與寫死色** —— token 現況 `styles.css:19-40`:`--success`(固定綠,紅漲區塊不覆寫)、`--up` / `--up-text` / `--dn` 在 `:root[data-up-color="red"]` 對調(`--dn: #15803d`)[VERIFIED: styles.css:19-40,Read]。
- Ops 失敗燈:`Ops.vue:31` `background: o.status === 'success' ? 'var(--success)' : 'var(--dn)'` [VERIFIED: Ops.vue:29-32,Read] → 紅漲模式下 `--dn` 是綠。建議新增 `--danger` / `--on-danger`(比照 `--success` / `--on-success`:淺 / 深主題各一值、`data-up-color="red"` 區塊**不得**覆寫),失敗 / 錯誤語意改用它。
- **範圍提醒**:用 `var(--dn)` 表達「錯誤」的不只 Ops —— `Header.vue:192,207`(session-chip danger)、`AuthPanel.vue:195-203`、`SessionBanner.vue:96-97`、`CashEntryDialog.vue:768,791`、`HoldingEventDialog.vue:1672,1681,1730,1746`、`OrderTicket.vue:1433,1442,1504,1510`、`Watchlist.vue:1054`(錯誤框 `rgba(220,38,38,0.10)` + `var(--dn)`)。D-02c 字面是「失敗 / 錯誤另立固定色」,建議 planner 把這些錯誤樣式一起改 `--danger`,並在 `contrastTokens.test.ts` 加一條源碼掃描:錯誤類選擇器(`.block-error`、`*-error*`、`.danger`)不得引用 `--dn`。對比度要量(`--danger` 淺色 `#dc2626` 白底約 4.83:1 —— 取自 `styles.css` 檔頭註解的 `--btn-dn-bg` 量測值)。
- 寫死的漲跌 rgba(grep):`.pill.buy/.sell` 於 `Overview.vue:1152-1153`、`Trades.vue:1044-1045`、`Backtest.vue:501-502`;`.kind-pill.buy/.sell` `Trades.vue:1056-1057`;`.side-btn.buy/.sell.active` `OrderTicket.vue:1465-1466`;`.depth.dn-bg/.up-bg` `Chart.vue:558-559`;`.metric-delta.good/.bad` `Analytics.vue:783-784`。改法:`background: color-mix(in oklch, var(--up) 12%, transparent)`(專案已用 `color-mix(in oklch, …)`,見 `AuthPanel.vue:195-197`、`SessionBanner.vue:96-97`)。`.pill.div`(`#a855f7` 紫)與 Notifications 的類別色不是漲跌語意,不改。
- 此項屬視覺 token 變更且 UI hint = yes:如 `workflow.ui_phase` 閘門要求 UI-SPEC,planner 應評估是否需要一份精簡 UI-SPEC(只涵蓋 `--danger` 值與受影響選擇器)。驗證加跑 `npx playwright test e2e/tests/layout.spec.ts`。

**(d) 「未來時間」改 `useNow`** —— 範本是 `HoldingEventDialog.vue:620-630`:`const now = useNow({ active: () => props.open }); function clockNow() { return Math.max(now.value, Date.now()); }`。
- `CashEntryDialog.vue`:`maxOccurredAt` 是 `ref('')`(:332),只在 `resetForm()` 設一次(:381);`occurredAtIssue()`(:465-467)是普通函式用 `Date.now()`。→ `maxOccurredAt` 改 `computed(() => toLocalInputValue(new Date(clockNow())))`,`occurredAtIssue` 改成依賴 `clockNow()` 的 computed;送出前自檢仍用 `Date.now()`(`useNow` Javadoc 明載)。
- `OrderTicket.vue`:`maxExecutedAt` `ref('')`(:635)於 `:1150` 設定;`executedAtIssue = computed(() => classifyInstantInput(executedAt.value, Date.now()))`(:993)—— computed 讀 `Date.now()` 不會隨時間重算,正是 IN-09 的問題。→ 同上改法;`active` 用 ticket 開啟狀態。
- 測試注意(前端 LEARNINGS 2026-11-07):有 interval 的元件不要 `vi.runAllTimers()`;假計時器下 `flushAsync` 只跑 microtask,送出路徑要 `flushAsync(16)`。

### Q11 (D-03) 文件 / 設定的確切行

| 檔 | 行 | 現況 | 改為 |
|----|----|------|------|
| `ai-docs/architecture.md` | 9、29、133、135 | 「Spring Data JDBC」「deliberate fit for Spring Data JDBC」「Aggregate Root Boundaries (Spring Data JDBC)」 | 手寫 `Jdbc*Repository` + `JdbcClient`(market-data 批次寫入用 `NamedParameterJdbcTemplate`);Aggregate 段標題同步。另:後端 `AGENTS.md` / `.planning/codebase/STACK.md`、`ARCHITECTURE` 摘要也寫「Spring Data JDBC(`spring-boot-starter-data-jdbc`)」,建議一併核對 |
| `ai-docs/browser-auth-contract.md` | 81-89(CSRF 節)、13-14(端點表 login / register 列) | 只寫「unsafe requests authenticated by browser cookies require CSRF」 | 補:瀏覽器已帶**有效** `stock_access` cookie 時,`/auth/login`、`/auth/register` 會被 `JwtAuthenticationFilter` 標成 cookie 認證,`BrowserCsrfFilter` 因此要求 `X-XSRF-TOKEN`;**失效** cookie 不擋(`staleAccessCookieDoesNotBlockReLoginOnPublicEndpoint`)。前端 apiClient 對所有 unsafe 請求本來就帶 CSRF,行為不變 |
| `ai-docs/browser-auth-contract.md` | 64-79(Auth Cookies)、182-191(Verification) | 無 TTL 相等規則 | 寫明 `stock.auth.cookie.access-token-ttl` 必須等於 `stock.jwt.access-token-ttl`(預設皆 `PT15M`),由啟動守衛強制;Verification 節補新測試名 |
| `stock-start/src/main/resources/application-demo.yaml` | 1-18 檔頭、17、70 | 未列 `STOCK_AUTH_COOKIE_SECURE`;`STOCK_JWT_ACCESS_TOKEN_TTL: … Defaults to PT30M` | 加 `# STOCK_AUTH_COOKIE_SECURE: Auth cookie Secure flag. Defaults to true (BrowserAuthCookieSecureGuard rejects false outside dev/test/e2e).`;TTL 改 `PT15M`(註解與 `:70` 預設值) |
| `stock-start/src/main/resources/application.yaml` | 81-83、85 | `java -cp <app.jar> dowob.xyz.stockwebv2.infrastructure.security.JwkKeyConverter private.pem public.pem`;`PT30M` | `java -cp <app.jar> -Dloader.main=dowob.xyz.stockwebv2.infrastructure.security.JwkKeyConverter org.springframework.boot.loader.launch.PropertiesLauncher private.pem public.pem`(與 `JwkKeyConverter.java:31-32` Javadoc、`JwtService.java:193-194` 錯誤訊息一致);`:85` 預設 `PT15M` [VERIFIED: application.yaml:79-86,Read] |
| `stock-start/src/test/resources/application-test.yaml` / `application-e2e.yaml` | 28 / 4 | `PT30M` | `PT15M`(D-22 守衛的前提) |
| `ai-docs/trading-portfolio-contract.md` | 22-32(序列化格式)、89、595+ | 「金額與數量 **JSON number**」「`0E-8`…以數值解析」「行情是字串、交易是數字」 | Wave 2:全部 BigDecimal 一律字串、一般記號(不再有 `0E-8`)、scale 保留(`"100.00000000"`);請求端「必須送字串」;刪除 M-7 的不一致說明 |
| `stock-module-trading/.../api/CashEntryDto.java` | 類別 Javadoc | 「`amount` 以 JSON number 輸出,不加 `ToStringSerializer`」 | Wave 2 改寫 |
| 前端 `docs/api-contracts/mock-to-real-contract.md` | Backtest 範例 `initialCapital: 100000`、`totalReturnPct: 42.5`… | number | Wave 2 改字串 |
| `docs/testing/overview.md` | 63-70 | `*E2ETest` 已知例外 | D-23 後刪除 |

### Q12 驗證架構 —— 見下方 `## Validation Architecture`

## Standard Stack

### Core(本 phase 新增)
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| big.js | 7.0.1 | 前端 decimal(字串 ↔ decimal、金額運算) | 3.0 KB gzip、加減乘精確、strict 模式禁止 number 建構;42.9M 週下載 [VERIFIED: npm registry + 本 session 實測] |
| @types/big.js(dev) | 7.0.0 | big.js 型別 | DefinitelyTyped;big.js README「TypeScript」節指名此套件 [CITED: node_modules/big.js/README.md:186-194] |
| tools.jackson.core:jackson-databind | Boot BOM 管理(本機 3.1.0) | `stock-infrastructure` 的 `PlainDecimalStringSerializer` | 已是所有 API 模組的傳遞依賴;只是讓 infrastructure 顯式依賴 [VERIFIED: ~/.m2 tools/jackson/core/jackson-databind/3.1.0] |

### Supporting(既有,沿用)
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| ArchUnit (`archunit-junit5`) | 1.3.0 | D-09 欄位規則、D-23 命名守門 | `stock-start` 測試 |
| Testcontainers | 1.21.4 BOM | D-04 singleton 容器 | market-data IT |
| @playwright/test | 1.61.1 | journey、SC5 | `vue-app/e2e/tests/` |
| Vitest | ^4.1.6 | SC2 缺口、decimal mapper | `vue-app/src/**/*.test.ts` |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| big.js | bignumber.js 11.1.5 | 內建型別、有 `toFormat` 千分位;8.7 KB gzip,API 較大。若 Yuan 不想多一個 `@types` 依賴可選它 |
| big.js | decimal.js 10.6.0 | 功能最全(三角函數等用不到);12.9 KB;**預設 20 位有效數字會截斷金額乘積**,必須 `Decimal.clone({ precision: 40 })` |
| `@DecimalString` 逐欄位 | 全域 `JsonMapperBuilderCustomizer` | 見 Q2:slice 測試分歧、波及 Redis 等非 API JSON |
| `@DecimalString`(plain) | 沿用 `ToStringSerializer` | 輸出 `0E-8` |

**Installation:**
```bash
cd /home/user/stock-web-v2-front-end/vue-app
npm install big.js@7.0.1
npm install -D @types/big.js@7.0.0
```
```xml
<!-- stock-infrastructure/pom.xml -->
<dependency>
    <groupId>tools.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
</dependency>
```

## Package Legitimacy Audit

`gsd-tools query package-legitimacy check --ecosystem npm big.js @types/big.js decimal.js bignumber.js decimal.js-light`(本 session 執行):

| Package | Registry | Age(最新版發佈) | Downloads | Source Repo | Verdict | Disposition |
|---------|----------|-----------------|-----------|-------------|---------|-------------|
| big.js | npm | 2025-04-21 | 42,859,581 / 週 | github.com/MikeMcl/big.js | [OK] | Approved(建議採用) |
| @types/big.js | npm | 2026-05-21 | 2,558,732 / 週 | github.com/DefinitelyTyped/DefinitelyTyped | [OK] | Approved(建議採用) |
| decimal.js | npm | 2025-07-06 | 137,090,557 / 週 | github.com/MikeMcl/decimal.js | [OK] | 未採用(比較用) |
| bignumber.js | npm | 2026-07-05 | 88,393,701 / 週 | github.com/MikeMcl/bignumber.js | [OK] | 未採用(備選) |
| decimal.js-light | npm | 2020-09-30 | 61,543,528 / 週 | github.com/MikeMcl/decimal.js-light | [OK] | 未採用 |

- postinstall:5 個套件 `postinstall: null`(seam 回報)。
- 套件名來源:big.js 的 TypeScript 指引來自其官方 README(本機安裝後讀取),不是 WebSearch。
- **Packages removed due to [SLOP] verdict:** none
- **Packages flagged as suspicious [SUS]:** none

## Architecture Patterns

### System Architecture Diagram

```
                    ┌───────────────────── Browser (Playwright / 使用者) ─────────────────────┐
  E2E journey ──►   │  page.on('request') 收集 ─► 結束時一次斷言 cookie / CSRF / 無 Bearer      │
  SC5 page.route ─► │  (fail mode: abort | 5xx envelope | 502 HTML/空 body) ─► continue()     │
                    │                                                                         │
                    │  Vue 元件 ──(domain 型別:Big)──► services/*Api.ts                        │
                    │        ▲                         │  wire DTO(string) ⇄ domain(Big)      │
                    │        │ moneyFormat / chart     │  mapper(mock adapter 同一條)          │
                    │        │ (Big→string/number)     ▼                                        │
                    │        └─────────────── apiClient.ts(唯一 transport)                     │
                    │                         credentials:'include', X-XSRF-TOKEN,              │
                    │                         信封解析, 401→refresh 一次, [建議] 網路錯誤正規化   │
                    └───────────────────────────────┬─────────────────────────────────────────┘
                                                    │ same-origin /api, /ws
                                   Vite dev(5173) / preview(4173) proxy
                                   target = $VITE_API_PROXY_TARGET ?? http://localhost:11180
                                   (Playwright / run-e2e → http://localhost:8080)
                                                    │
                    ┌───────────────────────────────▼─────────────────────────────────────────┐
                    │ Spring Boot (stock-start)                                               │
                    │  JwtAuthenticationFilter(Bearer | stock_access cookie)                  │
                    │    └► BrowserCsrfFilter(cookie 認證的 unsafe → 比對 XSRF cookie/header)  │
                    │  Controller ─► Service ─► Jdbc*Repository                               │
                    │  Request JSON "123.45" ──Jackson──► BigDecimal ──@DecimalMin/@Digits──►  │
                    │  DTO BigDecimal ──@DecimalString(toPlainString)──► JSON "123.45000000"   │
                    │  GlobalExceptionHandler ─► ApiResponse{success,data,error,meta.traceId} │
                    │  啟動守衛:CookieSecureGuard、[新] AccessTokenTtlConsistencyGuard          │
                    └─────────┬───────────────────┬───────────────────┬───────────────────────┘
                              ▼                   ▼                   ▼
                       PostgreSQL/Timescale    Redis(PortfolioCache   Kafka(PriceTickEvent,
                       (V20 CHECK)             v3→v4, 字串金額)        Jackson 2,不變)
```

### Recommended Project Structure(新增 / 變動檔)
```
stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/web/json/
├── DecimalString.java                 # meta-annotation
└── PlainDecimalStringSerializer.java  # toPlainString()
stock-start/src/test/java/dowob/xyz/stockwebv2/start/
├── ApiDecimalStringRulesTest.java     # ArchUnit 欄位規則 + 序列化行為(D-09)
└── E2eNamingRulesTest.java            # D-23 命名守門
stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/service/
└── AccessTokenTtlConsistencyGuard.java  # D-22
stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/support/
└── MarketDataContainers.java          # D-04 singleton + freshDatabase + ensureTopics
stock-db-migration/src/main/resources/db/migration/
└── V20__watchlists_name_blank_like_check.sql
vue-app/src/services/
├── decimal.ts                         # 設定好的 Big clone(strict)、parse/serialize/toChartNumber
└── (各 *Api.ts 內)wire→domain mapper
vue-app/e2e/tests/
├── contract-journey.spec.ts           # D-10~D-13
└── api-failure-states.spec.ts         # D-16~D-20(18 組)
vue-app/e2e/support/
└── mockSentinels.ts
```

### Pattern 1: 後端 `@DecimalString`(實測可用)
```java
// Source: 本 session Jackson 3.1.0 probe(輸出 {"amount":"0.00000000","nullable":null,"count":3})
package dowob.xyz.stockwebv2.infrastructure.web.json;

public final class PlainDecimalStringSerializer extends StdSerializer<BigDecimal> {
    public PlainDecimalStringSerializer() { super(BigDecimal.class); }
    @Override
    public void serialize(BigDecimal value, JsonGenerator gen, SerializationContext ctxt) {
        gen.writeString(value.toPlainString());   // 不用 toString():ZERO.setScale(8) → "0E-8"
    }
}

@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@JacksonAnnotationsInside                       // com.fasterxml.jackson.annotation
@JsonSerialize(using = PlainDecimalStringSerializer.class)   // tools.jackson.databind.annotation
public @interface DecimalString {}

// 使用:
public record CashEntryDto(String id, String type, String currency, @DecimalString BigDecimal amount, ...) {}
```
imports:`tools.jackson.core.JsonGenerator`、`tools.jackson.databind.SerializationContext`、`tools.jackson.databind.ser.std.StdSerializer`、`tools.jackson.databind.annotation.JsonSerialize`、`com.fasterxml.jackson.annotation.JacksonAnnotationsInside`(與 probe 相同)。

### Pattern 2: contract-journey 骨架(D-10 ~ D-13)
```ts
// Source: 既有 auth.spec.ts A11 / trade.spec.ts T1 寫法 + Playwright 1.61 型別(request.allHeaders 含 cookie)
import { test, expect, loginViaUi } from '../support/fixtures';

test('J0 登入 → /me → 讀持倉 → 記錄交易 → 重讀 → 登出(契約全程) @smoke', async ({ page, context, uniqueAccount }) => {
  const seen: Array<{ method: string; url: string; headers: Promise<Record<string, string>>; body: string | null; phase: 'anon' | 'authed' | 'loggedOut' }> = [];
  let phase: 'anon' | 'authed' | 'loggedOut' = 'anon';
  page.on('request', (req) => {
    if (!new URL(req.url()).pathname.startsWith('/api/v1/')) return;
    // headers() 不含 cookie;allHeaders() 才含(playwright-core types.d.ts:20268-20270)
    seen.push({ method: req.method(), url: req.url(), headers: req.allHeaders(), body: req.postData(), phase });
  });

  await test.step('登入', async () => { await page.goto('/'); await loginViaUi(page, uniqueAccount); /* ... */ phase = 'authed'; });
  await test.step('auth cookie 為 httpOnly 且 JS 讀不到', async () => {
    const cookies = await context.cookies();
    expect(cookies.find((c) => c.name === 'stock_access')?.httpOnly).toBe(true);
    expect(cookies.find((c) => c.name === 'stock_refresh')?.httpOnly).toBe(true);
    expect(await page.evaluate(() => document.cookie)).not.toContain('stock_access');
  });
  // ... /me、Overview + Positions、OrderTicket(ticket-price 填決定性價格)、waitForResponse 證明三頁重讀、登出
  await test.step('登出後 /me 401(jar 與舊 cookie 兩種)', async () => { /* page.request.get + out-of-band 帶舊 Cookie header(仿 A11) */ });

  // 一次斷言,列出違規 method + URL
  const resolved = await Promise.all(seen.map(async (r) => ({ ...r, headers: await r.headers })));
  const PUBLIC = ['/api/v1/csrf', '/api/v1/auth/login', '/api/v1/auth/register'];
  // (a) authed 階段的非 public 請求都帶 stock_access;(b) unsafe 帶 x-xsrf-token 且等於同請求 cookie 中的 XSRF-TOKEN;
  // (c) 無 authorization: Bearer;token 值不出現在 url / body
});
```
重點:
- 初始 `GET /me`(未登入、401)是預期的「沒帶 cookie 的受保護請求」—— 斷言 (a) 必須只看 `phase === 'authed'`。
- CSRF 比對用**同一請求** `cookie` header 解析出的 `XSRF-TOKEN`,不要用事後的 `context.cookies()`:`addCsrfCookie` 每次 `GET /csrf` 都產新亂數(`BrowserAuthCookieService.addCsrfCookie`)。
- login 也是 POST,apiClient 會先 bootstrap `/csrf` 再帶 header(`prepareRequestInit` 對所有 unsafe 方法)—— (b) 涵蓋 login。
- 登出後的 401:用 `page.request.get('/api/v1/me')`(jar 已清)**加上** out-of-band context 帶登出前擷取的 Cookie header(仿 A11)—— 後者才證明伺服器端撤銷(tokenVersion),前者只證明 cookie 被清。
- e2e-browser 是 http localhost,`secure` 預期為 `false`,`sameSite` 預期 `Lax`。

### Pattern 3: SC5 表格驅動(D-19)
```ts
// 18 組各自獨立 test 名稱,方便定位
const PAGES = [
  { key: 'overview', nav: null, match: (u: URL, m: string) => m === 'GET' && u.pathname === '/api/v1/portfolio/summary', prefix: 'overview-summary' },
  { key: 'positions', nav: 'nav-positions', match: (u, m) => m === 'GET' && u.pathname === '/api/v1/portfolio/holdings', prefix: 'positions-holdings' },
  // trades → trades-error / trades-error-code / trades-error-trace-id / trades-retry(命名不同,個別給 testid)
  // cash → /portfolio/cash-entries GET;watchlist → /watchlists(先等 Overview 載完再裝 route);settings → /me(先等 header-session-identity)
] as const;
const FAILURES = {
  abort: (r) => r.abort('failed'),
  envelope5xx: (r) => r.fulfill({ status: 503, contentType: 'application/json',
    body: JSON.stringify({ success: false, data: null, error: { code: 'SERVICE_UNAVAILABLE', message: 'x', fields: {} }, meta: { traceId: 'e2e-sc5-trace' } }) }),
  nonEnvelope502: (r) => r.fulfill({ status: 502, contentType: 'text/html', body: '<html>Bad Gateway</html>' }),
} as const;
for (const p of PAGES) for (const [kind, fail] of Object.entries(FAILURES)) {
  test(`S5 ${p.key} × ${kind}:錯誤 / 重試可見、無 mock 哨兵、放行後顯示後端真值 @extended`, async ({ page, uniqueAccount }) => {
    let failing = true;
    await page.route((url) => true, async (route) => {
      const req = route.request();
      if (failing && p.match(new URL(req.url()), req.method())) return fail(route);
      return route.continue();
    });
    // ...
  });
}
```
`error.code` 實值:5xx 信封列用注入的 code + `traceId` 列可見;502 HTML 列斷言 `HTTP_ERROR` 且 trace-id 隱藏;abort 列視 Open Question 1 決議為 `NETWORK_ERROR` 或 `UNKNOWN_ERROR`（D-26 已裁決:`NETWORK_ERROR`）。`ErrorCode` 的實際 503 code 名稱請 planner 從 `stock-common/.../ErrorCode.java` 取(本研究未讀該 enum,上例 `SERVICE_UNAVAILABLE` 為 [ASSUMED] 佔位;注入的 body 由測試自訂,不必是後端存在的 code)。

### Pattern 4: 前端 decimal 模組
```ts
// Source: big.js README(strict / DP / RM)+ 本 session tsc 實測
import Big from 'big.js';
const D = Big();
D.strict = true;            // new D(0.1) 丟錯;toNumber() 失真時丟錯
D.DP = 20;
D.RM = Big.roundHalfUp;     // 與後端 RoundingMode.HALF_UP 一致
export type Decimal = InstanceType<typeof D>;
export const parseDecimal = (wire: string): Decimal => new D(wire);
export const parseNullableDecimal = (wire: string | null): Decimal | null => (wire === null ? null : new D(wire));
export const toWire = (d: Decimal): string => d.toFixed();            // 不用 toString()(1e-8)
export const toChartNumber = (d: Decimal): number => Number(d.toFixed()); // 明確的有損出口
```
顯示:`new Intl.NumberFormat(locale, { minimumFractionDigits: dp, maximumFractionDigits: dp }).format(d.toFixed(dp) as unknown as number)` —— 規格允許 `format()` 收字串並保留精度(Intl.NumberFormat v3)[VERIFIED: Node 22 實測 `123,456,789,012.12345678`;瀏覽器支援 ASSUMED,見 Assumptions A2]。TS 的 `format()` 型別簽名是否已接受 `string` 要看 `lib` 版本(專案 `lib: ["ES2020", …]`),可能需要 `as unknown as number` 或擴充 lib 到 `ES2023` [ASSUMED]。

### Anti-Patterns to Avoid
- **`String(bigDecimal)` / `toString()` 送 wire**:兩邊都會出科學記號(Java `0E-8`、big.js `1e-8`)。
- **全域 Jackson 設定讓 module slice 測試與正式環境分歧**。
- **對整頁 body 做 mock 哨兵 `not.toContainText`**:API mode 合法的 Preview 區塊與真 seed 資料會誤判。
- **SC5 在 session bootstrap 前就攔 `/me`**:會把「Settings 載入失敗」測成「未登入」。
- **在 `@Container` 上做共用容器**:`@Testcontainers` 會在類別結束時停掉它。
- **TTL 守衛不改 test yaml**:全部 IT 起不來。
- **元件內 `Number(dto.amount)`**:違反 D-08,且 strict Big 不會攔到(那是在 Big 之外)。可在 ESLint 加 `no-restricted-syntax` 或靠 code review;planner 斟酌。

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| 前端十進位運算 | 自寫「乘 1e8 轉整數」 | big.js | 1e8 × 1e12 超過 2^53;捨入模式、負數、除法都會出錯 |
| 字串金額格式化 | 自寫千分位切割 | `Intl.NumberFormat.format(string)` | 已實測精確;locale 規則由 ICU 處理 |
| BigDecimal JSON 字串化 | 每個 Controller 手動轉 String DTO | Jackson serializer + meta-annotation | 單點、slice 測試一致 |
| 命名 / 欄位守門 | 讀檔名清單的 shell 腳本 | ArchUnit(已在 classpath) | 能區分 abstract、套件、巢狀類別 |
| 容器共用 | 自寫 docker run 腳本 | Testcontainers singleton + Ryuk | 自動清理、與既有 ContainerIT 一致 |
| 跨 repo checkout | 自寫 git clone + token | `actions/checkout@v4` `repository:` | 已在後端 CI 驗證可用 |
| Playwright 請求攔截 | 自架 proxy 注入故障 | `page.route` + `route.abort/fulfill/continue` | D-16 已裁決 |

**Key insight:** 這個 phase 的難點不在寫新功能,而在「格式邊界」—— 科學記號、session bootstrap 與頁面端點共用 `/me`、共用 broker 的殘留訊息、test profile 與守衛的互動。每一個都已在上面以實測或行號定位。

## Runtime State Inventory

> 本 phase 含改名(D-23)與 wire 格式遷移(D-05),逐類回答。

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | (1) Redis `portfolio:valuation:v3:*` / `portfolio:summary:v3:*`(`PortfolioCache`,TTL 60 秒)存 DTO JSON,金額將由 number 變 string。(2) PostgreSQL `watchlists.name` 既有列可能含「只有 NBSP」的名稱(V20 CHECK 會掃描既有列)。(3) DB 金額欄位是 `NUMERIC(24,8)`,**不受 wire 格式影響**。 | (1) 程式改:前綴升 `v4`(舊鍵 60 秒自然過期,不需資料遷移)。(2) V20 檔頭附查詢;若某環境有違規列,先修資料再套用(比照 V19)。(3) 無 |
| Live service config | Kafka topic `TOPIC_PRICE_TICK` 等:事件用 Jackson 2、`PriceTickEvent` 不改 → 無影響。GitHub Actions:前端 repo 新增 job,無 UI 端設定;後端 repo 為 public,不需 secret。 | None — 已以 `gh api` 確認 visibility;事件格式以程式碼確認未被觸及 |
| OS-registered state | None — 無 systemd / Task Scheduler 等;`run-e2e.sh` 為一次性行程 | None |
| Secrets / env vars | `STOCK_JWT_ACCESS_TOKEN_TTL` 預設值變更(部署若未顯式設定,行為由 30 → 15 分鐘);若某部署顯式設 `STOCK_JWT_ACCESS_TOKEN_TTL=PT30M` 而 cookie 未設,新守衛會**拒絕啟動**。新增前端 `VITE_API_PROXY_TARGET`(僅 dev / preview)。 | 部署環境檢查(人工):`STOCK_JWT_ACCESS_TOKEN_TTL` 與 `STOCK_AUTH_COOKIE_ACCESS_TOKEN_TTL` 是否成對;文件寫明 |
| Build artifacts | 改名的 7 個 E2E 類別在 `stock-start/target/test-classes/` 留下舊 `.class`(本機增量建置時舊名仍可能被 surefire 撿到)。 | 本機驗證前 `./mvnw clean`;CI 為乾淨 checkout 不受影響 |

## Common Pitfalls

### Pitfall 1: 科學記號滲入字串金額
**What goes wrong:** 前端收到 `"0E-8"`、送出 `"1e-8"`。
**Why it happens:** Java `BigDecimal.toString()` / Jackson `ToStringSerializer`、big.js `toString()` 都在小指數時用科學記號。
**How to avoid:** 後端 `toPlainString()`;前端 `toFixed()`。守門行為測試用 `BigDecimal.ZERO.setScale(8)` 當樣本。
**Warning signs:** 任何 fixture / snapshot 出現 `E-` 或 `e-`。

### Pitfall 2: TTL 守衛讓所有 IT 起不來
**What goes wrong:** `application-test.yaml:28`、`application-e2e.yaml:4` 是 `PT30M`,cookie 為主檔 `PT15M`。
**How to avoid:** 守衛與兩個 test yaml 同一個 commit。
**Warning signs:** IT 全部 `ApplicationContext failure`,訊息指向守衛。

### Pitfall 3: SC5 Settings / Watchlist 攔錯時機
**What goes wrong:** `/me` 被攔 → app 判定未登入;`/watchlists` 被 Overview 先消費。
**How to avoid:** 先完成登入與 Overview 載入,再裝 route,再 nav 到目標頁。

### Pitfall 4: journey 的 cookie 斷言用錯 API
**What goes wrong:** `request.headers()` 不含 cookie,斷言 (a) 全部失敗或被誤寫成跳過。
**How to avoid:** `await request.allHeaders()`(playwright-core types.d.ts:20268-20270 明載)。

### Pitfall 5: 共用 Kafka 的順序相依
**What goes wrong:** 本機綠、CI 紅(或反之),取決於類別執行順序。
**How to avoid:** 唯一 group id + 依 key 過濾 + `ensureTopics`;以亂序跑一次驗證;BackfillJobIT 先保留獨立 Kafka（已被 D-29 推翻:BackfillJobIT 一併共用 Kafka）。

### Pitfall 6: Wave 2 合併時序
**What goes wrong:** 前後端 develop 互相 checkout,單邊先合就紅。
**How to avoid:** 同時窗合併,合完各自 re-run develop CI;plan 寫成 checkpoint。（05-25 已宣告順序:前端 PR 先、後端 PR 緊接,並寫進兩個 PR 內文）

### Pitfall 7: `reuseExistingServer` 重用到錯 proxy 的 preview
**What goes wrong:** 本機已開的 `npm run preview`(預設 11180)被 Playwright 重用。
**How to avoid:** run-e2e 加 4173 佔用檢查或文件註明。

### Pitfall 8: 測試在 Node 22 綠、CI Node 20 紅
**What goes wrong:** 前端 LEARNINGS 2026-09-02 記錄 `flushAsync` 在 Node 20 需要更多 tick。本 cloud 環境 node 為 v22.22.0、nvm 沒有 20。
**How to avoid:** 以 CI(Node 20)為準;新測試避免依賴固定 microtask 輪數。

### Pitfall 9: 假的「錯誤色」只改 Ops
**What goes wrong:** 紅漲模式下 Ops 燈修好了,但 SessionBanner / 表單錯誤框仍是綠色。
**How to avoid:** 見 Q10(c) 範圍清單 + 源碼掃描測試。

## Code Examples

### 後端:啟動守衛(比照既有 `BrowserAuthCookieSecureGuard`)
```java
// Source: stock-module-user/.../service/BrowserAuthCookieSecureGuard.java 的模式
@Component
public class AccessTokenTtlConsistencyGuard {
    public AccessTokenTtlConsistencyGuard(BrowserAuthCookieProperties cookie, JwtProperties jwt) {
        if (!cookie.accessTokenTtl().equals(jwt.accessTokenTtl())) {
            throw new IllegalStateException(
                "STOCK_AUTH_COOKIE_ACCESS_TOKEN_TTL must equal STOCK_JWT_ACCESS_TOKEN_TTL");
        }
    }
}
```
(訊息為靜態字串,不帶實際值 —— 符合「不回射」慣例;是否帶值供除錯由 planner 決定。)

### 後端:executedAt 未來時間(D-02a)
```java
// Source: CashLedgerService.java:338-339 的既有形狀
if (requested.isAfter(now.plus(EXECUTED_AT_FUTURE_TOLERANCE))) {
    throw new FieldValidationException("Invalid executedAt",
        Map.of("executedAt", "must not be in the future"));
}
```

### 前端:apiClient 網路錯誤正規化(Open Question 1 已由 D-26 採納)
```ts
// Source: apiClient.ts:305 `const response = await fetch(path, init);` 的包裝建議
async function transportFetch(path: string, init: RequestInit): Promise<Response> {
  try {
    return await fetch(path, init);
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error;
    throw new ApiClientError({ status: 0, code: 'NETWORK_ERROR', message: 'Network error', requestId: null });
  }
}
```
三處 `fetch` 呼叫(`bootstrapCsrf`、`refreshSession`、`fetchWithSessionRecovery` 兩次)都要改用它。

### 前端 CI job(D-15)
```yaml
  browser-e2e:
    runs-on: ubuntu-latest
    needs: frontend
    timeout-minutes: 30
    permissions:
      contents: read
    steps:
      - uses: actions/checkout@v4
        with: { path: frontend }
      - id: be_ref
        run: |
          CANDIDATE="${{ github.head_ref || github.ref_name }}"
          BE_REPO="https://github.com/tommot20077/stock-web-v2.git"
          if git ls-remote --exit-code --heads "$BE_REPO" "$CANDIDATE" >/dev/null 2>&1; then
            echo "ref=$CANDIDATE" >> "$GITHUB_OUTPUT"
          else
            echo "ref=develop" >> "$GITHUB_OUTPUT"
          fi
      - uses: actions/checkout@v4
        with: { repository: tommot20077/stock-web-v2, ref: "${{ steps.be_ref.outputs.ref }}", path: backend }
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "21", cache: maven }
      - uses: actions/setup-node@v4
        with: { node-version: 20, cache: npm, cache-dependency-path: frontend/vue-app/package-lock.json }
      - run: npm ci
        working-directory: frontend/vue-app
      - run: npx playwright install --with-deps chromium
        working-directory: frontend/vue-app
      - run: bash e2e/run-e2e.sh
        working-directory: frontend/vue-app
        env: { E2E_BACKEND_DIR: "${{ github.workspace }}/backend" }
      - if: always()
        uses: actions/upload-artifact@v4
        with: { name: browser-e2e-artifacts, path: frontend/vue-app/e2e/artifacts/, retention-days: 7, if-no-files-found: ignore }
```
注意:現有前端 `frontend` job 有 `defaults.run.working-directory: vue-app`;新 job 因 checkout 到 `frontend/` 子目錄,不能沿用該 default。

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Jackson 2 `JsonNode.decimalValue()` 對非數字節點回預設值 | Jackson 3:`decimalValue()` 對 TextNode 丟 `JsonNodeException`;寬鬆轉換用 `asDecimal()` | Jackson 3.0 | 後端測試改字串後會大聲失敗(好事);機械修法 `asDecimal()` |
| `Intl.NumberFormat.format(number)` | `format(string)` 保留任意精度(NumberFormat v3) | 2023 前後各瀏覽器 | 顯示字串金額不必先轉 number |
| `com.fasterxml.jackson.databind.annotation.*` | Boot 4 / Jackson 3:`tools.jackson.databind.annotation.*`;`jackson-annotations` 仍是 `com.fasterxml.jackson.annotation` | Jackson 3.0 | 新 serializer 必須用 `tools.jackson`;`PriceTickEvent` 用的是 Jackson 2(Kafka),兩者不可混用 |

**Deprecated/outdated:**
- `ai-docs/trading-portfolio-contract.md:26-32` 的「金額為 JSON number」「`0E-8` 以數值解析」「行情字串 / 交易數字並存(M-7)」—— Wave 2 後全部作廢。
- `docs/testing/overview.md:63-70` 的 `*E2ETest` 已知例外 —— D-23 後作廢。

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | ArchUnit 1.3.0 能在 record 的 private field 上看到 `@DecimalString`(FIELD target 傳播) | Q2 守門 | 規則永遠紅或空轉;改用反射 `Class.getRecordComponents()` + `getAccessor()` 檢查即可(備案) |
| A2 | 目標瀏覽器支援 `Intl.NumberFormat.format(string)` 精確格式化(Chrome 106+ / Safari 15.4+ / Firefox 116+ 的記憶) | Pattern 4 | 舊瀏覽器會先轉 number 而失真;備案:big.js `toFixed(dp)` + 自寫千分位插入(只處理整數部分) |
| A3 | TS `lib: ES2020` 下 `Intl.NumberFormat.format` 型別不接受 `string`,需要轉型或升 lib | Pattern 4 | 只影響型別寫法 |
| A4 | http-proxy 對 `ws: true` + `http://` target 會正確升級 WebSocket | Q7 | WS 報價在 dev/preview 斷線;備案:`/ws` 用 `target.replace(/^http/, 'ws')` |
| A5 | failsafe 支援 `-Dfailsafe.runOrder=random` | Q3 | 只影響驗證手法 |
| A6 | 沒有 IT / E2E 依賴 access token 30 分鐘長度 | Q6 | 改 PT15M 後個別測試逾時;CI 會揭露 |
| A7 | 部署 DB 為 UTF8,E-string `\u00A0` 可用 | Q10(b) | V20 套用失敗;備案:`chr(160)` 等函式組字元集 |
| A8 | `ImportOption.OnlyIncludeTests` 以 `/test-classes/` 路徑辨識 | Q5 | 守門空轉;`allowEmptyShould(false)` 會讓它紅,不會靜默 |
| A9 | Pattern 3 範例中的 `SERVICE_UNAVAILABLE` 只是注入 body 的佔位 code | Q9 | 無(注入值由測試自訂);若要用後端真實 code 需讀 `ErrorCode.java` |
| A10 | 後端請求端不拒收 JSON number(維持 Jackson 預設)符合 D-07「不做雙格式並存」的本意 | Q2 | 若 Yuan 要嚴格拒收,需自訂 deserializer + 修改 118 處測試 body |

## Open Questions (RESOLVED)

> 2026-11-07 plan-phase:六題皆已定案。**以 CONTEXT.md 的裁決為準**;下列 Recommendation 保留作為研究紀錄,其中 Q2、Q5 的建議**已被 Yuan 推翻**,executor 不得採用。

1. **SC5 網路中斷:要不要在 `apiClient.ts` 正規化為 `NETWORK_ERROR`?** —— **RESOLVED → D-26(採納研究建議)**:`apiClient.ts` 統一轉 `NETWORK_ERROR`,六頁一致,`baseCurrencyErrorKey` 同步調整;SC5 abort 列斷言 `NETWORK_ERROR`(05-02)。
   - What we know:目前 Overview / Positions / Trades / Cash 對 fetch reject 顯示 `UNKNOWN_ERROR`,Watchlist / Settings 顯示 `NETWORK_ERROR`;無既有測試斷言 `UNKNOWN_ERROR`;`baseCurrencyErrorKey` 依賴「非 ApiClientError」判斷網路錯。
   - What's unclear:這是行為變更(雖小),是否屬本 phase 範圍。
   - Recommendation:採納(符合鐵律 1、SC5 斷言一致、使用者看到正確分類),排在 Wave 1 的 SC2 缺口 plan,連同 `baseCurrencyErrorKey` 調整與 F1 測試。若不採納,SC5 abort 列對 4 頁斷言 `UNKNOWN_ERROR`。
2. **D-23 守門與 `Abstract*E2ETest`** —— **RESOLVED → D-27(推翻研究建議)**:三個 `Abstract*E2ETest` 一併改名,守門規則對 `start/e2e` 套件內所有類別一律禁止 `*Test` 結尾,**不豁免 abstract**(規則不得含 `doNotHaveModifier(JavaModifier.ABSTRACT)`)。
   - Recommendation(已被 D-27 推翻,勿採用):規則排除 abstract 類別(零連帶修改);若 Yuan 偏好字面「一個 `*Test` 都不許」,則改名三個 base 類別。
3. **D-07 請求端是否拒收 JSON number**(見 A10)—— **RESOLVED → CONTEXT「Claude's Discretion」(採納研究建議)**:採 Jackson 預設(接受 JSON number),不另加拒收邏輯;契約寫「必須送字串」,前端以 wire 型別 + `Big.strict` 守住。
   - Recommendation:不拒收,契約寫「必須送字串」,前端以型別 + strict Big 守住。
4. **D-02c 錯誤色範圍**:只改 Ops 燈,還是所有以 `--dn` 表示「錯誤」的樣式?—— **RESOLVED → D-28(採納研究建議)**:所有失敗 / 錯誤樣式改用新的固定色 `--danger`,`--up` / `--dn` 只留給漲跌語意,寫死的 `rgba` 字面色改用會對調的 token。
   - Recommendation:全部改 `--danger`(D-02c 字面「失敗 / 錯誤另立固定色」),並加源碼掃描測試;需確認是否觸發 UI-SPEC 閘門。
5. **共用容器的 Kafka 範圍**:BackfillJobIT 是否一起共用?—— **RESOLVED → D-29(推翻研究建議)**:全部共用,**包括 `BackfillJobIT`**,不保留獨立 Kafka;以每個 IT 唯一的 topic 名稱與 consumer group 隔離,處理 `TopicExistsException`、`pollOne` 讀到殘留訊息、BackfillJobIT listener 從 earliest 讀的風險,並以 `-Dfailsafe.runOrder=random` 在 CI 證明無順序相依。
   - Recommendation(已被 D-29 推翻,勿採用):第一步保留 BackfillJobIT 自己的 Kafka,其餘 4 個共用;CI 亂序跑綠後再評估。
6. **CI 中 SC5 與 journey 的 tag** —— **RESOLVED(plans 採納研究建議,無需另立 D-XX)**:journey `J0` 標 `@smoke`(05-01,D-10 已鎖定);SC5 18 組標 `@extended`(05-02);D-14 之後兩 repo CI 不帶 `--grep` 全跑,tag 只影響本機快速迴圈。
   - Recommendation:journey `@smoke`(D-10)、SC5 18 組 `@extended`(D-14 後兩者在 CI 都會跑,tag 只影響本機快速迴圈)。

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| JDK | 後端 build / test | ✓ | OpenJDK 21.0.12.1 | — |
| Maven Wrapper | 後端 | ✓ | Maven 3.9.14 | — |
| Node.js | 前端 | ✓(版本不同) | v22.22.0(CI 用 20;nvm 無 20) | 以 CI Node 20 為準 |
| npm / npx | 前端 | ✓ | npx 10.9.4 | — |
| vitest / vue-tsc / playwright(node_modules) | 前端測試 | ✓ | Playwright 1.61.1;130 條可 `--list` | — |
| Docker daemon | Testcontainers IT、`-Pe2e`、`run-e2e.sh` | **✗**(client 29.9.1 存在,`/var/run/docker.sock` 不存在) | — | **以 CI 為唯一證據**(Integration / E2E / Browser E2E job) |
| `gh` CLI(REST) | CI 證據查詢、repo visibility | ✓(GraphQL 被擋,REST 可用) | — | — |
| GitHub Actions ubuntu-latest(Docker + compose v2) | D-15 | ✓(後端 CI 已在用) | — | — |

**Missing dependencies with no fallback:** 無(本機缺 Docker 有 CI 備案)。
**Missing dependencies with fallback:** Docker daemon → CI;Node 20 → CI。
**對 planner 的影響:** 凡是 IT / E2E / browser E2E 的驗收步驟,都要寫成「push 後以 CI job 結果與 artifact 為證據」(run id + 報告檔),不能寫成本機命令輸出。

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework(後端) | JUnit 5 + Spring Boot Test + Testcontainers 1.21.4 + ArchUnit 1.3.0;surefire(`*Test`)/ failsafe(`*IT`)/ `-Pe2e`(`*E2E`) |
| Framework(前端) | Vitest ^4.1.6(jsdom)+ vue-tsc + Playwright 1.61.1 |
| Config file | `stock-start/pom.xml:160-201`;`vue-app/vite.config.ts`(`test` 區塊);`vue-app/playwright.config.ts` |
| Quick run(後端,無容器) | `./mvnw -B -pl <module> -am test -Dtest='<Class>' -Dsurefire.failIfNoSpecifiedTests=false` |
| Quick run(前端) | `cd vue-app && npx vitest run <path>` |
| Full suite(後端) | `./mvnw -B test --fail-at-end`(Unit)→ `./mvnw -B -pl stock-start -am verify -Dspring-boot.repackage.skip=true`(IT,需 Docker)→ `./mvnw -B -pl stock-start -am test -Pe2e`(E2E,需 Docker);`./mvnw -B spotless:check` |
| Full suite(前端) | `cd vue-app && npm run lint && npm test && npm run build && VITE_DATA_MODE=api npm test && npm run build`;版面:`npx playwright test e2e/tests/layout.spec.ts`;真後端:`bash e2e/run-e2e.sh`(需 Docker + 後端 checkout) |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| VER-01 | cookie 屬性(G1) | unit | `./mvnw -pl stock-module-user -am test -Dtest=BrowserAuthCookieServiceTest -Dsurefire.failIfNoSpecifiedTests=false` | ❌ Wave 0 |
| VER-01 | trades cookie 無 CSRF 403(G2)、login 帶有效 cookie 需 CSRF(G3)、cookie 登出後 /me 401(G4) | IT | `./mvnw -B -pl stock-start -am verify -Dit.test='BrowserAuthFlowIT,TradingApiIT' -Dspring-boot.repackage.skip=true`(CI) | ✅ 檔存在,方法 ❌ |
| VER-01 | method-security 403 信封(G5) | IT | 同上 `-Dit.test=MethodSecurityDenialIT` | ✅ 檔,斷言 ❌ |
| VER-01 | BigDecimal 字串化守門(G6) | unit(ArchUnit + 行為) | `./mvnw -pl stock-start -am test -Dtest=ApiDecimalStringRulesTest -Dsurefire.failIfNoSpecifiedTests=false` | ❌ Wave 2 |
| VER-01 | 精度回歸 `123456789012.12345678` | IT | `-Dit.test=CashEntryApiIT`(CI) | ✅ 檔,案例 ❌ |
| VER-01 | TTL 守衛 | unit | `-Dtest=AccessTokenTtlConsistencyGuardTest`(stock-module-user) | ❌ |
| VER-01 | E2E 命名守門 | unit(ArchUnit) | `./mvnw -pl stock-start -am test -Dtest=E2eNamingRulesTest -Dsurefire.failIfNoSpecifiedTests=false` | ❌ |
| VER-01 | 7 個改名 E2E 在 E2E job 綠 | E2E | `./mvnw -B -pl stock-start -am test -Pe2e`(CI artifact `e2e-test-reports` 內 7 個 `TEST-*E2E.xml`) | ✅(改名) |
| VER-01 | executedAt 未來 → `fields.executedAt` | unit + IT | `-Dtest=TradingServiceTest`;`-Dit.test=TradingApiIT` | ✅ 需改 |
| VER-01 | V20 NBSP CHECK | IT | `-Dit.test=WatchlistApiIT` | ✅ 案例 ❌ |
| VER-01 | 共用容器後 market-data IT 全綠(亂序) | IT | `./mvnw -B -pl stock-module-market-data -am verify -Dfailsafe.runOrder=random`(CI / 有 Docker 處) | ✅ 需改 |
| VER-02 | fetch reject / 5xx 空 body(F1/F2) | unit | `npx vitest run src/services/apiClient.test.ts` | ✅ 案例 ❌ |
| VER-02 | proxy env(F4) | unit | `npx vitest run src/viteConfig.test.ts` | ✅ 案例 ❌ |
| VER-02 | decimal mapper / 精度(F3) | unit | `npx vitest run src/services/decimal.test.ts src/services/*Api.test.ts` | ❌ Wave 2 |
| VER-02 | mock 哨兵仍存在於 mock 資料 | unit | `npx vitest run src/mockSentinels.test.ts`(名稱由 planner 定) | ❌ |
| VER-02 | useNow(D-02d) | component | `npx vitest run src/components/CashEntryDialog.test.ts src/components/OrderTicket.test.ts` | ✅ 案例 ❌ |
| VER-02 | `--danger` token / 寫死色(D-02c) | unit(源碼掃描) | `npx vitest run src/contrastTokens.test.ts` + `npx playwright test e2e/tests/layout.spec.ts` | ✅ 案例 ❌ |
| VER-02 | type-check / build | build | `npm run build`(含 `vue-tsc --noEmit`) | ✅ |
| VER-03 | journey(SC3 + SC4) | browser E2E(真後端) | `bash e2e/run-e2e.sh e2e/tests/contract-journey.spec.ts`(CI:兩 repo 的 browser-e2e job) | ❌ Wave 1 |
| VER-03 | SC5 18 組 | browser E2E(真後端) | `bash e2e/run-e2e.sh e2e/tests/api-failure-states.spec.ts` | ❌ Wave 1 |
| VER-03 | 全部 Playwright(D-14) | browser E2E | `bash e2e/run-e2e.sh`(無參數 = 全部) | ✅(改腳本) |

### Sampling Rate
- **Per task commit:** 受影響模組的 quick run(後端 `-pl <module> -am test -Dtest=…`;前端 `npx vitest run <file>`)+ `./mvnw -B spotless:check`(後端)/ `npm run lint`(前端)。
- **Per wave merge:** 後端 `./mvnw -B test --fail-at-end`;前端完整閘門(mock + api 兩種 `npm test` + `npm run build`);push 後確認兩 repo CI 全綠(Unit / Integration / E2E / Browser E2E;前端 frontend + browser-e2e)。
- **Phase gate:** 兩 repo `develop` 上 CI 全綠(D-25:Wave 1 合併後先證明 develop 綠才開 Wave 2);`05-COVERAGE-AUDIT.md` 每列都有測試名與 CI run id;之後 `/gsd-verify-work`。

### Wave 0 Gaps
- [ ] `stock-module-user/src/test/java/dowob/xyz/stockwebv2/user/service/BrowserAuthCookieServiceTest.java` — G1
- [ ] `stock-module-user/src/test/java/dowob/xyz/stockwebv2/user/service/AccessTokenTtlConsistencyGuardTest.java` — D-22
- [ ] `stock-start/src/test/java/dowob/xyz/stockwebv2/start/E2eNamingRulesTest.java` — D-23
- [ ] `stock-start/src/test/java/dowob/xyz/stockwebv2/start/ApiDecimalStringRulesTest.java` — D-09(Wave 2 起頭先寫紅燈)
- [ ] `stock-module-market-data/src/test/java/dowob/xyz/stockwebv2/marketdata/support/MarketDataContainers.java` — D-04
- [ ] `vue-app/e2e/tests/contract-journey.spec.ts`、`vue-app/e2e/tests/api-failure-states.spec.ts`、`vue-app/e2e/support/mockSentinels.ts` — VER-03
- [ ] `vue-app/src/services/decimal.ts` + `decimal.test.ts` — D-08(Wave 2)
- [ ] Framework install:`npm install big.js@7.0.1 && npm install -D @types/big.js@7.0.0`(Wave 2)
- [ ] `.planning/phases/05-…/05-COVERAGE-AUDIT.md` — D-01 驗收證據

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | yes(間接) | 既有 JWT(ES256)+ refresh rotation;本 phase 只補測試(G4) |
| V3 Session Management | yes | HttpOnly / SameSite=Lax cookie(`BrowserAuthCookieService`)、TTL 對齊 PT15M + 啟動守衛、登出 tokenVersion 撤銷;SC4 browser 斷言 |
| V4 Access Control | yes | 401/403 信封測試(G5);擁有權 404 既有 |
| V5 Input Validation | yes | Bean Validation(`@DecimalMin`/`@DecimalMax`/`@Digits`)作用於字串轉換後的 `BigDecimal`;壞數字字串 → 400 靜態訊息不回射 |
| V6 Cryptography | no | 不涉及新加密;XSRF token 既有 `SecureRandom` 32 bytes |
| V13 API / Web Service | yes | 自訂 double-submit CSRF(`BrowserCsrfFilter`),包含「有效 cookie 下的 login / register」(G3 + 契約) |
| V14 Configuration | yes | TTL 一致性守衛、`STOCK_AUTH_COOKIE_SECURE` 文件化、proxy target 僅 dev / preview |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| CSRF 透過已登入瀏覽器發 unsafe 請求 | Tampering / Elevation | double-submit cookie + header;journey 斷言每個 unsafe 帶相符 header;G2 / G3 |
| Token 被 JS 讀取(XSS 竊取) | Information Disclosure | HttpOnly;journey 斷言 `document.cookie` 不含 `stock_access`、無 Bearer、token 不入 URL / body |
| 外洩 access cookie 長效可用 | Elevation | TTL 15 分鐘 + 守衛;登出撤銷(G4、journey out-of-band 401) |
| 金額精度被 JSON double 無聲改寫 | Tampering(資料完整性) | 字串序列化 + 前端 big.js strict;精度回歸測試 |
| 錯誤訊息回射使用者輸入(例如壞數字字串) | Information Disclosure | `GlobalExceptionHandler` 對 `HttpMessageNotReadableException` 用靜態訊息;新增的 `FieldValidationException` 用靜態值 `must not be in the future` |
| mock 資料在故障時掩蓋真實狀態 | Repudiation / 誤導 | SC5 哨兵斷言(限定區塊) |
| CI 跨 repo checkout 洩漏憑證 | Information Disclosure | 兩 repo 皆 public,使用預設 `github.token` 唯讀;job `permissions: contents: read` |

## Sources

### Primary (HIGH confidence)
- 本 session 程式碼實測:Jackson databind 3.1.0 probe(`ToStringSerializer` / `Shape.STRING` / `WRITE_BIGDECIMAL_AS_PLAIN` / meta-annotation / 字串反序列化 / `JsonNode.decimalValue()` vs `asDecimal()`)。
- 本 session big.js / decimal.js / bignumber.js / decimal.js-light 安裝 + esbuild 打包量測 + node 執行;TS 6.0.3 型別檢查。
- `node_modules/big.js/README.md`(strict、DP、RM、TypeScript 節);`node_modules/playwright-core/types/types.d.ts:20198-20270`(`allHeaders` / `headers` cookie 說明)。
- GitHub REST API:repo visibility(兩 repo public)、後端 develop CI run 42348849621 各 job 時間。
- `gsd-tools query package-legitimacy check`(5 套件 OK)。
- 以 Read 開啟:`application.yaml:36-86`、`TradingService.java:384-395`、`CashLedgerService.java:334-341`、`V18__watchlists.sql:22-35`、`styles.css:19-40`、`stock-start/pom.xml:160-201`、`vite.config.ts:1-25`、`Ops.vue:29-32`。

### Secondary (MEDIUM confidence)
- 以 shell(cat / sed / grep -n)讀取的其餘檔案:`apiClient.ts`、各頁 `describeError`、`ContainerIT.java`、11 個 market-data IT、`ci.yml`(兩 repo)、`run-e2e.sh`、`playwright.config.ts`、`journeyApi.ts`、`fixtures.ts`、`auth.spec.ts`、`trade.spec.ts`、`SecurityConfig.java`(`BrowserCsrfFilter`)、`BrowserAuthCookieService.java`、`BrowserAuthCookieSecureGuard.java`、`PortfolioCache.java`、各 DTO record、`browser-auth-contract.md`、`trading-portfolio-contract.md`、`docs/testing/overview.md`、`docs/guides/getting-started.md`。

### Tertiary (LOW confidence)
- 瀏覽器 `Intl.NumberFormat.format(string)` 支援版本(訓練知識,A2)。
- http-proxy 的 ws 升級行為(A4)。

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — 套件以 registry + seam + 實測打包 / 型別檢查;Jackson 行為以實測。
- Architecture: HIGH — 欄位清單、端點、testid、設定行號皆來自原始碼。
- Pitfalls: HIGH(科學記號、TTL test yaml、`/me` bootstrap、`allHeaders`、topic 重複建立皆有實證);MEDIUM(共用 Kafka 的實際順序相依需 CI 才能完全確認)。
- CI: MEDIUM — 範本來自既有後端 job;新 job 未實跑。

**Research date:** 2026-11-07
**Valid until:** 2026-12-07(套件版本與 CI 行為穩定;若 Spring Boot / Jackson 升級需重驗 Q2)
