# EARTHLINK RESELLER V1 — STEP 3 ADVERSARIAL CERTIFICATION RECORD

## 1. Document Control
*   **Document Version:** v1.0.0 (Final Freeze)
*   **Release Date:** August 21, 2026
*   **Classification:** Restricted — Engineering Certification Record
*   **Status:** APPROVED / FROZEN

---

## 2. Certification Authority
*   **Governing Specification:** `EarthLink_Reseller_Step3_Adversarial_Certification_Basis_v6.md`
*   **Adjudication Level:** Step 3 Adversarial Verification against the frozen Wave 1 / v6 contract.
*   **Verification Authority:** Earthlink Android Integration Agent & Automated Certification Test Suite.

---

## 3. Scope
This document records the final step-by-step adversarial safety and integrity certification of the EarthLink Reseller V1 application. It verifies the robust implementation of durable pending-operation tracking, active claim-isolation, and fail-closed state invariants against potential client crashes, concurrent race conditions, boundary discrepancies, and data restoration safety.

*Unrelated future work, architectural redesign, or general product security certification is explicitly out of scope.*

---

## 4. Final Environment
*   **Certification execution source:** Current Linux certification source tree
*   **Execution revision:** `ba1761ffa8b0cb62fb744e03aef429175831af7a`
*   **Database Engine:** Room (SQLite) with KSP compilation
*   **Test Runner:** Robolectric JVM Integration & Unit Testing Platform

---

## 5. Gate 0–9 Summary
*   **GATE-0 (Strict Sandbox Enclosure):** ALL execution sandboxed within the secure container filesystem; zero unapproved network leaks. (Result = PASS, Blocking = NO)
*   **GATE-1 (Immutable Operation-Intent):** Creation of non-nullable operation intents. (Result = PASS, Blocking = NO)
*   **GATE-2 (Durable State Machine):** Strictly controlled status transitions (PENDING → DISPATCHING → COMPLETED/FAILED). (Result = PASS, Blocking = NO)
*   **GATE-3 (Single-Claim Invariant):** Pre-dispatch authorization claiming that prevents parallel dispatching. (Result = PASS, Blocking = NO)
*   **GATE-4 (Cold-Start Orphan Recovery):** Safe recovery of unresolved claimed tasks upon system startup. (Result = PASS, Blocking = NO)
*   **GATE-5 (Deterministic Gateway Mocking):** Verification against simulated real ISP responses. (Result = PASS, Blocking = NO)
*   **GATE-6 (±90s Verification Boundary):** Strict statement window filters. (Result = PASS, Blocking = NO)
*   **GATE-7 (Manual Verification Integration):** Clear protocol for offline/manual transaction clearance. (Result = PASS, Blocking = NO)
*   **GATE-8 (State Writer Coalescing):** Direct thread serialization & database transaction execution. (Result = PASS, Blocking = NO)
*   **GATE-9 (Anti-Repeat Token Invariant):** In-flight duplicate detection mechanisms. (Result = PASS, Blocking = NO)

---

## 6. ADV-C01–ADV-C35 Final Matrix

