# tests/g8/test_manifest_boundary.py
import os
import sys
import shutil
import unittest
import tempfile

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.join(REPO_ROOT, "scripts"))

from build_g8_source_manifest import build_manifests

class TestManifestBoundary(unittest.TestCase):
    def test_build_manifests_deterministic(self):
        m1 = build_manifests()
        m2 = build_manifests()
        self.assertEqual(m1["product_artifact_id"], m2["product_artifact_id"])
        self.assertEqual(m1["certification_artifact_id"], m2["certification_artifact_id"])
        self.assertEqual(len(m1["product_artifact_id"]), 64)
        self.assertIn("certification_boundary_id", m1)
        self.assertEqual(len(m1["certification_boundary_id"]), 64)
        self.assertEqual(m1["certification_boundary_id"], m2["certification_boundary_id"])

    def test_toctou_mutation_detected_in_isolated_fixture(self):
        tmp_dir = tempfile.mkdtemp()
        mock_repo = os.path.join(tmp_dir, "mock_repo")
        mock_src = os.path.join(mock_repo, "app", "src", "main")
        os.makedirs(mock_src, exist_ok=True)
        
        # We also need contract/g8_certification_scope.yaml inside mock_repo
        mock_contract = os.path.join(mock_repo, "contract")
        os.makedirs(mock_contract, exist_ok=True)
        shutil.copy(os.path.join(REPO_ROOT, "contract", "g8_certification_scope.yaml"),
                    os.path.join(mock_contract, "g8_certification_scope.yaml"))

        target_file = os.path.join(mock_src, "Test.kt")
        with open(target_file, "w", encoding="utf-8") as f:
            f.write("initial content")

        try:
            # 1. Baseline verification
            m_initial = build_manifests(repo_root=mock_repo)

            # 2. Mutate file -> Must yield different hash
            with open(target_file, "w", encoding="utf-8") as f:
                f.write("mutated content")
            m_mutated = build_manifests(repo_root=mock_repo)
            self.assertNotEqual(m_mutated["product_artifact_id"], m_initial["product_artifact_id"], "TOCTOU mutation was not detected")

            # 3. Restore file -> Must match baseline again
            with open(target_file, "w", encoding="utf-8") as f:
                f.write("initial content")
            m_restored = build_manifests(repo_root=mock_repo)
            self.assertEqual(m_restored["product_artifact_id"], m_initial["product_artifact_id"], "Restored fixture failed to match baseline")

        finally:
            if os.path.exists(tmp_dir):
                shutil.rmtree(tmp_dir)

if __name__ == "__main__":
    unittest.main()
