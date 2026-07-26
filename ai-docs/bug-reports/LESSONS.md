# LESSONS — 非 bug 的踩雷教訓

> 一行一課。格式:`日期 | 教訓(一句) | 證據 file:line | 狀態(new/recurred/promoted)`
> 同一個坑第 2 次出現 → 標 recurred 並依 `ai-docs/maintenance-protocol.md` §2 晉升。
> 模型可自行 append;修改/刪除既有條目要先問 Yuan。

| 日期 | 教訓 | 證據 | 狀態 |
|------|------|------|------|
| 2026-07-07 | 新增 `ai-docs/` 檔案必須 `git add -f`,否則被 `.git/info/exclude` 默默排除、不進版控 | `.git/info/exclude:13` | promoted(maintenance-protocol §4)|
| 2026-07-07 | Claude Code 只自動載入 CLAUDE.md,不載 AGENTS.md;「寫進 AGENTS.md」≠「Claude 看得到」 | 官方 memory 文件(code.claude.com/docs/en/memory)| promoted(maintenance-protocol §6)|
| 2026-07-07 | PROJECT.md 的 `## Key Decisions` 不被 GSD 抽進 AGENTS.md,鐵律要放 `## Constraints` | gsd-core generate-claude-md 行為(查證記錄:`D:\end\institution-authoring-prompt.md`,workspace 外)| promoted(maintenance-protocol §2)|
| 2026-07-07 | 從 GSD 生成檔複製的相對路徑別直接信:AGENTS.md 寫 `../vue/stock-v2`,實際是 `../../vue/stock-v2`;引用路徑前先 `ls` 驗證 | 對抗審查 B1(6 處錯一層)| promoted(本 PR 已修)|
| 2026-07-07 | `security.md` §8 曾殘留「CSRF 全面停用」舊說法,Phase 01 後實為雙軌;文件與 SecurityConfig 不一致時以程式碼為準 | `SecurityConfig` 的 `BrowserCsrfFilter` vs 舊 `security.md:163` | promoted(本 PR 已修 §8)|
| 2026-07-26 | 驗證編譯只 grep `ERROR`/`BUILD` 而不看 `warning:` = 半盲。20 處用了 `@Deprecated` 的 `ObjectUtils.defaultIfNull` 全程無感,還把它的「取捨」寫進 PR 描述 | `javap -v` 確認 3.19.0 的 `defaultIfNull(T,T)` 帶 `Deprecated: true`;修正見 commit `3b44fe2` | new |
| 2026-07-26 | 用自己的 regex 證明自己重構「殘留 0」是循環論證;該量尺漏掉 5 處 null-or-blank + 3 處預設值三元 | PR #14 宣稱殘留 0,`3b44fe2` 補齊 8 處 | new |
| 2026-07-26 | 機械式模式替換時只問「符不符合新寫法」不問「這個值對嗎」:把 `ip == null ? "unknown" : ip` 換成 `defaultIfNull(ip, "unknown")`,保留了應改為 `ClientIpResolver.UNKNOWN` 的硬編重複(兩者分歧會使 per-IP 連線上限靜默失效)—— 同一批才剛編輯過該常數所在檔 | `WebSocketConnectionManager:88` vs `ClientIpResolver.UNKNOWN`;修正見 `3b44fe2` | new |
| 2026-07-26 | 看見 N 份內容相同的 helper 卻只改其內部實作 = 修在錯的高度。11 份重複的 `meta()`(9 個 controller + `SecurityConfig` + `GlobalExceptionHandler`),正解是抽出 `ApiMetaFactory` 而非逐份換三元式 | PR #14 只改三元;`302d15c` 抽 factory,12 檔(含新增的 factory)−128 行 | new |
| 2026-07-26 | 引第三方工具類前先查該 API 是否已 deprecated / JDK 是否已有同功能:`Objects.requireNonNullElse(Get)` 是 JDK 內建且無相依成本,優於 commons-lang3 的 `defaultIfNull`/`getIfNull` | `code-standards.md` 已更新此裁決 | promoted(code-standards)|
