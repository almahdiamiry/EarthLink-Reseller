#!/usr/bin/env python3
"""
scripts/verify_closure_evidence.py

Validates the machine-generated evidence bundle against contract/closure_contract.yaml
and contract/closure_schema.json.
- Verifies exact source identity & SHA consistency.
- Verifies cryptographic hash of PRODUCTION_INVARIANTS.md.
- Evaluates every finding in closure_contract.yaml:
    * Structural checks (e.g. forbidden patterns, invariant consistency, gradlew mode)
    * Behavioral test executions (0 failures, 0 errors, 0 skips)
    * Artifact presence & digests
- Derives closure verdict purely from machine evidence.
- Writes derived closure result to evidence/<source_sha>/closure_result.json.
- Returns exit code 0 ONLY if all required checks are present and passing.
"""

import argparse
import datetime
import hashlib
import json
import os
import re
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


def compute_sha256(filepath: str) -> str:
    if not os.path.exists(filepath):
        return ""
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


def validate_schema(bundle: dict, schema: dict) -> list[str]:
    """Lightweight schema validator using Python standard library without external dependencies."""
    errors = []

    bundle_def = schema.get("definitions", {}).get("closure_bundle", {})
    required_keys = bundle_def.get("required", [])

    for req in required_keys:
        if req not in bundle:
            errors.append(f"Bundle missing required root property: '{req}'")

    if "source_identity" in bundle:
        si = bundle["source_identity"]
        for k in ["git_sha", "is_dirty", "git_status_porcelain", "gradlew_git_mode"]:
            if k not in si:
                errors.append(f"source_identity missing '{k}'")

    if "contract_identity" in bundle:
        ci = bundle["contract_identity"]
        for k in ["production_invariants_file", "production_invariants_sha256", "closure_contract_file", "closure_contract_sha256"]:
            if k not in ci:
                errors.append(f"contract_identity missing '{k}'")

    if "test_execution" in bundle:
        te = bundle["test_execution"]
        for k in ["command", "exit_code", "total_tests", "passed_tests", "failed_tests", "suites"]:
            if k not in te:
                errors.append(f"test_execution missing '{k}'")

    return errors


def find_test_suite(suite_name: str, suites: list[dict]) -> dict | None:
    for s in suites:
        s_full = s.get("name", "")
        if s_full == suite_name or s_full.endswith("." + suite_name):
            return s
    return None


