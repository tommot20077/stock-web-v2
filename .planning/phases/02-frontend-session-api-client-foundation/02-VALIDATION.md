---
phase: 02
slug: frontend-session-api-client-foundation
status: draft
nyquist_compliant: true
wave_0_complete: false
created: 2026-05-30
---

# Phase 02 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | Vitest 4.1.6 with jsdom, Vue 3.5.34, Vite 8.0.13 |
| **Config file** | `../../vue/stock-v2/vue-app/vite.config.ts` |
| **Quick run command** | `cd ../../vue/stock-v2/vue-app && npm test -- src/services/apiClient.test.ts src/services/runtimeDataMode.test.ts` |
| **Full suite command** | `cd ../../vue/stock-v2/vue-app && npm test && npm run build` |
| **Estimated runtime** | Unknown; record during execution |

---

## Sampling Rate

- **After every task commit:** Run the focused Vitest file changed by that task.
- **After every plan wave:** Run `cd ../../vue/stock-v2/vue-app && npm test`.
- **Before `$gsd-verify-work`:** Run `cd ../../vue/stock-v2/vue-app && npm test && npm run build`.
- **Backend contract smoke:** Run `./mvnw -pl stock-start -am verify -Dspring-boot.repackage.skip=true --fail-at-end --no-transfer-progress` only if backend contract files are touched or frontend tests uncover a backend mismatch.
- **Max feedback latency:** Record measured focused-test runtime during execution.

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 02-01-01 | 01 | 1 | FAPI-01, FAPI-02, FAPI-04 | T-02-01 / T-02-07 | Shared client owns credentials, envelope parsing, paginated request handling, and trace id preservation. | unit | `cd ../../vue/stock-v2/vue-app && npm test -- src/services/apiClient.test.ts src/services/backtestApi.test.ts src/services/opsApi.test.ts src/services/aiAccessApi.test.ts` | ✅ extend | ⬜ pending |
| 02-02-01 | 02 | 2 | FAPI-03 | T-02-02 / T-02-05 | Unsafe API-mode requests send `X-XSRF-TOKEN`; CSRF 403 remains distinguishable as `AUTH_CSRF_TOKEN_INVALID`. | unit | `cd ../../vue/stock-v2/vue-app && npm test -- src/services/apiClient.test.ts` | ✅ extend | ⬜ pending |
| 02-02-02 | 02 | 2 | AUTH-04, FAPI-05 | T-02-03 / T-02-04 | 401 handling performs one single-flight refresh/replay and stops after refresh failure or replay 401. | unit | `cd ../../vue/stock-v2/vue-app && npm test -- src/services/apiClient.test.ts` | ✅ extend | ⬜ pending |
| 02-03-01 | 03 | 3 | AUTH-03, FAPI-06 | T-02-01 / T-02-06 | Session restore calls `/api/v1/me`, stores explicit non-token state, and never persists access or refresh tokens. | unit | `cd ../../vue/stock-v2/vue-app && npm test -- src/services/authSession.test.ts src/services/authApi.test.ts` | ❌ W0 | ⬜ pending |
| 02-04-01 | 04 | 4 | AUTH-03, AUTH-04, FAPI-07 | T-02-06 | App shell shows register/login/logout/session restore and global session banner/toast without stale authenticated UI after failure. | component | `cd ../../vue/stock-v2/vue-app && npm test -- src/App.test.ts` | ❌ W0 | ⬜ pending |
| 02-05-01 | 05 | 5 | FAPI-07, FAPI-08 | T-02-07 | Invalid explicit runtime mode fails fast; API-mode backend failure surfaces errors and does not silently use mock data. | unit/integration | `cd ../../vue/stock-v2/vue-app && npm test -- src/services/runtimeDataMode.test.ts src/api-adapter-wiring.test.ts` | ✅ extend | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `../../vue/stock-v2/vue-app/src/services/authApi.test.ts` — browser auth endpoint wrapper tests for register, login, logout, `/me`, refresh, and CSRF bootstrap.
- [ ] `../../vue/stock-v2/vue-app/src/services/authSession.test.ts` — explicit session state transitions and no token storage assertions.
- [ ] `../../vue/stock-v2/vue-app/src/App.test.ts` — app boot restore, global session banner/toast, and auth UI placement.
- [ ] Shared paginated request tests in `../../vue/stock-v2/vue-app/src/services/apiClient.test.ts` so existing domain adapters can remove direct paginated `fetch` duplication.

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Browser httpOnly cookie inspection | AUTH-03, FAPI-06 | JavaScript tests cannot read httpOnly cookies by design. | Covered in Phase 5 browser smoke; Phase 2 should rely on backend contract and assert Vue stores no token values. |

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies.
- [x] Sampling continuity: no 3 consecutive tasks without automated verify.
- [x] Wave 0 covers all missing references.
- [x] No watch-mode flags.
- [ ] Feedback latency recorded during execution.
- [x] `nyquist_compliant: true` set in frontmatter.

**Approval:** pending
