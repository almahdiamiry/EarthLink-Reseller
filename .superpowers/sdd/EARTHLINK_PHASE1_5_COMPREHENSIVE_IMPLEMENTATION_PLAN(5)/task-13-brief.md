# Task Brief: P1-13 — Phase-1 Evidence Collection and Gate Closure

## Context & Project Fit
Per `AGENTS.md`, `contract/phase_requirements.yaml`, and Section 4.14 of `EARTHLINK_PHASE1_5_COMPREHENSIVE_IMPLEMENTATION_PLAN(5).md`:
- Phase 1 (Core Invariant Consolidation & Outbox Durability / G1 Protection) gate closure requires 100% machine-verified proof for all Phase 1 requirements:
  - `P1-G2-REQ-01`: Outbox Durability (No Dead-Letter) -> `Phase1OutboxDurabilityTest`
  - `P1-G2-REQ-02`: Per-Item Failure Isolation -> `Phase1ItemIsolationTest`
  - `P1-G2-REQ-03`: Explicit Orphan Handling -> `Phase1OrphanHandlingTest`
  - `P1-G2-REQ-04`: Deterministic Firestore Document Identity -> `Phase1FirestoreDocumentIdentityTest`
  - `P1-G2-REQ-05`: Restore Transport Reconstruction -> `Phase1RestoreTransportReconstructionTest`
  - `P1-G2-REQ-06`: Room Atomicity & Lost-ACK Cloud Idempotency -> `Phase1AtomicityAndLostAckTest`, `Phase1G1PendingOperationDurabilityTest`
  - `P1-G2-REQ-07`: Multi-Device Convergence & Same-ID Immutability -> `Phase1TwoDeviceConvergenceTest`, `Phase1SameIdDivergentPayloadTest`, `Phase1DuplicateInitiationProtectionTest`, `Phase1UnknownOutcomeResolutionTest`

## Implementation Targets
- `contract/phase_requirements.yaml` (Phase 1 compliance status and test evidence mapping).
- `contract/compliance_matrix.yaml` (if present/updated).
- `scripts/verify_phase_compliance.py` (ensure phase 1 runs cleanly with exit code 0).
- `contract/invariant_contract.yaml`, `contract/invariant_test_map.yaml`, `contract/test_environment_matrix.yaml`.
- `.superpowers/sdd/EARTHLINK_PHASE1_5_COMPREHENSIVE_IMPLEMENTATION_PLAN(5)/task-13-report.md` & `phase-1-closure-memo.md`.

## Specific Requirements
1. Update `contract/phase_requirements.yaml` with machine-verified proof links and PASS status for all Phase 1 rows.
2. Run all verification scripts:
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_invariant_contract.py`
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_test_environment_matrix.py`
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/scan_forbidden_patterns.py`
   - `python scripts/run_verified_command.py --timeout 120 -- python scripts/verify_phase_compliance.py --phase 1` (if script exists)
   - `python scripts/run_verified_command.py --timeout 300 -- .\gradlew.bat testDebugUnitTest`
3. Commit changes to git and write report to `.superpowers/sdd/EARTHLINK_PHASE1_5_COMPREHENSIVE_IMPLEMENTATION_PLAN(5)/task-13-report.md`.
