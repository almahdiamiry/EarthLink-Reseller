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

if __name__ == "__main__":
    unittest.main()
