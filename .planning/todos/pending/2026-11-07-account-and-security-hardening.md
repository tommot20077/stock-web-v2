---
created: 2026-11-07
title: 帳號與安全強化(自 security.md / architecture.md 移出的設計目標)
area: security
target: backlog(v1.0 之後;排程待 Yuan 決定)
files:
  - ai-docs/security.md
  - ai-docs/architecture.md
source: Yuan 2026-11-07 裁決 3C-4;quick-261107-1j4
---

## 為什麼有這條

`ai-docs/security.md` 與 `ai-docs/architecture.md` 原本混著「已實作的規則」與「尚未實作的設計目標」。
規範檔應該只描述現行事實,否則未來的實作者會把設計目標當成既有行為(或反過來以為已經有保護)。
Yuan 2026-11-07 裁決:未實作的段落整段搬到本 todo,規範檔原處只留一行指向本檔,章節標題與編號保留(其他文件以 §11、§13 等引用)。

下方各節為**原文搬移**(保留英文術語與原本的「not implemented」註記;註記日期為搬移前最後一次核對的 2026-11-06)。
已實作的事實(append-only 帳本、Audit Logging、§15 已存在的 login / register / refresh 限流、Account Lockout 等)仍留在規範檔,不在本檔。

## 1. Permission overrides(原 security.md §3)

**Permission overrides** (design only — **not implemented** as of 2026-11-06: there is no `user_permissions` table or loader; permissions come solely from `Role`): a `user_permissions` table would allow per-user GRANT/REVOKE of individual permissions.
Loading logic: `Role default permissions ∪ Individual GRANTs − Individual REVOKEs = Final permission set`

## 2. Change-password endpoint(原 security.md「Public Endpoints」Notes 與 §15 rate-limit 表)

- There is no change-password endpoint yet; when added it must **require authentication**

原 §15 Authentication Endpoints 表的列:

| Endpoint | Limit | Scope | Action on Exceed |
|----------|-------|-------|-----------------|
| `POST /api/v1/auth/change-password` | 3 requests/hour | Per User | HTTP 429 — endpoint **not implemented** yet |

## 3. User Deletion Security Flow(原 security.md §11)

> Design target — **not implemented** as of 2026-11-06 (no user deletion endpoint, soft delete, anonymization job or `user:permissions:*` cache exists).

Soft delete → immediate Redis cleanup → anonymization after 30 days → transaction records retained for 2 years after anonymization.

**Redis Cleanup Steps (executed immediately, synchronous):**
1. Set `user:auth:{userId}` status = DELETED
2. Increment token version (invalidates all Access Tokens)
3. Find all refresh tokens via `user:refresh:index:{userId}` reverse index
4. Delete all refresh token keys
5. Delete the reverse index key
6. Delete `user:permissions:{userId}` cache

**Anonymization Schedule (after 30 days, Spring Batch):**
- username → `deleted_user_{hash}`
- email → `deleted_{hash}@anonymized.local`
- `transactions` table is retained but userId points to anonymized user
- `transactions` table has DB-level trigger prohibiting UPDATE/DELETE (append-only)

(最後一點是現行事實,原文照搬以保持段落完整;現行規則仍以 security.md / architecture.md 的 append-only 敘述為準。)

## 4. Security Testing 工具鏈(原 security.md §14)

> Design target. As of 2026-11-06 none of the tools below is configured (no Dependabot config, SpotBugs, OWASP Dependency-Check, ZAP or Trivy in `pom.xml` / `.github/workflows/ci.yml`); CI runs `spotless:check`, unit tests, `stock-start` IT (`verify`), backend E2E and browser E2E.

| Phase | Tool | Action |
|-------|------|--------|
| Phase 1 | Dependabot | Automated dependency update PRs |
| Phase 1 | SpotBugs + Find Security Bugs | SAST — High severity = fail build |
| Phase 1 | OWASP Dependency-Check | Dependency vulnerability scanning |
| Phase 2 | ZAP | DAST |
| Phase 2 | Trivy | Container image scanning |

(「Spring Boot upgrade strategy」是現行政策,留在 security.md §14。)

## 5. Compliance Design 未實作項目(原 architecture.md〈Compliance Design (Taiwan Personal Data Protection Act)〉)

> Design target. As of 2026-11-06 only the append-only ledgers are implemented (`transactions` V8, `cash_ledger_entries` V15, `cash_dividends` V16, `share_adjustments` V17); user soft delete / anonymization / data export do not exist in code.

- **User deletion**: Soft delete → immediate Redis cleanup → anonymization scheduled after 30 days (Spring Batch) → optional full purge
- **Data export**: Full data export API (JSON + CSV), Phase 2
- **Transaction record retention**: Retained for 2 years after anonymization

(`transactions` append-only 那條是現行事實,留在 architecture.md。)

## 實作時要先決定的事

- 使用者刪除與匿名化要不要跟 PROJECT.md Out of Scope 的「Password reset, email verification, multi-device session management」(STATE.md Deferred Items 的 Account Management)合併成一個 phase。
- 匿名化後 append-only 帳本的保留期限與 trigger 的關係(匿名化需要改 `users`,不能改帳本)。
- Security Testing 工具鏈要放進 CI 的哪個 job、SAST High severity 擋 build 對既有程式的影響。
- 落地任一項後,把對應段落搬回 security.md / architecture.md 當現行規則,並從本檔刪除。
