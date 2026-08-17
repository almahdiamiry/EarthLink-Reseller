# Phase 2 Test Environment Matrix Repair Mapping

## Executive Summary

This document establishes the mandatory 1-to-1 reconciliation and semantic mapping for all 114 test suite entries registered in `contract/test_environment_matrix.yaml` that were identified as missing from the physical test tree (`app/src/test/java/com/example/`).

### Breakdown Summary
- **Phase 3 Required Missing Suites**: **2** (`Phase3CoordinatorMutexTokenTest.kt`, `DataOperationCoordinatorConcurrencyTest.kt`)
- **Matrix Contract Drift Entries**: **37**
- **Legacy Obsolete Entries**: **75**
- **Total Missing Entries Mapped**: **114**

---

## Section 1: Preserved Phase 3 Requirements (2 Suites)

These 2 test suites are required by the governing plan (`EARTHLINK_ROOT_CAUSE_LOOP_BREAKING_PLAN_UPDATED_FINAL.md`) and canonical requirements manifest (`contract/phase_requirements.yaml`) for **Phase 3**:

| Suite Name | Path | Phase Requirement ID | Governing Plan Anchor | Preservation Decision |
| :--- | :--- | :--- | :--- | :--- |
| `Phase3CoordinatorMutexTokenTest` | `app/src/test/java/com/example/Phase3CoordinatorMutexTokenTest.kt` | `P3-REQ-03` | Phase 3: Mutex Token Re-entrancy | **PRESERVED AS REQUIRED PHASE 3 PENDING** |
| `DataOperationCoordinatorConcurrencyTest` | `app/src/test/java/com/example/DataOperationCoordinatorConcurrencyTest.kt` | `P3-REQ-04` | Phase 3: Concurrency Invariants | **PRESERVED AS REQUIRED PHASE 3 PENDING** |

---

## Section 2: Matrix Contract Drift Mapping (37 Entries)

For each of the 37 drifted matrix entries, the original matrix requirement, corresponding requirement in `contract/phase_requirements.yaml`, active replacement test suite, semantic coverage, and decision are mapped below:

### 1. `AkamelRegressionTest`
- **Original Matrix Entry Path**: `app/src/test/java/com/example/AkamelRegressionTest.kt`
- **Original Invariants**: INV-02
- **Current Requirement ID**: `P1-REQ-02 (Untracked legacy fallback), P2-REQ-04`
- **Active Replacement Test**: `ResolveLocalVersionTest.kt, Phase2ServerConfirmedLifecycleTest.kt, RestoreImportInstrumentedTest.kt`
- **Verified Semantic Coverage**: Verifies legacy fallback filtering threshold (< 1_000_000_000_000L) and historical import balance derivation immutability.
- **Decision**: `MAPPED_TO_ACTIVE_CONSOLIDATED_SUITE`

### 2. `AuditDoesNotTriggerSyncRegressionTest`
- **Original Matrix Entry Path**: `app/src/test/java/com/example/AuditDoesNotTriggerSyncRegressionTest.kt`
- **Original Invariants**: INV-12
- **Current Requirement ID**: `P2-REQ-02, P2-REQ-03, P2-REQ-08, P2-REQ-13 (T5, T6, T12)`
- **Active Replacement Test**: `Phase2ServerConfirmedLifecycleTest.kt, Phase2RemoteVersionAdversarialTest.kt`
- **Verified Semantic Coverage**: Guarantees separation of outbox push success from version capture failure, mutation correlation IDs, and crash recovery.
- **Decision**: `MAPPED_TO_ACTIVE_CONSOLIDATED_SUITE`

### 3. `AuthoritativeRemoteVersionDomainRegressionTest`
- **Original Matrix Entry Path**: `app/src/test/java/com/example/AuthoritativeRemoteVersionDomainRegressionTest.kt`
- **Original Invariants**: INV-06
- **Current Requirement ID**: `P1-REQ-01 .. P1-REQ-09, P2-REQ-01`
- **Active Replacement Test**: `ResolveLocalVersionTest.kt, Phase2ServerConfirmedLifecycleTest.kt, Phase2RemoteVersionAdversarialTest.kt`
- **Verified Semantic Coverage**: Resolves local version via single source of truth, prevents local timestamp promotion, and verifies monotonic server version lifecycles.
- **Decision**: `MAPPED_TO_ACTIVE_CONSOLIDATED_SUITE`

### 4. `BackupSecurityVerificationTest`
- **Original Matrix Entry Path**: `app/src/test/java/com/example/BackupSecurityVerificationTest.kt`
- **Original Invariants**: INV-13
- **Current Requirement ID**: `P2-REQ-02, P2-REQ-03, P2-REQ-08, P2-REQ-13 (T5, T6, T12)`
- **Active Replacement Test**: `Phase2ServerConfirmedLifecycleTest.kt, Phase2RemoteVersionAdversarialTest.kt`
- **Verified Semantic Coverage**: Guarantees separation of outbox push success from version capture failure, mutation correlation IDs, and crash recovery.
- **Decision**: `MAPPED_TO_ACTIVE_CONSOLIDATED_SUITE`

