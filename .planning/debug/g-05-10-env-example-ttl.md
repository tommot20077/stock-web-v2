---
status: diagnosed
trigger: "G-05-10 / T-05-12: .env.example:17 STOCK_JWT_ACCESS_TOKEN_TTL=PT30M without paired STOCK_AUTH_COOKIE_ACCESS_TOKEN_TTL; dev profile reads .env; AccessTokenTtlConsistencyGuard refuses startup (UAT Test 10, 2026-11-14)"
created: 2026-11-14T13:37:38Z
updated: 2026-11-14T13:55:00Z
goal: find_root_cause_only
symptoms_prefilled: true
---

## Current Focus

bug_class: Bohrbug (deterministic config drift; 100% reproducible from static inputs)
known_pattern_candidate: none (no knowledge-base.md existed; MemPalace not used)
hypothesis: "CONFIRMED — .env.example:17 sets STOCK_JWT_ACCESS_TOKEN_TTL=PT30M without a paired STOCK_AUTH_COOKIE_ACCESS_TOKEN_TTL; dev profile imports .env (application-dev.yaml:3), so stock.jwt.access-token-ttl binds to PT30M while stock.auth.cookie.access-token-ttl falls back to PT15M (application.yaml:75); AccessTokenTtlConsistencyGuard:38 Duration#equals fails → IllegalStateException → startup refused."
test: done (static read + Binder demo, Evidence B)
expecting: n/a
next_action: "Return ROOT CAUSE FOUND to orchestrator (goal: find_root_cause_only). Fix belongs to G-05-10 gap-closure plan."

reasoning_checkpoint:
  hypothesis: "Copying .env.example verbatim makes jwt access TTL = PT30M and cookie access TTL = PT15M under the dev profile, because the template carries a stale PT30M override from 2026-05 that 05-05 (eb0bc29) did not update when it lowered the default to PT15M and added the equality guard."
  confirming_evidence:
    - ".env.example:17 STOCK_JWT_ACCESS_TOKEN_TTL=PT30M; no STOCK_AUTH_COOKIE_* line"
    - "application-dev.yaml:3 spring.config.import: optional:file:.env[.properties]; README:54-57 run java -jar from repo root"
    - "Guard :38 Duration equality, unconditional @Component, no profile exemption"
    - "Binder demo case B: cookie=PT15M jwt=PT30M → guard throws exact static message; case A/C/D pass"
    - "git: eb0bc29 diff omits .env.example; 05-05-PLAN:115 grep gate scoped to stock-start/src/{main,test}/resources"
  falsification_test: "If the dev profile did not import .env, or the guard were profile-gated, or Binder resolved jwt TTL to PT15M with the template loaded — none observed (demo B shows PT30M)."
  fix_rationale: "Aligning the template (PT15M, or pairing the cookie TTL) removes the only drifted input; all yaml profiles and docs are already consistent."
  blind_spots: "Did not boot the full SpringApplication (ConfigDataEnvironment import ordering not exercised end-to-end) — irrelevant since the template keys (STOCK_*) do not overlap the yaml keys (stock.*), and run-dev.ps1 exports them as OS env vars which resolve identically. Bean creation order vs. DB/Redis connectivity failures not tested (a user with unreachable DB may see a DB error first)."
  candidate_causes:
    - "data/config-template: stale .env.example value (CONFIRMED)"
    - "code: guard comparing un-normalized vs normalized durations (eliminated — both are PT15M/PT30M Durations; equality semantics correct)"
    - "config: application-dev.yaml / demo profile overriding cookie TTL (eliminated — no profile except e2e-browser sets it, and e2e-browser sets both to PT2H)"
    - "process/verification: 05-05 grep gate scope excluded repo root (contributing — why not caught)"
  and_gate: "yes — failure requires BOTH (1) the stale explicit PT30M in the template AND (2) the 05-05 change (default PT15M + no-exemption guard). Before eb0bc29 the same template line was harmless. Condition (2) is intended behavior (D-22), so the fix target is (1)."

## Symptoms

expected: 照 README 複製 .env.example 為 .env 後,dev profile 可直接啟動(JWT access TTL 與 cookie access TTL 一致,不被 AccessTokenTtlConsistencyGuard 拒絕)
actual: .env.example:17 is STOCK_JWT_ACCESS_TOKEN_TTL=PT30M with no paired STOCK_AUTH_COOKIE_ACCESS_TOKEN_TTL; application.yaml defaults both to PT15M; D-22 AccessTokenTtlConsistencyGuard refuses startup when they differ. Threat id T-05-12.
errors: none captured (static analysis)
reproduction: UAT Test 10 (05-UAT.md)
started: discovered during phase 05 verification, confirmed in UAT 2026-11-14

