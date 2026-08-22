# tests/g8/test_junit_parser.py
import os
import sys
import unittest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.join(REPO_ROOT, "scripts"))

from g8_junit_parser import parse_junit_results

FIXTURES_DIR = os.path.join(os.path.dirname(__file__), "fixtures", "junit")

class TestJunitParser(unittest.TestCase):
    def test_parse_clean_pass(self):
        res = parse_junit_results(os.path.join(FIXTURES_DIR, "clean_pass"))
        self.assertEqual(res["status"], "PASS")
        self.assertEqual(res["failures"], 0)
        self.assertEqual(res["errors"], 0)
        self.assertEqual(res["skipped"], 0)
        self.assertEqual(res["total_tests"], 5)

    def test_parse_failure_fails_closed(self):
        res = parse_junit_results(os.path.join(FIXTURES_DIR, "with_failure"))
        self.assertEqual(res["status"], "FAIL")
        self.assertEqual(res["failures"], 1)

    def test_parse_skipped_fails_closed(self):
        res = parse_junit_results(os.path.join(FIXTURES_DIR, "with_skipped"))
        # As per plan, skipped should fail closed (either FAIL or SKIPPED_DETECTED)
        self.assertIn(res["status"], {"FAIL", "SKIPPED_DETECTED"})
        self.assertEqual(res["skipped"], 1)

    def test_parse_malformed_fails_closed(self):
        res = parse_junit_results(os.path.join(FIXTURES_DIR, "malformed"))
        self.assertEqual(res["status"], "MALFORMED_RESULT")

if __name__ == "__main__":
    unittest.main()
