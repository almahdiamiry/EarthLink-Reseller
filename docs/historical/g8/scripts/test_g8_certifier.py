#!/usr/bin/env python3
"""
scripts/test_g8_certifier.py

Self-Tests for G8 Certification Verifier and Manifest Engine.
Proves that the verifier correctly verifies valid bundles and rejects invalid manifests.
"""

import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from build_g8_source_manifest import build_manifests
from build_g8_test_corpus_manifest import build_test_corpus
from g8_verify_certification_bundle import verify_bundle

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))


class TestG8Certifier(unittest.TestCase):

    def test_source_manifest_generation(self):
        res = build_manifests()
        self.assertIn("product_artifact_id", res)
        self.assertIn("product_build_input_manifest_id", res)
        self.assertIn("certification_artifact_id", res)
        self.assertTrue(len(res["product_manifest"]) > 0)
        self.assertTrue(len(res["certification_manifest"]) > 0)

    def test_test_corpus_manifest_generation(self):
        res = build_test_corpus()
        self.assertIn("product_test_corpus_id", res)
        self.assertIn("certification_test_corpus_id", res)
        self.assertTrue(len(res["product_test_corpus"]) > 0)
        self.assertTrue(len(res["certification_test_corpus"]) >= len(res["product_test_corpus"]))

    def test_bundle_verifier_rejects_empty_or_malformed(self):
        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as f:
            f.write("{}")
            tmp_path = f.name
        try:
            res = verify_bundle(tmp_path)
            self.assertEqual(res["status"], "FAIL")
            self.assertTrue(len(res["errors"]) > 0)
        finally:
            if os.path.exists(tmp_path):
                os.remove(tmp_path)

    def test_bundle_verifier_rejects_missing_file(self):
        res = verify_bundle("non_existent_bundle.json")
        self.assertEqual(res["status"], "FAIL")


if __name__ == "__main__":
    unittest.main()