## Eliminated

- hypothesis: "Guard is profile-gated / dev exempt, so dev would start anyway"
  evidence: "AccessTokenTtlConsistencyGuard has no @Profile/@Conditional; Javadoc :19-20 states no exemption; component scan covers it; both properties records bound at StockWebV2Application:12"
  timestamp: 2026-11-14T13:42:00Z

- hypothesis: "Some yaml profile (dev/demo) or test resource also drifted and contributes"
  evidence: "full grep: only .env.example has live PT30M; application.yaml/demo/test/e2e = PT15M, e2e-browser = PT2H both; application-dev.yaml sets no TTL"
  timestamp: 2026-11-14T13:44:00Z

- hypothesis: "dev launch does not actually read .env (CWD mismatch)"
  evidence: "README:54-57 / getting-started:60,73 / overview:223 instruct running java -jar from repo root (CWD-relative import hits repo-root .env); run-dev.ps1 additionally exports .env as process env and Push-Location repoRoot"
  timestamp: 2026-11-14T13:41:00Z

## Evidence

- timestamp: 2026-11-14T13:40:00Z
  checked: .env.example (full file, 21 lines)
  found: "line 17 `STOCK_JWT_ACCESS_TOKEN_TTL=PT30M`; line 18 `STOCK_JWT_REFRESH_TOKEN_TTL=P14D`; NO `STOCK_AUTH_COOKIE_ACCESS_TOKEN_TTL` (no STOCK_AUTH_COOKIE_* line at all)"
  implication: copying the template yields an explicit JWT override of 30 min while the cookie TTL is left to its default.

- timestamp: 2026-11-14T13:40:30Z
  checked: stock-start/src/main/resources/application.yaml + application-dev.yaml
  found: "application.yaml:5 `profiles.default: dev`; :75 `access-token-ttl: ${STOCK_AUTH_COOKIE_ACCESS_TOKEN_TTL:PT15M}`; :86 comment `必須等於 stock.auth.cookie.access-token-ttl,否則 AccessTokenTtlConsistencyGuard 拒絕啟動(D-22)`; :87 `access-token-ttl: ${STOCK_JWT_ACCESS_TOKEN_TTL:PT15M}`. application-dev.yaml:2-3 `spring.config.import: optional:file:.env[.properties]` (relative to process CWD)."
  implication: dev profile (the default) imports `.env` as a properties PropertySource; the placeholder `${STOCK_JWT_ACCESS_TOKEN_TTL:PT15M}` resolves to PT30M from it, while `${STOCK_AUTH_COOKIE_ACCESS_TOKEN_TTL:PT15M}` falls back to PT15M.

- timestamp: 2026-11-14T13:41:00Z
  checked: README.md:48-59, docs/guides/getting-started.md:60-75, docs/guides/development.md:15-25, docs/configuration/overview.md:222-224, scripts/run-dev.ps1
  found: "README:50 `cp .env.example .env`; :52 only asks to fill DB/Redis (+ optional Kafka, JWT key blank OK) — TTL not mentioned; :54 `請在 repo 根目錄下執行,因為 dev profile 會以相對路徑讀取 .env`; :57 `java -jar stock-start/target/stock-start-0.0.1-SNAPSHOT.jar`. run-dev.ps1:7-22 exports every .env line as a process env var, :25-27 defaults SPRING_PROFILES_ACTIVE=dev, :29 Push-Location repoRoot, :45 java -jar. getting-started.md:66 likewise only lists DB/Redis to fill."
  implication: BOTH documented dev launch paths feed `.env` into the Environment (README path via config import from CWD=repo root; Windows path via OS env vars AND config import). No doc instructs the user to touch TTL lines, so a verbatim copy keeps PT30M.

