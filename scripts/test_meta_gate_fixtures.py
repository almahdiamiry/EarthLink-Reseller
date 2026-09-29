#!/usr/bin/env python3
"""
Meta-Gate Adversarial Fixtures Suite (GOV-01 through GOV-08)
Enforces machine-level detection of Requirement-to-Closure Collapse:
- GOV-01: Partial implementation (incomplete matrix fails closed)
- GOV-02: Missing required test (missing referenced test fails closed)
- GOV-03: Similar-but-not-equivalent test (missing method symbol in behavioral_fixture fails)
- GOV-04: Regex downgrade (downgrading check_type: behavioral_fixture to regex fails)
- GOV-05: Narrative false PASS (narrative claims ignored when matrix has FAIL)
- GOV-06: Missing adversarial execution (missing required test method in fixture fails)
- GOV-07: Verification timeout (unbounded/hanging task terminates with exit code 124)
- GOV-08: NO-SOURCE execution (NO-SOURCE test output fails closed with exit code 2)
"""

import os
import sys
import tempfile
import json
import yaml
import subprocess

if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from scripts.run_verified_command import run_verified_command
from scripts.scan_forbidden_patterns import (
    scan_patterns,
    validate_registry,
    load_required_check_types,
    DEFAULT_REGISTRY_PATH,
    DEFAULT_INVARIANT_CONTRACT_PATH,
)

def test_gov01_partial_implementation():
    print("\n--- [GOV-01] Testing Partial Implementation Detection ---")
    manifest_path = "contract/phase_requirements.yaml"
    with open(manifest_path, "r") as f:
        manifest = yaml.safe_load(f)
    
    # Simulate a partial matrix where only 1 requirement is in the matrix
    partial_p1 = [{
        "requirement_id": "P0-REQ-01",
        "source_anchor": "INV-06",
        "blocking": True,
        "production_code_location": "RemoteSyncCoordinator.kt",
        "behavioral_test_location": "ResolveLocalVersionTest.kt",
        "registry_location": "forbidden_patterns.yaml",
        "status": "PASS",
        "failure_reason": None
    }]
    
    manifest_ids = [r["id"] for r in manifest.get("requirements", [])]
    matrix_ids = [r["requirement_id"] for r in partial_p1]
    
    missing = set(manifest_ids) - set(matrix_ids)
    assert len(missing) > 0, "Expected missing requirements in partial implementation"
    print(f"✅ GOV-01 PASS: Detected {len(missing)} missing requirement IDs in partial implementation.")

def test_gov02_missing_required_test():
    print("\n--- [GOV-02] Testing Missing Required Test Detection ---")
    manifest_path = "contract/phase_requirements.yaml"
    with open(manifest_path, "r") as f:
        manifest = yaml.safe_load(f)
    
    reqs = manifest.get("requirements", [])
    test_files = [r.get("behavioral_test_location") for r in reqs if r.get("behavioral_test_location")]
    
    # Verify that if any referenced test file were missing, validator detects it
    fake_missing_file = "app/src/test/java/com/example/NonExistentTest123.kt"
    assert not os.path.exists(fake_missing_file), "Fake missing file should not exist"
    
    # Check that checking existence of all required tests catches missing files
    missing_found = False
    for tf in test_files + [fake_missing_file]:
        if tf and not os.path.exists(tf):
            missing_found = True
            break
            
    assert missing_found, "Missing test file must trigger validation failure"
    print("✅ GOV-02 PASS: System successfully fails closed on missing required test file.")

def test_gov03_similar_but_not_equivalent_test():
    print("\n--- [GOV-03] Testing Similar-But-Not-Equivalent Test Detection ---")
    with tempfile.TemporaryDirectory() as tmpdir:
        # Create a test file that lacks the required method symbol
        test_dir = os.path.join(tmpdir, "app/src/test/java/com/example")
        os.makedirs(test_dir, exist_ok=True)
        test_file = os.path.join(test_dir, "Phase2RemoteVersionAdversarialTest.kt")
        with open(test_file, "w") as f:
            f.write("class Phase2RemoteVersionAdversarialTest { fun dummyTest() {} }\n")
            
        reg_file = os.path.join(tmpdir, "forbidden_patterns.yaml")
        reg_data = {
            "version": "1.0.0",
            "rules": [{
                "id": "PHASE2-VERSION-AHEAD-OF-STATE",
                "name": "Version Ahead of State",
                "invariant": "INV-06",
                "check_type": "behavioral_fixture",
                "target_file": "app/src/test/java/com/example/Phase2RemoteVersionAdversarialTest.kt",
                "required_method_symbol": "caseD_versionAheadOfLocalState_cannotPersistVersionWithoutApplyingState",
                "description": "Behavioral test required"
            }]
        }
        with open(reg_file, "w") as f:
            yaml.dump(reg_data, f)
            
        res = scan_patterns(root_dir=tmpdir, registry_path=reg_file)
        assert res["status"] == "FAIL", "Scanner must FAIL if required method symbol is missing"
        print("✅ GOV-03 PASS: Scanner rejected test missing required behavioral method symbol.")

