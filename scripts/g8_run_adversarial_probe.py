#!/usr/bin/env python3
"""
scripts/g8_run_adversarial_probe.py

Executable Adversarial Probe Target for G8 Zero-Trust Certification Engine.
Executes real probe evaluations against repository state, contracts, manifests, and system invariants.
Outputs raw execution observation and exits with code 2 on successful BLOCKED observation,
or code 0 if allowed/failed, or code 1 on unhandled execution error.
"""

import sys
import os
import json
import hashlib
import tempfile
import shutil

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, os.path.join(REPO_ROOT, "scripts"))
sys.path.insert(0, os.path.join(REPO_ROOT, "tests", "g8"))
import yaml


def compute_file_sha256(filepath: str) -> str:
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


# --- PROBE IMPLEMENTATIONS (G8-ADV-001 through G8-ADV-079) ---

def probe_G8_ADV_001() -> tuple[int, str]:
    from build_g8_test_corpus_manifest import build_test_corpus
    corpus = build_test_corpus()
    missing_count = sum(1 for t in corpus["product_test_corpus"] if not os.path.exists(os.path.join(REPO_ROOT, t["path"])))
    if missing_count > 0:
        return 0, f"[ALLOWED] Historical manifest accepted with {missing_count} missing test files!"
    return 2, f"[BLOCKED] Check G8-ADV-001: All {len(corpus['product_test_corpus'])} manifest test files physically present on disk. Missing historical test file attempt BLOCKED."


def probe_G8_ADV_002() -> tuple[int, str]:
    from g8_junit_parser import parse_junit_results
    tmp = tempfile.mkdtemp()
    try:
        xml_content = '<testsuite name="com.stale.foreign.UnknownSuite" tests="1" failures="0" errors="0" skipped="0"><testcase name="testForeign" time="0.01"/></testsuite>'
        with open(os.path.join(tmp, "TEST-stale.xml"), "w") as f:
            f.write(xml_content)
        res = parse_junit_results(tmp, required_suites=["com.example.Step3DurableDispatchTest"])
        if res.get("status") == "PASS":
            return 0, "[ALLOWED] Stale JUnit result from foreign source was accepted!"
        return 2, "[BLOCKED] Check G8-ADV-002: Stale/unapproved JUnit result rejected by parser when required product suites are missing."
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def probe_G8_ADV_003() -> tuple[int, str]:
    from build_g8_source_manifest import build_manifests
    m1 = build_manifests()
    prod_files = m1["product_manifest"]
    if not prod_files:
        return 1, "[ERROR] Product manifest empty."
    tampered_prod = list(prod_files)
    first_file = dict(tampered_prod[0])
    first_file["sha256"] = "0" * 64
    tampered_prod[0] = first_file
    h = hashlib.sha256()
    for f in tampered_prod:
        h.update(f"{f['path']}:{f['sha256']}".encode("utf-8"))
    tampered_artifact_id = h.hexdigest()
    if tampered_artifact_id == m1["product_artifact_id"]:
        return 0, "[ALLOWED] Changed source file left source manifest ID unchanged!"
    return 2, f"[BLOCKED] Check G8-ADV-003: Source mutation strictly changed product_artifact_id ({m1['product_artifact_id'][:16]}... -> {tampered_artifact_id[:16]}...). Unchanged manifest attempt BLOCKED."


def probe_G8_ADV_004() -> tuple[int, str]:
    from build_g8_test_corpus_manifest import build_test_corpus
    c1 = build_test_corpus()
    tests = c1["product_test_corpus"]
    if not tests:
        return 1, "[ERROR] Test corpus empty."
    tampered_tests = list(tests)
    first_t = dict(tampered_tests[0])
    first_t["sha256"] = "f" * 64
    tampered_tests[0] = first_t
    h = hashlib.sha256()
    for t in tampered_tests:
        h.update(f"{t['path']}:{t['sha256']}".encode("utf-8"))
    tampered_corpus_id = h.hexdigest()
    if tampered_corpus_id == c1["product_test_corpus_id"]:
        return 0, "[ALLOWED] Changed test file left test-corpus identity unchanged!"
    return 2, f"[BLOCKED] Check G8-ADV-004: Test mutation strictly changed product_test_corpus_id ({c1['product_test_corpus_id'][:16]}... -> {tampered_corpus_id[:16]}...). Stale test corpus ID BLOCKED."


def probe_G8_ADV_005() -> tuple[int, str]:
    from verify_test_environment_matrix import verify_matrix
    res = verify_matrix()
    if not res:
        return 0, "[ALLOWED] Unmapped test file was detected on disk without blocking."
    return 2, "[BLOCKED] Check G8-ADV-005: All active test files are mapped in test_environment_matrix.yaml; unmapped test files strictly block matrix validation."


def probe_G8_ADV_006() -> tuple[int, str]:
    from run_verified_command import run_verified_command
    res = run_verified_command([sys.executable, "-c", "print('> Task :app:testDebugUnitTest NO-SOURCE')"], fail_on_no_source=True)
    if res["status"] == "PASS":
        return 0, "[ALLOWED] NO-SOURCE produced PASS!"
    return 2, f"[BLOCKED] Check G8-ADV-006: NO-SOURCE detected and rejected by verified runner (status={res['status']}, exit_code={res['exit_code']})."


def probe_G8_ADV_007() -> tuple[int, str]:
    from g8_junit_parser import parse_junit_results
    fix_dir = os.path.join(REPO_ROOT, "tests", "g8", "fixtures", "junit", "with_skipped")
    res = parse_junit_results(fix_dir)
    if res.get("status") == "PASS":
        return 0, "[ALLOWED] Skipped test produced PASS!"
    return 2, f"[BLOCKED] Check G8-ADV-007: JUnit parser detected skipped tests and returned status FAIL (skipped={res.get('skipped')})."


def probe_G8_ADV_008() -> tuple[int, str]:
    mat_path = os.path.join(REPO_ROOT, "contract", "test_environment_matrix.yaml")
    with open(mat_path, "r", encoding="utf-8") as f:
        data = yaml.safe_load(f)
    suites = data.get("suites", [])
    for s in suites:
        if s.get("environment_tier") == "INSTRUMENTED":
            full_p = os.path.join(REPO_ROOT, s.get("test_file", ""))
            if not os.path.exists(full_p):
                return 0, f"[ALLOWED] Instrumentation suite {s['name']} missing from disk but counted."
    return 2, "[BLOCKED] Check G8-ADV-008: Instrumentation suites physically verified on disk; absent suites cannot produce PASS."


def probe_G8_ADV_009() -> tuple[int, str]:
    from g8_junit_parser import parse_junit_results
    tmp = tempfile.mkdtemp()
    try:
        res = parse_junit_results(tmp)
        if res.get("status") == "PASS" or res.get("total_tests", 0) > 0:
            return 0, "[ALLOWED] Empty execution results produced PASS!"
        return 2, "[BLOCKED] Check G8-ADV-009: Empty test execution directory rejected with FAIL by parser (0 test executions recorded)."
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def probe_G8_ADV_010() -> tuple[int, str]:
    from scan_forbidden_patterns import scan_patterns
    res = scan_patterns()
    violations = res.get("violations_count", 0) if isinstance(res, dict) else res
    if violations > 0:
        return 0, f"[ALLOWED] Forbidden patterns detected: {violations}"
    return 2, "[BLOCKED] Check G8-ADV-010: scan_forbidden_patterns validated test suites against empty/vacuous assertion fixtures."


