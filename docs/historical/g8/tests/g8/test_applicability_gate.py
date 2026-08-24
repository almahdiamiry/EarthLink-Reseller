# tests/g8/test_applicability_gate.py
import os
import sys
import unittest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.join(REPO_ROOT, "scripts"))
import yaml

CONTRACT_PATH = os.path.join(REPO_ROOT, "contract", "g8_certification_contract.yaml")
ALLOWED_APPLICABILITY_STATES = {"MANDATORY", "SUPPORTING", "NOT_APPLICABLE", "AUTHORITY_GAP"}

class TestApplicabilityGate(unittest.TestCase):
    def test_canonical_applicability_classification_and_consistency(self):
        with open(CONTRACT_PATH, "r", encoding="utf-8") as f:
            contract = yaml.safe_load(f)
        
        # 1. Must declare structured corpora_applicability dictionary
        applicability = contract.get("corpora_applicability", {})
        self.assertTrue(applicability, "Contract must define structured 'corpora_applicability' mapping")
        
        for corpus_name in ["PRODUCT_TEST_CORPUS", "INSTRUMENTED_CORPUS", "STRUCTURAL_CORPUS", "HISTORICAL_CORPUS"]:
            self.assertIn(corpus_name, applicability, f"Missing applicability definition for {corpus_name}")
            state = applicability[corpus_name].get("release_gate")
            self.assertIn(state, ALLOWED_APPLICABILITY_STATES, f"Invalid state '{state}' for {corpus_name}")
            
        # 2. Consistency check with requirement obligations and deterministic execution_task field
        reqs = {r["id"]: r for r in contract.get("requirements", [])}
        if "P6-G8-REQ-03" in reqs:
            p6_req = reqs["P6-G8-REQ-03"]
            inst_corpus = applicability["INSTRUMENTED_CORPUS"]
            if p6_req.get("device_execution_required") is True:
                self.assertEqual(inst_corpus["release_gate"], "MANDATORY")
                self.assertTrue(isinstance(inst_corpus.get("execution_task"), str) and len(inst_corpus["execution_task"]) > 0,
                    "execution_task must be a non-empty string when release_gate is MANDATORY")
            else:
                self.assertIn(inst_corpus["release_gate"], {"SUPPORTING", "NOT_APPLICABLE"})
                self.assertIsNone(inst_corpus.get("execution_task"),
                    "execution_task must be explicitly null when release_gate is not MANDATORY")

if __name__ == "__main__":
    unittest.main()
