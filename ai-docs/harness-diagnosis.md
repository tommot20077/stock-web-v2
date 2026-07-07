# Harness 快速診斷(2026-07-07 快照)

> 本次制度建立時的診斷:這個 harness 最漏 token、最易失焦、最易出錯的前三名。
> 每條附本 repo 自己的證據與修法。修法多數已在同日的制度 PR 落地,標註於各條。

## 第 1 名(最易失焦):常載路由層與真制度斷線

**問題**:GSD 把 446 行專案地圖(project constraints、conventions、architecture)縫進 `AGENTS.md`,但 **Claude Code 不讀 AGENTS.md**(官方 memory 文件明載只讀 CLAUDE.md);而 CLAUDE.md(37 行)也沒有指向 AGENTS.md 或 `.planning/`。結果:Claude session 冷啟動看不到 PROJECT.md Constraints(含「交易=手動成交紀錄」「cookie auth 必先 CSRF」這些鐵律)。

**證據**:`AGENTS.md:43`(`<!-- GSD:project-start -->` 標記)、舊版 CLAUDE.md 全文無 AGENTS/.planning 字樣、`.planning/config.json` 無 claude_md 組裝設定。

**修法(已落地)**:CLAUDE.md 重寫為薄路由,以純文字路徑指向 AGENTS.md/`.planning`/新制度檔;PROJECT.md Constraints 加路由 bullet 讓 AGENTS.md 側也接上線。

## 第 2 名(最易出錯):治理檔是本機孤本,git 備份假設失效

**問題**:`.git/info/exclude` 排除了 `/CLAUDE.md`、`/ai-docs/`、`/.claude/`、`/docs/plans/`——整個手寫治理層(含 383 行 security.md)不在版控,無歷史、無備份、換機即丟;且**新增 ai-docs 檔案會默默不被追蹤**(除非 `git add -f`),弱模型幾乎必踩。

**證據**:`.git/info/exclude:9-15`;`git ls-files ai-docs/` 過去只回 `browser-auth-contract.md` 一檔。

**修法(已落地)**:制度檔一律 `git add -f` 進版控(Yuan 2026-07-07 拍板);維護協議 §4/§5 明文寫入 add -f 規則與 `git ls-files` 驗證;CLAUDE.md 維持本機是 Yuan 的決定,風險已知(見 letter-to-future-sessions.md)。

## 第 3 名(最漏 token + 學習空轉):學習機制三套並存、金礦埋沒、巨檔誘讀

**問題**:
- `ai-docs/bug-reports/` 建好但 0 筆(INDEX.md 統計全 0),`post-bug` skill 沒進入日常。
- 361 行的 `.planning/research/PITFALLS.md` 精準預言了整合風險(如風險 4「API mode 靜默退回 mock」),但躺在 research/ 沒有任何常載路由指過去——等於沒寫。
- `docs/superpowers/plans/` 有 3193 行的實作計畫巨檔,session 誤讀全文一次就是幾萬 token;AGENTS.md 446 行對讀它的 harness 每 session 全載,其中大量與 ai-docs 重複。

**證據**:`ai-docs/bug-reports/INDEX.md`(Total 0)、`.planning/research/PITFALLS.md:83-105`(風險 4)、`docs/superpowers/plans/2026-05-16-stock-foundation-implementation-plan.md`(3193 行)。

**修法(已落地)**:PITFALLS 精華晉升進 `judgment.md`(§1-5);維護協議把 LESSONS/bug-reports 定為唯一學習家並給晉升路;CLAUDE.md 對深檔一律純文字指標並標註「查針對段落,勿全文讀」。

## 次要發現(未修,列給 Yuan)

1. **pre-commit 測試 hook 可能沒生效**:`.claude/hooks/pre-commit-test.sh` 存在,但 `.claude/settings.local.json` 沒有 `hooks` 註冊欄位。**未確認**(可能註冊在使用者層 `~/.claude/settings.json`)。驗證法:随便 commit 一次看 hook 有沒有跑;或查使用者層 settings。若沒生效,「commit 前必跑測試」的假設是假的。
2. **`.mcp.json` 本機明文憑證**(GitHub PAT、Postgres/ES 密碼):未進 git(exclude 擋住,已驗證 `git ls-files` 為空),但建議改用環境變數引用,降低本機外洩面。
3. **雙契約信封不一致**:前端 repo `docs/api-contracts/mock-to-real-contract.md` 的 `{data, requestId}` vs 後端 `ApiResponse<T>`。裁決規則已寫入 `judgment.md` §4,但文件本身尚未對齊——建議排進 phase 工作。
4. **PROJECT.md 的 `## Key Decisions` 有 6 條決策,GSD 不抽取**:那些決策在 AGENTS.md 上不可見。鐵律級的已由本次制度收進 Constraints/judgment;其餘維持原位(它們是 milestone 決策記錄,非常載需求)。
