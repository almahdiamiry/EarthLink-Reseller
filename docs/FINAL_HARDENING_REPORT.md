> [!WARNING]
> **HISTORICAL / SUPERSEDED ARTIFACT (NON-AUTHORITATIVE)**
> This document is a historical development artifact and is NOT active implementation authority.
> Active authority is strictly defined by the Frozen Implementation Authority Bundle in `docs/authority/`:
> 1. `Target Product Contract v0.6.md`
> 2. `G1-G8 Consolidated Architecture Summary.md`
> 3. `Final Independent Adjudication Memo.md`
> 4. `EARTHLINK_V1_HANDOVER.md`
> 5. `EARTHLINK_IMPLEMENTATION_HANDOVER_APPENDIX_v1.0.md`
> Under INV-13 and frozen G4/G7 architecture, `DEAD_LETTER` is NOT an accepted terminal business state for user mutations; outbox items remain durable and retryable.

---

# Final Hardening Report — Earthlink Reseller App

---

## A. Problems Found & Resolved

1. **Unintended Money Scaling Violations**: Direct `*1000` or `/1000` implicit multiplications in UI parsing / balance displays were eliminated in Phase 1, enforcing raw IQD values across all models.
2. **Dual Remote Processing Pipelines**: Previously, `REALTIME` snapshot listeners and `PULL` sync cycles processed remote events through separate uncoordinated code paths. Unified under `RemoteSyncCoordinator`.
3. **Outbox Feedback Loops**: Remote event applications previously created secondary `SyncOutbox` records, causing recursive sync loops. Solved by direct DAO apply without outbox insertion.
4. **Audit Feedback Echoes**: Diagnostic/system audit logs generated outbox records that triggered recursive sync attempts. Solved by `AuditOrigin` classification restricting outbox eligibility strictly to `USER_ACTION`.
5. **Account/Ledger Zombie Resurrections**: Late-arriving child ledger entries or stale account upserts after account deletion caused accounts to be resurrected. Solved by tombstone tracking in `metadataDao` (`tombstone:account:$id` and `tombstone:ledger:$id`).
6. **Timestamp Drift on Remote Apply**: Remote ledger applications recalculated account balances while updating `updatedAt` to system time, causing artificial local timestamp drift. Fixed by preserving business `updatedAt` timestamps.
7. **Concurrent Sync Pipelines**: Multiple rapid sync calls caused parallel competing sync executions. Solved by `AtomicBoolean(pendingRunAfterCurrent)` single-flight coalescing in `SyncRepositoryImpl.kt`.
8. **Cursor Contention Data Loss**: A shared global `last_sync_timestamp` caused diverging entity timestamps (e.g. Accounts at 5000, Ledgers at 3000) to skip valid remote entries during pull queries. Solved by introducing independent per-collection watermarks (`last_sync_local_accounts`, etc.).
9. **Test Rigidity Issue**: `InfiniteCycleAdversarialIntegrationTest` was improperly dropping cycles 2-20 as duplicates. Rewritten to dynamically advance timestamps and prove stability against 20 distinct consecutive remote applications without outbox generation.

---

## B. Changes Implemented

- `RemoteSyncCoordinator.kt`: Central ingestion pipeline with deduplication, tombstone zombie protection, and version monotonicity.
- `SyncRepositoryImpl.kt`: Global single-flight coalescing loop, per-collection sync cursors, and `requestSync(reason: SyncReason)` unified entry point.
- `OutboxManager.kt`: Dead-letter state handling and active mutation checking.
- `Repositories.kt` (`AuditRepositoryImpl`): Strict `AuditOrigin.USER_ACTION` outbox eligibility guard.
- `MoneyParser.kt`: Raw IQD parsing and exact round-trip formatting.

---

## C. Tests Executed & Results

All test suites executed against the local JVM using Robolectric / Room in-memory database:

- `PerCollectionSyncCursorTest.kt`: **PASS** (Independent cursor monotonicity and legacy fallback verification).
- `InfiniteCycleAdversarialIntegrationTest.kt`: **PASS** (Rigid 20-cycle distinct payload adversarial sync convergence without loops).
- `RemoteEventDualPathTest.kt`: **PASS** (Realtime -> Pull vs Pull -> Realtime equivalence).
- `AuditDoesNotTriggerSyncRegressionTest.kt`: **PASS** (Audit origin isolation verification).
- `RemoteApplyDoesNotMutateBusinessTimestampTest.kt`: **PASS** (Business timestamp preservation and clock skew independence).
- `DeletionOrderingAndMonotonicityTest.kt`: **PASS** (Tombstone zombie protection and version monotonicity).
- `OutboxStateMachineTest.kt`: **PASS** (Exhaustive outbox state transitions and dead-letter isolation).
- `MoneyParserTest.kt`: **PASS** (Exact UI/DB money round-trip formatting).

---

## D. Static Audit Summary

