# Task Brief: P1-01 — Freeze the Phase-1 working allowlist and rebuild current test identity

## Task Overview
This is the foundational control-plane inventory task for Phase 1.
You must reconcile contract metadata, verify the active test corpus on disk, align ADR-028 in `DESIGN_DECISIONS.md` to ensure it is marked/clarified as an implementation mechanism subordinate to frozen INV-11, and run all contract verification scripts to prove a clean baseline.

## Implementation Targets
- `DESIGN_DECISIONS.md` — ADR-028 classification/wording
- `contract/phase_requirements.yaml`
- `contract/invariant_contract.yaml`
- `contract/invariant_test_map.yaml`
- `contract/test_environment_matrix.yaml`
- `contract/forbidden_patterns.yaml`
- `scripts/verify_invariant_contract.py`
- `scripts/verify_test_environment_matrix.py`
- `scripts/scan_forbidden_patterns.py`

## Specific Actions Required
1. In `DESIGN_DECISIONS.md`, update ADR-028 header and note to clarify that `DataOperationCoordinator` is an implementation mutual-exclusion mechanism subordinate to frozen `INV-11` (Direct Atomic Room with short local transactions and minimal maintenance exclusion), not a canonical architecture authority or a generic sync state machine.
2. Verify all contract files (`contract/invariant_contract.yaml`, `contract/test_environment_matrix.yaml`, `contract/phase_requirements.yaml`, `contract/forbidden_patterns.yaml`).
3. Execute all baseline validators using `python scripts/run_verified_command.py`:
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_invariant_contract.py`
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_test_environment_matrix.py`
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/scan_forbidden_patterns.py`
4. Confirm git commit of changes.
5. Write the full task report to `.superpowers/sdd/EARTHLINK_PHASE1_5_COMPREHENSIVE_IMPLEMENTATION_PLAN(5)/task-1-report.md`.
