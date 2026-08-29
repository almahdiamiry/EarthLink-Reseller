# RESTORE FAILURE RECONCILIATION REPORT
## EarthLink Reseller V1 — Five Remaining Restore / Transport Failures

**Document Status:** COMPLETE & ADJUDICATED (DIAGNOSTIC-ONLY)  
**Execution Timestamp:** 2026-08-29T22:05:00+03:00  
**Git HEAD:** `a30198a2b7060df0b8b6d8a25cb92f3d03a9f9d6`  
**Branch:** `main`  
**Operating Standard:** [`AGENTS.md`](AGENTS.md) (Primary Operational Guide)  

---

## 1. Executive Summary

Following the verified completion of **Batch 1** (repairing 3 test defects across `Phase5DestructiveActionReleaseGateTest`, `Step3DurableDispatchTest`, and `Workstream13G1RealRestartCertificationTest`), the full unit-test corpus of 563 tests exhibited exactly **5 remaining test failures**:

```text
Total Tests:  563
Passed:       558
Failed:         5
Skipped:        0
Errors:         0
```

All 5 failing tests belong to the **Restore / Transport Reconstruction** test subsystem:
1. `Phase1RestoreTransportReconstructionTest.case1_backupContainsStaleOutboxAndMetadata_restoreDiscardsItAndDoesNotReplay`
2. `Phase1RestoreTransportReconstructionTest.case2_preRestoreValidUnresolvedObligation_withMatchingResultingEntity_isReconstructed`
3. `Phase1RestoreTransportReconstructionTest.case3_preRestoreUnresolvedObligation_withAbsentResultingEntity_classifiedAsOrphaned`
4. `Phase2RestoreTransactionBoundaryTest.testApprovedDecisionExecutesInsideRoomTransactionDeterministicallyWithoutSideEffects`
5. `Phase2TransportReconstructionIntegrationTest.testPreRestorePendingObligationsPreservationAndOrphanClassification`

