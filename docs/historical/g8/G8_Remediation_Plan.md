# G8 Release-Verification Infrastructure Remediation & Test Matrix Synchronization Implementation Plan
## Final Audit-Grade Contract-First & Verifier-First Engineering Roadmap

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` (or `superpowers:subagent-driven-development`) to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Transform the G8 certification engine and test matrix contract into a truthful, machine-verifiable release verification system by resolving the **5 core root defects** (synthetic PASS receipts, JUnit XML file-count proxy, verifier dependence on producer booleans, incomplete artifact/evidence binding, and unregistered test matrix drift) with the **minimum necessary engineering delta**, avoiding over-engineering, generic frameworks, or unmandated scope creep, and maintaining strict zero-modification boundaries on production application code and runtime behavior.

---

## The Rule of Admissibility (Evidence Before Expansion)

> **MANDATORY GOVERNANCE RULE:**
> No new obligation, corpus, module, identity field, environment requirement, or execution stage may be introduced unless it is directly traceable to:
> 1. A canonical frozen contract clause (`contract/g8_certification_contract.yaml`, `contract/phase_requirements.yaml`),
> 2. A specific `G8-ADV-XXX` check (`contract/g8_adversarial_checks.yaml`),
> 3. A demonstrated current-repository defect, or
> 4. A failing automated exit-gate test.
>
> Any proposal or recommendation lacking one of these four evidence bases is **OUT OF SCOPE / NON-BLOCKING**.

---

## Implementation-Readiness Closure Rule

> This plan is considered **implementation-ready** only when every execution path, verifier predicate, applicability decision, proof-map selector, CLI/API reference, and exit-gate assertion is directly executable against the current repository interfaces or explicitly blocked by a canonical authority gap. No placeholder command, guessed API, metadata-only selector, vacuous test, or duplicated state predicate is admissible.

---

## Execution Environment Portability

> ### IMPORTANT — EXECUTION ENVIRONMENT BOUNDARY
>
> The G8 implementation plan is an **environment-portable implementation and certification roadmap**.
> It must be executable in any approved environment that satisfies the repository's actual toolchain requirements (for example Windows, Linux, or another supported CI/host environment).
>
> Therefore:
> 1. **The plan is OS/platform agnostic.**
> 2. The actual execution environment is selected at execution time according to the available, repository-authoritative toolchain and certification requirements.
> 3. Platform-specific commands MUST be resolved dynamically for the actual environment.
> 4. G8 semantics, evidence validity, PASS/FAIL rules, artifact binding, and verifier behavior MUST NOT depend on Windows vs Linux.
> 5. Do not hard-code:
>    - Windows-only paths
>    - Linux-only paths
>    - PowerShell-only syntax
>    - Bash-only syntax
>    - Platform-specific Gradle assumptions
> 6. Use the repository's authoritative Gradle wrapper and verified runner (`scripts/run_verified_command.py`) in whatever environment is actually being used.
> 7. When an operation is platform-dependent, the implementation must detect and use the correct invocation for the current environment rather than embedding one OS.
> 8. Environment identity SHOULD be recorded as certification evidence, but OS identity alone MUST NOT determine certification validity.
> 9. A certification run is valid when executed in an approved environment satisfying all required prerequisites, regardless of whether that environment is Windows, Linux, CI, or another supported host.
> 10. If the current environment cannot satisfy a required prerequisite, fail closed with an explicit `ENVIRONMENT-BLOCKED` reason. Do not substitute results from another environment merely because they are available.

### Platform Execution Rule

The plan expresses commands in platform-neutral logical terms first:
```bash
<python> scripts/run_verified_command.py <gradle-wrapper> <task>
```

Then resolves dynamically at execution time:
- `<python>` -> `python` / `python3` as available in the host environment.
- `<gradle-wrapper>` -> `./gradlew` (POSIX / Linux / macOS) or `.\gradlew.bat` (Windows).

Examples:
```bash
# Logical invocation:
<python> scripts/run_verified_command.py <gradle-wrapper> testDebugUnitTest
<python> scripts/run_verified_command.py <gradle-wrapper> assembleRelease

# Host resolution:
# Linux/POSIX:   python3 scripts/run_verified_command.py ./gradlew testDebugUnitTest
# Windows:       python scripts/run_verified_command.py .\gradlew.bat testDebugUnitTest
```

The exact instrumentation task, if contract-applicable, MUST be discovered from the repository's current Android/Gradle configuration and executed through the same verified runner. It MUST NOT be guessed or copied from an unverified historical environment.

### Environment Boundary Rule

```text
CURRENT AUTHORING / INSPECTION ENVIRONMENT
    = may be different from the final execution environment

APPROVED EXECUTION ENVIRONMENT
    = the environment actually used for authoritative implementation verification
      and certification, provided it satisfies all repository and contract prerequisites
