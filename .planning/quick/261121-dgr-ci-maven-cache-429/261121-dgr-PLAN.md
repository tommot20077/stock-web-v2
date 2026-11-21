---
phase: quick-261121-dgr
plan: 261121-dgr
type: execute
wave: 1
depends_on: []
mode: quick
autonomous: true
requirements:
  - quick-261121-dgr   # 修正後端(與前端 Browser E2E)CI 的 Maven 依賴快取，解決 Maven Central HTTP 429
repo_roots:
  backend: /home/user/stock-web-v2              # Task 1、Task 2 的路徑皆相對此根
  frontend: /home/user/stock-web-v2-front-end   # Task 3 的路徑皆相對此根
branch: claude/epic-curie-lj8alo                # 兩個 repo 皆在此分支(已同步 develop);push / PR 由 orchestrator 做
planning_heads:
  backend: 18dd1cc                              # 規劃時 HEAD;下方閘門的 baseline 在此量測
  frontend: 0783da9
execution_topology:
  task_1: { repo: backend }
  task_2: { repo: backend, depends_on: [task_1] }   # 同一個 ci.yml,必須在 Task 1 之後
  task_3: { repo: frontend }                        # 與 Task 1/2 無檔案交集，可平行
files_modified:
  # ── backend(相對 repo_roots.backend)──
  - .mvn/maven.config                     # 新檔(Task 1)
  - .github/workflows/ci.yml              # Task 1(deps + unit)、Task 2(lint / integration / e2e / browser-e2e)
  - ai-docs/bug-reports/LESSONS.md        # Task 2,append 一行(maintenance-protocol §4:模型可自行 append)
  # ── frontend(相對 repo_roots.frontend)──
  - .github/workflows/ci.yml              # Task 3(只動 browser-e2e job)
  - docs/LEARNINGS.md                     # Task 3,append 一行

estimate:
  tokens: 45000
  raw_tokens: 45000
  tasks: 3
  confidence: low        # estimate-calibration:factor 1、sample_count 0

must_haves:
  truths:
    - "後端 CI 只有 deps job 會寫入 Maven 快取(actions/cache/save),而且只在預熱 build 成功之後才寫"
    - "後端 lint / unit / integration / e2e / browser-e2e 都 needs deps,並以 needs.deps.outputs.m2-key 唯讀還原同一個快取(path 字串逐字相同),browser-e2e 不必自己重算 backend/ 前綴的 hashFiles"
    - "deps 的預熱命令涵蓋下游 job 需要的所有 plugin 與 test provider(verify -DskipTests -Djacoco.skip=true 加 stock-common test),本機離線可成功"
    - "精確 key 命中時 deps 不下載快取、不跑 Maven(lookup-only 探測)"
    - "Maven resolver 對 HTTP 429 / 503 的重試次數由預設 3 提高到 10(.mvn/maven.config,help:evaluate 讀得到 10);前端 Browser E2E 在後端 checkout 內跑 mvnw,所以同樣生效"
    - "前端 Browser E2E 的 Maven 快取有 restore-keys 回退，而且只在 job 成功時才存(不再把解析到一半的 ~/.m2 存成精確 key)"
  artifacts:
    - path: .mvn/maven.config   # backend
      provides: "resolver 重試次數設定，單行 -Daether.connector.http.retryHandler.count=10"
    - path: .github/workflows/ci.yml   # backend
      provides: "deps job(單一寫入者)+ 5 個唯讀 Maven job"
      contains: "needs.deps.outputs.m2-key"
    - path: .github/workflows/ci.yml   # frontend
      provides: "browser-e2e 改用 actions/cache@v4 快取 ~/.m2/repository + ~/.m2/wrapper,帶 restore-keys"
      contains: "restore-keys: m2-${{ runner.os }}-"
  key_links:
    - from: "backend ci.yml deps.outputs.m2-key"
      to: "5 個下游 job 的 actions/cache/restore key"
      via: "needs.deps.outputs.m2-key(job 必須把 deps 列在 needs 才讀得到)"
      pattern: "key: \\$\\{\\{ needs.deps.outputs.m2-key \\}\\}"
    - from: "deps 的 m2-probe(lookup-only)cache-primary-key"
      to: "deps 的 actions/cache/save key"
      via: "steps.m2-probe.outputs.cache-primary-key"
      pattern: "steps.m2-probe.outputs.cache-primary-key"
    - from: "frontend vue-app/e2e/run-e2e.sh(cd $BACKEND_DIR && ./mvnw ...)"
      to: "backend .mvn/maven.config"
      via: "Maven 從 cwd 往上找 .mvn/ 目錄並讀 maven.config"
      pattern: "retryHandler.count=10"
---

<objective>
修好兩個 repo 的 Maven 依賴快取，讓 CI 不再因 Maven Central HTTP 429 在測試開始前就失敗。