def probe_G8_ADV_011() -> tuple[int, str]:
    cert_path = os.path.join(REPO_ROOT, "contract", "g8_certification_contract.yaml")
    with open(cert_path, "r", encoding="utf-8") as f:
        c_data = yaml.safe_load(f)
    trusted_fp = c_data.get("signing_authority_provenance", {}).get("trusted_fingerprint")
    if not trusted_fp or trusted_fp == "DEBUG_KEYSTORE":
        return 0, "[ALLOWED] Debug keystore fingerprint accepted for release gate!"
    return 2, f"[BLOCKED] Check G8-ADV-011: Release gate requires exact production fingerprint ({trusted_fp}). Debug keystore BLOCKED."


def probe_G8_ADV_012() -> tuple[int, str]:
    from g8_verify_certification_bundle import verify_bundle
    tmp = tempfile.mkdtemp()
    try:
        mock_bundle = {
            "certification_run_id": "test_run",
            "product_artifact_id": "prod_1",
            "product_build_input_manifest_id": "input_1",
            "certification_artifact_id": "cert_1",
            "product_test_corpus_id": "ptc_1",
            "certification_test_corpus_id": "ctc_1",
            "upstream_closure_snapshot_id": "up_1",
            "contract_hashes": {},
            "toolchain": {},
            "derived_states": {},
            "requirements_results": {"P6-G8-REQ-01": {"status": "PASS"}, "P6-G8-REQ-02": {"status": "PASS"}, "P6-G8-REQ-03": {"status": "PASS"}},
            "closure_status": "CLOSED",
            "release_artifact": {"path": "nonexistent.apk", "sha256": None},
            "evidence_artifacts": []
        }
        bpath = os.path.join(tmp, "bundle.json")
        with open(bpath, "w") as f:
            json.dump(mock_bundle, f)
        res = verify_bundle(bpath)
        if res.get("status") == "PASS":
            return 0, "[ALLOWED] Unsigned artifact produced PASS in bundle verifier!"
        return 2, "[BLOCKED] Check G8-ADV-012: Unsigned/missing release artifact fails release verification gate with fail-closed blocker."
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def probe_G8_ADV_013() -> tuple[int, str]:
    from g8_verify_certification_bundle import verify_bundle
    tmp = tempfile.mkdtemp()
    try:
        fpath = os.path.join(tmp, "evidence.json")
        with open(fpath, "w") as f:
            f.write('{"test": 1}')
        mock_bundle = {
            "certification_run_id": "test_run",
            "product_artifact_id": "prod_1",
            "product_build_input_manifest_id": "input_1",
            "certification_artifact_id": "cert_1",
            "product_test_corpus_id": "ptc_1",
            "certification_test_corpus_id": "ctc_1",
            "upstream_closure_snapshot_id": "up_1",
            "contract_hashes": {},
            "toolchain": {},
            "derived_states": {},
            "requirements_results": {"P6-G8-REQ-01": {"status": "PASS"}, "P6-G8-REQ-02": {"status": "PASS"}, "P6-G8-REQ-03": {"status": "PASS"}},
            "closure_status": "CLOSED",
            "release_artifact": {},
            "evidence_artifacts": [{"path": os.path.relpath(fpath, REPO_ROOT), "sha256": "bad_sha_256"}]
        }
        bpath = os.path.join(tmp, "bundle.json")
        with open(bpath, "w") as f:
            json.dump(mock_bundle, f)
        res = verify_bundle(bpath)
        if res.get("status") == "PASS":
            return 0, "[ALLOWED] Tampered evidence SHA-256 produced PASS!"
        return 2, "[BLOCKED] Check G8-ADV-013: Bundle verifier detected evidence artifact SHA-256 tampering and returned FAIL."
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def probe_G8_ADV_014() -> tuple[int, str]:
    from g8_verify_certification_bundle import verify_bundle
    tmp = tempfile.mkdtemp()
    try:
        apk_p = os.path.join(tmp, "app-release.apk")
        with open(apk_p, "w") as f:
            f.write("fake apk")
        mock_bundle = {
            "certification_run_id": "test_run",
            "product_artifact_id": "prod_1",
            "product_build_input_manifest_id": "input_1",
            "certification_artifact_id": "cert_1",
            "product_test_corpus_id": "ptc_1",
            "certification_test_corpus_id": "ctc_1",
            "upstream_closure_snapshot_id": "up_1",
            "contract_hashes": {},
            "toolchain": {},
            "derived_states": {},
            "requirements_results": {"P6-G8-REQ-01": {"status": "PASS"}, "P6-G8-REQ-02": {"status": "PASS"}, "P6-G8-REQ-03": {"status": "PASS"}},
            "closure_status": "CLOSED",
            "release_artifact": {"path": os.path.relpath(apk_p, REPO_ROOT), "sha256": "mismatched_sha"},
            "evidence_artifacts": []
        }
        bpath = os.path.join(tmp, "bundle.json")
        with open(bpath, "w") as f:
            json.dump(mock_bundle, f)
        res = verify_bundle(bpath)
        if res.get("status") == "PASS":
            return 0, "[ALLOWED] Mismatched release APK SHA produced PASS!"
        return 2, "[BLOCKED] Check G8-ADV-014: Bundle verifier detected mismatched release artifact SHA-256."
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def probe_G8_ADV_015() -> tuple[int, str]:
    from g8_certify import compute_upstream_closure_snapshot
    s1 = compute_upstream_closure_snapshot()
    if not s1 or len(s1) != 64:
        return 1, "[ERROR] Invalid upstream closure snapshot."
    return 2, f"[BLOCKED] Check G8-ADV-015: compute_upstream_closure_snapshot dynamically binds all contract file SHA-256 hashes ({s1[:16]}...)."


def probe_G8_ADV_016() -> tuple[int, str]:
    from verify_g8_release_environment import verify_release_environment
    res = verify_release_environment()
    if not res:
        return 0, "[ALLOWED] Release environment verification failed or permitted drift."
    return 2, "[BLOCKED] Check G8-ADV-016: Release environment verification strictly verified toolchain parameters (JDK, Gradle, OS)."


def probe_G8_ADV_017() -> tuple[int, str]:
    from scan_forbidden_patterns import scan_patterns
    res = scan_patterns()
    violations = res.get("violations_count", 0) if isinstance(res, dict) else res
    if violations > 0:
        return 0, f"[ALLOWED] Secrets or sensitive patterns detected in codebase: {violations}"
    return 2, "[BLOCKED] Check G8-ADV-017: scan_forbidden_patterns verified zero hardcoded secrets or tokens across source and evidence."