```

The final certification boundary MUST record:
- OS/platform identity
- JDK identity
- Android SDK/build-tools identity
- Gradle wrapper identity
- Relevant runner/tool versions

These values are evidence about the execution boundary, not a hard-coded requirement that the project must run on one specific operating system.

---

## Infrastructure Reuse Policy (Audit First, Build Minimum)

Before creating any new verification module or script:
1. **Inspect** existing scripts ([`scripts/build_g8_source_manifest.py`](file:///C:/Users/Almahdi-BOC/.gemini/antigravity/worktrees/Earthlink-Reseller-V1/v1_scope_assessment_gate/scripts/build_g8_source_manifest.py), [`scripts/build_g8_test_corpus_manifest.py`](file:///C:/Users/Almahdi-BOC/.gemini/antigravity/worktrees/Earthlink-Reseller-V1/v1_scope_assessment_gate/scripts/build_g8_test_corpus_manifest.py), [`scripts/verify_test_environment_matrix.py`](file:///C:/Users/Almahdi-BOC/.gemini/antigravity/worktrees/Earthlink-Reseller-V1/v1_scope_assessment_gate/scripts/verify_test_environment_matrix.py), [`scripts/g8_verify_certification_bundle.py`](file:///C:/Users/Almahdi-BOC/.gemini/antigravity/worktrees/Earthlink-Reseller-V1/v1_scope_assessment_gate/scripts/g8_verify_certification_bundle.py)).
2. **Reuse** existing code if present and semantically sufficient.
3. **Minimally extend** existing code if present but insufficient.
4. **Build** the smallest new helper only if a demonstrated capability gap exists and no existing script can host it.
5. **Preserve** existing CLI/API signatures unless a demonstrated capability gap requires a deliberate, documented change.

---

## Strict Prohibitions & Scope Invariants

The following practices are **STRICTLY PROHIBITED**:
- **DO NOT** modify `app/src/main/`, production architecture, database schema, build logic, or application runtime behavior.
- **DO NOT** reopen G1–G7 functional implementation or invent new product features.
- **DO NOT** build generic testing frameworks, abstract policy engines, or complex reconciliation architectures.
- **DO NOT** promote on-disk files in `app/src/androidTest/` to mandatory V1 release obligations without contract authorization.
- **DO NOT** write 79 new test suites; map each check to *existing* authoritative proof targets, scripts, or minimal fixtures.
- **DO NOT** use historical PASS records (commit `ba1761f`) as current-HEAD execution proof.
- **DO NOT** derive certification state from producer-declared boolean flags (`bundle["verified"] == True`).
- **DO NOT** treat XML file presence or file count thresholds as proof of test success.
- **DO NOT** invent release signing credentials or certificate authorities inside G8 tooling; consume existing owner authority.
- **DO NOT** mutate the real repository working tree to test TOCTOU invariance; use isolated temporary directories.

---

### Task 0: Authority, Contract Semantics & Release Signing Provenance Audit

**Files:**
- Modify: `contract/g8_certification_contract.yaml`
- Test: `tests/g8/test_contract_semantics.py`

**Interfaces:**
- Consumes: Owner authority specifications, frozen architecture memos, release signing policy.
- Produces: Formally reconciled `contract/g8_certification_contract.yaml` binding external signing authority provenance and establishing exact mathematical predicates for derived states.

- [ ] **Step 1: Write test verifying that `contract/g8_certification_contract.yaml` specifies authoritative signing provenance and exact state predicates**

```python
# tests/g8/test_contract_semantics.py
import os
import yaml
import pytest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
CONTRACT_PATH = os.path.join(REPO_ROOT, "contract", "g8_certification_contract.yaml")

def test_contract_specifies_authoritative_signing_and_state_predicates():
    with open(CONTRACT_PATH, "r", encoding="utf-8") as f:
        contract = yaml.safe_load(f)
    
    assert "signing_authority_provenance" in contract, "Contract must declare external signing authority source"
    
    states = {s["id"]: s for s in contract.get("derived_states", [])}
    for state_id in ["ARCHITECTURE_COMPLETE", "IMPLEMENTATION_COMPLETE", "VERIFIED", "PRODUCTION_READY"]:
        assert state_id in states, f"Missing state definition for {state_id}"
        assert "formal_predicate" in states[state_id], f"Missing formal predicate for {state_id}"
```

- [ ] **Step 2: Run test to verify it fails on current contract**

Run: `python -m pytest tests/g8/test_contract_semantics.py -v`
Expected: FAIL with `AssertionError: Contract must declare external signing authority source`.

- [ ] **Step 3: Audit existing owner signing authority and reconcile contract**

- Verify if an authoritative release certificate fingerprint is defined in owner authority. If absent, halt and report signing task blockage without inventing a fingerprint.
- Update `contract/g8_certification_contract.yaml` with explicit signing provenance, discrete corpora definitions, and formal mathematical predicates matching `Target Product Contract v0.6` exactly.

- [ ] **Step 4: Run test to verify it passes**

Run: `python -m pytest tests/g8/test_contract_semantics.py -v`
Expected: PASS.

---

### Task 1: Applicability Verification (Canonical Corpora & Instrumentation Scope)

**Files:**
- Modify: `contract/g8_certification_contract.yaml`
- Test: `tests/g8/test_applicability_gate.py`

**Interfaces:**
- Consumes: `contract/g8_certification_contract.yaml`, `contract/test_environment_matrix.yaml`.
- Produces: Falsifiable classification of corpora, exact resolution of whether `INSTRUMENTED` tests gate V1 release (`MANDATORY`, `SUPPORTING`, `NOT_APPLICABLE`, or `AUTHORITY_GAP`), and deterministic recording of `execution_task` string or `null` under `corpora_applicability.INSTRUMENTED_CORPUS`.

- [ ] **Step 1: Write falsifiable test resolving canonical applicability and execution task binding**

```python
# tests/g8/test_applicability_gate.py
import os
import yaml
import pytest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
CONTRACT_PATH = os.path.join(REPO_ROOT, "contract", "g8_certification_contract.yaml")

ALLOWED_APPLICABILITY_STATES = {"MANDATORY", "SUPPORTING", "NOT_APPLICABLE", "AUTHORITY_GAP"}

def test_canonical_applicability_classification_and_consistency():
    with open(CONTRACT_PATH, "r", encoding="utf-8") as f:
        contract = yaml.safe_load(f)
    
    # 1. Must declare structured corpora_applicability dictionary
    applicability = contract.get("corpora_applicability", {})
    assert applicability, "Contract must define structured 'corpora_applicability' mapping"
    
    for corpus_name in ["PRODUCT_TEST_CORPUS", "INSTRUMENTED_CORPUS", "STRUCTURAL_CORPUS", "HISTORICAL_CORPUS"]:
        assert corpus_name in applicability, f"Missing applicability definition for {corpus_name}"
        state = applicability[corpus_name].get("release_gate")
        assert state in ALLOWED_APPLICABILITY_STATES, f"Invalid state '{state}' for {corpus_name}"
        
    # 2. Consistency check with requirement obligations and deterministic execution_task field
    reqs = {r["id"]: r for r in contract.get("requirements", [])}
    if "P6-G8-REQ-03" in reqs:
        p6_req = reqs["P6-G8-REQ-03"]
        inst_corpus = applicability["INSTRUMENTED_CORPUS"]
        if p6_req.get("device_execution_required") is True:
            assert inst_corpus["release_gate"] == "MANDATORY"
            assert isinstance(inst_corpus.get("execution_task"), str) and len(inst_corpus["execution_task"]) > 0, \
                "execution_task must be a non-empty string when release_gate is MANDATORY"
        else:
            assert inst_corpus["release_gate"] in {"SUPPORTING", "NOT_APPLICABLE"}
            assert inst_corpus.get("execution_task") is None, \
                "execution_task must be explicitly null when release_gate is not MANDATORY"
