---
task: quick-261106-fbs Task 3(文件掃描 + 審查處置 + todo 關閉,兩個 repo)
branch: claude/epic-curie-lj8alo
status: complete
t3_base_be: 7366702e034eb595a2d9cdc15ab2eb65eecc58e9
t3_base_fe: fbae35148e96dc787679fa67dfabb27b7910b604
be_docs_commits: 5   # 不含本報告的 commit
fe_docs_commits: 2
files_changed: { backend: 31, frontend: 6 }   # 全部 .md
---

# quick-261106-fbs Task 3 報告

D 全文件掃描已完成。依 Task 1 / Task 2 的程式變更,以程式為準修正兩個 repo 的文件；關閉 5 條 todo,並更新 04.1 / 04.2 審查處置與 04.1 UI-SPEC。本 task **只改了 `.md`**,程式與測試都沒動(證據見最後一節)。

## Commits

**後端**(`/home/user/stock-web-v2`),依時間序:

| SHA | 子項 | Subject |
|---|---|---|
| `0580376` | 3a | docs(quick-261106-fbs): 修正 04.2 G2 三處文件瑕疵 |
| `77b599b` | 3e | docs(quick-261106-fbs): 同步 04.1 / 04.2 審查處置與 04.1 UI-SPEC 文案 |
| `c4d3ee1` | 3f | docs(quick-261106-fbs): 關閉 5 條已解決待辦並安排 04.1 WR-01 / WR-04 去處 |
| `5ca148d` | 3b | docs(quick-261106-fbs): ai-docs 逐份依程式現況校正 |
| `4ef11e5` | 3c | docs(quick-261106-fbs): .planning/codebase 七份對照圖針對性更新至現況 |
| (本報告) | — | docs(quick-261106-fbs): Task 3 文件掃描執行報告 |

**前端**(`/home/user/stock-web-v2-front-end`):

| SHA | 子項 | Subject |
|---|---|---|
| `82edd5b` | 3d | docs(quick-261106-fbs): 前端 AGENTS.md 與 docs 依程式現況全面校正 |
| `5e215a0` | 3d | docs(quick-261106-fbs): 前端 AGENTS.md commit scope 敘述對齊兩邊實務 |

## 各檔改動摘要