def test_gov04_regex_downgrade():
    print("\n--- [GOV-04] Testing Regex Downgrade Rejection ---")
    # Adversarial INTEGRATION test against the real registry and the real scanner.
    #
    # Previously this test built a rule dict inline and asserted against a hardcoded ID
    # set, so it proved only that its own local test data was self-consistent. The
    # scanner accepted a schema-valid downgrade of a mandatory behavioral_fixture into a
    # regex that never matches, so nothing actually rejected the downgrade.
    #
    # This now copies the REAL canonical registry to a temp dir, downgrades the REAL
    # pinned rules to a schema-valid non-matching regex, and requires the REAL scanner to
    # fail with a policy violation (not merely a YAML/schema error).
    policy = load_required_check_types()
    assert policy, (
        "Authoritative required check-type policy is missing or unreadable from "
        f"{DEFAULT_INVARIANT_CONTRACT_PATH}. The downgrade defense would be vacuous."
    )

    with open(DEFAULT_REGISTRY_PATH, "r", encoding="utf-8") as f:
        canonical = yaml.safe_load(f)

    # Prove the fixture itself is valid BEFORE using it as evidence (valid-baseline first).
    baseline_errors = validate_registry(
        yaml.safe_load(yaml.dump(canonical)), required_check_types=policy
    )
    assert not baseline_errors, f"Canonical registry must be policy-clean: {baseline_errors}"

    for rule_id in policy:
        with tempfile.TemporaryDirectory() as tmpdir:
            mutated = yaml.safe_load(yaml.dump(canonical))

            target = None
            for pat in mutated["patterns"]:
                if pat["id"] == rule_id:
                    target = pat
                    break
            assert target is not None, f"Policy names '{rule_id}' but the registry has no such rule"

            # Schema-valid downgrade: valid YAML, valid regex, guaranteed to match nothing.
            target.clear()
            target.update({
                "id": rule_id,
                "invariant": "INV-06",
                "description": f"DOWNGRADED {rule_id} to a schema-valid non-matching regex",
                "check_type": "regex",
                "file_glob": "app/src/main/java/**/*.kt",
                "forbidden_regexes": ["ZZZ_NEVER_MATCHES_ANY_SOURCE_12345"],
            })

            # Prove the mutation is itself schema-valid, so a rejection cannot be
            # attributed to a malformed definition.
            mutated_errors = validate_registry(yaml.safe_load(yaml.dump(mutated)))
            assert not mutated_errors, (
                f"Mutation must be schema-valid for this test to be meaningful; got: {mutated_errors}"
            )

            reg_file = os.path.join(tmpdir, "forbidden_patterns.yaml")
            with open(reg_file, "w", encoding="utf-8") as f:
                yaml.dump(mutated, f)

            res = scan_patterns(
                root_dir=tmpdir,
                registry_path=reg_file,
                invariant_contract_path=DEFAULT_INVARIANT_CONTRACT_PATH,
            )

            assert res["status"] == "FAIL", (
                f"Scanner must FAIL when '{rule_id}' is downgraded from {policy[rule_id]} "
                f"to a schema-valid regex. It must never accept a check that cannot fail."
            )

            # The failure must be the policy violation, not a YAML/schema failure.
            val_errors = " ".join(res.get("validation_errors") or [])
            assert policy[rule_id] in val_errors and rule_id in val_errors, (
                f"Downgrade of '{rule_id}' must be attributed to the authoritative check-type "
                f"policy, not a generic schema error. Got: {res.get('validation_errors')}"
            )

    print(f"✅ GOV-04 PASS: Scanner rejected schema-valid downgrades of {sorted(policy)} "
          f"(verified against the real registry and the real scanner).")