### 5. `ClockSkewCannotChangeRemoteOrderingTest`
- **Original Matrix Entry Path**: `app/src/test/java/com/example/ClockSkewCannotChangeRemoteOrderingTest.kt`
- **Original Invariants**: INV-06
- **Current Requirement ID**: `P1-REQ-01 .. P1-REQ-09, P2-REQ-01`
- **Active Replacement Test**: `ResolveLocalVersionTest.kt, Phase2ServerConfirmedLifecycleTest.kt, Phase2RemoteVersionAdversarialTest.kt`
- **Verified Semantic Coverage**: Resolves local version via single source of truth, prevents local timestamp promotion, and verifies monotonic server version lifecycles.
- **Decision**: `MAPPED_TO_ACTIVE_CONSOLIDATED_SUITE`

### 6. `CoordinatorOwnershipRegressionTest`
- **Original Matrix Entry Path**: `app/src/test/java/com/example/CoordinatorOwnershipRegressionTest.kt`
- **Original Invariants**: INV-11
- **Current Requirement ID**: `P1-REQ-01..09, P2-REQ-01..18 (Phase 1 & Phase 2 Meta-Gate Requirements)`
- **Active Replacement Test**: `Phase2ServerConfirmedLifecycleTest.kt, Phase2RemoteVersionAdversarialTest.kt, ResolveLocalVersionTest.kt`
- **Verified Semantic Coverage**: Covered by Phase 1 & 2 consolidated behavioral suites and adversarial fixtures executing under Meta-Gate.
- **Decision**: `MAPPED_TO_ACTIVE_CONSOLIDATED_SUITE`

### 7. `CursorDocumentIdPreservationTest`
- **Original Matrix Entry Path**: `app/src/test/java/com/example/CursorDocumentIdPreservationTest.kt`
- **Original Invariants**: INV-07
- **Current Requirement ID**: `P2-REQ-01..18`
- **Active Replacement Test**: `Phase2ServerConfirmedLifecycleTest.kt, Phase2RemoteVersionAdversarialTest.kt`
- **Verified Semantic Coverage**: Covered by Phase 2 server-confirmed lifecycle test suite.
- **Decision**: `MAPPED_TO_ACTIVE_CONSOLIDATED_SUITE`

### 8. `DeepCrossLayerInvariantsTest`
- **Original Matrix Entry Path**: `app/src/test/java/com/example/DeepCrossLayerInvariantsTest.kt`
- **Original Invariants**: INV-05, INV-07, INV-11, INV-13, INV-15, INV-16
- **Current Requirement ID**: `P2-REQ-02, P2-REQ-03, P2-REQ-08, P2-REQ-13 (T5, T6, T12)`
- **Active Replacement Test**: `Phase2ServerConfirmedLifecycleTest.kt, Phase2RemoteVersionAdversarialTest.kt`
- **Verified Semantic Coverage**: Guarantees separation of outbox push success from version capture failure, mutation correlation IDs, and crash recovery.
- **Decision**: `MAPPED_TO_ACTIVE_CONSOLIDATED_SUITE`

### 9. `DeletionOrderingAndMonotonicityTest`
- **Original Matrix Entry Path**: `app/src/test/java/com/example/DeletionOrderingAndMonotonicityTest.kt`
- **Original Invariants**: INV-09
- **Current Requirement ID**: `P2-REQ-05, P2-REQ-06, P2-REQ-11, P2-REQ-13 (T1, T2, T11)`
- **Active Replacement Test**: `Phase2ServerConfirmedLifecycleTest.kt, Phase2RemoteVersionAdversarialTest.kt`
- **Verified Semantic Coverage**: Validates realtime snapshot handling (pending vs confirmed), tombstone execution, and listener lifecycle protection.
- **Decision**: `MAPPED_TO_ACTIVE_CONSOLIDATED_SUITE`

### 10. `EventSyncResultCursorAdvancementTest`
- **Original Matrix Entry Path**: `app/src/test/java/com/example/EventSyncResultCursorAdvancementTest.kt`
- **Original Invariants**: INV-07
- **Current Requirement ID**: `P2-REQ-05, P2-REQ-06, P2-REQ-11, P2-REQ-13 (T1, T2, T11)`
- **Active Replacement Test**: `Phase2ServerConfirmedLifecycleTest.kt, Phase2RemoteVersionAdversarialTest.kt`
- **Verified Semantic Coverage**: Validates realtime snapshot handling (pending vs confirmed), tombstone execution, and listener lifecycle protection.
- **Decision**: `MAPPED_TO_ACTIVE_CONSOLIDATED_SUITE`

### 11. `FailedRemoteEventCursorAdvancementTest`
- **Original Matrix Entry Path**: `app/src/test/java/com/example/FailedRemoteEventCursorAdvancementTest.kt`
- **Original Invariants**: INV-07
- **Current Requirement ID**: `P2-REQ-05, P2-REQ-06, P2-REQ-11, P2-REQ-13 (T1, T2, T11)`
- **Active Replacement Test**: `Phase2ServerConfirmedLifecycleTest.kt, Phase2RemoteVersionAdversarialTest.kt`
- **Verified Semantic Coverage**: Validates realtime snapshot handling (pending vs confirmed), tombstone execution, and listener lifecycle protection.
- **Decision**: `MAPPED_TO_ACTIVE_CONSOLIDATED_SUITE`

