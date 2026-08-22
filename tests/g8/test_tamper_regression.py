# tests/g8/test_tamper_regression.py
import sys
import os
import unittest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.join(REPO_ROOT, "scripts"))

from g8_verify_certification_bundle import verify_bundle

class TestTamperRegression(unittest.TestCase):
    def test_tamper_with_adv_check_outcome_rejected(self):
        import tempfile
        import shutil
        tmp_dir = tempfile.mkdtemp()
        try:
            tampered_bundle = os.path.join(tmp_dir, "tampered.json")
            with open(tampered_bundle, "w", encoding="utf-8") as f:
                f.write('{"evidence_receipts": [{"check_id": "G8-ADV-001", "observed_exit_code": 1}]}')
            res = verify_bundle(tampered_bundle)
            self.assertEqual(res.get("status"), "FAIL")
        finally:
            if os.path.exists(tmp_dir):
                shutil.rmtree(tmp_dir)

    def test_historical_receipt_rejected_as_current_evidence(self):
        import tempfile
        import shutil
        tmp_dir = tempfile.mkdtemp()
        try:
            historical_bundle = os.path.join(tmp_dir, "historical.json")
            with open(historical_bundle, "w", encoding="utf-8") as f:
                f.write('{"source_commit_sha": "ba1761ffa8b0cb62fb744e03aef429175831af7a", "evidence_receipts": []}')
            res = verify_bundle(historical_bundle)
            self.assertEqual(res.get("status"), "FAIL")
        finally:
            if os.path.exists(tmp_dir):
                shutil.rmtree(tmp_dir)

    def test_fake_signature_rejected_by_verifier(self):
        import tempfile
        import shutil
        import json
        tmp_dir = tempfile.mkdtemp()
        try:
            fake_bundle_path = os.path.join(tmp_dir, "fake_sign.json")
            fake_bundle = {
                "certification_run_id": "fake_run",
                "product_artifact_id": "prod_123",
                "product_build_input_manifest_id": "input_123",
                "certification_artifact_id": "cert_123",
                "product_test_corpus_id": "ptc_123",
                "certification_test_corpus_id": "ctc_123",
                "upstream_closure_snapshot_id": "up_123",
                "contract_hashes": {},
                "toolchain": {},
                "derived_states": {},
                "requirements_results": {},
                "closure_status": "CLOSED",
                "release_artifact": {
                    "path": "app/build/outputs/apk/release/app-release.apk",
                    "sha256": "fake_sha_256",
                    "signing_status": "VERIFIED_SIGNED"
                },
                "evidence_artifacts": []
            }
            with open(fake_bundle_path, "w", encoding="utf-8") as f:
                json.dump(fake_bundle, f)
            res = verify_bundle(fake_bundle_path)
            self.assertEqual(res.get("status"), "FAIL")
        finally:
            if os.path.exists(tmp_dir):
                shutil.rmtree(tmp_dir)

if __name__ == "__main__":
    unittest.main()
