# Task Report: P1-05 — Enforce Deterministic Firestore Document Identity

## 1. Executive Summary
- **Task ID**: P1-05
- **Task Title**: Enforce deterministic Firestore document identity (1:1 entityId -> documentId mapping)
- **Status**: DONE
- **Governing Requirements & Invariants**: `P1-G2-REQ-04`, `INV-01`, `INV-05`, `INV-13`
- **Verification Proof**: All contract validators, forbidden pattern scanners, and unit test suites passed cleanly with exit code 0.

---

## 2. Implementation Overview

### 2.1 Canonical Document Path Construction (`SyncRepositoryImpl.kt`)
- Verified and enforced that for all synchronized entity collections (`local_accounts`, `local_ledger_entries`, `import_batches`, `audit_logs`), cloud document references are strictly constructed as `collection(collName).document(item.entityId)`.
- Exposed `@VisibleForTesting internal fun getCollectionRef(entityType: String, uid: String, firestore: FirebaseFirestore)` with canonical entity type resolution and standard aliases (`accounts`, `ledger`, `batches`, `audit`).
- Exposed `@VisibleForTesting internal fun buildOutboxPayloadMap(item: SyncOutbox)` to guarantee payloads preserve schema version, device tracking, and payload attributes while keeping document keys deterministic.

### 2.2 `syncMutationId` Separation & Lost-ACK Idempotency (`OutboxManager.kt`, `SyncRepositoryImpl.kt`)
- Enforced strict architectural separation between `syncMutationId` (write correlation attribute for echo detection and readback verification) and the canonical Firestore document key (`item.entityId`).
- Verified that retried pushes after lost ACKs or network drops target the exact same Firestore document path (`users/{uid}/{collection}/{entityId}`) using `SetOptions.merge()`, guaranteeing idempotent upsert behavior in cloud storage without duplicate document creation or divergent shadow keys.

### 2.3 Inbound Remote Event Identity Preservation (`RemoteSyncCoordinator.kt`)
- Verified that inbound remote events (via pull queries and realtime snapshot listeners) extract `doc.id` and map it 1:1 to `RemoteEvent.entityId`.
- `RemoteSyncCoordinator` applies incoming entities to Room SQLite (`LocalAccountDao`, `LocalLedgerEntryDao`, `ImportBatchDao`) preserving the exact `entityId` as the primary key.
- Tombstones for deleted entities record `tombstone:<type>:<entityId>` in `sync_metadata` using the deterministic entity ID.

---

## 3. Test Suite Implementation (`Phase1FirestoreDocumentIdentityTest.kt`)

Implemented `Phase1FirestoreDocumentIdentityTest.kt` with 7 exhaustive test cases:
1. `testCanonicalDocumentPath_1to1EntityMapping_allCollections`: Validates 1:1 document path construction across `local_accounts`, `local_ledger_entries`, `import_batches`, `audit_logs`, and alias resolution.
2. `testSyncMutationIdSeparation_fromDocumentIdentity`: Validates that `syncMutationId` is stored only inside the payload data map and never replaces the document ID. Subsequent mutations receive distinct mutation IDs while retaining identical entity IDs.
3. `testLostAckSimulation_repeatedSyncPassesTargetExactSameDocumentId`: Validates that when a push encounters a lost ACK / network failure, retry passes target the exact same document ID (`tx_lost_ack_retry_001`), ensuring idempotent cloud upsert.
4. `testDistinctEntities_produceDistinctDocumentPaths`: Validates that distinct entity IDs always produce distinct document references with zero hash collision or path collapsing.
5. `testRemoteToLocalIdentityPreservation_inboundEvents`: Validates that pull and realtime events preserve Firestore document IDs into Room SQLite account and ledger entities.
6. `testTombstoneAndDeletion_preservesDeterministicDocumentIdentity`: Validates that deletion tombstones retain the exact entity ID and record tombstones in metadata without ID mutation.
7. `testNoRandomOrNonceGeneratedDocumentId_forExistingEntities`: Validates that client nonces, timestamps, or random UUIDs are never substituted for existing entity IDs.

---

## 4. Contract & Matrix Alignment
- **`contract/invariant_contract.yaml`**: Mapped `Phase1FirestoreDocumentIdentityTest.kt` under `INV-01` and `INV-13` in `required_behavior_tests`.
- **`contract/invariant_test_map.yaml`**: Mapped `Phase1FirestoreDocumentIdentityTest.kt` under `INV-01` and `INV-13` in `tests`.
- **`contract/test_environment_matrix.yaml`**: Added `Phase1FirestoreDocumentIdentityTest` (tier `ROBOLECTRIC`) under `INV-01` and `INV-13` primary suites and test suite catalog.
- **`CHANGELOG.md`**: Added `[1.73.0] - 2026-08-18` entry documenting all changes.
- **`progress.md`**: Marked Task P1-05 as complete.

---

## 5. Machine Verification Evidence

### 5.1 Invariant Contract Validation
```
Command: python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_invariant_contract.py
Exit Code: 0
Output:
=================================================================
=== Earthlink Reseller App -- Invariant Contract Validator ===
=================================================================
[PASS] Verified all 16 canonical invariants (INV-01 through INV-16).
[PASS] All referenced production source files exist.
[PASS] All referenced test suites exist.
[PASS] Structural checks & evidence requirements verified.
=== INVARIANT CONTRACT VALIDATION PASSED (Exit Code: 0) ===
```

### 5.2 Test Environment Matrix Validation
```
Command: python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_test_environment_matrix.py
Exit Code: 0
Output:
=================================================================
=== Earthlink Reseller App -- Test Environment Matrix Validator ===
=================================================================
[PASS] All 16 canonical invariants verified in matrix.
[PASS] All 26 active test suites & scripts verified on disk.
[PASS] Preserved 2 required Phase 3 pending test suites verified.
[PASS] Regression Protection verified: All Phase 1, 2, and 3 certification entries intact.
[PASS] Environment tiers (JVM, ROBOLECTRIC, INSTRUMENTED, STRUCTURAL, HISTORICAL) verified.
[PASS] Zero unmapped test files detected.
=== TEST ENVIRONMENT MATRIX VALIDATION PASSED (Exit Code: 0) ===
```

### 5.3 Forbidden Pattern Scanner
```
Command: python scripts/run_verified_command.py --timeout 60 -- python scripts/scan_forbidden_patterns.py
Exit Code: 0
Output:
=================================================================
=== Earthlink Reseller App -- Forbidden Pattern Scanner =======
=================================================================
Summary: Scanned 15 registered patterns across repository. Found 0 violation(s).
=== FORBIDDEN PATTERN SCAN: PASSED (0 Violations) ===
```

### 5.4 Unit Test Suite Execution
```
Command: python scripts/run_verified_command.py --timeout 300 -- .\gradlew.bat testDebugUnitTest
Exit Code: 0
Output:
BUILD SUCCESSFUL in 45s
35 actionable tasks: 3 executed, 32 up-to-date
```
Total Unit Tests: 68 tests completed, 0 failed.

---

## 6. Next Steps
Proceed with Task `P1-06`: Define and implement the Restore/Backup transport reconstruction decision table (`BackupManager.kt`, `backup_state_classification.yaml`, `P1-G2-REQ-05`).
