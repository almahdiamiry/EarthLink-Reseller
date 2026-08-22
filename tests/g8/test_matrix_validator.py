# tests/g8/test_matrix_validator.py
import os
import sys
import unittest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.join(REPO_ROOT, "scripts"))

from verify_test_environment_matrix import verify_matrix

class TestMatrixValidator(unittest.TestCase):
    def test_test_matrix_validation_passes(self):
        # The verify_matrix function must return True, meaning zero errors
        self.assertTrue(verify_matrix(), "Test Environment Matrix validation failed!")

if __name__ == "__main__":
    unittest.main()
