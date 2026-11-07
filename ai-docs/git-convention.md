# Git Commit Convention

## Conventional Commits

*   Follow **Conventional Commits v1.0.0** (https://www.conventionalcommits.org/en/v1.0.0/).
*   Format: `<type>(<scope>): <Traditional Chinese subject>`
*   **type**: `feat` / `fix` / `docs` / `style` / `refactor` / `perf` / `test` / `build` / `ci` / `chore`
*   **scope**: module (`user` / `asset` / `trading` / `market-data` / `common` / `infrastructure`) or layer (`controller` / `service` / `security` …)
    *   In practice (git history since Phase 04, re-checked 2026-11-06), GSD-driven work uses the **phase / plan / quick-task id** as scope — e.g. `feat(04.2-12)`, `docs(04.2)`, `fix(04.2-review)`, `fix(quick-261106-fbs)` — which is also what the frontend repo's AGENTS.md prescribes; module / layer scopes remain for non-GSD changes.
*   **Breaking Change**: append `!` after type, add `BREAKING CHANGE: <description>` in footer
*   Subject: Traditional Chinese, imperative mood, no trailing period, max 72 characters
*   Body (optional): motivation and change details after a blank line
*   Footer (optional): `BREAKING CHANGE:`, `Fixes #<issue>`, `Co-Authored-By:`
