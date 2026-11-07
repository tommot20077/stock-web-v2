# quick-261107-1j4 Task 3(文件 / 流程裁決 / todo / 04.2 審查收尾)執行報告

> 執行者:Task 3 executor(sequential,主工作樹;兩個 repo 皆在 `claude/epic-curie-lj8alo`)
> 日期:2026-11-07
> 範圍:PLAN Task 3 子項 3a–3i,並依〈Orchestrator addendum〉讓文件反映 E1(`HoldingDto.market`、持倉快取鍵 v3)、E2(對話框改用持倉 market,未知市場 UTC)、E3(accent 當文字一律 `--accent-text`)
> 狀態:**完成**。後端 8 個 commit、前端 3 個 commit,全部只含 `.md`;未 push;本報告未 commit;未改 STATE.md / ROADMAP.md / PLAN / SUMMARY / `.planning/codebase/*` / 後端 `AGENTS.md` / `ai-docs/bug-reports/*`。

## 1. 起點與 precondition

- T3_BASE_BE = `22401f2df92f3fda19170adf72b16a8dc18928d5`、T3_BASE_FE = `3d8ae1551dbcdab6e69ce06be7e5e5c9a328f184`。
- Precondition 成立:兩個 repo 的 `git log --grep quick-261107-1j4` 都有 GREEN commit;工作樹乾淨;`task1-report.md`、`task2-report.md` 存在(orchestrator 已 commit)。

## 2. Commits

### 後端 `/home/user/stock-web-v2`(`22401f2..eb2650a`,8 個)

| # | Hash | 子項 | Subject | 檔案 |
|---|---|---|---|---|
| 1 | `a9d33de` | 3a | docs(quick-261107-1j4): 前端 repo 路徑只在 judgment §8 定義一次,前端驗證命令加 lint | `ai-docs/judgment.md`、`ai-docs/task-briefs.md`、`ai-docs/maintenance-protocol.md` |
| 2 | `4f2b38d` | 3b | docs(quick-261107-1j4): git-convention 的 commit scope 正式改為 phase / quick id | `ai-docs/git-convention.md` |
| 3 | `6d8760a` | 3c | docs(quick-261107-1j4): 撤除新增 ai-docs 檔案必須強制加入版控的規則 | `ai-docs/maintenance-protocol.md`、`ai-docs/harness-diagnosis.md` |
| 4 | `fa39446` | 3d | docs(quick-261107-1j4): 未實作的帳號與安全設計目標移至 todo,規範檔只留指向 | `ai-docs/security.md`、`ai-docs/architecture.md`、新 todo `2026-11-07-account-and-security-hardening.md` |
| 5 | `cd29661` | 3e(+3a) | docs(quick-261107-1j4): Ops 與 AI Access 後端另立 backlog todo,PROJECT.md 同步裁決與路徑 | `.planning/PROJECT.md`、新 todo `2026-11-07-ops-and-ai-access-backend.md` |
| 6 | `33df981` | 3f(+3a) | docs(quick-261107-1j4): 依 Yuan 裁決更新既有 todo 排程並新增 Phase 5 範圍裁決 todo | 6 條既有 todo + 新 todo `2026-11-07-phase-5-scope-decisions.md` |
| 7 | `970c9bd` | 3g | docs(quick-261107-1j4): 04 / 04.1 / 04.2 UI-SPEC 與本次程式修正對齊 | `04-UI-SPEC.md`、`04.1-UI-SPEC.md`、`04.2-UI-SPEC.md` |
| 8 | `eb2650a` | 3h | docs(quick-261107-1j4): 04.2 審查處置收尾,剩餘 14 條改為 fixed、open 歸零 | `04.2-REVIEW-DISPOSITION.md` |

差異:22 個檔案,+345 / −119,全是 `.md`。

### 前端 `/home/user/stock-web-v2-front-end`(`3d8ae15..c65caeb`,3 個)