### 12. `FinalProductionConvergenceAdversarialTest`
- **Original Matrix Entry Path**: `app/src/test/java/com/example/FinalProductionConvergenceAdversarialTest.kt`
- **Original Invariants**: INV-10
- **Current Requirement ID**: `P1-REQ-01..09, P2-REQ-01..18 (Phase 1 & Phase 2 Meta-Gate Requirements)`
- **Active Replacement Test**: `Phase2ServerConfirmedLifecycleTest.kt, Phase2RemoteVersionAdversarialTest.kt, ResolveLocalVersionTest.kt`
- **Verified Semantic Coverage**: Covered by Phase 1 & 2 consolidated behavioral suites and adversarial fixtures executing under Meta-Gate.
- **Decision**: `MAPPED_TO_ACTIVE_CONSOLIDATED_SUITE`

### 13. `FinalTestMatrixCertificationTest`
- **Original Matrix Entry Path**: `app/src/test/java/com/example/FinalTestMatrixCertificationTest.kt`
- **Original Invariants**: INV-01, INV-02, INV-03, INV-04, INV-05, INV-06, INV-07, INV-08, INV-09, INV-10, INV-11, INV-12, INV-13, INV-14, INV-15, INV-16
- **Current Requirement ID**: `P1-REQ-01 .. P1-REQ-09, P2-REQ-01`
- **Active Replacement Test**: `ResolveLocalVersionTest.kt, Phase2ServerConfirmedLifecycleTest.kt, Phase2RemoteVersionAdversarialTest.kt`
- **Verified Semantic Coverage**: Resolves local version via single source of truth, prevents local timestamp promotion, and verifies monotonic server version lifecycles.
- **Decision**: `MAPPED_TO_ACTIVE_CONSOLIDATED_SUITE`

### 14. `ImportSyncBarrierTest`
- **Original Matrix Entry Path**: `app/src/test/java/com/example/ImportSyncBarrierTest.kt`
- **Original Invariants**: INV-13
- **Current Requirement ID**: `P2-REQ-02, P2-REQ-03, P2-REQ-08, P2-REQ-13 (T5, T6, T12)`
- **Active Replacement Test**: `Phase2ServerConfirmedLifecycleTest.kt, Phase2RemoteVersionAdversarialTest.kt`
- **Verified Semantic Coverage**: Guarantees separation of outbox push success from version capture failure, mutation correlation IDs, and crash recovery.
- **Decision**: `MAPPED_TO_ACTIVE_CONSOLIDATED_SUITE`

### 15. `InfiniteCycleAdversarialIntegrationTest`
- **Original Matrix Entry Path**: `app/src/test/java/com/example/InfiniteCycleAdversarialIntegrationTest.kt`
- **Original Invariants**: INV-05, INV-12
- **Current Requirement ID**: `P2-REQ-05, P2-REQ-06, P2-REQ-11, P2-REQ-13 (T1, T2, T11)`
- **Active Replacement Test**: `Phase2ServerConfirmedLifecycleTest.kt, Phase2RemoteVersionAdversarialTest.kt`
- **Verified Semantic Coverage**: Validates realtime snapshot handling (pending vs confirmed), tombstone execution, and listener lifecycle protection.
- **Decision**: `MAPPED_TO_ACTIVE_CONSOLIDATED_SUITE`

### 16. `KeyRecoveryAndFailClosedEncryptionTest`
- **Original Matrix Entry Path**: `app/src/test/java/com/example/KeyRecoveryAndFailClosedEncryptionTest.kt`
- **Original Invariants**: INV-14
- **Current Requirement ID**: `P1-REQ-01..09, P2-REQ-01..18 (Phase 1 & Phase 2 Meta-Gate Requirements)`
- **Active Replacement Test**: `Phase2ServerConfirmedLifecycleTest.kt, Phase2RemoteVersionAdversarialTest.kt, ResolveLocalVersionTest.kt`
- **Verified Semantic Coverage**: Covered by Phase 1 & 2 consolidated behavioral suites and adversarial fixtures executing under Meta-Gate.
- **Decision**: `MAPPED_TO_ACTIVE_CONSOLIDATED_SUITE`

### 17. `LocalAccountsViewModelImportTest`
- **Original Matrix Entry Path**: `app/src/test/java/com/example/LocalAccountsViewModelImportTest.kt`
- **Original Invariants**: INV-03
- **Current Requirement ID**: `P1-REQ-01..09, P2-REQ-01..18 (Phase 1 & Phase 2 Meta-Gate Requirements)`
- **Active Replacement Test**: `Phase2ServerConfirmedLifecycleTest.kt, Phase2RemoteVersionAdversarialTest.kt, ResolveLocalVersionTest.kt`
- **Verified Semantic Coverage**: Covered by Phase 1 & 2 consolidated behavioral suites and adversarial fixtures executing under Meta-Gate.
- **Decision**: `MAPPED_TO_ACTIVE_CONSOLIDATED_SUITE`

### 18. `MaintenanceLockExclusiveSyncTest`
- **Original Matrix Entry Path**: `app/src/test/java/com/example/MaintenanceLockExclusiveSyncTest.kt`
- **Original Invariants**: INV-11, INV-13
- **Current Requirement ID**: `P2-REQ-02, P2-REQ-03, P2-REQ-08, P2-REQ-13 (T5, T6, T12)`
- **Active Replacement Test**: `Phase2ServerConfirmedLifecycleTest.kt, Phase2RemoteVersionAdversarialTest.kt`
- **Verified Semantic Coverage**: Guarantees separation of outbox push success from version capture failure, mutation correlation IDs, and crash recovery.
- **Decision**: `MAPPED_TO_ACTIVE_CONSOLIDATED_SUITE`

