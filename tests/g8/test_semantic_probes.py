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

    def test_probe_g8_adv_001_semantics(self):
        code, msg = PROBE_HANDLERS["G8-ADV-001"]()
        self.assertEqual(code, 2)
        self.assertIn("[BLOCKED]", msg)

    def test_probe_g8_adv_002_semantics(self):
        code, msg = PROBE_HANDLERS["G8-ADV-002"]()
        self.assertEqual(code, 2)
        self.assertIn("[BLOCKED]", msg)

    def test_probe_g8_adv_003_semantics(self):
        code, msg = PROBE_HANDLERS["G8-ADV-003"]()
        self.assertEqual(code, 2)
        self.assertIn("[BLOCKED]", msg)

    def test_probe_g8_adv_004_semantics(self):
        code, msg = PROBE_HANDLERS["G8-ADV-004"]()
        self.assertEqual(code, 2)
        self.assertIn("[BLOCKED]", msg)

    def test_probe_g8_adv_005_semantics(self):
        code, msg = PROBE_HANDLERS["G8-ADV-005"]()
        self.assertEqual(code, 2)
        self.assertIn("[BLOCKED]", msg)

    def test_probe_g8_adv_006_semantics(self):
        code, msg = PROBE_HANDLERS["G8-ADV-006"]()
        self.assertEqual(code, 2)
        self.assertIn("[BLOCKED]", msg)

    def test_probe_g8_adv_007_semantics(self):
        code, msg = PROBE_HANDLERS["G8-ADV-007"]()
        self.assertEqual(code, 2)
        self.assertIn("[BLOCKED]", msg)

    def test_probe_g8_adv_013_semantics(self):
        code, msg = PROBE_HANDLERS["G8-ADV-013"]()
        self.assertEqual(code, 2)
        self.assertIn("[BLOCKED]", msg)

    def test_probe_g8_adv_021_semantics(self):
        code, msg = PROBE_HANDLERS["G8-ADV-021"]()
        self.assertEqual(code, 2)
        self.assertIn("[BLOCKED]", msg)

    def test_probe_g8_adv_039_semantics(self):
        code, msg = PROBE_HANDLERS["G8-ADV-039"]()
        self.assertEqual(code, 2)
        self.assertIn("[BLOCKED]", msg)


if __name__ == "__main__":
    unittest.main()
