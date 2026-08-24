#!/usr/bin/env python3
"""
scripts/g8_render_certification_report.py

Renders human-readable G8 Certification Report strictly from sealed closure bundle machine evidence.
"""

import json
import os
import sys

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))


def render_report(bundle_path: str) -> str:
    if not os.path.exists(bundle_path):
        raise FileNotFoundError(f"Bundle file not found: {bundle_path}")

    with open(bundle_path, "r", encoding="utf-8") as f:
        bundle = json.load(f)

    report_lines = [
        "# Earthlink Reseller V1 — G8 Machine Certification Report",
        "",
        f"- **Certification Run ID**: `{bundle.get('certification_run_id')}`",
        f"- **Timestamp**: `{bundle.get('created_at')}`",
        f"- **Product Artifact ID**: `{bundle.get('product_artifact_id')}`",
        f"- **Certification Artifact ID**: `{bundle.get('certification_artifact_id')}`",
        f"- **Product Test Corpus ID**: `{bundle.get('product_test_corpus_id')}`",
        f"- **Certification Test Corpus ID**: `{bundle.get('certification_test_corpus_id')}`",
        f"- **Closure Status**: **{bundle.get('closure_status')}**",
        "",
        "## Derived States",
        ""
    ]

    states = bundle.get("derived_states", {})
    for k, v in states.items():
        report_lines.append(f"- **{k}**: `{v}`")

    report_lines.extend([
        "",
        "## Requirement Evidence Results",
        ""
    ])

    reqs = bundle.get("requirements_results", {})
    for req_id, rdata in reqs.items():
        report_lines.append(f"### {req_id}: {rdata.get('status')}")
        report_lines.append(f"- Evidence Ref: `{rdata.get('evidence_ref')}`")
        report_lines.append(f"- Details: {rdata.get('details')}")
        report_lines.append("")

    return "\n".join(report_lines)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python scripts/g8_render_certification_report.py <path_to_bundle.json>")
        sys.exit(1)

    print(render_report(sys.argv[1]))