Global static codebase searches verified zero feedback loops across:
- `triggerSync` / `requestSync`
- `outboxDao.insert` / `OutboxManager`
- `auditRepository.log` / `AuditOrigin`
- `updatedAt` / `createdAt`

---

## E. Remaining Risks & Mitigations

During our extensive code-hardening phase, several critical architectural risks were identified, analyzed, and mitigated to safeguard the synchronization pipeline's integrity:

1. **Cursor Cross-Collection Data Loss (Mitigated)**:
   - *Risk*: A shared global synchronization timestamp watermark caused entities with lagging remote timestamps to be permanently skipped during pulls.
   - *Mitigation*: Refactored the cursor subsystem to track independent, per-collection watermarks (e.g., `last_sync_local_accounts`, `last_sync_local_ledger_entries`), ensuring complete data capture.

2. **Test False-Positives in Multi-Cycle Iterations (Mitigated)**:
   - *Risk*: The adversarial loop tests did not advance payloads/versions across cycles, resulting in false-positive "no-loop" passes that skipped real evaluation.
   - *Mitigation*: Hardened the test harness to advance and randomize payloads and timestamps dynamically across 20 full cycles, validating that no spurious outbox records are generated.

3. **Realtime Bootstrap vs. One-Shot Race (Mitigated)**:
   - *Risk*: Simultaneous startup initialization and manual triggers caused overlapping pull processes, leading to double-processing or cursor corruption.
   - *Mitigation*: Enforced strict mutual exclusion via a shared single-flight Mutex lock (`singleFlightMutex`), ensuring bootstrap initialization and one-shot syncs run in series.

4. **Ledger Timestamp Semantic Mismatch (Mitigated)**:
   - *Risk*: Remote ledger entries mapped via the standard `updatedAt` field deviated from local transactional creation time, violating transaction sequencing rules.
   - *Mitigation*: Enforced ledger timestamp consistency by mapping remote ledger event versions using `createdAt` or `occurredAt`, matching local transaction semantics.

5. **Incomplete Audit Trigger Verification (Mitigated)**:
   - *Risk*: Spurious system audit logs were not actively checked, presenting a risk of feedback loops if system logs triggered sync enqueues.
   - *Mitigation*: Rewrote the regression suite with a custom tracking FakeSyncRepository, explicitly verifying that `SYSTEM_ACTION` and `SYNC_FAILURE` origins produce exactly 0 sync calls, while only user actions initiate sync.

6. **WorkManager Thread Coordination & Delay**:
   - *Risk*: Since Android WorkManager is controlled by OS battery and scheduling optimization policies, sync events can be delayed or throttled in background modes.
   - *Mitigation*: Implemented a robust reason-based `requestSync(reason)` scheduler. High-priority user actions and manual requests utilize `ExistingWorkPolicy.REPLACE` to immediately cancel and preempt queued jobs, while startup, periodic, and recovery triggers use non-intrusive background-friendly scheduling policies.

---

## F. Invariant Matrix

| Invariant ID | Description | Status | Evidence / Verification |
|---|---|---|---|
| **INV-01** | No direct *1000 business-money conversion remains | **PASS** | `MoneyParserTest.kt` & Phase 1 static removal |
| **INV-02** | No unauthorized money conversion remains | **PASS** | `MoneyParser.kt` raw IQD parsing |
| **INV-03** | Remote realtime and pull feed one processing pipeline | **PASS** | `RemoteSyncCoordinator.kt` |
| **INV-04** | Duplicate remote events are safely deduplicated | **PASS** | `RemoteEventDualPathTest.kt` |
| **INV-05** | Remote apply never creates Outbox | **PASS** | `InfiniteCycleAdversarialIntegrationTest.kt` |
| **INV-06** | Remote apply does not mutate business timestamps | **PASS** | `RemoteApplyDoesNotMutateBusinessTimestampTest.kt` |
| **INV-07** | Ledger conflict timestamps use consistent semantics | **PASS** | `SyncConflictResolverTest.kt` |
| **INV-08** | Dead-letter is not treated as active pending work | **PASS** | `OutboxStateMachineTest.kt` |
| **INV-09** | Sync failure audit cannot trigger another sync | **PASS** | `AuditDoesNotTriggerSyncRegressionTest.kt` |
| **INV-10** | System audit origin is explicitly classified | **PASS** | `AuditOrigin` enum & `Repositories.kt` |
| **INV-11** | syncUserSettings does not write when data is unchanged | **PASS** | `SyncRepositoryImpl.kt` mutation guard |
| **INV-12** | All sync triggers use one coordinator | **PASS** | `SyncRepositoryImpl.kt` single entry point |
| **INV-13** | Synchronization is single-flight | **PASS** | `SyncRepositoryImpl.kt` coalescing loop |
| **INV-14** | Reordered deletion events cannot resurrect entities | **PASS** | `DeletionOrderingAndMonotonicityTest.kt` |
| **INV-15** | Older remote state cannot overwrite newer accepted state | **PASS** | `DeletionOrderingAndMonotonicityTest.kt` |
