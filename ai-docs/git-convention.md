# Git Commit Convention

## Conventional Commits

*   Follow **Conventional Commits v1.0.0** (https://www.conventionalcommits.org/en/v1.0.0/).
*   Format: `<type>(<scope>): <Traditional Chinese subject>`
*   **type**: `feat` / `fix` / `docs` / `style` / `refactor` / `perf` / `test` / `build` / `ci` / `chore`
*   **scope**: GSD-driven work uses the **phase / plan id** (`NN-NN`, e.g. `feat(04.2-12)`, `docs(04.2)`) or the **quick id** (`quick-YYMMDD-xxx`, e.g. `fix(quick-261106-fbs)`); module (`user` / `asset` / `trading` / `market-data` / `common` / `infrastructure`) or layer (`controller` / `service` / `security` …) scopes are only for non-GSD changes. The frontend repo follows the same rule (Yuan 2026-11-07, decision 3C-3).
*   **Breaking Change**: append `!` after type, add `BREAKING CHANGE: <description>` in footer
*   Subject: Traditional Chinese, imperative mood, no trailing period, max 72 characters
*   Body (optional): motivation and change details after a blank line
*   Footer (optional): `BREAKING CHANGE:`, `Fixes #<issue>`, `Co-Authored-By:`
