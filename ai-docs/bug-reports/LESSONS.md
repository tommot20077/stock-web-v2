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
| 2026-07-26 | review 一個 migration 的**內容**改得對不對之前,先問「這個檔案可不可以改」。我把 PR #15 的 V9 取捨(`CONCURRENTLY` 在 Flyway 下死鎖)驗到 `pg_stat_activity` 層級,卻沒發現 V9 早已隨 PR #13 合併——改它直接違反 `flyway-convention.md:33`。判斷檔案的**可變性**要先於判斷內容的正確性 | V9 內容 +43/−4 行,checksum 必變,已套用環境會 mismatch 啟動失敗;還原後 git blob `a2530ba` 與原始逐位元相同故 checksum 必然回復。抓到的是並行 session 的 `04-RESEARCH.md:2214`,不是我 | new |
| 2026-07-26 | 引用工具的內部數值前先查它**怎麼算**。我拿 `zlib.crc32(整個檔案位元組)` 當成「Flyway checksum」寫進 PR 與 SUMMARY;反編譯 `ChecksumCalculator` 才知它是 `readLine()` 逐行累加、**不含行尾終止符**。結論(內容變則 checksum 變)沒錯,但數字拿去比對 `flyway_schema_history.checksum` 會對不上 | `javap -c` flyway-core 11.14.1:`BufferedReader.readLine → BomFilter → getBytes → CRC32.update`。副產物:CRLF/LF **不影響** Flyway checksum,`.gitattributes` 不需為此調整 | new |
| 2026-07-26 | 用 `mvn -pl <module>` 驗證跨模組改動時,**沒有 `-am` 就是在測 m2 裡的舊 JAR**。我為了實證「重號 migration 會怎樣」建了第二個 V10,測試卻 BUILD SUCCESS——因為 JAR 內 V10 檔案數是 0,Flyway 根本沒看到。改用 `install` 後才得到真實結果 `Found more than one migration with version 10`。另外 `install` 不刪已移除的資源,清理污染的 m2 要用 `clean install` | 綠燈當下先問「這次執行真的載入我改的東西了嗎」,而不是直接採信 | new |
| 2026-07-26 | Testcontainers 綠燈**證明不了** migration 的 checksum 相容性:每次都是全新 DB,沒有既存 schema history 可比對。「修改已套用的 migration」這類問題對 CI/IT 完全隱形,只會炸在長期環境 | 三個 PR 的 Unit/Integration/E2E 全綠,卻沒有任何一關能看到 V9 checksum 已變 | new |
| 2026-07-26 | GSD `phase.insert` 回 `{}` 成功但只寫 Phase Details 一處,**不寫**頂部 Phases 清單、Progress 表與 Execution Order;且 slug 會被截斷(`...watchlist` → `...watchl`)。handler 回成功 ≠ 檔案一致,插入後必須自己 grep 四處 | `3068367` 補完三處 + 改名目錄 | new |
| 2026-07-26 | GSD `state.add-roadmap-evolution` 在 CLI 一律回 `Error: ... is SDK-only`(即使照 workflow 的寫法),insert-phase workflow 的該步驟無法照做;可用 `state.add-decision --summary` 代替(但會前綴 `[Phase ?]`) | 兩種 arg 形式皆失敗;`state.add-decision` 成功 | new |
| 2026-08-16 | CLAUDE.md 的兩條驗證指令(`./mvnw test`、`./mvnw -pl stock-start -am verify`)**都不會執行 `*E2E` 類別** —— 它們掛在 `-Pe2e` profile 下。改動 HTTP 契約(新增必填 header)時,本機兩條全綠而 CI 的 E2E job 紅。找「還有誰在呼叫這個端點」要 `grep -rn 'post("/api/v1/…")' --include=*.java`,不能只信驗證指令的綠燈,也不能只信 plan 的 `files_modified` | 04-05 的 plan 只列 `TradingApiIT`;實際還有 `ValidationBoundaryE2E` 的 11 處。CI run 31926162612 的 E2E job 4 個 failure 全是 `expected:<200/404> but was:<400>` | new |
| 2026-08-16 | Spring 依**參數宣告順序**解析 handler 參數:`@Valid @RequestBody` 在 `@RequestHeader` 之前,所以 body 不合法的請求會先拋 `MethodArgumentNotValidException`。這讓「缺 header」的迴歸只在 **body 合法** 的測試上現形——`ValidationBoundaryE2E` 11 條裡只有 4 條紅,另外 7 條期待 400 的仍綠。看到「部分紅」不要推論成「部分無關」 | 紅的 4 條全是 body 合法者(note 500 字、不存在標的、quantity/price 上界) | new |
| 2026-09-02 | 前端測試的 `flushAsync` 用固定輪數 microtask 清非同步鏈，在 Node 20（CI）下不夠：undici 的 `Response.json()` 需要的 tick 比 Node 24 多，本機全綠、CI 三個頁面 7 條紅。真計時器下改做 macrotask hop（`setImmediate`；`setTimeout(0)` 在 jsdom 有巢狀 4ms 下限，11 種 code 的迴圈測試會撞 5s 逾時），假計時器下維持 microtask。本機要重現 CI 就 `fnm install 20 && fnm exec --using=20 -- node ./node_modules/vitest/vitest.mjs run` | 前端 PR #9 CI run 33641692773；`vue-app/src/testUtils.ts` | new |
| 2026-09-02 | `docker info` 只有 Client 區段、`wsl -d <distro>` 回「機器設定不支援 WSL2」= **BIOS 虛擬化被關**（`Get-ComputerInfo -Property HyperV*` → `VirtualizationFirmwareEnabled: False`）。重啟 Docker Desktop、`wsl --shutdown` 都沒用，白等 20 分鐘。先查這個屬性再決定要不要等 | 2026-09-02 session；同機器 7/26、8/16 都能跑 IT → 期間 BIOS 設定變了 | new |
| 2026-09-02 | 再踩 7/26 那條「`-pl` 沒 `-am` 測 m2 舊 JAR」：新增 `stock-common` 類別後跑 `-pl 4 modules` 得到 trading 41 個 `NoClassDefFoundError`，看起來像大規模壞掉，其實是舊 JAR。CLAUDE.md 的 focused 指令本來就寫 `-pl <module> -am`，是執行時漏掉 | `scratchpad/be-unit2.log` vs `-am` 重跑全綠 | repeat（規範已有，執行紀律問題） |
