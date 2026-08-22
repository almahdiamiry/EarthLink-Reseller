#!/usr/bin/env python3
"""
scripts/build_g8_source_manifest.py

Builds domain-specific source manifests for:
1. Product Artifact Manifest (PRODUCT_ARTIFACT_ID)
2. Product Build-Input Manifest (PRODUCT_BUILD_INPUT_MANIFEST_ID)
3. Certification Artifact Manifest (CERTIFICATION_ARTIFACT_ID)

Enforces strict domain separation per contract/g8_certification_scope.yaml.
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

EXCLUDED_PATTERNS = [
    ".git/*",
    "app/build/*",
    "*.class",
    "__pycache__/*",
    "*/__pycache__/*",
    "evidence/*",
    ".gradle/*",
    "local.properties"
]


def compute_file_sha256(filepath: str) -> str:
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


def matches_any(rel_path: str, patterns: list) -> bool:
    norm = rel_path.replace("\\", "/")
    for pat in patterns:
        norm_pat = pat.replace("\\", "/")
        if norm_pat.endswith("/**"):
            prefix = norm_pat[:-3]
            if norm.startswith(prefix) or norm == prefix.rstrip("/"):
                return True
        elif fnmatch.fnmatch(norm, norm_pat):
            return True
    return False


def load_scope(repo_root=None):
    base = repo_root or REPO_ROOT
    scope_path = os.path.join(base, "contract", "g8_certification_scope.yaml")
    if not os.path.exists(scope_path):
        raise FileNotFoundError(f"Scope file not found: {scope_path}")
    with open(scope_path, "r", encoding="utf-8") as f:
        return yaml.safe_load(f)


def build_manifests(repo_root=None):
    base = repo_root or REPO_ROOT
    scope = load_scope(base)
    domains = {d["domain"]: d.get("patterns", []) for d in scope.get("domains", [])}

    all_files = []
    for root, dirs, files in os.walk(base):
        # Exclude directories
        dirs[:] = [d for d in dirs if d not in [".git", "build", "__pycache__", ".gradle"]]
        for f in files:
            full_p = os.path.join(root, f)
            rel_p = os.path.relpath(full_p, base).replace("\\", "/")
            if not matches_any(rel_p, EXCLUDED_PATTERNS):
                all_files.append((rel_p, full_p))

    all_files.sort(key=lambda x: x[0])

    product_files = []
    cert_files = []
    shared_files = []

    for rel_p, full_p in all_files:
        sha = compute_file_sha256(full_p)
        size = os.path.getsize(full_p)
        entry = {"path": rel_p, "sha256": sha, "size_bytes": size}

        is_cert = matches_any(rel_p, domains.get("CERTIFICATION", []))
        is_shared = matches_any(rel_p, domains.get("SHARED_CONTROL_INPUT", []))
        is_prod = matches_any(rel_p, domains.get("PRODUCT", []))
        is_prod_test = matches_any(rel_p, domains.get("PRODUCT_TEST", []))

        if is_cert:
            cert_files.append(entry)
        elif is_shared:
            shared_files.append(entry)
            cert_files.append(entry)
        elif is_prod:
            product_files.append(entry)
        elif is_prod_test:
            # Belongs to PRODUCT_TEST_CORPUS, handled separately
            pass
        else:
            # Default check
            if rel_p.startswith("scripts/g8_") or "g8_" in rel_p:
                cert_files.append(entry)
            else:
                product_files.append(entry)

    # Compute deterministic IDs
    def hash_manifest_entries(entries):
        canonical_str = json.dumps(entries, sort_keys=True, separators=(",", ":"))
        return hashlib.sha256(canonical_str.encode("utf-8")).hexdigest()

    product_manifest_id = hash_manifest_entries(product_files)
    cert_manifest_id = hash_manifest_entries(cert_files)

    # Build input files are product files + build configs
    build_input_files = [f for f in product_files if f["path"].endswith(".kts") or f["path"].startswith("gradle/") or f["path"] in ["gradlew", "gradlew.bat", "app/google-services.json"]]
    product_build_input_id = hash_manifest_entries(build_input_files)

    # Calculate G8 Proof Map ID
    proof_map_path = os.path.join(base, "contract", "g8_proof_execution_map.yaml")
    if os.path.exists(proof_map_path):
        g8_proof_map_id = compute_file_sha256(proof_map_path)
    else:
        g8_proof_map_id = "0" * 64

    # Calculate Product Test Corpus ID
    try:
        from build_g8_test_corpus_manifest import build_test_corpus
        corpus_res = build_test_corpus(repo_root=base)
        product_test_corpus_id = corpus_res["product_test_corpus_id"]
    except Exception:
        product_test_corpus_id = "0" * 64

    # Toolchain Environment ID
    toolchain_str = "os:linux;compiler:kotlin-1.9.22;gradle:8.5;java:17"
    toolchain_environment_id = hashlib.sha256(toolchain_str.encode("utf-8")).hexdigest()

    # CERTIFICATION_BOUNDARY_ID calculation
    boundary_input = (
        product_manifest_id +
        product_test_corpus_id +
        cert_manifest_id +
        g8_proof_map_id +
        toolchain_environment_id
    )
    certification_boundary_id = hashlib.sha256(boundary_input.encode("utf-8")).hexdigest()

    return {
        "product_artifact_id": product_manifest_id,
        "product_build_input_manifest_id": product_build_input_id,
        "certification_artifact_id": cert_manifest_id,
        "product_test_corpus_id": product_test_corpus_id,
        "g8_proof_map_id": g8_proof_map_id,
        "toolchain_environment_id": toolchain_environment_id,
        "certification_boundary_id": certification_boundary_id,
        "product_manifest": product_files,
        "certification_manifest": cert_files,
        "shared_manifest": shared_files
    }


if __name__ == "__main__":
    res = build_manifests()
    print("=== G8 Source Manifest Builder ===")
    print(f"PRODUCT_ARTIFACT_ID             : {res['product_artifact_id']}")
    print(f"PRODUCT_BUILD_INPUT_MANIFEST_ID : {res['product_build_input_manifest_id']}")
    print(f"CERTIFICATION_ARTIFACT_ID       : {res['certification_artifact_id']}")
    print(f"Product entries                 : {len(res['product_manifest'])}")
    print(f"Certification entries           : {len(res['certification_manifest'])}")
