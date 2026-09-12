# Lesson Learned: Round-2 Forensic Analysis of Finding R6 (ISP Disappearance Reconciliation)

**Identifier:** `LL-ROUND-2-R6-ISP-DISAPPEARANCE`  
**Status:** Historical forensic knowledge; non-authoritative practical engineering note.

---

## 1. Authority Boundary & Purpose

> **Important Boundary:** This document is historical forensic knowledge and prior investigation evidence. It is **NON-AUTHORITATIVE** and does **NOT** override current code, `AGENTS.md`, or product contracts (`Target Product Contract v0.6`, `G1-G8 Consolidated Architecture Summary`). Current code and authoritative project documents remain primary.

This note captures the complete architectural diagnosis, seam evaluation, and safety invariants established during the investigation and remediation of **Finding R6** (`IspDisappearanceReconciler` production wiring).

Its primary purpose is to prevent future agents from:
1. Misclassifying ISP disappearance as a two-fetch, threshold-based, or heuristic reconciliation problem.
2. Re-introducing identity contamination by relaxing snapshot completeness or non-blank `userID` invariants.
3. Attempting to reopen closed lifecycle designs without understanding the fail-closed boundaries of the authoritative subscriber refresh.

---

## 2. Core Architectural Context: Finding R6

### R6 — `IspDisappearanceReconciler` Production Wiring & Snapshot Identity Integrity
* **Target Components:** `DashboardViewModel`, `LocalAccountRepositoryImpl`, `IspDisappearanceReconciler`, `DataOperationCoordinator`.
* **Final Status:** `CONFIRMED PRODUCTION DEFECT — FIXED & VERIFIED IN bb210f3`

### Problem Description
`IspDisappearanceReconciler` was originally implemented and unit-tested to enforce Target Product Contract clause **RC-04** (transitioning local active accounts to `isHistoryOnlySubscriber = true` when an ISP subscriber legitimately disappears from the network provider). However, it had **zero production callers** in the codebase.

As a consequence:
1. Legitimate ISP deletions/cancellations left stale `LocalAccount` records in `isHistoryOnlySubscriber = false` indefinitely.
2. If an ISP recycled that username to a new subscriber, the active username lookup matched the stale active account instead of establishing a separate second-generation account.
3. Downstream operations risked associating new operational intents or payments with the stale subscriber's financial history.

---

## 3. Production Mechanism & Seam Architecture

### Safe Seam Boundaries
The reconciler was wired into `DashboardViewModel.loadDashboardData()` within the authoritative ISP user list fetch block (`searchUsers(query = "", startIndex = 0, rowCount = 5000)`), executing under `LocalAccountRepositoryImpl.reconcileIspDisappearance(...)`.

The production path strictly adheres to these boundary invariants:
1. **Coordination Boundary:** Network I/O occurs strictly **outside** the coordinator. Only the Room transaction and reconciliation logic execute inside `DataOperationCoordinator.withOperation(DataOperationMode.SYNC)`.
2. **Deterministic Coroutine Scope:** ViewModel data loading returns `kotlinx.coroutines.Job` so calling layers and test suites can deterministically join and await Room transaction completion without polling loops or arbitrary delays.
3. **Outbox Synchronization:** Every account transitioned to `isHistoryOnlySubscriber = true` immediately queues a sync outbox entry (`INV-08`) to propagate monotonically across peer devices via cloud Firestore sync.

---

## 4. Key Safety Invariants & Guard Contracts

The R6 remediation established three mandatory fail-closed barriers:

### 1. Authoritative Snapshot Completeness Contract
Reconciliation requires a complete and unfragmented snapshot of the reseller's subscriber population.
```kotlin
val total = subListRes.totalCount
val isComplete = subListRes.itemsList != null &&
        total != null &&
        total > 0 &&
        items.isNotEmpty() &&
        items.size >= total
```
* **Zero-Result Anomaly Guard:** If `totalCount == 0` or `items.isEmpty()`, `isComplete` evaluates to `false`. An empty gateway response (e.g. temporary reseller account suspension or gateway error) will **never** trigger mass-historization of active subscribers.
* **Partial Page Guard:** If `items.size < totalCount`, the fetch is partial and reconciliation is aborted immediately.

### 2. Snapshot Identity Integrity Guard (Non-Blank `userID` Contract)
A complete snapshot is **NOT reconciliation-safe** if any returned record has a missing, null, or blank `userID`.
```kotlin
val allItemsHaveValidUserId = items.all { it.userID.isNotBlank() }
if (allItemsHaveValidUserId) {
    val authoritativeUsernames = items.map { it.userID.trim() }.toSet()
    localAccountRepository.reconcileIspDisappearance(authoritativeUsernames, isFetchComplete = true)
} else {
    // Log warning and skip reconciliation without altering dashboard UI display
}
```
* **Rationale:** Silently filtering out malformed records via `mapNotNull` while passing `isFetchComplete = true` would omit the malformed user from the authoritative set, causing the reconciler to falsely treat the corresponding local account as disappeared.
* **Fail-Closed Behavior:** If even one item has an invalid/blank username, reconciliation is skipped entirely for that cycle, while the dashboard UI displays available data normally.

### 3. Rejection of Two-Fetch Confirmation & Heuristics
During the R6 design gate, two alternative designs were formally evaluated and **permanently rejected**:
* **Two-Fetch Confirmation:** Rejected because delaying the transition leaves the recycled-username vulnerability window open longer, adding local state tracking complexity without eliminating server-side race conditions.
* **Arbitrary Shrink Heuristics / Population Thresholds:** Rejected because the Target Product Contract (RC-04 §7) defines the single complete successful snapshot as authoritative. Fail-closed completeness and identity validation provide the intended fail-closed client-side protection against the failure classes covered by these guards.

---

## 5. Prevention Rules for Future Agents

1. **Do NOT add heuristic thresholds to reconciliation:** Never add percentage-drop checks, count limits, or multi-fetch state machines to `IspDisappearanceReconciler` or its caller. Completeness (`items.size >= totalCount`) and identity integrity (`items.all { it.userID.isNotBlank() }`) are the current reconciliation gates.
2. **Do NOT silently filter malformed identities in authoritative sets:** If an external dataset is being used to infer absence or deletion, omitting malformed entries via `mapNotNull` or `filter` turns data corruption into false business absence.
3. **Do NOT run network calls inside `DataOperationCoordinator`:** Network latency or socket timeouts inside the coordinator block concurrent app operations and create deadlocks. Only wrap the transactional database mutation inside `withOperation(SYNC)`.
4. **Do NOT infer account deletion from local absence:** Disappearance transitions accounts to `isHistoryOnlySubscriber = true`; it **never** deletes Room rows or ledger history (`INV-02`).