**根因(已由 orchestrator 查 CI log、規劃時於本機查證):**
1. 後端每個 Maven job 都用 setup-java 的內建 Maven 快取，全部共用同一把 key(`setup-java-Linux-x64-maven-<pom hash>`),誰先跑完誰存。Lint 只跑 spotless,存下約 14 MB 的殘缺快取。之後所有 job 都精確命中這份殘缺快取，Integration 每次都要冷下載 jacoco / failsafe / test 依賴，於是撞 429(`jacoco-maven-plugin:pom:0.8.13 ... status code: 429`)。
2. setup-java 的 action.yml 沒有 `post-if`(預設 `always()`),所以 job 失敗也會存。解析到一半撞 429 的 ~/.m2 會被存成精確 key,直到 pom 改動前都不會再修正。
3. `~/.m2/wrapper` 用獨立快取，但從沒在 develop scope 存過。develop push run 44886864147 的 Unit Tests 因此在下載 Maven 本體時就被限流。repo 的 default branch 是 `develop`(兩個 repo 都已用 gh api 確認),所以 deps 在 develop 存的快取，所有 feature branch 與 PR 都讀得到。
4. Maven 3.9.14 內建 resolver 1.9.27(`lib/maven-resolver-transport-http-1.9.27.jar`),預設會對 429 / 503 重試 3 次(`DEFAULT_HTTP_RETRY_HANDLER_COUNT = 3`、`DEFAULT_HTTP_RETRY_HANDLER_SERVICE_UNAVAILABLE = "429,503"`)。有 `Retry-After` 就照它等，否則以 interval 5000ms 乘上第幾次做線性退避，上限 300000ms。CI 仍然失敗，代表 3 次不夠。

**設計(Yuan 已核准方向，細節如下):**
- 後端新增 `deps` job 當**唯一寫入者**。先用 lookup-only 查精確 key,命中就整個 job 秒過。未命中時，先用 restore-keys 還原舊快取(只補差額)、帶重試下載 Maven 本體、跑兩個預熱命令，全部成功後才 save。
- 其他 5 個 Maven job 一律 `needs: deps`,改用 actions/cache/restore 唯讀還原。key 從 `needs.deps.outputs.m2-key` 傳入，保證與 deps 逐字相同。browser-e2e 把後端 checkout 在 `backend/`,同樣直接用這個 output,不必重算前綴 hashFiles。
- `.mvn/maven.config` 把 resolver 重試次數調高到 10。不換 mirror。
- 前端 Browser E2E 改動最小:setup-java 內建快取加上獨立的 wrapper 快取，合併成一個 actions/cache@v4(`post-if: success()`)並加 restore-keys。

**所有權提醒:** 後端 `ai-docs/maintenance-protocol.md` §4 把 CI 列為 Yuan 專屬。本 quick 依 orchestrator 轉達的 Yuan 核准方向執行，只在分支上 commit、不 push,由 Yuan 在 PR 審核後合併。不得動後端 `AGENTS.md`(GSD 機器管理)與 `CLAUDE.md`。

Purpose: CI 的紅燈應該來自程式本身，不是 Maven Central 限流。快取只能由單一 job 在成功後寫入，其他 job 只讀。
Output: backend `.mvn/maven.config`(新)、backend `.github/workflows/ci.yml`、backend `ai-docs/bug-reports/LESSONS.md`(+1 行)、frontend `.github/workflows/ci.yml`、frontend `docs/LEARNINGS.md`(+1 行)。
</objective>

<execution_context>
@~/.claude/gsd-core/workflows/execute-plan.md
@~/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@/home/user/stock-web-v2/.planning/STATE.md
@/home/user/stock-web-v2/.github/workflows/ci.yml
@/home/user/stock-web-v2-front-end/.github/workflows/ci.yml
@/home/user/stock-web-v2-front-end/vue-app/e2e/run-e2e.sh
@/home/user/stock-web-v2/pom.xml
@/home/user/stock-web-v2/stock-start/pom.xml
@/home/user/stock-web-v2/.mvn/wrapper/maven-wrapper.properties

