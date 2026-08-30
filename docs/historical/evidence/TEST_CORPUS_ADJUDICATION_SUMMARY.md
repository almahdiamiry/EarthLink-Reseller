# Test Corpus Adjudication & Audit Historical Summary

---

## 1. Baseline Context

* **Historical Milestone Corpus:** 563 active unit tests across 80 suites (pre-reduction milestone).
* **Release Gate Boundary:** 16 canonical release suites / 175 release-required tests executed by `scripts/production_gate.sh`.
* **Corpus Composition:**
  - `PERMANENT (RELEASE-REQUIRED)`: Direct invariant barriers guarding `INV-01` through `INV-16` and the Data Integrity Release Gate (`DataIntegrityReleaseGateTest`).
  - `PERMANENT (SUPPORTING)`: Domain-level regression, boundary, and concurrency suites serving as defense-in-depth outside the blocking gate.

---

## 2. What Was Proven

* **Corpus Inventory & Boundary Mapping:** All 80 test suites were categorized across execution tiers (`JVM`, `ROBOLECTRIC`, `INSTRUMENTED`, `STRUCTURAL`) and mapped against canonical product invariants.
* **Release-Gate Isolation:** The production gate (`production_gate.sh`) correctly operates as a standalone, deterministic invariant barrier without depending on non-release diagnostic artifacts.
* **Semantic Failure Taxonomy:** Formally established the 5-point root-cause taxonomy:
  1. `PRODUCT DEFECT`: Production code violates contract $\rightarrow$ fix production.
  2. `TEST DEFECT`: Test scenario or assertion incorrect $\rightarrow$ fix test.
  3. `FIXTURE / SETUP DEFECT`: Test precondition broken $\rightarrow$ fix fixture.
  4. `SEMANTIC-ASSUMPTION ERROR`: Mismatch on domain meaning $\rightarrow$ consult Product Contract.
  5. `ENVIRONMENT / TOOLING FAILURE`: Gradle/JVM/OS runtime issue $\rightarrow$ fix environment.

---

## 3. Important Adjudications

* **`Workstream9CDatasetReplacementTest` is NOT Redundant:**
  - It uniquely asserts that full-dataset replacement records `tombstone:account:*` metadata keys in Room to prevent delayed pull resurrection.
  - This evidence is distinct from standard import/restore suites.
* **`Phase3SameLineageFinancialMutationTest` is NOT Redundant:**
  - It uniquely asserts that standard financial operations (payments, debts, renewals) leave local lineage generation (`g4_local_generation`) invariant while server `remoteVersion` independently increments.
* **Literal Expected Constants are Valid Independent Oracles:**
  - Arithmetic oracles written as literal constants or primitive expressions ($D - A + L$) within test methods are valid independent oracles and do not constitute circular assertion copies of production logic.
* **Arbitrary Process Forking (`forkEvery=50`) Rejected:**
  - Arbitrary Gradle test process recycling was rejected as an unproven workaround. Flakiness was resolved through deterministic synchronization gates (`CompletableDeferred`) rather than process recycling.

---

## 4. Final Testing-Governance Conclusions

* **Evidence-Preserving Reduction Principle:** Test reduction must proceed only upon method-by-method proof of identical Claim + Scenario + Seam + Independent Oracle in a designated replacement suite.
* **Release Gate as Canonical Boundary:** `scripts/production_gate.sh` remains the sole blocking authority for releases. Supporting suites provide layered regression safety.
* **Diagnostic Reports Are Not Permanent Assets:** Ephemeral execution logs, command outputs, and intermediate planning YAMLs must not accumulate in the repository root.

---

## 5. Batch-1 Historical Repairs

Batch 1 resolved three pre-existing environment and fixture anomalies with targeted verification (3/3 passed):

1. **Phase 5 Structural Gate Alignment (`Phase5DestructiveActionReleaseGateTest`):**
   - Corrected relative path resolution so the structural release gate locates `SettingsScreen.kt` reliably across execution roots.
2. **UTC Fixture Correction (`Workstream13G1RealRestartCertificationTest`):**
   - Corrected date formatting fixtures to use explicit UTC epoch time, eliminating timezone-dependent parsing discrepancies.
3. **Real File-Backed SQLite Isolation (`Workstream13G1RealRestartCertificationTest`):**
   - Corrected database name collision handling and ensured distinct SQLite file paths during restart simulation tests.

---

## 6. Superseded Documents & Archival Trace

This consolidated record supersedes and replaces the following historical working documents:
* `TEST_CORPUS_INDEX_AND_COVERAGE_AUDIT.md` / `.yaml`
* `TEST_CORPUS_FINAL_ADJUDICATION.md` / `.yaml`
* `FINAL_OPEN_QUESTIONS_RESOLUTION.md` / `.yaml`
* `BATCH1_IMPLEMENTATION_VERIFICATION.md` / `.yaml`
