# Task Brief: P1-12 — Two-device convergence fixture and proof

## Context & Project Fit
Per `P1-G2-REQ-07`, `INV-01`, `INV-06`, `INV-11`, and Section 4.13 of `EARTHLINK_PHASE1_5_COMPREHENSIVE_IMPLEMENTATION_PLAN(5).md`:
- Multi-device distributed sync must guarantee full convergence across independent client devices (Device A and Device B) sharing a single cloud Firestore backend.
- Neither device may drop transactions, duplicate records, or diverge in computed balances.
- Tombstones must propagate cleanly across devices, deleting records and adjusting balances consistently.

## Implementation Targets
- `app/src/test/java/com/example/Phase1TwoDeviceConvergenceTest.kt` — comprehensive two-device simulation test suite.
- `contract/invariant_contract.yaml`, `contract/invariant_test_map.yaml`, `contract/test_environment_matrix.yaml` — contract mapping.

## Specific Requirements
1. Two-Device Simulation Test Fixture:
   - Simulate two distinct SQLite database instances (representing Device A and Device B) and a shared in-memory/mock Firestore backend.
   - Scenario 1 (Independent offline creation and cross-sync):
     - Device A creates Payment `tx-A1` for Account 1 offline;
     - Device B creates Renewal `tx-B1` for Account 2 offline;
     - Device A pushes to Firestore;
     - Device B pulls from Firestore, then pushes `tx-B1`;
     - Device A pulls from Firestore;
     - Verify: Both Device A and Device B have identical ledger records (`tx-A1` and `tx-B1`), matching balances, zero lost updates, zero duplicates.
   - Scenario 2 (Concurrent operations on same account):
     - Device A adds Debt (+50,000 IQD) to Account X;
     - Device B adds Payment (+30,000 IQD) to Account X;
     - Both devices sync to Firestore and pull updates;
     - Verify: Both devices contain both transactions and Account X balance on both devices converges to exactly 20,000 IQD debt.
   - Scenario 3 (Tombstone cross-device deletion & balance adjustment):
     - Device A deletes transaction `tx-A1` with tombstone;
     - Device A pushes tombstone to Firestore;
     - Device B syncs and pulls tombstone;
     - Verify: `tx-A1` is removed from Device B local ledger, and Device B account balance reverts correctly.
   - Scenario 4 (Remote version progression & metadata integrity):
     - Server version timestamps advance monotonically;
     - Outbox obligations are cleared on each device once successfully synced.
2. Verification:
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_invariant_contract.py`
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_test_environment_matrix.py`
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/scan_forbidden_patterns.py`
   - `python scripts/run_verified_command.py --timeout 300 -- .\gradlew.bat testDebugUnitTest`
3. Commit changes to git and write report to `.superpowers/sdd/EARTHLINK_PHASE1_5_COMPREHENSIVE_IMPLEMENTATION_PLAN(5)/task-12-report.md`.