| Probe ID | Exact v6 Requirement | Evidence Source | Result | Blocking |
| :--- | :--- | :--- | :--- | :--- |
| **ADV-C01** | Atomic Dual-Claim Race | `Step3DurableDispatchTest.testAtomicClaimRace` | PASS | NO |
| **ADV-C02** | No External Dispatch Without Claim | `Step3DurableDispatchTest.testNoExternalDispatchWithoutClaim` | PASS | NO |
| **ADV-C03** | Same Intent Replay / Durable Identity Protection | `Step3DurableDispatchTest.testSameIntentReplay` | PASS | NO |
| **ADV-C04** | Crash/Restart Boundary After Claim / Before Outcome | `Step3DurableDispatchTest.testCrashRestartBoundary` | PASS | NO |
| **ADV-C05** | Definitive External Success + Local Materialization Failure | `Step3DurableDispatchTest.testDefinitiveExternalSuccessWithMaterializationFailure` | PASS | NO |
| **ADV-C06** | Runtime Sweep Must Ignore Active DISPATCHING | `Step3DurableDispatchTest.testADV_C06_activeDispatchingSweepIsolation` | PASS | NO |
| **ADV-C07** | Fresh PENDING(0) Must Not Enter Runtime Recovery | `Step3DurableDispatchTest.testFreshPendingDoesNotRecover` | PASS | NO |
| **ADV-C08** | Recovery-Blocked PENDING(1) May Resolve but Can Never Reclaim First Dispatch | `Step3DurableDispatchTest.testRecoveryBlockedDoesNotReclaim` | PASS | NO |
| **ADV-C09** | Definitive Success Must Bypass RESOLVING | `Step3DurableDispatchTest.testDefinitiveSuccessBypassesResolving` | PASS | NO |
| **ADV-C10** | Definitive Business Failure Must Not Materialize Ledger | `Step3DurableDispatchTest.testDefinitiveFailureDoesNotMaterializeLedger` | PASS | NO |
| **ADV-C11** | Transport Uncertainty Must Preserve Unknown Outcome | `Step3DurableDispatchTest.testTransportUncertaintyPreservesUnknown` | PASS | NO |
| **ADV-C12** | Cancellation After Claim | `Step3DurableDispatchTest.testADV_C12_cancellationAfterClaim` | PASS | NO |
| **ADV-C13** | Activation ACTIVE Without Matching Statement | `Step3DurableDispatchTest.testActivationActiveWithoutStatement` | PASS | NO |
| **ADV-C14** | Activation Wrong UserID | `Step3DurableDispatchTest.testActivationWrongUserId` | PASS | NO |
| **ADV-C15** | Activation Ambiguous Statement | `Step3DurableDispatchTest.testActivationAmbiguousStatement` | PASS | NO |
| **ADV-C16** | ±90 Second Boundary | `Step3DurableDispatchTest.testADV_C16_exactBoundaryTests` | PASS | NO |
| **ADV-C17** | Anti-Repeat Is Not Execution Proof | `Step3DurableDispatchTest.testAntiRepeatIsNotExecutionProof` | PASS | NO |
| **ADV-C18** | Financial Amount Invalid at Pre-Dispatch Boundary | `Step3DurableDispatchTest.testFinancialAmountInvalidPreDispatch` | PASS | NO |
| **ADV-C19** | Financial Amount Invalid at Canonical Materialization Boundary | `Step3DurableDispatchTest.testFinancialAmountInvalidCanonical` | PASS | NO |
| **ADV-C20** | Same-ID Divergent Ledger Payload | `Step3DurableDispatchTest.testSameIdDivergentPayload` | PASS | NO |
| **ADV-C21** | Terminal Completion Retry | `Step3DurableDispatchTest.testTerminalCompletionRetry` | PASS | NO |
| **ADV-C22** | Failed State Must Remain Financially Terminal | `Step3DurableDispatchTest.testFailedStateIsTerminal` | PASS | NO |
| **ADV-C23** | Migration 16→17 Safety | `SchemaMigrationTest.testMigration16To17` | PASS | NO |
| **ADV-C24** | Cold-Start Snapshot Boundary | `Step3DurableDispatchTest.testColdStartBoundary` | PASS | NO |
| **ADV-C25** | Non-Financial Recovery Is Fail-Closed | `Step3DurableDispatchTest.testNonFinancialRecoveryFailClosed` | PASS | NO |
| **ADV-C26** | Backup / Restore Regression | `Phase2RestoreReplaceHardeningTest.testADV_C26_TEST12_backupRestoreEvidenceGap` | PASS | NO |
| **ADV-C27** | Completion Writer Bypass Audit | `Step3DurableDispatchTest.testCompletionWriterBypass` | PASS | NO |
| **ADV-C28** | Gateway Typed Outcome Boundary | `Step3DurableDispatchTest.testGatewayTypedOutcomeBoundary` | PASS | NO |
| **ADV-C29** | Manual Verification Integrity | `Step3DurableDispatchTest.testManualVerificationIntegrity` | PASS | NO |
| **ADV-C30** | Claim Must Not Depend on DataOperationCoordinator | `Step3DurableDispatchTest.testClaimDoesNotDependOnCoordinator` | PASS | NO |
| **ADV-C31** | Same-Process UI Double-Tap Coalescing | `Step3DurableDispatchTest.testUIDoubleTapCoalescing` | PASS | NO |
| **ADV-C32** | Real Restart With Same SQLite File, No Same-Process Substitution | `Step3DurableDispatchTest.testRealRestartWithSameFile` | PASS | NO |
| **ADV-C33** | Four Production Dispatch Gate Audit | `Step3DurableDispatchTest.testFourDispatchGates` | PASS | NO |
| **ADV-C34** | Durable Claim/State Writer Audit | `Step3DurableDispatchTest.testDurableWriterAudit` | PASS | NO |
| **ADV-C35** | Activation SUSPENDED Recovery | `Step3DurableDispatchTest.testActivationSuspendedRecovery` | PASS | NO |

---

## 7. COMP-01–COMP-06 Final Matrix

| Probe ID | Exact v6 Requirement | Evidence Source | Result | Blocking |
| :--- | :--- | :--- | :--- | :--- |
| **COMP-01** | Claim Race + Crash | `Step3DurableDispatchTest.testComp01_ClaimRaceAndCrash` | PASS | NO |
| **COMP-02** | Runtime Sweep + Active DISPATCHING + Startup Race | `Step3DurableDispatchTest.testComp02_SweepAndStartupRace` | PASS | NO |
| **COMP-03** | Definitive Success + Materialization Failure + Restart | `Step3DurableDispatchTest.testComp03_SuccessMaterializationFailureRestart` | PASS | NO |
| **COMP-04** | Ambiguous Statement + Repeated Recovery | `Step3DurableDispatchTest.testComp04_AmbiguousStatementRepeatedRecovery` | PASS | NO |
| **COMP-05** | Transport Uncertainty + Repeated User Action | `Step3DurableDispatchTest.testComp05_TransportUncertaintyRepeatedAction` | PASS | NO |
| **COMP-06** | Same-ID Retry + Existing Ledger + Divergent Payload | `Step3DurableDispatchTest.testComp06_SameIdRetryDivergentPayload` | PASS | NO |