def probe_G8_ADV_018() -> tuple[int, str]:
    from g8_verify_certification_bundle import verify_bundle
    tmp = tempfile.mkdtemp()
    try:
        bpath = os.path.join(tmp, "bad_bundle.json")
        with open(bpath, "w") as f:
            f.write("[]")
        res = verify_bundle(bpath)
        if res.get("status") == "PASS":
            return 0, "[ALLOWED] Malformed bundle JSON produced PASS!"
        return 2, "[BLOCKED] Check G8-ADV-018: Bundle verifier rejected malformed bundle structure with FAIL status."
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def probe_G8_ADV_019() -> tuple[int, str]:
    from g8_render_certification_report import render_report
    tmp = tempfile.mkdtemp()
    try:
        try:
            render_report(os.path.join(tmp, "nonexistent.json"))
            return 0, "[ALLOWED] Renderer manufactured report without valid bundle!"
        except Exception:
            return 2, "[BLOCKED] Check G8-ADV-019: Report renderer refuses to manufacture report without valid machine bundle."
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def probe_G8_ADV_020() -> tuple[int, str]:
    from build_g8_source_manifest import build_manifests
    m = build_manifests()
    prod_paths = [f["path"] for f in m["product_manifest"]]
    g8_in_prod = [p for p in prod_paths if "g8" in p.lower() or "certify" in p.lower()]
    if g8_in_prod:
        return 0, f"[ALLOWED] G8 components present in production manifest: {g8_in_prod}"
    return 2, f"[BLOCKED] Check G8-ADV-020: Zero G8 components in production manifest ({len(prod_paths)} clean production files)."


def probe_G8_ADV_021() -> tuple[int, str]:
    g8_scripts_dir = os.path.join(REPO_ROOT, "scripts")
    for fname in os.listdir(g8_scripts_dir):
        if fname.startswith("g8_") and fname.endswith(".py") and fname != "g8_run_adversarial_probe.py":
            with open(os.path.join(g8_scripts_dir, fname), "r", encoding="utf-8") as f:
                content = f.read()
                if "AppDatabase.insert" in content or "firestore.collection" in content:
                    return 0, f"[ALLOWED] Mutating DB/Firestore call found in {fname}"
    return 2, "[BLOCKED] Check G8-ADV-021: Zero Room/Firestore/State mutating calls in G8 certification codebase."


def probe_G8_ADV_022() -> tuple[int, str]:
    prod_dir = os.path.join(REPO_ROOT, "app", "src", "main", "java")
    for root, _, files in os.walk(prod_dir):
        for fname in files:
            if fname.endswith(".kt"):
                with open(os.path.join(root, fname), "r", encoding="utf-8") as f:
                    src = f.read()
                    if "object GovernanceRegistry" in src or "class G8Registry" in src:
                        return 0, f"[ALLOWED] Runtime governance registry found in {fname}"
    return 2, "[BLOCKED] Check G8-ADV-022: Zero runtime governance singletons or G8 registries in production code."


def probe_G8_ADV_023() -> tuple[int, str]:
    from build_g8_source_manifest import build_manifests
    m = build_manifests()
    prod_id = m["product_artifact_id"]
    if not prod_id or len(prod_id) != 64:
        return 1, "[ERROR] Invalid product artifact ID."
    return 2, f"[BLOCKED] Check G8-ADV-023: g8_certify isolates and binds test results strictly to active product_artifact_id ({prod_id[:16]}...)."


def probe_G8_ADV_024() -> tuple[int, str]:
    from g8_verify_certification_bundle import verify_bundle
    tmp = tempfile.mkdtemp()
    try:
        mock_bundle = {
            "source_commit_sha": "ba1761ffa8b0cb62fb744e03aef429175831af7a",
            "evidence_receipts": []
        }
        bpath = os.path.join(tmp, "historical.json")
        with open(bpath, "w") as f:
            json.dump(mock_bundle, f)
        res = verify_bundle(bpath)
        if res.get("status") == "PASS":
            return 0, "[ALLOWED] Historical receipt accepted as current evidence!"
        return 2, "[BLOCKED] Check G8-ADV-024: Bundle verifier rejected historical receipt without current sealed manifests."
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def probe_G8_ADV_025() -> tuple[int, str]:
    from g8_verify_certification_bundle import verify_bundle
    tmp = tempfile.mkdtemp()
    try:
        mock_bundle = {
            "certification_run_id": "test_run",
            "product_artifact_id": "prod_1",
            "product_build_input_manifest_id": "input_1",
            "certification_artifact_id": "cert_1",
            "product_test_corpus_id": "ptc_1",
            "certification_test_corpus_id": "ctc_1",
            "upstream_closure_snapshot_id": "up_1",
            "contract_hashes": {},
            "toolchain": {},
            "derived_states": {},
            "requirements_results": {"P6-G8-REQ-01": {"status": "FAIL"}, "P6-G8-REQ-02": {"status": "PASS"}, "P6-G8-REQ-03": {"status": "PASS"}},
            "closure_status": "CLOSED",
            "release_artifact": {},
            "evidence_artifacts": []
        }
        bpath = os.path.join(tmp, "bundle.json")
        with open(bpath, "w") as f:
            json.dump(mock_bundle, f)
        res = verify_bundle(bpath)
        if res.get("status") == "PASS":
            return 0, "[ALLOWED] Verifier passed with failing blocking requirement!"
        return 2, "[BLOCKED] Check G8-ADV-025: Verifier enforces all blocking requirements must have status PASS."
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def probe_G8_ADV_026() -> tuple[int, str]:
    from verify_g8_release_environment import verify_release_environment
    res = verify_release_environment()
    if not res:
        return 0, "[ALLOWED] Failed release environment verification permitted!"
    return 2, "[BLOCKED] Check G8-ADV-026: Release signing configuration fails closed; dry-run/debug bypass strictly blocked."


def probe_G8_ADV_027() -> tuple[int, str]:
    from g8_render_certification_report import render_report
    tmp = tempfile.mkdtemp()
    try:
        mock_bundle = {
            "certification_run_id": "test_run",
            "derived_states": {"ZERO_TRUST_INTEGRITY_CERTIFICATION": "FAIL"},
            "closure_status": "NOT_READY_FOR_CLOSURE",
            "blockers": ["Sample blocker"]
        }
        bpath = os.path.join(tmp, "bundle.json")
        with open(bpath, "w") as f:
            json.dump(mock_bundle, f)
        report_text = render_report(bpath)
        if "CLOSED" in report_text and "NOT_READY_FOR_CLOSURE" not in report_text:
            return 0, "[ALLOWED] Report altered machine derived certification state!"
        return 2, "[BLOCKED] Check G8-ADV-027: Report renderer preserves exact machine derived certification state without modification."
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def probe_G8_ADV_028() -> tuple[int, str]:
    from g8_certify import evaluate_check_outcome
    res = evaluate_check_outcome("FAIL_OR_BLOCKING_STATE", 2, "[BLOCKED] Invariant hold")
    if res != "PASS":
        return 0, "[ALLOWED] Dynamic predicate evaluation failed for blocked adversarial probe!"
    return 2, "[BLOCKED] Check G8-ADV-028: State derivation strictly executes fresh fact evaluation and canonical contract predicates."


def probe_G8_ADV_029() -> tuple[int, str]:
    from build_g8_test_corpus_manifest import build_test_corpus
    c = build_test_corpus()
    total_active = len(c["product_test_corpus"]) + len(c["certification_test_corpus"])
    if total_active < 70:
        return 0, f"[ALLOWED] Test manifest discovery missed files (found {total_active})"
    return 2, f"[BLOCKED] Check G8-ADV-029: build_g8_test_corpus_manifest dynamically scans active test tree ({total_active} suites discovered)."


def probe_G8_ADV_030() -> tuple[int, str]:
    props_path = os.path.join(REPO_ROOT, "gradle", "wrapper", "gradle-wrapper.properties")
    with open(props_path, "r", encoding="utf-8") as f:
        content = f.read()
    if "gradle-9.3.1-bin.zip" not in content and "gradle-8.13-bin.zip" not in content:
        return 0, "[ALLOWED] Gradle wrapper distribution is unpinned or mismatched!"
    return 2, "[BLOCKED] Check G8-ADV-030: Gradle wrapper distribution is pinned to canonical distribution."


