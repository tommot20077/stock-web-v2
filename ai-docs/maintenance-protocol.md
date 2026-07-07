# 維護協議:學習存放與晉升飛輪

> 這套制度的維護規則。核心:**一個學習家、一條晉升路、常載層永遠只當薄路由**。

## 1. 學習存放:`ai-docs/bug-reports/` 是唯一的家

- **Bug 事後剖析**:走既有 `post-bug` skill → `ai-docs/bug-reports/` 個別報告 + `INDEX.md` 統計;定期 `review-bugs` 找模式。
- **非 bug 的踩雷教訓**(工具行為、流程坑、環境陷阱):append 一行到 `ai-docs/bug-reports/LESSONS.md`,格式見該檔表頭。
- `.planning/research/PITFALLS.md` 是 2026-05-30 的一次性研究快照:**唯讀**,精華已晉升進 `ai-docs/judgment.md`;新教訓不寫那裡。
- 永不新增第三套學習機制。

## 2. 晉升飛輪

```
臨時觀察 → LESSONS.md / bug report(當下就寫,一行也好)
   ↓ 同一個坑第 2 次出現,或 review-bugs 找出模式
晉升進規範:依主題進對應 ai-docs 檔(見 §3 路由表)
   ↓ 若屬「鐵律級短句」(一行講得完、違反即事故)
再晉升:.planning/PROJECT.md 的 ## Constraints 加一條 bullet
   ↓
跑 /gsd-docs-update → read-back AGENTS.md 確認該 bullet 逐字出現
```

⚠️ **PROJECT.md 只有 `## What This Is`/`## Core Value`/`## Constraints` 三節會被 GSD 逐字抽進 AGENTS.md;`## Key Decisions` 不會被抽取**——鐵律不要放 Key Decisions。

## 3. 主題→檔案路由表(晉升時查這裡,不新開檔)

| 主題 | 進哪個檔 |
|------|---------|
| 判斷/取捨/裁決規則 | `ai-docs/judgment.md` |
| Java 代碼寫法 | `ai-docs/code-standards.md` |
| 測試方法 | `ai-docs/testing-standards.md` |
| 安全/權限/auth | `ai-docs/security.md`(契約類進 `browser-auth-contract.md`)|
| 架構/模組邊界 | `ai-docs/architecture.md` |
| DB migration | `ai-docs/flyway-convention.md` |
| Kafka/事件 | `ai-docs/event-conventions.md` |
| Redis | `ai-docs/redis-convention.md` |
| Git | `ai-docs/git-convention.md` |
| 模型調度/交辦 | `ai-docs/model-dispatch.md`、`ai-docs/task-briefs.md` |
| 前端(Vue repo) | `../../vue/stock-v2/AGENTS.md` 或其 `docs/`(前端 repo 的教訓記在 `../../vue/stock-v2/docs/LEARNINGS.md`)|

## 4. 檔案所有權:誰可以改什麼

> 本表**僅適用本(後端)repo**。前端 repo 的 AGENTS.md/CLAUDE.md 是手寫、git tracked 的薄路由(不是 GSD 生成),規則見該檔表頭,不受本表「AGENTS.md 禁手改」約束。

| 檔案 | 所有權 | 規則 |
|------|--------|------|
| `AGENTS.md`、`.planning/codebase/*` | **GSD 機器管理** | 禁止手改。改來源(codebase 類跑 `/gsd-map-codebase`;project 類改 `PROJECT.md`)再 `/gsd-docs-update` 同步。`.planning/codebase/` 會被整目錄覆寫——手寫判斷永遠別放那。 |
| `CLAUDE.md`(repo root) | Yuan(本機孤本,不在 git) | 模型要改:先把現版全文與 diff 貼給 Yuan 同意。它不進版控,改壞沒有 git 可救。上限 ~150 行,只當路由。 |
| `ai-docs/*.md` | Yuan 審核,模型可提案 | 走 branch+PR 或明示同意。**新增檔案必須 `git add -f`**(`/ai-docs/` 在 `.git/info/exclude`,不強制加會默默不進版控)。 |
| `ai-docs/bug-reports/LESSONS.md`、bug reports | 模型可自行 append | 追加不需審;修改/刪除既有條目要問。 |
| `.planning/PROJECT.md` | Yuan 審核,模型可提案 | 改 Constraints 影響常載層,PR 或明示同意;改完必跑 `/gsd-docs-update`。 |
| `SecurityConfig`、`GlobalExceptionHandler`、`apiClient.ts`、契約檔 | 動前先問 | 這些是唯一邊界,見 `judgment.md` §9-10。 |
| `.git/info/exclude`、CI、`.claude/hooks` | Yuan 專屬 | 模型只回報建議。 |

## 5. 同步流程 checklist(改規範後)

1. 改的是來源檔(不是 AGENTS.md 本體)?
2. 新檔案 `git add -f` 過了?(`git ls-files <檔>` 有輸出才算)
3. 動了 PROJECT.md → 跑 `/gsd-docs-update` → read-back AGENTS.md 對應區塊。
4. CLAUDE.md 的 Guidelines Index 有沒有指到新檔?
5. 常載預算:CLAUDE.md ≤150 行(官方建議 <200);超了就把內容下放按需檔,只留一行路由。

## 6. 常載層架構(為什麼長這樣)

- **Claude Code 只自動載入 CLAUDE.md,不載 AGENTS.md**(官方 memory 文件,2026-07 查證)。AGENTS.md 是給 Codex 等其他 harness 的,由 GSD 生成維護。
- CLAUDE.md 對深檔一律用**純文字路徑**(要用才讀);`@路徑` 是開場全載,除非刻意要常載,否則不用。
- 兩邊都要接得到真制度:CLAUDE.md 直接路由 ai-docs;AGENTS.md 靠 PROJECT.md Constraints 的路由 bullet(GSD 逐字抽取)。