| # | Hash | 子項 | Subject | 檔案 |
|---|---|---|---|---|
| 1 | `3ee6051` | 3a + 3b | docs(quick-261107-1j4): AGENTS.md 後端路徑指回 judgment §8,commit scope 規則同步後端 | `AGENTS.md`(47 行,≤ 120) |
| 2 | `262535d` | 3e | docs(quick-261107-1j4): 契約檔首 Ops / AI Access 提示指向後端 backlog todo | `docs/api-contracts/mock-to-real-contract.md` |
| 3 | `c65caeb` | 3i | docs(quick-261107-1j4): LEARNINGS 信封條目標為 resolved,追加本次測試踩坑 | `docs/LEARNINGS.md` |

差異:3 個檔案,+9 / −5,全是 `.md`。

所有 commit:Conventional Commits、繁中 subject、type `docs`、scope `quick-261107-1j4`,訊息結尾正好是兩行 trailer(`Co-Authored-By` 與 `Claude-Session`);每次 commit 前檢查分支為 `claude/epic-curie-lj8alo`、非 detached、只 stage 明確 `.md` 路徑;沒有刪除任何檔案。

## 3. 各文件改動摘要

**3a 路徑單一定義 + lint(3C-1、3C-3)**
- `judgment.md` §8 開頭新增「兩個 repo 的路徑(唯一定義處)」:前端 repo 根 = Yuan 本機 `../../vue/stock-v2`、cloud `../stock-web-v2-front-end`(相對後端根),程式在 `vue-app/`;後端 repo 根 = Yuan 本機 `../../java/stock-web-v2`、cloud `../stock-web-v2`(相對前端根);其他文件一律寫「前端 repo 根」並指回本節。§8 第 2 點前端命令改為 `cd <前端 repo 根>/vue-app && npm run lint && npm test && npm run build`,API mode 加 `VITE_DATA_MODE=api npm test`。
- `task-briefs.md` 前端命令同步(含 lint),路徑寫「前端 repo 根(定義見 judgment.md §8)」。
- `maintenance-protocol.md` §3 前端列改為「前端 repo 根的 `AGENTS.md` 或 `docs/`(教訓記在其 `docs/LEARNINGS.md`;路徑定義見 judgment.md §8)」。
- `PROJECT.md` Context 的 `/mnt/d/.../vue/stock-v2/vue-app` 改為 "the sibling frontend repository (frontend repo root; paths defined once in `ai-docs/judgment.md` §8), code under `vue-app/`";後端路徑那行不動。
- todo frontmatter 的前端路徑統一為 `"前端 repo 根/vue-app/…"`(asset-classification、cash-amount;新 todo ops-and-ai-access 也用此形式)。
- 前端 `AGENTS.md`:「這是什麼專案」保留一行後端 repo 根定義並加「兩邊路徑對照見後端 `ai-docs/judgment.md` §8」;〈按需路徑〉三處 `../../java/stock-web-v2/ai-docs/…` 改為「皆在後端 repo 根的 `ai-docs/`」。

**3b commit scope(3C-3)**:`git-convention.md` scope 規則改為 GSD 工作用 phase / plan id(`NN-NN`,例 `feat(04.2-12)`、`docs(04.2)`)或 quick id(`quick-YYMMDD-xxx`,例 `fix(quick-261106-fbs)`),module / layer 只用於非 GSD 變更,刪掉「In practice…」補充行(檔案原為英文,規則用英文寫)。前端 `AGENTS.md` Commit 行同步,刪掉「後端文字寫 module scope」括號。

**3c 撤除強制加入規則(3C-3)**:`maintenance-protocol.md` §4 `ai-docs/*.md` 列刪掉強制加入那句;§5 第 2 點改為「新檔案已進版控(`git ls-files <檔>` 有輸出)?」,編號連續。`harness-diagnosis.md` 第 2 名後補「後續(2026-11-07 Yuan 裁決 3C-3)」一段,原診斷保留。

