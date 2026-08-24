# tests/g8/test_verifier_model.py
import os
import sys
import unittest
import json
import tempfile

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.join(REPO_ROOT, "scripts"))

from g8_verify_certification_bundle import verify_bundle

class TestVerifierModel(unittest.TestCase):
    def test_verifier_rejects_empty_or_fabricated_receipts(self):
        # 1. Create a completely empty/invalid bundle
        with tempfile.NamedTemporaryFile(mode="w+", suffix=".json", delete=False) as tmp:
            json.dump({"derived_states": {"VERIFIED": "PASS"}, "evidence_artifacts": []}, tmp)
            tmp_path = tmp.name

        try:
            res = verify_bundle(tmp_path)
            self.assertEqual(res["status"], "FAIL")
            self.assertIn("Missing required field in bundle: certification_run_id", res["errors"])
        finally:
            if os.path.exists(tmp_path):
                os.remove(tmp_path)

    def test_verifier_rejects_mismatched_release_signature(self):
        # 2. Create a bundle with missing release artifact
        bundle_data = {
            "certification_run_id": "run-123",
            "product_artifact_id": "art-123",
            "product_build_input_manifest_id": "manifest-123",
            "certification_artifact_id": "cert-123",
            "product_test_corpus_id": "corpus-123",
            "certification_test_corpus_id": "ccorpus-123",
            "upstream_closure_snapshot_id": "snapshot-123",
            "contract_hashes": {},
            "toolchain": {},
            "derived_states": {
                "ARCHITECTURE_COMPLETE": "PASS",
                "IMPLEMENTATION_COMPLETE": "PASS",
                "VERIFIED": "PASS",
                "PRODUCTION_READY": "PASS"
            },
            "requirements_results": {
                "P6-G8-REQ-01": {"status": "PASS"},
                "P6-G8-REQ-02": {"status": "PASS"},
                "P6-G8-REQ-03": {"status": "PASS"}
            },
            "closure_status": "CLOSED",
            "evidence_artifacts": [{"path": "contract/g8_certification_contract.yaml", "sha256": "wrong-sha"}],
            "adversarial_results": {},
            "release_artifact": {"path": "non-existent-apk.apk", "sha256": "abc"}
        }

        with tempfile.NamedTemporaryFile(mode="w+", suffix=".json", delete=False) as tmp:
            json.dump(bundle_data, tmp)
            tmp_path = tmp.name

        try:
            res = verify_bundle(tmp_path)
            self.assertEqual(res["status"], "FAIL")
            # Should have errors because of missing/mismatched evidence artifacts and missing adversarial results
            self.assertTrue(len(res["errors"]) > 0)
        finally:
            if os.path.exists(tmp_path):
                os.remove(tmp_path)

if __name__ == "__main__":
    unittest.main()
