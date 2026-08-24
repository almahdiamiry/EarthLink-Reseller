# Historical Evidence Archive

This directory contains archived historical evidence artifacts, milestone completion records, old closure bundles, forensic audits, and historical phase reports from earlier development and recovery phases (Phases 0 through Phase 6 / G1–G8).

## Mandatory Operational Rules

1. **HISTORICAL / READ-ONLY:** All artifacts in this directory are non-operational historical records preserved exclusively for audit, decision provenance, and historical verification.
2. **NOT OPERATIONAL INPUTS:** Active release gates (`scripts/production_gate.sh`), verification scripts (`verify_invariant_contract.py`, `verify_test_environment_matrix.py`, `scan_forbidden_patterns.py`), and test suites do **NOT** read or depend on any file in this archive.
3. **DO NOT MODIFY:** Do not modify, rewrite, or delete historical evidence files unless explicitly requested as part of governance maintenance.

## Directory Structure

* `closure/`: Historical closure bundles and result JSON files for specific past Git SHAs (e.g., `51a3dbe...`, `92738c6...`, `ba1761f...`).
* `milestones/`: Milestone completion records and baseline JSON files (`phase1_completion.json`, `phase2_completion.json`, `phase3_completion.json`, `rootfix_baseline.json`).
* `audits/`: Independent forensic audit reports, root cause analyses, and crosswalk documents.
* `phase-reports/`: Historical phase closure memos, matrix reconciliation reports, and code review reports.
* `g8/`: Historical G8 build and test execution receipts (`g8_build_release.json`, `g8_test_execution.json`).
* `INDEX.md`: Comprehensive provenance index mapping every archived artifact to its original path, historical milestone, Git SHA, and verified verdict.