- timestamp: 2026-11-14T13:42:00Z
  checked: stock-module-user/.../user/service/AccessTokenTtlConsistencyGuard.java (43 lines) + BrowserAuthCookieProperties.java + JwtProperties.java + StockWebV2Application.java:12
  found: "Guard is an unconditional `@Component` (no @Profile/@Conditional; Javadoc :19-20 `不設 profile 豁免:dev / test / e2e / e2e-browser 也必須真的相等`). Constructor :38 `if (!cookie.accessTokenTtl().equals(jwt.accessTokenTtl()))` → :39-40 `throw new IllegalStateException(\"STOCK_AUTH_COOKIE_ACCESS_TOKEN_TTL must equal STOCK_JWT_ACCESS_TOKEN_TTL\")`. Comparison is java.time.Duration#equals (exact seconds+nanos). BrowserAuthCookieProperties:38-40 normalizes null/zero/negative cookie TTL to 15 min; JwtProperties has no normalization (only requirePositive). Both bound via `@EnableConfigurationProperties({JwtProperties.class, BrowserAuthCookieProperties.class, ...})` at StockWebV2Application:12; component scan base `dowob.xyz.stockwebv2` covers the guard."
  implication: in dev, cookie=PT15M vs jwt=PT30M → Duration.equals false → IllegalStateException during bean creation → context refresh fails → app refuses to start.

- timestamp: 2026-11-14T13:42:30Z
  checked: AccessTokenTtlConsistencyGuardTest.java:41-53 + target/surefire-reports (2026-11-12, 4 run / 0 fail)
  found: "test `mismatchedTtl_failsFastWithStaticMessage` — DisplayName `cookie PT15M、JWT PT30M → 拒絕啟動` — constructs exactly the .env.example combination and asserts IllegalStateException with the static message; it passes."
  implication: the repo's own unit test proves that the precise values produced by `cp .env.example .env` are rejected by the guard.

- timestamp: 2026-11-14T13:43:00Z
  checked: git log -- .env.example ; git show --stat eb0bc29
  found: "`.env.example:17 STOCK_JWT_ACCESS_TOKEN_TTL=PT30M` was added in 86fd897 (2026-05-16, `feat: add auth endpoints and security flow`) when the yaml JWT default was also PT30M; last touched 933b730 (2026-10-23, JWK comment only). eb0bc29 (2026-11-09, `feat(05-05): access token TTL 對齊 PT15M 並加啟動守衛`) changed only application.yaml, application-demo.yaml, application-test.yaml, application-e2e.yaml + guard + guard test — .env.example NOT in the diff."
  implication: the line was harmless (equal to default, no guard) until 05-05 lowered the default and added the guard; 05-05 missed the template, turning a stale explicit override into a fail-closed config.

- timestamp: 2026-11-14T13:43:30Z
  checked: 05-05-PLAN.md:80 (TTL location inventory) and :115 (automated verify)
  found: "Plan inventory lists application.yaml, application-demo.yaml, application-e2e-browser.yaml, application-test.yaml, application-e2e.yaml and docs — no `.env.example`. Automated check: `grep -rh \"PT30M\" stock-start/src/main/resources stock-start/src/test/resources | grep -v '^\\s*#' | wc -l == 0` — scope excludes repo root. 05-RESEARCH.md:686 even states `若某部署顯式設 STOCK_JWT_ACCESS_TOKEN_TTL=PT30M 而 cookie 未設,新守衛會拒絕啟動` but did not connect it to the template. 05-REVIEW.md:240 / 05-VERIFICATION.md:209 / 05-SECURITY.md:49 later flagged it (T-05-12 open)."
  implication: why-not-caught = plan scoping gap + grep gate scoped to stock-start resources only.

- timestamp: 2026-11-14T13:44:00Z
  checked: full-repo grep (excluding target/.git) for STOCK_JWT_ACCESS_TOKEN_TTL | STOCK_AUTH_COOKIE_ACCESS_TOKEN_TTL | PT30M; find for docker-compose*/compose*.y*ml
  found: "Live config/runtime occurrences: .env.example:17 (PT30M — the ONLY live PT30M); application.yaml:75 (cookie PT15M), :87 (JWT PT15M); application-demo.yaml:18 (comment, PT15M + must-equal), :71 (JWT PT15M; cookie TTL inherited from application.yaml PT15M); application-e2e-browser.yaml:37,41 (literal PT2H both, no env var); test resources application-test.yaml:28 / application-e2e.yaml:4 (JWT PT15M literal). Docs: docs/configuration/overview.md:45,54,174,203,208 (all PT15M + must-equal); ai-docs/browser-auth-contract.md:85,214 (must-equal, default PT15M, 'changed from PT30M'). README.md / docs/guides/getting-started.md / development.md: no TTL mention. No docker-compose in backend repo (only frontend vue-app/e2e/docker-compose.yml for e2e-browser, which does not use .env). Historical-only PT30M: docs/superpowers/plans/2026-05-16-stock-foundation-implementation-plan.md:600,649,1312; .planning/** (plans/research/todos)."
  implication: .env.example is the single drifted artifact; every yaml profile and the config doc are already consistent.

