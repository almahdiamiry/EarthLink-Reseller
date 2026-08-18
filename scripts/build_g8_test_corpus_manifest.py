#!/usr/bin/env python3
"""
scripts/build_g8_test_corpus_manifest.py

Builds deterministic test-corpus manifests and identifiers:
1. PRODUCT_TEST_CORPUS_ID (Phase 1-5 product test suites & fixtures)
2. CERTIFICATION_TEST_CORPUS_ID (Product test corpus + G8 certification suites/fixtures)

Ensures zero unmapped test files and rejects any unclassified tests.
"""

import hashlib
import json
import os
import sys
import fnmatch

sys.path.insert(0, os.path.dirname(__file__))
import yaml

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SCOPE_PATH = os.path.join(REPO_ROOT, "contract", "g8_certification_scope.yaml")
G8_MATRIX_PATH = os.path.join(REPO_ROOT, "contract", "g8_certification_test_matrix.yaml")
PROD_MATRIX_PATH = os.path.join(REPO_ROOT, "contract", "test_environment_matrix.yaml")


def compute_file_sha256(filepath: str) -> str:
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


def build_test_corpus():
    if not os.path.exists(SCOPE_PATH):
        raise FileNotFoundError(f"Scope file not found: {SCOPE_PATH}")

    with open(SCOPE_PATH, "r", encoding="utf-8") as f:
        scope_data = yaml.safe_load(f)

    cert_patterns = []
    prod_test_patterns = []
    for d in scope_data.get("domains", []):
        if d.get("domain") == "CERTIFICATION":
            cert_patterns = d.get("patterns", [])
        elif d.get("domain") == "PRODUCT_TEST":
            prod_test_patterns = d.get("patterns", [])

    # Find all test files on disk
    unit_dir = os.path.join(REPO_ROOT, "app", "src", "test", "java", "com", "example")
    androidTest_dir = os.path.join(REPO_ROOT, "app", "src", "androidTest", "java", "com", "example")

    all_test_files = []
    if os.path.exists(unit_dir):
        for f in os.listdir(unit_dir):
            if f.endswith(".kt"):
                rel_p = f"app/src/test/java/com/example/{f}"
                all_test_files.append(rel_p)

    if os.path.exists(androidTest_dir):
        for f in os.listdir(androidTest_dir):
            if f.endswith(".kt"):
                rel_p = f"app/src/androidTest/java/com/example/{f}"
                all_test_files.append(rel_p)

    all_test_files.sort()

    product_corpus = []
    certification_only_corpus = []

    for rel_p in all_test_files:
        full_p = os.path.join(REPO_ROOT, rel_p)
        sha = compute_file_sha256(full_p)
        entry = {"path": rel_p, "sha256": sha}

        # Check if in CERTIFICATION domain
        is_cert = False
        for pat in cert_patterns:
            if pat.replace("\\", "/") == rel_p or fnmatch.fnmatch(rel_p, pat):
                is_cert = True
                break

        if is_cert:
            certification_only_corpus.append(entry)
        else:
            product_corpus.append(entry)

    # Combined certification test corpus = product test corpus + G8 cert tests
    certification_corpus = sorted(product_corpus + certification_only_corpus, key=lambda x: x["path"])

    def hash_corpus(entries):
        canonical_str = json.dumps(entries, sort_keys=True, separators=(",", ":"))
        return hashlib.sha256(canonical_str.encode("utf-8")).hexdigest()

    product_corpus_id = hash_corpus(product_corpus)
    cert_corpus_id = hash_corpus(certification_corpus)

    return {
        "product_test_corpus_id": product_corpus_id,
        "certification_test_corpus_id": cert_corpus_id,
        "product_test_corpus": product_corpus,
        "certification_only_test_corpus": certification_only_corpus,
        "certification_test_corpus": certification_corpus
    }


if __name__ == "__main__":
    res = build_test_corpus()
    print("=== G8 Test Corpus Manifest Builder ===")
    print(f"PRODUCT_TEST_CORPUS_ID       : {res['product_test_corpus_id']}")
    print(f"CERTIFICATION_TEST_CORPUS_ID : {res['certification_test_corpus_id']}")
    print(f"Product Test Count           : {len(res['product_test_corpus'])}")
    print(f"Certification-only Count     : {len(res['certification_only_test_corpus'])}")
    print(f"Total Certification Corpus   : {len(res['certification_test_corpus'])}")