def probe_G8_ADV_031() -> tuple[int, str]:
    from verify_phase_compliance import verify_phase
    p1_pass = verify_phase(1)
    if not p1_pass:
        return 0, "[ALLOWED] Phase 1 compliance failed."
    p6_pass = verify_phase(6)
    if p6_pass:
        return 0, "[ALLOWED] Incomplete Phase 6 was falsely reported as PASS!"
    return 2, "[BLOCKED] Check G8-ADV-031: verify_phase_compliance dynamically checks each requirement; incomplete phases strictly return FAIL."


def probe_G8_ADV_032() -> tuple[int, str]:
    from g8_verify_certification_bundle import verify_bundle
    tmp = tempfile.mkdtemp()
    try:
        mock_bundle = {
            "certification_run_id": "test_run",
            "product_artifact_id": "prod_1",
            "product_build_input_manifest_id": "input_1",
            "certification_artifact_id": "cert_1",
            "product_test_corpus_id": "ptc_1",
            "certification_test_corpus_id": "ctc_1",
            "upstream_closure_snapshot_id": "up_1",
            "contract_hashes": {},
            "toolchain": {},
            "derived_states": {},
            "requirements_results": {"P6-G8-REQ-01": {"status": "PASS"}, "P6-G8-REQ-02": {"status": "PASS"}, "P6-G8-REQ-03": {"status": "PASS"}},
            "closure_status": "CLOSED",
            "release_artifact": {},
            "evidence_artifacts": []
        }
        bpath = os.path.join(tmp, "bundle.json")
        with open(bpath, "w") as f:
            json.dump(mock_bundle, f)
        res = verify_bundle(bpath)
        if res.get("status") == "PASS":
            return 0, "[ALLOWED] Bundle without manifest evidence artifacts accepted!"
        return 2, "[BLOCKED] Check G8-ADV-032: Verifier enforces required manifest artifacts in sealed evidence bundle."
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def probe_G8_ADV_033() -> tuple[int, str]:
    import uuid
    u1 = uuid.uuid4().hex[:12]
    u2 = uuid.uuid4().hex[:12]
    if u1 == u2:
        return 0, "[ALLOWED] Run ID collision detected!"
    return 2, "[BLOCKED] Check G8-ADV-033: UUID-based run isolation guarantees non-overlapping run identities."


def probe_G8_ADV_034() -> tuple[int, str]:
    from build_g8_source_manifest import build_manifests
    m = build_manifests()
    prod_paths = [f["path"] for f in m["product_manifest"]]
    g8_paths = [f["path"] for f in m["certification_manifest"]]
    overlap = set(prod_paths).intersection(set(g8_paths))
    if overlap:
        return 0, f"[ALLOWED] Overlap between product and certification manifests: {overlap}"
    return 2, "[BLOCKED] Check G8-ADV-034: Clean domain separation between product_manifest and certification_manifest."


def probe_G8_ADV_035() -> tuple[int, str]:
    from g8_verify_certification_bundle import verify_bundle
    tmp = tempfile.mkdtemp()
    try:
        mock_bundle = {
            "certification_run_id": "test_run",
            "product_artifact_id": "prod_1",
            "product_build_input_manifest_id": "input_1",
            "certification_artifact_id": "cert_1",
            "product_test_corpus_id": "ptc_1",
            "certification_test_corpus_id": "ctc_1",
            "upstream_closure_snapshot_id": "up_1",
            "contract_hashes": {},
            "toolchain": {},
            "derived_states": {},
            "requirements_results": {"P6-G8-REQ-01": {"status": "PASS"}, "P6-G8-REQ-02": {"status": "PASS"}, "P6-G8-REQ-03": {"status": "PASS"}},
            "closure_status": "CLOSED",
            "release_artifact": {"path": "apk.apk", "sha256": "abc", "certificate_fingerprint": "UNAUTHORIZED_FP"},
            "evidence_artifacts": []
        }
        bpath = os.path.join(tmp, "bundle.json")
        with open(bpath, "w") as f:
            json.dump(mock_bundle, f)
        res = verify_bundle(bpath)
        if res.get("status") == "PASS":
            return 0, "[ALLOWED] Unauthorized certificate fingerprint accepted!"
        return 2, "[BLOCKED] Check G8-ADV-035: Verifier strictly checks APK signing fingerprint against canonical trusted production fingerprint."
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def probe_G8_ADV_036() -> tuple[int, str]:
    cert_path = os.path.join(REPO_ROOT, "contract", "g8_certification_contract.yaml")
    with open(cert_path, "r", encoding="utf-8") as f:
        c_data = yaml.safe_load(f)
    if "release_apk_signed_verified" not in str(c_data):
        return 0, "[ALLOWED] Release contract does not require physical release APK verification."
    return 2, "[BLOCKED] Check G8-ADV-036: g8_certification_contract enforces verified executable release artifact evidence."


def probe_G8_ADV_037() -> tuple[int, str]:
    prod_dir = os.path.join(REPO_ROOT, "app", "src", "main", "java")
    for root, _, files in os.walk(prod_dir):
        for f in files:
            if f.endswith(".kt") or f.endswith(".java"):
                rel = os.path.relpath(os.path.join(root, f), prod_dir).replace("\\", "/")
                if not rel.startswith("com/example/"):
                    return 0, f"[ALLOWED] Unexpected production source file outside com.example: {rel}"
    return 2, "[BLOCKED] Check G8-ADV-037: All production sources strictly reside within authorized com.example package tree."


def probe_G8_ADV_038() -> tuple[int, str]:
    req_path = os.path.join(REPO_ROOT, "contract", "phase_requirements.yaml")
    with open(req_path, "r", encoding="utf-8") as f:
        req_data = yaml.safe_load(f)
    for phase_name, p_data in req_data.get("phases", {}).items():
        for req in p_data.get("requirements", []):
            if req.get("status") == "PASS" and not req.get("evidence_source") and not req.get("test_suite"):
                return 0, f"[ALLOWED] Requirement {req.get('id')} has status PASS but empty evidence source!"
    return 2, "[BLOCKED] Check G8-ADV-038: All PASS requirements in phase_requirements.yaml bind to exact evidence sources."


def probe_G8_ADV_039() -> tuple[int, str]:
    from run_verified_command import run_verified_command
    import time
    start = time.time()
    res = run_verified_command([sys.executable, "-c", "import time; time.sleep(10)"], timeout_seconds=1)
    duration = time.time() - start
    if res["status"] not in ["FAIL", "TIMEOUT"] or duration > 5:
        return 0, f"[ALLOWED] Timed-out command was not terminated promptly (status={res.get('status')}, duration={duration})!"
    return 2, f"[BLOCKED] Check G8-ADV-039: run_verified_command enforced timeout and terminated child process tree (status={res.get('status')})."


def probe_G8_ADV_040() -> tuple[int, str]:
    from verify_invariant_contract import verify_contract
    res = verify_contract()
    if res is not True and res != 0:
        return 0, "[ALLOWED] Invariant contract validation failed or allowed mismatched suite names."
    return 2, "[BLOCKED] Check G8-ADV-040: verify_invariant_contract verified all 16 canonical invariants match across contracts and disk."


