# Canonical Production Contract Matrix

This matrix is the frozen, authoritative mapping between `PRODUCTION_INVARIANTS.md` (INV-01 through INV-17), canonical production source code, frozen immutable verification tests, machine-readable contract (`contract/invariant_contract.yaml`), and machine-verifiable evidence.
All mappings are aligned with the Frozen Implementation Authority Bundle in `docs/authority/`.

---

## Contract Matrix

| Invariant ID | Canonical Behavior Summary | Canonical Source Files | Frozen Verification Tests | Machine Verification Evidence |
| :--- | :--- | :--- | :--- | :--- |
| **INV-01** | **Four Distinct State Tiers:** Partition state into Historical, Snapshot, Runtime, Remote. | `AppDatabase.kt`, `Repositories.kt` | `ProductionExecutableInvariantsTest.kt` | `contract/invariant_contract.yaml` |
| **INV-02** | **Historical Source Immutability:** uTower archives and committed history are immutable. | `LocalLedgerRepositoryImpl.kt` | `DeepCrossLayerInvariantsTest.kt` | `contract/invariant_contract.yaml` |
| **INV-03** | **Single Source of Truth:** Local Room database is the authoritative local state. | `AppDatabase.kt`, `SyncRepositoryImpl.kt` | `ProductionExecutableInvariantsTest.kt` | `contract/invariant_contract.yaml` |
| **INV-04** | **Zero Double-Application:** Calculations do not re-apply snapshot historical records. | `LocalLedgerRepositoryImpl.kt` | `ProductionExecutableInvariantsTest.kt` | `contract/invariant_contract.yaml` |
| **INV-05** | **One State, One Authority:** One semantic definition, one version domain, one mutation path. | `SyncRepositoryImpl.kt` | `ProductionCertificationPipelineTest.kt` | `contract/invariant_contract.yaml` |
| **INV-06** | **Authoritative Remote Version Domain:** Server timestamps (`updatedAt`, `deletedAt`) are the sole remote version. | `RemoteSyncCoordinator.kt`, `SyncRepositoryImpl.kt` | `ResolveLocalVersionTest.kt`, `Phase2RemoteVersionAdversarialTest.kt` | `contract/invariant_contract.yaml` |
| **INV-07** | **Composite Cursor Advancement:** Composite cursor `(serverTimestamp, documentId)` advancement. | `RemoteSyncCoordinator.kt` | `ProductionExecutableInvariantsTest.kt` | `contract/invariant_contract.yaml` |
| **INV-08** | **Realtime Echo Isolation:** Evaluate `hasPendingWrites()` on per-document basis. | `SyncRepositoryImpl.kt` | `Phase2ServerConfirmedLifecycleTest.kt` | `contract/invariant_contract.yaml` |
| **INV-09** | **Query Membership != Deletion:** `DocumentChange.REMOVED` is query boundary exit, not deletion. | `SyncRepositoryImpl.kt` | `ProductionExecutableInvariantsTest.kt` | `contract/invariant_contract.yaml` |
| **INV-10** | **Deterministic Convergence:** Multiple devices converge with zero trailing writes. | `SyncRepositoryImpl.kt` | `FinalTestMatrixCertificationTest.kt` | `contract/invariant_contract.yaml` |
| **INV-11** | **Deterministic Restore & Mutation Channel:** G3 deterministic snapshot restoration; atomic serialized mutation boundary (`DataOperationCoordinator` mechanism). | `DataOperationCoordinator.kt`, `SyncRepositoryImpl.kt` | `Phase3CoordinatorMutexTokenTest.kt` | `contract/invariant_contract.yaml` |
| **INV-12** | **No Outbox Loops on Remote Apply:** Remote apply never creates local sync outbox records. | `SyncRepositoryImpl.kt` | `ProductionExecutableInvariantsTest.kt` | `contract/invariant_contract.yaml` |
| **INV-13** | **Outbox Durability & Mutual Exclusion:** Outbox retry durability (no dead-letter blackhole); high-impact operation mutual exclusion. | `DataOperationCoordinator.kt`, `SyncRepositoryImpl.kt` | `Phase3CoordinatorMutexTokenTest.kt` | `contract/invariant_contract.yaml` |
| **INV-14** | **Fail-Closed Encryption:** Unrecoverable SQLCipher key fails closed safely. | `AppDatabase.kt` | `ProductionExecutableInvariantsTest.kt` | `contract/invariant_contract.yaml` |
| **INV-15** | **Fail-Closed Release Signing:** Missing release credentials fails build immediately. | `app/build.gradle.kts` | `ProductionCertificationPipelineTest.kt` | `contract/invariant_contract.yaml` |
| **INV-16** | **Immutable Certification Evidence:** Certification tests are frozen and immutable. | `FinalTestMatrixCertificationTest.kt` | `FinalTestMatrixCertificationTest.kt` | `contract/invariant_contract.yaml` |
| **INV-17** | **Fail-Closed Verification:** All closures verified via machine compliance matrix. | `scripts/run_verified_command.py`, `scripts/scan_forbidden_patterns.py` | `scripts/test_meta_gate_fixtures.py` | `contract/phase_requirements.yaml` |