**規劃時已查證的事實(執行時不必重查，直接採用):**
- `verify -DskipTests` 不會解析 surefire / failsafe 的 test provider。用 `-X` 實測，輸出中沒有 `surefire-junit-platform`。`-pl stock-common test` 有 106 個測試、約 5 秒，會解析 `surefire-junit-platform:3.5.5`、`common-java5:3.5.5`、`junit-platform-launcher:6.0.3`。failsafe 也是 3.5.5,用同一組 provider。
- `-Djacoco.skip=true` 時,jacoco plugin realm 與 `org.jacoco:org.jacoco.agent:jar:runtime:0.8.13` 仍會被解析(`-X` 有 `Populating class realm plugin>org.jacoco:jacoco-maven-plugin:0.8.13`),只是不執行。所以預熱不會漏掉 jacoco。之所以要加這個 flag:本機 `stock-module-asset/target/jacoco.exec` 有舊檔(Nov 14),不加的話 `verify -DskipTests` 會在 `jacoco:check` 失敗。CI 是 fresh checkout,不加其實也過，但加了能讓本機 smoke 與 CI 跑同一條命令。
- `e2e` profile 只改 surefire 的 includes,沒有新增 plugin。`verify` 會跑 spring-boot repackage(browser-e2e 的 `package` 需要)和 spotless:check(validate phase,會在 runtime provision 格式化工具)。
- 目前本機離線狀態:`./mvnw -o -B -ntp -q verify -DskipTests -Djacoco.skip=true` 成功(約 12 秒);`./mvnw -o -B -ntp -q -pl stock-common test -Djacoco.skip=true` 成功。
- `./mvnw -o -B -N -q help:evaluate -Dexpression=aether.connector.http.retryHandler.count -DforceStdout 2>/dev/null`:帶 `-D...=10` 時 stdout 剛好是 `10`;不帶時輸出 `null object or invalid expression`(這是 baseline)。
- actions/cache/restore@v4 的 outputs 有 `cache-hit`、`cache-primary-key`、`cache-matched-key`,input 有 `lookup-only`。actions/cache@v4 的 `post-if` 是 `success()`;setup-java@v4 沒有 `post-if`。
- actions/cache 的 cache version 由 `path` 字串(含順序)算出。所有 restore / save 步驟的 path 必須逐字相同，否則永遠 miss。
- 下方兩個結構閘門已用 scratch draft 驗證過：符合目標形狀時 PASS;在 HEAD 會 FAIL(後端 `KeyError: 'deps'`、前端 `AssertionError`)。
</context>

<tasks>

<task type="tracer">
  <name>Task 1(tracer,backend):maven.config 重試設定 + deps 預熱 job + Unit Tests 改為唯讀還原</name>
  <files>.mvn/maven.config, .github/workflows/ci.yml(皆 backend)</files>
  <action>
最細的端到端路徑：deps 寫入快取 → 透過 job output 傳 key → 一個下游 job(Unit Tests)唯讀還原。其他 job 先不動，保留原本的 setup-java 快取。它們的 key 前綴不同，不會衝突，所以這個 commit 單獨也能正常運作。

A. 新檔 backend `.mvn/maven.config`,內容只有一行 `-Daether.connector.http.retryHandler.count=10`,結尾換行。不要寫註解，Maven 3.9 的 maven.config 是一行一個參數，不要冒險加註解行。interval / intervalMax / serviceUnavailable 都維持預設(5000ms 線性退避、上限 300000ms、"429,503")。說明寫在 ci.yml 的 deps 註解裡(見 B)。

B. backend `.github/workflows/ci.yml`:在 `jobs:` 底下、`lint` 之前新增 job `deps`,name 為 `Resolve Maven Dependencies`,`runs-on: ubuntu-latest`,`timeout-minutes: 20`,不設 needs,並設 `outputs:` 的 `m2-key: ${{ steps.m2-probe.outputs.cache-primary-key }}`。依序建立以下步驟:
  1. `Checkout`:`actions/checkout@v4`。
  2. `Look up Maven cache`:id `m2-probe`,`uses: actions/cache/restore@v4`。with 的 `path` 用 block scalar `|`(不是 `|-`),兩行依序為 `~/.m2/repository`、`~/.m2/wrapper`。`key` 逐字為 `m2-${{ runner.os }}-${{ hashFiles('**/pom.xml', '.mvn/wrapper/maven-wrapper.properties') }}`。`lookup-only: true`(不加引號)。**不要**設 restore-keys:探測只看精確命中。
  3. 以下步驟全部加上 `if: steps.m2-probe.outputs.cache-hit != 'true'`。一律用 `!= 'true'`,miss 時 cache-hit 可能是空字串，所以不能寫成 `== 'false'`。
     - `Set up Java 21`:`actions/setup-java@v4`,with 只有 `distribution: temurin`、`java-version: "21"`,不要加任何 cache input。
     - `Restore previous Maven cache`:`actions/cache/restore@v4`,path 與步驟 2 逐字相同,`key: ${{ steps.m2-probe.outputs.cache-primary-key }}`,`restore-keys: m2-${{ runner.os }}-`(單行 scalar)。作用是 pom 改動時先拿最近一份舊快取，只補抓差額。
     - `Download Maven distribution (retry on rate limit)`:沿用現有 4 次重試迴圈，逐字複製現有 run 區塊，包括中文 echo。
     - `Resolve build and plugin dependencies`:run 逐字為 `./mvnw -B verify -DskipTests -Djacoco.skip=true --no-transfer-progress`。只 verify 不 install,不讓專案自己的 dowob.xyz SNAPSHOT 進快取。
     - `Resolve test provider`:run 逐字為 `./mvnw -B -pl stock-common test -Djacoco.skip=true --no-transfer-progress`。
     - `Save Maven cache`:`actions/cache/save@v4`,path 與步驟 2 逐字相同,`key: ${{ steps.m2-probe.outputs.cache-primary-key }}`。step 的 if 不含 status function,隱含 `success() &&`,所以預熱失敗就不會存，不能加 `always()`。
  deps job 上方加繁中註解，風格同現有註解：中文逗號用全形「，」,句末分號沿用現有半形「;」。內容要涵蓋:(a) 為什麼要有單一寫入者：內建快取所有 job 共用一把 key、誰先跑完誰存,Lint 存下殘缺快取，而且失敗也會存;(b) 命中就秒過、未命中只補差額、成功才存;(c) 下游一律唯讀，key 由 deps output 傳入;(d) 為什麼預熱是 verify -DskipTests -Djacoco.skip=true 再加 stock-common test:skipTests 不會解析 test provider,jacoco.skip 仍會載入 plugin 與 agent;(e) `.mvn/maven.config` 把 resolver 對 429/503 的重試由預設 3 次調為 10 次(Maven 3.9.14 內建 resolver 1.9.27,有 Retry-After 依其值，否則 5 秒 × 次數線性退避)。
  原本在 lint job 的 wrapper 註解(mvnw only-script、只快取 repository 不含 wrapper、429)這次先不動,Task 2 再改寫。

