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

    def test_all_79_handlers_registered_and_executable(self):
        import g8_run_adversarial_probe
        self.assertEqual(len(g8_run_adversarial_probe.PROBE_HANDLERS), 79)
        for i in range(1, 80):
            cid = f"G8-ADV-{i:03d}"
            self.assertIn(cid, g8_run_adversarial_probe.PROBE_HANDLERS, f"Handler for {cid} must be registered")
            exit_code = g8_run_adversarial_probe.run_probe(cid)
            self.assertEqual(exit_code, 2, f"Probe for {cid} must return exit code 2 (BLOCKED)")

    def test_unknown_check_fails_closed(self):
        import g8_run_adversarial_probe
        exit_code = g8_run_adversarial_probe.run_probe("G8-ADV-999")
        self.assertEqual(exit_code, 1, "Unknown check ID must fail closed with exit code 1")

if __name__ == "__main__":
    unittest.main()
