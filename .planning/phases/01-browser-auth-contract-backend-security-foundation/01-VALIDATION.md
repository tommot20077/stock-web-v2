---
phase: 01
slug: browser-auth-contract-backend-security-foundation
status: draft
nyquist_compliant: true
wave_0_complete: false
created: 2026-05-30
---

# Phase 01 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit Jupiter + Spring Boot MockMvc + Testcontainers |
| **Config file** | `pom.xml`, `stock-start/pom.xml` |
| **Quick run command** | `./mvnw -pl stock-start -am test -Dtest=BrowserAuthFlowIT,AuthFlowIT --fail-at-end --no-transfer-progress` |
| **Full suite command** | `./mvnw -pl stock-start -am verify -Dspring-boot.repackage.skip=true --fail-at-end --no-transfer-progress` |
| **Estimated runtime** | ~120-300 seconds locally, depending on Testcontainers startup |

---

## Sampling Rate

- **After every task commit:** Run the focused test named in that task's `<verification>` block.
- **After every plan wave:** Run `./mvnw -pl stock-start -am verify -Dspring-boot.repackage.skip=true --fail-at-end --no-transfer-progress`.
- **Before `$gsd-verify-work`:** Full backend verification must be green, or the phase summary must record the exact unavailable dependency/runtime blocker.
- **Max feedback latency:** 300 seconds for focused security integration checks.

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 01-01-01 | 01 | 1 | SEC-01 / AUTH-01 / AUTH-02 | T-01 / T-02 | Browser register/login issue HttpOnly auth cookies and omit refresh token from browser response bodies | integration | `./mvnw -pl stock-start -am test -Dtest=BrowserAuthFlowIT --fail-at-end --no-transfer-progress` | ❌ W0 | ⬜ pending |
| 01-01-02 | 01 | 1 | AUTH-07 | T-03 | Explicit non-browser bearer token endpoint returns JSON tokens and does not set browser auth cookies | integration | `./mvnw -pl stock-start -am test -Dtest=AuthFlowIT --fail-at-end --no-transfer-progress` | ✅ | ⬜ pending |
| 01-02-01 | 02 | 1 | SEC-02 / SEC-03 / AUTH-06 | T-01 / T-06 | Cookie-authenticated unsafe requests without valid CSRF fail with HTTP 403 ApiResponse and CSRF-specific code | integration | `./mvnw -pl stock-start -am test -Dtest=BrowserAuthFlowIT --fail-at-end --no-transfer-progress` | ❌ W0 | ⬜ pending |
| 01-02-02 | 02 | 1 | SEC-02 / SEC-03 | T-01 | Cookie-authenticated unsafe requests with matching `XSRF-TOKEN` cookie and `X-XSRF-TOKEN` header reach business handling | integration | `./mvnw -pl stock-start -am test -Dtest=BrowserAuthFlowIT --fail-at-end --no-transfer-progress` | ❌ W0 | ⬜ pending |
| 01-03-01 | 03 | 2 | AUTH-05 / AUTH-06 | T-04 / T-06 | Refresh rotates refresh cookie; invalid/replayed refresh clears cookies and returns 401 ApiResponse | integration + service | `./mvnw -pl stock-start,stock-module-user -am test -Dtest=BrowserAuthFlowIT,RefreshTokenServiceTest --fail-at-end --no-transfer-progress` | ❌ W0 | ⬜ pending |
| 01-03-02 | 03 | 2 | AUTH-05 | T-04 | Logout revokes current browser refresh state and clears auth cookies only for current session | integration | `./mvnw -pl stock-start -am test -Dtest=BrowserAuthFlowIT --fail-at-end --no-transfer-progress` | ❌ W0 | ⬜ pending |
| 01-04-01 | 04 | 2 | SEC-04 | T-05 | Allowed Vue origin can use credentials and required headers; unknown origin is rejected/not allowed | integration | `./mvnw -pl stock-start -am test -Dtest=BrowserAuthFlowIT --fail-at-end --no-transfer-progress` | ❌ W0 | ⬜ pending |
| 01-05-01 | 05 | 3 | VER-04 | — | Contract documentation lists cookies, CSRF token/header, refresh/logout, 401/403, bearer path, and frontend responsibilities | source assertion | `test -f ai-docs/browser-auth-contract.md && rg "X-XSRF-TOKEN|HttpOnly|/api/v1/auth/refresh|/api/v1/auth/token|AUTH_CSRF" ai-docs/browser-auth-contract.md` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `stock-start/src/test/java/dowob/xyz/stockwebv2/start/BrowserAuthFlowIT.java` — red integration tests for browser cookies, CSRF, refresh/logout, CORS, and envelopes.
- [ ] `stock-module-user/src/test/java/dowob/xyz/stockwebv2/user/service/RefreshTokenServiceTest.java` or equivalent focused tests — red service coverage for rotation/replay semantics if not covered cleanly in integration tests.
- [ ] `ai-docs/browser-auth-contract.md` — documentation target for VER-04 source assertions.

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Browser DevTools confirms auth cookies are HttpOnly | SEC-01 | MockMvc can inspect `Set-Cookie`, but a real browser check catches frontend/backend origin and cookie attribute mistakes | In Phase 5 browser smoke flow, login and inspect cookies for `HttpOnly`, expected `SameSite`, expected `Secure` per environment, and readable `XSRF-TOKEN` only |

---

## Validation Sign-Off

- [x] All production-code tasks have RED/GREEN/REFACTOR test gates.
- [x] All unsafe cookie-authenticated requests have positive and negative CSRF tests.
- [x] Bearer-token compatibility is covered by regression tests.
- [x] CORS tests cover allowed and rejected origins.
- [x] Security failures preserve `ApiResponse` envelope and trace metadata.
- [x] Contract documentation has source assertions for the Phase 2 frontend contract.
- [x] No watch-mode flags in verification commands.
- [x] Feedback latency remains under 300 seconds for focused checks.
- [x] `nyquist_compliant: true` set in frontmatter after plans map every task to automated verification.

**Approval:** approved 2026-05-30
