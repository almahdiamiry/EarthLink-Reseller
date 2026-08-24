# tests/g8/test_proof_dispatch.py
import sys
import os
import unittest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.join(REPO_ROOT, "scripts"))

from g8_certify import dispatch_adversarial_check, evaluate_check_outcome

class TestProofDispatch(unittest.TestCase):
    def test_outcome_evaluation_semantics(self):
        self.assertEqual(evaluate_check_outcome(
            expected_outcome="FAIL_OR_BLOCKING_STATE",
            probe_exit_code=2,
            probe_output="BLOCKED"
        ), "PASS")
        
        self.assertEqual(evaluate_check_outcome(
            expected_outcome="FAIL_OR_BLOCKING_STATE",
            probe_exit_code=0,
            probe_output="ALLOWED"
        ), "FAIL")

    def test_dispatch_adversarial_check(self):
        import tempfile
        import shutil
        tmp_dir = tempfile.mkdtemp()
        try:
            check = {
                "id": "G8-ADV-TEST",
                "proof_target_id": "PT_TEST",
                "proof_result_id": "PR_TEST",
                "primary_evidence_artifact_id": "EA_TEST",
                "failure_condition": "Test failure condition",
                "expected_outcome": "FAIL_OR_BLOCKING_STATE"
            }
            res, art = dispatch_adversarial_check(check, {}, tmp_dir)
            self.assertEqual(res["status"], "PASS")
            self.assertEqual(res["proof_target_id"], "PT_TEST")
            self.assertEqual(res["proof_result_id"], "PR_TEST")
            self.assertTrue(os.path.exists(os.path.join(tmp_dir, "EA_TEST.json")))
            self.assertTrue(os.path.exists(os.path.join(tmp_dir, "metadata_EA_TEST.json")))
        finally:
            if os.path.exists(tmp_dir):
                shutil.rmtree(tmp_dir)

if __name__ == "__main__":
    unittest.main()