C. backend ci.yml 的 `unit` job:
  - 在 `runs-on` 之後加 `needs: deps`。
  - 從 setup-java 的 with 移除內建 Maven 快取的 input,只留 distribution 與 java-version。
  - 把整個 `Cache Maven wrapper distribution` 步驟(id mvnw-cache)換成 `Restore Maven cache`:id `m2-cache`,`uses: actions/cache/restore@v4`,path 與 deps 逐字相同,`key: ${{ needs.deps.outputs.m2-key }}`,`restore-keys: m2-${{ runner.os }}-`。restore-keys 是安全網:deps 的 save 萬一失敗，仍可退回最近的快取。
  - 下一步 `Download Maven distribution (retry on rate limit)` 的 if 改為 `steps.m2-cache.outputs.cache-hit != 'true'`,run 不變。
  - 其餘步驟(Run unit tests、test-reporter、upload-artifact)不動。

D. Commit(只 commit 後端 repo,不 push):subject 為 `ci(quick-261121-dgr): 新增 deps job 預熱 Maven 快取並讓 Unit Tests 唯讀還原`,body 用 1-3 行繁中說明根因與做法，結尾附 session attribution trailer:`Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` 與 `Claude-Session: https://claude.ai/code/session_011humejHkzZbjSQ9fxnMUYv`(以執行時 system reminder 給的為準)。
  </action>
  <verify>
    <automated>cd /home/user/stock-web-v2 && python3 - <<'PY'
import yaml
J = yaml.safe_load(open('.github/workflows/ci.yml', encoding='utf-8'))['jobs']
P = '~/.m2/repository\n~/.m2/wrapper\n'
K = "m2-${{ runner.os }}-${{ hashFiles('**/pom.xml', '.mvn/wrapper/maven-wrapper.properties') }}"
R = 'm2-${{ runner.os }}-'
nd = lambda j: set(J[j]['needs'] if isinstance(J[j].get('needs'), list) else [J[j].get('needs')])
D = J['deps']
assert D['outputs']['m2-key'] == '${{ steps.m2-probe.outputs.cache-primary-key }}'
pr = [s for s in D['steps'] if s.get('id') == 'm2-probe'][0]
assert pr['uses'] == 'actions/cache/restore@v4' and pr['with']['key'] == K and pr['with'].get('lookup-only') is True
runs = ' '.join(s.get('run', '') for s in D['steps'])
assert 'verify -DskipTests -Djacoco.skip=true' in runs and '-pl stock-common test -Djacoco.skip=true' in runs
assert [s['uses'] for s in D['steps'] if 'actions/cache' in s.get('uses', '')][-1] == 'actions/cache/save@v4'
for j, extra in {'unit': set()}.items():
    assert nd(j) == {'deps'} | extra, (j, nd(j))
    c = [s for s in J[j]['steps'] if 'actions/cache' in s.get('uses', '')]
    assert len(c) == 1 and c[0]['uses'] == 'actions/cache/restore@v4' and c[0]['id'] == 'm2-cache', j
    assert c[0]['with']['key'] == '${{ needs.deps.outputs.m2-key }}' and c[0]['with']['restore-keys'] == R, j
    dl = [s for s in J[j]['steps'] if s.get('name', '').startswith('Download Maven distribution')]
    assert len(dl) == 1 and dl[0]['if'] == "steps.m2-cache.outputs.cache-hit != 'true'", j
steps = [s for j in ('deps', 'unit') for s in J[j]['steps']]
assert all('cache' not in (s.get('with') or {}) for s in steps if 'setup-java' in s.get('uses', ''))
assert all(s['with']['path'] == P for s in steps if 'actions/cache' in s.get('uses', ''))
assert not [s for s in steps if s.get('uses') == 'actions/cache@v4']
print('BACKEND TRACER GATE OK')
PY
test "$(./mvnw -o -B -N -q help:evaluate -Dexpression=aether.connector.http.retryHandler.count -DforceStdout 2>/dev/null)" = "10" && ./mvnw -o -B -ntp -q verify -DskipTests -Djacoco.skip=true && ./mvnw -o -B -ntp -q -pl stock-common test -Djacoco.skip=true && echo "MAVEN SMOKE OK"</automated>
  </verify>
  <done>