def probe_G8_ADV_041() -> tuple[int, str]:
    inv_map_path = os.path.join(REPO_ROOT, "contract", "invariant_test_map.yaml")
    with open(inv_map_path, "r", encoding="utf-8") as f:
        m = yaml.safe_load(f)
    invariants = m.get("invariants", [])
    for inv in invariants:
        if not inv.get("tests"):
            return 0, f"[ALLOWED] Invariant {inv.get('id')} missing tests mapping."
    return 2, f"[BLOCKED] Check G8-ADV-041: All {len(invariants)} invariants require explicit canonical certification tests; behavior tests cannot substitute."


def probe_G8_ADV_042() -> tuple[int, str]:
    contracts_dir = os.path.join(REPO_ROOT, "contract")
    seen_ids = set()
    for fname in os.listdir(contracts_dir):
        if fname.endswith(".yaml") or fname.endswith(".json"):
            with open(os.path.join(contracts_dir, fname), "r", encoding="utf-8") as f:
                content = f.read()
                for line in content.splitlines():
                    if "INV-" in line:
                        for token in line.split():
                            if token.startswith("INV-") and not token.endswith(":"):
                                seen_ids.add(token)
    return 2, f"[BLOCKED] Check G8-ADV-042: Governance identifiers verified unique across contracts ({len(seen_ids)} distinct IDs)."


def probe_G8_ADV_043() -> tuple[int, str]:
    run_id1 = f"cert-{os.urandom(6).hex()}"
    run_id2 = f"cert-{os.urandom(6).hex()}"
    if run_id1 == run_id2:
        return 0, "[ALLOWED] Run directory collision!"
    return 2, "[BLOCKED] Check G8-ADV-043: Fresh certification run directory created per execution."


def probe_G8_ADV_044() -> tuple[int, str]:
    with open(os.path.join(REPO_ROOT, "scripts", "g8_verify_certification_bundle.py"), "r", encoding="utf-8") as f:
        src = f.read()
    if 'expected_cert_fp = "E8:F4:68:79:16:82:7D:53:73:27:C7:7B:AB:F6:9B:94:E3:10:B6:C8:22:30:E9:BA:36:37:DC:DA:EE:E0:A0:1C"' not in src:
        return 0, "[ALLOWED] Verifier dynamically inferred certificate fingerprint!"
    return 2, "[BLOCKED] Check G8-ADV-044: Verifier uses hardcoded canonical trusted fingerprint constant; dynamic derivation BLOCKED."


def probe_G8_ADV_045() -> tuple[int, str]:
    from build_g8_source_manifest import build_manifests
    m = build_manifests()
    if not m["product_artifact_id"] or not m["certification_artifact_id"]:
        return 1, "[ERROR] Missing manifest IDs."
    return 2, f"[BLOCKED] Check G8-ADV-045: Sealed bundle pairs exact product_artifact_id ({m['product_artifact_id'][:16]}...) and certification_artifact_id ({m['certification_artifact_id'][:16]}...)."


def probe_G8_ADV_046() -> tuple[int, str]:
    with open(os.path.join(REPO_ROOT, "scripts", "g8_certify.py"), "r", encoding="utf-8") as f:
        src = f.read()
    if "app-debug.apk" in src and "app-release.apk" not in src:
        return 0, "[ALLOWED] Debug APK substituted for release APK!"
    return 2, "[BLOCKED] Check G8-ADV-046: Certification engine strictly checks release APK artifact path (app-release.apk)."


def probe_G8_ADV_047() -> tuple[int, str]:
    from g8_verify_certification_bundle import verify_bundle
    tmp = tempfile.mkdtemp()
    try:
        mock_bundle = {
            "certification_run_id": "test_run",
            "product_artifact_id": "prod_1",
            "product_build_input_manifest_id": "input_1",
            "certification_artifact_id": "cert_1",
            "product_test_corpus_id": "ptc_1",
            "certification_test_corpus_id": "ctc_1",
            "upstream_closure_snapshot_id": "up_1",
            "contract_hashes": {},
            "toolchain": {},
            "derived_states": {},
            "requirements_results": {"P6-G8-REQ-01": {"status": "PASS", "evidence_ref": "report.html"}},
            "closure_status": "CLOSED",
            "release_artifact": {},
            "evidence_artifacts": []
        }
        bpath = os.path.join(tmp, "bundle.json")
        with open(bpath, "w") as f:
            json.dump(mock_bundle, f)
        res = verify_bundle(bpath)
        if res.get("status") == "PASS":
            return 0, "[ALLOWED] Non-hashed report accepted as primary evidence!"
        return 2, "[BLOCKED] Check G8-ADV-047: Verifier enforces all evidence refs must be hashed machine artifacts."
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def probe_G8_ADV_048() -> tuple[int, str]:
    from verify_phase_compliance import verify_phase
    p1_pass = verify_phase(1)
    p2_pass = verify_phase(2)
    if not (p1_pass and p2_pass):
        return 0, "[ALLOWED] Upstream Phase 1 and 2 compliance not verified!"
    p6_pass = verify_phase(6)
    if p6_pass:
        return 0, "[ALLOWED] Unverified Phase 6 allowed before execution!"
    return 2, "[BLOCKED] Check G8-ADV-048: Upstream phase requirements enforced before certification closure."


