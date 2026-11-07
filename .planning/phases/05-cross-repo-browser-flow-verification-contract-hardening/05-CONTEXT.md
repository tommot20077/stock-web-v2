# Phase 5: Cross-Repo Browser Flow Verification & Contract Hardening - Context

**Gathered:** 2026-11-07
**Status:** Ready for planning

<domain>
## Phase Boundary

後端與前端(sibling Vue repo)的自動測試,加上真後端瀏覽器流程,共同證明整合契約可用(VER-01 / VER-02 / VER-03,ROADMAP Phase 5 SC1–SC5)。同時執行 Yuan 2026-11-07 裁決併入的契約強化與一致性修補:

1. **驗證**:SC1(後端 Maven)/ SC2(前端 Vitest / type-check / build)對照稽核補缺;SC3 串連 journey;SC4 cookie / CSRF / credentials 斷言;SC5 API mode 失敗狀態。
2. **CI**:`@extended` 每次 PR 一起跑;前端 CI 也跑真後端 browser E2E。
3. **契約強化**:全 API 的 `BigDecimal` 欄位改字串序列化,前端引入 decimal 套件。
4. **一致性修補**:quick-261107-1j4 的 4 條觀察、2026-11-07 文件更新時發現的 6 條契約 / 設定問題、`it-shared-containers`。

**範圍權威**:`.planning/todos/pending/2026-11-07-phase-5-scope-decisions.md`(Yuan 已裁決,勿重議)。本檔只記錄 discuss 中決定的「怎麼做」。

**不在本 phase**:五個大型功能(帳戶層級報酬率、匯率歷史、K 線還原、資產主檔、Analytics 產業分布)、帳號與安全強化、Ops / AI Access 後端;DB 計數維持現狀(見 Deferred)。

</domain>

<decisions>
## Implementation Decisions

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
- **D-15:** **兩邊 CI 都跑真後端 browser E2E**:前端 `.github/workflows/ci.yml` 新增 browser-e2e job,checkout 後端同名分支(不存在時用 `develop`),重用 `run-e2e.sh`(仿照後端 CI 的 Browser E2E job,只是方向相反)。若後端 repo 是 private,需要 Yuan 在前端 repo 設一個唯讀 secret(fine-grained PAT 或 deploy key);planner 要把這一步列成 Yuan 的人工步驟(checkpoint),不能假設已存在。

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

### Folded Todos
- **`2026-11-07-phase-5-scope-decisions.md`** — Phase 5 範圍裁決本身;D-01 ~ D-04 與本檔其餘決策都以它為準。
- **`2026-11-06-cash-amount-string-serialization.md`** — 現金金額以 JSON double 送出,超過約 15–17 位有效數字會被無聲改寫;Yuan 2026-11-07 已裁決擴大為全欄位字串化;D-05 ~ D-09。
- **`2026-09-27-it-shared-containers.md`** — market-data IT 共用容器(方案 B),估計 `verify` 可省約 60 秒;D-04。

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

> 路徑相對於後端 repo 根;前端路徑以「前端 repo 根/」開頭(Yuan 本機 `../../vue/stock-v2`、cloud `../stock-web-v2-front-end`,對照見 `ai-docs/judgment.md` §8)。

### 範圍與決策來源
- `.planning/todos/pending/2026-11-07-phase-5-scope-decisions.md` — Phase 5 範圍權威(Yuan 已裁決)
- `.planning/todos/pending/2026-11-06-cash-amount-string-serialization.md` — 金額精度問題背景與〈決定〉
- `.planning/todos/pending/2026-09-27-it-shared-containers.md` — 共用容器方案與風險
- `.planning/quick/261107-1j4-small-fixes-batch-b-04-2-review-closeout/261107-1j4-SUMMARY.md` — D-02 四條觀察的出處(〈需 Yuan 處理 / 決定〉)
- `.planning/ROADMAP.md` §Phase 5 — SC1–SC5
- `.planning/REQUIREMENTS.md` — VER-01 / VER-02 / VER-03

