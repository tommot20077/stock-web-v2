---
created: 2026-07-26
title: 導入 Spotless(格式)與 JaCoCo(覆蓋率)+ CI lint stage
area: build
files:
  - pom.xml
  - .github/workflows/ci.yml
---

## Problem

專案目前沒有自動化的格式檢查與覆蓋率量測:

| 工具 | 現況 | 查證 |
|---|---|---|
| Spotless | **未導入** | `git grep spotless origin/develop -- '*/pom.xml'` 無結果 |
| JaCoCo | **未導入** | 同上,`jacoco` 無結果 |
| CI lint stage | **無** | `.github/workflows/ci.yml` 只有 unit / integration / e2e / browser-e2e |

實務影響:

- 格式一致性全靠人工與 review。`ai-docs/code-standards.md` 有規範但沒有機器把關,
  違規只能靠 reviewer 抓——而本 repo 的 review 已經證明會漏(見 `LESSONS.md` 2026-07-26 數條)。
- 沒有覆蓋率數字,「補了測試」這件事無法量化,也看不出哪些新增程式碼完全沒被覆蓋。

## Solution

參考實作在本地 ref `archive/fullstack-review-q5nvfj`(commit `6473bff`,原
`claude/fullstack-review-architecture-q5nvfj` 分支,遠端已於 2026-07-26 刪除)。

**注意該參考實作是 2026-05-24 分岔、2026-07-02 的版本,develop 之後前進了 134 個 commit,
所以只能當設計參考,不要直接 cherry-pick。** 落地時至少要重新確認:

- [ ] Spotless 的格式規則要與 `ai-docs/code-standards.md` 現行內容一致(縮排、import 順序、
      行寬)。**第一次套用會動到大量既有檔案**——建議獨立一個「只有格式」的 commit,
      不要和邏輯變更混在一起,否則後續 `git blame` 全毀。
- [ ] JaCoCo 的 `check` 門檻要設多少?一開始就設高會讓 CI 立刻紅。建議先只「報告不阻擋」,
      量出現況基線後再決定門檻。
- [ ] 多模組專案的 JaCoCo 聚合報告需要額外設定(`report-aggregate`),否則只會得到 10 份
      各自獨立的報告。
- [ ] CI 的 lint job 要放在 unit 之前(快速失敗)還是並行?本 repo 的 `browser-e2e` 已有
      `needs:` 鏈,新 job 要接進去。

## Scheduling

TBD。不阻擋任何功能開發,但越晚導入,第一次 Spotless 套用的 diff 就越大。
