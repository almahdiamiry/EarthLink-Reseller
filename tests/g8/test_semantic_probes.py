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

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.join(REPO_ROOT, "scripts"))
import yaml
from g8_run_adversarial_probe import PROBE_HANDLERS, run_probe


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


if __name__ == "__main__":
    unittest.main()
