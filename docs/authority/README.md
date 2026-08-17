# Implementation Authority Bundle

This directory contains the frozen Implementation Reference Bundle for the Earthlink Reseller application.

## Authority Hierarchy

1. **Product / Business Authority**: `Target Product Contract v0.6.md`
2. **Engineering Interpretation**: `G1-G8 Consolidated Architecture Summary.md`
3. **Final Architectural Judgment / Implementation Boundary**: `Final Independent Adjudication Memo.md`
4. **Transition Guidance (Subordinate)**:
   - `EARTHLINK_V1_HANDOVER.md`
   - `EARTHLINK_IMPLEMENTATION_HANDOVER_APPENDIX_v1.0.md`

The first three files are the **Frozen Authority Set**. The last two are the **Transition Guidance Set**.
The transition guidance documents are strictly subordinate to the three frozen documents whenever terminology or priority appears to differ.

## Current Implementation & Verification Evidence

- **Implementation State Evidence**: Exact current source/artifact.
- **Verification Evidence**: Executable tests / instrumentation / build / release artifacts.

## AUTHORITY_BUNDLE_IMMUTABLE

Frozen authority files are immutable during normal implementation work.
Any change requires an explicit authority-change review, a new externally supplied artifact identity, updated hashes, and a documented product/architecture decision.
