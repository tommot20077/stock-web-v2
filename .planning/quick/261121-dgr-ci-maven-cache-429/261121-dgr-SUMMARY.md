---
phase: quick-261121-dgr
plan: 261121-dgr
subsystem: infra
tags: [ci, github-actions, maven, actions-cache, maven-resolver, rate-limit]

requires: []
provides:
  - "後端 CI deps job:Maven 依賴快取的唯一寫入者(lookup-only 探測、restore-keys 補差額、預熱成功才 save)"
  - "後端 lint / unit / integration / e2e / browser-e2e 以 needs.deps.outputs.m2-key 唯讀還原"
  - "後端 .mvn/maven.config:resolver 對 429/503 重試 10 次"
  - "前端 Browser E2E:單一 actions/cache@v4(repository + wrapper、restore-keys、success-only 存檔)"
affects: [ci, browser-e2e]

actuals:
  tokens: 3800       # chars/4 over 兩 repo 實際 diff(後端 11547 + 前端 3699 bytes)
  tasks: 3
  commits: 3         # 後端 2(697e1fa..76bb690)+ 前端 1(0783da9..988e489)
plan_head_before:
  backend: 697e1fa   # 規劃 commit;plan 的 planning_heads 18dd1cc 之後只多了 PLAN.md 的 docs commit
  frontend: 0783da9
plan_head_after:
  backend: 76bb690b1f12cd6c9bf23c523a526df7e32540f9
  frontend: 988e48920a085548e4933d332e99d9cf76a50dea

tech-stack:
  added: []
  patterns:
    - "多 job 共用依賴快取：單一寫入者 job(先預熱、成功才 actions/cache/save),其他 job 用 actions/cache/restore 唯讀，key 由 job output 傳遞"

key-files:
  created:
    - /home/user/stock-web-v2/.mvn/maven.config
  modified:
    - /home/user/stock-web-v2/.github/workflows/ci.yml
    - /home/user/stock-web-v2/ai-docs/bug-reports/LESSONS.md
    - /home/user/stock-web-v2-front-end/.github/workflows/ci.yml
    - /home/user/stock-web-v2-front-end/docs/LEARNINGS.md

key-decisions:
  - "後端 deps job 為 Maven 快取唯一寫入者;下游 5 個 job 一律 needs deps 並唯讀還原(依計畫)"
  - "前端不加 deps job,只把 setup-java 內建快取 + wrapper 快取合併為 actions/cache@v4 加 restore-keys(依計畫)"

patterns-established:
  - "Maven 快取 path 字串在所有 restore / save 步驟逐字相同(~/.m2/repository + ~/.m2/wrapper,block scalar |)"

requirements-completed: [quick-261121-dgr]

duration: 5min
completed: 2026-11-21
status: complete
---

# Quick 261121-dgr: CI Maven 快取 / Maven Central 429 修正 Summary

**後端 CI 新增 deps job 當 Maven 快取唯一寫入者(lookup-only 探測、restore-keys 補差額、預熱成功才 save),其餘 5 個 Maven job 以 deps output key 唯讀還原;`.mvn/maven.config` 把 resolver 429/503 重試調為 10 次;前端 Browser E2E 改用帶 restore-keys、僅成功才存的 actions/cache@v4。**

## Performance

- **Duration:** 約 5 min
- **Started:** 2026-11-21T09:58:09Z
- **Completed:** 2026-11-21T10:02:25Z(不含 SUMMARY 撰寫)
- **Tasks:** 3/3
- **Files modified:** 5(後端 3、前端 2)

## Accomplishments

- 後端 `ci.yml` 新增 `deps`(Resolve Maven Dependencies)job:精確 key 命中則其餘步驟全部 skip;未命中時 restore-keys 還原舊快取 → 帶重試下載 Maven 本體 → `verify -DskipTests -Djacoco.skip=true` → `-pl stock-common test -Djacoco.skip=true` → `actions/cache/save@v4`(隱含 success())。
- 後端 lint / unit / integration / e2e / browser-e2e 移除 setup-java `cache: maven` 與舊 wrapper 快取，改為 `Restore Maven cache`(id `m2-cache`、`actions/cache/restore@v4`、key `${{ needs.deps.outputs.m2-key }}`、restore-keys `m2-${{ runner.os }}-`);needs 分別為 `deps`、`deps`、`[deps, unit]`、`[deps, integration]`、`[deps, e2e]`。
- 後端 `.mvn/maven.config` 單行 `-Daether.connector.http.retryHandler.count=10`;`help:evaluate` 不帶 -D 讀到 `10`。
- 前端 browser-e2e:setup-java 只留 distribution / java-version;`Cache Maven repository and wrapper`(id `m2-cache`、`actions/cache@v4`、restore-keys);frontend job 與 0783da9 完全相同。
- 兩個 lessons 檔各 append 一行 2026-11-21 條目。