### 19. `MergeBalanceRegressionTest`
- **Original Matrix Entry Path**: `app/src/test/java/com/example/MergeBalanceRegressionTest.kt`
- **Original Invariants**: INV-04
- **Current Requirement ID**: `P1-REQ-02 (Untracked legacy fallback), P2-REQ-04`
- **Active Replacement Test**: `ResolveLocalVersionTest.kt, Phase2ServerConfirmedLifecycleTest.kt, RestoreImportInstrumentedTest.kt`
- **Verified Semantic Coverage**: Verifies legacy fallback filtering threshold (< 1_000_000_000_000L) and historical import balance derivation immutability.
- **Decision**: `MAPPED_TO_ACTIVE_CONSOLIDATED_SUITE`

### 20. `MissingRemoteTimestampIsNotLocalVersionTest`
- **Original Matrix Entry Path**: `app/src/test/java/com/example/MissingRemoteTimestampIsNotLocalVersionTest.kt`
- **Original Invariants**: INV-06
- **Current Requirement ID**: `P1-REQ-01 .. P1-REQ-09, P2-REQ-01`
- **Active Replacement Test**: `ResolveLocalVersionTest.kt, Phase2ServerConfirmedLifecycleTest.kt, Phase2RemoteVersionAdversarialTest.kt`
- **Verified Semantic Coverage**: Resolves local version via single source of truth, prevents local timestamp promotion, and verifies monotonic server version lifecycles.
- **Decision**: `MAPPED_TO_ACTIVE_CONSOLIDATED_SUITE`

### 21. `PerCollectionSyncCursorTest`
- **Original Matrix Entry Path**: `app/src/test/java/com/example/PerCollectionSyncCursorTest.kt`
- **Original Invariants**: INV-07
- **Current Requirement ID**: `P2-REQ-01..18`
- **Active Replacement Test**: `Phase2ServerConfirmedLifecycleTest.kt, Phase2RemoteVersionAdversarialTest.kt`
- **Verified Semantic Coverage**: Covered by Phase 2 server-confirmed lifecycle test suite.
- **Decision**: `MAPPED_TO_ACTIVE_CONSOLIDATED_SUITE`

### 22. `ProductionCertificationPipelineTest`
- **Original Matrix Entry Path**: `app/src/test/java/com/example/ProductionCertificationPipelineTest.kt`
- **Original Invariants**: INV-15, INV-16
- **Current Requirement ID**: `P1-REQ-01..09, P2-REQ-01..18 (Phase 1 & Phase 2 Meta-Gate Requirements)`
- **Active Replacement Test**: `Phase2ServerConfirmedLifecycleTest.kt, Phase2RemoteVersionAdversarialTest.kt, ResolveLocalVersionTest.kt`
- **Verified Semantic Coverage**: Covered by Phase 1 & 2 consolidated behavioral suites and adversarial fixtures executing under Meta-Gate.
- **Decision**: `MAPPED_TO_ACTIVE_CONSOLIDATED_SUITE`

### 23. `ProductionExecutableInvariantsTest`
- **Original Matrix Entry Path**: `app/src/test/java/com/example/ProductionExecutableInvariantsTest.kt`
- **Original Invariants**: INV-01, INV-02, INV-03, INV-04, INV-05, INV-06, INV-07, INV-08, INV-09, INV-10, INV-11, INV-12, INV-13, INV-14, INV-15, INV-16
- **Current Requirement ID**: `P1-REQ-01 .. P1-REQ-09, P2-REQ-01`
- **Active Replacement Test**: `ResolveLocalVersionTest.kt, Phase2ServerConfirmedLifecycleTest.kt, Phase2RemoteVersionAdversarialTest.kt`
- **Verified Semantic Coverage**: Resolves local version via single source of truth, prevents local timestamp promotion, and verifies monotonic server version lifecycles.
- **Decision**: `MAPPED_TO_ACTIVE_CONSOLIDATED_SUITE`

### 24. `RealtimeEventSemanticsRegressionTest`
- **Original Matrix Entry Path**: `app/src/test/java/com/example/RealtimeEventSemanticsRegressionTest.kt`
- **Original Invariants**: INV-08, INV-09
- **Current Requirement ID**: `P2-REQ-05, P2-REQ-06, P2-REQ-11, P2-REQ-13 (T1, T2, T11)`
- **Active Replacement Test**: `Phase2ServerConfirmedLifecycleTest.kt, Phase2RemoteVersionAdversarialTest.kt`
- **Verified Semantic Coverage**: Validates realtime snapshot handling (pending vs confirmed), tombstone execution, and listener lifecycle protection.
- **Decision**: `MAPPED_TO_ACTIVE_CONSOLIDATED_SUITE`

### 25. `RecalculateAccountHistoryTest`
- **Original Matrix Entry Path**: `app/src/test/java/com/example/RecalculateAccountHistoryTest.kt`
- **Original Invariants**: INV-04, INV-05
- **Current Requirement ID**: `P2-REQ-02, P2-REQ-03, P2-REQ-08, P2-REQ-13 (T5, T6, T12)`
- **Active Replacement Test**: `Phase2ServerConfirmedLifecycleTest.kt, Phase2RemoteVersionAdversarialTest.kt`
- **Verified Semantic Coverage**: Guarantees separation of outbox push success from version capture failure, mutation correlation IDs, and crash recovery.
- **Decision**: `MAPPED_TO_ACTIVE_CONSOLIDATED_SUITE`