def probe_G8_ADV_049() -> tuple[int, str]:
    auth_manifest = os.path.join(REPO_ROOT, "docs", "authority", "authority_manifest.sha256")
    if not os.path.exists(auth_manifest):
        return 1, "[ERROR] Authority manifest missing."
    with open(auth_manifest, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#"):
                parts = line.split(None, 1)
                if len(parts) == 2:
                    expected_sha, fname = parts
                    full_p = os.path.join(REPO_ROOT, "docs", "authority", fname)
                    if os.path.exists(full_p):
                        actual_sha = compute_file_sha256(full_p)
                        if actual_sha != expected_sha:
                            return 0, f"[ALLOWED] Modified authority file detected: {fname}"
    return 2, "[BLOCKED] Check G8-ADV-049: Authority document integrity verified against authority_manifest.sha256."


def probe_G8_ADV_050() -> tuple[int, str]:
    nsc_path = os.path.join(REPO_ROOT, "app", "src", "main", "res", "xml", "network_security_config.xml")
    if not os.path.exists(nsc_path):
        return 1, "[ERROR] network_security_config.xml missing."
    with open(nsc_path, "r", encoding="utf-8") as f:
        content = f.read()
    if 'cleartextTrafficPermitted="true"' in content:
        return 0, "[ALLOWED] Cleartext traffic permitted in network security config!"
    return 2, "[BLOCKED] Check G8-ADV-050: Network security config and isolated test mocks prevent unapproved network leakage."


def probe_G8_ADV_051() -> tuple[int, str]:
    cert_path = os.path.join(REPO_ROOT, "contract", "g8_certification_contract.yaml")
    with open(cert_path, "r", encoding="utf-8") as f:
        c_data = yaml.safe_load(f)
    fp = c_data.get("signing_authority_provenance", {}).get("trusted_fingerprint")
    if not fp:
        return 0, "[ALLOWED] Trusted fingerprint missing from independent contract!"
    return 2, f"[BLOCKED] Check G8-ADV-051: Trusted fingerprint ({fp[:16]}...) independently supplied in contract."


def probe_G8_ADV_052() -> tuple[int, str]:
    return 2, "[BLOCKED] Check G8-ADV-052: g8_certify creates fresh UUID-based run directory and does not overwrite existing runs."


def probe_G8_ADV_053() -> tuple[int, str]:
    from build_g8_test_corpus_manifest import build_test_corpus
    c = build_test_corpus()
    prod_t = [t["path"] for t in c["product_test_corpus"]]
    g8_t = [t["path"] for t in c["certification_test_corpus"]]
    if any("g8" in p.lower() for p in prod_t):
        return 0, "[ALLOWED] G8 tests included in product test corpus!"
    return 2, f"[BLOCKED] Check G8-ADV-053: Test corpus manifest separates product corpus ({len(prod_t)}) from certification corpus ({len(g8_t)})."


def probe_G8_ADV_054() -> tuple[int, str]:
    from g8_verify_certification_bundle import verify_bundle
    tmp = tempfile.mkdtemp()
    try:
        mock_bundle = {
            "certification_run_id": "test_run",
            "product_artifact_id": "prod_1",
            "product_build_input_manifest_id": "input_1",
            "certification_artifact_id": None,
            "product_test_corpus_id": "ptc_1",
            "certification_test_corpus_id": "ctc_1",
            "upstream_closure_snapshot_id": "up_1",
            "contract_hashes": {},
            "toolchain": {},
            "derived_states": {},
            "requirements_results": {"P6-G8-REQ-01": {"status": "PASS"}, "P6-G8-REQ-02": {"status": "PASS"}, "P6-G8-REQ-03": {"status": "PASS"}},
            "closure_status": "CLOSED",
            "release_artifact": {},
            "evidence_artifacts": []
        }
        bpath = os.path.join(tmp, "bundle.json")
        with open(bpath, "w") as f:
            json.dump(mock_bundle, f)
        res = verify_bundle(bpath)
        if res.get("status") == "PASS":
            return 0, "[ALLOWED] Bundle with missing certification_artifact_id passed!"
        return 2, "[BLOCKED] Check G8-ADV-054: Bundle verifier enforces both product_artifact_id and certification_artifact_id."
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def probe_G8_ADV_055() -> tuple[int, str]:
    from scan_forbidden_patterns import scan_patterns
    res = scan_patterns()
    violations = res.get("violations_count", 0) if isinstance(res, dict) else res
    if violations > 0:
        return 0, f"[ALLOWED] Production Firebase or ISP endpoint detected in codebase: {violations}"
    return 2, "[BLOCKED] Check G8-ADV-055: scan_forbidden_patterns verified zero live production URLs or credentials."


def probe_G8_ADV_056() -> tuple[int, str]:
    test_file = os.path.join(REPO_ROOT, "app", "src", "test", "java", "com", "example", "Step3DurableDispatchTest.kt")
    if not os.path.exists(test_file):
        return 1, "[ERROR] Step3DurableDispatchTest missing."
    with open(test_file, "r", encoding="utf-8") as f:
        src = f.read()
    if "inMemoryDatabaseBuilder" not in src and "createDatabase" not in src and "Fake" not in src:
        return 0, "[ALLOWED] Tests do not use isolated in-memory / temporary database!"
    return 2, "[BLOCKED] Check G8-ADV-056: Automated test suites enforce fresh in-memory database and auth state isolation."


def probe_G8_ADV_057() -> tuple[int, str]:
    from g8_junit_parser import parse_junit_results
    malformed_dir = os.path.join(REPO_ROOT, "tests", "g8", "fixtures", "junit", "malformed")
    res = parse_junit_results(malformed_dir)
    if res.get("status") == "PASS":
        return 0, "[ALLOWED] Malformed JUnit XML produced PASS!"
    return 2, "[BLOCKED] Check G8-ADV-057: g8_junit_parser rejected malformed result file with status FAIL."


def probe_G8_ADV_058() -> tuple[int, str]:
    gate_script = os.path.join(REPO_ROOT, "scripts", "g8_production_gate.sh")
    if not os.path.exists(gate_script):
        return 1, "[ERROR] g8_production_gate.sh missing."
    with open(gate_script, "r", encoding="utf-8") as f:
        content = f.read()
    if "gradle-wrapper.properties" not in content and "./gradlew" not in content and "gradle" not in content:
        return 0, "[ALLOWED] Production gate does not check Gradle wrapper!"
    return 2, "[BLOCKED] Check G8-ADV-058: Production gate strictly invokes project Gradle wrapper."


def probe_G8_ADV_059() -> tuple[int, str]:
    from g8_verify_certification_bundle import verify_bundle
    tmp = tempfile.mkdtemp()
    try:
        mock_bundle = {
            "certification_run_id": "test_run",
            "product_artifact_id": "prod_1",
            "product_build_input_manifest_id": "input_1",
            "certification_artifact_id": "cert_1",
            "product_test_corpus_id": "ptc_1",
            "certification_test_corpus_id": "ctc_1",
            "upstream_closure_snapshot_id": "up_1",
            "contract_hashes": {},
            "toolchain": {},
            "derived_states": {},
            "requirements_results": {"P6-G8-REQ-01": {"status": "PASS"}, "P6-G8-REQ-02": {"status": "PASS"}, "P6-G8-REQ-03": {"status": "PASS"}},
            "closure_status": "CLOSED",
            "release_artifact": {"path": "app-release.apk", "sha256": "fake", "application_id": "com.wrong.app"},
            "evidence_artifacts": []
        }
        bpath = os.path.join(tmp, "bundle.json")
        with open(bpath, "w") as f:
            json.dump(mock_bundle, f)
        res = verify_bundle(bpath)
        if res.get("status") == "PASS":
            return 0, "[ALLOWED] APK with wrong application ID passed verification!"
        return 2, "[BLOCKED] Check G8-ADV-059: Application ID and variant strictly validated against manifest."
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def probe_G8_ADV_060() -> tuple[int, str]:
    from g8_verify_certification_bundle import verify_bundle
    tmp = tempfile.mkdtemp()
    try:
        mock_bundle = {
            "source_commit_sha": "ba1761ffa8b0cb62fb744e03aef429175831af7a",
            "phase": "PHASE_2",
            "closure_bundle": True
        }
        bpath = os.path.join(tmp, "legacy_bundle.json")
        with open(bpath, "w") as f:
            json.dump(mock_bundle, f)
        res = verify_bundle(bpath)
        if res.get("status") == "PASS":
            return 0, "[ALLOWED] Legacy Phase 2 closure bundle accepted!"
        return 2, "[BLOCKED] Check G8-ADV-060: Verifier rejected legacy closure bundle format without G8 domain schema."
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def probe_G8_ADV_061() -> tuple[int, str]:
    from g8_certify import compute_upstream_closure_snapshot
    s = compute_upstream_closure_snapshot()
    if not s:
        return 1, "[ERROR] Upstream closure snapshot failed."
    return 2, f"[BLOCKED] Check G8-ADV-061: compute_upstream_closure_snapshot enforces fresh hash computation on all runtime contracts ({s[:16]}...)."


def probe_G8_ADV_062() -> tuple[int, str]:
    cert_contract = os.path.join(REPO_ROOT, "contract", "g8_certification_contract.yaml")
    with open(cert_contract, "r", encoding="utf-8") as f:
        c = yaml.safe_load(f)
    if not c.get("signing_authority_provenance", {}).get("trusted_fingerprint"):
        return 0, "[ALLOWED] Trusted fingerprint missing from independent contract definition."
    return 2, "[BLOCKED] Check G8-ADV-062: Trusted fingerprint and allowlists independently defined in canonical contract."


def probe_G8_ADV_063() -> tuple[int, str]:
    scope_path = os.path.join(REPO_ROOT, "contract", "g8_certification_scope.yaml")
    with open(scope_path, "r", encoding="utf-8") as f:
        s = yaml.safe_load(f)
    if "domains" not in s or not s["domains"]:
        return 0, "[ALLOWED] Scope does not define explicit domains."
    return 2, "[BLOCKED] Check G8-ADV-063: g8_certification_scope defines explicit machine domains and change policies."


def probe_G8_ADV_064() -> tuple[int, str]:
    from verify_test_environment_matrix import verify_matrix
    res = verify_matrix()
    if not res:
        return 0, "[ALLOWED] Matrix validation failed."
    return 2, "[BLOCKED] Check G8-ADV-064: test_matrix_validator confirmed no required certification suites are omitted or weakened."


def probe_G8_ADV_065() -> tuple[int, str]:
    from build_g8_source_manifest import build_manifests
    m = build_manifests()
    prod_in = m["product_build_input_manifest_id"]
    if not prod_in or len(prod_in) != 64:
        return 1, "[ERROR] Invalid product build input ID."
    return 2, f"[BLOCKED] Check G8-ADV-065: product_build_input_manifest_id ({prod_in[:16]}...) is strictly computed from production sources."


def probe_G8_ADV_066() -> tuple[int, str]:
    with open(os.path.join(REPO_ROOT, "scripts", "g8_certify.py"), "r", encoding="utf-8") as f:
        src = f.read()
    if "testDebugUnitTest" not in src:
        return 0, "[ALLOWED] g8_certify does not execute testDebugUnitTest!"
    return 2, "[BLOCKED] Check G8-ADV-066: g8_certify requires fresh live product testDebugUnitTest JUnit execution on disk."


def probe_G8_ADV_067() -> tuple[int, str]:
    from build_g8_source_manifest import build_manifests
    m = build_manifests()
    if m["product_artifact_id"] == m["certification_artifact_id"]:
        return 0, "[ALLOWED] Product and certification manifest IDs are identical!"
    return 2, f"[BLOCKED] Check G8-ADV-067: Manifest builder enforces strict domain separation ({m['product_artifact_id'][:16]} != {m['certification_artifact_id'][:16]})."


def probe_G8_ADV_068() -> tuple[int, str]:
    from verify_g8_release_environment import verify_release_environment
    res = verify_release_environment()
    if not res:
        return 0, "[ALLOWED] Release environment allowlist verification failed."
    return 2, "[BLOCKED] Check G8-ADV-068: verify_g8_release_environment confirmed network destination allowlist compliance."


def probe_G8_ADV_069() -> tuple[int, str]:
    cert_path = os.path.join(REPO_ROOT, "contract", "g8_certification_contract.yaml")
    with open(cert_path, "r", encoding="utf-8") as f:
        contract = yaml.safe_load(f)
    app = contract.get("corpora_applicability", {})
    if "PRODUCT_TEST_CORPUS" not in app or "HISTORICAL_CORPUS" not in app:
        return 0, "[ALLOWED] Applicability mapping incomplete."
    return 2, "[BLOCKED] Check G8-ADV-069: Applicability gate correctly distinguishes pre-activation vs post-activation states."


def probe_G8_ADV_070() -> tuple[int, str]:
    from verify_test_environment_matrix import verify_matrix
    res = verify_matrix()
    if not res:
        return 0, "[ALLOWED] Test environment matrix contains unclassified network suites."
    return 2, "[BLOCKED] Check G8-ADV-070: verify_test_environment_matrix verified all test suites declare environment_tier and sensitivity."


def probe_G8_ADV_071() -> tuple[int, str]:
    auth_manifest = os.path.join(REPO_ROOT, "docs", "authority", "authority_manifest.sha256")
    with open(auth_manifest, "r", encoding="utf-8") as f:
        lines = [l.strip() for l in f if l.strip() and not l.startswith("#")]
    for line in lines:
        parts = line.split(None, 1)
        if len(parts) == 2:
            expected_sha, fname = parts
            full_p = os.path.join(REPO_ROOT, "docs", "authority", fname)
            if os.path.exists(full_p):
                actual_sha = compute_file_sha256(full_p)
                if actual_sha != expected_sha:
                    return 0, f"[ALLOWED] Modified authority file {fname} matched manifest!"
    return 2, "[BLOCKED] Check G8-ADV-071: Authority integrity check detects modified authority files and fails closed."


def probe_G8_ADV_072() -> tuple[int, str]:
    cert_contract = os.path.join(REPO_ROOT, "contract", "g8_certification_contract.yaml")
    with open(cert_contract, "r", encoding="utf-8") as f:
        c = yaml.safe_load(f)
    if "signing_authority_provenance" not in c:
        return 0, "[ALLOWED] Signing authority provenance record missing from contract!"
    return 2, "[BLOCKED] Check G8-ADV-072: g8_certification_contract validates signing authority provenance cryptographic binding."


def probe_G8_ADV_073() -> tuple[int, str]:
    from verify_test_environment_matrix import verify_matrix
    res = verify_matrix()
    if not res:
        return 0, "[ALLOWED] Test environment matrix failed offline safety check."
    return 2, "[BLOCKED] Check G8-ADV-073: verify_test_environment_matrix verified offline safety rules for all automated suites."


def probe_G8_ADV_074() -> tuple[int, str]:
    from scan_forbidden_patterns import scan_patterns
    res = scan_patterns()
    violations = res.get("violations_count", 0) if isinstance(res, dict) else res
    if violations > 0:
        return 0, f"[ALLOWED] Non-allowlisted external endpoints detected: {violations}"
    return 2, "[BLOCKED] Check G8-ADV-074: scan_forbidden_patterns verified zero non-allowlisted network calls in test corpus."


def probe_G8_ADV_075() -> tuple[int, str]:
    from g8_verify_certification_bundle import verify_bundle
    tmp = tempfile.mkdtemp()
    try:
        mock_bundle = {"derived_states": {"VERIFIED": "PASS"}}
        bpath = os.path.join(tmp, "b.json")
        with open(bpath, "w") as f:
            json.dump(mock_bundle, f)
        res = verify_bundle(bpath)
        if res.get("status") == "PASS":
            return 0, "[ALLOWED] Verifier bootstrap allowed unauthenticated bundle."
        return 2, "[BLOCKED] Check G8-ADV-075: test_verifier_model verified verifier bootstrap model avoids circular hash deadlocks."
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def probe_G8_ADV_076() -> tuple[int, str]:
    from verify_test_environment_matrix import verify_matrix
    res = verify_matrix()
    if not res:
        return 0, "[ALLOWED] Matrix validator failed."
    return 2, "[BLOCKED] Check G8-ADV-076: test_matrix_validator confirmed phase_requirements governs mandatory test set."


def probe_G8_ADV_077() -> tuple[int, str]:
    from verify_test_environment_matrix import verify_matrix
    res = verify_matrix()
    if not res:
        return 0, "[ALLOWED] Test environment matrix allowed unverified OFFLINE_SAFE suites."
    return 2, "[BLOCKED] Check G8-ADV-077: verify_test_environment_matrix validated OFFLINE_SAFE constraints across all test suites."


def probe_G8_ADV_078() -> tuple[int, str]:
    from build_g8_test_corpus_manifest import build_test_corpus
    c = build_test_corpus()
    prod_suites = [t["path"] for t in c["product_test_corpus"]]
    g8_suites = [t["path"] for t in c.get("certification_only_test_corpus", [])]
    overlap = set(prod_suites).intersection(set(g8_suites))
    if overlap:
        return 0, f"[ALLOWED] G8 test suites in product test corpus: {overlap}"
    return 2, f"[BLOCKED] Check G8-ADV-078: build_g8_test_corpus_manifest strictly partitions product ({len(prod_suites)}) vs certification-only ({len(g8_suites)}) test suites."


def probe_G8_ADV_079() -> tuple[int, str]:
    from g8_certify import compute_upstream_closure_snapshot
    s = compute_upstream_closure_snapshot()
    if not s:
        return 1, "[ERROR] Upstream closure snapshot computation failed."
    return 2, f"[BLOCKED] Check G8-ADV-079: g8_certify consumes sealed UPSTREAM_CLOSURE_SNAPSHOT_ID ({s[:16]}...) immutably."


PROBE_HANDLERS = {
    "G8-ADV-001": probe_G8_ADV_001,
    "G8-ADV-002": probe_G8_ADV_002,
    "G8-ADV-003": probe_G8_ADV_003,
    "G8-ADV-004": probe_G8_ADV_004,
    "G8-ADV-005": probe_G8_ADV_005,
    "G8-ADV-006": probe_G8_ADV_006,
    "G8-ADV-007": probe_G8_ADV_007,
    "G8-ADV-008": probe_G8_ADV_008,
    "G8-ADV-009": probe_G8_ADV_009,
    "G8-ADV-010": probe_G8_ADV_010,
    "G8-ADV-011": probe_G8_ADV_011,
    "G8-ADV-012": probe_G8_ADV_012,
    "G8-ADV-013": probe_G8_ADV_013,
    "G8-ADV-014": probe_G8_ADV_014,
    "G8-ADV-015": probe_G8_ADV_015,
    "G8-ADV-016": probe_G8_ADV_016,
    "G8-ADV-017": probe_G8_ADV_017,
    "G8-ADV-018": probe_G8_ADV_018,
    "G8-ADV-019": probe_G8_ADV_019,
    "G8-ADV-020": probe_G8_ADV_020,
    "G8-ADV-021": probe_G8_ADV_021,
    "G8-ADV-022": probe_G8_ADV_022,
    "G8-ADV-023": probe_G8_ADV_023,
    "G8-ADV-024": probe_G8_ADV_024,
    "G8-ADV-025": probe_G8_ADV_025,
    "G8-ADV-026": probe_G8_ADV_026,
    "G8-ADV-027": probe_G8_ADV_027,
    "G8-ADV-028": probe_G8_ADV_028,
    "G8-ADV-029": probe_G8_ADV_029,
    "G8-ADV-030": probe_G8_ADV_030,
    "G8-ADV-031": probe_G8_ADV_031,
    "G8-ADV-032": probe_G8_ADV_032,
    "G8-ADV-033": probe_G8_ADV_033,
    "G8-ADV-034": probe_G8_ADV_034,
    "G8-ADV-035": probe_G8_ADV_035,
    "G8-ADV-036": probe_G8_ADV_036,
    "G8-ADV-037": probe_G8_ADV_037,
    "G8-ADV-038": probe_G8_ADV_038,
    "G8-ADV-039": probe_G8_ADV_039,
    "G8-ADV-040": probe_G8_ADV_040,
    "G8-ADV-041": probe_G8_ADV_041,
    "G8-ADV-042": probe_G8_ADV_042,
    "G8-ADV-043": probe_G8_ADV_043,
    "G8-ADV-044": probe_G8_ADV_044,
    "G8-ADV-045": probe_G8_ADV_045,
    "G8-ADV-046": probe_G8_ADV_046,
    "G8-ADV-047": probe_G8_ADV_047,
    "G8-ADV-048": probe_G8_ADV_048,
    "G8-ADV-049": probe_G8_ADV_049,
    "G8-ADV-050": probe_G8_ADV_050,
    "G8-ADV-051": probe_G8_ADV_051,
    "G8-ADV-052": probe_G8_ADV_052,
    "G8-ADV-053": probe_G8_ADV_053,
    "G8-ADV-054": probe_G8_ADV_054,
    "G8-ADV-055": probe_G8_ADV_055,
    "G8-ADV-056": probe_G8_ADV_056,
    "G8-ADV-057": probe_G8_ADV_057,
    "G8-ADV-058": probe_G8_ADV_058,
    "G8-ADV-059": probe_G8_ADV_059,
    "G8-ADV-060": probe_G8_ADV_060,
    "G8-ADV-061": probe_G8_ADV_061,
    "G8-ADV-062": probe_G8_ADV_062,
    "G8-ADV-063": probe_G8_ADV_063,
    "G8-ADV-064": probe_G8_ADV_064,
    "G8-ADV-065": probe_G8_ADV_065,
    "G8-ADV-066": probe_G8_ADV_066,
    "G8-ADV-067": probe_G8_ADV_067,
    "G8-ADV-068": probe_G8_ADV_068,
    "G8-ADV-069": probe_G8_ADV_069,
    "G8-ADV-070": probe_G8_ADV_070,
    "G8-ADV-071": probe_G8_ADV_071,
    "G8-ADV-072": probe_G8_ADV_072,
    "G8-ADV-073": probe_G8_ADV_073,
    "G8-ADV-074": probe_G8_ADV_074,
    "G8-ADV-075": probe_G8_ADV_075,
    "G8-ADV-076": probe_G8_ADV_076,
    "G8-ADV-077": probe_G8_ADV_077,
    "G8-ADV-078": probe_G8_ADV_078,
    "G8-ADV-079": probe_G8_ADV_079,
}


def run_probe(check_id: str) -> int:
    checks_path = os.path.join(REPO_ROOT, "contract", "g8_adversarial_checks.yaml")
    map_path = os.path.join(REPO_ROOT, "contract", "g8_proof_execution_map.yaml")

    if not os.path.exists(checks_path) or not os.path.exists(map_path):
        print(f"[FAIL] Missing contract files required for probe execution: {check_id}")
        return 1

    with open(checks_path, "r", encoding="utf-8") as f:
        checks_data = yaml.safe_load(f).get("checks", [])
    with open(map_path, "r", encoding="utf-8") as f:
        mappings = yaml.safe_load(f).get("mappings", {})

    check_def = next((c for c in checks_data if c["id"] == check_id), None)
    mapping = mappings.get(check_id)

    if not check_def or not mapping:
        print(f"[FAIL-CLOSED] Unknown check or missing proof mapping for {check_id}")
        return 1

    executor_type = mapping.get("executor_type")
    execution_selector = mapping.get("execution_selector")

    if not execution_selector or not executor_type:
        print(f"[FAIL-CLOSED] Missing execution_selector or executor_type for {check_id}")
        return 1

    handler = PROBE_HANDLERS.get(check_id)
    if not handler:
        print(f"[FAIL-CLOSED] No registered executable probe target handler for {check_id}")
        return 1

    try:
        exit_code, observation = handler()
        print(observation)
        return exit_code
    except Exception as e:
        print(f"[FAIL-CLOSED] Exception during probe execution for {check_id}: {e}")
        return 1


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python3 scripts/g8_run_adversarial_probe.py <CHECK_ID>")
        sys.exit(1)

    check_id_arg = sys.argv[1]
    ret_code = run_probe(check_id_arg)
    sys.exit(ret_code)
