# tests/g8/test_proof_map_integrity.py
import os
import sys
import unittest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.join(REPO_ROOT, "scripts"))
import yaml

CHECKS_PATH = os.path.join(REPO_ROOT, "contract", "g8_adversarial_checks.yaml")
MAP_PATH = os.path.join(REPO_ROOT, "contract", "g8_proof_execution_map.yaml")

class TestProofMapIntegrity(unittest.TestCase):
    def test_all_79_checks_resolve_to_valid_executable_targets(self):
        self.assertTrue(os.path.exists(MAP_PATH), "g8_proof_execution_map.yaml must exist in contract/")
        with open(CHECKS_PATH, "r", encoding="utf-8") as f:
            checks_data = yaml.safe_load(f)
        with open(MAP_PATH, "r", encoding="utf-8") as f:
            map_data = yaml.safe_load(f)
        
        declared_checks = {c["id"]: c for c in checks_data["checks"]}
        mappings = map_data.get("mappings", {})
        
        self.assertEqual(len(declared_checks), 79, "Expected exactly 79 declared checks")
        self.assertEqual(set(declared_checks.keys()), set(mappings.keys()), "Mappings and declared checks must match exactly")
        
        assertion_ids = set()
        evidence_ids = set()
        script_selectors = set()
        
        for check_id, mapping in mappings.items():
            check_def = declared_checks[check_id]
            self.assertEqual(mapping["proof_mode"], check_def["proof_mode"])
            self.assertEqual(mapping["expected_outcome"], check_def["expected_outcome"])
            
            # 1. Uniqueness of proof & evidence identity
            self.assertNotIn(mapping["assertion_id"], assertion_ids, f"Duplicate assertion_id: {mapping['assertion_id']}")
            self.assertNotIn(mapping["evidence_artifact_id"], evidence_ids, f"Duplicate evidence_artifact_id: {mapping['evidence_artifact_id']}")
            assertion_ids.add(mapping["assertion_id"])
            evidence_ids.add(mapping["evidence_artifact_id"])
            
            # 2. Executable Target Resolution & Distinct Invocation Semantics
            executor_type = mapping["executor_type"]
            selector = mapping.get("execution_selector")
            self.assertTrue(selector, f"Missing execution_selector for {check_id}")
            
            if executor_type == "JUNIT_TEST":
                target_file = mapping.get("target_file", "")
                target_class = mapping.get("target_class", "")
                target_method = mapping.get("target_method", "")
                
                full_target_path = os.path.join(REPO_ROOT, target_file)
                self.assertTrue(os.path.exists(full_target_path), f"Target file {target_file} missing for {check_id}")
                self.assertTrue(target_class and target_method, f"Target class/method missing for {check_id}")
                
                # Robust semantic verification of class and method inside Kotlin source file
                with open(full_target_path, "r", encoding="utf-8") as f:
                    src = f.read()
                import re
                simple_class_name = target_class.split(".")[-1]
                self.assertTrue(re.search(rf"\bclass\s+{re.escape(simple_class_name)}\b", src), f"Class {simple_class_name} not declared in {target_file}")
                self.assertTrue(re.search(rf"\bfun\s+{re.escape(target_method)}\b", src), f"Method {target_method} not declared in {target_file}")
                
                # Selector must form valid Gradle test filter syntax targeting class.method
                self.assertTrue(selector == f"--tests {target_class}.{target_method}" or selector == f"--tests \"{target_class}.{target_method}\"")
                
            elif executor_type == "STRUCTURAL_SCRIPT":
                script_path = os.path.join(REPO_ROOT, mapping.get("executor_ref", ""))
                self.assertTrue(os.path.exists(script_path), f"Script {script_path} missing for {check_id}")
                # Script selector must have distinct invocation identity (e.g. distinct argument/rule flag)
                self.assertNotIn(selector, script_selectors, f"Duplicate structural selector '{selector}' across checks")
                script_selectors.add(selector)
                self.assertTrue(mapping.get("assertion_id"), f"Missing assertion_id for structural check {check_id}")
                
            elif executor_type in {"MUTATION_PROBE", "ARTIFACT_PROBE"}:
                self.assertIn("expected_observation", mapping, f"Missing expected_observation for probe {check_id}")

if __name__ == "__main__":
    unittest.main()