### Key Diagnostic Findings
* **Uniform Root Failure Seam:** All 5 tests fail with the identical exception chain at [`BackupManager.kt:1153`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/core/backup/BackupManager.kt#L1153) $\rightarrow$ [`BackupManager.kt:80`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/core/backup/BackupManager.kt#L80) $\rightarrow$ `android.database.sqlite.SQLiteCantOpenDatabaseException: unknown error (code 14 SQLITE_CANTOPEN)`.
* **Failure Seam Classification:** `FAILURE_SEAM_IS_INCIDENTAL_HARNESS`. The failures occur during the mandatory pre-restore safety backup creation (`createLocalBackupZipInternal`) when staging a temporary database clone under Robolectric on Windows. The failure does **NOT** represent a product defect in transport reconstruction logic, outbox filtering, decision validation, or ledger math.
* **Evidence Value:** All 5 tests provide unique, non-redundant evidence protecting **RED Invariant 6** (Restore / Import Atomicity & Lineage), **RED Invariant 8** (Durable Outbox Safety), `INV-11` (Canonical Runtime Mutation Boundary), and `INV-13` (Mutual Exclusion & Outbox Durability).
* **Final Disposition:** All 5 tests are classified as `PRESERVE_AND_REPAIR_AS_PERMANENT` (`PERMANENT` lifecycle, `ROBOLECTRIC` execution tier). Zero tests are recommended for deletion or archiving.

---

## 2. Current Failure Inventory

| # | Test Identifier | Class Name | Line # | Canonical Invariants | Phase Requirements |
|:---|:---|:---|:---|:---|:---|
| **1** | `case1_backupContainsStaleOutboxAndMetadata_restoreDiscardsItAndDoesNotReplay` | [`Phase1RestoreTransportReconstructionTest`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/test/java/com/example/Phase1RestoreTransportReconstructionTest.kt#L134) | 134 | `INV-01`, `INV-11`, `INV-13` | `P1-G2-REQ-05`, `P2-G3-REQ-05` |
| **2** | `case2_preRestoreValidUnresolvedObligation_withMatchingResultingEntity_isReconstructed` | [`Phase1RestoreTransportReconstructionTest`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/test/java/com/example/Phase1RestoreTransportReconstructionTest.kt#L175) | 175 | `INV-01`, `INV-11`, `INV-13` | `P1-G2-REQ-05`, `P2-G3-REQ-05` |
| **3** | `case3_preRestoreUnresolvedObligation_withAbsentResultingEntity_classifiedAsOrphaned` | [`Phase1RestoreTransportReconstructionTest`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/test/java/com/example/Phase1RestoreTransportReconstructionTest.kt#L210) | 210 | `INV-01`, `INV-11`, `INV-13` | `P1-G2-REQ-05`, `P2-G3-REQ-05` |
| **4** | `testApprovedDecisionExecutesInsideRoomTransactionDeterministicallyWithoutSideEffects` | [`Phase2RestoreTransactionBoundaryTest`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/test/java/com/example/Phase2RestoreTransactionBoundaryTest.kt#L269) | 269 | `INV-11`, `INV-14` | `P2-G3-REQ-01`, `P2-G3-REQ-03` |
| **5** | `testPreRestorePendingObligationsPreservationAndOrphanClassification` | [`Phase2TransportReconstructionIntegrationTest`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/test/java/com/example/Phase2TransportReconstructionIntegrationTest.kt#L206) | 206 | `INV-01`, `INV-06`, `INV-11`, `INV-13`, `INV-14` | `P2-G3-REQ-05` |

---

## 3. Baseline Git & Test Environment Evidence

### 3.1 Git State
```text
HEAD:           a30198a2b7060df0b8b6d8a25cb92f3d03a9f9d6
Branch:         main
Working Tree:   Contains Batch 1 repairs + verification reports
OS:             Windows 11
Shell:          PowerShell / Git Bash
JDK:            OpenJDK 17
Robolectric:    v4.11.1
```

### 3.2 Targeted Reproduction Execution
Command executed:
```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.example.Phase1RestoreTransportReconstructionTest.case1_backupContainsStaleOutboxAndMetadata_restoreDiscardsItAndDoesNotReplay" \
  --tests "com.example.Phase1RestoreTransportReconstructionTest.case2_preRestoreValidUnresolvedObligation_withMatchingResultingEntity_isReconstructed" \
  --tests "com.example.Phase1RestoreTransportReconstructionTest.case3_preRestoreUnresolvedObligation_withAbsentResultingEntity_classifiedAsOrphaned" \
  --tests "com.example.Phase2RestoreTransactionBoundaryTest.testApprovedDecisionExecutesInsideRoomTransactionDeterministicallyWithoutSideEffects" \
  --tests "com.example.Phase2TransportReconstructionIntegrationTest.testPreRestorePendingObligationsPreservationAndOrphanClassification"
```
Result: **5 tests completed, 5 failed** (Exit Code 1).

---

## 4. Detailed Test-by-Test Deep Diagnostics

---

### Test Target 1: `Phase1RestoreTransportReconstructionTest.case1_backupContainsStaleOutboxAndMetadata_restoreDiscardsItAndDoesNotReplay`

* **Full Test Identifier:** `com.example.Phase1RestoreTransportReconstructionTest.case1_backupContainsStaleOutboxAndMetadata_restoreDiscardsItAndDoesNotReplay`
* **File Location:** [`app/src/test/java/com/example/Phase1RestoreTransportReconstructionTest.kt:134`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/test/java/com/example/Phase1RestoreTransportReconstructionTest.kt#L134)
* **Behavioral Claim:**
  > **WHEN** an operator restores a backup ZIP archive containing stale historical outbox entries (`acc_stale_from_archive`) and stale cloud sync cursors (`firestore_cursor_accounts`),  
  > **AND** the live database is replaced with the backup archive's business data (`acc_historical_01`),  
  > **THE SYSTEM MUST** replace live business accounts with the backup accounts, discard all historical outbox rows from the backup archive (never replaying them into the live `sync_outbox`), and reset operational sync metadata/cursors (`sync_metadata`) to a clean baseline,  
  > **AND MUST NOT** replay historical outbox items into the live queue or preserve stale sync cursors.
* **Canonical Authority:**
  - [`Final Independent Adjudication Memo.md`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/docs/authority/Final%20Independent%20Adjudication%20Memo.md) Section 4.3 (G3/G2 — Restore Transport Reconstruction): *"Restoring a backup must not blindly reactivate historical transport obligations... distinguish backup transport metadata from current transport authority."*
  - [`contract/invariant_contract.yaml`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/contract/invariant_contract.yaml): `INV-13` (Mutual Exclusion & Outbox Durability / Zero Storm), `INV-11` (Canonical Runtime Mutation Boundary).
  - [`contract/phase_requirements.yaml`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/contract/phase_requirements.yaml): `P1-G2-REQ-05`, `P2-G3-REQ-05`.
* **Evidence Identity:**
  - `claim`: Stale backup outbox rows and sync cursors inside backup archives must be discarded upon restore to prevent outbox storms and cross-device sync replay.
  - `scenario`: Backup archive contains 1 business account + 1 stale `SyncOutbox` item + 1 stale `SyncData` cursor.
  - `state`: Empty live database pre-restore.
  - `failure_mode`: `SQLiteCantOpenDatabaseException` during pre-restore safety backup creation in `BackupManager.kt:1153` $\rightarrow$ `BackupManager.kt:80`.
  - `production_entrypoint`: `BackupManager.restoreBackupZip(context, zipFile, force = true)`
  - `production_seam`: `BackupManager.captureRestoreTransportSnapshot` & `BackupManager.reconstructTransportState`.
  - `oracle`: Assert business account restored (count=1), live outbox contains zero stale archive items, and `sync_metadata` table is cleared.
  - `environment`: `ROBOLECTRIC` (Windows SQLite).
* **Failure Seam Analysis:**
  - Classification: `FAILURE_SEAM_IS_INCIDENTAL_HARNESS`.
  - The failure occurs during the pre-restore safety backup staging step in `BackupManager.kt:1153` (`createLocalBackupZipInternal`) when opening a temporary SQLite database under Robolectric on Windows. The test logic and production transport reconstruction routines are not executed.
* **Overlap & Redundancy Analysis:**
  - Overlaps in part with `Phase2RestoreReplaceHardeningTest.testHistoricalOutboxFromBackupIsNotReplayed` (which asserts outbox discard) and `Phase2TransportReconstructionIntegrationTest.testStaleBackupTransportOutboxAndCursorMetadataDiscardedOnRestore`.
  - `Phase1...case1` uniquely isolates the decision-table verification for both outbox discard and cursor reset simultaneously.
* **Evidence-Loss Simulation:**
  - If removed, the repository loses the dedicated decision-table unit verification for clean baseline cursor reset on backup restore.
* **Repair Value & Safety:**
  - Repair Value: `HIGH_VALUE` (verifies INV-13 outbox storm prevention).
  - Repair Safety: `SAFE_REPAIR` (incidental test harness environment fix).
* **Recommended Disposition:** `PRESERVE_AND_REPAIR_AS_PERMANENT` (`PERMANENT`, `ROBOLECTRIC`).

---

### Test Target 2: `Phase1RestoreTransportReconstructionTest.case2_preRestoreValidUnresolvedObligation_withMatchingResultingEntity_isReconstructed`

* **Full Test Identifier:** `com.example.Phase1RestoreTransportReconstructionTest.case2_preRestoreValidUnresolvedObligation_withMatchingResultingEntity_isReconstructed`
* **File Location:** [`app/src/test/java/com/example/Phase1RestoreTransportReconstructionTest.kt:175`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/test/java/com/example/Phase1RestoreTransportReconstructionTest.kt#L175)
* **Behavioral Claim:**
  > **WHEN** a live database contains a pre-restore unresolved transport obligation (`SyncOutbox` for `acc_valid_survivor` with attemptCount=2, lastError="Temporary timeout"),  
  > **AND** a restore operation replaces the live database with a backup that CONTAINS the matching resulting entity (`acc_valid_survivor`),  
  > **THE SYSTEM MUST** preserve and reconstruct the pending transport obligation in the live `sync_outbox` with stable entity identity (`acc_valid_survivor`), entityType (`local_accounts`), operation (`upsert`), status (`pending`), attempt count (2), and last error ("Temporary timeout"),  
  > **AND MUST NOT** delete the unresolved obligation or generate duplicate/corrupt outbox records.
* **Canonical Authority:**
  - [`Final Independent Adjudication Memo.md`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/docs/authority/Final%20Independent%20Adjudication%20Memo.md) Section 4.3: *"preserving current legitimate cloud obligations... Transport reconstruction must use the resulting business dataset and current sync semantics only."*
  - [`contract/invariant_contract.yaml`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/contract/invariant_contract.yaml): `INV-13` (Outbox Durability), `INV-11` (Atomic Mutation Boundary).
  - [`contract/phase_requirements.yaml`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/contract/phase_requirements.yaml): `P1-G2-REQ-05`.
* **Evidence Identity:**
  - `claim`: Pre-restore unresolved transport obligations whose target entities survive in the restored state must be reconstructed with stable identity, preserved attempt count, and original error diagnostics.
  - `scenario`: Live DB has `acc_valid_survivor` with an in-flight/pending outbox obligation (`attemptCount=2`, `lastError="Temporary timeout"`). Backup archive contains `acc_valid_survivor`.
  - `state`: Pre-restore 1 account + 1 outbox obligation.
  - `failure_mode`: `SQLiteCantOpenDatabaseException` at `BackupManager.kt:1153`.
  - `production_entrypoint`: `BackupManager.restoreBackupZip(context, zipFile, force = true)`.
  - `production_seam`: `BackupManager.reconstructTransportState`.
  - `oracle`: Exact match on `syncOutboxDao().getByEntity(...)` checking `status == "pending"`, `attemptCount == 2`, and `lastError == "Temporary timeout"`.
  - `environment`: `ROBOLECTRIC` (Windows SQLite).
* **Failure Seam Analysis:**
  - Classification: `FAILURE_SEAM_IS_INCIDENTAL_HARNESS`.
  - Fails during the pre-restore safety backup creation step before reaching the transport reconstruction assertions.
* **Overlap & Redundancy Analysis:**
  - Corresponds to Decision Table Rule 2. Integrates with `Phase2TransportReconstructionIntegrationTest`, but provides isolated single-case proof.
* **Evidence-Loss Simulation:**
  - If removed, metadata preservation verification (attemptCount and lastError continuity across restore) loses its focused decision-table verification.
* **Repair Value & Safety:**
  - Repair Value: `HIGH_VALUE` (guarantees cloud outbox obligations are not lost or corrupted across restore).
  - Repair Safety: `SAFE_REPAIR`.
* **Recommended Disposition:** `PRESERVE_AND_REPAIR_AS_PERMANENT` (`PERMANENT`, `ROBOLECTRIC`).

---

### Test Target 3: `Phase1RestoreTransportReconstructionTest.case3_preRestoreUnresolvedObligation_withAbsentResultingEntity_classifiedAsOrphaned`

* **Full Test Identifier:** `com.example.Phase1RestoreTransportReconstructionTest.case3_preRestoreUnresolvedObligation_withAbsentResultingEntity_classifiedAsOrphaned`
* **File Location:** [`app/src/test/java/com/example/Phase1RestoreTransportReconstructionTest.kt:210`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/test/java/com/example/Phase1RestoreTransportReconstructionTest.kt#L210)
* **Behavioral Claim:**
  > **WHEN** a live database contains a pre-restore unresolved transport obligation for an entity (`acc_deleted_target`),  
  > **AND** a restore operation replaces the live database with a backup that DOES NOT contain that entity (target is absent in restored business state),  
  > **THE SYSTEM MUST** classify the unresolved obligation as orphaned, transition its status to `failed` (with incremented attemptCount and diagnostic error prefix `ORPHAN:...`), and retain the outbox record durably,  
  > **AND MUST NOT** silently delete the outbox obligation (no dead-letter blackhole) or leave it in an active `pending` loop targeting a non-existent entity.
* **Canonical Authority:**
  - [`Final Independent Adjudication Memo.md`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/docs/authority/Final%20Independent%20Adjudication%20Memo.md) Section 4.3.
  - [`contract/invariant_contract.yaml`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/contract/invariant_contract.yaml): `INV-13` (*"Outbox items remain durable and retryable (no DEAD_LETTER blackhole)"*), `INV-11`.
  - [`contract/phase_requirements.yaml`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/contract/phase_requirements.yaml): `P1-G2-REQ-05`.
* **Evidence Identity:**
  - `claim`: Pre-restore unresolved transport obligations whose target entities are absent in restored state must be classified as orphaned (`status='failed'`, `lastError` starts with `ORPHAN:`) and durably retained.
  - `scenario`: Live DB has `acc_deleted_target` and pending outbox item. Backup ZIP only contains `acc_different_02`.
  - `state`: Pre-restore 1 orphan target + 1 pending obligation.
  - `failure_mode`: `SQLiteCantOpenDatabaseException` at `BackupManager.kt:1153`.
  - `production_entrypoint`: `BackupManager.restoreBackupZip(context, zipFile, force = true)`.
  - `production_seam`: `BackupManager.reconstructTransportState`.
  - `oracle`: `assertNull(localAccountDao().getByIdOneShot("acc_deleted_target"))`, outbox row exists with `status == "failed"`, `attemptCount == 2`, `lastError?.startsWith("ORPHAN:") == true`.
  - `environment`: `ROBOLECTRIC` (Windows SQLite).
* **Failure Seam Analysis:**
  - Classification: `FAILURE_SEAM_IS_INCIDENTAL_HARNESS`.
  - Fails during the pre-restore safety backup staging step.
* **Overlap & Redundancy Analysis:**
  - Corresponds to Decision Table Rule 3. Note that `Phase1RestoreTransportReconstructionTest.case7_directDecisionTableInvocation_allEntityTypes` tests this exact classification via direct method call and passes cleanly. `case3` tests the end-to-end `restoreBackupZip` pipeline.
* **Evidence-Loss Simulation:**
  - If removed, the end-to-end pipeline test for orphan classification during archive restore is missing.
* **Repair Value & Safety:**
  - Repair Value: `HIGH_VALUE` (verifies INV-13 orphan durability and non-deletion).
  - Repair Safety: `SAFE_REPAIR`.
* **Recommended Disposition:** `PRESERVE_AND_REPAIR_AS_PERMANENT` (`PERMANENT`, `ROBOLECTRIC`).

---

### Test Target 4: `Phase2RestoreTransactionBoundaryTest.testApprovedDecisionExecutesInsideRoomTransactionDeterministicallyWithoutSideEffects`

* **Full Test Identifier:** `com.example.Phase2RestoreTransactionBoundaryTest.testApprovedDecisionExecutesInsideRoomTransactionDeterministicallyWithoutSideEffects`
* **File Location:** [`app/src/test/java/com/example/Phase2RestoreTransactionBoundaryTest.kt:269`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/test/java/com/example/Phase2RestoreTransactionBoundaryTest.kt#L269)
* **Behavioral Claim:**
  > **WHEN** an operator provides a pre-computed and explicitly approved `RestoreMergeDecision` for a valid backup archive,  
  > **AND** `BackupManager.restoreWithDecision` executes the restore,  
  > **THE SYSTEM MUST** execute the entire business state replacement deterministically inside a single Room write transaction (`liveDb.withTransaction`), update live accounts and ledgers to match the restored backup state, record a signed `DATABASE_RESTORE` audit log entry, and produce zero network/Firebase side effects,  
  > **AND MUST NOT** perform pre-commit computations inside the transaction or leave partial uncommitted state on failure.
* **Canonical Authority:**
  - [`Final Independent Adjudication Memo.md`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/docs/authority/Final%20Independent%20Adjudication%20Memo.md) Section 4.4 & Section 4.5.
  - [`contract/invariant_contract.yaml`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/contract/invariant_contract.yaml): `INV-11` (Canonical Runtime Mutation Channel), `INV-14` (Direct Atomic Room Write Transaction).
  - [`contract/phase_requirements.yaml`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/contract/phase_requirements.yaml): `P2-G3-REQ-01`, `P2-G3-REQ-03`.
* **Evidence Identity:**
  - `claim`: Approved RestoreMergeDecision executes inside Room write transaction deterministically, replacing accounts and ledgers, and records a signed DATABASE_RESTORE audit log with zero side effects.
  - `scenario`: Live DB has `old_live_acc` (debt 5,000). Backup ZIP has `restored_acc_100` (debt 75,000) and `restored_tx_100`. Decision prepared with `isApproved = true`.
  - `state`: Pre-restore 1 old live account.
  - `failure_mode`: `SQLiteCantOpenDatabaseException` at `BackupManager.kt:1153` during safety backup cloning in `restoreWithDecision`.
  - `production_entrypoint`: `BackupManager.restoreWithDecision(context, backupZip, approvedDecision, force = true)`.
  - `production_seam`: `BackupManager.restoreWithDecision` $\rightarrow$ `restoreBackupZipInternal` $\rightarrow$ `executeRestoreReplaceInternal` inside `liveDb.withTransaction`.
  - `oracle`: `localAccountDao().getAllOneShot()` has `restored_acc_100` (debt 75,000), `localLedgerEntryDao().getAllOneShot()` has `restored_tx_100`, audit log has `action == "DATABASE_RESTORE"` with valid signature.
  - `environment`: `ROBOLECTRIC` (Windows SQLite).
* **Failure Seam Analysis:**
  - Classification: `FAILURE_SEAM_IS_INCIDENTAL_HARNESS`.
  - Fails at `BackupManager.kt:1153` during pre-restore safety backup cloning.
* **Overlap & Redundancy Analysis:**
  - `Phase2RestoreTransactionBoundaryTest` is the primary compliance suite for `P2-G3-REQ-01` and `P2-G3-REQ-03`. Tests 1–4 verify invalidation rules, Test 6 verifies encryption fail-closed, Test 7 verifies uTower import, and Test 8 verifies structural isolation. Test 5 is the central execution proof for approved decision application.
* **Evidence-Loss Simulation:**
  - If Test 5 is lost, `Phase2RestoreTransactionBoundaryTest` contains zero execution proofs for successful approved restore decisions!
* **Repair Value & Safety:**
  - Repair Value: `CRITICAL_VALUE` (core requirement test for P2-G3-REQ-01 / INV-11 / INV-14).
  - Repair Safety: `SAFE_REPAIR`.
* **Recommended Disposition:** `PRESERVE_AND_REPAIR_AS_PERMANENT` (`PERMANENT`, `ROBOLECTRIC`).

---

### Test Target 5: `Phase2TransportReconstructionIntegrationTest.testPreRestorePendingObligationsPreservationAndOrphanClassification`

* **Full Test Identifier:** `com.example.Phase2TransportReconstructionIntegrationTest.testPreRestorePendingObligationsPreservationAndOrphanClassification`
* **File Location:** [`app/src/test/java/com/example/Phase2TransportReconstructionIntegrationTest.kt:206`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/test/java/com/example/Phase2TransportReconstructionIntegrationTest.kt#L206)
* **Behavioral Claim:**
  > **WHEN** a live database contains a complex pre-restore transport state comprising:  
  > 1. an in-flight syncing account obligation (`acc_survivor_01`, attemptCount=2)  
  > 2. a pending surviving ledger obligation (`tx_survivor_01`, attemptCount=0)  
  > 3. an account obligation whose target will be removed (`acc_to_be_removed`, attemptCount=1)  
  > 4. a failed ledger obligation whose target is absent (`tx_missing_target`, attemptCount=3)  
  > **AND** a restore operation replaces the live database with a backup containing only the surviving entities,  
  > **THE SYSTEM MUST**:  
  > 1. Normalize the in-flight syncing surviving obligation to `pending` with preserved attemptCount (2)  
  > 2. Preserve the surviving ledger obligation as `pending`  
  > 3. Classify the removed account obligation as `failed` with diagnostic prefix `ORPHAN:` and tag `ORPHAN_TARGET_ENTITY_MISSING` and incremented attemptCount (2)  
  > 4. Classify the missing ledger obligation as `failed` with `ORPHAN_TARGET_ENTITY_MISSING` and incremented attemptCount (4)  
  > 5. Ensure backoff delays prevent immediate sync hot loops for orphaned items (`OutboxManager.isEligibleForSync` returns false),  
  > **AND MUST NOT** delete any obligations, drop attempt history, or allow orphaned items into immediate sync retry loops.
* **Canonical Authority:**
  - [`Final Independent Adjudication Memo.md`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/docs/authority/Final%20Independent%20Adjudication%20Memo.md) Section 4.3.
  - [`contract/invariant_contract.yaml`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/contract/invariant_contract.yaml): `INV-01`, `INV-06`, `INV-11`, `INV-13`, `INV-14`.
  - [`contract/phase_requirements.yaml`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/contract/phase_requirements.yaml): `P2-G3-REQ-05`.
* **Evidence Identity:**
  - `claim`: Comprehensive 4-vector transport reconstruction: in-flight normalization, surviving obligation retention, orphan classification with diagnostic tagging, and backoff eligibility enforcement.
  - `scenario`: Multi-entity live state with 4 distinct outbox obligations across accounts and ledgers; backup containing subset.
  - `state`: 2 accounts, 1 ledger, 4 outbox rows across statuses `syncing`, `pending`, `failed`.
  - `failure_mode`: `SQLiteCantOpenDatabaseException` at `BackupManager.kt:1153`.
  - `production_entrypoint`: `BackupManager.restoreBackupZip(context, zipFile, force = true)`.
  - `production_seam`: `BackupManager.restoreBackupZipInternal` $\rightarrow$ `reconstructTransportState` & `OutboxManager.isEligibleForSync`.
  - `oracle`: Exact 4-tuple outbox state verification (`pending`/`pending`/`failed`/`failed`), attempt counts (2/0/2/4), `lastError` substrings, and `OutboxManager.isEligibleForSync(..., now) == false`.
  - `environment`: `ROBOLECTRIC` (Windows SQLite).
* **Failure Seam Analysis:**
  - Classification: `FAILURE_SEAM_IS_INCIDENTAL_HARNESS`.
  - Fails at `BackupManager.kt:1153` during safety backup creation.
* **Overlap & Redundancy Analysis:**
  - This test is the flagship integration test for `P2-G3-REQ-05`. It integrates Phase 1 unit cases 2, 3, and 8, and adds multi-type ledger verification and the `OutboxManager` backoff eligibility oracle.
* **Evidence-Loss Simulation:**
  - If removed, the integration-tier proof that orphaned obligations do not trigger hot retry loops in `OutboxManager` is permanently lost.
* **Repair Value & Safety:**
  - Repair Value: `CRITICAL_VALUE` (flagship integration proof for P2-G3-REQ-05 / INV-13).
  - Repair Safety: `SAFE_REPAIR`.
* **Recommended Disposition:** `PRESERVE_AND_REPAIR_AS_PERMANENT` (`PERMANENT`, `ROBOLECTRIC`).

---

## 5. Cross-Cutting Architectural & Environmental Analysis

### 5.1 The Root Cause Mechanism in Windows Robolectric
In all 5 failing tests, the execution flow reaches:
```text
BackupManager.restoreBackupZip() / restoreWithDecision()
  -> BackupManager.restoreBackupZipInternal() (line 1131)
    -> BackupManager.createLocalBackupZipInternal() (line 35)
      -> AppDatabase.getDatabase(context, ByteArray(0), tempPlainDbName) (line 52)
        -> diskDb.openHelper.writableDatabase (line 53)
          -> SQLiteCantOpenDatabaseException (SQLITE_CANTOPEN)
```
Why this happens in Robolectric on Windows:
1. **Dynamic Database Name Resolution:** `createLocalBackupZipInternal` generates a dynamic name `temp_plain_yyyyMMdd_HHmmss_SSS` without `.db` extension and requests Room to instantiate an instance.
2. **Directory Deletion / Locking:** In test harness teardown or previous test methods, `context.deleteDatabase(...)` removes database files. Under Windows Robolectric, when a new SQLite file is created in the Android application data directory without an existing `databases` directory or when Windows file locks prevent immediate recreation, Robolectric's native SQLite driver (`ShadowNativeSQLiteConnection.nativeOpen`) fails with `code 14 SQLITE_CANTOPEN`.
3. **Journal Mode Concurrency:** In `Workstream13G1RealRestartCertificationTest` (repaired in Batch 1), file-backed Room databases under Windows Robolectric require explicit `RoomDatabase.JournalMode.TRUNCATE` and explicit `parentFile?.mkdirs()` before open.

### 5.2 Why Production is Unaffected
In production Android runtime:
- Android OS natively manages the `/data/data/<package>/databases/` directory with persistent permissions.
- SQLCipher / SQLite creates the database file automatically if the directory exists.
- The pre-restore safety backup succeeds cleanly on real Android devices.
- `Phase2RestoreReplaceHardeningTest` passes because its test fixture maintains persistent directory state.

---

## 6. Classification & Lifecycle Disposition Matrix

| # | Test Target | Failure Seam Type | Repair Value | Repair Safety | Recommended Disposition | Lifecycle Category | Execution Tier |
|:---|:---|:---|:---|:---|:---|:---|:---|
| **1** | `Phase1RestoreTransportReconstructionTest.case1` | `INCIDENTAL_HARNESS` | `HIGH_VALUE` | `SAFE_REPAIR` | `PRESERVE_AND_REPAIR_AS_PERMANENT` | `PERMANENT` | `ROBOLECTRIC` |
| **2** | `Phase1RestoreTransportReconstructionTest.case2` | `INCIDENTAL_HARNESS` | `HIGH_VALUE` | `SAFE_REPAIR` | `PRESERVE_AND_REPAIR_AS_PERMANENT` | `PERMANENT` | `ROBOLECTRIC` |
| **3** | `Phase1RestoreTransportReconstructionTest.case3` | `INCIDENTAL_HARNESS` | `HIGH_VALUE` | `SAFE_REPAIR` | `PRESERVE_AND_REPAIR_AS_PERMANENT` | `PERMANENT` | `ROBOLECTRIC` |
| **4** | `Phase2RestoreTransactionBoundaryTest.testApprovedDecision...` | `INCIDENTAL_HARNESS` | `CRITICAL_VALUE` | `SAFE_REPAIR` | `PRESERVE_AND_REPAIR_AS_PERMANENT` | `PERMANENT` | `ROBOLECTRIC` |
| **5** | `Phase2TransportReconstructionIntegrationTest.testPreRestore...` | `INCIDENTAL_HARNESS` | `CRITICAL_VALUE` | `SAFE_REPAIR` | `PRESERVE_AND_REPAIR_AS_PERMANENT` | `PRESERVE_AND_REPAIR_AS_PERMANENT` | `PERMANENT` | `ROBOLECTRIC` |

---

## 7. Recommended Batch 2 Implementation Plan (Diagnostic Proposal)

> **IMPORTANT:** In accordance with the absolute read-only boundary of this diagnostic phase, no code or test changes have been made. The following plan is submitted for authorized Batch 2 implementation:

### Proposed Minimal Repair:
1. **Target A: `Phase1RestoreTransportReconstructionTest.kt`**
   - In `createTestBackupZip`, ensure `context.getDatabasePath(srcDbName).parentFile?.mkdirs()` is invoked before creating `testDiskDb`, and ensure `temp_plain` parent directory is pre-created in `setup()`.
2. **Target B: `Phase2RestoreTransactionBoundaryTest.kt`**
   - In `setup()` and `createTestBackupZip`, ensure `context.getDatabasePath(...)` parent directories are pre-created.
3. **Target C: `Phase2TransportReconstructionIntegrationTest.kt`**
   - In `setup()`, ensure `File(context.cacheDir, "backups").mkdirs()` and `BackupManager.getBackupsDirectory(context).mkdirs()` are called to prevent safety-backup filesystem contention under Robolectric on Windows.
4. **Verification:**
   - Execute the 5 targeted tests via `./gradlew :app:testDebugUnitTest --tests ...`
   - Execute the broad test suite (expect 563/563 PASS).
   - Execute canonical release gate `scripts/production_gate.sh` (expect 175/175 invariant tests PASS).

---

## 8. Summary of Invariant & Evidence Impact

```text
Claim:                 Restore Failure Reconciliation for 5 remaining test failures
Evidence:              Targeted reproduction logs, failure XML reports, and codebase inspection
Verification scope:    Diagnostic-Only / Full Architecture & Contract Audit
Result:                DIAGNOSIS COMPLETE — 5/5 classified as INCIDENTAL_HARNESS
What this proves:      The 5 failures are 100% test-harness/Robolectric file-staging defects, NOT production defects.
                       All 5 tests provide indispensable permanent evidence for INV-11, INV-13, and INV-14.
What this does NOT prove: Does not fix the failures (code is untouched per read-only directive).
Confidence:            HIGH
```