- Python 閘門印出 `BACKEND TRACER GATE OK`(在 HEAD 18dd1cc 的 baseline 是 `KeyError: 'deps'`)。
- 不帶 -D 的 `help:evaluate` 輸出剛好是 `10`,證明 `.mvn/maven.config` 有被讀到(baseline 是 `null object or invalid expression`)。
- 兩個預熱命令在本機離線都成功(印出 `MAVEN SMOKE OK`)。
- 後端 repo 有一個 `ci(quick-261121-dgr): ...` commit,只含 `.mvn/maven.config` 與 `.github/workflows/ci.yml`。
  </done>
</task>

<task type="auto">
  <name>Task 2(backend):lint / integration / e2e / browser-e2e 全部改為唯讀還原 deps 快取 + LESSONS</name>
  <files>.github/workflows/ci.yml, ai-docs/bug-reports/LESSONS.md(皆 backend)</files>
  <action>
把 Task 1 在 unit 上驗證過的唯讀還原模式，套到其餘 4 個 Maven job。完成後，後端 ci.yml 裡只剩 deps 一個寫入者。

A. backend ci.yml 的 `lint`、`integration`、`e2e`、`browser-e2e` 四個 job 做和 Task 1 C 完全相同的改動，包括:
  - 從 setup-java 的 with 移除內建 Maven 快取 input。
  - 把 `Cache Maven wrapper distribution` 步驟換成 `Restore Maven cache`。它的 id、uses、path、key、restore-keys 都要和 unit 的同名步驟逐字相同。
  - 把 `Download Maven distribution (retry on rate limit)` 的 if 改為 `steps.m2-cache.outputs.cache-hit != 'true'`。browser-e2e 原本這步有 `working-directory: backend`,保留。
  needs 改為:lint 加上 `needs: deps`(放在 runs-on 之後);integration 由 `unit` 改為 `[deps, unit]`;e2e 由 `integration` 改為 `[deps, integration]`;browser-e2e 由 `e2e` 改為 `[deps, e2e]`。job 只讀得到直接列在 needs 裡的 job 的 outputs,所以一定要把 deps 列進去，不能只靠間接依賴。browser-e2e 既有的 `if:`(只在 PR 或 develop push 跑)不動。
  browser-e2e 的 key 也直接用 `needs.deps.outputs.m2-key`,**不要**改寫成帶 `backend/` 前綴的 hashFiles。key 由 deps 唯一計算，才保證逐字一致。

B. 註解更新:
  - lint job 裡原本那三行 wrapper 註解(mvnw only-script、只快取 repository 不含 wrapper、429)已經不符合現況，要刪除或改寫。改寫成一句即可：本體與依賴都在 deps 存的快取裡，未命中(例如 deps 的 save 失敗)時才帶重試下載。
  - lint 原有的「spotless:check 也綁在 validate 階段…」註解保留。
  - browser-e2e 原有的跨 repo / 成本註解保留。
  - 不得新增英文註解。中文標點規則同 Task 1。

C. backend `ai-docs/bug-reports/LESSONS.md`:在表格最後 append 一行(只追加，不改既有條目)。格式為 `| 2026-11-21 | 教訓 | 證據 | new |`。教訓用一句話寫:setup-java 內建 Maven 快取讓所有 job 共用一把 key、誰先跑完誰存，而且 post 存檔不看成敗;Lint 只跑 spotless,存下約 14 MB 殘缺快取，之後精確命中的 job 每次冷下載而撞 Maven Central 429;多 job 共用依賴快取要有單一寫入者(先預熱、成功才存),其他 job 只讀。證據欄寫 `develop run 44886864147(Unit 下載 Maven 本體被限流);Integration 429 於 jacoco-maven-plugin:pom:0.8.13;.github/workflows/ci.yml deps job`。

D. Commit(後端 repo,不 push):subject 為 `ci(quick-261121-dgr): 其餘 Maven job 改讀 deps 快取並移除 setup-java 內建快取`,trailer 同 Task 1。
  </action>
  <verify>
    <automated>cd /home/user/stock-web-v2 && python3 - <<'PY'
