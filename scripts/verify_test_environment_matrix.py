#!/usr/bin/env python3
"""
scripts/verify_test_environment_matrix.py

Validates the Test Environment Matrix contract (contract/test_environment_matrix.yaml):
- Validates YAML syntax and top-level schema.
- Verifies that all 16 canonical invariants (INV-01 through INV-16) are mapped.
- Ensures every invariant specifies required execution tiers, existing primary test suites, and rationale.
- Verifies that every referenced test suite and script exists on disk.
- Validates explicitly preserved Phase 3 required suites.
- Ensures environment consistency (e.g. INSTRUMENTED in androidTest, STRUCTURAL in scripts).
- Enforces regression protection: Phase 1/2/3 required entries CANNOT be removed from matrix.
- Checks that all test files on disk are registered in the matrix without omissions.
- Computes SHA256 checksums of the matrix and key artifacts.
"""

import hashlib
import os
import sys
import os
sys.path.insert(0, os.path.dirname(__file__))
import yaml

if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
MATRIX_PATH = os.path.join(REPO_ROOT, "contract", "test_environment_matrix.yaml")
EXPECTED_INVARIANTS = [f"INV-{i:02d}" for i in range(1, 17)]
VALID_ENVIRONMENTS = {"JVM", "ROBOLECTRIC", "INSTRUMENTED", "STRUCTURAL", "HISTORICAL"}

# Regression Protection: Immutable required certification matrix entries across Phases 1, 2, and 3
REQUIRED_CERTIFICATION_ENTRIES = [
    "app/src/test/java/com/example/ResolveLocalVersionTest.kt",
    "app/src/test/java/com/example/Phase2ServerConfirmedLifecycleTest.kt",
    "app/src/test/java/com/example/Phase2RemoteVersionAdversarialTest.kt",
    "app/src/test/java/com/example/Phase3CoordinatorMutexTokenTest.kt",
    "app/src/test/java/com/example/DataOperationCoordinatorConcurrencyTest.kt",
]


def compute_sha256(filepath: str) -> str:
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


