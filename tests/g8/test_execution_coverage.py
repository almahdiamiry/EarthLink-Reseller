# tests/g8/test_execution_coverage.py
import os
import sys
import unittest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.join(REPO_ROOT, "scripts"))

import yaml

class TestExecutionCoverage(unittest.TestCase):
    def test_79_checks_lifecycle_complete_coverage(self):
        with open(os.path.join(REPO_ROOT, "contract", "g8_adversarial_checks.yaml"), "r", encoding="utf-8") as f:
            declared_checks = {c["id"] for c in yaml.safe_load(f)["checks"]}
        with open(os.path.join(REPO_ROOT, "contract", "g8_proof_execution_map.yaml"), "r", encoding="utf-8") as f:
            mapped_checks = set(yaml.safe_load(f)["mappings"].keys())
        
        self.assertEqual(len(declared_checks), 79)
        self.assertEqual(declared_checks, mapped_checks, "Declared and mapped check IDs must match exactly")

if __name__ == "__main__":
    unittest.main()