**3d 帳號安全設計移到 todo(3C-4、3C-6)**:新 todo `2026-11-07-account-and-security-hardening.md` 原文搬移 security.md §3 Permission overrides + 載入公式、Notes 的 change-password、§11 User Deletion Security Flow 全段、§14 工具表與設計目標說明、§15 change-password 限流列,以及 architecture.md Compliance Design 的 user deletion / data export / retention。原處各留一行 `> 尚未實作的設計目標已移至 …(Yuan 2026-11-07 裁決)`,章節標題與編號保留;現行事實留在原檔(security.md §3 改寫為「Permissions come solely from `Role`」、§14 留 CI 現況一句與 Spring Boot upgrade strategy、architecture.md 留 append-only 帳本一條,並核對 V8 / V15 / V16 / V17 都有 no-update / no-delete trigger)。architecture.md 檔首對 `docs/plans/2026-03-21-architecture-design.md` 不在版控的說明核對仍正確(檔案不存在、`docs/plans/` 只追蹤兩份 2026-07-16 檔),**未改**(3C-6)。

**3e Ops / AI Access(3C-5)**:新 todo `2026-11-07-ops-and-ai-access-backend.md`(`area: backend`、`target: backlog`;已以 grep 核對後端無 `api/v1/ops` / `api/v1/ai` 端點與錯誤碼)。`PROJECT.md` Out of Scope 新增一條指向該 todo。前端契約檔首 Ops / AI Access 提示補一句指向後端 todo。

**3f 既有 todo(裁決 1、2)**:account-level-return(`target: v1.0 之後的新 milestone(第一個 phase)` + 排程一節);fx-pnl / kline / asset-master(`target: v1.0 之後的新 milestone,主題「真實行情與參考資料來源」` + 同主題排程一節);asset-classification(只留 Analytics,`target: 隨 Analytics API 化`,說明 Overview / Positions 已由 04.1 解決);it-shared-containers(`target: Phase 5`,標題與內文加併入說明);cash-amount(新增〈決定〉:decimal 套件、請求與回應金額 / 數量 / 價格改字串、service 層轉換、Phase 5 執行;原〈Suggested direction〉標「已裁決」保留)。新 todo `2026-11-07-phase-5-scope-decisions.md`(`target: Phase 5`、`area: verification`,標明勿重議;SC 文字對照 ROADMAP Phase 5 Success Criteria 原文)。

**3g UI-SPEC(文案逐字取自 `vue-app/src/i18n.ts`)**
- 04.2:除權日列 max / 預設改為標的市場今天(TradingDay 對照,**未知標的 UTC**,依 E2 補充,不是計畫原文的紐約),市場來源寫明「從持倉開啟用 `HoldingDto.market` + `assetType`,不另打 `/assets`;typeahead 未選定 → UTC」;`holdingEventMarketDate` 與五個時區標籤列;paidAt / effectiveDate 的 Required / TooEarly 列,`effectiveDate` 列說明補「空白 / 過早依目前輸入判斷,未來與時序仍共用」;U-07 補 2026-11-07 修訂;前端自檢列補三態;色彩段新增 `--accent-text` / `--success` / `--on-success` 與 `--fg-mute` 新值、result ✓ 與即時圓點改 `--success`、★ 改 `--accent-text`(E3);對話框主按鈕 36px / `8px 16px`;CSV 公式中和(IN-05);選擇器脫離錨點保留位置、`fallbackFocus` / `markets-table` 焦點落點(WR-08)、建立並加入固定送出當下代號(WR-10)、上限只計可見項目(WR-03);`connecting` 列補 WELCOME 10 秒逾時(IN-07);testid 表加 `holding-event-market-date`、`markets-table`。
- 04.1:`cashErrOccurredAtTooEarly` 列 + occurredAt 三態歸類說明(以 instant 比較);前端自檢列;主按鈕 36px;result ✓ 改 `--success`;`--fg-mute` 新值註記;`< 400px` 內距覆寫已刪除的註記。
- 04:executedAt 列改為 i18n 實際文案(含股數調整時序那句,並註明原 Phase 4 文案),新增 `tradeErrExecutedAtRequired` / `tradeErrExecutedAtTooEarly` 兩列與三態說明(未來 5 分鐘以上的後端錯誤仍無 `fields`);D-04 相關四處(:387、:430、:568、:727)加註「訂單類型與有效時間在兩種模式都已移除;mock 保留的只剩交易後現金與通知推送」;testid 表加 `ticket-form-error`;另兩處既有漂移加註(見 §6 偏離 3)。

