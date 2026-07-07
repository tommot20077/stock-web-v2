# LESSONS — 非 bug 的踩雷教訓

> 一行一課。格式:`日期 | 教訓(一句) | 證據 file:line | 狀態(new/recurred/promoted)`
> 同一個坑第 2 次出現 → 標 recurred 並依 `ai-docs/maintenance-protocol.md` §2 晉升。
> 模型可自行 append;修改/刪除既有條目要先問 Yuan。

| 日期 | 教訓 | 證據 | 狀態 |
|------|------|------|------|
| 2026-07-07 | 新增 `ai-docs/` 檔案必須 `git add -f`,否則被 `.git/info/exclude` 默默排除、不進版控 | `.git/info/exclude:13` | promoted(maintenance-protocol §4)|
| 2026-07-07 | Claude Code 只自動載入 CLAUDE.md,不載 AGENTS.md;「寫進 AGENTS.md」≠「Claude 看得到」 | 官方 memory 文件(code.claude.com/docs/en/memory)| promoted(maintenance-protocol §6)|
| 2026-07-07 | PROJECT.md 的 `## Key Decisions` 不被 GSD 抽進 AGENTS.md,鐵律要放 `## Constraints` | gsd-core generate-claude-md 行為(institution-authoring-prompt.md 查證記錄)| promoted(maintenance-protocol §2)|
