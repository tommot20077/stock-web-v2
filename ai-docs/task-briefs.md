# 任務交辦範本(Task Briefs)

> 交辦 subagent 時複製對應範本填空。三要素缺一不發:目標與動機、驗收條件、回報格式。
> 對齊 superpowers SDD 的 brief→report 慣例。回報一律「結論 + file:line 證據」,長產物存檔傳路徑。

## 本專案固定驗證命令(填進驗收條件用)

- Backend 全量:`./mvnw test`(focused:`./mvnw -pl <module> -am test`;IT:`./mvnw -pl stock-start -am verify`)
- Frontend:`cd ../../vue/stock-v2/vue-app && npm test && npm run build`
- Frontend API mode:上述命令前加 `VITE_DATA_MODE=api`
- ⚠ 以上為 Git Bash 語法;Windows PowerShell 5.1 不支援 `&&` 與 `VAR=` 前綴——PS 下改用 `mvnw.cmd`、分步執行、`$env:VITE_DATA_MODE='api'`

---

## 1. 搜尋/盤點(Search)

```
目標與動機:找出 <什麼>,因為 <主線要拿它做什麼決定>。
範圍:<目錄/模組>;排除 <目錄>。
驗收條件:
- 每個發現附 file:line。
- 明列「查過但沒有」的地方(證明覆蓋面,防漏報)。
回報格式:結論一段 + 發現清單(file:line + 一句說明)。不貼檔案全文。
```

## 2. 實作(Implement)

```
目標與動機:實作 <行為>,因為 <需求/phase 引用>。
邊界:只動 <檔案/模組>;不碰 SecurityConfig / GlobalExceptionHandler / 契約 shape(要動先回報)。
規範:遵守 ai-docs/code-standards.md、testing-standards.md;TDD(先紅後綠;紅的輸出要真的看到)。
驗收條件:
- <具體測試名/命令> 綠。
- 測試輸出乾淨(pristine),除非測錯誤處理。
- JavaDoc 繁中(類別含描述/作者/版本)。
回報格式:改了哪些檔(file:line)、測試命令與結果摘要、殘留風險。
```

## 3. 重構(Refactor)

```
目標與動機:重構 <目標>,因為 <可讀性/邊界/重複>。行為不得改變。
前置:先確認現有測試綠(貼命令輸出);測試不綠先停,回報。
邊界:不改公開 API 與測試斷言;模組邊界規則見 ai-docs/architecture.md 與 ArchUnit 規則。
驗收條件:重構前後同一組測試都綠;diff 不含行為變更。
回報格式:重構清單(每項:動機→做法→file:line)、測試前後結果。
```

## 4. 研究/查證(Research)

```
目標與動機:回答 <問題>,因為 <哪個決策卡在這>。
方法:優先讀本 repo 一手材料(引 file:line);外部資料要附來源 URL 與日期。
驗收條件:
- 每個結論標信心(HIGH/MEDIUM/LOW)與依據。
- 查不到就寫「未查到」,不推測、不編造(尤其型號/參數/計費)。
回報格式:問題→答案→證據→未確認事項。
```

## 5. 審查(Review)

```
目標與動機:審查 <diff/檔案>,聚焦 <正確性/安全/契約一致性>。
必查清單(本專案特有):
- SQL 是否有字串拼接(禁止;LIKE 要 LikeEscapeUtil.escape)。
- @ExceptionHandler 是否回 ResponseEntity(直接回 ApiResponse = 永遠 200)。
- ownership 檢查在 Service 層且失敗丟 ResourceNotFoundException(不是 403)。註:`SecurityUtils`/`LikeEscapeUtil` 是規範類別,先 grep 確認存在;不存在時檢查項改為「有沒有等價檢查」。
- 瀏覽器 auth:token 不落 JS 可讀處;unsafe 方法帶 CSRF。
- 信封:ApiResponse<T> 為權威(見 ai-docs/judgment.md §4)。
- 交易寫入是否 server-side 冪等。
驗收條件:每個 finding 附 file:line + 失敗情境(什麼輸入會炸);無 finding 也要說查了哪些面向。
回報格式:finding 依嚴重度排序;每項:一句結論 + 證據 + 建議修法。
```
