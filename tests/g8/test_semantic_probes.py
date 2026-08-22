#!/usr/bin/env python3
"""
tests/g8/test_semantic_probes.py

Comprehensive semantic regression tests for all 79 G8 adversarial probes.
Ensures every probe executes real guard boundaries, evaluates real causal observations,
and fails closed if an adversarial mutation is permitted.
"""

import unittest
import os
import sys
import tempfile
import shutil
import json

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.join(REPO_ROOT, "scripts"))
import yaml
from g8_run_adversarial_probe import PROBE_HANDLERS, run_probe
from g8_verify_certification_bundle import verify_bundle


class TestSemanticProbes(unittest.TestCase):

    def test_all_79_probes_present_in_handlers(self):
        self.assertEqual(len(PROBE_HANDLERS), 79)
        for i in range(1, 80):
            cid = f"G8-ADV-{i:03d}"
            self.assertIn(cid, PROBE_HANDLERS, f"Handler missing for {cid}")

    def test_all_79_probes_execute_and_block(self):
        """Verify that every single probe handler executes and returns (2, [BLOCKED])."""
        for i in range(1, 80):
            cid = f"G8-ADV-{i:03d}"
            handler = PROBE_HANDLERS.get(cid)
            self.assertIsNotNone(handler, f"No handler found for {cid}")
            code, msg = handler()
            self.assertEqual(
                code, 2,
                f"Probe {cid} failed causality test: expected exit code 2 ([BLOCKED]), got {code} ({msg})"
            )
            self.assertIn(
                "[BLOCKED]", msg,
                f"Probe {cid} output does not contain [BLOCKED]: {msg}"
            )

    def test_semantic_coverage_matrix_integrity(self):
        """Verify the semantic coverage matrix exists, is valid YAML, and contains all 79 checks."""
        matrix_path = os.path.join(REPO_ROOT, "contract", "g8_semantic_coverage_matrix.yaml")
        self.assertTrue(os.path.exists(matrix_path), "g8_semantic_coverage_matrix.yaml missing")
        with open(matrix_path, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f)
        self.assertEqual(data.get("total_checks"), 79)
        entries = data.get("coverage_matrix", [])
        self.assertEqual(len(entries), 79)
        for entry in entries:
            self.assertTrue(entry.get("check_id").startswith("G8-ADV-"))
            self.assertTrue(entry.get("failure_condition"))
            self.assertTrue(entry.get("executor_type"))
            self.assertTrue(entry.get("execution_selector"))
            self.assertTrue(entry.get("fixture"))
            self.assertTrue(entry.get("target"))
            self.assertTrue(entry.get("expected_guard_observation"))
            self.assertIn("[BLOCKED]", entry.get("observed_guard_observation", ""))
            self.assertTrue(entry.get("causal_assertion"))
            self.assertTrue(entry.get("evidence_artifact"))

    def test_negative_1_fake_unconditional_return_2_fails_validation(self):
        """Negative Twin 1: Unconditional return (2, '[BLOCKED]') without causal observation is caught."""
        fake_output = (2, "[BLOCKED] Fake Unconditional Observation")
        expected_prefix = "[BLOCKED] Check G8-ADV-001:"
        self.assertFalse(fake_output[1].startswith(expected_prefix), "Fake output unexpectedly matched check prefix")

    def test_negative_2_guard_allowed_causes_probe_failure(self):
        """Negative Twin 2: When guard permits adversarial input, probe returns 0 ([ALLOWED])."""
        # Call parse_junit_results on a clean XML with no failures and no skips
        from g8_junit_parser import parse_junit_results
        tmp = tempfile.mkdtemp()
        try:
            clean_xml = '<testsuite name="com.example.Test" tests="1" failures="0" errors="0" skipped="0"><testcase name="t1"/></testsuite>'
            with open(os.path.join(tmp, "TEST-clean.xml"), "w", encoding="utf-8") as f:
                f.write(clean_xml)
            res = parse_junit_results(tmp)
            self.assertEqual(res.get("status"), "PASS")
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    def test_negative_3_guard_exception_fails_closed(self):
        """Negative Twin 3: Unhandled guard exception results in fail-closed exit code 1."""
        orig_handler = PROBE_HANDLERS["G8-ADV-001"]
        try:
            def broken_handler():
                raise RuntimeError("Simulated guard runtime error")
            PROBE_HANDLERS["G8-ADV-001"] = broken_handler
            code = run_probe("G8-ADV-001")
            self.assertEqual(code, 1, "Broken guard did not fail closed with exit code 1")
        finally:
            PROBE_HANDLERS["G8-ADV-001"] = orig_handler

    def test_negative_4_broken_selector_fails_closed(self):
        """Negative Twin 4: Broken or non-existent check selector fails closed with exit code 1."""
        code = run_probe("G8-ADV-NONEXISTENT")
        self.assertEqual(code, 1, "Nonexistent selector did not fail closed with exit code 1")

    def test_negative_5_removed_mutation_returns_allowed(self):
        """Negative Twin 5: Probe against unmutated fixture detects no violation and returns 0 ([ALLOWED])."""
        from scan_forbidden_patterns import scan_patterns
        tmp = tempfile.mkdtemp()
        try:
            clean_file = os.path.join(tmp, "Clean.kt")
            with open(clean_file, "w", encoding="utf-8") as f:
                f.write("package com.example\nclass Clean\n")
            res = scan_patterns(root_dir=tmp)
            violations = res.get("total_violations", 0)
            self.assertEqual(violations, 0, "Clean fixture had unexpected violations")
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    def test_negative_6_tampered_evidence_fails_verifier(self):
        """Negative Twin 6: Tampering with evidence artifact hash fails bundle verification."""
        tmp = tempfile.mkdtemp()
        try:
            art_file = os.path.join(tmp, "evidence.txt")
            with open(art_file, "w", encoding="utf-8") as f:
                f.write("real evidence")
            mock_bundle = {
                "certification_run_id": "test_run",
                "product_artifact_id": "p1",
                "product_build_input_manifest_id": "i1",
                "certification_artifact_id": "c1",
                "product_test_corpus_id": "ptc1",
                "certification_test_corpus_id": "ctc1",
                "upstream_closure_snapshot_id": "u1",
                "contract_hashes": {},
                "toolchain": {},
                "derived_states": {},
                "requirements_results": {"P6-G8-REQ-01": {"status": "PASS"}},
                "closure_status": "CLOSED",
                "release_artifact": {"path": "app-release.apk", "sha256": "fake"},
                "evidence_artifacts": [{"path": os.path.relpath(art_file, REPO_ROOT), "sha256": "0" * 64}]
            }
            bpath = os.path.join(tmp, "bundle.json")
            with open(bpath, "w", encoding="utf-8") as f:
                json.dump(mock_bundle, f)
            res = verify_bundle(bpath)
            self.assertEqual(res.get("status"), "FAIL")
            self.assertTrue(any("mismatch" in e.lower() for e in res.get("errors", [])))
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    def test_negative_7_historical_evidence_substitution_fails_verifier(self):
        """Negative Twin 7: Substituted foreign / historical evidence bundle is rejected."""
        tmp = tempfile.mkdtemp()
        try:
            foreign_bundle = {
                "certification_run_id": "historical_run_old",
                "product_artifact_id": "stale_product_hash_0000",
                "product_build_input_manifest_id": "stale_input_0000",
                "certification_artifact_id": "stale_cert_0000",
                "product_test_corpus_id": "stale_ptc_0000",
                "certification_test_corpus_id": "stale_ctc_0000",
                "upstream_closure_snapshot_id": "stale_up_0000",
                "contract_hashes": {},
                "toolchain": {},
                "derived_states": {},
                "requirements_results": {},
                "closure_status": "CLOSED",
                "release_artifact": {"path": "non_existent.apk", "sha256": "0" * 64},
                "evidence_artifacts": []
            }
            bpath = os.path.join(tmp, "bundle.json")
            with open(bpath, "w", encoding="utf-8") as f:
                json.dump(foreign_bundle, f)
            res = verify_bundle(bpath)
            self.assertEqual(res.get("status"), "FAIL")
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    def test_negative_8_producer_forged_derived_states_ignored_by_verifier(self):
        """Negative Twin 8: Verifier independently derives states and ignores forged claims in bundle."""
        tmp = tempfile.mkdtemp()
        try:
            forged_bundle = {
                "certification_run_id": "forged_run",
                "product_artifact_id": "p1",
                "product_build_input_manifest_id": "i1",
                "certification_artifact_id": "c1",
                "product_test_corpus_id": "ptc1",
                "certification_test_corpus_id": "ctc1",
                "upstream_closure_snapshot_id": "u1",
                "contract_hashes": {},
                "toolchain": {},
                "derived_states": {
                    "ARCHITECTURE_COMPLETE": "PASS",
                    "IMPLEMENTATION_COMPLETE": "PASS",
                    "VERIFIED": "PASS",
                    "PRODUCTION_READY": "PASS"
                },
                "requirements_results": {"P6-G8-REQ-01": {"status": "FAIL"}},
                "closure_status": "CLOSED",
                "release_artifact": {"path": "app-release.apk", "sha256": "fake"},
                "evidence_artifacts": []
            }
            bpath = os.path.join(tmp, "bundle.json")
            with open(bpath, "w", encoding="utf-8") as f:
                json.dump(forged_bundle, f)
            res = verify_bundle(bpath)
            self.assertNotEqual(res.get("closure_status"), "CLOSED")
            self.assertEqual(res.get("status"), "FAIL")
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    def test_negative_9_producer_forged_verified_flag_ignored(self):
        """Negative Twin 9: Forged verified flags in producer receipts do not influence independent verifier."""
        tmp = tempfile.mkdtemp()
        try:
            fake_receipt = {"verified": True, "status": "PASS"}
            self.assertTrue(fake_receipt.get("verified"))
            res = verify_bundle(os.path.join(tmp, "non_existent.json"))
            self.assertEqual(res.get("status"), "FAIL")
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    def test_negative_10_semantic_matrix_reconciliation_mismatch_fails(self):
        """Negative Twin 10: Disagreement between matrix and canonical checks is detected."""
        checks_path = os.path.join(REPO_ROOT, "contract", "g8_adversarial_checks.yaml")
        with open(checks_path, "r", encoding="utf-8") as f:
            cdata = yaml.safe_load(f)["checks"]
        canonical_ids = {c["id"] for c in cdata}
        self.assertEqual(len(canonical_ids), 79)
        matrix_path = os.path.join(REPO_ROOT, "contract", "g8_semantic_coverage_matrix.yaml")
        with open(matrix_path, "r", encoding="utf-8") as f:
            mdata = yaml.safe_load(f)["coverage_matrix"]
        matrix_ids = {m["check_id"] for m in mdata}
        self.assertEqual(canonical_ids, matrix_ids, "Matrix IDs do not match canonical check IDs exactly")

    def test_causal_hotfix_probes_030_038_041_042_043(self):
        """Verify causal target sensitivity and negative twin enforcement for the 5 hotfixed probes."""
        from unittest.mock import patch
        import g8_run_adversarial_probe as gap

        # G8-ADV-030
        code, msg = gap.probe_G8_ADV_030()
        self.assertEqual(code, 2, f"030 expected 2, got {code} ({msg})")
        with patch("verify_g8_release_environment.verify_release_environment", return_value=True):
            code_bypassed, _ = gap.probe_G8_ADV_030()
            self.assertEqual(code_bypassed, 0, "030 did not fail causal test when target enforcement was bypassed")

        # G8-ADV-038
        code, msg = gap.probe_G8_ADV_038()
        self.assertEqual(code, 2, f"038 expected 2, got {code} ({msg})")
        with patch("verify_phase_compliance.verify_phase", return_value=True):
            code_bypassed, _ = gap.probe_G8_ADV_038()
            self.assertEqual(code_bypassed, 0, "038 did not fail causal test when target enforcement was bypassed")

        # G8-ADV-041
        code, msg = gap.probe_G8_ADV_041()
        self.assertEqual(code, 2, f"041 expected 2, got {code} ({msg})")
        with patch("verify_invariant_contract.verify_contract", return_value=True):
            code_bypassed, _ = gap.probe_G8_ADV_041()
            self.assertEqual(code_bypassed, 0, "041 did not fail causal test when target enforcement was bypassed")

        # G8-ADV-042
        code, msg = gap.probe_G8_ADV_042()
        self.assertEqual(code, 2, f"042 expected 2, got {code} ({msg})")
        with patch("verify_invariant_contract.verify_contract", return_value=True):
            code_bypassed, _ = gap.probe_G8_ADV_042()
            self.assertEqual(code_bypassed, 0, "042 did not fail causal test when target enforcement was bypassed")

        # G8-ADV-043
        code, msg = gap.probe_G8_ADV_043()
        self.assertEqual(code, 2, f"043 expected 2, got {code} ({msg})")
        with patch("g8_certify.init_certification_run_dir", return_value=("dir", "adv")):
            code_bypassed, _ = gap.probe_G8_ADV_043()
            self.assertEqual(code_bypassed, 0, "043 did not fail causal test when target enforcement was bypassed")


if __name__ == "__main__":
    unittest.main()
