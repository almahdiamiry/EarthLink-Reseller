# tests/g8/test_state_derivation.py
import sys
import os
import unittest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.join(REPO_ROOT, "scripts"))

import yaml
from g8_verify_certification_bundle import evaluate_contract_predicates

CONTRACT_PATH = os.path.join(REPO_ROOT, "contract", "g8_certification_contract.yaml")

class TestStateDerivation(unittest.TestCase):
    def test_state_derivation_from_canonical_contract_predicates(self):
        with open(CONTRACT_PATH, "r", encoding="utf-8") as f:
            contract = yaml.safe_load(f)
        
        # 1. Dynamically verify that all 4 states define machine-evaluable formal predicates
        state_defs = {s["id"]: s for s in contract.get("derived_states", [])}
        for state_id in ["ARCHITECTURE_COMPLETE", "IMPLEMENTATION_COMPLETE", "VERIFIED", "PRODUCTION_READY"]:
            self.assertIn(state_id, state_defs, f"Missing {state_id} definition in contract")
            self.assertIn("formal_predicate", state_defs[state_id], f"Missing formal_predicate for {state_id}")
        
        # 2. Evaluate against controlled passing evidence fixture
        baseline_evidence = {
            "invariant_contracts_passed": True,
            "forbidden_patterns_passed": True,
            "production_files_present": True,
            "g8_suites_present": True,
            "junit_failures_count": 0,
            "junit_errors_count": 0,
            "junit_skipped_count": 0,
            "adversarial_checks_failed": 0,
            "release_apk_signed_verified": True
        }
        
        states = evaluate_contract_predicates(contract, baseline_evidence)
        for state_id in ["ARCHITECTURE_COMPLETE", "IMPLEMENTATION_COMPLETE", "VERIFIED", "PRODUCTION_READY"]:
            self.assertEqual(states.get(state_id), "PASS", f"Expected {state_id} to PASS on baseline evidence")
            
        # 3. Tamper one required predicate input -> State must fail closed
        tampered_evidence = dict(baseline_evidence, release_apk_signed_verified=False)
        tampered_states = evaluate_contract_predicates(contract, tampered_evidence)
        self.assertEqual(tampered_states.get("PRODUCTION_READY"), "FAIL", "PRODUCTION_READY must FAIL if release signature unverified")

if __name__ == "__main__":
    unittest.main()