```

- [ ] **Step 2: Resolve instrumentation execution task and record in contract**

- Inspect available verification Gradle tasks in repository configuration (e.g. `connectedDebugAndroidTest`, `connectedCheck`).
- Update `contract/g8_certification_contract.yaml` under `corpora_applicability.INSTRUMENTED_CORPUS`:
  - If `release_gate == "MANDATORY"`: set `execution_task: "connectedDebugAndroidTest"` (or discovered exact task name).
  - If `release_gate != "MANDATORY"`: set `execution_task: null` explicitly.

- [ ] **Step 3: Run test to verify applicability classification and execution task binding**

Run: `python -m pytest tests/g8/test_applicability_gate.py -v`
Expected: PASS with explicit, structured determination of required corpora and exact task binding.

---

### Task 2: Test Matrix Semantic Reconciliation (Zero Unclassified)

**Files:**
- Modify: `contract/test_environment_matrix.yaml`
- Test: `tests/g8/test_matrix_validator.py`
- Verify: `scripts/verify_test_environment_matrix.py`

**Interfaces:**
- Consumes: All 61 Kotlin test files in `app/src/test/java/com/example/`.
- Produces: Reconciled `contract/test_environment_matrix.yaml` where every on-disk test file is semantically classified (mandatory certification, supporting, structural, or historical).

- [ ] **Step 1: Write test for test matrix validator enforcing `verify_matrix() == True`**

```python
# tests/g8/test_matrix_validator.py
import pytest
from scripts.verify_test_environment_matrix import verify_matrix

def test_matrix_validator_passes():
    # Uses existing verify_matrix() API returning bool
    assert verify_matrix() is True, "verify_matrix() must return True on clean repository state"
```

- [ ] **Step 2: Run test to verify it fails on current repository state**

Run: `python -m pytest tests/g8/test_matrix_validator.py -v`
Expected: FAIL with `[FAIL] MATRIX VALIDATION FAILED with 23 error(s)`.

- [ ] **Step 3: Semantically classify all 23 unmapped test files in `contract/test_environment_matrix.yaml`**

Inspect actual assertions in the 23 test files against `contract/invariant_contract.yaml`:
- Classify required production suites under their true invariant (`INV-01`..`INV-16`).
- Explicitly mark supporting, historical, or non-certification tests under appropriate categories.
- Update `contract/test_environment_matrix.yaml`.

- [ ] **Step 4: Run test to verify it passes**

Run: `python -m pytest tests/g8/test_matrix_validator.py -v`
Expected: PASS (0 unclassified test files).

---

### Task 3: Exact 79-Check Proof Execution Contract & Executable Target Resolution

**Files:**
- Create: `contract/g8_proof_execution_map.yaml`
- Test: `tests/g8/test_proof_map_integrity.py`

**Interfaces:**
- Consumes: All 79 checks from `contract/g8_adversarial_checks.yaml`.
- Produces: `contract/g8_proof_execution_map.yaml` mapping all 79 checks individually to verified executable targets with unique `assertion_id`s, unique `evidence_artifact_id`s, and resolvable execution selectors.

- [ ] **Step 1: Write test verifying executable selector resolution and unique assertion identity across all 79 checks**

```python
# tests/g8/test_proof_map_integrity.py
import os
import yaml
import pytest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
CHECKS_PATH = os.path.join(REPO_ROOT, "contract", "g8_adversarial_checks.yaml")
MAP_PATH = os.path.join(REPO_ROOT, "contract", "g8_proof_execution_map.yaml")