import yaml
J = yaml.safe_load(open('.github/workflows/ci.yml', encoding='utf-8'))['jobs']
P = '~/.m2/repository\n~/.m2/wrapper\n'
K = "m2-${{ runner.os }}-${{ hashFiles('**/pom.xml', '.mvn/wrapper/maven-wrapper.properties') }}"
R = 'm2-${{ runner.os }}-'
nd = lambda j: set(J[j]['needs'] if isinstance(J[j].get('needs'), list) else [J[j].get('needs')])
D = J['deps']
assert D['outputs']['m2-key'] == '${{ steps.m2-probe.outputs.cache-primary-key }}'
pr = [s for s in D['steps'] if s.get('id') == 'm2-probe'][0]
assert pr['uses'] == 'actions/cache/restore@v4' and pr['with']['key'] == K and pr['with'].get('lookup-only') is True
runs = ' '.join(s.get('run', '') for s in D['steps'])
assert 'verify -DskipTests -Djacoco.skip=true' in runs and '-pl stock-common test -Djacoco.skip=true' in runs
assert [s['uses'] for s in D['steps'] if 'actions/cache' in s.get('uses', '')][-1] == 'actions/cache/save@v4'
for j, extra in {'lint': set(), 'unit': set(), 'integration': {'unit'}, 'e2e': {'integration'}, 'browser-e2e': {'e2e'}}.items():
    assert nd(j) == {'deps'} | extra, (j, nd(j))
    c = [s for s in J[j]['steps'] if 'actions/cache' in s.get('uses', '')]
    assert len(c) == 1 and c[0]['uses'] == 'actions/cache/restore@v4' and c[0]['id'] == 'm2-cache', j
    assert c[0]['with']['key'] == '${{ needs.deps.outputs.m2-key }}' and c[0]['with']['restore-keys'] == R, j
    dl = [s for s in J[j]['steps'] if s.get('name', '').startswith('Download Maven distribution')]
    assert len(dl) == 1 and dl[0]['if'] == "steps.m2-cache.outputs.cache-hit != 'true'", j
steps = [s for j in J for s in J[j]['steps']]
assert all('cache' not in (s.get('with') or {}) for s in steps if 'setup-java' in s.get('uses', ''))
assert all(s['with']['path'] == P for s in steps if 'actions/cache' in s.get('uses', ''))
assert not [s for s in steps if s.get('uses') == 'actions/cache@v4']
print('BACKEND CI GATE OK')
PY
test "$(grep -c '^| 2026-11-21 |' ai-docs/bug-reports/LESSONS.md)" -ge 1 && NS="$(git diff --numstat 18dd1cc -- ai-docs/bug-reports/LESSONS.md)" && echo "$NS" | awk '$1 == 1 && $2 == 0 { ok = 1 } END { exit !ok }' && echo "LESSONS OK"</automated>
  </verify>
  <done>
- `BACKEND CI GATE OK`:6 個 job 的 needs 正確;5 個下游 job 各只有一個 actions/cache/restore 步驟(id m2-cache),key 是 deps output、restore-keys 相同;全檔所有 actions/cache* 步驟的 path 逐字相同;沒有任何 setup-java 帶 cache input;沒有任何讀寫型 actions/cache@v4 步驟。
- LESSONS.md 只多出 1 行 2026-11-21 條目(baseline 為 0 行)。
- 後端 repo 有第二個 `ci(quick-261121-dgr): ...` commit。
  </done>
</task>

<task type="auto">
  <name>Task 3(frontend):Browser E2E 的 Maven 快取改為 actions/cache@v4 + restore-keys,並記錄 LEARNINGS</name>
  <files>.github/workflows/ci.yml, docs/LEARNINGS.md(皆 frontend)</files>
  <action>
**前端的決策與理由:** 跨 repo 快取不共用，後端的 deps 無法替前端預熱。前端 repo 只有 browser-e2e 一個 Maven job,所以不會發生後端那種「別的 job 先存了殘缺快取」的競爭，不必在前端也加 deps job。前端現況剩兩個真實弱點:(1) setup-java 的內建快取沒有 restore-keys,後端任一 pom 改動就整包冷下載(數百個 artifact),正好暴露在 429 下;(2) setup-java 的 post 存檔不看 job 成敗(action.yml 沒有 `post-if`),run-e2e.sh 裡的 mvnw 解析到一半撞 429 時，殘缺的 ~/.m2 會被存成精確 key。最小修法是把兩個快取合併成一個 actions/cache@v4。它的 post 存檔有 `post-if: success()`,而且支援 restore-keys。代價是 Playwright 失敗時這次不存，可以接受，因為下次會用 restore-keys 還原再補差額。resolver 重試次數不需要在前端另外設：後端 `.mvn/maven.config` 跟著後端 checkout 進來，run-e2e.sh 是 `cd $BACKEND_DIR && ./mvnw`,所以會讀到。不加 jar-存在條件式 save 之類的額外步驟，維持最小 diff。

A. frontend `.github/workflows/ci.yml` 只動 `browser-e2e` job,`frontend` job 一字不改:
  - `Set up Java 21` 的 with 移除 `cache` 與 `cache-dependency-path` 兩個 input,只留 `distribution: temurin`、`java-version: "21"`。注意不要動 `Set up Node.js 20` 的 npm 快取。
  - 把 `Cache Maven wrapper distribution` 步驟換成 `Cache Maven repository and wrapper`:id `m2-cache`,`uses: actions/cache@v4`(讀寫型，靠它的 post 存檔)。path 用 block scalar `|`,兩行依序為 `~/.m2/repository`、`~/.m2/wrapper`。key 逐字為 `m2-${{ runner.os }}-${{ hashFiles('backend/**/pom.xml', 'backend/.mvn/wrapper/maven-wrapper.properties') }}`,`restore-keys: m2-${{ runner.os }}-`(單行)。key 格式刻意與後端相同以便閱讀，但兩個 repo 的快取命名空間各自獨立，彼此不會命中。key 在步驟開始時計算(此時還沒 build,backend/ 下沒有 target),post 存檔沿用同一把 key。
  - `Download Maven distribution (retry on rate limit)` 的 if 改為 `steps.m2-cache.outputs.cache-hit != 'true'`,`working-directory: backend` 與 run 都不變。
  - 在快取步驟上方加 1-3 行繁中註解，說明為什麼改用 actions/cache:有 restore-keys,只在成功時存;並註明 resolver 重試設定來自後端 `.mvn/maven.config`。中文逗號用全形。