**3h 04.2 REVIEW-DISPOSITION**:WR-01、WR-03、IN-01 ~ IN-04(後端)與 WR-08、WR-09、WR-10、IN-05 ~ IN-09(前端)全部改為 `fixed`,Source 寫 `quick-261107-1j4:<repo> <RED>→<GREEN>:<做法>`,IT 待 CI 的列明類別#方法。WR-09 Source 寫明「依 Yuan 裁決先改 04.2 UI-SPEC U-07(970c9bd)」與持倉市場 follow-up:後端 `aae1dc2`→`50b0c0e`(HoldingDto.market、持倉快取鍵 v3,TradingApiIT 待 CI,契約 `98d4851`)、前端 `8d4a84c`→`91c4d1c`(改用持倉 market、移除額外 searchAssets、未知市場 UTC)。frontmatter `open: 12 → 0`,20 列全為 fixed;finding id / title / 說明段落與 `04.2-REVIEW.md` 的 20 個 `### WR-NN:` / `### IN-NN:` 標題未動。

**3i 前端 LEARNINGS**:2026-07-07 第 2 條狀態改為 `resolved(2026-07-19 該節已改寫為後端信封;Yuan 2026-11-07 核准標註)`。另依 Task 2 報告「LEARNINGS 候選,由 Task 3 決定」追加 4 條 2026-11-07 測試坑(LEARNINGS 規則允許模型自行 append):`cleanupMounted` 清掉 mock 預設 stubEnv 與 adapter spy 順序、假計時器下 `flushAsync` 只跑 microtask、原始碼 CSS 斷言的同名規則與 jsdom rect、測「不呼叫端點」要路由該端點。

## 4. 驗證閘門(cwd = 後端根)

| 閘門 | 結果 |
|---|---|
| G1 task-briefs / maintenance-protocol / PROJECT.md 無 `vue/stock-v2`;judgment.md 有 `vue/stock-v2` 與 `stock-web-v2-front-end` | PASS |
| G2 maintenance-protocol 無 `git add -f`;judgment / task-briefs 有 `npm run lint`;git-convention 有 `quick-YYMMDD-xxx` | PASS |
| G3 三條新 todo 存在;hardening todo 含 `Dependabot` 與 `user_permissions` | PASS |
| G4 security.md 無 `\| Phase 1 \| Dependabot`;security.md / architecture.md 都指向 hardening todo | PASS |
| G5 it-shared-containers `^target: Phase 5`;cash-amount 含 `decimal`;asset-classification 無 `vue/stock-v2`;四條 2026-09-28 todo 含 `v1.0` | PASS |
| G6 REVIEW-DISPOSITION `^open: 0` 且無 `disposition: (open\|deferred)` | PASS |
| G7 04.2 UI-SPEC 無「本機日期」且有 `holdingEventMarketDate`;04.1 有 `cashErrOccurredAtTooEarly`;04 有 `tradeErrExecutedAtTooEarly` | PASS |
| G8 前端 AGENTS.md ≤ 120 行(47)、LEARNINGS 有 `resolved`、AGENTS.md 有 `§8`、無 `../../java/stock-web-v2/ai-docs` | PASS |
| G9 所有 `^docs(quick-261107-1j4)` commit 只含 `.md` | **FAIL(非 Task 3 造成,見下)** |
| 「只動 .md」複核:`git diff --name-only T3_BASE..HEAD \| grep -vE '\.md$'` | 後端 **0** 行、前端 **0** 行 → PASS |

**G9 失敗原因**:閘門以 commit 訊息 grep 全部 `docs(quick-261107-1j4)` commit,範圍包含 Task 1 的兩個 docs commit,它們本來就含 `.java`(都只改 Javadoc,屬計畫允許的 Task 1 程式範圍):
- `c530345`(IN-01 Javadoc 更正):`JdbcCashLedgerRepository.java`、`JdbcDividendRepository.java`、`JdbcShareAdjustmentRepository.java`
- `98d4851`(E1 契約 docs commit):`HoldingDto.java`(Javadoc `@param market`)