def verify_matrix(matrix_path=None, repo_root=None) -> bool:
    target_matrix = matrix_path or MATRIX_PATH
    target_root = repo_root or REPO_ROOT
    print("=================================================================")
    print("=== Earthlink Reseller App -- Test Environment Matrix Validator ===")
    print("=================================================================")

    if not os.path.exists(target_matrix):
        print(f"[FAIL] Test Environment Matrix file not found: {target_matrix}")
        return False

    matrix_sha = compute_sha256(target_matrix)
    print(f"Matrix File   : {os.path.relpath(target_matrix, target_root)}")
    print(f"Matrix SHA256 : {matrix_sha}")
    print("-----------------------------------------------------------------")

    try:
        with open(target_matrix, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f)
    except Exception as e:
        print(f"[FAIL] Failed to parse YAML: {e}")
        return False

    if not isinstance(data, dict):
        print("[FAIL] Root of matrix must be a YAML dictionary.")
        return False

    errors = []

    # 1. Validate environment definitions
    environments = data.get("environments")
    if not isinstance(environments, dict):
        errors.append("'environments' must be a dictionary.")
    else:
        for env_key in VALID_ENVIRONMENTS:
            if env_key not in environments:
                errors.append(f"Missing environment definition: {env_key}")

    # 2. Validate Invariants Matrix
    invariants = data.get("invariants_matrix")
    if not isinstance(invariants, list):
        errors.append("'invariants_matrix' key missing or not a list.")
        invariants = []

    found_inv_ids = []
    for idx, inv in enumerate(invariants):
        if not isinstance(inv, dict):
            errors.append(f"Invariant at index {idx} is not a dictionary.")
            continue

        inv_id = inv.get("id")
        name = inv.get("name")
        tiers = inv.get("required_tiers")
        primary_suites = inv.get("primary_suites")
        rationale = inv.get("tier_rationale")

        if not inv_id:
            errors.append(f"Invariant at index {idx} missing 'id'.")
            continue

        found_inv_ids.append(inv_id)

        if not name or not isinstance(name, str) or not name.strip():
            errors.append(f"[{inv_id}] Missing or empty 'name'.")

        if not tiers or not isinstance(tiers, list) or len(tiers) == 0:
            errors.append(f"[{inv_id}] Missing or empty 'required_tiers'.")
        else:
            for t in tiers:
                if t not in VALID_ENVIRONMENTS:
                    errors.append(f"[{inv_id}] Invalid required tier: '{t}'. Must be one of {VALID_ENVIRONMENTS}")

        if not primary_suites or not isinstance(primary_suites, list) or len(primary_suites) == 0:
            errors.append(f"[{inv_id}] Missing or empty 'primary_suites'.")
        else:
            for suite_path in primary_suites:
                full_path = os.path.join(REPO_ROOT, suite_path)
                if not os.path.exists(full_path):
                    errors.append(f"[{inv_id}] Referenced primary suite does not exist: {suite_path}")

        if not rationale or not isinstance(rationale, str) or not rationale.strip():
            errors.append(f"[{inv_id}] Missing or empty 'tier_rationale'.")

    missing_invs = [expected for expected in EXPECTED_INVARIANTS if expected not in found_inv_ids]
    extra_invs = [found for found in found_inv_ids if found not in EXPECTED_INVARIANTS]
    duplicate_invs = [fid for fid in set(found_inv_ids) if found_inv_ids.count(fid) > 1]

    if missing_invs:
        errors.append(f"Missing canonical invariants in matrix: {missing_invs}")
    if extra_invs:
        errors.append(f"Unexpected invariant IDs in matrix: {extra_invs}")
    if duplicate_invs:
        errors.append(f"Duplicate invariant entries in matrix: {duplicate_invs}")

    # 3. Validate Test Suites
    test_suites = data.get("test_suites")
    if not isinstance(test_suites, list):
        errors.append("'test_suites' key missing or not a list.")
        test_suites = []

    registered_paths = set()
    for idx, suite in enumerate(test_suites):
        if not isinstance(suite, dict):
            errors.append(f"Test suite at index {idx} is not a dictionary.")
            continue

        suite_name = suite.get("name")
        suite_path = suite.get("path")
        suite_env = suite.get("environment")
        suite_purpose = suite.get("tier_purpose")

        if not suite_name or not isinstance(suite_name, str):
            errors.append(f"Test suite at index {idx} missing 'name'.")
        if not suite_path or not isinstance(suite_path, str):
            errors.append(f"Test suite at index {idx} missing 'path'.")
            continue

        full_path = os.path.join(target_root, suite_path)
        if not os.path.exists(full_path):
            errors.append(f"[{suite_name}] Test suite path does not exist on disk: {suite_path}")

        if suite_env not in VALID_ENVIRONMENTS:
            errors.append(f"[{suite_name}] Invalid environment '{suite_env}'. Must be one of {VALID_ENVIRONMENTS}")

        if suite_env == "INSTRUMENTED" and not suite_path.startswith("app/src/androidTest/"):
            errors.append(f"[{suite_name}] INSTRUMENTED suite must reside in app/src/androidTest/: {suite_path}")

        if suite_env == "STRUCTURAL" and not suite_path.startswith("scripts/"):
            errors.append(f"[{suite_name}] STRUCTURAL suite must reside in scripts/: {suite_path}")

        if not suite_purpose or not isinstance(suite_purpose, str) or not suite_purpose.strip():
            errors.append(f"[{suite_name}] Missing or empty 'tier_purpose'.")

        if suite_path in registered_paths:
            errors.append(f"Duplicate test suite registration: {suite_path}")
        registered_paths.add(suite_path)

    # 4. Validate Phase 3 Preserved Required Suites
    phase3_suites = data.get("phase3_required_suites")
    if not isinstance(phase3_suites, list) or len(phase3_suites) == 0:
        errors.append("Missing or empty 'phase3_required_suites' registration.")
    else:
        for idx, suite in enumerate(phase3_suites):
            if not isinstance(suite, dict):
                errors.append(f"Phase 3 suite at index {idx} is not a dictionary.")
                continue
            s_name = suite.get("name")
            s_path = suite.get("path")
            s_req = suite.get("phase_requirement_id")
            s_status = suite.get("status")

            if not s_name or not s_path or not s_req or not s_status:
                errors.append(f"Phase 3 suite at index {idx} missing name, path, requirement_id, or status.")
            registered_paths.add(s_path)

    # 5. Regression Protection: Enforce Mandatory Phase 1/2/3 Certification Matrix Entries
    for req_entry in REQUIRED_CERTIFICATION_ENTRIES:
        if req_entry not in registered_paths:
            errors.append(f"REGRESSION: Mandatory certification entry missing from test_environment_matrix.yaml: {req_entry}")

    # Check physical existence for Phase 1 & Phase 2 required suites
    phase1_2_required_files = [
        "app/src/test/java/com/example/ResolveLocalVersionTest.kt",
        "app/src/test/java/com/example/Phase2ServerConfirmedLifecycleTest.kt",
        "app/src/test/java/com/example/Phase2RemoteVersionAdversarialTest.kt",
    ]
    for p_file in phase1_2_required_files:
        full_p = os.path.join(target_root, p_file)
        if not os.path.exists(full_p):
            errors.append(f"REGRESSION: Required Phase 1/2 physical test file missing from disk: {p_file}")

    # 6. Check for unmapped test files on disk
    unit_test_dir = os.path.join(target_root, "app", "src", "test", "java", "com", "example")
    androidTest_dir = os.path.join(target_root, "app", "src", "androidTest", "java", "com", "example")

    if os.path.exists(unit_test_dir):
        for f in os.listdir(unit_test_dir):
            if f.endswith(".kt"):
                rel_p = f"app/src/test/java/com/example/{f}"
                if rel_p not in registered_paths:
                    errors.append(f"Unregistered unit test file on disk: {rel_p}")

    if os.path.exists(androidTest_dir):
        for f in os.listdir(androidTest_dir):
            if f.endswith(".kt"):
                rel_p = f"app/src/androidTest/java/com/example/{f}"
                if rel_p not in registered_paths:
                    errors.append(f"Unregistered androidTest file on disk: {rel_p}")

    # Output validation results
    if errors:
        print(f"[FAIL] MATRIX VALIDATION FAILED with {len(errors)} error(s):")
        for err in errors:
            print(f"   * {err}")
        return False

    print(f"[PASS] All {len(EXPECTED_INVARIANTS)} canonical invariants verified in matrix.")
    print(f"[PASS] All {len(test_suites)} active test suites & scripts verified on disk.")
    print(f"[PASS] Preserved {len(phase3_suites)} required Phase 3 pending test suites verified.")
    print(f"[PASS] Regression Protection verified: All Phase 1, 2, and 3 certification entries intact.")
    print(f"[PASS] Environment tiers (JVM, ROBOLECTRIC, INSTRUMENTED, STRUCTURAL, HISTORICAL) verified.")
    print(f"[PASS] Zero unmapped test files detected.")
    print("=================================================================")
    print("=== TEST ENVIRONMENT MATRIX VALIDATION PASSED (Exit Code: 0) ===")
    print("=================================================================")
    return True


if __name__ == "__main__":
    success = verify_matrix()
    sys.exit(0 if success else 1)
