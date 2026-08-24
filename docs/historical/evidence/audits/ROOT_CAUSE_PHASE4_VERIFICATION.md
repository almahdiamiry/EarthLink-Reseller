# Root-Cause Phase 4 Verification Evidence

## Executive Summary
- **Phase:** Root-Cause Phase 4 — Forbidden Registry Hardening
- **Invariant:** `INV-06` (Authoritative Remote Version Semantics)
- **Rule ID:** `RC-1-v2-inline-version-resolution`
- **Result:** **PASS (0 Violations)**
- **Verification Date:** 2026-08-15

---

## 1. Problem Statement & Root Cause
In Phase 1 and Phase 2, `resolveLocalVersion()` was established as the single authoritative function for resolving local version states. However, the static forbidden pattern scanner initially lacked function-boundary scoping, which created a risk that inline local timestamp fallbacks (e.g. `existing?.updatedAt`, `existing?.takeIf { ... }?.occurredAt`, `storedMetaTs ?:`) could be reintroduced outside `resolveLocalVersion()` using alternate syntax variations without being flagged.

## 2. Hardened Architecture & Rule Enforcement

### A. Registry Rule Definition (`contract/forbidden_patterns.yaml`)
```yaml
  - id: "RC-1-v2-inline-version-resolution"
    invariant: "INV-06"
    description: "Forbidden inline local timestamp fallback or resolution outside resolveLocalVersion()"
    check_type: "regex"
    file_glob: "app/src/main/java/com/example/core/sync/RemoteSyncCoordinator.kt"
    allowed_in_functions:
      - "resolveLocalVersion"
    forbidden_regexes:
      - "(existing|duplicate)\\?\\.(updatedAt|createdAt|occurredAt)"
      - "(existing|duplicate)\\?\\.takeIf\\s*\\{[^}]*\\}\\?\\.(updatedAt|createdAt|occurredAt)"
      - "storedMetaTs\\s*\\?\\:"
    explanation: "INV-06 mandates that all local version resolution must happen solely inside resolveLocalVersion(). Inline resolution or fallback outside this function is forbidden."
```

### B. Function-Boundary Scanner Engine (`scripts/scan_forbidden_patterns.py`)
- The scanner extracts exact Kotlin function AST/syntax declaration spans via `extract_kotlin_function_spans(lines: list[str])`.
- Pattern matches occurring inside functions listed in `allowed_in_functions` are permitted.
- Any pattern match outside `allowed_in_functions` (such as in `applyAccountUpsert`, `applyAccountDelete`, `applyLedgerUpsert`, `applyLedgerDelete`, `applyBatchUpsert`, or `applyUserSettingsUpdate`) is immediately flagged as a blocking violation.

---

## 3. Adversarial Test Evidence (`scripts/test_forbidden_pattern_registry.py`)
The adversarial self-test suite executes 5 distinct syntax variants to verify fail-closed detection:
1. **Case A (Allowed):** Clean `resolveLocalVersion()` containing sequence fallback logic -> **PASS**
2. **Case B (Direct Fallback):** `existing?.updatedAt` in `applyAccountUpsert` -> **FAIL (Detected)**
3. **Case C (TakeIf Fallback):** `existing?.takeIf { it.isLegacy }?.occurredAt` in `applyLedgerUpsert` -> **FAIL (Detected)**
4. **Case D (Duplicate Fallback):** `duplicate?.createdAt` in `applyBatchUpsert` -> **FAIL (Detected)**
5. **Case E (Stored Metadata Fallback):** `storedMetaTs ?: 0L` in `applyAccountUpsert` -> **FAIL (Detected)**

---

## 4. Machine Execution Log
```
$ python3 scripts/scan_forbidden_patterns.py
[PASS] RC-1-v2-inline-version-resolution (INV-06) - Forbidden inline local timestamp fallback or resolution outside resolveLocalVersion()
Summary: Scanned 14 registered patterns across repository. Found 0 violation(s).
FORBIDDEN PATTERN SCAN: PASSED (0 Violations)
```