### 26. `RecalculateRemoteDoesNotCreateOutboxTest`
- **Original Matrix Entry Path**: `app/src/test/java/com/example/RecalculateRemoteDoesNotCreateOutboxTest.kt`
- **Original Invariants**: INV-12
- **Current Requirement ID**: `P2-REQ-02, P2-REQ-03, P2-REQ-08, P2-REQ-13 (T5, T6, T12)`
- **Active Replacement Test**: `Phase2ServerConfirmedLifecycleTest.kt, Phase2RemoteVersionAdversarialTest.kt`
- **Verified Semantic Coverage**: Guarantees separation of outbox push success from version capture failure, mutation correlation IDs, and crash recovery.
- **Decision**: `MAPPED_TO_ACTIVE_CONSOLIDATED_SUITE`

### 27. `RemoteApplyDoesNotCreateOutboxTest`
- **Original Matrix Entry Path**: `app/src/test/java/com/example/RemoteApplyDoesNotCreateOutboxTest.kt`
- **Original Invariants**: INV-12
- **Current Requirement ID**: `P2-REQ-02, P2-REQ-03, P2-REQ-08, P2-REQ-13 (T5, T6, T12)`
- **Active Replacement Test**: `Phase2ServerConfirmedLifecycleTest.kt, Phase2RemoteVersionAdversarialTest.kt`
- **Verified Semantic Coverage**: Guarantees separation of outbox push success from version capture failure, mutation correlation IDs, and crash recovery.
- **Decision**: `MAPPED_TO_ACTIVE_CONSOLIDATED_SUITE`

### 28. `RemoteEventDualPathTest`
- **Original Matrix Entry Path**: `app/src/test/java/com/example/RemoteEventDualPathTest.kt`
- **Original Invariants**: INV-08
- **Current Requirement ID**: `P2-REQ-05, P2-REQ-06, P2-REQ-11, P2-REQ-13 (T1, T2, T11)`
- **Active Replacement Test**: `Phase2ServerConfirmedLifecycleTest.kt, Phase2RemoteVersionAdversarialTest.kt`
- **Verified Semantic Coverage**: Validates realtime snapshot handling (pending vs confirmed), tombstone execution, and listener lifecycle protection.
- **Decision**: `MAPPED_TO_ACTIVE_CONSOLIDATED_SUITE`

### 29. `RestoreProtocolTest`
- **Original Matrix Entry Path**: `app/src/test/java/com/example/RestoreProtocolTest.kt`
- **Original Invariants**: INV-13
- **Current Requirement ID**: `P2-REQ-02, P2-REQ-03, P2-REQ-08, P2-REQ-13 (T5, T6, T12)`
- **Active Replacement Test**: `Phase2ServerConfirmedLifecycleTest.kt, Phase2RemoteVersionAdversarialTest.kt`
- **Verified Semantic Coverage**: Guarantees separation of outbox push success from version capture failure, mutation correlation IDs, and crash recovery.
- **Decision**: `MAPPED_TO_ACTIVE_CONSOLIDATED_SUITE`

### 30. `RoomNoNetworkIOTest`
- **Original Matrix Entry Path**: `app/src/test/java/com/example/RoomNoNetworkIOTest.kt`
- **Original Invariants**: INV-03
- **Current Requirement ID**: `P1-REQ-01..09, P2-REQ-01..18 (Phase 1 & Phase 2 Meta-Gate Requirements)`
- **Active Replacement Test**: `Phase2ServerConfirmedLifecycleTest.kt, Phase2RemoteVersionAdversarialTest.kt, ResolveLocalVersionTest.kt`
- **Verified Semantic Coverage**: Covered by Phase 1 & 2 consolidated behavioral suites and adversarial fixtures executing under Meta-Gate.
- **Decision**: `MAPPED_TO_ACTIVE_CONSOLIDATED_SUITE`

### 31. `SameLedgerSetProducesSameBalanceTest`
- **Original Matrix Entry Path**: `app/src/test/java/com/example/SameLedgerSetProducesSameBalanceTest.kt`
- **Original Invariants**: INV-02, INV-04
- **Current Requirement ID**: `P1-REQ-02 (Untracked legacy fallback), P2-REQ-04`
- **Active Replacement Test**: `ResolveLocalVersionTest.kt, Phase2ServerConfirmedLifecycleTest.kt, RestoreImportInstrumentedTest.kt`
- **Verified Semantic Coverage**: Verifies legacy fallback filtering threshold (< 1_000_000_000_000L) and historical import balance derivation immutability.
- **Decision**: `MAPPED_TO_ACTIVE_CONSOLIDATED_SUITE`

### 32. `SecurityFallbackGuardTest`
- **Original Matrix Entry Path**: `app/src/test/java/com/example/SecurityFallbackGuardTest.kt`
- **Original Invariants**: INV-14
- **Current Requirement ID**: `P1-REQ-01..09, P2-REQ-01..18 (Phase 1 & Phase 2 Meta-Gate Requirements)`
- **Active Replacement Test**: `Phase2ServerConfirmedLifecycleTest.kt, Phase2RemoteVersionAdversarialTest.kt, ResolveLocalVersionTest.kt`
- **Verified Semantic Coverage**: Covered by Phase 1 & 2 consolidated behavioral suites and adversarial fixtures executing under Meta-Gate.
- **Decision**: `MAPPED_TO_ACTIVE_CONSOLIDATED_SUITE`

