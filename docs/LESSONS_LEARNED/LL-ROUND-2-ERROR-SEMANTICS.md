# Lesson Learned: Round-2 Error-Semantics Forensic Closure (API-02, API-03, API-04, DATA-02)

**Identifier:** `LL-ROUND-2-ERROR-SEMANTICS`
**Status:** Historical forensic knowledge; non-authoritative practical engineering note.

---

## 1. Authority Boundary & Purpose

This document is historical forensic knowledge and prior investigation evidence. It is **NON-AUTHORITATIVE** and does NOT override current code, `AGENTS.md`, the Target Product Contract, architectural authority, or active verification.

This record documents forensic closure/classification of error-semantic findings. It does **NOT** authorize a production code change.

Its purpose is to prevent future agents from misclassifying already-investigated error-semantic patterns as correctness defects when downstream callers independently guard against harmful decisions.

---

## 2. Finding Summary

| ID | Final Classification | Production Consequence |
|:---|:---|:---|
| **API-02** | `CLOSED — VALID EXISTING BEHAVIOR` | Cost failure becomes `0.0`, but activation caller rejects `cost <= 0` before dispatch; other callers only display/fallback. |
| **API-03** | `CLOSED — INFORMATIONAL / FRESHNESS ONLY` | Failure becomes `0` test-user count; dashboard-only, no operational/financial decision. |
| **API-04** | `CLOSED — STALE / DEAD CODE` | Zero production callers; no production consequence. |
| **DATA-02** | `CLOSED — VALID EXISTING BEHAVIOR` | Failure/empty prepaid-needed can become `0.0`, but Dashboard has local calculation fallback; residual `0.0` is display-only. |

---

## 3. Shared Pattern: `exception → domain-zero`

All four findings share the implementation pattern: an API-call exception or parse failure is caught and replaced with a domain-zero value (`0` or `0.0`).

**Safety is determined by the downstream caller and decision boundary.** The four current paths were individually traced end-to-end and no harmful production consequence was established.

The pattern is not universally benign. Its safety depends on whether any production caller interprets the zero as a legitimate domain value that drives a financial, operational, or user-facing mutation.

---

## 4. API-02 — `getAccountCost()` → exception → `0.0`

**Mechanism:** `Repositories.kt:390` — outer `try/catch(Exception)` returns `0.0`. Inner parse block (`:448`) also silently defaults to `0.0`. Double-checked locking with mutex + cache.

**Production callers (5 across 4 files):**
- `EarthlinkSearchViewModel.kt:97` — thin wrapper, catches exception, returns `0.0`, used for UI display only.
- `EarthlinkSearchViewModel.kt:353` — `previewPackageCost()`, displays cost preview; exception → error message.
- `EarthlinkSearchViewModel.kt:469` — **activation dispatch guard**: `if (!cost.isFinite() || cost <= 0.0) → abort with error, no dispatch`.
- `UserDetailScreenV2.kt:328` — if `fetchedCost > 0.0` use it; else fall back to local package price from database. Display-only.
- `SettingsScreen.kt:2051` — loads API costs for settings display; exception → `apiCosts[key] = 0.0`. Display-only.

**Adversarial trace:** Gateway fails → `0.0` → activation caller at line 470 evaluates `cost <= 0.0` → aborts with "Failed to determine a valid IQD package cost" → no pending operation recorded, no gateway dispatch, no financial harm.

**Future-agent prevention rule:** Do NOT classify the fallback as a financial defect without proving that a production caller interprets failed cost retrieval as a valid positive financial operation and proceeds to dispatch or materialize.

---

## 5. API-03 — `getTestUsersCount()` → exception → `0`

**Mechanism:** `Repositories.kt:237` — outer `try/catch(Exception)` returns `0`. Inner JSON parse block (`:251`) defaults to `0` on failure.

**Production callers (1):**
- `DashboardViewModel.kt:186` — sets `_testCount.value = tests`; exception handler at line 188 sets `_testCount.value = 0`. Display-only dashboard counter.

**Adversarial trace:** Gateway fails → `0` → Dashboard shows "0 test users remaining" when actual count is unknown. No capacity limit, no package selection gating, no financial action, no provisioning decision depends on this value.

**Future-agent prevention rule:** Do NOT promote dashboard-only zero/failure ambiguity to a correctness defect unless a real operational decision consumes the value.

---

## 6. API-04 — `getActiveTestUsersCount()` → exception → `0` (DEAD CODE)

**Mechanism:** `Repositories.kt:262` — catches all exceptions, returns `0`. Special-cases HTTP 401 (clears auth token, throws).

**Production callers:** **ZERO.** The method is defined in the `EarthlinkGateway` interface (`Interfaces.kt:12`) and implemented in `Repositories.kt:262`, but has no production callers anywhere. Only test mocks reference it.

**Future-agent prevention rule:** Do NOT report an uncalled API method as a production failure-mode defect. Prove a production caller before assigning production impact.

---

## 7. DATA-02 — Prepaid-needed gateway failure → `0.0`

**Mechanism:** `Repositories.kt:294` — `safeApiCall { apiService.getPrepaidNeeded() }` → `calculatePrepaidNeededFromPayload(value)`. If `value == null`, returns `0.0`. If no rows parsed, returns `0.0`.

**Production callers (1):**
- `DashboardViewModel.kt:144` — two-tier fallback:
  1. Gateway call → if `needed == 0.0 && accounts.isNotEmpty()` → local `accounts.sumOf { acc.currentPriceIqd - acc.advanceIqd }` fallback.
  2. Exception → same local `sumOf` fallback at line 172.
  3. Both fail → `_prepaidNeeded.value = 0.0` at line 177.

**Adversarial trace:** Gateway fails or returns empty → local calculation succeeds → Dashboard shows local estimate. Both fail → Dashboard shows `0.0`. No payment, dispatch, or provisioning action consumes this value. Purely informational forecast display.

**Future-agent prevention rule:** Do NOT treat informational forecast fallback as financial corruption without proving that the fallback controls an actual financial or operational mutation.

---

## 8. Important Negative Knowledge

**Do NOT perform a broad "zero means error" refactor based only on these findings.**

Any future change to error semantics must first prove the complete chain:

```
API fallback (exception → zero)
    ↓
production caller receives zero
    ↓
caller interprets zero as legitimate domain value
    ↓
harmful business/financial decision occurs
```

Only if all four links are proven does the pattern become a new defect candidate. Theoretical ambiguity at the API boundary is insufficient evidence for corrective action when downstream callers independently guard against it or the values are purely informational.

---

## 9. Reopening Guidance

These findings are closed based on concrete production path tracing and empirical evidence. Future agents may reopen a specific finding **only** if:
- A new production caller is added that interprets the value as a legitimate domain input to a financial/operational decision, or
- The existing caller's guard logic is removed or materially changed, or
- Product contract changes require non-zero failure signaling at the API boundary.

---

*End of LL-ROUND-2-ERROR-SEMANTICS*