def test_all_79_checks_resolve_to_valid_executable_targets():
    assert os.path.exists(MAP_PATH), "g8_proof_execution_map.yaml must exist in contract/"
    with open(CHECKS_PATH, "r", encoding="utf-8") as f:
        checks_data = yaml.safe_load(f)
    with open(MAP_PATH, "r", encoding="utf-8") as f:
        map_data = yaml.safe_load(f)
    
    declared_checks = {c["id"]: c for c in checks_data["checks"]}
    mappings = map_data.get("mappings", {})
    
    assert len(declared_checks) == 79
    assert set(declared_checks.keys()) == set(mappings.keys())
    
    assertion_ids = set()
    evidence_ids = set()
    
    script_selectors = set()
    for check_id, mapping in mappings.items():
        check_def = declared_checks[check_id]
        assert mapping["proof_mode"] == check_def["proof_mode"]
        assert mapping["expected_outcome"] == check_def["expected_outcome"]
        
        # 1. Uniqueness of proof & evidence identity
        assert mapping["assertion_id"] not in assertion_ids, f"Duplicate assertion_id: {mapping['assertion_id']}"
        assert mapping["evidence_artifact_id"] not in evidence_ids, f"Duplicate evidence_artifact_id: {mapping['evidence_artifact_id']}"
        assertion_ids.add(mapping["assertion_id"])
        evidence_ids.add(mapping["evidence_artifact_id"])
        
        # 2. Executable Target Resolution & Distinct Invocation Semantics
        executor_type = mapping["executor_type"]
        selector = mapping.get("execution_selector")
        assert selector, f"Missing execution_selector for {check_id}"
        
        if executor_type == "JUNIT_TEST":
            target_file = mapping.get("target_file", "")
            target_class = mapping.get("target_class", "")
            target_method = mapping.get("target_method", "")
            
            full_target_path = os.path.join(REPO_ROOT, target_file)
            assert os.path.exists(full_target_path), f"Target file {target_file} missing for {check_id}"
            assert target_class and target_method, f"Target class/method missing for {check_id}"
            
            # Robust semantic verification of class and method inside Kotlin source file
            with open(full_target_path, "r", encoding="utf-8") as f:
                src = f.read()
            import re
            simple_class_name = target_class.split(".")[-1]
            assert re.search(rf"\bclass\s+{re.escape(simple_class_name)}\b", src), f"Class {simple_class_name} not declared in {target_file}"
            assert re.search(rf"\bfun\s+{re.escape(target_method)}\b", src), f"Method {target_method} not declared in {target_file}"
            
            # Selector must form valid Gradle test filter syntax targeting class.method
            assert selector == f"--tests {target_class}.{target_method}" or selector == f"--tests \"{target_class}.{target_method}\""
            
        elif executor_type == "STRUCTURAL_SCRIPT":
            script_path = os.path.join(REPO_ROOT, mapping.get("executor_ref", ""))
            assert os.path.exists(script_path), f"Script {script_path} missing for {check_id}"
            # Script selector must have distinct invocation identity (e.g. distinct argument/rule flag)
            assert selector not in script_selectors, f"Duplicate structural selector '{selector}' across checks"
            script_selectors.add(selector)
            assert mapping.get("assertion_id"), f"Missing assertion_id for structural check {check_id}"
            
        elif executor_type in {"MUTATION_PROBE", "ARTIFACT_PROBE"}:
            assert "expected_observation" in mapping, f"Missing expected_observation for probe {check_id}"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python -m pytest tests/g8/test_proof_map_integrity.py -v`
Expected: FAIL with `AssertionError: g8_proof_execution_map.yaml must exist in contract/`.

- [ ] **Step 3: Extract and generate `contract/g8_proof_execution_map.yaml` individually for all 79 checks**

Map each of the 79 checks to existing targets without creating 79 new test suites:
- Static contract & invariant checks $\rightarrow$ `scripts/verify_invariant_contract.py`, `scripts/scan_forbidden_patterns.py`, `scripts/verify_test_environment_matrix.py`.
- Hardware claims, durability, and outbox checks $\rightarrow$ existing methods in `app/src/test/java/com/example/Step3DurableDispatchTest.kt`, `Phase1OutboxDurabilityTest.kt`, `Phase1FirestoreDocumentIdentityTest.kt` with explicit Gradle selectors (e.g. `--tests "com.example.Step3DurableDispatchTest.testCrashRestartBoundary"`).
- Restore safety & lineage checks $\rightarrow$ existing methods in `Phase2RestoreReplaceHardeningTest.kt`, `Phase2RestoreMergeLineageTest.kt`, `Phase3PersistedGenerationTest.kt`.
- Release gate checks $\rightarrow$ existing methods in `Phase5DestructiveActionReleaseGateTest.kt`, `Phase4RuntimeLedgerIdentityTest.kt`.

- [ ] **Step 4: Run test to verify it passes**

Run: `python -m pytest tests/g8/test_proof_map_integrity.py -v`
Expected: PASS (79/79 individual bindings validated with unique assertion identities and resolvable selectors).

---

### Task 4: Freeze Independent Verifier Acceptance Model

**Files:**
- Modify: `scripts/g8_verify_certification_bundle.py`
- Test: `tests/g8/test_verifier_model.py`

**Interfaces:**
- Consumes: Raw execution receipts, contract manifests, release artifact metadata.
- Produces: Independent verification evaluation without trusting producer-declared booleans.

- [ ] **Step 1: Inspect actual `verify_bundle` interface in `scripts/g8_verify_certification_bundle.py` and write test asserting verifier evaluates canonical contract authority**

```python
# tests/g8/test_verifier_model.py
import pytest
from scripts.g8_verify_certification_bundle import verify_bundle

def test_verifier_rejects_empty_or_fabricated_receipts(tmp_path):
    fake_bundle = tmp_path / "fake_bundle.json"
    fake_bundle.write_text('{"derived_states": {"VERIFIED": "PASS"}, "evidence_receipts": []}', encoding="utf-8")
    # Uses actual verify_bundle(filepath) signature
    res = verify_bundle(str(fake_bundle))
    assert res.get("status") == "FAIL"