### 後端 — 3a:04.2 G2
- `ai-docs/watchlist-contract.md`
  - 檔首「凍結契約、程式碼尚不存在」改為「已實作(後端 PR #51 / 前端 PR #19),程式為事實來源」。
  - 上限表的兩列補明:count 必須是**取得列鎖之後另起**的 `countByUser` / `countItems` 查詢,並附 READ COMMITTED 快照的理由。
  - 〈變更紀錄〉加一條。
- `ai-docs/trading-portfolio-contract.md`
  - 檔首 〔04.2〕 凍結提示改為已實作。
  - D-01 語意變更的日期 2026-10-31 → 2026-11-01,共 4 處:語意變更提示、`totalPnl` 列、`roi` 列、快取鍵 v3 說明。四處指的都是同一個裁決;裁決日見 04.2-26-SUMMARY G2。
  - 〈變更紀錄〉加一條。

### 後端 — 3e:審查處置與 UI-SPEC
- `04.2-REVIEW-DISPOSITION.md`
  - WR-02 改為 `fixed`(`9bf3f83` → `dc7267d`)。
  - WR-04 改為 `fixed`(`c231197` → `7221c7f`)。
  - frontmatter 與表格兩處皆同步,`open: 14 → 12`;其他列不動。
  - 手改,沒有跑 disp2.sh,理由見〈偏離〉。
- `04.1-REVIEW-DISPOSITION.md`
  - 改為 `fixed`:WR-02(`67c2fac` → `4b16897`)、WR-03(`4ad719b` → `2083974`)、IN-01(`6900780`)、IN-05(`35c2181` → `c7a4d88`)、IN-02 / 03 / 04(前端 `5202cd8` → `fbae351`)。
  - WR-01 改指 `fx-pnl-and-rate-history`。
  - WR-04 改指 Phase 5 的新 todo。
  - frontmatter 加 `updated:`。
- `04.1-UI-SPEC.md`:Copywriting 表在 `occurredAt` 列之後新增「清空」一列,文案為 `請填寫發生時間。` / `Occurred at is required.` / `cashErrOccurredAtRequired`。已與前端 `i18n.ts:270 / :693` 逐字核對。

### 後端 — 3f:todo
- 移入 `completed/`,各自加上 `resolved: 2026-11-06`、`resolved_by: quick-261106-fbs(後端 da4b541..7221c7f、前端 b4ff61e..fbae351;PR 待 orchestrator 開)` 與 `## Resolution`:
  - `cash-occurred-at-empty-copy`
  - `top-movers-base-currency`
  - `contrast-text-tokens`
  - `phase-04.1-review-followups`(明列 WR-01 / WR-04 的延後去處)
  - `full-docs-sweep`(指向本報告的裁決清單)
- `pending/2026-09-28-fx-pnl-and-rate-history.md`:追加一節「併入 04.1 WR-01(基準幣總額未標示匯率時間)」。
- 新增 `pending/2026-11-06-cash-amount-string-serialization.md`:area portfolio,target Phase 5,內容摘錄 04.1 WR-04,並核對後端 `@Digits(13,8)` 與前端 `amount: number`。
- 目前 pending 共 7 條:`backend-asset-classification`、`it-shared-containers`、`account-level-return`、`asset-master-management`、`fx-pnl-and-rate-history`、`kline-adjusted-prices`、`cash-amount-string-serialization`。

### 後端 — 3b:ai-docs
- **architecture.md**
  - 模組樹補 `stock-module-backtest`。
  - 補現有的 4 個 Facade 與新聚合根:cash_ledger_entries / cash_dividends / share_adjustments / watchlists / backtest。
  - Watchlist 敘述改為已實作。
  - Compliance 段標為「設計目標、未實作」。
  - 失效連結 `docs/plans/2026-03-21-architecture-design.md` 改為說明文字(repo 與 git 歷史皆無此檔)。
- **security.md**
  - 〈Public Endpoints〉依 `SecurityConfig` 重寫。原表的 `/api/v1/market-data/**` 公開、`/api/v1/search*` 都是錯的;漏列 `/csrf`、`/auth/token`、`/auth/refresh`、`/auth/logout`。
  - 標示未實作:`user_permissions` 覆寫、§11 使用者刪除、§14 安全掃描工具、change-password 端點。
  - §15 限流改為實作描述:Redis `rl:*`、`RateLimitService` / `ApiRateLimitInterceptor`,不是 Resilience4j。refresh 限流以 token 擁有者計,找不到時退回 IP。
  - §17 管理埠 8081 → `11181`。
  - §19 註明哪些安全標頭是自訂、哪些是 Spring 預設。
- **browser-auth-contract.md**:錯誤碼表補 429 `AUTH_RATE_LIMITED` 與 `AUTH_ACCOUNT_LOCKED`。
- **flyway-convention.md**
  - 「check stock-start migrations」改為正確目錄,並註明最高版號 V19。
  - 範例檔名標為示意。
  - `@Query` 讀取改為 `JdbcClient`。
  - 註明目前沒有任何模組用 `db/testdata`。
- **redis-convention.md**:持倉鍵說明加 `unrealizedPnlInBase`(回應時補值、不寫入快取、不換鍵);summary v3 的日期改為 2026-11-01。
- **code-standards.md**:SQL 規則 2 改為 `JdbcClient` 具名參數(repo 沒有 `@Query`);v3 日期同上。
- **testing-standards.md**
  - 補 `*ControllerWebMvcTest` 與後端 E2E 層。
  - 補 `ContainerIT` 的實際映像(pg16 / redis 7.4 / cp-kafka 7.6,沒有 `withReuse`)。
  - 註明 DataProvider 測試樣式為設計目標。
  - 補 ArchUnit 的實作對照。
- **trading-portfolio-contract.md**:〈快取與一致性〉補「讀取的快照一致性」(REPEATABLE_READ,04.1 WR-03)。
- **event-conventions.md**:內容與程式一致,只加註 re-verified 日期。
- **git-convention.md**:補一行實務現況:GSD 工作以 phase / quick id 作 scope。
- **harness-diagnosis.md**:次要發現 3(信封不一致)加註「已於 2026-07-19 對齊」;其餘是快照,不改。
- **核對過、未改**:judgment、task-briefs、maintenance-protocol、model-dispatch(config 值與 `.planning/config.json` 一致)、letter-to-future-sessions、`bug-reports/INDEX.md` / `LESSONS.md`(連結全部有效)。其中前端路徑的寫法列入裁決清單第 1 項。

### 後端 — 3c:`.planning/codebase/` 七份
- 所有檔:
  - `last_mapped_commit` 改為 `5ca148d…`;前端 commit 改為 `5e215a0…`。
  - Analysis Date 改為 2026-11-06,並加一行 **Refresh** 說明:這是 targeted refresh,不是全量重產。
  - 把錯一層的 `../vue/stock-v2` 統一修成 `../../vue/stock-v2`(Yuan 本機佈局),並註明 cloud 環境的路徑。
- **STACK**:補 e2e-browser profile、ArchUnit、Playwright、Spotless / JaCoCo、ESLint / Prettier / TS 6、CI 各 job、V12 / V19、Node 20。
- **STRUCTURE**:補各模組的新職責(watchlist / cash / dividend / share adjustment / activity)、V8–V19、Cash 頁、完整的 services 清單、Pinia mock stores、`e2e/`;移除不存在的根目錄 `src/main/resources` 與 `src/store.ts`。
- **INTEGRATIONS**
  - auth 改為 cookie + CSRF / bearer 雙軌;公開與受保護端點依程式更新。
  - backfill 鍵更正為 `market:backfill:idem:`。
  - 補 `rl:*` 與 portfolio 鍵。
  - 補 spotless 與 browser E2E job,以及前端 CI。
  - OpenAPI / Swagger 的預設值 `true` → `false`。
  - 更正 `VITE_DATA_MODE` 的 fail-fast 行為。
  - 補 `fx_rates`。
- **CONVENTIONS**:改寫為 Spotless 與 import 順序、ESLint / Prettier;`ApiClientError` 欄位依實際程式;owner 檢查改為 query scoping;刪掉不存在的 `SystemException` / `LikeEscapeUtil`;記錄 `//` 註解政策。
- **ARCHITECTURE**
  - 元件表、服務層、領域層、Facade 依現況更新。
  - auth 流程改為 cookie + CSRF + 限流。
  - 新增 Trading / Portfolio 的 5 步流程與 Watchlist 流程。
  - 前端狀態改為 Pinia mock stores。
  - 信封不一致標為已解決。
  - 拿掉已漂移的 `.java:NNN` 行號。
- **TESTING**
  - CI 各 job 與前端 CI。
  - 檔案與測試數:後端 177 個 test 檔、949 個單元測試;前端 59 個測試檔、1138 個測試。
  - E2E 命名 `*E2E.java`;JaCoCo 綁在 `verify`;Playwright。
  - ArchUnit 已實作。
- **CONCERNS**:新增「2026-11-06 狀態總覽」表,逐條標註已解決 / 部分 / 仍存在,並附證據。原文保留作歷史脈絡;本次沒有逐條重驗的項目在表中明列。

### 前端 — 3d
- **AGENTS.md**(47 行,上限 120)
  - 鐵律 4 改述為「Common API Conventions 一節已於 2026-07-19 改寫為後端信封」,鐵律本身不變。
  - CI 改為 `npm ci → lint → test → build`。
  - 驗證命令加 `npm run lint` 與 layout.spec。
  - 技術棧版本對齊 `package.json`。
  - 行數「872」改為「近千行」(實際 953)。
  - 刪掉 judgment / task-briefs「合併後才存在」(兩檔都已存在),並補上另外兩份後端契約檔。
  - 補已接 API 的 domain 清單,以及 Ops / AI Access 的 Preview 狀態。
  - 補兩邊路徑(本機 / cloud)。
  - 註明 `screenshots/` 與 `*.log` 已 gitignore。
  - commit scope 的敘述對齊兩邊實務。
- **docs/api-contracts/mock-to-real-contract.md**
  - 檔首加 2026-11-06 核對狀態。
  - `apiClient.ts` 的職責描述更新為 cookie / CSRF / 信封。
  - Backtest:補後端實際規則(`currency=USD` / `benchmark=buy_hold` / `dataMode=cached` / symbol ≤ 32);後端目前**同步執行**;create-run 範例回應改為 `succeeded` 並補 `progress` / `error`。
  - Backtest 錯誤碼依後端 `ErrorCode` 重列:新增 `UNSUPPORTED_STRATEGY` / `UNSUPPORTED_DATA_MODE` / `RESULT_NOT_READY`;草案的 `MARKET_DATA_UNAVAILABLE` / `RUN_TIMEOUT` 標為後端未定義。
  - Ops 與 AI Access 兩節標「後端尚未實作」(後端沒有對應的 controller 與錯誤碼)。
  - Open Decisions 註明目前為同步執行。
- **docs/superpowers/** 三份(2 plans + 1 spec):檔首加一行「歷史文件(2026-05-16),不隨程式維護」。
- **docs/LEARNINGS.md**:append 一條(常載路由檔裡的時效性敘述會無聲過時)。既有條目沒有修改。

## 需 Yuan 裁決

1. **前端 sibling 路徑以哪個為準?**
   - 衝突點:todo `full-docs-sweep` 說 `../../vue/stock-v2/vue-app/` 是「舊路徑」。但 `.planning/PROJECT.md:52`(`/mnt/d/end/workspace/vue/stock-v2/vue-app`)與 `ai-docs/bug-reports/LESSONS.md` 2026-07-07(promoted:「實際是 `../../vue/stock-v2`」)都顯示它是你本機的正確路徑;cloud 環境則是 `../stock-web-v2-front-end`。
   - 本次做法:保留 `../../vue/stock-v2`;只把錯一層的 `../vue/stock-v2` 修成 `../../`;在 codebase 七份與前端 AGENTS.md 註明 cloud 路徑。
   - 選項:(a) 維持現狀;(b) 全面改成 sibling `../stock-web-v2-front-end`;(c) 改用「前端 repo 根」這類抽象稱呼。
   - 影響的檔案:judgment §8、task-briefs、maintenance-protocol §3、PROJECT.md、STATE.md、後端 AGENTS.md。
2. **後端 `AGENTS.md`(GSD 生成)仍是 2026-05-30 的內容。**
   - 例如 `../vue/stock-v2` 錯一層、「no Spotless / ESLint」、「frontend/backend envelope mismatch」、「frontend trading 只有 AI/backtest/ops adapter」。
   - 本次已修正它的來源 `.planning/codebase/*`;依 plan 沒有動 AGENTS.md 本體。
   - 請決定是否跑 `/gsd-docs-update` 重新生成並 read-back。
3. **`.planning/codebase/*` 屬 GSD 機器管理**(maintenance-protocol §4、judgment §9 規定「禁手改」)。
   - 本次依 plan 3c 的後備方案做 targeted 手改,原因是本環境無法派 mapper agents。
   - 建議找時間跑 `/gsd-map-codebase` 全量重產;它會整目錄覆寫,本次的修正可作為對照。
4. **「新增 ai-docs 檔案必須 `git add -f`」這條規則是否仍需要?**(maintenance-protocol §4 / §5 checklist、harness-diagnosis 第 2 名)
   - 本 clone 的 `.git/info/exclude` 只有 `.planning/milestone.lock`,而 `git ls-files ai-docs` 已有 20 檔。
   - 但 exclude 是各 clone 自己的本機設定,你本機可能仍排除 `/ai-docs/`。程式判斷不了,所以沒改。
5. **git commit scope 規則。**
   - `ai-docs/git-convention.md` 寫 module scope,但 04 以後的實務是 phase / quick id(後端最近 300 個 commit 的 scope 全是這類)。
   - 本次只在 git-convention 補一行描述實務,前端 AGENTS.md 同步;規則本身沒改,是否正式改寫請你定。
6. **後端規則檔的前端驗證命令沒有 `npm run lint`**(judgment §8、task-briefs),但前端 CI 會跑 lint,lint 失敗會擋 PR。
   - 前端 AGENTS.md 已經加上;後端兩份屬規則變更,沒動。
7. **前端 `docs/LEARNINGS.md` 2026-07-07 第 2 條已過時**(「mock-to-real 信封與後端不一致」;該節已於 2026-07-19 對齊)。
   - 該檔規定「修改既有條目要先問 Yuan」,所以沒改。請裁決是否加註 resolved。
8. **`docs/plans/2026-03-21-architecture-design.md` 在 repo 與全部 git 歷史都不存在。**
   - 可能是你本機的孤本(`/docs/plans/` 曾在 exclude 裡)。
   - architecture.md 已把失效連結改成說明文字。如果本機有這份檔,要不要進版控?
9. **mock-to-real-contract 的 Ops / AI Access 兩節後端完全沒有實作。** 這兩節要保留為提案、移到 backlog,還是另立 todo?
10. **security.md / architecture.md 裡有多段「設計目標、未實作」。**
    - 包括:`user_permissions` 覆寫、使用者刪除與匿名化、安全掃描工具(Dependabot / SpotBugs / OWASP / ZAP / Trivy)、change-password、Compliance 的資料匯出。
    - 本次只加了標示。要排 todo,還是從規範檔移出?
11. **(低優先)D-01 語意變更的日期依 plan 對齊裁決日 2026-11-01。**
    - 如果你認為「起」應該指生效日,實際合併是 2026-11-05(PR #51)。原本的 10-31 兩者都不是。
    - 需要時,要改的位置是 trading-portfolio-contract 4 處、redis-convention、code-standards。
12. **(觀察,屬程式,本 task 未改)OrderTicket 仍保留 MKT / LMT 切換與 TIF 標籤。**
    - 送出的 payload 已不含這些欄位(`tradingApi.ts` 的 `CreateTradeRequest`)。
    - 這些 UI 是否違反前端鐵律 5 的精神,請你判斷。

## 偏離 plan

1. **STATE.md 沒有改。** orchestrator 明確指示「do NOT edit STATE.md」,優先於 plan 3e。請 orchestrator 補兩處 scoped edit:
   - `### Pending Todos`(.planning/STATE.md:179–182):
     - 本次關閉 5 條、新增 1 條,現存 7 條 pending,盤點日 2026-11-06。
     - 第 180 行把 `backend-asset-classification` 列為「併入 04.1」,但它仍在 `pending/`;這點不是本 quick task 造成的,只順帶告知。
   - Blockers 第 204 行:04.2 REVIEW 的 WR-02 / WR-04、契約 G2、Markets < 640px 已由 quick-261106-fbs 處理;open finding 由 14 條降為 12 條。
2. **04.2 disposition 是手改,沒跑 `disp2.sh`。** plan 3e 只要求更新 frontmatter 與表格的 WR-02 / WR-04,沒有要求重產 ledger;手改可保留其他列的人工 Source 說明,`open` 計數也同步改為 12。
3. **3c 沒有用 `gsd-map-codebase` skill。** 該 skill 要平行派 mapper agents,本執行環境沒有 agent 派工工具,所以依 plan 的後備方案做 targeted 更新。沒有逐條重驗的部分,在 CONCERNS 狀態表與各檔 Refresh 行都已註明。
4. **`full-docs-sweep` 的 `resolved_by` 記的是 Task 1 / 2 的程式 commit 範圍。** Task 3 的文件 commit 列在本報告。

## Verify 結果(plan Task 3 `<automated>`,cwd = backend 根)

| # | 閘門 | 結果 |
|---|---|---|
| V1 | watchlist `程式碼尚不存在` = 0、trading `2026-10-31 語意變更` = 0(前值 1 / 1) | PASS |
| V2 | 5 條 todo 不在 `pending/`(前值 5) | PASS |
| V3 | 5 條 completed 皆有 `resolved: 2026-11-06` / `resolved_by:` / `## Resolution` | PASS |
| V4 | 新 todo `2026-11-06-cash-amount-string-serialization.md` 存在,且 fx todo 含 `WR-01` | PASS |
| V5 | `.planning/codebase/*.md` 沒有 `Analysis Date:** 2026-05-30`(前值 7) | PASS |
| V6 | 04.1 UI-SPEC 含 `cashErrOccurredAtRequired`;04.2 disposition 的 WR-02 / WR-04 為 `disposition: fixed`(前值 0 / 0) | PASS |
| V7 | 前端 AGENTS.md ≤ 120 行(實際 47) | PASS |
| V8 | 兩個 repo 所有 `docs(quick-261106-fbs)` commit 只含 `.md` | PASS |

**只改 `.md` 的複核:**
- `git diff --name-only 7366702..HEAD | grep -vE '\.md$'` → 無輸出(後端 31 檔,+516 / −338)。
- `git -C ../stock-web-v2-front-end diff --name-only fbae351..HEAD | grep -vE '\.md$'` → 無輸出(前端 6 檔,+51 / −23)。
- 沒有 code / test / config 檔被改動,也沒有跑 build 或 test 的必要(純文件)。

## Self-Check

- 7 個 docs commit(後端 5、前端 2)皆為各自 HEAD 的祖先;每個 commit 前都確認了 toplevel 與 branch `claude/epic-curie-lj8alo`。
- 沒有使用 stash / reset --hard / rebase / push / amend / --no-verify。
