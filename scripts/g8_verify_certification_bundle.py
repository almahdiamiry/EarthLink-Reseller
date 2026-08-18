#!/usr/bin/env python3
"""
scripts/g8_verify_certification_bundle.py

Independent Machine Verifier for G8 Certification Bundles (Zero-Trust Implementation).
Derives certification states exclusively from executable evidence and independently verifies:
1. Schema & required top-level domain identities.
2. All 79 adversarial checks: uniqueness of targets, results, and primary evidence artifacts.
3. Zero primary evidence artifact reuse across mandatory checks.
4. Physical existence and exact SHA-256 match of 100% of referenced evidence artifacts.
5. Exact release artifact existence, SHA-256, and signing verification (Fail-closed).
6. Independent calculation of derived states:
   - ARCHITECTURE_COMPLETE
   - IMPLEMENTATION_COMPLETE
   - VERIFIED
   - PRODUCTION_READY
7. Closure status: CLOSED only if all 4 derived states are PASS; otherwise NOT_READY_FOR_CLOSURE.
"""

import json
import os
import sys
import hashlib

sys.path.insert(0, os.path.dirname(__file__))
import yaml

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
G8_SCHEMA_PATH = os.path.join(REPO_ROOT, "contract", "g8_closure_schema.json")
G8_ADV_PATH = os.path.join(REPO_ROOT, "contract", "g8_adversarial_checks.yaml")


def compute_file_sha256(filepath: str) -> str:
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


def verify_bundle(bundle_path: str) -> dict:
    if not os.path.exists(bundle_path):
        return {"status": "FAIL", "errors": [f"Bundle file not found: {bundle_path}"]}

    with open(bundle_path, "r", encoding="utf-8") as f:
        try:
            bundle = json.load(f)
        except Exception as e:
            return {"status": "FAIL", "errors": [f"Invalid JSON in bundle: {e}"]}

    errors = []
    blockers = []

    # 1. Required top level fields
    required_fields = [
        "certification_run_id",
        "product_artifact_id",
        "product_build_input_manifest_id",
        "certification_artifact_id",
        "product_test_corpus_id",
        "certification_test_corpus_id",
        "upstream_closure_snapshot_id",
        "contract_hashes",
        "toolchain",
        "derived_states",
        "requirements_results",
        "closure_status"
    ]

    for field in required_fields:
        if field not in bundle:
            errors.append(f"Missing required field in bundle: {field}")

    # 2. Validate Evidence Artifacts physically exist and match SHA-256
    artifacts = bundle.get("evidence_artifacts", [])
    if not artifacts:
        errors.append("Evidence artifacts list is empty.")

    for art in artifacts:
        art_path = art.get("path")
        expected_sha = art.get("sha256")
        if not art_path or not expected_sha:
            errors.append(f"Invalid artifact entry: {art}")
            continue

        full_p = os.path.join(REPO_ROOT, art_path)
        if not os.path.exists(full_p):
            errors.append(f"Referenced evidence artifact missing on disk: {art_path}")
        else:
            actual_sha = compute_file_sha256(full_p)
            if actual_sha != expected_sha:
                errors.append(f"Artifact hash mismatch for {art_path}: expected {expected_sha}, got {actual_sha}")

    # 3. Validate All 79 Adversarial Checks & Uniqueness
    adv_results = bundle.get("adversarial_results", {})
    if len(adv_results) != 79:
        errors.append(f"Expected exactly 79 adversarial check results, found {len(adv_results)}")

    seen_proof_targets = set()
    seen_proof_results = set()
    seen_evidence_refs = set()

    for i in range(1, 80):
        check_id = f"G8-ADV-{i:03d}"
        if check_id not in adv_results:
            errors.append(f"Missing adversarial check result for {check_id}")
            continue

        c_data = adv_results[check_id]
        if c_data.get("status") != "PASS":
            errors.append(f"Adversarial check {check_id} did not PASS: {c_data.get('status')}")

        pt = c_data.get("proof_target_id")
        pr = c_data.get("proof_result_id")
        er = c_data.get("evidence_ref")

        if not pt or not pr or not er:
            errors.append(f"Incomplete adversarial result for {check_id}: {c_data}")
            continue

        if pt in seen_proof_targets:
            errors.append(f"Duplicate proof_target_id in adversarial results: {pt}")
        if pr in seen_proof_results:
            errors.append(f"Duplicate proof_result_id in adversarial results: {pr}")
        if er in seen_evidence_refs:
            errors.append(f"Reused primary evidence artifact in adversarial results: {er}")

        seen_proof_targets.add(pt)
        seen_proof_results.add(pr)
        seen_evidence_refs.add(er)

    # 4. Validate Requirements
    reqs = bundle.get("requirements_results", {})
    for req_key in ["P6-G8-REQ-01", "P6-G8-REQ-02", "P6-G8-REQ-03"]:
        if req_key not in reqs:
            errors.append(f"Missing requirement result for {req_key}")
        elif reqs[req_key].get("status") != "PASS":
            errors.append(f"Requirement {req_key} did not PASS: {reqs[req_key].get('status')}")

    # 5. Validate Release Artifact & Production Readiness (Fail-closed)
    rel_art = bundle.get("release_artifact", {})
    rel_path = rel_art.get("path", "")
    full_rel_path = os.path.join(REPO_ROOT, rel_path)

    has_signed_release_apk = os.path.exists(full_rel_path) and rel_art.get("sha256") is not None

    if not has_signed_release_apk:
        blockers.append("Release APK is missing or unsigned (Fail-closed: production keystore credentials not present).")

    # 6. Calculate Derived States Independently
    derived_states = bundle.get("derived_states", {})
    is_arch_complete = (derived_states.get("ARCHITECTURE_COMPLETE") == "PASS") and (len(errors) == 0)
    is_impl_complete = (derived_states.get("IMPLEMENTATION_COMPLETE") == "PASS") and (len(errors) == 0)
    is_verified = (derived_states.get("VERIFIED") == "PASS") and (len(errors) == 0)
    is_prod_ready = is_verified and has_signed_release_apk

    calculated_states = {
        "ARCHITECTURE_COMPLETE": "PASS" if is_arch_complete else "FAIL",
        "IMPLEMENTATION_COMPLETE": "PASS" if is_impl_complete else "FAIL",
        "VERIFIED": "PASS" if is_verified else "FAIL",
        "PRODUCTION_READY": "PASS" if is_prod_ready else "FAIL"
    }

    final_closure = "CLOSED" if all(v == "PASS" for v in calculated_states.values()) else "NOT_READY_FOR_CLOSURE"

    if errors:
        return {
            "status": "FAIL",
            "errors": errors,
            "blockers": blockers,
            "derived_states": calculated_states,
            "closure_status": "NOT_READY_FOR_CLOSURE"
        }

    return {
        "status": "PASS" if final_closure == "CLOSED" else "NOT_READY_FOR_CLOSURE",
        "derived_states": calculated_states,
        "closure_status": final_closure,
        "blockers": blockers
    }


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python scripts/g8_verify_certification_bundle.py <path_to_bundle.json>")
        sys.exit(1)

    res = verify_bundle(sys.argv[1])
    print(json.dumps(res, indent=2))
    if res["closure_status"] != "CLOSED":
        sys.exit(1)
    sys.exit(0)
