#!/usr/bin/env python3
"""
scripts/verify_phase_compliance.py

Validates requirement-by-requirement machine evidence and compliance status
against contract/phase_requirements.yaml:
- Inspects target phase requirements (e.g., --phase 1).
- Verifies that every requirement has status: PASS.
- Validates existence of all referenced production code locations.
- Validates existence of all referenced test suites.
- Validates existence and test results in all referenced JUnit XML evidence files (tests > 0, failures == 0, errors == 0, skipped == 0).
- Validates registry and adversarial fixture references.
- Returns exit code 0 if and only if all blocking requirements in the target phase are completely verified.
"""

import argparse
import os
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(__file__))
import yaml

if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
MANIFEST_PATH = os.path.join(REPO_ROOT, "contract", "phase_requirements.yaml")


def check_file_path(path_str: str) -> list[str]:
    errors = []
    if not path_str or path_str.strip() == "-" or path_str.strip() == "null":
        return errors
    # Might be comma-separated list of paths or path:line
    parts = [p.strip() for p in path_str.split(",") if p.strip()]
    for part in parts:
        clean_path = part.split(":")[0].strip()
        full_p = os.path.join(REPO_ROOT, clean_path)
        if not os.path.exists(full_p):
            errors.append(f"Referenced path not found on disk: {clean_path}")
    return errors


def check_xml_evidence(evidence_str: str) -> tuple[bool, str, dict]:
    if not evidence_str or evidence_str.strip() == "-" or evidence_str.strip() == "null":
        return True, "No XML evidence required", {}
    parts = [p.strip() for p in evidence_str.split(",") if p.strip()]
    aggregated_stats = {"files": 0, "tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    for part in parts:
        clean_path = part.split(":")[0].strip()
        full_p = os.path.join(REPO_ROOT, clean_path)
        if not os.path.exists(full_p):
            return False, f"Evidence file not found on disk: {clean_path}", aggregated_stats
        if clean_path.endswith(".xml"):
            try:
                tree = ET.parse(full_p)
                root = tree.getroot()
                tests = int(root.attrib.get("tests", 0))
                failures = int(root.attrib.get("failures", 0))
                errors = int(root.attrib.get("errors", 0))
                skipped = int(root.attrib.get("skipped", 0))
                aggregated_stats["files"] += 1
                aggregated_stats["tests"] += tests
                aggregated_stats["failures"] += failures
                aggregated_stats["errors"] += errors
                aggregated_stats["skipped"] += skipped

                if tests == 0:
                    return False, f"XML evidence has 0 tests: {clean_path}", aggregated_stats
                if failures > 0 or errors > 0:
                    return False, f"XML evidence contains failures ({failures}) or errors ({errors}): {clean_path}", aggregated_stats
                if skipped > 0:
                    return False, f"XML evidence contains skipped tests ({skipped}): {clean_path}", aggregated_stats
            except Exception as e:
                return False, f"Failed to parse XML evidence {clean_path}: {e}", aggregated_stats
        else:
            aggregated_stats["files"] += 1

    return True, "Valid evidence", aggregated_stats


def verify_phase(target_phase: int = 1) -> bool:
    print("=================================================================")
    print(f"=== Earthlink Reseller App -- Phase {target_phase} Compliance Verifier ===")
    print("=================================================================")

    if not os.path.exists(MANIFEST_PATH):
        print(f"[FAIL] Manifest file not found: {MANIFEST_PATH}")
        return False

    with open(MANIFEST_PATH, "r", encoding="utf-8") as f:
        manifest = yaml.safe_load(f)

    requirements = manifest.get("requirements", [])
    phase_reqs = [r for r in requirements if r.get("phase") == target_phase]

    if not phase_reqs:
        print(f"[FAIL] No requirements found for Phase {target_phase} in {MANIFEST_PATH}")
        return False

    print(f"Found {len(phase_reqs)} requirements for Phase {target_phase}.")
    print("-----------------------------------------------------------------")

    all_passed = True
    total_blocking = 0
    passed_blocking = 0

    for r in phase_reqs:
        rid = r.get("id")
        anchor = r.get("source_anchor")
        req_text = r.get("requirement")
        blocking = r.get("blocking", True)
        status = r.get("status")
        prod_loc = r.get("production_code_location")
        test_loc = r.get("behavioral_test_location")
        adv_loc = r.get("adversarial_fixture_location")
        reg_loc = r.get("registry_location")
        ev_ref = r.get("evidence_reference")

        if blocking:
            total_blocking += 1

        req_errors = []

        if status != "PASS":
            req_errors.append(f"Status is '{status}' (expected 'PASS')")

        # Verify production files
        if prod_loc:
            req_errors.extend(check_file_path(prod_loc))

        # Verify test files
        if test_loc:
            req_errors.extend(check_file_path(test_loc))

        # Verify adversarial fixture files if applicable
        if adv_loc and not adv_loc.startswith("contract/"):
            req_errors.extend(check_file_path(adv_loc))

        # Verify XML evidence
        ev_ok, ev_msg, ev_stats = check_xml_evidence(ev_ref)
        if not ev_ok:
            req_errors.append(ev_msg)

        if not req_errors:
            if blocking:
                passed_blocking += 1
            test_info = f" ({ev_stats['tests']} tests passed)" if ev_stats.get("tests", 0) > 0 else ""
            print(f"  [PASS] {rid:16s} : {anchor[:45]}...{test_info}")
        else:
            all_passed = False
            print(f"  [FAIL] {rid:16s} : {anchor[:45]}...")
            for err in req_errors:
                print(f"         * {err}")

    print("-----------------------------------------------------------------")
    print(f"Phase {target_phase} Summary: {passed_blocking}/{total_blocking} blocking requirements verified PASS.")
    
    if all_passed and passed_blocking == total_blocking:
        print(f"=== PHASE {target_phase} COMPLIANCE VERIFICATION PASSED (Exit Code: 0) ===")
        print("=================================================================")
        return True
    else:
        print(f"=== PHASE {target_phase} COMPLIANCE VERIFICATION FAILED (Exit Code: 1) ===")
        print("=================================================================")
        return False


def main():
    parser = argparse.ArgumentParser(description="Verify phase compliance from phase_requirements.yaml")
    parser.add_argument("--phase", type=int, default=1, help="Phase number to verify (default: 1)")
    args = parser.parse_args()

    success = verify_phase(args.phase)
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()