### 契約與規範
- `ai-docs/browser-auth-contract.md` — auth cookie、CSRF、refresh / logout、401 / 403(D-03、D-11、D-22 要更新)
- `ai-docs/trading-portfolio-contract.md` — DTO 欄位型別(D-05、D-09 要改)
- `ai-docs/watchlist-contract.md` — 觀察清單契約(D-02b、SC5 Watchlist 頁)
- `ai-docs/judgment.md` §4(信封權威)、§8(跨 repo 路徑與驗證)
- `ai-docs/testing-standards.md` — Unit / IT / E2E 規範與命名
- `ai-docs/architecture.md` — D-03 用詞更正
- `ai-docs/security.md` — 安全規範(SC4 斷言對照)
- `docs/testing/overview.md`、`docs/api/reference.md`、`docs/configuration/overview.md`、`docs/guides/getting-started.md` — 2026-11-07 文件更新時發現的問題細節(D-03、D-21 ~ D-23)
- 前端 repo 根/`docs/api-contracts/mock-to-real-contract.md` — Common API Conventions(大檔,只讀需要的段落)

### 程式與設定
- `.github/workflows/ci.yml` — 後端 CI,Browser E2E job(D-14 改 step、D-15 的範本)
- `stock-start/pom.xml` — surefire exclude `**/*E2E.java`、failsafe、`e2e` profile(D-23)
- `stock-start/src/main/resources/application.yaml`、`application-demo.yaml`、`application-e2e-browser.yaml` — port 與 TTL(D-03、D-21、D-22)
- `stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/` — 7 個 `*E2ETest`(D-23)與 `support/`
- `stock-start/src/test/java/dowob/xyz/stockwebv2/start/support/ContainerIT.java` — 共用容器基底(D-04)
- `stock-module-market-data/src/main/java/dowob/xyz/stockwebv2/marketdata/api/LatestPriceDto.java`、`KlineDto.java`、`stock-common/src/main/java/dowob/xyz/stockwebv2/common/event/PriceTickEvent.java` — 已字串化的範例(D-05)
- `stock-module-trading/src/main/java/dowob/xyz/stockwebv2/trading/api/*Dto.java`、`*Request.java` — 要字串化的 trading DTO
- 前端 repo 根/`.github/workflows/ci.yml` — D-15 新增 job
- 前端 repo 根/`vue-app/vite.config.ts` — proxy target(D-21)
- 前端 repo 根/`vue-app/playwright.config.ts`、`vue-app/e2e/run-e2e.sh`、`vue-app/e2e/run-e2e.ps1`、`vue-app/package.json` — D-14、D-21
- 前端 repo 根/`vue-app/e2e/tests/auth.spec.ts`、`trade.spec.ts`、`cash-journey.spec.ts` — 既有片段與寫法
- 前端 repo 根/`vue-app/e2e/support/fixtures.ts`、`journeyApi.ts`、`portfolioStubs.ts`、`selectors.md` — E2E 支援檔
- 前端 repo 根/`vue-app/src/services/apiClient.ts` — transport 唯一邊界(鐵律 1;SC5 非信封回應處理)
- 前端 repo 根/`vue-app/src/services/apiTypes.ts` — D-05 / D-09 型別變更
- 前端 repo 根/`vue-app/src/composables/useNow.ts` — D-02d

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- **後端 Browser E2E job**(`.github/workflows/ci.yml`):同名分支解析與 checkout、Node / Java setup、artifact 上傳,可直接作為前端 CI 新 job 的範本(D-15)。
- **`run-e2e.sh`**:compose 起 infra → 後端 package + `java -jar`(e2e-browser,8080)→ 等 health → Playwright;兩邊 CI 共用。
- **`e2e/support/fixtures.ts`** 的 `uniqueAccount`、**`journeyApi.ts`** 帶真 cookie + `X-XSRF-TOKEN` 的寫入輔助函式:journey 可以直接用。
- **`auth.spec.ts` A11** 已示範手組 `Cookie` 與 `X-XSRF-TOKEN` 打 API,可參考寫登出後 `/me` 401 的斷言。
- **各頁的重試 UI 已存在**:Overview 有 `overview-summary-retry`、`overview-trades-retry`、`overview-watchlist-retry`、`overview-refresh-retry`;Positions、Trades、Cash、Watchlist、Settings 也都有重試 / 錯誤區塊,SC5 不需要新做 UI(若稽核發現哪頁缺,就列為缺口)。
- **`ToStringSerializer`**:行情 DTO 已在使用,可作為 trading DTO 字串化的寫法範例。
- **`useNow`**:已有 composable 與測試(D-02d)。