B. frontend `docs/LEARNINGS.md` 表格最後 append 一行(只追加)。格式為 `| 2026-11-21 | 教訓 | 證據 | new |`。教訓寫:setup-java 內建 Maven 快取沒有 restore-keys,後端 pom 一改就整包冷下載;且 post 存檔不看 job 成敗(action.yml 無 post-if),解析到一半撞 429 的 ~/.m2 會被存成精確 key;改用 actions/cache@v4(post-if: success())加 restore-keys。證據欄寫 `後端 quick-261121-dgr;.github/workflows/ci.yml browser-e2e`。

C. 跑前端標準閘門(AGENTS.md 規定宣稱完成前必跑;只改 workflow,預期無影響)。

D. Commit(只 commit 前端 repo,不 push):subject 為 `ci(quick-261121-dgr): Browser E2E 的 Maven 快取改用 actions/cache 並加 restore-keys`,trailer 同 Task 1。
  </action>
  <verify>
    <automated>cd /home/user/stock-web-v2-front-end && python3 - <<'PY'
import subprocess, yaml
W = yaml.safe_load(open('.github/workflows/ci.yml', encoding='utf-8'))
H = yaml.safe_load(subprocess.run(['git', 'show', '0783da9:.github/workflows/ci.yml'], capture_output=True, text=True, check=True).stdout)
assert W['jobs']['frontend'] == H['jobs']['frontend']
S = W['jobs']['browser-e2e']['steps']
sj = [s for s in S if 'setup-java' in s.get('uses', '')][0]
assert set(sj['with']) == {'distribution', 'java-version'}
c = [s for s in S if 'actions/cache' in s.get('uses', '')]
assert len(c) == 1 and c[0]['uses'] == 'actions/cache@v4' and c[0]['id'] == 'm2-cache'
assert c[0]['with']['path'] == '~/.m2/repository\n~/.m2/wrapper\n'
assert c[0]['with']['key'] == "m2-${{ runner.os }}-${{ hashFiles('backend/**/pom.xml', 'backend/.mvn/wrapper/maven-wrapper.properties') }}"
assert c[0]['with']['restore-keys'] == 'm2-${{ runner.os }}-'
dl = [s for s in S if s.get('name', '').startswith('Download Maven distribution')]
assert len(dl) == 1 and dl[0]['if'] == "steps.m2-cache.outputs.cache-hit != 'true'" and dl[0]['working-directory'] == 'backend'
print('FRONTEND CI GATE OK')
PY
test "$(grep -c '^| 2026-11-21 |' docs/LEARNINGS.md)" -ge 1 && cd vue-app && npm run lint && npm test && npm run build && echo "FRONTEND GATE OK"</automated>
  </verify>
  <done>
- `FRONTEND CI GATE OK`:frontend job 與 0783da9 完全相同;browser-e2e 只有一個 actions/cache@v4 步驟(id m2-cache),帶 restore-keys,path 涵蓋 repository 與 wrapper;setup-java 不帶任何快取 input;重試步驟的 if 已指向 m2-cache,並保留 working-directory backend(baseline 在 HEAD 是 `AssertionError`)。
- LEARNINGS.md 多出 1 行 2026-11-21 條目(baseline 為 0 行)。
- `npm run lint && npm test && npm run build` 全綠。
- 前端 repo 有一個 `ci(quick-261121-dgr): ...` commit,只含上述兩個檔案。
  </done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| workflow run → GitHub Actions cache | PR / feature branch 的 run 會寫入快取，之後的 run 會還原並執行裡面的 jar |
