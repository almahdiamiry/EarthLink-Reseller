# Historical Evidence Provenance Index

This index provides historical provenance, Git SHA bindings, milestone mapping, and verified verdicts for all archived evidence artifacts.

---

## 1. Historical Closure Bundles (`closure/`)

| Archived Path | Original Path | Source Git SHA | Invariant Hash | Contract Hash | Machine Verdict | Milestone / Purpose |
|:---|:---|:---|:---|:---|:---:|:---|
| `docs/historical/evidence/closure/51a3dbe.../` | `evidence/51a3dbe7804a45ee20b8060999e4daa8331dfc13/` | `51a3dbe7804a45ee20b8060999e4daa8331dfc13` | Verified | Verified | **PASS / CLOSED** | Milestone Closure Bundle (392 Unit Tests PASS) |
| `docs/historical/evidence/closure/92738c6.../` | `evidence/92738c68fed5aa325faa91332e03022023174b3a/` | `92738c68fed5aa325faa91332e03022023174b3a` | Verified | Verified | **PASS / CLOSED** | Milestone Closure Bundle (392 Unit Tests PASS) |
| `docs/historical/evidence/closure/ba1761f.../` | `evidence/ba1761ffa8b0cb62fb744e03aef429175831af7a/` | `ba1761ffa8b0cb62fb744e03aef429175831af7a` | Verified | Verified | **PASS / CLOSED** | Milestone Closure Bundle (392 Unit Tests PASS) |
| `docs/historical/evidence/closure/phase2_closure_bundle.json` | `evidence/phase2_closure_bundle.json` | `ba1761ffa8b0cb62fb744e03aef429175831af7a` | Verified | Verified | **PASS / CLOSED** | Phase 2 Recovery Closure Bundle |

---

## 2. Milestone Completion Records (`milestones/`)

| Archived Path | Original Path | Milestone | Date / Version | What It Proved |
|:---|:---|:---|:---|:---|
| `docs/historical/evidence/milestones/phase1_completion.json` | `evidence/phase1_completion.json` | Phase 1 | 2026-08-14 | Verified local version resolution authority and `resolveLocalVersion()` |
| `docs/historical/evidence/milestones/phase2_completion.json` | `evidence/phase2_completion.json` | Phase 2 | 2026-08-15 | Verified server-confirmed `remote_version` lifecycle |
| `docs/historical/evidence/milestones/phase3_completion.json` | `evidence/phase3_completion.json` | Phase 3 | 2026-08-15 | Verified `DataOperationCoordinator` mutex token re-entrancy |
| `docs/historical/evidence/milestones/rootfix_baseline.json` | `evidence/rootfix_baseline.json` | Rootfix Baseline | 2026-08-14 | Structural baseline prior to Phase 1-6 recovery |

---

## 3. Historical G8 Execution Receipts (`g8/`)

| Archived Path | Original Path | Milestone | Date | What It Proved |
|:---|:---|:---|:---|:---|
| `docs/historical/evidence/g8/g8_build_release.json` | `evidence/g8_build_release.json` | G8 Certification | 2026-08-12 | Release APK build verification receipt |
| `docs/historical/evidence/g8/g8_test_execution.json` | `evidence/g8_test_execution.json` | G8 Certification | 2026-08-12 | 79/79 G8 adversarial probe execution receipt |

---

## 4. Forensic Audits & Root Cause Reports (`audits/`)

| Archived Path | Original Path | Subject / Scope | Key Finding / Decision |
|:---|:---|:---|:---|
| `docs/historical/evidence/audits/MAIN_PLAN_TASK_0_10_FORENSIC_REAUDIT.md` | `evidence/MAIN_PLAN_TASK_0_10_FORENSIC_REAUDIT.md` | Tasks 0-10 Forensic Re-Audit | Re-verified G1-G5 recovery implementation Parity |
| `docs/historical/evidence/audits/MAIN_PLAN_TASK_10_19_CROSSWALK.md` | `evidence/MAIN_PLAN_TASK_10_19_CROSSWALK.md` | Tasks 10-19 Crosswalk | Aligned Task 10-19 deliverables with Target Product Contract v0.6 |
| `docs/historical/evidence/audits/ROOT_CAUSE_PHASE4_VERIFICATION.md` | `evidence/ROOT_CAUSE_PHASE4_VERIFICATION.md` | Phase 4 Verification Root Cause | Diagnosed forbidden pattern scanner registry requirements |
| `docs/historical/evidence/audits/ROOT_CAUSE_PHASE5_VERIFICATION.md` | `evidence/ROOT_CAUSE_PHASE5_VERIFICATION.md` | Phase 5 Verification Root Cause | Diagnosed settings sync caller unification |
| `docs/historical/evidence/audits/ROOT_CAUSE_STABILIZATION_GATE.md` | `evidence/ROOT_CAUSE_STABILIZATION_GATE.md` | Stabilization Gate Root Cause | Diagnosed test runner execution & timeout mechanisms |
| `docs/historical/evidence/audits/TASK_19_INDEPENDENT_FINAL_AUDIT.md` | `evidence/TASK_19_INDEPENDENT_FINAL_AUDIT.md` | Task 19 Independent Audit | Final independent verification of G1-G8 architecture |
| `docs/historical/evidence/audits/WS8_5_MUTATION_DELETION_RECOVERY_INVENTORY.md` | `evidence/WS8_5_MUTATION_DELETION_RECOVERY_INVENTORY.md` | Workstream 8.5 Inventory | Mutation deletion recovery inventory & safety check |