### Established Patterns
- 後端測試後綴決定執行者:`*Test` → surefire、`*IT` → failsafe、`*E2E` → `-Pe2e`;需要容器的測試不得叫 `*Test`(D-23 修正既有違規)。
- Playwright 命名:`test` 名稱用繁中 + 案例編號 + tag;spec 檔名 kebab-case。
- 前端 mock 是明確模式(鐵律 3):SC5 斷言「沒有 mock 值」正是在驗證這條規則。
- 跨 repo 變更用同名分支,後端 CI 會自動抓前端同名分支(D-07 硬切的前提)。
- 前端 API 回應一律經 `apiClient.ts` 解析 `ApiResponse<T>`,追蹤 id 在 `meta.traceId`。

### Integration Points
- 前端 `apiTypes.ts` → 各 domain service(`tradingApi`、`cashApi`、`holdingEventApi`、`marketApi`、`profileApi` 等)→ Pinia store / 元件:decimal 轉換放在 service 層(D-08)。
- 前端 `moneyFormat` 與圖表元件:D-08 中 decimal 轉成顯示值的唯一出口。
- 後端 trading `*PayloadMatcher`(冪等比對)含 `BigDecimal`:字串化不能改變冪等比對語意(請求字串 → `BigDecimal` 後再比對)。
- 前端 mock adapter(`stores/mockPortfolio.ts` 等):回傳型別要跟著 `apiTypes` 改成字串。

</code_context>

<specifics>
## Specific Ideas

- 精度回歸案例:輸入 `123456789012.12345678`,必須原樣存進後端並原樣讀回(todo 原例,前後端都應有測試)。
- journey 的請求監聽是「整條流程結束時一次斷言」,失敗訊息要列出違規的請求(method + URL)。
- SC5 矩陣以表格(頁面 × 失敗種類)驅動,18 組都要有獨立的 test 名稱,方便定位。
- 真後端 browser E2E 依 Yuan 決定在**兩個 repo 的 CI** 都跑,前端單獨的 PR 也要有真後端證據。

</specifics>

<deferred>
## Deferred Ideas

- **DB 計數**(分頁 `count(*)`、觀察清單 100 檔上限 count)— Yuan 2026-11-07 裁決維持現狀,不排工作。

### Reviewed Todos (not folded)
- `2026-09-28-account-level-return.md` — 帳戶層級報酬率:新 milestone 的第一個 phase(Yuan 2026-11-07 第 1 題)。
- `2026-09-28-fx-pnl-and-rate-history.md` — 匯率歷史:新 milestone「真實行情與參考資料來源」主題。
- `2026-09-28-kline-adjusted-prices.md` — K 線還原:同上主題。
- `2026-09-28-asset-master-management.md` — 資產主檔:同上主題。
- `2026-07-19-backend-asset-classification.md` — 剩 Analytics 產業分布,隨 Analytics API 化處理。
- `2026-11-07-account-and-security-hardening.md` — 帳號與安全強化:backlog。
- `2026-11-07-ops-and-ai-access-backend.md` — Ops / AI Access 後端:backlog。

</deferred>

---

*Phase: 05-cross-repo-browser-flow-verification-contract-hardening*
*Context gathered: 2026-11-07*