```

- [ ] **Step 2: Run test to verify it fails on current verifier**

Run: `python -m pytest tests/g8/test_verifier_model.py -v`
Expected: FAIL.

- [ ] **Step 3: Update `scripts/g8_verify_certification_bundle.py` to evaluate proofs from canonical contract authority**

Update verifier to load `contract/g8_adversarial_checks.yaml` and `contract/g8_proof_execution_map.yaml` directly, ignoring producer-supplied `proof_mode` or `expected_outcome` copies.

- [ ] **Step 4: Run test to verify it passes**

Run: `python -m pytest tests/g8/test_verifier_model.py -v`
Expected: PASS.

---

### Task 5: Structured JUnit XML Result Parser

**Files:**
- Create: `scripts/g8_junit_parser.py`
- Test: `tests/g8/test_junit_parser.py`
- Fixtures: `tests/g8/fixtures/junit/*.xml`

**Interfaces:**
- Consumes: Target directory containing `TEST-*.xml` unit test reports.
- Produces: `parse_junit_results(results_dir: str, required_suites: list[str]) -> dict`
  returning structured metrics and fail-closed status.

- [ ] **Step 1: Create XML test fixtures for all JUnit outcome cases**

Create XML fixtures in `tests/g8/fixtures/junit/`:
- `clean_pass/TEST-SuiteA.xml`: `<testsuite tests="5" failures="0" errors="0" skipped="0">...</testsuite>`
- `with_failure/TEST-SuiteB.xml`: `<testsuite tests="5" failures="1" errors="0" skipped="0"><testcase name="t1"><failure message="fail"/></testcase></testsuite>`
- `with_error/TEST-SuiteC.xml`: `<testsuite tests="5" failures="0" errors="1" skipped="0"><testcase name="t2"><error message="err"/></testcase></testsuite>`
- `with_skipped/TEST-SuiteD.xml`: `<testsuite tests="5" failures="0" errors="0" skipped="1"><testcase name="t3"><skipped/></testcase></testsuite>`
- `malformed/TEST-Malformed.xml`: `<<<bad xml>>`

- [ ] **Step 2: Write failing unit tests for `g8_junit_parser.py`**

```python
# tests/g8/test_junit_parser.py
import os
import pytest
from scripts.g8_junit_parser import parse_junit_results

FIXTURES_DIR = os.path.join(os.path.dirname(__file__), "fixtures", "junit")

def test_parse_clean_pass():
    res = parse_junit_results(os.path.join(FIXTURES_DIR, "clean_pass"))
    assert res["status"] == "PASS"
    assert res["failures"] == 0 and res["errors"] == 0 and res["skipped"] == 0
    assert res["total_tests"] == 5

def test_parse_failure_fails_closed():
    res = parse_junit_results(os.path.join(FIXTURES_DIR, "with_failure"))
    assert res["status"] == "FAIL"
    assert res["failures"] == 1

def test_parse_skipped_fails_closed():
    res = parse_junit_results(os.path.join(FIXTURES_DIR, "with_skipped"))
    assert res["status"] == "FAIL"
    assert res["skipped"] == 1

def test_parse_malformed_fails_closed():
    res = parse_junit_results(os.path.join(FIXTURES_DIR, "malformed"))
    assert res["status"] == "MALFORMED_RESULT"
```

- [ ] **Step 3: Implement `scripts/g8_junit_parser.py`**

Implement DOM XML parser aggregating metrics, validating well-formedness, checking against `required_suites`, and enforcing enum statuses (`PASS`, `FAIL`, `SKIPPED_DETECTED`, `NO_REPORTS`, `MALFORMED_RESULT`, `MISSING_EXPECTED_SUITE`).

- [ ] **Step 4: Run unit tests to verify parser passes**

Run: `python -m pytest tests/g8/test_junit_parser.py -v`
Expected: PASS across all fixture scenarios.

---

### Task 6: Multi-Manifest Certification Boundary & Isolated TOCTOU Invariance

**Files:**
- Modify: `scripts/build_g8_source_manifest.py`, `scripts/build_g8_test_corpus_manifest.py`
- Test: `tests/g8/test_manifest_boundary.py`

**Interfaces:**
- Consumes: Existing manifest builders in `scripts/`.
- Produces: Extended manifest outputs covering `PRODUCT_ARTIFACT_ID`, `PRODUCT_TEST_CORPUS_ID`, `CERTIFICATION_ARTIFACT_ID`, `G8_PROOF_MAP_ID`, and the deterministic `CERTIFICATION_BOUNDARY_ID` binding without circular self-hashing, plus isolated TOCTOU validation.

> **Mathematical Definition of Certification Boundary Identity:**
> The certification boundary identity is a deterministic SHA-256 binding over all release-relevant canonical inputs:
> ```text
> CERTIFICATION_BOUNDARY_ID = SHA-256(
>     product_artifact_id +
>     product_test_corpus_id +
>     certification_artifact_id +
>     g8_proof_map_id +
>     toolchain_environment_id
> )
> ```
> The release APK binary SHA-256 is an independently computed cryptographic artifact bound into the release certification record under this boundary ID; binary hashes are never compared directly across unlike objects.

- [ ] **Step 1: Inspect existing manifest signatures and write test for deterministic manifest hashing and isolated TOCTOU mutation detection**

```python
# tests/g8/test_manifest_boundary.py
import os
import shutil
import pytest
from scripts.build_g8_source_manifest import build_manifests

def test_build_manifests_deterministic():
    # Tests existing build_manifests() entry point
    m1 = build_manifests()
    m2 = build_manifests()
    assert m1["product_artifact_id"] == m2["product_artifact_id"]
    assert m1["certification_artifact_id"] == m2["certification_artifact_id"]
    assert len(m1["product_artifact_id"]) == 64

def test_toctou_mutation_detected_in_isolated_fixture(tmp_path):
    # Setup isolated mock repository fixture to verify boundary change detection without mutating real repo
    mock_repo = tmp_path / "mock_repo"
    mock_src = mock_repo / "app" / "src" / "main"
    mock_src.mkdir(parents=True)
    target_file = mock_src / "Test.kt"
    target_file.write_text("initial content", encoding="utf-8")
    
    # 1. Baseline verification
    # Note: build_manifests will be minimally extended to accept optional repo_root
    m_initial = build_manifests(repo_root=str(mock_repo))
    
    # 2. Mutate file -> Must yield different hash
    target_file.write_text("mutated content", encoding="utf-8")
    m_mutated = build_manifests(repo_root=str(mock_repo))
    assert m_mutated["product_artifact_id"] != m_initial["product_artifact_id"], "TOCTOU mutation was not detected"
    
    # 3. Restore file -> Must match baseline again
    target_file.write_text("initial content", encoding="utf-8")
    m_restored = build_manifests(repo_root=str(mock_repo))
    assert m_restored["product_artifact_id"] == m_initial["product_artifact_id"], "Restored fixture failed to match baseline"
```

- [ ] **Step 2: Run test to verify it fails on existing parameter signature**

Run: `python -m pytest tests/g8/test_manifest_boundary.py -v`
Expected: FAIL with `TypeError: build_manifests() got an unexpected keyword argument 'repo_root'`.

- [ ] **Step 3: Minimally extend `scripts/build_g8_source_manifest.py` and `scripts/build_g8_test_corpus_manifest.py`**

- Add optional `repo_root` parameter (defaulting to current `REPO_ROOT`).
- Bind `contract/g8_proof_execution_map.yaml` into certification manifest.
- Compute deterministic `CERTIFICATION_BOUNDARY_ID`.

- [ ] **Step 4: Run test to verify it passes**

Run: `python -m pytest tests/g8/test_manifest_boundary.py -v`
Expected: PASS.

---

### Task 7: Causal Producer Implementation (Process-Boundary `run_verified_command`)

**Files:**
- Modify: `scripts/g8_certify.py`
- Test: `tests/g8/test_proof_dispatch.py`

**Interfaces:**
- Consumes: `contract/g8_proof_execution_map.yaml`, `scripts/g8_junit_parser.py`, manifest builders.
- Produces: Execution evidence receipts in `build/g8_certification/evidence/` via `scripts/run_verified_command.py` process supervisor.

- [ ] **Step 1: Write unit and integration tests for causal proof dispatching**

```python
# tests/g8/test_proof_dispatch.py
import pytest
from scripts.g8_certify import dispatch_adversarial_check, evaluate_check_outcome

def test_outcome_evaluation_semantics():
    assert evaluate_check_outcome(
        expected_outcome="FAIL_OR_BLOCKING_STATE",
        probe_exit_code=2,
        probe_output="BLOCKED"
    ) == "PASS"
    
    assert evaluate_check_outcome(
        expected_outcome="FAIL_OR_BLOCKING_STATE",
        probe_exit_code=0,
        probe_output="ALLOWED"
    ) == "FAIL"
```

- [ ] **Step 2: Run test to verify it fails on current synthetic implementation**

Run: `python -m pytest tests/g8/test_proof_dispatch.py -v`
Expected: FAIL with `NameError: name 'evaluate_check_outcome' is not defined`.

- [ ] **Step 3: Refactor `scripts/g8_certify.py` to route all executions through `scripts/run_verified_command.py` process boundary**

- Load `contract/g8_proof_execution_map.yaml`.
- Replace synthetic JSON receipt creation with genuine execution using `python scripts/run_verified_command.py`.
- Capture raw runner JSON output, evaluate outcome semantics, and generate normalized evidence receipts.
- Integrate `parse_junit_results` for unit test reporting.

- [ ] **Step 4: Run test to verify it passes**

Run: `python -m pytest tests/g8/test_proof_dispatch.py -v`
Expected: PASS.

---

### Task 8: Contract-Loaded Independent State Derivation

**Files:**
- Modify: `scripts/g8_verify_certification_bundle.py`
- Test: `tests/g8/test_state_derivation.py`

**Interfaces:**
- Consumes: `contract/g8_certification_contract.yaml`, audited evidence bundle.
- Produces: Independently derived certification states evaluated directly from contract predicates without re-authoring or introducing a general-purpose expression engine.

> **State Predicate Policy:**
> No general-purpose predicate language or policy engine may be introduced. The verifier must consume the canonical contract predicate representation directly. A new predicate representation may be introduced only if the existing contract is not machine-evaluable and the failing gate demonstrates the capability gap. The smallest fixed declarative representation shall be used.

- [ ] **Step 1: Write test verifying state derivations are evaluated directly from canonical contract predicates**

```python
# tests/g8/test_state_derivation.py
import os
import yaml
import pytest
from scripts.g8_verify_certification_bundle import evaluate_contract_predicates

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
CONTRACT_PATH = os.path.join(REPO_ROOT, "contract", "g8_certification_contract.yaml")

def test_state_derivation_from_canonical_contract_predicates():
    with open(CONTRACT_PATH, "r", encoding="utf-8") as f:
        contract = yaml.safe_load(f)
    
    # 1. Dynamically verify that all 4 states define machine-evaluable formal predicates
    state_defs = {s["id"]: s for s in contract.get("derived_states", [])}
    for state_id in ["ARCHITECTURE_COMPLETE", "IMPLEMENTATION_COMPLETE", "VERIFIED", "PRODUCTION_READY"]:
        assert state_id in state_defs, f"Missing {state_id} definition in contract"
        assert "formal_predicate" in state_defs[state_id], f"Missing formal_predicate for {state_id}"
    
    # 2. Evaluate against controlled passing evidence fixture
    baseline_evidence = {
        "invariant_contracts_passed": True,
        "forbidden_patterns_passed": True,
        "production_files_present": True,
        "g8_suites_present": True,
        "junit_failures_count": 0,
        "junit_errors_count": 0,
        "junit_skipped_count": 0,
        "adversarial_checks_failed": 0,
        "release_apk_signed_verified": True
    }
    
    states = evaluate_contract_predicates(contract, baseline_evidence)
    for state_id in ["ARCHITECTURE_COMPLETE", "IMPLEMENTATION_COMPLETE", "VERIFIED", "PRODUCTION_READY"]:
        assert states.get(state_id) == "PASS", f"Expected {state_id} to PASS on baseline evidence"
        
    # 3. Tamper one required predicate input -> State must fail closed
    tampered_evidence = dict(baseline_evidence, release_apk_signed_verified=False)
    tampered_states = evaluate_contract_predicates(contract, tampered_evidence)
    assert tampered_states.get("PRODUCTION_READY") == "FAIL", "PRODUCTION_READY must FAIL if release signature unverified"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python -m pytest tests/g8/test_state_derivation.py -v`
Expected: FAIL with `NameError: name 'evaluate_contract_predicates' is not defined`.

- [ ] **Step 3: Implement declarative contract-loaded state derivation logic in verifier**

Update `scripts/g8_verify_certification_bundle.py`:
- The verifier SHALL load the canonical `formal_predicate` for every derived state directly from `contract/g8_certification_contract.yaml` and evaluate it against independently verified evidence.
- State predicates MUST NOT be duplicated, approximated, or re-authored in implementation code, and no generic expression engine shall be introduced.

- [ ] **Step 4: Run test to verify it passes**

Run: `python -m pytest tests/g8/test_state_derivation.py -v`
Expected: PASS.

---

### Task 9: Tamper, Negative, Anti-Forgery & 79-Check Lifecycle Coverage Regression Suite

**Files:**
- Create: `tests/g8/test_tamper_regression.py`, `tests/g8/test_execution_coverage.py`

**Interfaces:**
- Consumes: Test modules in `tests/g8/`.
- Produces: 100% test pass when invoked via `python -m pytest tests/g8/ -v`.

- [ ] **Step 1: Write tamper mutation tests verifying verifier rejects all forged or altered bundles and historical receipts**

```python
# tests/g8/test_tamper_regression.py
import pytest
from scripts.g8_verify_certification_bundle import verify_bundle

def test_tamper_with_adv_check_outcome_rejected(tmp_path):
    tampered_bundle = tmp_path / "tampered.json"
    tampered_bundle.write_text('{"evidence_receipts": [{"check_id": "G8-ADV-001", "observed_exit_code": 1}]}', encoding="utf-8")
    res = verify_bundle(str(tampered_bundle))
    assert res.get("status") == "FAIL"

def test_historical_receipt_rejected_as_current_evidence(tmp_path):
    historical_bundle = tmp_path / "historical.json"
    historical_bundle.write_text('{"source_commit_sha": "ba1761ffa8b0cb62fb744e03aef429175831af7a", "evidence_receipts": []}', encoding="utf-8")
    res = verify_bundle(str(historical_bundle))
    assert res.get("status") == "FAIL"
```

- [ ] **Step 2: Write 79-Check lifecycle coverage test verifying complete cardinality closure**

```python
# tests/g8/test_execution_coverage.py
import os
import yaml
import pytest
from scripts.g8_verify_certification_bundle import verify_79_lifecycle_coverage

def test_79_checks_lifecycle_complete_coverage():
    REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
    with open(os.path.join(REPO_ROOT, "contract", "g8_adversarial_checks.yaml"), "r", encoding="utf-8") as f:
        declared_checks = {c["id"] for c in yaml.safe_load(f)["checks"]}
    with open(os.path.join(REPO_ROOT, "contract", "g8_proof_execution_map.yaml"), "r", encoding="utf-8") as f:
        mapped_checks = set(yaml.safe_load(f)["mappings"].keys())
    
    assert len(declared_checks) == 79
    assert declared_checks == mapped_checks, "Declared and mapped check IDs must match exactly"
```

- [ ] **Step 3: Run full G8 tooling test suite**

Run: `python -m pytest tests/g8/ -v`
Expected: PASS across all unit, integration, fixture, tamper, and lifecycle coverage modules.

---

### Task 10: Fresh Current-HEAD Runtime Execution (Approved Execution Environment)

**Files:** None (Execution only).

> **Execution Boundary:** Execution MUST occur in an approved execution environment satisfying repository prerequisites. Do not substitute unverified external results.
> **Command Notation:** `<python>` and `<gradle-wrapper>` are logical portable notations only; resolve dynamically to `python`/`python3` and `./gradlew`/`.\gradlew.bat` for the active host environment before execution.

- [ ] **Step 1: Resolve required corpora and execute test run via `run_verified_command.py`**

Execution steps:
- **10A:** Resolve required corpora from Task 1 canonical applicability determination.
- **10B:** Execute product/JVM unit test corpus:
  - Logical: `<python> scripts/run_verified_command.py <gradle-wrapper> testDebugUnitTest`
  - Host resolution (POSIX): `python3 scripts/run_verified_command.py ./gradlew testDebugUnitTest`
  - Host resolution (Windows): `python scripts/run_verified_command.py .\gradlew.bat testDebugUnitTest`
- **10C:** Execute instrumentation corpus ONLY when Task 1 applicability establishes it as mandatory for release gating:
  - If `contract.corpora_applicability.INSTRUMENTED_CORPUS.execution_task is not None`:
    - Logical: `<python> scripts/run_verified_command.py <gradle-wrapper> {contract.corpora_applicability.INSTRUMENTED_CORPUS.execution_task}`
    - Dynamically resolve wrapper for current host OS.
  - Else:
    - Skip step and record in evidence bundle: `"INSTRUMENTED_CORPUS: NOT_APPLICABLE — skipped by contract"`
- **10D:** Parse all required result sets using `scripts/g8_junit_parser.py`.
- **10E:** Fail closed if any mandatory corpus lacks verified execution evidence.

---

### Task 11: Release Build, Artifact & `apksigner` AND-Chain Verification

**Files:** None (Build & verify only).

- [ ] **Step 1: Build release APK and verify complete AND-chain in current approved environment**

Run:
1. Execute release build:
   - Logical: `<python> scripts/run_verified_command.py <gradle-wrapper> assembleRelease`
   - Host resolution (POSIX): `python3 scripts/run_verified_command.py ./gradlew assembleRelease`
   - Host resolution (Windows): `python scripts/run_verified_command.py .\gradlew.bat assembleRelease`
2. Independent AND-chain check:
   - APK exists on disk at `app/build/outputs/apk/release/app-release.apk`.
   - SHA-256 independently recomputed from binary.
   - `apksigner verify --verbose --print-certs` exits with 0.
   - Signature scheme v2/v3 verified.
   - Certificate SHA-256 fingerprint matches owner-authorized signing authority.
   - Tested boundary SHA matches release boundary SHA.

---

### Task 12: Final G8 Machine Certification Execution & Reviewer Exit Gate Verification

**Files:** None (Pipeline execution only).

> **Command Notation:** Logical commands are portable notation only; resolve `<python>` to the host Python executable prior to execution.

- [ ] **Step 1: Inspect actual CLI contract and execute certification pipeline**

Execution steps:
1. Inspect CLI contract of `scripts/g8_verify_certification_bundle.py` and preserve existing invocation syntax.
2. Execute certification engine:
   - Logical: `<python> scripts/run_verified_command.py <python> scripts/g8_certify.py`
3. Execute independent verifier using the exact discovered CLI interface:
   - Logical: `<python> scripts/run_verified_command.py <python> scripts/g8_verify_certification_bundle.py build/g8_certification/g8_certification_bundle.json`

Expected Outcome:
- **PASS with `PRODUCTION_READY`** ONLY when all canonical prerequisites (signing authority provenance, approved build environment, and required corpora execution) are fully satisfied.
- **FAIL CLOSED / BLOCKED** with explicit machine-readable blocking reasons if any canonical prerequisite is absent or unresolved.

- [ ] **Step 2: Execute 10-Pass Reviewer Exit Gate Checklist**

Verify all automated gates pass unconditionally in the approved execution environment (logical notation; resolve `<python>` for host):
```bash
<python> scripts/run_verified_command.py <python> -m pytest tests/g8/ -v
<python> scripts/run_verified_command.py <python> scripts/verify_test_environment_matrix.py
<python> scripts/run_verified_command.py <python> scripts/verify_invariant_contract.py
<python> scripts/run_verified_command.py <python> scripts/scan_forbidden_patterns.py
```

---

## The 10-Pass Reviewer Exit Gate

| Gate | Category | Critical Requirement | Machine / Governance Verification Method | Status |
|---|---|---|---|---|
| **GATE-01: Zero Product Scope** | Machine | `app/src/main/` and build config diff == 0 | `git diff --quiet -- app/src/main/ app/build.gradle.kts app/proguard-rules.pro` | REQUIRED |
| **GATE-02: Canonical Authority** | Machine | All plan predicates trace to contract/check | `test_contract_semantics.py` | REQUIRED |
| **GATE-03: Applicability** | Machine | Structured applicability loaded and verified against contract | `test_applicability_gate.py` | REQUIRED |
| **GATE-04: Test Matrix** | Machine | 0 unclassified test files | `verify_test_environment_matrix.py` | REQUIRED |
| **GATE-05: 79 Proof Mapping** | Machine | 79/79 unique IDs + selectors resolve | `test_proof_map_integrity.py` | REQUIRED |
| **GATE-06: Verifier Independence** | Machine | Tampered producer semantics rejected | `test_verifier_model.py` / `test_tamper_regression.py` | REQUIRED |
| **GATE-07: State Derivation** | Machine | Verifier evaluates canonical predicates | `test_state_derivation.py` | REQUIRED |
| **GATE-08: Evidence Boundary** | Machine | Multi-manifest boundary hashes match | `test_manifest_boundary.py` | REQUIRED |
| **GATE-09: Runtime & Artifact Path** | Machine | All mandatory runtime corpora executed in approved environment + release artifact independently verified + final verifier executed in same boundary | Verified execution of mandatory corpora in approved environment + `apksigner verify` + `g8_verify_certification_bundle.py` | REQUIRED |
| **GATE-10: Scope Discipline** | Governance | Zero unapproved modules or parallel architectures | Governance inspection against Infrastructure Reuse Policy | REQUIRED |

---

## Final Reviewer Exit Criteria

A review may remain open only for a concrete, demonstrable failure against one of these criteria:
1. Unauthorized product/runtime scope modification.
2. Requirement without admissibility evidence.
3. 79-check mapping/execution cardinality mismatch (`79 declared != 79 mapped != 79 executed != 79 evidenced != 79 verified`).
4. Non-resolvable proof target or selector.
5. Producer-defined acceptance semantics accepted as truth.
6. Non-canonical state derivation approximation.
7. Missing required corpus execution evidence.
8. Evidence boundary or TOCTOU failure.
9. Historical evidence accepted as current-HEAD proof.
10. Current repository API/CLI signature mismatch.
11. Placeholder or unverifiable execution command.
12. Scope or architecture expansion not justified by contract, defect, or failing gate.
13. No generic predicate/policy engine introduced without a demonstrated capability gap.
14. Certification boundary identity is explicitly defined and artifact-bound; no hash is compared across unlike objects.

*Otherwise, this plan is **IMPLEMENTATION-READY**.*

---

## Implementation Readiness Final Closure

This plan is **IMPLEMENTATION-READY** only when:
1. No product/runtime path is modified (`app/src/main/`, database schema, runtime behavior).
2. Every added obligation satisfies the Rule of Admissibility.
3. Corpus applicability is resolved from canonical authority.
4. 79/79 checks are mapped, executed, evidenced, and independently verified.
5. Proof selectors are actually resolvable, not metadata-only.
6. The verifier consumes canonical semantics and does not trust producer declarations.
7. State predicates are consumed from the canonical contract without introducing a general-purpose policy engine.
8. Existing manifest and verifier interfaces are reused or minimally extended only where a demonstrated capability gap exists.
9. The certification boundary identity is explicitly defined and binds source, test, contracts, proof map, environment, and release artifact.
10. No historical evidence can satisfy current certification.
11. No placeholder command or guessed API/CLI remains.
12. All mandatory exit gates are machine-verifiable in the approved execution environment.

Any subsequent review may keep the plan open only for a concrete evidence-backed defect against one of these twelve criteria. Architectural preference, optional hardening, or speculative future requirements are non-blocking and out of scope.

---

## Definition of Done (DoD)

- [ ] Applicability gate formally determines release obligations without introducing unmandated scope.
- [ ] 79/79 Lifecycle Closure: `79 checks declared == 79 mapped == 79 executed == 79 evidenced == 79 independently verified`.
- [ ] `proof_mode` and `expected_outcome` are strictly contract-derived for all 79 checks.
- [ ] Every executor is callable on disk and semantically bound to target class/method/script with exact execution selectors.
- [ ] Probe outcome (`FAIL_OR_BLOCKING_STATE`) is strictly distinguished from certification outcome (`PASS`).
- [ ] JUnit XML reports are parsed for structured test counts, failures, errors, skips, and NO-SOURCE.
- [ ] Product, tooling, structural, and historical corpora are separately and deterministically identified (zero unclassified).
- [ ] Production runtime boundary is cryptographically bound via existing manifest infrastructure into `CERTIFICATION_BOUNDARY_ID`.
- [ ] TOCTOU mutations are reliably detected using isolated fixtures.
- [ ] Producer cannot self-declare certification PASS; independent verifier recomputes all derived states directly from canonical contracts.
- [ ] Historical evidence records remain immutable and cannot close current-HEAD certification.
- [ ] Release APK SHA-256 is independently recomputed from binary and bound into the `CERTIFICATION_BOUNDARY_ID` release certification record (not a direct preimage of the boundary hash itself).
- [ ] APK signature is independently verified via `apksigner` against owner-authorized signing authority.
- [ ] Exact tested boundary matches released artifact boundary.
- [ ] Zero G8 runtime footprint exists in production artifact/classpath.
- [ ] All tamper and negative self-tests pass 100%.
- [ ] Production application source, database schema, and runtime behavior remain 100% unchanged.
- [ ] All 10 gates in the Reviewer Exit Gate pass unconditionally with exit code 0.