## Task Commits

1. **Task 1(tracer,backend):maven.config + deps job + Unit 唯讀還原** — backend `a210737` `ci(quick-261121-dgr): 新增 deps job 預熱 Maven 快取並讓 Unit Tests 唯讀還原`
2. **Task 2(backend):其餘 4 個 Maven job 唯讀還原 + LESSONS** — backend `76bb690` `ci(quick-261121-dgr): 其餘 Maven job 改讀 deps 快取並移除 setup-java 內建快取`
3. **Task 3(frontend):Browser E2E actions/cache@v4 + LEARNINGS** — frontend `988e489` `ci(quick-261121-dgr): Browser E2E 的 Maven 快取改用 actions/cache 並加 restore-keys`

皆未 push、未開 PR;SUMMARY 未 commit(由 orchestrator 處理)。

## 閘門輸出(本機)

- Task 1:`BACKEND TRACER GATE OK`;`help:evaluate` = `10`;兩個預熱命令離線成功 → `MAVEN SMOKE OK`。Tracer 回饋閘門(auto)重跑通過後才擴展到 Task 2。
- Task 2:`BACKEND CI GATE OK`;`LESSONS OK`(LESSONS.md 對 18dd1cc numstat 為 `1 0`)。
- Task 3:`FRONTEND CI GATE OK`;`npm run lint`(0 errors、38 個既有 warnings,未新增)、`npm test`(70 files / 1599 tests passed)、`npm run build`(成功，僅既有 chunk size 警告)→ `FRONTEND GATE OK`。
- 兩個 repo `git status --short` 乾淨。

## Files Created/Modified

- `/home/user/stock-web-v2/.mvn/maven.config` — resolver 重試次數 10
- `/home/user/stock-web-v2/.github/workflows/ci.yml` — deps job + 5 個唯讀 job
- `/home/user/stock-web-v2/ai-docs/bug-reports/LESSONS.md` — +1 行
- `/home/user/stock-web-v2-front-end/.github/workflows/ci.yml` — browser-e2e 快取改寫
- `/home/user/stock-web-v2-front-end/docs/LEARNINGS.md` — +1 行

## Decisions Made

None - followed plan as specified.

## Deviations from Plan

None - plan executed exactly as written.

附註(非偏差):
- `./mvnw -o` 在本機可直接用(wrapper 本體已在 `~/.m2/wrapper`),不需改用 `/opt/maven/bin/mvn -o`。
- 實際 base 是 697e1fa(18dd1cc 之上只多 PLAN.md 的 docs commit);閘門裡的 `18dd1cc` diff 仍成立。

## Issues Encountered

None.

## 待收集 CI 證據(本機無 Docker / act,由 orchestrator 推送後收集)

- 後端第一次 run:deps 的 `Look up Maven cache` 顯示 cache not found → 兩個預熱步驟成功 → `Cache saved with key: m2-Linux-<hash>`;其他 job 的 `Restore Maven cache` 顯示 `Cache restored from key: m2-Linux-<同一 hash>`(cache-hit true),`Download Maven distribution` 被 skip;Integration 不再出現 429。
- 後端第二次 run(pom 不變):deps 的 probe 顯示 `Cache found and can be restored from key`,其餘步驟全部 skipped,job 數秒內完成。
- 後端 browser-e2e(PR 事件)還原到同一個 key。
- 前端第一次 run:browser-e2e 冷下載(或 restore-keys 部分命中),job 成功後 post 顯示 `Cache saved`;第二次 run 命中。
- 已知一次性成本:feature branch / PR scope 的快取 develop 讀不到，合併後第一次 develop push 的 deps 會再冷解析一次;之後所有分支與 PR 讀 develop 快取。舊 `setup-java-Linux-x64-maven-*`、`maven-wrapper-*` 快取 7 天未用自動淘汰，要立即清可由 Yuan `gh cache delete`。
- 已知取捨:restore-keys 補差額後再 save,快取會累積舊版 artifact;日後明顯變大可刪快取重建。

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- 待 orchestrator push 兩個 repo 並在 PR 上收集上述 CI 證據;後端 CI 屬 Yuan 專屬(maintenance-protocol §4),需 Yuan 審 PR 後合併。

---
*Quick: 261121-dgr*
*Completed: 2026-11-21*

## Self-Check: PASSED

- FOUND: /home/user/stock-web-v2/.mvn/maven.config
- FOUND: backend a210737、76bb690(HEAD 祖先)
- FOUND: frontend 988e489(HEAD)