Task 3 自己的 11 個 commit 都只含 `.md`(上表最後一列以 T3_BASE 量測,這也是計畫在 G9 註解寫的「收尾另以 T3_BASE_BE / T3_BASE_FE 做 git diff 複核」)。不能 amend / rebase,所以照實回報;建議 SUMMARY 把 G9 記為「閘門範圍過寬,以 T3_BASE 複核取代」。

沒有跑 `npm` / `mvnw`:本 task 只改 `.md`,沒有程式或設定改動。

## 5. 給 orchestrator 的 STATE.md 建議修改(我未改 STATE.md)

1. **前端路徑(約 :254)**:`- 前端 commits(\`../../vue/stock-v2\`):…` 改為 `- 前端 commits(前端 repo 根,路徑定義見 \`ai-docs/judgment.md\` §8):…`(其餘 hash 不動)。
2. **Pending Todos**:`- 7 pending` 改為 `- 10 pending`,並在括號註記補「2026-11-07 quick-261107-1j4 新增 3 條:`account-and-security-hardening`(backlog)、`ops-and-ai-access-backend`(backlog)、`phase-5-scope-decisions`(Phase 5 discuss 輸入)」。Deferred / backlog 那行建議改寫為:
   - Phase 5:`it-shared-containers`、`cash-amount-string-serialization`、`phase-5-scope-decisions`
   - v1.0 之後新 milestone:`account-level-return`(第一個 phase);「真實行情與參考資料來源」主題:`fx-pnl-and-rate-history`、`kline-adjusted-prices`、`asset-master-management`
   - 隨 Analytics API 化:`backend-asset-classification`(只剩 Analytics 產業分布)
   - backlog:`account-and-security-hardening`、`ops-and-ai-access-backend`
   (`ls .planning/todos/pending | wc -l` = 10)
3. **Blockers/Concerns**:`[Phase 04.2] 04.2-REVIEW 仍有 12 條建議性 finding open(…)` 一條改為「04.2-REVIEW 20 條 finding 全部 fixed、open 0(WR-02 / WR-04 由 quick 261106-fbs;WR-01 / 03 / 08 / 09 / 10、IN-01 ~ IN-09 由 quick 261107-1j4),見 04.2-REVIEW-DISPOSITION.md」,或直接移除。
4. **Decisions** 中 `[Phase 04.2]: code review … WR-09 … 與 WR-01 … Yuan 同意延後,已列入 PROJECT.md Active` 建議加註「2026-11-07 已由 quick 261107-1j4 修正,PROJECT.md 已移到 Validated」。
5. **Quick Tasks Completed** 表新增一列:`261107-1j4 | 小型修補合併批次 B:04.2 review 收尾(WR-01/03/08/09/10、IN-01~09)、HoldingDto.market、UI token(--accent-text / --success / --fg-mute / 對話框 36px)、1900 下限、OrderTicket 移除訂單類型 / TIF、Yuan 2026-11-07 文件與 todo 裁決 | 2026-11-07 | <最終 commit> | [261107-1j4-small-fixes-batch-b-04-2-review-closeout](./quick/261107-1j4-small-fixes-batch-b-04-2-review-closeout/)`。
6. **Next action / Session Continuity**:改為「/gsd-map-codebase → /gsd-docs-update → Phase 5 discuss(輸入:`.planning/todos/pending/2026-11-07-phase-5-scope-decisions.md`)」。
7. Blockers 第一條「Confirm exact frontend package scripts in sibling repo during Phase 5 planning」可補一句:前端驗證命令已定義於 `ai-docs/judgment.md` §8(含 `npm run lint`)。

**ROADMAP.md 約 :100 不改的理由**:那是 Phase 4 的 2026-07-26 scope note,記錄當時兩個 repo 的狀態(`vue/stock-v2`、`develop @ a03e030`、V10 migration 字樣),屬已完成 phase 的歷史敘述;改寫會讓歷史紀錄與當時的 commit / PR 對不上(同 STATE.md「V10 字樣不要改」的原則)。且 ROADMAP 是 orchestrator 專屬。

## 6. 偏離計畫(Deviations)