---

## 5. Historical Phase Reports (`phase-reports/`)

| Archived Path | Original Path | Phase / Subject | Status / Outcome |
|:---|:---|:---|:---:|
| `docs/historical/evidence/phase-reports/PHASE2_CLOSURE_REPORT.md` | `evidence/PHASE2_CLOSURE_REPORT.md` | Phase 2 Closure Report | **CLOSED (PASS)** |
| `docs/historical/evidence/phase-reports/PHASE2_ENVIRONMENT_MATRIX_RECONCILIATION.md` | `evidence/PHASE2_ENVIRONMENT_MATRIX_RECONCILIATION.md` | Phase 2 Matrix Reconciliation | **RECONCILED** |
| `docs/historical/evidence/phase-reports/PHASE2_MATRIX_REPAIR_MAPPING.md` | `evidence/PHASE2_MATRIX_REPAIR_MAPPING.md` | Phase 2 Matrix Mapping | **REPAIRED** |
| `docs/historical/evidence/phase-reports/PHASE2_MATRIX_REPAIR_RESULT.md` | `evidence/PHASE2_MATRIX_REPAIR_RESULT.md` | Phase 2 Matrix Repair Result | **PASSED** |
| `docs/historical/evidence/phase-reports/PHASE_K_MANIFEST_SCOPE_INTEGRITY.md` | `evidence/PHASE_K_MANIFEST_SCOPE_INTEGRITY.md` | Phase K Scope Integrity | **VERIFIED** |
| `docs/historical/evidence/phase-reports/phase-1-closure-memo.md` | `evidence/phase-1-closure-memo.md` | Phase 1 Closure Memo | **CLOSED (PASS)** |
| `docs/historical/evidence/phase-reports/phase_k_independent_blind_code_review.md` | `evidence/phase_compliance/phase_k_independent_blind_code_review.md` | Phase K Independent Code Review | **APPROVED** |
| `docs/historical/evidence/phase-reports/G1_CLOSURE_RECORD.md` | `app/evidence/G1_CLOSURE_RECORD.md` | G1 Closure Record | **CLOSED (PASS)** |
| `docs/historical/evidence/phase-reports/INDEPENDENT_PHASE2_TEST_AUDIT.md` | `app/evidence/INDEPENDENT_PHASE2_TEST_AUDIT.md` | Phase 2 Test Audit | **AUDITED** |
| `docs/historical/evidence/phase-reports/PHASE2_ENVIRONMENT_MATRIX_RECONCILIATION.md` | `app/evidence/PHASE2_ENVIRONMENT_MATRIX_RECONCILIATION.md` | Phase 2 Env Matrix | **RECONCILED** |
| `docs/historical/evidence/phase-reports/PHASE2_FINAL_REQUIREMENT_AUDIT.md` | `app/evidence/PHASE2_FINAL_REQUIREMENT_AUDIT.md` | Phase 2 Final Req Audit | **AUDITED** |
| `docs/historical/evidence/phase-reports/PHASE2_MATRIX_REPAIR_RESULT.md` | `app/evidence/PHASE2_MATRIX_REPAIR_RESULT.md` | Phase 2 Repair Result | **PASSED** |
| `docs/historical/evidence/phase-reports/PHASE2_TEST_EXECUTION_PROOF.md` | `app/evidence/PHASE2_TEST_EXECUTION_PROOF.md` | Phase 2 Test Exec Proof | **VERIFIED** |