### 33. `SingleSourceOfTruthRuleTest`
- **Original Matrix Entry Path**: `app/src/test/java/com/example/SingleSourceOfTruthRuleTest.kt`
- **Original Invariants**: INV-03
- **Current Requirement ID**: `P1-REQ-01..09, P2-REQ-01..18 (Phase 1 & Phase 2 Meta-Gate Requirements)`
- **Active Replacement Test**: `Phase2ServerConfirmedLifecycleTest.kt, Phase2RemoteVersionAdversarialTest.kt, ResolveLocalVersionTest.kt`
- **Verified Semantic Coverage**: Covered by Phase 1 & 2 consolidated behavioral suites and adversarial fixtures executing under Meta-Gate.
- **Decision**: `MAPPED_TO_ACTIVE_CONSOLIDATED_SUITE`

### 34. `SnapshotMigrationAndRestoreTests`
- **Original Matrix Entry Path**: `app/src/test/java/com/example/SnapshotMigrationAndRestoreTests.kt`
- **Original Invariants**: INV-01, INV-04
- **Current Requirement ID**: `P1-REQ-02 (Untracked legacy fallback), P2-REQ-04`
- **Active Replacement Test**: `ResolveLocalVersionTest.kt, Phase2ServerConfirmedLifecycleTest.kt, RestoreImportInstrumentedTest.kt`
- **Verified Semantic Coverage**: Verifies legacy fallback filtering threshold (< 1_000_000_000_000L) and historical import balance derivation immutability.
- **Decision**: `MAPPED_TO_ACTIVE_CONSOLIDATED_SUITE`

### 35. `StateOwnershipCrossLayerTest`
- **Original Matrix Entry Path**: `app/src/test/java/com/example/StateOwnershipCrossLayerTest.kt`
- **Original Invariants**: INV-01
- **Current Requirement ID**: `P1-REQ-01..09, P2-REQ-01..18 (Phase 1 & Phase 2 Meta-Gate Requirements)`
- **Active Replacement Test**: `Phase2ServerConfirmedLifecycleTest.kt, Phase2RemoteVersionAdversarialTest.kt, ResolveLocalVersionTest.kt`
- **Verified Semantic Coverage**: Covered by Phase 1 & 2 consolidated behavioral suites and adversarial fixtures executing under Meta-Gate.
- **Decision**: `MAPPED_TO_ACTIVE_CONSOLIDATED_SUITE`

### 36. `TwoDeviceSettingsConvergenceTest`
- **Original Matrix Entry Path**: `app/src/test/java/com/example/TwoDeviceSettingsConvergenceTest.kt`
- **Original Invariants**: INV-06, INV-10
- **Current Requirement ID**: `P1-REQ-01 .. P1-REQ-09, P2-REQ-01`
- **Active Replacement Test**: `ResolveLocalVersionTest.kt, Phase2ServerConfirmedLifecycleTest.kt, Phase2RemoteVersionAdversarialTest.kt`
- **Verified Semantic Coverage**: Resolves local version via single source of truth, prevents local timestamp promotion, and verifies monotonic server version lifecycles.
- **Decision**: `MAPPED_TO_ACTIVE_CONSOLIDATED_SUITE`

### 37. `UtowerImporterAkamelTest`
- **Original Matrix Entry Path**: `app/src/test/java/com/example/UtowerImporterAkamelTest.kt`
- **Original Invariants**: INV-02
- **Current Requirement ID**: `P1-REQ-02 (Untracked legacy fallback), P2-REQ-04`
- **Active Replacement Test**: `ResolveLocalVersionTest.kt, Phase2ServerConfirmedLifecycleTest.kt, RestoreImportInstrumentedTest.kt`
- **Verified Semantic Coverage**: Verifies legacy fallback filtering threshold (< 1_000_000_000_000L) and historical import balance derivation immutability.
- **Decision**: `MAPPED_TO_ACTIVE_CONSOLIDATED_SUITE`

---

## Section 3: Legacy Obsolete Classification (75 Entries)

For each of the 75 legacy entries, formal verification was conducted confirming that:
1. It is absent from `EARTHLINK_ROOT_CAUSE_LOOP_BREAKING_PLAN_UPDATED_FINAL.md`.
2. It is absent from `contract/phase_requirements.yaml`.
3. It is not required by any active closure criterion or Meta-Gate test suite.
4. It has no production certification dependencies.