1. **[Addendum 優先] 04.2 UI-SPEC 的未知標的 fallback 寫 UTC,不是計畫 3g 原文的「未知標的以紐約日期」**:orchestrator addendum E2 補充已把 fallback 改為 UTC(與後端 `TradingDay.of` 一致),程式(`91c4d1c`)也是 UTC。依指示文件反映 E2。
2. **[Rule 2 — 文件一致性] PROJECT.md Active 的 04.2 WR-09 / WR-01 兩條移到 Validated**:計畫沒點名,但兩條已修正,留在 Active 會讓 `/gsd-docs-update` 產出錯誤的待辦;同時把 PROJECT.md 的 `Last updated` 改為 2026-11-07。PROJECT.md 有改動 → 依維護協議需跑 `/gsd-docs-update`(本來就是下一步)。
3. **[Rule 2 — 文件一致性] 04-UI-SPEC 兩處既有漂移加註**:`.btn-accent` 間距表(第 23 列寫 `8px 24px / 44px`,現行 OrderTicket 是 `8px 16px / 36px`)與 Semantic up 列(「已記錄」成功標記現為 `--success`)。只加註,不改原值。
4. **[Rule 2] 04.2 / 04.1 UI-SPEC 一併更新 token、按鈕、testid、IN-05 / IN-07 / WR-03 / WR-08 / WR-10 的敘述**:計畫 3g 只點名除權日、文案列與 U-07,但 task2-report「給 Task 3 記進 UI-SPEC」一節列了這些,且 done 條件要求 UI-SPEC 與程式一致。
5. **[Task 2 交付] 前端 LEARNINGS 追加 4 條**:計畫 3i 只要求標 resolved;Task 2 報告把 LEARNINGS 候選交給 Task 3 決定。LEARNINGS 檔頭允許模型自行 append。
6. **git-convention 的新規則用英文寫**:計畫給的是中文措辭,但該檔全英文,改用英文保持一致,語意相同(G2 的 `quick-YYMMDD-xxx` 字樣存在)。
7. **G9 閘門失敗**:見 §4,非 Task 3 commit 造成,以 T3_BASE diff 複核取代。
8. Phase 5 todo 的 SC1–SC5 敘述改為直接引用 ROADMAP Phase 5 Success Criteria 的原文,避免我自行詮釋 Yuan 的裁決;DB 計數只寫「影響分析已在 2026-11-07 討論中回答,裁決為不改」,沒有補寫分析內容(scratchpad 裁決檔沒有分析全文)。

## 7. 需要 Yuan 本機處理 / 決定的事

1. **本機 `.git/info/exclude` 移除 `/ai-docs/` 那一行**(3C-3 的最後一步,`.git/info/exclude` 是 Yuan 專屬,模型不能改)。不移除的話,Yuan 本機新增的 ai-docs 檔案仍會默默不被追蹤,而規則已不再提醒 `git add -f`。驗證:新增一個 ai-docs 檔後 `git status` 看得到它。
2. **`judgment.md` §8 的路徑表是唯一定義處**:Yuan 本機 `../../vue/stock-v2` / `../../java/stock-web-v2` 是沿用原文件的值,請確認本機實際目錄仍是這兩個。
3. **後端 `TradingDay.java` 檔頭是否加「前端 `marketDate.ts` 需同步」反向註記**(task2-report 交給 Task 3[BE] 決定):Task 3 只能改 `.md`,沒有做;如需要,可在之後的程式 commit 加一行註解。目前前端 `marketDate.ts` 檔頭已寫明鏡像 `TradingDay.of`,04.2 UI-SPEC 也寫了對照。
4. 前三份 task 報告「給 Yuan 的觀察」中仍未處理、可能要排 todo 的:executedAt「未來」錯誤沒有 `fields`;IN-04 DB CHECK 未擴充(需 V20);Ops `.ldot` 失敗燈在紅漲模式變綠(可另立 `--danger`);字面色底(`.pill.buy` 等)紅漲不對調;CashEntryDialog / OrderTicket 的「未來時間」判斷未套 `useNow`。本 task 沒有替它們建 todo(計畫未要求),由 Yuan 決定是否記錄。
