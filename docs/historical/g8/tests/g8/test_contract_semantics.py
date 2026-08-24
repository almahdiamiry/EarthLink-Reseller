# tests/g8/test_contract_semantics.py
import os
import sys
import unittest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.join(REPO_ROOT, "scripts"))
import yaml

CONTRACT_PATH = os.path.join(REPO_ROOT, "contract", "g8_certification_contract.yaml")

class TestContractSemantics(unittest.TestCase):
    def test_contract_specifies_authoritative_signing_and_state_predicates(self):
        with open(CONTRACT_PATH, "r", encoding="utf-8") as f:
            contract = yaml.safe_load(f)
        
        self.assertIn("signing_authority_provenance", contract, "Contract must declare external signing authority source")
        
        states = {s["id"]: s for s in contract.get("derived_states", [])}
        for state_id in ["ARCHITECTURE_COMPLETE", "IMPLEMENTATION_COMPLETE", "VERIFIED", "PRODUCTION_READY"]:
            self.assertIn(state_id, states, f"Missing state definition for {state_id}")
            self.assertIn("formal_predicate", states[state_id], f"Missing formal predicate for {state_id}")

if __name__ == "__main__":
    unittest.main()