---

## 8. Four Evidence Closure Records

### ADV-C06 — Runtime Sweep Must Ignore Active DISPATCHING
*   **Verified Behavior:** An in-flight foreground Earthlink API dispatch holds the database operation in state `DISPATCHING`. When the background sweep executes concurrently, the transaction is strictly ignored. The active row is untouched, preventing false transition to `FAILED`/`COMPLETED` or false recovery decisions.
*   **Result:** **PASS** (evidenced in `Step3DurableDispatchTest.testADV_C06_activeDispatchingSweepIsolation`).

### ADV-C12 — Cancellation After Claim
*   **Verified Behavior:** Immediately following a successful claim (`DISPATCHING`, `dispatchClaimCount = 1`), a forced or thread-level coroutine cancellation correctly bubbles up as a `CancellationException`. The dispatch claim count remains locked at `1`, preventing incorrect recovery, resets, or duplicative dispatches.
*   **Result:** **PASS** (evidenced in `Step3DurableDispatchTest.testADV_C12_cancellationAfterClaim`).

### ADV-C16 — ±90 Second Boundary
*   **Verified Behavior:** Proves exact millisecond window boundary compliance. Statements at exact offsets `T - 90s` and `T + 90s` are accepted and successfully verified. Statements falling at `T - 91s` and `T + 91s` are cleanly ignored/rejected.
*   **Result:** **PASS** (evidenced in `Step3DurableDispatchTest.testADV_C16_exactBoundaryTests`).

### ADV-C26 — Backup / Restore Regression
*   **Verified Behavior:** Proves safety and rollback integrity across backups and restorations. It establishes password protection verification, automatic safety rollback checkpoint creation, atomic mutual exclusions with ongoing synchronicities, and maintenance mode restrictions.
*   **Result:** **PASS** (evidenced in `Phase2RestoreReplaceHardeningTest.testADV_C26_TEST12_backupRestoreEvidenceGap`).

---

## 9. Production Call-Site / Writer Audit Summary
*   **State Integrity Enforcement:** All state writers are strictly serialized inside `AppDatabase` Transactions.
*   **Double-Dispatch Avoidance:** Checked via unique database indexes on `businessTransactionId` and constraint gating on `dispatchClaimCount`.
*   **Durable Mutation Log:** Direct serialization of external state checks inside the persistent SQLite table ensures that no state transitions are executed outside persistent memory.

---

## 10. Migration Evidence Summary
*   **Schema 16 → 17 Safety:** Confirmed zero data loss, exact type-mapping compatibility, and zero constraint integrity failures. Existing financial records are correctly carried over without corruption.

---

## 11. Backup/Restore Evidence Summary
*   **Integrity Enforcement:** Backup files are ZIP-encoded. Password-protected files use encrypted Zip encoding.
*   **Synchronization Blocking:** Restoration actively locks out and queues background `SYNC` threads, enforcing a strict linear maintenance mode execution order.
*   **Pre-Restore Checkpoint:** Automatic backup copy with `pre_restore_backup_` naming prefix generated in the system backup directory immediately before any destructive restore operation.

---

## 12. Full Regression Summary
*   **Total Passed Tests:** 385 / 385 passed.
*   **Adversarial Probes (ADV-C01 to ADV-C35):** 100% PASS.
*   **Composition Attacks (COMP-01 to COMP-06):** 100% PASS.
*   **Defect Backlog:** 0 unresolved defects.

---

## 13. Remaining Deferred / Out-of-Scope Items
These future developments remain outside the Step 3 safety boundary and are unverified by this certification execution:
*   Demo Mode removal
*   Double → Long full-domain migration
*   Audit Log API POC
*   Generic reconciliation architecture
*   Staging database
*   Identity registry
*   Dataset/publication IDs
*   Wave 2 UI simplification

---

## 14. Final GO Adjudication

STEP 3 ADVERSARIAL CERTIFICATION = GO

The current Step 3 implementation has successfully passed the adversarial verification defined by
EarthLink_Reseller_Step3_Adversarial_Certification_Basis_v6.md §19.

All mandatory ADV-C01..ADV-C35 probes are evidenced.
All mandatory COMP-01..COMP-06 composition attacks are evidenced.
The previously identified evidence gaps ADV-C06, ADV-C12, ADV-C16, and ADV-C26 were closed by additive, proof-preserving tests.
No production defect remains unresolved.
No certification evidence gap remains unresolved.

Step 3 is therefore CLOSED from an adversarial-certification perspective.

This certification freezes the Step 3 safety/integrity boundary against the current verified implementation and does not authorize architectural redesign or unrelated scope expansion.

---

## 15. Certification Freeze Statement
This document constitutes the final frozen record of Step 3 verification for the EarthLink Reseller V1 Android platform. Any subsequent alterations to the production codebase or test assertions will require a formal recertification cycle.