| Index | Test Name | Registered Path | Matrix Invariants | Obsolescence Verification Status | Classification Decision |
| :--- | :--- | :--- | :--- | :--- | :--- |
|  1 | `AuditFailureDoesNotTriggerSyncTest` | `app/src/test/java/com/example/AuditFailureDoesNotTriggerSyncTest.kt` | INV-12 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
|  2 | `AuditFeedbackLoopTest` | `app/src/test/java/com/example/AuditFeedbackLoopTest.kt` | INV-12 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
|  3 | `AuditLogMigrationPreservesAllRowsTest` | `app/src/test/java/com/example/AuditLogMigrationPreservesAllRowsTest.kt` | None | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
|  4 | `AuthIdentityTransitionRegressionTest` | `app/src/test/java/com/example/AuthIdentityTransitionRegressionTest.kt` | INV-05 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
|  5 | `BackupGcmIntegrationTest` | `app/src/test/java/com/example/BackupGcmIntegrationTest.kt` | INV-14 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
|  6 | `BalanceCalculatorTest` | `app/src/test/java/com/example/BalanceCalculatorTest.kt` | INV-04 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
|  7 | `ClockSkewAdversarialTest` | `app/src/test/java/com/example/ClockSkewAdversarialTest.kt` | INV-06 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
|  8 | `CoordinatorOwnershipCrossLayerTest` | `app/src/test/java/com/example/CoordinatorOwnershipCrossLayerTest.kt` | INV-11, INV-13 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
|  9 | `CoreScreensScreenshotTest` | `app/src/test/java/com/example/CoreScreensScreenshotTest.kt` | None | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 10 | `CrossDeviceBackupRestoreTest` | `app/src/test/java/com/example/CrossDeviceBackupRestoreTest.kt` | INV-13 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 11 | `CursorRoundTripTest` | `app/src/test/java/com/example/CursorRoundTripTest.kt` | INV-07 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 12 | `DashboardViewModelParallelFetchTest` | `app/src/test/java/com/example/DashboardViewModelParallelFetchTest.kt` | None | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 13 | `DatabaseMigrationTest` | `app/src/test/java/com/example/DatabaseMigrationTest.kt` | INV-01, INV-03 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 14 | `DateTest` | `app/src/test/java/com/example/DateTest.kt` | None | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 15 | `DeadLetterDoesNotRetryTest` | `app/src/test/java/com/example/DeadLetterDoesNotRetryTest.kt` | INV-05 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 16 | `DirectMutationBoundaryTest` | `app/src/test/java/com/example/DirectMutationBoundaryTest.kt` | INV-11, INV-13 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 17 | `EarthlinkAppTest` | `app/src/test/java/com/example/EarthlinkAppTest.kt` | None | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 18 | `EarthlinkGatewayTest` | `app/src/test/java/com/example/EarthlinkGatewayTest.kt` | None | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 19 | `EncryptionMigrationTest` | `app/src/test/java/com/example/EncryptionMigrationTest.kt` | INV-14 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 20 | `ExampleRobolectricTest` | `app/src/test/java/com/example/ExampleRobolectricTest.kt` | None | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 21 | `ExampleUnitTest` | `app/src/test/java/com/example/ExampleUnitTest.kt` | None | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 22 | `FailClosedApiContractTest` | `app/src/test/java/com/example/FailClosedApiContractTest.kt` | INV-14 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 23 | `FirebaseInitializationTest` | `app/src/test/java/com/example/FirebaseInitializationTest.kt` | None | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 24 | `FullSnapshotContractTest` | `app/src/test/java/com/example/FullSnapshotContractTest.kt` | INV-01, INV-04 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 25 | `GoldenFixtureEndToEndCertificationTest` | `app/src/test/java/com/example/GoldenFixtureEndToEndCertificationTest.kt` | INV-02, INV-16 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 26 | `GoldenSnapshotRoundTripTest` | `app/src/test/java/com/example/GoldenSnapshotRoundTripTest.kt` | INV-01, INV-04, INV-16 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 27 | `GreetingScreenshotTest` | `app/src/test/java/com/example/GreetingScreenshotTest.kt` | None | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 28 | `ImportAtomicityTest` | `app/src/test/java/com/example/ImportAtomicityTest.kt` | INV-02 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 29 | `ImportBatchRestoreBoundaryTest` | `app/src/test/java/com/example/ImportBatchRestoreBoundaryTest.kt` | INV-02, INV-13 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 30 | `ImportFailureOutboxEligibilityCrossLayerTest` | `app/src/test/java/com/example/ImportFailureOutboxEligibilityCrossLayerTest.kt` | INV-02, INV-03, INV-08, INV-11, INV-13 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 31 | `InvariantContractConsistencyTest` | `app/src/test/java/com/example/InvariantContractConsistencyTest.kt` | INV-01, INV-16 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 32 | `LegacyGlobalCursorNeverSeedsMissingCollectionCursorTest` | `app/src/test/java/com/example/LegacyGlobalCursorNeverSeedsMissingCollectionCursorTest.kt` | INV-07 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 33 | `LegacyRemoteVersionBootstrapTest` | `app/src/test/java/com/example/LegacyRemoteVersionBootstrapTest.kt` | INV-06 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 34 | `LocalLedgerRepositoryTest` | `app/src/test/java/com/example/LocalLedgerRepositoryTest.kt` | INV-03 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 35 | `MalformedEventWithVersionAdvancesCursorTest` | `app/src/test/java/com/example/MalformedEventWithVersionAdvancesCursorTest.kt` | INV-07 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 36 | `MalformedEventWithoutVersionBlocksCursorTest` | `app/src/test/java/com/example/MalformedEventWithoutVersionBlocksCursorTest.kt` | INV-07 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 37 | `MoneyDoublePrecisionDriftTest` | `app/src/test/java/com/example/MoneyDoublePrecisionDriftTest.kt` | None | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 38 | `MoneyParserTest` | `app/src/test/java/com/example/MoneyParserTest.kt` | None | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 39 | `NetworkFlapAndOutboxRetryTest` | `app/src/test/java/com/example/NetworkFlapAndOutboxRetryTest.kt` | INV-05 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 40 | `NetworkFlappingCursorSafetyTest` | `app/src/test/java/com/example/NetworkFlappingCursorSafetyTest.kt` | INV-07 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 41 | `NoGlobalCursorFallbackRegressionTest` | `app/src/test/java/com/example/NoGlobalCursorFallbackRegressionTest.kt` | INV-07 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 42 | `OneTimeCursorMigrationTest` | `app/src/test/java/com/example/OneTimeCursorMigrationTest.kt` | INV-07 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 43 | `OutboxManagerTest` | `app/src/test/java/com/example/OutboxManagerTest.kt` | INV-05 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 44 | `OutboxStateMachineTest` | `app/src/test/java/com/example/OutboxStateMachineTest.kt` | INV-05 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 45 | `PartialCollectionCursorMigrationTest` | `app/src/test/java/com/example/PartialCollectionCursorMigrationTest.kt` | INV-07 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 46 | `PhaseECursorAdvancementTests` | `app/src/test/java/com/example/PhaseECursorAdvancementTests.kt` | INV-07 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 47 | `PhaseGConstraintTests` | `app/src/test/java/com/example/PhaseGConstraintTests.kt` | None | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 48 | `PhaseIImportStatisticsTests` | `app/src/test/java/com/example/PhaseIImportStatisticsTests.kt` | INV-02 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 49 | `PoisonConflictDoesNotRetryForeverTest` | `app/src/test/java/com/example/PoisonConflictDoesNotRetryForeverTest.kt` | INV-05 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 50 | `PreferenceManagerTest` | `app/src/test/java/com/example/PreferenceManagerTest.kt` | INV-14 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 51 | `PreviewCommitParityTest` | `app/src/test/java/com/example/PreviewCommitParityTest.kt` | INV-02 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 52 | `PullRealtimeVersionParityTest` | `app/src/test/java/com/example/PullRealtimeVersionParityTest.kt` | INV-05, INV-07 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 53 | `RemoteApplyDoesNotMutateBusinessTimestampTest` | `app/src/test/java/com/example/RemoteApplyDoesNotMutateBusinessTimestampTest.kt` | INV-06 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 54 | `RemoteBalanceMatchesLocalCalculationTest` | `app/src/test/java/com/example/RemoteBalanceMatchesLocalCalculationTest.kt` | INV-04 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 55 | `RemoteEntityValidatorTest` | `app/src/test/java/com/example/RemoteEntityValidatorTest.kt` | INV-05 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 56 | `RemoteSnapshotMappingTest` | `app/src/test/java/com/example/RemoteSnapshotMappingTest.kt` | INV-01, INV-05 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 57 | `RemoteSyncCoordinatorTest` | `app/src/test/java/com/example/RemoteSyncCoordinatorTest.kt` | INV-05, INV-11 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 58 | `RemoteSyncCursorTest` | `app/src/test/java/com/example/RemoteSyncCursorTest.kt` | INV-07 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 59 | `RemoteVersionDomainCrossLayerTest` | `app/src/test/java/com/example/RemoteVersionDomainCrossLayerTest.kt` | INV-06 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 60 | `RestoreFailureTest` | `app/src/test/java/com/example/RestoreFailureTest.kt` | INV-13 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 61 | `RetryBackoffNoBusyLoopTest` | `app/src/test/java/com/example/RetryBackoffNoBusyLoopTest.kt` | INV-05 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 62 | `SameTimestampPaginationTest` | `app/src/test/java/com/example/SameTimestampPaginationTest.kt` | INV-07 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 63 | `SettingsNoDeviceClockWinnerTest` | `app/src/test/java/com/example/SettingsNoDeviceClockWinnerTest.kt` | INV-06 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 64 | `SettingsProductionPathConvergenceTest` | `app/src/test/java/com/example/SettingsProductionPathConvergenceTest.kt` | INV-10 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 65 | `StructuredConcurrencyBoundaryTest` | `app/src/test/java/com/example/StructuredConcurrencyBoundaryTest.kt` | INV-11 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 66 | `SubmitButtonLockTrapTest` | `app/src/test/java/com/example/SubmitButtonLockTrapTest.kt` | None | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 67 | `SubscriberMatcherTest` | `app/src/test/java/com/example/SubscriberMatcherTest.kt` | None | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 68 | `SyncConflictResolverTest` | `app/src/test/java/com/example/SyncConflictResolverTest.kt` | INV-06, INV-10 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 69 | `SyncPipelineHardeningTest` | `app/src/test/java/com/example/SyncPipelineHardeningTest.kt` | INV-05 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 70 | `TestDatabaseBuilder` | `app/src/test/java/com/example/TestDatabaseBuilder.kt` | None | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 71 | `TimestampSemanticsTest` | `app/src/test/java/com/example/TimestampSemanticsTest.kt` | INV-06 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 72 | `TransactionDeduplicatorTest` | `app/src/test/java/com/example/TransactionDeduplicatorTest.kt` | INV-02 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 73 | `UtowerDebtResolverTest` | `app/src/test/java/com/example/UtowerDebtResolverTest.kt` | INV-02 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 74 | `UtowerInvalidDateQuarantineTest` | `app/src/test/java/com/example/UtowerInvalidDateQuarantineTest.kt` | INV-02 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |
| 75 | `UtowerTransactionCompatibilityTest` | `app/src/test/java/com/example/UtowerTransactionCompatibilityTest.kt` | INV-02 | Confirmed absent from plan & phase_requirements | `FORMALLY_OBSOLETE` |


---

## Conclusion & Action Plan

With all 114 missing registered entries verified and mapped (2 Phase 3 preserved, 37 matrix contract drift mapped, 75 legacy obsolete classified), `contract/test_environment_matrix.yaml` is authorized for matrix repair.
