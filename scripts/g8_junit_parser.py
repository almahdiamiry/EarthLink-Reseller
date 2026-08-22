#!/usr/bin/env python3
"""
scripts/g8_junit_parser.py

Robust JUnit XML report parser for G8 certification.
"""

import os
import xml.etree.ElementTree as ET

def parse_junit_results(results_dir: str, required_suites: list = None) -> dict:
    if not os.path.exists(results_dir):
        return {
            "status": "NO_REPORTS",
            "total_tests": 0,
            "failures": 0,
            "errors": 0,
            "skipped": 0,
            "details": f"Directory not found: {results_dir}"
        }

    xml_files = [f for f in os.listdir(results_dir) if f.endswith(".xml")]
    if not xml_files:
        return {
            "status": "NO_REPORTS",
            "total_tests": 0,
            "failures": 0,
            "errors": 0,
            "skipped": 0,
            "details": "No XML files in directory"
        }

    total_tests = 0
    failures = 0
    errors = 0
    skipped = 0

    parsed_suites = set()

    for f in xml_files:
        path = os.path.join(results_dir, f)
        try:
            tree = ET.parse(path)
            root = tree.getroot()
        except Exception:
            return {
                "status": "MALFORMED_RESULT",
                "total_tests": 0,
                "failures": 0,
                "errors": 0,
                "skipped": 0,
                "details": f"Malformed XML in {f}"
            }

        # Handle either <testsuite> or <testsuites> roots
        suites = []
        if root.tag == "testsuite":
            suites.append(root)
        elif root.tag == "testsuites":
            suites.extend(root.findall("testsuite"))
        else:
            suites.append(root)

        for suite in suites:
            name = suite.get("name")
            if name:
                parsed_suites.add(name)

            # Accumulate metrics
            try:
                suite_tests = int(suite.get("tests", 0))
                suite_failures = int(suite.get("failures", 0))
                suite_errors = int(suite.get("errors", 0))
                suite_skipped = int(suite.get("skipped", 0))
            except (ValueError, TypeError):
                suite_tests = 0
                suite_failures = 0
                suite_errors = 0
                suite_skipped = 0

            # Double-check by scanning children in case attributes are incomplete or wrong
            found_failures = len(suite.findall(".//failure"))
            found_errors = len(suite.findall(".//error"))
            found_skipped = len(suite.findall(".//skipped"))

            suite_failures = max(suite_failures, found_failures)
            suite_errors = max(suite_errors, found_errors)
            suite_skipped = max(suite_skipped, found_skipped)

            total_tests += suite_tests or len(suite.findall(".//testcase"))
            failures += suite_failures
            errors += suite_errors
            skipped += suite_skipped

    if required_suites:
        for req in required_suites:
            if not any(ps == req or ps.endswith("." + req) for ps in parsed_suites):
                return {
                    "status": "MISSING_EXPECTED_SUITE",
                    "total_tests": total_tests,
                    "failures": failures,
                    "errors": errors,
                    "skipped": skipped,
                    "details": f"Missing expected suite: {req}"
                }

    if failures > 0 or errors > 0:
        status = "FAIL"
    elif skipped > 0:
        status = "SKIPPED_DETECTED"
    else:
        status = "PASS"

    return {
        "status": status,
        "total_tests": total_tests,
        "failures": failures,
        "errors": errors,
        "skipped": skipped,
        "parsed_suites": list(parsed_suites)
    }