- timestamp: 2026-11-14T13:44:30Z
  checked: grep src/test/**/*.java for env.example / dotenv / config.import / dev profile; .github/** for env.example; InfraProfileDefaultsTest / OpenApiExposureDefaultsTest
  found: "No test or CI step reads .env.example or boots the dev profile with a .env. ITs run with no profile (ContainerIT:63 note — default dev) but surefire/failsafe CWD is the module dir and CI has no .env, so the optional import is skipped. Static yaml-default tests exist (InfraProfileDefaultsTest parses yaml via YamlPropertySourceLoader; TradingSqlBoundaryTest reads files via Files.readString) — a precedent for a cheap file-level guard test."
  implication: nothing could catch template drift today; a cheap static test is feasible with existing patterns.

- timestamp: 2026-11-14T13:52:00Z
  checked: "scratch demo (outside repo: <scratchpad>/g0510/src/EnvExampleTtlDemo.java) — compiled CURRENT sources of JwtProperties, BrowserAuthCookieProperties, AccessTokenTtlConsistencyGuard with spring-boot 4.0.4 / spring-core 7.0.6 from ~/.m2; built a StandardEnvironment with application-dev.yaml + application.yaml (YamlPropertySourceLoader) and the real .env.example loaded via PropertiesPropertySourceLoader (the loader selected by the `[.properties]` hint in `optional:file:.env[.properties]`); bound with Spring Boot Binder (real placeholder resolution); then constructed the guard. OS env / system props removed to avoid interference. No repo file modified, app not started."
  found: |
    A) application.yaml only (no .env)            → cookie=PT15M jwt=PT15M → guard OK
    B) .env = verbatim copy of .env.example       → cookie=PT15M jwt=PT30M → guard THROWS IllegalStateException: STOCK_AUTH_COOKIE_ACCESS_TOKEN_TTL must equal STOCK_JWT_ACCESS_TOKEN_TTL
    C) counterfactual JWT=PT15M in template       → cookie=PT15M jwt=PT15M → guard OK
    D) counterfactual paired cookie=PT30M+JWT=PT30M → cookie=PT30M jwt=PT30M → guard OK
  implication: CONFIRMED. The template value alone flips the guard; either fix variant (align to PT15M, or add a paired cookie TTL) clears it. Control A shows defaults are consistent.

## Resolution

root_cause: ".env.example:17 carries a stale explicit `STOCK_JWT_ACCESS_TOKEN_TTL=PT30M` (added 2026-05-16, 86fd897, when the yaml default was PT30M) with no paired `STOCK_AUTH_COOKIE_ACCESS_TOKEN_TTL`. 05-05 (eb0bc29, 2026-11-09) lowered the JWT default to PT15M in all four yaml files and added the no-exemption AccessTokenTtlConsistencyGuard, but did not update the template (plan inventory 05-05-PLAN:80 and grep gate :115 were scoped to stock-start/src/{main,test}/resources). Under the default dev profile, `.env` is imported (application-dev.yaml:3), so jwt=PT30M vs cookie=PT15M → guard :38 throws IllegalStateException(\"STOCK_AUTH_COOKIE_ACCESS_TOKEN_TTL must equal STOCK_JWT_ACCESS_TOKEN_TTL\") → startup refused."
fix: "(not in scope — find_root_cause_only). User-chosen direction (05-UAT G-05-10 reason): change .env.example:17 to PT15M."
verification: "Root cause confirmed by Binder demo (Evidence, case B throws; A/C/D pass) and by existing AccessTokenTtlConsistencyGuardTest#mismatchedTtl_failsFastWithStaticMessage (PT15M vs PT30M rejected)."
files_changed: []
oracle_type: "specified (contract: browser-auth-contract.md:85 'must equal'; guard Duration equality)"
why_not_caught: "No gate reads .env.example: 05-05 grep gate scoped to stock-start resources; no unit test parses the template; no CI step boots dev profile with a .env."
recurrence_guard_suggestion: "(non-binding) a static unit test in stock-start (pattern: InfraProfileDefaultsTest / TradingSqlBoundaryTest Files.readString) that parses <repo-root>/.env.example and asserts effective JWT access TTL == effective cookie access TTL (template value or application.yaml placeholder default), or at minimum that the template never sets exactly one of the pair."
