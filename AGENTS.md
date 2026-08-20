# AGENTS.md

## Purpose

This file is the single active operational entry point for any AI coding agent working on this project.

---

## ACTIVE IMPLEMENTATION ENTRY POINT

1. **Read AGENTS.md first.**
2. **Read only the explicitly named current-phase plan for task execution.**
3. **Use `docs/authority/Target Product Contract v0.6.md` as product/business authority.**
4. **Use `docs/authority/G1-G8 Consolidated Architecture Summary.md` as engineering interpretation.**
5. **Use `docs/authority/Final Independent Adjudication Memo.md` as final architectural judgment / implementation boundary.**
6. **Inspect the current source/artifact for implementation state.**
7. **Use executable tests/evidence as verification proof.**
8. **For any EarthLink API work, read the canonical API specification `docs/earthlink_reseller_app_api_documentation_v0_7_0.md` and the reference POC `docs/earthlink_app_api_poc_v0_6_48.py` before reasoning about endpoints, requests, payloads, responses, authentication, unwrapping, or edge cases.**
9. **Do not select tasks from Git history, historical plans, ADRs, reports, SDD artifacts, or lessons learned.**
10. **Use historical material only when the current task explicitly requests forensic archaeology or historical rationale.**

> **CRITICAL RULE**: Only `AGENTS.md` and the explicitly named current-phase plan are active implementation instructions. Other repository documents are context, evidence, or history unless explicitly designated current by the frozen authority chain.
> `DESIGN_DECISIONS.md` is a technical ADR and historical context layer subordinate to the frozen authority bundle.
> The API specification and API POC are permanent technical reference material, not implementation plans.

---

## Implementation Authority Hierarchy

1. **Product / Business Authority**: `docs/authority/Target Product Contract v0.6.md`
2. **Engineering Interpretation**: `docs/authority/G1-G8 Consolidated Architecture Summary.md`
3. **Final Architectural Judgment / Implementation Boundary**: `docs/authority/Final Independent Adjudication Memo.md`
4. **Implementation Transition Guidance (Subordinate)**:
   - `docs/authority/EARTHLINK_V1_HANDOVER.md`
   - `docs/authority/EARTHLINK_IMPLEMENTATION_HANDOVER_APPENDIX_v1.0.md`
5. **API Protocol Reference**:
   - `docs/earthlink_reseller_app_api_documentation_v0_7_0.md`
   - `docs/earthlink_app_api_poc_v0_6_48.py`
6. **Implementation State Evidence**: Exact current source tree and build configuration.
7. **Verification Proof**: Executable automated tests, test matrix, and verification commands.

---

## Mandatory Non-Negotiable Invariants

### 1. No Deleting, Weakening, or Skipping Tests
- NEVER delete or skip tests.
- NEVER modify or weaken assertions/expected results in existing certification tests (`FinalTestMatrixCertificationTest`, `ProductionCertificationPipelineTest`, `ProductionExecutableInvariantsTest`, `DeepCrossLayerInvariantsTest`, and existing golden/regression tests).
- A failing test is proof of a production defect until the production code is proven otherwise. Never make a test pass by changing the test.

### 2. One State, One Authority
Every synchronized business state must have:
- one authoritative meaning;
- one version domain;
- one mutation policy;
- one synchronization path.

Do not introduce a second mechanism that independently writes the same state to Firebase.

### 3. Remote Version Semantics
Server version MUST be represented explicitly and compared only against the same semantic version domain.
Do not use `createdAt`, `occurredAt`, device clock, or local business timestamps as substitutes for server version.

### 4. Mutation Channel Rule
Any code capable of mutating synchronized application state or performing destructive database operations MUST pass through the canonical synchronization / mutation architecture defined in the frozen architecture bundle.

### 5. Snapshot & History Rule
Historical data, Snapshot state, and Runtime state are different concepts:
- Historical records (e.g., uTower import archives) must remain immutable.
- Snapshot state is the authoritative starting baseline.
- Runtime calculations must not re-apply historical snapshot records.

### 6. Fail-Closed Security
- **In Release Build:** missing or invalid production signing credentials => build failure. No fallback to debug signing, placeholder keys, or unsigned artifacts.
- **Android Keystore Failure:** fail closed immediately.
- **Existing Encrypted DB with Unrecoverable Key:** stop safely; never generate a replacement key over an existing database.

### 7. Certification Freeze
Existing certification tests are immutable during implementation and certification runs.

---

## Mandatory Workflow

For every development session:

1. Follow the **ACTIVE IMPLEMENTATION ENTRY POINT** above.
2. Implement ONLY the single assigned task in the current approved phase plan.
3. Do not modify unrelated files.
4. Verify the implementation with tests and verified runners.
5. Update `CHANGELOG.md` and active phase tracking.
6. Stop and wait for user approval.

---

## Permanent Verification & Compliance Rules

1. **Requirement-by-Requirement Compliance Reviews:** Every phase or task closure MUST prove every single blocking requirement in `contract/phase_requirements.yaml` through explicit machine evidence.
2. **No Phase Closure from Narrative Reports:** Narrative claims are strictly ignored. Only machine-verified compliance matrix status (`ALL BLOCKING ROWS PASS`) authorizes closure.
3. **No NO-SOURCE Pass:** Any test task or compilation step returning `NO-SOURCE` MUST fail closed with exit code 2.
4. **No Unbounded Verification Commands:** All verification commands MUST execute through `run_verified_command.py` enforcing strict timeouts, process-tree termination, and heartbeat emission.
5. **No Deleting, Skipping, or Weakening Tests:** Tests MUST NEVER be deleted, skipped, or weakened.