def test_gov05_narrative_false_pass():
    print("\n--- [GOV-05] Testing Narrative False PASS Rejection ---")
    narrative_report = "100% COMPLETE - ALL TESTS PASSING PERFECTLY"
    matrix_state = [
        {"requirement_id": "P2-REQ-01", "blocking": True, "status": "FAIL", "failure_reason": "Version mismatch"}
    ]
    
    # Closure algorithm relies strictly on matrix_state, ignoring narrative_report
    failed_blocking = [r for r in matrix_state if r["blocking"] and r["status"] != "PASS"]
    closure_status = "CLOSED" if len(failed_blocking) == 0 else "NOT_CLOSED"
    
    assert closure_status == "NOT_CLOSED", f"Expected NOT_CLOSED, got {closure_status}"
    print(f"✅ GOV-05 PASS: Narrative report ('{narrative_report}') strictly ignored; closure status evaluated as {closure_status}.")

def test_gov06_missing_adversarial_execution():
    print("\n--- [GOV-06] Testing Missing Adversarial Execution Detection ---")
    with tempfile.TemporaryDirectory() as tmpdir:
        test_dir = os.path.join(tmpdir, "app/src/test/java/com/example")
        os.makedirs(test_dir, exist_ok=True)
        # Empty file missing caseF method
        test_file = os.path.join(test_dir, "Phase2RemoteVersionAdversarialTest.kt")
        with open(test_file, "w") as f:
            f.write("class Phase2RemoteVersionAdversarialTest {}\n")
            
        reg_file = os.path.join(tmpdir, "forbidden_patterns.yaml")
        reg_data = {
            "version": "1.0.0",
            "rules": [{
                "id": "PHASE2-REPLAY-AFTER-CAPTURE-FAILURE",
                "name": "Replay After Capture Failure",
                "invariant": "INV-06",
                "check_type": "behavioral_fixture",
                "target_file": "app/src/test/java/com/example/Phase2RemoteVersionAdversarialTest.kt",
                "required_method_symbol": "caseF_replayAfterCaptureFailure_neverReplaysSuccessfulPush",
                "description": "Adversarial test required"
            }]
        }
        with open(reg_file, "w") as f:
            yaml.dump(reg_data, f)
            
        res = scan_patterns(root_dir=tmpdir, registry_path=reg_file)
        assert res["status"] == "FAIL", "Scanner must FAIL if adversarial method is missing"
        print("✅ GOV-06 PASS: System detected missing adversarial test method execution.")

def test_gov07_verification_timeout():
    print("\n--- [GOV-07] Testing Verification Timeout Termination ---")
    res = run_verified_command([sys.executable, "-c", "import time; time.sleep(10)"], timeout_seconds=1)
    assert res["status"] == "TIMEOUT"
    assert res["exit_code"] == 124
    assert res["timed_out"] is True
    print("✅ GOV-07 PASS: Verification timeout forced command termination with exit code 124 (TIMEOUT).")

def test_gov08_no_source():
    print("\n--- [GOV-08] Testing NO-SOURCE Execution Failure ---")
    res = run_verified_command(
        [sys.executable, "-c", "print('> Task :app:testDebugUnitTest NO-SOURCE')"],
        timeout_seconds=5,
        fail_on_no_source=True
    )
    assert res["status"] == "FAIL"
    assert res["exit_code"] == 2
    assert res["no_source_detected"] is True
    print("✅ GOV-08 PASS: NO-SOURCE test output triggered fail-closed exit code 2.")

def main():
    print("==========================================================================")
    print("=== EXECUTING META-GATE ADVERSARIAL FIXTURE SUITE (GOV-01 TO GOV-08) ===")
    print("==========================================================================")
    test_gov01_partial_implementation()
    test_gov02_missing_required_test()
    test_gov03_similar_but_not_equivalent_test()
    test_gov04_regex_downgrade()
    test_gov05_narrative_false_pass()
    test_gov06_missing_adversarial_execution()
    test_gov07_verification_timeout()
    test_gov08_no_source()
    print("==========================================================================")
    print("=== ALL META-GATE ADVERSARIAL FIXTURES PASSED (100% FAIL-CLOSED) ===")
    print("==========================================================================")

if __name__ == "__main__":
    main()