| CI runner → Maven Central | 所有 plugin 與依賴的來源(供應鏈 + 限流) |
| backend repo → frontend CI | 前端 Browser E2E 會 checkout 後端分支並執行其 mvnw 與 `.mvn/maven.config` |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-261121dgr-01 | Tampering | 後端 deps 的 actions/cache/save | medium | mitigate | GitHub 依 ref 隔離快取寫入:PR run 只能寫 refs/pull/N/merge,feature branch 只能寫自己的 scope,都覆寫不了 develop 的快取。全檔只有 deps 一個 save 步驟(Task 2 閘門斷言下游只有 restore@v4,沒有讀寫型 actions/cache@v4)。 |
| T-261121dgr-02 | Denial of Service | 精確 key 被存成殘缺快取(本次根因) | high | mitigate | 後端的 save 隱含 `success()`,預熱兩命令都成功才寫;前端改用 actions/cache@v4(`post-if: success()`)。setup-java 不看成敗的存檔路徑在兩個 repo 都移除(閘門斷言 setup-java 沒有 cache input)。 |
| T-261121dgr-03 | Denial of Service | Maven Central HTTP 429 | high | mitigate | 冷下載集中在 deps 一個 job;restore-keys 讓 pom 改動時只補差額;`.mvn/maven.config` 把 resolver 重試由 3 次調為 10 次(有 Retry-After 依其值);Maven 本體下載保留 4 次重試迴圈。不換 mirror(Yuan 指示)。 |
| T-261121dgr-04 | Information Disclosure | 快取內容 | low | accept | 只快取 `~/.m2/repository` 與 `~/.m2/wrapper`,都是公開 artifact。setup-java 產生的 `~/.m2/settings.xml` 不在快取路徑內。不讀任何 secrets。 |
| T-261121dgr-05 | Elevation of Privilege | workflow permissions | low | accept | 不改 `permissions:`(contents: read、checks: write)。actions/cache 用 ACTIONS_RUNTIME_TOKEN,不需要額外 GITHUB_TOKEN 權限。 |
| T-261121dgr-SC | Tampering | 套件安裝 / 第三方 action | low | accept | 本計畫沒有 npm / pip / cargo 安裝，不觸發 package legitimacy gate。只用既有第一方 `actions/cache@v4` 及其子 action `restore@v4` / `save@v4`。Maven artifact 來源仍是 Maven Central。 |
</threat_model>

<verification>
本機(執行者):
1. Task 1 / 2 / 3 的 `<automated>` 全部通過。最終狀態以 Task 2 的 `BACKEND CI GATE OK` 與 Task 3 的 `FRONTEND CI GATE OK` 為準。
2. `git -C /home/user/stock-web-v2 log --oneline 18dd1cc..HEAD` 有 2 個 `ci(quick-261121-dgr)` commit;`git -C /home/user/stock-web-v2-front-end log --oneline 0783da9..HEAD` 有 1 個。兩個 repo 的 `git status --short` 都乾淨(本機產生的 target/ 已 gitignore)。
3. 沒有 push、沒有開 PR。

CI(本機無 Docker / act,真正的證明在 PR 上，由 orchestrator 推送後收集;寫進 SUMMARY 的「待收集 CI 證據」):
- 後端第一次 run:deps 的 `Look up Maven cache` 顯示 cache not found → 預熱兩步成功 → `Cache saved with key: m2-Linux-<hash>`。其他 job 的 `Restore Maven cache` 顯示 `Cache restored from key: m2-Linux-<同一 hash>`(cache-hit true),而且下載 Maven 本體的步驟被 skip。Integration 不再出現 429。
- 後端第二次 run(pom 不變):deps 的 probe 顯示 `Cache found and can be restored from key`,其餘步驟全部 skipped,job 數秒內完成。
- 後端 browser-e2e(PR 事件)還原到同一個 key。
- 前端第一次 run:browser-e2e 冷下載(或 restore-keys 部分命中),job 成功後 post 顯示 `Cache saved`;第二次 run 命中。
- 已知一次性成本:feature branch / PR scope 的快取 develop 讀不到，所以合併後第一次 develop push 的 deps 會再冷解析一次。之後所有分支與 PR 都會讀 develop 的快取。舊的 `setup-java-Linux-x64-maven-*` 與 `maven-wrapper-*` 快取 7 天沒用會自動淘汰;要立即清可由 Yuan 用 `gh cache delete` 處理，不在本計畫範圍內。
- 已知取捨:restore-keys 補差額後再 save,快取裡會累積舊版本 artifact。日後若明顯變大，可由 Yuan 刪除快取讓它重建。
</verification>

<success_criteria>
- 後端 ci.yml:有 `deps` job 當唯一寫入者(lookup-only 探測、restore-keys 補差額、兩個預熱命令、成功才 save);lint / unit / integration / e2e / browser-e2e 全部 `needs` 含 deps,以 `needs.deps.outputs.m2-key` 唯讀還原;沒有任何 setup-java 內建 Maven 快取;沒有讀寫型 actions/cache@v4。
- 後端 `.mvn/maven.config` 存在,`help:evaluate` 讀得到 `aether.connector.http.retryHandler.count=10`。
- 前端 ci.yml:browser-e2e 改用 actions/cache@v4(repository + wrapper、restore-keys、success-only 存檔),frontend job 不變，前端標準閘門全綠。
- 兩個 lessons 檔各 append 一行;後端 2 個、前端 1 個 Conventional Commits(scope `quick-261121-dgr`、繁中 subject),都沒有 push。
</success_criteria>

<output>
Create `/home/user/stock-web-v2/.planning/quick/261121-dgr-ci-maven-cache-429/261121-dgr-SUMMARY.md` when done(含兩個 repo 的 commit hash、各閘門輸出，以及上方「CI 待收集證據」清單)。
</output>