def verify_bundle(source_sha: str = "", bundle_path: str = "") -> tuple[bool, dict]:
    print("=================================================================")
    print("=== Earthlink Reseller App -- Evidence Closure Verifier ===")
    print("=================================================================")

    # 1. Resolve source SHA and bundle path
    if not source_sha:
        import subprocess
        try:
            res = subprocess.run(["git", "rev-parse", "HEAD"], cwd=REPO_ROOT, capture_output=True, text=True)
            source_sha = res.stdout.strip()
        except Exception:
            source_sha = "UNKNOWN"

    if not bundle_path:
        bundle_path = os.path.join(REPO_ROOT, "evidence", source_sha, "closure_bundle.json")

    print(f"[*] Target Source SHA  : {source_sha}")
    print(f"[*] Evidence Bundle    : {os.path.relpath(bundle_path, REPO_ROOT) if os.path.exists(bundle_path) else bundle_path}")

    if not os.path.exists(bundle_path):
        print(f"[FAIL] Evidence bundle not found at: {bundle_path}")
        return False, {"error": "bundle_not_found"}

    # 2. Read Bundle & Schema
    try:
        with open(bundle_path, "r", encoding="utf-8") as f:
            bundle = json.load(f)
    except Exception as e:
        print(f"[FAIL] Failed to parse evidence bundle JSON: {e}")
        return False, {"error": f"invalid_json: {e}"}

    schema_path = os.path.join(REPO_ROOT, "contract", "closure_schema.json")
    if os.path.exists(schema_path):
        try:
            with open(schema_path, "r", encoding="utf-8") as f:
                schema = json.load(f)
            schema_errors = validate_schema(bundle, schema)
            if schema_errors:
                print(f"[FAIL] Schema validation errors ({len(schema_errors)}):")
                for se in schema_errors:
                    print(f"   * {se}")
                return False, {"error": "schema_validation_failed", "details": schema_errors}
        except Exception as e:
            print(f"[WARN] Could not validate schema: {e}")

    # 3. Read Closure Contract
    closure_contract_path = os.path.join(REPO_ROOT, "contract", "closure_contract.yaml")
    if not os.path.exists(closure_contract_path):
        print(f"[FAIL] Closure contract missing: {closure_contract_path}")
        return False, {"error": "closure_contract_missing"}

    try:
        with open(closure_contract_path, "r", encoding="utf-8") as f:
            closure_contract = yaml.safe_load(f)
    except Exception as e:
        print(f"[FAIL] Failed to parse closure_contract.yaml: {e}")
        return False, {"error": f"contract_yaml_parse_error: {e}"}

    closure_contract_sha256 = compute_sha256(closure_contract_path)

    # 4. Identity & Invariant Validation
    invariants_file = os.path.join(REPO_ROOT, "PRODUCTION_INVARIANTS.md")
    current_invariants_sha256 = compute_sha256(invariants_file)
    bundle_invariants_sha256 = bundle.get("contract_identity", {}).get("production_invariants_sha256", "")
    bundle_source_sha = bundle.get("source_identity", {}).get("git_sha", "")

    identity_errors = []
    if bundle_source_sha != source_sha:
        identity_errors.append(f"Source SHA mismatch: expected {source_sha}, got {bundle_source_sha}")

    if bundle_invariants_sha256 != current_invariants_sha256:
        identity_errors.append(f"PRODUCTION_INVARIANTS.md hash mismatch: current={current_invariants_sha256}, bundle={bundle_invariants_sha256}")

    if identity_errors:
        print("[FAIL] Source / Invariant Identity Check Failed:")
        for ie in identity_errors:
            print(f"   * {ie}")

    # 5. Evaluate Findings
    findings_spec = closure_contract.get("findings", [])
    evaluated_findings = []
    test_suites = bundle.get("test_execution", {}).get("suites", [])
    structural_checks = bundle.get("structural_checks", {})
    artifact_checks = bundle.get("artifact_checks", {})

    total_findings = len(findings_spec)
    passed_findings = 0
    failed_findings = 0
    p0_passed = 0
    p0_failed = 0
    p1_passed = 0
    p1_failed = 0

    print("-----------------------------------------------------------------")
    print(f"[*] Evaluating {total_findings} findings from closure_contract.yaml...")

    for f_spec in findings_spec:
        f_id = f_spec.get("id")
        f_title = f_spec.get("title")
        f_inv = f_spec.get("invariant")
        f_sev = f_spec.get("severity", "P0")
        f_req = f_spec.get("required_checks", {})
        f_rule = f_spec.get("closure_rule", "ALL_REQUIRED_CHECKS_PASS")

        failed_reasons = []

        # A. Structural checks
        structural_status = "PASS"
        req_structural = f_req.get("structural", [])
        for sc_id in req_structural:
            sc_data = structural_checks.get(sc_id)
            if not sc_data:
                structural_status = "FAIL"
                failed_reasons.append(f"Structural check '{sc_id}' missing in bundle")
            elif sc_id == "forbidden_patterns_scan" and "pattern_results" in sc_data:
                pattern_results = sc_data["pattern_results"]
                finding_violations = []
                all_f_ids = [x.get("id") for x in findings_spec]
                for p_id, p_info in pattern_results.items():
                    # If pattern ID is prefixed with a specific finding ID, match strictly to that finding
                    specific_match = any(p_id == fid or p_id.startswith(fid + "-") for fid in all_f_ids)
                    if specific_match:
                        applies = (p_id == f_id or p_id.startswith(f_id + "-"))
                    else:
                        applies = (p_info.get("invariant") == f_inv)

                    if applies and p_info.get("status") != "PASS":
                        finding_violations.extend(p_info.get("violations", []))
                if finding_violations:
                    structural_status = "FAIL"
                    failed_reasons.append(f"Forbidden pattern violations for {f_id} ({f_inv}): {finding_violations}")
            elif sc_data.get("status") != "PASS":
                structural_status = "FAIL"
                violations = sc_data.get("violations", [])
                details = sc_data.get("details", "")
                reason_detail = f"; violations: {violations}" if violations else f"; details: {details}"
                failed_reasons.append(f"Structural check '{sc_id}' FAILED{reason_detail}")

        # B. Behavioral tests
        behavioral_status = "PASS"
        req_tests = f_req.get("behavioral", [])
        for t_name in req_tests:
            suite = find_test_suite(t_name, test_suites)
            if not suite:
                behavioral_status = "FAIL"
                failed_reasons.append(f"Required test suite '{t_name}' not executed or not found in bundle")
            else:
                tests_count = suite.get("tests", 0)
                failures = suite.get("failures", 0)
                errors = suite.get("errors", 0)
                skipped = suite.get("skipped", 0)

                if tests_count == 0:
                    behavioral_status = "FAIL"
                    failed_reasons.append(f"Test suite '{t_name}' executed 0 test cases")
                if failures > 0 or errors > 0:
                    behavioral_status = "FAIL"
                    failed_reasons.append(f"Test suite '{t_name}' had {failures} failure(s) and {errors} error(s)")
                if skipped > 0:
                    behavioral_status = "FAIL"
                    failed_reasons.append(f"Test suite '{t_name}' had {skipped} skipped test(s)")

        # C. Artifact checks
        artifact_status = "PASS"
        req_artifacts = f_req.get("artifact", [])
        source_paths = f_spec.get("source_paths", [])

        for sp in source_paths:
            normalized_sp = sp.replace("\\", "/")
            ac_data = artifact_checks.get(normalized_sp)
            if not ac_data or not ac_data.get("exists") or ac_data.get("status") != "PASS":
                artifact_status = "FAIL"
                failed_reasons.append(f"Referenced source artifact '{sp}' missing or invalid")

        for art_id in req_artifacts:
            if art_id == "evidence_bundle_verified":
                continue
            ac_data = artifact_checks.get(art_id)
            if not ac_data or ac_data.get("status") != "PASS":
                artifact_status = "FAIL"
                failed_reasons.append(f"Artifact check '{art_id}' FAILED")

        # D. Derived Finding Verdict
        if f_rule == "ALL_REQUIRED_CHECKS_PASS":
            derived_status = "PASS" if (structural_status == "PASS" and behavioral_status == "PASS" and artifact_status == "PASS") else "FAIL"
        else:
            derived_status = "PASS" if (structural_status == "PASS" or behavioral_status == "PASS") else "FAIL"

        if derived_status == "PASS":
            passed_findings += 1
            if f_sev == "P0":
                p0_passed += 1
            elif f_sev == "P1":
                p1_passed += 1
            print(f"  [PASS] {f_id:28s} ({f_inv}) [{f_sev}]")
        else:
            failed_findings += 1
            if f_sev == "P0":
                p0_failed += 1
            elif f_sev == "P1":
                p1_failed += 1
            print(f"  [FAIL] {f_id:28s} ({f_inv}) [{f_sev}] - {len(failed_reasons)} failure(s)")
            for fr in failed_reasons:
                print(f"         * {fr}")

        evaluated_findings.append({
            "id": f_id,
            "title": f_title,
            "invariant": f_inv,
            "severity": f_sev,
            "derived_status": derived_status,
            "structural_status": structural_status,
            "behavioral_status": behavioral_status,
            "artifact_status": artifact_status,
            "failed_reasons": failed_reasons,
            "checks_summary": {
                "required_structural": req_structural,
                "required_behavioral": req_tests,
                "required_artifact": req_artifacts,
                "source_paths": source_paths
            }
        })

    # 6. Compute Overall Verdict
    all_p0_passed = (p0_failed == 0 and p0_passed > 0)
    all_p1_passed = (p1_failed == 0)
    identity_clean = (len(identity_errors) == 0)

    is_clean = (failed_findings == 0 and identity_clean)
    overall_verdict = "READY_FOR_CLOSURE" if is_clean else "NOT_READY_FOR_CLOSURE"

    now_iso = datetime.datetime.now(datetime.timezone.utc).isoformat()
    result_data = {
        "schema_version": "1.0",
        "timestamp": now_iso,
        "source_sha": source_sha,
        "evaluated_contract_sha256": closure_contract_sha256,
        "overall_verdict": overall_verdict,
        "findings": evaluated_findings,
        "summary": {
            "total_findings": total_findings,
            "passed_findings": passed_findings,
            "failed_findings": failed_findings,
            "p0_passed": p0_passed,
            "p0_failed": p0_failed,
            "p1_passed": p1_passed,
            "p1_failed": p1_failed,
            "all_p0_passed": all_p0_passed,
            "all_p1_passed": all_p1_passed
        }
    }

    # 7. Write closure_result.json
    out_dir = os.path.dirname(bundle_path)
    result_file = os.path.join(out_dir, "closure_result.json")
    with open(result_file, "w", encoding="utf-8") as f:
        json.dump(result_data, f, indent=2, ensure_ascii=False)

    print("-----------------------------------------------------------------")
    print(f"Summary: {passed_findings}/{total_findings} findings PASSED (P0 Failed: {p0_failed}, P1 Failed: {p1_failed})")
    print(f"Result Output: {os.path.relpath(result_file, REPO_ROOT)}")
    print(f"Final Machine Verdict: [{overall_verdict}]")
    print("=================================================================")

    return is_clean, result_data


def main():
    parser = argparse.ArgumentParser(description="Verify Earthlink Closure Evidence Bundle")
    parser.add_argument("--source-sha", default="", help="Git SHA of source commit")
    parser.add_argument("--bundle", default="", help="Path to closure_bundle.json")
    args = parser.parse_args()

    success, _ = verify_bundle(source_sha=args.source_sha, bundle_path=args.bundle)
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()
